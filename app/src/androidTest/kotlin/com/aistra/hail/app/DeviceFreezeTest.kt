package com.aistra.hail.app

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.ContentValues
import android.provider.MediaStore
import android.os.SystemClock
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.aistra.hail.ui.api.ApiActivity
import com.aistra.hail.ui.settings.BackupViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
        val originalLibrary = withContext(Dispatchers.Main) { HailData.backupSnapshot() }
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
            val snapshot = BackupContent(packages.map { AppInfo(it) }, listOf("All" to 0), emptyMap()).captureFrozenStates()
            assertEquals(mapOf(packages.first() to false, packages.last() to true), snapshot.frozenStates)
            val thawStart = SystemClock.elapsedRealtime()
            val thaw = AppManager.changeFrozen(packages, false)
            val thawMs = SystemClock.elapsedRealtime() - thawStart
            assertEquals(setOf(packages.last()), thaw.changed)
            assertEquals(setOf(packages.first()), thaw.unchanged)
            assertTrue(packages.none(ctx.packageManager::isPackageSuspended))
            val uri = requireNotNull(ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Hail-states-${System.nanoTime()}.json")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            }))
            try {
                requireNotNull(ctx.contentResolver.openOutputStream(uri, "wt")).use(snapshot::writeTo)
                val model = BackupViewModel()
                for (restoreStates in listOf(false, true)) {
                    withContext(Dispatchers.Main) { model.dismiss(); model.open(uri, true) }
                    val preview = withTimeout(10_000) { model.state.first { it is BackupViewModel.State.Preview } } as BackupViewModel.State.Preview
                    assertFalse(preview.applyStates)
                    withContext(Dispatchers.Main) { model.toggleStates(restoreStates); model.confirm() }
                    val restored = withTimeout(10_000) { model.state.first { it is BackupViewModel.State.Restored } } as BackupViewModel.State.Restored
                    assertEquals(if (restoreStates) 1 else null, restored.changed)
                    assertEquals(restoreStates, ctx.packageManager.isPackageSuspended(packages.last()))
                }
            } finally {
                ctx.contentResolver.delete(uri, null, null)
            }
            Log.i("HailTiming", "Two-package suspend ${freezeMs}ms, mixed-state thaw ${thawMs}ms")
        } finally {
            AppManager.changeFrozen(packages, false)
            withContext(Dispatchers.Main) {
                HailData.restoreBackup(originalLibrary, BackupContent.encodeApps(originalLibrary.apps).toString(),
                    BackupContent.encodeTags(originalLibrary.tags).toString())
            }
            prefs.edit().putString(HailData.WORKING_MODE, original).commit()
            device.pressHome()
        }
        Unit
    }
}
