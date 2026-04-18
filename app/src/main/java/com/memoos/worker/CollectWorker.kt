package com.memoos.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.memoos.core.config.ExecutionMode
import com.memoos.ui.main.MemoGraph

class CollectWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val graph = MemoGraph.from(applicationContext)
        val config = graph.configRepository.get()
        if (config.executionMode != ExecutionMode.ONLINE_DEVICE || !config.onlineCollectionEnabled) {
            return Result.success()
        }
        graph.collectPipeline.run(System.currentTimeMillis() - config.collectionIntervalMinutes * 60_000L)
        return Result.success()
    }
}
