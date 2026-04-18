package com.memoos.evaluation.recorder

import com.memoos.core.config.ExecutionMode
import com.memoos.core.model.ExperimentRecord
import com.memoos.core.model.PredictionBatch
import com.memoos.core.model.ResourceDecision
import com.memoos.data.repository.ExperimentRepository
import com.memoos.system.monitor.BatteryMonitor
import com.memoos.system.monitor.MemoryMonitor

class ExperimentRecorder(
    private val experimentRepository: ExperimentRepository,
    private val memoryMonitor: MemoryMonitor,
    private val batteryMonitor: BatteryMonitor,
) {
    suspend fun recordPredictionOutcome(
        mode: ExecutionMode,
        datasetName: String?,
        batch: PredictionBatch,
        decision: ResourceDecision,
        actualNextApp: String?,
        observationTimestamp: Long,
        launchLatencyMs: Long? = null,
        memorySnapshotRef: String? = null,
        batterySnapshotRef: String? = null,
    ): ExperimentRecord {
        val predictedTop3 = batch.predictions.take(3).map { it.packageName }
        val record = ExperimentRecord(
            mode = mode.name,
            datasetName = datasetName,
            predictorName = batch.predictorName,
            policyName = decision.policyName,
            predictedTop3 = predictedTop3,
            actualNextApp = actualNextApp,
            hitAt1 = predictedTop3.firstOrNull() == actualNextApp,
            hitAt3 = actualNextApp != null && actualNextApp in predictedTop3,
            predictionTimestamp = batch.generatedAt,
            observationTimestamp = observationTimestamp,
            keepAliveCount = decision.keepAlivePackages.size,
            prewarmCount = decision.prewarmPackages.size,
            hintCount = decision.hintPackages.size,
            launchLatencyMs = launchLatencyMs,
            memorySnapshotRef = memorySnapshotRef ?: memoryMonitor.snapshotRef(),
            batterySnapshotRef = batterySnapshotRef ?: batteryMonitor.snapshotRef(),
        )
        experimentRepository.insert(record)
        return record
    }
}
