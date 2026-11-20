package afield.geometry.mesh

import afield.geometry.path.FLine
import afield.geometry.path.ScalarField2D
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A 2-D signed distance field sampled on a square grid, with the global-consistency checks and
 * repairs of "Refining and Repairing Signed Distance Data With Global Consistency"
 * (arXiv 2605.01919).
 *
 * The observation is that an *exact* signed distance sample is a circle: centred on the sample,
 * radius `|d|`, it touches the surface and contains no point of it. A CSG `min`/`max` or a smooth
 * `min` gives values that are only bounds (a pseudo-SDF): their circles are too small inside a
 * union, or too large near a fillet. Treating "touches" and "contains nothing" as hard constraints
 * against the surface the samples themselves imply (their zero crossings) is enough to
 *
 * - measure how far a grid is from being a distance field ([consistency]),
 * - repair it ([repaired]): every value becomes the largest circle that contains no surface point,
 * - refine it to a finer grid without the original function ([upsampled]).
 *
 * Negative is inside. Sample `(i, j)` sits at `(x0 + i * h, y0 + j * h)` and is stored at `j * nx + i`.
 *
 * ```kotlin
 * val pseudo = sdfGrid2D(xMin = -1.5, yMin = -1.5, xMax = 1.5, yMax = 1.5, n = 96) { x, y ->
 *     min(hypot(x - 0.4, y) - 0.6, hypot(x + 0.4, y) - 0.6) // a union: a bound inside
 * }
 * println(pseudo.consistency())
 * val fixed = pseudo.repaired()
 * println(fixed.consistency())
 * ```
 *
 * The surface is only as good as its crossings: they are linear interpolations along grid edges, so
 * a repaired value is exact to the *sampled* zero set, which is within about `h / 2` of the true one
 * near sharp corners and much closer on smooth boundaries. The sign of every sample is trusted.
 *
 * @property values the samples, `nx * ny` of them.
 * @property h the grid spacing, the same on both axes.
 */
