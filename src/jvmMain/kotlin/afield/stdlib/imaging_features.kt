@file:afield.CompletionCategory("Imaging")

package afield.stdlib

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Edges, corners, lines, blobs and morphology for [Img] — the "find things in the picture" half of
 * the stdlib imaging family (see `imaging.kt` for the value type and `imaging_filters.kt` for
 * convolution and gradients).
 *
 * Everything here reduces pixels to *structure* — a point set, a label map, a list of lines — and
 * that structure is what `imaging_field.kt` turns into `FLine`s so it lands in Field's drawing
 * layer rather than staying trapped in a raster.
 *
 * Every detector here is the textbook algorithm, implemented plainly and documented with the
 * property a test can check:
 *
 * | function | what it finds | test invariant |
 * |---|---|---|
 * | [canny] | thin, connected edges | a synthetic square yields a 1-pixel-wide closed outline |
 * | [harrisCorners] / [shiTomasiCorners] | corners via the structure tensor | 4 responses at a square's 4 corners |
 * | [fastCorners] | corners via a brightness circle test | same, and much faster |
 * | [houghLines] | straight lines in polar form | a drawn line is recovered at its `(rho, theta)` |
 * | [connectedComponents] | blobs in a binary mask | N drawn discs give N labels with the right areas |
 * | [distanceTransform] | distance to the nearest zero | a disc's centre has distance = its radius |
 */

// ==============================================================================================
// Canny edges
// ==============================================================================================

/**
 * Canny edge detection.
 *
 * The four classic stages, in order: Gaussian smoothing at [sigma]; Sobel gradients; **non-maximum
 * suppression** (keep a pixel only if its gradient magnitude is a local maximum along the gradient
 * direction, which is what thins a fat gradient ridge to one pixel); **hysteresis** (seed from
 * pixels above [high], then flood along connected pixels above [low], which is what keeps a faint
 * but continuous edge while dropping isolated noise).
 *
 * @param low  weak-edge threshold, in gradient-magnitude units (the [Img.sobel] normalisation means
 *             a unit step edge has magnitude ~1, so 0.05–0.15 is a sensible weak threshold)
 * @param high strong-edge seed threshold; must exceed [low]
 * @return a 1-channel binary mask, 1 on an edge and 0 elsewhere
 */
fun Img.canny(low: Double = 0.08, high: Double = 0.20, sigma: Double = 1.0): Img {
    require(high > low) { "canny needs high > low, got $low / $high" }
    require(low >= 0.0) { "canny thresholds must be >= 0, got $low" }
    val g = (if (channels == 1) this else luma()).let { if (sigma > 0.0) it.gaussianBlur(sigma) else it }
    val grad = g.sobel()
    val mag = grad.magnitude()
    val nms = nonMaximumSuppression(mag, grad)
    return hysteresis(nms, low.toFloat(), high.toFloat())
}

/**
 * [canny] with the thresholds chosen from the image instead of guessed.
 *
 * A fixed `(low, high)` pair that works on a bright synthetic test card finds almost nothing in a
 * dark, softly-lit photograph — which is the single most common way Canny "doesn't work". This
 * variant computes the non-maximum-suppressed gradient magnitudes, takes the [highPercentile] of
 * the **non-zero** ones as the strong threshold, and sets the weak threshold to [lowRatio] times
 * it (Canny's own recommended 1:2–1:3 ratio).
 *
 * @param highPercentile fraction of surviving ridge pixels to treat as strong seeds' cut-off
 *   (0.9 keeps roughly the top 10% as seeds)
 */
fun Img.cannyAuto(highPercentile: Double = 0.90, lowRatio: Double = 0.4, sigma: Double = 1.0): Img {
    require(highPercentile in 0.5..0.999) { "highPercentile must be in 0.5..0.999, got $highPercentile" }
    require(lowRatio in 0.05..0.95) { "lowRatio must be in 0.05..0.95, got $lowRatio" }
    val g = (if (channels == 1) this else luma()).let { if (sigma > 0.0) it.gaussianBlur(sigma) else it }
    val grad = g.sobel()
    val nms = nonMaximumSuppression(grad.magnitude(), grad)
    val nonZero = nms.data.filter { it > 1e-6f }.sorted()
    if (nonZero.isEmpty()) return zerosLike(1)
    val high = nonZero[((nonZero.size - 1) * highPercentile).toInt().coerceIn(0, nonZero.size - 1)]
    val low = (high * lowRatio).toFloat()
    if (high <= low) return hysteresis(nms, low, low * 1.001f + 1e-9f)
    return hysteresis(nms, low, high)
}

/**
 * Thin a gradient-magnitude image to ridge crests: a pixel survives only if its magnitude is >=
 * both neighbours sampled along the gradient direction (quantised to the nearest of the four
 * 45-degree axes). Exposed because it is useful on its own, on any gradient field.
 */
