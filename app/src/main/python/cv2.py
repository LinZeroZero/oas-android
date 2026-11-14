"""cv2 兼容层（shim）：把 opencv-python 的调用面转到 Java 侧 `CvBridge`。

为什么需要它
------------
`opencv-python` 在 Chaquopy 的索引里只有 cp38 / cp310 轮子，Python 3.13 装不上；
官方 Android AAR 自带 arm64 原生库和 Java 绑定。所以这里做一层**薄封装**：

    numpy ndarray ──► CvBridge(Mat) ──► OpenCV 原生
    numpy ndarray ◄── CvBridge(Mat) ◄── OpenCV 原生

设计原则（和 HANDOFF 的硬要求一致）
----------------------------------
1. 只做「类型搬运 + 签名对齐」，不重实现算法；
2. **没实现的函数显式 `raise NotImplementedError`**，绝不静默返回错值；
3. dtype 不做隐式转换：uint8 / uint16 / int16 / int32 / float32 / float64 之外直接 `TypeError`；
4. 通道顺序不交换（np 第 k 通道 == Mat 第 k 通道），语义由传入的常量决定。

已知差异（写在这里，避免以后当成 bug 查）
----------------------------------------
* `FlannBasedMatcher` 实际走 Java 侧 `BFMatcher(NORM_L2)`：对 SIFT 描述子二者给出
  同样的 k-NN，只是速度差异（FLANN 的索引参数被忽略）。
* `findHomography` 只返回 (H, None)：OAS 不使用 inlier mask。
* GUI/绘图函数（`imshow` / `waitKey` / `namedWindow` / `polylines` / `drawMatchesKnn` /
  `rectangle` / `putText` …）没有实现 —— 它们只在 OAS 的调试分支里出现。
"""

import numpy as np
from java import jclass

BRIDGE_CLASS = "org.onmyoji.oas.android.cv.CvBridge"

_bridge = None


def _b():
    global _bridge
    if _bridge is None:
        bridge = jclass(BRIDGE_CLASS)
        if not bridge.load():
            raise RuntimeError("OpenCV 原生库加载失败: %s" % bridge.loadError())
        _bridge = bridge
    return _bridge


def opencv_version() -> str:
    """返回 OpenCV 版本串（Java 侧 Core.getVersionString()）。"""
    return str(_b().version())


# --------------------------------------------------------------------------- #
# numpy <-> Mat
# --------------------------------------------------------------------------- #

_DEPTH_TO_DTYPE = {
    0: np.uint8,
    2: np.uint16,
    3: np.int16,
    4: np.int32,
    5: np.float32,
    6: np.float64,
}

_SUPPORTED_DTYPES = (np.uint8, np.uint16, np.int16, np.int32, np.float32, np.float64)


def _to_mat(img, name="img"):
    """numpy ndarray → Mat（要求 C 连续；不做 dtype 隐式转换）。"""
    arr = img if isinstance(img, np.ndarray) else np.asarray(img)
    if arr.dtype not in _SUPPORTED_DTYPES:
        raise TypeError(
            "cv2 shim 只支持 uint8/uint16/int16/int32/float32/float64，%s 是 %s（不做静默转换）"
            % (name, arr.dtype)
        )
    arr = np.ascontiguousarray(arr)
    if arr.ndim == 2:
        h, w = arr.shape
        ch = 1
    elif arr.ndim == 3:
        h, w, ch = arr.shape
    else:
        raise ValueError("%s 的维度不支持: %s" % (name, arr.shape))

    b = _b()
    if arr.dtype == np.uint8:
        return b.matFromBytes(int(h), int(w), int(ch), arr.tobytes())
    if arr.dtype == np.uint16:
        # 统一走「小端字节 → 原生数组」：Chaquopy 只保证 bytes↔byte[]
        return b.matFromUShortBytes(int(h), int(w), int(ch), arr.tobytes())
    if arr.dtype == np.int16:
        # int16 是 Sobel / convertScaleAbs 的中间结果 dtype，字节布局与 uint16 一致
        return b.matFromInt16Bytes(int(h), int(w), int(ch), arr.tobytes())
    if arr.dtype == np.int32:
        return b.matFromInt32Bytes(int(h), int(w), int(ch), arr.tobytes())
    if arr.dtype == np.float32:
        return b.matFromFloat32Bytes(int(h), int(w), int(ch), arr.tobytes())
    return b.matFromFloat64Bytes(int(h), int(w), int(ch), arr.tobytes())


