#!/usr/sbin/dtrace -s

/*
 * macos_process_lifecycle.d
 *
 * Trace how processes are created, monitored, and destroyed on macOS.
 *
 * Run:
 *   sudo dtrace -q -s scripts/macos_process_lifecycle.d
 *
 * What it shows:
 *   - proc:::create        when a process is forked/created
 *   - proc:::exec-success  when the new image is successfully executed
 *   - profile:::profile-*  sampled "who is running" snapshots
 *   - proc:::exit          when the process exits, including lifetime
 *
 * Notes:
 *   - DTrace generally requires root on macOS.
 *   - profile:::profile-997 samples CPU activity 997 times/second across CPUs.
 *   - The 1-second summary gives you a lightweight "monitoring" view of
 *     processes that were active during the last interval.
 *   - This script intentionally sticks to core DTrace built-ins so it stays
 *     usable even on newer macOS installations where some bundled D headers
 *     fail to compile cleanly.
 */

#pragma D option quiet
#pragma D option switchrate=10hz
#pragma D option strsize=256

dtrace:::BEGIN
{
    printf("Tracing macOS process lifecycle. Press Ctrl+C to stop.\n");
    printf("%-8s %-8s %-16s %-12s %s\n",
        "PID", "PPID", "EVENT", "EXEC", "DETAIL");
}

proc:::create
{
    proc_start_ns[pid] = timestamp;
    proc_parent[pid] = ppid;

    printf("%-8d %-8d %-16s %-12s %s\n",
        pid, ppid, "create", execname, "process created");
}

proc:::exec-success
{
    printf("%-8d %-8d %-16s %-12s %s\n",
        pid, ppid, "exec-success", execname, "new image executed");
}

profile:::profile-997
/pid != 0/
{
    @active_samples[pid, ppid, execname] = count();
}

profile:::tick-1sec
{
    printf("\n[activity summary for the last second]\n");
    printa("  pid=%-8d ppid=%-8d exec=%-20s cpu-samples=%@d\n", @active_samples);
    trunc(@active_samples);
    printf("\n");
}

proc:::exit
/proc_start_ns[pid]/
{
    this->lifetime_ms = (timestamp - proc_start_ns[pid]) / 1000000;

    printf("%-8d %-8d %-16s %-12s lifetime=%d ms, exit=%d\n",
        pid, proc_parent[pid], "exit", execname, this->lifetime_ms, 0);

    proc_start_ns[pid] = 0;
    proc_parent[pid] = 0;
}

dtrace:::END
{
    printf("Stopped tracing process lifecycle.\n");
}
