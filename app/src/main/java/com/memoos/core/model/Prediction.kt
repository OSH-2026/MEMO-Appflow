package com.memoos.core.model

data class Prediction(
    val packageName: String,
    val score: Float,
    val rank: Int,
    val predictionTimestamp: Long,
)
