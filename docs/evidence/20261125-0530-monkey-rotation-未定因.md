# `monkey` 与 `accelerometer_rotation`：记录核查（结论：因果未定）

## 1. 被核查的说法
- 原文（`docs/handoff/20261118-1359-e1-handoff.md` §5 第 8 条）：「用 `monkey` 拉起前台 → 会把 `accelerometer_rotation` 写成 1（BG-31），一律 `am start -n`」。
- 该记录所引的 **BG-31 编号在 `docs/BACKLOG.md` 中 0 命中**（编号不存在）。
- `docs/evidence/` 中也没有任何 `monkey` / `accelerometer_rotation` 相关记录。

## 2. 外部核查（引用评审位，主 AI 未独立复算）
- D2 报（OAS-D2 评审，第 1 轮）：LineageOS-21.0（Android 14）`Monkey.java:716-719` 只在 finally 里
  `MonkeyRotationEvent(ROTATION_0,false)`；`MonkeyRotationEvent.java:53-56` 仅 `freezeRotation(0)+thawRotation()`，
  全文无 `Settings.System` / `ACCELEROMETER` / `putInt` → **AOSP stock 层面不成立**。
- 主 AI 复算尝试：`raw.githubusercontent.com` 取该文件三条路径均 **404**（未取到）→ 本条为**引用、未复算**。

## 3. 结论
- 「`monkey` 会把 `accelerometer_rotation` 写成 1」**因果未定**：可能是厂商 ROM 行为、或与其他操作混淆、或观察有误。
- **不得**以该因果作为任何代码决策的唯一依据；若要定因，需在真机上做单变量对照实验（前值/后值 + 若可，抓 `settings` 写入方）。

## 4. 对代码的影响
- `Device.startApp()` 改用 `am start -n` 的理由**不是**上述未定因，而是：「`monkey` 是测试工具（随机事件注入），生产启动应走 `am start`」。该理由独立成立。

## 5. 设备状态
- 本记录为文档核查，未动设备、未装机、未起屏。
