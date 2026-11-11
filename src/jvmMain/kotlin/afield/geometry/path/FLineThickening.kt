/**
 * Turning a line into an outline: offsets, ribbons and buffers of an [FLine], at fixed or varying width.
 *
 * [byRibboning] and [byVariableRibboning] return an [FLineRibbon] with left and right edges and a
 * closed shell. [byJavaOffsetting] uses Java2D strokes; [byJtsBuffering], [byJtsOffsetting] and
 * [byJtsVariableBuffering] use JTS, with [FLineStrokeJoin] and [FLineStrokeCap] for the corner and
 * end style. [byJtsSplitting] separates a self-intersecting line into an [FLineSplit].
 * [bySweptCircleEnveloping] and [sweptCircleStroke] give the exact outline of a circle of varying
 * radius swept along a path, with [sweptCircleDegenerateSpans] for where that outline does not exist.
 */

package afield.geometry.path

import afield.ui.visualization.l
import afield.ui.visualization.lines

import afield.ai.learning.*
import afield.ai.retrieval.*
import afield.animation.interpolation.*
import afield.animation.keyframes.*
import afield.animation.motion.*
import afield.app.commands.*
import afield.app.diagnostics.*
import afield.audio.analysis.*
import afield.audio.synth.*
import afield.data.collections.*
import afield.data.formats.*
import afield.data.graph.*
import afield.data.sorting.*
import afield.document.context.*
import afield.document.graph.*
import afield.document.properties.*
import afield.document.workspace.*
import afield.geometry.mesh.*
import afield.geometry.path.*
import afield.geometry.spatial.*
import afield.graphics.color.*
import afield.graphics.interaction.*
import afield.graphics.render.*
import afield.graphics.shader.*
import afield.graphics.text.*
import afield.graphics.vulkan.buffers.*
import afield.io.osc.*
import afield.io.process.*
import afield.lang.lambda.*
import afield.lang.python.*
import afield.lang.remotejs.*
import afield.lang.stacksl.*
import afield.math.autodiff.*
import afield.math.control.*
import afield.math.estimation.*
import afield.math.functions.*
import afield.math.linalg.*
import afield.math.optimization.*
import afield.math.statistics.*
import afield.math.units.*
import afield.media.image.*
import afield.media.video.*
import afield.runtime.execution.*
import afield.runtime.languages.*
import afield.runtime.lifecycle.*
import afield.simulation.agents.*
import afield.simulation.automata.*
import afield.simulation.particles.*
import afield.study.build.*
import afield.study.evidence.*
import afield.study.search.*
import afield.study.source.*
import afield.study.ui.*
import afield.ui.controls.*
import afield.ui.output.*
import afield.ui.visualization.*
import afield.geometry.path.java2d.FLinesAndJavaShapes
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryCollection
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.MultiLineString
import org.locationtech.jts.operation.buffer.BufferOp
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.operation.buffer.OffsetCurve
import org.locationtech.jts.operation.buffer.VariableBuffer
import org.locationtech.jts.operation.linemerge.LineMerger
import org.locationtech.jts.operation.polygonize.Polygonizer
import org.locationtech.jts.operation.union.UnaryUnionOp
import java.awt.BasicStroke
import java.awt.Shape
import java.awt.geom.FlatteningPathIterator
import java.awt.geom.GeneralPath
import java.awt.geom.PathIterator
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Join style for Java2D/JTS offset and buffer operations.
 */
enum class FLineStrokeJoin(internal val awtJoin: Int, internal val jtsJoin: Int) {
    MITER(BasicStroke.JOIN_MITER, BufferParameters.JOIN_MITRE),
    ROUND(BasicStroke.JOIN_ROUND, BufferParameters.JOIN_ROUND),
    BEVEL(BasicStroke.JOIN_BEVEL, BufferParameters.JOIN_BEVEL)
}

/**
 * Cap style for Java2D/JTS offset and buffer operations.
 */
enum class FLineStrokeCap(internal val awtCap: Int, internal val jtsCap: Int) {
    BUTT(BasicStroke.CAP_BUTT, BufferParameters.CAP_FLAT),
    ROUND(BasicStroke.CAP_ROUND, BufferParameters.CAP_ROUND),
    SQUARE(BasicStroke.CAP_SQUARE, BufferParameters.CAP_SQUARE)
}

/**
 * Ribbon result split into its left edge, right edge, and closed shell.
 */
data class FLineRibbon(
    val left: FLine,
    val right: FLine,
    val shell: FLine
) {
    override fun toString(): String = prettySummary(
        "FLineRibbon",
        "left" to left,
        "right" to right,
        "shell" to shell
    )
}

/**
 * JTS split result: largest outer loop, interior loops, and open line components.
 */
data class FLineSplit(
    val outerLoop: FLine?,
    val innerLoops: List<FLine>,
    val lines: List<FLine>
) {
    override fun toString(): String = prettySummary(
        "FLineSplit",
        "outerLoop" to outerLoop,
        "innerLoops" to innerLoops.size,
        "lines" to lines.size
    )
}

/**
 * Create left/right offset edges at equal distance from the centerline.
 */
