package org.onmyoji.oas.android.preview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.SurfaceHolder
import java.io.File

/**
 * BG-35 C-2 · 片②：预览渲染线程（**不含 UI 开关**，开关/释放由片③接）。
 *
 * 三条硬要求（主 AI 口径）：
 * 1. 独立 [HandlerThread] 抓帧+解码+贴图，**不占主线程**；
 * 2. [Bitmap] **只创建一次**反复复用，显示缩放靠 `drawBitmap(src, srcRect, dstRect, paint)` 的
 *    dst 矩形完成 —— **每帧不调 `createScaledBitmap`**（那会每帧新建一张 Bitmap，GC 抖动直接压主屏）；
 * 3. `lockCanvas()` 返回 null（surface 未就绪/已销毁）时**丢弃本帧**，不空指针、不阻塞线程。
 *
 * 节奏：默认 [FPS] 拍/秒（可调常量，主 AI 口径 5~6）；只取最新帧、不排队 —— 每拍完成后再排下一拍，
 * 单拍超时就自然降频，绝不堆积任务。抓帧走片① 的 [PreviewFrameSource]（daemon `grab`，不动 jar）。
 */
class PreviewRenderer(private val previewDir: File) : SurfaceHolder.Callback {

    companion object {
        /** 预览帧率；5~6 是主 AI 给的口径，调这里即可，不要散落魔数。 */
        const val FPS = 5

        /** 捕获侧固定尺寸（画布硬约束 1280×720，不改分辨率/dpi）。 */
        const val CAPTURE_W = 1280
        const val CAPTURE_H = 720
    }

    /** 一拍的统计，供片③/④ 显示与自测记录。回调在主线程。 */
    data class Stat(
        val running: Boolean,
        val grabs: Long,
        val drops: Long,
        val lastGrabMs: Double,
        val daemonFrames: Long,
        val drawMs: Long,
        val note: String,
    )

