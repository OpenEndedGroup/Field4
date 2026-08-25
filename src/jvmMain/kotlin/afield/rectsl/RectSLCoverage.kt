package afield.rectsl

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.operation.union.UnaryUnionOp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The **exact** coverage a soft raster is approximating, and the measurement of how far off it is.
 *
 * # Why the reference has to be exact
 *
 * A soft rasterizer is only useful if you know what it is wrong about. The claim a differentiable
 * renderer makes is "coverage is a smooth function of the parameters"; the claim it must not make is
 * "coverage is *correct*". Between those two sits a bias, and a bias you have not measured is a bias
 * your fit has silently absorbed into its answer.
 *
 * So this file computes the thing there is no argument about: the **area** of a pixel covered by the
 * scene, in closed form. A rect is a convex quadrilateral and a pixel is a convex box, so a single
 * rect's coverage is a convex-convex clip ([clipConvexToBox], Sutherland–Hodgman) followed by the
 * shoelace formula ([polygonArea]) — exact in the arithmetic, with no sampling anywhere. A *union* of
 * rects is not convex, so that case goes to JTS, which is an external implementation of the same
 * quantity and therefore an oracle rather than a second opinion from the same author.
 * `RectSLSoftRasterTest` cross-checks the two on the single-rect case where both apply.
 *
 * # The three biases, named
 *
 * The measurement in [measureCoverageBias] is designed around the fact that there are three
 * *different* reasons soft coverage departs from area coverage, and lumping them together produces a
 * number that means nothing:
 *
 * 1. **Obliquity.** Every kernel in [CoverageKernel] is a function of a single signed distance, so it
 *    is implicitly assuming the edge is axis-aligned. For an axis-aligned edge the exact area of a
 *    unit pixel on one side of it *is* `clamp(0.5 - d)`, so [CoverageKernel.LINEAR] at `beta` = one
 *    pixel has **zero** obliquity bias there — a fact worth checking, because if it does not come out
 *    at machine precision then either the kernel or the exact reference is wrong. As the edge rotates
 *    the true coverage curve becomes piecewise quadratic and the ramp cannot follow it.
 * 2. **Feature scale.** A rect thinner than the band never lets coverage saturate. As
 *    width/`beta` → 0 the error tends to the whole of the feature's coverage, which is the regime
 *    where a soft raster is not an approximation of the picture at all.
 * 3. **Union seams.** `min` of two signed distances is the union's distance *outside* the union and
 *    an under-estimate *inside* it, so where two rects abut the field reads about zero and coverage
 *    collapses towards 0.5 across a region that is entirely covered. This one is a property of the
 *    SDF, not of the kernel, and it is the reason [CoverageComposite] has two members.
 *
 * Each is reported separately, over a population that is counted so that an empty population cannot
 * read as a pass: see [CoverageBiasRow.partialPixels] and [CoverageBiasRow.edgePixels].
 *
 * # Scope of the reference
 *
 * Area coverage is defined for **opaque** geometry. A rect at alpha 0.4 has no area interpretation —
 * the exact answer to "what fraction of this pixel is covered" is still 1 if the rect fills it — so
 * [exactRaster] treats every visible rect as opaque and [measureCoverageBias] refuses a scene whose
 * rects are translucent rather than quietly comparing two different quantities.
 */

/**
 * A rect as the coverage reference sees it: a centre, half-extents that already include scale, and a
 * rotation — i.e. exactly what an [AdShapeFrame] holds, with the tape taken off.
 */
