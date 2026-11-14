package org.onmyoji.oas.server;

import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Display;
import android.view.Surface;

import org.onmyoji.oas.server.third.FakeContext;
import org.onmyoji.oas.server.third.Ln;
import org.onmyoji.oas.server.third.Workarounds;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 虚拟屏的建立 / 取帧 / 销毁。
 *
 * 设计要点（都是踩坑换来的）：
 * 1. 用公开 API {@code DisplayManager.createVirtualDisplay(name,w,h,dpi,surface,flags)}，
 *    只用反射去 new 那个 hidden 构造函数 —— 不自己玩 IDisplayManager binder。
 * 2. 屏幕内容由 ImageReader(RGBA_8888) 接收，我们是「这块屏的输出端」，取帧不用隐藏截图 API。
 * 3. ImageReader 必须被持续消费，否则 BufferQueue 塞满会让 SurfaceFlinger 阻塞；
 *    所以在 HandlerThread 里 acquireLatestImage 后立刻 close，只把像素拷进最新缓冲。
 * 4. 静态画面时 SurfaceFlinger 不再出帧，所以「最新缓冲」要一直留着 —— grab 读的是它。
 */
public final class DisplayEngine {

    /** 拷贝节流：最多 ~15fps 做一次 3.7MB 的 memcpy，静态画面靠缓存帧兜住。 */
    private static final long COPY_INTERVAL_MS = 60;

    private static DisplayManager sDisplayManager;
    private static String sInitError = "";

    private final Map<Integer, Entry> mEntries = new LinkedHashMap<>();
    private int mSeq = 0;

    public static synchronized String init() {
        if (sDisplayManager != null) {
            return "";
        }
        try {
            Workarounds.apply();
            Class<?> dmClass = Class.forName("android.hardware.display.DisplayManager");
            Constructor<?> ctor = dmClass.getDeclaredConstructor(android.content.Context.class);
            ctor.setAccessible(true);
            sDisplayManager = (DisplayManager) ctor.newInstance(FakeContext.get());
            Ln.i("DisplayManager 初始化成功");
        } catch (Throwable t) {
            sInitError = "DisplayManager 初始化失败: " + t;
            Ln.e(sInitError, t);
        }
        return sInitError;
    }

    public static String initError() {
        return sInitError;
    }

    public static final class Entry {
        public int id;
        public String name;
        public int width;
        public int height;
        public int dpi;
        public int flags;
        public int srcDisplayId = -1;
        public VirtualDisplay virtualDisplay;
        public ImageReader reader;
        public Surface surface;
        public HandlerThread thread;
        public byte[] latest;          // w*h*4，RGBA 顺序
        public boolean hasFrame = false;
        public long frames = 0;
        public long copies = 0;
        public long lastCopyAt = 0;
        public int lastRowStride = 0;
        public int lastPixelStride = 0;
        public String error = "";
        public final long createdAt = System.currentTimeMillis();
    }

    /** 建一块 PUBLIC|OWN_CONTENT_ONLY 的屏（默认 1280x720@320）。 */
    public synchronized Entry create(String name, int width, int height, int dpi, int flags) throws Exception {
        ensureReady();
        ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        Surface surface = reader.getSurface();
        VirtualDisplay vd;
        try {
            // API 34 起推荐走 VirtualDisplayConfig；旧的 5 参 mirror 重载在 API 35 已被移除，
            // 所以两种建屏（新建 / 镜像）统一用 config 表达，少一套分支。
            vd = sDisplayManager.createVirtualDisplay(
                    buildConfig(name, width, height, dpi, surface, flags, -1));
        } catch (Throwable t) {
            reader.close();
            throw new IOException("createVirtualDisplay 失败: " + t, t);
        }
        Entry entry = register(vd, reader, surface, name, width, height, dpi, flags, -1);
        Ln.i(String.format(Locale.US, "create %s %dx%d@%d flags=%d -> displayId=%d",
                name, width, height, dpi, flags, entry.id));
        return entry;
    }

