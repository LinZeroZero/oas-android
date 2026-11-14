# 协作与 Git 流程（本项目实际执行的版本）

> 目的：让「多个 AI 会话 + 用户」协作时有和正规项目组一样的纪律：分支隔离、小步提交、
> 构建门禁、代码评审、可回滚、证据留痕。所有会话开工前先读这篇。

## 1. 角色与边界

| 角色 | 会话 | 可以做什么 | 绝对不可以做什么 |
|---|---|---|---|
| 主 AI（调度） | 当前对话 | 建会话、投递任务、`git diff` 只读评审、汇总、改 `docs/HANDOFF.md` 与 `docs/WORKFLOW.md` | 改 `app/`、`poc/displayserver/` 等代码 |
| 写手（**唯一代码位，可接力换人**） | `OAS-A 写手（唯一代码位）` 及其接力会话（A2/A3…） | 代码、构建、装机、真机验证、`git commit` | 与另一个写手会话**并行**改代码；换工作区；未经评审直接合 `main` |
| 诊断 B | `OAS-B 旋转定责（只读）` | 只读命令、整理证据 | 写任何文件、装有副作用的设置 |
| 文档 C | `OAS-C 文档` | `README.md`、`THIRD_PARTY_NOTICES.md` | `app/`、`HANDOFF.md`、碰设备 |
| 调研 D（**兼任评审**） | `OAS-D daemon 长存调研` | 联网调研；只读审 `git diff <tag>...HEAD`、按 §5 检查表出 PASS/FAIL | 写代码、碰设备、改任何文件、`git add/commit` |

**唯一写手原则**：任一时刻只有 **1 个**写手会话持有代码写手位。B/C/D 发现要改代码，只交结论给写手。

**会话命名与分组**：本项目（OAS 适配安卓端）的**所有**子 AI 会话——包括每一棒继任写手 A2/A3/…——**一律放在已有分组「OAS安卓端适配」里，不新建分组**；标题统一为 `OAS-<角色><序号> <具体任务>`，例如 `OAS-A3 写手（BG-23 --socket 真参数）`。新会话建完**必须立刻改名**——自动生成的标题（如 `KbRun心跳报告分段统计改造`）等于没命名，接力时找不回是谁。

**写手接力（relay）：位置可以换人，但不能并行**

并行写同一套代码与「一个会话从 0 写到底」是两个极端，都错：

- **并行错的理由**：强依赖任务不适合多 agent 并行（Anthropic 在 multi-agent 研究里明确点出这一点，Cognition 更直接反对多 agent 共写同一代码库）。对本项目更硬的理由是物理的——**所有会话共用同一个工作区目录 = 同一个 `.git`**，两个写手同时切分支/提交会互相踩；即便分支隔离，两边改同一批文件也只会产出「编译过但语义冲突」的结果，评审成本高于串行收益。
- **一个会话写到底也错**：上下文长到后段会退化——实测表现是**被输出上限反复截断**（A 到 97% 时每轮只剩 `[Empty]`）、开始忘纪律、把已推翻的结论又写回去。

正确姿势是**接力**：每完成一个**可验证的小目标**就 `commit + push` 收尾、换棒给下一个会话。

**交棒阈值 = 上下文压缩是否即将触发，不是一个拍脑袋的 token 数。** 本机对话模型的实测配置（`operit_editor:get_context_summary_config`，function=`CHAT`）是：`context_length=256`（256K 词元）、`summary_token_threshold=0.7`、`enable_summary=true`、`enable_summary_by_message_count=true`（20 条）。也就是说：

- 阈值 = **256K × 0.7 ≈ 179K 词元**，或消息条数先到 20 条触发总结；
- **压缩一旦发生，写手就丢了前半棒的关键约束**（本项目实测：A 在上下文近满时每轮只剩 `[Empty]`、开始忘纪律、准备删掉实测存在的 `OmanHansManager`）；
- 所以判据是**「接近触发压缩 / 已经触发过一次压缩」就交棒**，而不是「感觉还早，再写两个任务吧」；
- 每棒开工前主 AI 记下当前 `inputTokens`，接近 179K（或已触发一次总结）即按下面三步换棒。

**交棒三信号**（不需要懂代码也能判断）：

1. 磁盘上有新 commit 且 `git status --porcelain` 为空 —— 说明一个可验证的小目标已经落地；
2. 该会话已连续被输出上限截断 ≥ 2 次 —— 单棒产出到极限；
3. 上下文接近窗口上限，或开始重复问同一件事、忘记已定纪律。

**交接固定三步**（缺一不可）：

