package com.memoos.predictor.service

import com.memoos.core.config.MemoConfig
import com.memoos.core.config.PredictorType
import com.memoos.core.model.AppEvent
import com.memoos.core.model.PredictionBatch
import com.memoos.data.repository.PredictionRepository
import com.memoos.predictor.api.Predictor

class PredictionService(
    private val predictors: Map<PredictorType, Predictor>,
    private val predictionRepository: PredictionRepository,
) {
    suspend fun predictAndStore(history: List<AppEvent>, config: MemoConfig): PredictionBatch {
        val predictor = predictors[config.predictorType] ?: predictors.getValue(PredictorType.MARKOV)
        val batch = predictor.predict(history, config)
        predictionRepository.save(batch)
        return batch
    }
}