fun nonMaximumSuppression(magnitude: Img, gradient: Gradient): Img {
    require(magnitude.channels == 1) { "nonMaximumSuppression expects a 1-channel magnitude image" }
    require(magnitude.width == gradient.width && magnitude.height == gradient.height) {
        "magnitude and gradient shapes must match"
    }
    val w = magnitude.width
    val h = magnitude.height
    val out = FloatArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        val p = y * w + x
        val m = magnitude.data[p]
        if (m <= 0f) continue
        val ang = atan2(gradient.dy.data[p], gradient.dx.data[p]).toDouble()
        // Quantise to 0 / 45 / 90 / 135 degrees.
        var a = Math.toDegrees(ang)
        if (a < 0) a += 180.0
        val (dx, dy) = when {
            a < 22.5 || a >= 157.5 -> 1 to 0
            a < 67.5 -> 1 to 1
            a < 112.5 -> 0 to 1
            else -> -1 to 1
        }
        val m1 = magnitude.get(x + dx, y + dy, 0, Border.ZERO)
        val m2 = magnitude.get(x - dx, y - dy, 0, Border.ZERO)
        if (m >= m1 && m >= m2) out[p] = m
    }
    return Img(w, h, 1, out)
}

/**
 * Double-threshold + connectivity: pixels above [high] are edges; pixels above [low] become edges
 * only if 8-connected (transitively) to one. Returns a binary mask.
 */
fun hysteresis(thinned: Img, low: Float, high: Float): Img {
    require(thinned.channels == 1) { "hysteresis expects a 1-channel image" }
    require(high > low) { "hysteresis needs high > low, got $low / $high" }
    val w = thinned.width
    val h = thinned.height
    val out = FloatArray(w * h)
    val stack = ArrayDeque<Int>()
    for (p in 0 until w * h) if (thinned.data[p] >= high) { out[p] = 1f; stack.addLast(p) }
    while (stack.isNotEmpty()) {
        val p = stack.removeLast()
        val x = p % w
        val y = p / w
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = x + dx
            val ny = y + dy
            if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
            val q = ny * w + nx
            if (out[q] == 0f && thinned.data[q] >= low) { out[q] = 1f; stack.addLast(q) }
        }
    }
    return Img(w, h, 1, out)
}

// ==============================================================================================
// Corners
// ==============================================================================================

/**
 * A detected interest point.
 * @property x sub-pixel-capable column
 * @property y sub-pixel-capable row
 * @property response detector strength (Harris response, Shi–Tomasi min-eigenvalue, FAST score, …)
 */
data class Corner(val x: Double, val y: Double, val response: Double) {
    /** As a [Vec2] so it drops straight into `polylineFrom` / the rest of the stdlib geometry. */
    fun toVec2(): Vec2 = Vec2(x.toFloat(), y.toFloat())
}

/**
 * The per-pixel structure tensor (second-moment matrix) of a scalar image, Gaussian-weighted over
 * a neighbourhood — the shared engine behind Harris, Shi–Tomasi and optical flow.
 *
 * @return `Triple(Ixx, Ixy, Iyy)`, each 1-channel, where `Ixx = G_sigma * (dx*dx)` etc.
 */
fun Img.structureTensor(sigma: Double = 1.5, gradientSigma: Double = 1.0): Triple<Img, Img, Img> {
    val grad = if (gradientSigma > 0.0) gaussianGradient(gradientSigma) else sobel()
    val ixx = (grad.dx * grad.dx).gaussianBlur(sigma)
    val ixy = (grad.dx * grad.dy).gaussianBlur(sigma)
    val iyy = (grad.dy * grad.dy).gaussianBlur(sigma)
    return Triple(ixx, ixy, iyy)
}

/**
 * Harris corner response image, `det(M) - k * trace(M)²` over the [structureTensor] `M`. Large and
 * positive at a corner, negative along an edge, near zero on flat ground.
 */
fun Img.harrisResponse(k: Double = 0.04, sigma: Double = 1.5, gradientSigma: Double = 1.0): Img {
    val (ixx, ixy, iyy) = structureTensor(sigma, gradientSigma)
    val kf = k.toFloat()
    val out = FloatArray(pixelCount)
    for (p in out.indices) {
        val a = ixx.data[p]; val b = ixy.data[p]; val c = iyy.data[p]
        val det = a * c - b * b
        val tr = a + c
        out[p] = det - kf * tr * tr
    }
    return Img(width, height, 1, out)
}

/**
 * Shi–Tomasi ("good features to track") response: the **smaller eigenvalue** of the structure
 * tensor. Unlike Harris it needs no magic `k`, and the response is directly interpretable — it is
 * the weakest intensity variation over any direction, so a large value means the point is
 * localisable in both axes. This is the response optical-flow trackers want.
 */
