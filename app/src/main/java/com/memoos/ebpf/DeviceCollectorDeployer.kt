package com.memoos.ebpf

import android.content.Context
import com.memoos.device.DevicePaths
import com.memoos.device.RootShell

class DeviceCollectorDeployer(private val context: Context) {
    fun ensureBaseDirs(): Boolean {
        val cmd = "mkdir -p ${DevicePaths.MEMO_ROOT} ${DevicePaths.MODEL_DIR} ${DevicePaths.LOG_DIR} ${DevicePaths.SCENARIO_DIR}; chmod 755 ${DevicePaths.MEMO_ROOT}"
        return RootShell.run(cmd, requireRoot = true, timeoutMs = 5_000L).ok
    }

    fun ensureRawCollectorExecutable(): Boolean {
        ensureBaseDirs()
        val localCollector = java.io.File(context.filesDir, "memo_libbpf_collector")
        val localObject = java.io.File(context.filesDir, "memo_appflow.bpf.o")
        runCatching {
            context.assets.open("memo_libbpf_collector").use { input ->
                localCollector.outputStream().use { output -> input.copyTo(output) }
            }
        }
        runCatching {
            context.assets.open("memo_appflow.bpf.o").use { input ->
                localObject.outputStream().use { output -> input.copyTo(output) }
            }
        }
        if (localCollector.isFile) {
            RootShell.run(
                "cp '${localCollector.absolutePath}' '${DevicePaths.RAW_COLLECTOR}'; chmod 755 '${DevicePaths.RAW_COLLECTOR}'",
                requireRoot = true,
                timeoutMs = 5_000L,
            )
        }
        if (localObject.isFile) {
            RootShell.run(
                "cp '${localObject.absolutePath}' '${DevicePaths.BPF_OBJECT}'; chmod 644 '${DevicePaths.BPF_OBJECT}'",
                requireRoot = true,
                timeoutMs = 5_000L,
            )
        }
        return RootShell.run(
            "test -x '${DevicePaths.RAW_COLLECTOR}' && test -r '${DevicePaths.BPF_OBJECT}'",
            requireRoot = true,
            timeoutMs = 3_000L,
        ).ok
    }
}
