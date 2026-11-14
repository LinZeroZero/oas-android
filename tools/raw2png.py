#!/usr/bin/env python3
"""把 screencap 兼容的 .raw（16 字节头 + RGBA8888）转成 PNG。

用法：python3 tools/raw2png.py <in.raw> <out.png>

两种头都支持：
  * 本项目的 displayserver：12 | width | height | format(1)  → 共 16 字节
  * 少数设备上的 screencap：width | height | format          → 共 12 字节
"""
import struct
import sys

import numpy as np
from PIL import Image


def convert(src, dst):
    with open(src, "rb") as fp:
        data = fp.read()
    if len(data) < 16:
        raise SystemExit("文件太小: %s" % src)

    first = struct.unpack("<I", data[:4])[0]
    if first == 12:
        width, height, fmt = struct.unpack("<III", data[4:16])
        offset = 16
    else:
        width, height, fmt = struct.unpack("<III", data[:12])
        offset = 12

    need = width * height * 4
    if len(data) - offset < need:
        raise SystemExit("数据不足: 需要 %d，实际 %d" % (need, len(data) - offset))

    pixels = np.frombuffer(data, np.uint8, count=need, offset=offset).reshape(height, width, 4)
    Image.fromarray(pixels[:, :, :3]).save(dst)
    print("%s  %dx%d  fmt=%d  bytes=%d" % (dst, width, height, fmt, len(data)))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    convert(sys.argv[1], sys.argv[2])
