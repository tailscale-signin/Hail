package com.aistra.hail.ui.settings

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.aistra.hail.R
import com.aistra.hail.app.*
import com.aistra.hail.ui.main.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class BackupSettingsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private fun button(id: Int) = requireNotNull(device.wait(Until.findObject(By.text(ctx.getString(id))), 15_000)) {
        "Missing control: " + ctx.getString(id)
    }
    private fun install(data: BackupContent) = HailData.restoreBackup(data,
        BackupContent.encodeApps(data.apps).toString(), BackupContent.encodeTags(data.tags).toString())

    @Test fun settingsWritesFilePreviewsCancelsAndRestoresMultiTagBackup() {
        val data = BackupContent(listOf(AppInfo("example.offline", true, true, mutableListOf(0,-8))),
            listOf("All" to 0, "Games 🎮" to -8), mapOf(HailData.HOME_FONT_SIZE to 16f))
        val original = HailData.backupSnapshot()
        val empty = BackupContent(emptyList(), listOf("Local" to 0), emptyMap())
        val uri = requireNotNull(ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Hail-test-${System.nanoTime()}.json")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
        }))
        val requests = mutableListOf<String>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action !in listOf(Intent.ACTION_CREATE_DOCUMENT, Intent.ACTION_OPEN_DOCUMENT)) return null
                requests.add(requireNotNull(intent.action))
                return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri))
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            instrumentation.runOnMainSync { install(data) }
            ActivityScenario.launch(MainActivity::class.java).use {
                requireNotNull(device.wait(Until.findObject(By.res(ctx.packageName, "nav_settings")), 15_000)).click()
                button(R.string.backup_save).click()
                button(R.string.backup_saved)
                button(android.R.string.ok).click()
                val exported = requireNotNull(ctx.contentResolver.openInputStream(uri)).use(BackupContent::readFrom)
                assertEquals(listOf(0,-8), exported.apps.single().tagIdList)
                assertEquals(data.tags, exported.tags)
                instrumentation.runOnMainSync { install(empty) }

                button(R.string.backup_restore).click()
                button(R.string.backup_replace)
                button(android.R.string.cancel).click()
                assertTrue(HailData.checkedList.isEmpty())
                button(R.string.backup_restore).click()
                button(R.string.backup_replace).click()
                button(R.string.backup_restored)
                assertEquals("example.offline", HailData.checkedList.single().packageName)
                assertTrue(HailData.checkedList.single().pinned && HailData.checkedList.single().whitelisted)
                assertEquals(data.tags, HailData.tags)
                assertEquals(16f, HailData.homeFontSize, 0f)
                assertEquals(listOf(Intent.ACTION_CREATE_DOCUMENT,Intent.ACTION_OPEN_DOCUMENT,Intent.ACTION_OPEN_DOCUMENT), requests)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { install(original) }
            ctx.contentResolver.delete(uri, null, null)
        }
    }

    @Test fun invalidBackupsAndDeviceLocalSettings() {
        val data = BackupContent(listOf(AppInfo("example.app")), listOf("Default" to 0), emptyMap())
        val text = ByteArrayOutputStream().also { data.writeTo(it) }.toString("UTF-8")
        val invalid = listOf(text.dropLast(1), text + "{}",
            text.replace("\"version\":1", "\"version\":2"),
            text.replace("\"tags\":[0]", "\"tags\":[56]"),
            text.replace("example.app", "invalid package"))
        for (bad in invalid) assertTrue(runCatching { BackupContent.readFrom(bad.byteInputStream()) }.isFailure)
        assertTrue(runCatching { BackupContent.readFrom(ByteArrayInputStream(ByteArray(4*1024*1024+1))) }.isFailure)
        val extra = text.replace("\"settings\":{}", "\"settings\":{\"working_mode\":\"owner_suspend\",\"biometric_login\":true}")
        assertTrue(BackupContent.readFrom(extra.byteInputStream()).settings.isEmpty())
    }
}
