package com.memoos.policy.api

import com.memoos.core.config.MemoConfig
import com.memoos.core.model.PredictionBatch
import com.memoos.core.model.ResourceDecision

interface PolicyEngine {
    val name: String
    fun evaluate(
        batch: PredictionBatch,
        config: MemoConfig,
        context: PolicyContext = PolicyContext(),
    ): ResourceDecision
}
