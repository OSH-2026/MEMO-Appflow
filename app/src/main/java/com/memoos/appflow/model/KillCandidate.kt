package com.memoos.appflow.model

data class KillCandidate(
    val packageName: String,
    val currentMemoryMb: Int,
    val relaunchBaselineMb: Int,
    val reclaimBenefitMb: Int,
    val lastUsedAt: Long,
    val recentlyUsed: Boolean,
)
