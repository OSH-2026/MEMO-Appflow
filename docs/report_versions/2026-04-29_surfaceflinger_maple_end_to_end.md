# 2026-04-29 SurfaceFlinger and MAPLE End-to-End Update

This version records the later 2026-04-29 update after the initial
`CONFIG_FTRACE_SYSCALLS` kernel work.

## What Changed

- Compared the broken custom kernel against the working stock Android 14
  emulator kernel.
- Added the Android virtual-device goldfish graphics/pipe drivers needed by
  SurfaceFlinger:
  - `GOLDFISH_PIPE`
  - `GOLDFISH_SYNC`
  - `GOLDFISH_ADDRESS_SPACE`
  - system DMA heap support
- Rebuilt the custom Android 14 x86_64 kernel as:

```text
dataset_cache/kernel/custom_android14_6.1_branch_ftrace_syscalls_graphics/bzImage
```

- Verified the same emulator boot now has:
  - visible UI
  - `surfaceflinger` process
  - `CONFIG_FTRACE_SYSCALLS=y`
  - working eBPF tracepoint collection
- Integrated the teammate-provided MAPLE engine under `llm/maple/`.
- Removed the old Python model scheduler entry.
- Connected real eBPF evidence to MAPLE through
  `scripts/android_ebpf/maple_context_from_ebpf.py`.

## Runtime Evidence

Kernel:

```text
Linux localhost 6.1.155 #4 SMP PREEMPT Wed Apr 29 22:15:27 CST 2026 x86_64 Toybox
```

SurfaceFlinger/UI:

```text
boot_completed=1
surfaceflinger_pid=420
Service SurfaceFlinger: found
```

Graphics-related device nodes:

```text
/dev/goldfish_pipe_dprctd
/dev/goldfish_sync
/dev/goldfish_address_space
/dev/dma_heap/system
/dev/dma_heap/system-uncached
```

Real eBPF run:

```text
dataset_cache/ebpf_runs/emulator_graphics_synced_002/trace_memo_events.jsonl
records: 17849
MEMO_BINDER: 13873
MEMO_OPENAT: 3976
```

MAPLE scenario:

```text
dataset_cache/maple_pipeline/ebpf_scenarios_graphics_synced.json
```

Verified MAPLE model output:

```text
Stage 1 raw output: Android Service IPC (90%)
Stage 2 raw output: This user will use App 110.
Parsed app id: 110
```

## Why It Matters

The project no longer needs to choose between a stock-kernel UI demo and a
custom-kernel eBPF demo. The fixed custom kernel supports both, so a demo can
show a normal Android emulator while collecting real system-level evidence and
feeding it into MAPLE.
