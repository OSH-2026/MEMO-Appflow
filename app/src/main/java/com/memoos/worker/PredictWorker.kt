package com.memoos.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.memoos.core.config.ExecutionMode
import com.memoos.ui.main.MemoGraph

class PredictWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val graph = MemoGraph.from(applicationContext)
        val config = graph.configRepository.get()
        if (config.executionMode != ExecutionMode.ONLINE_DEVICE) {
            return Result.success()
        }
        graph.predictPipeline.run(config)
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "memo_policy_worker",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PolicyWorker>().build(),
        )
        return Result.success()
    }
}
