# 交接文档（新对话从这里开始看）

> 更新时间：**2026-11-14 17:55**（本轮：**阶段 2 推进 —— cv2 shim 32/32、无头入口补齐、四段保活 C 段失败（OEM freezer）**；详见 §十一）
> 本文件是「当前状态 + 待办」的唯一准绳；`docs/PORTING.md` 是历史档案（里面有与现方案冲突的 `wm size` 旧结论，别照做）。
> 阶段 1 的设计、协议、实测数据、参考实现对照、踩坑清单 → **`docs/BACKGROUND.md`**（本轮新建，先看那个再动手）。

---

## 零、30 秒读懂 + 下一步

- 目标：**阴阳师跑在虚拟显示器上、主屏照常用手机**，脚本按恒定 **1280×720** 画布识别/点击，**绝不改物理分辨率**。
- 现在到哪了：阶段 0 / 阶段 1 PoC-A（**7/7**，抓帧 p50 **5.0ms**）/ 阶段 2 前半（Chaquopy **CPython 3.13.16**、`cv2.py` shim **32/32**、`oas_device.py`、`android_entry.py`）/ ADR-0001 三件套（BG-17 daemon 环境自检、BG-18 PARTIAL 锁 + reaper、BG-19 `kb run` 前移）**均已真机验证**；`tools/hang_check.sh`（BG-32 长时挂机一键检测）已入库（PR #21 squash `79d5f13`）。
- **最大缺口（下一步就是这个）**：上游 OAS 的插拔点还没接 —— `vendor/` 下**没有** `android_impl.py`，`screenshot_methods['android']` / `click_methods['android']` **未注册**（对应 §六 待办「阶段 2 第 9 项」）。也就是说「壳 + 通道 + 采集 + 点击」全通，但**还不会自动打本**。
- 全量待办看 `docs/BACKLOG.md`（main 基线实查：`[x]` 13 项 / `[ ]` 14 项）。**调度位换人先读 `docs/handoff/20261115-1158-main-ai.md`**（主 AI 交接包，七要素 + 会话名册 + 排除记录 + 待办优先级）。
- 一条必须先知道的结论：**后台进程会被系统的 cached-app freezer 冻住**（阶段 0 实测冻了 36 秒）；Realme/ColorOS 的 Hans freezer **连 FGS 也不豁免**。对策见 §十一 与 ADR-0001（PARTIAL 语义锁 + 心跳/调度前移 daemon）。
- 想先跑一遍现状验证：`pm install -r -t dist/oas-android-debug.apk` → `am start -n org.onmyoji.oas.android/.MainActivity --ei poc_display 1` → 80 秒后看 `display_poc.txt`，应仍是 **7/7**。

---

## 一、硬约束（不要再改，改了大概率连锁崩）

| 约束 | 内容 |
|---|---|
| 分辨率 | 绝不 `wm size`；物理分辨率任意 → 识别画布恒定 1280×720 |
| 运行时 | Chaquopy 嵌入式 CPython **3.13**（尚未升级，当前仍是 3.12.15），**单 APK** |
| cv2 | **C 混合**：PIL 做解码/缩放/颜色，OpenCV(Java/AAR) 做 `matchTemplate`/`findContours`/`SIFT`，按帧缓存 Mat、`submat` 零拷贝；APK 预计 +25~35MB |
| 抓帧 | 本轮只用 `screencap → raw 文件`；共享内存通道留作后续优化 |
| 版本矩阵 | Gradle 8.14.3 / AGP 8.13.2（**绝不能上 9.x，Chaquopy 17 的 DSK 会静默失效**）/ Kotlin 2.3.10 / Chaquopy 17.2.0 / `minSdk 26`、`compileSdk=targetSdk=35`、`abiFilters arm64-v8a`、`useLegacyPackaging true` |
| 不降级 | 虚拟屏跑不通就换 flag / 尺寸 / dpi / `PRESENTATION` 继续啃，**不退到息屏或分屏** |
| 模块形态 | `app` 模块是 **Groovy `app/build.gradle`**（不是 `.kts`，所以 `chaquopy {}` 处写 `version = "3.12"` 这种无类型访问器写法） |

---

## 二、当前真实状态（本会话实测，不是推测）

