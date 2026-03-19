package com.memoos.data.replay

import com.memoos.core.model.AppEvent
import com.memoos.core.model.ReplaySessionConfig

class ReplayEngine {
    fun slice(events: List<AppEvent>, config: ReplaySessionConfig): Sequence<List<AppEvent>> = sequence {
        if (events.isEmpty()) return@sequence
        val filtered = events.filter { event ->
            (config.startTimestamp == null || event.timestamp >= config.startTimestamp) &&
                (config.endTimestamp == null || event.timestamp <= config.endTimestamp)
        }
        val batchSize = config.batchSize.coerceAtLeast(1)
        var size = batchSize
        while (size <= filtered.size) {
            yield(filtered.subList(0, size))
            size += batchSize
        }
        if (filtered.size % batchSize != 0) {
            yield(filtered)
        }
    }
}
