package com.memoos.predictor.engine

import com.memoos.core.config.MemoConfig
import com.memoos.core.model.AppEvent
import com.memoos.core.model.Prediction
import com.memoos.core.model.PredictionBatch
import com.memoos.core.time.TimeProvider
import com.memoos.predictor.api.Predictor
import com.memoos.predictor.trainer.TransitionTrainer
import com.memoos.system.bridge.NativeScoreBridge

class MarkovPredictor(
    private val transitionTrainer: TransitionTrainer,
    private val timeProvider: TimeProvider,
    private val useNativeNormalization: Boolean = true,
) : Predictor {

    override val name: String = "markov_predictor"

    override suspend fun predict(history: List<AppEvent>, config: MemoConfig): PredictionBatch {
        val trimmed = history.sortedByDescending { it.timestamp }.take(config.historyWindowSize).sortedBy { it.timestamp }
        val generatedAt = timeProvider.now()
        if (trimmed.isEmpty()) return PredictionBatch(emptyList(), generatedAt, name, 0)

        val transitionMatrix = transitionTrainer.train(trimmed)
        val lastPackage = trimmed.last().packageName
        val nextCounts = transitionMatrix[lastPackage].orEmpty()
        val recentFrequency = trimmed
            .dropLast(1)
            .groupingBy { it.packageName }
            .eachCount()
            .toList()
            .sortedByDescending { (_, count) -> count }

        val weightedScores = linkedMapOf<String, Float>()
        if (nextCounts.isNotEmpty()) {
            val total = nextCounts.values.sum().coerceAtLeast(1)
            nextCounts.entries
                .sortedByDescending { it.value }
                .forEach { entry ->
                    weightedScores[entry.key] = entry.value.toFloat() / total
                }
        }

        if (weightedScores.size < config.topK) {
            val totalRecent = recentFrequency.sumOf { it.second }.coerceAtLeast(1)
            recentFrequency.forEach { (packageName, count) ->
                if (packageName in weightedScores) return@forEach
                if (packageName == lastPackage && weightedScores.size < config.topK) {
                    weightedScores[packageName] = (count.toFloat() / totalRecent) * 0.20f
                    return@forEach
                }
                if (packageName == lastPackage) return@forEach
                weightedScores[packageName] = (count.toFloat() / totalRecent) * 0.35f
            }
        }

        val packages = weightedScores.keys.toList()
        val rawScores = packages.map { weightedScores.getValue(it) }.toFloatArray()
        val normalizedScores = if (useNativeNormalization && rawScores.isNotEmpty()) {
            NativeScoreBridge.normalize(rawScores)
            rawScores
        } else {
            rawScores
        }
        val primaryPredictions = packages
            .mapIndexed { index, packageName ->
                Prediction(packageName, normalizedScores.getOrElse(index) { 0f }, 0, generatedAt)
            }
            .sortedByDescending { it.score }
            .take(config.topK)
        val fallbackPredictions = trimmed
            .asReversed()
            .map { it.packageName }
            .distinct()
            .filterNot { candidate -> primaryPredictions.any { it.packageName == candidate } }
            .take((config.topK - primaryPredictions.size).coerceAtLeast(0))
            .mapIndexed { index, packageName ->
                Prediction(
                    packageName = packageName,
                    score = 0.12f - (index * 0.01f),
                    rank = 0,
                    predictionTimestamp = generatedAt,
                )
            }
        val predictions = (primaryPredictions + fallbackPredictions)
            .take(config.topK)
            .mapIndexed { index, prediction -> prediction.copy(rank = index + 1) }

        return PredictionBatch(predictions, generatedAt, name, trimmed.size)
    }
}
