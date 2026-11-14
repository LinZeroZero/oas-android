# 缺陷与待办（BACKLOG）

> 规则：一条一行，带 ID；关闭时改成 `[x]` 并写明关闭依据（commit / 实测证据文件）。
> 归属方按 `docs/WORKFLOW.md` §1：代码 → A；文档 → C；只读诊断 → B；调研/评审 → D；流程 → 主 AI。

## 一、阻塞 B 路线验收（P0）

- [ ] **BG-01 四段拿不到 4/4：B 段起 app 侧驱动器被冻**
  - 证据：`/proc/2198/task/2375/stack` = `do_freezer_trap`（线程级冻结，进程 stat 仍 S）
  - 归属：A（改代码）
  - 方向候选：把 phase driver 前移到 daemon（`kb run <a> <b> <c> <reportPath>`），app 只触发+显示
  - 状态：**等用户拍板架构方向**

- [ ] **BG-02 C 段（息屏 60s）从未真正测过**
  - 因 BG-01 连带阻塞；daemon 心跳在后台无空洞（645 拍/644s/maxGap 1004ms）只是旁证，不是 C 段数据
  - 归属：A

## 二、流程与工具（P1）

- [x] **BG-03 门禁已脚本化** —— `tools/gate.sh` 五项一键 PASS/FAIL
  - 关闭依据：CI `static-gate` 多次 success；本地 `GATE: PASS`
  - 遗留：D 的 5 条评审意见未落地 → 见 BG-13
- [x] **BG-04 基线提交含 B v1 代码**，无法按提交粒度回溯 B 的改动
  - 已缓解：非 SemVer 旧 tag 已删除，改用 `v0.2.0-b1`；从此往后 A 必须小步提交
- [x] **BG-05 HANDOFF §11.7 里 C 的 chat_id 笔误**（`...dbd1d278198e` 应为 `...dbd1c278198e`）
  - 归属：主 AI

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
- [ ] **BG-13 `gate.sh` 的 5 条评审意见未落地**
  - L118 `[ "$PASSED" = "$TOTAL" ]` 在 `$TOTAL` 缺失时被 `set -u` 中断，GATE 行不输出
  - L143 固定 `sleep 240`，余量仅 55 秒，易误报
  - L229 正则排除空格，`wm size 1280x720` / `wm size -100x100` 全漏检
  - L221 只扫 `app/src` 与 `poc/displayserver/src`，漏 `tools/`、`*.kts`
  - 归属：A（D 评审结论 FAIL）
- [x] **BG-14 服务端强制已生效** —— `static-gate` strict + `enforce_admins=true` + 禁 force push
  - 依据：PR #1 / PR #2 的 `mergeable_state` 均从 `blocked` 变 `clean`
- [x] **BG-15 缺 CHANGELOG** —— 已建 `CHANGELOG.md`（Keep a Changelog 1.1.0）
- [ ] **BG-16 文档漂移**：`docs/HANDOFF.md §11` 仍写旧 tag 名 `v0.2-b1-daemon-hb-unverified`
  - 归属：C
