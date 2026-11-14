package org.onmyoji.oas.android.root

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * 虚拟屏守护进程（root app_process）的客户端。
 *
 * 为什么要有它：虚拟屏的生命周期绑在创建它的进程的 binder 回调上，
 * 进程一退屏就没了，所以必须有一个常驻进程替我们持有屏幕。
 * 这个进程由 su 拉起，独立于本 app 进程 —— 也正因为如此，它不会像 app 那样
 * 被 Android 14 的 cached-app freezer 冻住。
 *
 * 清理纪律（重要）：
 * - 启动前先 pkill 残留；
 * - [stop] 会先发 exit，再按 pid 杀，最后 pkill 兜底，并删掉 socket/pid/log；
 * - 不注册任何开机自启（没有 BootReceiver / JobScheduler），重启手机不会拉起它。
 */
object DisplayServer {

    /** 与 server 端 SocketServer.ABSTRACT_NAME 保持一致（抽象命名空间，无文件、无权限问题）。 */
    const val SOCKET_NAME = "org.onmyoji.oas.display"

    private const val MAIN_CLASS = "org.onmyoji.oas.server.Main"
    private const val ASSET_JAR = "server/server.jar"
    private const val APP_PROCESS = "/system/bin/app_process"

    /**
     * 所有 socket 调用的默认超时。
     * 上一轮是 8s：daemon 一旦不响应，验证线程就被这条调用拖着，报告直接没落盘
     * （HANDOFF 11.5）。现在统一 ≤2s，失败即降级。
     */
    private const val DEFAULT_TIMEOUT_MS = 2_000

    /** ensureStarted 串行化：两个调用方并发时也只允许拉起一个实例。 */
    private val startLock = Any()

    private fun dir(context: Context): File = File(context.filesDir, "oas/server").apply { mkdirs() }

    private fun jar(context: Context): File = File(dir(context), "server.jar")

    private fun pidFile(context: Context): File = File(dir(context), "server.pid")

    private fun logFile(context: Context): File = File(dir(context), "server.log")

