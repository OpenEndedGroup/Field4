@file:afield.CompletionCategory("Imaging")

package afield.stdlib

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Geometry and motion for [Img]: affine and perspective warps, homography estimation from point
 * correspondences (DLT + RANSAC), and pyramidal Lucas–Kanade optical flow.
 *
 * This is the part of the imaging family that relates *two* pictures rather than transforming one.
 * The through-line is:
 *
 * ```
 * val pts   = a.goodFeaturesToTrack(120)                 // where is there anything to track?
 * val flow  = opticalFlowLK(a, b, pts)                   // where did each of those go?
 * val fit   = findHomographyRansac(flow.sourcePoints(), flow.targetPoints())
 * val warped = b.warpPerspective(fit.homography.inverse()) // undo the motion
 * ```
 *
 * The linear algebra is deliberately self-contained (a 9x9 Jacobi eigen-solver and a 6x6 Gaussian
 * elimination), because a 3x3 homography does not justify a dependency and the whole point of this
 * family is that it works headless in a box with nothing installed.
 */

// ==============================================================================================
// Matrices
// ==============================================================================================

/**
 * A 2-D affine transform, `[a c e; b d f; 0 0 1]` — the same six-number layout as
 * `java.awt.geom.AffineTransform` and SVG's `matrix(a, b, c, d, e, f)`.
 *
 * Maps `(x, y) -> (a*x + c*y + e, b*x + d*y + f)`.
 */
