package org.onmyoji.oas.android.root

import android.content.Context
import java.io.File

/**
 * 给 Python 侧调用的设备接口（Chaquopy 通过 jclass 直接调这些静态方法）。
 *
 * 设计原则：
 * - 所有操作都走同一个常驻 su shell，避免反复 fork；
 * - 截图先落文件再由 Python 读，二进制不过 JVM/Python 边界，最省事也最快；
 * - 不做任何分辨率改动：裁切/坐标映射都在 Python 侧算。
 */
object Device {

    @Volatile
    private var lastError: String = ""

    /** 启动后轮询确认进程的时长上限（毫秒）。 */
    private const val LAUNCH_CONFIRM_MS = 20_000L

    /** Python 侧调用时没有 Context 可用，这里留一个由 UI 层注入的应用级 Context。 */
    @Volatile
    private var appContext: Context? = null

    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    @JvmStatic
    fun lastError(): String = lastError

    private fun shell(): SuShell? {
        val sh = SuShell.get()
        if (sh == null) lastError = "su 不可用"
        return sh
    }

    @JvmStatic
    fun hasRoot(): Boolean {
        val sh = shell() ?: return false
        return sh.run("id", 5_000).output.contains("uid=0")
    }

    /** 执行任意 root 命令，返回合并后的输出。 */
    @JvmStatic
    fun shell(cmd: String): String {
        val sh = shell() ?: return ""
        val result = sh.run(cmd)
        if (!result.ok) lastError = "cmd=$cmd code=${result.code} out=${result.output.take(200)}"
        return result.output
    }

    /**
     * 原始截图（RGBA_8888，带 12/16 字节头）直接写文件。
     * @return 写入的字节数，失败返回 -1
     */
    @JvmStatic
    fun captureRaw(path: String): Long = capture(path, null)

    /**
     * 指定显示器的原始截图。
     *
     * 走「虚拟屏 daemon 优先、screencap 兜底」：
     * 本机 `screencap -d <id>` 实测全部失败（HANDOFF 五-A），所以虚拟屏必须走 daemon；
     * 只有 daemon 不可用（或目标不是我们建的屏）时才退回 screencap。
     */
    @JvmStatic
    fun captureRawOnDisplay(path: String, displayId: Int): Long {
        if (displayId >= 0 && DisplayServer.isRunning()) {
            val bytes = DisplayServer.grabRaw(displayId, path)
            if (bytes > 0) return bytes
            lastError = "daemon grab 失败，退回 screencap -d（本机通常不可用）"
        }
        return capture(path, displayId)
    }

    // ------------------------------------------------------------------ //
    // 虚拟屏（daemon 封装，供 Python / UI 调用）
    // ------------------------------------------------------------------ //

    @JvmStatic
    fun createDisplay(width: Int, height: Int, dpi: Int): Int {
        val context = appContext
        if (context == null) {
            lastError = "Device.init(context) 未调用"
            return -1
        }
        val err = DisplayServer.ensureStarted(context)
        if (err != null) {
            lastError = err
            return -1
        }
        val id = DisplayServer.create(width, height, dpi)
        if (id < 0) lastError = "createDisplay 失败"
        return id
    }

    @JvmStatic
    fun destroyDisplay(displayId: Int): Boolean = DisplayServer.destroy(displayId)

    @JvmStatic
    fun displayList(): String = DisplayServer.list()

    @JvmStatic
    fun launchOnDisplay(component: String, displayId: Int): Boolean {
        if (displayId < 0) return false
        val out = DisplayServer.launchOnDisplay(component, displayId)
        val ok = out.contains("Status: ok") || out.contains("Warning: Activity not started")
        if (!ok) lastError = out.take(300)
        return ok
    }

    /** 停掉虚拟屏 daemon 并清理临时文件。 */
    @JvmStatic
    fun stopDisplayServer() {
        appContext?.let { DisplayServer.stop(it) }
    }

    /** 屏幕是否亮着（前台服务/息屏验证用；Python 心跳每秒采一次）。 */
    @JvmStatic
    fun screenOn(): Boolean {
        val ctx = appContext ?: return false
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isInteractive
    }

    /** 虚拟屏累计帧数（daemon 侧计数，被 app 冻结也不受影响 → 可信的真值）。 */
    @JvmStatic
    fun displayFrames(displayId: Int): Long {
        if (displayId < 0) return -1
        return runCatching {
            DisplayServer.stats(displayId).optJSONObject("display")?.optLong("frames", -1L) ?: -1L
        }.getOrDefault(-1L)
    }

