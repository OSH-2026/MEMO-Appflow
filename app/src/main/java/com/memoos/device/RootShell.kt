package com.memoos.device

import android.content.Context
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class ShellResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut
}

object RootShell {
    @Volatile
    private var bridge: RootBridgeClient? = null

    fun configureBridge(context: Context) {
        bridge = RootBridgeClient(context)
    }

    fun bridgeDaemonStartCommand(context: Context): String {
        val client = RootBridgeClient(context)
        bridge = client
        return client.daemonStartCommand()
    }

    fun run(command: String, requireRoot: Boolean = true, timeoutMs: Long = 15_000L): ShellResult {
        val args = if (requireRoot) {
            // On MagiskSU/Pixel 5, `su 0 sh -c ...` can run as uid=0 with an
            // empty CapEff set. Loading tracepoint eBPF needs the full
            // capability set, which Magisk keeps for shell-mediated `su -c`.
            listOf("sh", "-c", "su -c ${shellQuote(command)}")
        } else {
            listOf("sh", "-c", command)
        }
        val direct = execute(command, args, timeoutMs)
        if (!requireRoot || direct.ok) return direct
        return bridge?.run(command, timeoutMs) ?: direct
    }

    fun hasRoot(): Boolean {
        val result = run("id", requireRoot = true, timeoutMs = 3_000L)
        return result.ok && result.stdout.contains("uid=0")
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private fun execute(label: String, args: List<String>, timeoutMs: Long): ShellResult {
        return try {
            val process = ProcessBuilder(args)
                .redirectErrorStream(false)
                .start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outThread = streamTo(process.inputStream, stdout)
            val errThread = streamTo(process.errorStream, stderr)
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(750, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                }
            }
            outThread.join(500)
            errThread.join(500)
            ShellResult(
                command = label,
                exitCode = if (finished) process.exitValue() else -1,
                stdout = stdout.toString(),
                stderr = stderr.toString(),
                timedOut = !finished,
            )
        } catch (exc: Exception) {
            ShellResult(label, -1, "", exc.message ?: exc.toString(), timedOut = false)
        }
    }

    private fun streamTo(stream: InputStream, target: StringBuilder): Thread {
        return thread(start = true, isDaemon = true) {
            BufferedReader(InputStreamReader(stream)).useLines { lines ->
                lines.forEach { line ->
                    target.append(line).append('\n')
                }
            }
        }
    }
}
