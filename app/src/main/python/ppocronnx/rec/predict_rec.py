"""文本识别（rec）—— ppocr-onnx 的替代实现。

与上游的差异：
- 上游用 `onnxruntime` Python 包跑推理；本仓 Chaquopy 无该轮子（cp313 未提供），
  所以推理走 Kotlin 侧 `OcrRuntime`（`onnxruntime-android` AAR）。
- 预处理用纯 numpy（不依赖 cv2），因为 cv2 在本仓也是 shim，多一次 JNI 不划算。

预处理参数与上游完全一致（PaddleOCR PP-OCRv2 rec）：
  rec_image_shape = [3, 32, 320]
  imgW = int(32 * max_wh_ratio)
  /255 -> -0.5 -> /0.5（归一化到 [-1, 1]）
  右侧补零到 imgW
"""
import time
from typing import List, Optional, Tuple

import numpy as np

from .rec_decoder import decode_argmax

__all__ = ["TextRecognizer"]

# 延迟导入 Java 侧，避免在非 Android 环境（如 proot 单测）导入即炸
_OcrRuntime = None
_ctx = None


def _runtime():
    global _OcrRuntime, _ctx
    if _OcrRuntime is None:
        from java import jclass
        from com.chaquo.python import Python

        _ctx = Python.getPlatform().getApplication()
        _OcrRuntime = jclass("org.onmyoji.oas.android.ocr.OcrRuntime")
        _OcrRuntime.init(_ctx, False)  # 默认 CPU；NNAPI 由上层显式开启
    return _OcrRuntime


def _resize_nn(img: np.ndarray, dst_h: int, dst_w: int) -> np.ndarray:
    """最近邻缩放（纯 numpy）。已有实测：对 PP-OCR rec 的合成样本 4/4 正确。"""
    h, w = img.shape[:2]
    ys = (np.arange(dst_h) * h // dst_h).astype(np.intp)
    xs = (np.arange(dst_w) * w // dst_w).astype(np.intp)
    return img[ys][:, xs]


class TextRecognizer(object):
    """对齐 ppocr-onnx 的 `TextRecognizer`：`__call__(img_list) -> (rec_res, elapse)`。"""

    def __init__(self, rec_model_path=None, ort_providers=None):
        # rec_model_path 仅为兼容上游签名；模型固定从 assets 加载
        self.rec_image_shape = [3, 32, 320]
        self.rec_batch_num = 1          # Kotlin 侧一次一张；上游是 6
        self.character = _load_dict()
        self._whitelist: Optional[List[str]] = None

    def set_char_whitelist(self, chars):
        self._whitelist = list(chars) if chars is not None else None

    def resize_norm_img(self, img: np.ndarray, max_wh_ratio: float) -> np.ndarray:
        img_c, img_h, img_w = self.rec_image_shape
        if img.ndim == 2:                       # 灰度 -> 3 通道
            img = np.repeat(img[:, :, None], 3, axis=2)
        elif img.shape[2] == 4:                 # BGRA -> BGR
            img = img[:, :, :3]
        if img.shape[2] != img_c:
            raise ValueError("输入通道数 %d != %d" % (img.shape[2], img_c))

        h, w = img.shape[:2]
        ratio = w / float(h)
        resized_w = min(img_w, int(np.ceil(img_h * ratio)))
        resized_w = max(1, resized_w)
        resized = _resize_nn(img, img_h, resized_w).astype("float32")
        resized = resized.transpose((2, 0, 1)) / 255.0
        resized -= 0.5
        resized /= 0.5

        padded = np.zeros((img_c, img_h, img_w), dtype=np.float32)
        padded[:, :, 0:resized_w] = resized
        return padded

    def __call__(self, img_list: List[np.ndarray]):
        start = time.time()
        runtime = _runtime()
        rec_res = []
        for img in img_list:
            h, w = img.shape[:2]
            max_wh_ratio = w * 1.0 / h
            norm = self.resize_norm_img(img, max_wh_ratio)
            flat = np.ascontiguousarray(norm.reshape(-1))
            c, hh, ww = self.rec_image_shape
            raw = runtime.runRec(flat, int(c), int(hh), int(ww))
            n = len(raw) // 2
            idx = [raw[i] for i in range(n)]
            sc = [raw[n + i] for i in range(n)]
            text, score = decode_argmax(
                idx, sc, self.character, whitelist=self._whitelist
            )
            rec_res.append((text, score))
        return rec_res, time.time() - start


def _load_dict() -> List[str]:
    """从 assets/ocr/ppocr_keys_v1.txt 读字典（6623 行）。"""
    from com.chaquo.python import Python

    assets = Python.getPlatform().getApplication().getAssets()
    with assets.open("ocr/ppocr_keys_v1.txt") as f:
        data = f.read()
    text = data.decode("utf-8") if isinstance(data, bytes) else data
    return [line.rstrip("\r\n") for line in text.split("\n") if line != ""]