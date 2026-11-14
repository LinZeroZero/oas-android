package org.onmyoji.oas.android.root

import java.io.File
import java.util.concurrent.TimeUnit

data class ShellResult(
    val code: Int,
    val output: String,
    val timedOut: Boolean = false,
) {
    val ok: Boolean get() = code == 0 && !timedOut
}

/**
 * root 命令通道（阶段 1 的雏形）。
 *
 * 后续阶段会替换成常驻 su shell（一次 su 进程 + 分隔符读取），
 * 把每次截图/点击的进程创建开销从 ~80ms 降到 ~5ms。
 * 现在先用一次性 exec，够验证链路、也够做自检。
 */
object RootShell {

    private val suCandidates = listOf(
        arrayOf("su", "-c"),
        arrayOf("su", "0", "sh", "-c"),   // 部分 Magisk/KernelSU 配置
        arrayOf("su", "-s", "/system/bin/sh", "-c"),
    )

    @Volatile
    private var workingPrefix: Array<String>? = null

    fun exec(command: String, timeoutMs: Long = 10_000): ShellResult {
        workingPrefix?.let { prefix ->
            runCatching { return execWith(prefix, command, timeoutMs) }
        }
        var last = ShellResult(-1, "no su binary", timedOut = false)
        for (prefix in suCandidates) {
            val result = runCatching { execWith(prefix, command, timeoutMs) }
                .getOrElse { ShellResult(-1, "spawn failed: ${it.message}") }
            if (result.output.isNotEmpty() || result.ok) {
                workingPrefix = prefix
                return result
            }
            last = result
        }
        return last
    }

    private fun execWith(prefix: Array<String>, command: String, timeoutMs: Long): ShellResult {
        val cmd = prefix + command
        val process = ProcessBuilder(*cmd)
            .redirectErrorStream(true)
            .start()

        val buffer = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { reader ->
                    val chunk = CharArray(4096)
                    while (true) {
                        val n = reader.read(chunk)
                        if (n <= 0) break
                        synchronized(buffer) { buffer.append(chunk, 0, n) }
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "su-reader"
            start()
        }

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            reader.join(500)
            return ShellResult(-1, "timeout after ${timeoutMs}ms", timedOut = true)
        }
        reader.join(500)
        val output = synchronized(buffer) { buffer.toString().trim() }
        return ShellResult(process.exitValue(), output)
    }

    fun hasRoot(): Boolean {
        val result = exec("id")
        return result.output.contains("uid=0")
    }

    /** 用 root 截屏到 App 自己的目录（避免 /data/local/tmp 的权限问题）。 */
    fun captureScreenshot(target: File, timeoutMs: Long = 20_000): ShellResult {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        return exec("screencap -p '${target.absolutePath}'", timeoutMs)
    }

    fun screenSize(): String = exec("wm size").output

    fun screenDensity(): String = exec("wm density").output
}