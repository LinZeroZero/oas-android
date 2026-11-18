# PROGRESS.md — 位置（干到哪了、下次从哪接）

> 更新时间：**2026-11-18 20:31** ｜ 只写「现在」；历史看 `git log` 与 `docs/HANDOFF.md`。

## 当前
- `main` = `fe916f9`（PR #41）；本地与远端都只剩 `main`；`git status` 干净
- `server.jar` = **29,253 B**（未重打）
- 最近合并：**PR #40** C-2 App 内实时预览 `a531ba4`；**PR #41** 口径④修正 + C-2 入账 `fe916f9`
- 可分发 APK（本轮编译）：`app/build/outputs/apk/debug/app-debug.apk` = **48,583,631 B**；`dist/` 里两个包是旧的，未更新

## 在飞
- **无**。所有 feature/docs 分支已删除，无未评审代码在跑。

## 下一步（按序做）
1. **BG-35 C-2 真机验收**（口径四条一条未跑）：装上面的 APK → 起 daemon + 建 `oas` 屏 → 游戏拉上去 → 打开 App 点「预览虚拟屏」→ 看画面动不动 / 主屏卡不卡 → 关开关确认屏**还在**。**要占手机，等用户约时点**
2. **BG-32 P0-A** 14 分钟真熄屏长跑（游戏 27.7MB 资源已更新，工具链两轮冒烟已过），等约时点
3. **记录收口**：Q5 结论 + BG-34 定责重写落 `docs/research/`；C1 的 `CHANGELOG`/`README`/`THIRD_PARTY_NOTICES` 欠账一次写完

## 阻塞 / 等人
- 真机类实验（熄屏、占机）都必须用户给时点
- **BG-12**：GitHub classic PAT 未 revoke（要换 fine-grained 单仓 90 天 + 开 Secret scanning / Push protection）
- **C-1**（flags 重打 jar）押后到 C-2 验收有结论之后，否则又是注定 FAIL 的实验

## 设备态（2026-11-18 20:1x 实测）
- 无 `oas` 虚拟屏（`DisplayInfo{"oas"` 0 命中）、无 daemon / `app_process`、游戏未运行
- `mWakefulness=Awake`、`accelerometer_rotation=0`、`user_rotation=0`

## 已知腐坏（谁看谁修，别照抄）
- `docs/HANDOFF.md` §二 的 APK / jar 字节与自检数据已过期（以本文件 + `ls -l` 为准）
- `README.md:84` 的 APK 字节过期（实查 `dist/` 有 `oas-android-debug.apk` 与 `oas-bg22-debug.apk` 两个包）
- `CHANGELOG.md` 停在 11-15，**PR #30–#41 全未入账**