| 项 | 实测值 |
|---|---|
| APK | **`dist/oas-android-debug.apk` = 37,618,831 B**（`BUILD SUCCESSFUL in 49s` / 47 tasks，13:12 从零重建验证过；md5 `d800f025218cadb90832b6b1296b3098`）<br>`app/build/` 已清理，要新 APK 就 `./gradlew assembleDebug`（49s）再拷到 `dist/` |
| `assets/server/server.jar` | **16,037 B**（daemon dex 包，`bash tools/build_displayserver.sh` 生成并自动拷入） |
| `assets/chaquopy/app.imy` | 12,443 B（含 `android_entry.pyc` + `oas_device.pyc`） |
| `requirements-common.imy` | 13,487,743 B（numpy 1.26.2 + pillow） |
| 自检 6 项（11:18:11 那轮） | Python 491ms / NumPy 639ms / Root 215ms / 截图 530ms（1080×2412, 222KB）/ 工作目录 12ms / 存储 1ms（可用 13.8GB） |
| **虚拟屏 PoC-A（12:28 那轮）** | **7/7 通过**，displayId=13，抓帧 p50 **5.0ms** / p95 23.0ms，详见 `docs/BACKGROUND.md` §五 |
| **cv2 shim 自测（17:43 那轮）** | **32/32 通过**；Python 3.13.16 / numpy 1.26.2 / pillow 11.0.0 / OpenCV 4.13.0；`cvtColor 1280×720` **7.1ms**、`matchTemplate 60×40` p50 **140ms**（含每次重建 Mat 的搬运成本，OAS 侧须按帧缓存） |
| **四段保活（17:45–17:54）** | 报告 **1/5**，但测量被干预污染；决定性证据：`OplusHansManager` 冻结 uid10331 **119s**、WakeLock 17:47:38 被系统释放、心跳空洞 **122s**、同期 daemon `frames` **6024→9704（+3680）** |
| 设备 | Android 14 / arm64-v8a / Magisk（`u:r:magisk:s0`）/ 1080×2412 / density 480 / 挖孔 96px |
| 游戏 | `com.netease.onmyoji`，入口 `.tag0` → 实际落 `.Client`，`SCREEN_ORIENTATION_SENSOR_LANDSCAPE` |
| 工作区体积 | **76 MB**（清理前 351 MB；= dist 37MB + vendor/oas 32MB + 源码文档 7MB） |

**已打通并验证的链路**：`am start`（带 extra）→ `BenchTrigger` → `PyRuntime.ensureStarted` → Python `bench_capture(delay, display_id)` → `su screencap` → numpy 解码 → 内容区检测 → 裁切/letterbox → 1280×720 画布 + `cache/bench.json` 落盘。

---

## 三、无头验证器怎么用（本轮新增，最重要的一条工具）

```bash
# 1) 触发一次抓帧（delay 秒后抓，display_id=-1 表示主屏；>=0 抓虚拟屏）
am start -n org.onmyoji.oas.android/.MainActivity --ei bench_delay 8 --ei bench_display -1
# 2) 想把游戏画面抓进来：触发后 2 秒把游戏拉到前台
sleep 2; am start -n com.netease.onmyoji/.Client
# 3) 等 delay+2 秒，看结果
cat /data/user/0/org.onmyoji.oas.android/files/oas/cache/bench.json
```

`bench.json` 固定字段：`ts / work_dir / rounds / delay_s / display_id / canvas / last_map{rect,dst,mode,screen_w,screen_h} / results[]{screen,format,content_rect,crop_rect,canvas_rect,fit_mode,capture_ms,decode_ms,mean,std,black_ratio,round,total_ms}`。

### 本轮为此踩的三个坑（都已在代码里修好，不要再踩回去）

1. **`LaunchedEffect(Unit)` 只会跑一次**：`am start` 命中已存在的任务时走 `onNewIntent`，旧写法永远不触发。
   → 改成全局 `object BenchTrigger { val request = mutableStateOf<Request?>(null) }` 事件总线（带 `ts` 去重），`onCreate` 与 `onNewIntent` 都投递。
2. **`am start` 对 standard 启动模式的 Activity 会直接把 intent 丢掉**（只把任务提到前台，不回调）。
   → `AndroidManifest.xml` 里 `MainActivity` 加 `android:launchMode="singleTop"`。
3. **Chaquopy 未启动就调 Python**：自检（`SelfCheck`）才是首个 `Python.start` 调用者，无头触发可能抢在它前面，抛
   `Cannot use GenericPlatform on Android. Call Python.start(new AndroidPlatform(context))...`
   → `startBench()` 里先 `PyRuntime.ensureStarted(context)` 再 `PyRuntime.call(...)`。

排查时用的日志：`android.util.Log.i("OASBench", ...)`（`offer / compose / effect / startBench / bench done` 五个点）。
> ⚠️ 本机 `logcat -d -s OASBench` **不生效**（返回空），要用 `logcat -d --pid=$(pidof org.onmyoji.oas.android) | grep -a OASBench`。
> ⚠️ Operit 自身的 `AIService` 会把巨长请求体写进 logcat，很容易把目标 tag 冲掉 —— 排查要趁早、要当场看。

---

## 四、实测数据（可引用的证据，均已归档到 `poc/frames/`）

### 4.1 竖屏（App 自己的 UI，1080×2412）

| 项 | 值 |
|---|---|
| `content_rect` | `(0,0,1080,2412)`（整屏，无黑边） |
| `canvas_rect` | `(479,0,801,720)` → 内容只占 1280 宽里的 322 px |
| `fit_mode` | `letterbox` |
| `mean / black_ratio` | 59.45 / 0.7484（黑边占 75%，符合竖屏比例极端） |
| `capture_ms` / `decode_ms` / `total_ms` | 207.0 / 16.7 / 401.2；150.5 / 13.7 / 269.9 |

### 4.2 横屏游戏帧（**P0-1 已补齐**，2412×1080，文件 `poc/frames/game_landscape_1123.raw`）

| 项 | 第 1 轮 | 第 2 轮 |
|---|---|---|
| `screen` | 2412×1080 | 2412×1080 |
| `content_rect` | `(0,0,2412,1080)` | `(7,3,2407,1077)` ← 检测有 3~7px 抖动 |
| `crop_rect` | `(0,0,2412,1080)` | `(7,3,2407,1077)` |
| `canvas_rect` | `(0,73,1280,646)` | `(0,73,1280,646)` |
| `fit_mode` | `letterbox` | `letterbox` |
| `mean` | 79.84（过场/暗帧） | 188.84（游戏内） |
| `black_ratio` | 0.2042 | 0.2042 |
| `capture_ms` / `decode_ms` | 211.6 / 18.2 | 165.0 / 13.9 |

