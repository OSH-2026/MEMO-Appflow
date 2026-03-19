package com.memoos.policy

import com.memoos.core.config.MemoConfig
import com.memoos.core.model.Prediction
import com.memoos.core.model.PredictionBatch
import com.memoos.policy.engine.ThresholdPolicyEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class ThresholdPolicyReplayIntegrationTest {
    @Test
    fun thresholdPolicyProducesReplayDecisionCounts() {
        val decision = ThresholdPolicyEngine().evaluate(
            PredictionBatch(
                predictions = listOf(
                    Prediction("pkg.keep", 0.8f, 1, 100L),
                    Prediction("pkg.prewarm", 0.6f, 2, 100L),
                    Prediction("pkg.hint", 0.36f, 3, 100L),
                ),
                generatedAt = 100L,
                predictorName = "markov",
                historySize = 12,
            ),
            MemoConfig(),
        )

        assertEquals(1, decision.keepAlivePackages.size)
        assertEquals(1, decision.prewarmPackages.size)
        assertEquals(1, decision.hintPackages.size)
    }
}
