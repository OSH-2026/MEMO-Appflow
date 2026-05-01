# Android eBPF Collection Pipeline

This folder contains the Android-side evidence collection pipeline for
MEMO-Appflow.  The Android app is only a lightweight display/demo surface; the
main research path here is:

1. collect low-level Android kernel evidence with eBPF,
2. structure it into privacy-preserving JSONL,
3. convert the evidence window into a MAPLE scenario,
4. run the teammate-provided MAPLE engine from `llm/maple`.

Frequency and sequence predictors are only baselines.  The model-facing signal
should come from Binder, file/cache, scheduler, input, graphics, and memory
pressure evidence whenever the emulator kernel exposes those tracepoints.

## Target Checks

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices
& $adb root
& $adb shell "zcat /proc/config.gz | grep -E 'CONFIG_BPF_SYSCALL|CONFIG_FTRACE_SYSCALLS'"
& $adb shell "ls /sys/kernel/tracing/events/syscalls | head"
```

For syscall filename evidence, the custom Android 14 emulator kernel must have
`CONFIG_FTRACE_SYSCALLS=y`.  The local custom kernel built for this repo also
keeps `CONFIG_BPF_SYSCALL=y`, `CONFIG_DEBUG_INFO_BTF=y`, and the goldfish
graphics/pipe drivers needed for SurfaceFlinger and the visible emulator UI.

## Collect Or Parse

Probe available tracepoints:

```powershell
python scripts/android_ebpf/android_ebpf_collect.py probe `
  --adb "$adb" `
  --no-su `
  --out dataset_cache/ebpf_runs/probe.json
```

Parse an existing bpftrace-style fixture:

```powershell
python scripts/android_ebpf/android_ebpf_collect.py parse `
  --raw-trace scripts/android_ebpf/fixtures/sample_trace.tsv `
  --actions-json scripts/android_ebpf/fixtures/sample_actions.json `
  --uid-map-json scripts/android_ebpf/fixtures/sample_uid_map.json `
  --out dataset_cache/ebpf_runs/fixture_parse
```

For the Android 14 custom-kernel path, `bpf/memo_traceprint.bpf.c` can be
compiled with clang, loaded by `bpftool`, and converted from tracefs output with
`traceprint_to_jsonl.py`.  The latest local run on the SurfaceFlinger-fixed
custom kernel is stored under
`dataset_cache/ebpf_runs/emulator_graphics_synced_002/trace_memo_events.jsonl`.

## Structured Outputs

`android_ebpf_collect.py parse` writes:

- `normalized_events.jsonl`
- `binder_service_segments.jsonl`
- `cold_launch_file_profile.jsonl`
- `memory_pressure_segments.jsonl`
- `semantic_evidence_segments.jsonl`
- `llm_context_windows.jsonl`
- `run_manifest.json`

The old OpenAI/Qwen request generator has been removed.  MAPLE is now the only
model entry point in this project.

## MAPLE Bridge

Convert real eBPF evidence into a MAPLE scenario:

```powershell
python scripts/android_ebpf/maple_context_from_ebpf.py `
  --events dataset_cache/ebpf_runs/emulator_graphics_synced_002/trace_memo_events.jsonl `
  --out dataset_cache/maple_pipeline/ebpf_scenarios_graphics_synced.json
```

The generated context includes the original MAPLE fields plus MEMO-specific
extensions:

`installed_apps` is a compatibility field from MAPLE. In this eBPF bridge it
means candidate MAPLE IDs by evidence/resource category.

```json
{
  "historical_app_categories": ["Android Service IPC", "Display Composition"],
  "historical_app_ids": [110, 115],
  "points_of_interest": ["Binder service lookup", "camera service activity"],
  "system_evidence": [
    "17849 eBPF records in the observed Android emulator window",
    "event_type MEMO_BINDER: count=13873",
    "event_type MEMO_OPENAT: count=3976",
    "MAPLE evidence/resource category Android Service IPC: count=13873"
  ],
  "memory_pressure": "normal: no direct reclaim tracepoint was observed",
  "scheduler_goal": "Bridge MEMO eBPF evidence into MAPLE. Infer near-future resource demand and memory scheduling; treat app sequence statistics only as a weak baseline."
}
```

The `historical_app_*` fields are kept for MAPLE API compatibility, but their
values are derived from eBPF evidence categories rather than from a shallow app
launch sequence.

Run the MAPLE demo after building `llm/maple`:

```powershell
wsl.exe bash -lc "cd /mnt/c/Users/gjy20/Desktop/26sp/osh/appflow/MEMO-Appflow/llm/maple && ./maple_engine/build/maple_demo --model models/Qwen3.5-0.8B-Q4_K_M.gguf --scenarios ../../dataset_cache/maple_pipeline/ebpf_scenarios_graphics_synced.json"
```

## Validation

```powershell
python -m unittest `
  scripts.android_ebpf.tests.test_android_ebpf_collect `
  scripts.android_ebpf.tests.test_maple_context_from_ebpf
```
