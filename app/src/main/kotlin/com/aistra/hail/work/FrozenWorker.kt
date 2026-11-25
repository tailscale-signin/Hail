package com.aistra.hail.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.DecisionLog

class FrozenWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val pkg = inputData.getString(HailData.KEY_PACKAGE)
        val frozen = inputData.getBoolean(HailData.KEY_FROZEN, true)
        val trace = DecisionLog.change("deferred_worker", frozen, pkg = pkg)
        trace?.event("worker_started", pkg, "workId" to id.toString())
        if (pkg == null) {
            trace?.skipped(null, "Work request has no target package")
            return Result.failure()
        }
        val result = AppManager.changeFrozen(listOf(pkg), frozen, includeLabel = false, trace = trace)
        return if (result.reachedTarget(pkg, frozen)) Result.success() else Result.failure()
    }
}
