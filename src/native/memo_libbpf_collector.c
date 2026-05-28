// SPDX-License-Identifier: GPL-2.0 OR BSD-3-Clause
#include <errno.h>
#include <fcntl.h>
#include <getopt.h>
#include <linux/bpf.h>
#include <linux/perf_event.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/utsname.h>
#include <time.h>
#include <unistd.h>

#define MEMO_BPF_OBJECT "/data/local/tmp/memo/memo_appflow.bpf.o"
#define MEMO_PIN_PROG_DIR "/sys/fs/bpf/memo_appflow"
#define MEMO_PIN_MAP_DIR "/sys/fs/bpf/memo_maps"
#define MEMO_PIN_COUNTERS MEMO_PIN_MAP_DIR "/counters"

#ifndef __NR_bpf
#define __NR_bpf 280
#endif

#ifndef __NR_perf_event_open
#define __NR_perf_event_open 241
#endif

#ifndef PERF_FLAG_FD_CLOEXEC
#define PERF_FLAG_FD_CLOEXEC 8UL
#endif

static volatile sig_atomic_t exiting;

struct options {
    int duration_sec;
    int max_events;
    const char *output_path;
    int feature_check_only;
};

struct tracepoint_link {
    const char *group;
    const char *name;
    const char *prog_pin;
    int *fds;
    int fd_count;
    int required;
};

static void on_signal(int signo)
{
    (void)signo;
    exiting = 1;
}

static int path_exists(const char *path)
{
    struct stat st;
    return stat(path, &st) == 0;
}

static int sys_bpf(enum bpf_cmd cmd, union bpf_attr *attr, unsigned int size)
{
    return (int)syscall(__NR_bpf, cmd, attr, size);
}

static int obj_get(const char *path)
{
    union bpf_attr attr;

    memset(&attr, 0, sizeof(attr));
    attr.pathname = (uint64_t)(uintptr_t)path;
    return sys_bpf(BPF_OBJ_GET, &attr, sizeof(attr));
}

static int map_lookup_elem(int map_fd, const void *key, void *value)
{
    union bpf_attr attr;

    memset(&attr, 0, sizeof(attr));
    attr.map_fd = (uint32_t)map_fd;
    attr.key = (uint64_t)(uintptr_t)key;
    attr.value = (uint64_t)(uintptr_t)value;
    return sys_bpf(BPF_MAP_LOOKUP_ELEM, &attr, sizeof(attr));
}

static int sys_perf_event_open(struct perf_event_attr *attr, pid_t pid, int cpu, int group_fd,
                               unsigned long flags)
{
    return (int)syscall(__NR_perf_event_open, attr, pid, cpu, group_fd, flags);
}

static const char *find_tracefs(void)
{
    if (path_exists("/sys/kernel/tracing/available_events"))
        return "/sys/kernel/tracing";
    if (path_exists("/sys/kernel/debug/tracing/available_events"))
        return "/sys/kernel/debug/tracing";
    return NULL;
}

static const char *find_bpftool(void)
{
    if (path_exists("/data/local/tmp/memo/bpftool"))
        return "/data/local/tmp/memo/bpftool";
    if (path_exists("/system/bin/bpftool"))
        return "/system/bin/bpftool";
    if (path_exists("/vendor/bin/bpftool"))
        return "/vendor/bin/bpftool";
    return "bpftool";
}

static int run_command(const char *cmd)
{
    int rc = system(cmd);
    if (rc != 0)
        fprintf(stderr, "command failed (%d): %s\n", rc, cmd);
    return rc == 0 ? 0 : -1;
}

static int ensure_bpf_loaded(void)
{
    char cmd[1024];
    const char *bpftool = find_bpftool();

    if (!path_exists(MEMO_BPF_OBJECT)) {
        fprintf(stderr, "BPF object missing: %s\n", MEMO_BPF_OBJECT);
        return -1;
    }

    snprintf(cmd, sizeof(cmd),
             "find " MEMO_PIN_PROG_DIR " " MEMO_PIN_MAP_DIR " -maxdepth 1 -type f -delete >/dev/null 2>/dev/null; "
             "rmdir " MEMO_PIN_PROG_DIR " " MEMO_PIN_MAP_DIR " >/dev/null 2>/dev/null; "
             "mkdir -p " MEMO_PIN_PROG_DIR " " MEMO_PIN_MAP_DIR "; "
             "%s prog loadall " MEMO_BPF_OBJECT " " MEMO_PIN_PROG_DIR
             " pinmaps " MEMO_PIN_MAP_DIR " >/dev/null",
             bpftool);
    return run_command(cmd);
}

