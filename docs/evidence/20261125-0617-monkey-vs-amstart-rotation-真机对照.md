# `monkey` vs `am start` 对 `accelerometer_rotation` 的真机对照（结论：monkey 确实改坏）

## 1. 日期 / 分支 / commit
- 日期：2026-11-25（CST）
- 分支：`fix/startapp-am-start`；本文件随该分支提交（sha 以 `git log --oneline -1` 现查）

## 2. APK md5
- N/A（**未构建、未装机**；直接用 root shell 跑 `am`/`monkey`/`pidof`，验证的是命令链而非 APK 内的 Kotlin 代码）

## 3. 四段原始数字（单变量对照）

| 步骤 | accelerometer_rotation | user_rotation | pidof | 前台 |
|---|---|---|---|---|
| 基线 | 0 | 0 | 空 | Operit MainActivity |
| `am start -n com.netease.onmyoji/.tag0` → 等 6s | **0** | 0 | **32702** | `com.netease.onmyoji/.Client` |
| `am force-stop` → 等 2s | 0 | 0 | 空 | —— |
| `monkey -p com.netease.onmyoji -c android.intent.category.LAUNCHER 1` → 等 6s | **1** ❗ | 0 | **2839** | `com.netease.onmyoji/.Client` |
| `settings put system accelerometer_rotation 0`（复位） | **0** | 0 | —— | —— |

**两条启动命令都成功**（均有 pid、均把游戏带到前台）；**唯一差异是 `accelerometer_rotation`：`am start` 不动，`monkey` 0→1**。

`monkey` 原始输出关键行：
```
Events injected: 1
## Network stats: elapsed time=35ms (0ms mobile, 0ms wifi, 35ms not connected)
```

附带发现（命令链层面）：`cmd package resolve-activity --brief -c android.intent.category.LAUNCHER com.netease.onmyoji`
输出 launcher 组件为 **`com.netease.onmyoji/.tag0`**（activity-alias），运行后前台变为 **`.Client`**。

## 4. 设备状态（收工后）
- `accelerometer_rotation=0`（已复位）、`user_rotation=0`、`mWakefulness=Awake`
- 前台 `com.ai.assistance.operit/.ui.main.MainActivity`；`oas` 虚拟屏 0；游戏进程已停（`pidof` 空）
- 未装机、未起虚拟屏、未建 daemon

## 5. 结论
- **「`monkey` 会把 `accelerometer_rotation` 写成 1」= 真机复现，成立**（0→1，单变量对照）。
- 据此，该说法从「因果未定」升级为 **「真机已复现（本机 ColorOS / Android 14）」**。
- 与 `20261125-0530-monkey-rotation-未定因.md` 的关系：那份基于「AOSP/LineageOS stock 源码里 `MonkeyRotationEvent` 只 freeze/thaw、不改 settings」而判为未定因；
  本次真机结果为**相反证据**。合理解释：**源码分析只覆盖 stock 行为，厂商 ROM（或 freeze/thaw 的下游环节）可能在别处写 settings**；
  这正说明「源码核查 ≠ 真机行为」，因果判定必须以真机单变量对照为准。
- **不做过度归因**：本次只证明「跑 monkey 之后该值变 1」，未定位具体写入方（未开 `settings` 写入监控）。

## 6. 对代码的影响
- `Device.startApp()` 弃用 `monkey` 现具有**两条独立理由**：
  1. `monkey` 是测试工具（随机事件注入），生产启动应走 `am start`；
  2. **真机实测：`monkey` 会把 `accelerometer_rotation` 写成 1，而 `am start` 不会**（本记录）。
- 因此该改动从「风格改进」升级为「**消除可复现的设备污染**」。
