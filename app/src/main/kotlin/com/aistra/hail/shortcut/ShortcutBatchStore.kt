package com.aistra.hail.shortcut

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/** A single atomic snapshot, kept out of device backup and independent of Activity saved state. */
internal class ShortcutBatchStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "individual-shortcut-batch.json"))

    fun read(): BatchState? {
        // openRead also restores an AtomicFile backup left by an interrupted write.
        val input = try { file.openRead() } catch (_: FileNotFoundException) { return null }
        val json = input.bufferedReader().use { JSONObject(it.readText()) }
        require(json.getInt("schema") == 1) { "Unsupported shortcut batch format" }
        val packages = json.getJSONArray("packages").strings()
        val position = json.getInt("position")
        require(packages.distinct().size == packages.size && packages.all { PACKAGE.matches(it) })
        require(position in 0..packages.size)
        val batchId = json.getString("batchId")
        val pending = json.optJSONObject("awaiting")?.let {
            PendingShortcut(batchId, it.getString("requestId"), it.getString("packageName"),
                it.getString("label"), it.getString("launcherPackage"),
                it.getJSONArray("confirmationActivities").strings().toSet(), it.getLong("requestedAtElapsed"),
                it.optBoolean("approvalReceived"), it.optBoolean("confirmationObserved"),
                it.optBoolean("automationClicked"), it.optBoolean("confirmationDismissed"))
        }
        require(pending == null || pending.packageName == packages.getOrNull(position))
        val added = json.getJSONArray("added").strings()
        val existing = json.getJSONArray("existing").strings()
        val skipped = json.getJSONArray("skipped").strings()
        require(added.size + existing.size + skipped.size == position)
        require((added + existing + skipped).toSet() == packages.take(position).toSet())
        return BatchState(initialized = true, batchId = batchId, packages = packages, position = position,
            added = added, existing = existing, skipped = skipped,
            status = BatchStatus.valueOf(json.getString("status")), automatic = json.getBoolean("automatic"),
            launcherPackage = json.getString("launcherPackage"), awaiting = pending,
            message = json.optString("message").takeIf { it.isNotEmpty() })
    }

    fun write(state: BatchState) {
        if (state.batchId == null) return
        val json = JSONObject().put("schema", 1).put("batchId", state.batchId)
            .put("packages", JSONArray(state.packages)).put("position", state.position)
            .put("added", JSONArray(state.added)).put("existing", JSONArray(state.existing))
            .put("skipped", JSONArray(state.skipped)).put("status", state.status.name)
            .put("automatic", state.automatic).put("launcherPackage", state.launcherPackage)
            .put("message", state.message ?: "")
        state.awaiting?.let {
            json.put("awaiting", JSONObject().put("requestId", it.requestId).put("packageName", it.packageName)
                .put("label", it.label).put("launcherPackage", it.launcherPackage)
                .put("confirmationActivities", JSONArray(it.confirmationActivities.toList()))
                .put("requestedAtElapsed", it.requestedAtElapsed).put("approvalReceived", it.approvalReceived)
                .put("confirmationObserved", it.confirmationObserved).put("automationClicked", it.automationClicked)
                .put("confirmationDismissed", it.confirmationDismissed))
        }
        val output = file.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    private fun JSONArray.strings() = List(length()) { getString(it) }
    companion object { private val PACKAGE = Regex("[A-Za-z0-9_.]+") }
}
