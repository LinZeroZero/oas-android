# OASX 是否应作为新基座：评估报告

日期：2026-11-17｜评审位：OAS-D（只读调研）｜对象：https://github.com/AzurTian/OASX
抓取方式：GitHub REST API + raw.githubusercontent 原文；所有结论下方标出处，抓不到的显式标注。

---

## 0. 结论（先给可拍板的那一行）

**C —— 不参考（不作为基座）。置信度：高（0.9）。**

一句话理由：OASX 是一个 **Flutter/Dart 的 OAS 前端控制台**（自述「不包含 OAS 服务端本体」），**仓库已 archived（只读归档）**，其「本地部署」流程只认 **Windows 工具链**（`toolkit/python.exe`、`toolkit/Git/cmd/git.exe`）。它既不含我们要接的 Python 自动化引擎，也不含任何 Android 侧运行时/采集/注入/保活实现，**对我们四个卡点（无 python3、画布映射、后台保活、daemon 通信）零覆盖**。

---

## 1) OASX 到底是什么

| 事实 | 证据 |
|---|---|
| 自述是「面向 OAS 的 Flutter GUI 客户端」，**不含服务端本体** | README 第 2-3 行原文：「OASX 是面向 OAS 的 Flutter GUI 客户端，负责提供连接配置、本地服务部署入口、脚本工作台、任务参数编辑、日志查看…**不包含 OAS 服务端本体**；你可以把它理解为 OAS 的跨平台图形控制台。」<br>https://github.com/AzurTian/OASX/blob/master/README.md |
| 与上游 OnmyojiAutoScript 的关系：**不是 fork**，是独立仓库 | API `"fork": false`，且无 `parent` 字段（fork 仓库会有 `parent`/`source`）：https://api.github.com/repos/AzurTian/OASX |
| 与阴阳师是否相关：**相关，但只是「给 OAS 用的控制台」** | README 通篇以 OAS 为服务端（OAS = OnmyojiAutoScript）；名字里的 X 是客户端后缀，不是另一个游戏 |
| 定位：前端 + 桌面交互 | README「本仓库聚焦前端与桌面端交互体验」 |
| 技术栈 | README「Flutter / Dart / GetX / GetStorage / Dio·SSE·WebSocket / Windows 桌面能力（`window_manager`、`system_tray`）」；API `"language": "Dart"` |

结论：**它是 OAS 的 GUI 客户端，不是 OAS 本体，也不是 Android 运行层**。名字里没有阴阳师歧义，但它复用的是「调用远端/本地 OAS 服务」的模型。

## 2) 技术栈与 Android 落地方式

| 维度 | 事实 | 证据 |
|---|---|---|
| 运行时 | Flutter/Dart 应用；**未打包 Python 运行时**（`pubspec.yaml` 资源只引用 `assets/images/Icon-app.png` 图标；recursive tree 里 `termux/python3/toolkit` 命中 0，但 tree 返回 514 条且可能被截断，**此项标「抓不到完整树」，不作强结论**） | https://raw.githubusercontent.com/AzurTian/OASX/master/pubspec.yaml:82-94 |
| Android 落地 | 有 `android/` 平台目录（contents API 200），Actions 覆盖 Android 构建；但 Android 端只是**同一套 UI**，无服务端 | README「GitHub Actions 发布链路明确覆盖：Windows / Android / Web」；`/contents/android` 返回 200 |
| 屏幕采集 | **无**。README 全篇无 VirtualDisplay / MediaProjection / screencap；它是通过 HTTP/SSE/WebSocket 连 OAS 服务取数据 | README 技术栈行 |
| 控制注入 | **无**。无 adb / 无障碍 / uinput / IAT 相关描述 | 同上 |
| 「本地部署」实际形态 | 校验 OAS 根目录中存在 `toolkit/python.exe`、`toolkit/Git/cmd/git.exe`、`deploy/installer.py`、`config/deploy.yaml`；原文：「当前仓库内置的『一键部署』流程**主要面向带有 Windows 工具链的 OAS 根目录**」 | README「本地部署 OAS 服务」段 |

结论：Android 侧**没有**采集/注入/运行时这三件事，等于把 OAS 当远端服务用。

## 3) 它如何解我们踩过的坑

| 我们的坑 | OASX 覆盖情况 |
|---|---|
| 无 python3 的 Android | **零覆盖**。它不打包 Python；Android 端只连服务，本地部署靠 `python.exe`（Windows） |
| 1280×720 画布与坐标映射 | **零覆盖**。不涉及任何像素/坐标换算 |
| 后台保活、息屏继续跑 | **零覆盖**。README 无前台服务/守护/唤醒锁任何描述；它本身是 GUI 应用，息屏场景不在设计目标内 |
| daemon 通信与心跳 | **部分模型可借鉴**（Dio/SSE/WS 客户端 + 断线重连语义），但那是「连远端 HTTP 服务」，与我们「app_process + 抽象 socket + 单行 JSON 协议」不是一回事 |

