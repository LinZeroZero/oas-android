"""ppocr-onnx 的替代实现（shim）。

本包用于让 OAS（`vendor/oas`）在没有 `onnxruntime` Python 轮子的环境里照常工作：
接口保持与 ppocr-onnx 0.0.3.9 一致，内部推理改走 Kotlin 侧 `OcrRuntime`
（`onnxruntime-android` AAR）。

上游项目：https://github.com/triwinds/ppocr-onnx （Apache-2.0）
"""
__version__ = "0.0.3.9+oas-shim"

__all__ = []
