package com.aistra.hail.shizuku

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class BoundedCommandRunnerTest {
    @Test fun drainsBothStreamsBeyondPipeCapacityAndKeepsFailureStatus() {
        val result = BoundedCommandRunner().execute(listOf("/bin/sh", "-c", """
            i=0
            while [ "${'$'}i" -lt 12000 ]; do
                printf 'stdout-0123456789abcdefghijklmnopqrstuv\n'
                printf 'stderr-0123456789abcdefghijklmnopqrstuv\n' >&2
                i=${'$'}((i + 1))
            done
            exit 7
        """.trimIndent()), 5_000, AtomicBoolean(false))
        assertEquals(7, result.exitCode)
        assertFalse(result.timedOut)
        assertTrue(result.truncated)
        assertTrue(result.output.contains("stdout-"))
        assertTrue(result.output.contains("stderr-"))
        assertEquals(CommandLimits.OUTPUT_BYTES, result.output.toByteArray().size)
    }

    @Test fun retainsSmallOutputAndExitStatusWithoutTruncation() {
        val result = BoundedCommandRunner().execute(
            listOf("/bin/sh", "-c", "printf 'output'; printf ' error' >&2; exit 3"), 1_000, AtomicBoolean(false))
        assertEquals(CommandResult(3, "output error"), result)
    }

    @Test fun aBlockedProcessTimesOutWithoutWaitingForOutputEof() {
        val start = System.nanoTime()
        val result = BoundedCommandRunner().execute(
            listOf("/bin/sh", "-c", "printf 'started'; exec sleep 20"), 100, AtomicBoolean(false))
        assertEquals(124, result.exitCode)
        assertTrue(result.timedOut)
        assertEquals("started", result.output)
        assertTrue("Timeout must bound waiting for a still-open pipe", (System.nanoTime() - start) / 1_000_000 < 2_000)
    }

    @Test fun cancellationStopsWaitingAndDoesNotRunAnAlreadyCancelledCommand() {
        val cancelled = AtomicBoolean(false)
        val trigger = thread(isDaemon = true) { Thread.sleep(100); cancelled.set(true) }
        val result = BoundedCommandRunner().execute(
            listOf("/bin/sh", "-c", "printf 'started'; exec sleep 20"), 5_000, cancelled)
        trigger.join()
        assertEquals(130, result.exitCode)
        assertFalse(result.timedOut)
        assertEquals("started", result.output)
        // A nonexistent executable proves no process is even started after pre-cancellation.
        assertEquals(130, BoundedCommandRunner().execute(listOf("/no/such/command"), 100, cancelled).exitCode)
    }

    @Test fun invalidOrUnboundedArgumentsAreRejectedBeforeExecution() {
        for (argv in listOf(emptyList(), listOf(""), listOf("/bin/echo", "a\u0000b"),
            listOf("/bin/echo", "x".repeat(CommandLimits.MAX_ARGUMENT_BYTES + 1)))) {
            assertThrows(IllegalArgumentException::class.java) {
                BoundedCommandRunner().execute(argv, 100, AtomicBoolean(false))
            }
        }
    }
}
