package org.onmyoji.oas.android.poc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.onmyoji.oas.android.python.PyRuntime
import org.onmyoji.oas.android.root.Device
import org.onmyoji.oas.android.root.DisplayServer
import org.onmyoji.oas.android.root.SuShell
import org.onmyoji.oas.android.service.KeepAliveService
import java.io.File

/**
 * 阶段 2 / P1-1 验证器：**后台保活四段**（含息屏硬指标）。
 *
 * 为什么这么测（上一轮的教训）：cached-app freezer 会把整个 app 进程 SIGSTOP，
 * 所以「后台还活着吗」不能靠 app 自己「说」，必须是三路交叉证据：
 *   ① Python 心跳文件的时间戳间隔（被冻 → 出现几秒~几十秒的空洞）；
 *   ② daemon 侧虚拟屏 `frames` 增量（被冻不影响 daemon，所以它能给出真值）；
 *   ③ `/proc/<pid>/stat` 的进程状态位 + `dumpsys power` 的 wakelock 证据。
 *
 * 四段：
 *   A 前台基线 → B 切后台（主屏刷设置 60s） → C 息屏（60s） → D 唤醒 + 定向点击。
 * 结果写 `cache/keepalive_poc.txt/json`，无 UI 也能验收。
 */
object KeepAlivePoc {

    const val WIDTH = 1280
    const val HEIGHT = 720
    const val DPI = 320
    const val GAME_COMPONENT = "com.netease.onmyoji/.Client"

    /** 心跳断流判定（毫秒）：daemon 侧心跳超过这么久没新行才认为断流。 */
    private const val HB_STALL_MS = 6_000L

    /** 采样节拍（Python 心跳的 interval），毫秒。 */
    private const val HB_INTERVAL_MS = 1000

    @Volatile
    var running = false
        private set

    @Volatile
    var lastMessage = ""
        private set

    /** 从后台/无头入口拉起验证（自己开线程，不依赖 Activity 生命周期）。 */
    fun start(context: Context, aSec: Int = 30, bSec: Int = 60, cSec: Int = 60) {
        if (running) return
        running = true
        lastMessage = "准备中…"
        val app = context.applicationContext
        val thread = Thread({
            try {
                run(app, aSec, bSec, cSec)
            } catch (t: Throwable) {
                lastMessage = "验证失败: ${t.message ?: t}"
                // 线程里异常时也要把设备恢复干净（否则可能停在熄灭屏 + 常驻服务）
                emergencyCleanup(app, t)
            } finally {
                running = false
            }
        }, "keepalive-poc")
        thread.isDaemon = true
        thread.start()
    }

    fun summary(): String {
        val f = File(cacheDir ?: return "", "keepalive_poc.txt")
        return runCatching { f.readText() }.getOrDefault("")
    }

    @Volatile
    private var cacheDir: String? = null

    /** 当前验证持有的 displayId（-1 = 没有），异常兜底时用来销毁。 */
    @Volatile
    private var lastDisplay = -1

    /** 报告是否已落盘（避免异常兜底把成功报告覆盖成错误报告）。 */
    @Volatile
    private var reportWritten = false

    // ------------------------------------------------------------------ //

