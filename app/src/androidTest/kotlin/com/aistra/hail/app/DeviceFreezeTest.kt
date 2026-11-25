package com.aistra.hail.app

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.aistra.hail.ui.api.ApiActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceFreezeTest {
    @Test fun suspendBatchRepeatThenThawAndLaunchFromShortcut() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val packages = listOf("com.aistra.hail.testfixture.one", "com.aistra.hail.testfixture.two")
        val policy = ctx.getSystemService(DevicePolicyManager::class.java)
        val ready = policy.isDeviceOwnerApp(ctx.packageName) && packages.all {
            ctx.packageManager.getLaunchIntentForPackage(it) != null
        }
        if (InstrumentationRegistry.getArguments().getString("hailCiFixtures") == "true") assertTrue(ready)
        else assumeTrue(ready)
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val original = prefs.getString(HailData.WORKING_MODE, null)
        val device = UiDevice.getInstance(instrumentation)
        try {
            prefs.edit().putString(HailData.WORKING_MODE, HailData.MODE_OWNER_SUSPEND).commit()
            val start = SystemClock.elapsedRealtime()
            val freeze = AppManager.changeFrozen(packages + packages + ctx.packageName, true)
            val freezeMs = SystemClock.elapsedRealtime() - start
            assertEquals(packages.toSet(), freeze.changed)
            assertTrue(packages.all(ctx.packageManager::isPackageSuspended))
            assertEquals(packages.toSet(), AppManager.changeFrozen(packages, true).unchanged)
            ctx.startActivity(HailApi.getIntentForPackage(HailApi.ACTION_LAUNCH, packages.first())
                .setClass(ctx, ApiActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(Until.hasObject(By.pkg(packages.first())), 15_000))
            assertFalse(ctx.packageManager.isPackageSuspended(packages.first()))
            assertTrue(ctx.packageManager.isPackageSuspended(packages.last()))
            val thawStart = SystemClock.elapsedRealtime()
            val thaw = AppManager.changeFrozen(packages, false)
            val thawMs = SystemClock.elapsedRealtime() - thawStart
            assertEquals(setOf(packages.last()), thaw.changed)
            assertEquals(setOf(packages.first()), thaw.unchanged)
            assertTrue(packages.none(ctx.packageManager::isPackageSuspended))
            Log.i("HailTiming", "Two-package suspend ${freezeMs}ms, mixed-state thaw ${thawMs}ms")
        } finally {
            AppManager.changeFrozen(packages, false)
            prefs.edit().putString(HailData.WORKING_MODE, original).commit()
            device.pressHome()
        }
        Unit
    }
}
