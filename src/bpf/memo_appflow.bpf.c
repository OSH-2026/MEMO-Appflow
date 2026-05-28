// SPDX-License-Identifier: GPL-2.0
#include <linux/bpf.h>
#include <bpf/bpf_helpers.h>

enum memo_counter_key {
    MEMO_COUNTER_BINDER = 1,
    MEMO_COUNTER_FILE = 2,
    MEMO_COUNTER_RECLAIM = 3,
    MEMO_COUNTER_SENDTO = 4,
    MEMO_COUNTER_RECVFROM = 5,
    MEMO_COUNTER_SCHED = 6,
    MEMO_COUNTER_PROCESS_FORK = 7,
    MEMO_COUNTER_PROCESS_EXIT = 8,
    MEMO_COUNTER_PROCESS_EXEC = 9,
    MEMO_COUNTER_INPUT = 10,
};

struct {
    __uint(type, BPF_MAP_TYPE_ARRAY);
    __uint(max_entries, 16);
    __type(key, unsigned int);
    __type(value, unsigned long long);
} counters SEC(".maps");

static __always_inline void memo_count(unsigned int key)
{
    unsigned long long *value = bpf_map_lookup_elem(&counters, &key);

    if (value)
        __sync_fetch_and_add(value, 1);
}

SEC("tracepoint/binder/binder_transaction")
int handle_binder_transaction(void *ctx)
{
    memo_count(MEMO_COUNTER_BINDER);
    return 0;
}

SEC("tracepoint/syscalls/sys_enter_openat")
int handle_openat(void *ctx)
{
    memo_count(MEMO_COUNTER_FILE);
    return 0;
}

SEC("tracepoint/syscalls/sys_enter_openat2")
int handle_openat2(void *ctx)
{
    memo_count(MEMO_COUNTER_FILE);
    return 0;
}

SEC("tracepoint/syscalls/sys_enter_sendto")
int handle_sendto(void *ctx)
{
    memo_count(MEMO_COUNTER_SENDTO);
    return 0;
}

SEC("tracepoint/syscalls/sys_enter_recvfrom")
int handle_recvfrom(void *ctx)
{
    memo_count(MEMO_COUNTER_RECVFROM);
    return 0;
}

SEC("tracepoint/vmscan/mm_vmscan_direct_reclaim_begin")
int handle_direct_reclaim_begin(void *ctx)
{
    memo_count(MEMO_COUNTER_RECLAIM);
    return 0;
}

SEC("tracepoint/vmscan/mm_vmscan_direct_reclaim_end")
int handle_direct_reclaim_end(void *ctx)
{
    memo_count(MEMO_COUNTER_RECLAIM);
    return 0;
}

SEC("tracepoint/vmscan/mm_vmscan_kswapd_wake")
int handle_kswapd_wake(void *ctx)
{
    memo_count(MEMO_COUNTER_RECLAIM);
    return 0;
}

SEC("tracepoint/sched/sched_switch")
int handle_sched_switch(void *ctx)
{
    memo_count(MEMO_COUNTER_SCHED);
    return 0;
}

SEC("tracepoint/sched/sched_wakeup")
int handle_sched_wakeup(void *ctx)
{
    memo_count(MEMO_COUNTER_SCHED);
    return 0;
}

SEC("tracepoint/sched/sched_process_fork")
int handle_process_fork(void *ctx)
{
    memo_count(MEMO_COUNTER_PROCESS_FORK);
    return 0;
}

SEC("tracepoint/sched/sched_process_exit")
int handle_process_exit(void *ctx)
{
    memo_count(MEMO_COUNTER_PROCESS_EXIT);
    return 0;
}

SEC("tracepoint/sched/sched_process_exec")
int handle_process_exec(void *ctx)
{
    memo_count(MEMO_COUNTER_PROCESS_EXEC);
    return 0;
}

SEC("tracepoint/input/input_event")
int handle_input_event(void *ctx)
{
    memo_count(MEMO_COUNTER_INPUT);
    return 0;
}

char LICENSE[] SEC("license") = "GPL";
