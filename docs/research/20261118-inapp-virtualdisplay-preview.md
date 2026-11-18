# 调研：App 内实时预览虚拟屏（in-app live preview）

> **元信息**
> - 日期：2026-11-18｜关联：**BG-35**（立项：App 内实时预览）、**BG-34**（虚拟屏不可触 / activity PAUSED）
> - 触发：用户提供 `https://github.com/Shinarin/ALAS-AOS` + 内嵌虚拟屏截图，明确「这个项目就可以直接在软件里显示虚拟屏」，据此定口径 **q1=(b)「要 OAS 也能在 App 里内嵌实时预览」**
> - 执行：调研位 **OAS-D2**（`0ef69b6e-909b-4bdb-bb16-ef38d35eeeb7`）分三轮联网取证；本文件由**主 AI 落盘并做事实核对**（D 无写文件权限），凡与 D 回执不同处均已在正文注明「主 AI 核对修正」
> - 证据等级：**实读**＝读过源文件并给出路径/行号；**文档**＝官方或仓库页面明文；**推算**＝按语义或权限模型推断，不得当源码引用；**未核到**＝明确没查到
> - 环境基线：真机 Android 14（API 34）；我们的 daemon 跑 root 域 `app_process`；App 为非系统签名
> - 未核到项集中列在 §10，**不得当已解决**

---

## 一、结论摘要（先看这 5 条）

1. **ALAS-AOS 的预览不是「把 VD 直接输出给 App」**：VD 输出到 daemon/native 侧的 capturer Surface，App 侧再把捕获结果贴到自己的 Surface 上（**实读**，见 §2）。也就是说预览 = 「VD → 捕获面 → 二次贴图」，中间一定有一层读帧。
2. **我们与 ALAS 的建屏参数差异集中在 flags**：我们 `PUBLIC|OWN_CONTENT_ONLY`（实测 `flags=9`），缺 `SUPPORTS_TOUCH`、`TRUSTED`、`OWN_FOCUS` 等（§3）。AOSP 里 `SUPPORTS_TOUCH` 直接决定 `DisplayDeviceInfo.touch`（**实读**，§5）。
3. **AOSP 有强制的 flag 前置关系**：`OWN_FOCUS` 必须伴随 `TRUSTED`，否则被**静默忽略并打 Slog.w**；`STEAL_TOP_FOCUS_DISABLED` 需 `TRUSTED`+`OWN_FOCUS`；`ALWAYS_UNLOCKED` 需 `DEVICE_DISPLAY_GROUP` 或 `OWN_DISPLAY_GROUP`（**实读**，§5）。→ **「只加 OWN_FOCUS」是无效改动**。
4. **注入路径我们与 ALAS 并不相同**（主 AI 核对修正，§4）：ALAS 用 `InputManager.injectInputEvent()` API 在特权进程注入；**我们全仓 grep `injectInputEvent|InputManager` 命中 0**，实际走 `sh.run("input -d <id> tap ...")` 这条 **shell 命令**通路（`app/src/main/java/org/onmyoji/oas/android/root/Device.kt:160`）。→ D 回执里「注入 API 同路、不是差异点」这句**不成立**，`input` 命令（InputShellCommand）与 API 注入是两条路径，这本身就是一个候选真因。
5. **许可证**：ALAS-AOS 为 **AGPL-3.0**（**文档**级，来自仓库页/README，未读 LICENSE 正文）。参考其**架构思路与 flag 组合**无版权问题；**不得复制其代码**，否则我们的作品会被拖入 AGPL（与 `docs/BACKLOG.md` §十三 对 MAA-Meow / AzurPilot 的口径一致）。

---

## 二、Q1 呈现通路：ALAS-AOS 怎么把虚拟屏显示到 App 里（实读）

