package com.memoos.device

object DevicePaths {
    const val MEMO_ROOT = "/data/local/tmp/memo"
    const val MEMO_PUBLIC_ROOT = "/sdcard/MEMO"
    const val MODEL_FILE_NAME = "Qwen3.5-0.8B-Q4_K_M.gguf"
    const val MODEL_DIR = "$MEMO_ROOT/models"
    const val LOG_DIR = "$MEMO_PUBLIC_ROOT/logs"
    const val SCENARIO_DIR = "$MEMO_PUBLIC_ROOT/scenarios"
    const val BPFTOOL = "$MEMO_ROOT/bpftool"
    const val RAW_COLLECTOR = "$MEMO_ROOT/memo_libbpf_collector"
    const val BPF_OBJECT = "$MEMO_ROOT/memo_appflow.bpf.o"
    const val MAPLE_DEMO = "$MEMO_ROOT/maple_demo"
    const val MAPLE_ENGINE_SO = "$MEMO_ROOT/libmaple_engine.so"
    const val MAPLE_JNI_SO = "$MEMO_ROOT/libmaple-jni.so"
    const val MAPLE_CXX_SHARED = "$MEMO_ROOT/libc++_shared.so"
    const val DEFAULT_MODEL = "$MODEL_DIR/$MODEL_FILE_NAME"

    val traceFsCandidates = listOf(
        "/sys/kernel/tracing",
        "/sys/kernel/debug/tracing",
    )
}
