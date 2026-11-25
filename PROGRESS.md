# PROGRESS.md — 位置（干到哪了、下次从哪接）

> 更新时间：**2026-11-25 19:08** ｜ **上限 40 行**：只写「现在」，历史交 `git log`。

## 当前
- `main` = 最新合并提交（要具体 sha 就 `git log --oneline -1`；本文件不记，见 D-013）；本地与远端都只剩 `main`；`git status` 干净
- **流程 C 模式（D-029）**：改 → 开 draft PR → CI + 审查位 PASS → 转正式 → 用户签发 → squash 合并
- **角色（D-026/027）**：主 AI = 唯一执行位（全仓写 + commit/push + 开 PR + 合并）；`OAS-D2` = 只读审查位；用户 = 签发；必审/抽审清单见 `AGENTS.md §1`
- `server.jar` = **29,253 B**（未重打，md5 `a7830c84…`）
- 最近合并：**现查 `git log --oneline -3`**（本文件不写 PR 号/sha，见 D-013、D-017）
- 可分发 APK：`app/build/outputs/apk/debug/app-debug.apk`（字节以 `ls -l` 为准）；`dist/` 两个包是旧的，未更新
- **L2 烟测**：`vendor/oas` 作额外 Python 源根并**排除 `tasks/**` 等**（APK 105.9MB→64.8MB）；`module/base` 子集探针 `smoke_base()` 在机内 **ok=8/fail=0**（仅依赖 `rich`）已知风险：`module/device/**` 已 exclude（下轮烟测前须放开）；`module/config/**` 随包但依赖 pydantic 不可用
- **BG-40 OCR 已在 APK 内跑通（rec）**：真机 `text="123" score=0.9958`、**174.4ms**（proot 67ms，慢 2.6×）（n=1 不作结论）；⚠ Full/Quantity（~58）不可用（BG-41）；Single 系 rec 未命中→fallback det 抛异常；修掉 3 个真机 bug（`20261125-0832`）
- **`Device.startApp()` 已改 `am start -n` + 轮询 `pidof`**（不用 `-W` 避免长持锁）；**真机已验证**（2026-11-25）：`am start` 后 rotation 0→0，`monkey` 后 0→1（证据 `docs/evidence/20261125-0617-monkey-vs-amstart-rotation-真机对照.md`）（`monkey` 是测试工具不用于生产启动；「monkey 写坏 rotation」因果未定，见 `docs/evidence/20261125-0530-monkey-rotation-未定因.md`）

## BG-34 结论（已结案；细节见 evidence）
- motion **可投递**、仅 **key 需 focused window**（AOSP `:2313`）；⚠ **`frames` / 整帧 md5 都不能当点击判据**（静止画面仍有 ~1.2% 像素在动）——归因与阈值见 `docs/evidence/20261125-0433-BG-34-observable-criteria.md`

## 在飞
- **无在飞 PR**（数量现查；PR 号不记，D-013/D-017）

## 下一步（按序做）
1. **真实阴阳师截图复验 OCR**（中文/描边/复杂背景；需占机 + 用户给时点）
2. **BG-42 OCR 提速定标**（n≥5 取中位数；`FloatBuffer` 直传列第一优先，另测 release 构建/预热）
3. **BG-41 det**：Full/Quantity ~58 对象可用（两条路：纯 numpy unclip / 矩形框近似）
4. **`module/device` 烟测**（放开 exclude + 摘 `platform2`/`scrcpy`，需用户给时点）｜ gate 已扩到 12 项（BG-38/37 已落）

## 阻塞 / 等人
- 真机类实验（熄屏、占机）必须先得到用户给的时点
- **BG-12**：GitHub classic PAT 未 revoke（换 fine-grained 单仓 90 天 + 开 Secret scanning / Push protection）
- **C-1** 已降级为可选项，不在主线上

## 设备态（2026-11-25 19:08 实测）
- 无 `oas` 虚拟屏（`DisplayInfo{"oas"`=0）、无 daemon / 游戏进程；`/sdcard/oas_*` 无、`/data/local/tmp` 无本轮文件（有旧物 `daemon.log` 等，非本轮产物）
- `mWakefulness=Awake`、`user_rotation=0`、`accelerometer_rotation` 未设（默认 0）

## 已知腐坏 / 别再照抄（谁看谁修）
- 用户报告「实验后主屏上半部触控不灵、重启恢复」**尚未取证**；D2 已核：触摸状态按 display 分桶（`InputDispatcher.h:390`），主 AI 原「系统级共享」推断**已被证伪**
- `docs/HANDOFF.md` §二 的 APK/jar 字节与自检数据已过期（顶部已有 ⏳ 标注）；`README.md` 已改「字节现查」口径
- `vendor/oas` 既非 git 仓库、也无 `.oas-ref` → **无法追溯 pin**（`fetch_vendor.sh` 默认 `nearaura @ dev`）
