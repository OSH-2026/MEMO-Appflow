package com.memoos.ebpf

data class EBPFEvent(
    val eventType: String,
    val timestampS: Double? = null,
    val timestampNs: Long? = null,
    val cpu: Int? = null,
    val traceTask: String? = null,
    val traceTid: Int? = null,
    val uid: Int? = null,
    val pid: Int? = null,
    val tid: Int? = null,
    val comm: String? = null,
    val code: Int? = null,
    val toProc: Int? = null,
    val path: String? = null,
    val detail: String? = null,
    val evidenceCategory: String? = null,
    val source: String = "device",
    val extra: Map<String, String> = emptyMap(),
)
