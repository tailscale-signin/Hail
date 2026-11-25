package com.aistra.hail.ui.settings

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class ShortcutBatchTest {
    @Test fun allTagsShareOneShortcutPerPackage() {
        val batch = ShortcutBatch.from(listOf("a", "b", "a", "", " ", "c", "b"))
        assertEquals(listOf("a", "b", "c"), batch.packages)
    }

    @Test fun existingPinsAndApprovalsHaveSeparateCounts() {
        val pinned = setOf("a", "c", "unmanaged")
        val first = ShortcutBatch.from(listOf("a", "b", "c")).reconcile(pinned)
        assertEquals("b", first.current)
        val sent = first.copy(requestId = "for-b")
        assertEquals(sent, sent.reconcile(pinned))
        val done = sent.confirm("for-b").reconcile(pinned)
        assertNull(done.current)
        assertEquals(1, done.added)
        assertEquals(2, done.existing)
        assertEquals(0, done.skipped)
        assertEquals(done, done.confirm("for-b"))
    }

    @Test fun lateResponsesAfterSkipOrRetryCannotAdvanceAnotherRequest() {
        val sent = ShortcutBatch.from(listOf("a", "b")).copy(requestId = "first")
        val retry = sent.retry().copy(requestId = "retry")
        assertEquals(retry, retry.confirm("first"))
        val next = retry.skip().copy(requestId = "second")
        assertEquals(next, next.confirm("retry"))
        assertEquals(next, next.confirm(null))
        val done = next.confirm("second")
        assertNull(done.current)
        assertEquals(1, done.added)
        assertEquals(1, done.skipped)
    }

    @Test fun restoredRequestReconcilesAMissedApprovalExactlyOnce() {
        val original = ShortcutBatch.from(listOf("a", "b", "c")).copy(requestId = "pending")
        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { out -> out.writeObject(original) } }
        val restored = ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { it.readObject() as ShortcutBatch }
        assertEquals(original, restored.reconcile(emptySet()))
        val reconciled = restored.reconcile(setOf("a", "b"))
        assertEquals("c", reconciled.current)
        assertEquals(1, reconciled.added)
        assertEquals(1, reconciled.existing)
        assertEquals(reconciled, reconciled.confirm("pending"))
    }

    @Test fun emptyAndFullyPinnedListsNeedNoRequest() {
        assertNull(ShortcutBatch.from(emptyList()).current)
        val done = ShortcutBatch.from(listOf("a", "b")).reconcile(setOf("a", "b"))
        assertNull(done.current)
        assertEquals(2, done.existing)
        assertEquals(0, done.added)
    }
}
