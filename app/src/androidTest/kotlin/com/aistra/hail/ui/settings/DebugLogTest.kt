package com.aistra.hail.ui.settings

import android.content.ContentValues
import android.provider.MediaStore
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.aistra.hail.utils.DecisionLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class DebugLogTest {
    @Test fun offByDefaultOrderedBoundedAndExportableAfterStopping() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val dir = File(ctx.noBackupFilesDir, "decision-log")
        assertFalse(DecisionLog.enabled)
        assertNull(DecisionLog.begin("test", "before_opt_in"))
        assertFalse(dir.exists())
        val uri = requireNotNull(ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Hail-log-test-${System.nanoTime()}.jsonl")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/x-ndjson")
        }))
        try {
            DecisionLog.setEnabled(true)
            assertTrue(prefs.getBoolean(DecisionLog.PREF, false))
            val trace = requireNotNull(DecisionLog.begin("test", "retention_test"))
            val padding = "sample ".repeat(80)
            coroutineScope {
                repeat(4) { writer -> launch(Dispatchers.Default) {
                    repeat(1550) { index -> trace.event("sample", null, "writer" to writer, "index" to index, "detail" to padding) }
                } }
            }
            trace.event("captured_last")
            DecisionLog.setEnabled(false)
            assertFalse(prefs.getBoolean(DecisionLog.PREF, true))
            trace.event("after_opt_out")
            assertNull(DecisionLog.begin("test", "after_opt_out"))
            DecisionLog.export(uri)
            val lines = requireNotNull(ctx.contentResolver.openInputStream(uri)).bufferedReader().use { it.readLines() }
            val header = JSONObject(lines.first())
            assertFalse(header.getBoolean("recordingAtExport"))
            assertEquals(0L, header.getLong("writeErrorsThisProcess"))
            val files = requireNotNull(dir.listFiles())
            assertEquals(2, files.size)
            assertTrue(files.sumOf { it.length() } <= header.getLong("retentionBytes"))
            val events = lines.drop(1).map(::JSONObject)
            val seq = events.map { it.getLong("seq") }
            assertEquals(seq.sorted().distinct(), seq)
            assertTrue(seq.first() > 1)
            assertEquals("logging_stopped", events.last().getString("step"))
            assertEquals("captured_last", events[events.lastIndex - 1].getString("step"))
            assertFalse(events.any { it.getString("step") == "after_opt_out" })
        } finally {
            DecisionLog.setEnabled(false)
            ctx.contentResolver.delete(uri, null, null)
        }
        Unit
    }
}
