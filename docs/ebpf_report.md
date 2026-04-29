# MEMO-Appflow eBPF Evidence, MAPLE Bridge, and Emulator Report

Date: 2026-04-29

## 1. Current Goal

The project direction is:

```text
Android emulator behavior
-> low-level system evidence from eBPF
-> structured JSONL evidence
-> MAPLE scenario
-> MAPLE local inference
-> resource/memory scheduling interpretation
```

The Android app is deliberately light. It is a demo surface, not the main
implementation. The important part is the evidence path: when a user or script
interacts with the Android emulator, we collect Binder IPC, file access,
display/compositor activity, process activity, and memory-pressure signals from
the system level. The model side should reason from those signals, while simple
frequency or app-sequence statistics stay only as baseline context.

## 2. Implemented Pieces

| Piece | Path | Role |
| --- | --- | --- |
| eBPF tracepoint program | `scripts/android_ebpf/bpf/memo_traceprint.bpf.c` | Attaches to syscall, Binder, and vmscan tracepoints. Emits compact `MEMO_*` evidence through tracefs. |
| Trace parser | `scripts/android_ebpf/traceprint_to_jsonl.py` | Converts `bpf_trace_printk` lines into JSONL records with categories. |
| Android collector/orchestrator | `scripts/android_ebpf/android_ebpf_collect.py` | Installs tools, probes tracepoints, drives workloads, and structures bpftrace-style traces. |
| MAPLE adapter | `scripts/android_ebpf/maple_context_from_ebpf.py` | Summarizes real eBPF JSONL into `memo.maple_scenarios.v1`. |
| MAPLE engine integration | `llm/maple/maple_engine/` | Uses the teammate-provided MAPLE engine as the only model entry point. |
| Current report | `docs/ebpf_report.md` | Records what was run, data shapes, and current limitations. |

The old Python OpenAI/Qwen/rules scheduler path has been removed. The model
connection in this repository now goes through MAPLE.

## 3. Android 14 Custom Kernel and SurfaceFlinger

The first custom Android 14 emulator kernel had eBPF and
`CONFIG_FTRACE_SYSCALLS=y`, but the visible UI was broken because
SurfaceFlinger could not establish the emulator graphics pipe. The relevant
failure looked like:

```text
both vsock and goldfish_pipe paths failed
no suitable EGLConfig found, giving up
SurfaceFlinger crashed repeatedly
```

To fix this, the custom kernel was compared against the stock `MEMO_OS_API34`
kernel that could show the normal phone UI. The stock system exposed:

```text
/dev/goldfish_pipe_dprctd
/dev/goldfish_sync
/dev/goldfish_address_space
/dev/dma_heap/system
/dev/dma_heap/system-uncached
```

The custom kernel was rebuilt with Android virtual-device goldfish drivers and
DMA heap support built in. The working custom artifact is:

```text
dataset_cache/kernel/custom_android14_6.1_branch_ftrace_syscalls_graphics/bzImage
```

Runtime evidence from the fixed kernel:

```text
Linux localhost 6.1.155 #4 SMP PREEMPT Wed Apr 29 22:15:27 CST 2026 x86_64 Toybox
CONFIG_BPF_SYSCALL=y
CONFIG_DEBUG_INFO_BTF=y
CONFIG_FTRACE_SYSCALLS=y
CONFIG_GOLDFISH_PIPE=y
CONFIG_GOLDFISH_SYNC=y
CONFIG_GOLDFISH_ADDRESS_SPACE=y
```

Device nodes after boot:

```text
/dev/goldfish_pipe_dprctd     crw-rw-rw- system system
/dev/goldfish_sync            crw-rw-rw- system system
/dev/goldfish_address_space   crw-rw-rw- system system
/dev/dma_heap/system
/dev/dma_heap/system-uncached
```

SurfaceFlinger was then present and stable:

```text
boot_completed=1
surfaceflinger_pid=420
Service SurfaceFlinger: found
SurfaceFlinger: Boot is finished
```

This matters because the demo now uses one emulator for both visible UI and
eBPF collection instead of switching between a UI kernel and a tracing kernel.

## 4. Real eBPF Run on the Fixed Emulator

Latest local run:

```text
dataset_cache/ebpf_runs/emulator_graphics_synced_002/trace_memo_events.jsonl
```

The run collected:

```text
17849 structured eBPF records
MEMO_BINDER: 13873
MEMO_OPENAT: 3976
```

