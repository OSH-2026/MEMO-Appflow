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
            .putInt(KEY_KEEP_ALIVE_COUNT, report.retainedPackages.size)
            .putInt(KEY_PREWARM_COUNT, report.prewarmedPackages.size)
            .putInt(KEY_HINT_COUNT, report.hintedPackages.size)
            .putString(KEY_KEEP_ALIVE_PACKAGES, report.retainedPackages.joinToString("|"))
            .putString(KEY_PREWARM_PACKAGES, report.prewarmedPackages.joinToString("|"))
            .putString(KEY_HINT_PACKAGES, report.hintedPackages.joinToString("|"))
            .putLong(KEY_EXECUTION_TIMESTAMP, report.executionTimestamp)
            .apply()
    }

    fun read(): SystemStatusSnapshot {
        val bridgeName = sharedPreferences.getString(KEY_BRIDGE_NAME, "").orEmpty()
        val policyName = sharedPreferences.getString(KEY_POLICY_NAME, "").orEmpty()
        val keepAlivePackages = sharedPreferences.getString(KEY_KEEP_ALIVE_PACKAGES, "").orEmpty().splitPackages()
        val prewarmPackages = sharedPreferences.getString(KEY_PREWARM_PACKAGES, "").orEmpty().splitPackages()
        val hintPackages = sharedPreferences.getString(KEY_HINT_PACKAGES, "").orEmpty().splitPackages()
        val executionTimestamp = sharedPreferences.getLong(KEY_EXECUTION_TIMESTAMP, 0L)
        val summaryText = if (bridgeName.isBlank()) {
            "System idle"
        } else {
            "Bridge ${bridgeName.displayName()} | Policy ${policyName.displayName()} | Keep ${keepAlivePackages.size} | Prewarm ${prewarmPackages.size} | Hint ${hintPackages.size}"
        }
        return SystemStatusSnapshot(
            bridgeName = bridgeName,
            policyName = policyName,
            keepAlivePackages = keepAlivePackages,
            prewarmPackages = prewarmPackages,
            hintPackages = hintPackages,
            executionTimestamp = executionTimestamp,
            summaryText = summaryText,
        )
    }

    companion object {
        private const val KEY_BRIDGE_NAME = "system_status.bridge_name"
        private const val KEY_POLICY_NAME = "system_status.policy_name"
        private const val KEY_KEEP_ALIVE_COUNT = "system_status.keep_alive_count"
        private const val KEY_PREWARM_COUNT = "system_status.prewarm_count"
        private const val KEY_HINT_COUNT = "system_status.hint_count"
        private const val KEY_KEEP_ALIVE_PACKAGES = "system_status.keep_alive_packages"
        private const val KEY_PREWARM_PACKAGES = "system_status.prewarm_packages"
        private const val KEY_HINT_PACKAGES = "system_status.hint_packages"
        private const val KEY_EXECUTION_TIMESTAMP = "system_status.execution_timestamp"
    }
}

private fun String.displayName(): String {
    return when (this) {
        "app_level_system_bridge+native_system_bridge" -> "App bridge + native bridge"
        "app_level_system_bridge" -> "App bridge"
        "native_system_bridge" -> "Native bridge"
        "threshold_policy" -> "Adaptive threshold policy"
        else -> replace('_', ' ').replace('+', ' ').trim()
    }
}

private fun String.splitPackages(): List<String> {
    return split("|").filter { it.isNotBlank() }
}

data class SystemStatusSnapshot(
    val bridgeName: String,
    val policyName: String,
    val keepAlivePackages: List<String>,
    val prewarmPackages: List<String>,
    val hintPackages: List<String>,
    val executionTimestamp: Long,
    val summaryText: String,
)
