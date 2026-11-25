# 第三方代码声明（THIRD PARTY NOTICES）

本仓库包含少量派生自第三方开源项目的代码。所有派生文件均保留原始版权头。
未在此列出的项目**只作为架构参考**，不含任何源代码。

---

## 1. scrcpy（Genymobile/scrcpy）— Apache License 2.0

- 上游：https://github.com/Genymobile/scrcpy
- 许可证：Apache License, Version 2.0（https://www.apache.org/licenses/LICENSE-2.0）
- 用途：在 **root 特权进程**（`app_process`）中伪造 `Context` / `ActivityThread`，
  以便调用系统的隐藏 API 创建虚拟显示器。

| 本仓库文件 | 来源（上游路径） | 改动 |
|---|---|---|
| `poc/displayserver/src/org/onmyoji/oas/server/third/FakeContext.java` | `server/src/main/java/com/genymobile/scrcpy/FakeContext.java` | 改包名；删掉音频/剪贴板相关；`AttributionSource` 固定用 `ROOT_UID`；`getPackageName()` 固定 `com.android.shell` |
| `poc/displayserver/src/org/onmyoji/oas/server/third/Workarounds.java` | `server/src/main/java/com/genymobile/scrcpy/Workarounds.java` | 改包名；只保留 `fillActivityThread` / `fillAppContext` 所需部分 |
| `poc/displayserver/src/org/onmyoji/oas/server/third/Ln.java` | `server/src/main/java/com/genymobile/scrcpy/Ln.java` | 改包名；只保留 `d/i/w/e` 四个方法 |

以下文件**只参考了思路，不含上游代码**：
- `DisplayEngine.java`：`mirror` 命令用 5 参 `createVirtualDisplay` 镜像某块屏，思路同上游
  `VideoSource.createNewVirtualDisplay()`；本仓库用 `VirtualDisplayConfig.Builder` + 反射
  `setDisplayIdToMirror` 实现（因为 API 35 已移除 5 参重载）。
- `docs/BACKGROUND.md` §六：`ScreenCapture.java` 只读了思路，未采用为主抓帧通道。

Apache-2.0 要求：保留版权与许可声明、标明修改。本文件承担「标明修改」义务；
完整许可证文本见 https://www.apache.org/licenses/LICENSE-2.0 。

---

## 2. OnmyojiAutoScript（nearaura/OnmyojiAutoScript）— GNU GPL v3

- 上游：https://github.com/nearaura/OnmyojiAutoScript
- 许可证：GNU General Public License v3.0
- 位置：上游源码**不进本仓库**（`vendor/oas/` 已被 `.gitignore` 排除，避免把第三方代码塞进历史），
  用 `tools/fetch_vendor.sh` 按固定版本取回；本仓库对上游 Python 的改动全部记录在 `patches/` 下
- 说明：本修改版整体同样以 GPL-3.0 分发，保留原作者信息。仅供学习交流。

---

## 3. 仅作架构参考、**未复制任何代码**的项目

以下项目均为 **AGPL-3.0**，为避免许可污染，本仓库**不包含**它们的任何源代码、
资源或图标，只借鉴公开的架构思路：

