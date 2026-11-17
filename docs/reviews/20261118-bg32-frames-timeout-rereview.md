# BG-32 返工复审（第二轮 4-eyes）

对象：`git diff a056538..d658694`（HEAD `fix/bg32-frames-and-timeout`）｜只读，未 checkout/commit。

**结论：PASS**（1×P1 已真修；4×P2 中 3 条已修，另发现 3 条新 nit，均不阻塞）

## 逐条

1. **P1-1 ✅** `Main.java:199` `CONNECT_TIMEOUT_MS = 2000`；`:211` `setSoTimeout(CONNECT_TIMEOUT_MS)` 同常量引用，无第二处超时值残留（全文仅 2 处引用）。符合「socket ≤2s」口径，也不再超过 5s 采样间隔。
2. **P2-1 ✅** `hang_check.sh:497` 自停带 `OAS_SELF_STOP=1`；`:539-540` 命中时**整段跳过 kill**；`:559` 仍 `rm -f sampler.pid`；`:561+` 照常出报告。人工 stop（env 未设）走原 elif 链（`:541-556`），行为未变。残留窗口：无——父进程是前台等子进程，子进程写完报告才返回，父进程随后 `break` 退出，不会残留采样器、也不会二次出报告。
3. **P2-2 ✅ 但有一个实用副作用（新 nit-1）** `hb_frames` 现取 `$2` 作期望 pid（`:233/246`），`want` 为 `''|0|非数字` 时直接 `return 1`（`:234-236`）。→ **当 `dpid=0`（ps 未识别到 daemon / cmdline 被 `--nice-name` 改写 / 身份校验误判）时，快路整体失效**，即使心跳文件新鲜有数也记 `n/a`。严重度：**中低但需记录**——结果是帧判据降级为「未采到」→ WARN，不会假 PASS；但若长期处于该状态，`frames` 列会长期 n/a、帧判据形同废置。
   建议（下一轮，非阻塞）：`want=0` 时允许心跳自证——`hpid>0` 且 `/proc/<hpid>/cmdline` 含 `app_process`+`oas.server.Main`。
4. **P2-3 ✅（部分）** `hb_frames:233` 已 `local did want line hts hdid hfr hpid now age`，无遗漏。**但 `frames_of:274-276` 自身仍是全局赋值**（`did/tick/want_pid`），且 `channel` 也未 local → 新 nit-2（功能无碍，因为调用点只有 `:398` 一处且同值；属易碎写法）。
5. **无新问题 ✅** `frames_of` 3 参签名唯一调用点已改：`:398` `frames_of "$did" "$tick" "$dpid"`（全文仅此一处调用，`grep frames_of` 只命中定义 `:273` 与该行）。`set -u` 下无裸展开；`SAMPLE`/报告路径未改动（diff 仅 3 个文件）。
6. **jar 一致性（新 nit-3）** `app/src/main/assets/server/server.jar` 与 `poc/displayserver/build/server.jar` 同为 **29253 B**，`classes.dex` 内可见 `connectExec` / `--connect` 字符串（说明包含本次 client 模式代码），dex 时间戳 2026-11-17 15:18。**但 `poc/displayserver/build/.gate-java.sum` = `55732ba…`，与当前源码实时 md5 `f895f3d…` 不一致** → 无法从仓库内自证「jar 由当前 Main.java 重建」。建议跑一次 `tools/gate.sh`（① 会重编并刷新 stamp）后复核 29253 B。

## 新发现汇总

- nit-1：`dpid=0` 时心跳快路被废（影响：frames 长期 n/a，不是假 PASS）。
- nit-2：`frames_of` 缺 `local`。
- nit-3：jar 与 `.gate-java.sum` 不同步，可复现性缺口（P2）。

均不构成合并阻塞。
