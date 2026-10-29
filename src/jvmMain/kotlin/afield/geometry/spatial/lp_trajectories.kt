@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.spatial

import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.graphics.render.thickness
import afield.math.linalg.Vec2
import afield.math.linalg.Vec4
import afield.math.linalg.vec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Linear-programming algorithms on a convex polygon, used as curve generators.
 *
 * A 2-D linear program is "maximise c·x over a convex polygon". Every classical method for it walks
 * from somewhere to the same optimal vertex, and each walks differently: the barrier method's central
 * path is a smooth arc from the analytic centre, the primal-dual method takes a few long Newton
 * strides, PDHG spirals in, the ellipsoid method shrinks a sequence of ellipses, the simplex method
 * hops along the boundary and cutting planes carve the region away. Here those walks are the product.
 * Every method returns its whole trajectory with per-step metadata ([LpSample]), and each result type
 * has a `toFLine` that draws it, with `toFLines` for a whole fan of them.
 *
 * The pure math is independent of Box and of FLine: an [LpPolygon] is a list of half-planes and the
 * methods are plain functions on it. Doubles throughout; [LpSample.position] is the Vec2 for drawing.
 * The visual reference for all of this is https://lpviz.net.
 */

/**
 * A strictly convex polygon, held both as its vertices and as the half-planes `nᵢ·x ≤ bᵢ` of its
 * edges, ready for the trajectory methods ([centralPath], [centralFan], [primalDual], [pdhg],
 * [ellipsoid], [simplexWalk], [cuttingPlanes]).
 *
 * The constructor accepts the vertices in either winding and normalises them to counter-clockwise.
 * A closing vertex that repeats the first, and consecutive duplicates, are dropped. It rejects,
 * with a message saying which, fewer than three distinct vertices, zero area, a reflex corner and
 * three collinear vertices: each would make an edge redundant or the interior empty, and the
 * barrier methods need a strictly feasible interior. Use [LpPolygon.of] to read the vertices of
 * an [FLine], [LpPolygon.regular] for a regular polygon, and [LpPolygon.hull] / [LpPolygon.hullOf]
 * for the convex hull of any point set or drawn line.
 *
 * Edge `i` runs from `vertices[i]` to `vertices[i + 1]`; its unit outward normal is
 * `(normalX[i], normalY[i])` and its bound `bound[i]`.
 */
class LpPolygon(points: List<Vec2>) {
    /** The vertices, counter-clockwise, without a closing repeat. */
    val vertices: List<Vec2>
    /** x component of each edge's unit outward normal. */
    val normalX: DoubleArray
    /** y component of each edge's unit outward normal. */
    val normalY: DoubleArray
    /** Each edge's bound: the polygon is `normalX[i]·x + normalY[i]·y ≤ bound[i]` for every `i`. */
    val bound: DoubleArray
    /** The mean of the vertices, strictly inside a strictly convex polygon; the default start. */
    val center: Vec2

    init {
        val distinct = ArrayList<Vec2>()
        for (p in points) {
            require(p.x.isFinite() && p.y.isFinite()) { "LpPolygon: vertex $p is not finite" }
            if (distinct.isEmpty() || distinct.last().distance(p) > 1e-6f) distinct.add(vec(p.x, p.y))
        }
        if (distinct.size > 1 && distinct.first().distance(distinct.last()) <= 1e-6f) distinct.removeAt(distinct.size - 1)
        require(distinct.size >= 3) { "LpPolygon needs at least 3 distinct vertices, got ${distinct.size}" }
        var area2 = 0.0
        for (i in distinct.indices) {
            val a = distinct[i]
            val b = distinct[(i + 1) % distinct.size]
            area2 += a.x.toDouble() * b.y - a.y.toDouble() * b.x
        }
        val scale = distinct.maxOf { max(abs(it.x), abs(it.y)).toDouble() }.coerceAtLeast(1.0)
        require(abs(area2) > 1e-9 * scale * scale) { "LpPolygon: the vertices enclose no area" }
        val ccw = if (area2 > 0) distinct else distinct.reversed()
        val n = ccw.size
        for (i in 0 until n) {
            val a = ccw[i]
            val b = ccw[(i + 1) % n]
            val c = ccw[(i + 2) % n]
            val cross = (b.x.toDouble() - a.x) * (c.y.toDouble() - b.y) - (b.y.toDouble() - a.y) * (c.x.toDouble() - b.x)
            val norm = hypot(b.x.toDouble() - a.x, b.y.toDouble() - a.y) * hypot(c.x.toDouble() - b.x, c.y.toDouble() - b.y)
            require(cross > -1e-9 * norm) { "LpPolygon: not convex — vertex ${(i + 1) % n} at ${ccw[(i + 1) % n]} is a reflex corner" }
            require(cross > 1e-9 * norm) { "LpPolygon: vertices ${i}, ${(i + 1) % n}, ${(i + 2) % n} are collinear; drop the middle one" }
        }
        vertices = ccw
        normalX = DoubleArray(n)
        normalY = DoubleArray(n)
        bound = DoubleArray(n)
        for (i in 0 until n) {
            val a = ccw[i]
            val b = ccw[(i + 1) % n]
            val ex = b.x.toDouble() - a.x
            val ey = b.y.toDouble() - a.y
            val len = hypot(ex, ey)
            normalX[i] = ey / len
            normalY[i] = -ex / len
            bound[i] = normalX[i] * a.x + normalY[i] * a.y
        }
        center = vec(ccw.sumOf { it.x.toDouble() } / n, ccw.sumOf { it.y.toDouble() } / n)
    }

    /** The number of edges, which is also the number of constraints and of vertices. */
    val edgeCount: Int get() = vertices.size

    /** How far `(x, y)` is inside edge [i]: positive inside, zero on the edge, negative outside. */
    fun slack(i: Int, x: Double, y: Double): Double = bound[i] - normalX[i] * x - normalY[i] * y

    /** The largest constraint violation at `(x, y)`, zero for any point inside or on the polygon. */
    fun violation(x: Double, y: Double): Double {
        var v = 0.0
        for (i in 0 until edgeCount) v = max(v, -slack(i, x, y))
        return v
    }

