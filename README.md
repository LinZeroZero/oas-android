# OAS for Android

把 [OnmyojiAutoScript](https://github.com/nearaura/OnmyojiAutoScript)（Python 手游脚本）
做成**单个 APK、无需 PC、直接在 root 安卓手机上运行**的应用。

- 运行时：Chaquopy 17.2.0 嵌入式 CPython（**3.13.16**）
- 设备通道：root（`su` + `app_process` 特权守护进程 / `input`），无需 adb、无需电脑
- 当前状态：
  - **阶段 0 完成** —— 构建链路、环境自检、root 抓帧→1280×720 画布管线、可指定 displayId 的无头抓帧验证器（Android 14 / arm64 / Magisk 真机通过，横屏游戏帧 `2412×1080` 已实测）
  - **阶段 1 PoC-A 完成（7/7）** —— root 特权守护进程建 `1280×720@320` 虚拟屏，**阴阳师原生渲染在里面**，主屏照常用手机；抓帧 **p50 5.0 ms**、定向点击只作用虚拟屏、主屏刷别的 App 时虚拟屏仍持续出帧
- 下一步：**P1 前台服务 + WakeLock**（解决 app 进程被 cached-app freezer 冻结），然后 Python 3.13 / `CvBridge` / `android_impl.py` 插拔点

> 📌 **接手开发请先看 [docs/HANDOFF.md](docs/HANDOFF.md)**（当前状态 + 待办清单 + 冷启动步骤 + 已踩过的坑）。
> 阶段 1 虚拟屏的设计、socket 协议、7 项实测数据、参考实现对照见 **[docs/BACKGROUND.md](docs/BACKGROUND.md)**。
> 阶段 0 的历史踩坑记录见 [docs/PORTING.md](docs/PORTING.md)（历史档案，其中 `wm size` 相关结论已废弃）。
> 第三方代码许可（scrcpy / OAS / 仅借鉴的 AGPL 项目）见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 现在这个 APK 能做什么

1. 打开应用自动跑 6 项自检并显示结果；
2. 「立即抓帧」/「15 秒后抓帧（游戏前台用）」会用 root 抓一帧原始画面，
   自动检测内容区（黑边）、按比例裁切或 letterbox 到 1280×720 画布，
   落盘 `filesDir/oas/cache/canvas_1280x720.png`，并把
   `content_rect / crop_rect / canvas_rect / fit_mode` 与耗时写入
   `filesDir/oas/cache/bench.json`（**无 UI 也能验证**）；
3. **无头入口**（`am start` + extra，全部已实现、真机验证过；自动化 / CI / 无 UI 验收用）：

   | extra | 取值 | 作用 |
   |---|---|---|
   | `cleanup` | `1` | 收尾：停前台服务 + 杀 root 守护进程 + 删临时 `.raw`（不留垃圾、不会自启动） |
   | `keepalive_stop` | `1` | 中止正在跑的保活验证 |
   | `keepalive` | `1` | 后台保活四段验证：A 前台基线 → B 切后台 → C 息屏 → D 唤醒 + 定向点击 |
   | `kb_a` / `kb_b` / `kb_c` | 秒 | A / B / C 三段时长，默认 `30 / 60 / 60` |
   | `cv_selftest` | `1` | cv2 shim 自测（32 项，含 1280×720 模板匹配性能采样） |
   | `poc_display` | `1` | 虚拟屏 PoC-A（7 项验证：建屏 → 拉游戏 → 抓帧 → 定向点击 → 主屏并发） |
   | `bench_delay` | 秒 | 采集性能基准：延迟 N 秒后抓帧（配合 `bench_display`） |
   | `bench_display` | 显示 id | 抓哪块屏：`-1` 抓默认屏，`>= 0` 抓指定虚拟屏 |

   分发优先级（一条 `am start` 只做一件事，由 `MainActivity` 的 `when` 按序判定）：
   **`cleanup → keepalive_stop → keepalive → cv_selftest → poc_display → bench_delay`**。

   ```bash
   # 按优先级从「清场」到「跑基准」排的六条入口
   am start -n org.onmyoji.oas.android/.MainActivity --ei cleanup 1
   am start -n org.onmyoji.oas.android/.MainActivity --ei keepalive_stop 1
   am start -n org.onmyoji.oas.android/.MainActivity --ei keepalive 1 --ei kb_a 30 --ei kb_b 60 --ei kb_c 60
   am start -n org.onmyoji.oas.android/.MainActivity --ei cv_selftest 1
   am start -n org.onmyoji.oas.android/.MainActivity --ei poc_display 1
   am start -n org.onmyoji.oas.android/.MainActivity --ei bench_delay 8 --ei bench_display -1

   # 结果都落在 cache，不依赖 UI
   cd /data/user/0/org.onmyoji.oas.android/files/oas/cache
   cat keepalive_poc.txt cv_selftest.txt display_poc.txt bench.json
   ```

   守护进程只在被主动触发时由 `su` 拉起，**不注册任何自启动机制**（无 BootReceiver / JobScheduler / AlarmManager），
   app 正常退出时 `MainActivity.onDestroy` 也会把它和前台服务一起收掉。

自检项：

| 检查项 | 说明 |
|---|---|
| Python 运行时 | Chaquopy CPython 是否启动（~830ms） |
| NumPy | 1.26.2 导入 + 真实矩阵运算（~280ms） |
| Root 权限 | `su` 是否可用、当前屏幕尺寸 |
| root 截图 | `screencap` 实际抓一帧，报告分辨率/体积/耗时（~520ms） |
| 工作目录 | `filesDir/oas` 可写性 |
| 存储空间 | 剩余可用空间 |

结果同时写入 `filesDir/last_selfcheck.txt`，息屏/黑屏也能查看，方便反馈问题。

## 构建

```bash
chmod +x setup_android_env.sh && ./setup_android_env.sh   # 装 JDK17 / SDK / Gradle
bash tools/patch_aapt2.sh                                  # arm64 机器需要换 ARM64 aapt2
export ANDROID_HOME=$HOME/Android
bash tools/build_displayserver.sh                          # 生成虚拟屏 daemon（server.jar）并拷进 assets
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> `dist/oas-android-debug.apk` 是最近一次验证过的产物（37,618,831 B），可直接装；
> `app/build/` 平时保持清理状态，需要新包时再构建（约 50s）。

安装：

```bash
pm install -r -t app-debug.apk
am start -n org.onmyoji.oas.android/.MainActivity
```

首次运行需要授予 root（Magisk 可预先授权，见 docs/PORTING.md）。

## 环境要求

- 构建：JDK 17、Android SDK 35、Gradle 8.14.3、**AGP 8.13.2（不能用 9.x）**、Chaquopy 17.2.0
- 运行：Android 8.0+（minSdk 26）、arm64-v8a、已 root

## 许可

OAS 本体为 GPL-3.0，本修改版同样遵守该许可并保留原作者信息。仅供学习交流。
