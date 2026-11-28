package com.aistra.hail.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WidgetIdentityTest {
    @Test fun independentOfListOrderAndOldStringHashCollisions() {
        val packages = (1..300).map { "example.launcher.app$it" } + listOf("example.Aa", "example.BB")
        val original = packages.associateWith(WidgetIdentity::itemId)
        assertEquals(packages.size, original.values.toSet().size)
        assertEquals(original, packages.reversed().associateWith(WidgetIdentity::itemId))
        assertEquals("example.Aa".hashCode(), "example.BB".hashCode())
        assertNotEquals(original["example.Aa"], original["example.BB"])
    }
}