class SdfGrid2D(
    val values: DoubleArray,
    val nx: Int,
    val ny: Int,
    val x0: Double,
    val y0: Double,
    val h: Double,
) {
    init {
        require(nx >= 2 && ny >= 2) { "an SdfGrid2D needs at least 2 x 2 samples, got $nx x $ny" }
        require(values.size == nx * ny) { "expected ${nx * ny} values, got ${values.size}" }
    }

    /** The x coordinate of column [i]. */
    fun x(i: Int) = x0 + i * h

    /** The y coordinate of row [j]. */
    fun y(j: Int) = y0 + j * h

    /** The sample at column [i], row [j]. */
    operator fun get(i: Int, j: Int) = values[j * nx + i]

    /** The grid read back bilinearly at `(x, y)`, clamped to the grid's extent. */
    fun bilinear(x: Double, y: Double): Double {
        val fx = ((x - x0) / h).coerceIn(0.0, nx - 1.0)
        val fy = ((y - y0) / h).coerceIn(0.0, ny - 1.0)
        val i = min(floor(fx).toInt(), nx - 2)
        val j = min(floor(fy).toInt(), ny - 2)
        val u = fx - i
        val v = fy - j
        val a = this[i, j] * (1 - u) + this[i + 1, j] * u
        val b = this[i, j + 1] * (1 - u) + this[i + 1, j + 1] * u
        return a * (1 - v) + b * v
    }

    /**
     * The surface these samples imply: one point per grid edge whose ends differ in sign, placed by
     * linear interpolation, plus every sample that is exactly zero. Flat `[x0, y0, x1, y1, ...]`.
     */
    fun surfacePoints(): DoubleArray {
        val out = ArrayList<Double>()
        fun edge(ax: Double, ay: Double, a: Double, bx: Double, by: Double, b: Double) {
            if ((a < 0) == (b < 0) || a == 0.0 || b == 0.0) return
            val t = a / (a - b)
            out += ax + (bx - ax) * t
            out += ay + (by - ay) * t
        }
        for (j in 0 until ny) for (i in 0 until nx) {
            val v = this[i, j]
            if (v == 0.0) {
                out += x(i)
                out += y(j)
            }
            if (i + 1 < nx) edge(x(i), y(j), v, x(i + 1), y(j), this[i + 1, j])
            if (j + 1 < ny) edge(x(i), y(j), v, x(i), y(j + 1), this[i, j + 1])
        }
        return out.toDoubleArray()
    }

    /**
     * How far each sample is from the two hard constraints, judged against [surfacePoints]: its circle
     * may not contain a surface point ([SdfConsistency.maxOvershoot]) and must reach one
     * ([SdfConsistency.maxUndershoot]). An exact SDF scores close to zero on both; how close is set by
     * the crossings' interpolation error.
     *
     * @param tolerance violations smaller than this, in world units, are not counted as violating.
     *   The default is a tenth of a cell.
     */
    fun consistency(tolerance: Double = 0.1 * h): SdfConsistency {
        val nearest = nearestSurfaceDistances()
        var over = 0.0
        var under = 0.0
        var sumAbs = 0.0
        var violating = 0
        for (k in values.indices) {
            val r = abs(values[k])
            val gap = nearest[k] - r // > 0: circle too small; < 0: circle swallows a surface point
            if (gap < 0) over = max(over, -gap) else under = max(under, gap)
            sumAbs += abs(gap)
            if (abs(gap) > tolerance) violating++
        }
        return SdfConsistency(over, under, sumAbs / values.size, violating, values.size)
    }

    /**
     * The repair: every sample becomes, with its sign kept, the radius of the largest circle around it
     * that contains no surface point, which is the distance to the nearest of [surfacePoints]. The
     * zero set is unchanged; values away from it are now distances to it.
     *
     * A grid with no sign change has no surface to measure against and is returned unchanged.
     */
    fun repaired(): SdfGrid2D {
        val nearest = nearestSurfaceDistances()
        if (nearest.all { it.isInfinite() }) return this
        val out = DoubleArray(values.size) { k ->
            val v = values[k]
            if (v < 0) -nearest[k] else nearest[k]
        }
        return SdfGrid2D(out, nx, ny, x0, y0, h)
    }

    /**
     * A grid [factor] times finer over the same extent, filled from the circle constraints alone: no
     * access to the function that made these samples.
     *
     * For a fine point `p`, every coarse circle containing it says it lies on that circle's side and at
     * least `|d_j| - |p - x_j|` from the surface (the lower bound). The surface itself is known at the
     * coarse crossings and at the touch points `x_j - d_j * grad_j` of samples near it, so the distance
     * to the nearest of those is an upper bound. The fine value is the upper bound, raised to the lower
     * one where they disagree, with the sign of the circle `p` sits in, or the bilinear sign if none.
     *
     * Meant for a grid that already is a distance field (sample an exact SDF coarsely, or [repaired]
     * first); a pseudo-SDF's circles are not trustworthy lower bounds.
     */
    fun upsampled(factor: Int = 4): SdfGrid2D {
        require(factor >= 1) { "factor must be at least 1, got $factor" }
        val fnx = (nx - 1) * factor + 1
        val fny = (ny - 1) * factor + 1
        val fh = h / factor

        val surface = ArrayList<Double>()
        surface.addAll(surfacePoints().toList())
        for (j in 1 until ny - 1) for (i in 1 until nx - 1) {
            val d = this[i, j]
            if (abs(d) > 2 * h) continue // far from the surface the central difference meets the medial axis
            val gx = (this[i + 1, j] - this[i - 1, j]) / (2 * h)
            val gy = (this[i, j + 1] - this[i, j - 1]) / (2 * h)
            val g = hypot(gx, gy)
            if (g < 0.5) continue
            surface += x(i) - d * gx / g
            surface += y(j) - d * gy / g
        }
        val buckets = PointBuckets2D(surface.toDoubleArray(), x0, y0, h, nx, ny)

        val out = DoubleArray(fnx * fny)
        val reach = ceil(values.maxOf { abs(it) } / h).toInt() + 1
        for (fj in 0 until fny) for (fi in 0 until fnx) {
            val px = x0 + fi * fh
            val py = y0 + fj * fh
            var lower = 0.0
            var side = 0.0
            // Coarse circles that could contain p: only samples within `reach` cells.
            val ci = (fi / factor)
            val cj = (fj / factor)
            for (j in max(0, cj - reach)..min(ny - 1, cj + reach + 1)) {
                for (i in max(0, ci - reach)..min(nx - 1, ci + reach + 1)) {
                    val d = this[i, j]
                    val slack = abs(d) - hypot(px - x(i), py - y(j))
                    if (slack > lower) {
                        lower = slack
                        side = d
                    }
                }
            }
            val upper = buckets.nearest(px, py)
            val magnitude = if (upper.isInfinite()) lower else max(upper, lower)
            val sign = if (side != 0.0) side else bilinear(px, py)
            out[fj * fnx + fi] = if (sign < 0) -magnitude else magnitude
        }
        return SdfGrid2D(out, fnx, fny, x0, y0, fh)
    }

    /** The same grid's samples read back bilinearly on a grid [factor] times finer, for comparison. */
    fun bilinearUpsampled(factor: Int = 4): SdfGrid2D {
        val fnx = (nx - 1) * factor + 1
        val fny = (ny - 1) * factor + 1
        val fh = h / factor
        val out = DoubleArray(fnx * fny) { k -> bilinear(x0 + (k % fnx) * fh, y0 + (k / fnx) * fh) }
        return SdfGrid2D(out, fnx, fny, x0, y0, fh)
    }

    /**
     * Sphere-traces this grid (read [bilinear]ly) from `(ox, oy)` along `(dx, dy)`: step by the field's
     * value until it drops below [epsilon] or [maxDistance] is passed. A pseudo-SDF that underestimates
     * takes more steps than it needs; one that overestimates steps through the surface.
     */
    fun sphereTrace(
        ox: Double,
        oy: Double,
        dx: Double,
        dy: Double,
        maxSteps: Int = 256,
        epsilon: Double = 1e-3,
        maxDistance: Double = 10.0,
    ): SphereTrace {
        val len = hypot(dx, dy)
        val ux = dx / len
        val uy = dy / len
        var t = 0.0
        for (step in 1..maxSteps) {
            val d = bilinear(ox + ux * t, oy + uy * t)
            if (d < epsilon) return SphereTrace(step, ox + ux * t, oy + uy * t, hit = true)
            t += d
            if (t > maxDistance) return SphereTrace(step, ox + ux * t, oy + uy * t, hit = false)
        }
        return SphereTrace(maxSteps, ox + ux * t, oy + uy * t, hit = false)
    }

    /** Max and mean `|value - truth(x, y)|` over every sample: the error against a known distance. */
    fun errorAgainst(truth: ScalarField2D): SdfError {
        var mx = 0.0
        var sum = 0.0
        for (j in 0 until ny) for (i in 0 until nx) {
            val e = abs(this[i, j] - truth(x(i), y(j)))
            mx = max(mx, e)
            sum += e
        }
        return SdfError(mx, sum / values.size)
    }

    /** The distance from each sample to the nearest of [surfacePoints], `+Infinity` if there are none. */
    fun nearestSurfaceDistances(): DoubleArray {
        val buckets = PointBuckets2D(surfacePoints(), x0, y0, h, nx, ny)
        return DoubleArray(values.size) { k -> buckets.nearest(x(k % nx), y(k / nx)) }
    }

    override fun toString() = "SdfGrid2D(${nx}x$ny, h = %.4g)".format(h)
}

