#!/usr/sbin/dtrace -qs

proc:::exec
/ !seen[pid] /
{
    seen[pid] = 1;
    printf("%d %d %s\n", pid, ppid, execname);
}

proc:::exit
/ seen[pid] /
{
    seen[pid] = 0;
}