# 交接 · OAS 安卓端适配 · BG-34 只读定位（写手棒）

> 时间：2026-11-18 23:35 ｜ 项目：oas-android ｜ 上一棒：写手 A6
> 用途：接任写手的会话，第一动作 = 读本文全文 → 按 §7 只读实查 → 报 §9 卡点等主 AI 派单，不要先自由探索、不要改任何代码或 jar。

## 1. 当前任务目标（含验收口径）

- 岗位：写手（写手棒）。本棒**只有一条**任务 = **BG-34 只读定位**：查清「虚拟屏上的游戏 activity 为什么是 PAUSED、窗口为什么 `visible=false`」，以致 `input -d <displayId> tap` 被 `InputDispatcher` 丢弃。
- 产出：`dumpsys` **原文片段**（只读证据）+ 明确判定「A / B / C 哪条成立」，或「仍不能收敛、缺哪条证据」。
- 本棒 PASS 判据：能说出「为什么窗口 `visible=false`」的**机制级原因**，并给出一条可以验证它的**下一条命令**。**不需要修好。**
- 失败也算有效产出：三条假设全排除 → 写「全排除 + 新假设 X」，**不许空手**。
- 三条待判假设（在本棒里定性，不要提前选边）：
  - **A｜display 焦点**：该 VirtualDisplay 没有独立输入焦点（缺 `OWN_FOCUS` 一类）→ 窗口拿不到 focus。
  - **B｜activity 生命周期**：`am start --display` 拉起的 activity 被 WMS 停在 PAUSED（`mLastPausedActivity` 佐证）→ 窗口 `visible=false`。
  - **C｜窗口/输入关联**：窗口存在但不在 `InputDispatcher` 的 touchable 列表（`no touchable window` 佐证）。
- **本棒明确不做**：不重打 jar、不改 VirtualDisplay flags、不改 `app/` 代码、不跑 C-1 实现、不 push / commit、不写设备侧设置。

## 2. 已完成工作（只写验证过的）

- **仓库**：`main` = `ed9fe80`（PR #47 已合并；以现查为准）。当前工作区停在分支 `docs/progress-sync-47`，PR #48 待签发（该 PR 只同步 `PROGRESS.md` 一行，**与写手无关**）。
- **jar**：`server.jar` = **29,253 B**（D-011：不随意重打，要重打必须先落新决定）。
- **画布**：识别画布恒 **1280×720@320**（D-001），绝不 `wm size`。
- **BG-34 现象实测**（2026-11-18，证据 `docs/evidence/20261118-1520-BG-32-p0a-precondition.md` §4B，当时 display **38**）：
  - `mCurrentFocus=null`、`mFocusedApp=null`；
  - 游戏 `Task{#933} visible=false visibleRequested=false`、`mLastPausedActivity=.Client`；
  - 三次探针 `tap 800 530` / `keyevent 4` / `tap 640 120` 均**无像素变化**、`frames` 恒 **544**。
- **D-010（AOSP 实证，已定案）**：`input -d` **不是不可靠**；tap 丢在 `InputDispatcher`「**no touchable window**」，key 丢在「**no focused window**」（`InputDispatcher.cpp:2526-2530` / `:2313-2318`），全文无 `INVALID_DISPLAY`。后果：**只加 `SUPPORTS_TOUCH` 对 tap 支无效**，只可能影响 key 支。
- **上一棒越线已回滚**：commit `2619387`（改 `SocketServer.DEFAULT_FLAGS` 加三个 flag、jar 29253→29254）**未评审未合并、已整体回滚** → C-1 必须**从头重做**，不是「接着改」。
- **BG-22 / D-009**：`setSurface(null)` 已采纳但**默认关**（主屏熄屏时对虚拟屏停渲）→ 一旦打开会让窗口更不可能 `visible`，与 BG-34 必须对齐先后顺序。
- **长跑里的 D 段（2026-11-18 22:46–23:00，判定 PASS）**确实执行了 `input -d 43 tap 640 360`，但**未能证明生效**（`frames` 在该阶段被节流、取不到数）→ 任何规划 / 验收都**不得**当它是「点击已生效」。
- **BG-34 现状**：`docs/BACKLOG.md:14-18`，`[ ]` 未开；归属「写手先只读定位 + D 复核」；`BACKLOG:18` 明确写着「旧记录说 PoC 期 `input -d` 可用，但**以本轮实测为准、不得直接引用旧结论**」。
- **AOSP 强制前置（BG-35 调研已核，出处 `docs/HANDOFF.md §十二` 的「BG-35 立项」段）**：`OWN_FOCUS` 必须与 `TRUSTED` **成组加**，单加会被静默忽略并打 `Slog.w`（`VirtualDisplayAdapter.java:671-677`）；`mSurface == null` ⇒ `STATE_OFF`（`:695-696`），所以预览期间不可停渲。→ **本棒只读，这条只用于判断「假设 A 是否成立」，不构成本棒改代码的授权。**

