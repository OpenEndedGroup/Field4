@file:afield.CompletionCategory("Imaging")

package afield.stdlib

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Convolution, smoothing, gradients and multiscale pyramids for [Img] — the filter half of the
 * stdlib imaging family (see `imaging.kt` for the value type, `imaging_features.kt` for edges,
 * corners and blobs).
 *
 * Everything here is per-channel: a 3-channel image blurs each of R, G, B independently. Filters
 * that only make sense on a scalar field ([sobel], [laplacian], …) take the [Img.luma] of a colour
 * input automatically rather than erroring, because that is what you meant.
 *
 * Two performance-relevant choices worth knowing about:
 *
 * - **Separable when possible.** Gaussian and box blurs run as two 1-D passes (`O(k)` per pixel,
 *   not `O(k²)`). [convolveSeparable] is public so your own separable kernels get the same
 *   treatment, and [convolve2D] exists both as a general fallback and as the naive reference the
 *   unit tests check the separable path against.
 * - **Box blur is a running sum.** [boxBlur] is `O(1)` per pixel regardless of radius, so a
 *   radius-40 mean is as cheap as radius-1. Three box passes approximate a Gaussian closely
 *   (see [boxBlurApproxGaussian]) if you need a very large sigma cheaply.
 */

// ==============================================================================================
// Kernels
// ==============================================================================================

/**
 * A 1-D convolution kernel: [taps] centred on the middle element (so the length is normally odd).
 * @property taps the weights, index `radius` is the centre.
 */
class Kernel1D(val taps: FloatArray) {
    init { require(taps.isNotEmpty()) { "Kernel1D needs at least one tap" } }

    /** Half-width; index `radius` in [taps] is the centre tap. */
    val radius: Int get() = taps.size / 2

    /** Sum of the taps — 1 for a normalised smoothing kernel, 0 for a derivative kernel. */
    fun sum(): Float { var s = 0f; for (t in taps) s += t; return s }

    /** A copy scaled so the taps sum to 1. Throws for a zero-sum (derivative) kernel. */
    fun normalized(): Kernel1D {
        val s = sum()
        require(abs(s) > 1e-12f) { "cannot normalise a zero-sum kernel (this is a derivative kernel)" }
        return Kernel1D(FloatArray(taps.size) { taps[it] / s })
    }

    /** The outer product `this ⊗ other` as a full 2-D kernel — the separable→dense expansion. */
    fun outer(other: Kernel1D): Kernel2D {
        val w = taps.size
        val h = other.taps.size
        val m = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) m[y * w + x] = other.taps[y] * taps[x]
        return Kernel2D(w, h, m)
    }

    override fun toString(): String = "Kernel1D(${taps.joinToString(", ") { "%.4f".format(it) }})"
}

/**
 * A dense 2-D convolution kernel, [width] x [height], centred (odd dimensions expected).
 * @property weights row-major, `weights[y * width + x]`.
 */
class Kernel2D(val width: Int, val height: Int, val weights: FloatArray) {
    init {
        require(width >= 1 && height >= 1) { "Kernel2D must be at least 1x1" }
        require(weights.size == width * height) { "Kernel2D needs ${width * height} weights, got ${weights.size}" }
    }

    val radiusX: Int get() = width / 2
    val radiusY: Int get() = height / 2

    fun sum(): Float { var s = 0f; for (w in weights) s += w; return s }

    /** 180-degree rotation — the difference between convolution and correlation. */
    fun flipped(): Kernel2D = Kernel2D(width, height, FloatArray(weights.size) { weights[weights.size - 1 - it] })

    companion object {
        /** Build from rows given as a vararg of `FloatArray`s. */
        fun of(vararg rows: FloatArray): Kernel2D {
            require(rows.isNotEmpty()) { "Kernel2D.of needs at least one row" }
            val w = rows[0].size
            require(rows.all { it.size == w }) { "all rows must be the same length" }
            val m = FloatArray(w * rows.size)
            rows.forEachIndexed { y, r -> r.copyInto(m, y * w) }
            return Kernel2D(w, rows.size, m)
        }
    }
}

/**
 * A normalised 1-D Gaussian kernel with standard deviation [sigma], truncated at [truncate] sigmas
 * (the SciPy default of 4 gives < 1e-4 of the mass outside).
 */