fun FLine.byBidirectionalOffsetting(
    distance: Float,
    samples: Int = 200,
    sampler: FLineSampler? = null
): Pair<FLine, FLine> {
    val d = abs(distance)
    val ribbon = offsetRibbon(
        samples = samples,
        sampler = sampler,
        leftDistance = { _, _ -> d },
        rightDistance = { _, _ -> d }
    )
    return ribbon.left to ribbon.right
}

/**
 * Create left/right offset edges with independent distance functions.
 */
fun FLine.byVariableThicknessOffsetting(
    leftDistance: ParameterFunction,
    rightDistance: ParameterFunction = leftDistance,
    samples: Int = 200,
    sampler: FLineSampler? = null
): Pair<FLine, FLine> {
    val ribbon = offsetRibbon(
        samples = samples,
        sampler = sampler,
        leftDistance = { t, p -> leftDistance.evaluate(t, p).coerceAtLeast(0f) },
        rightDistance = { t, p -> rightDistance.evaluate(t, p).coerceAtLeast(0f) }
    )
    return ribbon.left to ribbon.right
}

/**
 * Build a constant-thickness ribbon polygon around this line.
 *
 * [thickness] is full ribbon width.
 */
fun FLine.byRibboning(
    thickness: Float,
    samples: Int = 200,
    sampler: FLineSampler? = null
): FLine {
    val half = abs(thickness) * 0.5f
    return offsetRibbon(
        samples = samples,
        sampler = sampler,
        leftDistance = { _, _ -> half },
        rightDistance = { _, _ -> half }
    ).shell
}

/**
 * Build a variable-thickness ribbon polygon around this line.
 *
 * [thicknessFunc] returns full ribbon width at each sample.
 */
fun FLine.byVariableRibboning(
    thicknessFunc: ParameterFunction,
    samples: Int = 200,
    sampler: FLineSampler? = null
): FLine {
    return offsetRibbon(
        samples = samples,
        sampler = sampler,
        leftDistance = { t, p -> (thicknessFunc.evaluate(t, p) * 0.5f).coerceAtLeast(0f) },
        rightDistance = { t, p -> (thicknessFunc.evaluate(t, p) * 0.5f).coerceAtLeast(0f) }
    ).shell
}

/**
 * Java2D stroke-based thickening using BasicStroke; good quality for 2D cases.
 *
 * [strokeWidth] is full stroke width.
 */
fun FLine.byJavaOffsetting(
    strokeWidth: Float,
    cap: FLineStrokeCap = FLineStrokeCap.ROUND,
    join: FLineStrokeJoin = FLineStrokeJoin.ROUND,
    miterLimit: Float = 10f
): FLine {
    val raw = FLinesAndJavaShapes.flineToJavaShape_notThickened(this)
    val stroked = BasicStroke(
        strokeWidth.coerceAtLeast(0f),
        cap.awtCap,
        join.awtJoin,
        miterLimit.coerceAtLeast(1f)
    ).createStrokedShape(raw)
    return FLinesAndJavaShapes.javaShapeToFLine(stroked)
}

/**
 * JTS buffer for this line (2D), returning filled geometry as an FLine.
 */
fun FLine.byJtsBuffering(
    distance: Double,
    flatness: Double = 0.5,
    quadrantSegments: Int = 8,
    join: FLineStrokeJoin = FLineStrokeJoin.ROUND,
    cap: FLineStrokeCap = FLineStrokeCap.ROUND,
    miterLimit: Double = 5.0
): FLine {
    val splitter = JtsCurveSplitter(flatness = flatness)
    val multi = splitter.toMultiLineString(this) ?: return FLine()
    val params = BufferParameters().apply {
        setQuadrantSegments(quadrantSegments.coerceAtLeast(1))
        setJoinStyle(join.jtsJoin)
        setEndCapStyle(cap.jtsCap)
        setMitreLimit(miterLimit.coerceAtLeast(1.0))
    }
    val buffered = BufferOp.bufferOp(multi, distance, params)
    return splitter.geometryToFLine(buffered)
}

/**
 * JTS offset-curve extraction (2D).
 */
fun FLine.byJtsOffsetting(
    distance: Double,
    flatness: Double = 0.5
): FLine {
    val splitter = JtsCurveSplitter(flatness = flatness)
    val multi = splitter.toMultiLineString(this) ?: return FLine()
    val offset = OffsetCurve.getCurve(multi, distance)
    return splitter.geometryToFLine(offset)
}

/**
 * JTS variable buffer sampled from this curve.
 */
fun FLine.byJtsVariableBuffering(
    numSamples: Int = 128,
    distance: (Double) -> Double,
    sampler: FLineSampler? = null,
    flatness: Double = 0.5
): FLine {
    val pieces = this.pieces().filter { it.nodes.size > 1 }.ifEmpty {
        if (this.nodes.size > 1) listOf(this) else emptyList()
    }
    if (pieces.isEmpty()) return FLine()

    val geoms = mutableListOf<Geometry>()
    for (piece in pieces) {
        val sampled = dedupeSeries2D(piece.sampleSeries(numSamples.coerceAtLeast(2), sampler = sampler))
        if (sampled.size < 2) continue
        val coords = sampled.map { Coordinate(it.point.x.toDouble(), it.point.y.toDouble()) }.toTypedArray()
        val distances = DoubleArray(sampled.size) { i -> distance(sampled[i].t.toDouble()) }
        val g = VariableBuffer(JtsCurveSplitter.GF.createLineString(coords), distances).result
        if (!g.isEmpty) geoms += g
    }

    if (geoms.isEmpty()) return FLine()
    val merged = UnaryUnionOp.union(geoms)
    return JtsCurveSplitter(flatness = flatness).geometryToFLine(merged)
}

