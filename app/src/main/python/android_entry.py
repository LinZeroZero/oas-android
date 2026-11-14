"""OAS Android 入口模块（Chaquopy 内运行的 Python 侧）。

当前阶段只提供环境自检用的小函数；后续阶段会在这里：
  * 把工作目录切到 filesDir/oas
  * 注入日志/状态回调到 Kotlin
  * 启动 OAS 的 Script 线程
"""

import json
import os
import platform
import sys
import threading
import time

__version__ = "0.1.0"

# Kotlin 侧注入的回调（可选）
_log_sink = None


def set_log_sink(sink) -> None:
    """由 Kotlin 传入一个可调用对象，用于把日志推回 UI。"""
    global _log_sink
    _log_sink = sink


def log(message: str) -> None:
    line = "[%s] %s" % (time.strftime("%H:%M:%S"), message)
    if _log_sink is not None:
        try:
            _log_sink(line)
            return
        except Exception:
            pass
    print(line)


def hello() -> str:
    return "Chaquopy CPython %s" % platform.python_version()


def set_workdir(path: str) -> str:
    """切换工作目录（OAS 的资源/配置/log 都相对于它）。"""
    os.makedirs(path, exist_ok=True)
    os.chdir(path)
    return os.getcwd()


def probe() -> str:
    """返回环境自检信息（JSON 字符串）。任何异常都转成可读文本，方便定位。"""
    info = {
        "python": platform.python_version(),
        "implementation": platform.python_implementation(),
        "cwd": os.getcwd(),
        "executable": sys.executable,
        "prefix": sys.prefix,
        "path_head": sys.path[:6],
    }

    try:
        import numpy

        info["numpy"] = numpy.__version__
        # 跑一个真实计算，确认 BLAS/ABI 没问题
        # arr = [[0,1,2],[3,4,5],[6,7,8]]，arr@arr 的 [0,0] = 0*0 + 1*3 + 2*6 = 15
        arr = numpy.arange(9, dtype="float32").reshape(3, 3)
        info["numpy_matmul_ok"] = bool(
            numpy.allclose((arr @ arr)[0, 0], 15.0)
        )
    except BaseException:  # noqa: BLE001
        import traceback

        info["numpy_error"] = traceback.format_exc()
        info["numpy_import_error"] = True

    return json.dumps(info, ensure_ascii=False)


def ping(delay_ms: int = 0) -> str:
    """给 Kotlin 侧做连通性/线程测试用。"""
    if delay_ms:
        time.sleep(delay_ms / 1000.0)
    return "pong"


def bench_capture(
    work_dir: str,
    rounds: int = 2,
    delay_s: int = 0,
    display_id: int = -1,
) -> str:
    """抓帧 + 转 1280x720 画布，同时把结果写进 cache/bench.json。

    参数：
      work_dir   工作目录（filesDir/oas）
      rounds     抓几轮（第 1 轮含 su shell 冷启动，第 2 轮是稳态）
      delay_s    延迟多少秒再抓。给"游戏在前台时抓帧"用：点完按钮切到游戏，
                 到点再抓，抓到的是游戏画面而不是 App 自己。
      display_id 要抓哪块屏（>=0 走 screencap -d，-1 抓默认屏）

    返回 JSON 字符串；无论成功失败都会落盘 bench.json，方便无 UI 验证。
    """
    import oas_device

    cache_dir = os.path.join(work_dir, "cache")
    os.makedirs(cache_dir, exist_ok=True)

    delay_s = max(0, int(delay_s))
    display_id = int(display_id)
    if delay_s:
        log("bench: 等待 %d 秒后抓帧（display_id=%d）" % (delay_s, display_id))
        time.sleep(delay_s)

    results = []
    for index in range(max(1, int(rounds))):
        raw_path = os.path.join(cache_dir, "frame.raw")
        png_path = os.path.join(cache_dir, "canvas_1280x720.png")
        started = time.time()
        try:
            _, stats = oas_device.capture_canvas(raw_path, png_path, display_id)
            stats["round"] = index + 1
            stats["total_ms"] = round((time.time() - started) * 1000.0, 1)
        except BaseException as exc:  # noqa: BLE001
            import traceback

            stats = {"round": index + 1, "error": traceback.format_exc(), "exc": repr(exc)}
        results.append(stats)

    payload = {
        "ts": time.strftime("%Y-%m-%d %H:%M:%S"),
        "work_dir": work_dir,
        "rounds": max(1, int(rounds)),
        "delay_s": delay_s,
        "display_id": display_id,
        "canvas": "%dx%d" % (oas_device.CANVAS_W, oas_device.CANVAS_H),
        "last_map": oas_device.last_map(),
        "results": results,
    }

    bench_path = os.path.join(cache_dir, "bench.json")
    try:
        with open(bench_path, "w", encoding="utf-8") as fp:
            json.dump(payload, fp, ensure_ascii=False, indent=2, default=str)
        payload["bench_json"] = bench_path
    except BaseException as exc:  # noqa: BLE001
        payload["bench_json_error"] = repr(exc)

    return json.dumps(payload, ensure_ascii=False, default=str)


