package com.memoos.orchestration

import com.memoos.core.config.MemoConfig
import com.memoos.core.model.AppEvent
import com.memoos.core.model.PredictionBatch
import com.memoos.data.repository.AppEventRepository
import com.memoos.predictor.service.PredictionService

class PredictPipeline(
    private val appEventRepository: AppEventRepository,
    private val predictionService: PredictionService,
) {
    suspend fun run(config: MemoConfig): PredictionBatch {
        val history = appEventRepository.recent(config.historyWindowSize)
        return run(history, config)
    }

    suspend fun run(history: List<AppEvent>, config: MemoConfig): PredictionBatch {
        return predictionService.predictAndStore(history, config)
    }
}
