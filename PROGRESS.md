# PROGRESS.md — 位置（干到哪了、下次从哪接）

> 更新时间：**2026-11-18 22:45** ｜ **上限 40 行**：只写「现在」，历史交 `git log`。

## 当前
- `main` = **最新合并提交**（要具体 sha 就 `git log --oneline -1`；本文件不记，见 `DECISIONS.md` D-013）；本地与远端都只剩 `main`；`git status` 干净
- `server.jar` = **29,253 B**（未重打）
- 最近合并：**PR #44** 预览临时 raw 清理修复（真机验收发现）；**PR #43** 瘦身纪律 + gate 第⑦项；**PR #42** 状态三件套 + gate 第⑥项；**PR #41** 口径④修正；**PR #40** C-2 实时预览
- 可分发 APK：`app/build/outputs/apk/debug/app-debug.apk`（含 PR #44 修复已验证：md5 `dae97e9a…`、dex 命中修复串；字节以 `ls -l` 为准，本文件不记，同 D-013）；`dist/` 里两个包是旧的，未更新
- **BG-35 C-2 已结案**：真机四条口径全 PASS（2026-11-18 22:35，口径④「清临时文件 3 个」+ `cache/preview` total 0 + 屏 42 未销毁）；证据 `docs/evidence/20261118-2235-BG-35-c2-recheck.md`

## 在飞
- **PR #45**（主 AI 棒包 `20261118-2200-main-ai.md`）门禁已绿、**待合**；分支 `docs/handoff-main-ai-20261118-2200`
- **已落盘未提交（等 docs 收口 PR 一起入库）**：`docs/handoff/20261118-2220-BG-35-c2-recheck.md`（写手棒包）、`docs/evidence/20261118-2235-BG-35-c2-recheck.md`

## 下一步（按序做）
1. **BG-32 P0-A** 14 分钟真熄屏长跑（游戏 27.7MB 资源已更新，工具链两轮冒烟已过），等约时点
2. **记录收口 PR**：`CHANGELOG`（#30–#44 欠账）/`README` APK 字节/`THIRD_PARTY_NOTICES` 由 C1 写；主 AI 同时收 BG-35 结案、写手棒包换新、删旧写手包 1449、指针同步
3. **C-1 / C-3**：C-1 押后（须从头重做），C-3 未开

## 阻塞 / 等人
- 真机类实验（熄屏、占机）都必须用户给时点
- **BG-12**：GitHub classic PAT 未 revoke（要换 fine-grained 单仓 90 天 + 开 Secret scanning / Push protection）
- **C-1**（flags 重打 jar）押后到 C-2 验收有结论之后，否则又是注定 FAIL 的实验

## 设备态（2026-11-18 22:40 实测）
- 无 `oas` 虚拟屏、无 daemon / `app_process`、游戏未运行、`cache/preview` 为 `total 0`、`/data/local/tmp` 本轮临时文件已清
- `mWakefulness=Awake`、`accelerometer_rotation=0`、`user_rotation=0`

## 已知腐坏（谁看谁修，别照抄）
- `docs/HANDOFF.md` §二 的 APK / jar 字节与自检数据已过期（以本文件 + `ls -l` 为准）
- `README.md:84` 的 APK 字节过期（实查 `dist/` 有 `oas-android-debug.apk` 与 `oas-bg22-debug.apk` 两个包）
- `CHANGELOG.md` 停在 11-15，**PR #30–#44 全未入账**