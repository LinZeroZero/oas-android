"""设备层（Android 侧）——不修改物理分辨率，靠「内容区检测 + 裁切/letterbox」适配 1280x720 画布。

整体思路（参考 MAA-Meow / AzurPilot 在设备本机做控制的做法）：

    常驻 su shell ──screencap [-d displayId] > raw 文件──► numpy 解析 RGBA
                                               │
                                    检测黑边/内容区（不依赖固定机型）
                                               │
                        16:9 内容 → 裁切；非 16:9 → letterbox（不丢 UI）
                                               │
                                  1280x720 画布 + 坐标正反算

为什么要"映射"：OAS 的所有模板/坐标都是 1280x720 标注的，
而真机是 1080x2412（20:9）或虚拟屏 2412x1080，两边比例不同，必须一一对应。
"""

import os
import time

import numpy as np

CANVAS_W = 1280
CANVAS_H = 720

# 内容区比例与 16:9 的允许偏差，超过就走 letterbox 而不是裁切
RATIO_TOLERANCE = 0.02

# 黑图判定：像素最大通道低于该值算"黑"
BLACK_LEVEL = 8

# 上一次截图得到的映射信息（点击坐标反算要用）
_last_map = {}
_last_stats = {}

_device_cls = None


def _device():
    global _device_cls
    if _device_cls is None:
        from java import jclass

        _device_cls = jclass("org.onmyoji.oas.android.root.Device")
    return _device_cls


# --------------------------------------------------------------------------- #
# 截图
# --------------------------------------------------------------------------- #

def capture_raw(raw_path, display_id=-1):
    """调用 Kotlin 侧用 root 截一张原始帧到文件，返回 (bytes, 耗时ms)。

    display_id >= 0 时走 `screencap -d <id>`（Android 10+），否则抓默认屏。
    """
    start = time.time()
    display_id = int(display_id)
    if display_id < 0:
        size = _device().captureRaw(raw_path)
    else:
        size = _device().captureRawOnDisplay(raw_path, display_id)
    if size is None or int(size) <= 0:
        raise RuntimeError("screencap 失败: %s" % _device().lastError())
    with open(raw_path, "rb") as fp:
        data = fp.read()
    return data, (time.time() - start) * 1000.0


def decode_raw(data):
    """解析 screencap 原始帧。

    头部布局（Android）：width, height, format 各 4 字节小端；
    有些版本会先写一个 0x0c 的长度前缀（12 字节头）。
    """
    first = int.from_bytes(data[0:4], "little")
    if first == 12:
        width = int.from_bytes(data[4:8], "little")
        height = int.from_bytes(data[8:12], "little")
        fmt = int.from_bytes(data[12:16], "little")
        offset = 16
    else:
        width = first
        height = int.from_bytes(data[4:8], "little")
        fmt = int.from_bytes(data[8:12], "little")
        offset = 12

    expected = width * height * 4
    if len(data) - offset < expected:
        raise RuntimeError(
            "原始帧数据不完整: %dx%d 需要 %d 字节, 实际 %d"
            % (width, height, expected, len(data) - offset)
        )

    buf = np.frombuffer(data, dtype=np.uint8, count=expected, offset=offset)
    # format 1 = RGBA_8888，通道顺序 R,G,B,A
    img = buf.reshape(height, width, 4)
    return np.ascontiguousarray(img[:, :, :3]), fmt


# --------------------------------------------------------------------------- #
# 内容区检测 / 画布适配
# --------------------------------------------------------------------------- #

def detect_content_rect(img, threshold=8):
    """找出非纯黑的内容区（游戏 16:9 渲染时两侧/上下会有黑边）。

    这样就不需要假设机型分辨率，也不用改物理分辨率。
    """
    height, width = img.shape[0], img.shape[1]
    col_mean = img.mean(axis=(0, 2))
    row_mean = img.mean(axis=(1, 2))

    cols = np.where(col_mean > threshold)[0]
    rows = np.where(row_mean > threshold)[0]
    if len(cols) == 0 or len(rows) == 0:
        return 0, 0, width, height

    x0, x1 = int(cols[0]), int(cols[-1]) + 1
    y0, y1 = int(rows[0]), int(rows[-1]) + 1
    return x0, y0, x1, y1