    /** True when `(x, y)` is strictly inside every edge. */
    fun containsStrictly(x: Double, y: Double): Boolean = (0 until edgeCount).all { slack(it, x, y) > 0.0 }

    /** The vertex that maximises `direction · x`: the answer every method here walks toward. */
    fun optimalVertex(direction: Vec2): Vec2 = vertices.maxBy { it.x.toDouble() * direction.x + it.y.toDouble() * direction.y }

    /** The polygon's outline as a closed [FLine]. */
    fun toFLine(color: Vec4 = vec(0.62, 0.70, 0.80, 0.9), thickness: Double = 1.4): FLine =
        closedPolyline(vertices, color, thickness)

    companion object {
        /**
         * The polygon whose vertices are the node positions of [line] — what you get from a drawn
         * or `fline { }`-built closed outline. Only the end point of each segment is read, so a
         * curved segment contributes its end vertex; flatten curves first if you want them.
         */
        fun of(line: FLine): LpPolygon = LpPolygon(line.nodes.map { vec(it.to.x, it.to.y) })

        /**
         * The convex hull of [points], as a polygon: any point set, in any order, with repeats,
         * interior points and collinear runs. Interior points are dropped, points on a hull edge
         * between two corners are dropped (they would be collinear vertices), and the hull is
         * counter-clockwise. Points closer than [mergeDistance] count as one.
         *
         * Andrew's monotone chain in doubles. It fails, with a message, when fewer than three
         * distinct points remain or every point lies on one line. Use [LpPolygon]'s own
         * constructor when the vertices are meant to be convex already and a reflex corner should
         * be an error rather than silently cut off.
         */
        fun hull(points: List<Vec2>, mergeDistance: Double = 1e-6): LpPolygon =
            LpPolygon(convexHull2(points, mergeDistance))

        /**
         * The convex hull of [line]'s node positions — a hand-drawn stroke, a non-convex outline
         * or a scatter drawn with `moveTo`s all give a polygon. As with [of], only the end point of
         * each segment is read, so flatten curved segments first if their bulge should count.
         */
        fun hullOf(line: FLine, mergeDistance: Double = 1e-6): LpPolygon =
            hull(line.nodes.indices.map { val p = line.endPointOf(it); vec(p.x, p.y) }, mergeDistance)

        /** A regular polygon with [sides] vertices on a circle of [radius] around `(cx, cy)`. */
        fun regular(sides: Int, radius: Double, cx: Double = 0.0, cy: Double = 0.0, rotation: Double = 0.0): LpPolygon {
            require(sides >= 3) { "LpPolygon.regular needs at least 3 sides, got $sides" }
            require(radius > 0.0) { "LpPolygon.regular needs a positive radius, got $radius" }
            return LpPolygon(List(sides) { k ->
                val a = rotation + 2.0 * PI * k / sides
                vec(cx + radius * cos(a), cy + radius * sin(a))
            })
        }
    }
}

/**
 * One step of a trajectory.
 */
data class LpSample(
    /** The iterate's x. */
    val x: Double,
    /** The iterate's y. */
    val y: Double,
    /** The step number, starting at 0. */
    val iteration: Int,
    /** `c·x` with `c` the normalised objective direction. */
    val objective: Double,
    /** The largest constraint violation, zero for a feasible iterate. */
    val violation: Double,
    /**
     * The method's own measure at this step: the barrier weight μ for [centralPath], the duality
     * measure μ for [primalDual], the dual norm for [pdhg], `√det` of the ellipse for [ellipsoid],
     * the remaining area for [cuttingPlanes], and `NaN` for [simplexWalk].
     */
    val scale: Double,
    /**
     * What kind of step this is: the method's name, or for [ellipsoid] which cut was taken —
     * `feasibility-cut`, `objective-cut`, or `final` at the last step.
     */
    val tag: String,
) {
    /** The iterate as a [Vec2], for drawing. */
    val position: Vec2 get() = vec(x, y)
}

/**
 * A method's whole walk, in order. [method] names the method that produced it.
 *
 * [solverSteps] counts the inner solver's work where there is one: the damped Newton steps taken
 * over the whole of [centralPath]. It is how a warm start ([LpContinuation]) shows what it saved;
 * methods without an inner solver leave it at 0.
 */
class LpTrajectory(val method: String, val samples: List<LpSample>, val solverSteps: Int = 0) {
    /** The final iterate. */
    val last: LpSample get() = samples.last()

    /** The iterates as [Vec2]s. */
    val positions: List<Vec2> get() = samples.map { it.position }

    /** The walk as an open polyline. */
    fun toFLine(color: Vec4 = vec(1.0, 0.72, 0.32, 0.95), thickness: Double = 1.6): FLine =
        FLine().also { f ->
            samples.forEachIndexed { i, s -> if (i == 0) f.moveTo(s.x, s.y) else f.lineTo(s.x, s.y) }
            f.color = Vec4(color)
            f.thickness = thickness
        }
}

/**
 * The ellipse `{ z : (z − c)ᵀ P⁻¹ (z − c) ≤ 1 }` with centre `(cx, cy)` and symmetric positive
 * definite shape matrix `P = [[pxx, pxy], [pxy, pyy]]`, as kept by [ellipsoid].
 */
data class LpEllipse(val cx: Double, val cy: Double, val pxx: Double, val pxy: Double, val pyy: Double) {
    /** `det P`; the ellipse's area is `π √det`. */
    val determinant: Double get() = pxx * pyy - pxy * pxy

    /** True when the shape matrix is symmetric positive definite, i.e. this is a real ellipse. */
    val isPositiveDefinite: Boolean get() = pxx > 0.0 && determinant > 0.0

    /** The boundary as a closed [FLine] of [segments] chords, through the Cholesky factor of P. */
    fun toFLine(segments: Int = 72, color: Vec4 = vec(0.55, 0.80, 1.0, 0.5), thickness: Double = 1.0): FLine {
        val l11 = sqrt(max(pxx, 0.0))
        val l21 = if (l11 > 0.0) pxy / l11 else 0.0
        val l22 = sqrt(max(pyy - l21 * l21, 0.0))
        return closedPolyline(List(segments) { k ->
            val a = 2.0 * PI * k / segments
            vec(cx + l11 * cos(a), cy + l21 * cos(a) + l22 * sin(a))
        }, color, thickness)
    }
}