**两个必须记住的结论**：

1. **阴阳师在 20:9 屏上是全屏铺满**（ratio **2.233**，无 16:9 黑边），跟 16:9 差 **19%**。
   现在 `fit_to_canvas` 走 letterbox → 内容只占 `1280×573`，**与 OAS 的 1280×720 模板不是 1:1**；
   改 crop 16:9 又要切掉左右各约 8% 的 UI。
   → 所以「**虚拟屏直接建成 1280×720**」不是可选项，是**把识别率做对的前提**（让游戏原生渲染 16:9，OAS 零适配）。
2. **内容区检测在动态画面上会抖动几像素**（`(0,0,2412,1080)` vs `(7,3,2407,1077)`）。
   → 后续要么对 `content_rect` 做稳定性判定（连续两帧一致才采信），要么在虚拟屏方案下干脆按固定尺寸、跳过检测。

---

## 五、本轮两个关键发现（会影响架构，务必看完）

### A. `screencap -d <displayId>` 在本机彻底不可用

`screencap -h` 显示 `-d: specify the display ID to capture (default: 4627039422300187648)`，但实测 `-d 0 / -d 1 / -d 2 / -d 4627039422300187648` 全部 `exit=1` 且输出 0 字节；默认 `screencap` 正常（10,419,856 B）。
→ **虚拟屏抓帧不能指望 screencap CLI，必须走 `IWindowManager.captureDisplay(displayId)`**（特权进程里反射），与 MAA-Meow / AzurPilot 同一条路。这也进一步确认 PoC-A 是主路线。
另外已确认 `input -d <displayId> tap x y` **可用**（usage 里有 `[-d DISPLAY_ID]`），定向注入不用写 Java。

### B. 后台进程会被 **cached-app freezer** 冻住（实测，不是理论）

- 11:23:01 触发 `bench_delay=8`（App 在前台），2 秒后把游戏拉到前台盖住 App。
- 预期 11:23:09 完成，实际 **11:23:45 才写出 `bench.json`**（延迟 36 秒）。
- 期间进程 pid 未变、无异常日志；11:23:46 我们再次 `am start` 把 App 拉到前台，它立刻“复活”并写盘。
- 结论：**App 在后台时整个进程被 SIGSTOP 冻结**（CPython 的 `time.sleep` 一起停）。这不是 bug，是 Android 14 的省电策略。

**架构含义**：所谓「后台挂机」= 必须有**前台服务**（`startForeground` + 通知 + `foregroundServiceType`，Android 14 还要声明具体类型如 `specialUse`/`dataSync`），否则识别线程会被冻结，帧和点击都会断。这条要写进阶段 3，别等到集成 OAS 才发现。

### C. 虚拟屏必须由**活着的进程**持有（PoC-A 的架构转折）

虚拟屏的生命周期绑在创建它的进程持有的 `IVirtualDisplayCallback` binder 上。
→ HANDOFF 原先设想的「`create` 打印 displayId 然后进程退出、后续命令再连」**在架构上不成立**，进程一退屏就销毁。
→ 所以 PoC-A 交付形态改成 **常驻 root daemon（`su` + `app_process`）+ socket 协议 + Kotlin 封装**。

### D. socket 用**抽象命名空间**，不用文件系统 socket

`LocalServerSocket` 的公开构造只接受 `String`（抽象名）或 `FileDescriptor`；
`android.system.UnixSocketAddress` **在本平台根本不存在**。
→ 用抽象名 `org.onmyoji.oas.display`，**顺带消掉两条陷阱**：
① root 创建的 socket 文件 app 无写权限（原计划要 `chmod 666`）；
② app 进不去 `/data/local/tmp`（0771 shell:shell）。
而且抽象 socket 没有文件实体，退出即消失，天然不产生垃圾。

### E. daemon **不受 freezer 影响** → 第 6 项必须在 daemon 侧测

`app_process` 起来的进程没有 process record，不在任何 app 的进程组里，系统不会 SIGSTOP 它。
反过来：**「主屏刷别的 App 时虚拟屏是否还出帧」这件事在 app 进程里根本测不出来**
（主屏一切走，我们的 app 立刻被冻，帧计数冻结 → 假阴性）。
→ 服务端为此专门加了 `watch <id> <ms> <returnComponent> [cmd...]`：
在 daemon 侧执行 launch、数帧数增量、再把 app 拉回前台。第 6 项就是这么测出来的（529 → 647，delta=118）。

---

## 六、待办清单（按顺序做）

### P0 — ✅ 已完成（阶段 1：虚拟显示器 PoC-A，2026-11-14）

- [x] **1. `poc/displayserver/`（root + `app_process` 常驻特权服务）** —— 已实现并真机跑通
  文件：`src/org/onmyoji/oas/server/{Main,SocketServer,DisplayEngine}.java` + `third/{Ln,Workarounds,FakeContext}.java`
  （`third/` 里 3 个派生自 scrcpy，Apache-2.0，已写 `THIRD_PARTY_NOTICES.md`）
  构建：`bash tools/build_displayserver.sh`（`javac -source 8 -target 8 -bootclasspath android.jar` → `d8` → `server.jar` 16,037 B）
  产物：拷进 `app/src/main/assets/server/server.jar`，首用解压到 `filesDir/oas/server/`（**不依赖 `/data/local/tmp`**）
