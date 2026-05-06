package com.memoos.device

object DevicePaths {
    const val MEMO_ROOT = "/data/local/tmp/memo"
    const val MEMO_PUBLIC_ROOT = "/sdcard/MEMO"
    const val MODEL_FILE_NAME = "Qwen3.5-0.8B-Q4_K_M.gguf"
    const val MODEL_DIR = "$MEMO_ROOT/models"
    const val LOG_DIR = "$MEMO_PUBLIC_ROOT/logs"
    const val SCENARIO_DIR = "$MEMO_PUBLIC_ROOT/scenarios"
    const val BPFTRACE = "$MEMO_ROOT/bpftrace"
    const val BPFTOOL = "$MEMO_ROOT/bpftool"
    const val TRACE_SCRIPT = "$MEMO_ROOT/memo_appflow_trace.bt"
    const val GENERATED_TRACE_SCRIPT = "$MEMO_ROOT/memo_appflow_generated.bt"
    const val MAPLE_DEMO = "$MEMO_ROOT/maple_demo"
    const val MAPLE_JNI_SO = "$MEMO_ROOT/libmaple-jni.so"
    const val DEFAULT_MODEL = "$MODEL_DIR/$MODEL_FILE_NAME"

    val traceFsCandidates = listOf(
        "/sys/kernel/tracing",
        "/sys/kernel/debug/tracing",
    )
}
