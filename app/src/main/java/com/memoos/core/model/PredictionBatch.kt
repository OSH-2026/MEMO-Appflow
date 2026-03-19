package com.memoos.core.model

data class PredictionBatch(
    val predictions: List<Prediction>,
    val generatedAt: Long,
    val predictorName: String,
    val historySize: Int,
)
