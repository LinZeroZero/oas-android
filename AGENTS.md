# AGENTS.md — 规矩（什么不许做）

> **任何 AI 会话开工第一件事：读本文件 + `PROGRESS.md` + `DECISIONS.md`。**
> **上限：本文件 ≤50 行 · `PROGRESS.md` ≤40 行 · `DECISIONS.md` 每条 1 行。超了就删，不是缩。**
> 只留**结论**，原因下沉 `docs/`；历史交 `git log`；**过时规则立即删**（过时比没有更糟）；新增一条就删一条，总长不变。
> 例外：**不可逆损失**类（boot/vbmeta、装包路径、权限、jar 字节）不受行数限制。详细流程 `docs/WORKFLOW.md`，全量待办 `docs/BACKLOG.md`，历史 `docs/HANDOFF.md`。

## 1. 角色边界（谁都不能越位）

| 角色 | 可以写 | 绝不能碰 |
|---|---|---|
| **主 AI（调度）** | 本文件 / `PROGRESS.md` / `DECISIONS.md` / `docs/HANDOFF.md` / `docs/WORKFLOW.md` / `docs/BACKLOG.md`、开 PR、合并 | 代码、逐行 review、联网搜、动设备 |
| **写手（唯一代码位）** | `app/` `poc/` `tools/`、`git commit/push` | 与另一写手并行改码、未评审就合 main |
| **诊断 / 调研兼评审** | 只读命令、结论、`/tmp/outbox/*.md` | 任何仓库文件、代码、`git add/commit`、动设备 |
| **文档位** | `README.md` `CHANGELOG.md` `THIRD_PARTY_NOTICES.md` | 代码、`docs/HANDOFF.md`、设备 |
| **交接包位** | `docs/handoff/*.md` | 同上，且不得 `git add/commit` |

- **唯一写手原则**：任一时刻只有 **1 个**会话持有代码写手位。
- **4-eyes**：写手不自审、主 AI 不自审；必须由调研评审位按检查表独立出 PASS / CHANGES。
- **并发上限 2**（写手 + 1 个只读位）。

## 2. 技术硬约束（改了大概率连锁崩）

1. **绝不改分辨率 / dpi**：不 `wm size`；物理分辨率任意，识别画布恒 **1280×720@320**。
2. **`server.jar` 字节数不随意变**：当前 **29,253 B**；要重打必须先落一条 `DECISIONS.md`。
3. **不注册开机自启**：不碰 `AlarmManager` / `BOOT_COMPLETED`。
4. **版本矩阵锁死**：Gradle 8.14.3 / AGP 8.13.2（**不能上 9.x**，Chaquopy 17 的 DSK 会静默失效）/ Kotlin 2.3.10 / Chaquopy 17.2.0 / minSdk 26 / compileSdk=targetSdk=35 / arm64-v8a。
5. **flag 写常量名不写魔数**；AGPL 项目（ALAS-AOS）只借鉴思路，不移植代码。
6. **锁只用 PARTIAL 语义**，不抢 `SCREEN_BRIGHT`。

## 3. 检索纪律（先定位，后读）

- **先定位再读**：要「哪里有」用 `grep -rn` / `find` 出清单，不读全文；要读只读命中行 **±30 行**（`read_file_part`）。
- **输出先落文件**：命令输出写文件后 `grep` 关键行，不把原始日志搬进对话；`git` 一律 `git --no-pager … | cat`（裸 `git diff` 进 less 锁死会话）。
- **无关键词可搜**的全局问题（架构、耦合）→ 交子会话读完只回结论，或先落一份说明文件。
- **同一工具连失败 2 次就换路**（例：`visit_web` 拉大文件返回 null → `curl` 落盘再 grep）。

## 4. 流程硬约束

- `main` 受保护（含 admin 也绕不过）：只能 **分支 → PR → 门禁绿 → 用户签发 → squash 合并**。
- push 前过 `bash tools/gate.sh --static`；CI 必需检查 `static-gate`。
- **改了 `app/` 或 `poc/` 就必须同时更新 `PROGRESS.md`**（gate 第⑥项强制；确无状态变化时用 `OAS_SKIP_PROGRESS_CHECK=1` 并在 PR 里说明）。
- 子会话：**先建产物骨架再补内容**（没核到写 `TODO(未核)`）；回执 **≤5 行**，详情写 `/tmp/outbox/<角色>-<任务>.md`；只用主工作区**绝对路径**，不许另建副本。
- **主 AI 不 `read_messages` 读子会话**（会灌 think 链进上下文），只看 outbox + `agent_status`。

## 5. 真机实验收工定序（缺一不算完）

**先确认真响铃 → 再亮屏 → 再把 Operit 拉回前台**；销毁所有 `oas-*` 虚拟屏、停 daemon 与游戏、`accelerometer_rotation` 复位 0、清 `/data/local/tmp` 本轮临时文件。
