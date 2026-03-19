package com.memoos.orchestration

import android.util.Log
import com.memoos.core.config.MemoConfig
import com.memoos.core.model.PredictionBatch
import com.memoos.data.repository.SystemStatusRepository
import com.memoos.policy.service.ResourcePolicyService
import com.memoos.system.bridge.SystemExecutionReport
import com.memoos.system.service.MemoSystemServiceFacade

class PolicyPipeline(
    private val resourcePolicyService: ResourcePolicyService,
    private val memoSystemServiceFacade: MemoSystemServiceFacade,
    private val systemStatusRepository: SystemStatusRepository,
) {
    fun run(batch: PredictionBatch, config: MemoConfig): PolicyExecutionResult {
        val decision = resourcePolicyService.decide(batch, config)
        val report = memoSystemServiceFacade.apply(decision, config.nativeBridgeEnabled)
        systemStatusRepository.record(decision, report)
        Log.i(
            "MemoOS",
            "policy.execute bridge=${report.bridgeName} retained=${report.retainedPackages.size} prewarmed=${report.prewarmedPackages.size} hinted=${report.hintedPackages.size}",
        )
        return PolicyExecutionResult(decision = decision, report = report)
    }
}

data class PolicyExecutionResult(
    val decision: com.memoos.core.model.ResourceDecision,
    val report: SystemExecutionReport,
)