1. **写手**：`git add <明确文件列表>` → `commit` → `push`，回执一行 = 新 sha + 本轮验证结果（编译/gate/真机）+ 未做项；
2. **主 AI**：在 `docs/HANDOFF.md` 追加「接力点」小节（分支名、sha、已完成、下一步、已知坑）；
3. **主 AI**：在新会话里**只贴这一小节 + 纪律摘要**，不贴历史对话——把旧上下文整段搬过去等于换个人继续背同样的包袱。

**交接期间的禁令**：换棒过程中不允许两个会话同时在同一个工作区操作 git；主 AI 在写手持有分支期间不切分支、不改代码目录。

**四眼原则（4-eyes）**：A 写完不自审，主 AI 也不自审——由 D 按 §5 检查表独立出 PASS/FAIL，主 AI 只执行「按意见合并或打回」。

## 2. 分支模型（与「合并 ≠ 发布」）

- `main` = **trunk（持续集成主线）**：必须始终可构建、`static-gate` 绿。
  **合进来 ≠ 发布**；验收状态看 tag。
- `feat/<主题>` / `fix/<主题>` / `docs/<主题>`：**短命分支**，一个任务一个，合完即删。
  禁止长期挂着的 PR——「等完美再合」是行业反模式 Stale Branches，会堆出集成悬崖。
- **tag = 发布标记**，用 SemVer：
  - `vX.Y.Z-bN` = 预发布（功能已合入，但发布门禁未过）
  - `vX.Y.Z` = 正式（发布门禁全过）
- 风险性改动优先做成「默认关」（ship dark）：只有显式触发才生效，
  这样合进 trunk 不改变默认行为（例：daemon 只由 `--ei keepalive 1` 这类 extra 拉起）。

## 3. 提交纪律

- **一次提交 = 一个能编译通过的小步**，不提交半成品（提交前必须 `./gradlew :app:assembleDebug` 通过）
- 提交信息用约定式前缀：
  - `feat(hb): daemon 新增 hb start/stop/status`
  - `fix(harness): 心跳断流判定改用 JSONL ts`
  - `test(keepalive): 四段验证落盘渐进报告`
  - `docs(process): 引入 git 流程`
  - `chore(build): server.jar 重编`
- 提交信息末尾带一行实测/构建信息，例如：
  `build: OK / APK md5 fa604011… / 48,891,504 B`

## 4. 两层门禁（合并门禁 ≠ 发布门禁）

### 4.1 合并门禁（每次合入 main 必过；CI 服务端强制）

1. `bash tools/build_displayserver.sh` 无错（改了 Java 才需要）
2. `./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`，记下 APK 大小与 md5
3. `bash tools/gate.sh --static` → `GATE: PASS`（语法 + 硬约束 + AGP 版本）
4. CI 检查 **`static-gate` 绿**（main 分支保护强制，`enforce_admins` 打开，主 AI 也绕不过）
5. 提交信息符合 Conventional Commits

### 4.2 发布门禁（决定 tag 是 `-bN` 还是正式版）

1. 装机后 `--ei cv_selftest 1` → **32/32**（改了 cv 相关才需要）
2. 四段验证 **4/4** 且 C 段心跳空洞 **< 5000ms**（改了保活/心跳才需要）
3. 收尾干净：`am force-stop`、daemon 0 个、`*.raw` 删净、Operit 回前台
4. 结果写 `docs/evidence/YYYYMMDD-HHMM-<主题>.md`

**判定**：合并门禁不过 → 不许合入 main；发布门禁不过 → 只能打 `-bN` 预发布 tag，不许打正式版。

## 5. 评审方式（主 AI 做）

**锚点用 tag，不用 `main`。** 因为 feature 分支可能就是从同一个提交切出来的，`main...HEAD` 会显示空 diff。

- 评审只看增量：`git diff <上一个已验证 tag>...HEAD --stat`，需要细节再加 `-- <路径>`
  例：`git diff v0.2.0-b1...HEAD --stat`
- 评审关注：是否动了硬约束（分辨率/画布/自启/AGP 版本）、socket 超时是否仍 ≤2s、
  是否又引入 app 侧心跳、异常路径是否仍能落盘、有没有往仓库里塞大二进制
- 通过才：`git checkout main && git merge --no-ff feat/<主题>` → 打新 tag（如 `v0.2-b2-verified`）
- 不通过：只在会话里给意见，**不动分支**

## 6. 证据留痕

- 每轮实测结果写 `docs/evidence/YYYYMMDD-HHMM-<主题>.md`（四段原始数字、md5、设备状态）
- 结论同步进 `docs/HANDOFF.md`（只由主 AI 改）
- 报告原文留在设备 `/data/user/0/org.onmyoji.oas.android/files/oas/cache/`，证据文件不删，只删 `*.raw`

## 7. 回滚

