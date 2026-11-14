package org.onmyoji.oas.server;

import org.onmyoji.oas.server.third.Ln;

import java.io.File;
import java.io.FileOutputStream;

/**
 * daemon 侧 wakelock（ADR-0001 ②：执行期间持锁），走**内核 sysfs 接口**而不是 PowerManager。
 *
 * 为什么不用 PowerManager：
 *   1) app_process 里的 FakeContext 的 base 可能是 null（Workarounds.getSystemContext() 失败时），
 *      `ctx.getSystemService(POWER_SERVICE)` 直接 NPE —— 实测就是这么挂的；
 *   2) 反射构造（ServiceManager → IPowerManager.Stub → hidden ctor）在 API 35 上也构造不出来。
 *   而 `/sys/power/wake_lock` 在 root 下可直接写、可直接读回校验，代价最小、语义等价（PARTIAL：
 *   只保 CPU，不点亮屏幕）。
 *
 * 为什么在 daemon 里持、而不是 app 里持：app 进程会被 Realme/ColorOS 的
 * `OplusHansManager`（cached-app freezer）冻住，冻住后它持有的 WakeLock 语义就不可靠。
 * HANDOFF §十一 实测：uid10331 被冻 119s，`oas:keepalive` 于 17:47:38 被系统释放、
 * 17:49:38 才恢复，心跳空洞 122s，而同期 daemon 侧虚拟屏帧数照涨（6024→9704）。
 * daemon 不受冻，所以锁必须在这里持。
 *
 * 只持 PARTIAL 语义：**绝不碰** SCREEN_BRIGHT / FULL 这类「不让灭屏」的锁，主屏必须能正常熄灭。
 *
 * 关键风险：**SIGKILL/SIGTERM 时都没有代码路径能执行**（app_process 里实测 shutdown hook 不跑），
 * 残留的表现就是「手机永远不休眠」。因此：① 启动时先往 wake_unlock 写一次做幂等清理；
 * ② 起一个独立 reaper 子进程盯着本进程 pid，进程一没就替我们解锁（KILL 也救得回来）；
 * ③ release 还能被 exit / 服务循环结束 / main finally / shutdown hook 调用（幂等）。
 */
public final class DaemonWakeLock {

    /** 锁名：`cat /sys/power/wake_lock` 里能看到它（自测命令依赖它）。 */
    public static final String TAG = "oas:daemon";

    private static final String LOCK_PATH = "/sys/power/wake_lock";
    private static final String UNLOCK_PATH = "/sys/power/wake_unlock";

    private static volatile boolean sHeld;
    private static volatile String sError = "";
    private static Thread sHook;
    private static volatile Process sReaper;

    private DaemonWakeLock() {
    }

    public static boolean isHeld() {
        return sHeld;
    }

    public static String lastError() {
        return sError;
    }

    /** 申请（幂等）。返回空串=成功；否则是告警文本（调用方打印后继续跑，不退出）。 */
    public static synchronized String acquire() {
        if (sHeld) {
            return "";
        }
        try {
            // ① 幂等清理：上一次可能被 SIGKILL，残留的锁会让机器不休眠
            write(UNLOCK_PATH, TAG);
            // ② 加锁
            if (!write(LOCK_PATH, TAG)) {
                sError = "写 " + LOCK_PATH + " 失败（无 root？）";
                Ln.w("wakelock: " + sError);
                return sError;
            }
            // ③ 读回校验：写成功不代表生效
            String held = read(LOCK_PATH);
            if (!held.contains(TAG)) {
                sError = "读回校验失败，wake_lock 里没有 " + TAG + "（当前: " + held.trim() + "）";
                Ln.w("wakelock: " + sError);
                return sError;
            }
            sHeld = true;
            sError = "";
            if (sHook == null) {
                sHook = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        release();
                    }
                }, "oas-wakelock-release");
                try {
                    Runtime.getRuntime().addShutdownHook(sHook);
                } catch (Throwable t) {
                    Ln.w("wakelock: 注册 shutdown hook 失败: " + t);
                }
            }
            Ln.i("wakelock: 持有 " + TAG + "（" + LOCK_PATH + "）");
            // 关键：SIGTERM/SIGKILL 都不会走 shutdown hook（app_process 实测），
            // 留下残留锁的表现就是「手机永不休眠」。所以起一个**独立的 reaper 子进程**：
            // 它盯着本进程 pid，进程一没就替我们把锁解掉 —— KILL 也救得回来。
            startReaper();
            return "";
        } catch (Throwable t) {
            sError = "申请异常: " + t;
            Ln.w("wakelock: " + sError);
            return sError;
        }
    }

    /** 释放（幂等，任何路径都可以重复调）。 */
    public static synchronized void release() {
        boolean was = sHeld;
        sHeld = false;
        try {
            write(UNLOCK_PATH, TAG);
            // reaper 只是兜底，主路径放锁后收掉它，别留一个空转的 sh
            Process reaper = sReaper;
            sReaper = null;
            if (reaper != null) {
                try {
                    reaper.destroy();
                } catch (Throwable ignored) {
                }
            }
            Ln.i("wakelock: 已释放 " + TAG + (was ? "" : "（此前未持有，幂等）"));
        } catch (Throwable t) {
            Ln.w("wakelock: release 异常: " + t);
        }
    }

    /**
     * 起 reaper 子进程：本进程一消失就把锁解掉。
     * 为什么必须有：SIGTERM/SIGKILL 不触发 shutdown hook（app_process 实测 kill -TERM 后锁残留），
     * 残留 = 手机永不休眠。子进程是独立进程，父进程死了它还在，正好替我们收尾。
     */
    private static void startReaper() {
        try {
            final int pid = android.os.Process.myPid();
            final String script = "P=" + pid + "; while kill -0 $P 2>/dev/null; do sleep 2; done; "
                    + "echo " + TAG + " > " + UNLOCK_PATH;
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", script);
            pb.redirectErrorStream(true);
            sReaper = pb.start();
            Ln.i("wakelock: reaper 已起（守 pid=" + pid + "）");
        } catch (Throwable t) {
            Ln.w("wakelock: reaper 启动失败（kill 后可能留残留锁）: " + t);
        }
    }

    /** 往 sysfs 节点写一行；返回是否成功。 */
    private static boolean write(String path, String value) {
        try (FileOutputStream out = new FileOutputStream(new File(path), false)) {
            out.write((value + "\n").getBytes("UTF-8"));
            out.flush();
            return true;
        } catch (Throwable t) {
            Ln.w("wakelock: 写 " + path + " 失败: " + t);
            return false;
        }
    }

    private static String read(String path) {
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(path)));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[512];
            int n;
            while ((n = br.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            br.close();
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}