def _from_mat(mat):
    """Mat → numpy ndarray（拷贝一份，保证可写）。"""
    h, w, ch, depth = [int(v) for v in _b().matShape(mat)]
    dtype = _DEPTH_TO_DTYPE.get(depth)
    if dtype is None:
        raise TypeError("Mat 的 depth=%d 不支持（只支持 8U/16U/32S/32F/64F）" % depth)

    b = _b()
    if dtype == np.uint8:
        raw = bytes(b.matToBytes(mat))
    elif dtype == np.uint16:
        raw = bytes(b.matToUShortBytes(mat))
    elif dtype == np.int16:
        raw = bytes(b.matToInt16Bytes(mat))
    elif dtype == np.int32:
        raw = bytes(b.matToInt32Bytes(mat))
    elif dtype == np.float32:
        raw = bytes(b.matToFloat32Bytes(mat))
    else:
        raw = bytes(b.matToFloat64Bytes(mat))

    arr = np.frombuffer(raw, dtype=dtype)
    if ch == 1:
        return arr.reshape(h, w).copy()
    return arr.reshape(h, w, ch).copy()


def _ret(out, dst):
    """模拟 cv2 的 dst= 参数：写回调用方给的数组并返回它。"""
    if dst is None:
        return out
    if isinstance(dst, np.ndarray) and dst.shape == out.shape and dst.dtype == out.dtype:
        dst[...] = out
        return dst
    # 形状/dtype 不一致时按 cv2 的语义「重新分配」，而不是写坏调用方的 buffer
    return out


def _scalar(value):
    """把标量/tuple 统一成 4 个 double（OpenCV Scalar 语义）。"""
    if isinstance(value, (tuple, list, np.ndarray)):
        vals = [float(v) for v in np.asarray(value).ravel().tolist()]
        while len(vals) < 4:
            vals.append(0.0)
        return vals[:4]
    s = float(value)
    return [s, s, s, s]


def _is_scalar(value):
    if isinstance(value, (int, float, np.integer, np.floating)):
        return True
    if isinstance(value, (tuple, list)) and len(value) <= 4:
        return all(isinstance(v, (int, float, np.integer, np.floating)) for v in value)
    return False


# --------------------------------------------------------------------------- #
# 常量表（值全部对齐 OpenCV 4.x）
# --------------------------------------------------------------------------- #

# 颜色空间转换
COLOR_BGR2BGRA = 0
COLOR_RGB2RGBA = 0
COLOR_BGRA2BGR = 1
COLOR_RGBA2RGB = 1
COLOR_BGR2RGBA = 2
COLOR_RGB2BGRA = 2
COLOR_RGBA2BGR = 3
COLOR_BGRA2RGB = 3
COLOR_BGR2RGB = 4
COLOR_RGB2BGR = 4
COLOR_BGRA2RGBA = 5
COLOR_RGBA2BGRA = 5
COLOR_BGR2GRAY = 6
COLOR_RGB2GRAY = 7
COLOR_GRAY2BGR = 8
COLOR_GRAY2RGB = 8
COLOR_GRAY2BGRA = 9
COLOR_GRAY2RGBA = 9
COLOR_BGRA2GRAY = 10
COLOR_RGBA2GRAY = 11
COLOR_BGR2YCrCb = 36
COLOR_RGB2YCrCb = 37
COLOR_BGR2HSV = 40
COLOR_RGB2HSV = 41
COLOR_BGR2LAB = 44
COLOR_RGB2LAB = 45
COLOR_HSV2BGR = 54
COLOR_HSV2RGB = 55
COLOR_BGR2YUV = 82
COLOR_RGB2YUV = 83
COLOR_YUV2BGR = 84
COLOR_YUV2RGB = 85
COLOR_BGR2HLS = 52
COLOR_RGB2HLS = 53

