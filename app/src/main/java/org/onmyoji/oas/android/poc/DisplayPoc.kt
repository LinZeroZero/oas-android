package org.onmyoji.oas.android.poc

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import org.onmyoji.oas.android.root.DisplayServer
import org.onmyoji.oas.android.root.Device
import org.onmyoji.oas.android.root.SuShell
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * 阶段 1 验证器：把 HANDOFF「六、待办」里的 7 项判据一次跑完，结果落盘。
 *
 * 为什么不用 UI：上一轮学到教训 —— Compose 里的结果卡片在 uiautomator 里看不到，
 * 所以这里所有结论都写进 `filesDir/oas/cache/display_poc.json` + `.txt`，无 UI 也能验证。
 */
object DisplayPoc {

    const val GAME_PKG = "com.netease.onmyoji"
    const val GAME_COMPONENT = "$GAME_PKG/.Client"
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val DPI = 320

    internal class Step(val name: String, var ok: Boolean, var detail: String, var ms: Long = 0)

    fun run(context: Context, progress: (String) -> Unit = {}): Result {
        val workDir = File(context.filesDir, "oas/cache").apply { mkdirs() }
        val steps = mutableListOf<Step>()
        val extra = JSONObject()
        var displayId = -1
        var started = 0L

        fun step(name: String, block: () -> Pair<Boolean, String>): Step {
            progress(name)
            val t0 = System.currentTimeMillis()
            val s = Step(name, false, "")
            runCatching(block)
                .onSuccess { (ok, detail) ->
                    s.ok = ok
                    s.detail = detail
                }
                .onFailure { s.detail = "异常: ${it.message ?: it}" }
            s.ms = System.currentTimeMillis() - t0
            steps.add(s)
            return s
        }

        val result = Result(workDir)

        // 1) 建屏
        val createStep = step("1. 建 PUBLIC|OWN_CONTENT_ONLY 虚拟屏") {
            val err = DisplayServer.ensureStarted(context)
            if (err != null) return@step false to "daemon 启动失败: $err"
            val t = System.currentTimeMillis()
            displayId = DisplayServer.create(WIDTH, HEIGHT, DPI)
            started = System.currentTimeMillis()
            if (displayId < 0) {
                false to "create 失败"
            } else {
                true to "displayId=$displayId，${System.currentTimeMillis() - t}ms，" +
                    "display=$WIDTH×$HEIGHT@$DPI"
            }
        }
        if (displayId < 0) {
            result.finish(steps, extra, displayId, started)
            return result
        }

        // 2) 游戏起在该屏
        step("2. 游戏起在虚拟屏（不被弹回主屏）") {
            val out = DisplayServer.launchOnDisplay(GAME_COMPONENT, displayId)
            val ok = out.contains("Status: ok")
            val where = activityDisplayOf(GAME_PKG)
            extra.put("launchOut", out.replace("\n", " ").take(300))
            extra.put("gameActivityDisplay", where)
            val onTarget = where == displayId
            (ok && onTarget) to buildString {
                append(if (ok) "am start ok" else "am start 失败")
                append("；dumpsys 显示游戏活动在 display ")
                append(if (where < 0) "未知" else where.toString())
                append(if (onTarget) "（正确）" else "（被移走，需 watchdog）")
            }
        }

        // 3) 等游戏渲染
        step("3. 等待渲染并抓 3 帧") {
            Thread.sleep(20_000)
            val samples = JSONArray()
            var okAll = true
            var lastDetail = ""
            repeat(3) { i ->
                val raw = File(workDir, "poc_vdisplay_$i.raw")
                val info = DisplayServer.grabInfo(displayId, raw.absolutePath)
                if (info == null || !info.optBoolean("ok")) {
                    okAll = false
                    lastDetail = "grab 失败: ${info?.optString("error") ?: "无响应"}"
                } else {
                    samples.put(
                        JSONObject()
                            .put("index", i)
                            .put("bytes", info.optLong("bytes"))
                            .put("ms", info.optDouble("ms"))
                            .put("mean", info.optDouble("mean"))
                            .put("blackRatio", info.optDouble("blackRatio"))
                            .put("frames", info.optLong("frames"))
                            .put("rotation", info.optInt("rotation"))
                    )
                    lastDetail = "bytes=${info.optLong("bytes")} mean=%.2f black=%.4f ms=%.1f frames=%d rot=%d"
                        .format(
                            info.optDouble("mean"), info.optDouble("blackRatio"),
                            info.optDouble("ms"), info.optLong("frames"), info.optInt("rotation")
                        )
                }
            }
            extra.put("frames", samples)
            // 判据：出帧非黑
            val first = if (samples.length() > 0) samples.getJSONObject(0) else null
            val mean = first?.optDouble("mean", 0.0) ?: 0.0
            val frames = first?.optLong("frames", 0) ?: 0
            val ok = okAll && mean > 8.0 && frames > 10
            ok to lastDetail
        }

        // 4) 渲染内容 + PNG 落盘
        step("4. 画面内容落 PNG（肉眼确认）") {
            val raw = File(workDir, "poc_vdisplay_0.raw")
            if (!raw.exists() || raw.length() < 1024) return@step false to "raw 不存在"
            val png = File(workDir, "poc_vdisplay_0.png")
            val ok = rawToPng(raw, png, WIDTH, HEIGHT)
            if (ok) result.pngPath = png.absolutePath
            (ok) to (if (ok) "PNG ${png.length() / 1024}KB → ${png.name}" else "PNG 编码失败")
        }

        // 5) 定向点击只作用虚拟屏
        step("5. 定向点击只作用虚拟屏") {
            val before = File(workDir, "poc_tap_before.raw")
            val after = File(workDir, "poc_tap_after.raw")
            val mainBefore = File(workDir, "poc_main_before.raw")
            val mainAfter = File(workDir, "poc_main_after.raw")
            DisplayServer.grabRaw(displayId, before.absolutePath)
            Device.captureRaw(mainBefore.absolutePath)
            Thread.sleep(600)
            val tap = SuShell.get()?.run("input -d $displayId tap ${WIDTH / 2} ${HEIGHT / 2}", 8_000)
            Thread.sleep(1_200)
            DisplayServer.grabRaw(displayId, after.absolutePath)
            Device.captureRaw(mainAfter.absolutePath)
            val virtualDiff = pixelDiff(before, after)
            val mainDiff = pixelDiff(mainBefore, mainAfter)
            extra.put("tapCode", tap?.code ?: -1)
            extra.put("virtualDiff", virtualDiff)
            extra.put("mainDiff", mainDiff)
            // 判据：虚拟屏有变化（说明点到游戏里了），主屏几乎不变
            val ok = virtualDiff > 0.0005 && mainDiff < 0.02
            ok to "点击后虚拟屏差异 %.4f%%，主屏差异 %.4f%%（tap code=%d）"
                .format(virtualDiff * 100, mainDiff * 100, tap?.code ?: -1)
        }

        // 6) 主屏并发使用，虚拟屏仍出帧
        step("6. 主屏刷别的 App 时虚拟屏仍持续出帧") {
            // 关键：这一步必须在 daemon 侧采样。我们一被切到后台就会被 cached-app freezer
            // 冻住（HANDOFF 五-B 实测），在 app 进程里根本测不出「有没有出帧」。
            val cmd = "watch $displayId 4000 org.onmyoji.oas.android/.MainActivity " +
                "am start -n com.android.settings/.Settings"
            val json = JSONObject(DisplayServer.rawCommand(cmd, 30_000).ifBlank { "{}" })
            val f0 = json.optLong("frames0", -1)
            val f1 = json.optLong("frames1", -1)
            extra.put("framesA", f0)
            extra.put("framesB", f1)
            extra.put("watchLaunchOut", json.optString("launchOut").take(200))
            val ok = json.optBoolean("ok") && f1 > f0
            ok to "主屏切到「设置」的 4s 内，虚拟屏帧数 $f0 → $f1（delta=${f1 - f0}）"
        }

        // 7) 抓帧耗时 + 功耗参考
        step("7. 抓帧耗时 / 电量参考") {
            val shell = SuShell.get() ?: return@step false to "su 不可用"
            val batteryBefore = shell.run("dumpsys battery | grep -E 'level|temperature'", 8_000).output
            val samples = mutableListOf<Double>()
            repeat(20) { i ->
                val raw = File(workDir, "poc_perf_$i.raw")
                val info = DisplayServer.grabInfo(displayId, raw.absolutePath)
                info?.optDouble("ms", -1.0)?.takeIf { it >= 0 }?.let { samples.add(it) }
                raw.delete()
            }
            val batteryAfter = shell.run("dumpsys battery | grep -E 'level|temperature'", 8_000).output
            val sorted = samples.sorted()
            val p50 = sorted.getOrElse(sorted.size / 2) { -1.0 }
            val p95 = sorted.getOrElse((sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)) { -1.0 }
            extra.put("grabP50", p50)
            extra.put("grabP95", p95)
            extra.put("batteryBefore", batteryBefore.replace("\n", " "))
            extra.put("batteryAfter", batteryAfter.replace("\n", " "))
            (samples.isNotEmpty()) to "p50=%.1fms p95=%.1fms（%d 次） battery: %s".format(
                p50, p95, samples.size, batteryBefore.replace("\n", " ").trim()
            )
        }

        // 收尾：销毁屏幕并停掉 daemon（不留常驻进程）
        progress("清理：销毁虚拟屏并停止 daemon")
        runCatching { DisplayServer.destroy(displayId) }
        runCatching { DisplayServer.stop(context) }
        extra.put("destroyed", true)

        result.finish(steps, extra, displayId, started)
        return result
    }

