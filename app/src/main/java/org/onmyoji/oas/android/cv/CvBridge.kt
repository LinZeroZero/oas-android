package org.onmyoji.oas.android.cv

import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfRect2d
import org.opencv.core.Point
import org.opencv.core.Rect2d
import org.opencv.core.Scalar
import org.opencv.dnn.Dnn
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.SIFT
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * Python 侧 `cv2` shim 的 Java 后端。
 *
 * 为什么要这层：`opencv-python` 只有 cp38 / cp310 轮子，Chaquopy 3.13 装不上；
 * 官方 AAR 的 Java 绑定自带 arm64 原生库。所以数据流是：
 *
 *     Python(numpy) ──数组──► CvBridge(Kotlin) ──► Mat ──► OpenCV 原生
 *     Python(numpy) ◄──数组── CvBridge ◄── Mat ◄──────────────┘
 *
 * 约定：
 *  - 支持 uint8(1/3/4 通道) / uint16 / int32 / float32 / float64：
 *    前两个是图像，后三个是 OAS 的 `astype(float)` 算术路径（`utils.color_mapping` 等）；
 *  - **不做隐式 dtype 转换**，不支持的类型显式报错（「算错但没报错」比报错危险）；
 *  - 通道顺序不交换：np 第 k 通道 == Mat 第 k 通道，语义只由传入的 code/method 常量决定。
 */
object CvBridge {

    @Volatile
    private var loaded = false

    @Volatile
    var loadError: String = ""
        private set

