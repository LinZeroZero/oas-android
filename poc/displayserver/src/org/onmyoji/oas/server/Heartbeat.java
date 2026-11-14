package org.onmyoji.oas.server;

import org.onmyoji.oas.server.third.Ln;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * daemon 侧心跳写入器（**纯 Java，不依赖 Python**）。
 *
 * 为什么要在 daemon 里做：app 进程会被 ColorOS(Hans) 的 cached-app freezer 冻住，
 * 这时 app 自己的线程根本写不出带时间戳的痕迹 —— 上一轮 C 段（息屏）心跳空洞 122s
 * 就是这么来的。daemon（app_process，无 process record、cgroup freezer:/、oom_score_adj=-1000）
 * 不受影响，所以「熄屏时还活着」这件事必须在 daemon 侧留下证据。
 *
 * 输出格式：JSONL，一行一拍，字段固定：
 *   {"ts":<epochMs>,"displayId":<id>,"frames":<daemon 侧累计帧>,"pid":<daemon pid>}
 *
 * 每拍 flush + fsync：被 SIGKILL 也不丢已写入的行（报告里能看出断在第几秒）。
 */
public final class Heartbeat {

    /** 帧数来源（由 SocketServer 注入 DisplayEngine，避免 Heartbeat 直接依赖它）。 */
    public interface FramesProvider {
        long framesOf(int displayId);
    }

    private final Object mLock = new Object();

    private Thread mThread;
    private volatile boolean mRunning;
    private volatile FileOutputStream mOut;
    private volatile String mPath = "";
    private volatile int mDisplayId = -1;
    private volatile int mIntervalMs = 0;
    private volatile long mLines;
    private volatile long mMaxGapMs;
    private volatile long mLastTs;
    private volatile long mFirstTs;
    private volatile long mStartedAtMs;
    private volatile String mLastError = "";

    public boolean isRunning() {
        return mRunning;
    }

    /** 起心跳；返回一行 JSON（ok=false 表示文件不可写）。已在跑时返回 already=true。 */
    public String start(int intervalMs, int displayId, String path, final FramesProvider provider) {
        synchronized (mLock) {
            if (mRunning) {
                return "{\"ok\":true,\"already\":true," + statusFields() + "}";
            }
            final int interval = Math.max(200, intervalMs);
            try {
                File f = new File(path);
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                // 截断：这份心跳只属于本次验证，不继承上一轮的尾巴
                mOut = new FileOutputStream(f, false);
            } catch (Throwable t) {
                mLastError = "心跳文件不可写: " + t;
                Ln.e(mLastError, t);
                return statusJson();
            }
            mPath = path;
            mDisplayId = displayId;
            mIntervalMs = interval;
            mLines = 0;
            mMaxGapMs = 0;
            mLastTs = 0;
            mFirstTs = 0;
            mLastError = "";
            mStartedAtMs = System.currentTimeMillis();
            mRunning = true;
            mThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop(provider, interval, mDisplayId);
                }
            }, "oas-heartbeat");
            mThread.setDaemon(true);
            mThread.start();
            Ln.i("hb start: interval=" + interval + "ms display=" + mDisplayId + " path=" + path);
            return "{\"ok\":true," + statusFields() + "}";
        }
    }

    /** 停心跳并返回摘要（行数 / 最大间隔 / 首末时间戳）。 */
    public String stop() {
        synchronized (mLock) {
            boolean was = mRunning;
            mRunning = false;
            Thread t = mThread;
            mThread = null;
            if (t != null) {
                t.interrupt();
                try {
                    t.join(1500);
                } catch (Throwable ignored) {
                }
            }
            FileOutputStream out = mOut;
            mOut = null;
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
            Ln.i("hb stop: lines=" + mLines + " maxGap=" + mMaxGapMs + "ms");
            return "{\"ok\":true,\"wasRunning\":" + was + "," + statusFields() + "}";
        }
    }

    public String statusJson() {
        return "{\"ok\":" + mRunning + "," + statusFields() + "}";
    }

    private String statusFields() {
        return String.format(Locale.US,
                "\"running\":%b,\"path\":\"%s\",\"displayId\":%d,\"intervalMs\":%d,"
                        + "\"lines\":%d,\"maxGapMs\":%d,\"firstTs\":%d,\"lastTs\":%d,"
                        + "\"startedAt\":%d,\"lastError\":\"%s\",\"pid\":%d",
                mRunning, SocketServer.esc(mPath), mDisplayId, mIntervalMs,
                mLines, mMaxGapMs, mFirstTs, mLastTs,
                mStartedAtMs, SocketServer.esc(mLastError), android.os.Process.myPid());
    }

    private void loop(FramesProvider provider, int interval, int displayId) {
        long next = System.currentTimeMillis();
        while (mRunning) {
            long ts = System.currentTimeMillis();
            try {
                long frames = provider == null ? -1 : provider.framesOf(displayId);
                String line = String.format(Locale.US,
                        "{\"ts\":%d,\"displayId\":%d,\"frames\":%d,\"pid\":%d}\n",
                        ts, displayId, frames, android.os.Process.myPid());
                FileOutputStream out = mOut;
                if (out == null) {
                    throw new IllegalStateException("输出流已关闭");
                }
                out.write(line.getBytes("UTF-8"));
                out.flush();
                out.getFD().sync();
                if (mLastTs > 0) {
                    long gap = ts - mLastTs;
                    if (gap > mMaxGapMs) {
                        mMaxGapMs = gap;
                    }
                } else {
                    mFirstTs = ts;
                }
                mLastTs = ts;
                mLines++;
            } catch (Throwable t) {
                mLastError = "写心跳失败: " + t;
            }
            next += interval;
            long sleep = next - System.currentTimeMillis();
            if (sleep < 1) {
                // 落后了就重新对齐，不在补拍上烧 CPU
                next = System.currentTimeMillis() + interval;
                sleep = interval;
            }
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                break;
            }
        }
    }
}
