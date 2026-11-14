package org.onmyoji.oas.server;

import org.onmyoji.oas.server.third.Ln;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * daemon 侧「保活三段」驱动器骨架（BG-19 / ADR-0001 ④）。
 *
 * 为什么必须搬进 daemon：app 侧驱动器一旦进后台就会被 Realme/ColorOS 的
 * `OplusHansManager` 冻住（HANDOFF §十一：线程卡在 do_freezer_trap，报告停在 A 段），
 * 所以阶段调度只能由不受冻的 daemon 来做，app 只当瘦客户端。
 *
 * 本步做骨架 + 判读：建线程 + 三段计时 + 兜底请假心跳 + 落 txt/json 报告；
 * `heartbeat.stop()` 之后按各段记录的起止 ts 切心跳 JSONL，逐段算
 * lines / maxGapMs / frames 首末差并给 pass 判词（BG-19 第三步）。
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
        List<Phase> phases = new ArrayList<>();
        try {
            if (mHeartbeat != null) {
                mHeartbeat.start(1000, displayId, reportPath + ".jsonl", new Heartbeat.FramesProvider() {
                    @Override
                    public long framesOf(int id) {
                        return mEngine == null ? -1 : mEngine.framesOf(id);
                    }
                });
            }
            Phase p;
            p = runPhase("A", aSec);
            phases.add(p);
            sb.append(p.line());
            writeText(reportPath, sb.toString());
            p = runPhase("B", bSec);
            phases.add(p);
            sb.append(p.line());
            writeText(reportPath, sb.toString());
            p = runPhase("C", cSec);
            phases.add(p);
            sb.append(p.line());
            writeText(reportPath, sb.toString());
            mTxt = "done";
        } catch (Throwable t) {
            mError = String.valueOf(t);
            sb.append("fatal: ").append(t).append('\n');
            Ln.e("kb run 失败", t);
        } finally {
            if (mHeartbeat != null) {
                mHeartbeat.stop();
            }
            // 心跳已落盘（Heartbeat.stop 里 join 过线程并关流），此刻按段窗口切分才准
            String json;
            try {
                PhaseStats[] stats = analyze(reportPath, displayId, phases);
                sb.append(statsTable(stats));
                json = statsJson(reportPath, displayId, stats);
                int pass = 0;
                for (PhaseStats s : stats) {
                    if (s.pass) {
                        pass++;
                    }
                }
                mTxt = pass + "/" + stats.length + " pass";
            } catch (Throwable t) {
                sb.append("analyze failed: ").append(t).append('\n');
                json = "{\"ok\":false,\"err\":\"" + SocketServer.esc(String.valueOf(t)) + "\"}";
                Ln.w("kb 判读失败: " + t);
            }
            sb.append("status=").append(mStopRequested ? "stopped" : (mError.isEmpty() ? "done" : "error"))
                    .append('\n');
            writeText(reportPath, sb.toString());
            writeText(reportPath + ".json", json);
            mPhase = "idle";
            mRunning = false;
        }
    }

    /** 跑一段并返回该段的起止记录（本步只计时，不做屏幕动作）。 */
    private Phase runPhase(String name, int seconds) {
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
        return new Phase(name, seconds, start, end);
    }

    /** 一段的起止（供 heartbeat.stop() 之后按 ts 窗口切分心跳）。 */
    private static final class Phase {
        final String name;
        final int seconds;
        final long startTs;
        final long endTs;

        Phase(String name, int seconds, long startTs, long endTs) {
            this.name = name;
            this.seconds = seconds;
            this.startTs = startTs;
            this.endTs = endTs;
        }

        String line() {
            return String.format(Locale.US, "%s start=%d end=%d dur=%dms%n",
                    name, startTs, endTs, endTs - startTs);
        }
    }

    // ---------------------------------------------- 判读（BG-19 第三步） //

    /** 单拍间隔超过这个值就算「被冻住 / 断流」（心跳 1s 一拍）。 */
    private static final long MAX_GAP_MS = 5000L;

    /** 一段（含 TOTAL 汇总行）的判读结果。 */
    private static final class PhaseStats {
        String name = "";
        int seconds;
        long startTs;
        long endTs;
        long lines;
        long maxGapMs;
        long framesFirst = -1;
        long framesLast = -1;
        long framesDelta = -1;
        /** 该段是否真的跑过；false = 未运行（skip），skip 一律不算 pass。 */
        boolean ran = true;
        boolean pass;

        PhaseStats() {
        }

        /** 没跑过的段：compare 时一律 fail，表格里打 -。 */
        static PhaseStats skipped(String name) {
            PhaseStats st = new PhaseStats();
            st.name = name;
            st.ran = false;
            st.pass = false;
            return st;
        }
    }

    /**
     * 按各段记录的起止 ts 切心跳 JSONL，算每段的 lines / maxGapMs / frames 首末差。
     * 产出 4 行：A、B、C + TOTAL（TOTAL 判词取前三段的逻辑与，避免被 0 秒段糊过去）。
     */
    private PhaseStats[] analyze(String reportPath, int displayId, List<Phase> phases) {
        List<long[]> samples = new ArrayList<>();   // {ts, frames}
        if (reportPath != null && !reportPath.isEmpty()) {
            readSamples(reportPath + ".jsonl", samples);
        }
        // 分母恒为 4（A / B / C / TOTAL）：下游门禁按 n/4 解析，不能随异常变形。
        // 没跑到的段显式 skip，skip 一律不算 pass（空 phases ⇒ 全 false）。
        PhaseStats[] out = new PhaseStats[4];
        long totalSec = 0;
        long totalStart = Long.MAX_VALUE;
        long totalEnd = Long.MIN_VALUE;
        boolean allPass = true;
        for (int i = 0; i < PHASE_NAMES.length; i++) {
            String name = PHASE_NAMES[i];
            Phase p = findPhase(phases, name);
            if (p == null) {
                out[i] = PhaseStats.skipped(name);
                allPass = false;
                continue;
            }
            PhaseStats st = window(name, p.seconds, p.startTs, p.endTs, samples);
            out[i] = st;
            allPass = allPass && st.pass;
            totalSec += p.seconds;
            totalStart = Math.min(totalStart, p.startTs);
            totalEnd = Math.max(totalEnd, p.endTs);
        }
        PhaseStats total;
        if (totalStart <= totalEnd) {
            total = window("TOTAL", (int) totalSec, totalStart, totalEnd, samples);
            total.pass = allPass;
        } else {
            total = PhaseStats.skipped("TOTAL");
        }
        out[3] = total;
        Ln.i("kb analyze: rows=" + out.length + " hbLines=" + samples.size() + " display=" + displayId);
        return out;
    }

    /** 段名固定为 A/B/C，按名字查（顺序由 out 数组保证，不依赖 phases 的追加顺序）。 */
    private static final String[] PHASE_NAMES = {"A", "B", "C"};

    private static Phase findPhase(List<Phase> phases, String name) {
        for (int i = 0; i < phases.size(); i++) {
            Phase p = phases.get(i);
            if (p != null && name.equals(p.name)) {
                return p;
            }
        }
        return null;
    }

    /** 读心跳 JSONL（只取 ts / frames；坏行跳过，不因一行脏数据废掉整份报告）。 */
    private static void readSamples(String jsonlPath, List<long[]> out) {
        File f = new File(jsonlPath);
        if (!f.isFile()) {
            Ln.w("kb 心跳文件不存在: " + jsonlPath);
            return;
        }
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                long ts = num(line, "ts");
                if (ts == Long.MIN_VALUE) {
                    continue;
                }
                long frames = num(line, "frames");
                out.add(new long[]{ts, frames == Long.MIN_VALUE ? -1L : frames});
            }
        } catch (Throwable t) {
            Ln.w("kb 读心跳失败: " + t);
        }
    }

    /** 从一行心跳 JSON 里取数字字段（不引 JSON 库，app_process 下不带 gson）。 */
    private static long num(String line, String key) {
        int i = line.indexOf("\"" + key + "\":");
        if (i < 0) {
            return Long.MIN_VALUE;
        }
        int n = line.length();
        int k = line.indexOf(':', i) + 1;
        while (k < n && (line.charAt(k) == ' ' || line.charAt(k) == '\t')) {
            k++;
        }
        int e = k;
        if (e < n && (line.charAt(e) == '-' || line.charAt(e) == '+')) {
            e++;
        }
        while (e < n && Character.isDigit(line.charAt(e))) {
            e++;
        }
        if (e == k) {
            return Long.MIN_VALUE;
        }
        try {
            return Long.parseLong(line.substring(k, e));
        } catch (Throwable t) {
            return Long.MIN_VALUE;
        }
    }

    /** 切一段窗口：[startTs, endTs] 内的心跳行。 */
    private static PhaseStats window(String name, int seconds, long startTs, long endTs,
                                    List<long[]> samples) {
        PhaseStats st = new PhaseStats();
        st.name = name;
        st.seconds = seconds;
        st.startTs = startTs;
        st.endTs = endTs;
        long prevTs = -1;
        // 边界衔接：拿窗口前最后一个心跳当起点，段首之前的空洞也要算进 maxGap
        // （否则「上一段刚断、这一段开头补上」会被漏判成好段）。
        for (int i = 0; i < samples.size(); i++) {
            long ts = samples.get(i)[0];
            if (ts < startTs) {
                prevTs = ts;
            } else {
                break;
            }
        }
        long firstFrames = Long.MIN_VALUE;
        for (int i = 0; i < samples.size(); i++) {
            long[] s = samples.get(i);
            long ts = s[0];
            if (ts < startTs || ts > endTs) {
                continue;
            }
            st.lines++;
            if (firstFrames == Long.MIN_VALUE) {
                firstFrames = s[1];
            }
            st.framesLast = s[1];
            if (prevTs >= 0) {
                long gap = ts - prevTs;
                if (gap > st.maxGapMs) {
                    st.maxGapMs = gap;
                }
            }
            prevTs = ts;
        }
        st.framesFirst = firstFrames == Long.MIN_VALUE ? -1L : firstFrames;
        st.framesDelta = (st.framesFirst >= 0 && st.framesLast >= 0)
                ? st.framesLast - st.framesFirst : -1L;
        // 判据：秒数必须 > 0（`kb run 0 5 5` 不许空过）、行数 ≥ max(1, 段秒数*0.8)，且单拍最大间隔 ≤ 5s
        st.pass = seconds > 0
                && st.lines >= Math.max(1L, needLines(seconds))
                && st.maxGapMs <= MAX_GAP_MS;
        return st;
    }

    private static long needLines(int seconds) {
        return Math.max(1L, (long) Math.ceil(seconds * 0.8));
    }

    /** txt 里的四段表格 + n/4。 */
    private static String statsTable(PhaseStats[] stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n判定：lines ≥ 段秒数*0.8 且 maxGap ≤ ").append(MAX_GAP_MS).append("ms\n");
        sb.append(String.format(Locale.US, "%-6s %5s %6s %6s %8s %20s  %s%n",
                "phase", "sec", "lines", "need", "maxGap", "frames(first->last)", "verdict"));
        int pass = 0;
        for (int i = 0; i < stats.length; i++) {
            PhaseStats s = stats[i];
            if (!s.ran) {
                sb.append(String.format(Locale.US, "%-6s %5s %6s %6s %8s %20s  %s%n",
                        s.name, "-", "-", "-", "-", "-", "skip（未运行）"));
                continue;
            }
            if (s.pass) {
                pass++;
            }
            String frames = s.framesDelta < 0
                    ? "n/a"
                    : (s.framesFirst + "->" + s.framesLast + "(+" + s.framesDelta + ")");
            sb.append(String.format(Locale.US, "%-6s %5d %6d %6d %8d %20s  %s%n",
                    s.name, s.seconds, s.lines, needLines(s.seconds), s.maxGapMs, frames,
                    s.pass ? "pass" : "FAIL"));
        }
        sb.append("n/4 = ").append(pass).append('/').append(stats.length)
                .append(pass == stats.length ? "（全过）" : "（有段未过）").append('\n');
        return sb.toString();
    }

    /** 机器可读的 reportPath.json。 */
    private static String statsJson(String reportPath, int displayId, PhaseStats[] stats) {
        int pass = 0;
        for (int i = 0; i < stats.length; i++) {
            if (stats[i].pass) {
                pass++;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"displayId\":").append(displayId)
                .append(",\"report\":\"").append(SocketServer.esc(reportPath)).append('"')
                .append(",\"heartbeat\":\"")
                .append(SocketServer.esc(reportPath == null || reportPath.isEmpty() ? "" : reportPath + ".jsonl"))
                .append('"')
                .append(",\"maxGapLimitMs\":").append(MAX_GAP_MS)
                .append(",\"passCount\":").append(pass)
                .append(",\"total\":").append(stats.length)
                .append(",\"verdict\":\"").append(pass == stats.length ? "pass" : "fail").append('"')
                .append(",\"phases\":[");
        for (int i = 0; i < stats.length; i++) {
            PhaseStats s = stats[i];
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.US,
                    "{\"name\":\"%s\",\"ran\":%b,\"seconds\":%d,\"startTs\":%d,\"endTs\":%d,\"durMs\":%d,"
                            + "\"lines\":%d,\"expectLines\":%d,\"maxGapMs\":%d,"
                            + "\"framesFirst\":%d,\"framesLast\":%d,\"framesDelta\":%d,\"pass\":%b}",
                    s.name, s.ran, s.seconds, s.startTs, s.endTs, s.endTs - s.startTs,
                    s.lines, needLines(s.seconds), s.maxGapMs,
                    s.framesFirst, s.framesLast, s.framesDelta, s.pass));
        }
        return sb.append("]}").toString();
    }

    /** 覆盖写文本文件（父目录不存在则建）。 */
    private void writeText(String path, String content) {
        if (path == null || path.isEmpty()) {
            return;
        }
        try {
            File f = new File(path);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try (FileOutputStream out = new FileOutputStream(f, false)) {
                out.write(content.getBytes("UTF-8"));
                out.flush();
            }
        } catch (Throwable t) {
            Ln.w("kb 写报告失败: " + t);
        }
    }
}