package com.memoos.data.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "experiment_records")
data class ExperimentRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mode: String,
    val datasetName: String?,
    val predictorName: String,
    val policyName: String,
    val predictedTop3: String,
    val actualNextApp: String?,
    val hitAt1: Boolean,
    val hitAt3: Boolean,
    val predictionTimestamp: Long,
    val observationTimestamp: Long,
    val keepAliveCount: Int,
    val prewarmCount: Int,
    val hintCount: Int,
    val launchLatencyMs: Long?,
    val memorySnapshotRef: String?,
    val batterySnapshotRef: String?,
)
