#!/usr/bin/env bash
set -euo pipefail

ADB="${ADB:-adb}"
USE_SU="${USE_SU:-1}"
BPFTOOL="${BPFTOOL:-/data/local/tmp/bpftool}"
VMLINUX_BTF_HOST="${VMLINUX_BTF_HOST:-/home/javaherobrine/桌面/pixel5-kernel/out/redbull-ftrace-syscalls-kprobes-uprobes-selinuxpermissive-btf/dist/vmlinux.btf}"
VMLINUX_BTF="${VMLINUX_BTF:-/data/local/tmp/vmlinux-4.19.278-ftrace-syscalls.btf}"
BTF_SMOKE_OBJ_HOST="${BTF_SMOKE_OBJ_HOST:-/home/javaherobrine/桌面/pixel5-kernel/out/redbull-ftrace-syscalls-selinuxpermissive-btf/test-staging/btf-smoke/core_task_smoke.o}"
BTF_SMOKE_OBJ="${BTF_SMOKE_OBJ:-/data/local/tmp/core_task_smoke.o}"
OUT="${OUT:-dataset_cache/ebpf_runs/probe.json}"
SYSCALL_ABI="${SYSCALL_ABI:-aarch64}"

quote_remote() {
  printf "'%s'" "$(printf "%s" "$1" | sed "s/'/'\\\\''/g")"
}

adb_shell_cmd() {
  if [[ "$USE_SU" == "1" ]]; then
    "$ADB" shell su -c "$1"
  else
    "$ADB" shell "$1"
  fi
}

"$ADB" devices

# `adb root` only works on userdebug/eng builds. This Pixel uses Magisk by
# default, so keep adb unrooted and execute privileged checks through `su -c`.
if [[ "$USE_SU" == "1" ]]; then
  "$ADB" shell su -c id
  PROBE_SU_ARG="--su"
else
  "$ADB" root >/dev/null 2>&1 || true
  PROBE_SU_ARG="--no-su"
fi

adb_shell_cmd "chmod 755 $(quote_remote "$BPFTOOL") 2>/dev/null || true"
adb_shell_cmd "test -x $(quote_remote "$BPFTOOL") && $(quote_remote "$BPFTOOL") version || { echo 'bpftool not executable at $BPFTOOL'; exit 1; }"

adb_shell_cmd "zcat /proc/config.gz 2>/dev/null | grep -E 'CONFIG_BPF|CONFIG_BPF_SYSCALL|CONFIG_DEBUG_INFO_BTF|CONFIG_FTRACE|CONFIG_FTRACE_SYSCALLS|CONFIG_KPROBES|CONFIG_KPROBE_EVENTS|CONFIG_UPROBES|CONFIG_UPROBE_EVENTS|CONFIG_BPF_EVENTS|CONFIG_SECURITY_SELINUX_BOOTPARAM' || true"
adb_shell_cmd "zcat /proc/config.gz 2>/dev/null | grep -q '^CONFIG_BPF_SYSCALL=y$'"
adb_shell_cmd "zcat /proc/config.gz 2>/dev/null | grep -q '^CONFIG_FTRACE_SYSCALLS=y$'"
adb_shell_cmd "zcat /proc/config.gz 2>/dev/null | grep -q '^CONFIG_KPROBES=y$'"
adb_shell_cmd "zcat /proc/config.gz 2>/dev/null | grep -q '^CONFIG_KPROBE_EVENTS=y$'"
adb_shell_cmd "zcat /proc/config.gz 2>/dev/null | grep -q '^CONFIG_UPROBES=y$'"
adb_shell_cmd "zcat /proc/config.gz 2>/dev/null | grep -q '^CONFIG_UPROBE_EVENTS=y$'"
adb_shell_cmd "test -d /sys/kernel/tracing/events/syscalls && ls /sys/kernel/tracing/events/syscalls | head || { echo 'no syscall tracepoints'; exit 1; }"
adb_shell_cmd "test -w /sys/kernel/tracing/kprobe_events"
adb_shell_cmd "test -w /sys/kernel/tracing/uprobe_events"
adb_shell_cmd "wc -l /sys/kernel/tracing/available_events 2>/dev/null || true"
echo "syscall_abi=$SYSCALL_ABI"

if [[ -f "$VMLINUX_BTF_HOST" ]]; then
  "$ADB" push "$VMLINUX_BTF_HOST" "$VMLINUX_BTF"
else
  echo "host external BTF not found at $VMLINUX_BTF_HOST; using device path $VMLINUX_BTF"
fi

adb_shell_cmd "test -s $(quote_remote "$VMLINUX_BTF")"
adb_shell_cmd "sha256sum $(quote_remote "$VMLINUX_BTF")"
adb_shell_cmd "$(quote_remote "$BPFTOOL") btf dump file $(quote_remote "$VMLINUX_BTF") format raw | head -20"

if [[ -f "$BTF_SMOKE_OBJ_HOST" ]]; then
  "$ADB" push "$BTF_SMOKE_OBJ_HOST" "$BTF_SMOKE_OBJ"
else
  echo "host CO-RE smoke object not found at $BTF_SMOKE_OBJ_HOST; using device path $BTF_SMOKE_OBJ"
fi

adb_shell_cmd "test -s $(quote_remote "$BTF_SMOKE_OBJ")"
adb_shell_cmd "rm -f /sys/fs/bpf/memo_core_btf_smoke"
adb_shell_cmd "$(quote_remote "$BPFTOOL") prog load $(quote_remote "$BTF_SMOKE_OBJ") /sys/fs/bpf/memo_core_btf_smoke type tracepoint kernel_btf $(quote_remote "$VMLINUX_BTF")"
echo "btf_core_load_rc=0"
adb_shell_cmd "$(quote_remote "$BPFTOOL") prog show pinned /sys/fs/bpf/memo_core_btf_smoke"
adb_shell_cmd "rm -f /sys/fs/bpf/memo_core_btf_smoke"

python3 scripts/android_ebpf/android_ebpf_collect.py probe \
  --adb "$ADB" \
  "$PROBE_SU_ARG" \
  --bpftool "$BPFTOOL" \
  --out "$OUT"
