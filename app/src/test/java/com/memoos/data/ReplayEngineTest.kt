package com.memoos.data

import com.memoos.core.model.AppEvent
import com.memoos.core.model.ReplaySessionConfig
import com.memoos.core.model.SourceType
import com.memoos.data.replay.ReplayEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayEngineTest {
    @Test
    fun slicesReplayHistoryProgressively() {
        val events = (1L..5L).map {
            AppEvent("pkg.$it", it, 1, 9, SourceType.REPLAY)
        }
        val windows = ReplayEngine().slice(events, ReplaySessionConfig("sample", batchSize = 2)).toList()

        assertEquals(3, windows.size)
        assertEquals(2, windows[0].size)
        assertEquals(4, windows[1].size)
        assertEquals(5, windows[2].size)
    }
}