data class CoverageRect(
    /** World-space centre x. */
    val centreX: Double,
    /** World-space centre y. */
    val centreY: Double,
    /** Half-width, including accumulated scale. */
    val halfX: Double,
    /** Half-height, including accumulated scale. */
    val halfY: Double,
    /** Rotation in radians about the centre. */
    val rotation: Double,
    /** Accumulated alpha in `0..1`, carried so a caller can refuse a translucent scene. */
    val alpha: Double = 1.0
) {
    /**
     * The four corners as `x0, y0, x1, y1, x2, y2, x3, y3`, counter-clockwise in an axis-aligned
     * frame and therefore counter-clockwise after any rotation.
     */
    fun corners(): DoubleArray {
        val c = cos(rotation)
        val s = sin(rotation)
        val out = DoubleArray(8)
        val signs = arrayOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0)
        signs.forEachIndexed { index, (sx, sy) ->
            val lx = sx * halfX
            val ly = sy * halfY
            out[index * 2] = centreX + c * lx - s * ly
            out[index * 2 + 1] = centreY + s * lx + c * ly
        }
        return out
    }

    /**
     * The exact signed distance from `(x, y)` to this rect in plain arithmetic — positive outside,
     * negative inside.
     *
     * The untracked twin of [AdShapeFrame.distanceAt], and deliberately the *only* one: a test pins
     * the two bit-for-bit over thousands of points, which turns what would be a duplicated formula
     * into a checked pair. Nothing here samples or approximates.
     */
    fun signedDistance(x: Double, y: Double): Double {
        val qx = x - centreX
        val qy = y - centreY
        val c = cos(rotation)
        val s = sin(rotation)
        val localX = c * qx + s * qy
        val localY = -s * qx + c * qy
        val dx = abs(localX) - halfX
        val dy = abs(localY) - halfY
        return hypot(max(dx, 0.0), max(dy, 0.0)) + min(max(dx, dy), 0.0)
    }

    /**
     * The exact fraction of the box `(x0, y0, x1, y1)` this rect covers, by convex clipping.
     *
     * Exact in the arithmetic sense: no sampling, no supersampling, no tolerance. A degenerate box
     * reports 0 rather than dividing by zero.
     */
    fun exactCoverage(box: DoubleArray): Double {
        val area = (box[2] - box[0]) * (box[3] - box[1])
        if (!(area > 0.0)) return 0.0
        return polygonArea(clipConvexToBox(corners(), box[0], box[1], box[2], box[3])) / area
    }
}

/** This frame's shape as the coverage reference sees it, with the tape taken off. */
fun AdShapeFrame.toCoverageRect(): CoverageRect = CoverageRect(
    centreX = centreX.value,
    centreY = centreY.value,
    halfX = halfX.value,
    halfY = halfY.value,
    rotation = rotation.value,
    alpha = alpha.value
)

/**
 * Clips a convex polygon to an axis-aligned box by Sutherland–Hodgman, returning the clipped ring as
 * flat `x, y` pairs — empty when nothing survives.
 *
 * Sutherland–Hodgman is exact for a *convex* clip region and can only misbehave on a concave subject;
 * both a rect and a pixel are convex, so this is the closed form rather than an approximation of one.
 * The four half-planes are applied in turn, which is why the intermediate ring is rebuilt four times
 * instead of being solved as one system.
 */
fun clipConvexToBox(polygon: DoubleArray, x0: Double, y0: Double, x1: Double, y1: Double): DoubleArray {
    require(polygon.size % 2 == 0) { "a polygon is flat x,y pairs; got ${polygon.size} numbers" }
    var ring = polygon
    // inside(p) for each edge, and the parameter of the crossing — written as four passes of one
    // shape rather than four special cases.
    val edges = arrayOf(
        { x: Double, _: Double -> x - x0 },
        { x: Double, _: Double -> x1 - x },
        { _: Double, y: Double -> y - y0 },
        { _: Double, y: Double -> y1 - y }
    )
    for (edge in edges) {
        if (ring.isEmpty()) return DoubleArray(0)
        val out = ArrayList<Double>(ring.size + 4)
        val count = ring.size / 2
        for (i in 0 until count) {
            val ax = ring[i * 2]
            val ay = ring[i * 2 + 1]
            val j = (i + 1) % count
            val bx = ring[j * 2]
            val by = ring[j * 2 + 1]
            val da = edge(ax, ay)
            val db = edge(bx, by)
            if (da >= 0.0) {
                out.add(ax)
                out.add(ay)
            }
            if ((da >= 0.0) != (db >= 0.0)) {
                val t = da / (da - db)
                out.add(ax + t * (bx - ax))
                out.add(ay + t * (by - ay))
            }
        }
        ring = out.toDoubleArray()
    }
    return ring
}

/** The unsigned area of a flat `x, y` ring by the shoelace formula; fewer than three points is 0. */
fun polygonArea(polygon: DoubleArray): Double {
    val count = polygon.size / 2
    if (count < 3) return 0.0
    var twice = 0.0
    for (i in 0 until count) {
        val j = (i + 1) % count
        twice += polygon[i * 2] * polygon[j * 2 + 1] - polygon[j * 2] * polygon[i * 2 + 1]
    }
    return abs(twice) * 0.5
}