fun gaussianKernel1D(sigma: Double, truncate: Double = 4.0): Kernel1D {
    require(sigma > 0.0) { "sigma must be > 0, got $sigma" }
    require(truncate > 0.0) { "truncate must be > 0, got $truncate" }
    val r = max(1, ceil(sigma * truncate).toInt())
    val taps = FloatArray(2 * r + 1)
    val inv2s2 = 1.0 / (2.0 * sigma * sigma)
    var s = 0.0
    for (i in -r..r) { val v = exp(-i * i * inv2s2); taps[i + r] = v.toFloat(); s += v }
    for (i in taps.indices) taps[i] = (taps[i] / s).toFloat()
    return Kernel1D(taps)
}

/** A normalised box (mean) kernel of half-width [radius]. */
fun boxKernel1D(radius: Int): Kernel1D {
    require(radius >= 0) { "radius must be >= 0, got $radius" }
    val n = 2 * radius + 1
    return Kernel1D(FloatArray(n) { 1f / n })
}

/**
 * The Sobel 3x3 derivative factor, **scaled to true units-per-pixel**: `[-1, 0, 1]/2`, i.e. the
 * central difference `(f(x+1) - f(x-1)) / 2`. The classic unscaled `[-1, 0, 1]` reports twice the
 * actual slope; dividing by 2 here means `sobel().dx` on a ramp of slope `s` really is `s`, and
 * a unit step edge really does integrate to a total gradient of 1.
 */
val SOBEL_DERIV: Kernel1D = Kernel1D(floatArrayOf(-0.5f, 0f, 0.5f))

/** The Sobel smoothing factor, normalised. */
val SOBEL_SMOOTH: Kernel1D = Kernel1D(floatArrayOf(0.25f, 0.5f, 0.25f))

/** The Scharr smoothing factor (`[3, 10, 3]/16`) — better rotational symmetry than Sobel's. */
val SCHARR_SMOOTH: Kernel1D = Kernel1D(floatArrayOf(3f / 16f, 10f / 16f, 3f / 16f))

/** The 4-neighbour discrete Laplacian. */
val LAPLACIAN_4: Kernel2D = Kernel2D.of(
    floatArrayOf(0f, 1f, 0f),
    floatArrayOf(1f, -4f, 1f),
    floatArrayOf(0f, 1f, 0f)
)

/** The 8-neighbour discrete Laplacian. */
val LAPLACIAN_8: Kernel2D = Kernel2D.of(
    floatArrayOf(1f, 1f, 1f),
    floatArrayOf(1f, -8f, 1f),
    floatArrayOf(1f, 1f, 1f)
)

// ==============================================================================================
// Convolution
// ==============================================================================================

/**
 * Separable convolution: [kx] along x then [ky] along y (or [kx] both ways if [ky] is null).
 * `O(kx + ky)` per pixel per channel.
 */
fun Img.convolveSeparable(kx: Kernel1D, ky: Kernel1D? = kx, border: Border = Border.CLAMP): Img {
    val kyy = ky ?: kx
    val tmp = FloatArray(data.size)
    val rx = kx.radius
    val tx = kx.taps
    for (y in 0 until height) for (x in 0 until width) for (c in 0 until channels) {
        var s = 0f
        for (t in tx.indices) s += tx[t] * get(x + t - rx, y, c, border)
        tmp[index(x, y, c)] = s
    }
    val mid = Img(width, height, channels, tmp)
    val out = FloatArray(data.size)
    val ry = kyy.radius
    val ty = kyy.taps
    for (y in 0 until height) for (x in 0 until width) for (c in 0 until channels) {
        var s = 0f
        for (t in ty.indices) s += ty[t] * mid.get(x, y + t - ry, c, border)
        out[index(x, y, c)] = s
    }
    return Img(width, height, channels, out)
}

/**
 * Dense 2-D convolution with [k]. True convolution (the kernel is flipped); pass an already-flipped
 * kernel or a symmetric one if you want correlation. `O(kw * kh)` per pixel per channel — this is
 * the general fallback and the reference the separable path is tested against.
 */
fun Img.convolve2D(k: Kernel2D, border: Border = Border.CLAMP): Img {
    val kf = k.flipped()
    val out = FloatArray(data.size)
    val rx = kf.radiusX
    val ry = kf.radiusY
    for (y in 0 until height) for (x in 0 until width) for (c in 0 until channels) {
        var s = 0f
        for (ky in 0 until kf.height) for (kx in 0 until kf.width) {
            val w = kf.weights[ky * kf.width + kx]
            if (w != 0f) s += w * get(x + kx - rx, y + ky - ry, c, border)
        }
        out[index(x, y, c)] = s
    }
    return Img(width, height, channels, out)
}