# --------------------------------------------------------------------------- #
# 后台保活心跳（阶段 2 / P1-1）
# --------------------------------------------------------------------------- #
#
# 为什么要用 Python 线程做心跳：后台挂机时真正会被 cached-app freezer 冻住的是
# **app 进程里的 Python 线程**（daemon 不受影响）。所以「还活着吗」必须在 Python 侧
# 留下带时间戳的痕迹，而不是让 Kotlin 自己报告。
#
# 心跳行（CSV，逗号分隔，列序固定 —— Kotlin 侧 KeepAlivePoc 依赖这个顺序）：
#   0 mono       time.monotonic() 秒
#   1 wall       HH:MM:SS
#   2 pid        进程号
#   3 cpu        /proc/self/stat 的 utime+stime（jiffies，证明真的在跑）
#   4 tid        线程号
#   5 frames     daemon 侧虚拟屏累计帧数（daemon 不受冻结影响 → 真值）
#   6 screen_on  1/0
#   7 mean       抓帧平均亮度（每 mean_every 拍一次，其余沿用上次）
#   8 tap        定向点击结果（1/0，按 tap_every 触发）

_hb_lock = threading.RLock()
_hb_thread = None
_hb_stop = threading.Event()
_hb_state = {
    "path": "",
    "lines": 0,
    "max_gap_ms": 0.0,
    "started_at": 0.0,
    "last_error": "",
    "interval_ms": 0,
    "display_id": -1,
}


def _cpu_jiffies() -> int:
    """本进程累计 CPU 时间（utime+stime，单位 jiffies）。"""
    try:
        with open("/proc/self/stat", "r") as fp:
            raw = fp.read()
        # 进程名可能带空格/括号，从最后一个 ')' 之后开始解析；第 3 个字段是 state
        fields = raw[raw.rfind(")") + 1:].split()
        return int(fields[11]) + int(fields[12])  # #14 utime, #15 stime
    except BaseException:  # noqa: BLE001
        return -1


def _frame_mean(dev, display_id: int, work_dir: str) -> float:
    """抓一帧算平均亮度（用来证明「不只是进程活着，画面也在变」）。"""
    import numpy as np

    raw_path = os.path.join(work_dir, "cache", "hb.raw")
    try:
        if display_id >= 0:
            size = int(dev.captureRawOnDisplay(raw_path, int(display_id)))
        else:
            size = int(dev.captureRaw(raw_path))
        if size <= 16:
            return -1.0
        data = np.fromfile(raw_path, dtype=np.uint8)
        if data.size <= 16:
            return -1.0
        # screencap 兼容头 16 字节（12 字节头时偏移也够，均值只做趋势判断）
        return float(data[16:].mean())
    except BaseException:  # noqa: BLE001
        return -1.0
    finally:
        try:
            os.remove(raw_path)
        except BaseException:  # noqa: BLE001
            pass


def _hb_loop(path: str, interval_ms: int, display_id: int, tap_every: int, mean_every: int, work_dir: str) -> None:
    from java import jclass

    dev = jclass("org.onmyoji.oas.android.root.Device")
    beat = 0
    last_mono = None
    mean = -1.0

    with open(path, "a", encoding="utf-8", buffering=1) as fp:
        while not _hb_stop.is_set():
            began = time.monotonic()
            try:
                frames = int(dev.displayFrames(int(display_id))) if display_id >= 0 else -1
                screen_on = 1 if dev.screenOn() else 0
                if mean_every > 0 and beat % mean_every == 0:
                    mean = _frame_mean(dev, display_id, work_dir)
                tap = ""
                if tap_every > 0 and beat % tap_every == 0 and display_id >= 0:
                    tap = "1" if dev.tapOnDisplay(640, 360, int(display_id)) else "0"

                mono = time.monotonic()
                if last_mono is not None:
                    gap = (mono - last_mono) * 1000.0
                    if gap > _hb_state["max_gap_ms"]:
                        _hb_state["max_gap_ms"] = gap
                last_mono = mono

                fp.write(
                    "%.3f,%s,%d,%d,%d,%d,%d,%.2f,%s\n"
                    % (
                        mono,
                        time.strftime("%H:%M:%S"),
                        os.getpid(),
                        _cpu_jiffies(),
                        threading.get_ident(),
                        frames,
                        screen_on,
                        mean,
                        tap,
                    )
                )
                fp.flush()
                os.fsync(fp.fileno())
                beat += 1
                _hb_state["lines"] = beat
            except BaseException as exc:  # noqa: BLE001
                _hb_state["last_error"] = repr(exc)

            remain = interval_ms / 1000.0 - (time.monotonic() - began)
            if remain > 0:
                _hb_stop.wait(remain)


