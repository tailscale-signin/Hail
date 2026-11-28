package com.aistra.hail.shizuku

import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.annotation.Keep
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/** A Shizuku UserService (a Binder, not an Android Service), running as Shizuku's existing UID. */
@Keep
class HailCommandService @Keep constructor() : IHailCommandService.Stub() {
    private val closed = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "hail-command-worker").apply { isDaemon = true }
    }
    private val running = AtomicReference<Running?>()
    private val ownerUid = AtomicInteger(-1)
    private val cancelledIds = mutableSetOf<String>()
    private val processGroup = runCatching {
        val pid = Os.getpid()
        // A dedicated session contains ordinary shell children without relying on a setsid binary,
        // hidden Process.pid APIs, or a second su process. Never signal an inherited process group.
        val created = runCatching { Os.setsid() }.getOrNull() == pid
        if (!created) {
            val stat = File("/proc/self/stat").readText().substringAfterLast(") ").split(' ')
            check(stat.getOrNull(2)?.toIntOrNull() == pid && stat.getOrNull(3)?.toIntOrNull() == pid) {
                "Could not isolate the command process"
            }
        }
        pid
    }

    private class Running(val id: String, val callback: IHailCommandCallback) {
        val cancelled = AtomicBoolean(false)
        val death = IBinder.DeathRecipient { cancelled.set(true) }
    }

    override fun execute(requestId: String, argv: Array<out String>, deadlineElapsedRealtime: Long,
                         callback: IHailCommandCallback) {
        val request = Running(requestId, callback)
        try {
            requireOwner()
            check(!closed.get()) { "Command service is closed" }
            require(requestId.length in 1..64) { "Invalid command ID" }
            CommandLimits.validate(argv.toList())
            processGroup.getOrThrow()
            callback.asBinder().linkToDeath(request.death, 0)
            check(running.compareAndSet(null, request)) { "A command is already running" }
            executor.execute {
                val result = try {
                    val remaining = deadlineElapsedRealtime - SystemClock.elapsedRealtime()
                    val cancelled = synchronized(cancelledIds) { requestId in cancelledIds }
                    when {
                        closed.get() || cancelled || request.cancelled.get() -> CommandResult(130, "Command cancelled")
                        remaining <= 0 -> CommandResult(124, "Command expired before execution", timedOut = true)
                        remaining > CommandLimits.MAX_TIMEOUT_MS -> CommandResult(1, "Invalid command deadline")
                        else -> BoundedCommandRunner().execute(argv.toList(), remaining, request.cancelled)
                    }
                } catch (error: Exception) {
                    CommandResult(1, error.message ?: error.javaClass.simpleName)
                }
                try {
                    callback.onComplete(requestId, result.exitCode, result.output, result.timedOut, result.truncated)
                } catch (_: Exception) {
                    // A cancelled or dead caller cannot receive a result; never retry its command.
                } finally {
                    running.compareAndSet(request, null)
                    runCatching { callback.asBinder().unlinkToDeath(request.death, 0) }
                }
            }
        } catch (error: Exception) {
            running.compareAndSet(request, null)
            runCatching { callback.asBinder().unlinkToDeath(request.death, 0) }
            runCatching { callback.onComplete(requestId, 1, error.message ?: error.javaClass.simpleName, false, false) }
        }
    }

    override fun cancel(requestId: String) {
        requireOwner()
        synchronized(cancelledIds) {
            // This service accepts one client's serialized commands; keep even malformed traffic bounded.
            if (cancelledIds.size < 16 && requestId.length <= 64) cancelledIds.add(requestId)
        }
        running.get()?.takeIf { it.id == requestId }?.cancelled?.set(true)
    }

    override fun destroy() {
        val caller = Binder.getCallingUid()
        check(caller == 0 || caller == 2000 || caller == ownerUid.get()) { "Unexpected service owner" }
        if (!closed.compareAndSet(false, true)) return
        running.get()?.cancelled?.set(true)
        executor.shutdownNow()
        try {
            // The result has been delivered, or the caller cancelled/disconnected. Removing this
            // worker also removes ordinary descendants, including a child holding an output pipe.
            processGroup.getOrNull()?.let { Os.kill(-it, OsConstants.SIGKILL) }
        } finally {
            exitProcess(0)
        }
    }

    private fun requireOwner() {
        // Shizuku controls who receives this unexported Binder. Once handed to its app, bind
        // execution and cancellation to that same caller instead of trusting a supplied UID.
        val caller = Binder.getCallingUid()
        ownerUid.compareAndSet(-1, caller)
        check(ownerUid.get() == caller) { "Command belongs to another caller" }
    }
}
