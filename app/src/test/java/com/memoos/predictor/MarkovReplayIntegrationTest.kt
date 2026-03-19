package com.memoos.predictor

import com.memoos.core.config.MemoConfig
import com.memoos.core.model.AppEvent
import com.memoos.core.model.SourceType
import com.memoos.core.time.TimeProvider
import com.memoos.data.replay.ReplayEventStream
import com.memoos.predictor.engine.MarkovPredictor
import com.memoos.predictor.trainer.TransitionTrainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkovReplayIntegrationTest {
    @Test
    fun markovPredictorConsumesReplayWindows() = runBlocking {
        val predictor = MarkovPredictor(
            transitionTrainer = TransitionTrainer(),
            timeProvider = object : TimeProvider {
                override fun now(): Long = 1234L
            },
            useNativeNormalization = false,
        )
        val events = listOf("a", "b", "a", "b", "a", "c").mapIndexed { index, pkg ->
            AppEvent(pkg, index.toLong() + 1L, 1, 8, SourceType.REPLAY)
        }
        val window = ReplayEventStream().temporalWindows(events, 4).first()

        val batch = predictor.predict(window.history, MemoConfig(historyWindowSize = 4))

        assertEquals("a", batch.predictions.first().packageName)
    }
}
