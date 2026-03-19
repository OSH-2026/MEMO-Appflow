package com.memoos.data.replay

import com.memoos.core.model.AppEvent

class ReplayEventStream {
    fun temporalWindows(events: List<AppEvent>, historyWindowSize: Int): Sequence<ReplayWindow> = sequence {
        val sorted = events.sortedBy { it.timestamp }
        if (sorted.size < 2) return@sequence
        val effectiveWindow = historyWindowSize
            .coerceAtLeast(1)
            .coerceAtMost(sorted.lastIndex)
        for (index in effectiveWindow until sorted.size) {
            val history = sorted.subList((index - effectiveWindow).coerceAtLeast(0), index)
            val actualNext = sorted[index]
            yield(ReplayWindow(history = history, actualNextEvent = actualNext))
        }
    }
}

data class ReplayWindow(
    val history: List<AppEvent>,
    val actualNextEvent: AppEvent,
)