/**
 * The exact fraction of the box `(x0, y0, x1, y1)` covered by the **union** of [rects], via JTS.
 *
 * A union of rects is not convex, so [clipConvexToBox] does not apply and this is not a place to
 * invent a boolean-op implementation. JTS is an external, independently-tested implementation of the
 * same quantity, which is what makes it worth the dependency here: on the single-rect case the two
 * paths overlap and a test asserts they agree, so the JTS answer is not taken on trust either.
 */
fun exactUnionCoverage(rects: List<CoverageRect>, box: DoubleArray): Double {
    val area = (box[2] - box[0]) * (box[3] - box[1])
    if (!(area > 0.0)) return 0.0
    if (rects.isEmpty()) return 0.0
    if (rects.size == 1) return rects[0].exactCoverage(box)
    val factory = GeometryFactory()
    // Cheap rejection first: JTS is the expensive path and most pixels touch no rect at all.
    val touching = rects.filter { rect ->
        val corners = clipConvexToBox(rect.corners(), box[0], box[1], box[2], box[3])
        corners.isNotEmpty() || rect.signedDistance(0.5 * (box[0] + box[2]), 0.5 * (box[1] + box[3])) < 0.0
    }
    if (touching.isEmpty()) return 0.0
    val polygons: List<Geometry> = touching.map { rect ->
        val c = rect.corners()
        factory.createPolygon(
            arrayOf(
                Coordinate(c[0], c[1]), Coordinate(c[2], c[3]), Coordinate(c[4], c[5]),
                Coordinate(c[6], c[7]), Coordinate(c[0], c[1])
            )
        )
    }
    val pixel = factory.createPolygon(
        arrayOf(
            Coordinate(box[0], box[1]), Coordinate(box[2], box[1]), Coordinate(box[2], box[3]),
            Coordinate(box[0], box[3]), Coordinate(box[0], box[1])
        )
    )
    val union = UnaryUnionOp.union(polygons) ?: return 0.0
    return union.intersection(pixel).area / area
}

/**
 * The exact area-coverage raster of [field] at [time], row-major, to compare a soft raster against.
 *
 * Every visible rect is treated as **opaque**, for the reason in the file KDoc: area coverage has no
 * meaning for a translucent rect. [measureCoverageBias] refuses a translucent scene outright; this
 * function will compute an answer for one, and that answer is a different quantity from the soft
 * raster's, so do not use it on one.
 */
fun exactRaster(field: SceneAdField, spec: SoftRasterSpec, time: Double): DoubleArray {
    val rects = field.frameAt(time).visible.map { it.toCoverageRect() }
    val out = DoubleArray(spec.pixelCount)
    for (j in 0 until spec.height) {
        for (i in 0 until spec.width) {
            out[j * spec.width + i] = exactUnionCoverage(rects, spec.pixelBox(i, j))
        }
    }
    return out
}

/**
 * One row of the bias table: what a given kernel, band width and geometry cost in coverage error.
 *
 * Every population this summarises is *counted* in the row. That is not padding. The failure mode a
 * bias measurement is most prone to is measuring the easy case — a sampler that never lands on a
 * partial pixel reports a beautiful zero — so [partialPixels] and [edgePixels] are here to be
 * asserted on, and a row where they are zero is evidence of nothing.
 */
