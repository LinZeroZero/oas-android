package org.onmyoji.oas.server;

import android.hardware.display.DisplayManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;

import org.onmyoji.oas.server.third.Ln;

import java.io.BufferedReader;
import java.io.BufferedWriter;
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

    public SocketServer(String path) {
        this.path = path;
    }

    public void run() throws Exception {
        LocalServerSocket server = openServer(path);
        Ln.i("socket ready: " + socketLabel(path));
        System.out.println("READY " + socketLabel(path));
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

    /** 供 `--cmd` 一次性执行用（不经过 socket），与 socket 里每行命令走同一条 dispatch。 */
    public String exec(String line) {
        return dispatch(line);
    }

    String dispatch(String line) {
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
                    // 一并写清 CLI 侧 --socket 的真实语义：此前 openServer 无视入参，
                    // 谁都以为抽象名和路径是两套东西。
                    return "{\"ok\":true"
                            + ",\"socket\":\"" + esc(socketLabel(path)) + "\""
                            + ",\"usage\":\"--socket <name> = 抽象命名空间名（缺省 " + ABSTRACT_NAME
                            + "，换名即可同机共存，同名报 Address already in use）;"
                            + " --socket /abs/path = 同样按抽象名 bind（Android 公开 API 只能建抽象"
                            + "命名空间 socket，不落地文件、无 chmod）;"
                            + " --cmd '<line>' = 只执行一条命令，不 bind socket\""
                            + ",\"cmds\":\"ping|create name w h dpi [flags]|mirror src w h out|"
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
            launchOut = sh(launch.toString().trim());
        }
        Thread.sleep(1200);
        long frames0 = engine.framesOf(id);
        Thread.sleep(ms);
        long frames1 = engine.framesOf(id);
        if (!returnComponent.isEmpty()) {
            sh("am start -n " + returnComponent);
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

    /** 打日志/回执里用的 socket 名：补一句 (abstract) 提醒它不是文件。 */
    private static String socketLabel(String path) {
        return resolveName(path) + " (abstract)";
    }

    /** `--socket` 的取值 → 真正 bind 的抽象名：null/空退到 {@link #ABSTRACT_NAME}，其余原样用。 */
    private static String resolveName(String path) {
        return (path == null || path.trim().isEmpty()) ? ABSTRACT_NAME : path;
    }

    /**
     * 按 `--socket` 的入参打开服务端 socket，**真的按名字 bind**。
     *
     * - `null` / 空 → 固定抽象名 {@link #ABSTRACT_NAME}（与旧行为一致，app 侧连的就是这个名字）
     * - 其余（含 `/` 开头的字符串）→ 原样当抽象名 bind：换名即可在同一台机器上跑第二个实例，
     *   同名则照常抛 Address already in use（说明 bind 确实按名字生效）
     *
     * 为什么不支持「/ 开头的文件系统 socket」：Android 公开 API 只能在抽象命名空间起监听。
     * 官方文档原话（android.net.LocalServerSocket）："On the Android platform, the name is
     * created in the Linux abstract namespace (instead of on the filesystem)"；类里也只有
     * `LocalServerSocket(String)` 与 `LocalServerSocket(FileDescriptor)` 两个构造，没有 File 版
     * （`new LocalServerSocket(new File(p))` 直接编译不过）。所以原来那套「文件 socket + chmod 666」
     * 分支无法落地，已一并删除，避免留死代码骗人。
     */
    private static LocalServerSocket openServer(String path) throws Exception {
        if (path != null && path.startsWith("/")) {
            Ln.w("--socket 收到路径形态，但公开 API 不支持文件系统 socket（只有抽象命名空间）→ "
                    + "按抽象名 bind: " + path);
        }
        return new LocalServerSocket(resolveName(path));
    }
    /** 跑一条 shell 命令并返回合并输出（daemon 是 root，am/input 都能直接调）。 */
    private static String sh(String cmd) {
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