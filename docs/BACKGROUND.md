# 阶段 1 · PoC-A：root 特权虚拟屏服务（设计与实测）

> 更新：**2026-11-14 12:30**
> 结论先行：**7/7 判据全部通过**，阴阳师可以原生渲染在 `1280×720@320` 的虚拟屏上，
> 主屏照常用手机，抓帧 p50 = **5.0 ms**，daemon 退出后**零残留**。
> 本文件是「为什么这么设计 + 协议 + 实测数据 + 参考实现对照 + 踩过的坑」，
> 上手操作与待办见 `docs/HANDOFF.md`。

---

## 一、为什么需要这一层

阶段 0 已经能抓主屏并裁成 1280×720 画布，但 `docs/HANDOFF.md` §4.2 的两条实测把主屏路线判死了：

1. 阴阳师在 20:9 屏上是**全屏铺满**（2412×1080，ratio 2.233，无 16:9 黑边），
   letterbox 到 1280×720 后内容只占 `1280×573`，与 OAS 模板**不是 1:1**；
   强行 crop 16:9 又要切掉左右各约 8% 的 UI。
2. 主屏被抓帧时用户就不能用手机。

于是有了 PoC-A：**让游戏渲染在一块 1280×720 的虚拟屏里**。这样
- 游戏原生按 16:9 渲染 → OAS 的 1280×720 模板**零适配**；
- 主屏完全解放，用户可以正常用手机；
- 抓帧不再需要 `screencap` 落文件（165~207ms）→ 我们自己的 Surface 直接读回（5ms）。

---

## 二、两条已核实的 AOSP 14 事实（决定架构，不是推测）

| # | 事实 | 出处 | 对架构的影响 |
|---|---|---|---|
| 1 | `DisplayManagerService.createVirtualDisplayInternal()` 里 `PUBLIC` 先 `flags \|= AUTO_MIRROR`（这步要 `CAPTURE_VIDEO_OUTPUT`），紧接着 `OWN_CONTENT_ONLY` 又 `flags &= ~AUTO_MIRROR` → **最终不带 AUTO_MIRROR，权限检查不触发** | AOSP 14 `services/core/java/com/android/server/display/DisplayManagerService.java` | `PUBLIC\|OWN_CONTENT_ONLY` 组合在**普通 app 权限模型下也合法**；root 只是让 `validatePackageName()` 走 `if (uid == ROOT_UID) return true` 免包名校验 |
| 2 | 虚拟屏的生命周期绑在创建进程的 `IVirtualDisplayCallback` binder 上 | 同上（`VirtualDisplayAdapter.createVirtualDisplayLocked`） | **HANDOFF 原设想「`create` 打印 id 然后 exit」在架构上不成立**：进程一退，binder 死，屏立刻销毁。必须**常驻守护进程**，或一条命令里 batch 跑完全流程 |

第 2 条直接推翻了原计划的「一次性子命令」形态，把交付形态改成
**常驻 daemon + socket 协议 + Kotlin 封装**（用户也确认选这个）。

---

## 三、架构

```
┌──────────── app 进程（u0_a331，Compose + Chaquopy）────────────┐
│ MainActivity  --ei poc_display 1 / --ei cleanup 1              │
│   → poc/DisplayPoc.kt（7 项验证器，结果落盘不依赖 UI）           │
│ Python oas_device.py → Device.captureRawOnDisplay()            │
│   → root/DisplayServer.kt ──LocalSocket(抽象命名空间)──┐         │
└────────────────────────────────────────────────────────┼────────┘
                                                          ▼
┌──── root daemon（uid 0，app_process，独立进程，无 process record）────┐
│ org.onmyoji.oas.server.Main                                      │
│   SocketServer（行协议）                                          │
│     └─ DisplayEngine                                             │
│          ├─ create: VirtualDisplay(PUBLIC|OWN_CONTENT_ONLY)      │
│          │          输出面 = 自持 ImageReader(RGBA_8888)          │
│          │          + 60ms 节流 memcpy 到 last-buffer（≈15fps）   │
│          ├─ grab:   写 screencap 兼容 raw（16B 头 + RGBA8888）   │
│          ├─ mirror: 5 参 createVirtualDisplay 镜像某块屏（兜底 F2）│
│          └─ watch:  在 daemon 侧采样帧数（绕开 freezer）          │
└──────────────────────────────────────────────────────────────────┘
```