- 文件：`app/app/src/main/java/com/aliothmoon/maafw/remote/internal/VirtualDisplayManager.kt`
- VD 的输出面 = `NativeBridgeLib.setupNativeCapturer(w, h)` 返回的 **native Surface**
- App 侧：`setMonitorSurface(Surface?)` → `NativeBridgeLib.setPreviewSurface`，把捕获到的内容贴到 App 的预览控件上
- **不是** `DisplayMirror` 直连；也不是 App 侧自建 VD
- 未核到：capturer 内部是否就是 `ImageReader`（`cpp/` 目录 404，取不到 JNI 实现，见 §10）

## 三、Q2 建屏参数对照（我们 vs ALAS-AOS）

| 项 | 我们（OAS） | ALAS-AOS | 证据 |
|---|---|---|---|
| 宽高 | 1280×720（源码注释标默认值；由 `create` 调用方传入） | 1280×720 | 我们：`poc/displayserver/.../DisplayEngine.java:97` 注释；ALAS：**实读** `constant/DefaultDisplayConfig.kt` |
| dpi | 320（同上，调用方传入） | **160** | ALAS **实读**；我们见 `DisplayEngine.java:97` 注释与 `mirror()` 里的 `320` |
| flags（我们） | `PUBLIC｜OWN_CONTENT_ONLY` = **9**（真机 `flags=9`） | — | **实读+实测**：`poc/displayserver/.../SocketServer.java:26-28`；2026-11-18 真机建屏日志 |
| flags（ALAS） | — | `PUBLIC｜PRESENTATION｜OWN_CONTENT_ONLY｜SUPPORTS_TOUCH(1<<6)` + `DESTROY_CONTENT_ON_REMOVAL(1<<8)`；**API33+** 追加 `TRUSTED｜OWN_DISPLAY_GROUP｜ALWAYS_UNLOCKED｜TOUCH_FEEDBACK_DISABLED`；**API34+** 追加 `OWN_FOCUS｜DEVICE_DISPLAY_GROUP｜STEAL_TOP_FOCUS_DISABLED` | **实读** `VirtualDisplayManager.kt` |
| `SYSTEM_DECORATIONS` | 未设 | `false` | ALAS **实读** |
| 输出面 | `ImageReader.newInstance(w,h,RGBA_8888,3)` 的 Surface | native capturer Surface | 我们 **实读** `DisplayEngine.java:100-101` |

> ⚠ **flag 数值一律不作依据**：D 报 `0x1fd47` 并自标「推算」；主 AI 按位定义自行累加得 `0x1FD4B`，两者不一致 → 说明该数值不可靠。实现时**只写常量名**，不写魔数。

## 四、Q3 点击注入方式（含主 AI 核对修正）

- **ALAS-AOS（实读）**：`app/.../bridge/InputControlUtils.java` 用 `InputManager.setDisplayId(event, displayId)` + 隐藏的 `InputManager.injectInputEvent(event, MODE_WAIT_FOR_FINISH|ASYNC)`，运行在 Shizuku 特权进程；App 预览控件上的手动触摸走 AIDL `touchDown/Move/Up` → 同一函数。
- **我们（实读）**：`app/src/main/java/org/onmyoji/oas/android/root/Device.kt:153/160/172` 全部是 `sh.run("input [-d <id>] tap|swipe ...")`；`DisplayPoc.kt:147`、`KeepAlivePoc.kt:206` 同。全仓 `grep -rn 'injectInputEvent\|InputManager'` **命中 0**。
- **结论修正**：既有记录里「注入 API 选错」这一假设**应当保留**，不能按 D 的说法排除。两者差异是：
  - API 注入（ALAS）：直接进 InputManagerService，授权靠特权进程 uid + 目标 display 支持
  - shell `input` 命令（我们）：走 `input` 可执行文件 → `InputShellCommand` → `injectInputEvent`，**多一层 shell 命令侧的 display 解析与权限判定**
- 未核到：`InputShellCommand` / `InputDispatcher` 里对 secondary display 的丢弃判定（§10，两次取证均未拿到源码）

## 五、Q4 AOSP：七个 flag 的语义与强制前置（实读，核心证据）

