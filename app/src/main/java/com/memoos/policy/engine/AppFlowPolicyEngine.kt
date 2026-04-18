package com.memoos.policy.engine

import com.memoos.appflow.model.KillCandidate
import com.memoos.appflow.model.PressureMode
import com.memoos.appflow.model.wireName
import com.memoos.appflow.profile.LaunchProfileRepository
import com.memoos.core.config.MemoConfig
import com.memoos.core.model.PredictionBatch
import com.memoos.core.model.ResourceDecision
import com.memoos.policy.api.PolicyContext
import com.memoos.policy.api.PolicyEngine
import kotlin.math.max

class AppFlowPolicyEngine(
    private val launchProfileRepository: LaunchProfileRepository,
) : PolicyEngine {
    override val name: String = "appflow_paper_policy"

    override fun evaluate(
        batch: PredictionBatch,
        config: MemoConfig,
        context: PolicyContext,
    ): ResourceDecision {
        val ranked = batch.predictions
            .sortedByDescending { it.score }
            .take(max(config.topK, 3))
        if (ranked.isEmpty()) {
            return ResourceDecision(
                keepAlivePackages = emptyList(),
                prewarmPackages = emptyList(),
                hintPackages = emptyList(),
                decisionTimestamp = batch.generatedAt,
                policyName = name,
                reclaimMode = PressureMode.NORMAL.wireName(),
                rationale = "No ranked prediction available for AppFlow scheduling.",
            )
        }

        val pressureMode = resolvePressureMode(context, config)
        val recentThreshold = context.nowMillis - config.appFlowRecentUseWindowMinutes * 60_000L
        val recentEvents = context.recentHistory.sortedByDescending { it.timestamp }
        val recentUnique = recentEvents.distinctBy { it.packageName }
        val recentPackages = recentEvents
            .filter { it.timestamp >= recentThreshold }
            .map { it.packageName }
            .toSet()

        val top = ranked.first()
        val topProfile = launchProfileRepository.profileFor(top.packageName)
        val preloadBudgetMb = when (pressureMode) {
            PressureMode.EFFICIENCY_FIRST -> minOf(config.appFlowPreloadBudgetMb, 64)
            PressureMode.REBALANCE -> minOf(config.appFlowPreloadBudgetMb, 84)
            PressureMode.NORMAL -> config.appFlowPreloadBudgetMb
        }

        val keepAlive = if (
            top.score >= config.keepAliveThreshold - 0.08f ||
            top.packageName in recentPackages
        ) {
            listOf(top.packageName)
        } else {
            emptyList()
        }

        val maxPrewarmTargets = when (pressureMode) {
            PressureMode.EFFICIENCY_FIRST -> 1
            PressureMode.REBALANCE -> 2
            PressureMode.NORMAL -> minOf(3, ranked.size)
        }

        val prewarmPackages = mutableListOf<String>()
        val protectedPackages = mutableListOf<String>()
        var reservedBudgetMb = 0
        var predictedBenefitMs = 0L

        for (prediction in ranked) {
            if (prediction.packageName in keepAlive) continue
            if (prewarmPackages.size >= maxPrewarmTargets) break

            val profile = launchProfileRepository.profileFor(prediction.packageName)
            val packageBudgetMb = profile.beforeLaunchBudgetMb
            val shouldSchedule = when {
                prediction.rank == 1 -> true
                pressureMode == PressureMode.EFFICIENCY_FIRST -> false
                prediction.score >= config.prewarmThreshold - 0.10f -> true
                prediction.packageName in recentPackages && prediction.score >= config.hintThreshold -> true
                else -> false
            }
            if (!shouldSchedule) continue
            if (reservedBudgetMb + packageBudgetMb > preloadBudgetMb) continue

            prewarmPackages += prediction.packageName
            protectedPackages += prediction.packageName
            reservedBudgetMb += packageBudgetMb
            predictedBenefitMs += estimatedSavedMs(profile = profile, pressureMode = pressureMode)
        }

        if (prewarmPackages.isEmpty() && top.packageName !in keepAlive && topProfile.beforeLaunchBudgetMb <= preloadBudgetMb) {
            prewarmPackages += top.packageName
            protectedPackages += top.packageName
            reservedBudgetMb += topProfile.beforeLaunchBudgetMb
            predictedBenefitMs += estimatedSavedMs(profile = topProfile, pressureMode = pressureMode)
        }

        val deferredKillPackages = recentUnique
            .filter { it.timestamp >= recentThreshold }
            .map { it.packageName }
            .filterNot { it in keepAlive || it in prewarmPackages }
            .take(3)

        val killCandidates = recentUnique
            .filterNot { it.packageName in keepAlive || it.packageName in prewarmPackages || it.packageName in deferredKillPackages }
            .map { event ->
                val profile = launchProfileRepository.profileFor(event.packageName)
                KillCandidate(
                    packageName = event.packageName,
                    currentMemoryMb = profile.bloatedBackgroundMb,
                    relaunchBaselineMb = profile.baselineRelaunchMb,
                    reclaimBenefitMb = profile.reclaimBenefitMb,
                    lastUsedAt = event.timestamp,
                    recentlyUsed = event.timestamp >= recentThreshold,
                )
            }
            .filter { !it.recentlyUsed && it.reclaimBenefitMb > 0 }
            .sortedWith(compareByDescending<KillCandidate> { it.reclaimBenefitMb }.thenBy { it.lastUsedAt })
            .take(config.appFlowKillCandidateLimit)

        val hints = ranked
            .map { it.packageName }
            .filterNot { it in keepAlive || it in prewarmPackages }
            .take(config.topK)

        val rationale = buildString {
            append("AppFlow mode=").append(pressureMode.wireName())
            append(" budgetMb=").append(preloadBudgetMb)
            append(" top=").append(top.packageName)
            append(" cutoffKb=").append(topProfile.cutoffKb)
            append(" recent=").append(recentPackages.size)
            append(" killCandidates=").append(killCandidates.size)
        }

        return ResourceDecision(
            keepAlivePackages = keepAlive,
            prewarmPackages = prewarmPackages,
            hintPackages = hints,
            decisionTimestamp = batch.generatedAt,
            policyName = name,
            reclaimMode = pressureMode.wireName(),
            protectedPackages = protectedPackages,
            deferredKillPackages = deferredKillPackages,
            killCandidatePackages = killCandidates.map { it.packageName },
            preloadBudgetMb = preloadBudgetMb,
            predictedLaunchBenefitMs = predictedBenefitMs,
            rationale = rationale,
        )
    }

    private fun resolvePressureMode(context: PolicyContext, config: MemoConfig): PressureMode {
        val snapshot = context.memorySnapshot ?: return PressureMode.REBALANCE
        val burstWindowStart = context.nowMillis - config.appFlowBurstWindowMinutes * 60_000L
        val burstCount = context.recentHistory.count { it.timestamp >= burstWindowStart }
        val headroomMb = snapshot.availableMb - snapshot.thresholdMb

        return when {
            snapshot.lowMemory -> PressureMode.EFFICIENCY_FIRST
            snapshot.availableMb <= snapshot.thresholdMb -> PressureMode.EFFICIENCY_FIRST
            headroomMb <= 256 && burstCount >= 3 -> PressureMode.EFFICIENCY_FIRST
            snapshot.availableMb <= snapshot.thresholdMb * 2 -> PressureMode.REBALANCE
            burstCount >= 4 -> PressureMode.REBALANCE
            else -> PressureMode.NORMAL
        }
    }

    private fun estimatedSavedMs(
        profile: com.memoos.appflow.model.AppLaunchProfile,
        pressureMode: PressureMode,
    ): Long {
        val ratio = when (pressureMode) {
            PressureMode.EFFICIENCY_FIRST -> 0.22
            PressureMode.REBALANCE -> 0.28
            PressureMode.NORMAL -> 0.35
        }
        return (profile.coldLaunchMs * ratio).toLong().coerceAtLeast(120L)
    }
}
