package com.memoos.data.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "predictions")
data class PredictionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val score: Float,
    val rank: Int,
    val predictionTimestamp: Long,
    val batchTimestamp: Long,
    val predictorName: String,
)
