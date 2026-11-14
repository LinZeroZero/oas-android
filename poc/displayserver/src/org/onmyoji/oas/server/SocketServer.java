package org.onmyoji.oas.server;

import android.hardware.display.DisplayManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;

import org.onmyoji.oas.server.third.Ln;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileDescriptor;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * AF_UNIX 行协议服务端：一行一条命令，回一行 JSON。
 *
 * 为什么用「app 私有目录里的文件系统 socket」而不是 /data/local/tmp：
 * /data/local/tmp 是 0771 shell:shell，app 进程连门都进不去；
 * 而 filesDir 是 app 自己的目录，root 服务端能读写，app 也能连。
 */
public final class SocketServer {

    public static final int DEFAULT_FLAGS =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;

    private final String path;
    private final DisplayEngine engine = new DisplayEngine();
    private final Heartbeat heartbeat = new Heartbeat();
    private final KbRun kbRun = new KbRun(engine, heartbeat);
    private volatile boolean running = true;

    /** true = 文件系统 socket（可 chmod、可被 app 直接连）；false = 退到抽象命名空间。 */
    private static boolean sFileSocket = true;

    public static boolean usingFileSocket() {
        return sFileSocket;
    }

    public SocketServer(String path) {
        this.path = path;
    }

    public void run() throws Exception {
        File file = new File(path);
        if (file.exists() && !file.delete()) {
            Ln.w("旧 socket 删不掉: " + path);
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        LocalServerSocket server = openServer(path);
        if (sFileSocket) {
            chmod666(path);
        }
        Ln.i("socket ready: " + path + (sFileSocket ? "" : " (abstract fallback)"));
        System.out.println("READY " + path);
        System.out.flush();

        while (running) {
            LocalSocket client = null;
            try {
                client = server.accept();
                handle(client);
            } catch (Throwable t) {
                if (running) {
                    Ln.e("accept/handle 异常", t);
                }
            } finally {
                closeQuietly(client);
            }
        }
        try {
            server.close();
        } catch (Throwable ignored) {
        }
        engine.releaseAll();
        heartbeat.stop();
        DaemonWakeLock.release();
        // 退出时把 socket 文件删掉，不给下次留垃圾
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        Ln.i("socket server 退出");
    }

    private void handle(LocalSocket client) throws Exception {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            String response = dispatch(line);
            out.write(response);
            out.write("\n");
            out.flush();
            if (!running) {
                break;
            }
        }
    }

    private String dispatch(String line) {
        String[] a = line.trim().split("\\s+");
        if (a.length == 0 || a[0].isEmpty()) {
            return err("empty command");
        }
        try {
            switch (a[0]) {
                case "ping":
                    return String.format(Locale.US,
                            "{\"ok\":true,\"pid\":%d,\"ver\":\"2\",\"displays\":%d,\"hbRunning\":%b,\"initError\":\"%s\"}",
                            android.os.Process.myPid(), engine.list().size(), heartbeat.isRunning(),
                            esc(DisplayEngine.initError()));
                case "hb":
                    return cmdHeartbeat(a);
                case "kb":
                    return cmdKb(a);
                case "create":
                    return cmdCreate(a);
                case "mirror":
                    return cmdMirror(a);
                case "grab":
                    return cmdGrab(a);
                case "stats":
                    return cmdStats(a);
                case "watch":
                    return cmdWatch(a);
                case "list":
                    return cmdList();
                case "destroy":
                    return cmdDestroy(a);
                case "exit":
                    running = false;
                    heartbeat.stop();
                    // 退出即放锁，不给系统留「已退出还占着 wake lock」的残留
                    DaemonWakeLock.release();
                    return "{\"ok\":true,\"bye\":true}";
                case "help":
                    return "{\"ok\":true,\"cmds\":\"ping|create name w h dpi [flags]|mirror src w h out|"
                            + "grab id out|stats id|list|destroy id|"
                            + "hb start intervalMs displayId path|hb stop|hb status|watch ...|exit\"}";
                default:
                    return err("unknown command: " + a[0]);
            }
        } catch (Throwable t) {
            Ln.e("dispatch 失败: " + line, t);
            return err(String.valueOf(t));
        }
    }