- [x] **2. 7 项验证表全过 + `docs/BACKGROUND.md` 已写**

  | # | 验证项 | 实测 |
  |---|---|---|
  | 1 | 建 `PUBLIC\|OWN_CONTENT_ONLY` 虚拟屏并拿到 displayId | ✅ displayId=13，建屏 102ms，1280×720@320 |
  | 2 | 阴阳师起在该 displayId 且不被弹回主屏 | ✅ `dumpsys` 确认在 display 13 |
  | 3 | 出帧非黑 | ✅ `mean=99.05 blackRatio=0.0031 bytes=3,686,416 frames=345` |
  | 4 | 游戏在该屏正常渲染 | ✅ PNG 511KB，游戏 UI 有结构 |
  | 5 | 定向点击只作用虚拟屏、主屏无副作用 | ✅ 虚拟屏帧差 92.17% / 主屏 0.0031% |
  | 6 | 主屏刷别的 App 时虚拟屏仍持续出帧 | ✅ 4s 内 529→647（delta=118），在 daemon 侧 `watch` 测得 |
  | 7 | 抓帧耗时 / 电量温升 | ✅ p50=**5.0ms** p95=23.0ms；level 61 / 31.8℃ |

- [x] **3. PoC-B（MediaProjection）** —— **不需要做**（PoC-A 已通过，用户也选了「不转 PoC-B」）

### P0.5 — ✅ 收尾已完成（2026-11-14 13:15，每轮结束照抄这套动作）

- [x] `.gitignore` 补 `/app/build`、`/build`、`/.kotlin`、`/.gradle`、`/poc/displayserver/build`、`/app/src/main/assets/server/*.jar`、`*.raw`、`/app/src/main/python/**/*.pyc`、`/.backup`、`/.operit`
- [x] 旧垃圾清理，**工作区 351 MB → 76 MB（回收 275 MB）**
  - 删 `poc/frames/game_landscape.raw`、`game_landscape_1123.raw`（各 10.4MB）、`canvas_last.png`
  - 删 `app/build/`（262MB）、`build/`、`.kotlin/`、`.gradle/`、`poc/displayserver/build/`
  - 删 `vendor/oas/.git/`（19MB，工作副本保留）
  - 删设备侧 `/data/local/tmp/oas/`（开发期推送物，`out/ out3/ out4/ out5/ server.jar`）
  - 删设备侧 `files/oas/cache/*.raw`（约 50MB）→ 只剩 `bench.json` / `canvas_1280x720.png` / `display_poc.json` / `.txt` / `poc_vdisplay_0.png`
  - **保留**：`docs/`、`vendor/oas` 源码、`poc/frames` 的 PNG+JSON、`tools/`、`dist/`（APK）、`.operit/`、`.backup/`
- [x] 清理后从零重建验证：`./gradlew assembleDebug` → **BUILD SUCCESSFUL in 49s**（47 tasks），APK 存到 `dist/oas-android-debug.apk`
- [x] `DisplayServer.stop()` 现在会顺带 `purgeTempRaw()` 删 cache 里的 `.raw`（PNG/JSON 保留），
      所以 `--ei cleanup 1` 就是完整收尾。**实测**：清理后 cache 660KB、server 目录只剩 `server.jar`、`ps -A | grep oas.server` = **0**
- **以后每轮收尾固定两条命令**：
  ```bash
  am start -n org.onmyoji.oas.android/.MainActivity --ei cleanup 1   # 杀 daemon + 删临时 raw
  # 工作区侧：删 app/build、build、.kotlin、.gradle、*.raw
  ```

### P1 — PoC 通过后（阶段 2/3）

> 本轮（2026-11-14 17:xx）已完成第 4~7 项：前台服务、Python 3.13、CvBridge/cv2 shim、OAS 3.13 补丁。
> 但第 4 项的**息屏判据未通过**（OEM freezer），实测与结论见 **§十一**。

- [ ] 4. **前台服务**（解决 freezer）+ `WakeLock`/`svc power stayon`，保证后台持续出帧
      —— **下轮第一件事**：`startForeground` + 通知 + `foregroundServiceType`（Android 14 要具体类型，如 `specialUse`），
      把 Python 识别线程挂上去，验证「app 切后台 60s 后仍能 1fps 出帧 + 点击」
- [ ] 5. Python 升 **3.13**（`app/build.gradle` 只改 `version`），`numpy`/`pillow` 有 cp313 轮子；`opencv-python` 只有 cp38/cp310 轮子 → 所以 cv2 必须走 Java AAR
- [ ] 6. `CvBridge.kt`（`@JvmStatic`，与 `Device.kt` 同套路）+ Python `cv2.py` shim（约 25 个函数：`matchTemplate/minMaxLoc/cvtColor/resize/imdecode/copyMakeBorder/split/merge/...`；未实现函数**显式抛错**，不静默返回错值）
- [ ] 7. **OAS 兼容修补**：`grep -rn "np\.float\|np\.int\|np\.bool\|np\.object"` —— `module/base/utils/utils.py:536` 有 `astype(np.float)`，numpy 1.24+ 已删除该别名，3.13 下必崩；统一改内建类型并记入 `patches/`
- [x] 8. **`Device.kt` 扩展** —— 本轮已随 PoC-A 完成：`init(Context)` / `createDisplay` / `destroyDisplay` / `displayList` / `launchOnDisplay` / `stopDisplayServer`，
      `captureRawOnDisplay` 改成「daemon 优先、`screencap -d` 兜底」；`tapOnDisplay` 沿用 `input -d <id> tap`

