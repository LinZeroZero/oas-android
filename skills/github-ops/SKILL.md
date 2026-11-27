---
name: github-ops
description: 本仓（LinZeroZero/oas-android）的 GitHub 运维：分支/PR/评审/合并/清场，含本仓独有的踩坑处置。
---

# github-ops — 本仓 GitHub 运维

## 适用场景
- 开分支、推 PR、转正式、squash 合并、删本地/远端分支
- PR 状态核查（`mergeable_state`、CI check-runs）
- CI / pre-push / gate 红了要定位
- 处理 rebase、force-with-lease、网络抖动（TLS / 5xx）

## 铁律（先读，不许绕）
1. `main` 受保护：**分支 → draft PR → CI + 审查位 PASS → 转正式 → 用户签发 → squash**。任何一步不能跳。
2. **不许 force-push `main`**；只允许 `--force-with-lease` 推到自己的分支。
3. **不许 `--no-verify`**；pre-push 红了要修，不是绕。
4. 合并只用 **squash**；不做 merge commit 进 main。
5. 合并后必须**双删分支**（本地 + 远端），远端只留 `main`。

## 标准动作
```bash
cd <workspace>
export GIT_PAGER=cat
git checkout -b <type>/<scope>-<topic>
# …改动…
bash tools/gate.sh --static            # 必须看到末行 GATE: PASS
git add <点名的文件>                    # 禁 git add -A
git commit -m '<type>(<scope>): <一句话>'   # 约定式前缀，见 WORKFLOW §3
git push origin <branch>
```
开 PR（draft）用 API：`POST /repos/{owner}/{repo}/pulls`，`draft: true`。
转正式**只能**走 GraphQL：
```graphql
mutation($id: ID!){ markPullRequestReadyForReview(input:{pullRequestId:$id}){ pullRequest { isDraft } } }
```
（REST `PATCH {"draft":false}` 返回 200 但**静默不生效**。）

## 本仓踩过的坑（照这个处置）
| 症状 | 真因 | 正确做法 |
|---|---|---|
| 本地 gate 12 项 PASS，CI `static-gate` FAIL | CI 多一条 Conventional Commits 检查（`rev-list --no-merges` 按对象类型豁免 merge） | 普通提交必须符合前缀；merge 提交随便写，别手改 |
| squash 合并后 `git branch -d` 报 "not fully merged" | squash 后本地提交不是 main 祖先 | 先 `git rev-parse main^{tree} <branch>^{tree}` 两边相同 → `git branch -D` |
| 合并 API 返回 500/502，但 PR 已 merged | GitHub 服务端抖动，响应丢了 | **先 GET 查 `state/merged`**，再决定是否重试；不要盲重试造成误解 |
| `git push --delete` 报 TLS handshake failed | 网络抖动 | 直接重试，一般第二次就过 |
| 已合并的 PR 分支还挂在远端 | 忘了删（真实发生过） | 合并后立刻 `git ls-remote --heads origin` 自检 |
| pre-push 报 `progress-not-updated` | 改了 `app/` 或 `poc/` 但没改 `PROGRESS.md` | 改 PROGRESS（字节吃紧时只改时间戳，零增长） |

## 约束
- 派单文本（给审查位的）由主 AI 拟、**用户转发**；内容只含「目的 + 范围 + 对象 sha + 锚点 sha」，禁自列判据/检索词（D-023/D-030）。
- 任何写操作（合并/转正式/删分支）前，先在回复里把**将要执行的命令**摆出来。
- **优先用 Operit 的 `github` 包工具**（PR / check-runs / 合并 / 删分支）；包不可用或需 GraphQL 时，用 Python + `urllib`，token 从 `/root/.git-credentials` 读，**不回显 token**；不用 `gh` CLI（本机没有）。

## 期望输出
- 一段「状态表」：PR 号 / head sha / `mergeable_state` / CI 结论 / 分支是否已删
- 失败的项给**命令 + 原始报错行**，不写"大概""可能"

## PR 描述怎么写

照 `.github/PULL_REQUEST_TEMPLATE.md` 填。本仓三条额外要求：

1. **「验证」必填**：环境 → 命令 → 结果；性能类给 n 与分母（D-018），证据落 `docs/evidence/` 并给路径。
2. **「AI 使用披露」必填**：哪些由 AI 生成、人核到什么程度。
3. **不给审查方派作业**：不写判据、结论、期望值；「给审查方的说明」只写"哪里不显然、哪里可跳过"。

GitHub 操作优先级：**`gh`（已装 2.106.0）> Operit `github` 包 > 手搓 `urllib`**。
