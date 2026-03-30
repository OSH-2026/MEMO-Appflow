package com.memoos.system.bridge

import android.util.Log
import com.memoos.core.model.ResourceDecision
import com.memoos.system.memory.RetentionController
import com.memoos.system.prewarm.PrewarmController

class AppLevelSystemBridge(
    private val prewarmController: PrewarmController,
    private val retentionController: RetentionController,
) : SystemBridge {
    override fun execute(decision: ResourceDecision): SystemExecutionReport {
        val retained = retentionController.retain(decision.keepAlivePackages)
        val prewarmed = prewarmController.prewarm(decision.prewarmPackages)
        Log.d(
            "MemoOS",
            "App bridge mode=${decision.reclaimMode} retained=$retained prewarmed=$prewarmed protected=${decision.protectedPackages} deferredKill=${decision.deferredKillPackages} hints=${decision.hintPackages}",
        )
        return SystemExecutionReport(
            retainedPackages = retained,
            prewarmedPackages = prewarmed,
            hintedPackages = decision.hintPackages,
            bridgeName = "app_level_system_bridge",
            executionTimestamp = System.currentTimeMillis(),
        )
    }
}