/**
 * Outline of a circle of varying radius swept along this line: the exact envelope, not a
 * displaced ribbon.
 *
 * At each point the circle's centre moves with velocity `P′` and its radius changes at `r′`. The
 * edge of the swept shape is where the circle touches its neighbour, which is not along the
 * normal: it is the normal tilted back by `sin θ = r′ / |P′|`, toward the end the circle grows
 * from. [byVariableRibboning] uses the untilted normal, so a stroke that swells quickly comes out
 * too thin on its flanks, and it has butt ends; this one has round ends and the true flanks.
 *
 * Where `|r′| ≥ |P′|` (the radius changes faster than the centre moves) the circle swallows
 * itself and there is no envelope. [FLineRibbon.left] and [FLineRibbon.right] break there; the
 * [FLineRibbon.shell] fills that span with the union of the circles, so the shell is always one
 * valid filled outline. It is built as a JTS union, so a tight turn whose inner edge folds back
 * (a cusp) does not leave a bow-tie either.
 *
 * @param radius radius (half the width) at each parameter `t` along the line, in line units.
 * @param samples stations along the whole line.
 * @param sampler optional sampling strategy, as for [byVariableRibboning].
 * @param flatness tolerance for the round caps and disc polygons.
 */
fun FLine.bySweptCircleEnveloping(
    radius: ParameterFunction,
    samples: Int = 200,
    sampler: FLineSampler? = null,
    flatness: Double = 0.25
): FLineRibbon {
    val series = this.sampleSeries(samples.coerceAtLeast(3), sampler = sampler)
    if (series.size < 2) return FLineRibbon(FLine(), FLine(), FLine())
    val rs = series.map { radius.evaluate(it.t, it.point).toDouble().coerceAtLeast(0.0) }
    val n = series.lastIndex
    val stations = series.indices.map { i ->
        val a = (i - 1).coerceAtLeast(0)
        val b = (i + 1).coerceAtMost(n)
        val dt = (series[b].t - series[a].t).toDouble().takeIf { it > 1e-12 } ?: 1.0
        SweptStation(
            series[i].point.x.toDouble(), series[i].point.y.toDouble(),
            (series[b].point.x - series[a].point.x) / dt, (series[b].point.y - series[a].point.y) / dt,
            rs[i], (rs[b] - rs[a]) / dt
        )
    }
    return sweptEnvelope(stations, flatness)
}

/**
 * A variable-width stroke from pen samples: each knot is `(x, y, r)` with the radius in `z`.
 *
 * Consecutive knots are joined by cubic Béziers in `(x, y, r)` whose tangents come from the
 * neighbouring knots (Catmull-Rom), so the width is as smooth as the path. The outline is the
 * exact swept-circle envelope of that spline, as in [bySweptCircleEnveloping]. For a pen, a radius
 * of `w · p^1.5` to `w · p^2` from pressure `p` reads as natural ink.
 *
 * A pen stroke with a sharp turn in it (the point of a "v", the corners of a "z") is rounded off
 * by the spline, and overshoots. List those knots in [corners]: there the path keeps its corner,
 * each side taking its tangent from its own span, and the stroke turns it with a round join, the
 * circle at the corner. [penCorners] finds them by angle.
 *
 * @param knots pen samples, `z` = radius.
 * @param samplesPerSegment stations on each Bézier span.
 * @param flatness tolerance for the round caps and disc polygons.
 * @param corners indices into [knots] where the path has a corner instead of passing smoothly.
 */
fun sweptCircleStroke(
    knots: List<Vec3>,
    samplesPerSegment: Int = 24,
    flatness: Double = 0.25,
    corners: Collection<Int> = emptyList(),
): FLineRibbon {
    val segs = sweptCircleSegments(knots, corners)
    if (segs.isEmpty()) {
        if (knots.size == 1) {
            val k = knots[0]
            val disc = discPolygon(k.x.toDouble(), k.y.toDouble(), k.z.toDouble().coerceAtLeast(0.0), flatness)
            return FLineRibbon(FLine(), FLine(), JtsCurveSplitter(flatness).geometryToFLine(disc))
        }
        return FLineRibbon(FLine(), FLine(), FLine())
    }
    val m = samplesPerSegment.coerceAtLeast(2)
    val stations = ArrayList<SweptStation>()
    val joins = HashSet<Int>()
    val sharp = corners.toSet()
    for ((si, s) in segs.withIndex()) {
        // a corner keeps both of its stations (one per tangent) and is joined by its disc
        val first = if (si == 0) 0 else if (si in sharp) { joins += stations.size; 0 } else 1
        for (j in first..m) stations += s.station(j.toDouble() / m)
    }
    return sweptEnvelope(stations, flatness, joins)
}

