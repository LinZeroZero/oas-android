# ADR-0001：自动化与阶段调度归 root daemon，app 退化为瘦客户端

- **状态**：Accepted
- **日期**：2026-11-14
- **决策者**：项目所有者（用户）；提出与论证：主 AI；实现：A
- **取代/相关**：BG-01（四段拿不到 4/4）、BG-02（C 段从未真测）、`docs/BACKLOG.md`

## 1. Context（背景）

OAS 移植版要在**用户正常使用手机**的前提下跑游戏自动化：游戏渲染在 1280×720 虚拟屏，主屏照常使用，全程不需要 PC。这要求自动化在「app 不在前台、屏幕可能已关闭」时仍然继续。

我们先后被三类现象误导过，必须先厘清安卓到底有几套机制在动我们的进程：

| 机制 | 触发条件 | 行为 | 依据 |
|---|---|---|---|
| LMK（`lmkd`） | 内存压力（PSI 监视器） | 按 `oom_adj` 从低到高杀进程；`ro.lmk.low=1001` 覆盖 cached 与非必要服务，`medium=800`，`critical=0`（任何进程） | AOSP《Low memory killer daemon》 |
| Cached apps freezer | 进程进入 cached 态 | **不杀，冻结**：所有线程停止 CPU。Android 14+ 进入 cached 态 10 秒后冻结。若某 app 的全部进程都被冻结，系统会**终止该 app 持有的 TCP socket** | AOSP《Cached apps freezer》 |
| Doze / App Standby | 息屏且长期静止 | 限制网络访问，推迟 Job、sync 与标准 alarm | developer.android.com《Doze and App Standby》 |
| SoC suspend | 无人持有 wake lock | 整机挂起，CPU 停止，定时器不走 | `PowerManager`：`PARTIAL_WAKE_LOCK` 才是「保证 CPU 运行、允许屏幕关闭」 |
| Phantom process killer | Android 12+，app 的派生进程过多/占 CPU | 杀「幽灵进程」 | 实测本机 `settings_enable_monitor_phantom_procs=false`，**不可依赖** |

官方对 app 的结论是明确的，不是「想办法保活」：

> “Cached processes can be killed by the system at any time, apps **should cease all work** while in the cached state.”
> —— developer.android.com《Processes and app lifecycle》

### 现场证据（本项目实测，非推测）

1. **app 侧驱动被冻结**：`/proc/2198/task/2375/stack = do_freezer_trap`。进程 `stat` 仍是 `S`，所以只看进程状态看不出来——这是线程级冻结。
2. **同一时刻 daemon 心跳完好**：pid 2707 连续 **645 拍 / 644 s / maxGap 1004 ms / 零空洞**。据此推翻了早期「daemon 约 60 秒后消失」的误判（当时看错对象：真因是 app 侧 socket 调用阻塞 + 报告只写一次）。
3. **daemon 与 app 不在同一个 cgroup**（2026-11-14 实测）：

   | 对象 | cgroup | `oom_score_adj` | 父进程 |
   |---|---|---|---|
   | daemon `pid 8143` | `memory:/` | **-1000** | `sh`（root，非 app） |
   | app `pid 8016` | `memory:/apps/uid_10331`、`cpuset:/foreground` | 200 | zygote |

   即：LMK、freezer、phantom 三套机制**都不作用于 daemon**，这不是运气，是结构性免疫。
4. **本机会真的挂起**：`/sys/power/suspend_stats/success = 1164`。所以 C 段（息屏）失败的主因可能不是逻辑 bug，而是没人持 wake lock。
5. **「息屏测试」此前不成立**：`dumpsys power` 里游戏自己持有
   `SCREEN_BRIGHT_WAKE_LOCK 'WindowManager/displayId:19'`（`WorkSource{10386 com.netease.onmyoji}`，已持续 1h25m），虚拟屏被它锁着亮。

### 同类先例

- **scrcpy**：`adb shell CLASSPATH=... app_process / com.genymobile.scrcpy.Server`（`doc/develop.md`）——server 由 shell 拉起，进程不属于 app，与我们 daemon 同构。
- **Termux**：前台服务 + `PowerManager.WakeLock` + `WifiManager.WifiLock`（`TermuxService.java`，即 `termux-wake-lock`），另外还要处理 phantom killer。这是「留在 app 里」的官方认可做法，但代价是常驻通知、Doze 期网络受限、锁屏后仍有风险。