static int read_int_file(const char *path)
{
    char buf[64];
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    ssize_t n;

    if (fd < 0)
        return -1;
    n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0)
        return -1;
    buf[n] = '\0';
    return atoi(buf);
}

static int tracepoint_id(const char *tracefs, const char *group, const char *name)
{
    char path[256];

    snprintf(path, sizeof(path), "%s/events/%s/%s/id", tracefs, group, name);
    return read_int_file(path);
}

static int attach_tracepoint_on_cpu(int prog_fd, int tracepoint, int cpu, int *stage, int *saved_errno)
{
    struct perf_event_attr attr;
    int fd;

    memset(&attr, 0, sizeof(attr));
    attr.type = PERF_TYPE_TRACEPOINT;
    attr.size = sizeof(attr);
    attr.config = (uint64_t)tracepoint;
    attr.sample_period = 1;
    attr.sample_type = PERF_SAMPLE_RAW;
    attr.wakeup_events = 1;

    fd = sys_perf_event_open(&attr, -1, cpu, -1, PERF_FLAG_FD_CLOEXEC);
    if (fd < 0) {
        if (stage)
            *stage = 1;
        if (saved_errno)
            *saved_errno = errno;
        return -1;
    }
    if (ioctl(fd, PERF_EVENT_IOC_SET_BPF, prog_fd) < 0) {
        if (stage)
            *stage = 2;
        if (saved_errno)
            *saved_errno = errno;
        close(fd);
        return -1;
    }
    if (ioctl(fd, PERF_EVENT_IOC_ENABLE, 0) < 0) {
        if (stage)
            *stage = 3;
        if (saved_errno)
            *saved_errno = errno;
        close(fd);
        return -1;
    }
    return fd;
}

static int attach_tracepoint(const char *tracefs, struct tracepoint_link *link, int cpu_count)
{
    int id = tracepoint_id(tracefs, link->group, link->name);
    int prog_fd;
    int attached = 0;
    int first_stage = 0;
    int first_errno = 0;

    if (id < 0)
        return link->required ? -1 : 0;

    prog_fd = obj_get(link->prog_pin);
    if (prog_fd < 0) {
        if (link->required)
            fprintf(stderr, "failed to open pinned BPF program %s: %s\n", link->prog_pin, strerror(errno));
        return link->required ? -1 : 0;
    }

    link->fds = calloc((size_t)cpu_count, sizeof(*link->fds));
    if (!link->fds) {
        close(prog_fd);
        return -1;
    }
    link->fd_count = cpu_count;
    for (int cpu = 0; cpu < cpu_count; cpu++)
        link->fds[cpu] = -1;

    for (int cpu = 0; cpu < cpu_count; cpu++) {
        int stage = 0;
        int err_no = 0;
        int fd = attach_tracepoint_on_cpu(prog_fd, id, cpu, &stage, &err_no);
        if (fd < 0) {
            if (!first_errno) {
                first_stage = stage;
                first_errno = err_no;
            }
            continue;
        }
        link->fds[cpu] = fd;
        attached++;
    }
    close(prog_fd);

    if (attached == 0 && link->required) {
        fprintf(stderr, "failed to attach required tracepoint: %s:%s stage=%d errno=%d %s\n",
                link->group, link->name, first_stage, first_errno, strerror(first_errno));
        return -1;
    }
    return attached;
}

static void close_tracepoints(struct tracepoint_link *links, int link_count)
{
    for (int i = 0; i < link_count; i++) {
        if (!links[i].fds)
            continue;
        for (int cpu = 0; cpu < links[i].fd_count; cpu++) {
            if (links[i].fds[cpu] >= 0)
                close(links[i].fds[cpu]);
        }
        free(links[i].fds);
        links[i].fds = NULL;
    }
}