    private fun capture(path: String, displayId: Int?): Long {
        val sh = shell() ?: return -1
        val file = File(path)
        file.parentFile?.mkdirs()
        if (file.exists()) file.delete()

        val cmd = if (displayId == null) "screencap > '$path'" else "screencap -d $displayId > '$path'"
        val result = sh.run(cmd, 20_000)
        if (!file.exists()) {
            lastError = "screencap 失败: ${result.output.take(200)}"
            return -1
        }
        return file.length()
    }

    @JvmStatic
    fun tap(x: Int, y: Int): Boolean {
        val sh = shell() ?: return false
        return sh.run("input tap $x $y", 8_000).code == 0
    }

    /** 只向指定显示器注入点击（`input -d <id>`），主屏不受影响。 */
    @JvmStatic
    fun tapOnDisplay(x: Int, y: Int, displayId: Int): Boolean {
        val sh = shell() ?: return false
        return sh.run("input -d $displayId tap $x $y", 8_000).code == 0
    }

    @JvmStatic
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        val sh = shell() ?: return false
        return sh.run("input swipe $x1 $y1 $x2 $y2 $durationMs", 10_000).code == 0
    }

    @JvmStatic
    fun swipeOnDisplay(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, displayId: Int): Boolean {
        val sh = shell() ?: return false
        return sh.run("input -d $displayId swipe $x1 $y1 $x2 $y2 $durationMs", 10_000).code == 0
    }

    @JvmStatic
    fun longPress(x: Int, y: Int, durationMs: Int): Boolean = swipe(x, y, x, y, durationMs)

    @JvmStatic
    fun screenSize(): String = shell("wm size")

    @JvmStatic
    fun appPid(pkg: String): Int = shell("pidof $pkg").trim().lineSequence().firstOrNull()
        ?.toIntOrNull() ?: 0

    /**
     * 拉起 app 到前台。
     *
     * 实现：`cmd package resolve-activity` + `am start -n`。**不要改用 `monkey`** ——
     * `monkey` 是测试工具（随机事件注入），不是生产启动方式。
     *
     * ⚠ **不带 `-W`**：`SuShell.run()` 在 `synchronized(lock)` 内调用 `waitFor`
     * （`SuShell.kt:62-72`），带 `-W` 会把抓帧/点击等**所有同期 shell 调用**一起阻塞
     * 至启动完成（游戏冷启动可达数秒）。故改为「`am start` 立即返回 + 轮询 `pidof` 确认」，
     * 每次 shell 调用都很短；判据也从文本改为「进程真的起来了」。
     *
     * ⚠ 调用约束：
     *   - **必须在后台线程调用**：本函数会阻塞调用线程最多 `LAUNCH_CONFIRM_MS`（20s）轮询，
     *     主线程调用会 ANR。
     *   - 判据是「进程存在」而非「界面已起」：目标包若有常驻后台进程，可能提前返回 true；
     *     需要「界面已起」语义时须另行复核 `dumpsys activity` 的 ResumedActivity。
     *
     * 另：交接包曾记录「`monkey` 会把 `accelerometer_rotation` 写成 1」，但该说法**因果未定**
     * （出处仅交接包；所引 BG-31 不存在；AOSP stock 层面已复算不成立）。
     * 核查过程见 `docs/evidence/20261125-0530-monkey-rotation-未定因.md`。
     */
    @JvmStatic
    fun startApp(pkg: String): Boolean {
        val component = resolveLauncherComponent(pkg) ?: return false
        val out = shell("am start -n $component")
        if (!out.contains("Starting:")) {
            lastError = "startApp($pkg) 未返回 Starting: ${out.take(200)}"
            return false
        }
        val deadline = System.currentTimeMillis() + LAUNCH_CONFIRM_MS
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(400)
            if (appPid(pkg) > 0) return true
        }
        lastError = "startApp($pkg) am start 已发出，但 ${LAUNCH_CONFIRM_MS}ms 内未见进程（未知：可能仍在启动）"
        return false
    }

    /** 解析 launcher activity（`<pkg>/<activity>`）；失败返回 null。 */
    private fun resolveLauncherComponent(pkg: String): String? =
        shell("cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $pkg")
            .lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("$pkg/") }

    @JvmStatic
    fun forceStop(pkg: String): Boolean = shell("am force-stop $pkg").isEmpty()

    @JvmStatic
    fun shellRecycle() = SuShell.shutdown()
}