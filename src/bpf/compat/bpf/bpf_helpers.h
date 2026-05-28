/* SPDX-License-Identifier: GPL-2.0 OR BSD-3-Clause */
#ifndef MEMO_COMPAT_BPF_HELPERS_H
#define MEMO_COMPAT_BPF_HELPERS_H

#include <linux/bpf.h>

#define SEC(name) __attribute__((section(name), used))
#define __uint(name, val) int (*name)[val]
#define __type(name, val) typeof(val) *name

#ifndef __always_inline
#define __always_inline inline __attribute__((always_inline))
#endif

static void *(*bpf_map_lookup_elem)(void *map, const void *key) = (void *)BPF_FUNC_map_lookup_elem;
static long (*bpf_map_update_elem)(void *map, const void *key, const void *value, unsigned long long flags) =
    (void *)BPF_FUNC_map_update_elem;
static long (*bpf_map_delete_elem)(void *map, const void *key) = (void *)BPF_FUNC_map_delete_elem;
static long (*bpf_probe_read_kernel)(void *dst, unsigned int size, const void *unsafe_ptr) =
    (void *)BPF_FUNC_probe_read_kernel;
static long (*bpf_probe_read_user_str)(void *dst, unsigned int size, const void *unsafe_ptr) =
    (void *)BPF_FUNC_probe_read_user_str;
static long (*bpf_probe_read_kernel_str)(void *dst, unsigned int size, const void *unsafe_ptr) =
    (void *)BPF_FUNC_probe_read_kernel_str;
static unsigned long long (*bpf_ktime_get_ns)(void) = (void *)BPF_FUNC_ktime_get_ns;
static unsigned long long (*bpf_get_current_pid_tgid)(void) = (void *)BPF_FUNC_get_current_pid_tgid;
static unsigned long long (*bpf_get_current_uid_gid)(void) = (void *)BPF_FUNC_get_current_uid_gid;
static long (*bpf_get_current_comm)(void *buf, unsigned int size_of_buf) = (void *)BPF_FUNC_get_current_comm;
static long (*bpf_perf_event_output)(void *ctx, void *map, unsigned long long flags, void *data,
                                     unsigned long long size) = (void *)BPF_FUNC_perf_event_output;
static long (*bpf_trace_printk)(const char *fmt, unsigned int fmt_size, ...) =
    (void *)BPF_FUNC_trace_printk;

#endif
