# BG-22 · E3 熄屏三组对照：虚拟屏在熄屏态到底有没有持锁

> 时间：2026-11-15 15:46–15:48（设备本地）｜分支 `feat/bg22-setSurface`，代码基线 `656ec1a`
> 执行：OAS-A4（只读测量，全程在**同一次熄屏窗口**内完成，中途不按电源键）｜判读：主 AI
> 目的：`mHoldingDisplaySuspendBlocker` 在主屏亮时恒为 `true`，无法区分；必须在熄屏态比对「无虚拟屏 / attach / detach」三组。

## 一句话结论（**推翻原动机**）

三组结果**完全一致**：`mWakefulness=Dozing`、`mHoldingDisplaySuspendBlocker=false`、`/sys/power/wake_lock` 为空，且 **`dumpsys power` 里连 `displayId:0` 的 `SCREEN_BRIGHT` 行都没有** —— 也就是说：

> **主屏熄屏后，虚拟屏根本没有持有 `SCREEN_BRIGHT` 锁，也就谈不上「它让整机不肯休眠」。**
> BG-22 的原始动机（虚拟屏持常亮锁 → 整机不睡）**在熄屏态不成立**；`setSurface(null)` 的真实收益只剩「熄屏期间停止渲染」（省 GPU/CPU），而不是「解除常亮锁」。

## 环境与方法

- 起始 `dumpsys display | grep -c 'oas-'` = **0**；daemon 沿用常驻 `oas.chan`（pid 20643，未重启）
- 全程只在 G1 开头按一次 `input keyevent 26` 熄屏，中间不按键，第 8 步再按一次唤醒
- 每组采两次（相隔 15s），探针：`dumpsys power | grep -E 'mWakefulness=|mHoldingDisplaySuspendBlocker'`、`cat /sys/power/wake_lock`、`dumpsys power | grep -E 'SCREEN_BRIGHT_WAKE_LOCK'`
- G1 = 无虚拟屏；G2 = 新建 `oas-e3`（**displayId=29**，1280×720@240）并把阴阳师拉上去；G3 = 对该屏 `surface detach`

## 原始数据

| 组 | 时刻 | `mWakefulness` | `mHoldingDisplaySuspendBlocker` | `/sys/power/wake_lock` | `SCREEN_BRIGHT_WAKE_LOCK` 行 |
|---|---|---|---|---|---|
| G1a 无虚拟屏 | 15:46:59 | `Dozing` | **`false`** | `[]` | **无** |
| G1b | 15:47:14 | `Dozing` | `false` | `[]` | — |
| G2a 虚拟屏 attach + 游戏在渲 | 15:47:35 | `Dozing` | **`false`** | `[]` | **无** |
| G2b | 15:47:51 | `Dozing` | `false` | `[]` | — |
| G3a `surface detach 29` 后 5s | 15:48:09 | `Dozing` | **`false`** | `[]` | **无** |
| G3b | 15:48:25 | `Dozing` | `false` | `[]` | — |

- `detach 29` 返回 ok，紧随的 `stats 29` = frames **443**
- 收尾：`destroy 29` ok；`input keyevent 26` 后 `mWakefulness=Awake`、`wl=[]`、`oas-` 计数 **0**（设备已还机）

## 判读

1. **原动机被证伪**：主屏熄屏 → 主屏自身不持 `SCREEN_BRIGHT`（`displayId:0` 行消失属正常），**虚拟屏的锁也随之不存在**；`mHoldingDisplaySuspendBlocker` 在三种状态下都是 `false`。所以「虚拟屏让整机不能睡」这个说法**在熄屏态没有证据**。
2. **锁只在主屏亮着时出现**：E2 基线（亮屏）明确存在 `SCREEN_BRIGHT_WAKE_LOCK 'WindowManager/displayId:28'`；一旦主屏熄屏，E3 的 G2 里同样配置下**完全采不到该行**。→ 该锁是「display 处于 ON」的副产物，不是虚拟屏独立索取的资源。
3. **`setSurface(null)` 的收益缩水为「停渲」**：E1 已证熄屏期间虚拟屏仍在出帧（+289），所以 detach 确实能让游戏停渲、省 GPU/CPU；但它**换不来「让整机进入更深休眠」**这种收益，因为那个阻塞本来就不存在。
4. **与本机既有结论自洽**：BG-21 pilot 已证本机灭屏待机**根本不进 suspend**（`suspend_stats/success` 1165→1165），所以「整机能不能睡」在本机本来就不是虚拟屏能左右的问题。

## 影响

- **BG-22 的取舍论证要重写**：不能再写「解得掉常亮锁、省电收益明确」，只能写「熄屏时停渲，省 GPU/CPU」。这削弱了「合入默认开」的必要性 —— 因为代价（游戏停摆、需广播触发、BG-28 四条 P1）没变，收益变小。
- **BG-28 ⑤ 的注释必须保持「假设」**：`mHoldingDisplaySuspendBlocker` 在熄屏态本来就是 `false`，detach 与它也无关，注释里那句「detach 能解除该锁」应直接删掉。
- **E2 的「锁消失」结论仍然成立**（亮屏态 detach → 该 display 的 `SCREEN_BRIGHT` 被 `REL`），只是它的意义是「亮屏态下让该屏不再计入 bright」，对整机休眠无影响。

## 未验 / 后续

1. 熄屏态虚拟屏是否进入 `STATE_OFF`（E3 未采 `dumpsys display` 的 state 字段）—— 若熄屏时 state 仍 `ON`，detach 的「state 收益」也需重新界定
2. 熄屏期间游戏是否因此降帧/停渲（E1 已测出帧在涨，说明未停；如需量化，需在熄屏窗口内 `stats` 两次）
3. 若要维持「合入」的决定，需要换一个新的、可量化的收益判据（例如同一 65s 内 CPU 时间对比），否则应判「零收益 → revert」

## 复现命令块

```sh
# 同一次熄屏窗口内跑完，中途不按电源键
dumpsys display | grep -c 'oas-'                    # 起始应 0
input keyevent 26; sleep 8
dumpsys power | grep -E 'mWakefulness=|mHoldingDisplaySuspendBlocker'
echo "wl=[$(cat /sys/power/wake_lock)]"
dumpsys power | grep -E 'SCREEN_BRIGHT_WAKE_LOCK'    # 期望：无

# （proot，屏幕仍灭）create oas-e3 1280 720 240 16457
# am start --display <id> -n com.netease.onmyoji/com.netease.onmyoji.Client
# 重复上面三条采集 → 仍应 false / [] / 无

# （proot）surface detach <id> → 再采 → 仍应 false / [] / 无
# （proot）destroy <id>；input keyevent 26（唤醒）；monkey -p com.ai.assistance.operit -c android.intent.category.LAUNCHER 1
```