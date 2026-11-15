# 虚拟屏 + 后台跑游戏：判定链、可行手法与硬限制（只读调研）

日期：2026-11-15
范围：Android 10 ~ main（Android 17）AOSP + scrcpy 源码 + 两个同类项目源码
证据等级标注：`[AOSP:文件:行]` / `[scrcpy:文件:行]` / `[文档]` / `[待实测]`
说明：所有 AOSP 行号取自 `refs/heads/main` 当前快照，链接已给出，后续分支变动可能导致行号漂移。

---

## 0. 结论先行

1. 把**别的 app** 拉到虚拟屏上跑，判定在 `ActivityTaskSupervisor`，三条放行路径只有一条对我们可用：**建屏者 uid == 发起者 uid（ownerUid）**。所以「root/shell 建屏 + 同一 uid 的 `am start --display`」是官方判定链里唯一不依赖签名权限的路子。
2. 虚拟屏 **必须带 PUBLIC**，否则只有建屏 app 自己的 uid 和已在该屏上的 Activity 能进来。
3. `FLAG_SECURE` 的游戏在非 SECURE 虚拟屏上**必定黑图**，这是 AOSP 明文行为，不是 ROM 玄学；`VIRTUAL_DISPLAY_FLAG_SECURE` 是系统权限。
4. 熄屏/Doze **不影响虚拟屏合成**，虚拟屏的电源态与物理屏相互独立；但会限制 CPU，daemon 侧必须自己持有 PARTIAL 锁。
5. 二级 `startActivity` 不带 `launchDisplayId` 会继承 task 所在屏；`singleTask`/已有任务在主屏时表现为「跳回主屏」——这是应用内跳转类问题的根因，**不能靠建屏参数解决**，只能靠冷启动/独占 task。

---

## 1. 判定链：App 为什么进不了虚拟屏

### 1.1 入口与调用顺序

`am start --display <id>` 只是把 displayId 塞进 `ActivityOptions`：

- `[AOSP:services/core/java/com/android/server/am/ActivityManagerShellCommand.java:4381]`
  `--display <DISPLAY_ID>: The display to launch the activity into.`
- 解析点：同文件 `2579`、`3114`（`--display must be a non-negative integer`）。

WMS 侧实际判定顺序（`ActivityTaskSupervisor`）：

| 步骤 | 位置 | 语义 |
|---|---|---|
| ① 默认屏直接放行 | `ActivityTaskSupervisor.java:417-420` | `displayId == DEFAULT_DISPLAY` 不检查 |
| ② 多屏特性开关 | `:421-424` | `mSupportsMultiDisplay == false` 直接拒绝 |
| ③ 是否允许落在该屏 | `:426-431` → `isCallerAllowedToLaunchOnDisplay()` | 核心闸门 |
| ④ 该屏能否容纳这些 Activity | `:433-450` | `DisplayContent.mDwpcHelper.canContainActivities(...)` |

### 1.2 `isCallerAllowedToLaunchOnDisplay` 的五道关（`ActivityTaskSupervisor.java:1215-1293`）

| 行 | 条件 | 结果 |
|---|---|---|
| `1220-1223` | caller pid/uid 都是 -1（系统内部调用） | **放行** |
| `1227-1230` | 目标 DisplayContent 不存在/已移除 | 拒绝（log `Launch on display check: display not found`） |
| `1232-1235` | `Display.FLAG_REAR`（后屏） | 拒绝 |
| `1239-1244` | 持有 `INTERNAL_SYSTEM_WINDOW` 权限 | **放行**（uid 1000/system 才有；普通 app 与 uid 2000 shell 都没有） |
| `1250-1265` | 屏**不受信**（`!display.isTrusted()`） | 要求目标 Activity 自己声明 `ActivityInfo.FLAG_ALLOW_EMBEDDED`，且调用方有 `ACTIVITY_EMBEDDING` 或该 uid 已在屏上 |
| `1267-1275` | 是 **public** 屏 | 还要 `UserManagerInternal.isUserVisible(userId, displayId)` 为真（多用户可见性） |
| `1279-1283` | `display.getOwnerUid() == callingUid` | **放行** ← 我们的主路径 |
| `1285-1289` | 调用方 uid 已在该屏上有 Activity | 放行 |
| `1291` | 以上都不满足 | `Slog.w(TAG, "Launch on display check: denied")` |

