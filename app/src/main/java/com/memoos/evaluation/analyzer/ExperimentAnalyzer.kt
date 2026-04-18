package com.memoos.evaluation.analyzer

import com.memoos.data.repository.ExperimentRepository
import com.memoos.evaluation.metrics.PredictionMetricSummary
import com.memoos.evaluation.metrics.PredictionMetrics

class ExperimentAnalyzer(
    private val repository: ExperimentRepository,
    private val predictionMetrics: PredictionMetrics,
) {
    suspend fun summarizeRecent(limit: Int = 50): PredictionMetricSummary {
        return predictionMetrics.summarize(repository.latest(limit))
    }
}
