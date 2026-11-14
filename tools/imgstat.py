#!/usr/bin/env python3
"""粗看一张图：通道统计 + 16x9 亮度字符图（用来在没有看图能力时判断内容）。

用法：python3 tools/imgstat.py <img.png>
"""
import sys

import numpy as np
from PIL import Image

CHARS = " .:-=+*#%@"


def main(path):
    img = Image.open(path).convert("RGB")
    a = np.asarray(img).astype(np.float32)
    print("%s  %dx%d" % (path, img.width, img.height))
    for i, name in enumerate("RGB"):
        ch = a[:, :, i]
        print("  %s: mean=%.1f std=%.1f min=%d max=%d" % (name, ch.mean(), ch.std(), ch.min(), ch.max()))
    lum = a.mean(axis=2)
    h, w = lum.shape
    rows, cols = 9, 16
    print("  亮度图（左上=原点）:")
    for r in range(rows):
        line = ""
        for c in range(cols):
            block = lum[r * h // rows:(r + 1) * h // rows, c * w // cols:(c + 1) * w // cols]
            v = block.mean() / 255.0
            line += CHARS[min(len(CHARS) - 1, int(v * len(CHARS)))]
        print("    |" + line + "|")
    # 颜色结构粗判：取出现最多的 3 个量化色
    q = (a // 32).astype(np.int32)
    flat = q.reshape(-1, 3)
    keys = flat[:, 0] * 64 + flat[:, 1] * 8 + flat[:, 2]
    vals, counts = np.unique(keys, return_counts=True)
    order = np.argsort(-counts)[:3]
    total = flat.shape[0]
    print("  主色（量化到 32 级）:")
    for idx in order:
        k = vals[idx]
        r, g, b = (k // 64) * 32, ((k // 8) % 8) * 32, (k % 8) * 32
        print("    rgb(%3d,%3d,%3d) 占 %.1f%%" % (r, g, b, counts[idx] * 100.0 / total))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    main(sys.argv[1])