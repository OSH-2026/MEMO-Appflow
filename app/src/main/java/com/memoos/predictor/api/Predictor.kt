package com.memoos.predictor.api

import com.memoos.core.config.MemoConfig
import com.memoos.core.model.AppEvent
import com.memoos.core.model.PredictionBatch

interface Predictor {
    val name: String
    suspend fun predict(history: List<AppEvent>, config: MemoConfig): PredictionBatch
}