### P2 — OAS 落地（阶段 4）

- [ ] 9. 新增 `module/device/method/android_impl.py`，注册到 `screenshot_methods['android']` / `click_methods['android']`
- [ ] 10. `screenshot.py:check_screen_black()` 的 `area` 改成内容区（否则 letterbox 黑边会被判黑屏）
- [ ] 11. 适配策略 `auto`：内容区比例与 16:9 偏差 <2% → crop（逐像素 1:1）；否则 letterbox（带偏移映射）
      **不改 `tasks/**/assets.py` 的 ROI、不缩放 181 张模板**
- [ ] 12. `multiprocessing` → 线程（`script_process.py` / `module/ocr/rpc.py`），Chaquopy 不支持 fork
- [ ] 13. 决定 `vendor/oas`（约 52MB，含 `.git`）怎么进 APK：①剔 `.git/deploy/gui` 打 assets；②首启解压到 `filesDir/oas`；③只打 `module/ tasks/ config/`
      注意：美术资源不在仓库里，模板散在 `tasks/*/res/*.png` 共 181 个，其余靠运行时下载 → **要确认下载源与离线可用性**

### P3 — 清理

- [ ] `SuShell.kt:27` 用 `java.lang.Object` 当锁，Kotlin 告警「不推荐」，换 `ReentrantLock`
- [ ] `RootShell.kt` 已被 `SuShell` 取代，自检路径可收敛
- [ ] `MainActivity.kt` 里 `OASBench` 的调试日志，等功能稳定后降级或删掉
- [ ] `vendor/oas/.git` 可删（几十 MB）

---

## 七、环境备忘（冷启动照抄）

```bash
cd /data/user/0/com.ai.assistance.operit/files/workspace/8868caab-ae27-4814-9992-11fb83225a35
export ANDROID_HOME=$HOME/Android
bash tools/build_displayserver.sh      # 改了 poc/displayserver/**/*.java 之后必须先跑这个
                                        # → poc/displayserver/build/server.jar → 自动拷进 app/src/main/assets/server/
./gradlew assembleDebug --console=plain        # 必须用 ./gradlew
pm install -r -t app/build/outputs/apk/debug/app-debug.apk   # 设备 shell 没有 adb，用 pm
am start -n org.onmyoji.oas.android/.MainActivity --ei bench_delay 8 --ei bench_display -1
am start -n org.onmyoji.oas.android/.MainActivity --ei poc_display 1     # 跑 7 项虚拟屏验证
am start -n org.onmyoji.oas.android/.MainActivity --ei cleanup 1         # 收尾：杀 daemon + 删临时 raw
cat /data/user/0/org.onmyoji.oas.android/files/oas/cache/display_poc.txt
cat /data/user/0/org.onmyoji.oas.android/files/oas/cache/bench.json
```

- `PATH` 上的 `gradle` 是 apt 装的 **4.4.1**，会误报 `Task 'assembleDebug' not found`（不认 `settings.gradle.kts`）—— **永远用 `./gradlew`**。
- daemon 探活：`su -c 'CLASSPATH=... app_process ...'` 之外的入口只有 socket；手工看进程用 `ps -A | grep oas.server`（正常应为 **0 行**）。
- 工作区 `poc/frames/` 只保留 PNG + JSON 证据（raw 已清理）：`canvas_landscape_1123.png` / `bench_landscape_1123.json`（阶段 0 横屏游戏帧）、`vdisplay_game_client.png`（虚拟屏里的游戏）/ `vdisplay_settings_1280x720.png` / `mirror_main_1280x720.png` / `main_after_game_attempt.png` / `vdisplay_selftest.json`（PoC-A 证据）。
- 设备 shell **没有 `python3`**（exit 127），解析 json 用 `grep/cat`，或把文件拉回工作区再分析。
- 应用数据目录 `/data/user/0/org.onmyoji.oas.android/...` 在 Ubuntu 终端里**看不到**（只挂载了 Operit 自己的沙箱），要用 `super_admin:shell`（su）访问；`cp` 到工作区时用 shell 侧命令。
- 包名 `org.onmyoji.oas.android`，uid `u0_a331`（Magisk policy=2 已预授权，重装不变）。

---

## 八、风险（文档必须明说，不做安全承诺）

| 风险 | 应对 |
|---|---|
| **网易反作弊/封号** | 产品风险而非技术风险；测试号先跑，不承诺安全 |
| 阴阳师（Unity + 安全 SDK）拒绝在虚拟屏渲染/黑屏 | 换 flag 组合、改尺寸/dpi、试 `PRESENTATION`；不降级 |
| 游戏被系统弹回主屏（`resizeableActivity=false`） | 启动后立即校验 displayId，Watchdog 自动重拉 |
| 隐藏 API 签名随 Android 版本变 | 直接用 scrcpy 最新封装，别自写反射 |
| 虚拟屏被系统回收 | 前台服务 + 周期校验 display 是否还存在 |
| 耗电/发热 | PoC 阶段就测 5 分钟功耗；限识别频率 1~2fps 比优化算法更有效 |
| Python 3.13 与 OAS(3.10) 不兼容 | 先导入冒烟测试，`np.float` 类问题批量修进 `patches/` |
| cv2 shim 覆盖不全 | 未实现函数显式抛错，按真实报错逐个补 |

