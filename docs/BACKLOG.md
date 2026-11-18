# 缺陷与待办（BACKLOG）

> 规则：一条一行，带 ID；关闭时改成 `[x]` 并写明关闭依据（commit / 实测证据文件）。
> 归属方按 `docs/WORKFLOW.md` §1：代码 → A；文档 → C；只读诊断 → B；调研/评审 → D；流程 → 主 AI。

## 一、阻塞 B 路线验收（P0）

- [x] **BG-01 四段拿不到 4/4：B 段起 app 侧驱动器被冻**（决策完成，实现见 BG-17~BG-21）
  - 证据：`/proc/2198/task/2375/stack` = `do_freezer_trap`（线程级冻结，进程 stat 仍 S）
  - 归属：A（改代码）
  - 方向候选：把 phase driver 前移到 daemon（`kb run <a> <b> <c> <reportPath>`），app 只触发+显示
  - 决策：**ADR-0001 Accepted**（`docs/adr/0001-daemon-owns-automation.md`），实现拆成 BG-17~BG-21

- [ ] **BG-34 虚拟屏上的游戏 activity 是 PAUSED，`input -d <displayId>` 不投递**（阻塞 P0-A 的 D 段判据）
  - 实测（2026-11-18，证据 `docs/evidence/20261118-1520-BG-32-p0a-precondition.md` §4B）：display 38 `mCurrentFocus=null`、`mFocusedApp=null`；游戏 `Task{#933} visible=false visibleRequested=false`、`mLastPausedActivity=.Client`；`tap 800 530`／`keyevent 4`／`tap 640 120` 三次探针均无像素变化、`frames` 恒 544
  - 影响：P0-A 的 D 段「唤醒 + 定向点击」无法执行；也无法远程点掉游戏内弹窗
  - 归属：写手（先只读定位：是 display 焦点问题 / activity 生命周期问题 / 还是 `input -d` 在本机不可靠）+ D 复核
  - ⚠ 本轮实测与「PoC 期 `input -d <displayId> tap` 可用」的旧记录冲突（旧记录出处待核）→ **以本轮实测为准**，需重新定位，不得直接引用旧结论

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
- [x] **BG-11 D 的 daemon 长存调研结论未回传** —— 关闭依据：该调研（`docs/research/20261116-android-background-keepalive.md`）**从未入库**，结论以入库文档为准，处置见 **BG-33**

## 五、流程落地实况（P1）

- [x] **BG-33 旧 D 自述的两份 `docs/research/*` 调研从未入库** —— 处置（主 AI，2026-11-18）：**认丢、不重做**
  - 跟丢的两份：`docs/research/20261115-android-vdisplay-background.md`、`docs/research/20261116-android-background-keepalive.md`
  - 实查（2026-11-18，main = `6fe46aa`）：`docs/research/` 目录**不存在**；`git log --all -- docs/research` 空；`git log --all --diff-filter=A` 里 `research|vdisplay|keepalive` 只命中 `KeepAlivePoc.kt` / `KeepAliveService.kt` / `poc/frames/vdisplay_*.png` → 从未入库，不是被删
  - 不重做的依据：两份主题的结论已落在入库文档——虚拟屏／出帧／freezer 判定见 `docs/BACKGROUND.md` §二/§三/§五/§七（含 AOSP 14 两条事实与 7 项判据实测）；后台保活见同文件 §九 第 1 条（Python 识别线程仍会被 freezer 冻 → 前台服务 + WakeLock）与 `docs/PORTING.md` §6；实现侧见 `app/src/main/java/org/onmyoji/oas/android/service/KeepAliveService.kt`
  - 若日后某条结论必须引「原文」：先查上述文件；确实缺才派 D 重做（联网重写，两份各开一个 PR，不得与代码 PR 混）
  - **2026-11-18 补记**：`docs/research/` 目录已在本日建立（首个文件 `docs/research/20261118-inapp-virtualdisplay-preview.md`，属 **BG-35** 调研）→ **跟丢的那两份仍不重做**，本条结论不变；日后新增 research 文件一律按「谁调研谁给结论、主 AI 落盘」办
  - 归属：主 AI（记账）；重做时 → D

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
  - **2026-11-15 决策（用户）**：**采纳** `setSurface(null)`（主屏熄屏时对虚拟屏停渲），**默认关**；此前「暂不采纳／先不改代码」的表态作废；归属：写手 A4（续棒）
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

