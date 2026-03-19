package com.memoos.evaluation.metrics

import com.memoos.core.model.ExperimentRecord

data class PredictionMetricSummary(
    val hitAt1: Double,
    val hitAt3: Double,
    val mrr: Double,
    val evaluatedCount: Int,
    val pendingCount: Int,
)

class PredictionMetrics {
    fun summarize(records: List<ExperimentRecord>): PredictionMetricSummary {
        if (records.isEmpty()) return PredictionMetricSummary(0.0, 0.0, 0.0, 0, 0)
        val evaluated = records.filter { it.actualNextApp != null }
        val pending = records.size - evaluated.size
        if (evaluated.isEmpty()) {
            return PredictionMetricSummary(
                hitAt1 = 0.0,
                hitAt3 = 0.0,
                mrr = 0.0,
                evaluatedCount = 0,
                pendingCount = pending,
            )
        }
        val size = evaluated.size.toDouble()
        val hit1 = evaluated.count { it.hitAt1 } / size
        val hit3 = evaluated.count { it.hitAt3 } / size
        val reciprocalRank = evaluated.sumOf { record ->
            val actual = record.actualNextApp ?: return@sumOf 0.0
            val index = record.predictedTop3.indexOf(actual)
            if (index >= 0) 1.0 / (index + 1) else 0.0
        } / size
        return PredictionMetricSummary(hit1, hit3, reciprocalRank, evaluated.size, pending)
    }
}
