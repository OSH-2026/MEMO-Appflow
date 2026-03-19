package com.memoos.system.bridge

import com.memoos.core.model.ResourceDecision

interface SystemBridge {
    fun execute(decision: ResourceDecision): SystemExecutionReport
}

data class SystemExecutionReport(
    val retainedPackages: List<String>,
    val prewarmedPackages: List<String>,
    val hintedPackages: List<String>,
    val bridgeName: String,
    val executionTimestamp: Long,
)
