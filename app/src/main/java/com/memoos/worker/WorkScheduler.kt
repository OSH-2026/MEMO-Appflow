package com.memoos.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.memoos.core.config.ExecutionMode
import com.memoos.core.config.MemoConfig
import java.util.concurrent.TimeUnit

class WorkScheduler(
    private val context: Context,
) {
    fun schedule(config: MemoConfig) {
        val manager = WorkManager.getInstance(context.applicationContext)
        manager.enqueueUniquePeriodicWork(
            "memo_widget_refresh_worker",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<WidgetRefreshWorker>(
                config.widgetRefreshIntervalMinutes,
                TimeUnit.MINUTES,
            ).build(),
        )
        when (config.executionMode) {
            ExecutionMode.ONLINE_DEVICE -> {
                manager.enqueueUniquePeriodicWork(
                    "memo_collect_worker",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<CollectWorker>(
                        config.collectionIntervalMinutes,
                        TimeUnit.MINUTES,
                    ).build(),
                )
                manager.enqueueUniquePeriodicWork(
                    "memo_predict_worker",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<PredictWorker>(30, TimeUnit.MINUTES).build(),
                )
                manager.enqueueUniquePeriodicWork(
                    "memo_evaluate_worker",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<EvaluateWorker>(60, TimeUnit.MINUTES).build(),
                )
            }
            ExecutionMode.PUBLIC_DATASET_REPLAY,
            ExecutionMode.LOCAL_REPLAY,
            -> {
                // Replay mode stays explicitly user-triggered so emulator demos remain deterministic.
            }
        }
    }
}