## 4) 许可与合规

- **GPL-3.0**：`LICENSE` 首行原文 `GNU GENERAL PUBLIC LICENSE / Version 3, 29 June 2007`（https://raw.githubusercontent.com/AzurTian/OASX/master/LICENSE）。
- 与本仓库（GPL）**同族，无新增传染性冲突**；但若真把其代码并入，分发义务照旧（提供源码 + 保留声明），且它是 Flutter/Dart，引入会带来第二套构建链（Flutter SDK 3.35+、Dart 3.9+）。
- 第三方素材：`pubspec.yaml` 仅引用 `assets/images/Icon-app.png` 图标；**未发现游戏图像模板/字体素材**（tree 可能截断，标「抓不到」）。仓库无 `THIRD_PARTY_NOTICES` 类文件（未检索到）。

## 5) 成熟度

| 指标 | 值 | 出处 |
|---|---|---|
| **仓库状态** | **archived: true（已归档只读）** ← 决定性 | https://api.github.com/repos/AzurTian/OASX |
| 最近 push | 2026-07-13T14:33:31Z（距今约 4 个月无更新，且已归档） | 同上 `pushed_at` |
| 提交总数 | 380（commits API `Link: rel="last" ... page=380`） | https://api.github.com/repos/AzurTian/OASX/commits?per_page=1 |
| Star / Fork | 80 / 38 | API |
| Release / APK | **无**（`/releases` 返回 `[]`）；README 称靠 Actions 发布 | https://api.github.com/repos/AzurTian/OASX/releases |
| Issue 活跃度 | `open_issues_count: 0`（归档后不可再开） | API |
| 中文文档 | 有，README 全中文 + 文档站 https://azurtian.github.io/OASX/ （HTTP 200） | 实测 |
| 真机验证说明 | README 未见 Android 真机验证/兼容性说明（只有平台目录与构建覆盖） | README |

**判读**：技术成熟度尚可（380 commits、有 Web 站与 CI），但**已归档 = 上游不再接受任何改动**，不能作为长期演进基座。

## 6) 迁移成本

| 我们的资产 | 若换基座 | 说明 |
|---|---|---|
| `app/`（Kotlin + Compose + 前台服务 + root 封装） | **全部丢弃或重写为 Dart 插件** | Flutter 要调原能力需写 Platform Channel + Kotlin 插件，等价于把现有 Kotlin 层再包一遍 |
| `poc/displayserver`（daemon：VirtualDisplay + grab + input） | **仍需自己写**（OASX 无采集/注入） | 这部分是纯 Java/app_process，Dart 侧无对应物 |
| `patches/`（vendor OAS 3.13 补丁）、`vendor/oas` | **仍需要**，与 OASX 无关 | OASX 不含引擎 |
| `tools/hang_check.sh`、`tools/gate.sh` | 可复用（与语言无关） | — |
| `docs/`（ADR、evidence、研究） | 可复用 | — |
| 新增成本 | Flutter 3.35 + Dart 3.9 工具链、GetX 架构学习、Dart↔Kotlin 桥接 | 与现有 Compose 方案**并行两套 UI 技术栈** |

- 方案 A（换基座）剩余工作量：**≈ 从现在重做一遍**（引擎接入 + Android 运行层 + daemon 全要自研，且基座已归档，无法上游修 bug）。
- 方案 B/C（保持自研）：剩余工作量 = 现有 BACKLOG 的 P0-B 收尾 + 四段保活/帧通路，**远小于** A。

## 7) 明确结论

**选 C：不参考（不作为基座）。置信度 0.9。**

理由（按权重）：
1. **定位不匹配**：OASX 自述不含 OAS 服务端（README 第 2-3 行），换基座等于换个 UI 外壳，引擎与 Android 运行层仍要自研；
2. **已归档**：`archived: true`，上游不再维护，作为基座违反「长期可演进」前提；
3. **部署面向 Windows**：本地部署校验 `toolkit/python.exe`，与「单 APK、无 PC」的产品前提直接冲突；
4. **零覆盖四个卡点**（采集/注入/保活/daemon 通信）。

唯一可借鉴点（不值得为它换基座）：其「客户端 ↔ 服务」的**断线重连与 SSE/WS 状态推送**建模，可能对我们 app↔daemon 的短连接重试策略有参考价值；属于**阅读借鉴**，不需要引入代码。

> 若未来确需 UI 参考（Flutter 桌面端体验），可把 OASX 当**只读设计参考**（GPL-3.0，注意若抄代码需保留来源），但**不作为基座、不作为依赖**。

---

## 附：抓不到 / 未验证项

1. 完整文件树（API 返回 514 条，`truncated` 标志读取失败）→ 「无 Python 运行时/无 Termux」属**弱结论**，仅由 README 与 pubspec 佐证。
2. Android 端是否有真机验证记录：未抓不到（README 无相关段落，issue 已归档不可查）。
3. 其 Actions 产出的 APK 是否可用：未下载验证（只读调研，未取 artifact）。
