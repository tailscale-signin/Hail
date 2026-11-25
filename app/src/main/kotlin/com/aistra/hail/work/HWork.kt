package com.aistra.hail.work

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.DecisionLog
import java.util.concurrent.TimeUnit

object HWork {
    fun cancelWork(name: String) =
        WorkManager.getInstance(app).cancelUniqueWork(name)

    fun setDeferredFrozen(packageName: String, frozen: Boolean = true, minutes: Long) {
        val request = OneTimeWorkRequestBuilder<FrozenWorker>().setInputData(
            workDataOf(HailData.KEY_PACKAGE to packageName, HailData.KEY_FROZEN to frozen)
        ).setInitialDelay(minutes, TimeUnit.MINUTES).build()
        WorkManager.getInstance(app).enqueueUniqueWork(packageName, ExistingWorkPolicy.REPLACE, request)
        DecisionLog.change("home_schedule", frozen, pkg = packageName)?.event("work_enqueued", packageName,
            "workId" to request.id.toString(), "delayMinutes" to minutes, "policy" to "replace")
    }

    fun setAutoFreeze(screenOff: Boolean, source: String = "screen_off") {
        val delay = if (screenOff) HailData.autoFreezeDelay else 0L
        val request = OneTimeWorkRequestBuilder<AutoFreezeWorker>().setInputData(
            workDataOf(HailData.ACTION_LOCK to screenOff)
        ).setInitialDelay(delay, TimeUnit.MINUTES).build()
        WorkManager.getInstance(app).enqueueUniqueWork(HailApi.ACTION_FREEZE_ALL, ExistingWorkPolicy.REPLACE, request)
        DecisionLog.change(source, true)?.event("work_enqueued", null, "workId" to request.id.toString(),
            "delayMinutes" to delay, "screenOffTrigger" to screenOff, "policy" to "replace")
    }
}