| 项目 | 参考内容 |
|---|---|
| [MAA-Meow](https://github.com/Aliothmoon/MAA-Meow) | 目录布局习惯（`third/` 放 vendored 封装、设备侧实现集中）；特权进程形态选型（它用 Shizuku UserService，我们用 `su + app_process`）；能力清单（虚拟屏管理 / 帧率估计 / 坐标映射 / 电源控制） |
| [AzurPilot-for-Android](https://github.com/Aliothmoon/AzurPilot-for-Android) | 匿名共享内存帧通道的收益结论（47.2ms → 15.6ms）；防烧屏；渠道 SDK 弹窗抓回 |
| [MaaAssistantArknights](https://github.com/MaaAssistantArknights/MaaAssistantArknights) | 控制器抽象与任务流水线的概念（该项目为 PC 端工具，无安卓本机后台能力） |

另：BG-35（App 内实时预览）调研参考的 **ALAS-AOS**（AGPL-3.0）同属此类——**仅思路参考、无代码引用**，
不作为依赖、不复制其任何文件；**AOSP**（Apache-2.0）仅按公开常量语义自行实现，未复制其源码。

---

## 4. 运行时依赖（仅列出，未修改）

> 本节及后续 §4.x 所有第三方组件的**「标明修改」义务由本文件承担**；Apache-2.0 与 MIT 的**许可证全文副本**见 `docs/licenses/Apache-2.0.txt`、`docs/licenses/MIT.txt`。
> 未随本仓分发的依赖（NumPy BSD-3 / Pillow HPND / CPython PSF）**不另附副本**，全文见各自上游——这是刻意的取舍，避免为未修改的库重复搬运许可证。

| 依赖 | 许可证 |
|---|---|
| Chaquopy 17.2.0（嵌入式 CPython 3.13） | MIT（Chaquopy 自身）/ PSF（CPython） |
| OpenCV 4.13.0（`org.opencv:opencv` AAR） | Apache-2.0 |
| ONNX Runtime 1.20.0（`onnxruntime-android` AAR） | MIT |
| ppocr-onnx 0.0.3.9（接口/算法参考，本仓重新实现为 shim） | Apache-2.0 |
| NumPy 1.26.2（`install "numpy==1.26.2"`，钉版本） | BSD-3-Clause |
| Pillow（`install "pillow"` 未钉版本，由 Chaquopy 预编译轮子决定，构建注释记为 11.0.0） | HPND / PIL License |
| AndroidX / Material Components | Apache-2.0 |
| Kotlin / kotlinx | Apache-2.0 |

许可证全文副本：`docs/licenses/Apache-2.0.txt`、`docs/licenses/MIT.txt`（本仓分发的第三方组件适用）。

版本单一来源：`gradle/libs.versions.toml`（AGP 8.13.2 / Kotlin 2.3.10 / Compose BOM 2026.01.01 / Chaquopy 17.2.0 / OpenCV 4.13.0）与
`app/build.gradle`（Python 3.13、`numpy==1.26.2`、`pillow`）；本表与那两个文件不一致时，以文件为准。

### 4.1 OpenCV 4.13.0 — Apache License 2.0

- 上游：https://opencv.org / https://github.com/opencv/opencv
- 引入方式：`org.opencv:opencv` 官方 AAR（Java 绑定 + `libopencv_java4.so`），
  `abiFilters "arm64-v8a"` 只保留一份原生库
- 暴露给 Python 的两层：
  - `app/src/main/java/org/onmyoji/oas/android/cv/CvBridge.kt`（numpy ↔ `Mat` 搬运）
  - `app/src/main/python/cv2.py`（Python shim，签名对齐；未实现的函数显式报错而非静默）
- **未修改 OpenCV 源码**，只在 Java 侧调用其公开 API。

### 4.2 Chaquopy 17.2.0 — MIT（另有商业许可）

- 上游：https://chaquo.com/chaquopy/
- 许可证：MIT（开源项目免费）；闭源/商业分发另有商业许可，详见上游
- 用途：嵌入式 CPython 3.13 运行时，把 `app/src/main/python/*.py` 打进 APK 并用 `PyRuntime` 调用
- **未修改 Chaquopy 源码**，仅通过 Gradle 插件与其配置块使用。

### 4.3 ONNX Runtime 1.20.0 — MIT

- 上游：https://github.com/microsoft/onnxruntime
- 引入方式：`com.microsoft.onnxruntime:onnxruntime-android` 官方 AAR（`libonnxruntime.so` + `libonnxruntime4j_jni.so`）；
  选它是因为 Chaquopy 的 cp313 没有 `onnxruntime` Python 轮子（索引 404），而 Maven 有 AAR。
- 暴露给 Python 的一层：`app/src/main/java/org/onmyoji/oas/android/ocr/OcrRuntime.kt`
- **未修改 ONNX Runtime 源码**，只在 Kotlin 侧调用其公开 API。

### 4.4 ppocr-onnx 0.0.3.9 — Apache License 2.0（接口与算法参考，本仓重新实现）

- 上游：https://github.com/triwinds/ppocr-onnx （Apache-2.0）
- 模型来源：PaddleOCR PP-OCRv2 识别模型 `ch_PP-OCRv2_rec_infer.onnx` + 字典 `ppocr_keys_v1.txt`（Apache-2.0）
- **本仓未安装该 Python 包**（其硬依赖 `onnxruntime`/`pyclipper` 在 cp313 无轮子），而是写了同名 shim：
  - `app/src/main/python/ppocronnx/predict_system.py` —— `TextSystem` / `BoxedResult` 接口
  - `app/src/main/python/ppocronnx/rec/predict_rec.py` —— 预处理参数与上游一致（`[3,32,320]`、归一化到 [-1,1]）
  - `app/src/main/python/ppocronnx/rec/rec_decoder.py` —— CTC 贪心解码（语义对齐上游 `CTCLabelDecode`）
- 与上游的差异：推理改走 Kotlin `OcrRuntime`；`detect_and_ocr`（det）**未实现**，调用会抛 `NotImplementedError`
- 模型与字典随 APK 分发于 `app/src/main/assets/ocr/`
