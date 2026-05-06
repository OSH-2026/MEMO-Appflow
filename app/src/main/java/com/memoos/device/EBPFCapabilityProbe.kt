package com.memoos.device

data class EBPFCapabilityReport(
    val rootAvailable: Boolean,
    val traceFsPath: String?,
    val bpftracePath: String?,
    val bpftoolPath: String?,
    val btfAvailable: Boolean,
    val ftraceSyscallsAvailable: Boolean,
    val binderTracepointAvailable: Boolean,
    val vmscanTracepointAvailable: Boolean,
    val schedTracepointAvailable: Boolean,
    val inputTracepointAvailable: Boolean,
    val networkSyscallsAvailable: Boolean,
    val availableEvents: Set<String>,
    val notes: List<String>,
) {
    val canRunBpftrace: Boolean get() = rootAvailable && traceFsPath != null && bpftracePath != null
    val canReadTracePipe: Boolean get() = rootAvailable && traceFsPath != null
}

object EBPFCapabilityProbe {
    fun probe(): EBPFCapabilityReport {
        val notes = mutableListOf<String>()
        val root = RootShell.hasRoot()
        if (!root) notes += "root unavailable; eBPF collection cannot attach kernel tracepoints"

        val traceFs = if (root) firstExisting(DevicePaths.traceFsCandidates) else null
        if (traceFs == null) notes += "tracefs/debugfs not found"

        val bpftrace = if (root) firstExecutable(
            listOf(DevicePaths.BPFTRACE, "/system/bin/bpftrace", "/vendor/bin/bpftrace", "bpftrace"),
        ) else null
        if (bpftrace == null) notes += "bpftrace not found on device"

        val bpftool = if (root) firstExecutable(
            listOf(DevicePaths.BPFTOOL, "/system/bin/bpftool", "/vendor/bin/bpftool", "bpftool"),
        ) else null
        if (bpftool == null) notes += "bpftool not found on device"

        val events = if (root && traceFs != null) {
            RootShell.run("cat $traceFs/available_events 2>/dev/null", timeoutMs = 5_000L)
                .stdout
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        } else {
            emptySet()
        }

        val btf = root && RootShell.run("test -r /sys/kernel/btf/vmlinux", timeoutMs = 2_000L).ok
        val ftraceSyscalls = events.any { it.startsWith("syscalls:sys_enter_") }
        val binder = events.contains("binder:binder_transaction")
        val vmscan = events.any { it.startsWith("vmscan:") }
        val sched = events.any { it.startsWith("sched:") }
        val input = events.any { it.startsWith("input:") }
        val network = events.contains("syscalls:sys_enter_sendto") || events.contains("syscalls:sys_enter_recvfrom")

        if (!ftraceSyscalls) notes += "syscall tracepoints unavailable; openat/sendto/recvfrom eBPF coverage is limited"
        if (!binder) notes += "binder transaction tracepoint unavailable"
        if (!network) notes += "sendto/recvfrom tracepoints unavailable"

        return EBPFCapabilityReport(
            rootAvailable = root,
            traceFsPath = traceFs,
            bpftracePath = bpftrace,
            bpftoolPath = bpftool,
            btfAvailable = btf,
            ftraceSyscallsAvailable = ftraceSyscalls,
            binderTracepointAvailable = binder,
            vmscanTracepointAvailable = vmscan,
            schedTracepointAvailable = sched,
            inputTracepointAvailable = input,
            networkSyscallsAvailable = network,
            availableEvents = events,
            notes = notes,
        )
    }

    private fun firstExisting(paths: List<String>): String? {
        val joined = paths.joinToString(" ") { "'$it'" }
        val result = RootShell.run(
            "for p in $joined; do if [ -d \"\$p\" ]; then echo \"\$p\"; exit 0; fi; done; exit 1",
            timeoutMs = 3_000L,
        )
        return result.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
    }

    private fun firstExecutable(paths: List<String>): String? {
        val checks = paths.joinToString(" ") { "'$it'" }
        val result = RootShell.run(
            "for p in $checks; do if command -v \"\$p\" >/dev/null 2>&1; then command -v \"\$p\"; exit 0; fi; if [ -x \"\$p\" ]; then echo \"\$p\"; exit 0; fi; done; exit 1",
            timeoutMs = 3_000L,
        )
        return result.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
    }
}
