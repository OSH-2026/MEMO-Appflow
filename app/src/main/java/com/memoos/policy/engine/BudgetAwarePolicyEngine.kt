package com.memoos.policy.engine

import com.memoos.core.config.MemoConfig
import com.memoos.core.model.PredictionBatch
import com.memoos.core.model.ResourceDecision
import com.memoos.policy.api.PolicyContext
import com.memoos.policy.api.PolicyEngine

class BudgetAwarePolicyEngine : PolicyEngine {
    override val name: String = "budget_aware_policy"

    override fun evaluate(batch: PredictionBatch, config: MemoConfig, context: PolicyContext): ResourceDecision {
        val capped = batch.predictions.sortedByDescending { it.score }.take(2)
        return ResourceDecision(
            keepAlivePackages = capped.take(1).map { it.packageName },
            prewarmPackages = capped.map { it.packageName },
            hintPackages = batch.predictions.take(config.topK).map { it.packageName },
            decisionTimestamp = batch.generatedAt,
            policyName = name,
        )
    }
}
