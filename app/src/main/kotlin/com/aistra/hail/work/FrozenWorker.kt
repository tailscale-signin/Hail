package com.aistra.hail.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData

class FrozenWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val pkg = inputData.getString(HailData.KEY_PACKAGE) ?: return Result.failure()
        val result = AppManager.changeFrozen(listOf(pkg), inputData.getBoolean(HailData.KEY_FROZEN, true), includeLabel = false)
        return if (result.reachedTarget(pkg)) Result.success() else Result.failure()
    }
}