/**
 * The Bézier spans of [sweptCircleStroke] in `(x, y, r)`: four control points each, `z` = radius.
 *
 * @param knots pen samples, `z` = radius.
 * @param corners knots where the path has a corner, as for [sweptCircleStroke].
 */
fun sweptCircleBeziers(knots: List<Vec3>, corners: Collection<Int> = emptyList()): List<List<Vec3>> =
    sweptCircleSegments(knots, corners).map { s -> s.c.map { vec(it[0].toFloat(), it[1].toFloat(), it[2].toFloat()) } }

/**
 * Where the stroke of [knots] has no envelope: parameter spans on which `|r′| ≥ |P′|`, so the
 * circle swallows itself.
 *
 * Parameters run `0..knots.size - 1`, span `i` covering knots `i` to `i + 1`. On each cubic span
 * `|P′(t)|² − r′(t)²` is a quartic; its roots are found by sign changes on a fine grid refined by
 * bisection, so the ends are exact to about `1e-9`. A span that only touches zero (a single
 * stationary instant) is reported as a zero-length range.
 *
 * @param knots pen samples, `z` = radius.
 * @param corners knots where the path has a corner, as for [sweptCircleStroke].
 */
fun sweptCircleDegenerateSpans(knots: List<Vec3>, corners: Collection<Int> = emptyList()): List<ClosedFloatingPointRange<Double>> {
    val segs = sweptCircleSegments(knots, corners)
    val out = ArrayList<ClosedFloatingPointRange<Double>>()
    var open: Double? = null
    val grid = 64
    for ((si, s) in segs.withIndex()) {
        fun g(t: Double) = s.speedSqMinusDr(t)
        fun root(a0: Double, b0: Double): Double {
            var a = a0
            var b = b0
            val ga = g(a) < 0
            repeat(60) {
                val mid = (a + b) / 2
                if ((g(mid) < 0) == ga) a = mid else b = mid
            }
            return (a + b) / 2
        }
        if (si == 0 && g(0.0) <= 0) open = 0.0
        for (k in 0 until grid) {
            val a = k.toDouble() / grid
            val b = (k + 1).toDouble() / grid
            val ga = g(a)
            val gb = g(b)
            if (ga > 0 && gb <= 0) open = si + root(a, b)
            else if (ga <= 0 && gb > 0) {
                val r = si + root(a, b)
                out += (open ?: r)..r
                open = null
            }
        }
    }
    open?.let { out += it..segs.size.toDouble() }
    return out
}

/**
 * Fewer pen samples, drawing the same stroke.
 *
 * A tablet reports a point every pixel or two, and its pressure wobbles from one to the next. Fed
 * to [sweptCircleStroke] directly, that is slow (hundreds of Bézier spans), and the wobble, where
 * the pen moves slowly, outruns the pen and makes spans with no envelope. This first averages the
 * radius over [widthSmoothing] units of path either side of each knot (positions are left alone, so
 * corners stay sharp), then drops each knot that lies within [tolerance] of the straight run between
 * the knots kept either side of it (Ramer-Douglas-Peucker in `(x, y, r)`, so a change in width counts
 * as much as a change in position). The first and last knots are always kept, and repeats are
 * dropped.
 *
 * @param knots pen samples, `z` = radius.
 * @param tolerance largest distance, in `(x, y, r)`, a dropped knot may lie from what replaces it.
 * @param widthSmoothing half-width, in path length, of the window the radius is averaged over; 0
 * keeps the radii as they are.
 */
fun thinPenSamples(knots: List<Vec3>, tolerance: Double = 0.5, widthSmoothing: Double = 3.0): List<Vec3> {
    val p = ArrayList<Vec3>(knots.size)
    for (k in knots) if (p.isEmpty() || p.last().distance(k) > 1e-9) p += Vec3(k)
    if (widthSmoothing > 0 && p.size > 2) {
        val arc = DoubleArray(p.size)
        for (i in 1 until p.size) arc[i] = arc[i - 1] + kotlin.math.hypot((p[i].x - p[i - 1].x).toDouble(), (p[i].y - p[i - 1].y).toDouble())
        val r = DoubleArray(p.size) { p[it].z.toDouble() }
        var lo = 0
        var hi = 0
        for (i in p.indices) {
            while (arc[i] - arc[lo] > widthSmoothing) lo++
            while (hi + 1 < p.size && arc[hi + 1] - arc[i] <= widthSmoothing) hi++
            var sum = 0.0
            var weight = 0.0
            for (j in lo..hi) {
                val w = 1 - abs(arc[j] - arc[i]) / (widthSmoothing * 1.0001)
                sum += w * r[j]
                weight += w
            }
            p[i].z = (sum / weight).toFloat()
        }
    }
    if (p.size <= 2) return p
    val keep = BooleanArray(p.size)
    keep[0] = true
    keep[p.lastIndex] = true
    val stack = ArrayDeque<Pair<Int, Int>>()
    stack.addLast(0 to p.lastIndex)
    while (stack.isNotEmpty()) {
        val (a, b) = stack.removeLast()
        if (b - a < 2) continue
        val ax = p[a].x.toDouble()
        val ay = p[a].y.toDouble()
        val az = p[a].z.toDouble()
        val dx = p[b].x - ax
        val dy = p[b].y - ay
        val dz = p[b].z - az
        val len2 = dx * dx + dy * dy + dz * dz
        var worst = -1.0
        var at = -1
        for (k in a + 1 until b) {
            val qx = p[k].x - ax
            val qy = p[k].y - ay
            val qz = p[k].z - az
            val u = if (len2 > 1e-18) ((qx * dx + qy * dy + qz * dz) / len2).coerceIn(0.0, 1.0) else 0.0
            val ex = qx - u * dx
            val ey = qy - u * dy
            val ez = qz - u * dz
            val d = ex * ex + ey * ey + ez * ez
            if (d > worst) { worst = d; at = k }
        }
        if (worst > tolerance * tolerance) {
            keep[at] = true
            stack.addLast(a to at)
            stack.addLast(at to b)
        }
    }
    return p.filterIndexed { i, _ -> keep[i] }
}