fun Img.shiTomasiResponse(sigma: Double = 1.5, gradientSigma: Double = 1.0): Img {
    val (ixx, ixy, iyy) = structureTensor(sigma, gradientSigma)
    val out = FloatArray(pixelCount)
    for (p in out.indices) {
        val a = ixx.data[p]; val b = ixy.data[p]; val c = iyy.data[p]
        val tr = a + c
        val disc = sqrt(max(0f, (a - c) * (a - c) + 4f * b * b))
        out[p] = (tr - disc) / 2f
    }
    return Img(width, height, 1, out)
}

/**
 * Peak extraction shared by the corner detectors: keep local maxima of [response] that clear
 * `threshold * max(response)`, sort by strength, and greedily drop any peak within [minDistance]
 * pixels of a stronger one.
 *
 * @param maxCount cap on returned corners (`<= 0` for no cap)
 * @param subPixel refine each peak by fitting a parabola to its 3-tap neighbourhood in x and y
 */
fun peakCorners(
    response: Img,
    threshold: Double = 0.01,
    minDistance: Int = 5,
    maxCount: Int = 200,
    subPixel: Boolean = true
): List<Corner> {
    require(response.channels == 1) { "peakCorners expects a 1-channel response image" }
    require(minDistance >= 1) { "minDistance must be >= 1, got $minDistance" }
    val w = response.width
    val h = response.height
    val mx = response.max()
    if (mx <= 0f) return emptyList()
    val cut = (threshold * mx).toFloat()
    val cand = ArrayList<Corner>()
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
        val v = response.data[y * w + x]
        if (v < cut) continue
        var isMax = true
        loop@ for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            if (response.data[(y + dy) * w + (x + dx)] > v) { isMax = false; break@loop }
        }
        if (!isMax) continue
        var fx = x.toDouble()
        var fy = y.toDouble()
        if (subPixel) {
            fx += parabolicOffset(response.data[y * w + x - 1], v, response.data[y * w + x + 1])
            fy += parabolicOffset(response.data[(y - 1) * w + x], v, response.data[(y + 1) * w + x])
        }
        cand.add(Corner(fx, fy, v.toDouble()))
    }
    cand.sortByDescending { it.response }
    val kept = ArrayList<Corner>()
    val md2 = (minDistance * minDistance).toDouble()
    for (c in cand) {
        if (maxCount > 0 && kept.size >= maxCount) break
        if (kept.any { val dx = it.x - c.x; val dy = it.y - c.y; dx * dx + dy * dy < md2 }) continue
        kept.add(c)
    }
    return kept
}

/** Sub-pixel peak offset from a 3-tap parabola fit; clamped to +-0.5 so a flat fit cannot run away. */
private fun parabolicOffset(a: Float, b: Float, c: Float): Double {
    val denom = (a - 2f * b + c).toDouble()
    if (abs(denom) < 1e-12) return 0.0
    return (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5)
}

/** Harris corners: [harrisResponse] followed by [peakCorners]. */
fun Img.harrisCorners(
    k: Double = 0.04,
    threshold: Double = 0.01,
    minDistance: Int = 5,
    maxCount: Int = 200,
    sigma: Double = 1.5
): List<Corner> = peakCorners(harrisResponse(k, sigma), threshold, minDistance, maxCount)

/** Shi–Tomasi corners: [shiTomasiResponse] followed by [peakCorners]. The default tracker seed. */
fun Img.shiTomasiCorners(
    threshold: Double = 0.01,
    minDistance: Int = 5,
    maxCount: Int = 200,
    sigma: Double = 1.5
): List<Corner> = peakCorners(shiTomasiResponse(sigma), threshold, minDistance, maxCount)

/** Alias for [shiTomasiCorners] under the name the OpenCV-literate will reach for first. */
fun Img.goodFeaturesToTrack(
    maxCount: Int = 200,
    threshold: Double = 0.01,
    minDistance: Int = 5
): List<Corner> = shiTomasiCorners(threshold, minDistance, maxCount)

/** The 16 offsets of the FAST Bresenham circle of radius 3, in order around the circle. */
private val FAST_CIRCLE: Array<IntArray> = arrayOf(
    intArrayOf(0, -3), intArrayOf(1, -3), intArrayOf(2, -2), intArrayOf(3, -1),
    intArrayOf(3, 0), intArrayOf(3, 1), intArrayOf(2, 2), intArrayOf(1, 3),
    intArrayOf(0, 3), intArrayOf(-1, 3), intArrayOf(-2, 2), intArrayOf(-3, 1),
    intArrayOf(-3, 0), intArrayOf(-3, -1), intArrayOf(-2, -2), intArrayOf(-1, -3)
)