## 2. Decision（决定）

**自动化执行体与阶段调度归 root daemon；app 只做 UI、控制与展示。**

具体四条硬约束：

1. **daemon 必须留在 root 域**
   启动路径固定为 `su -c "CLASSPATH=... app_process ..."`，不得由 app 直接 `fork`。
   daemon 启动时**自检**：`ppid` 不是 app 进程、`oom_score_adj == -1000`、`/proc/self/cgroup` 不在 `/apps/` 下；任一不满足则拒绝执行并写日志。目的：防止以后有人「顺手」改成从 app 派生，从而静默退回被冻结状态。

2. **执行自动化期间持 `PARTIAL_WAKE_LOCK`**
   由 daemon 通过 `PowerManager` 申请（root 可直连系统服务），可配 `--wakelock on|off`，任务结束或异常退出必须释放。
   **不申请**屏幕锁（`SCREEN_BRIGHT_WAKE_LOCK` / `FULL_WAKE_LOCK`）——主屏必须能被用户正常熄灭，这是产品硬约束。

3. **app ↔ daemon 用短连接**
   不假设长连接：每次请求新建连接、带超时、失败重连。理由：freezer 会在 app 全冻结时终止 TCP socket，长连接语义天然不可靠。现有 `DisplayServer.ping()` 已无状态，改造成本低。

4. **阶段调度（phase driver）前移到 daemon**
   daemon 提供 `kb run <a_sec> <b_sec> <c_sec> <reportPath>`：自己分段计时、自己读心跳 JSONL、自己算 maxGap、自己写报告。
   app 端 `KeepAlivePoc` 退化为「发一条命令 + 稍后读报告」。

### 不做的事（明确排除的替代方案）

| 方案 | 否决理由 |
|---|---|
| app 前台服务 + wake lock（Termux 式） | 常驻通知；Doze 期网络仍被推迟；锁屏长时间运行仍可能被冻；与「主屏照常使用」的体验目标冲突 |
| 关闭 freezer（`device_config put ... use_freezer false`） | 全局副作用、需重启、影响整机；不是本应用有权做的取舍 |
| 1 像素 Activity / 循环静音音频等「保活黑科技」 | 与系统对抗、不可靠、属于灰产手法；一旦系统升级即失效 |
| 由 PC 侧 ADB 常驻驱动 | 直接违背「单 APK、无需 PC」的产品前提 |

## 3. Consequences（后果）

**正面**

- 四段保活测试第一次有「能跑完」的可能；C 段从「必然假失败」变为可测。
- 明确分工后，app 可以随时被系统回收而不影响正在跑的任务，符合官方「cached 时停止干活」的要求。
- 门禁与验收标准可写死：daemon 自检、wake lock 申请/释放、maxGap 与 suspend 次数对照。

**负面 / 代价**

- daemon 侧代码量上升（段循环、JSONL 解析、空洞计算、报告写入、wake lock 生命周期）。
- root 域调试更难：日志必须同时落 `/data/local/tmp` 与 app cache，否则又是「看不见」。
- 需要新增一条「daemon 环境自检」的回归检查，否则架构约束会随时间腐化。
- 持 `PARTIAL_WAKE_LOCK` 会增加耗电，属于用户显式启动自动化时的可接受成本，但必须在结束时释放。

## 4. 对验收方法的影响（必须同步改）

- **C 段测试前置条件**：先确认游戏那个 `displayId:19` 的 `SCREEN_BRIGHT_WAKE_LOCK` 已释放，否则测到的是「主屏灭了、虚拟屏还亮」，不是真息屏。
- **记录两组数据**：`maxGap` **与** `/sys/power/suspend_stats/success` 的前后差值。
- **判读规则**：若空洞与挂起次数强相关 → 是电源/唤醒问题（检查 wake lock），不是调度 bug；若持锁后仍空洞 → 才是 daemon 逻辑问题。

## 5. 参考

- AOSP《Cached apps freezer》《Low memory killer daemon》
- developer.android.com《Processes and app lifecycle》《Doze and App Standby》
- developer.android.com《PowerManager》（`PARTIAL_WAKE_LOCK`）
- scrcpy `doc/develop.md`；Termux `TermuxService.java`
- 本项目证据：`docs/evidence/`（四段与心跳实测记录）
