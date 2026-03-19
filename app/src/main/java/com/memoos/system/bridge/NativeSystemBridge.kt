package com.memoos.system.bridge

import android.util.Log
import com.memoos.core.model.ResourceDecision

class NativeSystemBridge : SystemBridge {
    override fun execute(decision: ResourceDecision): SystemExecutionReport {
        val ranking = decision.prewarmPackages.mapIndexed { index, _ ->
            (decision.prewarmPackages.size - index).toFloat()
        }.toFloatArray()
        val normalized = if (ranking.isEmpty()) emptyList() else {
            NativeScoreBridge.normalize(ranking)
            ranking.toList()
        }
        Log.d("MemoOS", "Native bridge placeholder normalized=$normalized policy=${decision.policyName}")
        return SystemExecutionReport(
            retainedPackages = decision.keepAlivePackages,
            prewarmedPackages = decision.prewarmPackages,
            hintedPackages = decision.hintPackages,
            bridgeName = "native_system_bridge",
            executionTimestamp = System.currentTimeMillis(),
        )
    }
}
