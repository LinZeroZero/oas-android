# PROGRESS.md — 位置（干到哪了、下次从哪接）

> 更新时间：**2026-11-18 23:10** ｜ **上限 40 行**：只写「现在」，历史交 `git log`。

## 当前
- `main` = **最新合并提交**（要具体 sha 就 `git log --oneline -1`；本文件不记，见 `DECISIONS.md` D-013）；本地与远端都只剩 `main`；`git status` 干净
- `server.jar` = **29,253 B**（未重打）
- 最近合并：**PR #46** docs 收口（CHANGELOG #30–#45 补账 / README / THIRD_PARTY / 写手棒包换新 + 删旧 1449 + 指针同步）；**PR #45** 主 AI 棒包；**PR #44** 预览临时 raw 清理修复（真机验收发现）；**PR #43** 瘦身纪律 + gate 第⑦项
- 可分发 APK：`app/build/outputs/apk/debug/app-debug.apk`（含 PR #44 修复已验证：md5 `dae97e9a…`、dex 命中修复串；字节以 `ls -l` 为准，本文件不记，同 D-013）；`dist/` 里两个包是旧的，未更新
- **BG-35 C-2 已结案**：真机四条口径全 PASS（2026-11-18 22:35，口径④「清临时文件 3 个」+ `cache/preview` total 0 + 屏 42 未销毁）；证据 `docs/evidence/20261118-2235-BG-35-c2-recheck.md`
- **BG-32 P0-A 已结案**：正式 14 分钟真熄屏长跑 PASS（2026-11-18 22:46–23:00，样本 135 / 846s、屏 displayId 43 恒定、daemon 26738 与游戏 26808 全程在、帧数 603→7435、CSV 恒 15 列、非 Awake 69%）；证据 `docs/evidence/20261118-2300-BG-32-p0a-longrun.md`

## 在飞
- **PR #47**（BG-32 P0-A 结案 + 判据纠正 + 证据）**待合**；分支 `docs/bg32-p0a-closeout`。提醒：`stop` 落盘样本 ≈ 理论拍数的 8 成（单拍耗时 >5s 采样间隔），官方口径无样本数下限，别再拿「样本≥168」当判据

## 下一步（按序做）
1. **签 PR #47**：门禁绿后由用户签发 squash 合并；合并后回 `main`、本地/远端双删分支
2. **BG-34**（虚拟屏 activity PAUSED、`input -d` 不投递）—— 挡住 P0-A 的 D 段「定向点击生效」，也是 C-1 的前置
3. **C-1 / C-3**：C-1 押后（须从头重做），C-3 未开

## 阻塞 / 等人
- 真机类实验（熄屏、占机）都必须用户给时点
- **BG-12**：GitHub classic PAT 未 revoke（要换 fine-grained 单仓 90 天 + 开 Secret scanning / Push protection）
- **C-1**（flags 重打 jar）押后到 C-2 验收有结论之后，否则又是注定 FAIL 的实验

## 设备态（2026-11-18 23:07 实测）
- 无 `oas` 虚拟屏（`grep -c 'DisplayInfo{"oas"'` = 0）、无 daemon / 游戏进程、`/data/local/tmp` 本轮临时文件已清
- `mWakefulness=Awake`、Operit `MainActivity` 在前台（`t944`）、`accelerometer_rotation=0`、`user_rotation=0`

## 已知腐坏（谁看谁修，别照抄）
- `docs/HANDOFF.md` §二 的 APK / jar 字节与自检数据已过期（以本文件 + `ls -l` 为准）
- `README.md:84` 的 APK 字节过期（实查 `dist/` 有 `oas-android-debug.apk` 与 `oas-bg22-debug.apk` 两个包）