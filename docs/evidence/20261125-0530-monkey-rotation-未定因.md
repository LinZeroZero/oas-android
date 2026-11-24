# `monkey` 与 `accelerometer_rotation`：记录核查（结论：因果未定）

## 1. 日期 / 分支 / commit
- 日期：2026-11-25 05:30（CST）
- 分支：`fix/startapp-am-start`；本文件随该分支提交（sha 以 `git log --oneline -1` 现查）

## 2. APK md5
- N/A（纯文档核查，本轮未构建 APK）

## 3. 原始数字（核查产出）
| 项 | 值 |
|---|---|
| 被核查说法出处 | `docs/handoff/20261118-1359-e1-handoff.md` §5 第 8 条 |
| 该说法所引 `BG-31` 在 `docs/BACKLOG.md` 命中数 | **0** |
| `docs/evidence/` 中 `monkey` / `accelerometer_rotation` 记录数 | **0** |
| `Monkey.java` | 57,598 B（HTTP 200） |
| `MonkeyRotationEvent.java` | 2,035 B（HTTP 200） |
| `ACCELEROMETER\|Settings.System\|putInt` 在两文件命中 | **0 / 0** |
| `freezeRotation` / `thawRotation` | 第 53 / 55 行 |

可复现路径（2026-11-25 现取）：
```
https://raw.githubusercontent.com/LineageOS/android_development/lineage-21.0/cmds/monkey/src/com/android/commands/monkey/Monkey.java
https://raw.githubusercontent.com/LineageOS/android_development/lineage-21.0/cmds/monkey/src/com/android/commands/monkey/MonkeyRotationEvent.java
```
（注意：`monkey` 属 `platform/development`，**不在** `frameworks/base`；前者路径会 404）

## 4. 设备状态
- 未动设备、未装机、未起屏（文档核查）

## 5. 结论
- 「`monkey` 会把 `accelerometer_rotation` 写成 1」**因果未定**：
  - 该说法的唯一仓内出处是交接包（其所引 BG-31 编号不存在，无 evidence 记录）；
  - AOSP/LineageOS stock 的 `MonkeyRotationEvent` 只做 `freezeRotation`+`thawRotation`，全文无 `Settings.System` / `ACCELEROMETER` / `putInt` → **stock 层面不成立**（本轮已复算）；
  - 可能是厂商 ROM 行为、与其他操作混淆、或观察有误。
- **不得**以该因果作为任何代码决策的唯一依据；要定因需真机单变量对照（前值/后值 + 抓 `settings` 写入方）。

## 6. 对代码的影响
- `Device.startApp()` 弃用 `monkey` 的理由**不是**上述未定因，而是：「`monkey` 是测试工具（随机事件注入），生产启动应走 `am start`」——该理由独立成立。
