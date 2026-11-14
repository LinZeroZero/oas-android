# 协作与 Git 流程（本项目实际执行的版本）

> 目的：让「多个 AI 会话 + 用户」协作时有和正规项目组一样的纪律：分支隔离、小步提交、
> 构建门禁、代码评审、可回滚、证据留痕。所有会话开工前先读这篇。

## 1. 角色与边界

| 角色 | 会话 | 可以做什么 | 绝对不可以做什么 |
|---|---|---|---|
| 主 AI（调度） | 当前对话 | 建会话、投递任务、`git diff` 只读评审、汇总、改 `docs/HANDOFF.md` 与 `docs/WORKFLOW.md` | 改 `app/`、`poc/displayserver/` 等代码 |
| 写手 A | `OAS-A 写手（唯一代码位）` | 代码、构建、装机、真机验证、`git commit` | 换工作区、把工程复制到别处、未经评审直接合 `main` |
| 诊断 B | `OAS-B 旋转定责（只读）` | 只读命令、整理证据 | 写任何文件、装有副作用的设置 |
| 文档 C | `OAS-C 文档` | `README.md`、`THIRD_PARTY_NOTICES.md` | `app/`、`HANDOFF.md`、碰设备 |
| 调研 D | `OAS-D daemon 长存调研` | 联网调研、给做法清单 | 写代码、碰设备 |

**唯一写手原则**：代码只由 A 落地。B/C/D 发现要改代码，只交结论给 A。

## 2. 分支模型

- `main`：只能通过验收的快照（四段 4/4），随时可回滚
- `feat/<主题>`：一个任务一个分支，例如 `feat/b-daemon-heartbeat`
- `fix/<主题>`：修缺陷
- 标签：`v<阶段>-<序号>-<含义>`，例如 `v0.2-b0-baseline`、`v0.2-b1-4of4`

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

## 4. 门禁（每次提交和每次合入都要过）

1. `bash tools/build_displayserver.sh` 无错（改了 Java 才需要）
2. `./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`，记下 APK 大小与 md5
3. 装机后 `am start --ei cv_selftest 1` → 32/32（改了 cv 相关才需要）
4. 四段验证 **4/4** 且 C 段心跳空洞 < 5000ms（改了保活/心跳才需要）
5. 收尾干净：`am force-stop`、daemon 0、`*.raw` 删净、Operit 回前台

任何一条不过：留在 `feat/` 分支上修，**不许合 `main`**。

## 5. 评审方式（主 AI 做）

**锚点用 tag，不用 `main`。** 因为 feature 分支可能就是从同一个提交切出来的，`main...HEAD` 会显示空 diff。

- 评审只看增量：`git diff <上一个已验证 tag>...HEAD --stat`，需要细节再加 `-- <路径>`
  例：`git diff v0.2-b1-daemon-hb-unverified...HEAD --stat`
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

## 10. 一个任务的完整生命周期（照这个走）

1. 主 AI 从最新已验证 tag 切分支：`git checkout -b feat/<主题> <tag>`，通知 A 当前分支名
2. A 小步实现 + 小步 commit（每次 commit 前跑一次 assembleDebug）
3. 门禁：需要时跑 cv_selftest 32/32 与四段 4/4，结果写 `docs/evidence/`
4. 主 AI 按 tag 做 diff 评审（只看增量）
5. 通过 → `merge --no-ff` 进 main → 打 tag → 改 `docs/HANDOFF.md` 结论
6. 不通过 → 留在 feat 分支继续修，历史不污染 main
