package com.memoos.predictor.engine

import com.memoos.core.config.MemoConfig
import com.memoos.core.model.AppEvent
import com.memoos.core.model.PredictionBatch
import com.memoos.predictor.api.Predictor

class SequencePredictor : Predictor {
    override val name: String = "sequence_predictor"

    override suspend fun predict(history: List<AppEvent>, config: MemoConfig): PredictionBatch {
        return PredictionBatch(
            predictions = emptyList(),
            generatedAt = history.maxOfOrNull { it.timestamp } ?: 0L,
            predictorName = name,
            historySize = history.size,
        )
    }
}
