package org.onmyoji.oas.android.preview

import android.graphics.Bitmap
import org.json.JSONObject
import org.onmyoji.oas.android.root.DisplayServer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * BG-35 C-2 · 片①：App 内实时预览的「取一帧 + RGBA→Bitmap」能力（**不含 UI、不含线程**）。
 *
 * 通路为什么只复用 `grab`：
 * - 本轮硬约束是**不重打 server.jar**，所以不能给 daemon 加新命令（如二进制直推帧）。
 * - daemon 侧 `ImageReader` 早已是建屏输出面（`DisplayEngine.create` :101-103），`grab` 只是把
 *   最新一帧按 RGBA_8888 落盘并回 JSON（实测 40~66 ms/帧），是现成可用的唯一通路。
 * - `mirror` 虽支持任意 w/h（能缩到 640×360 = 0.92 MB），但 `SocketServer.cmdMirror` 里硬编码
 *   `Thread.sleep(1200)` 且每帧建屏+销毁（`:171-176`）→ 帧率锁死 ≤0.8 fps，预览不可用，已排除。
 *
 * 尺寸约定：捕获侧恒 1280×720（画布硬约束，不改分辨率/dpi）；缩放只发生在显示侧。
 * 线程/UI 由片②③接：本类所有方法都是**同步、可重入**的，由调用方在自己线程里按帧率调。
 */
object PreviewFrameSource {

    /** 抓一帧的结果。`bytes`/`ms`/`frames` 原样取自 daemon 回包，便于自测记录。 */
    data class Frame(val bytes: Long, val ms: Double, val frames: Long, val path: String)

    /** 复用缓冲：1280×720×4 = 3,686,400 B 定长，避免每帧分配 3.6 MB 触发 GC 抖动。 */
    private var packedBuffer: ByteBuffer? = null
    private var strideBuffer: ByteArray? = null

    /**
     * 现查当前 oas 虚拟屏 id；没有返回 -1。
     * **每次都要现查**：displayId 每次建屏都会变（历史实测 36 → 37 → 38），绝不能缓存。
     */
    fun currentDisplayId(): Int {
        val line = DisplayServer.rawCommand("list", 2_000)
        if (line.isBlank()) return -1
        return try {
            val arr = JSONObject(line).optJSONArray("displays") ?: return -1
            if (arr.length() == 0) return -1
            arr.optJSONObject(0)?.optInt("displayId", -1) ?: -1
        } catch (t: Throwable) {
            -1
        }
    }

    /**
     * 抓一帧到 [dir]/frame.raw 并返回结果；daemon 不在 / 屏不在 / 超时一律返回 null。
     * 超时按 4 s（与 `DisplayServer.grabInfo` 一致），保证「只取最新帧、不排队、超时丢帧」。
     */
    fun grabTo(dir: File, displayId: Int = -1): Frame? {
        val id = if (displayId >= 0) displayId else currentDisplayId()
        if (id < 0) return null
        if (!dir.exists() && !dir.mkdirs()) return null
        val out = File(dir, "frame.raw")
        val json = JSONObject(
            DisplayServer.rawCommand("grab $id ${out.absolutePath}", 4_000).ifBlank { "{}" }
        )
        val grab = json.optJSONObject("grab") ?: return null
        if (!grab.optBoolean("ok", false)) return null
        return Frame(
            bytes = grab.optLong("bytes", -1L),
            ms = grab.optDouble("ms", -1.0),
            frames = grab.optLong("frames", -1L),
            path = out.absolutePath,
        )
    }

    /**
     * 把 [file] 里的 RGBA_8888 raw 解到**调用方提供的** [bitmap] 上（复用同一张 Bitmap）。
     *
     * [strideBytes] 来自 daemon 回包的 `rowStride`（本机实测 5120 = 1280×4，无 padding）；
     * 若未来出现 padding，这里按行拷，不会解出斜图。
     * 返回 false = 文件不完整 / 尺寸不匹配（调用方应丢帧，不要显示半张图）。
     */
    fun decodeInto(file: File, bitmap: Bitmap, strideBytes: Int = bitmap.width * 4): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val rowBytes = w * 4
        if (strideBytes < rowBytes) return false
        val need = strideBytes.toLong() * h
        if (file.length() < need) return false

        val packed = packedBuffer?.takeIf { it.capacity() >= rowBytes * h }
            ?: ByteBuffer.allocate(rowBytes * h).also { packedBuffer = it }
        packed.clear()

        try {
            RandomAccessFile(file, "r").use { raf ->
                if (strideBytes == rowBytes) {
                    // 快路：无 padding，整块读
                    val all = ByteArray(rowBytes * h)
                    raf.readFully(all)
                    packed.put(all)
                } else {
                    // 慢路：逐行读 stride、只拷 rowBytes
                    val stride = strideBuffer?.takeIf { it.size >= strideBytes }
                        ?: ByteArray(strideBytes).also { strideBuffer = it }
                    val row = ByteArray(rowBytes)
                    for (y in 0 until h) {
                        raf.readFully(stride, 0, strideBytes)
                        System.arraycopy(stride, 0, row, 0, rowBytes)
                        packed.put(row)
                    }
                }
            }
            packed.rewind()
            bitmap.copyPixelsFromBuffer(packed)
            return true
        } catch (t: Throwable) {
            return false
        }
    }
}
