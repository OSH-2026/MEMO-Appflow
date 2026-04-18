package com.memoos.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.memoos.ui.widget.RecommendationWidgetUpdater

class WidgetRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        RecommendationWidgetUpdater.updateAll(applicationContext)
        return Result.success()
    }
}
