package com.memoos.core.config

data class MemoConfig(
    val topK: Int = 3,
    val historyWindowSize: Int = 5,
    val prewarmThreshold: Float = 0.55f,
    val keepAliveThreshold: Float = 0.70f,
    val hintThreshold: Float = 0.35f,
    val collectionIntervalMinutes: Long = 15L,
    val replayModeEnabled: Boolean = false,
    val datasetMode: Boolean = false,
    val executionMode: ExecutionMode = ExecutionMode.ONLINE_DEVICE,
    val datasetName: String = "sample_local",
    val datasetUrl: String? = null,
    val datasetLocalPath: String? = null,
    val datasetCacheDir: String = "dataset_cache",
    val trainSplit: Float = 0.7f,
    val valSplit: Float = 0.15f,
    val testSplit: Float = 0.15f,
    val onlineCollectionEnabled: Boolean = true,
    val widgetRefreshIntervalMinutes: Long = 30L,
    val nativeBridgeEnabled: Boolean = true,
    val structuredLoggingEnabled: Boolean = true,
    val predictorType: PredictorType = PredictorType.MARKOV,
)

enum class ExecutionMode {
    PUBLIC_DATASET_REPLAY,
    LOCAL_REPLAY,
    ONLINE_DEVICE,
}

enum class PredictorType {
    MARKOV,
    FREQUENCY,
}
