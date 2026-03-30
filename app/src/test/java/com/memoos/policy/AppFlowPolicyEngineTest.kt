package com.memoos.policy

import com.memoos.appflow.profile.LaunchProfileRepository
import com.memoos.core.config.MemoConfig
import com.memoos.core.model.AppEvent
import com.memoos.core.model.Prediction
import com.memoos.core.model.PredictionBatch
import com.memoos.core.model.SourceType
import com.memoos.policy.api.PolicyContext
import com.memoos.policy.engine.AppFlowPolicyEngine
import com.memoos.system.monitor.MemorySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFlowPolicyEngineTest {
    @Test
    fun usesRecentDeferralAndProtectedPreloadPlanning() {
        val now = 1_000_000L
        val engine = AppFlowPolicyEngine(LaunchProfileRepository())
        val decision = engine.evaluate(
            batch = PredictionBatch(
                predictions = listOf(
                    Prediction("pkg.top", 0.60f, 1, now),
                    Prediction("pkg.secondary", 0.42f, 2, now),
                    Prediction("pkg.third", 0.30f, 3, now),
                ),
                generatedAt = now,
                predictorName = "markov",
                historySize = 8,
            ),
            config = MemoConfig(),
            context = PolicyContext(
                memorySnapshot = MemorySnapshot(
                    availableMb = 1200,
                    totalMb = 8000,
                    thresholdMb = 300,
                    lowMemory = false,
                ),
                recentHistory = listOf(
                    event("pkg.recent", now - 5 * 60_000L),
                    event("pkg.old_heavy", now - 60 * 60_000L),
                    event("pkg.secondary", now - 10 * 60_000L),
                ),
                nowMillis = now,
            ),
        )

        assertEquals("appflow_paper_policy", decision.policyName)
        assertTrue(decision.prewarmPackages.contains("pkg.top"))
        assertTrue(decision.protectedPackages.contains("pkg.top"))
        assertTrue(decision.deferredKillPackages.contains("pkg.recent"))
        assertFalse(decision.killCandidatePackages.contains("pkg.recent"))
    }

    private fun event(packageName: String, timestamp: Long): AppEvent {
        return AppEvent(
            packageName = packageName,
            timestamp = timestamp,
            dayOfWeek = 1,
            hourOfDay = 10,
            sourceType = SourceType.REPLAY,
        )
    }
}
