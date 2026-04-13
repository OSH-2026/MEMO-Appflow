#!/usr/sbin/dtrace -s

/*
 * macos_fork_exit_only.d
 *
 * Trace only process creation and destruction on macOS.
 * This script does not trace exec().
 *
 * Run:
 *   sudo dtrace -q -s scripts/macos_fork_exit_only.d
 *
 * Events:
 *   - proc:::create  when a process is created (fork/vfork-style creation path)
 *   - proc:::exit    when that process exits
 */

#pragma D option quiet
#pragma D option switchrate=10hz

dtrace:::BEGIN
{
    printf("Tracing process create/exit only. Press Ctrl+C to stop.\n");
    printf("%-8s %-8s %-12s %s\n", "PID", "PPID", "EVENT", "DETAIL");
}

proc:::create
{
    proc_start_ns[pid] = timestamp;
    proc_parent[pid] = ppid;

    printf("%-8d %-8d %-12s %s\n",
        pid, ppid, "create", execname);
}

proc:::exit
/proc_start_ns[pid]/
{
    this->lifetime_ms = (timestamp - proc_start_ns[pid]) / 1000000;

    printf("%-8d %-8d %-12s lifetime=%d ms\n",
        pid, proc_parent[pid], "exit", this->lifetime_ms);

    proc_start_ns[pid] = 0;
    proc_parent[pid] = 0;
}

dtrace:::END
{
    printf("Stopped create/exit tracing.\n");
}