**关键含义**：

- uid 2000（adb shell）/ root 也**没有** `INTERNAL_SYSTEM_WINDOW`，所以「shell 一定能进」是错觉——shell 进去靠的是**它自己是建屏者（ownerUid）**。
- 失败信号可 grep：logcat 里的 `Launch on display check: denied` / `... disallow launch on virtual display for not-embedded activity`（`1254-1256`）。

### 1.3 各种 VirtualDisplay flag 的语义（`core/java/android/hardware/display/DisplayManager.java`）

| flag | 行 | 官方语义（原文要点） | 对本项目的意义 |
|---|---|---|---|
| `PUBLIC = 1<<0` | `237` | 不设时是 private 屏，只有「建屏的 uid」和「已在该屏的 Activity」能交互（`228-230`） | **必须设**，否则游戏包名进不去 |
| `PRESENTATION = 1<<1` | `263` | 声明为 presentation 类显示 | 可设，无副作用 |
| `SECURE = 1<<2` | `289` | 不设即「unsecure」，**secure 窗口的内容在该屏上会被 blank（黑图）**（`280-283`）；该权限「reserved for system components，不对三方开放」（`276-277`） | FLAG_SECURE 游戏的死结 |
| `OWN_CONTENT_ONLY = 1<<3` | `316` | 只显示本屏自己的内容，不镜像主屏内容；PUBLIC+AUTO_MIRROR 都没设时是默认行为（`309-312`） | 我们要的语义（游戏独占这块屏） |
| `AUTO_MIRROR = 1<<4` | `346` | 镜像默认屏；需 `CAPTURE_VIDEO_OUTPUT` / `CAPTURE_SECURE_VIDEO_OUTPUT`，系统专用（`335-338`） | 不适用 |
| `SUPPORTS_TOUCH = 1<<6` | `384` | 声明支持触摸注入 | 需要输入时设 |
| `ROTATES_WITH_CONTENT = 1<<7` | `402` | 屏随内容旋转 | 横屏游戏相关 |
| `DESTROY_CONTENT_ON_REMOVAL = 1<<8` | `416` | 销毁屏时干掉上面的内容 | 清理用 |
| `TRUSTED = 1<<10` | `439` | `@SystemApi @hide`，「trusted to show system decorations and receive inputs without users' touch」（`431-432`） | 三方 app 拿不到 → 只能靠 ownerUid 路线 |
| `OWN_DISPLAY_GROUP / ALWAYS_UNLOCKED / TOUCH_FEEDBACK_DISABLED` | `448/459/468` | Android 13 起 | 可选 |
| `OWN_FOCUS / DEVICE_DISPLAY_GROUP` | `483/492` | Android 14 起 | 焦点/输入隔离，值得开 |
| `STEAL_TOP_FOCUS_DISABLED = 1<<16` | `505` | main（Android 17）新增 | 未取到语义，`[待实测]` |

### 1.4 逐版门槛变化（能证实到哪一步）

- flag 面：Android 13 增加 TRUSTED/OWN_DISPLAY_GROUP/ALWAYS_UNLOCKED/TOUCH_FEEDBACK_DISABLED，Android 14 增加 OWN_FOCUS/DEVICE_DISPLAY_GROUP —— 由 scrcpy 自己的 SDK 分支给出旁证：`[scrcpy:server/src/main/java/com/genymobile/scrcpy/video/NewDisplayCapture.java:224-233]`。
- 判定链面：`isCallerAllowedToLaunchOnDisplay` 在 Android 10/11/12/13/14/main 的结构（trusted → ownerUid → uidPresentOnDisplay → isUserVisible）未见收紧；**未找到** 15/16/17 针对虚拟屏启动新增限制的官方记录，`STEAL_TOP_FOCUS_DISABLED`（`505`）语义未核实 → 标 `[待实测]`，不作结论。