    private fun run(context: Context, aSec: Int, bSec: Int, cSec: Int) {
        val cache = File(context.filesDir, "oas/cache").apply { mkdirs() }
        cacheDir = cache.absolutePath
        lastDisplay = -1
        reportWritten = false

        val phases = JSONArray()
        val extra = JSONObject()
        val workDir = File(context.filesDir, "oas").absolutePath
        val startedAt = System.currentTimeMillis()
        var displayId = -1
        var verdicts = 0

        Device.init(context)
        // 心跳已前移到 daemon 侧，这里不再启动 Python（省掉一次 Chaquopy 初始化）

        // ---- 0) 起前台服务 ----
        val svcErr = KeepAliveService.start(context)
        extra.put("serviceStartError", svcErr ?: "")
        var upTries = 0
        while (!KeepAliveService.running && upTries < 40) {
            Thread.sleep(100)
            upTries++
        }
        extra.put("serviceRunning", KeepAliveService.running)
        extra.put("wakeLockHeld", KeepAliveService.wakeLockHeld)
        extra.put("notificationPermission", notificationPermission(context))
        if (!KeepAliveService.running) {
            finish(cache, phases, extra, displayId, startedAt, 0, "前台服务没起来，后面全无意义")
            return
        }

        // ---- 1) daemon + 虚拟屏 + 游戏 ----
        val daemonErr = DisplayServer.ensureStarted(context)
        if (daemonErr != null) {
            finish(cache, phases, extra, displayId, startedAt, 0, "daemon 起不来: $daemonErr")
            return
        }
        displayId = DisplayServer.create(WIDTH, HEIGHT, DPI)
        lastDisplay = displayId
        extra.put("displayId", displayId)
        if (displayId < 0) {
            // daemon 已经起来了，建屏失败也要把它收掉
            cleanupRuntime(context, displayId)
            finish(cache, phases, extra, displayId, startedAt, 0, "create 虚拟屏失败")
            return
        }
        DisplayServer.launchOnDisplay(GAME_COMPONENT, displayId)
        lastMessage = "等游戏渲染（20s）…"
        Thread.sleep(20_000)
        val warm = DisplayServer.grabInfo(displayId, File(cache, "keepalive_warm.raw").absolutePath)
        extra.put("warmGrabMean", warm?.optDouble("mean", -1.0) ?: -1.0)
        extra.put("warmFrames", warm?.optLong("frames", -1) ?: -1)
        File(cache, "keepalive_warm.raw").delete()

        // ---- 2) 起 daemon 侧心跳（纯 Java，熄屏/被冻也不断流） ----
        val hbFile = File(cache, "keepalive_hb.jsonl")
        hbFile.delete()
        val hbStart = DisplayServer.hbStart(HB_INTERVAL_MS, displayId, hbFile.absolutePath)
        extra.put("heartbeatStart", hbStart?.toString() ?: "hb start 失败（daemon 无响应）")
        extra.put("heartbeatPath", hbFile.absolutePath)
        if (hbStart == null) {
            // 此时 daemon/虚拟屏/前台服务都已经起来了，必须收干净再退出
            cleanupRuntime(context, displayId)
            finish(cache, phases, extra, displayId, startedAt, 0, "daemon 心跳没起来")
            return
        }

        // ---- 3) 四段 ----
        // A：前台基线
        phases.put(
            phase("A 前台基线", aSec, hbFile, displayId) { _ ->
                KeepAliveService.updateNote("A 前台基线")
            },
        )
        partialReport(cache, phases, extra, displayId, startedAt)

        // B：切后台（按 Home 回桌面，把我们的界面盖掉）
        phases.put(
            phase("B 切后台（回桌面）", bSec, hbFile, displayId) { _ ->
                KeepAliveService.updateNote("B 切后台")
                // 用 Home 让本 app 进后台即可；不要拉系统设置页（会干扰用户正在用的界面）
                su("input keyevent 3")
                Thread.sleep(3_000)
                extra.put("phaseBForeground", foregroundOf(context))
            },
        )
        partialReport(cache, phases, extra, displayId, startedAt)

        // C：息屏（这才是这轮的硬指标）
        phases.put(
            phase("C 息屏", cSec, hbFile, displayId) { _ ->
                KeepAliveService.updateNote("C 息屏")
                su("input keyevent 223") // KEYCODE_SLEEP
                Thread.sleep(3_000)
                extra.put("phaseCScreenOff", screenOff())
                extra.put("phaseCPower", su("dumpsys power | grep -E 'oas:keepalive|mWakefulness|PARTIAL_WAKE_LOCK' | head -8"))
                extra.put("phaseCActivity", su("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -4"))
            },
        )
        partialReport(cache, phases, extra, displayId, startedAt)

        // D：唤醒 + 定向点击（证明「点击仍然只作用虚拟屏」没被后台态破掉）
        val before = File(cache, "keepalive_tap_before.raw")
        val after = File(cache, "keepalive_tap_after.raw")
        val mainBefore = File(cache, "keepalive_main_before.raw")
        val mainAfter = File(cache, "keepalive_main_after.raw")
        var tapDiff = 0.0
        var mainDiff = 0.0
        phases.put(
            phase("D 唤醒 + 定向点击", 15, hbFile, displayId) { _ ->
                KeepAliveService.updateNote("D 唤醒+点击")
                su("input keyevent 224") // KEYCODE_WAKEUP
                Thread.sleep(1_500)
                su("wm dismiss-keyguard")
                Thread.sleep(2_000)
                extra.put("phaseDScreenOff", screenOff())
                DisplayServer.grabRaw(displayId, before.absolutePath)
                Device.captureRaw(mainBefore.absolutePath)
                Thread.sleep(500)
                su("input -d $displayId tap ${WIDTH / 2} ${HEIGHT / 2}")
                Thread.sleep(1_200)
                DisplayServer.grabRaw(displayId, after.absolutePath)
                Device.captureRaw(mainAfter.absolutePath)
                tapDiff = pixelDiff(before, after)
                mainDiff = pixelDiff(mainBefore, mainAfter)
                extra.put("tapDiff", tapDiff)
                extra.put("mainDiff", mainDiff)
                before.delete(); after.delete(); mainBefore.delete(); mainAfter.delete()
            },
        )
        // D 的判定要把「定向点击」算进去：虚拟屏有变化、主屏没被影响
        val tapPassed = tapDiff > 0.0005 && mainDiff < 0.02
        phases.getJSONObject(phases.length() - 1).apply {
            put("tapDiff", tapDiff)
            put("mainDiff", mainDiff)
            put(
                "detail",
                optString("detail") + "；定向点击%s（虚拟屏差 %.4f%% / 主屏差 %.4f%%）".format(
                    if (tapPassed) "通过" else "未通过",
                    tapDiff * 100,
                    mainDiff * 100,
                ),
            )
            if (!tapPassed) put("ok", false)
        }
        partialReport(cache, phases, extra, displayId, startedAt)

        // ---- 4) 停心跳、收尾 ----
        val hbStop = DisplayServer.hbStop()
        extra.put("heartbeatStop", hbStop?.toString() ?: "hb stop 无响应（daemon 可能已停）")

        // 汇总判定：四段 4/4（D 段含定向点击）
        verdicts = 0
        for (i in 0 until phases.length()) {
            val p = phases.getJSONObject(i)
            if (p.optBoolean("ok")) verdicts++
        }
        extra.put("tapPassed", tapPassed)

        // 收尾：停心跳 / 销毁虚拟屏 / 停 daemon 与前台服务 / 亮屏 / 删临时 raw
        cleanupRuntime(context, displayId)

        finish(cache, phases, extra, displayId, startedAt, verdicts, null)
        lastMessage = "四段完成：$verdicts/${phases.length()}"
    }