/** [ellipsoid]'s result: the centres, and the ellipse kept at every step (same length). */
class LpEllipsoidTrace(val trajectory: LpTrajectory, val ellipses: List<LpEllipse>)

/** [pdhg]'s result: the primal walk, and the dual vector (one entry per edge) at every step. */
class LpPdhgTrace(val trajectory: LpTrajectory, val duals: List<DoubleArray>)

/** [cuttingPlanes]'s result: the centres, and the region still in play at every step. */
class LpCuttingPlaneTrace(val trajectory: LpTrajectory, val regions: List<List<Vec2>>)

/** The centres [cuttingPlanes] can cut through. */
enum class LpCenter {
    /** The analytic centre: the maximiser of `Σ ln slackᵢ` over the region's constraints, cuts included. */
    ANALYTIC,
    /** The centroid (centre of gravity) of the region that remains. */
    CENTROID,
    /**
     * The Chebyshev centre: the centre of the largest disc inside the region. Only the constraints
     * the disc touches decide it, so a cut that misses the disc does not move it.
     */
    CHEBYSHEV,
    /**
     * Vaidya's volumetric centre: the minimiser of `½ ln det ∇²(−Σ ln slackᵢ)` over the region's
     * constraints, cuts included. It weighs each constraint by its leverage, so a crowd of nearly
     * parallel cuts counts for less than it does at the analytic centre.
     */
    VOLUMETRIC,
}

private fun unit(direction: Vec2): DoubleArray {
    val len = hypot(direction.x.toDouble(), direction.y.toDouble())
    require(len > 0.0 && len.isFinite()) { "the objective direction must be a nonzero finite vector, got $direction" }
    return doubleArrayOf(direction.x / len, direction.y / len)
}

private fun LpPolygon.sample(c: DoubleArray, x: Double, y: Double, k: Int, scale: Double, tag: String) =
    LpSample(x, y, k, c[0] * x + c[1] * y, violation(x, y), scale, tag)

/*
Andrew's monotone chain. Sort by (x, y), drop near-duplicates, build the lower and upper chains,
popping every point that is not a strict left turn: collinear and interior points go. The turn
test is relative to the two edge lengths, a little looser than LpPolygon's own collinearity check,
so whatever survives here is accepted there.
*/
internal fun convexHull2(points: List<Vec2>, mergeDistance: Double): List<Vec2> {
    require(mergeDistance >= 0.0) { "mergeDistance must be non-negative, got $mergeDistance" }
    val sorted = points.map {
        require(it.x.isFinite() && it.y.isFinite()) { "LpPolygon.hull: point $it is not finite" }
        doubleArrayOf(it.x.toDouble(), it.y.toDouble())
    }.sortedWith(compareBy<DoubleArray>({ it[0] }, { it[1] }))
    val distinct = ArrayList<DoubleArray>()
    for (p in sorted) {
        // Sorted by x, so only the points within mergeDistance in x can be repeats of p.
        var j = distinct.size - 1
        var repeat = false
        while (j >= 0 && distinct[j][0] >= p[0] - mergeDistance) {
            if (hypot(distinct[j][0] - p[0], distinct[j][1] - p[1]) <= mergeDistance) { repeat = true; break }
            j--
        }
        if (!repeat) distinct.add(p)
    }
    require(distinct.size >= 3) { "LpPolygon.hull needs at least 3 distinct points, got ${distinct.size}" }
    fun leftTurn(o: DoubleArray, a: DoubleArray, b: DoubleArray): Boolean {
        val cross = (a[0] - o[0]) * (b[1] - a[1]) - (a[1] - o[1]) * (b[0] - a[0])
        val norm = hypot(a[0] - o[0], a[1] - o[1]) * hypot(b[0] - a[0], b[1] - a[1])
        return cross > 1e-8 * norm
    }
    fun chain(ps: List<DoubleArray>): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        for (p in ps) {
            while (out.size >= 2 && !leftTurn(out[out.size - 2], out[out.size - 1], p)) out.removeAt(out.size - 1)
            out.add(p)
        }
        return out
    }
    val lower = chain(distinct)
    val upper = chain(distinct.reversed())
    val hull = lower.dropLast(1) + upper.dropLast(1)
    require(hull.size >= 3) { "LpPolygon.hull: all ${distinct.size} distinct points lie on one line, so their hull has no area" }
    return hull.map { vec(it[0], it[1]) }
}

private fun closedPolyline(points: List<Vec2>, color: Vec4, thickness: Double): FLine = FLine().also { f ->
    points.forEachIndexed { i, p -> if (i == 0) f.moveTo(p) else f.lineTo(p) }
    f.lineTo(points[0])
    f.color = Vec4(color)
    f.thickness = thickness
}

/*
Damped Newton on  f(x) = c·x + mu Σ wᵢ ln(bᵢ − nᵢ·x)  over half-planes given as arrays. The
Hessian is 2×2 and negative definite inside, so the step is solved in closed form. Returns the
maximiser, starting from a strictly feasible (x0, y0), as (x, y, Newton steps taken).
*/
internal fun barrierNewton(
    nx: DoubleArray, ny: DoubleArray, b: DoubleArray, w: DoubleArray,
    cx: Double, cy: Double, mu: Double, x0: Double, y0: Double, maxIterations: Int = 100,
): DoubleArray {
    var x = x0
    var y = y0
    var steps = 0
    fun done() = doubleArrayOf(x, y, steps.toDouble())
    fun value(px: Double, py: Double): Double {
        var v = cx * px + cy * py
        for (i in nx.indices) {
            val s = b[i] - nx[i] * px - ny[i] * py
            if (s <= 0.0) return Double.NEGATIVE_INFINITY
            v += mu * w[i] * ln(s)
        }
        return v
    }
    repeat(maxIterations) {
        var gx = cx
        var gy = cy
        var hxx = 0.0
        var hxy = 0.0
        var hyy = 0.0
        for (i in nx.indices) {
            val s = b[i] - nx[i] * x - ny[i] * y
            val z = mu * w[i] / s
            gx -= z * nx[i]
            gy -= z * ny[i]
            val q = z / s
            hxx += q * nx[i] * nx[i]
            hxy += q * nx[i] * ny[i]
            hyy += q * ny[i] * ny[i]
        }
        val det = hxx * hyy - hxy * hxy
        check(det > 0.0 && det.isFinite()) { "degenerate barrier Hessian (det = $det)" }
        val dx = (hyy * gx - hxy * gy) / det
        val dy = (hxx * gy - hxy * gx) / det
        val decrement = gx * dx + gy * dy
        if (decrement < 1e-18) return done()
        steps++
        val before = value(x, y)
        var alpha = 1.0
        var accepted = false
        for (tries in 0 until 80) {
            val after = value(x + alpha * dx, y + alpha * dy)
            if (after.isFinite() && after >= before + 1e-4 * alpha * decrement) {
                x += alpha * dx
                y += alpha * dy
                accepted = true
                break
            }
            alpha *= 0.5
        }
        if (!accepted) return done()
    }
    return done()
}

