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
- [ ] **BG-19 `kb run <a> <b> <c> <reportPath>`：driver 前移进 daemon**
  - 段循环、读心跳 JSONL、算 maxGap、写报告都在 daemon；app 只发命令 + 读报告
  - 归属：A；验收：app 全程在后台（甚至 force-stop 之外）仍能拿到完整四段报告
- [ ] **BG-20 app↔daemon 改短连接**
  - 每次请求新建连接 + 超时 + 重连，不假设长连接（freezer 会掐 socket）
  - 归属：A；验收：daemon 重启或 socket 被断开后，app 下一次操作自动恢复
- [ ] **BG-21 C 段真测与数据判读**
  - 先释放/确认 `displayId:19` 屏锁，再锁屏静置 60s，同记 maxGap 与 suspend 差值
  - 归属：A（执行）+ 主 AI（写 evidence）
- [ ] **BG-22 排查游戏自持 `SCREEN_BRIGHT_WAKE_LOCK 'WindowManager/displayId:19'`**
  - 现象：`WorkSource{10386 com.netease.onmyoji}` 长期持有，虚拟屏不灭，会让「息屏测试」变成假息屏
  - 归属：B（只读诊断）