---

## 九、已调研过的参考项目（结论，不用重查）

- **MaaAssistantArknights**：PC 端工具（C++ Core + ADB/模拟器 + 桌面客户端），**没有安卓本机后台能力**。可抄的是控制器抽象、任务流水线、回调协议。
- **MAA-Meow**（AGPL-3.0，1.9k★）：真正的安卓本机后台实现。scrcpy 的 Apache-2.0 隐藏 API 封装 + Shizuku/root 特权进程反射 `createNewVirtualDisplay()` + `setLaunchDisplayId`，按 displayId 抓帧注入。
- **AzurPilot-for-Android**（AGPL-3.0，67★）：架构类似，另加 PRoot+Ubuntu 跑完整 CPython、匿名共享内存帧通道（47.2ms→15.6ms）、渠道 SDK 弹窗抓回、防烧屏。
  > ⚠️ 这两个都是 **AGPL-3.0**：只借鉴公开架构思路，**不复制源文件/资源/图标**，避免许可污染。
- `MediaProjection.createVirtualDisplay` 允许 `PUBLIC/PRESENTATION/SECURE`（`PRESENTATION` 自动带 `OWN_CONTENT_ONLY` 语义），真正放行由 `MANAGE_MEDIA_PROJECTION` 组件按授权决定 → 需实测。
- Chaquopy 轮子现状（`chaquo.com/pypi-13.1/`）：numpy 有 cp312/cp313、pillow 有 cp312/cp313、**opencv-python 只有 cp38 与 cp310**。

---

## 十一、阶段 2 实测与结论（2026-11-14 17:35–17:55）

### 11.1 已完成并可复现

| 项 | 结果 |
|---|---|
| 构建 | `BUILD SUCCESSFUL in 16s`（harness 修复后重建）；APK **48,891,504 B**，md5 `821f63acc4fc8292de35be7a2d6a06e1`（已覆盖 `dist/`） |
| 装机 | `pm install -r -t` Success；`lastUpdateTime=2026-11-14 17:35`；通知权限已 `pm grant` |
| cv2 shim 自测 | **32/32**；Python 3.13.16 / numpy 1.26.2 / pillow 11.0.0 / OpenCV 4.13.0 |
| 四段保活 | 报告 1/5，但**测量被干预污染**（详见 11.3），不作为性能结论 |

**无头入口已修好**（上一版是坏的）：`MainActivity` 的 `when` 原先只有 `cleanup/poc/bench`，
`keepalive / keepalive_stop / cv_selftest` 解析后**无人分发**，`--ei keepalive 1` 会掉进 bench 分支空跑。
现在顺序为 `cleanup → keepalive_stop → keepalive → cv_selftest → poc → bench`，UI 也补了三个兜底按钮。

**异常兜底已补**：`KeepAlivePoc` 新增 `cleanupRuntime()`（停心跳 → 销毁虚拟屏 → 停 daemon/前台服务 → 亮屏解锁 → 清 raw）
与 `emergencyCleanup()`（异常也写 `keepalive_poc_error.txt`），不会再把屏幕留在熄灭状态。

### 11.2 cv2 shim 本轮修掉的真实缺陷（都不是「改断言」）

1. **int16 不支持** —— Sobel / `convertScaleAbs` 的中间结果就是 int16 → `matFromInt16Bytes` + `cv2.py` 放行；
2. **2 通道不支持** —— `findContours` 返回的点集是 `CV_32SC2` → `typeOf()` 改用 `CvType.makeType(depth, ch)`；
3. **JPEG `params` 未实现** —— `imencode(ext, mat, IntArray)` + `IMWRITE_JPEG_QUALITY`；平滑渐变 q=95 往返误差 **0.56**；
4. **`matToPoints` 用 `DoubleArray` 读 `CV_32FC2`** —— 抛 `Mat data type is not compatible: 13`，
   实际是 `perspectiveTransform` 输出后的读取路径 → 按 `depth` 分流；`findHomography` 往返四角误差 **0.0000**。

断言侧只改了两处，且都是**断言本身写错**：
- `convertScaleAbs`：`np.abs(int16).astype(uint8)` 会把 -300 溢出成 44，改为 `clip(0,255)`；
- SIFT 探针：原先把 3 通道图喂给 SIFT（shim 正确地要求灰度），改为先 `cvtColor`。

**SIFT 观察**：8px 棋盘只出 **1** 个关键点，随机噪声出 **48** 个 → 是图案本身特性，不是包装层丢点（已写入注释）。

### 11.3 C 段（息屏）失败：根因是 OEM freezer，不是合成层

**硬证据（干预前，同一 pid 20449 未重启）：**

| 证据 | 值 |
|---|---|
| 前台服务 | `isForeground=true`、`foregroundId=1001`、`types=40000000(specialUse)`、通知 `oas_keepalive` 在列、`startForegroundCount=1` |
| WakeLock | `oas:keepalive` C 段持有中，**17:47:38 被系统释放**，17:49:38 才恢复 |
| 系统日志 | `OplusHansManager unfreeze uid:10331 org.onmyoji.oas.android ... reason: LcdOn`、`F stay=119` |
| Python 心跳 | 17:47:37 → 17:49:39 **空洞 122s**（文件从 36 行跳到 47 行，同一 pid） |
| daemon 侧 frames | **6024 → 9704（+3680，≈30fps）**：冻结期间虚拟屏合成完全正常 |
| D 段点击 | 虚拟屏帧差 **1.18%** / 主屏 **0.28%**（判据 0.05% / 2%，通过） |