/*
The Newton decrement gᵀH⁻¹g of the barrier objective at (x, y): how far, in Newton's own measure,
the point is from the maximiser for this μ. Infinite outside. Used to pick the better of two starts.
*/
internal fun barrierDecrement(
    nx: DoubleArray, ny: DoubleArray, b: DoubleArray, w: DoubleArray,
    cx: Double, cy: Double, mu: Double, x: Double, y: Double,
): Double {
    var gx = cx
    var gy = cy
    var hxx = 0.0
    var hxy = 0.0
    var hyy = 0.0
    for (i in nx.indices) {
        val s = b[i] - nx[i] * x - ny[i] * y
        if (s <= 0.0) return Double.POSITIVE_INFINITY
        val z = mu * w[i] / s
        gx -= z * nx[i]
        gy -= z * ny[i]
        val q = z / s
        hxx += q * nx[i] * nx[i]
        hxy += q * nx[i] * ny[i]
        hyy += q * ny[i] * ny[i]
    }
    val det = hxx * hyy - hxy * hxy
    if (!(det > 0.0) || !det.isFinite()) return Double.POSITIVE_INFINITY
    return (gx * (hyy * gx - hxy * gy) + gy * (hxx * gy - hxy * gx)) / det
}

internal fun LpPolygon.checkedWeights(weights: DoubleArray?): DoubleArray {
    val w = weights ?: DoubleArray(edgeCount) { 1.0 }
    require(w.size == edgeCount) { "need one weight per edge: ${w.size} weights for $edgeCount edges" }
    require(w.all { it.isFinite() && it > 0.0 }) { "edge weights must be positive and finite: ${w.toList()}" }
    return w
}

/**
 * The log-barrier central path: for μ falling geometrically from [muHigh] to [muLow], the
 * maximiser of `c·x + μ Σ wᵢ ln(slackᵢ)`, found by damped Newton warm-started from the previous
 * sample. At large μ it sits at the weighted analytic centre; as μ falls it bends toward the
 * optimal vertex for [direction], which it approaches as μ → 0.
 *
 * [weights] gives each edge's barrier weight (default all 1). Weights are an artistic control: a
 * heavier edge pushes the path away from itself, and weight `k` is exactly the same as repeating
 * that constraint `k` times, which is how redundant constraints change the path although they do
 * not change the polygon. Every sample is strictly feasible. [LpSample.scale] is μ.
 *
 * @param samples how many values of μ, at least 2.
 * @param start where Newton starts for the first μ; must be strictly inside.
 * @param warm a previous central path with the same [samples] (typically for a slightly different
 *   polygon, direction or weights). Newton for each μ then starts from that path's sample at the
 *   same μ instead of from this path's previous sample, whenever its Newton decrement says it is
 *   the closer of the two. The answer is the same — each sample is the unique barrier maximiser — but it takes
 *   fewer Newton steps when the change is small ([LpTrajectory.solverSteps]). [LpContinuation]
 *   threads this through an animation for you.
 */
fun LpPolygon.centralPath(
    direction: Vec2,
    weights: DoubleArray? = null,
    samples: Int = 160,
    muHigh: Double = 1000.0,
    muLow: Double = 1e-5,
    start: Vec2 = center,
    warm: LpTrajectory? = null,
): LpTrajectory {
    require(samples >= 2) { "centralPath needs at least 2 samples, got $samples" }
    require(muHigh > muLow && muLow > 0.0) { "centralPath needs muHigh > muLow > 0, got $muHigh and $muLow" }
    require(containsStrictly(start.x.toDouble(), start.y.toDouble())) { "centralPath must start strictly inside the polygon; $start is not" }
    val c = unit(direction)
    val w = checkedWeights(weights)
    val guide = warm?.samples?.takeIf { it.size == samples }
    var p = doubleArrayOf(start.x.toDouble(), start.y.toDouble())
    var steps = 0
    val out = List(samples) { k ->
        val mu = exp(ln(muHigh) + (ln(muLow) - ln(muHigh)) * k / (samples - 1))
        // Start from whichever of the previous path's sample and this path's last one Newton
        // judges closer, so a warm start is never worse than following the path itself.
        val hint = guide?.get(k)?.takeIf {
            barrierDecrement(normalX, normalY, bound, w, c[0], c[1], mu, it.x, it.y) <
                barrierDecrement(normalX, normalY, bound, w, c[0], c[1], mu, p[0], p[1])
        }
        p = if (hint != null) barrierNewton(normalX, normalY, bound, w, c[0], c[1], mu, hint.x, hint.y)
        else barrierNewton(normalX, normalY, bound, w, c[0], c[1], mu, p[0], p[1])
        steps += p[2].toInt()
        sample(c, p[0], p[1], k, mu, "central-path")
    }
    return LpTrajectory("central-path", out, steps)
}

