# Android Kernel eBPF Config for MEMO

Your rooted device currently has enough generic eBPF to run `bpftool`, but not
enough tracing support for the full MEMO collector.

Observed on the connected device:

```text
CONFIG_BPF=y
CONFIG_BPF_SYSCALL=y
CONFIG_BPF_EVENTS=y
CONFIG_FTRACE=y
# CONFIG_FTRACE_SYSCALLS is not set
# CONFIG_KPROBES is not set
CONFIG_DEBUG_INFO_BTF is not set
```

This means:

- Binder, scheduler, and memory pressure tracepoints can be used.
- `syscalls:sys_enter_openat`, `syscalls:sys_enter_sendto`, and
  `syscalls:sys_enter_recvfrom` do not exist, so MEMO cannot collect file path
  or syscall-level network evidence from tracepoints.
- kprobe fallback paths are not available.
- CO-RE/BTF workflows are limited because `/sys/kernel/btf/vmlinux` is absent.

## Required Config

Apply this repo fragment to the Android kernel tree:

```text
scripts/android_ebpf/kernel/memo_android_ebpf.config
```

The most important symbols are:

```text
CONFIG_BPF=y
CONFIG_BPF_SYSCALL=y
CONFIG_BPF_JIT=y
CONFIG_BPF_EVENTS=y
CONFIG_TRACING=y
CONFIG_FTRACE=y
CONFIG_HAVE_SYSCALL_TRACEPOINTS=y
CONFIG_FTRACE_SYSCALLS=y
CONFIG_KPROBES=y
CONFIG_KPROBE_EVENTS=y
CONFIG_UPROBES=y
CONFIG_UPROBE_EVENTS=y
CONFIG_DEBUG_INFO_BTF=y
CONFIG_IKCONFIG_PROC=y
```

`CONFIG_FTRACE_SYSCALLS=y` is the key setting for MEMO file/network evidence.
Without it, `/sys/kernel/tracing/events/syscalls` stays absent.

## Applying It

In a classic kernel tree:

```bash
cp /path/to/MEMO-OS/scripts/android_ebpf/kernel/memo_android_ebpf.config .
scripts/kconfig/merge_config.sh -m .config memo_android_ebpf.config
make olddefconfig
```

Then verify the final `.config`:

```bash
grep -E 'CONFIG_BPF_SYSCALL|CONFIG_FTRACE_SYSCALLS|CONFIG_KPROBES|CONFIG_KPROBE_EVENTS|CONFIG_DEBUG_INFO_BTF|CONFIG_IKCONFIG_PROC' .config
```

For Android GKI/Kleaf builds, add the same symbols to the device/vendor kernel
config fragment used by the build target, then rebuild the boot image or kernel
image normally for that device.

## Device Verification

After flashing or booting the rebuilt kernel:

```bash
adb shell su -c "zcat /proc/config.gz | grep -E 'CONFIG_BPF_SYSCALL|CONFIG_FTRACE_SYSCALLS|CONFIG_KPROBES|CONFIG_KPROBE_EVENTS|CONFIG_DEBUG_INFO_BTF'"
adb shell su -c "ls /sys/kernel/tracing/events/syscalls | grep -E 'sys_enter_openat|sys_enter_sendto|sys_enter_recvfrom'"
adb shell su -c "test -r /sys/kernel/btf/vmlinux && echo BTF_OK || echo BTF_MISSING"
./probe.sh
```

Expected MEMO probe result:

```text
"syscalls:sys_enter_openat": true
"vmscan:mm_vmscan_direct_reclaim_begin": true
"sched:sched_process_fork": true
"bpftool_path": "/data/local/tmp/bpftool"
```

If this is a physical production phone, you usually cannot fix these symbols
from userspace or Magisk alone. They are compile-time kernel options, so you
need the matching kernel source, the correct device defconfig, an unlockable
bootloader, and a flashable or bootable rebuilt kernel image.
