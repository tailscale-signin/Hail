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

    @Test fun lostCallbackAt115DoesNotStop284ConfirmedPins() {
        val packages = (1..284).map { "app.number$it" }
        var state = batch(packages)
        val pinned = mutableSetOf<String>()
        for (index in 1..284) {
            val pkg = state.current!!
            val id = "request-$index"
            var waiting = requested(state, id)
            if (index != 115) waiting = waiting.recordApproval("batch", id, pkg)
            // Clicking is not completion: neither a click nor an early callback advances while
            // the launcher's confirmation remains open, even if readback already contains the ID.
            waiting = waiting.copy(awaiting = waiting.awaiting!!.copy(
                confirmationObserved = true, automationClicked = true))
            pinned.add(pkg)
            assertEquals(waiting, waiting.confirmPinned(pinned))
            waiting = waiting.copy(awaiting = waiting.awaiting!!.copy(confirmationDismissed = true))
            assertTrue(waiting.canCheckPendingPin)
            assertEquals(waiting, waiting.confirmPinned(pinned - pkg)) // delayed pin visibility
            state = waiting.confirmPinned(pinned)
            assertEquals(index, state.completed)
            assertEquals(state, state.recordApproval("batch", id, pkg)) // delayed duplicate callback
        }
        assertEquals(BatchStatus.COMPLETED, state.status)
        assertEquals(packages, state.added)
        assertEquals(284, state.added.distinct().size)
        assertTrue(state.skipped.isEmpty())
    }

    @Test fun dismissalWithoutAnApprovalOrOurOwnClickCannotConfirmAnAutomaticPin() {
        val waiting = requested(batch()).let {
            it.copy(awaiting = it.awaiting!!.copy(confirmationObserved = true, confirmationDismissed = true))
        }
        assertFalse(waiting.canCheckPendingPin)
        assertEquals(waiting, waiting.confirmPinned(setOf("app.one")))
    }

    @Test fun manualApprovalReadbackCanRecoverALostCallbackAfterReturningToHail() {
        val waiting = requested(batch().copy(automatic = false)).let {
            it.copy(awaiting = it.awaiting!!.copy(confirmationDismissed = true))
        }
        assertEquals(waiting, waiting.confirmPinned(emptySet()))
        val next = waiting.confirmPinned(setOf("app.one"))
        assertEquals(listOf("app.one"), next.added)
        assertEquals("app.two", next.current)
    }

    @Test fun resumeReconcilesCompletedItem115WithoutReissuingOrDuplicatingIt() {
        val packages = (1..284).map { "app.number$it" }
        var state = batch(packages)
        repeat(114) { state = state.advance(BatchState.Outcome.ADDED) }
        val paused = requested(state, "item115").recover("Interrupted")
        val pinned = packages.take(115).toSet()
        val resumed = paused.resumeWithPins("batch", "item115", pinned, skip = false, manual = false, now = 900)
        assertEquals(115, resumed.completed)
        assertEquals(packages.take(115), resumed.added)
        assertEquals(packages[115], resumed.current)
        assertNull(resumed.awaiting)
        assertTrue(resumed.active)
    }

    @Test fun resumeAcceptsLateCallbackDuringReadbackAndPreservesApprovalWhenReadbackLags() {
        val paused = requested(batch()).paused("Waiting")
        val lateApproval = paused.recordApproval("batch", "request", "app.one")
        assertNotEquals(paused, lateApproval)
        val resumed = lateApproval.resumeWithPins(paused.batchId, paused.awaiting?.requestId,
            emptySet(), skip = false, manual = false, now = 999)
        assertTrue(resumed.active)
        assertTrue(resumed.awaiting!!.approvalReceived)
        assertEquals("request", resumed.awaiting!!.requestId)
        assertEquals(999L, resumed.awaiting!!.requestedAtElapsed)
        assertEquals(0, resumed.completed)
        val reconciled = lateApproval.resumeWithPins(paused.batchId, paused.awaiting?.requestId,
            setOf("app.one"), skip = false, manual = false, now = 999)
        assertTrue(reconciled.active)
        assertEquals(1, reconciled.completed)
        assertNull(reconciled.awaiting)
    }

    @Test fun resumeDoesNotResurrectACancelledOrReplacedRequest() {
        val paused = requested(batch()).paused("Waiting")
        val cancelled = paused.copy(status = BatchStatus.CANCELLED, awaiting = null)
        assertEquals(cancelled, cancelled.resumeWithPins("batch", "request", setOf("app.one"),
            skip = false, manual = false, now = 999))
        val replaced = requested(paused, "replacement")
        assertEquals(replaced, replaced.resumeWithPins("batch", "request", setOf("app.one"),
            skip = false, manual = false, now = 999))
    }

    @Test fun explicitRetryCanReplaceAStaleApprovalButPreservesOneArrivingDuringTheRead() {
        val approved = requested(batch()).recordApproval("batch", "request", "app.one").paused("Waiting")
        val retry = approved.resumeWithPins("batch", "request", emptySet(), skip = false, manual = false,
            now = 999, retry = true, acceptedBeforeRead = true)
        assertTrue(retry.active)
        assertNull(retry.awaiting)
        assertEquals("app.one", retry.current)
        val late = approved.resumeWithPins("batch", "request", emptySet(), skip = false, manual = false,
            now = 999, retry = true, acceptedBeforeRead = false)
        assertEquals("request", late.awaiting!!.requestId)
        assertTrue(late.awaiting!!.approvalReceived)
        assertEquals(0, late.completed)
    }

    @Test fun resumePreservesSuccessfulClickWhenCallbackIsMissingAndPinReadbackLags() {
        val clicked = requested(batch()).let {
            it.copy(awaiting = it.awaiting!!.copy(confirmationObserved = true, automationClicked = true))
        }.paused("Interrupted")
        val resumed = clicked.resumeWithPins("batch", "request", emptySet(), skip = false,
            manual = false, now = 999, acceptedBeforeRead = true)
        assertTrue(resumed.active)
        assertEquals("request", resumed.awaiting!!.requestId)
        assertFalse(resumed.awaiting!!.approvalReceived)
        assertTrue(resumed.awaiting!!.automationClicked)
        assertEquals(999L, resumed.awaiting!!.requestedAtElapsed)
        assertEquals(0, resumed.completed)
        // Preserve the one outstanding request, wait for dismissal, then reconcile the pin
        // without asking the launcher to place a second icon for the same package.
        assertEquals(resumed, resumed.confirmPinned(setOf("app.one")))
        val dismissed = resumed.copy(awaiting = resumed.awaiting!!.copy(confirmationDismissed = true))
        assertEquals(dismissed, dismissed.confirmPinned(emptySet()))
        val completed = dismissed.confirmPinned(setOf("app.one"))
        assertEquals(listOf("app.one"), completed.added)
        assertEquals("app.two", completed.current)
        assertNull(completed.awaiting)
    }

    @Test fun explicitRetryMayReplaceAnUnresolvedSuccessfulClickAfterCheckingPins() {
        val clicked = requested(batch()).let {
            it.copy(awaiting = it.awaiting!!.copy(confirmationObserved = true, automationClicked = true))
        }.paused("Waiting")
        val retry = clicked.resumeWithPins("batch", "request", emptySet(), skip = false,
            manual = false, now = 999, retry = true, acceptedBeforeRead = true)
        assertTrue(retry.active)
        assertNull(retry.awaiting)
        assertEquals("app.one", retry.current)
        val pinned = clicked.resumeWithPins("batch", "request", setOf("app.one"), skip = false,
            manual = false, now = 999, retry = true, acceptedBeforeRead = true)
        assertEquals(listOf("app.one"), pinned.added)
        assertEquals("app.two", pinned.current)
        assertNull(pinned.awaiting)
    }

    @Test fun merelyObservingAConfirmationDoesNotPreventResumeFromRequestingItAgain() {
        val observed = requested(batch()).let {
            it.copy(awaiting = it.awaiting!!.copy(confirmationObserved = true))
        }.paused("Waiting")
        val resumed = observed.resumeWithPins("batch", "request", emptySet(), skip = false,
            manual = false, now = 999)
        assertTrue(resumed.active)
        assertNull(resumed.awaiting)
        assertEquals("app.one", resumed.current)
        assertTrue(resumed.added.isEmpty())
    }
}
