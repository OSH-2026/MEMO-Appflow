# Raw eBPF / libbpf Collector

This project no longer requires `bpftrace` in the Android product path. The collector path is:

```text
src/bpf/memo_appflow.bpf.c
-> clang -target bpf
-> optional bpftool gen skeleton for Linux development builds
-> src/native/memo_libbpf_collector.c Android loader
-> /data/local/tmp/memo/memo_libbpf_collector
-> Android EBPFCollectorService parses MEMO TSV output
```

The verified rooted Pixel 5 path uses `bpftool prog loadall` plus raw
`bpf(2)`/`perf_event_open(2)` syscalls because that phone exposes no
`/sys/kernel/btf/vmlinux` and no ring-buffer map type. The product path is
still raw eBPF C bytecode and does not execute bpftrace.

## Required Kernel Features

- `CONFIG_BPF`
- `CONFIG_BPF_SYSCALL`
- `CONFIG_BPF_JIT`
- `CONFIG_DEBUG_INFO_BTF` or readable `/sys/kernel/btf/vmlinux` for CO-RE. If unavailable, the current minimal collector uses stable tracepoint layouts instead of BTF field relocation.
- tracefs/debugfs with stable tracepoints:
  - `syscalls:sys_enter_openat`
  - `syscalls:sys_enter_sendto`
  - `syscalls:sys_enter_recvfrom`
  - `sched:sched_switch`
  - `sched:sched_process_fork`
  - `sched:sched_process_exit`
  - `binder:binder_transaction`
  - `vmscan:*`

## Build

The build follows libbpf-bootstrap style.

```bash
git clone --depth 1 https://github.com/libbpf/libbpf.git dataset_cache/libbpf
make -C dataset_cache/libbpf/src BUILD_STATIC_ONLY=1 OBJDIR=$PWD/build/memo_libbpf/libbpf DESTDIR=$PWD/build/memo_libbpf/libbpf-install install
LIBBPF_A=$PWD/build/memo_libbpf/libbpf/libbpf.a ./scripts/build_memo_libbpf.sh
```

For Android ARM64, cross-compile libbpf, libelf, and zlib first, then run:

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk
TARGET=android-arm64 \
LIBBPF_A=/path/to/android/libbpf.a \
LIBELF_A=/path/to/android/libelf.a \
ZLIB_A=/path/to/android/libz.a \
./scripts/build_memo_libbpf.sh
```

## Install On Rooted Android

```bash
adb shell su -c 'mkdir -p /data/local/tmp/memo /sdcard/MEMO/logs'
adb push build/memo_libbpf/memo_libbpf_collector /data/local/tmp/memo/memo_libbpf_collector
adb push build/memo_libbpf/memo_appflow.bpf.o /data/local/tmp/memo/memo_appflow.bpf.o
adb shell su -c 'chmod 755 /data/local/tmp/memo/memo_libbpf_collector'
```

## Smoke Test

```bash
adb shell su -c '/data/local/tmp/memo/memo_libbpf_collector --feature-check'
adb shell su -c '/data/local/tmp/memo/memo_libbpf_collector --duration-sec 5 --max-events 20'
```

Expected output begins with:

```text
MEMO    0    status    0    0    0    memo    0    0    0    0    collector_started
```

The same `MEMO` TSV format is consumed by `EBPFTraceParser`, so the MAPLE scenario builder and ActionExecutor do not need a separate parser.

## Android App Integration

The Android service now checks for:

```text
/data/local/tmp/memo/memo_libbpf_collector
/data/local/tmp/memo/memo_appflow.bpf.o
```

and runs it directly. It does not generate `.bt` files and does not execute `bpftrace`.

## Verified Pixel 5 Run

Device:

```text
Pixel 5 / redfin / Android 14 / arm64
Linux 4.19.278
root: Magisk su
BTF: /sys/kernel/btf/vmlinux absent
```

Real user scroll/display experiment:

```text
trigger:
adb shell am start -S -n com.memoos/.MainActivity --es memo_action com.memoos.action.REAL_EXPERIMENT_SCROLL

raw trace:
/sdcard/MEMO/logs/real_user_1779966570132.trace

device result files:
/sdcard/Android/data/com.memoos/files/latest_real_user_experiment.txt
/sdcard/Android/data/com.memoos/files/latest_maple_scenario.json
/sdcard/Android/data/com.memoos/files/latest_pipeline_latency.json
/sdcard/Android/data/com.memoos/files/latest_full_local_evaluation.txt
```

Key result:

```text
parsed_events=2474
target_app=Chrome (com.android.chrome)
MAPLE backend=shell
MAPLE App ID=280
Top-3=Settings, Chrome, Messages
total_ms=44480
foreground_ms=26279
maple_inference_ms=18201
realtime_status=ok
```