The observed Android workload included visible UI activity and system/user
actions. In the trace this appears as SurfaceFlinger, RenderThread,
app_process, launcher/settings/maps/camera-related threads, Binder worker
threads, `commands.monkey`, `input`, and media/codec service activity.

Raw JSONL examples from the run:

```json
{"schema_version":"memo.ebpf.traceprint.v1","timestamp_s":230.553396,"cpu":1,"trace_task":"bpftool","trace_tid":4830,"event_type":"MEMO_OPENAT","pid":4830,"comm":"bpftool","path":"/sys/kernel/tracing/events/syscalls/sys_enter_openat2/id","evidence_category":"sysfs_kernel_state"}
{"schema_version":"memo.ebpf.traceprint.v1","timestamp_s":230.665931,"cpu":3,"trace_task":"m.android.phone","trace_tid":962,"event_type":"MEMO_BINDER","pid":962,"code":5,"to_proc":555}
{"schema_version":"memo.ebpf.traceprint.v1","timestamp_s":230.666318,"cpu":3,"trace_task":"binder:555_18","trace_tid":2199,"event_type":"MEMO_BINDER","pid":555,"code":0,"to_proc":962}
{"schema_version":"memo.ebpf.traceprint.v1","timestamp_s":230.666397,"cpu":3,"trace_task":"m.android.phone","trace_tid":962,"event_type":"MEMO_BINDER","pid":962,"code":12,"to_proc":555}
```

The first event shows the collector loading tracepoint IDs. The later events
show Android Binder traffic generated by the emulator workload. The model-facing
adapter keeps counts, categories, and process hints; it does not need raw Binder
payloads, input text, network content, or app-private file contents.

## 5. Evidence Structure

Each low-level event contains a stable schema:

```json
{
  "schema_version": "memo.ebpf.traceprint.v1",
  "timestamp_s": 230.666397,
  "cpu": 3,
  "trace_task": "m.android.phone",
  "trace_tid": 962,
  "event_type": "MEMO_BINDER",
  "pid": 962,
  "code": 12,
  "to_proc": 555
}
```

For file/syscall evidence:

```json
{
  "schema_version": "memo.ebpf.traceprint.v1",
  "event_type": "MEMO_OPENAT",
  "comm": "surfaceflinger",
  "path": "/system/lib64/libEGL.so",
  "evidence_category": "native_library"
}
```

The raw trace may contain paths because this is local debugging evidence. The
MAPLE scenario summarizes them into bridge categories such as
`Android Service IPC`, `Display Composition`, `Native Runtime Loading`,
`Framework Loading`, `Process State Inspection`, and `Memory Management`.

MAPLE scenario generated from the real run:

`installed_apps` is kept because it is part of MAPLE's original interface. In
the eBPF bridge it means "candidate MAPLE IDs by evidence/resource category",
not literal Android packages installed on the phone.

```json
{
  "schema_version": "memo.maple_scenarios.v1",
  "scenarios": [
    {
      "id": "ebpf_graphics_synced_window",
      "source": "android_ebpf",
      "context": {
        "historical_app_categories": [
          "Android Service IPC",
          "Display Composition",
          "App Process Runtime",
          "Other File Access",
          "Media Codec"
        ],
        "historical_app_ids": [110, 115, 280, 230, 260],
        "points_of_interest": [
          "display compositor activity",
          "app_process process activity",
          "RenderThread process activity",
          "lmkd process activity",
          "NDK MediaCodec_ process activity"
        ],
        "installed_apps": {
          "Android Service IPC": [110],
          "Display Composition": [115],
          "App Process Runtime": [280],
          "Other File Access": [230],
          "Media Codec": [260]
        },
        "system_evidence": [
          "17849 eBPF records in the observed Android emulator window",
          "event_type MEMO_BINDER: count=13873",
          "event_type MEMO_OPENAT: count=3976",
          "MAPLE evidence/resource category Android Service IPC: count=13873",
          "MAPLE evidence/resource category Display Composition: count=3086",
          "process surfaceflinger: count=2129",
          "process app_process: count=1160"
        ],
        "memory_pressure": "normal: no direct reclaim tracepoint was observed",
        "scheduler_goal": "Reason about near-future resource demand and memory scheduling from eBPF evidence; treat sequence statistics only as a weak baseline."
      }
    }
  ]
}
```

This is intentionally compatible with MAPLE's app-category and app-id fields,
but the values come from eBPF evidence categories rather than from a shallow app
launch sequence.

## 6. MAPLE Changes

