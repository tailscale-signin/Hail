package com.aistra.hail.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import com.aistra.hail.utils.HPackages
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
    @Test fun freezeRepeatThenThawLaunchAndRestoreFromBackup() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val packages = listOf("com.aistra.hail.testfixture.one", "com.aistra.hail.testfixture.two")
        val ready = packages.all {
            ctx.packageManager.getLaunchIntentForPackage(it) != null
        }
        if (InstrumentationRegistry.getArguments().getString("hailCiFixtures") == "true") assertTrue(ready)
        else assumeTrue(ready)
        fun disabled(pkg: String) = ctx.packageManager.getApplicationEnabledSetting(pkg) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val original = prefs.getString(HailData.WORKING_MODE, null)
        val device = UiDevice.getInstance(instrumentation)
        val originalLibrary = withContext(Dispatchers.Main) { HailData.backupSnapshot() }
        val automation = instrumentation.uiAutomation
        automation.adoptShellPermissionIdentity(Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE)
        try {
            prefs.edit().putString(HailData.WORKING_MODE, HailData.MODE_PRIVAPP_DISABLE).commit()
            val start = SystemClock.elapsedRealtime()
            val freeze = AppManager.changeFrozen(packages + packages + ctx.packageName, true)
            val freezeMs = SystemClock.elapsedRealtime() - start
            assertEquals(packages.toSet(), freeze.changed)
            assertTrue(packages.all(::disabled))
            assertEquals(packages.toSet(), AppManager.changeFrozen(packages, true).unchanged)
            ctx.startActivity(HailApi.getIntentForPackage(HailApi.ACTION_LAUNCH, packages.first())
                .setClass(ctx, ApiActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(Until.hasObject(By.pkg(packages.first())), 15_000))
            assertFalse(disabled(packages.first()))
            assertTrue(disabled(packages.last()))
            val snapshot = BackupContent(packages.map { AppInfo(it) }, listOf("All" to 0), emptyMap()).captureFrozenStates()
            assertEquals(mapOf(packages.first() to false, packages.last() to true), snapshot.frozenStates)
            val thawStart = SystemClock.elapsedRealtime()
            val thaw = AppManager.changeFrozen(packages, false)
            val thawMs = SystemClock.elapsedRealtime() - thawStart
            assertEquals(setOf(packages.last()), thaw.changed)
            assertEquals(setOf(packages.first()), thaw.unchanged)
            assertTrue(packages.none(::disabled))
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
                    assertEquals(restoreStates, disabled(packages.last()))
                }
            } finally {
                ctx.contentResolver.delete(uri, null, null)
            }
            Log.i("HailTiming", "Two-package disable ${freezeMs}ms, mixed-state thaw ${thawMs}ms")
        } finally {
            AppManager.changeFrozen(packages, false)
            withContext(Dispatchers.Main) {
                HailData.restoreBackup(originalLibrary, BackupContent.encodeApps(originalLibrary.apps).toString(),
                    BackupContent.encodeTags(originalLibrary.tags).toString())
            }
            prefs.edit().putString(HailData.WORKING_MODE, original).commit()
            automation.dropShellPermissionIdentity()
            device.pressHome()
        }
        Unit
    }
    @Test fun readsAndroidSuspensionAndReportsDeniedOwnerMutation() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hailCiFixtures") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val prefs = PreferenceManager.getDefaultSharedPreferences(instrumentation.targetContext)
        val original = prefs.getString(HailData.WORKING_MODE, null)
        val pkg = "com.aistra.hail.testfixture.one"
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .bufferedReader().use { it.readText() }
        }
        try {
            prefs.edit().putString(HailData.WORKING_MODE, HailData.MODE_OWNER_SUSPEND).commit()
            shell("pm suspend $pkg")
            assertTrue(AppManager.isAppFrozen(requireNotNull(HPackages.getApplicationInfoOrNull(pkg))))
            val denied = AppManager.changeFrozen(listOf(pkg), false)
            assertEquals(setOf(pkg), denied.failed)
            assertTrue(instrumentation.targetContext.packageManager.isPackageSuspended(pkg))
        } finally {
            shell("pm unsuspend $pkg")
            prefs.edit().putString(HailData.WORKING_MODE, original).commit()
        }
        Unit
    }

}