/**
 * Cross-correlation with [k] (kernel *not* flipped). This is what most "apply this stencil"
 * intuitions actually mean; identical to [convolve2D] for symmetric kernels.
 */
fun Img.correlate2D(k: Kernel2D, border: Border = Border.CLAMP): Img = convolve2D(k.flipped(), border)

// ==============================================================================================
// Smoothing
// ==============================================================================================

/**
 * Gaussian blur with standard deviation [sigma], run as two separable 1-D passes.
 *
 * The kernel is normalised, so the blur preserves the image mean (its "DC"); with
 * [Border.CLAMP] or [Border.REFLECT] this holds at the borders too.
 */
fun Img.gaussianBlur(sigma: Double, truncate: Double = 4.0, border: Border = Border.CLAMP): Img =
    convolveSeparable(gaussianKernel1D(sigma, truncate), border = border)

/**
 * Box (mean) blur over a `(2*radius+1)²` window, computed with running sums so the cost is
 * independent of [radius].
 */
fun Img.boxBlur(radius: Int, border: Border = Border.CLAMP): Img {
    require(radius >= 0) { "radius must be >= 0, got $radius" }
    if (radius == 0) return copy()
    val n = 2 * radius + 1
    // horizontal pass
    val tmp = FloatArray(data.size)
    for (y in 0 until height) for (c in 0 until channels) {
        var acc = 0f
        for (t in -radius..radius) acc += get(t, y, c, border)
        tmp[index(0, y, c)] = acc / n
        for (x in 1 until width) {
            acc += get(x + radius, y, c, border) - get(x - radius - 1, y, c, border)
            tmp[index(x, y, c)] = acc / n
        }
    }
    val mid = Img(width, height, channels, tmp)
    // vertical pass
    val out = FloatArray(data.size)
    for (x in 0 until width) for (c in 0 until channels) {
        var acc = 0f
        for (t in -radius..radius) acc += mid.get(x, t, c, border)
        out[index(x, 0, c)] = acc / n
        for (y in 1 until height) {
            acc += mid.get(x, y + radius, c, border) - mid.get(x, y - radius - 1, c, border)
            out[index(x, y, c)] = acc / n
        }
    }
    return Img(width, height, channels, out)
}

/**
 * Three successive [boxBlur] passes, which by the central limit theorem approximate a Gaussian of
 * standard deviation [sigma] — `O(1)` per pixel at any sigma. Use when sigma is large and you care
 * more about speed than about the exact kernel shape.
 */
fun Img.boxBlurApproxGaussian(sigma: Double, border: Border = Border.CLAMP): Img {
    require(sigma > 0.0) { "sigma must be > 0, got $sigma" }
    // Variance of one box of half-width r is r(r+1)/3; three boxes add variances.
    val r = max(1, ((sqrt(1.0 + 4.0 * sigma * sigma) - 1.0) / 2.0).roundToInt())
    return boxBlur(r, border).boxBlur(r, border).boxBlur(r, border)
}

/**
 * Median filter over a `(2*radius+1)²` window — the standard salt-and-pepper de-noiser, and unlike
 * a mean it does not smear edges.
 */
fun Img.median(radius: Int = 1, border: Border = Border.CLAMP): Img {
    require(radius >= 1) { "radius must be >= 1, got $radius" }
    val n = (2 * radius + 1) * (2 * radius + 1)
    val buf = FloatArray(n)
    val out = FloatArray(data.size)
    for (y in 0 until height) for (x in 0 until width) for (c in 0 until channels) {
        var i = 0
        for (dy in -radius..radius) for (dx in -radius..radius) buf[i++] = get(x + dx, y + dy, c, border)
        buf.sort()
        out[index(x, y, c)] = buf[n / 2]
    }
    return Img(width, height, channels, out)
}

/**
 * Bilateral filter: a Gaussian blur whose weights are additionally attenuated by intensity
 * distance, so it smooths flat regions while leaving edges sharp.
 *
 * @param sigmaSpace spatial standard deviation, in pixels (sets the window as `ceil(2*sigmaSpace)`)
 * @param sigmaRange intensity standard deviation, in sample units (0..1 for normal image data);
 *   small values preserve more edges, large values degenerate to a plain Gaussian.
 */
