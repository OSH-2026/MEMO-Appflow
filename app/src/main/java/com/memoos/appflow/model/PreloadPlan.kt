package com.memoos.appflow.model

data class PreloadPlan(
    val targetPackage: String?,
    val beforeLaunchPackages: List<String>,
    val duringLaunchPackages: List<String>,
    val protectedPackages: List<String>,
    val budgetMb: Int,
    val reservedBudgetMb: Int,
    val predictedBenefitMs: Long,
    val cutoffKb: Int,
)
