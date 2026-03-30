package com.memoos.core.model

data class ResourceDecision(
    val keepAlivePackages: List<String>,
    val prewarmPackages: List<String>,
    val hintPackages: List<String>,
    val decisionTimestamp: Long,
    val policyName: String,
    val reclaimMode: String = "normal",
    val protectedPackages: List<String> = emptyList(),
    val deferredKillPackages: List<String> = emptyList(),
    val killCandidatePackages: List<String> = emptyList(),
    val preloadBudgetMb: Int = 0,
    val predictedLaunchBenefitMs: Long = 0L,
    val rationale: String = "",
)