/**
 * A warm-started [centralPath] for a polygon, direction or weights that move smoothly between
 * calls — an animation's frames. From the third call on it extrapolates each sample linearly from
 * the previous two paths (`2·last − before`, a secant predictor) and passes that as
 * [centralPath]'s `warm`; on the second call it passes the previous path itself. Newton then starts
 * next to the answer and needs fewer steps than following the path cold, for the same path.
 * [last] is the latest path and [lastSteps] its Newton step count; [reset] forgets the history,
 * which a jump should do. A prediction that does not fit (a different sample count, or a sample
 * Newton judges further off than the path's own previous sample) is ignored, so a jump costs a
 * cold start and nothing else.
 */
class LpContinuation {
    /** The path the previous call returned, or `null` before the first call and after [reset]. */
    var last: LpTrajectory? = null
        private set

    private var before: LpTrajectory? = null

    /** Newton steps the previous call took. */
    val lastSteps: Int get() = last?.solverSteps ?: 0

    /** [polygon]'s central path for [direction], warm-started from the previous calls'. */
    fun centralPath(
        polygon: LpPolygon,
        direction: Vec2,
        weights: DoubleArray? = null,
        samples: Int = 160,
        muHigh: Double = 1000.0,
        muLow: Double = 1e-5,
    ): LpTrajectory {
        val a = last
        val b = before
        val guess = if (a != null && b != null && a.samples.size == b.samples.size)
            LpTrajectory("prediction", a.samples.indices.map { k ->
                val s = a.samples[k]
                s.copy(x = 2.0 * s.x - b.samples[k].x, y = 2.0 * s.y - b.samples[k].y)
            })
        else a
        val path = polygon.centralPath(direction, weights, samples, muHigh, muLow, warm = guess)
        before = a
        last = path
        return path
    }

    /** Forget the previous paths, so the next call starts cold. */
    fun reset() {
        last = null
        before = null
    }
}

/**
 * A fan of central paths: the objective direction turned through a full circle in [angles] equal
 * steps, starting at [startAngle] radians, one [centralPath] each. Every path leaves the same
 * weighted analytic centre and ends at the vertex that is optimal for its direction, so the fan
 * is a sampling of the surface `x(θ, μ)`.
 */
fun LpPolygon.centralFan(
    angles: Int = 48,
    samples: Int = 120,
    weights: DoubleArray? = null,
    startAngle: Double = 0.0,
    muHigh: Double = 1000.0,
    muLow: Double = 1e-5,
): List<LpTrajectory> {
    require(angles > 0) { "centralFan needs at least one angle, got $angles" }
    return List(angles) { i ->
        val theta = startAngle + 2.0 * PI * i / angles
        centralPath(vec(cos(theta), sin(theta)), weights, samples, muHigh, muLow)
    }
}

/**
 * Mehrotra's predictor–corrector primal-dual interior-point method, recording every Newton
 * iterate. Primal `x` starts at [start] with slacks `s = b − Ax`, so it stays strictly feasible;
 * the duals start at 1 and the dual residual `Aᵀy − c` is driven to zero alongside the duality
 * measure μ = `sᵀy / m`. Each step is the 2×2 normal-equation solve, with separate primal and dual
 * step lengths at fraction [stepFraction] of the distance to the boundary. Stops when μ and the
 * dual residual are below [tolerance], or after [maxIterations]. [LpSample.scale] is μ.
 *
 * Expect a handful of long strides rather than a smooth arc: this is the same central path,
 * followed coarsely.
 */
fun LpPolygon.primalDual(
    direction: Vec2,
    maxIterations: Int = 60,
    tolerance: Double = 1e-9,
    stepFraction: Double = 0.95,
    start: Vec2 = center,
): LpTrajectory {
    require(maxIterations >= 0) { "maxIterations must be non-negative, got $maxIterations" }
    require(stepFraction > 0.0 && stepFraction < 1.0) { "stepFraction must be in (0, 1), got $stepFraction" }
    require(containsStrictly(start.x.toDouble(), start.y.toDouble())) { "primalDual must start strictly inside the polygon; $start is not" }
    val c = unit(direction)
    val m = edgeCount
    var x = start.x.toDouble()
    var y = start.y.toDouble()
    val s = DoubleArray(m) { slack(it, x, y) }
    val z = DoubleArray(m) { 1.0 }
    val out = ArrayList<LpSample>()
    fun mu() = (0 until m).sumOf { s[it] * z[it] } / m
    out.add(sample(c, x, y, 0, mu(), "primal-dual"))

    val ds = DoubleArray(m)
    val dz = DoubleArray(m)
    val rc = DoubleArray(m)
    // Solves the Newton system for complementarity right-hand side rc; rp is zero by construction.
    fun direction(rd0: Double, rd1: Double): DoubleArray {
        var hxx = 0.0
        var hxy = 0.0
        var hyy = 0.0
        var r0 = -rd0
        var r1 = -rd1
        for (i in 0 until m) {
            val q = z[i] / s[i]
            hxx += q * normalX[i] * normalX[i]
            hxy += q * normalX[i] * normalY[i]
            hyy += q * normalY[i] * normalY[i]
            r0 -= normalX[i] * rc[i] / s[i]
            r1 -= normalY[i] * rc[i] / s[i]
        }
        val det = hxx * hyy - hxy * hxy
        check(det > 0.0 && det.isFinite()) { "degenerate primal-dual normal matrix (det = $det)" }
        val dx = (hyy * r0 - hxy * r1) / det
        val dy = (hxx * r1 - hxy * r0) / det
        for (i in 0 until m) {
            ds[i] = -(normalX[i] * dx + normalY[i] * dy)
            dz[i] = (rc[i] - z[i] * ds[i]) / s[i]
        }
        return doubleArrayOf(dx, dy)
    }
    fun maxStep(v: DoubleArray, dv: DoubleArray): Double {
        var a = 1.0
        for (i in v.indices) if (dv[i] < 0.0) a = min(a, -v[i] / dv[i])
        return a
    }

    for (k in 1..maxIterations) {
        val rd0 = (0 until m).sumOf { normalX[it] * z[it] } - c[0]
        val rd1 = (0 until m).sumOf { normalY[it] * z[it] } - c[1]
        val muNow = mu()
        if (muNow < tolerance && hypot(rd0, rd1) < tolerance) break
        // Predictor: the affine-scaling direction, aiming straight at complementarity.
        for (i in 0 until m) rc[i] = -s[i] * z[i]
        direction(rd0, rd1)
        val ap = maxStep(s, ds)
        val ad = maxStep(z, dz)
        val muAff = (0 until m).sumOf { (s[it] + ap * ds[it]) * (z[it] + ad * dz[it]) } / m
        val sigma = (muAff / muNow).let { it * it * it }
        // Corrector: recentre by sigma·mu and cancel the predictor's second-order term.
        for (i in 0 until m) rc[i] = -s[i] * z[i] - ds[i] * dz[i] + sigma * muNow
        val d = direction(rd0, rd1)
        val alphaP = min(1.0, stepFraction * maxStep(s, ds))
        val alphaD = min(1.0, stepFraction * maxStep(z, dz))
        x += alphaP * d[0]
        y += alphaP * d[1]
        for (i in 0 until m) {
            s[i] = slack(i, x, y)
            z[i] += alphaD * dz[i]
        }
        out.add(sample(c, x, y, k, mu(), "primal-dual"))
    }
    return LpTrajectory("primal-dual", out)
}

