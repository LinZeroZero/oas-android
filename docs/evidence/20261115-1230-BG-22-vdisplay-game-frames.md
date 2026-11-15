# BG-22：虚拟屏跑游戏 + surface detach/attach 验证（亮屏）

> 时间：2026-11-15 12:30–12:45（设备本地）｜分支 `feat/bg22-setSurface` 基线 `cb1faea`
> 全部数据为真机实测原始值；**本轮未做熄屏实验**。

## 一句话结论

1. **坑**：`am start --display <id> -n …/.tag0` 看似成功（ActivityRecord 记在虚拟屏），但游戏内部跳转到 `.Client` 时**丢了 launchDisplayId**，窗口落到 Display #0（主屏），虚拟屏只拿到 45 帧静态白屏 —— 表现为「frames 卡住」而不是报错。
2. **解法**：`am force-stop` 后**直启 `.Client`**（`am start --display <id> -n com.netease.onmyoji/com.netease.onmyoji.Client`）即可稳在副屏连续出帧。
3. **BG-22 机制成立**：`surface detach` 让帧计数**精确冻结**、`surface attach` 让它**继续增长**，亮屏下即可复现，无需熄屏。**但熄屏广播触发（ACTION_SCREEN_OFF→detach）与熄屏下行为本轮未验。**

## 环境

- Android 14 / SDK 34 / RMX3560；主屏 1080×2412
- `cat /sys/power/wake_lock` = 空；`suspend_stats/success` = **1165**，`fail` = **135**
- 虚拟屏：1280×720 @ 240dpi，`flags=16457`（PUBLIC|OWN_CONTENT_ONLY|SUPPORTS_TOUCH|OWN_FOCUS），`dumpsys display` 确认 `touch VIRTUAL`、`state ON`、`owner com.android.shell (uid 0)`
- 本机 `cmd display` **无** power-off 子命令（熄屏只能靠 `input keyevent 26`，本轮按纪律全程未用）

## 方法

1. 常驻 daemon：`app_process -Djava.class.path=/data/local/tmp/kbdaemon.jar /system/bin org.onmyoji.oas.server.Main --socket oas.bg22`（jar 29225 B，含 `surface detach/attach` 与 `frames` 字段）
2. `create oas-game 1280 720 240` / `create oas-game2 1280 720 240 16457`
3. `am force-stop com.netease.onmyoji` → `am start --display <id> -n …`
4. 客户端：`nc -U @name` 在 toybox 不支持抽象命名空间（`ENOENT`），临时用仓库同款 javac+d8 编了一个 1.4 KB 的 `KbClient`（源码 `staging/kbclient/`，**不进仓库**，`/data/local/tmp/kbclient.jar`）：`KbClient oas.bg22 <command>`

## 发现 1：`.tag0 → .Client` 跳屏（新坑）

```
am start --display 23 -n com.netease.onmyoji/.tag0
list #1: frames=45  copies=9  hasFrame=true
list #2: frames=45  copies=9        （3s 后不变）
stats 再采样（+25s、+60s）: 仍 45
grab: bytes=3686416 mean=246.32 blackRatio=0.0000   ← 静态白屏
logcat: BufferQueueProducer [...onmyoji.Client...(BLAST)] queueBuffer: fps=59.96   ← 游戏其实在渲
dumpsys activity: Display #0 → Task{f1a8746 #880 …com.netease.onmyoji}
                  Hist #0: ActivityRecord{e145a7 …/.Client t880}
                  source=ActivityRecord{8ac2a98 …/.tag0 t879}（挂在 Display #25）
dumpsys window:  Window{661e7d8 …/.Client} mDisplayId=0
```

结论：`.tag0` 起来后自己跳 `.Client`，**跳转 Intent 未带 launchDisplayId**，窗口跑回主屏；虚拟屏只留下首帧白屏。
无 `Launch on display check: denied`、无 `not-embedded activity`、非 FLAG_SECURE（黑比例 0）。
`flags=9` → 补到 `16457`（+SUPPORTS_TOUCH +OWN_FOCUS）后 `touch` 才从 NONE 变 VIRTUAL，但**这并未修复跳屏**，跳屏是应用侧行为。