def start_heartbeat(
    work_dir: str,
    interval_ms: int = 1000,
    display_id: int = -1,
    tap_every: int = 0,
    mean_every: int = 10,
) -> str:
    """起一个 Python 心跳线程（daemon 线程，随进程退出）。

    返回 JSON；`ok=true` 表示线程已启动（或本来就在跑）。
    """
    global _hb_thread

    with _hb_lock:
        if _hb_thread is not None and _hb_thread.is_alive():
            return json.dumps(
                {"ok": True, "already": True, "path": _hb_state.get("path", "")},
                ensure_ascii=False,
            )

        cache = os.path.join(work_dir, "cache")
        os.makedirs(cache, exist_ok=True)
        path = os.path.join(cache, "keepalive_hb.txt")
        try:
            with open(path, "w", encoding="utf-8"):
                pass
        except BaseException as exc:  # noqa: BLE001
            return json.dumps({"ok": False, "error": "心跳文件不可写: %r" % (exc,)}, ensure_ascii=False)

        _hb_stop.clear()
        _hb_state.update(
            {
                "path": path,
                "lines": 0,
                "max_gap_ms": 0.0,
                "started_at": time.time(),
                "last_error": "",
                "interval_ms": int(interval_ms),
                "display_id": int(display_id),
            }
        )
        _hb_thread = threading.Thread(
            target=_hb_loop,
            args=(path, max(200, int(interval_ms)), int(display_id), int(tap_every), int(mean_every), work_dir),
            name="oas-heartbeat",
            daemon=True,
        )
        _hb_thread.start()

        return json.dumps(
            {
                "ok": True,
                "path": path,
                "interval_ms": int(interval_ms),
                "display_id": int(display_id),
                "python": platform.python_version(),
            },
            ensure_ascii=False,
        )


def stop_heartbeat() -> str:
    """停心跳线程并落一份摘要（行数、最大间隔、错误）。"""
    global _hb_thread

    _hb_stop.set()
    thread = _hb_thread
    if thread is not None:
        thread.join(timeout=3.0)
    payload = {
        "ok": True,
        "alive": bool(thread.is_alive()) if thread is not None else False,
        "python": platform.python_version(),
    }
    payload.update(dict(_hb_state))
    _hb_thread = None

    path = payload.get("path", "")
    if path:
        with _hb_lock:
            try:
                with open(os.path.join(os.path.dirname(path), "keepalive.json"), "w", encoding="utf-8") as fp:
                    json.dump({k: v for k, v in payload.items() if k != "path"}, fp, ensure_ascii=False, indent=2)
            except BaseException:  # noqa: BLE001
                pass
    return json.dumps(payload, ensure_ascii=False, default=str)


def heartbeat_status() -> str:
    payload = dict(_hb_state)
    thread = _hb_thread
    payload["alive"] = bool(thread is not None and thread.is_alive())
    return json.dumps(payload, ensure_ascii=False, default=str)


# --------------------------------------------------------------------------- #
# cv2 shim 自测（阶段 2：CvBridge + cv2.py）
# --------------------------------------------------------------------------- #

