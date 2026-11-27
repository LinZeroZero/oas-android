# AGENTS.md — 规矩（什么不许做）

> **任何 AI 会话开工第一件事：读本文件 + `PROGRESS.md` + `DECISIONS.md`。**
> **上限：本文件 ≤50 行且 ≤5 KB · `PROGRESS.md` ≤40 行且 ≤4 KB · `DECISIONS.md` 每条 1 行且 ≤300 B。超了就删，不是缩。**
> 只留**结论**，原因下沉 `docs/`；历史交 `git log`；**过时规则立即删**（过时比没有更糟）；新增一条就删一条，总长不变。
> 例外：**不可逆损失**类（boot/vbmeta、装包路径、权限、jar 字节）不受行数限制。详流程 `docs/WORKFLOW.md`、待办 `docs/BACKLOG.md`、历史 `docs/HANDOFF.md`、**交接包模板 `docs/handoff/README.md`**。

## 1. 角色边界（谁都不能越位）
- **执行位 = 主 AI**：仓库所有文件、`git commit/push`、开 PR、合并，全由主 AI 做；写完不自评。
- **审查位 = 唯一审查对话**：只读命令、结论、`/tmp/outbox/*.md`；不得写仓库、`git add/commit/switch`、动设备。
- **必审（改产物/门禁/规矩）**：`app/` `poc/` `tools/` `vendor/` `patches/` `.github/` `.githooks/` `dist/`、`gradle*` `*.gradle.kts` `setup_android_env.sh` 及任何构建产物；`AGENTS.md` `docs/WORKFLOW.md` `DECISIONS.md`。
- **抽审（只描述状态/历史/证据）**：`PROGRESS.md` `docs/BACKLOG.md` `README.md` `CHANGELOG.md` `THIRD_PARTY_NOTICES.md` `.gitignore` `LICENSE` `docs/(HANDOFF.md|handoff/*|evidence/*|research/*|reviews/*|adr/*)`（类正则，非 glob）；不阻塞合并，但须 gate 机器判据（BG-38）+ 每 5 单全查一次。
- **GitHub 操作走 `tools/pr.sh`**。
- **顺序（C 模式）：改完 → 开 draft PR → CI + 审查位审 → PASS → 转正式 → 用户签发 → 合**；未 PASS 不得转正式、不得合入 main；主 AI 不得自批。
- **并发 ≤2**：主 AI + 1 个审查位（按当场 `free`/`loadavg` 复核）。

## 2. 技术硬约束（改了大概率连锁崩）

1. **绝不改分辨率 / dpi**：不 `wm size`；物理分辨率任意，识别画布恒 **1280×720@320**。
2. **`server.jar` 字节数不随意变**：当前 **29,253 B**；要重打必须先落一条 `DECISIONS.md`。
3. **不注册开机自启**：不碰 `AlarmManager` / `BOOT_COMPLETED`。
4. **版本矩阵锁死**：Gradle 8.14.3 / AGP 8.13.2（**不能上 9.x**，Chaquopy 17 的 DSK 会静默失效）/ Kotlin 2.3.10 / Chaquopy 17.2.0 / minSdk 26 / compileSdk=targetSdk=35 / arm64-v8a。
5. **flag 写常量名不写魔数**；AGPL 项目（ALAS-AOS）只借鉴思路，不移植代码。
6. **锁只用 PARTIAL 语义**，不抢 `SCREEN_BRIGHT`。

## 3. 检索纪律（先定位，后读）

- **先定位再读**：要「哪里有」用 `grep -rn` / `find` 出清单，不读全文；要读只读命中行 **±30 行**（`read_file_part`）；**单次进上下文的原文 ≤400 行，超了只回「行号区间 + 摘要 + 路径」，不回全文**。
- **输出先落文件**：命令输出写文件后 `grep` 关键行，不把原始日志搬进对话；`git` 一律 `git --no-pager … | cat`（裸 `git diff` 进 less 锁死会话）；`>>` 追加前先确认文件末尾有换行（否则新内容会拼到上一行）。
- **无关键词可搜**的全局问题（架构、耦合）→ 交子会话读完只回结论，或先落一份说明文件。
- **同一工具连失败 2 次就换路**（例：`visit_web` 拉大文件返回 null → `curl` 落盘再 grep）。
- **核对非当前分支内容用 `git show <branch>:<path>`**，别读工作区文件（曾误判 3 次）

## 4. 流程硬约束

- `main` 受保护（含 admin 也绕不过）：只能 **分支 → PR → 门禁绿 → 用户签发 → squash 合并**。
- push 前过 `bash tools/gate.sh --static`；CI 必需检查 `static-gate`。
- **完工判据**（缺一不算完，不许自判）：`bash tools/gate.sh --static` PASS + 目标行为真机复现一次且证据路径写进 `PROGRESS.md` + `PROGRESS.md` 已更新。
- **改了 `app/` 或 `poc/` 就必须同时更新 `PROGRESS.md`**（gate 第⑥项强制；确无状态变化时用 `OAS_SKIP_PROGRESS_CHECK=1` 并在 PR 里说明）。
- **`PROGRESS.md` 不写死 PR 号/sha**：读「最近合并」一律 `git log --oneline -3` 现查（D-013、D-017）；写了就必须能在 `git log` 找到，否则 gate 第⑧项 FAIL。
- 子会话：**先建产物骨架再补内容**（没核到写 `TODO(未核)`）；回执 **≤5 行**，详情写 `/tmp/outbox/<角色>-<任务>.md`；只用主工作区**绝对路径**，不许另建副本。
- **主 AI 不 `read_messages` 读子会话**（会灌 think 链进上下文），只看 outbox + `agent_status`。

## 5. 真机实验收工定序（缺一不算完）

**先确认真响铃 → 再亮屏 → 再把 Operit 拉回前台**；销毁所有 `oas-*` 虚拟屏、停 daemon 与游戏、`accelerometer_rotation` 复位 0、清 `/data/local/tmp` 本轮临时文件。
- 收工还要：杀自起后台进程（`logcat -s … &` 等，自查 `pgrep -f 'logcat -s'` 应空）；清 `/sdcard/oas_*`（自查 `ls /sdcard/oas_*` 应空）