/**
 * FAST corner detection (Rosten & Drummond): a pixel is a corner if at least [contiguous] of the
 * 16 pixels on a radius-3 circle around it are all brighter than `centre + t` or all darker than
 * `centre - t`. No gradients, no eigenvalues — just a wrap-around run-length test, which is why it
 * is the fastest usable corner detector.
 *
 * The [Corner.response] is the classic FAST score: the largest [threshold] at which the pixel would
 * still qualify, found by bisection, which gives non-maximum suppression something to rank by.
 *
 * @param threshold brightness difference, in sample units (0..1 image data → 0.05–0.15 is typical)
 * @param contiguous required arc length, 9 (FAST-9, the standard) through 16
 */
fun Img.fastCorners(
    threshold: Double = 0.08,
    contiguous: Int = 9,
    minDistance: Int = 5,
    maxCount: Int = 300
): List<Corner> {
    require(contiguous in 9..16) { "contiguous must be in 9..16, got $contiguous" }
    require(threshold > 0.0) { "threshold must be > 0, got $threshold" }
    val g = if (channels == 1) this else luma()
    val w = g.width
    val h = g.height
    val ring = FloatArray(16)
    val cand = ArrayList<Corner>()
    for (y in 3 until h - 3) for (x in 3 until w - 3) {
        val c = g.data[y * w + x]
        for (i in 0 until 16) {
            val o = FAST_CIRCLE[i]
            ring[i] = g.data[(y + o[1]) * w + (x + o[0])]
        }
        if (!fastQualifies(ring, c, threshold.toFloat(), contiguous)) continue
        // Score = largest t for which the test still passes (bisection, 12 steps is plenty for floats).
        var lo = threshold.toFloat()
        var hi = 1.5f
        repeat(12) {
            val mid = (lo + hi) / 2f
            if (fastQualifies(ring, c, mid, contiguous)) lo = mid else hi = mid
        }
        cand.add(Corner(x.toDouble(), y.toDouble(), lo.toDouble()))
    }
    cand.sortByDescending { it.response }
    val kept = ArrayList<Corner>()
    val md2 = (minDistance * minDistance).toDouble()
    for (cc in cand) {
        if (maxCount > 0 && kept.size >= maxCount) break
        if (kept.any { val dx = it.x - cc.x; val dy = it.y - cc.y; dx * dx + dy * dy < md2 }) continue
        kept.add(cc)
    }
    return kept
}

/** Wrap-around contiguous-run test on the 16-pixel FAST ring. */
private fun fastQualifies(ring: FloatArray, centre: Float, t: Float, need: Int): Boolean {
    var runBright = 0
    var runDark = 0
    // Walk twice around so a run spanning index 15→0 is counted.
    for (i in 0 until 32) {
        val v = ring[i and 15]
        runBright = if (v > centre + t) runBright + 1 else 0
        runDark = if (v < centre - t) runDark + 1 else 0
        if (runBright >= need || runDark >= need) return true
    }
    return false
}

// ==============================================================================================
// Hough transform
// ==============================================================================================

/**
 * A line in Hesse normal form: `x*cos(theta) + y*sin(theta) = rho`.
 * @property rho signed distance from the image origin (top-left) to the line, in pixels
 * @property theta line normal angle in radians, `[0, π)`
 * @property votes accumulator strength — how many edge pixels supported this line
 */
data class HoughLine(val rho: Double, val theta: Double, val votes: Int) {

    /** Clip the (infinite) line to the `w x h` image rectangle; `null` if it misses entirely. */
    fun clipTo(w: Int, h: Int): Pair<Vec2, Vec2>? {
        val ct = cos(theta)
        val st = sin(theta)
        val pts = ArrayList<Vec2>(4)
        fun add(x: Double, y: Double) {
            if (x >= -1e-6 && x <= w - 1 + 1e-6 && y >= -1e-6 && y <= h - 1 + 1e-6) {
                val v = Vec2(x.toFloat(), y.toFloat())
                if (pts.none { abs(it.x - v.x) < 1e-3f && abs(it.y - v.y) < 1e-3f }) pts.add(v)
            }
        }
        if (abs(st) > 1e-9) { add(0.0, rho / st); add((w - 1).toDouble(), (rho - (w - 1) * ct) / st) }
        if (abs(ct) > 1e-9) { add(rho / ct, 0.0); add((rho - (h - 1) * st) / ct, (h - 1).toDouble()) }
        return if (pts.size >= 2) pts[0] to pts[1] else null
    }
}

/**
 * Standard Hough line transform over a binary edge mask.
 *
 * Every non-zero pixel votes for every line through it; a real line in the image becomes a bright
 * peak in `(rho, theta)` accumulator space. The classic demonstration that a *global* structure
 * (a line) can be found from purely *local* evidence (edge pixels) with no grouping step.
 *
 * @param edges 1-channel mask; any sample > 0.5 votes
 * @param thetaSteps angular resolution of the accumulator over `[0, π)`
 * @param rhoStep radial bin width in pixels
 * @param threshold minimum votes for a peak; `<= 0` means `0.5 * max votes`
 * @param maxLines cap on returned lines
 * @param minSeparationTheta / [minSeparationRho] non-maximum suppression in accumulator space
 */