    /** 把 assets 里的 server.jar 落到私有目录（只有本 app 和 root 能读）。 */
    fun ensureJar(context: Context): File {
        val target = jar(context)
        context.assets.open(ASSET_JAR).use { input ->
            val bytes = input.readBytes()
            val same = target.exists() && target.length() == bytes.size.toLong()
            if (!same) {
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
        }
        return target
    }

    /**
     * 存活判定 = socket ping。
     * 不再用 pidof / ps 名字匹配：daemon 是 app_process，同名进程可能是别的模块；
     * 而「ping 得通」本来就是可用的充要条件（socket 在它手里 = 它在服务）。
     */
    fun isRunning(): Boolean = ping()

    /** 起 daemon；返回 null 表示成功（含「已在跑，直接复用」），否则返回错误描述。 */
    fun ensureStarted(context: Context): String? = synchronized(startLock) {
        // 1) ping 通就复用 —— 绝不允许拉起第二个实例抢同一个抽象 socket 名
        if (ping()) return null
        val shell = SuShell.get() ?: return "su 不可用"
        val pid = pidFile(context)
        val log = logFile(context)
        val jarPath = ensureJar(context).absolutePath

        // 2) 清残留：只杀 cmdline 里真带 MAIN_CLASS 的 pid（先按 pidfile，再全扫一遍兜底）。
        //    不用 pkill -f 裸匹配：它可能杀掉命令行里恰好含这串字符的无关进程。
        val cleanup = buildString {
            append("P=$(cat '").append(pid.absolutePath).append("' 2>/dev/null); ")
            append("if [ -n \"${'$'}P\" ] && grep -qa ").append(MAIN_CLASS)
            append(" /proc/${'$'}P/cmdline 2>/dev/null; then kill -9 ${'$'}P 2>/dev/null; fi; ")
            append("for f in $(grep -la ").append(MAIN_CLASS)
            append(" /proc/[0-9]*/cmdline 2>/dev/null); do kill -9 $(echo ${'$'}f | cut -d/ -f3) 2>/dev/null; done; ")
            append("sleep 0.3; rm -f '").append(pid.absolutePath).append("'")
        }
        shell.run(cleanup, 8_000)

        // 3) 拉起；setsid + 重定向保证 su shell 立即返回
        val cmd = buildString {
            append("CLASSPATH='").append(jarPath).append("' ")
            append("setsid ").append(APP_PROCESS).append(" /system/bin ").append(MAIN_CLASS)
            append(" --socket ").append(SOCKET_NAME)
            append(" --pidfile '").append(pid.absolutePath).append("'")
            append(" > '").append(log.absolutePath).append("' 2>&1 & ")
            append("sleep 0.2; chmod 666 '").append(log.absolutePath).append("' 2>/dev/null; echo spawned")
        }
        val result = shell.run(cmd, 20_000)
        if (!result.ok && result.output.isBlank()) {
            return "启动失败: code=${result.code} ${result.output.take(200)}"
        }
        // 等 socket 就绪（最多 ~4s）
        repeat(20) {
            if (ping()) return null
            Thread.sleep(200)
        }
        val tail = runCatching { log.readText().takeLast(400) }.getOrDefault("")
        return "daemon 未就绪（log: $tail）"
    }

    /** 停掉 daemon 并清理临时文件（uninstall 前、PoC 结束后都该调一下）。 */
    fun stop(context: Context) {
        // 先让 daemon 把心跳收干净（报告里要用它的摘要），再退出
        runCatching { rawCommand("hb stop", 1_500) }
        runCatching { rawCommand("exit", 1_500) }
        val pid = runCatching { pidFile(context).readText().trim().toInt() }.getOrNull()
        val shell = SuShell.get()
        if (shell != null) {
            val cmd = buildString {
                if (pid != null && pid > 0) append("kill ").append(pid).append(" 2>/dev/null; ")
                append("pkill -f ").append(MAIN_CLASS).append(" 2>/dev/null; ")
                append("sleep 0.2; ")
                append("rm -f '").append(pidFile(context).absolutePath).append("' ")
                append("'").append(logFile(context).absolutePath).append("' ")
                append("'").append(File(dir(context), "*.tmp").absolutePath).append("' 2>/dev/null; ")
                append("echo cleaned")
            }
            shell.run(cmd, 8_000)
        }
        purgeTempRaw(context)
    }

    /**
     * 删掉 `files/oas/cache` 里的临时 `.raw`（每帧 3.7~10.4MB，纯中间产物）。
     * **保留** `*.png` 证据与 `*.json` 统计 —— 删了就没法复盘了。
     */
    fun purgeTempRaw(context: Context): Int {
        val cache = File(context.filesDir, "oas/cache")
        var n = 0
        cache.listFiles { f -> f.isFile && f.name.endsWith(".raw") }?.forEach {
            if (runCatching { it.delete() }.getOrDefault(false)) n++
        }
        return n
    }

    private fun ping(): Boolean {
        val response = rawCommand("ping", 1_200)
        return response.contains("\"ok\":true")
    }

    /** 发一条命令，返回单行 JSON；失败返回空串（超时默认 2s，绝不拖死调用线程）。 */
    fun rawCommand(command: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): String {
        val socket = LocalSocket()
        return try {
            socket.connect(LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
            socket.soTimeout = timeoutMs
            socket.outputStream.let {
                it.write((command + "\n").toByteArray())
                it.flush()
            }
            BufferedReader(InputStreamReader(socket.inputStream)).readLine().orEmpty()
        } catch (t: Throwable) {
            ""
        } finally {
            runCatching { socket.close() }
        }
    }

    // ------------------------------------------------------------------ //
    // daemon 侧心跳（熄屏时唯一可信的证据）
    // ------------------------------------------------------------------ //

    /**
     * 起 daemon 心跳：daemon 每秒往 [path] 追加一行
     * `{"ts":..,"displayId":..,"frames":..,"pid":..}`。
     *
     * app 被 Hans 冻住时这里仍在写 —— 所以 C 段空洞只能靠它证明。
     * 返回 null 表示没起来（调用方要降级，不能死等）。
     */
    fun hbStart(intervalMs: Int, displayId: Int, path: String): JSONObject? {
        val json = rawCommand("hb start $intervalMs $displayId $path", 3_000)
        if (json.isBlank()) return null
        return runCatching { JSONObject(json) }.getOrNull()?.takeIf { it.optBoolean("ok") }
    }

    fun hbStop(): JSONObject? {
        val json = rawCommand("hb stop", 2_000)
        if (json.isBlank()) return null
        return runCatching { JSONObject(json) }.getOrNull()
    }

    fun hbStatus(): JSONObject? {
        val json = rawCommand("hb status", 1_500)
        if (json.isBlank()) return null
        return runCatching { JSONObject(json) }.getOrNull()
    }

    // ------------------------------------------------------------------ //
    // 具体动作
    // ------------------------------------------------------------------ //

    /**
     * 建虚拟屏，返回 displayId；失败返回 -1。
     * flags = 0 时用服务端默认值（PUBLIC | OWN_CONTENT_ONLY，Android 14 上不需要 CAPTURE_VIDEO_OUTPUT）。
     */
    fun create(width: Int, height: Int, dpi: Int, name: String = "oas", flags: Int = 0): Int {
        val cmd = if (flags > 0) "create $name $width $height $dpi $flags" else "create $name $width $height $dpi"
        val json = JSONObject(rawCommand(cmd, 6_000).ifBlank { "{}" })
        return json.optJSONObject("display")?.optInt("displayId", -1) ?: -1
    }

    fun destroy(displayId: Int): Boolean {
        val json = JSONObject(rawCommand("destroy $displayId", 3_000).ifBlank { "{}" })
        return json.optBoolean("ok")
    }

    fun list(): String = rawCommand("list", 2_000)

    fun stats(displayId: Int): JSONObject = JSONObject(rawCommand("stats $displayId", 2_000).ifBlank { "{}" })

    /** 抓一帧到 raw（screencap 兼容格式），返回字节数；失败 -1。 */
    fun grabRaw(displayId: Int, outPath: String): Long {
        val json = JSONObject(rawCommand("grab $displayId $outPath", 4_000).ifBlank { "{}" })
        val grab = json.optJSONObject("grab") ?: return -1
        return if (grab.optBoolean("ok")) grab.optLong("bytes", -1) else -1
    }

    fun grabInfo(displayId: Int, outPath: String): JSONObject? {
        val json = JSONObject(rawCommand("grab $displayId $outPath", 4_000).ifBlank { "{}" })
        return json.optJSONObject("grab")
    }

    /** 兜底通道：把 srcDisplayId 镜像到我们自己的 surface 上抓一帧（归因用）。 */
    fun mirrorGrab(srcDisplayId: Int, width: Int, height: Int, outPath: String): JSONObject? {
        val json = JSONObject(rawCommand("mirror $srcDisplayId $width $height $outPath", 6_000).ifBlank { "{}" })
        return json.optJSONObject("grab")
    }

    /** 在指定屏上拉起一个 Activity（root 跑 am，所以 --display 不会被权限拦）。 */
    fun launchOnDisplay(component: String, displayId: Int, extra: String = ""): String {
        val pkg = component.substringBefore('/')
        val shell = SuShell.get() ?: return "su 不可用"
        val cmd = "am force-stop $pkg; am start -W --display $displayId -n $component $extra"
        return shell.run(cmd, 60_000).output
    }
}