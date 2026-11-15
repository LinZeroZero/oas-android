# 缺陷与待办（BACKLOG）

> 规则：一条一行，带 ID；关闭时改成 `[x]` 并写明关闭依据（commit / 实测证据文件）。
> 归属方按 `docs/WORKFLOW.md` §1：代码 → A；文档 → C；只读诊断 → B；调研/评审 → D；流程 → 主 AI。

## 一、阻塞 B 路线验收（P0）

- [x] **BG-01 四段拿不到 4/4：B 段起 app 侧驱动器被冻**（决策完成，实现见 BG-17~BG-21）
  - 证据：`/proc/2198/task/2375/stack` = `do_freezer_trap`（线程级冻结，进程 stat 仍 S）
  - 归属：A（改代码）
  - 方向候选：把 phase driver 前移到 daemon（`kb run <a> <b> <c> <reportPath>`），app 只触发+显示
  - 决策：**ADR-0001 Accepted**（`docs/adr/0001-daemon-owns-automation.md`），实现拆成 BG-17~BG-21

- [ ] **BG-02 C 段（息屏 60s）从未真正测过**
  - 因 BG-01 连带阻塞；daemon 心跳在后台无空洞（645 拍/644s/maxGap 1004ms）只是旁证，不是 C 段数据
  - **测法按 ADR-0001 §4 改**：先确认游戏 `displayId:19` 的 SCREEN_BRIGHT_WAKE_LOCK 已释放，再同记 `maxGap` 与 `suspend_stats/success` 前后差值
  - 归属：A

## 二、流程与工具（P1）

- [x] **BG-03 门禁已脚本化** —— `tools/gate.sh` 五项一键 PASS/FAIL
  - 关闭依据：CI `static-gate` 多次 success；本地 `GATE: PASS`
  - 遗留：D 的 5 条评审意见未落地 → 见 BG-13
- [x] **BG-04 基线提交含 B v1 代码**，无法按提交粒度回溯 B 的改动
  - 已缓解：非 SemVer 旧 tag 已删除，改用 `v0.2.0-b1`；从此往后 A 必须小步提交
- [x] **BG-05 HANDOFF §11.7 里 C 的 chat_id 笔误**（`...dbd1d278198e` 应为 `...dbd1c278198e`）
  - 归属：主 AI
- [x] **BG-26 `super_admin:shell` 默认 cwd 无效，`app_process` 启动期 SIGSEGV**
  - 现象：`System_specialProperties → __strncat_chk` 空指针崩，`getcwd()` 返回 NULL；所有 `app_process` 调用必先 `cd /data/local/tmp`
  - `env -i` 不能绕过，反而变成 rc=0 全空跑（更难查）
  - 关闭依据：`docs/HANDOFF.md §十二 已知坑` 已入册 + 交接包 §4 复述（`docs/evidence/20261115-0057-BG-21-pilot.md §0` 为原始发现）
- [x] **BG-27 阴阳师 `.tag0 → .Client` 自行跳 Activity，丢 `launchDisplayId` 落回主屏**
  - 现象：`am start --display N -n <pkg>/.tag0` 返回成功，但游戏内部再跳 `.Client`，新 Activity 不带 `launchDisplayId` → 落回主屏，虚拟屏只剩黑
  - 绕过（已实测）：先 `am force-stop`，再直启 `.Client`；证据 `docs/evidence/20261115-1230-BG-22-vdisplay-game-frames.md`
  - 状态 `[x]`（有绕过方案），但**依赖包内 Activity 名**：游戏版本升级若改名，需重新确认 `.Client` 是否仍存在
  - 归属：B（诊断）/ A4（验证）

## 三、设备侧隐患（P2）

- [ ] **BG-06 `screen_off_timeout=1800000`（30 分钟）疑似被改坏未还原**
  - 归属：需用户点头后由 A 改回 60000
- [ ] **BG-07 陈旧 `server.pid` 可能指向被复用的 pid**
  - A 已加 cmdline 校验（kill 前验证 MAIN_CLASS），风险降为低；收尾时建议清 pidfile