来源（D 已落盘到缓存目录，可直接复读）：
- `DisplayManager.java` → `/data/user/0/com.ai.assistance.operit/cache/Operit/cleanOnExit/visit_web_android.googlesource.com_1794993554472_74671053.txt`（82678 字符）
- `VirtualDisplayAdapter.java` → `..._1794993605128_2193c02e.txt`（35199 字符）

| flag | 值 | 原文语义（摘） |
|---|---|---|
| `SUPPORTS_TOUCH` | `1<<6` | 该虚拟屏可关联唯一 id 匹配的触摸设备 |
| `TRUSTED` | `1<<10` | 可信 → 可显示系统装饰、**无需用户触摸即可接收输入** |
| `OWN_DISPLAY_GROUP` | `1<<11` | 拥有独立 display group |
| `ALWAYS_UNLOCKED` | `1<<12` | 始终视为已解锁 |
| `TOUCH_FEEDBACK_DISABLED` | `1<<13` | 关闭触摸反馈 |
| `OWN_FOCUS` | `1<<14` | 拥有自己的焦点；**注释明写：display must be trusted in order to have its own focus** |
| `DEVICE_DISPLAY_GROUP` | `1<<15` | 加入 device display group |
| `STEAL_TOP_FOCUS_DISABLED` | `1<<16` | 不抢 top focus |

**强制前置（`VirtualDisplayAdapter.java`，行号为落盘文件行号）**

- `:671-677` 设了 `OWN_FOCUS` 但**没设** `TRUSTED` → **直接忽略该 flag**，并打
  `Slog.w(TAG, "Ignoring VIRTUAL_DISPLAY_FLAG_OWN_FOCUS as it requires VIRTUAL_DISPLAY_FLAG_TRUSTED.")`
  → 想「让虚拟屏拿到焦点」必须 `TRUSTED` + `OWN_FOCUS` **成组加**；单加无效且不留痕迹（只有 logcat warning）
- `:679-688` `STEAL_TOP_FOCUS_DISABLED` 需 `TRUSTED` + `OWN_FOCUS`，否则同样忽略
- `:658-665` `ALWAYS_UNLOCKED` 需 `DEVICE_DISPLAY_GROUP` 或 `OWN_DISPLAY_GROUP`，否则忽略
- `:692-693` **`mInfo.touch = ((mFlags & SUPPORTS_TOUCH) == 0) ? TOUCH_NONE : TOUCH_VIRTUAL;`** ← 直接决定这块屏「有没有触摸能力」
- `:695-696` `if (mSurface == null) mInfo.state = Display.STATE_OFF;` ← 空 Surface 即 OFF（对 BG-22 的 `setSurface(null)` 方案是已知代价：停渲）

---

## 六、Q5 `input -d <displayId>` 在 secondary display 上的丢弃条件 —— **未核到**

- 已落盘的两份 AOSP 文件只能证明：缺 `SUPPORTS_TOUCH` ⇒ `DisplayDeviceInfo.touch = TOUCH_NONE`（`Adapter:692`），**不涉及事件分发链路**
- 「谁挡、挡不挡」要看 `InputDispatcher.cpp`、`InputManagerService`（含 `InputShellCommand` 的 display 解析与权限判定）。**这两处本轮均未核到**
- 结论：**BG-34 的真因仍未被 AOSP 原文钉死**。目前只能靠「flags 缺 SUPPORTS_TOUCH → 屏被标 TOUCH_NONE」这条**间接**证据，不足以断言「补上 SUPPORTS_TOUCH 就能被点」
- 落法建议：C-1 的实验本身就是这条问题的判据（改 flags 后 `input -d <id> tap` 能否让 `frames` 变化），比再花轮次去抓 AOSP 更省

## 七、Q6 跨进程把 Surface 交给 root daemon 的四种做法（可行性）

