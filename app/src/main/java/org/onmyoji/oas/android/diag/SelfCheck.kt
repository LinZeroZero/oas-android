package org.onmyoji.oas.android.diag

import android.content.Context
import android.graphics.BitmapFactory
import android.os.StatFs
import org.json.JSONObject
import org.onmyoji.oas.android.python.PyRuntime
import org.onmyoji.oas.android.root.RootShell
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CheckState { OK, WARN, FAIL }

data class CheckItem(
    val name: String,
    val state: CheckState,
    val detail: String,
    val costMs: Long = 0,
)

/**
 * 阶段 0 自检：把「Python / NumPy / root / 截图 / 目录 / 空间」这些
 * 决定整条方案是否成立的环节逐个跑一遍，结果直接显示在首页。
 */
object SelfCheck {

    fun runAll(context: Context, onItem: (CheckItem) -> Unit) {
        checkPython(context, onItem)
        checkNumpy(onItem)
        checkRoot(onItem)
        checkScreenshot(context, onItem)
        checkWorkdir(context, onItem)
        checkStorage(context, onItem)
    }

    private fun checkPython(context: Context, onItem: (CheckItem) -> Unit) {
        measure("Python 运行时") {
            PyRuntime.ensureStarted(context)
            val hello = PyRuntime.call("hello")
            // L2 子集烟测：module/base 逐模块 import + 纯计算，结果只落 logcat（不动 UI）
            try {
                android.util.Log.i("OAS_SMOKE", PyRuntime.call("smoke_base"))
            } catch (t: Throwable) {
                android.util.Log.e("OAS_SMOKE", "smoke_base failed: ${t.message}", t)
            }
            // BG-40 OCR 烟测：ppocronnx shim -> OcrRuntime(AAR) -> ONNX
            try {
                android.util.Log.i("OAS_OCR", PyRuntime.call("ocr_probe"))
            } catch (t: Throwable) {
                android.util.Log.e("OAS_OCR", "ocr_probe failed: ${t.message}", t)
            }
            // BG-41：全图检测（det）烟测（独立 try，避免 det 失败被记成 ocr_probe failed）
            try {
                android.util.Log.i("OAS_DET", PyRuntime.call("det_probe"))
            } catch (t: Throwable) {
                android.util.Log.e("OAS_DET", "det_probe failed: ${t.message}", t)
            }
            CheckItem("Python 运行时", CheckState.OK, hello)
        }.also(onItem)
    }

    private fun checkNumpy(onItem: (CheckItem) -> Unit) {
        measure("NumPy") {
            if (!PyRuntime.isStarted()) {
                return@measure CheckItem("NumPy", CheckState.FAIL, "Python 未启动")
            }
            val json = JSONObject(PyRuntime.call("probe"))
            val numpy = json.optString("numpy", "")
            if (numpy.isNotEmpty() && json.optBoolean("numpy_matmul_ok")) {
                CheckItem("NumPy", CheckState.OK, "$numpy（矩阵乘法正常）")
            } else {
                val detail = json.optString("numpy_error", json.toString())
                    .replace("\n", " ⏎ ")
                CheckItem(
                    "NumPy",
                    CheckState.FAIL,
                    detail.take(900),
                )
            }
        }.also(onItem)
    }

    private fun checkRoot(onItem: (CheckItem) -> Unit) {
        measure("Root 权限") {
            val id = RootShell.exec("id")
            if (id.output.contains("uid=0")) {
                val size = RootShell.screenSize().lines().firstOrNull().orEmpty()
                CheckItem("Root 权限", CheckState.OK, "$size / ${id.output.lineSequence().first()}")
            } else {
                CheckItem(
                    "Root 权限",
                    CheckState.FAIL,
                    "su 不可用：${id.output.ifBlank { "无输出" }}",
                )
            }
        }.also(onItem)
    }

    private fun checkScreenshot(context: Context, onItem: (CheckItem) -> Unit) {
        measure("root 截图") {
            val target = File(context.cacheDir, "selfcheck/shot.png")
            val result = RootShell.captureScreenshot(target)
            if (!target.exists() || target.length() < 1024) {
                return@measure CheckItem(
                    "root 截图",
                    CheckState.FAIL,
                    "screencap 失败：${result.output.ifBlank { "无输出" }}",
                )
            }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, options)
            val ratio = options.outWidth.toDouble() / options.outHeight.coerceAtLeast(1)
            val state = if (options.outWidth > 0) {
                if (kotlin.math.abs(ratio - 16.0 / 9.0) < 0.02) CheckState.OK else CheckState.WARN
            } else {
                CheckState.FAIL
            }
            val hint = if (state == CheckState.WARN) "，非 16:9，后面要强制 1280x720" else ""
            CheckItem(
                "root 截图",
                state,
                "${options.outWidth}x${options.outHeight}，${target.length() / 1024}KB$hint",
            )
        }.also(onItem)
    }

    private fun checkWorkdir(context: Context, onItem: (CheckItem) -> Unit) {
        measure("工作目录") {
            val dir = File(context.filesDir, "oas")
            val created = dir.exists() || dir.mkdirs()
            val probe = File(dir, ".write_test").apply { writeText("ok") }
            val writable = probe.exists() && probe.readText() == "ok"
            probe.delete()
            if (created && writable) {
                CheckItem("工作目录", CheckState.OK, dir.absolutePath)
            } else {
                CheckItem("工作目录", CheckState.FAIL, "无法写入 ${dir.absolutePath}")
            }
        }.also(onItem)
    }

    private fun checkStorage(context: Context, onItem: (CheckItem) -> Unit) {
        measure("存储空间") {
            val stat = StatFs(context.filesDir.absolutePath)
            val freeGb = stat.availableBytes / 1024.0 / 1024.0 / 1024.0
            CheckItem(
                "存储空间",
                if (freeGb > 1.0) CheckState.OK else CheckState.WARN,
                String.format(Locale.US, "可用 %.1f GB", freeGb),
            )
        }.also(onItem)
    }

    private inline fun measure(name: String, block: () -> CheckItem): CheckItem {
        val start = System.currentTimeMillis()
        val item = runCatching(block).getOrElse { error ->
            CheckItem(name, CheckState.FAIL, error.stackTraceToString().lineSequence().take(6).joinToString(" | "))
        }
        return item.copy(costMs = System.currentTimeMillis() - start)
    }

    fun formatReport(items: List<CheckItem>): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("OAS Android 自检 $time")
            items.forEach { item ->
                appendLine("[${item.state}] ${item.name} (${item.costMs}ms): ${item.detail}")
            }
        }
    }

    /**
     * 把自检结果落到 filesDir/last_selfcheck.txt。
     * 好处：屏幕息屏/黑屏时也能确认链路是否跑通，用户反馈问题时也能直接发这个文件。
     */
    fun writeReport(context: Context, items: List<CheckItem>): File {
        val file = File(context.filesDir, "last_selfcheck.txt")
        file.writeText(formatReport(items))
        return file
    }
}