---

## 2. scrcpy `--new-display` 的服务端实现

### 2.1 建屏

`[scrcpy:server/src/main/java/com/genymobile/scrcpy/video/NewDisplayCapture.java:211-235]`

```java
int flags = VIRTUAL_DISPLAY_FLAG_PUBLIC | VIRTUAL_DISPLAY_FLAG_PRESENTATION
          | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
          | VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT;
if (vdDestroyContent)        flags |= DESTROY_CONTENT_ON_REMOVAL;
if (vdSystemDecorations)     flags |= SHOULD_SHOW_SYSTEM_DECORATIONS;
if (SDK_INT >= 33)           flags |= TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED | TOUCH_FEEDBACK_DISABLED;
if (SDK_INT >= 34)           flags |= OWN_FOCUS | DEVICE_DISPLAY_GROUP;
ServiceManager.getDisplayManager().createNewVirtualDisplay("scrcpy", w, h, dpi, surface, flags);
```

要点：

- scrcpy 走的是 **`ServiceManager` 直呼 `IDisplayManager`**（不经 `DisplayManager` 公开 API），所以能塞 `@SystemApi/hide` 的 TRUSTED 等 flag；它本身以 shell(2000)/root 起的 app_process 身份运行。
- `setDisplayImePolicy(virtualDisplayId, ...)`（`240-242`）单独设置该屏输入法策略——游戏要输入时必须处理。
- IME 策略必须是 `-1`（不改）之外的值时，`--display-ime-policy` 才有意义（同上）。

### 2.2 `--start-app` 与 `+` 前缀

- CLI 帮助：`[scrcpy:app/src/cli.c:890-903]`
  `Add a '+' prefix to force-stop before starting the app` / 例 `scrcpy --new-display --start-app=+org.mozilla.firefox`。
- 服务端：`[scrcpy:.../control/Controller.java:774-775]`
  ```java
  private void startApp(String name) {
      boolean forceStopBeforeStart = name.startsWith("+");
  ```
  目标屏由 `getStartAppDisplayId()`（`807`）决定，即「刚建的新屏」，找不到就报错退出（`808-812`）。
- 真正启动：`[scrcpy:.../device/Device.java:298-321]`
  ```java
  launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
  ActivityOptions launchOptions = ActivityOptions.makeBasic();
  launchOptions.setLaunchDisplayId(displayId);
  if (forceStop) am.forceStopPackage(packageName);
  am.startActivity(launchIntent, options);
  ```
  并**不做** `am` shell 调用，而是直接 `IActivityManager.startActivity` —— 此时 callingUid 是 shell/root，配合「它自己建的屏（ownerUid）」正好命中 `ATS:1279`。

**`+` 为什么必要**：不清掉旧实例时，`startActivity` 遇到已存在的任务/单实例（`singleTask`/`singleInstance`/`FLAG_ACTIVITY_NEW_TASK` 且已有 task）只会把**已有实例**拉到前台——而那个实例在默认屏，于是就"回了主屏"。`force-stop` 保证任务被清掉，新任务才会按 `launchDisplayId` 落在虚拟屏。`[依据:ATS:1279-1289 + Device.java:307-320 组合推断，行为已由 scrcpy 自己的 CLI 文档背书；严格指针级复现待实测]`

---

## 3. 同类项目源码核对

### 3.1 `Hy-ou/workbuddy-agent-apk` — `android/vds-helper/src/com/wb/vds/Server.java`

- 启动方式：`[Server.java:24-25]` `app_process -Djava.class.path=<app files>/vds.dex /system/bin com.wb.vds.Server W H DPI DIR`（Shizuku 以 shell/root 拉起）。
- 设计理由自述：`[Server.java:27-30]`
  > 为什么必须由 shell/root 创建：Android 17 的 ActivityTaskSupervisor 明确限制「普通 App 在虚拟屏上启动其他 App」……让 shell/root 成为显示 owner，再用同一 uid 的 `am start --display` 启动。
  > （同处还提到 `SecurityManagerService.isVirtualDisplayActive` 会直接拒绝 —— 这条**未在 AOSP 中检索到对应实现**，属国产 ROM 私有服务，标 `[待实测]`）