    private String cmdCreate(String[] a) throws Exception {
        if (a.length < 5) {
            return err("usage: create <name> <w> <h> <dpi> [flags]");
        }
        String name = a[1];
        int w = Integer.parseInt(a[2]);
        int h = Integer.parseInt(a[3]);
        int dpi = Integer.parseInt(a[4]);
        int flags = a.length > 5 ? Integer.parseInt(a[5]) : DEFAULT_FLAGS;
        DisplayEngine.Entry e = engine.create(name, w, h, dpi, flags);
        return "{\"ok\":true,\"display\":" + DisplayEngine.describe(e) + "}";
    }

    private String cmdMirror(String[] a) throws Exception {
        if (a.length < 5) {
            return err("usage: mirror <srcDisplayId> <w> <h> <out.raw>");
        }
        int src = Integer.parseInt(a[1]);
        int w = Integer.parseInt(a[2]);
        int h = Integer.parseInt(a[3]);
        String out = a[4];
        DisplayEngine.Entry e = engine.mirror("oas-mirror-" + src, w, h, src);
        Thread.sleep(1200);
        DisplayEngine.Grab g = engine.grab(e.id, out);
        String json = "{\"ok\":" + g.ok + ",\"display\":" + DisplayEngine.describe(e)
                + ",\"grab\":" + grabJson(g) + "}";
        engine.destroy(e.id);
        return json;
    }

    private String cmdGrab(String[] a) {
        if (a.length < 3) {
            return err("usage: grab <displayId> <out.raw>");
        }
        int id = Integer.parseInt(a[1]);
        DisplayEngine.Grab g = engine.grab(id, a[2]);
        return "{\"ok\":" + g.ok + ",\"grab\":" + grabJson(g) + "}";
    }

    private String cmdStats(String[] a) {
        if (a.length < 2) {
            return err("usage: stats <displayId>");
        }
        DisplayEngine.Entry e = engine.get(Integer.parseInt(a[1]));
        if (e == null) {
            return err("no such display");
        }
        return "{\"ok\":true,\"display\":" + DisplayEngine.describe(e)
                + ",\"rotation\":" + DisplayEngine.displayRotation(e) + "}";
    }

    /**
     * watch <id> <ms> <returnComponent> [launch shell cmd...]
     *
     * 在 daemon 侧采样：先执行 launch 命令（把别的 App 拉到主屏），再数 ms 毫秒内的
     * 帧数增量，最后把本 app 拉回前台。
     * 为什么必须在 daemon 里做：app 一被切到后台就会被 cached-app freezer 冻住，
     * 「主屏在刷别的 App 时虚拟屏是否还出帧」这件事在 app 进程里根本测不出来。
     */
    private String cmdWatch(String[] a) throws Exception {
        if (a.length < 4) {
            return err("usage: watch <displayId> <ms> <returnComponent> [shell cmd...]");
        }
        int id = Integer.parseInt(a[1]);
        int ms = Integer.parseInt(a[2]);
        String returnComponent = a[3];
        StringBuilder launch = new StringBuilder();
        for (int i = 4; i < a.length; i++) {
            launch.append(a[i]).append(' ');
        }
        String launchOut = "";
        if (launch.length() > 0) {
            launchOut = exec(launch.toString().trim());
        }
        Thread.sleep(1200);
        long frames0 = engine.framesOf(id);
        Thread.sleep(ms);
        long frames1 = engine.framesOf(id);
        if (!returnComponent.isEmpty()) {
            exec("am start -n " + returnComponent);
        }
        return String.format(Locale.US,
                "{\"ok\":%b,\"frames0\":%d,\"frames1\":%d,\"delta\":%d,\"ms\":%d,\"launchOut\":\"%s\"}",
                frames1 > frames0, frames0, frames1, frames1 - frames0, ms, esc(launchOut));
    }
    /**
     * hb start <intervalMs> <displayId> <path> | hb stop | hb status
     *
     * 心跳落在 **daemon 侧**：熄屏后 app 被 Hans freezer 冻住时，这里仍在按秒写 JSONL，
     * 所以「C 段空洞」只有这一路证据是可信的。纯 Java 实现，不依赖 Python。
     */
    private String cmdHeartbeat(String[] a) {
        if (a.length < 2) {
            return err("usage: hb start <intervalMs> <displayId> <path> | hb stop | hb status");
        }
        String sub = a[1];
        if ("start".equals(sub)) {
            if (a.length < 5) {
                return err("usage: hb start <intervalMs> <displayId> <path>");
            }
            int interval = Integer.parseInt(a[2]);
            final int displayId = Integer.parseInt(a[3]);
            String path = a[4];
            if (displayId >= 0 && engine.get(displayId) == null) {
                return err("displayId " + displayId + " 不存在");
            }
            return heartbeat.start(interval, displayId, path, new Heartbeat.FramesProvider() {
                @Override
                public long framesOf(int id) {
                    return engine.framesOf(id);
                }
            });
        }
        if ("stop".equals(sub)) {
            // 注意：这里**不放锁**。hb 只是心跳录制，daemon 还在服务、自动化也可能还在跑；
            // 在 hb stop 放锁会重现「息屏时 CPU 真停」。锁的作用域跟进程生命周期（见类注释）。
            return heartbeat.stop();
        }
        if ("status".equals(sub)) {
            return heartbeat.statusJson();
        }
        return err("unknown hb sub: " + sub);
    }

