# 后台存活机制：谁在杀/冻我们，业界靠什么活（只读调研 第二轮）

日期：2026-11-16
证据等级：`[AOSP:文件:行]`（refs/heads/main 快照）/ `[文档]`（链接均已 200 校验）/ `[实测]` / `[未验]`
纪律：无源码依据的写法一律标「闭源/未验」，不转述博客。

---

## 0. 五条硬结论

1. **`do_freezer_trap` 不是厂商玄学，就是 AOSP 的 cached-app freezer**：进程被 cgroup freezer 冻住时内核栈就停在该函数。判定条件只有一条：`curAdj >= CACHED_APP_MIN_ADJ(900)` 且未被豁免 → `[AOSP:OomAdjuster.java:4063-4091]` + `[AOSP:ProcessList.java:389]`。
2. **冻结是 am 侧对 `ProcessRecord` 的动作**，uid 0 的 `app_process` 没有 ProcessRecord，AOSP 这条路径**根本不会看它**；能冻它的只有厂商私有服务（Hans/Athena），需另证（我们上一轮的 `--wait` 60s 消失仍属 `[未验]`）。
3. 前台服务能拿到的最好 adj 是 `PERCEPTIBLE_APP_ADJ(200)`/`SERVICE_ADJ(500)`，**都远低于 900 的冻结线**——所以 FGS 确实能免冻，但它换不来 CPU 不休眠，`PARTIAL_WAKE_LOCK` 才是 CPU 侧的钥匙。
4. LMK/lmkd 与 freezer 是两套系统：freezer 看 adj 门槛冻结（可逆），lmkd 看内存压力直接 `SIGKILL`（不可逆）。
5. 双进程互拉（A 起 B、B 起 A）在 Android 8+ 的后台执行限制 + 8.1 的后台服务限制下**已不具备可靠性**，且 12+ 的「后台启动 FGS 受限」把最后的口子也堵了；它不是 AOSP 提供的机制，属早期 trick，本项目不应依赖。

---

## 1. 进程优先级与杀人者（问题一 A）

### 1.1 adj 阶梯（`ProcessList.java`）

| 常量 | 行 | 值 | 语义 |
|---|---|---|---|
| `PERSISTENT_PROC_ADJ` | `283` | -800 | 常驻（system/phone） |
| `PERSISTENT_SERVICE_ADJ` | `279` | -700 | 绑定了常驻服务 |
| `SYSTEM_ADJ` | `286` | -900 | system_server |
| `FOREGROUND_APP_ADJ` | `275` | 0 | 前台/正在交互 |
| `PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ` | `271` | 50 | 刚离开前台 |
| `VISIBLE_APP_ADJ` | `265` | 100 | 可见但不可交互 |
| `PERCEPTIBLE_APP_ADJ` | `261` | 200 | 可感知（含 FGS 的“前台上限”） |
| `SERVICE_ADJ` | `238` | 500 | 普通后台服务 |
| `CACHED_APP_MIN_ADJ` | `207` | **900** | **缓存线：≥900 即可被冻/被优先回收** |
| `CACHED_APP_MAX_ADJ` | `206` | 999 | 最冷缓存 |

### 1.2 LMK / lmkd

- am 侧不自己决定内存回收，而是把 adj/内存水位下发给 lmkd：`[AOSP:ProcessList.java:1133,1563,1600,1612]` 全是 `writeLmkd(buf, ...)`；协议常量注释明确「must be kept in sync with lmk_cmd definitions in lmkd.h」`[AOSP:ProcessList.java:349]`。
- 语义：freezer 是**可逆冻结**（写 cgroup），lmkd 是**直接 SIGKILL**。两者判据不同，不能互相替代。

### 1.3 `am_kill` 的 reason

- 事件定义：`[AOSP:EventLogTags.logtags:34]` `30023 am_kill (User),(PID),(Process Name),(OomAdj),(Reason),(Rss)` —— **Reason 是自由字符串**，没有枚举白名单。
- 因此「哪些 reason 属于后台清理」不能靠背清单，只能按调用点取：例如 `[AOSP:ActivityManagerService.java:15817]` `app.killLocked("empty", ...)` 就是典型的「空进程清理」。
- 可操作做法（本机可验证，非转述）：`logcat -b events | grep am_kill` 直接读真机 reason，比任何清单都准。

---

## 2. Cached app freezer（问题一 B，最关键）

### 2.1 判定链（三段，缺一不冻）