- 建屏 flags：`[Server.java:106-108]` `VIRTUAL_DISPLAY_FLAG_PUBLIC | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY` —— 与 AOSP 判定链自洽（PUBLIC 让别人能进，OWN_CONTENT_ONLY 不镜像主屏）。
- 取帧：`ImageReader`（`78-84`），把 `displayId` 与最新帧 via 私有缓存目录交接（`32-33`），不跨进程传帧、不用 `screencap`。
- **对本项目的价值**：与我们的架构（root app_process + 虚拟屏 + app 只读缓存）几乎同构，说明「建屏放 daemon、app 端只拿 id/帧」是被验证过的形态。

### 3.2 `kilolonion/agent-vscreen`

- 仓库存在（GitHub API 返回 `full_name=kilolonion/agent-vscreen`），但**本轮未取到源码结论**，不作任何技术断言。

### 3.3 云手机 / 双开容器（红手指、VMOS、VirtualApp 类）

- 红手指 / VMOS 为闭源商业产品，**无源码可引**，只能记录"闭源、无法核对"。
- VirtualApp 类（开源双开）路线与虚拟屏路线**本质不同**：它靠 Java 层 Hook `ActivityManager`/`Instrumentation` 做 **io 重定向 + 虚拟 uid**，让目标 app 以为自己在默认屏跑；不建真虚拟屏，因此不存在本文第 1 节的启动闸门问题，但**拿不到"后台独立屏"语义**，也无法在多任务下与主屏解耦。
- 结论：双开容器解决的是"多开/隔离"，我们要的是"后台独占一块屏"，两者不能互相替代。`[依据：VirtualApp README 的 io 重定向描述；未逐行核对源码 → 标 [待实测]]`

---

## 4. 游戏场景的具体坑

| 坑 | 机制/证据 | 应对 |
|---|---|---|
| `FLAG_SECURE` → 黑图 | `DisplayManager.java:280-283`：非 SECURE 虚拟屏上 **secure 窗口内容被 blank**；`VIRTUAL_DISPLAY_FLAG_SECURE` 系统权限（`276-277`） | 无 root 手段；root 下也要以 system/持 `CAPTURE_SECURE_VIDEO_OUTPUT` 的身份建 SECURE 屏 → 实际多以"该游戏无 FLAG_SECURE"为前提 `[待实测]` |
| 应用内跳转回主屏 | 二级 `startActivity` 不带 `launchDisplayId` 时继承 task 所在屏；`singleTask` 已存在于默认屏 → 任务被带到默认屏（`ATS:1279-1289` 的组合后果） | 冷启动（`force-stop` + `am start --display`）、或让游戏任务成为该屏的 root task；**不要指望建屏参数** |
| Unity/UE 用 SurfaceView | `OWN_CONTENT_ONLY` 屏上只有该屏内容，SurfaceView 走 hardware composer；读回需 `PixelCopy`/`ImageReader`，直接 `screencap` 只得到主屏 | 用 `ImageReader`（workbuddy `Server.java:78`）或 `VirtualDisplay` 自带 surface 取帧 |
| HARDWARE bitmap 读回失败 | `Bitmap.Config.HARDWARE` 的像素不可 `getPixels()`；`ImageReader` 需选 `PixelFormat.RGBA_8888` + `USAGE_CPU_READ_OFTEN` | 建 ImageReader 时显式 `ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2)`，别用默认 |
| 横屏游戏 | 虚拟屏自身**不随内容旋转**，scrcpy 注释明确：`NewDisplayCapture.java:192-194`「virtual display video always remains in its original orientation, so it must be rotated manually」 | 建屏直接给横屏尺寸（1280×720），或按 `ROTATES_WITH_CONTENT` + 自己在管线里转 |
| 熄屏/深睡是否继续合成 | `VirtualDisplayAdapter.java:372` 构造时 `mDisplayState = Display.STATE_ON`；`641-645` 只有 `mSurface == null` 时才 `STATE_OFF`；`462-473` 仅在被显式 request 时改（companion device 路径）。即**虚拟屏电源态独立于物理屏** | 合成不断，但 CPU 可能被 Doze 限流 → daemon 需自己持 PARTIAL 锁（本项目已有 `DaemonWakeLock`） |
| 输入注入 | 需 `SUPPORTS_TOUCH`（`DisplayManager.java:384`）+ `OWN_FOCUS`/`DEVICE_DISPLAY_GROUP`（`483/492`，Android 14+） | 14+ 全开，否则输入事件落主屏 |

