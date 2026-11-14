package org.onmyoji.oas.android.root

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * 常驻 su shell。
 *
 * 为什么要常驻：一次性 `su -c xxx` 每次都要 fork + 申请 root（实测 ~100ms），
 * 截图/点击每秒好几次，光是进程创建就吃掉大半预算。
 * 这里保持一个 root shell，命令用「哨兵标记 + 退出码」切分输出。
 */
class SuShell private constructor(command: Array<String>) : Closeable {

    private val process: Process = ProcessBuilder(*command)
        .redirectErrorStream(false)
        .start()

    private val stdin: OutputStream = BufferedOutputStream(process.outputStream, 1 shl 16)
    private val stdout: InputStream = BufferedInputStream(process.inputStream, 1 shl 16)

    private val lock = Object()
    private val buffer = ByteArrayOutputStream()
    private var eof = false

    private val reader = Thread {
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val n = try {
                stdout.read(chunk)
            } catch (_: Throwable) {
                -1
            }
            if (n <= 0) break
            synchronized(lock) {
                buffer.write(chunk, 0, n)
                lock.notifyAll()
            }
        }
        synchronized(lock) {
            eof = true
            lock.notifyAll()
        }
    }.apply {
        isDaemon = true
        name = "su-shell-reader"
        start()
    }

    val alive: Boolean get() = process.isAlive && !eof

    /**
     * 执行命令，返回 stdout+stderr 文本（不含哨兵行）。
     */
    fun run(command: String, timeoutMs: Long = 10_000): ShellResult {
        val token = "OAS%08x".format(System.nanoTime() and 0xffffffffL)
        synchronized(lock) {
            buffer.reset()
            // 用子 shell 包一层，保证 2>&1 和退出码都拿到；printf 保证行首对齐
            val script = "{ $command ; } 2>&1; printf '\\n__%s__ %s\\n' $token \$?\n"
            return try {
                stdin.write(script.toByteArray())
                stdin.flush()
            } catch (e: Throwable) {
                return ShellResult(-1, "shell write failed: ${e.message}")
            }.let { waitFor(token, timeoutMs) }
        }
    }

    private fun waitFor(token: String, timeoutMs: Long): ShellResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        val marker = "__${token}__ "
        while (true) {
            val text = synchronized(lock) { buffer.toString("UTF-8") }
            val idx = text.lastIndexOf(marker)
            if (idx >= 0) {
                val lineEnd = text.indexOf('\n', idx)
                if (lineEnd > 0) {
                    val code = text.substring(idx + marker.length, lineEnd).trim().toIntOrNull() ?: -1
                    val body = text.substring(0, idx).trim('\n')
                    return ShellResult(code, body)
                }
            }
            val remain = deadline - System.currentTimeMillis()
            if (remain <= 0) {
                return ShellResult(-1, synchronized(lock) { buffer.toString("UTF-8") }.trim().takeLast(400), timedOut = true)
            }
            synchronized(lock) {
                try {
                    lock.wait(minOf(remain, 50))
                } catch (_: InterruptedException) {
                }
                if (eof) {
                    return ShellResult(-1, "shell closed", timedOut = true)
                }
            }
        }
    }

    override fun close() {
        runCatching { stdin.write("exit\n".toByteArray()); stdin.flush() }
        runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }
        runCatching { process.destroy() }
        runCatching { reader.join(300) }
    }

    companion object {
        private val candidates = listOf(
            arrayOf("su"),
            arrayOf("su", "0", "sh"),
            arrayOf("su", "-s", "/system/bin/sh"),
        )

        @Volatile
        private var instance: SuShell? = null

        @Synchronized
        fun get(): SuShell? {
            instance?.let { if (it.alive) return it else runCatching { it.close() } }
            for (cmd in candidates) {
                val shell = runCatching { SuShell(cmd) }.getOrNull() ?: continue
                val probe = shell.run("id", 5_000)
                if (probe.output.contains("uid=0")) {
                    instance = shell
                    return shell
                }
                runCatching { shell.close() }
            }
            return null
        }

        @Synchronized
        fun shutdown() {
            instance?.let { runCatching { it.close() } }
            instance = null
        }
    }
}