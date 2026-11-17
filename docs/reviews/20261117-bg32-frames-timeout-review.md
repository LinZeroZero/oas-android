# BG-32 增量评审：frames 走 `--connect` + 心跳快路 + 硬超时自停

日期：2026-11-17｜评审位：OAS-D（4-eyes，只读）
范围：`git diff a056538..b22dda8`，涉及 3 个文件：`poc/displayserver/.../Main.java`（+80）、`tools/hang_check.sh`（+97/-17）、`server.jar`（28650→29259，二进制）。
已核对基线：`b22dda8` = `fix/bg32-frames-and-timeout` 头；未改任何文件、未 checkout/commit。

---

## 结论

**REQUEST CHANGES**（1 条 P1：socket 超时口径；4 条 P2）

---

## P1（必须改，阻塞合并）

### P1-1 `Main.java:198` `CONNECT_TIMEOUT_MS = 8000` 违反项目「socket ≤2s」口径，且大于采样间隔

- 事实：`Main.java:198` `private static final int CONNECT_TIMEOUT_MS = 8000;`，`:210` 用 `socket.setSoTimeout(CONNECT_TIMEOUT_MS)`。
- 项目口径：`app/.../DisplayServer.rawCommand(cmd, 2000)`（既有 app 侧通道固定 2s），BG-22 评审结论也要求「socket 调用最长 2s」。
- 功能影响：`hang_check.sh:194-205` 的 `daemon_cmd` 是**同步调用**，`frames_of`（`:300` 段）在采样循环里调它；`INTERVAL` 默认 5s，8s 超时 > 采样间隔 —— daemon 一旦卡住，单拍会被拖到 8s，直接造成 samples 空洞、`loadavg1` 被 JVM 抬高，报告里的「采样最大间隔」会失真。
- 修法（二选一）：
  1. 直接把 `CONNECT_TIMEOUT_MS` 改 `2000`（与 `rawCommand` 同口径）；或
  2. 拆两段：`connect` 最多 2s、`readLine` 最多 2s，并在 `hang_check.sh` 里对 `daemon_cmd` 加 `timeout 3` 之类的外层兜底（若设备有 `timeout`）。

---

## P2（可改可不改）

### P2-1 `hang_check.sh:488` 自停时 `stop` 会 kill 掉自己的父进程（采样循环）
`OAS_HANG_DIR="$out" sh "$SELF" stop` → `do_stop` 的 kill 段（`:527-544`）验证 `pid_is_sampler` 通过后 `kill` 的就是采样循环进程。父进程被 SIGTERM 时若正处在 `printf … >> samples.csv`（`:478` → `sample_once` 尾部），理论上可留半行 → 触发 `badcols` → 把「正常自停」误报成 **FAIL（列错位）**。
建议：`do_stop` 在 `[ -f "$out/timeout" ]` 时**跳过 kill 段**（自己就是采样器），或在 `do_loop` 自停分支里不要调 `stop`，改为落 `timeout` 后 `break`，由外层收尾。
（注：报告不会被写两遍——父进程没有写报告路径；这点设计是对的。）

### P2-2 `hang_check.sh:230-245` 心跳快路未校验心跳的 `pid` 是否仍是当前 daemon
文件格式里已有 `"pid"` 字段（`Heartbeat.java` 每拍写），但 `hb_frames` 只校验 `ts` 与 `displayId`。同一路径被「上一代 daemon 的旧文件 + 新一代 daemon 新写入」混写时（重启瞬间），`age≤5s` 仍可能命中旧值 → 帧数基线跳变（`frames` 归 0 → `fdelta<0` → 判 FAIL）。
建议：把 `"pid"` 与当前 `daemon_pid` 比对，不一致即 `return 1` 回落。

### P2-3 `hang_check.sh:230-245` 变量无 `local`，污染调用者作用域
`hb_frames` 里 `did/hts/hdid/hfr/now/age/line` 全是全局；`frames_of`、`sample_once` 也在用 `did`。当前值相同故未出 bug，但属易碎写法（设备 `/system/bin/sh` 是 mksh，支持 `local`）。
建议：加 `local`。

### P2-4 `hang_check.sh:434` 写 `limit` 文件仅为报告文案，冗余
`printf '%s\n' "$MAX_DURATION" >"$out/limit"`（`:434`），`do_stop:552` 只用它拼「上限 Ns」。可直接从 `OAS_MAX_DURATION` 读，少一个状态文件；不影响正确性。

---

## 已核对通过的点（无问题）

1. **`--connect` 语义正确、不会重蹈 BG-23 假参数坑**：`Main.java:140-148` 先判 `connectName != null`，缺 `--cmd` 时 `System.exit(2)` 并在 stderr 说明（`:145`）；该分支在 `--socket` 之前，不会 bind。`LocalSocketAddress.Namespace.ABSTRACT`（`:209`）与 daemon 的一致。
2. **输出契约清晰**：成功 `println(response)` + `return 0`；连不上/超时/对端关闭都会 `println` 一行 `{"ok":false,…}` + `return 1`（`:221-233`），stdout 与退出码双通道，脚本侧 `head -1` + `grep '"frames"'` 都能判。
3. **关流与资源**：`finally { socket.close() }`（`:234-239`）；只读一行即关，不持有长连接 —— 符合「app↔daemon 短连接」的既有决定。
4. **心跳快路不会把「心跳停了」误读成「帧在涨」**：`hb_frames` 要求 `ts` 新鲜（`age` 在 `[0, HB_MAX_AGE]`，负数即未来时间也拒绝，`:242`）、`displayId` 匹配（`:239`）、`frames` 是纯数字（`:243`），任一不满足 `return 1` 回落到 app 通道；心跳停 → `age` 变大 → 必然回落，不会静默给数。
5. **`OAS_MAX_DURATION=0` 关闭语义真的生效**：`:481` `if [ "$MAX_DURATION" -gt 0 ]`，0 时整块跳过。
6. **`set -u` 安全**：`SOCKET_NAME`(`:36`)、`HB_FILE`(`:42`)、`HB_MAX_AGE`(`:43`)、`MAX_DURATION`(`:45`) 全部带默认值；`hb_value` 在 `if` 里赋值（`:273`）；`selftimed` 在 `:550` 先赋予 `'否'`；`lim` 取值后兜底 `'?'`（`:553`）。
7. **toybox/mksh 兼容**：新增的 `sed -n 's/.*"ts":\([0-9]*\).*/\1/p'`、`tail -1`、`$((now - hts / 1000))`、`head -1` 都是 POSIX 子集，无 GNU 专有开关。
8. **硬约束**：diff 仅 3 个文件，未改 `app/**`、未改分辨率/画布；心跳文件由 **daemon** 写（`KeepAlivePoc.kt:144` `DisplayServer.hbStart(...)`），app 只是发起者 —— 不算「引入 app 侧心跳」。

## 附：本轮未验

1. `server.jar`（28650→29259）未做字节级核对：二进制入库，建议 CI 用 `build_displayserver.sh` 的 `JAVA_SUM` 与 jar 的 md5 对齐（P2）。
2. 8000ms 超时在真机上的实际卡顿幅度未实测（只读评审，不占设备）。