    class Result internal constructor(
        private val workDir: File,
    ) {
        var jsonPath: String = ""
        var textPath: String = ""
        var pngPath: String = ""

        internal fun finish(steps: List<Step>, extra: JSONObject, displayId: Int, started: Long) {
            val passed = steps.count { it.ok }
            val json = JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("displayId", displayId)
                .put("canvas", "$WIDTH×$HEIGHT@$DPI")
                .put("elapsedMs", if (started > 0) System.currentTimeMillis() - started else 0)
                .put("passed", passed)
                .put("total", steps.size)
                .put("png", pngPath)
                .put("extra", extra)
                .put("steps", JSONArray().apply {
                    steps.forEach { s ->
                        put(
                            JSONObject()
                                .put("name", s.name)
                                .put("ok", s.ok)
                                .put("ms", s.ms)
                                .put("detail", s.detail)
                        )
                    }
                })
            val jsonFile = File(workDir, "display_poc.json")
            jsonFile.writeText(json.toString(2))
            jsonPath = jsonFile.absolutePath

            val text = buildString {
                appendLine("OAS 虚拟屏验证（PoC-A）  通过 $passed/${steps.size}")
                appendLine("画布 $WIDTH×$HEIGHT@$DPI  displayId=$displayId")
                steps.forEach { s ->
                    appendLine("[${if (s.ok) "OK" else "FAIL"}] ${s.name} (${s.ms}ms)")
                    appendLine("    ${s.detail}")
                }
                if (pngPath.isNotEmpty()) appendLine("PNG: $pngPath")
                appendLine("JSON: $jsonPath")
            }
            val textFile = File(workDir, "display_poc.txt")
            textFile.writeText(text)
            textPath = textFile.absolutePath
        }

        fun summary(): String = runCatching { File(textPath).readText() }.getOrDefault("")
    }

