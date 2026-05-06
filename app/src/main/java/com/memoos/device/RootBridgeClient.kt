package com.memoos.device

import android.content.Context
import java.io.File
import java.util.UUID

class RootBridgeClient(context: Context) {
    private val baseDir: File = File(context.applicationContext.getExternalFilesDir(null), "root_bridge")
    private val requestDir = File(baseDir, "requests")
    private val responseDir = File(baseDir, "responses")
    private val daemonScript = File(baseDir, "memo_rootd.sh")

    init {
        ensureLayout()
    }

    fun ensureLayout() {
        requestDir.mkdirs()
        responseDir.mkdirs()
        if (!daemonScript.exists()) {
            daemonScript.writeText(DAEMON_SCRIPT)
            daemonScript.setExecutable(true, false)
        }
    }

    fun run(command: String, timeoutMs: Long): ShellResult {
        ensureLayout()
        val id = "${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val request = File(requestDir, "$id.cmd")
        val tmp = File(requestDir, "$id.tmp")
        val out = File(responseDir, "$id.out")
        val err = File(responseDir, "$id.err")
        val exit = File(responseDir, "$id.exit")
        tmp.writeText(command)
        if (!tmp.renameTo(request)) {
            return ShellResult(command, -1, "", "root bridge request rename failed", timedOut = false)
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (exit.exists()) {
                val exitCode = exit.readText().trim().toIntOrNull() ?: -1
                return ShellResult(
                    command = command,
                    exitCode = exitCode,
                    stdout = out.takeIf { it.exists() }?.readText().orEmpty(),
                    stderr = err.takeIf { it.exists() }?.readText().orEmpty(),
                    timedOut = false,
                )
            }
            Thread.sleep(80)
        }
        return ShellResult(
            command = command,
            exitCode = -1,
            stdout = out.takeIf { it.exists() }?.readText().orEmpty(),
            stderr = "root bridge timeout",
            timedOut = true,
        )
    }

    fun daemonStartCommand(): String {
        ensureLayout()
        return "nohup sh '${daemonScript.absolutePath}' >/dev/null 2>&1 &"
    }

    companion object {
        private val DAEMON_SCRIPT = """
            #!/system/bin/sh
            BASE="${'$'}{1:-/sdcard/Android/data/com.memoos/files/root_bridge}"
            REQ="${'$'}BASE/requests"
            RESP="${'$'}BASE/responses"
            mkdir -p "${'$'}REQ" "${'$'}RESP"
            echo "${'$'}${'$'}" > "${'$'}BASE/rootd.pid"
            while true; do
              for f in "${'$'}REQ"/*.cmd; do
                [ -f "${'$'}f" ] || continue
                id="${'$'}{f##*/}"
                id="${'$'}{id%.cmd}"
                out="${'$'}RESP/${'$'}id.out"
                err="${'$'}RESP/${'$'}id.err"
                exitf="${'$'}RESP/${'$'}id.exit"
                donef="${'$'}RESP/${'$'}id.cmd.done"
                cmd="${'$'}(cat "${'$'}f")"
                sh -c "${'$'}cmd" > "${'$'}out.tmp" 2> "${'$'}err.tmp"
                rc="${'$'}?"
                mv "${'$'}out.tmp" "${'$'}out"
                mv "${'$'}err.tmp" "${'$'}err"
                echo "${'$'}rc" > "${'$'}exitf"
                mv "${'$'}f" "${'$'}donef"
              done
              sleep 0.1
            done
        """.trimIndent() + "\n"
    }
}