    /**
     * 跑一段，并在段内采样（段结束后从心跳文件切出本段的切片算统计）。
     */
    private fun phase(
        name: String,
        seconds: Int,
        hbFile: File,
        displayId: Int,
        action: (Int) -> Unit,
    ): JSONObject {
        val out = JSONObject().put("name", name).put("plannedS", seconds)
        val t0 = System.currentTimeMillis()
        runCatching { action(displayId) }

        // 采样只读 daemon 写的 JSONL，**全程不发 socket 请求**：
        // 上一轮报告没落盘，就是验证线程被 socket 调用拖住（HANDOFF 11.5）。
        val states = mutableSetOf<String>()
        val deadline = t0 + seconds * 1000L
        val graceEnd = t0 + 3_000
        var lastTs = 0L
        var stalledMs = 0L
        while (System.currentTimeMillis() < deadline) {
            states.add(procState())
            val ts = lastBeatTs(hbFile)
            if (ts > lastTs) lastTs = ts
            val now = System.currentTimeMillis()
            if (lastTs == 0L) {
                if (now > graceEnd) {
                    stalledMs = -1L // 一直没出第一拍：daemon 心跳没起来
                    break
                }
            } else if (now - lastTs > HB_STALL_MS) {
                // 心跳断流：不再傻等整段（报告里能看出断在第几秒）
                stalledMs = now - lastTs
                break
            }
            Thread.sleep(1_000)
        }
        val actualS = (System.currentTimeMillis() - t0) / 1000.0
        val t1 = System.currentTimeMillis()
        val beats = readBeats(hbFile).filter { it.first in t0..t1 }

        val gaps = beats.map { it.first }.zipWithNext { a, b -> (b - a).toDouble() }
        val maxGap = gaps.maxOrNull() ?: -1.0
        val f0 = beats.firstOrNull()?.second ?: -1L
        val f1 = beats.lastOrNull()?.second ?: -1L
        val pids = beats.map { it.third }.distinct()

        val expected = (seconds * 1000.0 / HB_INTERVAL_MS).toInt()
        val enough = beats.size >= (expected * 0.9).toInt()
        val noHole = maxGap in 1.0..(HB_INTERVAL_MS * 2.0)
        val framesGrew = f1 > f0

        out.put("actualS", actualS)
        out.put("hbLines", beats.size)
        out.put("hbExpected", expected)
        out.put("maxGapMs", maxGap)
        out.put("frames0", f0)
        out.put("frames1", f1)
        out.put("framesDelta", f1 - f0)
        out.put("hbPids", JSONArray(pids))
        out.put("procStates", JSONArray(states.toList()))
        out.put("stalledMs", stalledMs)
        out.put("ok", enough && noHole && framesGrew && stalledMs == 0L)
        out.put(
            "detail",
            "daemon 心跳 %d/%d 拍，空洞 %.0fms（判据 <%.0fms），虚拟屏帧 %d→%d（delta=%d）"
                .format(beats.size, expected, maxGap, HB_INTERVAL_MS * 2.0, f0, f1, f1 - f0),
        )
        return out
    }