def cv_selftest(work_dir: str) -> str:
    """把 cv2 shim 的关键语义逐条对拍（期望值用 PIL/numpy 独立算，不自我循环验证）。

    结果写 `cache/cv_selftest.json` + `.txt`，返回 JSON 摘要。
    """
    import traceback

    import numpy as np

    checks = []
    info = {}
    perf = {}

    def check(name, ok, detail=""):
        checks.append({"name": name, "ok": bool(ok), "detail": str(detail)})

    def guarded(name, fn):
        try:
            ok, detail = fn()
            check(name, ok, detail)
        except BaseException as exc:  # noqa: BLE001
            check(name, False, "异常: %r" % (exc,))
            info.setdefault("tracebacks", {})[name] = traceback.format_exc()[-800:]

    cache = os.path.join(work_dir, "cache")
    os.makedirs(cache, exist_ok=True)

    try:
        from PIL import Image

        info["pillow"] = getattr(Image, "__version__", "?")
    except BaseException as exc:  # noqa: BLE001
        info["pillow_error"] = repr(exc)

    import cv2

    info["python"] = platform.python_version()
    info["numpy"] = np.__version__
    try:
        info["opencv"] = cv2.opencv_version()
    except BaseException as exc:  # noqa: BLE001
        info["opencv_error"] = repr(exc)
        info["tracebacks"] = {"load": traceback.format_exc()[-1500:]}
        return _finish_selftest(cache, checks, info, perf, fatal="OpenCV 加载失败")

    # ---- 造图：固定种子，结果可复现 ----
    rng = np.random.RandomState(20261114)
    img = rng.randint(0, 256, size=(120, 160, 3), dtype=np.uint8)

    def t_cvt_gray():
        got = cv2.cvtColor(img, cv2.COLOR_RGB2GRAY)
        expect = np.clip(
            0.299 * img[:, :, 0] + 0.587 * img[:, :, 1] + 0.114 * img[:, :, 2], 0, 255
        ).round()
        diff = float(np.abs(got.astype(np.float32) - expect.astype(np.float32)).max())
        return diff <= 2.0, "shape=%s maxDiff=%.2f（OpenCV 定点系数 vs 浮点公式）" % (got.shape, diff)

    guarded("cvtColor RGB2GRAY 与浮点公式一致(≤2)", t_cvt_gray)

    def t_resize_nearest():
        got = cv2.resize(img, (80, 60), interpolation=cv2.INTER_NEAREST)
        src_h, src_w = img.shape[:2]
        ys = (np.arange(60) * src_h // 60).clip(0, src_h - 1)
        xs = (np.arange(80) * src_w // 80).clip(0, src_w - 1)
        expect = img[ys][:, xs]
        same = bool(np.array_equal(got, expect))
        return same, "shape=%s 与 numpy 最近邻逐像素一致=%s" % (got.shape, same)

    guarded("resize INTER_NEAREST 与 numpy 参考一致", t_resize_nearest)

    def t_resize_shape():
        got = cv2.resize(img, (333, 77), interpolation=cv2.INTER_LINEAR)
        return got.shape == (77, 333, 3), "shape=%s" % (got.shape,)

    guarded("resize INTER_LINEAR 输出形状", t_resize_shape)

    def t_border():
        got = cv2.copyMakeBorder(img, 4, 6, 7, 8, cv2.BORDER_CONSTANT, value=(1, 2, 3))
        ok_shape = got.shape == (120 + 10, 160 + 15, 3)
        ok_corner = bool(np.array_equal(got[0, 0], np.array([1, 2, 3], dtype=np.uint8)))
        return ok_shape and ok_corner, "shape=%s 角点=%s" % (got.shape, got[0, 0].tolist())

    guarded("copyMakeBorder 尺寸 + 填充值", t_border)

    # 埋一个 20x16 的补丁，验证模板匹配定位
    patch_x, patch_y = 37, 53
    patch = rng.randint(0, 256, size=(16, 20, 3), dtype=np.uint8)
    img2 = img.copy()
    img2[patch_y:patch_y + 16, patch_x:patch_x + 20] = patch

    def t_match():
        res = cv2.matchTemplate(img2, patch, cv2.TM_CCOEFF_NORMED)
        min_v, max_v, min_loc, max_loc = cv2.minMaxLoc(res)
        return (
            max_loc == (patch_x, patch_y) and max_v > 0.99,
            "result=%s max=(%.4f) loc=%s 期望=%s" % (res.shape, max_v, max_loc, (patch_x, patch_y)),
        )

    guarded("matchTemplate + minMaxLoc 定位命中", t_match)

    def t_match_mask():
        mask = np.full((16, 20, 3), 255, dtype=np.uint8)
        res = cv2.matchTemplate(img2, patch, cv2.TM_CCORR_NORMED, mask=mask)
        _, max_v, _, max_loc = cv2.minMaxLoc(res)
        return max_v > 0.99 and max_loc == (patch_x, patch_y), "max=%.4f loc=%s" % (max_v, max_loc)

    guarded("matchTemplate(mask=) 分支可用", t_match_mask)

    def t_match_result_dtype():
        res = cv2.matchTemplate(img2, patch, cv2.TM_CCORR_NORMED)
        return res.dtype == np.float32 and res.ndim == 2, "dtype=%s shape=%s" % (res.dtype, res.shape)

    guarded("matchTemplate 返回 float32 单通道", t_match_result_dtype)

    def t_png_roundtrip():
        ok, buf = cv2.imencode(".png", img)
        back = cv2.imdecode(buf, cv2.IMREAD_UNCHANGED)
        same = bool(np.array_equal(back, img))
        return ok and same, "编码 %d 字节，PNG 往返逐像素一致=%s" % (len(buf), same)

    guarded("imencode/imdecode PNG 无损往返", t_png_roundtrip)

    def t_jpg_roundtrip():
        # 判据图不能用白噪声：噪声是 JPEG 的最坏情形（4:2:0 色度子采样 + DCT 高频全丢），
        # 那样测出来的误差反映的是压缩格式本身，而不是 shim 有没有把参数/通道搬对。
        # 这里用平滑渐变复现「真实画面」量级，并显式指定 q=95。
        yy, xx = np.mgrid[0:120, 0:160]
        smooth = np.dstack([
            (xx * 255 // 159),
            (yy * 255 // 119),
            ((xx + yy) * 255 // 278),
        ]).astype(np.uint8)
        ok, buf = cv2.imencode(".jpg", smooth, [int(cv2.IMWRITE_JPEG_QUALITY), 95])
        back = cv2.imdecode(buf, cv2.IMREAD_COLOR)
        diff = float(np.abs(back.astype(np.int16) - smooth.astype(np.int16)).mean())
        return ok and back.shape == smooth.shape and diff < 3.0, "JPEG(q=95) 平均误差=%.2f" % diff

    guarded("imencode/imdecode JPEG 有损往返(q=95,误差<3)", t_jpg_roundtrip)

    def t_imwrite_read():
        path = os.path.join(cache, "cv_selftest.png")
        okw = cv2.imwrite(path, img)
        back = cv2.imread(path, cv2.IMREAD_COLOR)
        try:
            os.remove(path)
        except BaseException:  # noqa: BLE001
            pass
        return okw and back is not None and bool(np.array_equal(back, img)), "imwrite=%s 读回一致" % okw

    guarded("imwrite/imread 落盘往返", t_imwrite_read)

    def t_split_merge():
        parts = cv2.split(img)
        back = cv2.merge(parts)
        same = bool(np.array_equal(back, img)) and all(p.ndim == 2 for p in parts)
        return same, "split=%d 通道，merge 往返一致=%s" % (len(parts), same)

    guarded("split/merge 往返", t_split_merge)

    def t_add_saturate():
        a = np.full((4, 4), 250, dtype=np.uint8)
        b = np.full((4, 4), 10, dtype=np.uint8)
        got = cv2.add(a, b)
        return int(got.max()) == 255 and int(got.min()) == 255, "250+10 → %d（cv2 饱和）" % int(got.max())

    guarded("add uint8 饱和到 255", t_add_saturate)

    def t_sub_saturate():
        a = np.full((4, 4), 5, dtype=np.uint8)
        got = cv2.subtract(a, 10)
        return int(got.max()) == 0, "5-10 → %d（cv2 饱和到 0）" % int(got.max())

    guarded("subtract uint8 饱和到 0", t_sub_saturate)

    def t_scalar_subtract():
        a = np.full((4, 4), 30, dtype=np.uint8)
        got = cv2.subtract(255, a)
        return int(got.min()) == 225 and int(got.max()) == 225, "255-30 → %d" % int(got.min())

    guarded("subtract(标量, 图) 方向", t_scalar_subtract)

    def t_mul_float():
        a = np.full((3, 3), 2.0, dtype=np.float64)
        got = cv2.multiply(a, 0.5)
        ok = got.dtype == np.float64 and float(got.mean()) == 1.0
        return ok, "dtype=%s mean=%.2f（float64 标量乘）" % (got.dtype, float(got.mean()))

    guarded("multiply 标量 + float64 保持 dtype", t_mul_float)

    def t_color_mapping_style(): 
        # 复刻 OAS utils.color_mapping 的调用形态：astype(float) + multiply + add + clip
        a = img.astype(float)
        low, high = np.min(a), np.max(a)
        multiply = min(255 / (high - low), 2)
        add = (255 - multiply * (low + high)) / 2
        out = cv2.add(cv2.multiply(a, multiply), add)
        out[out > 255] = 255
        out[out < 0] = 0
        ref = np.clip(a * multiply + add, 0, 255)
        diff = float(np.abs(out - ref).max())
        return diff < 1e-6, "与 numpy 参考最大差=%.3g dtype=%s" % (diff, out.dtype)

    guarded("color_mapping 形态（float 乘法+加法+clip）", t_color_mapping_style)

    # cv2 的 convertScaleAbs 是「|src*alpha+beta| 后饱和到 uint8」，
    # 所以期望值必须按 clip(0,255) 算；直接 astype(uint8) 会把 -300 溢出成 44。
    def t_convert_scale_abs():
        a = np.array([[-300, -1], [0, 200]], dtype=np.int16)
        got = cv2.convertScaleAbs(a)
        exp = np.clip(np.abs(a.astype(np.int32)), 0, 255).astype(np.uint8)
        return bool(np.array_equal(got, exp)), "got=%s 期望=%s" % (got.tolist(), exp.tolist())

    guarded("convertScaleAbs 绝对值+饱和", t_convert_scale_abs)

    def t_bitwise():
        a = np.array([[0b10110000, 0b00001111]], dtype=np.uint8)
        ok1 = bool(np.array_equal(cv2.bitwise_not(a), np.array([[0b01001111, 0b11110000]], dtype=np.uint8)))
        ok2 = bool(np.array_equal(cv2.bitwise_and(a, 0b11110000), np.array([[0b10110000, 0b00000000]], dtype=np.uint8)))
        return ok1 and ok2, "not/and(标量) 均符合按位语义"

    guarded("bitwise_not / bitwise_and(标量)", t_bitwise)

    def t_threshold():
        gray = np.array([[10, 200], [127, 128]], dtype=np.uint8)
        ret, out = cv2.threshold(gray, 127, 255, cv2.THRESH_BINARY)
        exp = np.where(gray > 127, 255, 0).astype(np.uint8)
        return ret == 127.0 and bool(np.array_equal(out, exp)), "ret=%.1f out=%s" % (ret, out.tolist())

    guarded("threshold BINARY", t_threshold)

    def t_threshold_otsu():
        # 用连续斜坡而不是「两峰」图：完美双峰时类间方差在 20..219 之间全都相等，
        # OpenCV 取第一个最大值 → 返回 20（那是图像最小值，不是 bug），
        # 原先的 20 < ret < 220 判据会把正确实现判成失败。
        ramp = np.tile(np.arange(0, 256, dtype=np.uint8), (8, 1))
        ret, out = cv2.threshold(ramp, 0, 255, cv2.THRESH_BINARY | cv2.THRESH_OTSU)
        fg = int((out == 255).sum())
        total = int(ramp.size)
        return 100 <= ret <= 155 and 0 < fg < total, "Otsu ret=%.1f 前景=%d/%d" % (ret, fg, total)

    guarded("threshold OTSU 阈值合理", t_threshold_otsu)

    def t_count_nonzero():
        a = np.array([[0, 1, 2], [0, 0, 7]], dtype=np.uint8)
        return cv2.countNonZero(a) == int(np.count_nonzero(a)) == 3, "countNonZero=%d" % cv2.countNonZero(a)

    guarded("countNonZero 与 numpy 一致", t_count_nonzero)

    def t_kernel_dilate():
        kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 1))
        # OpenCV 的 getStructuringElement 返回的是「1 矩阵」（元素值为 1，不是 255），
        # 原先断言 sum == 5*255 会把正确实现判成失败。
        ker_ok = kernel.shape == (1, 5) and int(kernel.sum()) == 5
        binimg = np.zeros((10, 10), dtype=np.uint8)
        binimg[5, 5] = 255
        dilated = cv2.dilate(binimg, kernel, iterations=1)
        n = int(cv2.countNonZero(dilated))
        return ker_ok and n == 5, "kernel=%s sum=%d 单点膨胀后非零=%d（期望 5）" % (
            kernel.shape,
            int(kernel.sum()),
            n,
        )

    guarded("getStructuringElement + dilate", t_kernel_dilate)

    def t_contours():
        binimg = np.zeros((60, 80), dtype=np.uint8)
        binimg[10:20, 5:25] = 255
        binimg[35:55, 40:75] = 255
        contours, hierarchy = cv2.findContours(binimg, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        boxes = sorted([cv2.boundingRect(c) for c in contours], key=lambda b: b[0])
        expect = [(5, 10, 20, 10), (40, 35, 35, 20)]
        return (
            len(contours) == 2 and boxes == expect and hierarchy is not None,
            "轮廓数=%d boxes=%s 期望=%s" % (len(contours), boxes, expect),
        )

    guarded("findContours + boundingRect 精确命中", t_contours)

    def t_mean():
        m = cv2.mean(img)
        exp = img.reshape(-1, 3).mean(axis=0)
        diff = max(abs(m[i] - exp[i]) for i in range(3))
        return len(m) == 4 and m[3] == 0.0 and diff < 0.01, "mean=%s 误差=%.4f" % (m, diff)

    guarded("mean 逐通道均值", t_mean)

    def t_rotate_flip_transpose():
        r = cv2.rotate(img, cv2.ROTATE_90_CLOCKWISE)
        ok1 = r.shape == (img.shape[1], img.shape[0], 3) and bool(np.array_equal(r, np.rot90(img, k=-1)))
        f = cv2.flip(img, 1)
        ok2 = bool(np.array_equal(f, np.fliplr(img)))
        t = cv2.transpose(img)
        ok3 = bool(np.array_equal(t, np.transpose(img, (1, 0, 2))))
        return ok1 and ok2 and ok3, "rotate90=%s flip=%s transpose=%s" % (ok1, ok2, ok3)

    guarded("rotate/flip/transpose 与 numpy 一致", t_rotate_flip_transpose)

    def t_homography():
        src = np.float32([[0, 0], [0, 99], [149, 99], [149, 0]]).reshape(-1, 1, 2)
        dst = np.float32([[10, 20], [10, 119], [159, 119], [159, 20]]).reshape(-1, 1, 2)
        h, _ = cv2.findHomography(src, dst, cv2.RANSAC, 5.0)
        if h is None:
            return False, "findHomography 返回 None"
        mapped = cv2.perspectiveTransform(src, h)
        diff = float(np.abs(mapped.reshape(-1, 2) - dst.reshape(-1, 2)).max())
        return diff < 1e-2, "H=%s 四角误差=%.4f" % (h.shape, diff)

    guarded("findHomography + perspectiveTransform 往返", t_homography)

    def t_sift():
        board = (np.indices((120, 160)).sum(axis=0) // 8 % 2 * 255).astype(np.uint8)
        kps, des = cv2.SIFT_create().detectAndCompute(board, None)
        # 诊断探针：随机噪声的 SIFT 响应应该远多于 1 个关键点。
        # 若噪声也只有 1 个，说明是包装层丢点（而非「棋盘太规整」）。
        probe_gray = cv2.cvtColor(img, cv2.COLOR_RGB2GRAY)
        probe_kps, _ = cv2.SIFT_create().detectAndCompute(probe_gray, None)
        ok = (
            len(kps) > 0
            and des is not None
            and des.shape[1] == 128
            and des.shape[0] == len(kps)
        )
        return ok, "关键点=%d 描述子=%s 噪声探针关键点=%d" % (
            len(kps),
            None if des is None else des.shape,
            len(probe_kps),
        )

    guarded("SIFT detectAndCompute", t_sift)

    def t_knn():
        board = (np.indices((120, 160)).sum(axis=0) // 8 % 2 * 255).astype(np.uint8)
        _, des = cv2.SIFT_create().detectAndCompute(board, None)
        matcher = cv2.FlannBasedMatcher(dict(algorithm=1, trees=5), dict(checks=50))
        matches = matcher.knnMatch(des, des, k=2)
        # 这张棋盘图只出 1 个描述子，而 kNN 对每个 query 最多返回 min(k, N) 个邻居；
        # 断言按「实际可得的邻居数」收敛，避免把「样本不足」记成 shim 的 bug。
        k_eff = min(2, int(des.shape[0]))
        size_ok = all(len(pair) == k_eff for pair in matches)
        ordered = all(pair[0].distance <= pair[1].distance for pair in matches if len(pair) == 2)
        self_hit = sum(1 for pair in matches if pair and pair[0].distance < 1e-3)
        return (
            len(matches) == int(des.shape[0]) and size_ok and ordered and self_hit > 0,
            "描述子=%d 匹配组=%d 每组=%d 自匹配命中=%d（kNN 顺序正确）"
            % (des.shape[0], len(matches), k_eff, self_hit),
        )

    guarded("FlannBasedMatcher(BF) knnMatch 自匹配", t_knn)

    def t_nms():
        boxes = [[0, 0, 10, 10], [1, 1, 10, 10], [50, 50, 10, 10]]
        scores = [0.9, 0.8, 0.7]
        keep = cv2.dnn.NMSBoxes(boxes, scores, 0.5, 0.4)
        return list(map(int, keep)) == [0, 2], "保留下标=%s 期望=[0, 2]" % list(map(int, keep))

    guarded("dnn.NMSBoxes 抑制重叠框", t_nms)

    def t_not_implemented():
        try:
            cv2.imshow("x", img)
            return False, "imshow 居然没报错"
        except NotImplementedError as exc:
            return True, str(exc)[:60]

    guarded("未实现函数显式抛 NotImplementedError", t_not_implemented)

    def t_dtype_guard():
        try:
            cv2.cvtColor(np.zeros((4, 4, 3), dtype=np.float16), cv2.COLOR_RGB2GRAY)
            return False, "float16 居然没报错"
        except TypeError as exc:
            return True, str(exc)[:60]

    guarded("不支持的 dtype 显式 TypeError", t_dtype_guard)

    # ---- 性能：转换开销 + 1280x720 模板匹配 ----
    try:
        big = rng.randint(0, 256, size=(720, 1280, 3), dtype=np.uint8)
        tpl = rng.randint(0, 256, size=(40, 60, 3), dtype=np.uint8)
        to_ms, from_ms, match_ms = [], [], []
        for _ in range(5):
            t0 = time.monotonic()
            big_gray = cv2.cvtColor(big, cv2.COLOR_RGB2GRAY)
            to_ms.append((time.monotonic() - t0) * 1000.0)
        for _ in range(20):
            t0 = time.monotonic()
            res = cv2.matchTemplate(big, tpl, cv2.TM_CCOEFF_NORMED)
            match_ms.append((time.monotonic() - t0) * 1000.0)
        for _ in range(5):
            t0 = time.monotonic()
            _ = np.array(big_gray)
            from_ms.append((time.monotonic() - t0) * 1000.0)
        match_ms.sort()
        perf["cvtColor_1280x720_ms"] = round(float(np.median(to_ms)), 2)
        perf["matchTemplate_60x40_ms_p50"] = round(match_ms[len(match_ms) // 2], 2)
        perf["matchTemplate_60x40_ms_p95"] = round(match_ms[int(len(match_ms) * 0.95) - 1], 2)
        perf["result_shape"] = list(res.shape)
        check("性能采样（转换/matchTemplate）", True, str(perf))
    except BaseException as exc:  # noqa: BLE001
        check("性能采样", False, "异常: %r" % (exc,))

    return _finish_selftest(cache, checks, info, perf, fatal=None)


def _finish_selftest(cache, checks, info, perf, fatal):
    passed = sum(1 for c in checks if c["ok"])
    total = len(checks)
    payload = {
        "ts": time.strftime("%Y-%m-%d %H:%M:%S"),
        "python": platform.python_version(),
        "passed": passed,
        "total": total,
        "fatal": fatal or "",
        "info": info,
        "perf": perf,
        "checks": checks,
    }
    try:
        with open(os.path.join(cache, "cv_selftest.json"), "w", encoding="utf-8") as fp:
            json.dump(payload, fp, ensure_ascii=False, indent=2, default=str)
        lines = ["cv2 shim 自测 通过 %d/%d" % (passed, total)]
        if fatal:
            lines.append("致命: %s" % fatal)
        lines.append("python=%s numpy=%s pillow=%s opencv=%s"
                     % (payload["python"], info.get("numpy"), info.get("pillow"), info.get("opencv")))
        for c in checks:
            lines.append("[%s] %s" % ("OK" if c["ok"] else "FAIL", c["name"]))
            if c["detail"]:
                lines.append("    %s" % c["detail"])
        if perf:
            lines.append("性能: %s" % json.dumps(perf, ensure_ascii=False))
        if info.get("tracebacks"):
            lines.append("异常栈: %s" % json.dumps(info["tracebacks"], ensure_ascii=False)[:2000])
        with open(os.path.join(cache, "cv_selftest.txt"), "w", encoding="utf-8") as fp:
            fp.write("\n".join(lines) + "\n")
    except BaseException:  # noqa: BLE001
        pass
    return json.dumps(payload, ensure_ascii=False, default=str)
