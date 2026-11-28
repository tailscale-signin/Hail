package com.aistra.hail.shizuku

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

internal data class CommandResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false
) {
    fun asPair(): Pair<Int, String?> = exitCode to buildString {
        append(output)
        if (truncated) append("\n[Additional command output was omitted]")
        if (timedOut) append("\n[Command timed out; its result may be incomplete]")
    }.ifBlank { null }
}

internal object CommandLimits {
    const val CONNECT_TIMEOUT_MS = 8_000L
    const val EXECUTE_TIMEOUT_MS = 30_000L
    const val MAX_TIMEOUT_MS = 60_000L
    const val OUTPUT_BYTES = 65_536
    const val MAX_ARGUMENT_BYTES = 32_768

    fun validate(argv: List<String>) {
        require(argv.isNotEmpty() && argv.size <= 64) { "Invalid command arguments" }
        require(argv.first().isNotBlank()) { "Missing command executable" }
        require(argv.none { '\u0000' in it }) { "Command contains a null character" }
        require(argv.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } <= MAX_ARGUMENT_BYTES) {
            "Command is too long"
        }
    }
}

/** Consumes the merged stdout/stderr pipe while the process runs, even after the retained output is full. */
internal class BoundedCommandRunner {
    fun execute(argv: List<String>, timeoutMs: Long, cancelled: AtomicBoolean): CommandResult {
        CommandLimits.validate(argv)
        require(timeoutMs in 1..CommandLimits.MAX_TIMEOUT_MS)
        if (cancelled.get()) return CommandResult(130, "Command cancelled")
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        val retained = ByteArrayOutputStream()
        val truncated = AtomicBoolean(false)
        val reader = Thread({
            runCatching {
                process.inputStream.use { input ->
                    val buffer = ByteArray(4_096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(retained) {
                            val keep = minOf(count, CommandLimits.OUTPUT_BYTES - retained.size())
                            if (keep > 0) retained.write(buffer, 0, keep)
                            if (keep < count) truncated.set(true)
                        }
                    }
                }
            }
        }, "hail-command-output").apply { isDaemon = true; start() }
        var exit = 1
        var timedOut = false
        try {
            process.outputStream.close()
            while (true) {
                val finished = runCatching { process.exitValue() }.getOrNull()
                if (finished != null) {
                    exit = finished
                    break
                }
                if (cancelled.get() || Thread.currentThread().isInterrupted) {
                    exit = 130
                    break
                }
                if (System.nanoTime() >= deadline) {
                    exit = 124
                    timedOut = true
                    break
                }
                Thread.sleep(20)
            }
            // A child may keep the pipe open after the shell exits. Never wait indefinitely for EOF.
            reader.join(250)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            exit = 130
        } finally {
            // Some Process implementations close streams synchronously in destroy(). Keep that off
            // the deadline path. The Android service also kills its private process group on removal.
            Thread({ runCatching { process.destroy() } }, "hail-command-reap")
                .apply { isDaemon = true; start() }
        }
        return CommandResult(exit, synchronized(retained) { retained.toString("UTF-8") },
            timedOut, truncated.get() || reader.isAlive)
    }
}