    // ------------------------------------------------------------------ //
    // 小工具
    // ------------------------------------------------------------------ //

    /** 从 dumpsys 里找出某包当前 activity 所在的 displayId；找不到返回 -1。 */
    private fun activityDisplayOf(pkg: String): Int {
        val out = SuShell.get()?.run("dumpsys activity activities", 20_000)?.output ?: return -1
        var currentDisplay = -1
        for (line in out.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("Display #")) {
                currentDisplay = trimmed.removePrefix("Display #").substringBefore(' ')
                    .toIntOrNull() ?: -1
            } else if (trimmed.contains(pkg) && trimmed.startsWith("topResumedActivity")) {
                return currentDisplay
            } else if (trimmed.contains("$pkg/") && trimmed.contains("ActivityRecord")) {
                return currentDisplay
            }
        }
        return -1
    }

    /** screencap 兼容 raw（16 字节头 + RGBA8888）→ PNG。 */
    private fun rawToPng(raw: File, png: File, width: Int, height: Int): Boolean = runCatching {
        val bytes = raw.readBytes()
        val needed = width * height * 4
        val offset = if (bytes.size >= needed + 16) 16 else 12
        if (bytes.size - offset < needed) return@runCatching false
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes, offset, needed))
        FileOutputStream(png).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        true
    }.getOrDefault(false)

    /** 两张 raw 的像素差异比例（任一通道差 > 24 记为不同）。 */
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