/**
 * How far an [SdfGrid2D] is from satisfying the circle constraints, from [SdfGrid2D.consistency].
 *
 * @property maxOvershoot the most any sample's circle reaches past the nearest surface point.
 * @property maxUndershoot the most any sample's circle falls short of the nearest surface point.
 * @property meanViolation the mean of `| |d| - distance to the surface |` over all samples.
 * @property violating how many samples miss by more than the tolerance.
 */
data class SdfConsistency(
    val maxOvershoot: Double,
    val maxUndershoot: Double,
    val meanViolation: Double,
    val violating: Int,
    val samples: Int,
) {
    override fun toString() =
        "overshoot max %.4f, undershoot max %.4f, mean %.5f, %d of %d samples violate"
            .format(maxOvershoot, maxUndershoot, meanViolation, violating, samples)
}

/**
 * One ray from [SdfGrid2D.sphereTrace].
 *
 * @property steps field evaluations taken.
 * @property x where the ray stopped.
 * @property y where the ray stopped.
 * @property hit whether it stopped on the surface rather than running out of steps or distance.
 */
data class SphereTrace(val steps: Int, val x: Double, val y: Double, val hit: Boolean)

/** Max and mean absolute error of a grid against a true distance, from [SdfGrid2D.errorAgainst]. */
data class SdfError(val max: Double, val mean: Double) {
    override fun toString() = "max %.4f, mean %.5f".format(max, mean)
}

/**
 * Samples [field] on a square-celled grid covering `[xMin, xMax] x [yMin, yMax]` with [n] samples along
 * the longer side.
 */
fun sdfGrid2D(xMin: Double, yMin: Double, xMax: Double, yMax: Double, n: Int, field: ScalarField2D): SdfGrid2D {
    require(n >= 2) { "n must be at least 2, got $n" }
    val h = max(xMax - xMin, yMax - yMin) / (n - 1)
    val nx = ((xMax - xMin) / h).toInt() + 1
    val ny = ((yMax - yMin) / h).toInt() + 1
    val values = DoubleArray(nx * ny) { k -> field(xMin + (k % nx) * h, yMin + (k / nx) * h) }
    return SdfGrid2D(values, nx, ny, xMin, yMin, h)
}

