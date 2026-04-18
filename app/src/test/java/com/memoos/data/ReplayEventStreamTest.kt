package com.memoos.data

import com.memoos.core.model.AppEvent
import com.memoos.core.model.SourceType
import com.memoos.data.replay.ReplayEventStream
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayEventStreamTest {
    @Test
    fun createsTemporalPredictionWindows() {
        val events = (1L..5L).map { AppEvent("pkg.$it", it, 1, 8, SourceType.REPLAY) }
        val windows = ReplayEventStream().temporalWindows(events, 2).toList()

        assertEquals(3, windows.size)
        assertEquals(2, windows.first().history.size)
        assertEquals("pkg.3", windows.first().actualNextEvent.packageName)
    }
}
