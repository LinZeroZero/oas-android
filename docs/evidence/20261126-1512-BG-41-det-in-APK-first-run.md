# BG-41 det 在 APK 内首次跑通（真机）

## 1. 日期 / 分支 / commit
- 2026-11-26 15:12（CST）；分支 `feat/bg41-det-probe`（基于 main `2a15316`）
- APK **69,690,996 B**，md5 `23fceee0c51d83326be80ce9d6928f5f`（`app/build/outputs/apk/debug/app-debug.apk`）

## 2. 环境与做法
- 真机 `org.onmyoji.oas.android/.MainActivity` 启动 → 应用自检调用 `PyRuntime.call("det_probe")`
- `det_probe()`（本 PR 新增）：PIL 默认字体画两行 ASCII（`HELLO 123` / `OAS OCR TEST`，600×240 白底）
  → `TextSystem.detect_and_ocr(img, drop_score=0.3)` → 结果以 JSON 打 `OAS_DET` 标签

## 3. 四段原始数字（logcat 原文）
```
11-26 15:12:27.994 I OAS_OCR : {"ok": true, "text": "123", "score": 0.9958, "init_ms": 156.2, "run_ms": 131.4}
11-26 15:12:32.111 I OAS_DET : {"ok": true, "n": 2, "ms": 4106.7,
  "items": [{"text": "HELLO23", "score": 0.8191}, {"text": " AS OCRTEST", "score": 0.7791}]}
```
| 指标 | 值 |
|---|---|
| det 会话初始化 | 成功（`isDetReady()` 未触异常） |
| 检出框数 | **2**（两行各一个） |
| 识别结果 | `HELLO23`（0.8191）、` AS OCRTEST`（0.7791） |
| 端到端耗时 | **4106.7 ms**（首次调用：含 det 模型 2.4 MB 拷贝 + 两次 session 冷启 + numpy 首次导入） |
| rec 侧回归 | `{"ok": true, "text": "123", "score": 0.9958, run 131.4ms}` —— **未受影响** |

## 4. 结论
- **`detect_and_ocr` 在 APK 内可用**：不再抛 `NotImplementedError`，全图 → 检测 → 逐框识别 → `BoxedResult` 全链路跑通；`RuleOcr` 的 det 分支因此有了实现（`Full`/`Quantity` 的分发不再必然失败）。
- **限定（重要）**：`build.gradle` 仍 `exclude 'module/ocr/**'` —— 实测 `app.imy` 内 `module/ocr/` **0 个文件**、`ppocronnx/` 13 个；故本条只保证 **shim 层 det 链路**，那 58 个对象的**模块级实现**要等解禁才进包。
- **首跑 4.1 s 不可当性能结论**（D-018）：单次、含模型拷贝与冷启；后续应按 n≥5 中位数定标（BG-42）。

## 5. 真机暴露的 bug（proot 侧测不到）
- **`ValueError: attempted relative import beyond top-level package`**：`predict_det.py` 用 `from ..rec.predict_rec import _runtime`，
  在 Chaquopy 的包加载方式下直接抛错（首次跑 `OAS_DET: ok=false` 即此因）。**修法：改绝对导入** `from ppocronnx.rec.predict_rec import _runtime`
  （`predict_system._detector()` 内同一处一并改）。
- 这正是 BG-40 那条教训的复现：**只有装机才暴露的导入/打包类问题**。

## 6. 未覆盖 / 待办
- 输入是 **PIL 合成 ASCII**，不是阴阳师真实界面（中文/描边/多列）；真实界面 det 效果待验
- `HELLO 123` → `HELLO23`（丢了空格后的 `1`）、`OAS OCR TEST` → 前置一个空格：属 rec 单行切分/空格处理问题，非 det 失败
- 竖排文本、深色背景、`thr`/外扩比网格、`max_boxes=20` 上限效果，均未验

## 7. 收工定序核账（AGENTS §5）
- `am force-stop org.onmyoji.oas.android`；`/data/local/tmp/oas_det.apk` 已删
- `DisplayInfo{"oas"` = 0；`user_rotation=0`、`accelerometer_rotation=0`
- 游戏进程 `pidof com.netease.onmyoji` = none；未起 `logcat` 后台进程
- 前台 = `com.ai.assistance.operit/.ui.main.MainActivity`