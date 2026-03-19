package com.memoos.policy.engine

import com.memoos.core.config.MemoConfig
import com.memoos.core.model.PredictionBatch
import com.memoos.core.model.ResourceDecision
import com.memoos.policy.api.PolicyEngine

class EnergyAwarePolicyEngine : PolicyEngine {
    override val name: String = "energy_aware_policy"

    override fun evaluate(batch: PredictionBatch, config: MemoConfig): ResourceDecision {
        val highConfidence = batch.predictions.filter { it.score >= config.keepAliveThreshold }
        return ResourceDecision(
            keepAlivePackages = emptyList(),
            prewarmPackages = highConfidence.take(1).map { it.packageName },
            hintPackages = batch.predictions.take(config.topK).map { it.packageName },
            decisionTimestamp = batch.generatedAt,
            policyName = name,
        )
    }
}