- 回到上一个能跑的版本：`git reset --hard <tag>`（工作区会丢未提交改动，执行前先确认）
- 只想撤一次坏提交：`git revert <sha>`
- 设备侧回滚：`./gradlew :app:assembleDebug` 出旧 tag 的包重新 `pm install -r -t`

## 8. 硬约束（任何分支都不许破）

- 绝不 `wm size` 改分辨率；识别画布恒 1280×720；不改 dpi
- 不注册开机自启（无 BootReceiver / JobScheduler / AlarmManager）
- Gradle 8.14.3 / AGP 8.13.2（不能上 9.x）/ minSdk 26 / targetSdk 35 / arm64-v8a
- 构建只用 `./gradlew`（PATH 上的 gradle 4.31 会报 Task not found）
- 测完必须把手机还给用户（亮屏 + Operit 回前台）

## 9. 并发与 git 权限（重要）

多个会话共享**同一个工作区和同一个分支**，所以 git 写权限必须分开：

| 操作 | 允许方 |
|---|---|
| `git add` / `git commit` | 写手 A |
| `git checkout` / `merge` / `tag` / `reset` / `revert` | **只有主 AI** |
| `git diff` / `log` / `status` / `show` | 所有人（只读） |

- A 绝对不要 `checkout`/`reset`/`rebase`：会把主 AI 或其他会话的工作区一起切走
- 主 AI 做 `checkout`/`merge` 前先确认：没有 gradle 在跑、A 处于空闲
- 找不到能回滚的点时用最近一个 tag，不要用 `reset --hard` 盲猜

### 9.1 同时活跃上限（硬性，实测内存只剩 401MB / loadavg 18.6）

- 同一时刻**最多 2 个活跃会话**：写手 A + 1 个只读（评审 D 或诊断 B）
- C（文档）与 D 不同时开工；D 做评审期间 B 暂停，反之亦然
- 有人要长时间占设备（四段约 4 分钟）时，另一个只读会话必须先停手
- 超出的活写进 `docs/BACKLOG.md` 排队，**不并行开**


## 10. 一个任务的完整生命周期（照这个走）

1. 主 AI 从最新已验证 tag 切分支：`git checkout -b feat/<主题> <tag>`，通知 A 当前分支名
2. A 小步实现 + 小步 commit（每次 commit 前跑一次 assembleDebug）
3. 门禁：需要时跑 cv_selftest 32/32 与四段 4/4，结果写 `docs/evidence/`
4. 主 AI 按 tag 做 diff 评审（只看增量）
5. 通过 → `merge --no-ff` 进 main → 打 tag → 改 `docs/HANDOFF.md` 结论
6. 不通过 → 留在 feat 分支继续修，历史不污染 main

## 11. 服务端实况（GitHub）与强制力排序

仓库：https://github.com/LinZeroZero/oas-android（public）

| 项 | 现状 |
|---|---|
| main 分支保护 | requires status check **`static-gate`**（strict：分支必须最新） |
| 强制 push / 删分支 | 均禁止 |
| 管理员豁免 | **无**（enforce_admins=true，主 AI 也绕不过去） |
| 评审要求 | **不设 required approvals**（单账号永远无法自审，见下） |

**强制力排序：服务端 CI > 本地 pre-push hook > 人的自律**。

- 为什么不要 required approvals：GitHub 官方明确「PR 作者不能批准自己的 PR」，
  单人项目开这个必死锁。Martin Fowler 站的 Ship/Show/Ask 也明确反对把“评审批准”
  当合并前提，并把“每个 PR 都要 2 个 reviewer”列为缺乏信任的信号。
- 所以本项目的评审是**建议性（Show 模式）**：CI 绿即可自合并；
  D 的 `REVIEW: PASS/FAIL` 写进 PR 评论或 `docs/reviews/`，**不阻塞合并**，
  但 FAIL 必须开一条 `docs/BACKLOG.md` 条目——否则等于没评。
- 本地 hook：`.githooks/pre-push`（进仓库）+ `git config core.hooksPath .githooks`。
  新 clone 要跑一次这条 config；它是护栏不是锁，可 `--no-verify` 绕过。
- 密钥纪律：PAT / 私钥绝不入库；`.gitignore` 已含 `.netrc`、`*.token`、`*.pem`、`*_rsa`。

## 12. 案例：PR #1（首次真实跑通）

| 步骤 | 实际发生 |
|---|---|
| 分支 | `feat/b-daemon-heartbeat`（9 个提交） |
| CI | `static-gate` 绿 → PR `mergeable_state` 从 `blocked` 变 `clean`（证明拦截生效） |
| 合并 | merge commit `929750b`，分支短命，合完即可删 |
| tag | `v0.2.0-b1`（预发布，因 BG-01 未过四段 4/4） |
| 未验收的部分 | 明确写进 merge commit message，不藏在聊天里 |