/**
 * Primal-dual hybrid gradient (Chambolle–Pock) on the saddle problem
 * `max_x min_{y ≥ 0} c·x − yᵀ(Ax − b)`, keeping every primal and dual state, infeasible ones
 * included. Each step projects the dual `y ← max(0, y + σ(A x̄ − b))`, moves the primal
 * `x ← x + τ(c − Aᵀy)`, and extrapolates `x̄ = x + θ(x − x_prev)`.
 *
 * The primal walk is the interesting curve: it overshoots the polygon, is pulled back by the edge
 * duals and spirals into the optimal vertex. Larger [tau] and [sigma] give wider spirals; with
 * `τσ‖A‖² ≥ 1` it may oscillate or diverge, which is kept, not hidden. A non-finite iterate ends
 * the trace. [LpSample.scale] is the dual vector's Euclidean norm.
 */
fun LpPolygon.pdhg(
    direction: Vec2,
    iterations: Int = 1000,
    tau: Double = 0.15,
    sigma: Double = 0.15,
    theta: Double = 1.0,
    start: Vec2 = center,
): LpPdhgTrace {
    require(iterations >= 0) { "iterations must be non-negative, got $iterations" }
    require(tau > 0.0 && sigma > 0.0 && theta >= 0.0) { "pdhg needs tau > 0, sigma > 0, theta >= 0; got $tau, $sigma, $theta" }
    val c = unit(direction)
    val m = edgeCount
    val dual = DoubleArray(m)
    var x = start.x.toDouble()
    var y = start.y.toDouble()
    var bx = x
    var by = y
    fun dualNorm() = sqrt(dual.sumOf { it * it })
    val samples = arrayListOf(sample(c, x, y, 0, 0.0, "pdhg"))
    val duals = arrayListOf(dual.copyOf())
    for (k in 1..iterations) {
        for (i in 0 until m) dual[i] = max(0.0, dual[i] - sigma * slack(i, bx, by))
        var gx = c[0]
        var gy = c[1]
        for (i in 0 until m) {
            gx -= normalX[i] * dual[i]
            gy -= normalY[i] * dual[i]
        }
        val nx = x + tau * gx
        val ny = y + tau * gy
        if (!nx.isFinite() || !ny.isFinite()) break
        bx = nx + theta * (nx - x)
        by = ny + theta * (ny - y)
        x = nx
        y = ny
        samples.add(sample(c, x, y, k, dualNorm(), "pdhg"))
        duals.add(dual.copyOf())
    }
    return LpPdhgTrace(LpTrajectory("pdhg", samples), duals)
}

/**
 * The central-cut ellipsoid method, returning every centre and every ellipse. It starts from a
 * disc of [initialRadius] (default: the polygon's diameter) around [start]. At an infeasible
 * centre it cuts on the most violated edge; at a feasible one it cuts on the objective, keeping the
 * half where `c·x` is at least its current value. Each central cut in two dimensions moves the
 * centre by `−Pg / (3√(gᵀPg))` and replaces `P` by `4/3 · (P − 2/3 · (Pg)(Pg)ᵀ / gᵀPg)`, which
 * multiplies the area by `√(16/27) ≈ 0.77` every step. [LpSample.tag] is `"feasibility-cut"` or
 * `"objective-cut"` for the cut taken at that centre (`"final"` for the last), and
 * [LpSample.scale] is `√det P`.
 *
 * This is an exploratory trajectory, not a high-accuracy solver: its centres are often outside
 * the polygon, and the best feasible centre, not the last, is the method's answer.
 */
fun LpPolygon.ellipsoid(
    direction: Vec2,
    iterations: Int = 80,
    initialRadius: Double? = null,
    start: Vec2 = center,
): LpEllipsoidTrace {
    require(iterations >= 0) { "iterations must be non-negative, got $iterations" }
    val c = unit(direction)
    val radius = initialRadius ?: vertices.maxOf { a -> vertices.maxOf { b -> a.distance(b).toDouble() } }
    require(radius > 0.0 && radius.isFinite()) { "initialRadius must be positive, got $radius" }
    var x = start.x.toDouble()
    var y = start.y.toDouble()
    var pxx = radius * radius
    var pxy = 0.0
    var pyy = radius * radius
    val samples = ArrayList<LpSample>()
    val ellipses = ArrayList<LpEllipse>()
    for (k in 0..iterations) {
        val det = pxx * pyy - pxy * pxy
        ellipses.add(LpEllipse(x, y, pxx, pxy, pyy))
        var worst = -1
        var worstViolation = 0.0
        for (i in 0 until edgeCount) {
            val v = -slack(i, x, y)
            if (v > worstViolation) {
                worstViolation = v
                worst = i
            }
        }
        val tag = if (k == iterations) "final" else if (worst >= 0) "feasibility-cut" else "objective-cut"
        samples.add(sample(c, x, y, k, sqrt(max(det, 0.0)), tag))
        if (k == iterations) break
        val gx = if (worst >= 0) normalX[worst] else -c[0]
        val gy = if (worst >= 0) normalY[worst] else -c[1]
        val ux = pxx * gx + pxy * gy
        val uy = pxy * gx + pyy * gy
        val gpg = gx * ux + gy * uy
        if (gpg <= 1e-280 || !gpg.isFinite()) break
        val norm = sqrt(gpg)
        x -= ux / (3.0 * norm)
        y -= uy / (3.0 * norm)
        val factor = 4.0 / 3.0
        pxx = factor * (pxx - (2.0 / 3.0) * ux * ux / gpg)
        pxy = factor * (pxy - (2.0 / 3.0) * ux * uy / gpg)
        pyy = factor * (pyy - (2.0 / 3.0) * uy * uy / gpg)
    }
    return LpEllipsoidTrace(LpTrajectory("ellipsoid", samples), ellipses)
}

