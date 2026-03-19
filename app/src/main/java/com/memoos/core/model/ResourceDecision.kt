package com.memoos.core.model

data class ResourceDecision(
    val keepAlivePackages: List<String>,
    val prewarmPackages: List<String>,
    val hintPackages: List<String>,
    val decisionTimestamp: Long,
    val policyName: String,
)
