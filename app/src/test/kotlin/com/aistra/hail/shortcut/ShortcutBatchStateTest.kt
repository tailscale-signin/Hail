package com.aistra.hail.shortcut

import org.junit.Assert.*
import org.junit.Test

class ShortcutBatchStateTest {
    private fun batch(packages: List<String> = listOf("app.one", "app.two")) =
        BatchState.create("batch", packages, "launcher", automatic = true)

    private fun requested(state: BatchState, id: String = "request") = state.copy(awaiting = PendingShortcut(
        "batch", id, state.current!!, state.current!!, "launcher", setOf("launcher.PinActivity"), 123))

    @Test fun callbackReadbackAndDismissalAreAllRequiredBeforeAdvancing() {
        val sent = requested(batch())
        assertEquals(sent, sent.confirmPinned(setOf("app.one")))
        val callback = sent.recordApproval("batch", "request", "app.one")
        assertEquals(callback, callback.confirmPinned(setOf("app.one")))
        val closed = callback.copy(awaiting = callback.awaiting!!.copy(confirmationDismissed = true))
        assertEquals(closed, closed.confirmPinned(emptySet()))
        val next = closed.confirmPinned(setOf("app.one"))
        assertEquals("app.two", next.current)
        assertEquals(listOf("app.one"), next.added)
        assertNull(next.awaiting)
        assertEquals(next, next.recordApproval("batch", "request", "app.one"))
    }

    @Test fun staleWrongPackageAndCancelledCallbacksNeverAdvanceTheQueue() {
        val sent = requested(batch())
        assertEquals(sent, sent.recordApproval("older-batch", "request", "app.one"))
        assertEquals(sent, sent.recordApproval("batch", "older-request", "app.one"))
        assertEquals(sent, sent.recordApproval("batch", "request", "app.two"))
        val cancelled = sent.copy(status = BatchStatus.CANCELLED, awaiting = null)
        assertEquals(cancelled, cancelled.recordApproval("batch", "request", "app.one"))
        assertEquals(cancelled, cancelled.confirmPinned(setOf("app.one")))
        val skipped = requested(sent.advance(BatchState.Outcome.SKIPPED), "second")
        assertEquals(skipped, skipped.recordApproval("batch", "request", "app.one"))
    }

    @Test fun manualApprovalDoesNotRequireTheLauncherToReturnToHailsActivity() {
        val sent = requested(batch().copy(automatic = false))
        val callback = sent.recordApproval("batch", "request", "app.one")
        assertEquals(callback, callback.confirmPinned(emptySet()))
        val next = callback.confirmPinned(setOf("app.one"))
        assertEquals("app.two", next.current)
        assertEquals(listOf("app.one"), next.added)
    }

    @Test fun restoringAnActiveRequestRequiresExplicitResumeEvenIfApprovalArrives() {
        val recovered = requested(batch()).recover("Interrupted")
        assertFalse(recovered.active)
        assertTrue(recovered.canResume)
        assertEquals(BatchStatus.PAUSED, recovered.status)
        assertEquals("request", recovered.awaiting?.requestId)
        val confirmed = recovered.recordApproval("batch", "request", "app.one")
            .let { it.copy(awaiting = it.awaiting!!.copy(confirmationDismissed = true)) }
        assertEquals(confirmed, confirmed.confirmPinned(setOf("app.one")))
        assertFalse(confirmed.active)
    }

    @Test fun threeHundredPackagesAdvanceOnceEachAndPreserveExistingAndSkippedCounts() {
        val packages = (1..300).map { "app.number$it" }
        var state = batch(packages + packages.take(10) + listOf("", " "))
        assertEquals(300, state.total)
        val pinned = mutableSetOf<String>()
        for (index in 1..300) {
            val pkg = state.current!!
            state = when {
                index % 10 == 0 -> state.advance(BatchState.Outcome.EXISTING)
                index % 15 == 0 -> state.advance(BatchState.Outcome.SKIPPED)
                else -> {
                    val id = "request-$index"
                    val waiting = requested(state, id).recordApproval("batch", id, pkg)
                    assertEquals(index - 1, waiting.position)
                    pinned.add(pkg)
                    val closed = waiting.copy(awaiting = waiting.awaiting!!.copy(confirmationDismissed = true))
                    val next = closed.confirmPinned(pinned)
                    assertEquals(next, next.recordApproval("batch", id, pkg))
                    next
                }
            }
            assertEquals(index, state.position)
            assertEquals(index, state.added.size + state.existing.size + state.skipped.size)
        }
        assertEquals(BatchStatus.COMPLETED, state.status)
        assertFalse(state.active)
        assertNull(state.current)
        assertEquals(30, state.existing.size)
        assertEquals(10, state.skipped.size)
        assertEquals(260, state.added.size)
        assertEquals(state, state.advance(BatchState.Outcome.SKIPPED))
    }

    @Test fun emptyQueuesNeedNoForegroundWork() {
        val state = batch(emptyList())
        assertEquals(BatchStatus.COMPLETED, state.status)
        assertFalse(state.active)
        assertNull(state.current)
    }
}