fun Img.bilateral(sigmaSpace: Double = 3.0, sigmaRange: Double = 0.1, border: Border = Border.CLAMP): Img {
    require(sigmaSpace > 0.0) { "sigmaSpace must be > 0, got $sigmaSpace" }
    require(sigmaRange > 0.0) { "sigmaRange must be > 0, got $sigmaRange" }
    val r = max(1, ceil(sigmaSpace * 2.0).toInt())
    val spatial = FloatArray((2 * r + 1) * (2 * r + 1))
    val inv2ss = 1.0 / (2.0 * sigmaSpace * sigmaSpace)
    var i = 0
    for (dy in -r..r) for (dx in -r..r) spatial[i++] = exp(-(dx * dx + dy * dy) * inv2ss).toFloat()
    val inv2sr = (1.0 / (2.0 * sigmaRange * sigmaRange)).toFloat()
    val out = FloatArray(data.size)
    for (y in 0 until height) for (x in 0 until width) for (c in 0 until channels) {
        val centre = data[index(x, y, c)]
        var num = 0f
        var den = 0f
        var k = 0
        for (dy in -r..r) for (dx in -r..r) {
            val v = get(x + dx, y + dy, c, border)
            val d = v - centre
            val w = spatial[k++] * exp((-d * d * inv2sr).toDouble()).toFloat()
            num += w * v
            den += w
        }
        out[index(x, y, c)] = if (den > 0f) num / den else centre
    }
    return Img(width, height, channels, out)
}

/**
 * Unsharp mask: `image + amount * (image - gaussianBlur(image, sigma))`. The oldest sharpening
 * trick there is; [threshold] suppresses amplification of low-contrast noise.
 */
fun Img.unsharpMask(sigma: Double = 1.5, amount: Double = 1.0, threshold: Float = 0f): Img {
    val blurred = gaussianBlur(sigma)
    val a = amount.toFloat()
    return zip(blurred) { v, b ->
        val d = v - b
        if (abs(d) < threshold) v else v + a * d
    }
}

// ==============================================================================================
// Gradients
// ==============================================================================================

/**
 * A gradient field: per-pixel partial derivatives of a scalar image.
 * @property dx derivative along +x (rightwards), 1-channel
 * @property dy derivative along +y (**downwards**, matching image coordinates), 1-channel
 */
class Gradient(val dx: Img, val dy: Img) {
    init {
        require(dx.channels == 1 && dy.channels == 1) { "Gradient components must be 1-channel" }
        require(dx.sameShapeAs(dy)) { "Gradient dx/dy shapes must match" }
    }

    val width: Int get() = dx.width
    val height: Int get() = dx.height

    /** Per-pixel `sqrt(dx² + dy²)`. */
    fun magnitude(): Img = dx.zip(dy) { a, b -> sqrt(a * a + b * b) }

    /** Per-pixel `|dx| + |dy|` — the cheap L1 stand-in for [magnitude]. */
    fun magnitudeL1(): Img = dx.zip(dy) { a, b -> abs(a) + abs(b) }

    /**
     * Per-pixel gradient direction in **radians**, `atan2(dy, dx)` in `(-π, π]`. Note `dy` points
     * down, so a bright-below-dark horizontal edge has orientation `+π/2`.
     */
    fun orientation(): Img = dx.zip(dy) { a, b -> atan2(b, a) }

    /** Orientation folded into `[0, π)` — direction without sign, the form Hough and HOG want. */
    fun orientationUnsigned(): Img = orientation().map {
        var a = it
        while (a < 0f) a += PI.toFloat()
        while (a >= PI.toFloat()) a -= PI.toFloat()
        a
    }

    /** Colour visualisation: hue = direction, value = magnitude (normalised). */
    fun toColorImg(): Img {
        val mag = magnitude().normalized()
        val ang = orientation()
        val hsv = Img(width, height, 3, FloatArray(width * height * 3))
        for (p in 0 until width * height) {
            val a = ang.data[p]
            hsv.data[p * 3] = (((a / (2.0 * PI)) + 1.0) % 1.0).toFloat()
            hsv.data[p * 3 + 1] = 1f
            hsv.data[p * 3 + 2] = mag.data[p]
        }
        return hsv.hsvToRgb()
    }

