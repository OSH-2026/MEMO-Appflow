package com.memoos.ebpf

import com.memoos.device.EBPFCapabilityReport

object BpftraceProgramBuilder {
    fun build(report: EBPFCapabilityReport): String {
        val events = report.availableEvents
        val blocks = mutableListOf<String>()

        if ("binder:binder_transaction" in events) {
            blocks += """
                tracepoint:binder:binder_transaction
                {
                  printf("MEMO\t%llu\tbinder\t0\t%d\t0\t-\t%d\t%d\t0\t0\t-\n",
                    nsecs, pid, args->code, args->flags);
                }
            """.trimIndent()
        }
        if ("syscalls:sys_enter_openat" in events) {
            blocks += openatBlock("tracepoint:syscalls:sys_enter_openat")
        }
        if ("syscalls:sys_enter_openat2" in events) {
            blocks += openatBlock("tracepoint:syscalls:sys_enter_openat2")
        }
        if ("syscalls:sys_enter_sendto" in events) {
            blocks += """
                tracepoint:syscalls:sys_enter_sendto
                {
                  printf("MEMO\t%llu\tnetwork\t0\t%d\t0\t-\t1\t%d\t0\t0\tsendto\n",
                    nsecs, pid, args->len);
                }
            """.trimIndent()
        }
        if ("syscalls:sys_enter_recvfrom" in events) {
            blocks += """
                tracepoint:syscalls:sys_enter_recvfrom
                {
                  printf("MEMO\t%llu\tnetwork\t0\t%d\t0\t-\t2\t%d\t0\t0\trecvfrom\n",
                    nsecs, pid, args->size);
                }
            """.trimIndent()
        }
        if ("vmscan:mm_vmscan_direct_reclaim_begin" in events) {
            blocks += memoryBlock("tracepoint:vmscan:mm_vmscan_direct_reclaim_begin", 1, "direct_reclaim_begin")
        }
        if ("vmscan:mm_vmscan_direct_reclaim_end" in events) {
            blocks += memoryBlock("tracepoint:vmscan:mm_vmscan_direct_reclaim_end", 2, "direct_reclaim_end")
        }
        if ("vmscan:mm_vmscan_kswapd_wake" in events) {
            blocks += memoryBlock("tracepoint:vmscan:mm_vmscan_kswapd_wake", 3, "kswapd_wake")
        }
        if ("sched:sched_process_fork" in events) {
            blocks += """
                tracepoint:sched:sched_process_fork
                {
                  printf("MEMO\t%llu\tprocess_fork\t0\t%d\t0\t-\t%d\t%d\t0\t0\tfork\n",
                    nsecs, pid, args->parent_pid, args->child_pid);
                }
            """.trimIndent()
        }
        if ("sched:sched_process_exit" in events) {
            blocks += """
                tracepoint:sched:sched_process_exit
                {
                  printf("MEMO\t%llu\tprocess_exit\t0\t%d\t0\t-\t0\t0\t0\t0\texit\n",
                    nsecs, pid);
                }
            """.trimIndent()
        }
        if ("sched:sched_switch" in events) {
            blocks += """
                tracepoint:sched:sched_switch
                {
                  if (comm == "surfaceflinger" || comm == "RenderThread" || comm == "system_server" ||
                      comm == "ndroid.systemui" || comm == "cameraserver" || comm == "media.codec" ||
                      comm == "mediaserver" || comm == "lmkd" || comm == "netd") {
                    printf("MEMO\t%llu\tsched\t0\t%d\t0\t-\t%d\t%d\t0\t0\tswitch\n",
                      nsecs, pid, args->prev_pid, args->next_pid);
                  }
                }
            """.trimIndent()
        }
        if ("sched:sched_wakeup" in events) {
            blocks += """
                tracepoint:sched:sched_wakeup
                {
                  if (comm == "surfaceflinger" || comm == "RenderThread" || comm == "system_server" ||
                      comm == "ndroid.systemui" || comm == "cameraserver" || comm == "media.codec" ||
                      comm == "mediaserver" || comm == "lmkd" || comm == "netd") {
                    printf("MEMO\t%llu\tsched\t0\t%d\t0\t-\t%d\t%d\t0\t0\twakeup\n",
                      nsecs, pid, args->pid, args->prio);
                  }
                }
            """.trimIndent()
        }
        if ("input:input_event" in events) {
            blocks += """
                tracepoint:input:input_event
                {
                  printf("MEMO\t%llu\tinput\t0\t%d\t0\t-\t0\t0\t0\t0\tinput_event\n",
                    nsecs, pid);
                }
            """.trimIndent()
        } else if ("input_event" in report.traceableFunctions) {
            blocks += """
                kprobe:input_event
                {
                  printf("MEMO\t%llu\tinput\t0\t%d\t0\t-\t0\t0\t0\t0\tinput_event\n",
                    nsecs, pid);
                }
            """.trimIndent()
        }
        return blocks.joinToString("\n\n") + "\n"
    }

    private fun openatBlock(probe: String): String {
        return """
            $probe
            {
              printf("MEMO\t%llu\tfile\t0\t%d\t0\t-\t0\t0\t0\t0\topenat\n",
                nsecs, pid);
            }
        """.trimIndent()
    }

    private fun memoryBlock(probe: String, code: Int, detail: String): String {
        return """
            $probe
            {
              printf("MEMO\t%llu\tmemory\t0\t%d\t0\t-\t$code\t0\t0\t0\t$detail\n",
                nsecs, pid);
            }
        """.trimIndent()
    }
}
