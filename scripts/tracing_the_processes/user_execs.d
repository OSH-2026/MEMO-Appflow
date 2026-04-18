#!/usr/sbin/dtrace -qs

#pragma D option quiet

/*
Output format:
ts_ns \t pid \t ppid \t execname
*/

proc:::exec-success
/ !printed[pid] /
{
    printed[pid] = 1;
    printf("%lld\t%d\t%d\t%s\n",
        walltimestamp,
        pid,
        ppid,
        execname);
}

proc:::exit
{
    printed[pid] = 0;
}