# 0. Overall

In the MEMO-Appflow project, we need to collect some information in the system. For example, we need to collect the syscall `openat` to optimize file I/O. So, we implemented a set of eBPF hooks in the application.
To make the eBPF hooks runnable in the device, we also flashed a custom Android Kernel into the phone with root available.

Device: Google Pixel 5

# 1. eBPF Requirements

To make the prediction more accurate, we need 14 tracepoints, as follows:

```text
tracepoint/binder/binder_transaction (IPC trace)
tracepoint/syscalls/sys_enter_openat
tracepoint/syscalls/sys_enter_openat2 (file I/O optimization, also, it's used to detect whether camera is used)
tracepoint/syscalls/sys_enter_sendto
tracepoint/syscalls/sys_enter_recvfrom (network I/O trace, especially for UDP)
tracepoint/vmscan/mm_vmscan_direct_reclaim_begin
tracepoint/vmscan/mm_vmscan_direct_reclaim_end
tracepoint/vmscan/mm_vmscan_kswapd_wake (memory pressure trace)
tracepoint/sched/sched_switch
tracepoint/sched/sched_wakeup (scheduling trace)
tracepoint/sched/sched_process_fork
tracepoint/sched/sched_process_exit
tracepoint/sched/sched_process_exec (process lifecycle trace)
tracepoint/input/input_event (user action trace)
```

They are all needed. To make them runnable in Android, we flashed a custom kernel with root available and SELinux disabled.

# 2. Custom Kernel

The preferred parameters are as follows:

- [x] `CONFIG_BPF`
- [x] `CONFIG_BPF_SYSCALL`
- [x] `CONFIG_BPF_JIT`
- [x] `CONFIG_FTRACE`
- [x] `CONFIG_FTRACE_SYSCALLS`
- [x] `CONFIG_KPROBES`
- [x] `CONFIG_UPROBES`
- [ ] `CONFIG_DEBUG_INFO_BTF`

But, when we switched `CONFIG_DEBUG_INFO_BTF` on, the cross compilation will fail as the kernel is not bootable.
To fix it, we used an alternative way to do it. We used an external BTF (/data/local/tmp/vmlinux-4.19.278-ftrace-syscalls.raw.btf) to make `bpftrace` runnable. Also, `tracefs` is
missing. To fix it, we wrote an external capabilities text file (`/data/local/tmp/traceable-functions.txt`). After these works, the bpftrace is bootable now.

Also, disabling SELinux in Android forever requires us to modifying the kernel tree. Function `selinux_is_enforcing` is returning false forever now.

To make root available, we installed Magisk in the phone.

# 3. Cross Compilations

There are also some tools that must be cross compiled into Android, as follows:

- bpftool
- bpftrace

To complete these cross compilations, we also cross compiled some dependencies:

- libelf
- libbpf
- zlib
- llvm

In bpftrace, we selected libbpf backend instead of BCC backend. To finish this cross compilation, we provided a fake BCC with empty implementation first. Also, there are some capabilities missing
in the kernel. As it does not match the minimum version requirement, we also applied some patches to it. For example, the `printf` function. We make the memory used in this function allocated statically
in stack instead of dynamically allcated in heap.

Also, the libraries, external BTF and external capabilities text file are all configured in the command line. To make the usage easier, we wrote a wrapper program and it's `/data/local/tmp/bpftrace`.
We also registered it into `/bin` with Magisk modules.
