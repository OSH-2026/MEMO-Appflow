package com.memoos.system.service

import com.memoos.core.model.ResourceDecision
import com.memoos.system.bridge.SystemBridge
import com.memoos.system.bridge.SystemExecutionReport

class MemoSystemServiceFacade(
    private val appLevelSystemBridge: SystemBridge,
    private val nativeSystemBridge: SystemBridge? = null,
) {
    fun apply(decision: ResourceDecision, useNativeBridge: Boolean): SystemExecutionReport {
        val nativeReport = if (useNativeBridge) nativeSystemBridge?.execute(decision) else null
        val appReport = appLevelSystemBridge.execute(decision)
        return if (nativeReport == null) {
            appReport
        } else {
            appReport.copy(
                bridgeName = "${appReport.bridgeName}+${nativeReport.bridgeName}",
                executionTimestamp = maxOf(appReport.executionTimestamp, nativeReport.executionTimestamp),
            )
        }
    }
}
