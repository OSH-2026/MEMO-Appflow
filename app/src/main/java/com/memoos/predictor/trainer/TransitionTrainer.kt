package com.memoos.predictor.trainer

import com.memoos.core.model.AppEvent

class TransitionTrainer {
    fun train(history: List<AppEvent>): Map<String, Map<String, Int>> {
        if (history.size < 2) return emptyMap()
        val sorted = history.sortedBy { it.timestamp }
        val transitions = mutableMapOf<String, MutableMap<String, Int>>()
        for (index in 0 until sorted.lastIndex) {
            val current = sorted[index].packageName
            val next = sorted[index + 1].packageName
            val nextCounts = transitions.getOrPut(current) { mutableMapOf() }
            nextCounts[next] = nextCounts.getOrDefault(next, 0) + 1
        }
        return transitions
    }
}