static int feature_check(void)
{
    struct utsname uts;
    const char *tracefs = find_tracefs();
    int ok = 1;

    if (uname(&uts) == 0) {
        printf("uname_machine=%s\n", uts.machine);
        printf("uname_release=%s\n", uts.release);
    } else {
        perror("uname");
        ok = 0;
    }

    printf("btf_vmlinux=%s\n", path_exists("/sys/kernel/btf/vmlinux") ? "yes" : "no");
    printf("tracefs=%s\n", tracefs ? tracefs : "no");
    printf("sched_process_exec=%s\n",
           tracefs && tracepoint_id(tracefs, "sched", "sched_process_exec") >= 0 ? "yes" : "no");
    printf("counter_map=yes\n");
    printf("collector=raw_ebpf_counter_map\n");
    printf("bpftrace=no\n");

    if (!tracefs)
        ok = 0;
    if (tracefs && tracepoint_id(tracefs, "sched", "sched_process_exec") < 0)
        ok = 0;
    return ok ? 0 : 1;
}

static void usage(const char *argv0)
{
    fprintf(stderr,
            "Usage: %s [--duration-sec N] [--max-events N] [--output PATH] [--feature-check]\n",
            argv0);
}

static int parse_args(int argc, char **argv, struct options *opts)
{
    static const struct option long_opts[] = {
        {"duration-sec", required_argument, 0, 'd'},
        {"max-events", required_argument, 0, 'm'},
        {"output", required_argument, 0, 'o'},
        {"feature-check", no_argument, 0, 'f'},
        {"help", no_argument, 0, 'h'},
        {},
    };
    int c;

    opts->duration_sec = 30;
    opts->max_events = 4000;
    opts->output_path = NULL;
    opts->feature_check_only = 0;

    while ((c = getopt_long(argc, argv, "d:m:o:fh", long_opts, NULL)) != -1) {
        switch (c) {
        case 'd':
            opts->duration_sec = atoi(optarg);
            break;
        case 'm':
            opts->max_events = atoi(optarg);
            break;
        case 'o':
            opts->output_path = optarg;
            break;
        case 'f':
            opts->feature_check_only = 1;
            break;
        case 'h':
            usage(argv[0]);
            return 1;
        default:
            usage(argv[0]);
            return -1;
        }
    }
    return 0;
}

static const char *counter_type(unsigned int key)
{
    switch (key) {
    case 1:
        return "binder";
    case 2:
        return "file";
    case 3:
        return "memory";
    case 4:
    case 5:
        return "network";
    case 6:
        return "sched";
    case 7:
        return "process_fork";
    case 8:
        return "process_exit";
    case 9:
        return "process_exec";
    case 10:
        return "input";
    default:
        return "unknown";
    }
}

static const char *counter_detail(unsigned int key)
{
    switch (key) {
    case 1:
        return "binder_transaction";
    case 2:
        return "openat";
    case 3:
        return "reclaim_or_kswapd";
    case 4:
        return "sendto";
    case 5:
        return "recvfrom";
    case 6:
        return "sched_activity";
    case 7:
        return "process_fork";
    case 8:
        return "process_exit";
    case 9:
        return "process_exec";
    case 10:
        return "input_event";
    default:
        return "counter";
    }
}

static unsigned int counter_arg0(unsigned int key)
{
    if (key == 4)
        return 1;
    if (key == 5)
        return 2;
    return key;
}

static int emit_counter_events(FILE *out, int max_events)
{
    int map_fd = obj_get(MEMO_PIN_COUNTERS);
    int emitted = 0;
    unsigned long long now_ns = (unsigned long long)time(NULL) * 1000000000ULL;

    if (map_fd < 0) {
        fprintf(stderr, "failed to open counter map %s: %s\n", MEMO_PIN_COUNTERS, strerror(errno));
        return -1;
    }

    for (unsigned int key = 1; key <= 10 && (max_events <= 0 || emitted < max_events); key++) {
        unsigned long long count = 0;
        unsigned long long limit;
        unsigned long long per_key_limit = max_events > 0 ? (unsigned long long)(max_events / 10) : 0;

        if (map_lookup_elem(map_fd, &key, &count) < 0 || count == 0)
            continue;
        if (max_events > 0 && per_key_limit == 0)
            per_key_limit = 1;
        limit = count;
        if (per_key_limit > 0 && limit > per_key_limit)
            limit = per_key_limit;
        if (max_events > 0 && limit > (unsigned long long)(max_events - emitted))
            limit = (unsigned long long)(max_events - emitted);
        for (unsigned long long i = 0; i < limit; i++) {
            fprintf(out, "MEMO\t%llu\t%s\t0\t0\t0\tebpf_counter\t%u\t%llu\t0\t0\t%s\n",
                    now_ns + (unsigned long long)emitted,
                    counter_type(key),
                    counter_arg0(key),
                    count,
                    counter_detail(key));
            emitted++;
        }
    }
    fprintf(out, "MEMO\t0\tstatus\t0\t0\t0\tmemo\t0\t0\t0\t0\tcollector_stopped\n");
    fflush(out);
    close(map_fd);
    return emitted > 0 ? 0 : -1;
}

