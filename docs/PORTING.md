# OAS for Android —— 移植记录

> ⚠️ **历史档案（2026-11-14 之前的记录），当前状态以 [`docs/HANDOFF.md`](HANDOFF.md) 为准。**
> 本文中「进任务前 `wm size 1280x720`」「20:9 屏必须强制 `wm size`」等结论**已废弃**：
> 现方案是**绝不修改物理分辨率**，改用「内容区检测 + 裁切/letterbox + 坐标双向映射」，
> 并由虚拟显示器（目标 1280×720）承担识别画布一致性。保留原文只为记录当年的踩坑过程。


把 [OnmyojiAutoScript](https://github.com/nearaura/OnmyojiAutoScript)（Python 脚本）
做成**单 APK、可离线跑在 root 安卓手机**上的应用。

目标形态（已与需求确认）：

- 脚本与游戏同机运行，不依赖 PC / adb 主机；
- 设备已 root，截图与点击走 `su`；
- 运行时用 **Chaquopy 嵌入式 CPython**（单进程），不用 proot。

---

## 1. 当前进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0 | 构建链路打通 + 环境自检（Python/NumPy/root/截图/目录/空间） | ✅ 已完成，真机验证通过 |
| 1 | 设备层：常驻 `su` shell、`root` 截图/点击方法、强制 1280×720 | ⬜ 待做 |
| 2 | 单进程化：`multiprocessing` → 线程，OCR 去 RPC | ⬜ 待做 |
| 3 | 依赖替换：`cv2` / `onnxruntime` / `pydantic` / `shapely` | ⬜ 待做 |
| 4 | 跑通最小任务闭环（截图→识别→点击→日志） | ⬜ 待做 |
| 5 | App 外壳：前台服务保活、通知、日志导出 | ⬜ 待做 |
| 6 | 配置 UI、任务调度 | ⬜ 待做 |

### 阶段 0 真机实测（Android 14 / arm64 / Magisk）

设备屏幕 1080×2412（20:9）。

| 项目 | 结果 | 耗时 |
|---|---|---|
| Python 运行时 | Chaquopy CPython 3.12.15 | ~830ms（首次启动） |
| NumPy | 1.26.2，矩阵乘法正常 | ~280ms |
| Root | `uid=0(root)` | ~210ms |
| root 截图 | 1080×2412 PNG，137KB | ~520ms |
| 工作目录 | `/data/user/0/org.onmyoji.oas.android/files/oas` | < 10ms |

结论：**Chaquopy 路线可行**。截图 520ms 偏高（每次 `screencap` 都要新起进程 + 落盘），
阶段 1 换常驻 su shell + 原始 raw 数据直读可以压到 100ms 内。

---

## 2. 版本矩阵（重要，互相强约束）

| 组件 | 版本 | 说明 |
|---|---|---|
| Gradle | 8.14.3 | AGP 8.x 不支持 Gradle 9 |
| AGP | 8.13.2 | **不能用 9.x**，见下方坑 1 |
| Kotlin | 2.3.10 | 需显式 `org.jetbrains.kotlin.android`，见坑 3 |
| Chaquopy | 17.2.0 | DSL 已变更，见坑 2 |
| Python | 3.12 | 与构建机 `/usr/bin/python3.12` 对齐 |
| numpy | 1.26.2 | Chaquopy 提供的 Android 轮子 |

---

## 3. 踩坑记录（都已解决，重装环境时按此排查）

### 坑 1：AGP 9 与 Chaquopy 不兼容

AGP 9 取消了旧版 AGP 插件类，Chaquopy 找不到它，`apply` 里注册 DSL 的回调不触发，
表现为**任意 DSL 都不可用**（Kotlin 报 `Unresolved reference 'python'`，
Groovy 报 `Could not find method python()`），且**没有任何提示**。

→ 解决：AGP 降到 8.13.2 + Gradle 8.14.3。

### 坑 2：Chaquopy 15+ 的 DSL 变了

旧的顶层 `python { version = "3.8" }` 已废弃，现在是：

```groovy
chaquopy {
    defaultConfig {
        version = "3.12"
        pip {
            install "numpy"
        }
    }
}
```

app 模块因此使用 **Groovy DSL**（`app/build.gradle`）。Kotlin DSL 里该扩展的
类型安全访问器有时机问题，Groovy 更稳。

### 坑 3：降 AGP 后必须显式加 Kotlin 插件

模板原本依赖 AGP 9 的"内置 Kotlin 支持"，降到 AGP 8 后没有任何 Kotlin 编译任务，
APK 能装但一启动就 `ClassNotFoundException: MainActivity`（dex 里没有类）。

```groovy
plugins {
    id 'org.jetbrains.kotlin.android'   // 不写 version
}
```

不写 version 是因为 KGP 已经通过 `kotlin.plugin.compose` 进入构建脚本 classpath，
再带版本号会报 `already on the classpath with an unknown version`。

### 坑 4：numpy 装不上（pip index 被覆盖）

Chaquopy 的原生包（numpy 等）是它自己编译的 Android 轮子，只在自己的索引里
（`https://chaquo.com/pypi-13.1`）。把 `pip { options "--index-url", "清华源" }`
写进去会得到 `Could not find a version that satisfies the requirement numpy`。

→ 解决：**不要覆盖 index-url**，让 Chaquopy 用默认索引（PyPI + 它自己的）。

### 坑 5：arm64 构建机上 AAPT2 必须是 ARM64 版

`AAPT2 ... Daemon startup failed`。setup 脚本只补了当时 AGP 版本对应的 jar，
换 AGP 后要重新补：`bash tools/patch_aapt2.sh`。

---

## 4. 目录结构

```
app/
├── build.gradle                       # Groovy DSL：android + chaquopy + 依赖
└── src/main/
    ├── AndroidManifest.xml
    ├── java/org/onmyoji/oas/android/
    │   ├── MainActivity.kt            # Compose 首页：自检面板
    │   ├── python/PyRuntime.kt        # Chaquopy 启动/调用封装
    │   ├── root/RootShell.kt          # su 执行 + 截屏（阶段 1 会换常驻 shell）
    │   ├── diag/SelfCheck.kt          # 六项自检 + 报告落盘
    │   └── ui/theme/                  # Compose 主题
    ├── python/
    │   └── android_entry.py           # Python 侧入口（hello/probe/set_workdir/ping）
    └── res/
tools/
├── aapt2/aapt2-arm64-v8a              # 内置 ARM64 aapt2
└── patch_aapt2.sh                     # 换 AGP 版本后重新打补丁
setup_android_env.sh                   # 装 JDK/SDK/Gradle + 打补丁（模板自带）
```

自检报告会写到 `filesDir/last_selfcheck.txt`——息屏或黑屏时也能确认链路是否通，
用户反馈问题时直接发这个文件即可。

---

## 5. 构建 / 安装

```bash
# 1) 一次性环境准备（装 JDK17、Android SDK、Gradle 8.x、打 aapt2 补丁）
chmod +x setup_android_env.sh
./setup_android_env.sh

# 2) 若换过 AGP 版本，重新补 aapt2
bash tools/patch_aapt2.sh

# 3) 构建
export ANDROID_HOME=$HOME/Android
./gradlew :app:assembleDebug

# 产物
app/build/outputs/apk/debug/app-debug.apk
```

安装（root/shell 环境）：

```bash
pm install -r -t app-debug.apk
am start -n org.onmyoji.oas.android/.MainActivity
```

> 首次运行需要授予 root。Magisk 可预先授权（免弹窗）：
> `magisk --sqlite "INSERT OR REPLACE INTO policies (uid,policy,until,logging,notification) VALUES (<uid>,2,0,1,1)"`
> （`policy=2` 表示允许；uid 用 `pm list packages -U | grep onmyoji` 查）

---

## 6. 后续阶段要点

### 阶段 1：设备层

- `RootShell` 升级为常驻 `su` 进程：一次 `su`，用分隔符（如 `printf '\x01%s\x02'`）
  读取命令输出，避免每条命令 fork 一次。
- 截图：`screencap` 输出 **raw**（宽高头 + RGBA）直接 pipe 回进程，`numpy.frombuffer`
  解析后转 BGR；目标 100ms 内。
- 新增 Python 侧 `module/device/method/root.py`，注册到 OAS 已有的
  `screenshot_methods` / `click_methods` 字典（OAS 本身是可插拔设计，不用改调用方）。
- 分辨率：进任务前 `wm size 1280x720` + `settings put global policy_control immersive.full=*`
  隐藏系统栏；原值存 `filesDir/.display_backup`，退出/崩溃时恢复（防手机被锁在 720p）。
- 应用控制：`am start / am force-stop / pidof`（阴阳师包名 `com.netease.onmyoji`）。

### 阶段 2：单进程化

- `module/server/script_process.py`：`multiprocessing.Process/Pipe/Queue` → `threading.Thread` + `queue.Queue`；
  去掉 `signal.signal`（只能在主线程注册）。
- `module/ocr/rpc.py`：Android 下 `ensure_ocr_server_started()` 直接返回 False，OCR 进程内跑。

### 阶段 3：依赖替换（最大工作量）

- `cv2`：先统计真实用到的函数集合，重的（`matchTemplate`/`resize`/`cvtColor`/`imdecode`）
  桥到 Java 侧 OpenCV，简单的用 numpy 实现。
- `onnxruntime`：实现 `InferenceSession.run` 的同名 shim，内部调 `onnxruntime-android` 的
  `OrtSession`，张量用 bytes↔ByteBuffer 传输。
- `pydantic`：v2 的 `pydantic-core` 是 Rust 扩展，Chaquopy 上大概率装不上；
  降 v1 + 兼容垫片，或只实现配置读写需要的子集。
- `shapely` / `pyclipper`（ppocr 的 DB 后处理）：用纯 Python 实现替换。
- 去掉：`adbutils`、`uiautomator2`、`frida`、`zerorpc`、`gevent`、`pyzmq`、`psutil`、`pywin32`。

### 已知风险

1. **20:9 屏幕**：OAS 模板全部按 1280×720 标注，必须强制 `wm size`。异形屏/挖孔可能让
   UI 元素偏移，需要逐个任务验证；备选方案是 MediaProjection 裁切 + 坐标换算。
2. **OCR 数值一致性**：必须用同一张图在 PC 与手机端比对输出，DB 后处理要单独对齐。
3. **上游更新**：所有对 OAS 源码的改动都要以 patch 形式记录（`patches/`），便于跟进上游。

---

## 7. 许可

OAS 本体是 GPL-3.0。分发本修改版需同样开源，并保留原作者信息。
本项目仅供学习交流。
