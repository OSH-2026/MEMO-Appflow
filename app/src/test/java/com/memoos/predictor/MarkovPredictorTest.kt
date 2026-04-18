package com.memoos.predictor

import com.memoos.core.config.MemoConfig
import com.memoos.core.model.AppEvent
import com.memoos.core.model.SourceType
import com.memoos.core.time.TimeProvider
import com.memoos.predictor.engine.MarkovPredictor
import com.memoos.predictor.trainer.TransitionTrainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkovPredictorTest {
    @Test
    fun predictsMostLikelyNextApp() = runBlocking {
        val predictor = MarkovPredictor(
            transitionTrainer = TransitionTrainer(),
            timeProvider = object : TimeProvider {
                override fun now(): Long = 999L
            },
            useNativeNormalization = false,
        )
        val history = listOf(
            event("a", 1L),
            event("b", 2L),
            event("a", 3L),
            event("b", 4L),
            event("a", 5L),
            event("c", 6L),
            event("a", 7L),
        )

        val batch = predictor.predict(history, MemoConfig(topK = 3))

        assertEquals("markov_predictor", batch.predictorName)
        assertTrue(batch.predictions.isNotEmpty())
        assertEquals("b", batch.predictions.first().packageName)
    }

    private fun event(packageName: String, timestamp: Long) = AppEvent(
        packageName = packageName,
        timestamp = timestamp,
        dayOfWeek = 1,
        hourOfDay = 8,
        sourceType = SourceType.REPLAY,
    )
}