| 段 | 位置 | 内容 |
|---|---|---|
| ① 总开关 | `[AOSP:CachedAppOptimizer.java:262]` `DEFAULT_USE_FREEZER = true`；`[AOSP:...:729]` `useFreezer()` | 默认开 |
| ② 单进程判词 | `[AOSP:OomAdjuster.java:4063-4091]` `getFreezePolicy()` | 若持 `PROCESS_CAPABILITY_CPU_TIME`（"重要到不该冻"）→ false；若 `isFreezeExempt()` → false；若 `curAdj >= FREEZER_CUTOFF_ADJ` → true |
| ③ 门槛值 | `[AOSP:ProcessList.java:389]` `FREEZER_CUTOFF_ADJ = CACHED_APP_MIN_ADJ` = **900** | 即：只有 cached/empty 才可能被冻 |
| ④ 执行/解冻 | `[AOSP:OomAdjuster.java:4094-4145]` `updateAppFreezeStateLSP()` | `getFreezePolicy` 为真 → `freezeAppAsyncLSP`（`4131-4138`）；为假且已冻 → `unfreezeAppLSP`（`4139-4143`） |

**结论（可直接用于本项目）**：冻结只看 **adj 是否 ≥900**，与包名、是否游戏、是否厂商白名单无关（厂商另有私有逻辑）。所以「把进程 adj 压到 900 以下」就是免冻的唯一通用解；而 adj 由 am 按组件状态算，**没有 ProcessRecord 的 uid 0 daemon 天然不在这个体系里**。

### 2.2 binder freeze

- 开关：`[AOSP:CachedAppOptimizer.java:282]` `DEFAULT_FREEZER_BINDER_ENABLED = true`；`[AOSP:...:286]` binder 回调也默认开。
- 调用点：`[AOSP:...:1493]`、`[AOSP:...:1560]`（普通冻结）、`[AOSP:...:2339]`（binder 冻结路径），超时常量 `[AOSP:...:254]` `FREEZE_BINDER_TIMEOUT_MS = 0`。
- 后果：被冻进程的 binder 事务会被**挂起而不是失败**，所以 app 侧「调用无响应但不报错」是冻住的典型指纹。

### 2.3 解冻时机

- 主路径：adj 掉回 900 以下（收到广播/被 bind/有 Activity 可见等）→ `[AOSP:OomAdjuster.java:4139-4143]`。
- 临时解冻：`[AOSP:CachedAppOptimizer.java:1317,1323]` `unfreezeTemporarily(app, reason[, delayMillis])`。

### 2.4 uid 排除（回答「uid 0 会不会被冻」）

- AOSP 的冻结入口全部挂在 `ProcessRecord`/`ProcessCachedOptimizerRecord` 上（`[AOSP:OomAdjuster.java:4100-4101]`），**没有 ProcessRecord 的进程不参与**：uid 0 由 `app_process`/init 侧起的进程、uid<10000 的系统进程都不在这个集合里。
- 我们真机看到的 `do_freezer_trap` 落在**我方 APK 进程**（uid 10xxx，有 ProcessRecord，切后台后 adj=9xx）——**是 AOSP 机制命中，不是厂商特供**。`[实测]`
- 厂商侧（ColorOS Hans/Athena）是否存在额外冻结/杀进程逻辑：**AOSP 无此代码，闭源，`[未验]`**。

---

## 3. Doze / App Standby / 后台限制（问题一 C）

| 机制 | 依据 | 对我们的影响 |
|---|---|---|
| Doze（idle）进入与维护窗口 | `[文档]` https://developer.android.com/training/monitoring-device-state/doze-standby | 熄屏静止后：网络封禁、Alarm/Job 推迟、wake lock 被忽略（除白名单） |
| App Standby bucket | 同上文档 | 长期不用会被降 bucket → 后台任务/网络更受限 |
| `am set-inactive <pkg> true` | `[AOSP:ActivityManagerShellCommand.java:378]`（解析）、`[AOSP:...:4623]`（usage 文本） | 可**手动把 app 打成 idle 复现问题**，是很干净的实验手段 |
| 电池优化白名单 | `[文档]` 同上（Doze 白名单段） | 只豁免 Doze/standby 的网络与任务限制，**不豁免 freezer、不豁免 lmkd、不代表 CPU 不休眠** |
| Play 政策 | `[文档]` https://developer.android.com/develop/background-work/services/fgs/service-types | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 的用途受限（需核心功能理由），上架走不通；本项目侧载，不构成阻断但要在文档写明 |

**要点**：Doze 白名单常被当成「保活神药」，实际上它管的是「网络/任务能不能跑」，与「进程会不会被冻/被杀」「CPU 会不会 suspend」是三件不同的事。

---

## 4. 合法保活手段与天花板（问题一 D）

