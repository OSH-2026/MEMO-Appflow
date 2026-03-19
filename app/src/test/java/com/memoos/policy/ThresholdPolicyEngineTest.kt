package com.memoos.policy

import com.memoos.core.config.MemoConfig
import com.memoos.core.model.Prediction
import com.memoos.core.model.PredictionBatch
import com.memoos.policy.engine.ThresholdPolicyEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class ThresholdPolicyEngineTest {
    @Test
    fun mapsPredictionsIntoDecisionBuckets() {
        val engine = ThresholdPolicyEngine()
        val batch = PredictionBatch(
            predictions = listOf(
                Prediction("pkg.a", 0.8f, 1, 100L),
                Prediction("pkg.b", 0.6f, 2, 100L),
                Prediction("pkg.c", 0.4f, 3, 100L),
            ),
            generatedAt = 100L,
            predictorName = "markov_predictor",
            historySize = 10,
        )

        val decision = engine.evaluate(batch, MemoConfig())

        assertEquals(listOf("pkg.a"), decision.keepAlivePackages)
        assertEquals(listOf("pkg.b"), decision.prewarmPackages)
        assertEquals(listOf("pkg.c"), decision.hintPackages)
    }
}
