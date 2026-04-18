package com.memoos.data.dataset

import com.memoos.core.config.MemoConfig
import com.memoos.core.model.AppEvent

data class DatasetSplits(
    val train: List<AppEvent>,
    val validation: List<AppEvent>,
    val test: List<AppEvent>,
)

class DatasetSplitManager {
    fun split(events: List<AppEvent>, config: MemoConfig): DatasetSplits {
        if (events.isEmpty()) return DatasetSplits(emptyList(), emptyList(), emptyList())
        val sorted = events.sortedBy { it.timestamp }
        val trainEnd = (sorted.size * config.trainSplit).toInt().coerceAtLeast(1).coerceAtMost(sorted.size)
        val valEnd = (trainEnd + (sorted.size * config.valSplit).toInt()).coerceAtMost(sorted.size)
        return DatasetSplits(
            train = sorted.subList(0, trainEnd),
            validation = sorted.subList(trainEnd, valEnd),
            test = sorted.subList(valEnd, sorted.size),
        )
    }
}
