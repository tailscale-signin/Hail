package com.aistra.hail.utils

import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.preference.PreferenceManager
import com.aistra.hail.BuildConfig
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.app.HailData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors

class DecisionTrace internal constructor(internal val session: String, internal val source: String,
                                         internal val action: String, internal val mode: String) {
    internal val id = UUID.randomUUID().toString()
    internal val started = SystemClock.elapsedRealtime()

    fun event(step: String, pkg: String? = null, vararg fields: Pair<String, Any?>) =
        DecisionLog.record(this, step, pkg, fields)

    fun error(step: String, pkg: String?, error: Throwable) {
        val cause = (error as? InvocationTargetException)?.targetException ?: error
        event(step, pkg, "errorType" to cause.javaClass.name, "error" to cause.message?.take(600))
    }

    fun skipped(pkg: String?, reason: String) = event("skipped", pkg, "reason" to reason)
}

/** Only Hail's own package decisions enter this log. A single queue orders writes, rotation and snapshots. */
object DecisionLog {
    const val PREF = "debug_decision_logging"
    private const val SEGMENT_BYTES = 2 * 1024 * 1024L
    private val preferences by lazy { PreferenceManager.getDefaultSharedPreferences(app) }
    @Volatile var enabled = preferences.getBoolean(PREF, false)
        private set
    private var session: String? = null
    private var sequence = 0L
    private val worker by lazy { Executors.newSingleThreadExecutor { task -> Thread(task, "Hail decision log") } }
    private val dispatcher by lazy { worker.asCoroutineDispatcher() }
    private val directory get() = File(app.noBackupFilesDir, "decision-log")
    private val current get() = File(directory, "current.jsonl")
    private val previous get() = File(directory, "previous.jsonl")
    private var writeFailures = 0L // Writer thread only
    private val utc by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    @Synchronized fun setEnabled(value: Boolean) {
        if (value == enabled) return
        if (value) {
            enabled = true
            ensureSession()
        } else {
            val trace = DecisionTrace(ensureSession(), "settings", "logging", HailData.workingMode)
            trace.event("logging_stopped")
            enabled = false
            session = null
        }
        preferences.edit().putBoolean(PREF, value).apply()
    }

    // Called only under this object's monitor, including the first request after process restart.
    private fun ensureSession(): String {
        session?.let { return it }
        val id = UUID.randomUUID().toString()
        session = id
        sequence = 0
        DecisionTrace(id, "hail", "logging", HailData.workingMode).event("session_started", null,
            "version" to BuildConfig.VERSION_NAME, "build" to BuildConfig.VERSION_CODE,
            "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL, "android" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT, "appUid" to android.os.Process.myUid())
        return id
    }

    fun change(source: String, frozen: Boolean, mode: String = HailData.workingMode, pkg: String? = null) =
        begin(source, if (frozen) "freeze" else "unfreeze", mode, pkg)

    fun begin(source: String, action: String, mode: String = HailData.workingMode, pkg: String? = null): DecisionTrace? {
        if (!enabled) return null
        return synchronized(this) {
            if (!enabled) null else DecisionTrace(ensureSession(), source, action, mode).also { it.event("request", pkg) }
        }
    }

    @Synchronized internal fun record(trace: DecisionTrace, step: String, pkg: String?, fields: Array<out Pair<String, Any?>>) {
        if (!enabled || session != trace.session) return
        val seq = ++sequence
        val time = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime() - trace.started
        worker.execute {
            try {
                val row = JSONObject().apply {
                    put("time", utc.format(Date(time)))
                    put("session", trace.session)
                    put("seq", seq)
                    put("operation", trace.id)
                    put("elapsedMs", elapsed)
                    put("source", trace.source)
                    put("action", trace.action)
                    put("mode", trace.mode)
                    put("androidUser", HPackages.myUserId)
                    put("step", step)
                    pkg?.let { put("package", it.take(256)) }
                    fields.forEach { (key, value) -> put(key, if (value is String) value.take(600) else value ?: JSONObject.NULL) }
                }
                val bytes = (row.toString() + "\n").toByteArray(Charsets.UTF_8)
                val file = current
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create debug log directory")
                if (file.length() + bytes.size > SEGMENT_BYTES && !file.renameTo(previous))
                    throw IOException("Cannot rotate debug log")
                file.appendBytes(bytes)
            } catch (e: Exception) {
                if (writeFailures++ == 0L) HLog.e(e)
            }
        }
    }

    /** All earlier writes run before deletion. If recording is on, later events start a new history. */
    suspend fun clear() = withContext(dispatcher) {
        for (file in listOf(previous, current)) {
            if (file.exists() && !file.delete()) throw IOException("Cannot erase debug log")
        }
        writeFailures = 0L
    }

    suspend fun export(uri: Uri) {
        // Snapshot the bounded files on the writer queue before handing data to the chosen document provider.
        val snapshot = withContext(dispatcher) {
            val header = JSONObject().apply {
                put("format", "Hail decision log")
                put("schema", 1)
                put("enabledSettings", JSONObject().apply {
                    put("0", "DEFAULT"); put("1", "ENABLED"); put("2", "DISABLED")
                    put("3", "DISABLED_USER"); put("4", "DISABLED_UNTIL_USED")
                })
                put("exportedUtc", utc.format(Date()))
                put("version", BuildConfig.VERSION_NAME)
                put("build", BuildConfig.VERSION_CODE)
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("sdk", Build.VERSION.SDK_INT)
                put("androidUser", HPackages.myUserId)
                put("workingModeAtExport", HailData.workingMode)
                put("recordingAtExport", enabled)
                put("retentionBytes", 2 * SEGMENT_BYTES)
                put("writeErrorsThisProcess", writeFailures)
            }
            listOf((header.toString() + "\n").toByteArray(Charsets.UTF_8)) +
                listOf(previous, current).filter { it.exists() }.map { it.readBytes() }
        }
        withContext(Dispatchers.IO) {
            val output = app.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open export document")
            output.use { stream -> snapshot.forEach { stream.write(it) } }
        }
    }

    fun suggestedName(): String = "Hail-debug-" +
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".jsonl"
}