- [ ] **BG-08 `pkill -f` / `pidof` 看不见不代表不存在**（cmdline 匹配会撞到自己的 shell，造成假 pid / 漏判）
  - 归属：A；建议统一用 `/proc/<pid>/cmdline` 遍历 + 排除自身

## 四、待回收的回执（P3）

- [ ] **BG-09 B 已给旋转结论，但 logcat 无写入记录，定责仍为「证据不足」**
- [ ] **BG-10 C 的 README / THIRD_PARTY_NOTICES 改动未复核**
- [ ] **BG-11 D 的 daemon 长存调研结论未回传**

## 五、流程落地实况（P1）

- [ ] **BG-12 用户的 classic PAT 仍未 revoke（安全，最高优先）**
  - 该 token 已出现在对话中，并已用于建仓 / 推送 / 开 PR / 改可见性
  - 要求：GitHub → Developer settings → 撤销；改用 fine-grained、单仓、90 天
  - 归属：用户（主 AI 无法代做）
- [x] **BG-13 `gate.sh` 的 5 条评审意见已落地**
  - L118 `[ "$PASSED" = "$TOTAL" ]` 在 `$TOTAL` 缺失时被 `set -u` 中断，GATE 行不输出
  - L143 固定 `sleep 240`，余量仅 55 秒，易误报
  - L229 正则排除空格，`wm size 1280x720` / `wm size -100x100` 全漏检
  - L221 只扫 `app/src` 与 `poc/displayserver/src`，漏 `tools/`、`*.kts`
  - 关闭依据：PR #4 → `f029b6a`；`gate.sh --static` = `GATE: PASS`，并实测能拦住 `wm size 1280x720`
- [x] **BG-14 服务端强制已生效** —— `static-gate` strict + `enforce_admins=true` + 禁 force push
  - 依据：PR #1 / PR #2 的 `mergeable_state` 均从 `blocked` 变 `clean`
- [x] **BG-15 缺 CHANGELOG** —— 已建 `CHANGELOG.md`（Keep a Changelog 1.1.0）
- [x] **BG-16 文档漂移** —— `docs/HANDOFF.md §11` 与 `docs/WORKFLOW.md` 的旧 tag 名已统一为 `v0.2.0-b1`
  - 归属：C

## 六、ADR-0001 实现拆分（P0，按顺序做，每项一个 PR）

- [x] **BG-17 daemon 环境自检（root 域守卫）** —— 已实现（PR #6）
  - 启动时校验：父进程不在 app 域、自身 `oom_score_adj <= -900`、自身 `/proc/self/cgroup` 的 `memory` 行不含 `/apps/`
  - 判据修正：**不用 cmdline 子串**（`su -c "CLASSPATH=/data/user/0/org.onmyoji.oas.android/..."` 会合法带包名，会误杀生产路径），改读父进程 cgroup
  - 日志双写：`/data/local/tmp/oas-daemon.log` + app cache `server/daemon.log`
  - 实测：生产路径 `envcheck: ppid=25293 parent_memory_cgroup=4:memory:/ → OK`
  - 归属：A；关闭依据：commit `846f134` + `bcd8066`
- [x] **BG-18 daemon 持 PARTIAL 语义 wake lock** —— 已实现（PR #6）
  - 实现方式修正：`PowerManager` 在 `app_process` 下取不到（`getSystemContext()` null → NPE），改走内核 `/sys/power/wake_lock`（tag `oas:daemon`）
  - reaper 兜底：`kill -TERM` 不跑 shutdown hook，会漏释放导致**永不休眠**；已加 `sh` 守护子进程循环 `kill -0` 探活后 `wake_unlock`
  - 四路释放：正常 exit / 服务循环结束 / finally / shutdown hook，外加 reaper 第五道
  - 实测：`held=[oas:daemon]` + 1 个 reaper；`kill -TERM` 后 `lock=[]`、`reaper=0`、`daemon=0`
  - 归属：A；关闭依据：commit `0de9b89`