data class Affine2D(
    val a: Double, val b: Double,
    val c: Double, val d: Double,
    val e: Double, val f: Double
) {
    /** Apply to a point. */
    fun apply(x: Double, y: Double): Pair<Double, Double> = (a * x + c * y + e) to (b * x + d * y + f)

    /** Apply to a [Vec2]. */
    fun apply(v: Vec2): Vec2 {
        val (x, y) = apply(v.x.toDouble(), v.y.toDouble())
        return Vec2(x.toFloat(), y.toFloat())
    }

    /** Determinant of the linear part — the area scale factor. */
    fun determinant(): Double = a * d - b * c

    /** Inverse transform. Throws if the linear part is singular. */
    fun inverse(): Affine2D {
        val det = determinant()
        require(abs(det) > 1e-12) { "affine transform is singular (det = $det)" }
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return Affine2D(ia, ib, ic, id, -(ia * e + ic * f), -(ib * e + id * f))
    }

    /** `this` applied *after* [other]: `this ∘ other`. */
    fun compose(other: Affine2D): Affine2D = Affine2D(
        a * other.a + c * other.b,
        b * other.a + d * other.b,
        a * other.c + c * other.d,
        b * other.c + d * other.d,
        a * other.e + c * other.f + e,
        b * other.e + d * other.f + f
    )

    /** Promote to a [Homography] (last row `0 0 1`). */
    fun toHomography(): Homography = Homography(doubleArrayOf(a, c, e, b, d, f, 0.0, 0.0, 1.0))

    companion object {
        val IDENTITY = Affine2D(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        fun translation(tx: Double, ty: Double) = Affine2D(1.0, 0.0, 0.0, 1.0, tx, ty)

        fun scale(sx: Double, sy: Double = sx) = Affine2D(sx, 0.0, 0.0, sy, 0.0, 0.0)

        /** Rotation by [radians] about the origin (positive = clockwise in image coordinates). */
        fun rotation(radians: Double): Affine2D {
            val c = cos(radians); val s = sin(radians)
            return Affine2D(c, s, -s, c, 0.0, 0.0)
        }

        /** Rotation by [radians] about `(cx, cy)`. */
        fun rotationAbout(radians: Double, cx: Double, cy: Double): Affine2D =
            translation(cx, cy).compose(rotation(radians)).compose(translation(-cx, -cy))

        /**
         * Least-squares affine fit from >= 3 point correspondences (exact for 3). Solves the two
         * independent 3-parameter normal-equation systems for x' and y'.
         */
        fun fromCorrespondences(src: List<Vec2>, dst: List<Vec2>): Affine2D {
            require(src.size == dst.size) { "src and dst must be the same length" }
            require(src.size >= 3) { "an affine fit needs at least 3 correspondences, got ${src.size}" }
            // Normal equations for [x y 1] * [p0 p1 p2]^T = x'
            val m = Array(3) { DoubleArray(3) }
            val rx = DoubleArray(3)
            val ry = DoubleArray(3)
            for (i in src.indices) {
                val v = doubleArrayOf(src[i].x.toDouble(), src[i].y.toDouble(), 1.0)
                for (r in 0..2) {
                    for (c in 0..2) m[r][c] += v[r] * v[c]
                    rx[r] += v[r] * dst[i].x
                    ry[r] += v[r] * dst[i].y
                }
            }
            val px = solveDenseSystem(m.map { it.copyOf() }.toTypedArray(), rx.copyOf())
            val py = solveDenseSystem(m, ry)
            return Affine2D(px[0], py[0], px[1], py[1], px[2], py[2])
        }
    }
}

/**
 * A 2-D projective transform (homography), a 3x3 matrix in row-major order, mapping
 * `(x, y) -> ((m0 x + m1 y + m2)/(m6 x + m7 y + m8), (m3 x + m4 y + m5)/(m6 x + m7 y + m8))`.
 *
 * Homographies are defined only up to scale; [normalized] divides by `m8` (or by the Frobenius norm
 * if `m8` is ~0) so two equal transforms compare equal numerically.
 *
 * @property m nine coefficients, row-major
 */
class Homography(val m: DoubleArray) {
    init { require(m.size == 9) { "a homography needs 9 coefficients, got ${m.size}" } }

    /** Map a point; returns `(x', y')` after the perspective divide. */
    fun apply(x: Double, y: Double): Pair<Double, Double> {
        val w = m[6] * x + m[7] * y + m[8]
        val iw = if (abs(w) < 1e-14) 0.0 else 1.0 / w
        return ((m[0] * x + m[1] * y + m[2]) * iw) to ((m[3] * x + m[4] * y + m[5]) * iw)
    }

    /** Map a [Vec2]. */
    fun apply(v: Vec2): Vec2 {
        val (x, y) = apply(v.x.toDouble(), v.y.toDouble())
        return Vec2(x.toFloat(), y.toFloat())
    }

    /** Matrix product `this * other` (apply [other] first). */
    fun compose(other: Homography): Homography {
        val o = DoubleArray(9)
        for (r in 0..2) for (c in 0..2) {
            var s = 0.0
            for (k in 0..2) s += m[r * 3 + k] * other.m[k * 3 + c]
            o[r * 3 + c] = s
        }
        return Homography(o)
    }

    /** Adjugate-based inverse (equivalent up to scale, which is all a homography has). */
    fun inverse(): Homography {
        val a = m
        val c = DoubleArray(9)
        c[0] = a[4] * a[8] - a[5] * a[7]
        c[1] = a[2] * a[7] - a[1] * a[8]
        c[2] = a[1] * a[5] - a[2] * a[4]
        c[3] = a[5] * a[6] - a[3] * a[8]
        c[4] = a[0] * a[8] - a[2] * a[6]
        c[5] = a[2] * a[3] - a[0] * a[5]
        c[6] = a[3] * a[7] - a[4] * a[6]
        c[7] = a[1] * a[6] - a[0] * a[7]
        c[8] = a[0] * a[4] - a[1] * a[3]
        val det = a[0] * c[0] + a[1] * c[3] + a[2] * c[6]
        require(abs(det) > 1e-14) { "homography is singular (det = $det)" }
        return Homography(DoubleArray(9) { c[it] / det }).normalized()
    }

    /** Rescale so `m8 == 1` where possible, otherwise to unit Frobenius norm. */
    fun normalized(): Homography {
        if (abs(m[8]) > 1e-12) return Homography(DoubleArray(9) { m[it] / m[8] })
        var n = 0.0
        for (v in m) n += v * v
        n = sqrt(n)
        if (n < 1e-18) return this
        return Homography(DoubleArray(9) { m[it] / n })
    }

    /** Largest absolute coefficient difference against [other], both [normalized]. */
    fun maxCoefficientDifference(other: Homography): Double {
        val a = normalized().m
        val b = other.normalized().m
        var d = 0.0
        for (i in 0..8) d = max(d, abs(a[i] - b[i]))
        return d
    }

    /** `true` if the last row is `(0, 0, k)` — i.e. this is really an affine transform. */
    fun isAffine(tol: Double = 1e-9): Boolean {
        val n = normalized().m
        return abs(n[6]) < tol && abs(n[7]) < tol
    }

    override fun toString(): String {
        val n = normalized().m
        return "Homography[" + (0..2).joinToString("; ") { r ->
            (0..2).joinToString(", ") { c -> "%.5f".format(n[r * 3 + c]) }
        } + "]"
    }

    companion object {
        val IDENTITY = Homography(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
    }
}

// ==============================================================================================
// Linear algebra helpers (self-contained on purpose)
// ==============================================================================================

/** Solve `A x = b` for a small dense square system by Gaussian elimination with partial pivoting. */
fun solveDenseSystem(A: Array<DoubleArray>, b: DoubleArray): DoubleArray {
    val n = b.size
    require(A.size == n && A.all { it.size == n }) { "A must be ${n}x$n to match b" }
    val m = Array(n) { A[it].copyOf() }
    val x = b.copyOf()
    for (col in 0 until n) {
        var piv = col
        for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
        require(abs(m[piv][col]) > 1e-14) { "singular system at column $col" }
        if (piv != col) { val t = m[piv]; m[piv] = m[col]; m[col] = t; val tv = x[piv]; x[piv] = x[col]; x[col] = tv }
        val d = m[col][col]
        for (r in col + 1 until n) {
            val k = m[r][col] / d
            if (k == 0.0) continue
            for (c in col until n) m[r][c] -= k * m[col][c]
            x[r] -= k * x[col]
        }
    }
    for (r in n - 1 downTo 0) {
        var s = x[r]
        for (c in r + 1 until n) s -= m[r][c] * x[c]
        x[r] = s / m[r][r]
    }
    return x
}

/**
 * Eigenvector of the smallest eigenvalue of a small **symmetric** matrix, by the cyclic Jacobi
 * rotation method. Used to find the null space of `AᵀA` in the DLT, which is what makes homography
 * estimation possible without an SVD library.
 */
fun smallestEigenvector(symmetric: Array<DoubleArray>, sweeps: Int = 64): DoubleArray {
    val n = symmetric.size
    require(symmetric.all { it.size == n }) { "matrix must be square" }
    val a = Array(n) { symmetric[it].copyOf() }
    val v = Array(n) { r -> DoubleArray(n) { c -> if (r == c) 1.0 else 0.0 } }
    repeat(sweeps) {
        var off = 0.0
        for (p in 0 until n) for (q in p + 1 until n) off += a[p][q] * a[p][q]
        if (off < 1e-24) return@repeat
        for (p in 0 until n) for (q in p + 1 until n) {
            if (abs(a[p][q]) < 1e-18) continue
            val theta = (a[q][q] - a[p][p]) / (2.0 * a[p][q])
            val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1.0))
            val c = 1.0 / sqrt(t * t + 1.0)
            val s = t * c
            for (k in 0 until n) {
                val akp = a[k][p]; val akq = a[k][q]
                a[k][p] = c * akp - s * akq
                a[k][q] = s * akp + c * akq
            }
            for (k in 0 until n) {
                val apk = a[p][k]; val aqk = a[q][k]
                a[p][k] = c * apk - s * aqk
                a[q][k] = s * apk + c * aqk
            }
            for (k in 0 until n) {
                val vkp = v[k][p]; val vkq = v[k][q]
                v[k][p] = c * vkp - s * vkq
                v[k][q] = s * vkp + c * vkq
            }
        }
    }
    var best = 0
    for (i in 1 until n) if (a[i][i] < a[best][best]) best = i
    return DoubleArray(n) { v[it][best] }
}