/**
 * The exact signed distance to a closed boundary given as dense points: `sign(f) * min |p - s|`. A
 * brute-force reference for testing a repair, when no closed form exists (a union's inside, a fillet).
 */
fun sdfFromBoundary(boundary: DoubleArray, sign: ScalarField2D, xMin: Double, yMin: Double, cell: Double, nx: Int, ny: Int): ScalarField2D {
    val buckets = PointBuckets2D(boundary, xMin, yMin, cell, nx, ny)
    return { x, y ->
        val d = buckets.nearest(x, y)
        if (sign(x, y) < 0) -d else d
    }
}

/**
 * The signed distance to the outline of a closed 2-D `FLine`, negative inside: the exact field for
 * a drawn shape, ready for [sdfGrid2D], CSG and [SdfGrid2D.repaired].
 *
 * The line is flattened into closed rings, [samplesPerCubic] points per `cubicTo`, every subpath
 * closed back to its `moveTo`,, and each edge is resampled every [spacing] (default: a two-thousandth of
 * the bounding box's longer side). The distance is to the nearest of those samples, so it is exact
 * to within half a [spacing] plus the flattening's chord error. Inside is decided by the even-odd
 * rule against the flattened edges, so a counter wound either way is a hole. Edges are bucketed by
 * row for the inside test and samples by cell for the distance, so one query costs a few dozen
 * operations rather than a pass over the outline.
 *
 * ```kotlin
 * val shape = squircleRectFrom(-1.0, -0.5, 2.0, 1.0, radius = 0.4, gauge = 0.79)
 * val field = shape.signedDistance2D()
 * val grid = sdfGrid2D(xMin = -1.5, yMin = -1.0, xMax = 1.5, yMax = 1.0, n = 97, field = field)
 * ```
 */
fun FLine.signedDistance2D(samplesPerCubic: Int = 24, spacing: Double? = null): ScalarField2D {
    // Flatten into closed rings. Coincident points are kept: a zero-length edge never crosses a
    // row, whereas dropping it (as pointEdgeGraph2D does) can open a gap exactly on a vertex's y.
    val px = ArrayList<Double>()
    val py = ArrayList<Double>()
    val edgeList = ArrayList<Int>() // pairs of point indices
    var ringStart = -1
    fun closeRing() {
        if (ringStart >= 0 && px.size - 1 > ringStart) edgeList.apply { add(px.size - 1); add(ringStart) }
        ringStart = -1
    }
    fun addPoint(x: Double, y: Double) {
        if (ringStart >= 0) edgeList.apply { add(px.size - 1); add(px.size) } else ringStart = px.size
        px += x
        py += y
    }
    for (node in nodes) {
        when (node) {
            is FLine.MoveTo -> {
                closeRing()
                addPoint(node.to.x.toDouble(), node.to.y.toDouble())
            }
            is FLine.LineTo -> addPoint(node.to.x.toDouble(), node.to.y.toDouble())
            is FLine.CubicTo -> {
                if (ringStart < 0) {
                    addPoint(node.to.x.toDouble(), node.to.y.toDouble())
                } else {
                    val ax = px.last()
                    val ay = py.last()
                    for (k in 1..samplesPerCubic) {
                        val t = k.toDouble() / samplesPerCubic
                        val u = 1 - t
                        val b0 = u * u * u
                        val b1 = 3 * u * u * t
                        val b2 = 3 * u * t * t
                        val b3 = t * t * t
                        addPoint(
                            b0 * ax + b1 * node.c1.x + b2 * node.c2.x + b3 * node.to.x,
                            b0 * ay + b1 * node.c1.y + b2 * node.c2.y + b3 * node.to.y,
                        )
                    }
                }
            }
            else -> closeRing()
        }
    }
    closeRing()
    require(edgeList.isNotEmpty()) { "signedDistance2D needs a line with at least one edge" }
    val xs = px.toDoubleArray()
    val ys = py.toDoubleArray()
    val edges = List(edgeList.size / 2) { edgeList[2 * it] to edgeList[2 * it + 1] }
    val xMin = xs.min()
    val xMax = xs.max()
    val yMin = ys.min()
    val yMax = ys.max()
    val extent = max(max(xMax - xMin, yMax - yMin), 1e-12)
    val step = spacing ?: (extent / 2000)
    require(step > 0) { "spacing must be positive, got $step" }

    val samples = ArrayList<Double>()
    for ((a, b) in edges) {
        val n = max(1, ceil(hypot(xs[b] - xs[a], ys[b] - ys[a]) / step).toInt())
        for (k in 0 until n) {
            val t = k.toDouble() / n
            samples += xs[a] + t * (xs[b] - xs[a])
            samples += ys[a] + t * (ys[b] - ys[a])
        }
    }
    val cells = max(1, sqrt(samples.size / 2.0 / 4).toInt())
    val cell = extent / cells
    val buckets = PointBuckets2D(samples.toDoubleArray(), xMin, yMin, cell, cells + 1, cells + 1)

    // Each edge is listed in every row its y-span touches, for the crossing count.
    val rows = max(1, sqrt(edges.size.toDouble()).toInt())
    val rowHeight = max(yMax - yMin, 1e-12) / rows
    fun row(y: Double) = floor((y - yMin) / rowHeight).toInt().coerceIn(0, rows - 1)
    val byRow = Array(rows) { ArrayList<Int>() }
    for ((e, edge) in edges.withIndex()) {
        val (a, b) = edge
        for (r in row(min(ys[a], ys[b]))..row(max(ys[a], ys[b]))) byRow[r] += e
    }

    return { x, y ->
        val d = buckets.nearest(x, y)
        var inside = false
        if (y in yMin..yMax) {
            for (e in byRow[row(y)]) {
                val (a, b) = edges[e]
                val ya = ys[a]
                val yb = ys[b]
                if ((ya > y) != (yb > y) && x < xs[a] + (y - ya) * (xs[b] - xs[a]) / (yb - ya)) inside = !inside
            }
        }
        if (inside) -d else d
    }
}