## 3. 当前断点

- **无半成品代码**：工作区在 docs 分支（PR #48 只改 `PROGRESS.md` 一行），没有 `app/` 改动需要回滚。
- **定位停在「现象稳定复现、机制未收敛」**：三次探针无像素变化可复现，但 A / B / C 三条假设**都还没被排除**，也没有 `dumpsys input` 的 touchable 列表原文。
- **两条会带偏结论的已知项**：① `SUPPORTS_TOUCH` 只可能影响 key 支，别再把它当 tap 的解法（D-010）；② `setSurface(null)` 默认关，但一旦开会让窗口更不可能 visible（D-009）→ 判 BG-34 前先确认它当前是**关**的。
- **jar 状态是干净的**：29,253 B（D-011），本棒只读，不重打。

## 4. 关键约束

- **只读到底**：不重打 jar（D-011）、不改 VirtualDisplay flags、不改 `app/` 代码、不改仓库任何文件、不 `git add/commit/push`、不写设备侧设置。
- 画布恒 **1280×720@320**（D-001）→ 绝不 `wm size`、不改 dpi、不启用自动旋转。
- 设备是用户**主力机**：占机类操作先约时点；收工定序 = **先确认真响铃 → 再亮屏 → 再 `am start -n` 拉回 Operit 前台**（缺一不算完）；并 destroy 所有 `oas-*` 虚拟屏、停 daemon 与游戏、`accelerometer_rotation` 复位 0、清 `/data/local/tmp` 本轮临时文件。
- **文档边界**：写手不改 `docs/*`（含 `BACKLOG.md`、`evidence/`）；结论只回主 AI，证据文件由主 AI 落。
- 仓库是 **public**：任何文件、任何回执里都不出现 PAT / token / 密钥。
- 唯一写手原则：任一时刻只有 1 个写手；**禁 `checkout` / `reset` / `rebase` / 切分支**（所有会话共用同一工作区）；`merge` / `tag` 只有主 AI 做。
- **数虚拟屏必须按 displayId 去重**：运行期 `dumpsys display` 会为同一屏打 `mBaseDisplayInfo` 与 `mOverrideDisplayInfo` 两行 → 不能拿 `grep -c` 直接跟 1 比。
- 本棒**不需要熄屏 / 不做长跑**：只需建虚拟屏 + 拉游戏 + 抓 `dumpsys`，预计 5–10 分钟；但会占主屏前台（游戏显示在虚拟屏、主屏仍在用），收工时按上面定序拉回 Operit。

## 5. 排除记录（已试过、不要再试）

1. **「只加 `SUPPORTS_TOUCH` 就能让 tap 生效」** → 已排除（D-010：tap 丢在 `no touchable window`，与 touch 标志无关；`SUPPORTS_TOUCH` 只可能影响 key 支）。
2. **「`input -d` 本身不可靠 / 会被按 display 丢弃」** → 已排除（D-010：AOSP 全文无 `INVALID_DISPLAY`，丢点在 WMS/WM 侧没有可命中窗口）。
3. **「上一棒 C-1 那版接着改就行」** → 不行：`2619387` 已整体回滚（jar 29253→29254 也退了），要做得**从头**。
4. **「旧记录说 PoC 期 `input -d` 能用」** → 不得引用（`BACKLOG:18` 明确：以本轮实测为准）。
5. **「长跑 D 段点过了，说明点击生效」** → 不成立：D 段 `input -d 43 tap 640 360` 未能证明生效（`frames` 被节流、取不到数）。
6. **「`dumpsys display | grep -c` 数虚拟屏」** → 会重复计数（`mBaseDisplayInfo` + `mOverrideDisplayInfo`），必须按 displayId 去重。
- TODO(未核)：「用 uiautomator dump 取窗口/界面状态」是否可行未实测（本棒若要试，先小步试一次再下结论，不得直接写「稳定报错」）。

## 6. 当前故障

1. **无阻塞性故障**（工作区在 docs 分支、无代码半成品），但**当前故障本身就是「机制未收敛」**：窗口 `visible=false` + `mCurrentFocus=null` 的原因未知。
2. **观测坑**：`frames` 在长跑 D 段被节流 → 该阶段拿不到数，**不能用它判点击效果**。
3. **潜在污染**：BG-22 `setSurface(null)`（默认关）若被打开，会让虚拟屏窗口更不可能 visible → 与本结论互相污染；本棒要记录它当前的开关状态。
4. `TODO(未核)`：本包对 `docs/evidence/20261118-1520-BG-32-p0a-precondition.md` §4B 是**转述**，未逐字引用原文；接手若要引原文请自己打开核对。