    /**
     * 兜底通道：把 srcDisplayId 镜像到我们自己的 surface 上（scrcpy 同款 5 参 API）。
     * 用途是归因 —— 若它拿得到图而主通道拿不到，说明是目标屏本身没内容或渲染在别处。
     */
    public synchronized Entry mirror(String name, int width, int height, int srcDisplayId) throws Exception {
        ensureReady();
        ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        Surface surface = reader.getSurface();
        VirtualDisplay vd;
        try {
            // PUBLIC 会被 DMS 自动补上 AUTO_MIRROR（root 有 CAPTURE_VIDEO_OUTPUT，能过检查），
            // 再指定 displayIdToMirror 就得到「把某块屏镜像到我们 surface 上」的效果。
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
            vd = sDisplayManager.createVirtualDisplay(
                    buildConfig(name, width, height, width > height ? 320 : 320, surface, flags, srcDisplayId));
        } catch (Throwable t) {
            reader.close();
            throw new IOException("mirror createVirtualDisplay 失败: " + t, t);
        }
        int dpi = 320;
        Entry entry = register(vd, reader, surface, name, width, height, dpi, 0, srcDisplayId);
        Ln.i(String.format(Locale.US, "mirror src=%d %dx%d -> displayId=%d",
                srcDisplayId, width, height, entry.id));
        return entry;
    }

    /** 组装 VirtualDisplayConfig；src >= 0 表示镜像那块屏。 */
    private static VirtualDisplayConfig buildConfig(String name, int w, int h, int dpi,
                                                    Surface surface, int flags, int src) {
        VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(name, w, h, dpi);
        builder.setSurface(surface);
        builder.setFlags(flags);
        if (src >= 0) {
            // setDisplayIdToMirror 是 @hide（android.jar 里没有），只能反射。
            // app_process 不受 hidden API 名单限制，所以这里能调到。
            try {
                java.lang.reflect.Method m = builder.getClass()
                        .getMethod("setDisplayIdToMirror", int.class);
                m.invoke(builder, src);
            } catch (Throwable t) {
                throw new IllegalStateException(
                        "本机 VirtualDisplayConfig.Builder 不支持 setDisplayIdToMirror: " + t, t);
            }
        }
        return builder.build();
    }