### 为什么 daemon 不受 cached-app freezer 影响

freezer 是按 **process record**（有 Activity/Service 的 app 进程）判定的；
`app_process` 起来的 daemon 没有 process record、不在任何 app 的进程组里，
系统不会 SIGSTOP 它。这条是本轮**架构性收益**：
「主屏在刷别的 App 时虚拟屏是否还出帧」这类测量必须在 daemon 侧做，
放 app 进程里测必然得到假阴性（阶段 0 已经踩过一次，延迟 36 秒才写盘）。

---

## 四、socket 协议（行文本 + 单行 JSON）

- 地址：**抽象命名空间** `org.onmyoji.oas.display`
  （`LocalServerSocket(String name)`；不走文件系统 socket → 没有跨 uid 文件权限问题，
  也不留 socket 文件，退出自动消失）
- 编码：UTF-8，一行一条命令，回一行 JSON

| 命令 | 返回要点 |
|---|---|
| `ping` | `{ok,pid,ver,displays,initError}` —— 探活用；`initError` 非空说明反射初始化失败 |
| `create <name> <w> <h> <dpi> [flags]` | `{ok,display:{id,name,w,h,dpi,flags,copies,frames}}` |
| `list` | `{ok,displays:[...]}` |
| `grab <id> <out.raw>` | `{ok,grab:{bytes,ms,mean,blackRatio,frames,copies,w,h,rowStride,pixelStride,rotation}}` |
| `mirror <srcId> <w> <h> <out.raw>` | 建镜像屏 → 睡 1.2s → grab → 自动 destroy（兜底 F2，一次调用自包含） |
| `stats <id>` | `{ok,display:{...},rotation}` |
| `watch <id> <ms> <returnComponent> [shell cmd...]` | `{ok,frames0,frames1,delta,ms,launchOut}` —— **在 daemon 侧**先执行 launch 命令、再数 `ms` 内的帧数增量、最后把 app 拉回前台 |
| `destroy <id>` | `{ok}` |
| `exit` | `{ok,bye}` → daemon 关 socket、`releaseAll()`、删文件、进程退出 |

`grab` 返回的 `frames / copies` 是**归因字段**：
- `frames=0` → 目标屏根本没画出东西（游戏拒绝渲染 / 屏是新的空的）；
- `frames>0` 但 `mean≈0` → 画了但读回是黑的（readback 路径问题）；
- `copies` 是节流后的 memcpy 次数，`frames` 是 ImageReader 收到的总帧数。

raw 格式刻意做成 **`screencap` 兼容**：`12 | width | height | format(=1 RGBA_8888)` 16 字节头
+ RGBA8888 像素，因此 `oas_device.decode_raw()` **零改动**即可解码。
实测 `rowStride=5120`（1280×4，正好无 padding）、`pixelStride=4`。
（代码仍按 `planes[0].rowStride` 逐行拷，换尺寸时不会被 padding 坑。）

---

## 五、7 项判据实测结果（2026-11-14 12:28，displayId=13）

原始记录：设备侧 `files/oas/cache/display_poc.txt` / `display_poc.json`，
证据图归档在 `poc/frames/`。

| # | 判据 | 结果 | 实测数据 |
|---|---|---|---|
| 1 | 建 `PUBLIC\|OWN_CONTENT_ONLY` 虚拟屏拿到 id | ✅ | `displayId=13`，建屏 **102 ms**，`1280×720@320` |
| 2 | 游戏起在该屏、不被弹回主屏 | ✅ | `am start` ok；`dumpsys` 确认游戏 Activity 在 display 13 |
| 3 | 出帧非黑 | ✅ | 3 帧 `bytes=3,686,416 mean=99.05 blackRatio=0.0031 ms=12.0 frames=345 rot=0` |
| 4 | 画面内容落 PNG | ✅ | `poc_vdisplay_0.png` = **511 KB**（非黑、有结构） |
| 5 | 定向点击只作用虚拟屏 | ✅ | 点击后虚拟屏帧差 **92.1738%**，主屏帧差 **0.0031%**，`input -d 13 tap code=0` |
| 6 | 主屏刷别的 App 时虚拟屏仍出帧 | ✅ | 主屏切「设置」的 4 s 内帧数 **529 → 647（delta=118）** |
| 7 | 抓帧耗时 / 电量参考 | ✅ | `p50=5.0ms`、`p95=23.0ms`（20 次）；`battery level=61`、`temperature=318`（31.8℃） |

