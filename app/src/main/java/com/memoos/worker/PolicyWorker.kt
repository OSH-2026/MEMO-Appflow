package com.memoos.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.memoos.core.config.ExecutionMode
import com.memoos.ui.main.MemoGraph
import com.memoos.ui.widget.RecommendationWidgetUpdater

class PolicyWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val graph = MemoGraph.from(applicationContext)
        val config = graph.configRepository.get()
        if (config.executionMode != ExecutionMode.ONLINE_DEVICE) {
            return Result.success()
        }
        val batch = graph.predictionRepository.latestBatch() ?: return Result.success()
        val execution = graph.policyPipeline.run(batch, config)
        graph.evaluationPipeline.run(
            mode = ExecutionMode.ONLINE_DEVICE,
            datasetName = null,
            batch = batch,
            decision = execution.decision,
            actualNextApp = null,
            observationTimestamp = System.currentTimeMillis(),
        )
        RecommendationWidgetUpdater.updateAll(applicationContext)
        return Result.success()
    }
}