- [x] **BG-19 `kb run <a> <b> <c> <displayId> <reportPath>`：driver 前移进 daemon** —— 已实现（PR #9 → `753dd9e`）
  - 段循环、读心跳 JSONL、算 maxGap / framesΔ、写 txt+json 报告都在 daemon；`SocketServer` 新增 `exec(String)`，`Main` 新增 `--cmd/--wait`（不起 APK 也能验）
  - 判据 fail-closed：`lines >= max(1, ceil(sec*0.8))` 且 `maxGap <= 5000ms`；0 秒段不许空过、缺段标 `skip` 且不算 pass、`n/4` 分母恒 4（D 的 4-eyes FAIL 已在 `0671407` 返工）
  - 实测：`kb run 5 5 5` → `4/4`；`kb run 0 0 0` → `0/4`；证据 `docs/evidence/20261114-2330-BG-19-daemon-kb-run.md`
  - **未覆盖**：真实虚拟屏下 `frames` 是否递增 → BG-24；C 段真熄屏 → BG-21
  - 归属：A → A2 接力完成
- [ ] **BG-20 app↔daemon 改短连接**
  - 每次请求新建连接 + 超时 + 重连，不假设长连接（freezer 会掐 socket）
  - 归属：A；验收：daemon 重启或 socket 被断开后，app 下一次操作自动恢复
- [ ] **BG-21 C 段真测与数据判读** —— 本轮先做**预实验（pilot）**，过 gate 才做正式测
  - 预实验只回答方法学问题：探针读得到吗（P1）；能不能造出阴性对照让 `suspend_stats/success` 真的 +1（P2）；`--wakelock on/off` 这组对照有没有灵敏度（P3）；60s 够不够、`maxGap` 阈值 5000ms 是否合适（P4）
  - gate 三条：G1 四个探针都有明确值 / G2 至少一条路能让计数增长 ≥1 / G3 `kb run` 在本分支可直接跑且报告落盘。全绿才做正式测；不绿则改探针或提前触发 BG-22 取舍决策
  - **纪律**：预实验数据一律不得写进「通过/不通过」结论，只用于校准方法与阈值；预实验失败也算有效产出（避免跑完 60s 才发现测不到）
  - 归属：A4（执行）+ 主 AI（复核数字、定 BG-22 取舍、写 evidence）
  - **2026-11-15 状态**：pilot 已证伪「灭屏前后 `suspend_stats/success` 差值」判据（`docs/evidence/20261115-0057-BG-21-pilot.md`，G1/G2/G3 全绿）；**官方口径暂不改**，先用复现数据（A4 的 BG-22 实验中 feature off 组）验证新口径「真熄屏 65s → `n/4=4/4` 且 C 段 `maxGap ≤1500ms`」再定案
