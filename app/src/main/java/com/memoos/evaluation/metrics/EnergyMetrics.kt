package com.memoos.evaluation.metrics

class EnergyMetrics {
    fun invalidPrewarmRate(requested: List<String>, launched: List<String>): Double {
        if (requested.isEmpty()) return 0.0
        val invalid = requested.count { it !in launched }
        return invalid.toDouble() / requested.size
    }
}