# 模板匹配
TM_SQDIFF = 0
TM_SQDIFF_NORMED = 1
TM_CCORR = 2
TM_CCORR_NORMED = 3
TM_CCOEFF = 4
TM_CCOEFF_NORMED = 5

# 插值
INTER_NEAREST = 0
INTER_LINEAR = 1
INTER_CUBIC = 2
INTER_AREA = 3
INTER_LANCZOS4 = 4

# 阈值
THRESH_BINARY = 0
THRESH_BINARY_INV = 1
THRESH_TRUNC = 2
THRESH_TOZERO = 3
THRESH_TOZERO_INV = 4
THRESH_MASK = 7
THRESH_OTSU = 8
THRESH_TRIANGLE = 16

# imencode/imwrite 的 params 键（与 OpenCV 的 Imgcodecs 常量对齐）
IMWRITE_JPEG_QUALITY = 1
IMWRITE_PNG_COMPRESSION = 16

# 形态学
MORPH_RECT = 0
MORPH_CROSS = 1
MORPH_ELLIPSE = 2
MORPH_ERODE = 0
MORPH_DILATE = 1
MORPH_OPEN = 2
MORPH_CLOSE = 3
MORPH_GRADIENT = 4
MORPH_TOPHAT = 5
MORPH_BLACKHAT = 6

# 轮廓
RETR_EXTERNAL = 0
RETR_LIST = 1
RETR_CCOMP = 2
RETR_TREE = 3
RETR_FLOODFILL = 4
CHAIN_APPROX_NONE = 1
CHAIN_APPROX_SIMPLE = 2
CHAIN_APPROX_TC89_L1 = 3
CHAIN_APPROX_TC89_KCOS = 4

# 边界
BORDER_CONSTANT = 0
BORDER_REPLICATE = 1
BORDER_REFLECT = 2
BORDER_WRAP = 3
BORDER_REFLECT_101 = 4
BORDER_TRANSPARENT = 5
BORDER_DEFAULT = 4
BORDER_ISOLATED = 16

# 读图
IMREAD_UNCHANGED = -1
IMREAD_GRAYSCALE = 0
IMREAD_COLOR = 1
IMREAD_ANYDEPTH = 2
IMREAD_ANYCOLOR = 4
IMREAD_IGNORE_ORIENTATION = 128

# 旋转
ROTATE_90_CLOCKWISE = 0
ROTATE_180 = 1
ROTATE_90_COUNTERCLOCKWISE = 2

# 单应
RANSAC = 8
LMEDS = 4
RHO = 12

# 范数
NORM_INF = 1
NORM_L1 = 2
NORM_L2 = 4
NORM_HAMMING = 6

CV_8U = 0
CV_8UC1 = 0
CV_8UC3 = 16
CV_8UC4 = 24
CV_32F = 5
CV_64F = 6


# --------------------------------------------------------------------------- #
# 图像变换
# --------------------------------------------------------------------------- #

def cvtColor(src, code, dst=None):
    return _ret(_from_mat(_b().cvtColor(_to_mat(src, "src"), int(code))), dst)


def resize(src, dsize, dst=None, fx=None, fy=None, interpolation=INTER_LINEAR):
    h, w = src.shape[:2]
    if dsize is None:
        if fx is None or fy is None:
            raise ValueError("resize 需要 dsize 或 fx/fy")
        w2, h2 = int(round(w * fx)), int(round(h * fy))
    else:
        w2, h2 = int(round(dsize[0])), int(round(dsize[1]))
    if w2 <= 0 or h2 <= 0:
        raise ValueError("resize 目标尺寸非法: %dx%d" % (w2, h2))
    return _ret(_from_mat(_b().resize(_to_mat(src, "src"), w2, h2, int(interpolation))), dst)


def copyMakeBorder(src, top, bottom, left, right, borderType, dst=None, value=(0, 0, 0)):
    v = _scalar(value)
    out = _b().copyMakeBorder(
        _to_mat(src, "src"),
        int(top), int(bottom), int(left), int(right), int(borderType),
        v[0], v[1], v[2], v[3],
    )
    return _ret(_from_mat(out), dst)


