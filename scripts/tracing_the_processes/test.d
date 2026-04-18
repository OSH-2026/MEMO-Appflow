
dtrace:::BEGIN
{
    printf("Press Ctrl+C to halt\n");
}

/* Fires when a new process image is executed */
proc:::exec-success
{
    printf("At time %Y, %s executed %s\n",
        walltimestamp, execname, curpsinfo->pr_psargs);
}

dtrace:::END
{
    printf("exited\n");
}