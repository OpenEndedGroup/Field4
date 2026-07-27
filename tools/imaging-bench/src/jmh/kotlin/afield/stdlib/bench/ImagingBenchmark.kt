package afield.stdlib.bench

import afield.stdlib.Border
import afield.stdlib.Img
import afield.stdlib.Kernel2D
import afield.stdlib.SOBEL_DERIV
import afield.stdlib.SOBEL_SMOOTH
import afield.stdlib.bilateral
import afield.stdlib.boxBlur
import afield.stdlib.convolve2D
import afield.stdlib.convolveSeparable
import afield.stdlib.convolveSeparableReference
import afield.stdlib.gaussianBlur
import afield.stdlib.gaussianKernel1D
import afield.stdlib.laplacian
import afield.stdlib.luma
import afield.stdlib.median
import afield.stdlib.pyrDown
import afield.stdlib.resize
import afield.stdlib.scharr
import afield.stdlib.sobel
import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.global.opencv_imgproc
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Size
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import java.nio.FloatBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The stdlib imaging family against OpenCV 4.9, at a realistic image size.
 *
 * ### What each arm means
 *
 * - **`ours…`** — the shipping implementation.
 * - **`scalarOurs…`** — only exists for the separable-convolution family, where
 *   [convolveSeparableReference] preserves the pre-SIMD implementation. It is the "before"
 *   number, and it is the honest way to report what the vectorization actually bought.
 * - **`opencv…`** — OpenCV on a `CV_32F` Mat of the same pixels, pinned to one thread
 *   (`setNumThreads(1)`) because our code is single-threaded. On this machine OpenCV's own
 *   threading did not help at these sizes anyway — measured, not assumed.
 *
 * ### Which comparisons are exact, and which are only "same work"
 *
 * [verifyComparableArms] runs once per trial and hard-fails if the arms it can compare stop
 * agreeing. It covers Gaussian blur, box blur and the Laplacian, where our definition and
 * OpenCV's are genuinely the same operation with the same parameters.
 *
 * The rest are matched in *work* but not in *definition*, and are labelled here rather than
 * quietly presented as equivalent:
 *
 * - **`luma`** — ours is BT.709, `cvtColor(COLOR_RGB2GRAY)` is BT.601. Same cost, different
 *   weights, so the outputs legitimately differ.
 * - **`scharr` / `sobel`** — ours are scaled to true units-per-pixel (the derivative tap is
 *   `[-1,0,1]/2`, not `[-1,0,1]`), so ours equals OpenCV's divided by a constant. Same arithmetic.
 * - **`pyrDown`** — ours is `gaussianBlur(sigma)` then subsample with a configurable sigma;
 *   OpenCV's is a fixed 5-tap kernel. Comparable work, different filter.
 * - **`median` / `bilateral`** — parameters are matched (`radius 2` = `ksize 5`,
 *   `sigmaSpace 3` = `d 13`), but OpenCV's implementations use different internal algorithms.
 *
 * ### Fairness notes
 *
 * Our API returns a new [Img], so each call allocates its output; OpenCV writes into a `dst` Mat
 * allocated once in [setup]. That is normal usage on both sides, but part of our cost is
 * allocation OpenCV is not paying. Not corrected for — disclosed.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class ImagingBenchmark {

    /** 1920x1080 — an HD frame, 2.07 Mpx. Non-square on purpose: it catches row/column mixups. */
    private val w = 1920
    private val h = 1080

    private val sigma = 2.0
    private var ksize = 0

    private lateinit var gray: Img
    private lateinit var rgb: Img
    private lateinit var k5: Kernel2D

    private lateinit var grayMat: Mat
    private lateinit var rgbMat: Mat
    private lateinit var dst: Mat
    private lateinit var dst2: Mat
    private lateinit var k5Mat: Mat

    @Setup(Level.Trial)
    fun setup() {
        gray = Img.generate(w, h) { x, y -> ((x * 37 + y * 61) % 101) / 101f }
        rgb = Img.generateRGB(w, h) { x, y ->
            Triple(
                ((x * 37 + y * 61) % 101) / 101f,
                ((x * 17 + y * 29) % 97) / 97f,
                ((x * 7 + y * 13) % 89) / 89f
            )
        }
        ksize = 2 * gaussianKernel1D(sigma).radius + 1
        val g = gaussianKernel1D(1.1)
        k5 = g.outer(g)

        opencv_core.setNumThreads(1)
        grayMat = matOf(gray)
        rgbMat = matOf(rgb)
        dst = Mat()
        dst2 = Mat()
        k5Mat = Mat(k5.height, k5.width, opencv_core.CV_32FC1)
        k5Mat.createBuffer<FloatBuffer>().put(k5.flipped().weights).rewind()

        verifyComparableArms()
    }

    @TearDown(Level.Trial)
    fun teardown() {
        grayMat.close(); rgbMat.close(); dst.close(); dst2.close(); k5Mat.close()
    }

    private fun matOf(img: Img): Mat {
        val type = if (img.channels == 1) opencv_core.CV_32FC1 else opencv_core.CV_32FC3
        val m = Mat(img.height, img.width, type)
        m.createBuffer<FloatBuffer>().put(img.data).rewind()
        return m
    }

    private fun worstDiff(img: Img, m: Mat): Float {
        val cv = FloatArray(img.data.size)
        m.createBuffer<FloatBuffer>().get(cv)
        var worst = 0f
        for (i in cv.indices) worst = maxOf(worst, abs(img.data[i] - cv[i]))
        return worst
    }

    /**
     * Fail the whole trial if an arm we claim is comparable stops computing the same image. A fast
     * wrong answer is not a benchmark result.
     */
    private fun verifyComparableArms() {
        val k = gaussianKernel1D(sigma)
        val simd = gray.convolveSeparable(k, k, Border.REFLECT)
        val scalar = gray.convolveSeparableReference(k, k, Border.REFLECT)
        var worst = 0f
        for (i in simd.data.indices) worst = maxOf(worst, abs(simd.data[i] - scalar.data[i]))
        check(worst < 2e-6f) { "SIMD and scalar separable arms disagree by $worst" }
        val simdVsScalar = worst

        Mat().use { cv ->
            opencv_imgproc.GaussianBlur(grayMat, cv, Size(ksize, ksize), sigma, sigma, opencv_core.BORDER_REFLECT101)
            val d = worstDiff(simd, cv)
            check(d < 2e-5f) { "our Gaussian and OpenCV's disagree by $d" }
            println("[verify] gaussian: simd-vs-scalar=$simdVsScalar simd-vs-opencv=$d")
        }
        Mat().use { cv ->
            opencv_imgproc.blur(grayMat, cv, Size(5, 5), org.bytedeco.opencv.opencv_core.Point(-1, -1), opencv_core.BORDER_REFLECT101)
            val d = worstDiff(gray.boxBlur(2, Border.REFLECT), cv)
            check(d < 2e-5f) { "our boxBlur and OpenCV's blur disagree by $d" }
            println("[verify] boxBlur: ours-vs-opencv=$d")
        }
        Mat().use { cv ->
            opencv_imgproc.Laplacian(grayMat, cv, opencv_core.CV_32F, 1, 1.0, 0.0, opencv_core.BORDER_REFLECT101)
            val d = worstDiff(gray.laplacian(false, Border.REFLECT), cv)
            check(d < 2e-5f) { "our Laplacian and OpenCV's disagree by $d" }
            println("[verify] laplacian: ours-vs-opencv=$d")
        }
    }

    // ---- separable convolution: the family that was vectorized ---------------------------------

    @Benchmark fun scalarOursGaussianGray(bh: Blackhole) {
        val k = gaussianKernel1D(sigma)
        bh.consume(gray.convolveSeparableReference(k, k, Border.REFLECT))
    }

    @Benchmark fun oursGaussianGray(bh: Blackhole) =
        bh.consume(gray.gaussianBlur(sigma, border = Border.REFLECT))

    @Benchmark fun opencvGaussianGray(bh: Blackhole) {
        opencv_imgproc.GaussianBlur(grayMat, dst, Size(ksize, ksize), sigma, sigma, opencv_core.BORDER_REFLECT101)
        bh.consume(dst)
    }

    @Benchmark fun scalarOursGaussianRgb(bh: Blackhole) {
        val k = gaussianKernel1D(sigma)
        bh.consume(rgb.convolveSeparableReference(k, k, Border.REFLECT))
    }

    @Benchmark fun oursGaussianRgb(bh: Blackhole) =
        bh.consume(rgb.gaussianBlur(sigma, border = Border.REFLECT))

    @Benchmark fun opencvGaussianRgb(bh: Blackhole) {
        opencv_imgproc.GaussianBlur(rgbMat, dst, Size(ksize, ksize), sigma, sigma, opencv_core.BORDER_REFLECT101)
        bh.consume(dst)
    }

    @Benchmark fun scalarOursSobel(bh: Blackhole) {
        bh.consume(gray.convolveSeparableReference(SOBEL_DERIV, SOBEL_SMOOTH, Border.REFLECT))
        bh.consume(gray.convolveSeparableReference(SOBEL_SMOOTH, SOBEL_DERIV, Border.REFLECT))
    }

    @Benchmark fun oursSobel(bh: Blackhole) = bh.consume(gray.sobel(Border.REFLECT))

    @Benchmark fun opencvSobel(bh: Blackhole) {
        opencv_imgproc.Sobel(grayMat, dst, opencv_core.CV_32F, 1, 0, 3, 1.0, 0.0, opencv_core.BORDER_REFLECT101)
        opencv_imgproc.Sobel(grayMat, dst2, opencv_core.CV_32F, 0, 1, 3, 1.0, 0.0, opencv_core.BORDER_REFLECT101)
        bh.consume(dst); bh.consume(dst2)
    }

    @Benchmark fun oursScharr(bh: Blackhole) = bh.consume(gray.scharr(Border.REFLECT))

    @Benchmark fun opencvScharr(bh: Blackhole) {
        opencv_imgproc.Scharr(grayMat, dst, opencv_core.CV_32F, 1, 0, 1.0 / 32.0, 0.0, opencv_core.BORDER_REFLECT101)
        opencv_imgproc.Scharr(grayMat, dst2, opencv_core.CV_32F, 0, 1, 1.0 / 32.0, 0.0, opencv_core.BORDER_REFLECT101)
        bh.consume(dst); bh.consume(dst2)
    }

    @Benchmark fun oursPyrDown(bh: Blackhole) = bh.consume(gray.pyrDown())

    @Benchmark fun opencvPyrDown(bh: Blackhole) {
        opencv_imgproc.pyrDown(grayMat, dst)
        bh.consume(dst)
    }

    // ---- ops that were NOT touched by the vectorization -----------------------------------------

    @Benchmark fun oursBoxBlur(bh: Blackhole) = bh.consume(gray.boxBlur(2, Border.REFLECT))

    @Benchmark fun opencvBoxBlur(bh: Blackhole) {
        opencv_imgproc.blur(grayMat, dst, Size(5, 5), org.bytedeco.opencv.opencv_core.Point(-1, -1), opencv_core.BORDER_REFLECT101)
        bh.consume(dst)
    }

    @Benchmark fun oursLaplacian(bh: Blackhole) = bh.consume(gray.laplacian(false, Border.REFLECT))

    @Benchmark fun opencvLaplacian(bh: Blackhole) {
        opencv_imgproc.Laplacian(grayMat, dst, opencv_core.CV_32F, 1, 1.0, 0.0, opencv_core.BORDER_REFLECT101)
        bh.consume(dst)
    }

    @Benchmark fun oursConvolve2D(bh: Blackhole) = bh.consume(gray.convolve2D(k5, Border.REFLECT))

    @Benchmark fun opencvFilter2D(bh: Blackhole) {
        opencv_imgproc.filter2D(grayMat, dst, opencv_core.CV_32F, k5Mat)
        bh.consume(dst)
    }

    @Benchmark fun oursMedian(bh: Blackhole) = bh.consume(gray.median(2, Border.REFLECT))

    @Benchmark fun opencvMedian(bh: Blackhole) {
        opencv_imgproc.medianBlur(grayMat, dst, 5)
        bh.consume(dst)
    }

    @Benchmark fun oursBilateral(bh: Blackhole) =
        bh.consume(gray.bilateral(sigmaSpace = 3.0, sigmaRange = 0.12, border = Border.REFLECT))

    @Benchmark fun opencvBilateral(bh: Blackhole) {
        opencv_imgproc.bilateralFilter(grayMat, dst, 13, 0.12, 3.0)
        bh.consume(dst)
    }

    @Benchmark fun oursResizeDown(bh: Blackhole) = bh.consume(gray.resize(w / 2, h / 2))

    @Benchmark fun opencvResizeDown(bh: Blackhole) {
        opencv_imgproc.resize(grayMat, dst, Size(w / 2, h / 2), 0.0, 0.0, opencv_imgproc.INTER_AREA)
        bh.consume(dst)
    }

    @Benchmark fun oursLuma(bh: Blackhole) = bh.consume(rgb.luma())

    @Benchmark fun opencvLuma(bh: Blackhole) {
        opencv_imgproc.cvtColor(rgbMat, dst, opencv_imgproc.COLOR_RGB2GRAY)
        bh.consume(dst)
    }
}