| 手段 | 依据 | 天花板 |
|---|---|---|
| 前台服务（FGS） | `[AOSP:ActiveServices.java:783-787]` 用 `SERVICE_FOREGROUND_TIMEOUT_MSG` / `SERVICE_SHORT_FGS_ANR_TIMEOUT_MSG` 计时；`[AOSP:...:502]` 引用 `ActivityManagerConstants.DEFAULT_SERVICE_START_FOREGROUND_TIMEOUT_MS` | 超时未 `startForeground()` → ANR/崩溃；只能把 adj 压到 200，**不解决 CPU suspend** |
| FGS 必须声明 type（A14） | `[文档]` service-types 页；源码侧 `[AOSP:ActiveServices.java:49-51]`（`FOREGROUND_SERVICE_TYPE_MANIFEST/NONE/SHORT_SERVICE`） | 缺 type 直接抛 `MissingForegroundServiceTypeException`；`specialUse` 需在 manifest 填 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明理由（上架审查项） |
| Android 15 对 FGS 的时长限制 | `[文档]` https://developer.android.com/about/versions/15/behavior-changes-15 | 主要针对 `dataSync`/`mediaProcessing` 等类型设上限；**`specialUse` 不在这类硬超时清单里**（仍需申报合理性） |
| `PARTIAL_WAKE_LOCK` | `[文档]` PowerManager 文档；社区实现见 Termux `[Termux:TermuxService.java:102-103,303-320]`（`PARTIAL_WAKE_LOCK` + `WIFI_MODE_FULL_HIGH_PERF` 双锁） | 只挡 CPU suspend，不挡 freezer/lmkd；root 侧更硬的写法是直接写 `/sys/power/wake_lock`（本项目 `DaemonWakeLock` 已用） |
| `svc power stayon` | `[AOSP:PowerCommand.java:41]`（usage）、`:64`（`stayon` 分支） | 改的是全局 `STAY_ON_WHILE_PLUGGED_IN`，**副作用：屏幕不灭**，与「主屏照常用」冲突 → 本机不适用 |
| JobScheduler / AlarmManager | `[文档]` doze-standby 页 | idle 下被推迟/合并，精度不可依赖；`setExactAndAllowWhileIdle` 也有配额 |
| 双进程互拉 | 无 AOSP 机制支撑；Android 8+ 后台执行限制 + 8.1 后台服务限制（`[文档]` 同页背景执行段）使其退化 | **判为不可用**，不写进方案 |

---

## 5. 「进程没死但没帧」的三种成因（问题一 E）

| 成因 | 依据 | 指纹 |
|---|---|---|
| ① 被 cgroup freezer 冻住 | `[AOSP:OomAdjuster.java:4063-4091]` + 内核 `do_freezer_trap` | `ps -A` STAT 含 `do_freezer_trap` 或线程停在该栈；binder 调用挂起不失败；**进程 pid 还在** |
| ② 输出端被摘/表面销毁 | `[AOSP:VirtualDisplayAdapter.java:641-645]`：`mSurface == null` → display `STATE_OFF` | 帧计数停止但进程 CPU 正常；`dumpsys display` 里 state=OFF |
| ③ 应用自己暂停渲染（`onPause`/`surfaceDestroyed`/`onWindowVisibilityChanged`） | Unity/UE 官方文档本轮**未校验**，`[未验]` | logcat 里 fps 归零但无 freezer 痕迹 |

补充：`[实测]` 本轮 BG-22 已出现「虚拟屏帧冻结 + logcat `queueBuffer fps=59.96`」的组合，属**成因 ② 的近亲（输出端不在虚拟屏，而在主屏）**，说明「三条成因必须靠指纹区分，不能只看帧数」。

---

## 6. 同类项目源码对照（问题一 F）

| 项目 | 手法 | 出处 | 可迁移性 |
|---|---|---|---|
| Termux | 服务里同时持 `PARTIAL_WAKE_LOCK` + `WIFI_MODE_FULL_HIGH_PERF`，用 `START_NOT_STICKY` 级别服务 + 通知保活 | `[Termux:app/src/main/java/com/termux/app/TermuxService.java:102-103]`、`:303-320` | 高：与我们 `KeepAliveService` 同构，可补 Wi-Fi 锁（只在需要网络时） |
| AOSP `svc` | `svc power stayon [true/false/usb/ac/...]` 改全局常亮 | `[AOSP:cmds/svc/src/com/android/commands/svc/PowerCommand.java:41,64]` | 低：会让主屏不灭，违反「主板照常用」 |
| Shizuku | `app_process` 起服务：`setsid()` + 重定向 stdio + 切 cgroup（`/acct`、`/dev/cg2_bpf`、`/sys/fs/cgroup`）+ 切 mount namespace 到 init + `--nice-name` 便于自识别 | `[Shizuku:manager/src/main/jni/starter.cpp]`（**本轮未取行号**，按函数名 `start_server`/`switch_cgroup` 引用） | 高：这正是我们 daemon 该有的启动姿势（cgroup 切出去 = 不进 `/apps/`） |
| scrcpy | `--stay-awake` / `--turn-screen-off` | 本轮**未定位到服务端实现**，不作断言 | — |
| 云手机（红手指/VMOS） | 闭源，**无法引证** | — | — |
| VirtualApp 类双开 | io 重定向 + 虚拟 uid，不建真虚拟屏 | 未逐行核对，`[未验]` | 低：解决多开，不解决后台 |