// ==============================================================================================
// Homography estimation
// ==============================================================================================

/**
 * Direct Linear Transform: the exact homography through 4 correspondences, or the least-squares
 * homography through more.
 *
 * Points are **Hartley-normalised** first (translated to zero mean, scaled to mean distance `√2`)
 * and the result un-normalised afterwards, which is not optional — without it the DLT's conditioning
 * on pixel-scale coordinates is bad enough to visibly distort the fit.
 *
 * @throws IllegalArgumentException if fewer than 4 correspondences are supplied
 */
fun findHomography(src: List<Vec2>, dst: List<Vec2>): Homography {
    require(src.size == dst.size) { "src and dst must be the same length, got ${src.size} / ${dst.size}" }
    require(src.size >= 4) { "a homography needs at least 4 correspondences, got ${src.size}" }
    val (ns, ts) = hartleyNormalize(src)
    val (nd, td) = hartleyNormalize(dst)
    // Build A^T A for the 2N x 9 DLT system without materialising A.
    val ata = Array(9) { DoubleArray(9) }
    val row = DoubleArray(9)
    fun accumulate() {
        for (i in 0..8) for (j in 0..8) ata[i][j] += row[i] * row[j]
    }
    for (i in ns.indices) {
        val x = ns[i].x.toDouble(); val y = ns[i].y.toDouble()
        val u = nd[i].x.toDouble(); val v = nd[i].y.toDouble()
        row[0] = -x; row[1] = -y; row[2] = -1.0; row[3] = 0.0; row[4] = 0.0; row[5] = 0.0
        row[6] = u * x; row[7] = u * y; row[8] = u
        accumulate()
        row[0] = 0.0; row[1] = 0.0; row[2] = 0.0; row[3] = -x; row[4] = -y; row[5] = -1.0
        row[6] = v * x; row[7] = v * y; row[8] = v
        accumulate()
    }
    val h = smallestEigenvector(ata)
    val hn = Homography(h)
    // Un-normalise: H = Td^-1 * Hn * Ts
    return td.inverse().compose(hn).compose(ts).normalized()
}

