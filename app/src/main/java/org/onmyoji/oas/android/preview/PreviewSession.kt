package org.onmyoji.oas.android.preview

import org.json.JSONObject
import org.onmyoji.oas.android.root.DisplayServer
import org.onmyoji.oas.android.root.SuShell

/**
 * BG-35 C-2 · 片③：预览关闭时的**释放与校验**（UI 开关在 `MainActivity.PreviewSection`）。
 *
 * 纪律（BG-35 口径④）：退出预览即释放 —— 停渲染线程（调用方做）→ `destroy` 所有 oas 虚拟屏 →
 * 用 `dumpsys display` 复核 `DisplayInfo{"oas"` **归 0**。
 * 不注册任何自启、不改分辨率/dpi、不停 daemon（daemon 的生死由「停止虚拟屏服务」按钮与
 * `MainActivity.onDestroy` 管，预览只负责自己的那块屏）。
 */
object PreviewSession {

    /** 释放结果：`dumpCount` 是 `DisplayInfo{"oas"` 的**行数**（本机 base+override 各一行 → 0 才算真归零）。 */
    data class Release(
        val oasIdsBefore: List<Int>,
        val destroyed: List<Int>,
        val oasIdsAfter: List<Int>,
        val dumpCount: Int,
        val dumpRaw: String,
    ) {
        val zero: Boolean get() = dumpCount == 0 && oasIdsAfter.isEmpty()

        fun summary(): String = buildString {
            append("释放：销毁 ").append(destroyed.size).append(" 块（")
            append(if (destroyed.isEmpty()) "无" else destroyed.joinToString(",")).append("）")
            append("；DisplayInfo{\"oas\"行数=").append(dumpCount)
            append("；剩余 oas 屏=").append(if (oasIdsAfter.isEmpty()) "0" else oasIdsAfter.joinToString(","))
            if (!zero) append("（**未归零**，dump=").append(dumpRaw.take(120)).append("）")
        }
    }

    /** 现查 daemon 里 name=oas 的虚拟屏 id 列表；daemon 不在/无屏 → 空列表。 */
    fun oasDisplayIds(): List<Int> {
        val line = DisplayServer.rawCommand("list", 2_000)
        if (line.isBlank()) return emptyList()
        return try {
            val arr = JSONObject(line).optJSONArray("displays") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                if (o.optString("name") == "oas") o.optInt("displayId", -1).takeIf { it >= 0 } else null
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** 复核 `dumpsys display` 里 `DisplayInfo{"oas"` 的行数；拿不到返回 -1。 */
    fun dumpsysOasCount(): Pair<Int, String> {
        val shell = SuShell.get() ?: return -1 to "su 不可用"
        val r = shell.run("dumpsys display | grep -c 'DisplayInfo{\"oas\"'", 8_000)
        val text = r.output.trim()
        return (text.toIntOrNull() ?: -1) to text
    }

    /** 销毁所有 oas 屏并复核归零。**同步、阻塞**，调用方应放在 IO 线程。 */
    fun releaseAll(): Release {
        val before = oasDisplayIds()
        val destroyed = ArrayList<Int>(before.size)
        for (id in before) {
            if (DisplayServer.destroy(id)) destroyed.add(id)
        }
        val after = oasDisplayIds()
        val (count, raw) = dumpsysOasCount()
        return Release(before, destroyed, after, count, raw)
    }
}