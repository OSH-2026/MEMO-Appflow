package com.memoos.ebpf

import android.content.Context
import com.memoos.device.DevicePaths
import com.memoos.device.RootShell
import java.io.File

class DeviceCollectorDeployer(private val context: Context) {
    fun ensureBaseDirs(): Boolean {
        val cmd = "mkdir -p ${DevicePaths.MEMO_ROOT} ${DevicePaths.MODEL_DIR} ${DevicePaths.LOG_DIR} ${DevicePaths.SCENARIO_DIR}; chmod 755 ${DevicePaths.MEMO_ROOT}"
        return RootShell.run(cmd, requireRoot = true, timeoutMs = 5_000L).ok
    }

    fun deployGeneratedBpftrace(script: String): File {
        val local = File(context.filesDir, "memo_appflow_generated.bt")
        local.writeText(script)
        ensureBaseDirs()
        val copy = "cp '${local.absolutePath}' '${DevicePaths.GENERATED_TRACE_SCRIPT}'; chmod 644 '${DevicePaths.GENERATED_TRACE_SCRIPT}'"
        RootShell.run(copy, requireRoot = true, timeoutMs = 5_000L)
        return local
    }
}
