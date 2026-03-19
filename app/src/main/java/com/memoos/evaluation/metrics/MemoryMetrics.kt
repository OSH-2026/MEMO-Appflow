package com.memoos.evaluation.metrics

class MemoryMetrics {
    fun retentionDecisionCount(retainedPackages: List<String>): Int = retainedPackages.distinct().size
}