- [ ] **BG-22 `displayId:19` 的 `SCREEN_BRIGHT_WAKE_LOCK` 定责与取舍** —— 定责已完成（B），**待真机验证 + 取舍决策**
  - **定责修正**：持锁者是 `system_server`（uid=1000 pid=2106），不是游戏自持；`WorkSource{10386 com.netease.onmyoji}` 只是「该 display 上 top app」的标签。同一时刻 `displayId:0` 有同形系统锁（标签 `com.ai.assistance.operit`）
  - 对「主屏能否被用户熄灭」：**不阻断**（超时看 `userActivity`，电源键走 `goToSleep`）→ 原先「假息屏」的担心是假象
  - 对「整机不休眠 / 熄屏后自己复亮」：**真阻断**（SCREEN_BRIGHT 计入 `mWakeLockSummary`，`mHoldingDisplaySuspendBlocker=true`）
  - 取舍结论：保留虚拟屏又不拿这把锁**无公开 API 可解**（改 `VIRTUAL_DISPLAY_FLAG` 无效、改 display power mode 需 system 权限）。唯一可行是主屏熄屏时对虚拟屏 `setSurface(null)` / `release()`，代价是游戏停渲 → **需用户点头**
  - 验证法：`input keyevent 26` 后连查 `dumpsys power | grep -E 'mWakefulness=|displayId:19'` 与 `cat /sys/power/wake_lock`（与 BG-21 一起做，省一次熄屏）
  - **本轮决策（用户，2026-11-15）**：先不改代码，先做基线实测（见 BG-21 预实验），拿到「有屏锁时 60s 的心跳 / 挂起计数」原始数据再决定是否采纳 `setSurface(null)`
  - 归属：B（定责已交付）→ 主 AI（决策）/ A（改代码，若采纳 setSurface 方案）
  - 状态（2026-11-15 实机）：`frames` 探针早已存在、无需新代码；`detach/attach` 已在**亮屏**下验证真停渲/真恢复（`docs/evidence/20261115-1230-BG-22-vdisplay-game-frames.md`）；**熄屏触发链路未验**（`ACTION_SCREEN_OFF` → detach 代码在但没跑过）
   - **2026-11-15 决策（用户）**：**采纳** `setSurface(null)`（主屏熄屏时对虚拟屏停渲），**默认关**；此前「暂不采纳／先不改代码」的表态作废；归属：写手 A4（续棒）
   - **2026-11-15 排期（用户第三次定调）**：E1 熄屏实验**挪后**（必须用户在场辅助），本轮一次也不熄屏；**BG-22 本轮不合 main、不打 tag**，去留等前置清空后一并定。BG-29 已于同日以「无需实现」收尾，故现只剩 E1 这一个前置
   - **E1 判据矩阵（已定稿，开跑前不许改）**：① `frames` 仍增 + 心跳连续（C 段 65/65、`maxGap ≤1500ms`）→ **有效**（detach 有省电收益）；② `frames` 停增 + 心跳连续 → **零收益**（该 revert）；③ `frames` 停增 + 心跳断/daemon 消失 → **不可判**（只记原始值，禁止据此下结论，需先修存活再重测）。采样必须由 daemon 侧落盘，**熄屏期间不轮询 shell**
  - 4-eyes 评审（P0 无阻塞，5×P1 + 3×P2）见 BG-28。
- [x] **BG-23 `--socket` 参数是假参数**（`openServer(String path)` 无视入参，恒 bind `ABSTRACT_NAME`）
  - 后果：① 换个 socket 名起第二实例必报 `Address already in use`；② `sFileSocket` 恒 false，`chmod666` 与文件 socket 分支是死代码
  - 归属：A3（写手位第 3 棒）；验收：`--socket x.a --cmd ping` 与 `--socket x.b --cmd ping` 可并存；不传时行为与现在一致
  - 关闭依据：PR #12 → `507574e`；实测 `oas.a` 与 `oas.b` 可并存、同名报 `Address already in use`；API 35 的 `LocalServerSocket` 只有 `(String)` / `(FileDescriptor)`，文件 socket 路线不可行（同 `docs/BACKGROUND.md` §结论 4）
- [ ] **BG-24 BG-19 的 D 段未做**：唤醒 + 定向点击（默认关），并验真实虚拟屏下 `frames` 是否递增
  - 现状 `frames` 判据只能靠 `displayId=-1` 跑成 `n/a`，等于没验「画面在动」
  - 归属：A（后续棒次）；前置：BG-22 的 `setSurface` 取舍先定，否则熄屏即停渲
- [ ] **BG-25 会话分组无法由 AI 设定，直改数据库会被 Room 写回**（流程/工具缺陷）
  - 现象：`chat_with_agent` 新建会话默认落「子任务」；想改到「OAS安卓端适配」只能靠 UI 手工操作
  - 已试过并失败：设备无 `sqlite3`；proot python3 + sqlite 3.45.1 直改 `chats."group"`（先 `chmod 666` 三件套）→ 当场 `integrity_check=ok`、汇总正确，但 App 运行时把内存旧行整行写回，杀进程重启仍回退（`None 1 / OAS安卓端适配 9 / 子任务 3`）
  - 现状处置（用户决策 2026-11-15）：**不追分组**，靠 `OAS-<角色><序号> <任务>` 标题前缀识别接力会话；需归组时由用户手动移动
  - 归属：工具侧限制（非本项目代码问题）；若 Operit 后续提供设置分组的 API，再回来关掉这条
