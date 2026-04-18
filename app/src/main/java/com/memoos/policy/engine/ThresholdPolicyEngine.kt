package com.memoos.policy.engine

import com.memoos.core.config.MemoConfig
import com.memoos.core.model.PredictionBatch
import com.memoos.core.model.ResourceDecision
import com.memoos.policy.api.PolicyContext
import com.memoos.policy.api.PolicyEngine
import com.memoos.system.bridge.NativeScoreBridge

class ThresholdPolicyEngine : PolicyEngine {
    override val name: String = "threshold_policy"

    override fun evaluate(batch: PredictionBatch, config: MemoConfig, context: PolicyContext): ResourceDecision {
        val ranked = batch.predictions
            .sortedByDescending { it.score }
            .take(config.topK)
        if (ranked.isEmpty()) {
            return ResourceDecision(
                keepAlivePackages = emptyList(),
                prewarmPackages = emptyList(),
                hintPackages = emptyList(),
                decisionTimestamp = batch.generatedAt,
                policyName = name,
            )
        }
        val mergedThreshold = NativeScoreBridge.mergeThresholds(
            floatArrayOf(config.keepAliveThreshold, config.prewarmThreshold, config.hintThreshold),
        )
        val top = ranked.first()
        val secondScore = ranked.getOrNull(1)?.score ?: 0f
        val dominanceGap = top.score - secondScore

        val adaptiveKeepThreshold = when {
            dominanceGap >= 0.30f -> config.keepAliveThreshold - 0.12f
            dominanceGap >= 0.18f -> config.keepAliveThreshold - 0.06f
            else -> config.keepAliveThreshold
        }.coerceAtLeast(mergedThreshold + 0.05f)

        val adaptivePrewarmThreshold = when {
            batch.historySize >= 12 -> config.prewarmThreshold - 0.05f
            batch.historySize >= 8 -> config.prewarmThreshold - 0.03f
            else -> config.prewarmThreshold
        }.coerceAtLeast(config.hintThreshold + 0.05f)
        val opportunisticPrewarmPackage = ranked.getOrNull(1)
            ?.takeIf { batch.historySize >= 4 && top.score >= 0.60f && it.score >= 0.08f }
            ?.packageName

        val keepAlive = if (top.score >= adaptiveKeepThreshold || dominanceGap >= 0.22f) {
            listOf(top.packageName)
        } else {
            emptyList()
        }

        val prewarmBudget = when {
            ranked.size <= 1 -> 0
            dominanceGap < 0.08f -> 2
            secondScore >= adaptivePrewarmThreshold -> 1
            batch.historySize >= 5 && top.score >= 0.60f && secondScore >= 0.12f -> 1
            else -> 0
        }

        val prewarm = ranked
            .filterNot { it.packageName in keepAlive }
            .filter { prediction ->
                prediction.score >= adaptivePrewarmThreshold || prediction.packageName == opportunisticPrewarmPackage
            }
            .take(prewarmBudget)
            .map { it.packageName }

        val adaptiveHintThreshold = minOf(config.hintThreshold, mergedThreshold)
        val hints = ranked
            .filterNot { it.packageName in keepAlive || it.packageName in prewarm }
            .filterIndexed { index, prediction ->
                prediction.score >= adaptiveHintThreshold || index == 0
            }
            .map { it.packageName }

        return ResourceDecision(
            keepAlivePackages = keepAlive,
            prewarmPackages = prewarm,
            hintPackages = hints,
            decisionTimestamp = batch.generatedAt,
            policyName = name,
        )
    }
}
