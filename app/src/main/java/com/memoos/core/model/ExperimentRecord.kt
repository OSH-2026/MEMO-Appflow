package com.memoos.core.model

data class ExperimentRecord(
    val mode: String,
    val datasetName: String?,
    val predictorName: String,
    val policyName: String,
    val predictedTop3: List<String>,
    val actualNextApp: String?,
    val hitAt1: Boolean,
    val hitAt3: Boolean,
    val predictionTimestamp: Long,
    val observationTimestamp: Long,
    val keepAliveCount: Int,
    val prewarmCount: Int,
    val hintCount: Int,
    val launchLatencyMs: Long? = null,
    val memorySnapshotRef: String? = null,
    val batterySnapshotRef: String? = null,
)