    var onStat: ((Stat) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var holder: SurfaceHolder? = null

    @Volatile
    private var running = false

    /**
     * 代际：每次 `start()`/`stop()` 自增。每一拍的 Runnable 都绑定自己那一代的编号，
     * 收尾排下一拍前比对 —— 旧代 in-flight 的一拍**不会再往新 handler 上挂任务**，
     * 否则 stop→start 会形成两条 tick 链（帧率翻倍、IO 翻倍）。
     */
    @Volatile
    private var generation = 0
    private var bitmap: Bitmap? = null
    /** 预览临时目录（**唯一来源**）：调用方传进来的就是预览目录本身，这里绝不再拼一层 "preview"。 */
    val frameDir: File = previewDir
    private var grabs = 0L
    private var drops = 0L
    private var lastLatchAt = 0L
    private var lastGrabMs = -1.0
    private var daemonFrames = -1L
    private var drawMs = 0L
    private var note = "未开始"

    fun bind(holder: SurfaceHolder) {
        // 先摘再挂：重复 bind（反复开关预览）不会累积回调
        this.holder?.let { runCatching { it.removeCallback(this) } }
        this.holder = holder
        holder.removeCallback(this)
        holder.addCallback(this)
    }

    fun isRunning(): Boolean = running

    /** 手动开：起渲染线程并开始抓帧（默认关，只有用户点开才走这里）。 */
    fun start() {
        if (running) return
        running = true
        generation++
        val gen = generation
        lastLatchAt = System.currentTimeMillis()
        val t = HandlerThread("oas-preview").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        handler?.post(newTick(gen))
        publish("抓帧中")
    }

    /** 停：不再排下一拍、等本拍收尾后退出线程；**不销毁虚拟屏**（那属于退出预览的整体释放）。 */
    fun stop() {
        if (!running && thread == null) return
        running = false
        generation++
        handler?.removeCallbacksAndMessages(null)
        // 先把字段置空、再用局部变量收尾：stop() 可能跑 4.5s，期间用户「关了马上再开」会在
        // 主线程建新 HandlerThread 并写进 thread/handler —— 旧代绝不能在 join 回来后把它们清掉，
        // 否则新 tick 链会在 scheduleNext() 的 `handler ?: return` 处默默断掉（开关开着却不刷新）。
        val t = thread
        thread = null
        handler = null
        t?.quitSafely()
        // 等 in-flight 那一拍真正收尾再返回：否则调用方紧接着 wipe 目录，而 daemon 会在
        // grab 收尾时把 frame.raw 写回来 —— 「退出清不掉 raw」会偶发重现。
        // 上限必须 **大于** 抓帧超时（PreviewFrameSource.grabTo 用 4000ms），留 500ms 余量。
        // ⚠ 本方法会阻塞调用线程最长 4500ms → 调用方必须放在 IO 线程，不许在主线程调。
        try {
            t?.join(4500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        publish("已停止")
    }

    /** 每一代一个 Runnable：旧代 in-flight 的一拍收尾时 gen != generation → 不再往新 handler 排任务。 */
    private fun newTick(gen: Int): Runnable = object : Runnable {
        override fun run() {
            if (gen != generation || !running) return
            tickOnce(gen)
        }
    }

    /** 一拍本体；[gen] 是这一拍所属的代际。 */
    private fun tickOnce(gen: Int) {
        val started = System.currentTimeMillis()
        val h = holder
        if (h == null || !h.surface.isValid) {
            // surface 还没准备好：不抓帧（省 IO），下一拍再看
            note = "surface 未就绪"
            drops++
            publishLater(started, gen)
            return
        }
        val bmp = ensureBitmap() ?: run {
            note = "Bitmap 不可用"
            publishLater(started, gen)
            return
        }
        val frame = PreviewFrameSource.grabTo(frameDir)
        if (frame == null) {
            note = "取帧失败（daemon/屏不在或超时）"
            drops++
            publishLater(started, gen)
            return
        }
        grabs++
        lastGrabMs = frame.ms
        daemonFrames = frame.frames
        val ok = PreviewFrameSource.decodeInto(
            File(frame.path), bmp, frame.width, frame.height, frame.rowStride,
        )
        if (!ok) {
            note = "解码失败（raw 不完整/尺寸不符），丢帧"
            drops++
            publishLater(started, gen)
            return
        }
        val drawStart = System.currentTimeMillis()
        val canvas: Canvas? = try {
            h.lockCanvas()
        } catch (t: Throwable) {
            null
        }
        if (canvas == null) {
            // 口径②：surface 已销毁/被占用 → 丢帧，绝不空指针、绝不卡线程
            note = "lockCanvas 返回 null，丢帧"
            drops++
            publishLater(started, gen)
            return
        }
        try {
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bmp, null, dstRect(canvas.width, canvas.height), paint)
        } finally {
            try {
                h.unlockCanvasAndPost(canvas)
            } catch (t: Throwable) {
                // surface 在这期间没了：忽略，下一拍照常
            }
        }
        drawMs = System.currentTimeMillis() - drawStart
        note = "ok"
        publishLater(started, gen)
    }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isFilterBitmap = true; isAntiAlias = true }

    /** 等比居中铺满控件，返回复用的 dst 矩形（避免每帧 new Rect）。 */
    private val dst = Rect()
    private fun dstRect(vw: Int, vh: Int): Rect {
        if (vw <= 0 || vh <= 0) {
            dst.set(0, 0, CAPTURE_W, CAPTURE_H)
            return dst
        }
        val scale = minOf(vw.toFloat() / CAPTURE_W, vh.toFloat() / CAPTURE_H)
        val w = (CAPTURE_W * scale).toInt()
        val h = (CAPTURE_H * scale).toInt()
        val l = (vw - w) / 2
        val t = (vh - h) / 2
        dst.set(l, t, l + w, t + h)
        return dst
    }

    private fun ensureBitmap(): Bitmap? {
        bitmap?.let { return it }
        return try {
            // 整个预览生命周期只建这一张，之后每帧 decodeInto 覆盖它的像素
            Bitmap.createBitmap(CAPTURE_W, CAPTURE_H, Bitmap.Config.ARGB_8888).also { bitmap = it }
        } catch (t: Throwable) {
            null
        }
    }

    /** 排下一拍：**只给同代续排**（旧代 in-flight 的一拍到此为止），不排队、不补帧。 */
    private fun scheduleNext(gen: Int) {
        if (!running || gen != generation) return
        val next = handler ?: return
        next.postDelayed(newTick(gen), 1000L / FPS)
    }

    private fun publishLater(started: Long, gen: Int) {
        publish(note)
        scheduleNext(gen)
        lastLatchAt = started
    }

    private fun publish(msg: String) {
        val stat = Stat(running, grabs, drops, lastGrabMs, daemonFrames, drawMs, msg)
        main.post { onStat?.invoke(stat) }
    }

    override fun surfaceCreated(h: SurfaceHolder) {
        // surface 每次重建都是新的：Bitmap 与缓冲区留着，下一拍直接往新 surface 上贴
        note = if (running) "surface 就绪" else "surface 就绪（预览未开）"
        publish(note)
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
        publish("surface ${width}x$height")
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        // 只标记：渲染线程下一拍会因 !surface.isValid 自动丢帧，不会碰已销毁的 surface
        note = "surface 已销毁"
        publish(note)
    }
}