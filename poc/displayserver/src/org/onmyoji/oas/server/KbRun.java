package org.onmyoji.oas.server;

import org.onmyoji.oas.server.third.Ln;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * daemon 侧「保活三段」驱动器骨架（BG-19 / ADR-0001 ④）。
 *
 * 为什么必须搬进 daemon：app 侧驱动器一旦进后台就会被 Realme/ColorOS 的
 * `OplusHansManager` 冻住（HANDOFF §十一：线程卡在 do_freezer_trap，报告停在 A 段），
 * 所以阶段调度只能由不受冻的 daemon 来做，app 只当瘦客户端。
 *
 * 本步只做**骨架**：建线程 + 三段计时 + 兜底请假心跳 + 落 txt 报告。
 * A/B/C 的屏幕动作（回桌面、息屏、唤醒、定向点击）留到下一步实现。
 */
public final class KbRun {

    private final DisplayEngine mEngine;
    private final Heartbeat mHeartbeat;

    private Thread mThread;
    private volatile boolean mRunning;
    private volatile boolean mStopRequested;
    private volatile String mPhase = "";
    private volatile long mStartedAt;
    private volatile String mReportPath = "";
    private volatile String mError = "";
    private volatile int mDisplayId = -1;
    private volatile String mTxt = "";

    public KbRun(DisplayEngine engine, Heartbeat heartbeat) {
        this.mEngine = engine;
        this.mHeartbeat = heartbeat;
    }

    public boolean isRunning() {
        return mRunning;
    }

    /** 起一次三段验证。已在跑则返回 busy（不做第二次，避免两套计时互相踩）。 */
    public String run(final int aSec, final int bSec, final int cSec,
                      final int displayId, final String reportPath) {
        synchronized (this) {
            if (mRunning) {
                return "{\"ok\":false,\"err\":\"busy\"}";
            }
            mRunning = true;
            mStopRequested = false;
            mPhase = "prep";
            mError = "";
            mTxt = "";
            mDisplayId = displayId;
            mReportPath = reportPath == null ? "" : reportPath;
            mStartedAt = System.currentTimeMillis();
            mThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop(aSec, bSec, cSec, displayId, mReportPath);
                }
            }, "oas-kb");
            mThread.setDaemon(true);
            mThread.start();
        }
        Ln.i("kb run: a=" + aSec + "s b=" + bSec + "s c=" + cSec + "s display=" + displayId
                + " report=" + reportPath);
        return "{\"ok\":true,\"a\":" + aSec + ",\"b\":" + bSec + ",\"c\":" + cSec
                + ",\"displayId\":" + displayId + ",\"report\":\"" + SocketServer.esc(reportPath) + "\"}";
    }

    /** 请求停止（当前段会提前结束），返回一行 JSON。 */
    public String stop() {
        synchronized (this) {
            if (!mRunning) {
                return "{\"ok\":true,\"wasRunning\":false}";
            }
            mStopRequested = true;
        }
        Ln.i("kb stop requested (phase=" + mPhase + ")");
        return "{\"ok\":true,\"wasRunning\":true}";
    }

    public String statusJson() {
        return String.format(Locale.US,
                "{\"ok\":true,\"running\":%b,\"phase\":\"%s\",\"displayId\":%d,"
                        + "\"elapsedMs\":%d,\"report\":\"%s\",\"error\":\"%s\",\"txt\":\"%s\"}",
                mRunning, SocketServer.esc(mPhase), mDisplayId,
                mRunning ? (System.currentTimeMillis() - mStartedAt) : 0L,
                SocketServer.esc(mReportPath), SocketServer.esc(mError), SocketServer.esc(mTxt));
    }

    // ------------------------------------------------------------------ //

    private void loop(int aSec, int bSec, int cSec, int displayId, String reportPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("KbRun（daemon 侧三段）\n");
        sb.append("displayId=").append(displayId)
                .append(" a=").append(aSec).append("s b=").append(bSec)
                .append("s c=").append(cSec).append("s\n");
        try {
            if (mHeartbeat != null) {
                mHeartbeat.start(1000, displayId, reportPath + ".jsonl", new Heartbeat.FramesProvider() {
                    @Override
                    public long framesOf(int id) {
                        return mEngine == null ? -1 : mEngine.framesOf(id);
                    }
                });
            }
            sb.append(phaseLine("A", aSec));
            writeReport(reportPath, sb);
            sb.append(phaseLine("B", bSec));
            writeReport(reportPath, sb);
            sb.append(phaseLine("C", cSec));
            writeReport(reportPath, sb);
            mTxt = "done";
        } catch (Throwable t) {
            mError = String.valueOf(t);
            sb.append("fatal: ").append(t).append('\n');
            Ln.e("kb run 失败", t);
        } finally {
            if (mHeartbeat != null) {
                mHeartbeat.stop();
            }
            sb.append("status=").append(mStopRequested ? "stopped" : (mError.isEmpty() ? "done" : "error"))
                    .append('\n');
            writeReport(reportPath, sb);
            mPhase = "idle";
            mRunning = false;
        }
    }

    /** 跑一段并返回一行 `A start=… end=… dur=…ms`（本步只计时，不做屏幕动作）。 */
    private String phaseLine(String name, int seconds) {
        mPhase = name;
        long start = System.currentTimeMillis();
        long deadline = start + Math.max(0, seconds) * 1000L;
        while (System.currentTimeMillis() < deadline && !mStopRequested) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                break;
            }
        }
        long end = System.currentTimeMillis();
        Ln.i("kb phase " + name + " done in " + (end - start) + "ms");
        return String.format(Locale.US, "%s start=%d end=%d dur=%dms%n",
                name, start, end, end - start);
    }

    private void writeReport(String reportPath, StringBuilder sb) {
        if (reportPath == null || reportPath.isEmpty()) {
            return;
        }
        try {
            File f = new File(reportPath);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try (FileOutputStream out = new FileOutputStream(f, false)) {
                out.write(sb.toString().getBytes("UTF-8"));
                out.flush();
            }
        } catch (Throwable t) {
            Ln.w("kb 写报告失败: " + t);
        }
    }
}