- [ ] **BG-28 BG-22 合 main 前置：4-eyes 评审遗留（P0 无阻塞，5×P1 + 3×P2）**
  - P1 `poc/displayserver/src/org/onmyoji/oas/server/DisplayEngine.java:162/178` — detach/attach 在引擎级 `synchronized(this)` 内做 binder 调用，同一把锁被 `grab`(:284，含整帧写盘) 与心跳 `framesOf`(:145) 共用 → 心跳可能迟到，被 BG-19 误判「冻住」；修法：binder 调用移出全局锁，或改 entry 级锁
  - P1 `DisplayEngine.java:198` — `onFrame` 无锁，与 `grab`/`detach` 并发读写 `entry.latest` / `hasFrame` / `frames`（均非 volatile）→ 可见性与撕裂；修法：这些字段改 `volatile`，或统一走锁
  - P1 `app/src/main/java/org/onmyoji/oas/android/service/KeepAliveService.kt:131-133` + `:160` — 二次 `onStartCommand` 不带 extra 时不会注销已注册的 receiver → 「默认关」只在冷启动成立；修法：每次 `onStartCommand` 先 `unregister`，再按 extra 决定是否注册
  - P1 `KeepAliveService.kt:196-209` — 先 `list` 再取「第一块」屏 → 多屏时会 detach/attach 错 id；修法：按 name 匹配，或校验只有一块屏才动手
  - P1 `DisplayEngine.java:158-159` — 注释断言 detach 能解除 `mHoldingDisplaySuspendBlocker` / `SCREEN_BRIGHT`，而 evidence 明列该项未验 → 推断写成结论；修法：注释降级为「假设，待验」
  - P2 `docs/evidence/20261115-1230-BG-22-vdisplay-game-frames.md:41` — 「跳转 Intent 未带 launchDisplayId」是推断（未抓 intent）；改为标注「推断」
  - P2 `KeepAliveService.kt:180` — `registerReceiver` 未带 `RECEIVER_NOT_EXPORTED`（系统广播可豁免，但吃 lint）
  - P2 `DisplayEngine.java:150` — detach 重复调用仍回 true，日志误导；建议已摘除时回 false
  - 归属：写手 A4（合 main 前统一返工）；验收：8 条逐条落地 + `tools/gate.sh --static` 仍 PASS + 亮屏 detach/attach 复测不回归
> 追加背景：本轮不改这些代码 —— BG-22 去留取决于熄屏实验（未做），现在改会让 evidence 与 commit 不再一一对应；故一律作为**合 main 前置**。
   - **2026-11-15 D 的 4-eyes 复核：`REVIEW: PASS`（无崩溃级阻塞；合 main 前建议修 1–4）**，行号已按当前文件重定位（BACKLOG 上面引用的旧行号作废）：
     - ① 成立 — `DisplayEngine.java:380/396` 的锁覆盖 `:386/:402` 的 binder 调用，与 `grab:284`、`framesOf:363` 共用；修法：先取 `final VirtualDisplay vd`，锁外 `setSurface`，或独立 `sSurfaceLock`
     - ② 成立 — `DisplayEngine.java:198` `onFrame` 无锁写 `frames/latest/hasFrame`（`Entry` 无 volatile）；修法：三字段 volatile + `latest` 双缓冲换指针
     - ③ 成立 — `KeepAliveService.kt:131` 只判 `==1`，`:160` `registerSurfaceReceiver` 无 else 注销；修法：`else unregisterSurfaceReceiver()`
     - ④ 成立 — `KeepAliveService.kt:196/:206` 取首个 `displayId`，**mirror 并存时会摘错屏**；修法：按 `list` 的 `name` 匹配或校验仅一块
     - ⑤ 成立 — `DisplayEngine.java:377` 注释把 `mHoldingDisplaySuspendBlocker` 写成实测；修法：降级为「推断/待验」，与 `evidence:69-75` 对齐
     - ⑥ 成立 — `KeepAliveService.kt:180` 缺 `RECEIVER_NOT_EXPORTED`（系统广播可豁免，但 A14 lint 报）
     - ⑦ **PASS** — 重复 detach 回 true 属幂等语义，仅建议加 `"already":true` 区分
     - ⑧ **新发现（P2）** — detach 期间若 `destroy()/releaseAll()` 已 release，`attach` 会拿已释放的 `Surface` 调 `setSurface` → 异常被 catch 吞掉、返回 false（不崩但静默失败），建议加 `entry.released` 判定；另 `SCREEN_ON` 广播在 app 被冻时投递可能延迟 → 亮屏后 attach 迟到（待实测）
     - 判定：`vdisplay_detach` 若改成**默认开**，①②③④ 必修；若维持默认关，可在合 main 前只做 ⑤⑥（文档/告警级）
   - **流程事件（须留痕）**：D 在取行号时执行了 `git checkout 656ec1a`（detached HEAD），7 秒后自行切回。主 AI 已按磁盘实况复核：HEAD 回 `f03e928`、`git status -sb` 无改动、`git reflog` 两条记录在案（`06:51:31` 切出 / `06:51:38` 切回），**未丢文件**。禁令重申：子 AI 一律不得 `checkout`/`reset`/`merge`/`rebase`
