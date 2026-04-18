package com.memoos.orchestration

import com.memoos.core.config.ExecutionMode
import com.memoos.core.model.PredictionBatch
import com.memoos.core.model.ResourceDecision
import com.memoos.evaluation.recorder.ExperimentRecorder

class EvaluationPipeline(
    private val experimentRecorder: ExperimentRecorder,
) {
    suspend fun run(
        mode: ExecutionMode,
        datasetName: String?,
        batch: PredictionBatch,
        decision: ResourceDecision,
        actualNextApp: String?,
        observationTimestamp: Long,
        memorySnapshotRef: String? = null,
        batterySnapshotRef: String? = null,
    ) {
        experimentRecorder.recordPredictionOutcome(
            mode = mode,
            datasetName = datasetName,
            batch = batch,
            decision = decision,
            actualNextApp = actualNextApp,
            observationTimestamp = observationTimestamp,
            memorySnapshotRef = memorySnapshotRef,
            batterySnapshotRef = batterySnapshotRef,
        )
    }
}
