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
- 位置：`vendor/oas/`（源码副本）
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

---

## 4. 运行时依赖（仅列出，未修改）

| 依赖 | 许可证 |
|---|---|
| Chaquopy（嵌入式 CPython） | MIT（Chaquopy 自身）/ PSF（CPython） |
| NumPy | BSD-3-Clause |
| Pillow | HPND / PIL License |
| AndroidX / Material Components | Apache-2.0 |
| Kotlin / kotlinx | Apache-2.0 |