data class CoverageBiasRow(
    /** Which kernel produced the soft raster. */
    val kernel: CoverageKernel,
    /** How per-rect coverage was combined. */
    val composite: CoverageComposite,
    /** The band width, in pixel widths — the unit the finding is stated in. */
    val betaInPixels: Double,
    /** How many pixels the raster had. */
    val pixels: Int,
    /** Pixels whose **exact** coverage is strictly between 0 and 1: the genuinely partial ones. */
    val partialPixels: Int,
    /** Partial pixels further than the corner guard from every rect corner. */
    val edgePixels: Int,
    /** Partial pixels *within* the corner guard of some rect corner. */
    val cornerPixels: Int,
    /** The worst `|soft - exact|` anywhere in the image. */
    val worstBias: Double,
    /** The worst `|soft - exact|` over [partialPixels]. */
    val worstPartialBias: Double,
    /** The worst `|soft - exact|` over [edgePixels] — obliquity bias, with corners excluded. */
    val worstEdgeBias: Double,
    /**
     * The worst `|soft - exact|` over [cornerPixels] — its own error source, and the measurement
     * says the larger one.
     *
     * Two edges meet inside one pixel at a corner and no function of a *single* signed distance can be
     * right there, so this is not obliquity bias with a worse constant: it is a different failure. It
     * was not obvious in advance that it would dominate, which is the reason it has its own column
     * rather than being folded into [worstBias].
     */
    val worstCornerBias: Double,
    /** The mean `|soft - exact|` over [partialPixels]. */
    val meanPartialBias: Double,
    /**
     * The fraction of pixels whose coverage has a slope of at least [RECTSL_REACH_THRESHOLD] — how
     * much of *this image* can push a fit at all.
     *
     * The number that makes the bias table a *trade-off* rather than a ranking: the kernel with the
     * least bias also has the least reach, so a fit whose parameters start far from the answer needs
     * the biased one.
     */
    val gradientReach: Double,
    /**
     * How far from a surface, in pixel widths, coverage still has a slope of at least
     * [RECTSL_REACH_THRESHOLD] — the scene-independent form of [gradientReach].
     *
     * Found by scanning rather than solved in closed form, so that a change to a kernel cannot leave
     * a stale analytic expression behind. Reported because "the sigmoid reaches further" is only
     * useful as a number: at one pixel of band it is about eight times further, not infinitely.
     */
    val reachInPixels: Double
) {
    /** A single line, for a log or a docs table. */
    override fun toString(): String =
        ("%-10s %-9s beta=%.2fpx worst=%.3e edge=%.3e corner=%.3e mean=%.3e " +
            "reach=%.3f/%.2fpx (%d partial, %d edge, %d corner)")
            .format(
                kernel, composite, betaInPixels, worstBias, worstEdgeBias, worstCornerBias,
                meanPartialBias, gradientReach, reachInPixels, partialPixels, edgePixels, cornerPixels
            )
}

/** Coverage slope below which a pixel is treated as handing a fit nothing. See [CoverageBiasRow.gradientReach]. */
const val RECTSL_REACH_THRESHOLD: Double = 1e-6

/**
 * Measures [field]'s soft raster against exact area coverage at [time], and reports the three biases.
 *
 * @param cornerGuard how far, in **pixel widths**, a pixel must be from every rect corner to count
 *        towards [CoverageBiasRow.worstEdgeBias]. Corners are their own error source — two edges meet
 *        inside one pixel and no function of a single distance can be right there — so isolating the
 *        obliquity bias means excluding them. Default 2, i.e. twice the pixel pitch.
 * @throws RectLangException when any visible rect is translucent, because then the exact reference
 *         and the soft raster are not measuring the same quantity. Refusing beats returning a number
 *         that looks like a bias and is not one.
 */
