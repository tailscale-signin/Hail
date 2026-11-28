package com.aistra.hail.utils

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aistra.hail.shizuku.CommandLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ShizukuCommandServiceTest {
    @Test fun executesDrainsAndRebindsWithoutEscalation() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hailCiFixtures") == "true")
        // The preceding system-app scenarios grant permission through Shizuku's normal consent UI.
        // This test neither grants permission itself nor starts another privileged backend.
        assertTrue("The shell-backed CI Shizuku service must be authorized", HShizuku.isAuthorized())
        assertEquals(2000, Shizuku.getUid())
        fun shell(command: String) = listOf("/system/bin/sh", "-c", command)
        fun fixtureShell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText().trim() }

        val identity = ShizukuCommandClient.execute(shell("id -u"))
        assertEquals(identity.output, 0, identity.exitCode)
        assertEquals("2000", identity.output.trim())

        val large = ShizukuCommandClient.execute(shell("""
            i=0
            while [ "${'$'}i" -lt 12000 ]; do
                printf 'stdout-0123456789abcdefghijklmnopqrstuv\n'
                printf 'stderr-0123456789abcdefghijklmnopqrstuv\n' >&2
                i=${'$'}((i + 1))
            done
            exit 7
        """.trimIndent()))
        assertEquals(large.output.takeLast(500), 7, large.exitCode)
        assertTrue(large.truncated)
        assertFalse(large.timedOut)
        assertTrue(large.output.contains("stdout-") && large.output.contains("stderr-"))
        assertTrue(large.output.toByteArray().size <= CommandLimits.OUTPUT_BYTES)

        val start = SystemClock.elapsedRealtime()
        val timeout = ShizukuCommandClient.execute(shell("printf 'started'; sleep 20; printf 'unexpected'"), 500)
        assertTrue(timeout.output, timeout.timedOut)
        assertEquals(124, timeout.exitCode)
        assertFalse(timeout.output.contains("unexpected"))
        assertTrue("Worker timeout and teardown must be bounded", SystemClock.elapsedRealtime() - start < 12_000)

        val marker = "/data/local/tmp/hail-command-${UUID.randomUUID()}"
        try {
            val pending = async(Dispatchers.Default) {
                ShizukuCommandClient.execute(shell("sleep 20 & child=${'$'}!; printf '%s %s' ${'$'}${'$'} ${'$'}child > '$marker'; wait ${'$'}child"))
            }
            val childPids = try {
                withTimeout(10_000) {
                    var pids = ""
                    while (!pids.matches(Regex("[0-9]+ [0-9]+"))) {
                        pids = fixtureShell("cat '$marker' 2>/dev/null")
                        if (!pids.matches(Regex("[0-9]+ [0-9]+"))) delay(50)
                    }
                    pids
                }
            } finally {
                withTimeout(5_000) { pending.cancelAndJoin() }
            }
            withTimeout(3_000) {
                for (pid in childPids.split(' ')) {
                    while (fixtureShell("if kill -0 $pid 2>/dev/null; then printf alive; else printf gone; fi") != "gone") delay(20)
                }
            }
            fixtureShell("rm -f '$marker'")

            // Kill only this disposable worker, never Shizuku. A marker proves the client does
            // not replay the submitted command after binder death, even though its result is lost.
            val death = ShizukuCommandClient.execute(shell("printf x >> '$marker'; kill -9 ${'$'}PPID; exit 19"))
            assertNotEquals(death.output, 0, death.exitCode)
            assertEquals("x", fixtureShell("cat '$marker'"))
            val rebound = ShizukuCommandClient.execute(shell("id -u; printf 'rebound'"))
            assertEquals(rebound.output, 0, rebound.exitCode)
            assertEquals("2000\nrebound", rebound.output.trim())
        } finally {
            fixtureShell("rm -f '$marker'")
            ShizukuCommandClient.disconnect()
        }
    }
}