## 发现 2：直启 `.Client` 可稳在副屏

```
am force-stop com.netease.onmyoji
am start --display 25 -n com.netease.onmyoji/com.netease.onmyoji.Client
frames: 149 → 254 → 498 → 622        （持续增长）
grab: mean=83.42 blackRatio=0.0000   （真实游戏画面，不是白屏）
主屏 topResumedActivity = com.ai.assistance.operit/.ui.main.MainActivity（未被抢）
虚拟屏自己的 topResumedActivity = com.netease.onmyoji/.Client t881
```

## 发现 3：BG-22 detach/attach（亮屏即可复现）

| 步骤 | 观测 |
|---|---|
| 基线 A | frames=1691 |
| 5s 后 B | frames=1885（+194，约 39 fps） |
| `surface detach 25` → `{"ok":true,"displayId":25}` | C=2080，**再等 5s 仍 2080**（完全冻结） |
| detach 态 `grab 25` | mean=70.33 blackRatio=0.0028 frames=2080（拿到停渲前的旧帧，非全黑） |
| `surface attach 25` → `{"ok":true,"displayId":25}` | D=2262（5s 内 +182），grab 时 2294（**恢复增长**） |
| attach 后 `grab 25` | mean=72.28 blackRatio=0.0000（新帧，与 70.33 不同） |

主屏全程仍是 Operit；attach 后游戏没有跑回主屏。

## 未验项

- 熄屏广播触发：`ACTION_SCREEN_OFF → surface detach`、`ACTION_SCREEN_ON → attach`（`KeepAliveService` 的 `vdisplay_detach=1` 门控路径**一行都没跑过**）
- 熄屏下虚拟屏是否还继续合成 / 是否停合成
- CPU suspend 对本应用的影响（BG-21 已证：本机灭屏待机不进 suspend）
- OAS 脚本本体在副屏跑 65s（`kb run 5 5 65 <id>` 尚未在真实 displayId 上跑过）
- detach 期间游戏是否会因为「无输出端」被系统判定不可见而自行暂停/降帧（本轮只停 10s，未观察长时间行为）

## 复现命令块

```sh
# 全部在 root shell 里跑，且必须先 cd 到真实存在的目录（默认 cwd 无效会让 app_process 启动期 SIGSEGV）
cd /data/local/tmp

# 1) 常驻 daemon（jar 从工作区 poc/displayserver/build/server.jar 拷来，29225 B）
cp <WORKSPACE>/poc/displayserver/build/server.jar /data/local/tmp/kbdaemon.jar
nohup app_process -Djava.class.path=/data/local/tmp/kbdaemon.jar /system/bin \
  org.onmyoji.oas.server.Main --socket oas.bg22 > /data/local/tmp/kb/daemon.log 2>&1 &

# 2) 客户端（toybox nc -U 不支持抽象命名空间，需自备 KbClient）
KC='app_process -Djava.class.path=/data/local/tmp/kbclient.jar /system/bin KbClient oas.bg22'
$KC ping
$KC create oas-game 1280 720 240 16457

# 3) 关键：直启 .Client，不要经过 .tag0（否则窗口跳回主屏）
am force-stop com.netease.onmyoji
am start --display 25 -n com.netease.onmyoji/com.netease.onmyoji.Client
$KC stats 25

# 4) detach / attach 验证
$KC surface detach 25      # frames 应冻结
$KC grab 25 /data/local/tmp/kb/x.raw
$KC surface attach 25      # frames 应恢复增长
$KC grab 25 /data/local/tmp/kb/y.raw

# 5) 还机
am force-stop com.netease.onmyoji
$KC destroy 25
monkey -p com.ai.assistance.operit -c android.intent.category.LAUNCHER 1
cat /sys/power/wake_lock   # 期望为空
```
