package com.memoos.appflow.model

data class AppLaunchProfile(
    val packageName: String,
    val coldLaunchMs: Long,
    val smallHotSetMb: Int,
    val hotLargeSegmentsMb: Int,
    val streamingTailMb: Int,
    val baselineRelaunchMb: Int,
    val bloatedBackgroundMb: Int,
    val cutoffKb: Int,
) {
    val beforeLaunchBudgetMb: Int
        get() = smallHotSetMb + hotLargeSegmentsMb

    val reclaimBenefitMb: Int
        get() = (bloatedBackgroundMb - baselineRelaunchMb).coerceAtLeast(0)
}
