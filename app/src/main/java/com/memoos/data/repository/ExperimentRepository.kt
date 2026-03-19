package com.memoos.data.repository

import com.memoos.core.model.ExperimentRecord
import com.memoos.data.storage.dao.ExperimentRecordDao
import com.memoos.data.storage.entity.ExperimentRecordEntity

class ExperimentRepository(
    private val dao: ExperimentRecordDao,
) {
    suspend fun insert(record: ExperimentRecord) {
        dao.insert(
            ExperimentRecordEntity(
                mode = record.mode,
                datasetName = record.datasetName,
                predictorName = record.predictorName,
                policyName = record.policyName,
                predictedTop3 = record.predictedTop3.joinToString("|"),
                actualNextApp = record.actualNextApp,
                hitAt1 = record.hitAt1,
                hitAt3 = record.hitAt3,
                predictionTimestamp = record.predictionTimestamp,
                observationTimestamp = record.observationTimestamp,
                keepAliveCount = record.keepAliveCount,
                prewarmCount = record.prewarmCount,
                hintCount = record.hintCount,
                launchLatencyMs = record.launchLatencyMs,
                memorySnapshotRef = record.memorySnapshotRef,
                batterySnapshotRef = record.batterySnapshotRef,
            ),
        )
    }

    suspend fun latest(limit: Int): List<ExperimentRecord> {
        return dao.latest(limit).map { entity ->
            ExperimentRecord(
                mode = entity.mode,
                datasetName = entity.datasetName,
                predictorName = entity.predictorName,
                policyName = entity.policyName,
                predictedTop3 = entity.predictedTop3.split("|").filter { it.isNotBlank() },
                actualNextApp = entity.actualNextApp,
                hitAt1 = entity.hitAt1,
                hitAt3 = entity.hitAt3,
                predictionTimestamp = entity.predictionTimestamp,
                observationTimestamp = entity.observationTimestamp,
                keepAliveCount = entity.keepAliveCount,
                prewarmCount = entity.prewarmCount,
                hintCount = entity.hintCount,
                launchLatencyMs = entity.launchLatencyMs,
                memorySnapshotRef = entity.memorySnapshotRef,
                batterySnapshotRef = entity.batterySnapshotRef,
            )
        }
    }

    suspend fun resolveLatestPendingOnline(actualNextApp: String, observationTimestamp: Long): Boolean {
        val pending = dao.latestPending("ONLINE_DEVICE") ?: return false
        val predicted = pending.predictedTop3.split("|").filter { it.isNotBlank() }
        dao.resolvePending(
            id = pending.id,
            actualNextApp = actualNextApp,
            hitAt1 = predicted.firstOrNull() == actualNextApp,
            hitAt3 = actualNextApp in predicted,
            observationTimestamp = observationTimestamp,
        )
        return true
    }
}
