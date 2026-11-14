package org.onmyoji.oas.server;

import org.onmyoji.oas.server.third.Ln;
import org.onmyoji.oas.server.third.Workarounds;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.Locale;

/**
 * 入口。三种用法：
 *
 * 1) 常驻服务（app 用）：
 *      app_process /system/bin org.onmyoji.oas.server.Main --socket <name> [--pidfile /path/pid]
 *      --socket 的值原样交给 SocketServer.openServer（见那里的注释）：
 *        <name>      抽象命名空间名，缺省 org.onmyoji.oas.display；换名即可同机跑第二个实例
 *        /abs/path   同样按抽象名 bind —— Android 公开 API 只在抽象命名空间监听，
 *                    不落地文件、无 chmod（官方文档：name is created in the Linux abstract
 *                    namespace, instead of on the filesystem）
 *      注意 --cmd 只执行一条命令，不 bind socket。
 * 2) 自检（开发期一条命令验证建屏+取帧是否通）：
 *      app_process /system/bin org.onmyoji.oas.server.Main --selftest /path/outdir [w] [h] [dpi]
 * 3) 一次性抓帧（不用 socket，适合排查）
 *      app_process /system/bin org.onmyoji.oas.server.Main --once /path/out.raw [w] [h] [dpi] [waitMs]
 * 4) 一次性执行一条 socket 命令（不 bind socket、不常驻；给 --wait N 则轮询 kb status
 *    最多等 N ms，跑完（或超时告警）后打印结果退出）：
 *      app_process /system/bin org.onmyoji.oas.server.Main --cmd "kb run 3 3 3 -1 /path/report" --wait 20000
 */
public final class Main {