def rotate(src, rotateCode, dst=None):
    return _ret(_from_mat(_b().rotate(_to_mat(src, "src"), int(rotateCode))), dst)


def flip(src, flipCode, dst=None):
    return _ret(_from_mat(_b().flip(_to_mat(src, "src"), int(flipCode))), dst)


def transpose(src, dst=None):
    return _ret(_from_mat(_b().transpose(_to_mat(src, "src"))), dst)


# --------------------------------------------------------------------------- #
# 模板匹配 / 极值
# --------------------------------------------------------------------------- #

def matchTemplate(image, templ, method, result=None, mask=None):
    if mask is not None:
        out = _b().matchTemplateMask(
            _to_mat(image, "image"), _to_mat(templ, "templ"), int(method), _to_mat(mask, "mask")
        )
    else:
        out = _b().matchTemplate(_to_mat(image, "image"), _to_mat(templ, "templ"), int(method))
    return _ret(_from_mat(out), result)


def minMaxLoc(src, mask=None):
    if mask is not None:
        raise NotImplementedError("cv2.minMaxLoc 的 mask 参数未实现")
    if isinstance(src, np.ndarray):
        arr = src
        w = arr.shape[1] if arr.ndim >= 2 else arr.shape[0]
        min_idx = int(np.argmin(arr))
        max_idx = int(np.argmax(arr))
        min_loc = (min_idx % w, min_idx // w) if arr.ndim >= 2 else (min_idx, 0)
        max_loc = (max_idx % w, max_idx // w) if arr.ndim >= 2 else (max_idx, 0)
        return float(arr.min()), float(arr.max()), min_loc, max_loc
    r = _b().minMaxLoc(src)
    return float(r[0]), float(r[1]), (int(r[2]), int(r[3])), (int(r[4]), int(r[5]))


# --------------------------------------------------------------------------- #
# 编解码
# --------------------------------------------------------------------------- #

def imdecode(buf, flags):
    data = np.asarray(buf)
    if data.dtype != np.uint8:
        raise TypeError("imdecode 需要 uint8 缓冲区，收到 %s" % data.dtype)
    mat = _b().imdecode(np.ascontiguousarray(data).tobytes(), int(flags))
    if _b().isEmpty(mat):
        return None
    return _from_mat(mat)


def imencode(ext, img, params=None):
    """编码成内存字节；`params` 支持 cv2 风格的成对 key/value（如 JPEG 质量）。"""
    flat = [] if params is None else [int(v) for v in np.asarray(params).ravel().tolist()]
    if flat and len(flat) % 2 != 0:
        raise ValueError("imencode 的 params 必须是成对的 (key, value)，收到 %r" % (params,))
    data = bytes(_b().imencode(str(ext), _to_mat(img, "img"), flat))
    if not data:
        return False, np.zeros((0, 1), dtype=np.uint8)
    return True, np.frombuffer(data, dtype=np.uint8).reshape(-1, 1).copy()


def imread(filename, flags=IMREAD_COLOR):
    mat = _b().imread(str(filename), int(flags))
    if _b().isEmpty(mat):
        return None
    return _from_mat(mat)


def imwrite(filename, img, params=None):
    if params is not None:
        raise NotImplementedError("cv2.imwrite 的 params 参数未实现")
    return bool(_b().imwrite(str(filename), _to_mat(img, "img")))


# --------------------------------------------------------------------------- #
# 通道 / 算术
# --------------------------------------------------------------------------- #

def split(m, mv=None):
    return [_from_mat(mat) for mat in _b().split(_to_mat(m, "m"))]


def merge(mv, dst=None):
    out = _b().merge([_to_mat(x, "mv[%d]" % i) for i, x in enumerate(mv)])
    return _ret(_from_mat(out), dst)


def _arith(a, b, dst, op_mat, op_scalar):
    """cv2 的算术语义：双方都是图 → Mat 运算；第二操作数是标量 → Scalar 运算。"""
    ma = _to_mat(a, "src1")
    if _is_scalar(b):
        v = _scalar(b)
        out = op_scalar(ma, v[0], v[1], v[2], v[3])
    else:
        out = op_mat(ma, _to_mat(b, "src2"))
    return _ret(_from_mat(out), dst)


def _b_is_scalar_a(a):
    return isinstance(a, (int, float, np.integer, np.floating)) or (
        isinstance(a, (tuple, list)) and len(a) <= 4
    )


def add(src1, src2, dst=None, mask=None, dtype=None):
    if mask is not None or dtype is not None:
        raise NotImplementedError("cv2.add 的 mask/dtype 参数未实现")
    b = _b()
    return _arith(src1, src2, dst, b.add, b.addScalar)


def subtract(src1, src2, dst=None, mask=None, dtype=None):
    if mask is not None or dtype is not None:
        raise NotImplementedError("cv2.subtract 的 mask/dtype 参数未实现")
    b = _b()
    # cv2.subtract(标量, 图) 这个方向 OAS 里真的在用（extract_letters 等）
    if _b_is_scalar_a(src1):
        v = _scalar(src1)
        return _ret(_from_mat(b.scalarsubtract(v[0], v[1], v[2], v[3], _to_mat(src2, "src2"))), dst)
    return _arith(src1, src2, dst, b.subtract, b.subtractScalar)


def multiply(src1, src2, dst=None, scale=None, dtype=None):
    if scale is not None or dtype is not None:
        raise NotImplementedError("cv2.multiply 的 scale/dtype 参数未实现")
    b = _b()
    return _arith(src1, src2, dst, b.multiply, lambda m, v0, v1, v2, v3: b.multiplyScalar(m, v0))


def max(src1, src2, dst=None):
    return _ret(_from_mat(_b().max(_to_mat(src1, "src1"), _to_mat(src2, "src2"))), dst)


def min(src1, src2, dst=None):
    return _ret(_from_mat(_b().min(_to_mat(src1, "src1"), _to_mat(src2, "src2"))), dst)


def mean(src, mask=None):
    if mask is not None:
        raise NotImplementedError("cv2.mean 的 mask 参数未实现")
    vals = [float(v) for v in _b().mean(_to_mat(src, "src"))]
    while len(vals) < 4:
        vals.append(0.0)
    return tuple(vals[:4])


def convertScaleAbs(src, dst=None, alpha=1, beta=0):
    return _ret(_from_mat(_b().convertScaleAbs(_to_mat(src, "src"), float(alpha), float(beta))), dst)


def bitwise_not(src, dst=None, mask=None):
    if mask is not None:
        raise NotImplementedError("cv2.bitwise_not 的 mask 参数未实现")
    return _ret(_from_mat(_b().bitwiseNot(_to_mat(src, "src"))), dst)


def bitwise_and(src1, src2, dst=None, mask=None):
    if mask is not None:
        raise NotImplementedError("cv2.bitwise_and 的 mask 参数未实现")
    if _is_scalar(src2) and not np.isscalar(src1):
        # 标量与图：用 numpy 的按位语义（与 cv2 在整数上完全一致）
        arr = np.asarray(src1)
        return _ret(arr & np.asarray(src2, dtype=arr.dtype), dst)
    return _ret(_from_mat(_b().bitwiseAnd(_to_mat(src1, "src1"), _to_mat(src2, "src2"))), dst)


def bitwise_or(src1, src2, dst=None, mask=None):
    if mask is not None:
        raise NotImplementedError("cv2.bitwise_or 的 mask 参数未实现")
    if _is_scalar(src2) and not np.isscalar(src1):
        arr = np.asarray(src1)
        return _ret(arr | np.asarray(src2, dtype=arr.dtype), dst)
    return _ret(_from_mat(_b().bitwiseOr(_to_mat(src1, "src1"), _to_mat(src2, "src2"))), dst)


def bitwise_xor(src1, src2, dst=None, mask=None):
    if mask is not None:
        raise NotImplementedError("cv2.bitwise_xor 的 mask 参数未实现")
    if _is_scalar(src2) and not np.isscalar(src1):
        arr = np.asarray(src1)
        return _ret(arr ^ np.asarray(src2, dtype=arr.dtype), dst)
    return _ret(_from_mat(_b().bitwiseXor(_to_mat(src1, "src1"), _to_mat(src2, "src2"))), dst)


# --------------------------------------------------------------------------- #
# 二值化 / 形态学 / 轮廓
# --------------------------------------------------------------------------- #

def threshold(src, thresh, maxval, type, dst=None):
    out = _b().threshold(_to_mat(src, "src"), float(thresh), float(maxval), int(type))
    ret = float(_b().lastThresholdValue())
    return ret, _ret(_from_mat(out), dst)


def countNonZero(src):
    return int(_b().countNonZero(_to_mat(src, "src")))


def getStructuringElement(shape, ksize, anchor=None):
    return _from_mat(_b().getStructuringElement(int(shape), int(ksize[0]), int(ksize[1])))


def dilate(src, kernel, dst=None, anchor=None, iterations=1, borderType=BORDER_CONSTANT, borderValue=(0, 0, 0)):
    out = _b().dilate(_to_mat(src, "src"), _to_mat(kernel, "kernel"), int(iterations))
    return _ret(_from_mat(out), dst)


def erode(src, kernel, dst=None, anchor=None, iterations=1, borderType=BORDER_CONSTANT, borderValue=(0, 0, 0)):
    out = _b().erode(_to_mat(src, "src"), _to_mat(kernel, "kernel"), int(iterations))
    return _ret(_from_mat(out), dst)


def findContours(image, mode, method, contours=None, hierarchy=None, offset=None):
    mats = _b().findContours(_to_mat(image, "image"), int(mode), int(method))
    result = [_from_mat(m) for m in mats]
    hier = _from_mat(_b().lastContourHierarchy())
    if hier.size == 0:
        hier = None
    else:
        hier = hier.reshape(1, -1, 4)
    return result, hier


def boundingRect(array):
    return tuple(int(v) for v in _b().boundingRect(_to_mat(array, "array")))


# --------------------------------------------------------------------------- #
# 特征（SIFT）与单应
# --------------------------------------------------------------------------- #

class KeyPoint:
    """模仿 cv2.KeyPoint 的最小可用面（OAS 只用 .pt / .size / .angle）。"""

    __slots__ = ("pt", "size", "angle", "response", "octave", "class_id")

    def __init__(self, x, y, size, angle, response, octave, class_id):
        self.pt = (x, y)
        self.size = size
        self.angle = angle
        self.response = response
        self.octave = octave
        self.class_id = class_id

    def __repr__(self):
        return "KeyPoint(pt=%s, size=%.1f, angle=%.1f)" % (self.pt, self.size, self.angle)


class DMatch:
    """模仿 cv2.DMatch（OAS 只用 queryIdx / trainIdx / distance）。"""

    __slots__ = ("queryIdx", "trainIdx", "distance")

    def __init__(self, query_idx, train_idx, distance):
        self.queryIdx = query_idx
        self.trainIdx = train_idx
        self.distance = distance

    def __repr__(self):
        return "DMatch(q=%d, t=%d, d=%.4f)" % (self.queryIdx, self.trainIdx, self.distance)


class SIFT:
    """`cv2.SIFT_create()` 的返回对象。"""

    def detectAndCompute(self, image, mask=None, descriptors=None, useProvidedKeypoints=False):
        if mask is not None:
            raise NotImplementedError("SIFT.detectAndCompute 的 mask 参数未实现")
        arr = np.asarray(image)
        if arr.ndim != 2:
            # 与真正的 OpenCV 一致：SIFT 只接受单通道 8 位图
            raise ValueError("SIFT_image is not grayscale（需要单通道图，收到 shape=%s）" % (arr.shape,))
        desc = _b().siftDetectAndCompute(_to_mat(arr, "gray"))
        flat = [float(v) for v in _b().lastKeypointData()]
        kps = [
            KeyPoint(flat[i], flat[i + 1], flat[i + 2], flat[i + 3], flat[i + 4], int(flat[i + 5]), int(flat[i + 6]))
            for i in range(0, len(flat), 7)
        ]
        d = _from_mat(desc) if not _b().isEmpty(desc) else None
        return kps, d

    def detect(self, image, mask=None):
        kps, _ = self.detectAndCompute(image, mask)
        return kps


def SIFT_create(nfeatures=0, nOctaveLayers=3, contrastThreshold=0.04, edgeThreshold=10, sigma=1.6):
    # 这些参数当前没有透传（Java 侧用 SIFT.create() 默认值）；如需定制再补
    return SIFT()


class FlannBasedMatcher:
    """FLANN 的替身：实际用 BFMatcher(NORM_L2)。语义对 SIFT 描述子是等价的。"""

    def __init__(self, index_params=None, search_params=None):
        self.index_params = index_params
        self.search_params = search_params

    def knnMatch(self, queryDescriptors, trainDescriptors, k=2, masks=None):
        flat = [float(v) for v in _b().knnMatch(
            _to_mat(queryDescriptors, "query"),
            _to_mat(trainDescriptors, "train"),
            int(k),
        )]
        matches = []
        current = []
        last_q = None
        for i in range(0, len(flat), 3):
            q, t, d = int(flat[i]), int(flat[i + 1]), flat[i + 2]
            if last_q is not None and q != last_q:
                matches.append(current)
                current = []
            current.append(DMatch(q, t, d))
            last_q = q
        if current:
            matches.append(current)
        return matches


class BFMatcher(FlannBasedMatcher):
    pass


def findHomography(srcPoints, dstPoints, method=0, ransacReprojThreshold=3.0, mask=None,
                   maxIters=2000, confidence=0.995):
    src = np.asarray(srcPoints, dtype=np.float32).reshape(-1, 1, 2)
    dst = np.asarray(dstPoints, dtype=np.float32).reshape(-1, 1, 2)
    if src.shape[0] < 4:
        return None, None
    mat = _b().findHomography(
        _b().matFromPoints2f([float(x) for x in src.ravel()]),
        _b().matFromPoints2f([float(x) for x in dst.ravel()]),
        int(method),
        float(ransacReprojThreshold),
    )
    if _b().isEmpty(mat):
        return None, None
    return _from_mat(mat), None


def perspectiveTransform(src, m, dst=None):
    pts = np.asarray(src, dtype=np.float32).reshape(-1, 1, 2)
    out = _b().perspectiveTransform(
        _b().matFromPoints2f([float(x) for x in pts.ravel()]),
        _to_mat(m, "m"),
    )
    flat = [float(v) for v in _b().matToPoints(out)]
    arr = np.asarray(flat, dtype=np.float32).reshape(-1, 1, 2)
    return _ret(arr, dst)


class _Dnn:
    def NMSBoxes(self, bboxes, scores, score_threshold=0.5, nms_threshold=0.5, eta=1.0, top_k=0):
        boxes = np.asarray(bboxes, dtype=np.float64).ravel().tolist()
        sc = np.asarray(scores, dtype=np.float64).ravel().tolist()
        keep = _b().nmsBoxes(boxes, sc, float(score_threshold), float(nms_threshold))
        return np.asarray([int(i) for i in keep], dtype=np.int32)


dnn = _Dnn()


# --------------------------------------------------------------------------- #
# 明确不实现（OAS 只在调试分支用）
# --------------------------------------------------------------------------- #

def _not_implemented(name):
    def _raise(*_args, **_kwargs):
        raise NotImplementedError("cv2.%s 未实现（shim 不支持 GUI/绘图；不要指望它静默返回）" % name)

    return _raise


imshow = _not_implemented("imshow")
waitKey = _not_implemented("waitKey")
destroyAllWindows = _not_implemented("destroyAllWindows")
namedWindow = _not_implemented("namedWindow")
polylines = _not_implemented("polylines")
drawMatchesKnn = _not_implemented("drawMatchesKnn")
drawMatches = _not_implemented("drawMatches")
rectangle = _not_implemented("rectangle")
circle = _not_implemented("circle")
line = _not_implemented("line")
putText = _not_implemented("putText")
calcHist = _not_implemented("calcHist")


def selftest_probe():
    """供自检用：确认原生库能加载、版本能读出来。"""
    return {"version": opencv_version()}
