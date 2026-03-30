package com.memoos.data.repository

import android.content.SharedPreferences
import com.memoos.core.config.ExecutionMode
import com.memoos.core.config.MemoConfig
import com.memoos.core.config.PredictorType

class ConfigRepository(
    private val sharedPreferences: SharedPreferences,
) {
    fun get(): MemoConfig {
        return MemoConfig(
            topK = sharedPreferences.getInt(KEY_TOP_K, 3),
            historyWindowSize = sharedPreferences.getInt(KEY_HISTORY_WINDOW, 5),
            prewarmThreshold = sharedPreferences.getFloat(KEY_PREWARM_THRESHOLD, 0.55f),
            keepAliveThreshold = sharedPreferences.getFloat(KEY_KEEP_ALIVE_THRESHOLD, 0.70f),
            hintThreshold = sharedPreferences.getFloat(KEY_HINT_THRESHOLD, 0.35f),
            appFlowPreloadBudgetMb = sharedPreferences.getInt(KEY_APPFLOW_PRELOAD_BUDGET_MB, 100),
            appFlowRecentUseWindowMinutes = sharedPreferences.getLong(KEY_APPFLOW_RECENT_WINDOW_MINUTES, 30L),
            appFlowBurstWindowMinutes = sharedPreferences.getLong(KEY_APPFLOW_BURST_WINDOW_MINUTES, 5L),
            appFlowKillCandidateLimit = sharedPreferences.getInt(KEY_APPFLOW_KILL_CANDIDATE_LIMIT, 3),
            collectionIntervalMinutes = sharedPreferences.getLong(KEY_COLLECTION_INTERVAL, 15L),
            replayModeEnabled = sharedPreferences.getBoolean(KEY_REPLAY_MODE_ENABLED, false),
            datasetMode = sharedPreferences.getBoolean(KEY_DATASET_MODE, false),
            executionMode = ExecutionMode.valueOf(
                sharedPreferences.getString(KEY_EXECUTION_MODE, ExecutionMode.ONLINE_DEVICE.name)
                    ?: ExecutionMode.ONLINE_DEVICE.name,
            ),
            datasetName = sharedPreferences.getString(KEY_DATASET_NAME, "sample_local").orEmpty(),
            datasetUrl = sharedPreferences.getString(KEY_DATASET_URL, null),
            datasetLocalPath = sharedPreferences.getString(KEY_DATASET_LOCAL_PATH, null),
            datasetCacheDir = sharedPreferences.getString(KEY_DATASET_CACHE_DIR, "dataset_cache").orEmpty(),
            trainSplit = sharedPreferences.getFloat(KEY_TRAIN_SPLIT, 0.7f),
            valSplit = sharedPreferences.getFloat(KEY_VAL_SPLIT, 0.15f),
            testSplit = sharedPreferences.getFloat(KEY_TEST_SPLIT, 0.15f),
            onlineCollectionEnabled = sharedPreferences.getBoolean(KEY_ONLINE_COLLECTION_ENABLED, true),
            widgetRefreshIntervalMinutes = sharedPreferences.getLong(KEY_WIDGET_REFRESH_INTERVAL, 30L),
            nativeBridgeEnabled = sharedPreferences.getBoolean(KEY_NATIVE_BRIDGE_ENABLED, true),
            structuredLoggingEnabled = sharedPreferences.getBoolean(KEY_STRUCTURED_LOGGING_ENABLED, true),
            predictorType = PredictorType.valueOf(
                sharedPreferences.getString(KEY_PREDICTOR_TYPE, PredictorType.MARKOV.name)
                    ?: PredictorType.MARKOV.name,
            ),
        )
    }

    fun update(transform: (MemoConfig) -> MemoConfig) {
        val updated = transform(get())
        sharedPreferences.edit()
            .putInt(KEY_TOP_K, updated.topK)
            .putInt(KEY_HISTORY_WINDOW, updated.historyWindowSize)
            .putFloat(KEY_PREWARM_THRESHOLD, updated.prewarmThreshold)
            .putFloat(KEY_KEEP_ALIVE_THRESHOLD, updated.keepAliveThreshold)
            .putFloat(KEY_HINT_THRESHOLD, updated.hintThreshold)
            .putInt(KEY_APPFLOW_PRELOAD_BUDGET_MB, updated.appFlowPreloadBudgetMb)
            .putLong(KEY_APPFLOW_RECENT_WINDOW_MINUTES, updated.appFlowRecentUseWindowMinutes)
            .putLong(KEY_APPFLOW_BURST_WINDOW_MINUTES, updated.appFlowBurstWindowMinutes)
            .putInt(KEY_APPFLOW_KILL_CANDIDATE_LIMIT, updated.appFlowKillCandidateLimit)
            .putLong(KEY_COLLECTION_INTERVAL, updated.collectionIntervalMinutes)
            .putBoolean(KEY_REPLAY_MODE_ENABLED, updated.replayModeEnabled)
            .putBoolean(KEY_DATASET_MODE, updated.datasetMode)
            .putString(KEY_EXECUTION_MODE, updated.executionMode.name)
            .putString(KEY_DATASET_NAME, updated.datasetName)
            .putString(KEY_DATASET_URL, updated.datasetUrl)
            .putString(KEY_DATASET_LOCAL_PATH, updated.datasetLocalPath)
            .putString(KEY_DATASET_CACHE_DIR, updated.datasetCacheDir)
            .putFloat(KEY_TRAIN_SPLIT, updated.trainSplit)
            .putFloat(KEY_VAL_SPLIT, updated.valSplit)
            .putFloat(KEY_TEST_SPLIT, updated.testSplit)
            .putBoolean(KEY_ONLINE_COLLECTION_ENABLED, updated.onlineCollectionEnabled)
            .putLong(KEY_WIDGET_REFRESH_INTERVAL, updated.widgetRefreshIntervalMinutes)
            .putBoolean(KEY_NATIVE_BRIDGE_ENABLED, updated.nativeBridgeEnabled)
            .putBoolean(KEY_STRUCTURED_LOGGING_ENABLED, updated.structuredLoggingEnabled)
            .putString(KEY_PREDICTOR_TYPE, updated.predictorType.name)
            .apply()
    }

    companion object {
        private const val KEY_TOP_K = "config.top_k"
        private const val KEY_HISTORY_WINDOW = "config.history_window"
        private const val KEY_PREWARM_THRESHOLD = "config.prewarm_threshold"
        private const val KEY_KEEP_ALIVE_THRESHOLD = "config.keep_alive_threshold"
        private const val KEY_HINT_THRESHOLD = "config.hint_threshold"
        private const val KEY_APPFLOW_PRELOAD_BUDGET_MB = "config.appflow_preload_budget_mb"
        private const val KEY_APPFLOW_RECENT_WINDOW_MINUTES = "config.appflow_recent_window_minutes"
        private const val KEY_APPFLOW_BURST_WINDOW_MINUTES = "config.appflow_burst_window_minutes"
        private const val KEY_APPFLOW_KILL_CANDIDATE_LIMIT = "config.appflow_kill_candidate_limit"
        private const val KEY_COLLECTION_INTERVAL = "config.collection_interval"
        private const val KEY_REPLAY_MODE_ENABLED = "config.replay_mode_enabled"
        private const val KEY_DATASET_MODE = "config.dataset_mode"
        private const val KEY_EXECUTION_MODE = "config.execution_mode"
        private const val KEY_DATASET_NAME = "config.dataset_name"
        private const val KEY_DATASET_URL = "config.dataset_url"
        private const val KEY_DATASET_LOCAL_PATH = "config.dataset_local_path"
        private const val KEY_DATASET_CACHE_DIR = "config.dataset_cache_dir"
        private const val KEY_TRAIN_SPLIT = "config.train_split"
        private const val KEY_VAL_SPLIT = "config.val_split"
        private const val KEY_TEST_SPLIT = "config.test_split"
        private const val KEY_ONLINE_COLLECTION_ENABLED = "config.online_collection_enabled"
        private const val KEY_WIDGET_REFRESH_INTERVAL = "config.widget_refresh_interval"
        private const val KEY_NATIVE_BRIDGE_ENABLED = "config.native_bridge_enabled"
        private const val KEY_STRUCTURED_LOGGING_ENABLED = "config.structured_logging_enabled"
        private const val KEY_PREDICTOR_TYPE = "config.predictor_type"
    }
}
