package com.aistra.hail.utils

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku

@RunWith(AndroidJUnit4::class)
class ShizukuCompatibilityTest {
    /** API 23 needs core library desugaring for Shizuku's listener removal methods. */
    @Test
    fun shizukuListenersCanBeAddedAndRemovedOnSupportedAndroidVersions() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val received = Shizuku.OnBinderReceivedListener { }
            val dead = Shizuku.OnBinderDeadListener { }
            val permission = Shizuku.OnRequestPermissionResultListener { _, _ -> }
            try {
                // This checks the actual library without requiring a service or granting permission.
                Shizuku.addBinderReceivedListener(received)
                Shizuku.addBinderDeadListener(dead)
                Shizuku.addRequestPermissionResultListener(permission)

                assertTrue(Shizuku.removeBinderReceivedListener(received))
                assertTrue(Shizuku.removeBinderDeadListener(dead))
                assertTrue(Shizuku.removeRequestPermissionResultListener(permission))
                assertFalse(Shizuku.removeBinderReceivedListener(received))
                assertFalse(Shizuku.removeBinderDeadListener(dead))
                assertFalse(Shizuku.removeRequestPermissionResultListener(permission))
            } finally {
                Shizuku.removeBinderReceivedListener(received)
                Shizuku.removeBinderDeadListener(dead)
                Shizuku.removeRequestPermissionResultListener(permission)
            }
        }
    }
}