- [ ] **BG-32 长时挂机没有可复现的检测手段**（工具链路已真机验证，**正式 14 分钟真熄屏长跑待约时点**）
  - 交付：`tools/hang_check.sh`（现 776 行，写手 A5 实现；D 三轮 4-eyes 均 PASS，最新 2026-11-17 `20fa1a1`）
  - 形态：Android root shell（toybox `sh`），`start` / `status` / `stop` / `help`；只读 `/proc`、`/sys/power/wake_lock`、`dumpsys`；不改系统设置、不装应用、不注册自启动；自己不建屏、不拉游戏
  - 每拍 15 列（`|` 分隔）：`ts|awake|ndisp|did|dpid|djiff|gpid|gjiff|sfjiff|wl|load1|memfree|frames|dstate|gstate`
  - 判定：样本<3 或 <60s → 「样本太短」；daemon/游戏 有样本 pid=0 → FAIL；虚拟屏=0 → FAIL；`D`/`T` 状态样本 ≥25% → FAIL（疑似 cgroup freezer 冻住）；帧数采到但无增长 → FAIL；CSV 列错位 → FAIL；否则 PASS（CPU 全无增长记 WARN）
  - 防坑（本轮评审逼出来的）：kill 前用 `/proc/<pid>/cmdline` 校验身份（防 pid 复用误杀）、stop 后清 `sampler.pid`（幂等）、`wl` 剔 `|` 防列错位、awk 用 `app[_]process` 写法防自我命中
  - 已验证：阴性对照（无 daemon/游戏/虚拟屏 → FAIL 且逐项点名）、陈旧 pidfile 不误杀（`sleep 300` 存活）、重复 stop 幂等、`sh -n`/`bash -n` 通过
  - **已知限制**（不阻塞，来自 D 的 nits）：① 游戏若跑在分身子进程（`pkg:xxx`），`pidof` 精确名取不到 → 会以 FAIL 形式暴露（不是假 PASS），必要时加 `ps` 前缀兜底；② 若将来 daemon 改用 `--nice-name`，cmdline 会被改写成单一名字 → 身份校验全部失败、全样本判 FAIL；③ 小时级真实长跑的 PASS 路径尚未跑过
  - 用法：`su -c 'sh tools/hang_check.sh start'` → 锁屏挂机 → `su -c 'sh tools/hang_check.sh stop'`
  - **2026-11-17 增量（PR #29 / #30 / #31，main = `20fa1a1`）**：
    - **frames 真通路**：`--cmd` 是假通路（本进程 `new SocketServer` 就地 dispatch，`DisplayEngine` 空表 → 恒回 `no such display`，上一轮 47.8h 长跑 819 次 100% `n/a` 的真因）→ 新增 `--connect <抽象名>`（`LocalSocket` ABSTRACT）；socket 超时统一 **2000ms**（对齐 `DisplayServer.rawCommand(cmd,2000)`，且必须 < 5s 采样间隔）
    - **心跳快路**：读 daemon 写的 `keepalive_hb.jsonl`（末行 `ts`≤5s 且 `displayId`、`pid` 与当前 daemon 一致才采信），否则回落 `--connect`
    - **硬超时自停** `OAS_MAX_DURATION`（默认 900s，0=不限）+ `OAS_SELF_STOP=1` 让 `do_stop` 跳过 kill 段（防自停杀父进程、留半行 CSV → `badcols` 假 FAIL）
    - **真机坑（已修）**：Android `/system/bin/sh` 是 mksh，**算术 32 位**；`hts/1000`（13 位 ms）先被截成负数，`age≈1.79e9`，快路恒不命中。改为 `${#hts}` 校 13 位 + `hsec=${hts%???}` 字符串截秒 —— 以后任何 13 位毫秒都不能进 `$(( ))`
    - **已验证（亮屏不熄屏冒烟，两轮）**：① 3 分钟轮 `frames 505→5289` 递增但**快路 0 命中**（正是它暴露了 32 位问题）；② 90s 轮 `frames 355→2474`、**每个采样拍都出数**（快路生效，节流仅 3 拍）；两轮自停都正常、CSV 恒 15 列。证据 `docs/evidence/20261117-2339-BG-32-frames-smoke.md`、`docs/evidence/20261117-2344-BG-32-hbframes-smoke.md`
    - **仍待**：正式 14 分钟真熄屏长跑（A 前台 120s + B 回桌面 60s + C 熄屏 600s + D 唤醒点击 15s）= PASS 路径唯一验收口径；「首拍 pid=0 即 FAIL」是否放宽为前置启动期豁免，等长跑数据再定（不提前动判据）
  - **2026-11-18 增量（前置搭建实测，证据 `docs/evidence/20261118-1520-BG-32-p0a-precondition.md`）**：载体按文档序列搭起来了（daemon `21114`、虚拟屏 `oas` = displayId `38`、游戏拉上 38），但**长跑仍不能开**：
    - ①游戏停在「客户端版本已更新，需下载额外资源 **27.7MB**，建议 wifi」静态弹窗（grab 转图读出；本机无 Wi‑Fi、默认 `MOBILE NR`）→ 画面不动，`frames` 冻在 544（292→544 后无增长；`grab` 三次同帧 `mean=126.39`、`blackRatio=0.0000`）→ 冻结是**结果**，不是出帧通路坏
    - ②display 38 上游戏 `Task{#933}` = `visible=false visibleRequested=false`、`mLastPausedActivity=.Client`、`mCurrentFocus=null`；三次输入探针（`tap 800 530` / `keyevent 4` / `tap 640 120`）均无像素变化、`frames` 恒 544 → **`input -d 38` 未投递**，D 段「唤醒 + 定向点击」**当前不可验** → 见 **BG-34**
    - 本轮**未熄屏、未跑 `hang_check start`、未点「确定」**（27.7MB 走移动数据，需用户点头）
  - 归属：A5（代码）/ D（评审）/ 主 AI（签发）/ 用户（真机长跑配合）