    public static void main(String[] args) {
        String socket = null;
        String pidfile = null;
        String selftestDir = null;
        String oncePath = null;
        String launchComponent = null;
        String cmdLine = null;   // --cmd <line>：不 bind socket，执行一条命令就退出
        boolean waitGiven = false;
        boolean wakeLock = false; // ADR-0001 ②：--wakelock on 才持锁（默认 off，先不改默认行为）
        int w = 1280;
        int h = 720;
        int dpi = 320;
        int waitMs = 3000;
        int grabs = 3;

        try {
            int i = 0;
            while (i < args.length) {
                String a = args[i];
                switch (a) {
                    case "--socket":
                        socket = args[++i];
                        break;
                    case "--pidfile":
                        pidfile = args[++i];
                        break;
                    case "--selftest":
                        selftestDir = args[++i];
                        break;
                    case "--once":
                        oncePath = args[++i];
                        break;
                    case "--launch":
                        launchComponent = args[++i];
                        break;
                    case "--wakelock":
                        wakeLock = "on".equalsIgnoreCase(args[++i]);
                        break;
                    case "--grabs":
                        grabs = Integer.parseInt(args[++i]);
                        break;
                    case "--size":
                        w = Integer.parseInt(args[++i]);
                        h = Integer.parseInt(args[++i]);
                        break;
                    case "--dpi":
                        dpi = Integer.parseInt(args[++i]);
                        break;
                    case "--wait":
                        waitGiven = true;
                        waitMs = Integer.parseInt(args[++i]);
                        break;
                    case "--cmd":
                        cmdLine = args[++i];
                        break;
                    default:
                        Ln.w("忽略未知参数: " + a);
                        break;
                }
                i++;
            }
        } catch (Throwable t) {
            System.err.println("参数解析失败: " + t);
            System.exit(2);
        }

        Ln.i("bootstrap: pid=" + android.os.Process.myPid()
                + " uid=" + android.os.Process.myUid()
                + " workarounds=" + (Workarounds.initError().isEmpty() ? "ok" : Workarounds.initError()));

        // ADR-0001 ①：daemon 必须留在 root 域。任何一条不满足就**拒绝启动**，不要静默降级。
        String envErr = checkEnv(pidfile);
        if (!envErr.isEmpty()) {
            System.err.println("BOOTSTRAP-REJECTED " + envErr);
            System.exit(3);
        }

        if (pidfile != null) {
            writePid(pidfile);
        }

        // ADR-0001 ②：执行期间持 PARTIAL_WAKE_LOCK。默认 off，只有显式 --wakelock on 才申请；
        // 申请失败只告警（bootLog），不能让 daemon 起不来。
        if (wakeLock) {
            String wlErr = DaemonWakeLock.acquire();
            bootLog(pidfile, "wakelock: " + (wlErr.isEmpty()
                    ? "持有 " + DaemonWakeLock.TAG
                    : "申请失败（继续跑）: " + wlErr));
        }

        try {
            if (cmdLine != null) {
                // 一次性执行一条 socket 命令：不 bind socket、不常驻（为了不做 APK 也能验 kb run）。
                // 后台线程（kb 段循环）挂在本进程上，--wait 用来给它留跑完的时间。
                SocketServer server = new SocketServer(socket == null ? "" : socket);
                String response = server.exec(cmdLine);
                boolean finished = !waitGiven || awaitIdle(server, waitMs);
                System.out.println(response);
                System.out.flush();
                if (!finished) {
                    // 报告可能还没写全：退出码仍算命令已执行，但在 stderr 明确提醒
                    System.err.println("--wait " + waitMs + "ms 超时：后台任务可能仍在跑"
                            + "（用 --cmd \"kb status\" 复查，或稍后读报告）");
                }
                System.exit(0);
            }
            if (selftestDir != null) {
                int code = selftest(selftestDir, w, h, dpi, waitMs, launchComponent, grabs);
                System.exit(code);
            }
            if (oncePath != null) {
                int code = once(oncePath, w, h, dpi, waitMs);
                System.exit(code);
            }
            if (socket == null) {
                System.err.println("必须指定 --socket 或 --selftest 或 --once 或 --cmd");
                System.exit(2);
            }
            SocketServer server = new SocketServer(socket);
            server.run();
        } catch (Throwable t) {
            Ln.e("main 异常", t);
            t.printStackTrace();
            System.exit(1);
        } finally {
            // 正常返回 / 异常退出都要放锁（System.exit 时 shutdown hook 也会兜一次）
            DaemonWakeLock.release();
        }
    }

    // ------------------------------------------------------------------ //
    // 启动自检（BG-17 / ADR-0001 ①）
    //
    // 为什么必须查：daemon 的价值全在「app_process 由 init 收养、不在 app 的
    // cgroup 里、oom_score_adj=-1000」——所以 Hans/cached-app freezer 冻不到它。
    // 哪天有人图省事从 app 里直接 fork（或忘了 setsid），它会照样跑起来、
    // socket 照样通，但会被静默冻住：心跳断、帧不动，而且**没有任何报错**。
    // 上一轮 C 段空洞 122s 就是这类静默失败的形态，所以宁可启动即失败。
    // ------------------------------------------------------------------ //

    /** 自检日志固定也落一份到 /data/local/tmp（root shell 可读，app 目录被卸载也还在）。 */
    private static final String TMP_LOG = "/data/local/tmp/oas-daemon.log";

    /** oom_score_adj 门槛：实测 root daemon 是 -1000；> -900 说明没拿到 root 域待遇。 */
    private static final int OOM_ADJ_MAX = -900;