/** Hartley normalisation: returns the transformed points and the similarity transform applied. */
private fun hartleyNormalize(pts: List<Vec2>): Pair<List<Vec2>, Homography> {
    var mx = 0.0; var my = 0.0
    for (p in pts) { mx += p.x; my += p.y }
    mx /= pts.size; my /= pts.size
    var meanDist = 0.0
    for (p in pts) meanDist += hypot(p.x - mx, p.y - my)
    meanDist /= pts.size
    val s = if (meanDist < 1e-12) 1.0 else sqrt(2.0) / meanDist
    val t = Homography(doubleArrayOf(s, 0.0, -s * mx, 0.0, s, -s * my, 0.0, 0.0, 1.0))
    return pts.map { Vec2(((it.x - mx) * s).toFloat(), ((it.y - my) * s).toFloat()) } to t
}

/**
 * Result of [findHomographyRansac].
 * @property homography the model refit on the inlier set
 * @property inliers indices (into the input lists) of the correspondences that agree with it
 * @property iterations how many random samples were actually drawn
 */
class RansacFit(val homography: Homography, val inliers: List<Int>, val iterations: Int) {
    val inlierCount: Int get() = inliers.size

    /** Inlier fraction of the supplied correspondences. */
    fun inlierRatio(total: Int): Double = inliers.size.toDouble() / total

    override fun toString(): String = "RansacFit($inlierCount inliers after $iterations iterations)"
}

/**
 * RANSAC homography estimation: repeatedly fit a homography to a random minimal sample of 4
 * correspondences, score it by how many of the rest it maps to within [threshold] pixels, and keep
 * the best consensus set — then refit on that set with a full least-squares [findHomography].
 *
 * This is the whole reason feature matching works in practice: half your correspondences can be
 * garbage and the answer is still correct, because a wrong model has no reason to be *consistently*
 * wrong across many points.
 *
 * @param threshold inlier symmetric-transfer distance, in pixels
 * @param iterations maximum random samples
 * @param seed RNG seed, so results are reproducible in tests and docs captures
 */