def _crop_16_9(rect):
    """把内容区裁成 16:9（居中裁掉多余部分），返回设备侧裁切矩形。"""
    x0, y0, x1, y1 = rect
    cw, ch = x1 - x0, y1 - y0
    target = CANVAS_W / CANVAS_H
    ratio = cw / ch

    if ratio > target:  # 比 16:9 更宽 → 裁左右
        new_w = int(round(ch * target))
        new_w = min(new_w, cw)
        center = (x0 + x1) // 2
        nx0 = max(x0, min(center - new_w // 2, x1 - new_w))
        return nx0, y0, nx0 + new_w, y1

    if ratio < target:  # 比 16:9 更高 → 裁上下
        new_h = int(round(cw / target))
        new_h = min(new_h, ch)
        center = (y0 + y1) // 2
        ny0 = max(y0, min(center - new_h // 2, y1 - new_h))
        return x0, ny0, x1, ny0 + new_h

    return rect


def fit_to_canvas(rect, img_w, img_h, mode="auto", tolerance=RATIO_TOLERANCE):
    """决定「怎么塞进 1280x720 画布」，返回 (设备侧裁切矩形, 画布目标矩形, 实际模式)。

    模式：
      crop      —— 内容区已接近 16:9，裁切后与模板逐像素 1:1（识别率最高）
      letterbox —— 内容区不是 16:9，等比缩放 + 居中黑边，不丢弃任何 UI
      full      —— 内容区异常（全黑等），退化为整屏缩放
    """
    x0, y0, x1, y1 = rect
    cw, ch = x1 - x0, y1 - y0
    if cw <= 0 or ch <= 0:
        return (0, 0, img_w, img_h), (0, 0, CANVAS_W, CANVAS_H), "full"

    target = CANVAS_W / CANVAS_H
    ratio = cw / ch

    if mode == "auto":
        mode = "crop" if abs(ratio / target - 1.0) <= tolerance else "letterbox"

    if mode == "crop":
        return _crop_16_9(rect), (0, 0, CANVAS_W, CANVAS_H), "crop"

    # letterbox：等比缩放到画布内，居中
    if ratio > target:  # 比 16:9 更宽 → 以宽为准
        new_w = CANVAS_W
        new_h = int(round(CANVAS_W / ratio))
    else:
        new_h = CANVAS_H
        new_w = int(round(CANVAS_H * ratio))
    new_w = max(1, min(new_w, CANVAS_W))
    new_h = max(1, min(new_h, CANVAS_H))
    ox = (CANVAS_W - new_w) // 2
    oy = (CANVAS_H - new_h) // 2
    return (x0, y0, x1, y1), (ox, oy, ox + new_w, oy + new_h), "letterbox"


def _resize(crop, width, height):
    """缩放到指定尺寸。优先 PIL（Chaquopy 有预编译轮子），否则退化 NumPy 最近邻。"""
    try:
        from PIL import Image

        image = Image.fromarray(crop)
        image = image.resize((width, height), Image.BILINEAR)
        return np.asarray(image)
    except Exception:
        src_h, src_w = crop.shape[0], crop.shape[1]
        ys = (np.arange(height) * src_h // height).clip(0, src_h - 1)
        xs = (np.arange(width) * src_w // width).clip(0, src_w - 1)
        return crop[ys][:, xs]


def to_canvas(crop, dst_rect):
    """把裁出来的图放到画布的目标矩形里（letterbox 时四周补黑）。"""
    x0, y0, x1, y1 = dst_rect
    width, height = x1 - x0, y1 - y0
    resized = _resize(crop, width, height)
    if (x0, y0, x1, y1) == (0, 0, CANVAS_W, CANVAS_H):
        return resized
    canvas = np.zeros((CANVAS_H, CANVAS_W, 3), dtype=np.uint8)
    canvas[y0:y1, x0:x1] = resized
    return canvas


def image_stats(image):
    """画布统计，用来判定「是不是抓到黑图」。"""
    flat = image.reshape(-1, image.shape[2])
    peak = flat.max(axis=1)
    return {
        "mean": round(float(flat.mean()), 2),
        "std": round(float(flat.std()), 2),
        "black_ratio": round(float((peak < BLACK_LEVEL).mean()), 4),
    }


def capture_canvas(raw_path, preview_png=None, display_id=-1, mode="auto"):
    """抓一帧并转成 1280x720 画布，返回 (canvas_img, stats)。"""
    data, capture_ms = capture_raw(raw_path, display_id)
    decode_start = time.time()
    img, fmt = decode_raw(data)
    decode_ms = (time.time() - decode_start) * 1000.0

    rect = detect_content_rect(img)
    crop_rect, dst_rect, used_mode = fit_to_canvas(rect, img.shape[1], img.shape[0], mode)

    crop = img[crop_rect[1]:crop_rect[3], crop_rect[0]:crop_rect[2]]
    canvas = to_canvas(crop, dst_rect)

    _last_map.clear()
    _last_map.update(
        {
            "screen_w": img.shape[1],
            "screen_h": img.shape[0],
            "rect": crop_rect,      # 设备像素坐标
            "dst": dst_rect,        # 画布坐标
            "mode": used_mode,
        }
    )

    stats = {
        "display_id": int(display_id),
        "screen": "%dx%d" % (img.shape[1], img.shape[0]),
        "format": fmt,
        "content_rect": rect,
        "crop_rect": crop_rect,
        "crop_size": "%dx%d" % (crop_rect[2] - crop_rect[0], crop_rect[3] - crop_rect[1]),
        "canvas_rect": dst_rect,
        "fit_mode": used_mode,
        "canvas": "%dx%d" % (CANVAS_W, CANVAS_H),
        "capture_ms": round(capture_ms, 1),
        "decode_ms": round(decode_ms, 1),
        "raw_bytes": len(data),
    }
    stats.update(image_stats(canvas))
    _last_stats.clear()
    _last_stats.update(stats)

    if preview_png:
        try:
            from PIL import Image

            os.makedirs(os.path.dirname(preview_png), exist_ok=True)
            Image.fromarray(canvas).save(preview_png)
            stats["preview"] = preview_png
        except Exception as exc:  # noqa: BLE001
            stats["preview_error"] = repr(exc)

    return canvas, stats


# --------------------------------------------------------------------------- #
# 坐标映射（支持 letterbox 偏移）
# --------------------------------------------------------------------------- #

def canvas_to_device(cx, cy):
    """1280x720 画布坐标 → 真机/虚拟屏像素坐标。"""
    m = _last_map
    if not m:
        raise RuntimeError("还没有截图，无法换算坐标")

    dx0, dy0, dx1, dy1 = m["rect"]
    cx0, cy0, cx1, cy1 = m.get("dst", (0, 0, CANVAS_W, CANVAS_H))

    # letterbox 黑边上的点击夹到内容区边界，避免点到屏幕外
    cx = min(max(cx, cx0), cx1 - 1)
    cy = min(max(cy, cy0), cy1 - 1)

    sx = dx0 + (cx - cx0) * (dx1 - dx0) / max(1, (cx1 - cx0))
    sy = dy0 + (cy - cy0) * (dy1 - dy0) / max(1, (cy1 - cy0))
    return int(round(sx)), int(round(sy))


def device_to_canvas(sx, sy):
    """真机像素坐标 → 1280x720 画布坐标（标注工具用）。"""
    m = _last_map
    if not m:
        raise RuntimeError("还没有截图，无法换算坐标")

    dx0, dy0, dx1, dy1 = m["rect"]
    cx0, cy0, cx1, cy1 = m.get("dst", (0, 0, CANVAS_W, CANVAS_H))

    cx = cx0 + (sx - dx0) * (cx1 - cx0) / max(1, (dx1 - dx0))
    cy = cy0 + (sy - dy0) * (cy1 - cy0) / max(1, (dy1 - dy0))
    return int(round(cx)), int(round(cy))


def tap_canvas(cx, cy, display_id=-1):
    sx, sy = canvas_to_device(cx, cy)
    display_id = int(display_id)
    if display_id < 0:
        return bool(_device().tap(sx, sy))
    return bool(_device().tapOnDisplay(sx, sy, display_id))


def swipe_canvas(x1, y1, x2, y2, duration_ms=300, display_id=-1):
    p1 = canvas_to_device(x1, y1)
    p2 = canvas_to_device(x2, y2)
    display_id = int(display_id)
    if display_id < 0:
        return bool(_device().swipe(p1[0], p1[1], p2[0], p2[1], duration_ms))
    return bool(_device().swipeOnDisplay(p1[0], p1[1], p2[0], p2[1], duration_ms, display_id))


def last_stats():
    return dict(_last_stats)


def last_map():
    return dict(_last_map)