---

# 表 A：谁在杀/冻我们

| 机制 | 触发条件 | 我们是否命中 | 对抗手段（证据等级） |
|---|---|---|---|
| cached-app freezer | `curAdj >= 900` 且非 exempt，freezer 开 | **是**（`do_freezer_trap` 实测） | 让进程不进 am 管理体系（uid 0 daemon）`[已证实:AOSP OomAdjuster:4063]`；或维持 adj<900 的组件态 `[已证实]` |
| binder freeze | 同上 + `FREEZER_BINDER_ENABLED` | 是（同一机制） | 同上；另：daemon 侧不要依赖被冻 app 的 binder 回调 `[推断]` |
| lmkd SIGKILL | 内存压力到水位，按 adj 从 999 往下杀 | 潜在（daemon adj 低则基本不命中） | 给 daemon 低 adj/不进 lmk 名单 `[推断]`；减少常驻内存 `[已证实]` |
| `am_kill "empty"` | 进程 adj=1000（空进程）且无需保留 | 否（daemon 无 ProcessRecord） | — |
| FGS 超时/缺 type | 5s 未 `startForeground`、无 `foregroundServiceType` | 潜在（我方 FGS） | 启动即 `startForeground`、manifest 声明 `specialUse` `[已证实:ActiveServices:783-787]` |
| Doze / App Standby | 熄屏静止、长期未使用 | 是（网络/任务被推迟） | 白名单（仅豁免网络/任务）`[已证实:文档]`；daemon 侧绕开（自己发包）`[推断]` |
| 厂商 Hans/Athena 私有杀/冻 | 闭源 | `[未验]`（上一轮 60s 消失无 lmk 记录） | 只能靠 uid 0 + cgroup 切出 + 心跳留证反推 `[待实测]` |
| 用户/系统 force-stop | 手动、安装更新、`am force-stop` | 是（每次实验都会） | 无对抗（属正常语义） |

# 表 B：本机（A14 / ColorOS / root / 不改主屏）可行方案（按合法性与稳定性排序）

| # | 方案 | 解决表 A 的哪个机制 | 合法性 | 稳定性 | 证据 |
|---|---|---|---|---|---|
| 1 | **root daemon（uid 0，`setsid` + 切 cgroup + 切 mnt ns）持虚拟屏/调度/心跳** | freezer、binder freeze、lmkd、FGS 超时、Doze 网络（daemon 自己发包） | 侧载 root 场景，无 Play 约束 | 最高：完全在 am 体系外 | `[已证实:AOSP OomAdjuster:4063 + Shizuku starter.cpp]`，厂商分发 `[待实测]` |
| 2 | **daemon 侧 `PARTIAL_WAKE_LOCK`（写 `/sys/power/wake_lock`）** | CPU suspend（不是冻结） | root 可写 | 高（要保证 KILL 也能解锁） | `[已证实:项目 DaemonWakeLock + Termux:303-320]` |
| 3 | **app 侧 FGS `specialUse` + 常驻通知** | freezer（app 自身 adj 压到 200）、进程被回收 | 需申报 subtype；Play 政策风险 | 中（ColorOS 仍可能冻 app） | `[已证实:ActiveServices:49-51,783-787]` |
| 4 | 电池优化白名单（`dumpsys deviceidle whitelist +pkg`） | Doze 网络/任务、App Standby | 侧载可用；`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 上架受限 | 中 | `[已证实:文档 doze-standby]` |
| 5 | 双进程互拉 | 无（9+ 已退化） | 合法但不生效 | 低 | `[已证实:文档 背景执行限制]`，**不建议** |
| 6 | `svc power stayon true` | CPU suspend | 改全局设置 | 副作用大（主屏不灭），**与本项目冲突** | `[已证实:PowerCommand:41,64]` |

> 注：表 B 的第 1 条与第 3 条不互斥——daemon 负责「活着」，FGS 负责「让 app 侧还能被叫醒」；不要用 FGS 去保 daemon 的命。

---

## 附：本轮未验清单

1. ColorOS Hans 是否有独立于 AOSP 的冻结/杀进程路径（需 `/sys/kernel/debug` 或 magisk 模块级反查）。
2. 冻结是否也作用于 uid 0 的自起进程（AOSP 无路径，厂商 `[未验]`）。
3. Unity/UE 在 `surfaceDestroyed`/`onPause` 下的停渲染行为（官方文档未校验）。
4. scrcpy `--stay-awake` 的服务端实现位置。
5. Android 15/16 对 `specialUse` FGS 的额外审查口径（仅文档页已校验 200，细则未逐条读）。