fun findHomographyRansac(
    src: List<Vec2>,
    dst: List<Vec2>,
    threshold: Double = 3.0,
    iterations: Int = 500,
    seed: Long = 0L
): RansacFit {
    require(src.size == dst.size) { "src and dst must be the same length" }
    require(src.size >= 4) { "RANSAC needs at least 4 correspondences, got ${src.size}" }
    val n = src.size
    if (n == 4) {
        val h = findHomography(src, dst)
        return RansacFit(h, (0 until n).toList(), 0)
    }
    val rng = Random(seed)
    val t2 = threshold * threshold
    var bestInliers: List<Int> = emptyList()
    var used = 0
    val idx = IntArray(4)
    for (iter in 0 until iterations) {
        used = iter + 1
        // Draw 4 distinct indices.
        var k = 0
        var guard = 0
        while (k < 4 && guard++ < 200) {
            val c = rng.nextInt(n)
            if ((0 until k).none { idx[it] == c }) idx[k++] = c
        }
        if (k < 4) continue
        val ss = idx.map { src[it] }
        val dd = idx.map { dst[it] }
        val h = try { findHomography(ss, dd) } catch (_: Exception) { continue }
        if (h.m.any { !it.isFinite() }) continue
        val inl = ArrayList<Int>()
        for (i in 0 until n) {
            val (px, py) = h.apply(src[i].x.toDouble(), src[i].y.toDouble())
            if (!px.isFinite() || !py.isFinite()) continue
            val dx = px - dst[i].x
            val dy = py - dst[i].y
            if (dx * dx + dy * dy <= t2) inl.add(i)
        }
        if (inl.size > bestInliers.size) {
            bestInliers = inl
            // Early exit once essentially everything agrees.
            if (bestInliers.size == n) break
        }
    }
    if (bestInliers.size < 4) {
        return RansacFit(findHomography(src, dst), bestInliers, used)
    }
    val refit = findHomography(bestInliers.map { src[it] }, bestInliers.map { dst[it] })
    // Re-score with the refit model so the reported inliers match the reported homography.
    val finalInliers = (0 until n).filter { i ->
        val (px, py) = refit.apply(src[i].x.toDouble(), src[i].y.toDouble())
        if (!px.isFinite() || !py.isFinite()) return@filter false
        val dx = px - dst[i].x
        val dy = py - dst[i].y
        dx * dx + dy * dy <= t2
    }
    return RansacFit(refit, if (finalInliers.size >= bestInliers.size) finalInliers else bestInliers, used)
}

// ==============================================================================================
// Warping
// ==============================================================================================

/**
 * Resample through an affine transform. The transform maps **source → destination**; the
 * implementation inverts it and does the correct backwards mapping (for every output pixel, find
 * where it came from), so there are no holes.
 *
 * @param outWidth/[outHeight] output size, defaulting to the input size
 * @param bilinear bilinear resampling (`false` = nearest, which preserves hard label values)
 */
fun Img.warpAffine(
    t: Affine2D,
    outWidth: Int = width,
    outHeight: Int = height,
    bilinear: Boolean = true,
    border: Border = Border.ZERO
): Img = warpPerspective(t.toHomography(), outWidth, outHeight, bilinear, border)

/**
 * Resample through a projective transform, mapping **source → destination** (inverted internally,
 * see [warpAffine]). Pixels whose source falls outside the image are resolved by [border].
 */
fun Img.warpPerspective(
    h: Homography,
    outWidth: Int = width,
    outHeight: Int = height,
    bilinear: Boolean = true,
    border: Border = Border.ZERO
): Img {
    require(outWidth >= 1 && outHeight >= 1) { "warp target must be >= 1x1, got ${outWidth}x$outHeight" }
    val inv = h.inverse()
    val out = FloatArray(outWidth * outHeight * channels)
    var i = 0
    for (y in 0 until outHeight) for (x in 0 until outWidth) {
        val (sx, sy) = inv.apply(x.toDouble(), y.toDouble())
        for (c in 0 until channels) {
            out[i++] = if (bilinear) sampleBilinear(sx, sy, c, border) else sampleNearest(sx, sy, c, border)
        }
    }
    return Img(outWidth, outHeight, channels, out)
}

