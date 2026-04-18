package com.memoos.data.repository

import android.content.SharedPreferences
import com.memoos.core.model.ResourceDecision
import com.memoos.system.bridge.SystemExecutionReport

class SystemStatusRepository(
    private val sharedPreferences: SharedPreferences,
) {
    fun record(decision: ResourceDecision, report: SystemExecutionReport) {
        sharedPreferences.edit()
            .putString(KEY_BRIDGE_NAME, report.bridgeName)
            .putString(KEY_POLICY_NAME, decision.policyName)
            .putString(KEY_RECLAIM_MODE, decision.reclaimMode)
            .putInt(KEY_KEEP_ALIVE_COUNT, report.retainedPackages.size)
            .putInt(KEY_PREWARM_COUNT, report.prewarmedPackages.size)
            .putInt(KEY_HINT_COUNT, report.hintedPackages.size)
            .putString(KEY_KEEP_ALIVE_PACKAGES, report.retainedPackages.joinToString("|"))
            .putString(KEY_PREWARM_PACKAGES, report.prewarmedPackages.joinToString("|"))
            .putString(KEY_HINT_PACKAGES, report.hintedPackages.joinToString("|"))
            .putString(KEY_PROTECTED_PACKAGES, decision.protectedPackages.joinToString("|"))
            .putString(KEY_DEFERRED_KILL_PACKAGES, decision.deferredKillPackages.joinToString("|"))
            .putString(KEY_KILL_CANDIDATE_PACKAGES, decision.killCandidatePackages.joinToString("|"))
            .putInt(KEY_PRELOAD_BUDGET_MB, decision.preloadBudgetMb)
            .putLong(KEY_PREDICTED_BENEFIT_MS, decision.predictedLaunchBenefitMs)
            .putString(KEY_RATIONALE, decision.rationale)
            .putLong(KEY_EXECUTION_TIMESTAMP, report.executionTimestamp)
            .apply()
    }

    fun read(): SystemStatusSnapshot {
        val bridgeName = sharedPreferences.getString(KEY_BRIDGE_NAME, "").orEmpty()
        val policyName = sharedPreferences.getString(KEY_POLICY_NAME, "").orEmpty()
        val reclaimMode = sharedPreferences.getString(KEY_RECLAIM_MODE, "normal").orEmpty()
        val keepAlivePackages = sharedPreferences.getString(KEY_KEEP_ALIVE_PACKAGES, "").orEmpty().splitPackages()
        val prewarmPackages = sharedPreferences.getString(KEY_PREWARM_PACKAGES, "").orEmpty().splitPackages()
        val hintPackages = sharedPreferences.getString(KEY_HINT_PACKAGES, "").orEmpty().splitPackages()
        val protectedPackages = sharedPreferences.getString(KEY_PROTECTED_PACKAGES, "").orEmpty().splitPackages()
        val deferredKillPackages = sharedPreferences.getString(KEY_DEFERRED_KILL_PACKAGES, "").orEmpty().splitPackages()
        val killCandidatePackages = sharedPreferences.getString(KEY_KILL_CANDIDATE_PACKAGES, "").orEmpty().splitPackages()
        val preloadBudgetMb = sharedPreferences.getInt(KEY_PRELOAD_BUDGET_MB, 0)
        val predictedLaunchBenefitMs = sharedPreferences.getLong(KEY_PREDICTED_BENEFIT_MS, 0L)
        val rationale = sharedPreferences.getString(KEY_RATIONALE, "").orEmpty()
        val executionTimestamp = sharedPreferences.getLong(KEY_EXECUTION_TIMESTAMP, 0L)
        val summaryText = if (bridgeName.isBlank()) {
            "System idle"
        } else {
            "Bridge ${bridgeName.displayName()} | Policy ${policyName.displayName()} | Reclaim ${reclaimMode.displayMode()} | Keep ${keepAlivePackages.size} | Prewarm ${prewarmPackages.size} | Protect ${protectedPackages.size}"
        }
        return SystemStatusSnapshot(
            bridgeName = bridgeName,
            policyName = policyName,
            reclaimMode = reclaimMode,
            keepAlivePackages = keepAlivePackages,
            prewarmPackages = prewarmPackages,
            hintPackages = hintPackages,
            protectedPackages = protectedPackages,
            deferredKillPackages = deferredKillPackages,
            killCandidatePackages = killCandidatePackages,
            preloadBudgetMb = preloadBudgetMb,
            predictedLaunchBenefitMs = predictedLaunchBenefitMs,
            rationale = rationale,
            executionTimestamp = executionTimestamp,
            summaryText = summaryText,
        )
    }

    companion object {
        private const val KEY_BRIDGE_NAME = "system_status.bridge_name"
        private const val KEY_POLICY_NAME = "system_status.policy_name"
        private const val KEY_RECLAIM_MODE = "system_status.reclaim_mode"
        private const val KEY_KEEP_ALIVE_COUNT = "system_status.keep_alive_count"
        private const val KEY_PREWARM_COUNT = "system_status.prewarm_count"
        private const val KEY_HINT_COUNT = "system_status.hint_count"
        private const val KEY_KEEP_ALIVE_PACKAGES = "system_status.keep_alive_packages"
        private const val KEY_PREWARM_PACKAGES = "system_status.prewarm_packages"
        private const val KEY_HINT_PACKAGES = "system_status.hint_packages"
        private const val KEY_PROTECTED_PACKAGES = "system_status.protected_packages"
        private const val KEY_DEFERRED_KILL_PACKAGES = "system_status.deferred_kill_packages"
        private const val KEY_KILL_CANDIDATE_PACKAGES = "system_status.kill_candidate_packages"
        private const val KEY_PRELOAD_BUDGET_MB = "system_status.preload_budget_mb"
        private const val KEY_PREDICTED_BENEFIT_MS = "system_status.predicted_benefit_ms"
        private const val KEY_RATIONALE = "system_status.rationale"
        private const val KEY_EXECUTION_TIMESTAMP = "system_status.execution_timestamp"
    }
}

private fun String.displayName(): String {
    return when (this) {
        "app_level_system_bridge+native_system_bridge" -> "App bridge + native bridge"
        "app_level_system_bridge" -> "App bridge"
        "native_system_bridge" -> "Native bridge"
        "threshold_policy" -> "Adaptive threshold policy"
        "appflow_paper_policy" -> "AppFlow paper policy"
        else -> replace('_', ' ').replace('+', ' ').trim()
    }
}

private fun String.displayMode(): String = replace('_', ' ')

private fun String.splitPackages(): List<String> {
    return split("|").filter { it.isNotBlank() }
}

data class SystemStatusSnapshot(
    val bridgeName: String,
    val policyName: String,
    val reclaimMode: String,
    val keepAlivePackages: List<String>,
    val prewarmPackages: List<String>,
    val hintPackages: List<String>,
    val protectedPackages: List<String>,
    val deferredKillPackages: List<String>,
    val killCandidatePackages: List<String>,
    val preloadBudgetMb: Int,
    val predictedLaunchBenefitMs: Long,
    val rationale: String,
    val executionTimestamp: Long,
    val summaryText: String,
)
