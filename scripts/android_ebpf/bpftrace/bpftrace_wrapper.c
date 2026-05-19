#include<unistd.h>
#include<stdlib.h>
#include<errno.h>
int main(int argc, char** argv){
	char** new_argv=malloc(sizeof(char*)*(argc+3));
	new_argv[0]="/data/local/tmp/bpftrace-arm64/bin/bpftrace";
	for (int i=1;i<argc;i++) {
		new_argv[i]=argv[i];
	}
	new_argv[argc]="--traceable-functions";
	new_argv[argc+1]="/data/local/tmp/traceable-functions.txt";
	new_argv[argc+2]=0;
	setenv("BPFTRACE_BTF","/data/local/tmp/vmlinux-4.19.278-ftrace-syscalls.raw.btf",1);
	setenv("LD_LIBRARY_PATH","/data/local/tmp/bpftrace-arm64/lib",1);
	execvp("/data/local/tmp/bpftrace-arm64/bin/bpftrace",new_argv);
	perror("");
}
