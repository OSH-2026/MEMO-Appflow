// SPDX-License-Identifier: GPL-2.0
/*
 * MEMO-Appflow eBPF event skeleton.
 *
 * This file mirrors the short-term report contract: Binder metadata, cold
 * launch file opens, and memory pressure are emitted as fixed-size events over
 * a ring buffer. It is intended for a rooted/userdebug Android target with
 * libbpf headers and target-matched tracepoint layouts.
 */

#include <linux/bpf.h>
#include <bpf/bpf_helpers.h>
#include <bpf/bpf_tracing.h>

#define TASK_COMM_LEN 16
#define MEMO_PATH_PREFIX_LEN 64

enum memo_event_type {
    MEMO_EVENT_BINDER = 1,
    MEMO_EVENT_FILE = 2,
    MEMO_EVENT_MEMORY = 3,
};

enum memo_memory_arg {
    MEMO_RECLAIM_BEGIN = 1,
    MEMO_RECLAIM_END = 2,
    MEMO_KSWAPD_WAKE = 3,
};

struct memo_event {
    __u64 ts_ns;
    __u32 uid;
    __u32 pid;
    __u32 tid;
    __u32 event_type;
    __u32 arg0;
    __u32 arg1;
    char comm[TASK_COMM_LEN];
    char detail[MEMO_PATH_PREFIX_LEN];
};

struct {
    __uint(type, BPF_MAP_TYPE_RINGBUF);
    __uint(max_entries, 1 << 20);
} events SEC(".maps");

struct {
    __uint(type, BPF_MAP_TYPE_LRU_HASH);
    __uint(max_entries, 8192);
    __type(key, __u64);
    __type(value, __u64);
} file_category_counts SEC(".maps");

struct {
    __uint(type, BPF_MAP_TYPE_HASH);
    __uint(max_entries, 4096);
    __type(key, __u64);
    __type(value, __u64);
} reclaim_start_ns SEC(".maps");

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

struct trace_event_raw_sys_enter {
    unsigned short common_type;
    unsigned char common_flags;
    unsigned char common_preempt_count;
    int common_pid;
    long id;
    unsigned long args[6];
};

struct trace_event_raw_mm_vmscan_direct_reclaim_end {
    unsigned short common_type;
    unsigned char common_flags;
    unsigned char common_preempt_count;
    int common_pid;
    unsigned long nr_reclaimed;
};

static __always_inline struct memo_event *reserve_event(__u32 event_type)
{
    struct memo_event *event = bpf_ringbuf_reserve(&events, sizeof(*event), 0);
    __u64 pid_tgid;
    __u64 uid_gid;

    if (!event)
        return 0;

    pid_tgid = bpf_get_current_pid_tgid();
    uid_gid = bpf_get_current_uid_gid();
    event->ts_ns = bpf_ktime_get_ns();
    event->uid = (__u32)uid_gid;
    event->pid = pid_tgid >> 32;
    event->tid = (__u32)pid_tgid;
    event->event_type = event_type;
    event->arg0 = 0;
    event->arg1 = 0;
    __builtin_memset(event->detail, 0, sizeof(event->detail));
    bpf_get_current_comm(event->comm, sizeof(event->comm));
    return event;
}

SEC("tracepoint/binder/binder_transaction")
int on_binder_tx(struct trace_event_raw_binder_transaction *ctx)
{
    struct memo_event *event = reserve_event(MEMO_EVENT_BINDER);

    if (!event)
        return 0;

    event->arg0 = ctx->code;
    event->arg1 = ctx->to_proc;
    bpf_ringbuf_submit(event, 0);
    return 0;
}

static __always_inline int on_openat_common(struct trace_event_raw_sys_enter *ctx)
{
    struct memo_event *event = reserve_event(MEMO_EVENT_FILE);
    const char *filename = (const char *)ctx->args[1];
    __u64 key;
    __u64 initial = 1;
    __u64 *count;

    if (!event)
        return 0;

    bpf_probe_read_user_str(event->detail, sizeof(event->detail), filename);
    event->arg0 = 0;
    event->arg1 = 0;

    /*
     * The kernel side only keeps a coarse per-uid hash bucket count. The user
     * daemon performs the final dex/so/asset/database/cache/model category
     * mapping and drops raw paths from normalized JSONL output.
     */
    key = ((__u64)event->uid << 32) ^ (__u64)event->pid;
    count = bpf_map_lookup_elem(&file_category_counts, &key);
    if (count)
        __sync_fetch_and_add(count, 1);
    else
        bpf_map_update_elem(&file_category_counts, &key, &initial, BPF_ANY);

    bpf_ringbuf_submit(event, 0);
    return 0;
}

SEC("tracepoint/syscalls/sys_enter_openat")
int on_openat(struct trace_event_raw_sys_enter *ctx)
{
    return on_openat_common(ctx);
}

SEC("tracepoint/syscalls/sys_enter_openat2")
int on_openat2(struct trace_event_raw_sys_enter *ctx)
{
    return on_openat_common(ctx);
}

SEC("tracepoint/vmscan/mm_vmscan_direct_reclaim_begin")
int on_direct_reclaim_begin(void *ctx)
{
    struct memo_event *event = reserve_event(MEMO_EVENT_MEMORY);
    __u64 pid_tgid = bpf_get_current_pid_tgid();
    __u64 ts = bpf_ktime_get_ns();

    if (!event)
        return 0;

    event->arg0 = MEMO_RECLAIM_BEGIN;
    event->arg1 = 0;
    bpf_map_update_elem(&reclaim_start_ns, &pid_tgid, &ts, BPF_ANY);
    bpf_ringbuf_submit(event, 0);
    return 0;
}

SEC("tracepoint/vmscan/mm_vmscan_direct_reclaim_end")
int on_direct_reclaim_end(struct trace_event_raw_mm_vmscan_direct_reclaim_end *ctx)
{
    struct memo_event *event = reserve_event(MEMO_EVENT_MEMORY);
    __u64 pid_tgid = bpf_get_current_pid_tgid();
    __u64 *start = bpf_map_lookup_elem(&reclaim_start_ns, &pid_tgid);

    if (!event)
        return 0;

    event->arg0 = MEMO_RECLAIM_END;
    event->arg1 = ctx ? (__u32)ctx->nr_reclaimed : 0;
    if (start) {
        event->arg1 = (__u32)((event->ts_ns - *start) / 1000000);
        bpf_map_delete_elem(&reclaim_start_ns, &pid_tgid);
    }
    bpf_ringbuf_submit(event, 0);
    return 0;
}

SEC("tracepoint/vmscan/mm_vmscan_kswapd_wake")
int on_kswapd_wake(void *ctx)
{
    struct memo_event *event = reserve_event(MEMO_EVENT_MEMORY);

    if (!event)
        return 0;

    event->arg0 = MEMO_KSWAPD_WAKE;
    event->arg1 = 0;
    bpf_ringbuf_submit(event, 0);
    return 0;
}

char LICENSE[] SEC("license") = "GPL";
