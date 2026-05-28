/* SPDX-License-Identifier: GPL-2.0 OR BSD-3-Clause */
#ifndef MEMO_COMPAT_BPF_CORE_READ_H
#define MEMO_COMPAT_BPF_CORE_READ_H

#include <bpf/bpf_helpers.h>

#ifndef __builtin_preserve_access_index
#define __builtin_preserve_access_index(x) (x)
#endif

#define bpf_core_read(dst, sz, src) \
    bpf_probe_read_kernel((dst), (sz), (const void *)__builtin_preserve_access_index(src))

#define BPF_CORE_READ(src, field) ({          \
    typeof((src)->field) __r;                 \
    bpf_core_read(&__r, sizeof(__r), &((src)->field)); \
    __r;                                      \
})

#endif