fun houghLines(
    edges: Img,
    thetaSteps: Int = 360,
    rhoStep: Double = 1.0,
    threshold: Int = 0,
    maxLines: Int = 20,
    minSeparationTheta: Double = 0.15,
    minSeparationRho: Double = 12.0
): List<HoughLine> {
    require(edges.channels == 1) { "houghLines expects a 1-channel edge mask" }
    require(thetaSteps >= 8) { "thetaSteps must be >= 8, got $thetaSteps" }
    require(rhoStep > 0.0) { "rhoStep must be > 0, got $rhoStep" }
    val w = edges.width
    val h = edges.height
    val rhoMax = sqrt((w * w + h * h).toDouble())
    val rhoBins = (2 * rhoMax / rhoStep).toInt() + 1
    val acc = IntArray(thetaSteps * rhoBins)
    val cosT = DoubleArray(thetaSteps)
    val sinT = DoubleArray(thetaSteps)
    for (t in 0 until thetaSteps) {
        val a = PI * t / thetaSteps
        cosT[t] = cos(a); sinT[t] = sin(a)
    }
    for (y in 0 until h) for (x in 0 until w) {
        if (edges.data[y * w + x] <= 0.5f) continue
        for (t in 0 until thetaSteps) {
            val rho = x * cosT[t] + y * sinT[t]
            val b = ((rho + rhoMax) / rhoStep).roundToInt()
            if (b in 0 until rhoBins) acc[t * rhoBins + b]++
        }
    }
    val peak = acc.max()
    if (peak == 0) return emptyList()
    val cut = if (threshold > 0) threshold else max(2, (peak * 0.5).toInt())
    val cand = ArrayList<HoughLine>()
    for (t in 0 until thetaSteps) for (b in 0 until rhoBins) {
        val v = acc[t * rhoBins + b]
        if (v < cut) continue
        // Local maximum in the accumulator (theta wraps, rho does not).
        var isMax = true
        loop@ for (dt in -1..1) for (db in -1..1) {
            if (dt == 0 && db == 0) continue
            val tt = Math.floorMod(t + dt, thetaSteps)
            val bb = b + db
            if (bb < 0 || bb >= rhoBins) continue
            if (acc[tt * rhoBins + bb] > v) { isMax = false; break@loop }
        }
        if (!isMax) continue
        cand.add(HoughLine(b * rhoStep - rhoMax, PI * t / thetaSteps, v))
    }
    cand.sortByDescending { it.votes }
    val kept = ArrayList<HoughLine>()
    for (c in cand) {
        if (kept.size >= maxLines) break
        if (kept.any { angularClose(it.theta, c.theta, minSeparationTheta) && abs(it.rho - c.rho) < minSeparationRho }) continue
        kept.add(c)
    }
    return kept
}

/** Angular proximity on the `[0, π)` circle (so 0.01 and π-0.01 are close). */
private fun angularClose(a: Double, b: Double, tol: Double): Boolean {
    var d = abs(a - b) % PI
    if (d > PI / 2) d = PI - d
    return d < tol
}

/**
 * A circle found by [houghCircles].
 * @property votes accumulator strength at the centre for this radius
 */
data class HoughCircle(val x: Double, val y: Double, val radius: Double, val votes: Int)

/**
 * Hough circle transform over a binary edge mask, brute force over the radius range. Each edge
 * pixel votes for the centres of all circles of each candidate radius passing through it.
 *
 * Cost is `O(edgePixels * radii * 360/angleStepDeg)`, so keep the radius range tight.
 */
fun houghCircles(
    edges: Img,
    minRadius: Int = 8,
    maxRadius: Int = 40,
    radiusStep: Int = 2,
    threshold: Double = 0.5,
    maxCircles: Int = 10,
    minSeparation: Double = 10.0,
    angleStepDeg: Double = 3.0
): List<HoughCircle> {
    require(edges.channels == 1) { "houghCircles expects a 1-channel edge mask" }
    require(minRadius >= 1 && maxRadius >= minRadius) { "need 1 <= minRadius <= maxRadius" }
    require(radiusStep >= 1) { "radiusStep must be >= 1" }
    val w = edges.width
    val h = edges.height
    val pts = ArrayList<Int>()
    for (p in 0 until w * h) if (edges.data[p] > 0.5f) pts.add(p)
    if (pts.isEmpty()) return emptyList()
    val steps = max(8, (360.0 / angleStepDeg).toInt())
    val cand = ArrayList<HoughCircle>()
    var r = minRadius
    while (r <= maxRadius) {
        val acc = IntArray(w * h)
        for (p in pts) {
            val px = p % w
            val py = p / w
            for (s in 0 until steps) {
                val a = 2.0 * PI * s / steps
                val cx = (px - r * cos(a)).roundToInt()
                val cy = (py - r * sin(a)).roundToInt()
                if (cx in 0 until w && cy in 0 until h) acc[cy * w + cx]++
            }
        }
        val expected = steps.toDouble()
        val cut = max(4, (threshold * expected).toInt())
        for (p in acc.indices) {
            if (acc[p] < cut) continue
            val x = p % w
            val y = p / w
            var isMax = true
            loop@ for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx; val ny = y + dy
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                if (acc[ny * w + nx] > acc[p]) { isMax = false; break@loop }
            }
            if (isMax) cand.add(HoughCircle(x.toDouble(), y.toDouble(), r.toDouble(), acc[p]))
        }
        r += radiusStep
    }
    cand.sortByDescending { it.votes }
    val kept = ArrayList<HoughCircle>()
    for (c in cand) {
        if (kept.size >= maxCircles) break
        if (kept.any {
                val dx = it.x - c.x; val dy = it.y - c.y
                sqrt(dx * dx + dy * dy) < minSeparation
            }) continue
        kept.add(c)
    }
    return kept
}

