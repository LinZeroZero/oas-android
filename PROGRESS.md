# PROGRESS.md — 位置（干到哪了、下次从哪接）

> 更新时间：**2026-11-18 21:25** ｜ **上限 40 行**：只写「现在」，历史交 `git log`。

## 当前
- `main` = **最新合并提交**（要具体 sha 就 `git log --oneline -1`；本文件不记，见 `DECISIONS.md` D-013）；本地与远端都只剩 `main`；`git status` 干净
- `server.jar` = **29,253 B**（未重打）
- 最近合并：**PR #44** 预览临时 raw 清理修复（真机验收发现）；**PR #43** 瘦身纪律 + gate 第⑦项；**PR #42** 状态三件套 + gate 第⑥项；**PR #41** 口径④修正；**PR #40** C-2 实时预览
- 可分发 APK（PR #44 那次编译）：`app/build/outputs/apk/debug/app-debug.apk` = **48,584,270 B**；`dist/` 里两个包是旧的，未更新

## 在飞
- **无**。PR #44 合并后分支即删；下一个 PR 是 `docs/records-closeout`（下一条第 3 项）

## 下一步（按序做）
1. **BG-35 C-2 复验（小）**：真机验收 2026-11-18 21:08–21:14 已跑（抓帧 369/丢帧 51/grab 12ms，3 过 1 挂）；PR #44 已修缺陷，装新包只需再点一次开关，看回显从「清临时文件 0 个」变成删到真数（1 分钟级）
2. **BG-32 P0-A** 14 分钟真熄屏长跑（游戏 27.7MB 资源已更新，工具链两轮冒烟已过），等约时点
3. **记录收口**：Q5 结论 + BG-34 定责重写落 `docs/research/`；C1 的 `CHANGELOG`/`README`/`THIRD_PARTY_NOTICES` 欠账一次写完

## 阻塞 / 等人
- 真机类实验（熄屏、占机）都必须用户给时点
- **BG-12**：GitHub classic PAT 未 revoke（要换 fine-grained 单仓 90 天 + 开 Secret scanning / Push protection）
- **C-1**（flags 重打 jar）押后到 C-2 验收有结论之后，否则又是注定 FAIL 的实验

## 设备态（2026-11-18 21:15 实测）
- 无 `oas` 虚拟屏（`DisplayInfo{"oas"` 0 命中）、无 daemon / `app_process`、游戏未运行
- `mWakefulness=Awake`、`accelerometer_rotation=0`、`user_rotation=0`

## 已知腐坏（谁看谁修，别照抄）
- `docs/HANDOFF.md` §二 的 APK / jar 字节与自检数据已过期（以本文件 + `ls -l` 为准）
- `README.md:84` 的 APK 字节过期（实查 `dist/` 有 `oas-android-debug.apk` 与 `oas-bg22-debug.apk` 两个包）
- `CHANGELOG.md` 停在 11-15，**PR #30–#41 全未入账**