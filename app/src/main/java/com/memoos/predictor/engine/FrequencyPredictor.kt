package com.memoos.predictor.engine

import com.memoos.core.config.MemoConfig
import com.memoos.core.model.AppEvent
import com.memoos.core.model.Prediction
import com.memoos.core.model.PredictionBatch
import com.memoos.core.time.TimeProvider
import com.memoos.predictor.api.Predictor

class FrequencyPredictor(
    private val timeProvider: TimeProvider,
) : Predictor {
    override val name: String = "frequency_predictor"

    override suspend fun predict(history: List<AppEvent>, config: MemoConfig): PredictionBatch {
        val generatedAt = timeProvider.now()
        val groups = history.groupingBy { it.packageName }.eachCount()
        val total = groups.values.sum().coerceAtLeast(1)
        val predictions = groups.entries
            .sortedByDescending { it.value }
            .take(config.topK)
            .mapIndexed { index, entry ->
                Prediction(entry.key, entry.value.toFloat() / total, index + 1, generatedAt)
            }
        return PredictionBatch(predictions, generatedAt, name, history.size)
    }
}
