/* SPDX-License-Identifier: GPL-2.0 OR BSD-3-Clause */
#ifndef MEMO_APPFLOW_H
#define MEMO_APPFLOW_H

#define MEMO_TASK_COMM_LEN 16
#define MEMO_DETAIL_LEN 128

enum memo_event_type {
    MEMO_EVENT_BINDER = 1,
    MEMO_EVENT_FILE = 2,
    MEMO_EVENT_MEMORY = 3,
    MEMO_EVENT_NETWORK = 4,
    MEMO_EVENT_SCHED = 5,
    MEMO_EVENT_PROCESS_FORK = 6,
    MEMO_EVENT_PROCESS_EXIT = 7,
    MEMO_EVENT_INPUT = 8,
    MEMO_EVENT_PROCESS_EXEC = 9,
};

enum memo_memory_code {
    MEMO_RECLAIM_BEGIN = 1,
    MEMO_RECLAIM_END = 2,
    MEMO_KSWAPD_WAKE = 3,
};

enum memo_network_code {
    MEMO_NET_SENDTO = 1,
    MEMO_NET_RECVFROM = 2,
};

enum memo_sched_code {
    MEMO_SCHED_SWITCH = 1,
    MEMO_SCHED_WAKEUP = 2,
};

struct memo_event {
    unsigned long long ts_ns;
    unsigned int uid;
    unsigned int pid;
    unsigned int tid;
    unsigned int event_type;
    unsigned int arg0;
    unsigned int arg1;
    unsigned int arg2;
    unsigned int arg3;
    char comm[MEMO_TASK_COMM_LEN];
    char detail[MEMO_DETAIL_LEN];
};

#endif