## 7. 启动动作

> 接手后第一件事 = **只读实查**，把 `dumpsys` 原文片段留下当证据。**全程不重打 jar、不改 flags、不改代码、不 commit、不写任何设置。**

```sh
# —— proot 终端（super_admin:terminal）：仓库现状（只读）——
cd /data/user/0/com.ai.assistance.operit/files/workspace/8868caab-ae27-4814-9992-11fb83225a35
export GIT_PAGER=cat PAGER=cat
git log --oneline -1; git status --short; git branch -a
wc -c app/src/main/assets/server/server.jar
```

```sh
# —— root shell（super_admin:shell）：定位三件套（全部只读）——
# 1) 焦点：该 display 的 mCurrentFocus / mFocusedApp / mLastPausedActivity
dumpsys window displays | grep -E 'Display: mDisplayId=|mCurrentFocus|mFocusedApp|mLastPausedActivity' | head -40
# 2) 生命周期：游戏 Task 是否 RESUMED、窗口是否 visible
dumpsys activity activities | grep -E 'Task\{|visible=|ResumedActivity|mLastPausedActivity' | head -40
# 3) 输入关联：InputDispatcher 眼里有没有 touchable window
dumpsys input | grep -nE 'touchable|FocusedWindow|focused window' | head -30
# 4) 虚拟屏计数：必须按 displayId 去重（同一屏会打 mBaseDisplayInfo + mOverrideDisplayInfo 两行）
dumpsys display | grep -oE 'mDisplayId=[0-9]+' | sort -u
# 注：运行期这个 count 会是 2（同一屏打 mBaseDisplayInfo + mOverrideDisplayInfo 两行），清场后才是 0；判屏数一律用上一行按 displayId 去重的结果
```

- 参考值（写入时点，**一律以现查为准**）：`server.jar` 29,253 B；现象为 `mCurrentFocus=null`、`mFocusedApp=null`、游戏 `Task{...} visible=false visibleRequested=false`、`mLastPausedActivity=.Client`、`frames` 恒 544（**displayId 是 38 还是 43 随本轮实例变，别照抄**，以 `dumpsys display` 现查为准）。
- 与本文描述不符：**原样报差异，不要自行猜原因、不要顺手改任何东西**。

## 8. 岗位速查

| 角色 | 会话 id | 职责 | 硬边界 |
|---|---|---|---|
| 主 AI（调度） | `8ea4b9ce-0ad6-4932-953a-14922eace680`（当前调度会话，实查自 `list_chats` 的 currentChatId） | 派单、验收、记账、`merge` / `tag` | 不写代码、不逐行 review、不联网搜 |
| 写手 A6 | `53ba6d3f-ded9-4d1d-856b-d203ad787272` | **唯一代码位**：代码 + 构建 + 装机 + 真机验证 | 禁与另一写手并行；禁切分支 / `checkout` / `reset` / `rebase`；不合 main |
| 调研兼评审 D2 | `0ef69b6e-909b-4bdb-bb16-ef38d35eeeb7` | 联网调研 + **代码 4-eyes 评审**（评审归 D） | 不写代码、不碰设备、不 git |
| 交接包位 E2 | `8c1b4eb4-2bce-4072-91e2-ef6954274128` | 只写 `docs/handoff/*.md` | 不碰 `docs/HANDOFF.md` / `WORKFLOW` / 代码 / 设备 |
| 文档位 C1 | `5ca72fd5-8c2c-429a-9774-90e2946fc6ed` | `README.md` / `CHANGELOG.md` / `THIRD_PARTY_NOTICES.md` | 不碰代码、`docs/HANDOFF.md`、设备 |

- **并发上限 2**（写手 + 1 个只读位）；所有子会话共用**同一工作区**（同一个 `.git`）。

## 9. 待办优先级（按序，别重排）

1. **本棒唯一任务 = BG-34 只读定位**：跑 §7 的四类只读命令 → 判定 **A / B / C 哪条成立**（判据见 §1：能给机制级原因 + 一条验证命令，不需要修好）。三条全排除就给「全排除 + 新假设 X」。
2. **把结论回主 AI**：一行判定 + `dumpsys` 原文片段；证据文件由**主 AI** 落（写手不写 `docs/`）。
3. **明确不做（别顺手开工）**：不重打 jar、不改 flags、不跑 C-1 实现、不改 `app/` 代码、不 push / commit。C-1 若要做，必须**从头**（上一版 `2619387` 已回滚）。
4. **依赖 / 阻塞（不是本棒任务）**：BG-22 `setSurface(null)` 默认关，开之前要先与 BG-34 结论对齐先后；D-010 结论在案，别再重复验证「`input -d` 是否可靠」。

**动手前先问主 AI「从哪条命令起」，不要自己发挥。**