/**
 * The simplex method in two dimensions: a walk along the boundary from [startVertex], moving to
 * whichever neighbouring vertex improves `c·x` more, until neither does. It ends at the optimal
 * vertex, having visited at most half the polygon. [LpSample.scale] is `NaN`.
 */
fun LpPolygon.simplexWalk(direction: Vec2, startVertex: Int = 0): LpTrajectory {
    require(startVertex in vertices.indices) { "startVertex must be in 0 until $edgeCount, got $startVertex" }
    val c = unit(direction)
    val n = edgeCount
    fun value(i: Int) = c[0] * vertices[i].x + c[1] * vertices[i].y
    val out = ArrayList<LpSample>()
    var i = startVertex
    for (step in 0..n) {
        out.add(sample(c, vertices[i].x.toDouble(), vertices[i].y.toDouble(), step, Double.NaN, "simplex"))
        val next = listOf((i + n - 1) % n, (i + 1) % n).maxBy { value(it) }
        if (value(next) > value(i) + 1e-12) i = next else break
    }
    return LpTrajectory("simplex", out)
}

/** The part of convex [region] with `nx·x + ny·y ≤ b` (one Sutherland–Hodgman pass). */
private fun clipConvex(region: List<DoubleArray>, nx: Double, ny: Double, b: Double): List<DoubleArray> {
    val out = ArrayList<DoubleArray>()
    for (i in region.indices) {
        val p = region[i]
        val q = region[(i + 1) % region.size]
        val sp = b - nx * p[0] - ny * p[1]
        val sq = b - nx * q[0] - ny * q[1]
        if (sp >= 0.0) out.add(p)
        if ((sp >= 0.0) != (sq >= 0.0)) {
            val t = sp / (sp - sq)
            out.add(doubleArrayOf(p[0] + t * (q[0] - p[0]), p[1] + t * (q[1] - p[1])))
        }
    }
    return out
}

/*
The Chebyshev centre of { x : nᵢ·x ≤ bᵢ } with unit normals: maximise r subject to nᵢ·x + r ≤ bᵢ.
That is a three-variable LP whose bounded optimum has three tight constraints, so for the few
dozen constraints a cutting-plane run accumulates it is enough to solve each triple's 3×3 system
(Cramer's rule) and keep the feasible solution with the largest r. Returns (x, y, r).
*/
internal fun chebyshevCenter(nx: DoubleArray, ny: DoubleArray, b: DoubleArray): DoubleArray {
    val m = nx.size
    var best: DoubleArray? = null
    for (i in 0 until m) for (j in i + 1 until m) for (k in j + 1 until m) {
        val det = nx[i] * (ny[j] - ny[k]) - ny[i] * (nx[j] - nx[k]) + (nx[j] * ny[k] - nx[k] * ny[j])
        if (abs(det) < 1e-12) continue
        val dx = b[i] * (ny[j] - ny[k]) - ny[i] * (b[j] - b[k]) + (b[j] * ny[k] - b[k] * ny[j])
        val dy = nx[i] * (b[j] - b[k]) - b[i] * (nx[j] - nx[k]) + (nx[j] * b[k] - nx[k] * b[j])
        val dr = nx[i] * (ny[j] * b[k] - ny[k] * b[j]) - ny[i] * (nx[j] * b[k] - nx[k] * b[j]) + b[i] * (nx[j] * ny[k] - nx[k] * ny[j])
        val x = dx / det
        val y = dy / det
        val r = dr / det
        if (r <= 0.0 || (best != null && r <= best[2])) continue
        val tolerance = 1e-9 * (1.0 + abs(r))
        if ((0 until m).all { nx[it] * x + ny[it] * y + r <= b[it] + tolerance }) best = doubleArrayOf(x, y, r)
    }
    return checkNotNull(best) { "chebyshevCenter: the region has no interior" }
}

/*
Vaidya's volumetric centre: minimise V(x) = ½ ln det H(x), H = Σ aᵢaᵢᵀ / sᵢ², over the interior.
∇V = Σ σᵢ aᵢ / sᵢ with leverages σᵢ = aᵢᵀH⁻¹aᵢ / sᵢ²; the Newton step uses Vaidya's
approximation Q = Σ σᵢ aᵢaᵢᵀ / sᵢ² of the Hessian (Q ≼ ∇²V ≼ 5Q), with backtracking on V, from
a strictly feasible start (the analytic centre).
*/
internal fun volumetricCenter(nx: DoubleArray, ny: DoubleArray, b: DoubleArray, x0: Double, y0: Double, maxIterations: Int = 100): DoubleArray {
    var x = x0
    var y = y0
    fun hessian(px: Double, py: Double): DoubleArray? {
        var hxx = 0.0
        var hxy = 0.0
        var hyy = 0.0
        for (i in nx.indices) {
            val s = b[i] - nx[i] * px - ny[i] * py
            if (s <= 0.0) return null
            val q = 1.0 / (s * s)
            hxx += q * nx[i] * nx[i]
            hxy += q * nx[i] * ny[i]
            hyy += q * ny[i] * ny[i]
        }
        return doubleArrayOf(hxx, hxy, hyy)
    }
    fun value(px: Double, py: Double): Double {
        val h = hessian(px, py) ?: return Double.POSITIVE_INFINITY
        return 0.5 * ln(h[0] * h[2] - h[1] * h[1])
    }
    repeat(maxIterations) {
        val h = hessian(x, y) ?: return doubleArrayOf(x, y)
        val det = h[0] * h[2] - h[1] * h[1]
        var gx = 0.0
        var gy = 0.0
        var qxx = 0.0
        var qxy = 0.0
        var qyy = 0.0
        for (i in nx.indices) {
            val s = b[i] - nx[i] * x - ny[i] * y
            val leverage = (h[2] * nx[i] * nx[i] - 2.0 * h[1] * nx[i] * ny[i] + h[0] * ny[i] * ny[i]) / det / (s * s)
            gx += leverage * nx[i] / s
            gy += leverage * ny[i] / s
            val q = leverage / (s * s)
            qxx += q * nx[i] * nx[i]
            qxy += q * nx[i] * ny[i]
            qyy += q * ny[i] * ny[i]
        }
        val qdet = qxx * qyy - qxy * qxy
        if (!(qdet > 0.0) || !qdet.isFinite()) return doubleArrayOf(x, y)
        val dx = -(qyy * gx - qxy * gy) / qdet
        val dy = -(qxx * gy - qxy * gx) / qdet
        val decrement = -(gx * dx + gy * dy)
        if (decrement < 1e-18) return doubleArrayOf(x, y)
        val before = value(x, y)
        var accepted = false
        var alpha = 1.0
        for (tries in 0 until 80) {
            val after = value(x + alpha * dx, y + alpha * dy)
            if (after.isFinite() && after <= before - 1e-4 * alpha * decrement) {
                x += alpha * dx
                y += alpha * dy
                accepted = true
                break
            }
            alpha *= 0.5
        }
        if (!accepted) return doubleArrayOf(x, y)
    }
    return doubleArrayOf(x, y)
}

