package com.memoos.evaluation.metrics

class StartupMetrics {
    fun averageLatency(latencies: List<Long>): Double = if (latencies.isEmpty()) 0.0 else latencies.average()

    fun p95Latency(latencies: List<Long>): Long {
        if (latencies.isEmpty()) return 0L
        val sorted = latencies.sorted()
        val index = ((sorted.size - 1) * 0.95).toInt()
        return sorted[index]
    }
}