// ==============================================================================================
// Morphology
// ==============================================================================================

/** Neighbourhood shape for the morphological operators. */
enum class StructuringElement {
    /** A `(2r+1)²` square (Chebyshev ball) — separable, and the cheapest. */
    SQUARE,

    /** A discrete disc of radius `r` (Euclidean ball) — isotropic, no corner artefacts. */
    DISC,

    /** The 4-neighbour plus (Manhattan ball). */
    CROSS
}

/** Offsets of a structuring element of radius [radius]. */
private fun elementOffsets(shape: StructuringElement, radius: Int): Array<IntArray> {
    val out = ArrayList<IntArray>()
    for (dy in -radius..radius) for (dx in -radius..radius) {
        val keep = when (shape) {
            StructuringElement.SQUARE -> true
            StructuringElement.DISC -> dx * dx + dy * dy <= radius * radius
            StructuringElement.CROSS -> abs(dx) + abs(dy) <= radius
        }
        if (keep) out.add(intArrayOf(dx, dy))
    }
    return out.toTypedArray()
}

/**
 * Grayscale erosion: each pixel becomes the **minimum** over the structuring element. On a binary
 * mask this shrinks bright regions and deletes anything thinner than the element.
 */
fun Img.erode(radius: Int = 1, shape: StructuringElement = StructuringElement.SQUARE): Img =
    morph(radius, shape, dilate = false)

/**
 * Grayscale dilation: each pixel becomes the **maximum** over the structuring element. On a binary
 * mask this grows bright regions and bridges gaps narrower than the element.
 */
fun Img.dilate(radius: Int = 1, shape: StructuringElement = StructuringElement.SQUARE): Img =
    morph(radius, shape, dilate = true)

private fun Img.morph(radius: Int, shape: StructuringElement, dilate: Boolean): Img {
    require(radius >= 0) { "radius must be >= 0, got $radius" }
    if (radius == 0) return copy()
    val offs = elementOffsets(shape, radius)
    val out = FloatArray(data.size)
    for (y in 0 until height) for (x in 0 until width) for (c in 0 until channels) {
        var best = if (dilate) Float.NEGATIVE_INFINITY else Float.POSITIVE_INFINITY
        for (o in offs) {
            val v = get(x + o[0], y + o[1], c, Border.CLAMP)
            best = if (dilate) max(best, v) else min(best, v)
        }
        out[index(x, y, c)] = best
    }
    return Img(width, height, channels, out)
}

/** Erode then dilate — removes specks and thin protrusions, keeps the overall size of what remains. */
fun Img.open(radius: Int = 1, shape: StructuringElement = StructuringElement.SQUARE): Img =
    erode(radius, shape).dilate(radius, shape)

/** Dilate then erode — fills pinholes and hairline cracks, keeps the overall size. */
fun Img.close(radius: Int = 1, shape: StructuringElement = StructuringElement.SQUARE): Img =
    dilate(radius, shape).erode(radius, shape)

/** `dilate - erode`: a one-pixel-ish outline of every bright region. */
fun Img.morphologicalGradient(radius: Int = 1, shape: StructuringElement = StructuringElement.SQUARE): Img =
    dilate(radius, shape) - erode(radius, shape)

/** `image - open(image)`: bright detail smaller than the element (specks, text on a gradient). */
fun Img.topHat(radius: Int = 3, shape: StructuringElement = StructuringElement.SQUARE): Img =
    this - open(radius, shape)

/** `close(image) - image`: dark detail smaller than the element. */
fun Img.blackHat(radius: Int = 3, shape: StructuringElement = StructuringElement.SQUARE): Img =
    close(radius, shape) - this

// ==============================================================================================
// Connected components
// ==============================================================================================

