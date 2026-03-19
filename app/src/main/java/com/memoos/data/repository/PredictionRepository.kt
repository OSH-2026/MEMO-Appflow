package com.memoos.data.repository

import android.content.SharedPreferences
import com.memoos.core.model.Prediction
import com.memoos.core.model.PredictionBatch
import com.memoos.data.storage.dao.PredictionDao
import com.memoos.data.storage.entity.PredictionEntity

class PredictionRepository(
    private val dao: PredictionDao,
    private val widgetStateStore: WidgetStateStore,
) {
    suspend fun save(batch: PredictionBatch) {
        dao.deleteBatch(batch.generatedAt)
        dao.insertAll(
            batch.predictions.map {
                PredictionEntity(
                    packageName = it.packageName,
                    score = it.score,
                    rank = it.rank,
                    predictionTimestamp = it.predictionTimestamp,
                    batchTimestamp = batch.generatedAt,
                    predictorName = batch.predictorName,
                )
            },
        )
        widgetStateStore.write(batch)
    }

    suspend fun latestBatch(): PredictionBatch? {
        val batchTimestamp = dao.latestBatchTimestamp() ?: return null
        val rows = dao.byBatch(batchTimestamp)
        if (rows.isEmpty()) return null
        return PredictionBatch(
            predictions = rows.map {
                Prediction(
                    packageName = it.packageName,
                    score = it.score,
                    rank = it.rank,
                    predictionTimestamp = it.predictionTimestamp,
                )
            },
            generatedAt = batchTimestamp,
            predictorName = rows.first().predictorName,
            historySize = rows.size,
        )
    }

    fun widgetSnapshot(): WidgetSnapshot = widgetStateStore.read()
}

class WidgetStateStore(
    private val sharedPreferences: SharedPreferences,
) {
    fun write(batch: PredictionBatch) {
        val joined = batch.predictions.joinToString("|") { prediction ->
            listOf(prediction.packageName, prediction.score, prediction.rank).joinToString(",")
        }
        sharedPreferences.edit()
            .putString(KEY_PREDICTIONS, joined)
            .putString(KEY_STATUS, "Predictor=${batch.predictorName} @ ${batch.generatedAt}")
            .apply()
    }

    fun read(): WidgetSnapshot {
        val predictions = sharedPreferences.getString(KEY_PREDICTIONS, "").orEmpty()
            .split("|")
            .filter { it.isNotBlank() }
            .mapNotNull { row ->
                val cells = row.split(",")
                val pkg = cells.getOrNull(0) ?: return@mapNotNull null
                val score = cells.getOrNull(1)?.toFloatOrNull() ?: 0f
                val rank = cells.getOrNull(2)?.toIntOrNull() ?: 0
                Prediction(pkg, score, rank, 0L)
            }
        return WidgetSnapshot(
            predictions = predictions,
            status = sharedPreferences.getString(KEY_STATUS, "No predictions yet").orEmpty(),
        )
    }

    companion object {
        private const val KEY_PREDICTIONS = "memo_widget_predictions"
        private const val KEY_STATUS = "memo_widget_status"
    }
}

data class WidgetSnapshot(
    val predictions: List<Prediction>,
    val status: String,
)