---

## 5. 本机该走哪条路（Realme / ColorOS / Android 14 / Magisk root）

| 路线 | 判定 | 依据 |
|---|---|---|
| **A. root daemon 建 PUBLIC 虚拟屏 + 同 uid `am start --display`** | ✅ 唯一无签名权限的合法路径 | `ATS:1279-1283`（ownerUid 放行）+ `DisplayManager:237`（PUBLIC） |
| B. app 进程（uid 10xxx）自己建屏并 launch | ❌ 双重拒绝 | 非 trusted 且目标未声明 `FLAG_ALLOW_EMBEDDED` → `ATS:1253-1257`；无 `INTERNAL_SYSTEM_WINDOW` → `1239` |
| C. app 内 `ActivityOptions.setLaunchDisplayId` | ❌ 需要 `ACTIVITY_EMBEDDING` + 目标 `FLAG_ALLOW_EMBEDDED`（游戏 APK 不可控） | `ATS:1259-1264` |
| D. `wm size` 改主屏后当"虚拟屏"用 | ❌ 违反项目硬约束，且主屏不可用 | 项目约定 + 本表无需 AOSP 依据 |
| E. MediaProjection 建屏 | ❌ 只能镜像，不能把别的 app 拉进去；且受 SECURE 限制 | `DisplayManager:335-341` + `280-283` |

### 硬限制（改不了的部分）

1. 普通 app 永远拿不到 `VIRTUAL_DISPLAY_FLAG_TRUSTED`（`439` `@SystemApi`）→ 必须 root 侧建屏。
2. 没有 `INTERNAL_SYSTEM_WINDOW` 就进不了"别人的屏"，uid 2000 也不例外（`1239-1244`）。
3. 非 SECURE 屏上 secure 窗口必黑（`280-283`）；SECURE 需系统权限（`276-277`）。
4. public 屏还要过 `isUserVisible`（`1267-1275`）——多用户/分身场景会额外失败。
5. 二级跳转继承 task 屏幕：应用自己 `startActivity` 决定落点，外部无法拦截（第 4 节第 2 行）。
6. 虚拟屏不随内容自动转向，横屏必须建屏时给对尺寸（`NewDisplayCapture.java:192-194`）。

---

## 附：引用清单

- AOSP `ActivityTaskSupervisor.java`
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/ActivityTaskSupervisor.java
- AOSP `DisplayManager.java`（VirtualDisplayFlag）
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/display/DisplayManager.java
- AOSP `VirtualDisplayAdapter.java`
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/display/VirtualDisplayAdapter.java
- AOSP `ActivityManagerShellCommand.java`
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ActivityManagerShellCommand.java
- scrcpy `NewDisplayCapture.java` / `Controller.java` / `Device.java` / `cli.c`
  https://github.com/Genymobile/scrcpy/tree/master/server/src/main/java/com/genymobile/scrcpy
- workbuddy `Server.java`
  https://github.com/Hy-ou/workbuddy-agent-apk/blob/main/android/vds-helper/src/com/wb/vds/Server.java

**待实测清单**（本轮只读，未验证）：`STEAL_TOP_FOCUS_DISABLED` 语义；`SecurityManagerService.isVirtualDisplayActive` 是否存在及行为；ColorOS 在客户端是否有额外拦截；FLAG_SECURE 游戏在本机的实际表现；`singleTask` 跳屏的现场复现。