## 七、新需求立项（P1，先立项再排期）

- [x] **BG-35 App 内实时预览虚拟屏（in-app live preview）** —— 2026-11-18 立项；**C-2 已结案：代码 PR #40 = `a531ba4` + 缺陷修复 PR #44 = `e8b2a81`，真机四条口径全 PASS（2026-11-18 22:35）**；C-1 / C-3 未开（C-1 押后，见本条末）
  - **来源**：用户 2026-11-18 提供 `https://github.com/Shinarin/ALAS-AOS` + 内嵌虚拟屏截图，明确「这个项目就可以直接在软件里显示虚拟屏」；计划模式拍板口径 **q1=(b)「要 OAS 也能在 App 里内嵌实时预览」**
  - **现状实查（2026-11-18，main = `e4d3e3e`）**：`app/src/main` 全 28 个文件 grep `SurfaceView|TextureView|SurfaceTexture|ImageReader` **命中 0** → App 内**零**预览能力；当前唯一出图路径是 daemon `grab` → raw → 转 PNG（离线单帧，不是实时）
  - **已有基础**：daemon 侧 `ImageReader.newInstance(w,h,RGBA_8888,3)` 早已在跑（`poc/displayserver/.../DisplayEngine.java:100-101`），`grab` 就在读它 → **预览不需要重建捕获通路**，只缺「帧 → App → 显示」这一段
  - **调研文档**：`docs/research/20261118-inapp-virtualdisplay-preview.md`（ALAS-AOS 通路 / AOSP 七 flag 语义与强制前置 / 注入路径差异 / 许可证 / 未核到清单）
  - **与 BG-34 的关系**：BG-34 是「能不能操作」（不可触 + activity PAUSED），BG-35 是「能不能看见」。两者共享 flags 面，但**验收口径独立**，任一通过不代表另一个通过，不得互相勾掉
  - **四条验收口径（立项即锁定，改动须用户点头）**：
    1. App 内预览区显示 `oas` 虚拟屏，**肉眼可见画面在动**
    2. 预览期间**主屏可正常使用**，不掉帧、不黑屏
    3. 预览更新时 daemon `stats` 的 **`frames` 单调增长**
    4. **不引入开机自启**、不改分辨率/系统设置；退出预览＝停渲染线程 + 清临时 raw，**不销毁任何虚拟屏**（2026-11-18 用户批准修正。原写「收工 `DisplayInfo{"oas"` 归 0」是照 C-2 早期 `mirror` 方案（会自建屏）写的；现实现只用 `grab` 抓现有画面、**不自建屏**，照原口径会在用户挂机时点开预览再关掉即 destroy 掉挂机屏 —— 事故级，故改）
  - **归途**：主 AI 立项/验收 → 写手实现（唯一代码位 A6）→ D 4-eyes 评审 → 真机验收（熄屏类须用户定时点、收工先确认铃响再亮屏再拉 Operit 前台）
  - **排期候选（主 AI 建议，未拍板）**：**C-1** 只改 flags 重打 jar（判据：`input -d <id> tap` 让 `frames` 变化且 `mCurrentFocus` 非 null）→ **C-2** App 内预览（判据＝上面四条）→ **C-3** 预览与主屏共存调优（含与 BG-22 `setSurface(null)` 的冲突：空 Surface 即 `STATE_OFF`，预览中不可停渲）；每项一个 PR，不合并铺开
  - **约束**：ALAS-AOS 为 **AGPL-3.0**，**只按 AOSP 常量名自行实现，不移植其任何文件**；flag 数值一律写常量名，不写魔数（D 报的 `0x1fd47` 是推算且与按位累加不符）
  - **2026-11-18 增量（C-2 落地，PR #40 = `a531ba4`）**：
  - 实现：**不复用 `mirror`**（`SocketServer.cmdMirror` 硬编码 `Thread.sleep(1200)` + 每帧建屏/销毁），改「daemon `grab` 写 raw → App `Bitmap` → `SurfaceView` + `lockCanvas`」；5fps、单帧 1280×720×4 = 3,686,400 B；预览**默认关**、手动点开；`server.jar` 全程保持 **29253 B**（未重打、未动 flags）
  - 新增文件：`preview/PreviewFrameSource.kt`（取帧 + RGBA→Bitmap 解码，buffer 复用、`rowStride` 校验）、`preview/PreviewRenderer.kt`（渲染线程 + 代际校验 + Bitmap 复用）、`preview/PreviewSession.kt`（屏/释放复核工具，当前无调用方）、`MainActivity` 的 `PreviewSection`
  - 4-eyes 两轮（D2）：首轮 **CHANGES 4 条阻断** → 修复三笔（`461db0a` 守卫 / `7ed6a2e` 代际+buffer+stride / `3a518c3` 退出不销毁屏）→ 二轮 **PASS**
  - 阻断里有一条**事故级**：`onDispose` 无条件 `releaseAll()` → 用户挂机时点开预览再退出即 destroy 掉挂机屏。已改为「预览只读不建屏，退出只停渲染 + 清临时 raw」
  - 构建证据：`assembleDebug` rc=0；`app-debug.apk` = 48,583,631 B
  - **真机验收（已跑完）**：2026-11-18 21:08–21:14 首轮 **3 过 1 挂**（口径④「清临时 raw」挂：回显恒 `0`、raw 落成 `preview/preview/` 嵌套）→ PR #44 修复（清目录改 canonical 路径 + `stop()` 先置空再 `join(4500)` 且挪 IO）→ 22:25–22:35 复验**口径④ PASS**：回显「清临时文件 **3 个**」、`cache/preview` 关后 `total 0`（含旧嵌套目录一并清掉）、`oas` 屏 displayId 42 未销毁、daemon 未停。证据 `docs/evidence/20261118-2235-BG-35-c2-recheck.md`
  - **观测口径修正**：判「屏还在不在」不能直接拿 `dumpsys display | grep -c 'DisplayInfo{"oas"'` 跟 1 比 —— 运行期实测为 **2**（同一屏打印 `mBaseDisplayInfo` + `mOverrideDisplayInfo` 两行），**按 displayId 去重后才是 1**；清场后应为 0
  - **未开**：C-1（flags 重打 jar，**押后**且必须从头重做 —— 上一棒越线做过已整体回滚，未评审未合并）、C-3（预览与主屏共存，含与 BG-22 `setSurface(null)` 的冲突）
- 归属：主 AI（立项/排期/验收）→ 写手（实现）→ D（评审）
