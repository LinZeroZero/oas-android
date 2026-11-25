package org.onmyoji.oas.android.ocr

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/**
 * ONNX Runtime 的 OCR 推理入口（供 Python 侧 ppocronnx shim 通过 Chaquopy 调用）。
 *
 * 为什么在 Kotlin 侧做：
 * - Chaquopy 的 cp313 没有 onnxruntime 轮子，Python 侧装不了；
 * - 但 Maven 有 `onnxruntime-android` AAR，可直接在进程内跑。
 *
 * 为什么 argmax 在 Kotlin 侧做：
 * 模型输出是 [1, T, 6625] 浮点（T 通常 40~80）。若整块传回 Python 再 argmax，
 * 每次要跨 Python/Java 边界搬 ~2 MB。这里直接压成 T 个索引（几十字节）。
 */
object OcrRuntime {

    /** 模型与字典都在 assets/ocr/ 下 */
    private const val ASSET_DIR = "ocr"
    private const val MODEL_ASSET = "$ASSET_DIR/ch_PP-OCRv2_rec_infer.onnx"

    @Volatile
    private var env: OrtEnvironment? = null

    @Volatile
    private var session: OrtSession? = null

    @Volatile
    private var inputName: String = "x"

    @Volatile
    private var lastError: String = ""

    /** 保存 Application context，供 [readAssetText] 使用。 */
    @Volatile
    private var appContext: Context? = null

    @JvmStatic
    fun lastError(): String = lastError

    @JvmStatic
    fun isReady(): Boolean = session != null

    /**
     * 初始化。可重复调用；已初始化则直接返回。
     *
     * @param useNnapi true 时尝试挂 NNAPI（GPU/NPU）；不支持的算子会自动回退 CPU
     * @return 成功与否；失败原因见 [lastError]
     */
    @JvmStatic
    @Synchronized
    fun init(context: Context, useNnapi: Boolean): Boolean {
        appContext = context.applicationContext
        if (session != null) return true
        return try {
            val modelFile = copyAssetOnce(context, MODEL_ASSET)
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(2)
            // 日志调到 warning，避免每帧刷屏
            opts.setSessionLogLevel(ai.onnxruntime.OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING)
            if (useNnapi) {
                try {
                    opts.addNnapi()
                } catch (t: Throwable) {
                    // NNAPI 不可用不算失败：回退 CPU
                    lastError = "addNnapi failed, fallback CPU: ${t.message}"
                }
            }
            val s = e.createSession(modelFile.absolutePath, opts)
            env = e
            session = s
            inputName = s.inputNames.firstOrNull() ?: "x"
            true
        } catch (t: Throwable) {
            lastError = "init failed: ${t.javaClass.simpleName}: ${t.message}"
            session = null
            false
        }
    }

    /**
     * 跑一次识别，返回「每个时间步的 argmax 索引 + 对应概率」。
     *
     * @param input NCHW 展平的 float 数组，长度必须 = c*h*w
     * @return 长度 2T：前 T 个是索引（float 表示），后 T 个是概率
     */
    @JvmStatic
    fun runRec(input: FloatArray, c: Int, h: Int, w: Int): FloatArray {
        val e = env ?: throw IllegalStateException("OcrRuntime.init 未调用")
        val s = session ?: throw IllegalStateException("session 未就绪: $lastError")
        require(input.size == c * h * w) { "输入长度 ${input.size} != $c*$h*$w" }

        val shape = longArrayOf(1L, c.toLong(), h.toLong(), w.toLong())
        OnnxTensor.createTensor(e, FloatBuffer.wrap(input), shape).use { tensor ->
            s.run(mapOf(inputName to tensor)).use { result ->
                // 输出 [1][T][classNum]
                @Suppress("UNCHECKED_CAST")
                val out = result.get(0).value as Array<Array<FloatArray>>
                val tSteps = out[0].size
                val res = FloatArray(tSteps * 2)
                for (t in 0 until tSteps) {
                    val row = out[0][t]
                    var best = 0
                    var bestV = row[0]
                    for (i in 1 until row.size) {
                        if (row[i] > bestV) { bestV = row[i]; best = i }
                    }
                    res[t] = best.toFloat()
                    res[tSteps + t] = bestV
                }
                return res
            }
        }
    }

    /**
     * 读 assets 下任意文本文件，返回全文。
     *
     * 供 Python 侧读字典用：Chaquopy 里 `import java.io.X` 不成立
     * （2026-11-25 真机报 `ModuleNotFoundError: No module named 'java.io'`），
     * 而 Kotlin 的 `assets.open(...).bufferedReader()` 是已验证路径（模型加载就在用）。
     */
    @JvmStatic
    fun readAssetText(assetPath: String): String {
        val ctx = appContext ?: throw IllegalStateException("OcrRuntime.init 未调用")
        return ctx.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** 类别数（= 1 blank + 字典 + 1 space），供 Python 侧校验 */
    @JvmStatic
    fun classCount(): Int {
        // PP-OCRv2 rec 固定 6625；写死以免再跑一次推理探测
        return 6625
    }

    @JvmStatic
    @Synchronized
    fun close() {
        runCatching { session?.close() }
        session = null
    }

    /** 把 assets 里的模型拷到 filesDir 一次（OrtSession 需要真实文件路径） */
    private fun copyAssetOnce(context: Context, assetPath: String): File {
        val name = assetPath.substringAfterLast('/')
        val out = File(context.filesDir, "ocr/$name")
        if (out.isFile && out.length() > 0) return out
        out.parentFile?.mkdirs()
        context.assets.open(assetPath).use { ins ->
            out.outputStream().use { os -> ins.copyTo(os) }
        }
        return out
    }
}