/** Nearest-point queries over a flat `[x, y, ...]` array, bucketed on a uniform grid of cells. */
internal class PointBuckets2D(
    private val points: DoubleArray,
    private val x0: Double,
    private val y0: Double,
    private val cell: Double,
    nx: Int,
    ny: Int,
) {
    private val cols = max(1, nx)
    private val rows = max(1, ny)
    private val start = IntArray(cols * rows + 1)
    private val order: IntArray

    init {
        val n = points.size / 2
        val bucketOf = IntArray(n) { bucket(points[2 * it], points[2 * it + 1]) }
        for (b in bucketOf) start[b + 1]++
        for (b in 0 until cols * rows) start[b + 1] += start[b]
        val fill = start.copyOf()
        order = IntArray(n)
        for (p in 0 until n) order[fill[bucketOf[p]]++] = p
    }

    private fun col(x: Double) = ((x - x0) / cell).toInt().coerceIn(0, cols - 1)

    private fun row(y: Double) = ((y - y0) / cell).toInt().coerceIn(0, rows - 1)

    private fun bucket(x: Double, y: Double) = row(y) * cols + col(x)

    /** The distance from `(x, y)` to the nearest point, `+Infinity` if there are none. */
    fun nearest(x: Double, y: Double): Double {
        if (order.isEmpty()) return Double.POSITIVE_INFINITY
        val c = col(x)
        val r = row(y)
        var best2 = Double.POSITIVE_INFINITY
        var ring = 0
        val maxRing = max(cols, rows)
        while (ring <= maxRing) {
            for (rr in r - ring..r + ring) {
                if (rr < 0 || rr >= rows) continue
                val edgeRow = rr == r - ring || rr == r + ring
                var cc = c - ring
                while (cc <= c + ring) {
                    if (cc in 0 until cols) {
                        val b = rr * cols + cc
                        for (k in start[b] until start[b + 1]) {
                            val p = order[k]
                            val dx = points[2 * p] - x
                            val dy = points[2 * p + 1] - y
                            val d2 = dx * dx + dy * dy
                            if (d2 < best2) best2 = d2
                        }
                    }
                    cc += if (edgeRow || ring == 0) 1 else 2 * ring
                }
            }
            // Every unvisited point is at least `ring * cell` away (query clamped into the grid adds slack).
            val reached = ring * cell - outside(x, y)
            if (best2.isFinite() && reached > 0 && reached * reached >= best2) break
            ring++
        }
        return sqrt(best2)
    }

    private fun outside(x: Double, y: Double): Double {
        val dx = max(0.0, max(x0 - x, x - (x0 + cols * cell)))
        val dy = max(0.0, max(y0 - y, y - (y0 + rows * cell)))
        return hypot(dx, dy)
    }
}
