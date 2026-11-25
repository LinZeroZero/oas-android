"""全图文本检测（det）—— PP-OCRv3 det + 纯 numpy DB 后处理。

背景（BG-41）：上游用 pyclipper/shapely 做 DB 的 unclip（多边形外扩），
本仓 Chaquopy cp313 没有这两个原生依赖，也刻意不引 cv2（cv2 在本仓是 shim）。
因此这里用**矩形近似**替代 unclip：连通域外接矩形再按比例外扩。

代价（已实测记录，见 docs/evidence/20261125-2325-BG-41-det-spike-numpy-db.md）：
同一行并排的多段文字可能被并进同一框；多列布局可能串行。第一版接受该失真，
后续再用多边形 unclip（Vatti 裁剪）替换。
"""
from typing import List, Tuple

import numpy as np

from ..rec.predict_rec import _runtime

MODEL_ASSET = "ocr/ch_PP-OCRv3_det_infer.onnx"

# 与 spike 一致的参数（n=1 图经验值，非网格搜索结果）
LIMIT_SIDE = 960
BIN_THR = 0.3
UNCLIP_RATIO = 1.15      # 矩形近似：外扩 15%
MIN_PX = 6               # 4x 降采样后的最小正像素数
MAX_BOXES = 20
DOWNSCALE = 4

MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)


def _label_boxes(mask: np.ndarray, f: int, min_px: int, max_boxes: int) -> List[Tuple[int, int, int, int]]:
    """在 4x 降采样掩码上做连通域标注（BFS），返回**降采样坐标**的外接矩形。"""
    h, w = mask.shape
    seen = np.zeros((h, w), np.uint8)
    boxes = []
    for y in range(h):
        if not mask[y].any():
            continue
        for x in range(w):
            if not mask[y, x] or seen[y, x]:
                continue
            stack = [(y, x)]
            seen[y, x] = 1
            n = 0
            miny = maxy = y
            minx = maxx = x
            while stack:
                cy, cx = stack.pop()
                n += 1
                if cy < miny:
                    miny = cy
                if cy > maxy:
                    maxy = cy
                if cx < minx:
                    minx = cx
                if cx > maxx:
                    maxx = cx
                if cy > 0 and mask[cy - 1, cx] and not seen[cy - 1, cx]:
                    seen[cy - 1, cx] = 1
                    stack.append((cy - 1, cx))
                if cy + 1 < h and mask[cy + 1, cx] and not seen[cy + 1, cx]:
                    seen[cy + 1, cx] = 1
                    stack.append((cy + 1, cx))
                if cx > 0 and mask[cy, cx - 1] and not seen[cy, cx - 1]:
                    seen[cy, cx - 1] = 1
                    stack.append((cy, cx - 1))
                if cx + 1 < w and mask[cy, cx + 1] and not seen[cy, cx + 1]:
                    seen[cy, cx + 1] = 1
                    stack.append((cy, cx + 1))
            if n < min_px:
                continue
            bw = (maxx - minx + 1) * f
            bh = (maxy - miny + 1) * f
            if bw < 8 or bh < 8:
                continue
            boxes.append((minx * f, miny * f, bw, bh))
    boxes.sort(key=lambda b: (b[1], b[0]))
    return boxes[:max_boxes]


class TextDetector(object):
    """`__call__(img) -> [(x0, y0, x1, y1), ...]`，坐标是**原图像素**。"""

    def __init__(self, limit_side=LIMIT_SIDE, bin_thr=BIN_THR, unclip_ratio=UNCLIP_RATIO,
                 max_boxes=MAX_BOXES):
        self.limit_side = limit_side
        self.bin_thr = bin_thr
        self.unclip_ratio = unclip_ratio
        self.max_boxes = max_boxes

    def __call__(self, img: np.ndarray) -> List[Tuple[int, int, int, int]]:
        if img is None or img.size == 0:
            return []
        H, W = img.shape[0], img.shape[1]
        scale = min(1.0, float(self.limit_side) / max(W, H))
        nh = max(32, int(round(H * scale / 32)) * 32)
        nw = max(32, int(round(W * scale / 32)) * 32)
        scale = min(nh / float(H), nw / float(W))

        from PIL import Image
        small = np.asarray(
            Image.fromarray(np.ascontiguousarray(img[:, :, :3].astype(np.uint8))).resize((nw, nh), Image.BILINEAR),
            dtype=np.float32,
        ) / 255.0
        x = ((small - MEAN) / STD).transpose(2, 0, 1)[np.newaxis, ...]
        flat = np.ascontiguousarray(x, dtype=np.float32).reshape(-1)

        raw = _runtime().runDet(flat.tolist(), 3, nh, nw)   # Kotlin: FloatArray, c, h, w
        prob = np.asarray(list(raw), dtype=np.float32).reshape(nh, nw)

        mask = prob > self.bin_thr
        f = DOWNSCALE
        hs, ws = nh // f, nw // f
        if hs == 0 or ws == 0:
            return []
        small_mask = mask[:hs * f, :ws * f].reshape(hs, f, ws, f).max(axis=(1, 3))
        raw_boxes = _label_boxes(small_mask, f, MIN_PX, self.max_boxes)

        out = []
        for bx, by, bw, bh in raw_boxes:
            ox, oy = bx / scale, by / scale
            ow, oh = bw / scale, bh / scale
            # 矩形近似 unclip：按比例外扩
            ew = ow * (self.unclip_ratio - 1.0)
            eh = oh * (self.unclip_ratio - 1.0)
            x0 = max(0, int(ox - ew))
            y0 = max(0, int(oy - eh))
            x1 = min(W, int(ox + ow + ew))
            y1 = min(H, int(oy + oh + eh))
            if x1 - x0 < 8 or y1 - y0 < 8:
                continue
            out.append((x0, y0, x1, y1))
        return out