    // ------------------------------------------------------------------ //
    // 小工具
    // ------------------------------------------------------------------ //

    // ------------------------------------------------------------------ //
    // 收尾与异常兜底
    // ------------------------------------------------------------------ //

    /**
     * 把设备/进程恢复到干净状态（幂等，可重复调用）：
     * 停 Python 心跳 → 销毁虚拟屏 → 停 daemon 与前台服务 → 亮屏解锁 → 删临时 raw。
     * 「中途失败」也不会把屏幕留在熄灭状态、不会留下常驻进程。
     */
    private fun cleanupRuntime(context: Context, displayId: Int) {
        runCatching { if (PyRuntime.isStarted()) PyRuntime.call("stop_heartbeat") }
        runCatching { DisplayServer.hbStop() }
        runCatching { if (displayId >= 0) DisplayServer.destroy(displayId) }
        runCatching { DisplayServer.stop(context) }
        runCatching { KeepAliveService.stop(context) }
        runCatching { Thread.sleep(300) }
        runCatching { su("input keyevent 224") } // 保证不留在熄灭屏
        runCatching { su("wm dismiss-keyguard") }
        runCatching { DisplayServer.purgeTempRaw(context) }
    }

    /** 线程里抛异常时的最后兜底：先清理，再保证有一份可读报告（不覆盖已落盘的成功报告）。 */
    private fun emergencyCleanup(context: Context, t: Throwable) {
        val cache = cacheDir?.let { File(it) } ?: File(context.filesDir, "oas/cache").apply { mkdirs() }
        cleanupRuntime(context, lastDisplay)
        runCatching { File(cache, "keepalive_poc_error.txt").writeText(t.stackTraceToString()) }
        if (!reportWritten) {
            runCatching {
                val f = File(cache, "keepalive_poc.txt")
                val head = if (f.exists()) f.readText() else "OAS 后台保活验证（P1-1）\n"
                f.writeText(head + "\n致命异常（中途退出）: ${t.javaClass.simpleName}: ${t.message ?: t}\n")
            }
        }
    }

    /**
     * 渐进报告：每段结束就把当前结果落到 keepalive_poc.txt。
     *
     * **只写文件、不发 socket**：这样即使验证线程被冻/被杀，磁盘上也有可读的半成品。
     * 上一轮 C 段之后报告完全缺失，就是因为只有最后那一次写盘。
     */
    private fun partialReport(
        cache: File,
        phases: JSONArray,
        extra: JSONObject,
        displayId: Int,
        startedAt: Long,
    ) {
        runCatching {
            var passed = 0
            for (i in 0 until phases.length()) {
                if (phases.getJSONObject(i).optBoolean("ok")) passed++
            }
            val text = buildString {
                appendLine("OAS 后台保活验证（P1-1）  $passed/${phases.length()} 段通过（进行中）")
                appendLine("displayId=$displayId 已耗时 ${(System.currentTimeMillis() - startedAt) / 1000}s")
                for (i in 0 until phases.length()) {
                    val p = phases.getJSONObject(i)
                    appendLine("[${if (p.optBoolean("ok")) "OK" else "FAIL"}] ${p.optString("name")}")
                    appendLine("    ${p.optString("detail")}")
                }
                appendLine("心跳文件: ${extra.optString("heartbeatPath")}")
            }
            File(cache, "keepalive_poc.txt").writeText(text)
        }
    }