/**
 * One labelled blob from [connectedComponents].
 *
 * @property label the value this blob carries in the label map (1-based; 0 is background)
 * @property area pixel count
 * @property minX/[minY]/[maxX]/[maxY] inclusive bounding box
 * @property centroidX/[centroidY] area centroid
 */
data class Component(
    val label: Int,
    val area: Int,
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
    val centroidX: Double,
    val centroidY: Double
) {
    val boundingWidth: Int get() = maxX - minX + 1
    val boundingHeight: Int get() = maxY - minY + 1

    /** Area over bounding-box area — 1.0 for a filled rectangle, ~π/4 for a disc. */
    val fillRatio: Double get() = area.toDouble() / (boundingWidth * boundingHeight)

    /** Centroid as a [Vec2]. */
    fun centroid(): Vec2 = Vec2(centroidX.toFloat(), centroidY.toFloat())
}

/**
 * Result of a connected-component labelling.
 * @property labels 1-channel image whose samples are the integer label of each pixel (0 = background)
 * @property components per-blob statistics, in label order
 */
class ComponentLabelling(val labels: Img, val components: List<Component>) {
    /** How many blobs were found (excluding background). */
    val count: Int get() = components.size

    /** A binary mask selecting only the pixels of [label]. */
    fun mask(label: Int): Img = labels.map { if (it.roundToInt() == label) 1f else 0f }

    /** The largest blob by area, or `null` if there are none. */
    fun largest(): Component? = components.maxByOrNull { it.area }

    /** Blobs with at least [minArea] pixels. */
    fun filterByArea(minArea: Int, maxArea: Int = Int.MAX_VALUE): List<Component> =
        components.filter { it.area in minArea..maxArea }

    override fun toString(): String = "ComponentLabelling($count components in ${labels.width}x${labels.height})"
}

/**
 * Two-pass connected-component labelling of a binary mask (samples > 0.5 are foreground), with
 * per-blob area, bounding box and centroid.
 *
 * @param connectivity 4 (edge-adjacent) or 8 (edge- or corner-adjacent)
 */
fun Img.connectedComponents(connectivity: Int = 8): ComponentLabelling {
    require(channels == 1) { "connectedComponents expects a 1-channel binary mask" }
    require(connectivity == 4 || connectivity == 8) { "connectivity must be 4 or 8, got $connectivity" }
    val w = width
    val h = height
    val lab = IntArray(w * h)
    val parent = ArrayList<Int>()
    parent.add(0) // label 0 = background

    fun find(a: Int): Int {
        var r = a
        while (parent[r] != r) r = parent[r]
        var c = a
        while (parent[c] != c) { val n = parent[c]; parent[c] = r; c = n }
        return r
    }

    fun union(a: Int, b: Int) {
        val ra = find(a); val rb = find(b)
        if (ra != rb) parent[max(ra, rb)] = min(ra, rb)
    }

    // Pass 1: provisional labels + equivalences from already-visited neighbours.
    val neigh = if (connectivity == 4) arrayOf(intArrayOf(-1, 0), intArrayOf(0, -1))
    else arrayOf(intArrayOf(-1, 0), intArrayOf(0, -1), intArrayOf(-1, -1), intArrayOf(1, -1))
    for (y in 0 until h) for (x in 0 until w) {
        if (data[y * w + x] <= 0.5f) continue
        var best = 0
        for (n in neigh) {
            val nx = x + n[0]; val ny = y + n[1]
            if (nx < 0 || ny < 0 || nx >= w) continue
            val l = lab[ny * w + nx]
            if (l != 0) best = if (best == 0) l else min(best, l)
        }
        if (best == 0) { best = parent.size; parent.add(best) }
        lab[y * w + x] = best
        for (n in neigh) {
            val nx = x + n[0]; val ny = y + n[1]
            if (nx < 0 || ny < 0 || nx >= w) continue
            val l = lab[ny * w + nx]
            if (l != 0) union(best, l)
        }
    }

    // Pass 2: resolve to compact 1..n labels and accumulate stats.
    val remap = HashMap<Int, Int>()
    val out = FloatArray(w * h)
    val areas = ArrayList<Int>()
    val minXs = ArrayList<Int>(); val minYs = ArrayList<Int>()
    val maxXs = ArrayList<Int>(); val maxYs = ArrayList<Int>()
    val sumXs = ArrayList<Double>(); val sumYs = ArrayList<Double>()
    for (y in 0 until h) for (x in 0 until w) {
        val l = lab[y * w + x]
        if (l == 0) continue
        val root = find(l)
        val id = remap.getOrPut(root) {
            areas.add(0); minXs.add(w); minYs.add(h); maxXs.add(-1); maxYs.add(-1)
            sumXs.add(0.0); sumYs.add(0.0)
            remap.size + 1
        }
        val i = id - 1
        out[y * w + x] = id.toFloat()
        areas[i] = areas[i] + 1
        if (x < minXs[i]) minXs[i] = x
        if (y < minYs[i]) minYs[i] = y
        if (x > maxXs[i]) maxXs[i] = x
        if (y > maxYs[i]) maxYs[i] = y
        sumXs[i] = sumXs[i] + x
        sumYs[i] = sumYs[i] + y
    }
    val comps = (0 until remap.size).map { i ->
        Component(
            label = i + 1,
            area = areas[i],
            minX = minXs[i], minY = minYs[i], maxX = maxXs[i], maxY = maxYs[i],
            centroidX = sumXs[i] / areas[i],
            centroidY = sumYs[i] / areas[i]
        )
    }
    return ComponentLabelling(Img(w, h, 1, out), comps)
}

