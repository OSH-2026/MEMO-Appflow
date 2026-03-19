package com.memoos.policy.service

import com.memoos.core.config.MemoConfig
import com.memoos.core.model.PredictionBatch
import com.memoos.core.model.ResourceDecision
import com.memoos.policy.api.PolicyEngine

class ResourcePolicyService(
    private val policyEngine: PolicyEngine,
) {
    fun decide(batch: PredictionBatch, config: MemoConfig): ResourceDecision {
        return policyEngine.evaluate(batch, config)
    }
}