### 与阶段 0 的对比（同一台机器）

| 通道 | 单帧耗时 | 备注 |
|---|---|---|
| `su screencap` → 文件 → 读回（阶段 0） | **165 ~ 211 ms** | 还只是「抓主屏」，不含解码 |
| `captureRawOnDisplay` via daemon（本轮） | **p50 5.0 ms / p95 23.0 ms** | 直接读自持 Surface，不落中间文件 |

> 结论：抓帧不再是瓶颈，后续限频到 1~2 fps 纯粹是为了省电，不是为了性能。

### 附带记录

- `screencap -d <displayId>` 在本机**仍然全部失效**（阶段 0 的结论复现）：所以
  `Device.captureRawOnDisplay()` 改成「daemon 优先、`screencap -d` 兜底」，
  那个兜底路径在这台机器上等于永远不会成功，但它保证了别的 ROM 上还能退。
- 主屏息屏/锁屏时虚拟屏是否仍出帧：**本轮未测**（7 项里没有，属 P1 前台服务阶段一起验）。

---

## 六、参考实现对照（只借鉴架构，不复制代码）

| 参考 | 许可证 | 我们用了什么 | 明确没用 |
|---|---|---|---|
| [scrcpy](https://github.com/Genymobile/scrcpy) | **Apache-2.0** | `third/FakeContext.java`（伪造 `AttributionSource(ROOT_UID)` 拿 system context）与 `third/Workarounds.java`（伪造 `ActivityThread`）是**派生**自它的同名文件，保留版权头；`mirror` 用 5 参 `createVirtualDisplay` 也是它 `createNewVirtualDisplay()` 的同款思路 | 音频/剪贴板/输入/UHID 全部不要；`ScreenCapture.java` 只读了思路（反射 `android.window.ScreenCapture`）未采用为主通道 |
| [MAA-Meow](https://github.com/Aliothmoon/MAA-Meow) | **AGPL-3.0** | 目录布局习惯：`third/` 放 vendored 隐藏 API 封装、设备侧实现集中在一处；特权进程形态的选型参考（它用 Shizuku UserService，我们用 `su + app_process`，更简单且不依赖 Shizuku） | **一行代码都没抄**：`remote/internal/VirtualDisplayManager.kt`、`FrameCaptureHelper.kt`、`FrameCountFpsEstimator.kt`、`TouchCoordMapper.kt`、`DisplayHelper.kt`、`PrimaryDisplayManager.kt`、`PowerController.kt` 只作为「该有哪些能力」的清单参考 |
| [AzurPilot-for-Android](https://github.com/Aliothmoon/AzurPilot-for-Android) | **AGPL-3.0** | 只读结论：匿名共享内存帧通道能把 47.2ms → 15.6ms；防烧屏；渠道 SDK 弹窗抓回 | 不复制代码。我们的 5ms 已经够快，共享内存留作 P1 之后的优化项 |
| [MaaAssistantArknights](https://github.com/MaaAssistantArknights/MaaAssistantArknights) | AGPL-3.0 | 控制器抽象 / 任务流水线的**概念** | 它是 PC 端工具，没有安卓本机后台能力 |

> 许可策略：只有 scrcpy（Apache-2.0）的代码进入本仓库，其余 AGPL 项目**只借鉴架构思路**。
> 见 `THIRD_PARTY_NOTICES.md`。

---

## 七、踩过的坑（本轮新增，后续不要重踩）

### API 兼容坑（4 个，全部已在代码里修好）

| # | 坑 | 现象 | 解法 |
|---|---|---|---|
| 1 | **API 35 移除了 `DisplayManager.createVirtualDisplay(name,w,h,displayIdToMirror,surface)` 5 参重载** | `javac` 报 `no suitable method found` | 镜像屏改用 `VirtualDisplayConfig.Builder` + **反射** `setDisplayIdToMirror`（该方法是 `@hide`） |
| 2 | `Display.getDensityDpi()` **不存在**（那是 `DisplayMetrics` 的字段） | 编译错误 | 直接删掉该调用，dpi 用建屏时传的值 |
| 3 | `LocalServerSocket` 公开构造**只接受 `String`（抽象名）或 `FileDescriptor`** | 传路径当名字 → 抽象名带 `/` 非法 | 改抽象命名空间 `org.onmyoji.oas.display`（**顺带消掉了「root 建的 socket app 无权限」「`/data/local/tmp` 进不去」两条陷阱**） |
| 4 | **`android.system.UnixSocketAddress` 根本不存在**（那是别的平台的类） | 想建文件系统 socket 找不到 API | 只能 `Os.socket/Os.bind` 手搓，工作量不划算 → 放弃文件 socket 路线 |

### 认知坑（2 个，直接影响架构）

| # | 坑 | 说明 |
|---|---|---|
| 5 | **虚拟屏必须由活着的进程持有** | 原 HANDOFF 计划「`create` 打印 id 再 exit」不成立，屏随 `IVirtualDisplayCallback` binder 一起死 |
| 6 | **第 6 项不能在 app 进程里测** | 主屏刷别的 App → 我们的 app 被切后台 → cached-app freezer SIGSTOP → 帧计数冻结，测得的是假阴性。必须在 daemon 侧 `watch` |

### 环境坑

| # | 坑 | 说明 |
|---|---|---|
| 7 | `poc/` 目录是 `dr-xr-xr-x`（只读） | 编辑/删除前先 `chmod -R u+w poc` |
| 8 | `logcat -d \| grep -a OASDisplay` | `OASBench` / `OASDisplay` 在本机 `-s` 过滤不生效，必须管道 grep |
| 9 | daemon 的 stdout 进不了 logcat | 统一走 `android.util.Log`（`third/Ln.java`），另写 `server.log` 文件 |

---

## 八、生命周期与「不留垃圾」的落实

| 场景 | 行为 |
|---|---|
| 启动 | **只由用户主动触发**：`DisplayServer.ensureStarted()` → `su -c 'pkill -f ...; CLASSPATH=... app_process /system/bin ...'` |
| 自启动 | **完全没有**：无 `BootReceiver`、无 `JobScheduler`、无 `AlarmManager`，AndroidManifest 里连 `RECEIVE_BOOT_COMPLETED` 权限都不申请 |
| 运行中 | `filesDir/oas/server/server.pid` 记 pid；`ping` 失败自动重启一次 |
| PoC 结束 | `destroy` → `exit` → `kill <pid>`（等 300ms）→ `pkill -f org.onmyoji.oas.server.Main` 兜底 → 删 pidfile 与 log |
| app 退出 | `MainActivity.onDestroy()` 在 `isFinishing` 时调 `DisplayServer.stop()` |
| 手机重启 | 什么都不起 |
| 手动收尾 | `am start ... --ei cleanup 1` → 杀 daemon + 删临时 raw |

**落盘位置**（全部在 app 私有目录，卸载即清）：
`filesDir/oas/server/server.jar` + `server.pid` + `server.log`（`stop()` 后只留 jar）；
`filesDir/oas/cache/*.raw` / `*.png` / `*.json`。
**不产生**：socket 文件（抽象命名空间）、/data/local/tmp 残留（只在开发期手推 jar 用，收尾已删）。

---

## 九、结论与下一步

**PoC-A 通过** → 阶段 1 主路线确定：root daemon + 自持 Surface + 抽象 socket + Kotlin 封装。
不需要转 PoC-B（MediaProjection）。

进入 P1 时按这个顺序（详见 `HANDOFF.md` §六）：

1. **前台服务 + WakeLock**：daemon 解决了「虚拟屏出帧不被冻」，但**Python 识别线程跑在 app 进程里，仍会被 freezer 冻**。这是真正「后台挂机」的最后一道门。
2. Python 3.13 升级 + `CvBridge.kt` / `cv2.py` shim。
3. `android_impl.py` 插拔点（`screenshot_methods['android']`）。
4. `vendor/oas`（约 52MB）如何进 APK。
5. 顺带：抓帧限频 1~2 fps 省电、息屏/锁屏下是否仍出帧的专项验证。

> 反作弊风险仍按 `HANDOFF.md` §八 处理：产品风险而非技术风险，测试号先跑，不承诺安全。
