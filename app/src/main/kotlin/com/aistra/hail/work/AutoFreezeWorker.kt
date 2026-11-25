package com.aistra.hail.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.services.AutoFreezeService
import com.aistra.hail.utils.HSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AutoFreezeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val screenOff = inputData.getBoolean(HailData.ACTION_LOCK, true)
        if ((screenOff && (!HailData.autoFreezeAfterLock || HSystem.isInteractive(context))) ||
            (HailData.skipWhileCharging && HSystem.isCharging(context))) return@withContext Result.success()
        val foreground = if (HailData.skipForegroundApp) HSystem.foregroundPackage(context)
            ?: return@withContext Result.success() else null
        val notifying = if (HailData.skipNotifyingApp) AutoFreezeService.notifyingPackages()
            ?: return@withContext Result.success() else emptySet()
        val packages = withContext(Dispatchers.Main.immediate) {
            HailData.checkedList.filterNot { it.whitelisted || it.packageName == foreground || it.packageName in notifying }
                .map { it.packageName }
        }
        val result = AppManager.changeFrozen(packages, true)
        app.setAutoFreezeService()
        if (result.failed.isEmpty()) Result.success() else Result.failure()
    }
}