private fun areaAndCentroid(region: List<DoubleArray>): DoubleArray {
    var a2 = 0.0
    var cx = 0.0
    var cy = 0.0
    for (i in region.indices) {
        val p = region[i]
        val q = region[(i + 1) % region.size]
        val cr = p[0] * q[1] - q[0] * p[1]
        a2 += cr
        cx += (p[0] + q[0]) * cr
        cy += (p[1] + q[1]) * cr
    }
    if (abs(a2) < 1e-300) return doubleArrayOf(0.0, region.sumOf { it[0] } / region.size, region.sumOf { it[1] } / region.size)
    return doubleArrayOf(a2 / 2.0, cx / (3.0 * a2), cy / (3.0 * a2))
}

/**
 * A cutting-plane walk: find a centre of the region still in play, cut the region with the
 * objective (`c·x ≥ c·centre`), and repeat. With [LpCenter.ANALYTIC] the centre is the analytic
 * centre of every constraint so far, cuts included, so earlier cuts keep pushing on the path just
 * as extra constraints push a central path; with [LpCenter.CENTROID] it is the centre of gravity,
 * which removes at least `1 − (2/3)²` of the area each step; with [LpCenter.CHEBYSHEV] it is the
 * centre of the largest inscribed disc, and with [LpCenter.VOLUMETRIC] Vaidya's volumetric centre
 * of every constraint so far. The trajectory approaches the optimal
 * vertex from inside; it stops when the remaining area falls below [minArea] or after
 * [iterations]. [LpSample.scale] is the remaining area, and [LpCuttingPlaneTrace.regions] holds
 * the region at every step.
 */
fun LpPolygon.cuttingPlanes(
    direction: Vec2,
    iterations: Int = 40,
    center: LpCenter = LpCenter.ANALYTIC,
    minArea: Double = 1e-10,
): LpCuttingPlaneTrace {
    require(iterations >= 0) { "iterations must be non-negative, got $iterations" }
    val c = unit(direction)
    var region = vertices.map { doubleArrayOf(it.x.toDouble(), it.y.toDouble()) }
    val nx = normalX.toMutableList()
    val ny = normalY.toMutableList()
    val b = bound.toMutableList()
    val samples = ArrayList<LpSample>()
    val regions = ArrayList<List<Vec2>>()
    for (k in 0..iterations) {
        val ac = areaAndCentroid(region)
        val area = abs(ac[0])
        val p = when (center) {
            LpCenter.CENTROID -> doubleArrayOf(ac[1], ac[2])
            LpCenter.ANALYTIC, LpCenter.VOLUMETRIC -> {
                // The region's vertex mean is strictly inside it, so Newton can start there.
                val sx = region.sumOf { it[0] } / region.size
                val sy = region.sumOf { it[1] } / region.size
                val ax = nx.toDoubleArray()
                val ay = ny.toDoubleArray()
                val bounds = b.toDoubleArray()
                val analytic = barrierNewton(ax, ay, bounds, DoubleArray(ax.size) { 1.0 }, 0.0, 0.0, 1.0, sx, sy)
                if (center == LpCenter.ANALYTIC) analytic else volumetricCenter(ax, ay, bounds, analytic[0], analytic[1])
            }
            LpCenter.CHEBYSHEV -> chebyshevCenter(nx.toDoubleArray(), ny.toDoubleArray(), b.toDoubleArray())
        }
        samples.add(sample(c, p[0], p[1], k, area, "cutting-plane"))
        regions.add(region.map { vec(it[0], it[1]) })
        if (k == iterations || area < minArea) break
        // Keep c·x ≥ c·p, i.e. (−c)·x ≤ −c·p.
        val cut = -(c[0] * p[0] + c[1] * p[1])
        val next = clipConvex(region, -c[0], -c[1], cut)
        if (next.size < 3) break
        region = next
        nx.add(-c[0])
        ny.add(-c[1])
        b.add(cut)
    }
    return LpCuttingPlaneTrace(LpTrajectory("cutting-plane-${center.name.lowercase()}", samples), regions)
}

/** Every path of a fan as an [FLine], coloured round a hue circle so neighbouring angles read apart. */
fun List<LpTrajectory>.toFLines(alpha: Double = 0.8, thickness: Double = 1.1): List<FLine> =
    mapIndexed { i, t ->
        val h = 2.0 * PI * i / size
        t.toFLine(vec(0.6 + 0.4 * cos(h), 0.6 + 0.4 * cos(h - 2.1), 0.6 + 0.4 * cos(h + 2.1), alpha), thickness)
    }
