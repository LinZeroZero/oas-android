"""`ppocronnx.predict_system` 的替代实现（shim）。

目的：让 OAS 的 `from ppocronnx.predict_system import TextSystem` 原样工作，
而内部推理走 Kotlin 侧的 `OcrRuntime`（onnxruntime-android AAR）。

当前实现范围（BG-40 第一版）：
  ✅ ocr_single_line / ocr_lines  —— 单行识别（rec）
  ❌ detect_and_ocr              —— 全图检测（det），需要 pyclipper/shapely，
                                    本仓 Chaquopy 无轮子；调用会抛 NotImplementedError

⚠ 能力边界（2026-11-25 按实际调用链核实，勿再引用「.ocr() 121 处只走 rec」的旧说法）：
  `RuleOcr.ocr()` 是**分发器**（`module/atom/ocr.py:31`），按 mode 转到 `module/ocr/sub_ocr.py` 的 6 个类：
    Full     -> `detect_and_ocr`   ← det，**本版不可用**（约 42 个 O_XXX 实例）
    Quantity -> `detect_and_ocr`   ← det，**本版不可用**（约 22 个）
    Single   -> `ocr_single_line`  ← rec ✅；**失败时会 fallback 到 detect_and_ocr（抛异常）**
    Digit / DigitCounter / Duration -> 继承 Single，同样 rec 优先
  即：Full / Quantity 两模式完全不可用；Single 系主路径可用，但 rec 失败时不会优雅降级。

上游接口与实现参考：ppocr-onnx 0.0.3.9（Apache-2.0，triwinds/ppocr-onnx）。
"""
from typing import Iterable, List, Optional

import numpy as np

from .rec.predict_rec import TextRecognizer

__all__ = ["TextSystem", "BoxedResult"]


class BoxedResult(object):
    """与上游同名字段：box / text_img / ocr_text / score。"""

    box: List[int]
    text_img: np.ndarray
    ocr_text: str
    score: float

    def __init__(self, box, text_img, ocr_text, score):
        self.box = box
        self.text_img = text_img
        self.ocr_text = ocr_text
        self.score = score

    def __str__(self):
        return "BoxedResult(box=%s, text=%r, score=%.4f)" % (
            self.box,
            self.ocr_text,
            self.score,
        )

    def __repr__(self):
        return self.__str__()


class TextSystem(object):
    """对齐上游 `TextSystem` 构造签名（多余参数忽略，保持调用方无需改动）。"""

    def __init__(
        self,
        use_angle_cls=False,
        box_thresh=None,
        unclip_ratio=None,
        rec_model_path=None,
        det_model_path=None,
        ort_providers=None,
    ):
        self.use_angle_cls = use_angle_cls
        self.box_thresh = box_thresh
        self.unclip_ratio = unclip_ratio
        # det 第一版不提供；保留字段以便将来接入
        self.text_detector = None
        self.text_classifier = None
        self.text_recognizer = TextRecognizer(
            rec_model_path=rec_model_path, ort_providers=ort_providers
        )

    def set_char_whitelist(self, chars: Optional[Iterable[str]]):
        self.text_recognizer.set_char_whitelist(chars)

    def ocr_single_line(self, img):
        """单行识别。返回 (text, score)；空结果返回 None（与上游一致）。"""
        res = self.ocr_lines([img])
        if res:
            return res[0]
        return None

    def ocr_lines(self, img_list: List[np.ndarray]):
        """批量单行识别。竖图（高/宽 >= 1.5）会旋转 90°，与上游一致。"""
        tmp_img_list = []
        for img in img_list:
            img_height, img_width = img.shape[0:2]
            if img_width == 0 or img_height == 0:
                tmp_img_list.append(np.zeros((32, 32, 3), dtype=np.uint8))
                continue
            if img_height * 1.0 / img_width >= 1.5:
                img = np.rot90(img)
            tmp_img_list.append(img)
        rec_res, _elapse = self.text_recognizer(tmp_img_list)
        return rec_res

    def detect_and_ocr(self, img: np.ndarray, drop_score=0.5, unclip_ratio=None, box_thresh=None):
        """全图检测 + 识别。本仓第一版未实现（缺 det + pyclipper/shapely）。

        抛 NotImplementedError 而不是返回空列表 —— 静默返回 [] 会让上游把
        「检测失败」误当成「画面没字」，进而做出错误决策。
        """
        raise NotImplementedError(
            "detect_and_ocr 尚未实现（BG-40 第一版只做单行 rec）。"
            "det 需要 pyclipper/shapely，Chaquopy cp313 无轮子；"
            "若要支持全图检测，需先解决这两个原生依赖。"
        )