- [x] **BG-29 daemon 启动姿势应向 Shizuku 看齐：setsid + 切 cgroup + 切 mnt ns** —— **已关闭：实测前提不成立，无需实现**
  - 依据：`docs/research/20261116-android-background-keepalive.md` 表 B #1；Shizuku `manager/src/main/jni/starter.cpp`（`start_server`/`switch_cgroup`，未取行号）
  - 目的：让 uid 0 daemon 彻底离开 `/apps/` cgroup，避开 cached-app freezer 与 lmkd 波及（AOSP 侧目前「无 `ProcessRecord` 即不参与冻结」`OomAdjuster.java:4063-4091`，但 ColorOS 私有逻辑 `[未验]`）
  - 验收：daemon 起来后 `cat /proc/<pid>/cgroup` 不在 `/apps/`；后台静置 10 分钟后 `ps` 仍在且 `frames` 仍可读
   - 归属：A4（写手）/ B（诊断）；前置：BG-22 取舍先定
   - **关闭依据（2026-11-15 实测，`docs/evidence/20261115-1452-BG-29-cgroup-probe.md`）**：uid 0 daemon 六行 cgroup **全在 `/`**（`5:freezer:/` `4:memory:/` `3:cpuset:/` `2:cpu:/` `1:blkio:/` `0::/`）、`oom_score_adj=-1000`、ppid=`/system/bin/sh`；对照 app 是 `0::/uid_10252/pid_5207`（per-process 组，内含 `cgroup.freeze`）。本机**无 `/dev/cg2_bpf`**、`cgroup.controllers` 为空、`/sys/fs/cgroup` 下 312 个 `uid_*` 无 `apps/` → Shizuku 式 `switch_cgroup` 在本机是 no-op
   - **判据修正（重要）**：原验收「`cgroup` 不在 `/apps/`」不可用 —— v1 的 `freezer:` 行对 app 与 daemon 都显示 `/`（零区分度），只看 `memory:` 行也只能证明 memory 归属。**正确判据是 `0::` 行是否形如 `/uid_x/pid_y`**
   - 仍值得抄、但与 BG-29 动机无关的项（`setsid` + 父进程退出 + `--nice-name`）→ 归入 BG-30 一并处理
- [ ] **BG-30 启动自检覆盖面不足：只读 `:memory:` 一行，漏掉 v2 per-process 组**
   - 现状：`poc/displayserver/src/org/onmyoji/oas/server/Main.java:180-186` 只用 `memoryCgroupOf()` 判断「自己是否在 `/apps/`」
   - 后果：若 daemon 被 app 域进程 fork 起、memory 恰为 `/` 而 v2 行在 `/uid_x/pid_y`，自检**会放行**，随后静默被冻（正是自检要防的失败）
   - 修法：主判据改「`0::` 行必须是 `/`」，`memory:` 行降为附带日志；顺带补 `setsid` + `--nice-name`（可识别进程名，便于 `ps` 自检）
   - 验收：单测/脚本断言三种 cgroup 文本（全 `/` / `/apps/uid_x` / `/uid_x/pid_y`）判定正确；`tools/gate.sh --static` PASS
   - 归属：A4（写手）；前置：无（不依赖熄屏实验），但**本轮不动代码**
