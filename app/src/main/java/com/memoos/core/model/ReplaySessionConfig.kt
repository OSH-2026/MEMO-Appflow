package com.memoos.core.model

data class ReplaySessionConfig(
    val datasetName: String,
    val batchSize: Int,
    val startTimestamp: Long? = null,
    val endTimestamp: Long? = null,
    val loopReplay: Boolean = false,
    val exportResults: Boolean = true,
)