    private Entry register(VirtualDisplay vd, ImageReader reader, Surface surface,
                           String name, int width, int height, int dpi, int flags, int srcId) {
        Entry entry = new Entry();
        entry.virtualDisplay = vd;
        entry.reader = reader;
        entry.surface = surface;
        entry.name = name;
        entry.width = width;
        entry.height = height;
        entry.dpi = dpi;
        entry.flags = flags;
        entry.srcDisplayId = srcId;
        entry.latest = new byte[width * height * 4];
        try {
            entry.id = vd.getDisplay().getDisplayId();
        } catch (Throwable t) {
            entry.id = -1;
            entry.error = "拿不到 displayId: " + t;
        }
        final Entry ref = entry;
        entry.thread = new HandlerThread("oas-grab-" + entry.id);
        entry.thread.start();
        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader r) {
                onFrame(ref, r);
            }
        }, new Handler(entry.thread.getLooper()));
        mEntries.put(entry.id, entry);
        return entry;
    }

    private void onFrame(Entry entry, ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }
            entry.frames++;
            long now = SystemClock.uptimeMillis();
            if (now - entry.lastCopyAt >= COPY_INTERVAL_MS) {
                copy(image, entry);
                entry.lastCopyAt = now;
                entry.copies++;
            }
        } catch (Throwable t) {
            entry.error = "取帧异常: " + t;
            Ln.e("onFrame", t);
        } finally {
            if (image != null) {
                try {
                    image.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 把 Image 拷进 entry.latest，处理 rowStride 对齐。 */
    private void copy(Image image, Entry entry) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        entry.lastRowStride = rowStride;
        entry.lastPixelStride = pixelStride;

        int w = entry.width;
        int h = entry.height;
        int rowBytes = w * 4;
        byte[] dst = entry.latest;

        if (pixelStride == 4) {
            for (int y = 0; y < h; y++) {
                int base = y * rowStride;
                if (base + rowBytes > buffer.limit()) {
                    break;
                }
                buffer.position(base);
                buffer.get(dst, y * rowBytes, rowBytes);
            }
        } else {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int si = y * rowStride + x * pixelStride;
                    int di = y * rowBytes + x * 4;
                    if (si + 3 >= buffer.limit() || di + 3 >= dst.length) {
                        break;
                    }
                    dst[di] = buffer.get(si);
                    dst[di + 1] = buffer.get(si + 1);
                    dst[di + 2] = buffer.get(si + 2);
                    dst[di + 3] = buffer.get(si + 3);
                }
            }
        }
        entry.hasFrame = true;
    }

    /** 抓帧结果。 */
    public static final class Grab {
        public boolean ok;
        public String error = "";
        public String path = "";
        public long bytes;
        public double ms;
        public double mean;
        public double blackRatio;
        public long frames;
        public long copies;
        public int width;
        public int height;
        public int rowStride;
        public int pixelStride;
        public int rotation;
    }

    public synchronized Grab grab(int displayId, String outPath) {
        Grab g = new Grab();
        Entry entry = mEntries.get(displayId);
        if (entry == null) {
            g.error = "displayId " + displayId + " 不存在";
            return g;
        }
        long start = SystemClock.uptimeMillis();
        g.width = entry.width;
        g.height = entry.height;
        g.frames = entry.frames;
        g.copies = entry.copies;
        g.rowStride = entry.lastRowStride;
        g.pixelStride = entry.lastPixelStride;
        try {
            g.rotation = entry.virtualDisplay.getDisplay().getRotation();
        } catch (Throwable ignored) {
        }
        if (!entry.hasFrame) {
            g.error = "还没有任何帧（frames=" + entry.frames + "）";
            return g;
        }
        // 统计（每 2 个像素采样一次，够用且快）
        byte[] pix = entry.latest;
        long sum = 0;
        long black = 0;
        long sampled = 0;
        for (int i = 0; i < pix.length; i += 8) {
            int r = pix[i] & 0xff;
            int gg = pix[i + 1] & 0xff;
            int b = pix[i + 2] & 0xff;
            sum += r + gg + b;
            int peak = Math.max(r, Math.max(gg, b));
            if (peak < 8) {
                black++;
            }
            sampled++;
        }
        g.mean = sampled == 0 ? 0 : (double) sum / (sampled * 3.0);
        g.blackRatio = sampled == 0 ? 0 : (double) black / sampled;

        File file = new File(outPath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(file), 1 << 16)) {
            // 与 screencap 兼容的 16 字节头：12 | width | height | format(1=RGBA_8888)
            writeIntLE(out, 12);
            writeIntLE(out, entry.width);
            writeIntLE(out, entry.height);
            writeIntLE(out, 1);
            out.write(pix, 0, pix.length);
        } catch (IOException e) {
            g.error = "写文件失败: " + e;
            return g;
        }
        g.ok = true;
        g.path = outPath;
        g.bytes = file.length();
        g.ms = SystemClock.uptimeMillis() - start;
        Ln.i(String.format(Locale.US, "grab id=%d %dx%d bytes=%d ms=%.1f mean=%.2f black=%.4f frames=%d",
                displayId, g.width, g.height, g.bytes, g.ms, g.mean, g.blackRatio, g.frames));
        return g;
    }

    private static void writeIntLE(BufferedOutputStream out, int v) throws IOException {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
        out.write((v >> 16) & 0xff);
        out.write((v >> 24) & 0xff);
    }

    public synchronized List<Entry> list() {
        return new ArrayList<>(mEntries.values());
    }

    /** 已采集的帧数（供 watch 用；daemon 侧采样，不受 app 被冻结影响）。 */
    public synchronized long framesOf(int displayId) {
        Entry entry = mEntries.get(displayId);
        return entry == null ? -1 : entry.frames;
    }
    public synchronized Entry get(int displayId) {
        return mEntries.get(displayId);
    }

    /**
     * BG-22：把这块虚拟屏的**输出端**摘掉（`setSurface(null)`），屏本身留着。
     *
     * 与 `destroy()` 的区别：这里不 close reader、不 release entry.surface，
     * 所以 {@link #attach(int)} 能把同一个 Surface 接回去继续出帧。
     * 用途：主屏熄屏时停渲，避免虚拟屏一直挂着让 system_server 为它持
     * SCREEN_BRIGHT 锁（BG-21 实测 `mHoldingDisplaySuspendBlocker` 恒 true）。
     * 代价：摘面期间 VirtualDisplay 没有输出端，画面会停（用户已知情接受）。
     */
    public synchronized boolean detach(int displayId) {
        Entry entry = mEntries.get(displayId);
        if (entry == null || entry.virtualDisplay == null) {
            return false;
        }
        try {
            entry.virtualDisplay.setSurface(null);
            Ln.i("detach displayId=" + displayId + "（停渲，reader/surface 保留）");
            return true;
        } catch (Throwable t) {
            Ln.w("detach displayId=" + displayId + " 失败: " + t);
            return false;
        }
    }

    /** BG-22：把 {@link #detach(int)} 摘掉的 Surface 接回去（复用同一个 ImageReader 的 Surface）。 */
    public synchronized boolean attach(int displayId) {
        Entry entry = mEntries.get(displayId);
        if (entry == null || entry.virtualDisplay == null || entry.surface == null) {
            return false;
        }
        try {
            entry.virtualDisplay.setSurface(entry.surface);
            Ln.i("attach displayId=" + displayId + "（恢复出帧）");
            return true;
        } catch (Throwable t) {
            Ln.w("attach displayId=" + displayId + " 失败: " + t);
            return false;
        }
    }

    public synchronized boolean destroy(int displayId) {

        Entry entry = mEntries.remove(displayId);
        if (entry == null) {
            return false;
        }
        release(entry);
        Ln.i("destroy displayId=" + displayId);
        return true;
    }

    public synchronized void releaseAll() {
        for (Entry entry : new ArrayList<>(mEntries.values())) {
            release(entry);
        }
        mEntries.clear();
    }

    private void release(Entry entry) {
        try {
            if (entry.reader != null) {
                entry.reader.setOnImageAvailableListener(null, null);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (entry.virtualDisplay != null) {
                entry.virtualDisplay.release();
            }
        } catch (Throwable t) {
            Ln.w("release virtualDisplay: " + t);
        }
        try {
            if (entry.reader != null) {
                entry.reader.close();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (entry.surface != null) {
                entry.surface.release();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (entry.thread != null) {
                entry.thread.quitSafely();
            }
        } catch (Throwable ignored) {
        }
    }

    private void ensureReady() throws IOException {
        if (sDisplayManager != null) {
            return;
        }
        String err = init();
        if (sDisplayManager == null) {
            throw new IOException(err.isEmpty() ? "DisplayManager 不可用" : err);
        }
    }

    /** 供 selftest / list 使用的一行描述。 */
    public static String describe(Entry e) {
        return String.format(Locale.US,
                "{\"displayId\":%d,\"name\":\"%s\",\"w\":%d,\"h\":%d,\"dpi\":%d,\"flags\":%d,"
                        + "\"src\":%d,\"frames\":%d,\"copies\":%d,\"hasFrame\":%s,\"rowStride\":%d,"
                        + "\"pixelStride\":%d,\"error\":\"%s\"}",
                e.id, e.name, e.width, e.height, e.dpi, e.flags, e.srcDisplayId,
                e.frames, e.copies, e.hasFrame, e.lastRowStride, e.lastPixelStride,
                e.error == null ? "" : e.error.replace("\"", "'"));
    }

    public static int displayRotation(Entry e) {
        try {
            return e.virtualDisplay.getDisplay().getRotation();
        } catch (Throwable t) {
            return -1;
        }
    }

    static {
        // 触发一次类加载，让 Workarounds 的静态块尽早失败（失败信息更好定位）
        try {
            Class.forName(Display.class.getName());
        } catch (Throwable ignored) {
        }
    }
}