    /**
     * 检查 + 记录；返回空串表示通过，否则返回人类可读的原因（调用方据此 exit 3）。
     */
    private static String checkEnv(String pidfile) {
        StringBuilder bad = new StringBuilder();
        int pid = android.os.Process.myPid();
        int uid = android.os.Process.myUid();
        bootLog(pidfile, "envcheck: start pid=" + pid + " uid=" + uid);

        // ① 父进程不能在 app 域里。
        //    判定用 **父进程的 cgroup memory 行是否含 /apps/**，而不是 cmdline 里有没有包名：
        //    常驻 su shell（SuShell.kt）与 `su -c "CLASSPATH=/data/user/0/org.onmyoji.oas.android/...
        //    app_process ..."` 的命令行都会**合法地**包含 app 私有路径，用子串匹配会把生产
        //    路径直接误杀。cgroup 才是「在不在 app 域」的精确语义。
        //    ppid==1 是正常形态（setsid 后由 init 收养），直接通过。
        int ppid = readPpid(pid);
        String ppidMemCgroup = ppid > 0 ? memoryCgroupOf("/proc/" + ppid + "/cgroup") : "";
        bootLog(pidfile, "envcheck: ppid=" + ppid + " parent_memory_cgroup=" + ppidMemCgroup);
        if (ppid != 1 && ppidMemCgroup.contains("/apps/")) {
            bad.append("ppid=").append(ppid)
                    .append(" 在 app 域里（memory cgroup=").append(ppidMemCgroup.trim())
                    .append("）→ 会跟着被 freezer 冻住");
        }

        // ② oom_score_adj 必须足够负。
        String oomRaw = readText("/proc/self/oom_score_adj").trim();
        int oom = parseIntOr(oomRaw, 1000);
        bootLog(pidfile, "envcheck: oom_score_adj=" + oomRaw);
        if (oom > OOM_ADJ_MAX) {
            appendSep(bad);
            bad.append("oom_score_adj=").append(oomRaw.isEmpty() ? "未知" : oomRaw)
                    .append("（要求 <= ").append(OOM_ADJ_MAX).append("）");
        }

        // ③ memory cgroup 不能在 /apps/ 下（那就是 app 的组，随 app 一起被冻）。
        String selfMem = memoryCgroupOf("/proc/self/cgroup");
        bootLog(pidfile, "envcheck: self_memory_cgroup=" + selfMem);
        if (selfMem.contains("/apps/")) {
            appendSep(bad);
            bad.append("自己的 memory cgroup 在 /apps/ 下（").append(selfMem).append("）");
        }

        if (bad.length() > 0) {
            bootLog(pidfile, "envcheck: FAIL " + bad);
            return bad.toString();
        }
        bootLog(pidfile, "envcheck: OK（root 域 / ppid=" + ppid + " / oom=" + oomRaw + "）");
        return "";
    }

    /** 取某个 cgroup 文件里的 memory 控制行（形如 `4:memory:/apps/uid_10331`）；空串=没找到。 */
    private static String memoryCgroupOf(String cgroupPath) {
        for (String line : readText(cgroupPath).split("\n")) {
            if (line.contains(":memory:")) {
                return line.trim();
            }
        }
        return "";
    }

    private static void appendSep(StringBuilder sb) {
        if (sb.length() > 0) {
            sb.append("；");
        }
    }

    /** 读 /proc/<pid>/status 的 PPid。 */
    private static int readPpid(int pid) {
        String status = readText("/proc/" + pid + "/status");
        for (String line : status.split("\n")) {
            if (line.startsWith("PPid:")) {
                return parseIntOr(line.substring(5).trim(), -1);
            }
        }
        return -1;
    }

