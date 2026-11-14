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
 *      app_process /system/bin org.onmyoji.oas.server.Main --socket /path/display.sock [--pidfile /path/pid]
 * 2) 自检（开发期一条命令验证建屏+取帧是否通）：
 *      app_process /system/bin org.onmyoji.oas.server.Main --selftest /path/outdir [w] [h] [dpi]
 * 3) 一次性抓帧（不用 socket，适合排查）
 *      app_process /system/bin org.onmyoji.oas.server.Main --once /path/out.raw [w] [h] [dpi] [waitMs]
 */
public final class Main {

    public static void main(String[] args) {
        String socket = null;
        String pidfile = null;
        String selftestDir = null;
        String oncePath = null;
        String launchComponent = null;
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
                        waitMs = Integer.parseInt(args[++i]);
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
        if (pidfile != null) {
            writePid(pidfile);
        }

        try {
            if (selftestDir != null) {
                int code = selftest(selftestDir, w, h, dpi, waitMs, launchComponent, grabs);
                System.exit(code);
            }
            if (oncePath != null) {
                int code = once(oncePath, w, h, dpi, waitMs);
                System.exit(code);
            }
            if (socket == null) {
                System.err.println("必须指定 --socket 或 --selftest 或 --once");
                System.exit(2);
            }
            SocketServer server = new SocketServer(socket);
            server.run();
        } catch (Throwable t) {
            Ln.e("main 异常", t);
            t.printStackTrace();
            System.exit(1);
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