/**
 * Rotate about the image centre by [radians], keeping the same canvas size.
 * Positive angles rotate clockwise on screen (y points down).
 */
fun Img.rotate(radians: Double, bilinear: Boolean = true, border: Border = Border.ZERO): Img =
    warpAffine(
        Affine2D.rotationAbout(radians, (width - 1) / 2.0, (height - 1) / 2.0),
        bilinear = bilinear, border = border
    )

/**
 * Rectify a quadrilateral in this image to a `outWidth x outHeight` rectangle — the "flatten the
 * photographed page" operation, and the most immediately convincing use of a homography.
 *
 * @param quad four source corners in **clockwise order starting top-left**
 */
fun Img.warpQuadToRect(quad: List<Vec2>, outWidth: Int, outHeight: Int, bilinear: Boolean = true): Img {
    require(quad.size == 4) { "warpQuadToRect needs exactly 4 corners, got ${quad.size}" }
    val dst = listOf(
        Vec2(0f, 0f),
        Vec2((outWidth - 1).toFloat(), 0f),
        Vec2((outWidth - 1).toFloat(), (outHeight - 1).toFloat()),
        Vec2(0f, (outHeight - 1).toFloat())
    )
    return warpPerspective(findHomography(quad, dst), outWidth, outHeight, bilinear, Border.ZERO)
}

// ==============================================================================================
// Motion
// ==============================================================================================

/**
 * One tracked point's motion, as produced by [opticalFlowLK].
 *
 * @property x0/[y0] position in the first frame
 * @property x1/[y1] position in the second frame (sub-pixel)
 * @property ok `false` if the solve was ill-conditioned or the point left the image — a tracker
 *   that never reports failure is lying
 * @property error mean absolute residual over the tracking window after convergence
 */
data class FlowVector(
    val x0: Double, val y0: Double,
    val x1: Double, val y1: Double,
    val ok: Boolean,
    val error: Double
) {
    val dx: Double get() = x1 - x0
    val dy: Double get() = y1 - y0

    /** Displacement magnitude in pixels. */
    val magnitude: Double get() = hypot(dx, dy)

    /** Start point as a [Vec2]. */
    fun from(): Vec2 = Vec2(x0.toFloat(), y0.toFloat())

    /** End point as a [Vec2]. */
    fun to(): Vec2 = Vec2(x1.toFloat(), y1.toFloat())
}

/** The result of a sparse optical-flow run. */
class FlowField(val vectors: List<FlowVector>) : Iterable<FlowVector> {
    override fun iterator(): Iterator<FlowVector> = vectors.iterator()

    /** Only the vectors whose track converged. */
    fun tracked(): List<FlowVector> = vectors.filter { it.ok }

    /** Source points of the converged tracks (pairs with [targetPoints] for homography fitting). */
    fun sourcePoints(): List<Vec2> = tracked().map { it.from() }

    /** Target points of the converged tracks. */
    fun targetPoints(): List<Vec2> = tracked().map { it.to() }

    /** Median displacement magnitude over the converged tracks — a robust "how much moved". */
    fun medianMagnitude(): Double {
        val m = tracked().map { it.magnitude }.sorted()
        if (m.isEmpty()) return 0.0
        return m[m.size / 2]
    }

    /** Component-wise median displacement — a robust global translation estimate. */
    fun medianDisplacement(): Pair<Double, Double> {
        val t = tracked()
        if (t.isEmpty()) return 0.0 to 0.0
        val xs = t.map { it.dx }.sorted()
        val ys = t.map { it.dy }.sorted()
        return xs[xs.size / 2] to ys[ys.size / 2]
    }

    override fun toString(): String = "FlowField(${tracked().size}/${vectors.size} tracked)"
}

