package com.memoos.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.memoos.core.model.ReplaySessionConfig
import com.memoos.ui.main.MemoGraph
import com.memoos.ui.widget.RecommendationWidgetUpdater

class EvaluateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val graph = MemoGraph.from(applicationContext)
        val config = graph.configRepository.get()
        if (config.replayModeEnabled) {
            graph.runConfiguredReplay(
                ReplaySessionConfig(
                    datasetName = config.datasetName,
                    batchSize = config.historyWindowSize,
                    exportResults = true,
                ),
            )
        } else {
            graph.experimentAnalyzer.summarizeRecent()
        }
        RecommendationWidgetUpdater.updateAll(applicationContext)
        return Result.success()
    }
}
