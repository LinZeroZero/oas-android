# BG-40 首次在 APK 内跑通 OCR（真机）

## 1. 日期 / 分支 / commit
- 日期：2026-11-25（CST）
- 分支：`feat/bg40-ocr-probe`；本文件随该分支提交（sha 以 `git log --oneline -1` 现查）

## 2. APK md5
- `app/build/outputs/apk/debug/app-debug.apk` = **66,984,435 B**
- md5 = `3e66a86f550d5d29dcf3b4ae3593b2b1`

## 3. 四段原始数字

**识别（真机，logcat `OAS_OCR` 原文）**
```
OAS_OCR : {"ok": true, "text": "123", "score": 0.9958, "init_ms": 172.0, "run_ms": 174.4}
```
—— 输入是 PIL 默认字体渲染的数字 `123`（140x64），**识别完全正确，score 0.9958**。

**速度对比**

| 环境 | 路径 | 耗时 |
|---|---|---|
| proot | Python `onnxruntime` 直接推理 | **66–68 ms** |
| **APK 真机** | Python shim → JNI → Kotlin `OrtSession` | **174.4 ms**（init 另计 172 ms） |

**环境**：org.onmyoji.oas.android，ChaQuopy CPython 3.13.16；onnxruntime-android 1.20.0（CPU EP）。

## 4. 设备状态（收工后）
- 已 `force-stop` app、拉回 Operit MainActivity；`accelerometer_rotation=0`、`user_rotation=0`、`mWakefulness=Awake`
- `oas` 虚拟屏 0、无游戏/daemon 进程、`/data/local/tmp` 与 `/sdcard` 本轮文件已清

## 5. 结论
- **OAS 的 OCR 链路首次在 APK 内跑通**（proot 侧只能证明 Python 逻辑，证明不了 Chaquopy/JNI 边界）。
- 真机比 proot 慢 **约 2.6 倍**（174 vs 67 ms）。**未定位到具体开销**，候选：numpy→Java `FloatArray` 的逐元素转换、debug 构建、首次推理未预热。
- 本版仍只有 rec；`detect_and_ocr` 抛 `NotImplementedError`。

## 6. 本次真机首次运行暴露并修掉的 3 个 bug（proot 侧全部无法覆盖）

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | `OCR probe` 根本不跑 | 探针写进了 `probe()`，而 `SelfCheck.checkPython` 只调 `hello`/`smoke_base`，从不调 `probe` | 抽出 `ocr_probe()` 并在 `SelfCheck` 里显式调用 |
| 2 | `ModuleNotFoundError: No module named 'java.io'` | Chaquopy 不支持 `import java.io.X` | 改由 Kotlin `OcrRuntime.readAssetText()` 读字典 |
| 3 | （预防性）`assets.open()` 返回 Java `InputStream` | 不支持 `with`；无参 `read()` 只读 1 字节 | 不再由 Python 读 assets |

## 7. 局限
- 输入是**合成数字图**，不是阴阳师真实截图；中文/描边/复杂背景未验。
- 只测了 CPU EP；NNAPI 未测。
- 单次样本（n=1），按 D-018 不能当性能结论，仅作「能跑通」的证据。