| 做法 | 可行性 | 依据 |
|---|---|---|
| ① daemon 自建 VD，输出到 daemon 内 `ImageReader`，App 只取帧 | **可行**（我们已是这条） | 我们 `DisplayEngine.java:100-101` **实读**；daemon 持特权 uid，`ImageReader` 面在 daemon 内 |
| ② App 建 `SurfaceView`，`Surface` 经 AIDL 传给 daemon 当 VD 输出面 | **可行，且是 ALAS 的路** | ALAS `setPreviewSurface` **实读**（§2）。代价：App 进程一死/退出预览，VD 输出面变 null → `STATE_OFF`（§5 :695） |
| ③ `DisplayMirror` 直连 | **对非系统 App 不可行** | 无公开 API（**推算**，权限模型） |
| ④ `MediaProjection` 抓镜像 | 需用户授权弹窗，且只镜像不接管 | **推算**；root daemon 不能替 App 拿授权 |

**⇒ 对我们最省的一条**：**①+② 的混合**——daemon 侧的 `ImageReader` 已经存在（`grab` 就在读它），C-2 只需要新增「把最新帧推给 App 并在 `SurfaceView/TextureView` 上显示」这一段 IPC + UI，**不需要重建捕获通路**。

## 八、Q7 许可证

- `Shinarin/ALAS-AOS`：**AGPL-3.0**（**文档**级：仓库页与 README 标签；根 `LICENSE` 正文未读 → §10）
- 对我们的约束：借鉴**架构与 flag 组合**（思想不受版权保护）无需额外披露；**复制其代码**会把本仓库拖入 AGPL 义务（本项目为 GPL-3.0 修改版，见 `docs/HANDOFF.md §十三`）
- 实操口径：**只按 AOSP 常量名自行实现，不移植 ALAS 的任何文件**

## 九、对我们的落点（候选拆分，主 AI 建议 → **未拍板**）

| 候选 | 范围 | 判据（验收） |
|---|---|---|
| **C-1 最小闭环：改 flags 重打 jar** | `SocketServer.DEFAULT_FLAGS` 补 `SUPPORTS_TOUCH`；按 AOSP 前置关系成组补 `TRUSTED` + `OWN_FOCUS`（**不可单独加 OWN_FOCUS**）；`tools/build_displayserver.sh` 重打 `server.jar` | `input -d <id> tap` 能让 `frames` 变化，且 `mCurrentFocus` 变非 `null` |
| **C-2 App 内预览** | daemon 已有 `ImageReader` → 新增帧推送（AIDL/socket）；App 加 `SurfaceView`（或 `TextureView`）预览区 + 生命周期释放 | BG-35 四条口径全过 |
| **C-3 预览与主屏共存调优** | 预览开启时的帧率/分辨率/功耗折中；与 BG-22 `setSurface(null)` 停机策略的冲突处理（预览中不可停渲） | 预览期间主屏不掉帧；熄屏策略与预览互斥有明确开关 |

- 通信提示：`DisplayPoc.kt:147` 属于 PoC 目录（写手可自行改）；`App` 侧 UI 是新增能力，**不是修复**
- **C-1 与 C-2 是两件独立的事**：C-1 解决「能不能操作」，C-2 解决「能不能看见」。任一通过不代表另一个通过
- 若 AOSP 后续证据显示「不可触」与「activity PAUSED」是两个独立原因（焦点 / 触摸能力），则 C-1 可再拆 `C-1a 可触` / `C-1b 可聚焦`

## 十、未核到清单（**不得当已解决**）

1. `input -d` / `InputShellCommand` / `InputDispatcher` 对 secondary display 的丢弃判定（`Input.java` 的 main 与 android14-release 两个路径均 NOT_FOUND）
2. ALAS `NativeBridgeLib.setupNativeCapturer` 内部是否就是 `ImageReader`（`cpp/` 目录 404）
3. ALAS App 预览控件是 `SurfaceView` 还是 `TextureView`（`AlasScreen.kt` 两次超时）
4. ALAS-AOS 根 `LICENSE` 正文（仅有仓库页/README 标签）
5. ALAS flags 的精确数值（`0x1fd47` 为推算，与按位累加值不一致）
6. 我们建屏的宽高/dpi 实际入参（源码注释标 1280×720@320，未逐条核对 App 侧调用点）