    override fun toString(): String = "Gradient(${width}x$height)"
}

/**
 * Sobel gradients. The 3x3 Sobel operator factors into a `[-1, 0, 1]` derivative across the
 * gradient axis and a `[1, 2, 1]/4` smoothing along it, so both components run separably.
 *
 * The smoothing factor is **normalised** (divided by 4), which means on a unit step edge the
 * gradient magnitude comes out as 1 per unit of intensity change rather than the unnormalised
 * operator's 4 — a far more useful scale to threshold against.
 */
fun Img.sobel(border: Border = Border.CLAMP): Gradient {
    val g = if (channels == 1) this else luma()
    val dx = g.convolveSeparable(SOBEL_DERIV, SOBEL_SMOOTH, border)
    val dy = g.convolveSeparable(SOBEL_SMOOTH, SOBEL_DERIV, border)
    return Gradient(dx, dy)
}

/** Shorthand for `sobel().magnitude()`. */
fun Img.sobelMagnitude(border: Border = Border.CLAMP): Img = sobel(border).magnitude()

/**
 * Scharr gradients — same structure as [sobel] but with the `[3, 10, 3]/16` smoothing factor,
 * which has markedly better rotational symmetry (the direction estimate is more accurate on
 * diagonal edges).
 */
fun Img.scharr(border: Border = Border.CLAMP): Gradient {
    val g = if (channels == 1) this else luma()
    val dx = g.convolveSeparable(SOBEL_DERIV, SCHARR_SMOOTH, border)
    val dy = g.convolveSeparable(SCHARR_SMOOTH, SOBEL_DERIV, border)
    return Gradient(dx, dy)
}

/**
 * Gradients of a Gaussian-smoothed image at scale [sigma], computed as the analytic derivative of
 * the Gaussian (not a blur followed by a difference), so the scale is exact.
 */
fun Img.gaussianGradient(sigma: Double, truncate: Double = 4.0, border: Border = Border.CLAMP): Gradient {
    require(sigma > 0.0) { "sigma must be > 0, got $sigma" }
    val g = if (channels == 1) this else luma()
    val smooth = gaussianKernel1D(sigma, truncate)
    val r = smooth.radius
    // d/dx of a Gaussian: -x/sigma^2 * G(x), then scaled so that convolving a ramp of slope s
    // returns exactly s (i.e. sum_i taps[i] * x_i == 1, the first-moment normalisation).
    val d = FloatArray(smooth.taps.size)
    var norm = 0.0
    for (i in d.indices) {
        val x = (i - r).toDouble()
        d[i] = (-x / (sigma * sigma) * smooth.taps[i]).toFloat()
        norm += x * d[i]
    }
    if (abs(norm) > 1e-12) for (i in d.indices) d[i] = (d[i] / norm).toFloat()
    val deriv = Kernel1D(d)
    return Gradient(
        g.convolveSeparable(deriv, smooth, border),
        g.convolveSeparable(smooth, deriv, border)
    )
}

/** Discrete Laplacian (second derivative). [eightNeighbour] picks [LAPLACIAN_8] over [LAPLACIAN_4]. */
fun Img.laplacian(eightNeighbour: Boolean = false, border: Border = Border.CLAMP): Img {
    val g = if (channels == 1) this else luma()
    return g.convolve2D(if (eightNeighbour) LAPLACIAN_8 else LAPLACIAN_4, border)
}

/**
 * Laplacian of Gaussian at scale [sigma] — computed as the difference of the image and its blur's
 * Laplacian, i.e. `laplacian(gaussianBlur(img, sigma))`. Zero crossings are edges at that scale.
 */
fun Img.laplacianOfGaussian(sigma: Double = 1.5, border: Border = Border.CLAMP): Img =
    gaussianBlur(sigma, border = border).laplacian(border = border)

/**
 * Difference of Gaussians, `blur(sigma1) - blur(sigma2)` with `sigma1 < sigma2` — the cheap
 * band-pass approximation to [laplacianOfGaussian] and the basis of blob detection.
 */
fun Img.differenceOfGaussians(sigma1: Double = 1.0, sigma2: Double = 2.0, border: Border = Border.CLAMP): Img {
    require(sigma1 > 0.0 && sigma2 > sigma1) { "need 0 < sigma1 < sigma2, got $sigma1 / $sigma2" }
    return gaussianBlur(sigma1, border = border) - gaussianBlur(sigma2, border = border)
}