    private static int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static String readText(String path) {
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(path)))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[1024];
            int n;
            while ((n = br.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------------ //

    /**
     * 轮询 `kb status` 等后台段循环结束（最多 waitMs）。
     *
     * 为什么不用固定 sleep：段循环的实际耗时随 --stop / 异常变化，定长等待要么白等、
     * 要么提前退出把半份报告当成品。返回 true = 已结束（或本来没在跑）；false = 超时。
     */
    private static boolean awaitIdle(SocketServer server, int waitMs) {
        long deadline = System.currentTimeMillis() + Math.max(0, waitMs);
        while (true) {
            String st = server.exec("kb status");
            if (st == null || !st.contains("\"running\":true")) {
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return false;
            }
        }
    }

    /** 带时间戳追加到两处日志：/data/local/tmp 与 app cache 的 server/daemon.log。 */
    private static void bootLog(String pidfile, String msg) {
        String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new java.util.Date());
        String line = ts + " [pid " + android.os.Process.myPid() + "] " + msg + "\n";
        Ln.i("[boot] " + msg);
        appendFile(new java.io.File(TMP_LOG), line);
        java.io.File appLog = appLogFile(pidfile);
        if (appLog != null) {
            appendFile(appLog, line);
        }
    }

    /** pidfile 在 `<filesDir>/oas/server/server.pid`，同目录就是 app 侧可读的日志位置。 */
    private static java.io.File appLogFile(String pidfile) {
        if (pidfile == null || pidfile.isEmpty()) {
            return null;
        }
        try {
            java.io.File parent = new java.io.File(pidfile).getParentFile();
            if (parent == null) {
                return null;
            }
            if (!parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            return new java.io.File(parent, "daemon.log");
        } catch (Throwable t) {
            return null;
        }
    }

    private static void appendFile(java.io.File f, String text) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f, true)) {
            out.write(text.getBytes("UTF-8"));
            out.flush();
        } catch (Throwable t) {
            // 日志写不了不能影响启动；tmp 那份失败时给 stderr 留个痕迹
            System.err.println("log 写入失败 " + f + ": " + t);
        }
    }

    private static void writePid(String pidfile) {
        try {
            File f = new File(pidfile);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try (PrintStream ps = new PrintStream(new FileOutputStream(f))) {
                ps.println(android.os.Process.myPid());
            }
        } catch (Throwable t) {
            Ln.w("写 pidfile 失败: " + t);
        }
    }

    /**
     * 自检：建屏 → 等一会儿 → 抓 3 帧 → 镜像主屏做对照 → 销毁。
     * 结果同时打到 logcat 和 outdir/selftest.json（无 UI 也能验证）。
     */
    private static int selftest(String dir, int w, int h, int dpi, int waitMs,
                                String launchComponent, int grabs) {
        StringBuilder sb = new StringBuilder();
        StringBuilder json = new StringBuilder();
        json.append("{\"ts\":").append(System.currentTimeMillis())
                .append(",\"pid\":").append(android.os.Process.myPid())
                .append(",\"uid\":").append(android.os.Process.myUid())
                .append(",\"workarounds\":\"").append(SocketServer.esc(Workarounds.initError()))
                .append("\",\"steps\":[");

        DisplayEngine engine = new DisplayEngine();
        int code = 0;
        try {
            new File(dir).mkdirs();
            String initErr = DisplayEngine.init();
            line(sb, "init: " + (initErr.isEmpty() ? "ok" : initErr));

            long t0 = System.currentTimeMillis();
            DisplayEngine.Entry e = engine.create("oas-selftest", w, h, dpi, SocketServer.DEFAULT_FLAGS);
            long createMs = System.currentTimeMillis() - t0;
            line(sb, "create: displayId=" + e.id + " in " + createMs + "ms");
            json.append("{\"step\":\"create\",\"displayId\":").append(e.id)
                    .append(",\"ms\":").append(createMs)
                    .append(",\"info\":").append(DisplayEngine.describe(e)).append("},");

            if (launchComponent != null) {
                // 在虚拟屏上拉起一个 Activity（我们是 root，直接 am --display 指过去）
                String pkg = launchComponent.split("/")[0];
                String cmd = "am force-stop " + pkg + " ; am start -W --display " + e.id
                        + " -n " + launchComponent;
                line(sb, "launch: " + cmd);
                String outText = exec(cmd);
                line(sb, "launch 输出: " + outText.trim().replace('\n', ' '));
                json.append("{\"step\":\"launch\",\"cmd\":\"").append(SocketServer.esc(cmd))
                        .append("\",\"out\":\"").append(SocketServer.esc(outText)).append("\"},");
            }

            line(sb, "等待 " + waitMs + "ms 让合成器出帧…");
            Thread.sleep(waitMs);

            for (int k = 0; k < grabs; k++) {
                String out = new File(dir, "selftest_" + k + ".raw").getAbsolutePath();
                DisplayEngine.Grab g = engine.grab(e.id, out);
                line(sb, "grab#" + k + ": " + SocketServer.grabJson(g));
                json.append("{\"step\":\"grab\",\"index\":").append(k)
                        .append(",\"grab\":").append(SocketServer.grabJson(g)).append("},");
                if (!g.ok) {
                    code = 3;
                }
                Thread.sleep(400);
            }

            // 兜底对照：镜像主屏（displayId=0）。它能出图而上面不能 → readback 没问题，是目标屏没内容。
            line(sb, "对照：mirror 主屏 0");
            try {
                DisplayEngine.Entry m = engine.mirror("oas-mirror-0", w, h, 0);
                Thread.sleep(1500);
                DisplayEngine.Grab g = engine.grab(m.id, new File(dir, "selftest_mirror_main.raw").getAbsolutePath());
                line(sb, "mirror-main grab: " + SocketServer.grabJson(g));
                json.append("{\"step\":\"mirror_main\",\"grab\":").append(SocketServer.grabJson(g)).append("},");
                engine.destroy(m.id);
            } catch (Throwable t) {
                line(sb, "mirror 主屏失败: " + t);
                json.append("{\"step\":\"mirror_main\",\"error\":\"").append(SocketServer.esc(String.valueOf(t))).append("\"},");
            }

            engine.destroy(e.id);
            line(sb, "destroy: ok");
        } catch (Throwable t) {
            line(sb, "失败: " + t);
            json.append("{\"step\":\"fatal\",\"error\":\"").append(SocketServer.esc(String.valueOf(t))).append("\"},");
            code = 1;
        } finally {
            engine.releaseAll();
        }

        if (json.charAt(json.length() - 1) == ',') {
            json.setLength(json.length() - 1);
        }
        json.append("],\"exitCode\":").append(code).append("}");
        String text = sb.toString();
        System.out.println(text);
        try {
            new File(dir).mkdirs();
            try (PrintStream ps = new PrintStream(new FileOutputStream(new File(dir, "selftest.json")))) {
                ps.println(json);
            }
            try (PrintStream ps = new PrintStream(new FileOutputStream(new File(dir, "selftest.txt")))) {
                ps.println(text);
            }
        } catch (Throwable t) {
            Ln.w("写 selftest 结果失败: " + t);
        }
        Ln.i("selftest 结束 exitCode=" + code);
        return code;
    }

    /** 一次性抓帧：建屏 → 等 → 抓 1 帧 → 销毁。 */
    private static int once(String outPath, int w, int h, int dpi, int waitMs) {
        DisplayEngine engine = new DisplayEngine();
        try {
            String initErr = DisplayEngine.init();
            if (!initErr.isEmpty()) {
                Ln.e(initErr);
            }
            DisplayEngine.Entry e = engine.create("oas-once", w, h, dpi, SocketServer.DEFAULT_FLAGS);
            Thread.sleep(waitMs);
            DisplayEngine.Grab g = engine.grab(e.id, outPath);
            System.out.println(SocketServer.grabJson(g));
            engine.destroy(e.id);
            return g.ok ? 0 : 3;
        } catch (Throwable t) {
            Ln.e("once 失败", t);
            return 1;
        } finally {
            engine.releaseAll();
        }
    }

    private static void line(StringBuilder sb, String s) {
        String msg = String.format(Locale.US, "[%tT] %s", System.currentTimeMillis(), s);
        sb.append(msg).append('\n');
        Ln.i(s);
    }

    /** 跑一条 shell 命令并返回合并后的输出（用来在虚拟屏上 am start）。 */
    private static String exec(String cmd) {
        try {
            Process p = new ProcessBuilder("/system/bin/sh", "-c", cmd)
                    .redirectErrorStream(true)
                    .start();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            p.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            return "exec 失败: " + t;
        }
    }
}