/**
 * Euclidean distance transform of a binary mask: each foreground pixel's value becomes its distance
 * (in pixels) to the nearest background pixel.
 *
 * Uses Felzenszwalb & Huttenlocher's exact `O(n)` two-pass squared-distance algorithm (a lower
 * envelope of parabolas per row then per column) — exact Euclidean, not the chamfer approximation.
 */
fun Img.distanceTransform(): Img {
    require(channels == 1) { "distanceTransform expects a 1-channel binary mask" }
    val w = width
    val h = height
    val inf = 1e20
    val f = DoubleArray(w * h) { if (data[it] > 0.5f) inf else 0.0 }
    // columns
    val tmp = DoubleArray(max(w, h))
    val v = IntArray(max(w, h))
    val z = DoubleArray(max(w, h) + 1)
    fun edt1d(src: DoubleArray, n: Int, read: (Int) -> Double, write: (Int, Double) -> Unit) {
        for (i in 0 until n) src[i] = read(i)
        var k = 0
        v[0] = 0
        z[0] = -inf
        z[1] = inf
        for (q in 1 until n) {
            var s: Double
            while (true) {
                val p = v[k]
                s = ((src[q] + q * q) - (src[p] + p * p)) / (2.0 * q - 2.0 * p)
                if (s <= z[k]) k-- else break
            }
            k++
            v[k] = q
            z[k] = s
            z[k + 1] = inf
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val p = v[k]
            write(q, (q - p).toDouble() * (q - p) + src[p])
        }
    }
    val col = DoubleArray(max(w, h))
    for (x in 0 until w) edt1d(col, h, { f[it * w + x] }, { i, d -> f[i * w + x] = d })
    for (y in 0 until h) edt1d(col, w, { f[y * w + it] }, { i, d -> f[y * w + i] = d })
    return Img(w, h, 1, FloatArray(w * h) { sqrt(f[it]).toFloat() })
}

/**
 * Trace the outline of every blob in a binary mask as a closed pixel-boundary polygon
 * (Moore-neighbour tracing). Complements [connectedComponents]' statistics with actual geometry —
 * feed the result to `contoursToFLines` in `imaging_field.kt` to draw it.
 *
 * @param minPerimeter drop contours shorter than this many boundary pixels (noise suppression)
 * @return one closed point list per blob outer boundary, in image coordinates
 */
fun Img.traceContours(minPerimeter: Int = 8): List<List<Vec2>> {
    require(channels == 1) { "traceContours expects a 1-channel binary mask" }
    val w = width
    val h = height
    fun fg(x: Int, y: Int) = x in 0 until w && y in 0 until h && data[y * w + x] > 0.5f
    val visited = BooleanArray(w * h)
    val out = ArrayList<List<Vec2>>()
    // Clockwise Moore neighbourhood, starting west.
    val nx = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)
    val ny = intArrayOf(0, -1, -1, -1, 0, 1, 1, 1)
    for (y in 0 until h) for (x in 0 until w) {
        if (!fg(x, y) || visited[y * w + x]) continue
        if (fg(x - 1, y)) continue // not a left-edge boundary start
        val pts = ArrayList<Vec2>()
        var cx = x
        var cy = y
        // The start pixel was reached from the west (guaranteed background by the test above), so
        // pretend we arrived travelling east; the scan then begins just clockwise of the backtrack.
        var dir = 4
        val startX = x
        val startY = y
        var guard = 0
        val maxSteps = 8 * w * h
        while (guard++ < maxSteps) {
            visited[cy * w + cx] = true
            pts.add(Vec2(cx.toFloat(), cy.toFloat()))
            var found = false
            // Moore-neighbour sweep: clockwise, starting one step past the pixel we came from.
            for (k in 0 until 8) {
                val d = (dir + 5 + k) % 8
                val tx = cx + nx[d]
                val ty = cy + ny[d]
                if (fg(tx, ty)) {
                    cx = tx; cy = ty; dir = d; found = true; break
                }
            }
            if (!found) break // isolated pixel
            if (cx == startX && cy == startY && pts.size > 2) break
        }
        if (pts.size >= minPerimeter) out.add(pts)
    }
    return out
}
