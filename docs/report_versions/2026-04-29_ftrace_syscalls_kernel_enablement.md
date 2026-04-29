# 2026-04-29 FTRACE_SYSCALLS Kernel Enablement

This version records the pivot from "stock emulator has eBPF but no syscall
filename tracepoints" to "custom Android 14 emulator kernel exposes syscall
tracepoints and runs real eBPF against them."

## What Changed

- Built an Android 14 x86_64 kernel from the AOSP mirror `android14-6.1`
  branch.
- Enabled `CONFIG_FTRACE_SYSCALLS=y`.
- Also built key emulator devices into the kernel so the AVD can reach root
  ADB: `VIRTIO_BLK`, `VIRTIO_NET`, `VIRTIO_PCI`, `VIRTIO_INPUT`,
  `VIRTIO_VSOCKETS`, `DRM_VIRTIO_GPU`, `GOLDFISH_PIPE`, and `SND_VIRTIO`.
- Booted `MEMO_OS_API34` with the custom `bzImage`.
- Verified inside the emulator that `/proc/config.gz` includes
  `CONFIG_FTRACE_SYSCALLS=y`.
- Verified `/sys/kernel/tracing/events/syscalls` exposes
  `sys_enter_openat`, `sys_enter_openat2`, `sys_enter_execve`, and
  `sys_enter_mmap`.

## Artifacts

- Kernel image:
  `dataset_cache/kernel/custom_android14_6.1_branch_ftrace_syscalls/bzImage`
- Kernel config:
  `dataset_cache/kernel/custom_android14_6.1_branch_ftrace_syscalls/config`
- Boot log:
  `dataset_cache/kernel/custom_android14_6.1_branch_ftrace_syscalls/boot_full_virtio_goldfish_stdout.log`
- eBPF verifier:
  `scripts/android_ebpf/bpf/memo_traceprint.bpf.c`
- Trace-to-JSONL parser:
  `scripts/android_ebpf/traceprint_to_jsonl.py`
- Compiled BPF object:
  `dataset_cache/ebpf_build/memo_traceprint.bpf.o`
- Raw trace evidence:
  `dataset_cache/ebpf_runs/emulator_ftrace_syscalls_001/trace_memo_events.txt`
- Structured evidence:
  `dataset_cache/ebpf_runs/emulator_ftrace_syscalls_001/trace_memo_events.jsonl`

## Runtime Evidence

The custom kernel reported:

```text
Linux localhost 6.1.155 #3 SMP PREEMPT Wed Apr 29 20:25:54 CST 2026 x86_64 Toybox
CONFIG_BPF_SYSCALL=y
CONFIG_DEBUG_INFO_BTF=y
CONFIG_HAVE_SYSCALL_TRACEPOINTS=y
CONFIG_FTRACE_SYSCALLS=y
```

The syscall tracepoint directory reported 630 syscall events, including:

```text
sys_enter_execve
sys_enter_execveat
sys_enter_mmap
sys_enter_openat
sys_enter_openat2
sys_exit_openat
sys_exit_openat2
```

`bpftool loadall /data/local/tmp/memo_traceprint.bpf.o
/sys/fs/bpf/memo_traceprint autoattach` loaded and attached five eBPF
tracepoint programs:

```text
memo_sys_enter_openat
memo_sys_enter_openat2
memo_binder_transaction
memo_reclaim_begin
memo_reclaim_end
```

The collected JSONL contains 240 records in the verification window. Examples:

```json
{"schema_version":"memo.ebpf.traceprint.v1","event_type":"MEMO_OPENAT","pid":11150,"comm":"netd","path":"/system/lib64/libc.so","evidence_category":"native_library"}
{"schema_version":"memo.ebpf.traceprint.v1","event_type":"MEMO_OPENAT","pid":11139,"comm":"main","path":"/system/framework/framework.jar","evidence_category":"java_framework_or_classpath"}
{"schema_version":"memo.ebpf.traceprint.v1","event_type":"MEMO_BINDER","pid":11141,"code":13,"to_proc":168}
```

## Follow-up

This version captured the first syscall-tracepoint kernel. A later 2026-04-29
version fixed the graphics side as well by syncing the Android virtual-device
goldfish pipe/sync/address-space drivers and DMA heap support into the custom
kernel.

The current UI + eBPF + MAPLE status is recorded in:

```text
docs/report_versions/2026-04-29_surfaceflinger_maple_end_to_end.md
```