→ **结论**：Realme/ColorOS(Oplus) 的 Hans freezer 在熄屏后冻住了 app 进程，
`specialUse` 前台服务 + PARTIAL_WAKE_LOCK 都**不豁免**；虚拟屏与游戏渲染没有受影响。
这也解释了为何报告缺失：验证线程被冻在收尾途中，直到人为唤醒才继续。

### 11.4 下一步（二选一，必须先定）

| 路线 | 做法 | 代价 / 风险 |
|---|---|---|
| **A. 设备侧豁免（先试）** | 电池优化白名单（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 已声明，需用户点授权）+ ColorOS「允许后台活动 / 自启动」+ 关智能省电；然后重跑四段（~4 分钟） | 成本低；但 OEM 白名单未必覆盖 Hans，可能仍冻 |
| **B. 架构侧前移（终态）** | 心跳 / 识别 / 调度放进 root daemon（`app_process` 无 process record，实测不受 freezer），app 只做 UI + 前台服务 + socket 客户端 | 工作量大，等于提前做 P2-9 插拔点；但这是长跑唯一稳的形态 |

### 11.5 A 路线（设备侧豁免）实测结果：**不足**

已做的设备侧动作（全部生效）：

```bash
cmd deviceidle whitelist +org.onmyoji.oas.android     # 已加入
cmd appops set … RUN_IN_BACKGROUND allow               # allow
cmd appops set … RUN_ANY_IN_BACKGROUND allow           # allow
am set-standby-bucket … active                         # bucket=5 (EXEMPTED)
```

但 `dumpsys activity processes` 仍为 **`isFreezeExempt=false`** —— ColorOS/Hans 不认 AOSP 的电池白名单。

两次干净复跑（17:58 / 18:05）都是**约 60 秒后心跳停止**：daemon 消失，第二次检查时 `state=S`、`cgroup freezer:/`、`isFrozen=false`，
说明这次**不是冻结**，而是 daemon 死亡后 `Device.displayFrames` 走 socket 把心跳线程拖死 → 报告没落盘。

本轮顺手修的 harness 健壮性：

| 改动 | 作用 |
|---|---|
| `KeepAlivePoc.HB_STALL_MS = 4s` | 心跳 4s 无新行即结束该段，不再死等整段（A/B 从 30/60s 缩到 4s） |
| B 段 `am start Settings` → `input keyevent 3` | 不再弹设置页打断用户，app 照样进后台 |
| `DisplayServer.stats` 超时 5s → 2s | 心跳每秒采样不被 socket 拖慢 |

**结论：A 不足以支撑长跑，转 B** —— 心跳 / 识别 / 调度前移到 root daemon（`app_process` 无 process record、不受 freezer），
app 只保留 UI + 前台服务 + socket 客户端。

### 11.6 本轮未做（按约定留到下一轮）

- `README.md` 新入口清单（`keepalive / keepalive_stop / cv_selftest / kb_a|b|c`）
- `THIRD_PARTY_NOTICES.md` 补 OpenCV 4.13.0（Apache-2.0）与 Chaquopy 17.2 归属
- `du` 前后对比与构建中间产物清理（`app/build` 约 200MB 仍在）



### 11.7 协作与 Git 流程（2026-11-14 20:20 起生效）

- 仓库已 git 化：基线 commit `8da15fb`（**已含 B v1 实现**），当前 tag 为 `v0.2.0-b1`（SemVer 预发布，旧 tag `v0.2-b1-daemon-hb-unverified` 已删除），主干 `main`。
- 流程文档：`docs/WORKFLOW.md`（分支 / 提交纪律 / 门禁 / 评审 / 回滚 / 并发 git 权限 / 任务生命周期）。
- 评审锚点用 tag 不用 main（feature 与 main 同源时 main...HEAD 是空 diff）；证据写 `docs/evidence/`。
- 会话分工与 chat_id：
  - A 写手（唯一代码位）`519dbd40-9e9a-480e-939e-248a88fa46fc`
  - B 诊断（只读）`b53d34d9-cb1e-4f60-8516-9e51aef35f77`
  - C 文档 `3bf3c365-dc5b-49a8-84be-dbd1c278198e`
  - D 调研 `e29b2b98-efe0-4c9b-97ee-5d78f9282368`
- **事实修正（重要）**：`HANDOFF 11.5` 里「daemon 约 60s 后消失」是误判。实测 pid 990 **持续存活 ≈71 分钟**，`oom_score_adj=-1000`、cgroup `freezer:/`、PPid=1，`server.log` 仅 `READY`。
  真正导致 A 路线没数字的是：app 侧心跳线程被 socket 调用拖死 + 报告只在最后写一次（所以磁盘上一片空白）。
- **自动旋转定责**：当前 `accelerometer_rotation=0`、`user_rotation=0`；`RotationHistory` 中 18:14:38 的 `USER_ROTATION_FREE` 来源是 `com.ai.assistance.operit MainActivity`（未锁方向的 UI 跟随传感器），**不是脚本写设置**；仓库 2706 个文件 0 处写旋转。
- 隐患：`screen_off_timeout=1800000`，很可能是当时“还原”时把改后的值当成了原值。

