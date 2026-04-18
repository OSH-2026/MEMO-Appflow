package com.memoos.orchestration

import android.util.Log
import com.memoos.core.config.MemoConfig
import com.memoos.core.model.AppEvent
import com.memoos.core.model.PredictionBatch
import com.memoos.data.repository.AppEventRepository
import com.memoos.data.repository.SystemStatusRepository
import com.memoos.policy.service.ResourcePolicyService
import com.memoos.policy.api.PolicyContext
import com.memoos.system.bridge.SystemExecutionReport
import com.memoos.system.monitor.MemoryMonitor
import com.memoos.system.service.MemoSystemServiceFacade

class PolicyPipeline(
    private val resourcePolicyService: ResourcePolicyService,
    private val memoSystemServiceFacade: MemoSystemServiceFacade,
    private val systemStatusRepository: SystemStatusRepository,
    private val appEventRepository: AppEventRepository,
    private val memoryMonitor: MemoryMonitor,
) {
    suspend fun run(
        batch: PredictionBatch,
        config: MemoConfig,
        recentHistoryOverride: List<AppEvent> = emptyList(),
    ): PolicyExecutionResult {
        val recentHistory = if (recentHistoryOverride.isNotEmpty()) {
            recentHistoryOverride
        } else {
            appEventRepository.recent(maxOf(config.historyWindowSize * 12, 64))
        }
        val decision = resourcePolicyService.decide(
            batch = batch,
            config = config,
            context = PolicyContext(
                memorySnapshot = memoryMonitor.snapshot(),
                recentHistory = recentHistory,
                nowMillis = batch.generatedAt,
            ),
        )
        val report = memoSystemServiceFacade.apply(decision, config.nativeBridgeEnabled)
        systemStatusRepository.record(decision, report)
        Log.i(
            "MemoOS",
            "policy.execute bridge=${report.bridgeName} mode=${decision.reclaimMode} retained=${report.retainedPackages.size} prewarmed=${report.prewarmedPackages.size} hinted=${report.hintedPackages.size} protected=${decision.protectedPackages.size} deferredKill=${decision.deferredKillPackages.size}",
        )
        return PolicyExecutionResult(decision = decision, report = report)
    }
}

data class PolicyExecutionResult(
    val decision: com.memoos.core.model.ResourceDecision,
    val report: SystemExecutionReport,
)
