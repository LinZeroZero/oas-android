# PROGRESS.md — 位置（干到哪了、下次从哪接）

> 更新时间：**2026-11-25 00:20** ｜ **上限 40 行**：只写「现在」，历史交 `git log`。

## 当前
- `main` = 最新合并提交（要具体 sha 就 `git log --oneline -1`；本文件不记，见 D-013）；本地与远端都只剩 `main`；`git status` 干净
- **流程 C 模式（D-029）**：改 → 开 draft PR → CI + 审查位 PASS → 转正式 → 用户签发 → squash 合并
- **角色（D-026/027）**：主 AI = 唯一执行位（全仓写 + commit/push + 开 PR + 合并）；`OAS-D2` = 只读审查位；用户 = 签发；必审/抽审清单见 `AGENTS.md §1`
- `server.jar` = **29,253 B**（未重打，md5 `a7830c84…`）
- 最近合并：**现查 `git log --oneline -3`**（本文件不写 PR 号/sha，见 D-013、D-017）
- 可分发 APK：`app/build/outputs/apk/debug/app-debug.apk`（字节以 `ls -l` 为准）；`dist/` 两个包是旧的，未更新
- **L2 烟测**：`vendor/oas` 作额外 Python 源根并**排除 `tasks/**` 等**（APK 105.9MB→64.8MB）；`module/base` 子集探针 `smoke_base()` 在机内 **ok=8/fail=0**（仅依赖 `rich`）已知风险：`module/device/**` 已 exclude（下轮烟测前须放开）；`module/config/**` 随包但依赖 pydantic 不可用

## BG-34 结论（2026-11-19 两轮实测 + 两次 4-eyes）
- **原前提「`input -d` 不投递」不成立**：motion 投递成功（`TouchStatesByDisplay` 出现该窗口 target、`firstDownTimeInTarget` 非零，全程无 `Dropping`）
- 真实剩余问题：**key 在无 focused window 的屏上被丢**（AOSP `:2313`）→ C-1（`TRUSTED|OWN_FOCUS`）降级为「仅 key 需要」的可选项
- ⚠ **判据纠正**：`frames` 与**整帧 md5 都不能当点击生效判据**（静止画面仍有 ~1.2% 像素在动）；可观测判据见 `/tmp/outbox/A7-step1-observable.md` §结论②

## 在飞
- **无在飞 PR**（上一轮已合并回 main；要具体 sha 现查 `git log --oneline -1`，本文件不记，见 D-013/D-017）

## 下一步（按序做）
1. **`module/device` 烟测**：放开 exclude + 摘 `platform2`（pydantic）与 `scrcpy`（av）→ 装机（需用户给时点）
2. **OCR 后端选型**：opencv-DNN / TFLite / ncnn 三选一（不占机）
3. **`tools/tplmatch.py`**：numpy NCC + 降采样，基线 `1234.jpg`
4. **BG-38 实现**（gate ⑨⑩⑪⑫）｜ **BG-37** ⑦ 加「只判新增条目」校验（存量已按 D-031 标「历史超标」豁免）

## 阻塞 / 等人
- 真机类实验（熄屏、占机）必须先得到用户给的时点
- **BG-12**：GitHub classic PAT 未 revoke（换 fine-grained 单仓 90 天 + 开 Secret scanning / Push protection）
- **C-1** 已降级为可选项，不在主线上

## 设备态（2026-11-19 22:11 实测）
- 无 `oas` 虚拟屏（`DisplayInfo{"oas"`=0）、无 daemon / 游戏进程、`/data/local/tmp` 与 `/sdcard/Download` 无本轮文件
- `mWakefulness=Awake`、Operit 在前台、`accelerometer_rotation=0`、`user_rotation=0`

## 已知腐坏 / 别再照抄（谁看谁修）
- 用户报告「实验后主屏上半部触控不灵、重启恢复」**尚未取证**；D2 已核：触摸状态按 display 分桶（`InputDispatcher.h:390`），主 AI 原「系统级共享」推断**已被证伪**
- `docs/HANDOFF.md` §二 的 APK/jar 字节与自检数据已过期（顶部已有 ⏳ 标注）；`README.md` 已改「字节现查」口径
- `vendor/oas` 既非 git 仓库、也无 `.oas-ref` → **无法追溯 pin**（`fetch_vendor.sh` 默认 `nearaura @ dev`）
