package com.memoos.system.monitor

class StartupMonitor {
    fun recordStartupLatency(packageName: String, latencyMs: Long): Pair<String, Long> = packageName to latencyMs
}