/**
 * The knots where the pen turns more sharply than [degrees], for the `corners` of
 * [sweptCircleStroke]. Measure on thinned samples ([thinPenSamples]): on raw tablet points every
 * small wobble is a sharp turn.
 *
 * @param knots pen samples, `z` = radius.
 * @param degrees turn, between the span arriving at a knot and the span leaving it, above which
 * the knot is a corner.
 */
fun penCorners(knots: List<Vec3>, degrees: Double = 60.0): List<Int> {
    val limit = Math.toRadians(degrees)
    return (1 until knots.lastIndex).filter { i ->
        val ax = (knots[i].x - knots[i - 1].x).toDouble()
        val ay = (knots[i].y - knots[i - 1].y).toDouble()
        val bx = (knots[i + 1].x - knots[i].x).toDouble()
        val by = (knots[i + 1].y - knots[i].y).toDouble()
        if (ax * ax + ay * ay < 1e-18 || bx * bx + by * by < 1e-18) false
        else abs(kotlin.math.atan2(ax * by - ay * bx, ax * bx + ay * by)) > limit
    }
}

/**
 * Largest distance between the boundary of this filled outline and the boundary of [other] (the
 * discrete Hausdorff distance), for measuring one outline against a reference.
 *
 * @param other outline to compare against.
 * @param flatness curve flattening tolerance.
 */
fun FLine.outlineDistanceTo(other: FLine, flatness: Double = 0.1): Double {
    val sp = JtsCurveSplitter(flatness)
    val a = sp.toMultiLineString(this) ?: return Double.NaN
    val b = sp.toMultiLineString(other) ?: return Double.NaN
    return org.locationtech.jts.algorithm.distance.DiscreteHausdorffDistance(a, b).apply { setDensifyFraction(0.05) }
        .distance()
}

private class SweptStation(
    val x: Double, val y: Double, val dx: Double, val dy: Double, val r: Double, val dr: Double
)

private class SweptSegment(val c: Array<DoubleArray>) {
    private fun at(t: Double, k: Int): Double {
        val u = 1 - t
        return u * u * u * c[0][k] + 3 * u * u * t * c[1][k] + 3 * u * t * t * c[2][k] + t * t * t * c[3][k]
    }

    private fun d(t: Double, k: Int): Double {
        val u = 1 - t
        return 3 * (u * u * (c[1][k] - c[0][k]) + 2 * u * t * (c[2][k] - c[1][k]) + t * t * (c[3][k] - c[2][k]))
    }

    fun station(t: Double) = SweptStation(at(t, 0), at(t, 1), d(t, 0), d(t, 1), at(t, 2).coerceAtLeast(0.0), d(t, 2))
    fun speedSqMinusDr(t: Double): Double {
        val dx = d(t, 0)
        val dy = d(t, 1)
        val dr = d(t, 2)
        return dx * dx + dy * dy - dr * dr
    }
}

