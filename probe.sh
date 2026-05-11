#!/usr/bin/env bash
set -u

ADB="${ADB:-adb}"
BPFTOOL="${BPFTOOL:-/data/local/tmp/bpftool}"
OUT="${OUT:-dataset_cache/ebpf_runs/probe.json}"

"$ADB" devices

# `adb root` only works on userdebug/eng builds. Magisk-rooted production
# devices usually need `su -c`, which the Python probe uses below.
"$ADB" root >/dev/null 2>&1 || true

"$ADB" shell su -c id
"$ADB" shell su -c "chmod 755 '$BPFTOOL' 2>/dev/null || true"
"$ADB" shell su -c "test -x '$BPFTOOL' && '$BPFTOOL' version || echo 'bpftool not executable at $BPFTOOL'"

"$ADB" shell su -c "zcat /proc/config.gz 2>/dev/null | grep -E 'CONFIG_BPF|CONFIG_BPF_SYSCALL|CONFIG_DEBUG_INFO_BTF|CONFIG_FTRACE|CONFIG_FTRACE_SYSCALLS|CONFIG_KPROBES|CONFIG_KPROBE_EVENTS|CONFIG_BPF_EVENTS' || true"
"$ADB" shell su -c "ls /sys/kernel/tracing/events/syscalls 2>/dev/null | head || echo 'no syscall tracepoints'"
"$ADB" shell su -c "wc -l /sys/kernel/tracing/available_events 2>/dev/null || true"

python3 scripts/android_ebpf/android_ebpf_collect.py probe \
  --su \
  --bpftool "$BPFTOOL" \
  --out "$OUT"