    private fun finish(
        cache: File,
        phases: JSONArray,
        extra: JSONObject,
        displayId: Int,
        startedAt: Long,
        passed: Int,
        fatal: String?,
    ) {
        reportWritten = true
        val total = phases.length().coerceAtLeast(1) // A/B/C/D（D 段含定向点击）
        val json = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("displayId", displayId)
            .put("canvas", "$WIDTH×${HEIGHT}@$DPI")
            .put("elapsedMs", System.currentTimeMillis() - startedAt)
            .put("passed", passed)
            .put("total", total)
            .put("fatal", fatal ?: "")
            .put("extra", extra)
            .put("phases", phases)
        File(cache, "keepalive_poc.json").writeText(json.toString(2))

        val text = buildString {
            appendLine("OAS 后台保活验证（P1-1）  通过 $passed/$total")
            if (fatal != null) appendLine("致命: $fatal")
            for (i in 0 until phases.length()) {
                val p = phases.getJSONObject(i)
                appendLine("[${if (p.optBoolean("ok")) "OK" else "FAIL"}] ${p.optString("name")}")
                appendLine("    ${p.optString("detail")}")
            }
            appendLine("前台服务: ${KeepAliveService.statusLine()}")
            appendLine("wakeLockHeld=${extra.optBoolean("wakeLockHeld")} notifPerm=${extra.optString("notificationPermission")}")
            appendLine("点击: 虚拟屏差 ${"%.4f".format(extra.optDouble("tapDiff", 0.0) * 100)}% / 主屏差 ${"%.4f".format(extra.optDouble("mainDiff", 0.0) * 100)}%")
            appendLine("心跳文件: ${extra.optString("heartbeatPath")}")
            appendLine("JSON: ${File(cache, "keepalive_poc.json").absolutePath}")
        }
        File(cache, "keepalive_poc.txt").writeText(text)
    }

    private fun su(cmd: String): String = runCatching {
        SuShell.get()?.run(cmd, 20_000)?.output?.trim() ?: ""
    }.getOrDefault("")

    private fun screenOff(): String = su("dumpsys power | grep -E 'mWakefulness=' | head -1").trim()

    private fun foregroundOf(context: Context): String = su(
        "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -2",
    )

    /** 当前进程状态位（R/S/D/T/Z…）。T = 被 SIGSTOP（freezer 冻住的典型特征）。 */
    private fun procState(): String = runCatching {
        val stat = File("/proc/self/stat").readText()
        // 进程名可能带空格，取最后一个 ')' 之后的第二个字段
        val after = stat.substringAfterLast(')').trim()
        after.split(' ').getOrNull(0) ?: "?"
    }.getOrDefault("?")

    private fun notificationPermission(context: Context): String = runCatching {
        val pm = context.packageManager
        val granted = pm.checkPermission("android.permission.POST_NOTIFICATIONS", context.packageName) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) "granted" else "denied"
    }.getOrDefault("unknown")

    private fun countLines(file: File): Int = runCatching {
        if (!file.exists()) 0 else file.useLines { it.count() }
    }.getOrDefault(0)

    private fun readLines(file: File, from: Int, to: Int): List<String> = runCatching {
        if (!file.exists()) return emptyList()
        file.useLines { seq -> seq.drop(from).take((to - from).coerceAtLeast(0)).toList() }
    }.getOrDefault(emptyList())

    /** 解析 daemon 心跳 JSONL：返回 (ts, frames, pid)，坏行/半行跳过。 */
    private fun readBeats(file: File): List<Triple<Long, Long, Long>> = runCatching {
        if (!file.exists()) {
            emptyList()
        } else {
            file.useLines { seq ->
                seq.mapNotNull { line ->
                    if (!line.trimEnd().endsWith("}")) {
                        null
                    } else {
                        runCatching {
                            val o = JSONObject(line)
                            Triple(o.getLong("ts"), o.optLong("frames", -1L), o.optLong("pid", -1L))
                        }.getOrNull()
                    }
                }.toList()
            }
        }
    }.getOrDefault(emptyList())

    /** 最后一条心跳的时间戳（0 = 还没有）。文件只有几 KB，读全文件最省事也最不容易出错。 */
    private fun lastBeatTs(file: File): Long = readBeats(file).lastOrNull()?.first ?: 0L

    /** raw（screencap 兼容格式：16B 头 + RGBA8888）像素差异比例。 */
    private fun pixelDiff(a: File, b: File): Double = runCatching {
        if (!a.exists() || !b.exists()) return 0.0
        val ba = a.readBytes()
        val bb = b.readBytes()
        val offset = if (ba.size >= 16 && bb.size >= 16) 16 else 12
        val n = minOf(ba.size, bb.size) - offset
        if (n <= 0) return 0.0
        var diff = 0
        var i = offset
        while (i + 3 < ba.size && i + 3 < bb.size) {
            if (kotlin.math.abs((ba[i].toInt() and 0xff) - (bb[i].toInt() and 0xff)) > 24 ||
                kotlin.math.abs((ba[i + 1].toInt() and 0xff) - (bb[i + 1].toInt() and 0xff)) > 24 ||
                kotlin.math.abs((ba[i + 2].toInt() and 0xff) - (bb[i + 2].toInt() and 0xff)) > 24
            ) {
                diff++
            }
            i += 4
        }
        diff.toDouble() / (n / 4).coerceAtLeast(1)
    }.getOrDefault(0.0)
}