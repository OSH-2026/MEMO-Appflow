package com.memoos.ebpf

object EBPFTraceParser {
    private val tracePrintLine = Regex(
        """^\s*(.+)-(\d+)\s+\[(\d+)] .*?(\d+\.\d+): bpf_trace_printk: (MEMO_\w+)\s*(.*)$""",
    )
    private val keyValue = Regex("""(\w+)=([^=]+?)(?=\s+\w+=|$)""")

    fun parseLines(lines: Sequence<String>): List<EBPFEvent> {
        return lines.mapNotNull { parseLine(it) }.toList()
    }

    fun parseLine(line: String): EBPFEvent? {
        val trimmed = line.trimEnd()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("MEMO\t")) return parseTsv(trimmed)
        return parseTracePrint(trimmed)
    }

    private fun parseTracePrint(line: String): EBPFEvent? {
        val match = tracePrintLine.matchEntire(line) ?: return null
        val rest = keyValue.findAll(match.groupValues[6])
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
        val path = rest["path"]
        return EBPFEvent(
            eventType = match.groupValues[5],
            timestampS = match.groupValues[4].toDoubleOrNull(),
            cpu = match.groupValues[3].toIntOrNull(),
            traceTask = match.groupValues[1].trim(),
            traceTid = match.groupValues[2].toIntOrNull(),
            pid = rest["pid"]?.toIntOrNull(),
            comm = rest["comm"],
            code = rest["code"]?.toIntOrNull(),
            toProc = rest["to_proc"]?.toIntOrNull(),
            path = path,
            detail = rest["detail"],
            evidenceCategory = evidenceCategory(path),
            source = "bpf_trace_printk",
            extra = rest,
        )
    }

    private fun parseTsv(line: String): EBPFEvent? {
        val parts = line.split('\t')
        if (parts.size < 11 || parts[0] != "MEMO") return null
        val rawType = parts[2]
        val arg0 = parts[7].toIntOrNull()
        val arg1 = parts[8].toIntOrNull()
        val arg2 = parts[9].toIntOrNull()
        val arg3 = parts[10].toIntOrNull()
        val detail = parts.drop(11).joinToString("\t").ifBlank { null }
        val mapped = when (rawType) {
            "binder" -> "MEMO_BINDER"
            "file" -> "MEMO_OPENAT"
            "memory" -> when (detail) {
                "direct_reclaim_begin" -> "MEMO_RECLAIM_BEGIN"
                "direct_reclaim_end" -> "MEMO_RECLAIM_END"
                "kswapd_wake" -> "MEMO_KSWAPD_WAKE"
                else -> "MEMO_MEMORY"
            }
            "network" -> if (arg0 == 1) "MEMO_SENDTO" else "MEMO_RECVFROM"
            "process_fork" -> "MEMO_PROCESS_FORK"
            "process_exit" -> "MEMO_PROCESS_EXIT"
            "sched" -> "MEMO_SCHED"
            "input" -> "MEMO_INPUT"
            "status" -> "MEMO_STATUS"
            else -> "MEMO_${rawType.uppercase()}"
        }
        val path = if (rawType == "file") detail else null
        val extra = mapOf(
            "raw_type" to rawType,
            "arg0" to (arg0?.toString() ?: ""),
            "arg1" to (arg1?.toString() ?: ""),
            "arg2" to (arg2?.toString() ?: ""),
            "arg3" to (arg3?.toString() ?: ""),
        )
        return EBPFEvent(
            eventType = mapped,
            timestampNs = parts[1].toLongOrNull(),
            uid = parts[3].toIntOrNull(),
            pid = parts[4].toIntOrNull(),
            tid = parts[5].toIntOrNull(),
            comm = parts[6].takeIf { it.isNotBlank() },
            code = if (mapped == "MEMO_BINDER") arg0 else null,
            toProc = if (mapped == "MEMO_BINDER") arg2 else null,
            path = path,
            detail = detail,
            evidenceCategory = evidenceCategory(path),
            source = "bpftrace_tsv",
            extra = extra,
        )
    }

    fun evidenceCategory(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return when {
            path.endsWith(".jar") || path.contains("/framework/") -> "java_framework_or_classpath"
            path.endsWith(".so") || path.contains("/lib64/") -> "native_library"
            path.contains("/__properties__/") -> "android_property_area"
            path.startsWith("/proc/") -> "procfs_process_state"
            path.startsWith("/sys/") -> "sysfs_kernel_state"
            path.startsWith("/apex/") -> "apex_runtime_asset"
            path.startsWith("/dev/") -> "device_or_ipc_node"
            path.contains("/cache/") -> "cache"
            path.endsWith(".db") || path.contains("/databases/") -> "database"
            path.endsWith(".dex") || path.endsWith(".oat") || path.endsWith(".vdex") -> "dex_or_oat"
            else -> "other"
        }
    }
}