// ==============================================================================================
// Multiscale
// ==============================================================================================

/**
 * One-octave downsample: blur by [sigma] then take every other pixel. The blur is what keeps this
 * from aliasing — a bare decimation of a fine texture produces moiré, not a small picture.
 */
fun Img.pyrDown(sigma: Double = 1.0): Img {
    val blurred = gaussianBlur(sigma)
    val nw = max(1, width / 2)
    val nh = max(1, height / 2)
    val out = FloatArray(nw * nh * channels)
    var i = 0
    for (y in 0 until nh) for (x in 0 until nw) for (c in 0 until channels) {
        out[i++] = blurred.data[blurred.index(min(x * 2, width - 1), min(y * 2, height - 1), c)]
    }
    return Img(nw, nh, channels, out)
}

/**
 * One-octave upsample to exactly [targetWidth] x [targetHeight] (default 2x), bilinear then
 * smoothed by [sigma] to suppress the interpolation grid. The approximate inverse of [pyrDown].
 */
fun Img.pyrUp(targetWidth: Int = width * 2, targetHeight: Int = height * 2, sigma: Double = 1.0): Img =
    resize(targetWidth, targetHeight, areaAverage = false).gaussianBlur(sigma)

/**
 * A Gaussian pyramid: `levels[0]` is the original, each subsequent level is [pyrDown] of the last.
 * Stops early if a level would fall below [minSize] on either axis.
 */
class GaussianPyramid(val levels: List<Img>) {
    init { require(levels.isNotEmpty()) { "a pyramid needs at least one level" } }

    /** Number of octaves, including the base. */
    val size: Int get() = levels.size

    /** The full-resolution base. */
    val base: Img get() = levels.first()

    operator fun get(i: Int): Img = levels[i]

    override fun toString(): String =
        "GaussianPyramid(${levels.joinToString(" -> ") { "${it.width}x${it.height}" }})"
}

/** Build a [GaussianPyramid] with at most [levels] octaves, stopping at [minSize] pixels. */
fun Img.gaussianPyramid(levels: Int = 4, sigma: Double = 1.0, minSize: Int = 8): GaussianPyramid {
    require(levels >= 1) { "levels must be >= 1, got $levels" }
    val out = ArrayList<Img>(levels)
    var cur = copy()
    out.add(cur)
    while (out.size < levels && cur.width / 2 >= minSize && cur.height / 2 >= minSize) {
        cur = cur.pyrDown(sigma)
        out.add(cur)
    }
    return GaussianPyramid(out)
}

/**
 * A Laplacian pyramid: [bands] hold the band-pass detail at each octave and [residual] is the
 * coarsest low-pass. [reconstruct] inverts the decomposition (to within the blur's precision),
 * which is exactly the invariant the unit test checks.
 */
class LaplacianPyramid(val bands: List<Img>, val residual: Img) {

    /** Number of detail bands. */
    val size: Int get() = bands.size

    /**
     * Collapse back to a single image: start from [residual], upsample to each band's size and add
     * the band back on. Round-trips the source image to within a small numerical error.
     */
    fun reconstruct(sigma: Double = 1.0): Img {
        var cur = residual
        for (i in bands.indices.reversed()) {
            val b = bands[i]
            cur = cur.pyrUp(b.width, b.height, sigma) + b
        }
        return cur
    }

    override fun toString(): String =
        "LaplacianPyramid(${bands.size} bands, residual ${residual.width}x${residual.height})"
}

/**
 * Build a [LaplacianPyramid]: at each octave, `band = level - pyrUp(pyrDown(level))`.
 * The reconstruction is exact up to the upsampling filter, so it is a genuine invertible
 * band-splitting — the basis of multiband blending, texture synthesis and detail transfer.
 */
fun Img.laplacianPyramid(levels: Int = 4, sigma: Double = 1.0, minSize: Int = 8): LaplacianPyramid {
    require(levels >= 1) { "levels must be >= 1, got $levels" }
    val bands = ArrayList<Img>()
    var cur = copy()
    while (bands.size < levels && cur.width / 2 >= minSize && cur.height / 2 >= minSize) {
        val down = cur.pyrDown(sigma)
        val up = down.pyrUp(cur.width, cur.height, sigma)
        bands.add(cur - up)
        cur = down
    }
    return LaplacianPyramid(bands, cur)
}
