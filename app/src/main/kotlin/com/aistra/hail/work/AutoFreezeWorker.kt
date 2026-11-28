package com.aistra.hail.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.ui.FreezeFeedback
import com.aistra.hail.services.AutoFreezeService
import com.aistra.hail.utils.DecisionLog
import com.aistra.hail.utils.HSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AutoFreezeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val screenOff = inputData.getBoolean(HailData.ACTION_LOCK, true)
        val mode = HailData.workingMode
        val trace = DecisionLog.change("auto_freeze", true, mode)
        trace?.event("worker_started", null, "workId" to id.toString(), "screenOffTrigger" to screenOff)
        fun skip(reason: String): Result {
            trace?.skipped(null, reason)
            return Result.success()
        }
        if (screenOff && !HailData.autoFreezeAfterLock) return@withContext skip("Auto-freeze after lock is off")
        if (screenOff && HSystem.isInteractive(context)) return@withContext skip("Screen is interactive")
        if (HailData.skipWhileCharging && HSystem.isCharging(context)) return@withContext skip("Skip while charging is enabled")
        val foreground = if (HailData.skipForegroundApp) HSystem.foregroundPackage(context)
            ?: return@withContext skip("Foreground package could not be determined") else null
        val notifying = if (HailData.skipNotifyingApp) AutoFreezeService.notifyingPackages()
            ?: return@withContext skip("Notification access unavailable") else emptySet()
        val packages = withContext(Dispatchers.Main.immediate) {
            HailData.checkedList.mapNotNull {
                val reason = when {
                    it.whitelisted -> "Whitelisted"
                    it.packageName == foreground -> "Foreground package"
                    it.packageName in notifying -> "Active notification"
                    else -> null
                }
                if (reason == null) it.packageName else {
                    trace?.skipped(it.packageName, reason)
                    null
                }
            }
        }
        val result = AppManager.changeFrozen(packages, true, includeLabel = false, mode = mode, trace = trace)
        if (!screenOff) withContext(Dispatchers.Main.immediate) { FreezeFeedback.show(result) }
        app.setAutoFreezeService()
        if (result.failed.isEmpty()) Result.success() else Result.failure()
    }
}