fun measureCoverageBias(
    field: SceneAdField,
    spec: SoftRasterSpec,
    time: Double,
    cornerGuard: Double = 2.0
): CoverageBiasRow {
    val frame = field.frameAt(time)
    val rects = frame.visible.map { it.toCoverageRect() }
    val translucent = rects.filter { it.alpha < 1.0 }
    if (translucent.isNotEmpty()) {
        throw RectLangException(
            "${translucent.size} of ${rects.size} visible rect(s) are translucent, and area coverage " +
                "is only defined for opaque geometry — the exact reference and the soft raster would " +
                "be measuring different quantities. Set the alphas to 1 (or 0) to measure a bias."
        )
    }
    val soft = softRaster(field, spec, time)
    val guard = cornerGuard * spec.pixelWidth
    val cornerXs = ArrayList<Double>()
    val cornerYs = ArrayList<Double>()
    for (rect in rects) {
        val c = rect.corners()
        for (k in 0 until 4) {
            cornerXs.add(c[k * 2])
            cornerYs.add(c[k * 2 + 1])
        }
    }

    var worst = 0.0
    var worstPartial = 0.0
    var worstEdge = 0.0
    var worstCorner = 0.0
    var partialSum = 0.0
    var partial = 0
    var edge = 0
    var corner = 0
    var reaching = 0
    for (j in 0 until spec.height) {
        for (i in 0 until spec.width) {
            val k = j * spec.width + i
            val exact = exactUnionCoverage(rects, spec.pixelBox(i, j))
            val bias = abs(soft[k] - exact)
            if (bias > worst) worst = bias
            val x = spec.centreX(i)
            val y = spec.centreY(j)
            val distance = rects.minOfOrNull { it.signedDistance(x, y) } ?: RECTSL_INVISIBLE_DISTANCE
            if (abs(spec.kernel.slope(distance, spec.beta)) >= RECTSL_REACH_THRESHOLD) reaching++
            if (exact <= 0.0 || exact >= 1.0) continue
            partial++
            partialSum += bias
            if (bias > worstPartial) worstPartial = bias
            var nearCorner = false
            for (c in cornerXs.indices) {
                if (hypot(x - cornerXs[c], y - cornerYs[c]) < guard) {
                    nearCorner = true
                    break
                }
            }
            if (nearCorner) {
                corner++
                if (bias > worstCorner) worstCorner = bias
            } else {
                edge++
                if (bias > worstEdge) worstEdge = bias
            }
        }
    }
    return CoverageBiasRow(
        kernel = spec.kernel,
        composite = spec.composite,
        betaInPixels = spec.betaInPixels,
        pixels = spec.pixelCount,
        partialPixels = partial,
        edgePixels = edge,
        cornerPixels = corner,
        worstBias = worst,
        worstPartialBias = worstPartial,
        worstEdgeBias = worstEdge,
        worstCornerBias = worstCorner,
        meanPartialBias = if (partial == 0) 0.0 else partialSum / partial,
        gradientReach = reaching.toDouble() / spec.pixelCount,
        reachInPixels = spec.kernel.reachInPixels(spec.beta, spec.pixelWidth)
    )
}

/**
 * How far from a surface, in units of [pixelWidth], this kernel's coverage still has a slope of at
 * least [threshold] — the scene-independent reach.
 *
 * Found by walking outwards and stopping, rather than by inverting the slope analytically, so that a
 * change to [CoverageKernel.coverage] cannot leave a stale closed form behind here. The scan is
 * coarse-then-fine because the sigmoid's reach is tens of pixels while the ramp's is half of one.
 */
fun CoverageKernel.reachInPixels(
    beta: Double,
    pixelWidth: Double,
    threshold: Double = RECTSL_REACH_THRESHOLD
): Double {
    require(beta > 0.0 && pixelWidth > 0.0) { "beta and pixelWidth must be positive" }
    var lo = 0.0
    var hi = beta
    // Bracket first: double outwards until the slope has fallen below the threshold. Bounded, so a
    // kernel that never falls off reports the bound rather than looping.
    var guard = 0
    while (abs(slope(hi, beta)) >= threshold && guard++ < 60) {
        lo = hi
        hi *= 2.0
    }
    repeat(60) {
        val mid = 0.5 * (lo + hi)
        if (abs(slope(mid, beta)) >= threshold) lo = mid else hi = mid
    }
    return lo / pixelWidth
}

/**
 * `d(coverage)/d(distance)` for this kernel, in closed form — the number
 * [CoverageBiasRow.gradientReach] thresholds.
 *
 * Exposed rather than hidden because it is the honest statement of what a kernel *costs*: the two
 * kernels with the least bias are exactly zero outside `beta/2`, and a pixel where this is zero
 * cannot move a fit no matter how wrong the picture is there. A test checks it against a central
 * difference of [CoverageKernel.coverage], since a slope transcribed by hand beside a value is the
 * classic place for the two to drift apart.
 */
fun CoverageKernel.slope(d: Double, beta: Double): Double = when (this) {
    CoverageKernel.LINEAR -> if (abs(d) < 0.5 * beta) -1.0 / beta else 0.0
    CoverageKernel.SMOOTHSTEP -> {
        val u = 0.5 - d / beta
        if (u <= 0.0 || u >= 1.0) 0.0 else -6.0 * u * (1.0 - u) / beta
    }
    CoverageKernel.SIGMOID -> {
        val c = coverage(d, beta)
        -4.0 * c * (1.0 - c) / beta
    }
}
