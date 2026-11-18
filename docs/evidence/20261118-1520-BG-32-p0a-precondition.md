# BG-32 / P0-A 长跑前置搭建实测（2026-11-18 15:12–15:20）

> 目的：P0-A「14 分钟真熄屏长跑」必须先有载体（daemon + 虚拟屏 + 游戏在虚拟屏上跑）。
> 本轮只做前置，**未熄屏、未跑 `hang_check start`、未改任何系统设置**。
> 记录人：主 AI；执行人：写手 A6（`53ba6d3f-ded9-4d1d-856b-d203ad787272`）。
> 结论先行：**前置未达成，长跑今天不能开跑**；两个卡点见 §4，均非操作失误。

依据：head `6fe46aa`（PR #35 合并后）。工作区 `/data/user/0/com.ai.assistance.operit/files/workspace/8868caab-ae27-4814-9992-11fb83225a35`。

---

## 1. 开跑前的基线（主 AI 只读实查）

| 项 | 实测值 |
|---|---|
| 游戏 `com.netease.onmyoji` | 在跑，pid `14163`，**在主屏**（不是虚拟屏） |
| daemon | **未运行**；`/data/local/tmp/oas-daemon.log` 304390 B，时间停在 2026-11-17 23:44 |
| 虚拟屏 `oas` | `DisplayInfo{"oas"` = **0** |
| `mWakefulness` | `Awake`，`mHoldingDisplaySuspendBlocker=true` |
| `STREAM_ALARM` | `Muted: false`（未静音），`zen_mode=0` |
| `screen_off_timeout` | `1800000`（30 分钟，即 BACKLOG BG-06 那条未修的旧账） |

结论：`hang_check.sh` 只负责「看」，不建屏、不拉游戏 → 载体必须由人/AI 先搭。

## 2. 前置搭建（A6 按文档标准序列执行）

启动序列**不是臆造**，取自 `DisplayServer.kt` + `Main.java` 头部注释 + `hang_check.sh` 的 `daemon_cmd`：

| 步骤 | 命令/结果 | 实测 |
|---|---|---|
| 起 daemon | `cd /data/local/tmp && CLASSPATH=<jar> setsid /system/bin/app_process /system/bin org.onmyoji.oas.server.Main --socket org.onmyoji.oas.display --pidfile <pidfile>` | **pid 21114**；`ping` → `{"ok":true,"pid":21114,"displays":0…}`；pidfile = 21114 |
| 建虚拟屏 | `--connect org.onmyoji.oas.display --cmd "create oas 1280 720 320"` | **displayId 38**（`oas`，1280×720，dpi320，flags 9，layerStack 38，distinct=1） |
| 拉游戏 | 开跑前 `pidof` 为空（**不是「已在跑」**）→ `am force-stop; am start -W --display 38 -n com.netease.onmyoji/.Client` | `Status: ok`，游戏窗口 7–14 个，`mLastFocusedRootTask` 指到游戏 task |

daemon 身份复核（主 AI 亲查，不只信回执）：
`/proc/21114/cmdline` = `/system/bin/app_process /system/bin org.onmyoji.oas.server.Main --socket org.onmyoji.oas.display --pidfile …/server.pid`，pidfile 内容 `21114`，一致。

## 3. 「画面在动」验证失败

| 时间 | frames | copies | 说明 |
|---|---|---|---|
| 建屏后首读 | 292 | 127 | — |
| 重拉游戏后 | 544 | 238 | 有跳变 |
| +6s | 544 | 238 | 不动 |
| +40s（三次读） | 544 | 238 | **仍不动**，同期游戏 CPU 1699→5328 jiff（进程在忙） |
| 探针后 | 544 | 238 | 仍不动 |

`grab 38` 三次结果**完全相同**：`bytes=3686416`、`mean=126.39`、`blackRatio=0.0000` → 不是黑屏，是**同一帧被冻结**。

## 4. 两个卡点（原样记录，不猜因）

### 卡点 A：游戏停在「资源更新」静态弹窗

把 `grab` 的 raw 转 PNG 后读出的画面文字：

> `0.0.802`／**客户端版本已更新，需要下载额外资源**／**资源大小：27.7MB**／**建议在 wifi 环境下下载**／`取消` `确定`／背景另有「正在检测下载资源」

网络实查：**无 Wi‑Fi**，活跃默认网络为 `MOBILE NR`（`ccmni1`／`ctnet`）。
→ 这一屏是静态的，SurfaceFlinger 无新帧 → `frames` 冻结属**结果**，不是出帧通路坏了。
→ 点「确定」会走**移动数据下载 27.7MB**，属用户主力机支出，**未经用户同意不点**。

### 卡点 B：虚拟屏上的游戏 activity 是 PAUSED，`input -d 38` 不投递

| 观测 | 值 |
|---|---|
| `dumpsys window displays`（display 38） | `mCurrentFocus=null`、`mFocusedApp=null` |
| 游戏 task | `#932` 消失 → 新 `Task{#933 … visible=false visibleRequested=false}`，`mLastPausedActivity: com.netease.onmyoji/.Client` |
| 游戏 pid 变化 | `21309` → `21955` → `22814`（两次换 pid） |
| 输入探针 1 | `input -d 38 tap 800 530`（确定按钮位置）→ 画面 `mean` 不变、frames 544 |
| 输入探针 2 | `input -d 38 keyevent 4`（BACK）→ frames 544 |
| 输入探针 3 | `input -d 38 tap 640 120`（空白区）→ `mCurrentFocus` 仍 null、frames 544 |

判定：**`input -d 38` 未投递**。且「无焦点」与「注入不生效」是同一状态的两面——activity 已 PAUSED/不可见，display 38 上只剩冻结残帧。

**影响**：P0-A 的 D 段判据是「唤醒 + 定向点击」，若虚拟屏上的游戏无法接收注入输入，D 段**当前不可验** → 这是 P0-A 之前必须解决的技术前置，不是跑一次长跑就能绕过。

## 5. 本轮留下的产物

| 路径 | 内容 |
|---|---|
| `/sdcard/Download/oas-d38.png` | 虚拟屏 38 首帧转图（640×360，1280×720 RGBA 源） |
| ~~`oasL.png` / `oasR.png`~~ | A6 自述有做左右半裁剪图，**实查磁盘不存在** → 按磁盘为准，不作证据引用 |
| ~~`oas-d38.raw` / `oas-d38b.raw`~~ | 两份 3.6MB raw 已在收工清理时删除（像素数据已由上面三张 PNG 承载） |

设备侧现状（文档写入时）：daemon 21114 存活、display 38 存在、游戏 pid 22814、**屏幕未熄**。
→ 若当天不再继续，收工须按 `docs/HANDOFF.md` §十二 收工套件清理（destroy 38 → `kill -9 21114` → `am force-stop` 游戏 → 拉回 Operit 前台）。

## 6. 下一步（待用户拍板）

1. **资源下载**：Wi‑Fi 下点「确定」下 27.7MB，或用户在主屏手动把游戏更新完再拉回虚拟屏。
2. **卡点 B**：需写手排一轮「虚拟屏上 activity 为何 PAUSED、`input -d <id>` 是否真能投递」的定位（属代码/诊断，非长跑）。
3. 两者都过，才谈 P0-A 的 14 分钟真熄屏长跑与闹钟收工。