/**
 * Pyramidal Lucas–Kanade sparse optical flow.
 *
 * For each point, assume the motion is locally constant over a `(2*windowRadius+1)²` window and the
 * brightness of a moving patch is constant; those two assumptions linearise into a 2x2 system in
 * `(dx, dy)` built from the spatial gradients and the temporal difference, solved iteratively
 * (Newton steps on the warped patch). A Gaussian pyramid makes large motions tractable: solve at
 * the coarsest octave where the motion is small, then propagate the estimate down.
 *
 * A track is marked `ok = false` when the 2x2 system is ill-conditioned (an aperture-problem
 * location — a smooth region or a straight edge) or the point walks out of the frame. Seed with
 * [Img.goodFeaturesToTrack], which selects exactly the points where the system is well conditioned.
 *
 * @param a first frame
 * @param b second frame
 * @param points points to track, in [a]'s coordinates
 * @param windowRadius half-width of the tracking window
 * @param levels pyramid octaves (1 disables the pyramid)
 * @param iterations Newton iterations per level
 * @param minEigenvalue smallest acceptable eigenvalue of the 2x2 system, relative to the window size
 */
fun opticalFlowLK(
    a: Img,
    b: Img,
    points: List<Vec2>,
    windowRadius: Int = 7,
    levels: Int = 3,
    iterations: Int = 20,
    minEigenvalue: Double = 1e-4,
    epsilon: Double = 0.01
): FlowField {
    require(a.width == b.width && a.height == b.height) { "frames must be the same size" }
    require(windowRadius >= 1) { "windowRadius must be >= 1, got $windowRadius" }
    require(levels >= 1) { "levels must be >= 1, got $levels" }
    val ga = (if (a.channels == 1) a else a.luma())
    val gb = (if (b.channels == 1) b else b.luma())
    val pa = ga.gaussianPyramid(levels, minSize = 2 * windowRadius + 2)
    val pb = gb.gaussianPyramid(pa.size, minSize = 2 * windowRadius + 2)
    val n = min(pa.size, pb.size)

    val out = ArrayList<FlowVector>(points.size)
    for (p in points) {
        var gx = 0.0
        var gy = 0.0
        var ok = true
        var err = 0.0
        for (lvl in n - 1 downTo 0) {
            val la = pa[lvl]
            val lb = pb[lvl]
            val scale = 1.0 / (1 shl lvl)
            val px = p.x * scale
            val py = p.y * scale
            // Spatial gradients of the first frame at this level.
            val grad = la.sobel()
            // Build the 2x2 structure matrix over the window (fixed, from frame a).
            var ixx = 0.0; var ixy = 0.0; var iyy = 0.0
            for (dy in -windowRadius..windowRadius) for (dx in -windowRadius..windowRadius) {
                val ix = grad.dx.sampleBilinear(px + dx, py + dy).toDouble()
                val iy = grad.dy.sampleBilinear(px + dx, py + dy).toDouble()
                ixx += ix * ix; ixy += ix * iy; iyy += iy * iy
            }
            val det = ixx * iyy - ixy * ixy
            val tr = ixx + iyy
            val minEig = (tr - sqrt(max(0.0, tr * tr - 4.0 * det))) / 2.0
            val wCount = (2.0 * windowRadius + 1).let { it * it }
            if (minEig / wCount < minEigenvalue || abs(det) < 1e-12) { ok = false; break }
            // Newton iterations on the displacement at this level.
            for (it2 in 0 until iterations) {
                var bx = 0.0; var by = 0.0
                var sumAbs = 0.0
                var count = 0
                for (dy in -windowRadius..windowRadius) for (dx in -windowRadius..windowRadius) {
                    val va = la.sampleBilinear(px + dx, py + dy).toDouble()
                    val vb = lb.sampleBilinear(px + gx + dx, py + gy + dy).toDouble()
                    val dt = va - vb
                    val ix = grad.dx.sampleBilinear(px + dx, py + dy).toDouble()
                    val iy = grad.dy.sampleBilinear(px + dx, py + dy).toDouble()
                    bx += dt * ix; by += dt * iy
                    sumAbs += abs(dt); count++
                }
                err = sumAbs / count
                val ux = (iyy * bx - ixy * by) / det
                val uy = (ixx * by - ixy * bx) / det
                gx += ux; gy += uy
                if (hypot(ux, uy) < epsilon) break
                if (!gx.isFinite() || !gy.isFinite()) { ok = false; break }
            }
            if (!ok) break
            if (lvl > 0) { gx *= 2.0; gy *= 2.0 }
        }
        val x1 = p.x + gx
        val y1 = p.y + gy
        if (!x1.isFinite() || !y1.isFinite() ||
            x1 < 0 || y1 < 0 || x1 > a.width - 1 || y1 > a.height - 1
        ) ok = false
        out.add(FlowVector(p.x.toDouble(), p.y.toDouble(), x1, y1, ok, err))
    }
    return FlowField(out)
}