The teammate-provided MAPLE engine was integrated under `llm/maple/`. The ZIP
is no longer the runtime entry. Current MAPLE-side changes are:

- `UserContext` accepts `system_evidence`, `memory_pressure`, and
  `scheduler_goal`.
- Stage 1 prompt can use eBPF evidence without exposing candidate app IDs,
  avoiding the earlier mistake where App ID `110` was treated as `110%`.
- Stage 2 prompt requires exactly:

```text
This user will use App <id>.
```

- `parse_next_app` now only accepts explicit `App <id>` output. It no longer
  treats a bare number as a predicted app id, because a bare number could be a
  Binder code, trace count, or confidence value.
- The llama backend uses a Qwen-style non-thinking chat prefix with an empty
  `<think></think>` block, so generation starts at the final answer instead of
  spending all tokens on reasoning.

Current verified MAPLE output:

```text
Stage 1 raw output:
Android Service IPC (90%)

Stage 2 raw output:
This user will use App 110.

Parsed final result:
App ID: 110
```

The important part is that Stage 2 is now a real model output in the requested
format, not a deterministic evidence-to-ID mapping.

## 7. Commands Used for Reproduction

Boot the fixed custom kernel:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$emu = "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe"
$kernel = (Resolve-Path .\dataset_cache\kernel\custom_android14_6.1_branch_ftrace_syscalls_graphics\bzImage).Path

Start-Process -FilePath $emu -ArgumentList @(
  '-avd','MEMO_OS_API34',
  '-no-snapshot-load',
  '-no-snapshot-save',
  '-gpu','host',
  '-kernel',$kernel
)
& $adb wait-for-device
```

Install tracing tools if needed:

```powershell
python scripts/android_ebpf/android_ebpf_collect.py install-tools `
  --adb "$adb" `
  --no-su `
  --out dataset_cache/ebpf_runs/graphics_synced_tool_install.json
```

Convert collected eBPF JSONL into a MAPLE scenario:

```powershell
python scripts/android_ebpf/maple_context_from_ebpf.py `
  --events dataset_cache/ebpf_runs/emulator_graphics_synced_002/trace_memo_events.jsonl `
  --out dataset_cache/maple_pipeline/ebpf_scenarios_graphics_synced.json `
  --scenario-id ebpf_graphics_synced_window `
  --description "Android UI workload on custom eBPF+SurfaceFlinger kernel"
```

Run MAPLE:

```powershell
wsl.exe bash -lc "cd /mnt/c/Users/gjy20/Desktop/26sp/osh/appflow/MEMO-Appflow/llm/maple && ./maple_engine/build/maple_demo --model models/Qwen3.5-0.8B-Q4_K_M.gguf --scenarios ../../dataset_cache/maple_pipeline/ebpf_scenarios_graphics_synced.json"
```

## 8. How This Supports Memory Scheduling

The model should not only say "next app". The useful reasoning target is:

- What system resource demand is visible or likely soon?
- Does Binder evidence imply camera, display, media, input, location, package
  manager, or system-service activity?
- Are native libraries, framework jars, procfs/sysfs, or app data being touched
  during a cold or active window?
- Is there direct reclaim or kswapd pressure?
- Should the scheduler keep an actor alive, avoid killing a likely-needed
  process, defer heavy prewarm, or reclaim more aggressively?

Example interpretation from the current run:

```text
Android Service IPC dominates the window, SurfaceFlinger and RenderThread are active,
openat touches native/framework/process-state files, and direct reclaim is not
observed.

That supports a conservative "keep visible/system actors stable; do not infer
critical memory pressure" decision. If future windows show vmscan direct
reclaim, the same schema can tell MAPLE to lower prewarm aggressiveness.
```

## 9. Current Limits

- `memo_traceprint.bpf.c` is still trace-buffer based. It proves real eBPF
  collection on the emulator and is easy to demo, but a ring-buffer version
  would be cleaner for long runs.
- The current MAPLE scenario is one summarized window. The next useful step is
  windowed scenarios: launcher, camera, maps, settings, background memory
  pressure, and app switching as separate MAPLE examples.
- `bpftrace` and `bpftool` are installed on the emulator. The verified custom
  kernel path uses `bpftool` plus a compiled eBPF object because that path was
  stable on the SurfaceFlinger-fixed boot.
- Android app code is intentionally minimal. The important demo is: visible
  emulator UI, real eBPF JSONL, MAPLE scenario conversion, and MAPLE local
  inference.
