// SPDX-License-Identifier: GPL-2.0
/*
 * Lightweight Android emulator eBPF verifier.
 *
 * This program is intentionally trace-buffer based: it can be loaded with
 * bpftool on a rooted emulator without a custom user-space ring-buffer daemon.
 * The production contract is still memo_events.bpf.c; this file is for proving
 * that CONFIG_FTRACE_SYSCALLS-backed syscall tracepoints are live and useful.
 */

#include <linux/bpf.h>
#include <bpf/bpf_helpers.h>

#define TASK_COMM_LEN 16
#define PATH_PREFIX_LEN 96

struct trace_event_raw_sys_enter {
    unsigned short common_type;
    unsigned char common_flags;
    unsigned char common_preempt_count;
    int common_pid;
    long id;
    unsigned long args[6];
};

struct trace_event_raw_binder_transaction {
    unsigned short common_type;
    unsigned char common_flags;
    unsigned char common_preempt_count;
    int common_pid;
    int debug_id;
    int target_node;
    int to_proc;
    int to_thread;
    int reply;
    unsigned int code;
    unsigned int flags;
};

static __always_inline int emit_openat(struct trace_event_raw_sys_enter *ctx)
{
    char comm[TASK_COMM_LEN];
    char path[PATH_PREFIX_LEN];
    const char *filename = (const char *)ctx->args[1];
    __u64 pid_tgid = bpf_get_current_pid_tgid();
    __u32 pid = pid_tgid >> 32;

    __builtin_memset(path, 0, sizeof(path));
    bpf_get_current_comm(comm, sizeof(comm));
    bpf_probe_read_user_str(path, sizeof(path), filename);
    bpf_printk("MEMO_OPENAT pid=%u comm=%s path=%s", pid, comm, path);
    return 0;
}

SEC("tracepoint/syscalls/sys_enter_openat")
int memo_sys_enter_openat(struct trace_event_raw_sys_enter *ctx)
{
    return emit_openat(ctx);
}

SEC("tracepoint/syscalls/sys_enter_openat2")
int memo_sys_enter_openat2(struct trace_event_raw_sys_enter *ctx)
{
    return emit_openat(ctx);
}

SEC("tracepoint/binder/binder_transaction")
int memo_binder_transaction(struct trace_event_raw_binder_transaction *ctx)
{
    __u64 pid_tgid = bpf_get_current_pid_tgid();
    __u32 pid = pid_tgid >> 32;

    bpf_printk("MEMO_BINDER pid=%u code=%u to_proc=%d", pid, ctx->code, ctx->to_proc);
    return 0;
}

SEC("tracepoint/vmscan/mm_vmscan_direct_reclaim_begin")
int memo_reclaim_begin(void *ctx)
{
    __u64 pid_tgid = bpf_get_current_pid_tgid();
    __u32 pid = pid_tgid >> 32;

    bpf_printk("MEMO_RECLAIM_BEGIN pid=%u", pid);
    return 0;
}

SEC("tracepoint/vmscan/mm_vmscan_direct_reclaim_end")
int memo_reclaim_end(void *ctx)
{
    __u64 pid_tgid = bpf_get_current_pid_tgid();
    __u32 pid = pid_tgid >> 32;

    bpf_printk("MEMO_RECLAIM_END pid=%u", pid);
    return 0;
}

char LICENSE[] SEC("license") = "GPL";
