package com.aistra.hail.utils

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import com.aistra.hail.BuildConfig
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.shizuku.CommandLimits
import com.aistra.hail.shizuku.CommandResult
import com.aistra.hail.shizuku.HailCommandService
import com.aistra.hail.shizuku.IHailCommandCallback
import com.aistra.hail.shizuku.IHailCommandService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Explicit, serialized commands. Connection changes invalidate work; they never replay a mutation. */
internal object ShizukuCommandClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Mutex()
    private val active = AtomicReference<Session?>()

    suspend fun execute(argv: List<String>, timeoutMs: Long = CommandLimits.EXECUTE_TIMEOUT_MS): CommandResult =
        withContext(Dispatchers.IO) {
            try {
                CommandLimits.validate(argv)
                require(timeoutMs in 1..CommandLimits.MAX_TIMEOUT_MS)
                commands.withLock {
                    checkAuthorized()
                    // Do not let an old remove=true race a new bind using the same stable service tag.
                    active.get()?.let { previous ->
                        previous.close(IOException("Previous command finished"))
                        withTimeout(CommandLimits.CONNECT_TIMEOUT_MS) {
                            try {
                                previous.stopped.await()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                if (previous.workerIsAlive ||
                                    previous.serverBinder.isBinderAlive && Shizuku.getBinder() == previous.serverBinder) {
                                    // Permission may have been revoked during teardown, then granted
                                    // again. Retry removal only; the old command is never resubmitted.
                                    previous.retryCleanup().await()
                                }
                            }
                        }
                        active.compareAndSet(previous, null)
                    }
                    val session = Session(requireNotNull(Shizuku.getBinder()), Shizuku.getUid())
                    active.set(session)
                    session.startBinding()
                    try {
                        val service = withTimeout(CommandLimits.CONNECT_TIMEOUT_MS) { session.connected.await() }
                        currentCoroutineContext().ensureActive()
                        checkAuthorized()
                        check(Shizuku.getBinder() == session.serverBinder) { "Shizuku connection changed" }
                        withTimeout(timeoutMs + 1_000) {
                            session.submit(service, argv, SystemClock.elapsedRealtime() + timeoutMs)
                            session.result.await()
                        }
                    } finally {
                        session.close(IOException("Command connection closed"))
                        // Cleanup continues in the independent IO scope if a dead server does not answer.
                        // A later command must await that same cleanup before it is allowed to bind.
                        val stopped = withContext(NonCancellable) {
                            withTimeoutOrNull(2_000) { runCatching { session.stopped.await() }.isSuccess }
                        }
                        if (stopped == true) active.compareAndSet(session, null)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                CommandResult(124, "Timed out waiting for the Shizuku command service", timedOut = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CommandResult(1, error.message ?: error.javaClass.simpleName)
            }
        }

    /** Safe from Shizuku's main-thread callbacks and while ShizukuConnection holds its own monitor. */
    fun disconnect() {
        active.get()?.close(IOException("Shizuku disconnected or permission changed"))
    }

    private fun checkAuthorized() {
        check(Shizuku.pingBinder() && !Shizuku.isPreV11()) { "Shizuku is not connected" }
        check(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { "Shizuku permission is required" }
    }

    private class Session(val serverBinder: IBinder, private val serviceUid: Int) : ServiceConnection {
        val connected = CompletableDeferred<IHailCommandService>()
        val result = CompletableDeferred<CommandResult>()
        private val cleanupState = AtomicReference(CompletableDeferred<Unit>())
        val stopped get() = cleanupState.get()
        private val bindFinished = CompletableDeferred<Unit>()
        private val closed = AtomicBoolean(false)
        private val requestId = UUID.randomUUID().toString()
        private val binder = AtomicReference<IBinder?>()
        val workerIsAlive get() = binder.get()?.isBinderAlive == true
        private val args = Shizuku.UserServiceArgs(ComponentName(app, HailCommandService::class.java))
            .daemon(false)
            .tag("hail-command-user-${HPackages.myUserId}")
            .processNameSuffix("hail_command_${HPackages.myUserId}")
            .version(BuildConfig.VERSION_CODE)
            .debuggable(BuildConfig.DEBUG)
        private val death = IBinder.DeathRecipient {
            close(IOException("Shizuku command service stopped; the command was not retried"))
        }
        private val callback = object : IHailCommandCallback.Stub() {
            override fun onComplete(id: String, exitCode: Int, output: String, timedOut: Boolean, truncated: Boolean) {
                if (Binder.getCallingUid() == serviceUid && id == requestId && !closed.get()) {
                    result.complete(CommandResult(exitCode, output.take(CommandLimits.OUTPUT_BYTES), timedOut, truncated))
                }
            }
        }

        fun startBinding() {
            scope.launch {
                try {
                    if (!closed.get()) {
                        checkAuthorized()
                        check(Shizuku.getBinder() == serverBinder) { "Shizuku connection changed" }
                        // API 13.1.5 mutates an unsynchronized callback set also iterated on Main.
                        // Only registration runs there; awaiting the connection never blocks Main.
                        withContext(Dispatchers.Main.immediate) {
                            if (!closed.get()) Shizuku.bindUserService(args, this@Session)
                        }
                    }
                } catch (error: Exception) {
                    close(error)
                } finally {
                    bindFinished.complete(Unit)
                }
            }
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (closed.get()) return
            try {
                service.linkToDeath(death, 0)
                if (!binder.compareAndSet(null, service)) {
                    service.unlinkToDeath(death, 0)
                    return
                }
                if (closed.get()) return
                connected.complete(IHailCommandService.Stub.asInterface(service))
            } catch (error: Exception) {
                close(error)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            close(IOException("Shizuku command service disconnected"))
        }

        fun submit(service: IHailCommandService, argv: List<String>, deadline: Long) {
            // Binder calls run outside the waiting coroutine, so even a blocked transaction cannot
            // hold the UI or prevent its timeout/cancellation. The worker rejects expired requests.
            scope.launch {
                try {
                    if (!closed.get()) service.execute(requestId, argv.toTypedArray(), deadline, callback)
                } catch (error: Exception) {
                    close(error)
                }
            }
        }

        fun close(reason: Throwable) {
            if (!closed.compareAndSet(false, true)) return
            connected.completeExceptionally(reason)
            result.completeExceptionally(reason)
            launchCleanup(stopped)
        }

        fun retryCleanup(): CompletableDeferred<Unit> {
            val previous = stopped
            if (previous.isCompleted) {
                val next = CompletableDeferred<Unit>()
                if (cleanupState.compareAndSet(previous, next)) launchCleanup(next)
            }
            return stopped
        }

        private fun launchCleanup(completion: CompletableDeferred<Unit>) {
            scope.launch {
                var failure: Throwable? = null
                try {
                    // bindUserService registers the callback before making its Binder call. This
                    // barrier covers exceptions during binding as well as cancellation before bind.
                    bindFinished.await()
                    // onServiceConnected runs on Main. Capture after any callback already in flight
                    // has finished; later callbacks see closed and cannot install another binder.
                    val service = withContext(Dispatchers.Main.immediate) { binder.get() }
                    runCatching { service?.let { IHailCommandService.Stub.asInterface(it).cancel(requestId) } }
                    try {
                        Shizuku.unbindUserService(args, this@Session, true)
                        awaitWorkerStopped(service)
                    } catch (error: Exception) {
                        // Revocation can reject server-side removal while our existing worker is
                        // still alive. This Binder belongs only to this command: destroy it without
                        // requesting permission, replaying the command, or touching another process.
                        if (service?.isBinderAlive == true) {
                            try {
                                IHailCommandService.Stub.asInterface(service).destroy()
                                awaitWorkerStopped(service)
                            } catch (cleanupError: Exception) {
                                error.addSuppressed(cleanupError)
                            }
                        }
                        if (service?.isBinderAlive == true ||
                            serverBinder.isBinderAlive && Shizuku.getBinder() == serverBinder) failure = error
                    } finally {
                        // API 13.1.5 remove=true does not itself clear the cached client callbacks.
                        // Invalidate this Session first, then explicitly release that registration.
                        runCatching {
                            withContext(Dispatchers.Main.immediate) {
                                Shizuku.unbindUserService(args, this@Session, false)
                            }
                        }
                            .onFailure { if (serverBinder.isBinderAlive && Shizuku.getBinder() == serverBinder) failure = it }
                        if (failure == null) {
                            runCatching { service?.unlinkToDeath(death, 0) }
                            binder.set(null)
                        }
                    }
                } catch (error: Exception) {
                    failure = error
                } finally {
                    val error = failure
                    if (error == null) completion.complete(Unit) else completion.completeExceptionally(error)
                }
            }
        }

        private suspend fun awaitWorkerStopped(service: IBinder?) {
            if (service == null || !service.isBinderAlive) return
            check(withTimeoutOrNull(2_000) {
                // An SDK disconnect is not proof that the privileged worker exited.
                while (service.isBinderAlive) delay(20)
                true
            } == true) { "Shizuku command worker did not stop" }
        }
    }
}