int main(int argc, char **argv)
{
    struct options opts;
    struct tracepoint_link links[14];
    FILE *out = stdout;
    const char *tracefs;
    int link_count = 0;
    int cpu_count;
    int attached = 0;
    int err;

    err = parse_args(argc, argv, &opts);
    if (err != 0)
        return err > 0 ? 0 : 2;

    if (opts.feature_check_only)
        return feature_check();

    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    memset(links, 0, sizeof(links));

    if (opts.output_path) {
        out = fopen(opts.output_path, "w");
        if (!out) {
            fprintf(stderr, "failed to open output %s: %s\n", opts.output_path, strerror(errno));
            return 1;
        }
    }

    tracefs = find_tracefs();
    if (!tracefs) {
        fprintf(stderr, "tracefs is unavailable\n");
        err = 1;
        goto cleanup;
    }

    if (ensure_bpf_loaded() < 0) {
        err = 1;
        goto cleanup;
    }

    cpu_count = (int)sysconf(_SC_NPROCESSORS_CONF);
    if (cpu_count <= 0)
        cpu_count = 1;

#define ADD_TP(group_name, event_name, prog_name, required_flag)               \
    do {                                                                       \
        links[link_count++] = (struct tracepoint_link){                         \
            group_name, event_name, MEMO_PIN_PROG_DIR "/" prog_name, NULL, 0, required_flag}; \
    } while (0)

    ADD_TP("sched", "sched_process_exec", "handle_process_exec", 1);
    ADD_TP("syscalls", "sys_enter_openat", "handle_openat", 0);
    ADD_TP("syscalls", "sys_enter_openat2", "handle_openat2", 0);
    ADD_TP("syscalls", "sys_enter_sendto", "handle_sendto", 0);
    ADD_TP("syscalls", "sys_enter_recvfrom", "handle_recvfrom", 0);
    ADD_TP("sched", "sched_switch", "handle_sched_switch", 0);
    ADD_TP("sched", "sched_wakeup", "handle_sched_wakeup", 0);
    ADD_TP("sched", "sched_process_fork", "handle_process_fork", 0);
    ADD_TP("sched", "sched_process_exit", "handle_process_exit", 0);
    ADD_TP("binder", "binder_transaction", "handle_binder_transaction", 0);
    ADD_TP("vmscan", "mm_vmscan_direct_reclaim_begin", "handle_direct_reclaim_begin", 0);
    ADD_TP("vmscan", "mm_vmscan_direct_reclaim_end", "handle_direct_reclaim_end", 0);
    ADD_TP("vmscan", "mm_vmscan_kswapd_wake", "handle_kswapd_wake", 0);
    ADD_TP("input", "input_event", "handle_input_event", 0);

#undef ADD_TP

    for (int i = 0; i < link_count; i++) {
        int n = attach_tracepoint(tracefs, &links[i], cpu_count);
        if (n < 0) {
            err = 1;
            goto cleanup;
        }
        attached += n;
    }

    if (attached <= 0) {
        fprintf(stderr, "no tracepoints were attached\n");
        err = 1;
        goto cleanup;
    }

    fprintf(out, "MEMO\t0\tstatus\t0\t0\t0\tmemo\t0\t0\t0\t0\tcollector_started\n");
    fflush(out);
    sleep((unsigned int)opts.duration_sec);
    err = emit_counter_events(out, opts.max_events) == 0 ? 0 : 1;

cleanup:
    close_tracepoints(links, link_count);
    if (out && out != stdout)
        fclose(out);
    return err;
}
