package com.aistra.hail.app

import org.junit.Assert.*
import org.junit.Test

class FreezeBatchTest {
    @Test fun aLargeTagUsesOneLookupPerPackageAndThreePolicyCalls() {
        val packages = (1..130).map { "example.app$it" }
        val reads = mutableListOf<String>()
        val sizes = mutableListOf<Int>()
        val result = FreezeBatch.run(packages + packages + "hail", "hail", true, {
            reads.add(it)
            AppInfo.State.UNFROZEN
        }, { sizes.add(it.size); it.toSet() })
        assertEquals(packages, reads)
        assertEquals(listOf(64, 64, 2), sizes)
        assertEquals(packages.toSet(), result.changed)
    }

    @Test fun noOpMissingSelfAndPartialFailureAreAccountedForSeparately() {
        val states = mapOf("ok" to AppInfo.State.FROZEN, "denied" to AppInfo.State.FROZEN,
            "ready" to AppInfo.State.UNFROZEN, "gone" to AppInfo.State.NOT_FOUND)
        val outcome = FreezeBatch.run(states.keys + "hail", "hail", false, states::getValue) {
            assertEquals(listOf("ok", "denied"), it)
            setOf("ok")
        }
        assertEquals(setOf("ok"), outcome.changed)
        assertEquals(setOf("ready"), outcome.unchanged)
        assertEquals(setOf("gone"), outcome.missing)
        assertEquals(setOf("denied"), outcome.failed)
        assertTrue(outcome.reachedTarget("ok"))
        assertTrue(outcome.reachedTarget("ready"))
        assertFalse(outcome.reachedTarget("denied"))
    }

    @Test fun alreadyFrozenAndEmptySelectionsNeverTouchTheBackend() {
        for (names in listOf(emptyList(), listOf("app", "app", "hail"))) {
            val result = FreezeBatch.run(names, "hail", true, { AppInfo.State.FROZEN }) {
                error("A no-op must not reach the backend")
            }
            assertTrue(result.changed.isEmpty())
            assertTrue(result.failed.isEmpty())
        }
    }
}
