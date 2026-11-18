# Changelog

本文件记录本项目的所有**值得记录**的变更。
格式基于 [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/)，版本号遵循 [SemVer 2.0.0](https://semver.org/)。
本项目处于 `0.y.z` 阶段（API 未稳定），因此 **破坏性变更也可能只递增 MINOR**。

> 纪律：不要把 `git log` 倒进来。只写「对使用者/维护者有意义」的变化，并按 Added / Changed / Deprecated / Removed / Fixed / Security 分类。
> 发布门禁未过的版本一律是预发布（`-bN`），见 `docs/WORKFLOW.md §4.2`。

## [Unreleased]

### Added
- daemon 启动自检（root 域守卫）：父进程不在 app 域、自身 `oom_score_adj <= -900`、自身 memory cgroup 不含 `/apps/`，任一不满足即拒绝执行并写日志
- daemon `--wakelock on|off`：写内核 `/sys/power/wake_lock`（tag `oas:daemon`）持 PARTIAL 语义锁
- reaper 守护子进程：daemon 以任何方式死亡后自动 `wake_unlock`（因 `kill -TERM` 不执行 shutdown hook）
- `docs/adr/0001-daemon-owns-automation.md`：自动化执行体归 root daemon 的架构决策记录
- `kb run <a> <b> <c> <displayId> <reportPath>`：daemon 侧三段计时 + 心跳判读（lines / maxGap / framesΔ）+ txt/json 报告与 `n/4` 判定
- daemon `--cmd <line>` / `--wait <ms>`：一次性执行 socket 命令后退出（不 bind、不常驻），不装 APK 也能验 daemon 行为
- `docs/evidence/` 开始留痕：首批真机证据见 `20261114-2330-BG-19-daemon-kb-run.md`
- `docs/handoff/` 交接包目录：七要素模板 + 两条合格判据，写手换棒的第一动作是读交接包并执行 §7「启动动作」
- **BG-35 App 内实时预览 `oas` 虚拟屏（C-2，PR #40 / #44）**：预览**默认关**、手动点开，App 内 5 fps 量级回显虚拟屏画面；退出预览即清临时文件、**不销毁**虚拟屏；`server.jar` 全程未重打（29,253 B）。四条验收口径真机全 PASS
- **长时挂机检测工具 `tools/hang_check.sh` 真通路（PR #29 / #30 / #31）**：`--connect` 连真 daemon 读运行态 `frames`、心跳文件快路、socket 2 s 口径、`OAS_MAX_DURATION` 硬超时自停
- **外置状态三件套 `AGENTS.md` / `PROGRESS.md` / `DECISIONS.md`（PR #42 / #43）**：规矩 / 位置 / 决定分文件，并接入门禁第⑥项（改 `app/` 或 `poc/` 必须同时更新 `PROGRESS.md`）与第⑦项（状态文件行数上限）
- **BG-34 虚拟屏不可触证据 + BG-33 调研处置口径（PR #36）**；**BG-35 立项调研 `docs/research/`（PR #37，含 ALAS-AOS / AOSP 对照）**

### Changed
- wake lock 实现由 `PowerManager` 改为内核 sysfs（`app_process` 下 `getSystemContext()` 为 null，`getSystemService` 必然 NPE）
- `kb` 判据改为 fail-closed：0 秒段不许空过、缺段标 `skip` 且不算 pass、`n/4` 分母恒 4、`need` 下限取 1（表格显示与判据一致）
- daemon 环境判据不用 cmdline 子串（`su -c` 起 shell 时会合法带包名，会误杀生产路径），改读父进程 cgroup
- BG-21 改为**预实验（pilot）→ 正式测**两段式：先验证「能不能观测到整机 suspend」，再谈 60s 心跳是否停跳
- `docs/WORKFLOW.md §1` 记录已知限制：**会话分组无法由 AI 设定**，直改 Room 库会被 App 运行时写回（杀进程无效），故用 `OAS-` 标题前缀识别接力会话
- docs: 交接包目录只保留最新一份，`docs/handoff/archive/` 已清空（旧包不再归档，历史见 `git log`）；`CHANGELOG.md` 维护权归属「文档 C」
- BG-35 验收口径④由「退出预览即销毁虚拟屏」改为「**退出预览只清临时文件、不销毁虚拟屏**」（PR #41，避免每次开关预览重建屏）
- 长时挂机检测不再用 `--cmd` 假通路，改 `--connect` 连运行中的 daemon；`frames` 取不到即视为通道问题而非画面问题
- 交接包制度落地：主 AI / E / D / 写手四类棒包各自「只留最新一份」，换棒由交接包位执笔、主 AI 只验收与留指针（PR #32 / #33 / #34 / #35 / #38 / #39 / #45）

### Fixed
- `--wait` 由固定 sleep 改为轮询 `kb status`；段窗口用前一个心跳 `ts` 衔接，段首空洞不再被低估
- 文档与实现不一致：ADR-0001 §Decision 第 2 条与 `docs/HANDOFF.md`/`docs/WORKFLOW.md` 的 tag 名同步为当前实现与 `v0.2.0-b1`
- 预览退出后临时文件清不掉、且落点错成 `preview/preview` 目录（PR #44，真机验收发现）：现退出即清空 `cache/preview`，回显「清临时文件 N 个」
- 心跳快路的 13 位毫秒进 mksh 32 位 `$(( ))` 被截成负数，导致快路恒不命中（PR #31）：改按字符串取前 10 位得秒

## [0.2.0-b1] - 2026-11-14

**预发布**：功能已合入 trunk，但发布门禁未通过（四段 4/4 未达成，见 BG-01）。

### Added

- root daemon 心跳：独立线程按固定周期写 JSONL（每拍 `fd.sync()`），提供 `hb start|stop|status`
- socket 协议 `ver:"2"`，`ping` 返回 `hbRunning`，使 app 能判断 daemon 是否真活着
- `DisplayServer.ensureStarted()` 改为「ping 通即复用」，并加 `synchronized(startLock)` 防并发重复拉起
- `KeepAlivePoc` 增加 `HB_STALL_MS=6000` 与 `partialReport()`，用于把心跳空洞量化成数字而不是感觉
- `tools/gate.sh`（支持 `--static`，供 CI 与 pre-push 使用）
- `tools/fetch_vendor.sh`：按 `REF=` 从上游取回 `vendor/oas` 并打 `patches/`
- `docs/UPSTREAM.md`：上游 URL / 版本 / patch 用法 / GPL 说明
- `docs/WORKFLOW.md §4`：门禁拆成**合并门禁**与**发布门禁**两层

### Changed

- **app 不再是驱动器**：验证线程在 B 段起会被 `do_freezer_trap` 线程级冻结，因此调度前移到 daemon 的方向被确认为唯一出路
- daemon 默认不随 app 启动（只在显式 extra 时拉起），即 **ship dark**，合入 trunk 不改变默认行为

### Fixed

- 修正「daemon 约 60 秒后消失」的误判：看错了对象，实为 app 侧 socket 调用阻塞 + 报告只写一次；daemon 侧实测 645 拍 / 644 s / maxGap 1004 ms / 零空洞

### Known Issues

- **BG-01** 四段 4/4 未达成（B 段被冻结），故本版只能是预发布
- **BG-02** C 段（息屏 60 s）从未真正测过，无真数字
- **BG-13** `gate.sh` 的 5 条评审意见未落地（`set -u` 中断风险、固定 `sleep 240`、`wm size` 正则漏检等）

## [0.1.0] - 2026-11-13

阶段 1（移植骨架）与阶段 2 前半的合并结果，仅作为历史基线保留，未单独打 tag。

### Added

- 单 APK + Chaquopy 17.2（CPython 3.13）嵌 OAS
- `cv2` 走「Python shim + OpenCV Java AAR」
- 1280×720 虚拟副屏渲染，主屏不受影响
- `docs/HANDOFF.md` / `docs/PORTING.md` / `docs/BACKGROUND.md` 交接文档
