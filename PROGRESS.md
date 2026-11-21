# PROGRESS.md — 位置（干到哪了、下次从哪接）

> 更新时间：**2026-11-22 00:36** ｜ **上限 40 行**：只写「现在」，历史交 `git log`。

## 当前
- `main` = 最新合并提交（要具体 sha 就 `git log --oneline -1`；本文件不记，见 D-013）；本地与远端都只剩 `main`；`git status` 干净
- `server.jar` = **29,253 B**（未重打，md5 `a7830c84…`）
- 最近合并：**现查 `git log --oneline -3`**（本文件不写 PR 号/sha，见 D-013、D-017）
- 可分发 APK：`app/build/outputs/apk/debug/app-debug.apk`（字节以 `ls -l` 为准）；`dist/` 两个包是旧的，未更新
- **BG-32 P0-A 已结案**、**BG-35 C-2 已结案**（证据 `docs/evidence/20261118-2300-*` 与 `20261118-2235-*`）

## BG-34 结论（2026-11-19 两轮实测 + 两次 4-eyes）
- **原前提「`input -d` 不投递」不成立**：motion 投递成功（`TouchStatesByDisplay` 出现该窗口 target、`firstDownTimeInTarget` 非零，全程无 `Dropping`）
- 真实剩余问题：**key 在无 focused window 的屏上被丢**（AOSP `:2313`）→ C-1（`TRUSTED|OWN_FOCUS`）降级为「仅 key 需要」的可选项
- 旧证据 `frames 恒 544` 归因（推断）= **真冻结**（采集链路健康：frames 单调涨、copies 同步、md5 逐帧不同）
- ⚠ **判据纠正**：`frames` 与**整帧 md5 都不能当点击生效判据**（静止画面仍有 ~1.2% 像素在动）；可观测判据见 `/tmp/outbox/A7-step1-observable.md` §结论②

## 在飞
- **无在飞 PR**（上一轮已合并回 main；要具体 sha 现查 `git log --oneline -1`，本文件不记，见 D-013/D-017）

## 下一步（按序做）
1. **BG-34 结案文档**：evidence + `DECISIONS.md` 追加「motion 通、仅 key 需焦点」+ `BACKLOG` 状态行（**已落**，见 DECISIONS D-020）
2. **模板漂移量化**：`tools/tplmatch.py`（proot 有 numpy+PIL、无 cv2）+ 用户截图 `1234.jpg` 作基线
3. **引擎烟测（L2 最大关）**：让 `vendor/oas` 在 APK 内 import + 跑一个纯识别步骤，暴露真实缺哪些依赖

## 阻塞 / 等人
- 真机类实验（熄屏、占机）必须先得到用户给的时点
- **BG-12**：GitHub classic PAT 未 revoke（换 fine-grained 单仓 90 天 + 开 Secret scanning / Push protection）
- **C-1** 已降级为可选项，不在主线上

## 设备态（2026-11-19 22:11 实测）
- 无 `oas` 虚拟屏（`DisplayInfo{"oas"`=0）、无 daemon / 游戏进程、`/data/local/tmp` 与 `/sdcard/Download` 无本轮文件
- `mWakefulness=Awake`、Operit 在前台、`accelerometer_rotation=0`、`user_rotation=0`

## 已知腐坏 / 别再照抄（谁看谁修）
- 用户报告「实验后主屏上半部触控不灵、重启恢复」**尚未取证**；D2 已核：触摸状态按 display 分桶（`InputDispatcher.h:390`），主 AI 原「系统级共享」推断**已被证伪**
- `docs/HANDOFF.md` §二 的 APK / jar 字节与自检数据已过期；`README.md:84` 的 APK 字节过期
- `vendor/oas` 既非 git 仓库、也无 `.oas-ref` → **无法追溯 pin**（`fetch_vendor.sh` 默认 `nearaura @ dev`）
