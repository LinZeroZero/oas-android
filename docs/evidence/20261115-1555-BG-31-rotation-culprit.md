# BG-31 定责：主屏自动旋转被打开的元凶 = `monkey` 清理命令

> 时间：2026-11-15 15:53–15:56（设备本地）｜分支 `feat/bg22-setSurface`
> 取证手段：常驻监视器 `/data/local/tmp/rotwatch.sh`（0.6s 一拍读 `accelerometer_rotation` / `user_rotation` / `mUserRotationMode`，变化时快照前台与 wm 事件）+ 手动逐步读值
> 结论：**我们这个项目里一直在用的清理命令 `monkey -p <pkg> -c android.intent.category.LAUNCHER 1` 会把 `accelerometer_rotation` 写成 `1`**，用户观感即「自动旋转被打开了」。

## 决定性 A/B（同一次运行，虚拟屏 `oas-ab` displayId=30 + 游戏在其上）

| 时刻/步骤 | `accelerometer_rotation` | `mUserRotationMode` | 监视器记录 |
|---|---|---|---|
| 基线（A 由用户手动关闭） | `0` | `LOCKED` | `15:53:39 SETTING_CHANGE A=0 … LOCKED` |
| 建屏 + 起游戏 | `0` | **`FREE`** | `15:54:46 MODE … FREE (A=0 U=0)` |
| 步骤 1 `am force-stop com.netease.onmyoji` | `0` | `FREE` | MARK 前后无 SETTING_CHANGE |
| **步骤 2 `monkey -p com.ai.assistance.operit -c android.intent.category.LAUNCHER 1`** | **`1`** | `FREE` | `15:55:11 SETTING_CHANGE A=1 …` |
| 收尾 `am start -n com.ai.assistance.operit/.ui.main.MainActivity` | `1`（不变） | `FREE` | MARK 之后无 SETTING_CHANGE |

**同一夜第二次独立复现**：`15:44:11 SETTING_CHANGE A=1`，对应 E2 收尾的同一句 `monkey`（前后 `am force-stop` 均未引起变化）。两次独立复现，中间夹着一步步隔离，指向同一句命令。

## 为什么之前被我排除掉了

我只查了 `monkey` 的帮助里有没有 `--pct-rotation`，见没有就宣布「生成不出旋转事件」——**这是查错了层次**：`--pct-rotation` 只是「可调百分比」的开关，不代表事件源不存在；本机 monkey 仍会按内置默认比例产生 rotation 事件，而 monkey 的 rotation 事件会落到用户的旋转设置上。这是本轮的方法论教训：**帮助文本里没列出的选项 ≠ 该行为不存在**。

## 顺带查明的第二个来源（现象 ①）

`mUserRotationMode` 变成 `USER_ROTATION_FREE` **不需要** `accelerometer_rotation=1`：

- `15:42:45 MODE … FREE (A=0 U=0)`
- `15:54:46 MODE … FREE (A=0 U=0)`（建屏 + 游戏上屏之后）

即：**开关关着，只要出现「横屏 Activity 上到虚拟屏 / 主屏」，RotationPolicy 也可能临时进入 FREE**，此时若传感器报横屏就会真的转。这解释了「你手动关了开关，屏幕有时还是会转」。此来源尚未定责到具体组件（候选：游戏上屏时的窗口方向请求、ColorOS 的显示策略），记为 BG-31 的第二个待查项。

## 影响与处置

1. **纪律级修正**：所有针对本项目的「把 App 拉到前台」操作**禁用 `monkey`**，改用 `am start -n <pkg>/<activity>`（或 `am start -W -a MAIN -c LAUNCHER -n …`）。历史上 E1 / E2 / BG-22 的收尾都用了 monkey，因此 12:52 与 15:15 两簇旋转很可能都是它留下的 `A=1` 状态。
2. **交付物清理**：`monkey` 只出现在操作命令里，仓库代码中无引用（代码 grep 0 处写旋转仍成立），不需要改代码。
3. **BG-31 结案口径**：主因已定责（monkey，两次复现）；次因（FREE 不需 A=1）留作后续观测，不阻塞任何主线。

## 复现命令块

```sh
# 前提：监视器在跑；记下自己的 MARK 时刻
echo "MARK step1" >> /data/local/tmp/rotwatch.log
am force-stop com.netease.onmyoji; sleep 6
echo "A=$(settings get system accelerometer_rotation)"        # 期望不变
echo "MARK step2" >> /data/local/tmp/rotwatch.log
monkey -p com.ai.assistance.operit -c android.intent.category.LAUNCHER 1; sleep 6
echo "A=$(settings get system accelerometer_rotation)"        # 期望 1（复现）
```

## 未验

1. FREE 模式的触发组件（是窗口方向请求，还是 ColorOS 的显示策略）—— 需要在建屏/上屏过程中抓 `wm_` 事件与 `dumpsys window` 的 `mLastOrientationSource`
2. monkey 的 rotation 事件具体走哪条 API（`WMS.setUserRotation` 还是写 `Settings.System`）—— 若要彻底解释「为什么写的是 1」，需读 AOSP `Monkey.java` / `MonkeySourceRandom.java` 的 rotation 分支