private fun sweptCircleSegments(knots: List<Vec3>, corners: Collection<Int> = emptyList()): List<SweptSegment> {
    if (knots.size < 2) return emptyList()
    val p = knots.map { doubleArrayOf(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }
    val n = p.lastIndex
    val sharp = corners.toSet()
    // span lengths in the plane; a span on which only the radius changes counts by that
    val len = DoubleArray(n) { i ->
        val d = kotlin.math.hypot(p[i + 1][0] - p[i][0], p[i + 1][1] - p[i][1])
        if (d > 1e-12) d else abs(p[i + 1][2] - p[i][2])
    }
    // The tangent at an interior knot is the neighbours' difference shared out by span length, so
    // each side gets a tangent as long as its own span. Pen samples bunch up where the pen slows, and
    // with the plain Catmull-Rom tangent a short span next to a long one overshoots by a loop; with
    // evenly spaced knots the two are the same. At a corner, and at the ends, each side takes its
    // tangent from its own span only.
    fun tangent(i: Int, span: Int): DoubleArray {
        if (i == 0 || i == n || i in sharp) {
            val a = if (span < i) i - 1 else i
            return DoubleArray(3) { k -> p[a + 1][k] - p[a][k] }
        }
        val total = len[i - 1] + len[i]
        val scale = if (total > 1e-12) len[span] / total else 0.5
        return DoubleArray(3) { k -> (p[i + 1][k] - p[i - 1][k]) * scale }
    }
    fun incoming(i: Int) = tangent(i, i - 1)
    fun outgoing(i: Int) = tangent(i, i)
    return (0 until n).map { i ->
        val t0 = outgoing(i)
        val t1 = incoming(i + 1)
        SweptSegment(
            arrayOf(
                p[i],
                DoubleArray(3) { k -> p[i][k] + t0[k] / 3 },
                DoubleArray(3) { k -> p[i + 1][k] - t1[k] / 3 },
                p[i + 1]
            )
        )
    }
}

private fun discPolygon(x: Double, y: Double, r: Double, flatness: Double): Geometry {
    val quadrant = if (r <= flatness) 2 else (Math.PI / 2 / (2 * kotlin.math.acos(1 - flatness / r))).toInt().coerceIn(2, 64)
    return JtsCurveSplitter.GF.createPoint(Coordinate(x, y)).buffer(r, quadrant)
}

/** [g] without the zero-area rings a union of touching pieces can leave behind. */
private fun withoutSlivers(g: Geometry, minArea: Double): Geometry {
    val gf = JtsCurveSplitter.GF
    val kept = ArrayList<org.locationtech.jts.geom.Polygon>()
    for (k in 0 until g.numGeometries) {
        val poly = g.getGeometryN(k) as? org.locationtech.jts.geom.Polygon ?: continue
        if (poly.area < minArea) continue
        val holes = (0 until poly.numInteriorRing).map { poly.getInteriorRingN(it) }
            .filter { abs(org.locationtech.jts.algorithm.Area.ofRing(it.coordinateSequence)) >= minArea }
        kept += gf.createPolygon(poly.exteriorRing, holes.toTypedArray())
    }
    return gf.createMultiPolygon(kept.toTypedArray())
}

private fun sweptEnvelope(st: List<SweptStation>, flatness: Double, joins: Set<Int> = emptySet()): FLineRibbon {
    val gf = JtsCurveSplitter.GF
    val n = st.size
    val left = arrayOfNulls<Coordinate>(n)
    val right = arrayOfNulls<Coordinate>(n)
    for ((i, s) in st.withIndex()) {
        val speed = sqrt(s.dx * s.dx + s.dy * s.dy)
        if (speed <= 1e-12 || abs(s.dr) >= speed) continue
        val tx = s.dx / speed
        val ty = s.dy / speed
        val sin = s.dr / speed
        val cos = sqrt(1 - sin * sin)
        val bx = -sin * tx
        val by = -sin * ty
        left[i] = Coordinate(s.x + s.r * (bx - cos * ty), s.y + s.r * (by + cos * tx))
        right[i] = Coordinate(s.x + s.r * (bx + cos * ty), s.y + s.r * (by - cos * tx))
    }
    val parts = ArrayList<Geometry>()
    fun disc(i: Int) = discPolygon(st[i].x, st[i].y, st[i].r, flatness)
    parts += disc(0)
    parts += disc(n - 1)
    // the two stations of a corner (j - 1 and j) sit on one centre; its disc is the join
    for (j in joins) parts += disc(j)
    // An interval is plain when both flanks exist at both ends and neither runs backwards against
    // the centre (an inner cusp). Runs of plain intervals go in as one strip polygon each, which
    // is what keeps a stroke of hundreds of knots fast; a strip that crosses itself falls back to
    // one hull per interval, as does every interval that is not plain.
    fun plain(i: Int): Boolean {
        if (i + 1 in joins) return false
        val l0 = left[i] ?: return false
        val l1 = left[i + 1] ?: return false
        val r0 = right[i] ?: return false
        val r1 = right[i + 1] ?: return false
        val cx = st[i + 1].x - st[i].x
        val cy = st[i + 1].y - st[i].y
        return (l1.x - l0.x) * cx + (l1.y - l0.y) * cy >= 0 && (r1.x - r0.x) * cx + (r1.y - r0.y) * cy >= 0
    }
    fun quad(i: Int) = gf.createMultiPointFromCoords(arrayOf(left[i], left[i + 1], right[i + 1], right[i])).convexHull()
    fun bridge(i: Int) = UnaryUnionOp.union(listOf(disc(i), disc(i + 1))).convexHull()
    var i = 0
    while (i < n - 1) {
        if (!plain(i)) {
            if (i + 1 in joins) { i++; continue }
            if (left[i] != null && left[i + 1] != null && right[i] != null && right[i + 1] != null) parts += quad(i)
            parts += bridge(i)
            i++
            continue
        }
        var end = i
        while (end + 1 < n - 1 && end - i < 31 && plain(end + 1)) end++
        val ring = ArrayList<Coordinate>(2 * (end - i) + 5)
        for (k in i..end + 1) ring += left[k]!!
        for (k in end + 1 downTo i) ring += right[k]!!
        ring += left[i]!!
        val strip = runCatching { gf.createPolygon(ring.toTypedArray()) }.getOrNull()
        if (strip != null && strip.isValid) parts += strip else for (k in i..end) parts += quad(k)
        i = end + 1
    }
    val shell = JtsCurveSplitter(flatness).geometryToFLine(withoutSlivers(UnaryUnionOp.union(parts), flatness * flatness * 1e-2))

    fun edge(pts: Array<Coordinate?>): FLine {
        val f = FLine()
        var pen = false
        for ((k, c) in pts.withIndex()) {
            if (k in joins) pen = false
            if (c == null) { pen = false; continue }
            if (pen) f.lineTo(c.x.toFloat(), c.y.toFloat()) else f.moveTo(c.x.toFloat(), c.y.toFloat())
            pen = true
        }
        return f
    }
    return FLineRibbon(left = edge(left), right = edge(right), shell = shell)
}

/**
 * Split potentially self-crossing geometry into loops and open lines using JTS noding/polygonization.
 */
fun FLine.byJtsSplitting(flatness: Double = 0.5): FLineSplit {
    return JtsCurveSplitter(flatness = flatness).split(this)
}

private fun FLine.offsetRibbon(
    samples: Int,
    sampler: FLineSampler?,
    leftDistance: (Float, Vec3) -> Float,
    rightDistance: (Float, Vec3) -> Float
): FLineRibbon {
    val series = this.sampleSeries(samples.coerceAtLeast(2), sampler = sampler)
    if (series.size < 2) return FLineRibbon(FLine(), FLine(), FLine())

    val normals = planarNormals(series)
    val left = ArrayList<Vec3>(series.size)
    val right = ArrayList<Vec3>(series.size)

    for (i in series.indices) {
        val s = series[i]
        val n = normals[i]
        val dl = leftDistance(s.t, s.point).coerceAtLeast(0f)
        val dr = rightDistance(s.t, s.point).coerceAtLeast(0f)
        left += Vec3(s.point).add(Vec3(n).mul(dl))
        right += Vec3(s.point).sub(Vec3(n).mul(dr))
    }

    return FLineRibbon(
        left = toPolyline(left),
        right = toPolyline(right),
        shell = toRibbonShell(left, right)
    )
}

private fun toPolyline(points: List<Vec3>): FLine {
    val f = FLine()
    if (points.isEmpty()) return f
    f.moveTo(points.first())
    for (i in 1 until points.size) f.lineTo(points[i])
    return f
}

private fun toRibbonShell(left: List<Vec3>, right: List<Vec3>): FLine {
    val f = FLine()
    if (left.isEmpty() || right.isEmpty()) return f

    f.moveTo(left.first())
    for (i in 1 until left.size) f.lineTo(left[i])
    for (i in right.lastIndex downTo 0) f.lineTo(right[i])
    f.close()
    return f
}

private fun planarNormals(series: List<FLineSample>): List<Vec3> {
    if (series.isEmpty()) return emptyList()
    val normals = MutableList(series.size) { vec(0f, 1f, 0f) }
    var lastNormal = vec(0f, 1f, 0f)
    val n = series.lastIndex
    for (i in series.indices) {
        val pPrev = series[(i - 1).coerceAtLeast(0)].point
        val pNext = series[(i + 1).coerceAtMost(n)].point
        val tx = pNext.x - pPrev.x
        val ty = pNext.y - pPrev.y
        val len = sqrt(tx * tx + ty * ty)
        val candidate = if (len > 1e-6f) {
            vec(-ty / len, tx / len, 0f)
        } else {
            Vec3(lastNormal)
        }
        if (i > 0 && candidate.dot(normals[i - 1]) < 0f) candidate.mul(-1f)
        normals[i] = candidate
        lastNormal = Vec3(candidate)
    }
    return normals
}

private fun dedupeSeries2D(series: List<FLineSample>, eps: Float = 1e-4f): List<FLineSample> {
    if (series.isEmpty()) return emptyList()
    val out = ArrayList<FLineSample>(series.size)
    var last: FLineSample? = null
    for (s in series) {
        val l = last
        if (l == null || (abs(s.point.x - l.point.x) > eps || abs(s.point.y - l.point.y) > eps)) {
            out += s
            last = s
        }
    }
    return out
}

/**
 * JTS helper for splitting/flattening/shape conversion.
 */
class JtsCurveSplitter(private val flatness: Double = 0.5) {
    companion object {
        internal val GF = GeometryFactory()
        private const val EPS = 1e-6
    }

    fun split(line: FLine): FLineSplit {
        val multi = toMultiLineString(line) ?: return FLineSplit(null, emptyList(), emptyList())
        val noded = UnaryUnionOp.union(multi)
        val merged = mergeLineStrings(noded)

        val closedLoops = merged.filter { it.coordinates.size > 1 && it.coordinates.first().equals2D(it.coordinates.last()) }
        val openCurves = merged.filterNot { it.coordinates.size > 1 && it.coordinates.first().equals2D(it.coordinates.last()) }

        val polygonizer = Polygonizer(true)
        merged.forEach { polygonizer.add(it) }
        @Suppress("UNCHECKED_CAST")
        val polygons = polygonizer.polygons as Collection<org.locationtech.jts.geom.Polygon>

        var outerLoop: FLine? = null
        val innerLoops = mutableListOf<FLine>()
        if (polygons.isNotEmpty()) {
            val sorted = polygons.sortedByDescending { it.area }
            val outer = sorted.first()
            outerLoop = ringToFLine(outer.exteriorRing as LineString, close = true)

            // Holes in the largest polygon are explicit inner loops.
            for (i in 0 until outer.numInteriorRing) {
                innerLoops += ringToFLine(outer.getInteriorRingN(i) as LineString, close = true)
            }
            // Remaining polygons become additional inner loops.
            for (i in 1 until sorted.size) {
                innerLoops += ringToFLine(sorted[i].exteriorRing as LineString, close = true)
                for (h in 0 until sorted[i].numInteriorRing) {
                    innerLoops += ringToFLine(sorted[i].getInteriorRingN(h) as LineString, close = true)
                }
            }
        } else if (closedLoops.isNotEmpty()) {
            // If polygonization fails, still return the largest closed loop.
            val biggest = closedLoops.maxByOrNull { it.length }
            outerLoop = biggest?.let { ringToFLine(it, close = true) }
            for (loop in closedLoops) {
                if (loop !== biggest) innerLoops += ringToFLine(loop, close = true)
            }
        }

        val lines = openCurves
            .filter { it.length > EPS }
            .map { lineToFLine(it) }

        return FLineSplit(outerLoop = outerLoop, innerLoops = innerLoops, lines = lines)
    }

    internal fun toMultiLineString(line: FLine): MultiLineString? {
        val segments = flattenShape(FLinesAndJavaShapes.flineToJavaShape_notThickened(line))
        if (segments.isEmpty()) return null
        return GF.createMultiLineString(segments.toTypedArray())
    }

    internal fun geometryToFLine(geom: Geometry): FLine {
        return FLinesAndJavaShapes.javaShapeToFLine(geometryToGeneralPath(geom))
    }

    internal fun geometryToGeneralPath(geom: Geometry): GeneralPath {
        val gp = GeneralPath()

        fun appendLineString(ls: LineString, close: Boolean) {
            val coords = ls.coordinates
            if (coords.isEmpty()) return
            gp.moveTo(coords[0].x.toFloat(), coords[0].y.toFloat())
            for (i in 1 until coords.size) gp.lineTo(coords[i].x.toFloat(), coords[i].y.toFloat())
            if (close) gp.closePath()
        }

        fun walk(g: Geometry) {
            when (g) {
                is org.locationtech.jts.geom.Polygon -> {
                    appendLineString(g.exteriorRing as LineString, close = true)
                    for (i in 0 until g.numInteriorRing) {
                        appendLineString(g.getInteriorRingN(i) as LineString, close = true)
                    }
                }

                is LineString -> appendLineString(g, close = false)
                is GeometryCollection -> for (i in 0 until g.numGeometries) walk(g.getGeometryN(i))
            }
        }

        walk(geom)
        return gp
    }

    private fun mergeLineStrings(geometry: Geometry): List<LineString> {
        val merger = LineMerger()

        fun addLines(g: Geometry) {
            when (g) {
                is LineString -> merger.add(g)
                is GeometryCollection -> for (i in 0 until g.numGeometries) addLines(g.getGeometryN(i))
            }
        }

        addLines(geometry)
        @Suppress("UNCHECKED_CAST")
        return (merger.mergedLineStrings as Collection<LineString>).toList()
    }

    private fun flattenShape(shape: Shape): List<LineString> {
        val pit = FlatteningPathIterator(shape.getPathIterator(null), flatness)
        val coords = DoubleArray(6)
        val out = mutableListOf<LineString>()
        val current = mutableListOf<Coordinate>()
        var moveStart: Coordinate? = null

        fun appendIfNotDuplicate(c: Coordinate) {
            val last = current.lastOrNull()
            if (last == null || !last.equals2D(c)) current += c
        }

        fun flush() {
            if (current.size > 1) {
                out += GF.createLineString(current.toTypedArray())
            }
            current.clear()
            moveStart = null
        }

        while (!pit.isDone) {
            when (pit.currentSegment(coords)) {
                PathIterator.SEG_MOVETO -> {
                    flush()
                    val c = Coordinate(coords[0], coords[1])
                    current += c
                    moveStart = c
                }

                PathIterator.SEG_LINETO -> appendIfNotDuplicate(Coordinate(coords[0], coords[1]))
                PathIterator.SEG_CLOSE -> {
                    val start = moveStart
                    if (start != null) appendIfNotDuplicate(Coordinate(start.x, start.y))
                    flush()
                }
            }
            pit.next()
        }
        flush()
        return out
    }

    private fun lineToFLine(line: LineString): FLine {
        val f = FLine()
        val coords = line.coordinates
        if (coords.isEmpty()) return f
        f.moveTo(coords[0].x.toFloat(), coords[0].y.toFloat())
        for (i in 1 until coords.size) {
            f.lineTo(coords[i].x.toFloat(), coords[i].y.toFloat())
        }
        return f
    }

    private fun ringToFLine(ring: LineString, close: Boolean): FLine {
        val f = FLine()
        val coords = ring.coordinates
        if (coords.isEmpty()) return f
        f.moveTo(coords[0].x.toFloat(), coords[0].y.toFloat())
        val last = if (close && coords.size > 1 && coords.first().equals2D(coords.last())) coords.lastIndex - 1 else coords.lastIndex
        for (i in 1..last) {
            f.lineTo(coords[i].x.toFloat(), coords[i].y.toFloat())
        }
        if (close) f.close()
        return f
    }
}