/**
 * Dense block-matching flow on a regular [step]-pixel grid: for each grid cell, search
 * `[-maxShift, maxShift]²` for the integer displacement minimising the sum of absolute differences
 * over a `(2*blockRadius+1)²` block. Brute force, but robust to the large displacements that break
 * Lucas–Kanade's linearisation, and it needs no gradient.
 */
fun opticalFlowBlockMatch(
    a: Img,
    b: Img,
    step: Int = 16,
    blockRadius: Int = 8,
    maxShift: Int = 12
): FlowField {
    require(a.width == b.width && a.height == b.height) { "frames must be the same size" }
    require(step >= 1 && blockRadius >= 1 && maxShift >= 1) { "step/blockRadius/maxShift must be >= 1" }
    val ga = if (a.channels == 1) a else a.luma()
    val gb = if (b.channels == 1) b else b.luma()
    val out = ArrayList<FlowVector>()
    var y = blockRadius
    while (y < ga.height - blockRadius) {
        var x = blockRadius
        while (x < ga.width - blockRadius) {
            var best = Double.MAX_VALUE
            var bdx = 0
            var bdy = 0
            for (sy in -maxShift..maxShift) for (sx in -maxShift..maxShift) {
                var sad = 0.0
                for (dy in -blockRadius..blockRadius) for (dx in -blockRadius..blockRadius) {
                    val va = ga.get(x + dx, y + dy, 0, Border.CLAMP)
                    val vb = gb.get(x + dx + sx, y + dy + sy, 0, Border.CLAMP)
                    sad += abs(va - vb)
                }
                if (sad < best) { best = sad; bdx = sx; bdy = sy }
            }
            val nBlock = (2.0 * blockRadius + 1).let { it * it }
            out.add(
                FlowVector(
                    x.toDouble(), y.toDouble(),
                    (x + bdx).toDouble(), (y + bdy).toDouble(),
                    ok = true, error = best / nBlock
                )
            )
            x += step
        }
        y += step
    }
    return FlowField(out)
}

/**
 * Absolute frame difference, optionally thresholded to a binary motion mask. The crudest and most
 * reliable motion cue there is; pair with [Img.open] to drop sensor noise and
 * [Img.connectedComponents] to count what moved.
 */
fun frameDifference(a: Img, b: Img, threshold: Double = 0.0, blurSigma: Double = 0.0): Img {
    require(a.width == b.width && a.height == b.height) { "frames must be the same size" }
    val ga = if (a.channels == 1) a else a.luma()
    val gb = if (b.channels == 1) b else b.luma()
    var d = ga.zip(gb) { p, q -> abs(p - q) }
    if (blurSigma > 0.0) d = d.gaussianBlur(blurSigma)
    return if (threshold > 0.0) d.threshold(threshold.toFloat()) else d
}

/**
 * Running background model: `background = (1 - alpha) * background + alpha * frame`. Returns the
 * updated background; difference the next frame against it for a cheap foreground mask that adapts
 * to slow lighting changes.
 */
fun updateBackground(background: Img, frame: Img, alpha: Double = 0.05): Img {
    require(background.sameShapeAs(frame)) { "background and frame shapes must match" }
    require(alpha in 0.0..1.0) { "alpha must be in 0..1, got $alpha" }
    val af = alpha.toFloat()
    return background.zip(frame) { bg, f -> bg * (1f - af) + f * af }
}