    /** 加载 OpenCV 原生库；幂等。 */
    @JvmStatic
    fun load(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true
            loaded = try {
                if (!OpenCVLoader.initLocal()) {
                    // 个别 ROM 上 initLocal 的 ABI 探测会失败，退化成直接加载
                    System.loadLibrary("opencv_java4")
                }
                true
            } catch (t: Throwable) {
                loadError = "OpenCV 加载失败: ${t.message ?: t}"
                false
            }
            return loaded
        }
    }

    @JvmStatic
    fun version(): String = if (load()) Core.getVersionString() else "unloaded($loadError)"

    // ------------------------------------------------------------------ //
    // numpy <-> Mat
    // ------------------------------------------------------------------ //

    private fun typeOf(ch: Int, depth: Int): Int {
        // 2 通道是必须支持的：`findContours` 出来的是 CV_32SC2 点集，
        // `boundingRect` / `arcLength` / `perspectiveTransform` 又要能把它原样搬回 Mat。
        if (ch != 1 && ch != 2 && ch != 3 && ch != 4) {
            throw IllegalArgumentException("只支持 1/2/3/4 通道，收到 ch=$ch")
        }
        when (depth) {
            CvType.CV_8U, CvType.CV_16U, CvType.CV_16S,
            CvType.CV_32S, CvType.CV_32F, CvType.CV_64F -> Unit
            else -> throw IllegalArgumentException("不支持的 depth=$depth")
        }
        // OpenCV 的 type = depth + (channels-1)*8，官方 makeType 就是干这个的
        return CvType.makeType(depth, ch)
    }

    private fun checkSize(h: Int, w: Int, ch: Int, size: Int) {
        val need = h * w * ch
        if (size < need) throw IllegalArgumentException("数据不足: 需要 $need，实际 $size")
    }

    @JvmStatic
    fun matFromBytes(h: Int, w: Int, ch: Int, data: ByteArray): Mat {
        checkSize(h, w, ch, data.size)
        return Mat(h, w, typeOf(ch, CvType.CV_8U)).also { it.put(0, 0, data) }
    }

    // 非 uint8 的 dtype 统一走「小端字节 → 原生数组」：Chaquopy 只保证 bytes↔byte[]，
    // 传 short[]/float[] 这种数组参数不可靠，所以这里自己重解释，语义明确。
    @JvmStatic
    fun matFromUShortBytes(h: Int, w: Int, ch: Int, data: ByteArray): Mat {
        val n = h * w * ch
        if (data.size < n * 2) throw IllegalArgumentException("数据不足: 需要 ${n * 2}，实际 ${data.size}")
        return Mat(h, w, typeOf(ch, CvType.CV_16U)).also { it.put(0, 0, bytesToShorts(data, n)) }
    }

    /** int16（CV_16S）：OAS 里 Sobel / convertScaleAbs 的中间结果是 int16，必须能进 Mat。 */
    @JvmStatic
    fun matFromInt16Bytes(h: Int, w: Int, ch: Int, data: ByteArray): Mat {
        val n = h * w * ch
        if (data.size < n * 2) throw IllegalArgumentException("数据不足: 需要 ${n * 2}，实际 ${data.size}")
        return Mat(h, w, typeOf(ch, CvType.CV_16S)).also { it.put(0, 0, bytesToShorts(data, n)) }
    }

    @JvmStatic
    fun matFromInt32Bytes(h: Int, w: Int, ch: Int, data: ByteArray): Mat {
        val n = h * w * ch
        if (data.size < n * 4) throw IllegalArgumentException("数据不足: 需要 ${n * 4}，实际 ${data.size}")
        return Mat(h, w, typeOf(ch, CvType.CV_32S)).also { it.put(0, 0, bytesToInts(data, n)) }
    }

    @JvmStatic
    fun matFromFloat32Bytes(h: Int, w: Int, ch: Int, data: ByteArray): Mat {
        val n = h * w * ch
        if (data.size < n * 4) throw IllegalArgumentException("数据不足: 需要 ${n * 4}，实际 ${data.size}")
        return Mat(h, w, typeOf(ch, CvType.CV_32F)).also { it.put(0, 0, bytesToFloats(data, n)) }
    }

    @JvmStatic
    fun matFromFloat64Bytes(h: Int, w: Int, ch: Int, data: ByteArray): Mat {
        val n = h * w * ch
        if (data.size < n * 8) throw IllegalArgumentException("数据不足: 需要 ${n * 8}，实际 ${data.size}")
        return Mat(h, w, typeOf(ch, CvType.CV_64F)).also { putDoubles(it, h, w * ch, bytesToDoubles(data, n)) }
    }

    /**
     * Mat 没有 `put(int,int,double[])` 重载（只有 `put(int,int,double...)` 变长版本），
     * 所以按行用 spread 传进去；每行元素数 = w*ch，远小于整图。
     */
    private fun putDoubles(mat: Mat, h: Int, perRow: Int, values: DoubleArray) {
        for (r in 0 until h) {
            val from = r * perRow
            val to = minOf(from + perRow, values.size)
            mat.put(r, 0, *values.copyOfRange(from, to))
        }
    }

    @JvmStatic
    fun matToBytes(mat: Mat): ByteArray =
        ByteArray((mat.total() * mat.channels()).toInt()).also { mat.get(0, 0, it) }

    @JvmStatic
    fun matToUShortBytes(mat: Mat): ByteArray = shortsToBytes(
        ShortArray((mat.total() * mat.channels()).toInt()).also { mat.get(0, 0, it) },
    )

    /** int16 与 uint16 的字节布局完全一致，只是 depth 不同。 */
    @JvmStatic
    fun matToInt16Bytes(mat: Mat): ByteArray = shortsToBytes(
        ShortArray((mat.total() * mat.channels()).toInt()).also { mat.get(0, 0, it) },
    )

    @JvmStatic
    fun matToInt32Bytes(mat: Mat): ByteArray = intsToBytes(
        IntArray((mat.total() * mat.channels()).toInt()).also { mat.get(0, 0, it) },
    )

    @JvmStatic
    fun matToFloat32Bytes(mat: Mat): ByteArray = floatsToBytes(
        FloatArray((mat.total() * mat.channels()).toInt()).also { mat.get(0, 0, it) },
    )

    @JvmStatic
    fun matToFloat64Bytes(mat: Mat): ByteArray = doublesToBytes(
        DoubleArray((mat.total() * mat.channels()).toInt()).also { mat.get(0, 0, it) },
    )

    private fun bytesToShorts(data: ByteArray, n: Int): ShortArray {
        val out = ShortArray(n)
        java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }

    private fun bytesToInts(data: ByteArray, n: Int): IntArray {
        val out = IntArray(n)
        java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(out)
        return out
    }

    private fun bytesToFloats(data: ByteArray, n: Int): FloatArray {
        val out = FloatArray(n)
        java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    private fun bytesToDoubles(data: ByteArray, n: Int): DoubleArray {
        val out = DoubleArray(n)
        java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).asDoubleBuffer().get(out)
        return out
    }

    private fun shortsToBytes(a: ShortArray): ByteArray =
        java.nio.ByteBuffer.allocate(a.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .also { it.asShortBuffer().put(a) }.array()

    private fun intsToBytes(a: IntArray): ByteArray =
        java.nio.ByteBuffer.allocate(a.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .also { it.asIntBuffer().put(a) }.array()

    private fun floatsToBytes(a: FloatArray): ByteArray =
        java.nio.ByteBuffer.allocate(a.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .also { it.asFloatBuffer().put(a) }.array()

    private fun doublesToBytes(a: DoubleArray): ByteArray =
        java.nio.ByteBuffer.allocate(a.size * 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .also { it.asDoubleBuffer().put(a) }.array()

    /** [h, w, ch, depth]；depth 用 CvType 数值（CV_8U=0 / CV_16U=2 / CV_32S=4 / CV_32F=5 / CV_64F=6）。 */
    @JvmStatic
    fun matShape(mat: Mat): IntArray = intArrayOf(mat.rows(), mat.cols(), mat.channels(), mat.depth())

    @JvmStatic
    fun isEmpty(mat: Mat): Boolean = mat.empty()

    // ------------------------------------------------------------------ //
    // 图像变换
    // ------------------------------------------------------------------ //

    @JvmStatic
    fun cvtColor(src: Mat, code: Int): Mat = Mat().also { Imgproc.cvtColor(src, it, code) }

    @JvmStatic
    fun resize(src: Mat, w: Int, h: Int, interpolation: Int): Mat = Mat().also {
        Imgproc.resize(src, it, org.opencv.core.Size(w.toDouble(), h.toDouble()), 0.0, 0.0, interpolation)
    }

    @JvmStatic
    fun copyMakeBorder(
        src: Mat,
        top: Int,
        bottom: Int,
        left: Int,
        right: Int,
        type: Int,
        v0: Double,
        v1: Double,
        v2: Double,
        v3: Double,
    ): Mat = Mat().also {
        Core.copyMakeBorder(src, it, top, bottom, left, right, type, Scalar(v0, v1, v2, v3))
    }

    @JvmStatic
    fun rotate(src: Mat, code: Int): Mat = Mat().also { Core.rotate(src, it, code) }

    @JvmStatic
    fun flip(src: Mat, code: Int): Mat = Mat().also { Core.flip(src, it, code) }

    @JvmStatic
    fun transpose(src: Mat): Mat = Mat().also { Core.transpose(src, it) }

    // ------------------------------------------------------------------ //
    // 模板匹配 / 极值
    // ------------------------------------------------------------------ //

    @JvmStatic
    fun matchTemplate(image: Mat, templ: Mat, method: Int): Mat =
        Mat().also { Imgproc.matchTemplate(image, templ, it, method) }

    @JvmStatic
    fun matchTemplateMask(image: Mat, templ: Mat, method: Int, mask: Mat): Mat =
        Mat().also { Imgproc.matchTemplate(image, templ, it, method, mask) }

    /** [minVal, maxVal, minX, minY, maxX, maxY] */
    @JvmStatic
    fun minMaxLoc(src: Mat): DoubleArray {
        val r = Core.minMaxLoc(src)
        return doubleArrayOf(r.minVal, r.maxVal, r.minLoc.x, r.minLoc.y, r.maxLoc.x, r.maxLoc.y)
    }

    // ------------------------------------------------------------------ //
    // 编解码
    // ------------------------------------------------------------------ //

    @JvmStatic
    fun imdecode(data: ByteArray, flags: Int): Mat {
        val buf = MatOfByte()
        buf.fromArray(*data)
        return Imgcodecs.imdecode(buf, flags)
    }

    /** 成功返回编码字节；失败返回空数组。params 语义同 cv2.imencode（成对的 key/value）。 */
    @JvmStatic
    fun imencode(ext: String, mat: Mat, params: IntArray): ByteArray {
        val buf = MatOfByte()
        val ok = if (params.isEmpty()) {
            Imgcodecs.imencode(ext, mat, buf)
        } else {
            Imgcodecs.imencode(ext, mat, buf, MatOfInt(*params))
        }
        return if (ok) buf.toArray() else ByteArray(0)
    }

    @JvmStatic
    fun imencode(ext: String, mat: Mat): ByteArray = imencode(ext, mat, IntArray(0))

    @JvmStatic
    fun imread(path: String, flags: Int): Mat = Imgcodecs.imread(path, flags)

    @JvmStatic
    fun imwrite(path: String, mat: Mat): Boolean = Imgcodecs.imwrite(path, mat)

    // ------------------------------------------------------------------ //
    // 通道 / 算术
    // ------------------------------------------------------------------ //

    @JvmStatic
    fun split(src: Mat): Array<Mat> {
        val list = ArrayList<Mat>()
        Core.split(src, list)
        return list.toTypedArray()
    }

    @JvmStatic
    fun merge(parts: Array<Mat>): Mat = Mat().also { Core.merge(parts.toList(), it) }

    @JvmStatic
    fun add(a: Mat, b: Mat): Mat = Mat().also { Core.add(a, b, it) }

    @JvmStatic
    fun addScalar(a: Mat, s0: Double, s1: Double, s2: Double, s3: Double): Mat =
        Mat().also { Core.add(a, Scalar(s0, s1, s2, s3), it) }

    @JvmStatic
    fun subtract(a: Mat, b: Mat): Mat = Mat().also { Core.subtract(a, b, it) }

    @JvmStatic
    fun subtractScalar(a: Mat, s0: Double, s1: Double, s2: Double, s3: Double): Mat =
        Mat().also { Core.subtract(a, Scalar(s0, s1, s2, s3), it) }

    /** cv2.subtract(标量, 图)：OAS 的 `cv2.subtract(255, image)` / `cv2.subtract((*color,0), image)`。 */
    @JvmStatic
    fun scalarsubtract(s0: Double, s1: Double, s2: Double, s3: Double, a: Mat): Mat = Mat().also {
        val constant = Mat(a.size(), a.type(), Scalar(s0, s1, s2, s3))
        Core.subtract(constant, a, it)
        constant.release()
    }

    @JvmStatic
    fun multiply(a: Mat, b: Mat): Mat = Mat().also { Core.multiply(a, b, it) }

    @JvmStatic
    fun multiplyScalar(a: Mat, s: Double): Mat = Mat().also { Core.multiply(a, Scalar.all(s), it) }

    @JvmStatic
    fun max(a: Mat, b: Mat): Mat = Mat().also { Core.max(a, b, it) }

    @JvmStatic
    fun min(a: Mat, b: Mat): Mat = Mat().also { Core.min(a, b, it) }

    /** 逐通道均值（长度 = 通道数，顺序同存储顺序）。 */
    @JvmStatic
    fun mean(src: Mat): DoubleArray = Core.mean(src).`val`

    @JvmStatic
    fun convertScaleAbs(src: Mat, alpha: Double, beta: Double): Mat =
        Mat().also { Core.convertScaleAbs(src, it, alpha, beta) }

    @JvmStatic
    fun bitwiseNot(src: Mat): Mat = Mat().also { Core.bitwise_not(src, it) }

    @JvmStatic
    fun bitwiseAnd(a: Mat, b: Mat): Mat = Mat().also { Core.bitwise_and(a, b, it) }

    @JvmStatic
    fun bitwiseOr(a: Mat, b: Mat): Mat = Mat().also { Core.bitwise_or(a, b, it) }

    @JvmStatic
    fun bitwiseXor(a: Mat, b: Mat): Mat = Mat().also { Core.bitwise_xor(a, b, it) }

    // ------------------------------------------------------------------ //
    // 二值化 / 形态学 / 轮廓
    // ------------------------------------------------------------------ //

    private val lastThreshold = ThreadLocal<Double>()

    /** 返回 dst；Otsu 阈值用 [lastThresholdValue] 取（模仿 cv2 的 (ret, dst) 双返回值）。 */
    @JvmStatic
    fun threshold(src: Mat, thresh: Double, maxval: Double, type: Int): Mat =
        Mat().also { lastThreshold.set(Imgproc.threshold(src, it, thresh, maxval, type)) }

    @JvmStatic
    fun lastThresholdValue(): Double = lastThreshold.get() ?: -1.0

    @JvmStatic
    fun countNonZero(src: Mat): Int = Core.countNonZero(src)

    @JvmStatic
    fun getStructuringElement(shape: Int, w: Int, h: Int): Mat =
        Imgproc.getStructuringElement(shape, org.opencv.core.Size(w.toDouble(), h.toDouble()))

    @JvmStatic
    fun dilate(src: Mat, kernel: Mat, iterations: Int): Mat = Mat().also {
        Imgproc.dilate(src, it, kernel, Point(-1.0, -1.0), iterations)
    }

    @JvmStatic
    fun erode(src: Mat, kernel: Mat, iterations: Int): Mat = Mat().also {
        Imgproc.erode(src, it, kernel, Point(-1.0, -1.0), iterations)
    }

    private val lastHierarchy = ThreadLocal<Mat>()

    /** 返回轮廓列表；hierarchy 用 [lastContourHierarchy] 取。 */
    @JvmStatic
    fun findContours(bin: Mat, mode: Int, method: Int): Array<Mat> {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(bin, contours, hierarchy, mode, method)
        lastHierarchy.set(hierarchy)
        return contours.map { it as Mat }.toTypedArray()
    }

    @JvmStatic
    fun lastContourHierarchy(): Mat = lastHierarchy.get() ?: Mat()

    /** [x, y, w, h] */
    @JvmStatic
    fun boundingRect(contour: Mat): IntArray {
        val r = Imgproc.boundingRect(MatOfPoint(contour))
        return intArrayOf(r.x, r.y, r.width, r.height)
    }

    // ------------------------------------------------------------------ //
    // 特征（SIFT + BF kNN）与单应
    // ------------------------------------------------------------------ //

    private val lastKeypoints = ThreadLocal<DoubleArray>()

    /** 返回描述子（CV_32F, N×128）；关键点用 [lastKeypointData] 取。 */
    @JvmStatic
    fun siftDetectAndCompute(gray: Mat): Mat {
        load()
        val sift = SIFT.create()
        val kp = MatOfKeyPoint()
        val desc = Mat()
        sift.detectAndCompute(gray, Mat(), kp, desc)
        val points = kp.toArray()
        val flat = DoubleArray(points.size * 7)
        points.forEachIndexed { i, p ->
            flat[i * 7] = p.pt.x
            flat[i * 7 + 1] = p.pt.y
            flat[i * 7 + 2] = p.size.toDouble()
            flat[i * 7 + 3] = p.angle.toDouble()
            flat[i * 7 + 4] = p.response.toDouble()
            flat[i * 7 + 5] = p.octave.toDouble()
            // KeyPoint.classId 在 Java 绑定里没有稳定字段名，OAS 也不读它，恒填 0
            flat[i * 7 + 6] = 0.0
        }
        lastKeypoints.set(flat)
        return desc
    }

    /** 每个关键点 7 个 double：x, y, size, angle, response, octave, classId */
    @JvmStatic
    fun lastKeypointData(): DoubleArray = lastKeypoints.get() ?: DoubleArray(0)

    /**
     * k-NN 匹配，返回扁平三元组 [queryIdx, trainIdx, distance, ...]。
     *
     * 用 BFMatcher(NORM_L2) 而非 FLANN：对 SIFT 描述子二者给出同样的 k-NN，
     * 只有速度差异；OAS 的 `cv2.FlannBasedMatcher(...)` 也映射到这里（见 cv2.py）。
     */
    @JvmStatic
    fun knnMatch(queryDesc: Mat, trainDesc: Mat, k: Int): DoubleArray {
        if (queryDesc.empty() || trainDesc.empty()) return DoubleArray(0)
        val matcher = BFMatcher(Core.NORM_L2, false)
        val matches = ArrayList<MatOfDMatch>()
        matcher.knnMatch(queryDesc, trainDesc, matches, k)
        val out = ArrayList<Double>()
        for (m in matches) {
            for (d: DMatch in m.toArray()) {
                out.add(d.queryIdx.toDouble())
                out.add(d.trainIdx.toDouble())
                out.add(d.distance.toDouble())
            }
        }
        return out.toDoubleArray()
    }

    /** 把 N×1 / N×2 的 CV_32FC2（或 CV_64FC2）Mat 转成 MatOfPoint2f。
     *
     * Calib3d.findHomography 的 Java 绑定只接受 MatOfPoint2f，而
     * `MatOfPoint2f(mat)` 在本机 OpenCV 4.13 上会抛
     * `UnsupportedOperationException: Mat data type is not compatible: 13`
     * （13 = CV_32FC2）。所以手工取点重建，绕开那条构造路径。
     */
    private fun toPoints2f(mat: Mat): MatOfPoint2f {
        val n = (mat.total() * mat.channels() / 2).toInt()
        val pts = Array(n) { Point() }
        if (mat.type() == CvType.CV_64FC2) {
            val d = DoubleArray(n * 2)
            mat.get(0, 0, d)
            for (i in 0 until n) pts[i] = Point(d[i * 2], d[i * 2 + 1])
        } else {
            val f = FloatArray(n * 2)
            mat.get(0, 0, f)
            for (i in 0 until n) pts[i] = Point(f[i * 2].toDouble(), f[i * 2 + 1].toDouble())
        }
        return MatOfPoint2f(*pts)
    }

    /** 3×3 单应矩阵（CV_64F）；失败返回空 Mat。 */
    @JvmStatic
    fun findHomography(src: Mat, dst: Mat, method: Int, ransacReproj: Double): Mat =
        Calib3d.findHomography(
            toPoints2f(src),
            toPoints2f(dst),
            method,
            ransacReproj,
            Mat(),
            2000,
            0.995,
        ) ?: Mat()

    @JvmStatic
    fun perspectiveTransform(pts: Mat, h: Mat): Mat =
        Mat().also { Core.perspectiveTransform(pts, it, h) }

    /** N×1 的 CV_32FC2 点集。 */
    @JvmStatic
    fun matFromPoints2f(flat: DoubleArray): Mat {
        val n = flat.size / 2
        val mat = Mat(n, 1, CvType.CV_32FC2)
        putDoubles(mat, n, 2, flat)
        return mat
    }

    /** 把任意 2 通道点集 Mat（CV_32FC2 / CV_64FC2）读成扁平 double[]。
     *
     * 不能无脑用 `get(0, 0, DoubleArray)`：那要求 Mat 是 CV_64F，
     * 对 CV_32FC2（type=13）会抛 `Mat data type is not compatible: 13`。
     * `perspectiveTransform` 的输出正是 CV_32FC2，所以这里按类型分流。
     */
    @JvmStatic
    fun matToPoints(mat: Mat): DoubleArray {
        val n = (mat.total() * mat.channels()).toInt()
        if (mat.depth() == CvType.CV_64F) {
            return DoubleArray(n).also { mat.get(0, 0, it) }
        }
        val f = FloatArray(n)
        mat.get(0, 0, f)
        return DoubleArray(n) { f[it].toDouble() }
    }

    /** NMS，返回保留的下标；boxes 按 [x,y,w,h] 扁平排列。 */
    @JvmStatic
    fun nmsBoxes(boxes: DoubleArray, scores: DoubleArray, scoreThreshold: Float, nmsThreshold: Float): IntArray {
        val n = boxes.size / 4
        if (n == 0 || scores.size < n) return IntArray(0)
        val rects = ArrayList<Rect2d>(n)
        val scoreList = ArrayList<Float>(n)
        for (i in 0 until n) {
            rects.add(Rect2d(boxes[i * 4], boxes[i * 4 + 1], boxes[i * 4 + 2], boxes[i * 4 + 3]))
            scoreList.add(scores[i].toFloat())
        }
        // AAR 里 NMSBoxes 只有 MatOfRect2d/MatOfFloat 重载（没有 List 版本）
        val keep = MatOfInt()
        Dnn.NMSBoxes(
            MatOfRect2d(*rects.toTypedArray()),
            MatOfFloat(*scoreList.toFloatArray()),
            scoreThreshold,
            nmsThreshold,
            keep,
        )
        return keep.toArray()
    }
}