    /**
     * kb run &lt;aSec&gt; &lt;bSec&gt; &lt;cSec&gt; &lt;displayId&gt; &lt;reportPath&gt; | kb status | kb stop
     *
     * 驱动器放 daemon 侧（ADR-0001 ④）：app 一进后台就被冻住，只有 daemon 能按段推进。
     * 本步只做骨架（计时 + 报告），屏幕动作用下一步。
     */
    private String cmdKb(String[] a) {
        if (a.length < 2) {
            return err("usage: kb run <aSec> <bSec> <cSec> <displayId> <reportPath> | kb status | kb stop");
        }
        String sub = a[1];
        if ("run".equals(sub)) {
            if (a.length < 7) {
                return err("usage: kb run <aSec> <bSec> <cSec> <displayId> <reportPath>");
            }
            int aSec;
            int bSec;
            int cSec;
            int displayId;
            try {
                aSec = Integer.parseInt(a[2]);
                bSec = Integer.parseInt(a[3]);
                cSec = Integer.parseInt(a[4]);
                displayId = Integer.parseInt(a[5]);
            } catch (Throwable t) {
                return err("参数不是整数: " + t);
            }
            String reportPath = a[6];
            return kbRun.run(aSec, bSec, cSec, displayId, reportPath);
        }
        if ("status".equals(sub)) {
            return kbRun.statusJson();
        }
        if ("stop".equals(sub)) {
            return kbRun.stop();
        }
        return err("unknown kb sub: " + sub);
    }

    private String cmdList() {
        List<DisplayEngine.Entry> all = engine.list();
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"displays\":[");
        for (int i = 0; i < all.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(DisplayEngine.describe(all.get(i)));
        }
        return sb.append("]}").toString();
    }

    private String cmdDestroy(String[] a) {
        if (a.length < 2) {
            return err("usage: destroy <displayId>");
        }
        boolean ok = engine.destroy(Integer.parseInt(a[1]));
        return "{\"ok\":" + ok + "}";
    }

    public static String grabJson(DisplayEngine.Grab g) {
        return String.format(Locale.US,
                "{\"ok\":%b,\"error\":\"%s\",\"path\":\"%s\",\"bytes\":%d,\"ms\":%.1f,"
                        + "\"mean\":%.2f,\"blackRatio\":%.4f,\"frames\":%d,\"copies\":%d,"
                        + "\"w\":%d,\"h\":%d,\"rowStride\":%d,\"pixelStride\":%d,\"rotation\":%d}",
                g.ok, esc(g.error), esc(g.path), g.bytes, g.ms, g.mean, g.blackRatio,
                g.frames, g.copies, g.width, g.height, g.rowStride, g.pixelStride, g.rotation);
    }

    private static String err(String message) {
        return "{\"ok\":false,\"error\":\"" + esc(message) + "\"}";
    }

    public static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "/").replace("\"", "'").replace("\n", " ");
    }

    private static final String ABSTRACT_NAME = "org.onmyoji.oas.display";

    /**
     * 打开服务端 socket。
     *
     * 用「抽象命名空间」而不是文件系统 socket：LocalServerSocket 的公开构造只接受
     * 名字或 fd，抽象名空间没有文件权限问题，app 跨 uid 也能直连，也不用留 socket 文件。
     */
    private static LocalServerSocket openServer(String path) throws Exception {
        sFileSocket = false;
        return new LocalServerSocket(ABSTRACT_NAME);
    }
    /** 跑一条 shell 命令并返回合并输出（daemon 是 root，am/input 都能直接调）。 */
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

    private static void chmod666(String path) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"chmod", "666", path});
            p.waitFor();
        } catch (Throwable t) {
            Ln.w("chmod 失败: " + t);
        }
    }

    private static void closeQuietly(LocalSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (Throwable ignored) {
        }
    }
}