## 十二、接力点（写手交棒用）
> 制度见 `docs/WORKFLOW.md §1`：**一个写手位，可以换人，不可并行**。换棒时本节整节替换，不追加历史；**详细内容写在交接包里**（`docs/handoff/`）。

- **分支**：`main`，head = `7854c3f`（PR #21 = BG-32 工具入库 `79d5f13`；PR #22 = 交接包 E 制度 + 主 AI 交接包），与 `origin/main` 同一 sha，ahead=0
- **在飞分支**：无（`docs/e1-handoff-role` 已随 #22 合并；工作区干净，`app/build` 已清、体积 165M）
- **主 AI 交接包（当前）**：`docs/handoff/20261115-1158-main-ai.md`（**调度位换人先读这个**；本棒主 AI 已压缩 3 次）
- **写手棒交接包（上一份，已归档）**：`docs/handoff/archive/20261115-0130-bg22-setSurface.md`（BG-22，A4 续棒用；BG-22 已结案、不进 main）
- **换棒判据**：上下文接近压缩阈值即换棒（256K × 0.7 ≈ 179K 词元，或消息数到 20 条触发总结）；已触发过一次压缩也换
- **写手位交接**：A（`519dbd40…`）→ A2（`8833100c-271d-4988-b752-ebce0f2321ca`）→ A3（`ca2b0219-…`，BG-23）→ A4（`3464a2d7-9d14-4b05-9ee2-35dcd7f6e265`，BG-21/BG-22，已退休不复用）→ **A5（`ca2b0219-0992-4e48-996d-dc9fcf071460`）为当前唯一代码位**
- **本棒任务**：BG-32 工具已入库（PR #21 squash `79d5f13`）；下一步 P0 = 用户配合做**含游戏 + 虚拟屏的真机长跑**，验 `tools/hang_check.sh` 的 **PASS 路径**（至今只跑过阴性对照与半阳性）
- **本轮决策（用户，2026-11-15）**：① **交接包固定由 `OAS-E1 交接包`（`acbf5597-741f-4a08-b437-9c6b7950f184`）撰写**，主 AI 只验收 + 在本节留指针（`WORKFLOW §1` 已改，commit `886c8a6`）；② BG-32 走「分支 + PR + 门禁绿」入库（PR #21），不直推 main；③ 会话**不追分组**，靠 `OAS-<角色><序号> <任务>` 标题前缀识别（BG-25）；④ 文档收口与交接包**外包给子会话**，主 AI 只做决策/验收/合并
- **已知坑**：
  - 心跳每行格式固定 `{"ts":ms,"displayId":id,"frames":n,"pid":p}`（`Heartbeat.java`），别自创字段
  - `displayId=-1` 时 `frames` 恒为 -1，不能用来判「画面在动」；要判渲染必须在真实虚拟屏 displayId 上跑
  - daemon 在 root 域，日志必须双写 `/data/local/tmp/oas-daemon.log` + app cache
  - 改完 jar 必须重跑 `tools/build_displayserver.sh` 覆盖 `app/src/main/assets/server/server.jar`
  - 子会话**没有工作区附件**，主 AI 下指令必须贴绝对路径 `/data/user/0/com.ai.assistance.operit/files/workspace/8868caab-ae27-4814-9992-11fb83225a35`，否则它会去 `/sdcard` 下瞎找
  - **工具分工**：工作区/git 用 `super_admin:terminal`（proot），摸设备用 `super_admin:shell`（root）；proot 里读 `/sys/power/*` 是 `Permission denied`（实测）
  - 从 proot 连不上抽象 socket（`/proc/net/unix` Permission denied、`ConnectionRefused`）
  - 【新】**`super_admin:shell` 默认 cwd 是已被删除的目录** → 任何 `app_process`（含 `kb run`）**必须先 `cd /data/local/tmp`**，否则 JVM 启动期 SIGSEGV（`System_specialProperties ← __strncat_chk`，`getcwd()` 返回 NULL）；`env -i` 不能绕过，只会变成「rc=0 但完全空跑」
  - 【新】`dumpsys power` 里的 `displayId:N` **跟着当前前台画面走**（游戏 `:19`、Operit `:0`），脚本里禁止写死 19
  - 【新】`main` 是受保护分支：直推被 `protected branch hook declined` + `Required status check "static-gate" is expected` 拒 → 一律 分支 + PR + 门禁绿后 squash
  - 【新】proot(`terminal`) 的 `ps` 是 Ubuntu procps，**不支持 `-o PID,ARGS`**（报 `unknown user-defined format specifier "PID"`）→ 设备进程查询一律走 `super_admin:shell`（toybox）
  - 【新】`git commit -F -` 配 heredoc 在 `terminal` 里会被吞字符 → 提交一律用多个 `-m`；判定 push 是否真上去用 `git ls-remote --heads origin`，**不信 push 回执正文**

## 十三、许可

OAS 上游为 **GPL-3.0**，本修改版同样遵守并保留原作者信息，仅供学习交流。
引入 scrcpy 封装须保留其 Apache-2.0 版权头并写 `THIRD_PARTY_NOTICES.md`；MAA-Meow / AzurPilot 为 AGPL-3.0，**不复制其代码**。
