package afield.stdlib

import afield.Rect
import afield.graphics.FLine
import afield.graphics.cubicSplineFrom
import afield.graphics.polylineFrom
import afield.stdlib.Vec2
import afield.stdlib.vec
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

private data class Vec2Segment(val a: Vec2, val b: Vec2)
private data class PointEdgeGraph2D(val points: List<Vec2>, val edges: List<Pair<Int, Int>>)

/**
 * Scalar field f(x, y) -> value.
 * You can use a lambda or method reference, e.g. rbf::interpolate2D.
 */
typealias ScalarField2D = (Double, Double) -> Double

/**
 * Build a live scalar field from a graph of 2D points plus edges between them.
 *
 * Each point contributes a weighted disc term and each edge contributes a
 * weighted capsule term. The field is the union of those primitives:
 *
 * `max(pointRadius * pointWeight_i - distanceToPoint_i,
 *      edgeRadius * edgeWeight_j - distanceToSegment_j)`
 *
 * Positive values are inside the graph "tube", `0.0` is the boundary, and
 * negative values are outside. This makes `iso = 0.0` the natural choice for
 * [generateIsoContours].
 *
 * The returned field reads the supplied [points] list each time it is sampled,
 * so mutating those `Vec2`s updates the field live.
 */
fun pointEdgeScalarField2D(
    points: List<Vec2>,
    edges: List<Pair<Int, Int>>,
    pointRadius: Double,
    edgeRadius: Double,
    pointWeights: List<Double>? = null,
    edgeWeights: List<Double>? = null
): ScalarField2D {
    require(pointRadius >= 0.0) { "pointRadius must be >= 0" }
    require(edgeRadius >= 0.0) { "edgeRadius must be >= 0" }
    pointWeights?.let {
        require(it.size == points.size) { "pointWeights must match points.size" }
        require(it.all { w -> w >= 0.0 }) { "pointWeights must all be >= 0" }
    }
    edgeWeights?.let {
        require(it.size == edges.size) { "edgeWeights must match edges.size" }
        require(it.all { w -> w >= 0.0 }) { "edgeWeights must all be >= 0" }
    }
    edges.forEachIndexed { index, (a, b) ->
        require(a in points.indices) { "edge $index references missing point index $a" }
        require(b in points.indices) { "edge $index references missing point index $b" }
    }

    if (points.isEmpty() && edges.isEmpty()) return { _, _ -> Double.NEGATIVE_INFINITY }

    return { x, y ->
        var best = Double.NEGATIVE_INFINITY

        points.forEachIndexed { index, point ->
            val weight = pointWeights?.get(index) ?: 1.0
            val value = pointRadius * weight - distanceToPoint(x, y, point)
            if (value > best) best = value
        }

        edges.forEachIndexed { index, (a, b) ->
            val weight = edgeWeights?.get(index) ?: 1.0
            val value = edgeRadius * weight - distanceToSegment(x, y, points[a], points[b])
            if (value > best) best = value
        }

        best
    }
}

fun pointEdgeScalarField2D(
    points: List<Vec2>,
    edges: List<Pair<Int, Int>>,
    radius: Double,
    pointWeights: List<Double>? = null,
    edgeWeights: List<Double>? = null
): ScalarField2D = pointEdgeScalarField2D(
    points = points,
    edges = edges,
    pointRadius = radius,
    edgeRadius = radius,
    pointWeights = pointWeights,
    edgeWeights = edgeWeights
)

fun pointEdgeScalarField2D(
    line: FLine,
    pointRadius: Double,
    edgeRadius: Double,
    samplesPerCubic: Int = 24,
    pointWeights: List<Double>? = null,
    edgeWeights: List<Double>? = null
): ScalarField2D {
    val graph = pointEdgeGraph2D(line, samplesPerCubic)
    return pointEdgeScalarField2D(
        points = graph.points,
        edges = graph.edges,
        pointRadius = pointRadius,
        edgeRadius = edgeRadius,
        pointWeights = pointWeights,
        edgeWeights = edgeWeights
    )
}

fun pointEdgeScalarField2D(
    line: FLine,
    radius: Double,
    samplesPerCubic: Int = 24,
    pointWeights: List<Double>? = null,
    edgeWeights: List<Double>? = null
): ScalarField2D = pointEdgeScalarField2D(
    line = line,
    pointRadius = radius,
    edgeRadius = radius,
    samplesPerCubic = samplesPerCubic,
    pointWeights = pointWeights,
    edgeWeights = edgeWeights
)

/**
 * Generate iso-contours for a scalar field using marching squares.
 *
 * @param field    function f(x, y) returning a scalar value
 * @param xMin     left bound of domain
 * @param xMax     right bound of domain
 * @param yMin     bottom bound of domain
 * @param yMax     top bound of domain
 * @param nx       number of cells in x (>= 1)
 * @param ny       number of cells in y (>= 1)
 * @param iso      iso-value to extract
 *
 * @return list of polylines; each polyline is a List<Vec2>.
 */
fun generateIsoContours(
    field: ScalarField2D,
    xMin: Double,
    xMax: Double,
    yMin: Double,
    yMax: Double,
    nx: Int,
    ny: Int,
    iso: Double
): List<List<Vec2>> {
    require(nx >= 1 && ny >= 1) { "nx and ny must be >= 1" }

    // Grid spacing
    val dx = (xMax - xMin) / nx.toDouble()
    val dy = (yMax - yMin) / ny.toDouble()

    // We'll store actual doubles separately for clarity
    val scalar = Array(nx + 1) { DoubleArray(ny + 1) }

    for (i in 0..nx) {
        val x = xMin + i * dx
        for (j in 0..ny) {
            val y = yMin + j * dy
            scalar[i][j] = field(x, y)
        }
    }

    val segments = mutableListOf<Vec2Segment>()

    // Helper: does edge (v1, v2) cross iso?
    fun crosses(v1: Double, v2: Double): Boolean {
        // Strict sign change; if you want to capture "touching" you can relax this.
        return (v1 < iso && v2 > iso) || (v1 > iso && v2 < iso)
    }

    // Linear interpolation along an edge
    fun interp(p1: Vec2, p2: Vec2, v1: Double, v2: Double): Vec2 {
        val denom = (v2 - v1)
        val t = if (denom == 0.0) 0.5 else (iso - v1) / denom
        return Vec2(
            (p1.x + t * (p2.x - p1.x)).toFloat(),
             (p1.y + t * (p2.y - p1.y)).toFloat()
        )
    }

    // March over cells
    for (i in 0 until nx) {
        val x0 = xMin + i * dx
        val x1 = x0 + dx
        for (j in 0 until ny) {
            val y0 = yMin + j * dy
            val y1 = y0 + dy

            // Vertex positions (counter-clockwise):
            // v0: (x0, y0) bottom-left
            // v1: (x1, y0) bottom-right
            // v2: (x1, y1) top-right
            // v3: (x0, y1) top-left
            val v0 = scalar[i][j]
            val v1 = scalar[i + 1][j]
            val v2 = scalar[i + 1][j + 1]
            val v3 = scalar[i][j + 1]

            val p0 = vec(x0, y0)
            val p1 = vec(x1, y0)
            val p2 = vec(x1, y1)
            val p3 = vec(x0, y1)

            // Collect intersections along edges in a consistent order:
            // bottom (p0-p1), right (p1-p2), top (p2-p3), left (p3-p0)
            val pts = mutableListOf<Vec2>()

            if (crosses(v0, v1)) pts += interp(p0, p1, v0, v1)
            if (crosses(v1, v2)) pts += interp(p1, p2, v1, v2)
            if (crosses(v2, v3)) pts += interp(p2, p3, v2, v3)
            if (crosses(v3, v0)) pts += interp(p3, p0, v3, v0)

            when (pts.size) {
                0 -> {
                    // No contour through this cell
                }
                2 -> {
                    // Single segment through cell
                    segments += Vec2Segment(pts[0], pts[1])
                }
                4 -> {
                    // Ambiguous saddle case: split as two segments (0-1, 2-3).
                    // This is a standard but arbitrary resolution.
                    segments += Vec2Segment(pts[0], pts[1])
                    segments += Vec2Segment(pts[2], pts[3])
                }
                else -> {
                    // Rare degenerate case; you can log if you want.
                    // We'll just pair them sequentially.
                    for (k in 0 until (pts.size - 1) step 2) {
                        segments += Vec2Segment(pts[k], pts[k + 1])
                    }
                }
            }
        }
    }

    // Now stitch segments into polylines
    return stitchSegmentsIntoPolylines(segments, max(dx, dy))
}

/**
 * Convenience overload taking approximate spatial resolution instead of nx, ny.
 *
 * @param spatialStep target cell size (same in x and y); will be rounded so that
 *             nx,ny are integers.
 */
fun generateIsoContoursWithStep(
    field: ScalarField2D,
    xMin: Double,
    xMax: Double,
    yMin: Double,
    yMax: Double,
    spatialStep: Double,
    value: Double
): List<List<Vec2>> {
    require(spatialStep > 0.0) { "step must be > 0" }
    val nx = max(1, ceil((xMax - xMin) / spatialStep).toInt())
    val ny = max(1, ceil((yMax - yMin) / spatialStep).toInt())
    return generateIsoContours(field, xMin, xMax, yMin, yMax, nx, ny, value)
}

/**
 * Convenience overload that turns a 2D [FLine] into a point/edge scalar field,
 * expands its bounds by the effective tube radius, and extracts contours.
 */
fun generateIsoContours(
    line: FLine,
    pointRadius: Double,
    edgeRadius: Double,
    spatialStep: Double,
    iso: Double = 0.0,
    padding: Double? = null,
    samplesPerCubic: Int = 24,
    pointWeights: List<Double>? = null,
    edgeWeights: List<Double>? = null
): List<List<Vec2>> {
    if (line.nodes.isEmpty()) return emptyList()
    val graph = pointEdgeGraph2D(line, samplesPerCubic)
    val field = pointEdgeScalarField2D(
        points = graph.points,
        edges = graph.edges,
        pointRadius = pointRadius,
        edgeRadius = edgeRadius,
        pointWeights = pointWeights,
        edgeWeights = edgeWeights
    )
    val bounds = expandedIsoBounds(
        line = line,
        pointRadius = pointRadius,
        edgeRadius = edgeRadius,
        spatialStep = spatialStep,
        padding = padding,
        pointWeights = pointWeights,
        edgeWeights = edgeWeights
    )
    return generateIsoContoursWithStep(
        field = field,
        xMin = bounds.x,
        xMax = bounds.x + bounds.w,
        yMin = bounds.y,
        yMax = bounds.y + bounds.h,
        spatialStep = spatialStep,
        value = iso
    )
}

fun generateIsoContours(
    line: FLine,
    radius: Double,
    spatialStep: Double,
    iso: Double = 0.0,
    padding: Double? = null,
    samplesPerCubic: Int = 24,
    pointWeights: List<Double>? = null,
    edgeWeights: List<Double>? = null
): List<List<Vec2>> = generateIsoContours(
    line = line,
    pointRadius = radius,
    edgeRadius = radius,
    spatialStep = spatialStep,
    iso = iso,
    padding = padding,
    samplesPerCubic = samplesPerCubic,
    pointWeights = pointWeights,
    edgeWeights = edgeWeights
)

/**
 * Smooth marching-squares polylines into cubic curves.
 *
 * Contours that terminate on the sampling bounds are treated as open; the rest
 * are assumed to be closed loops.
 */
fun smoothIsoContours(
    contours: List<List<Vec2>>,
    xMin: Double,
    xMax: Double,
    yMin: Double,
    yMax: Double,
    tension: Float = 1f,
    boundaryEpsilon: Double = 1e-6
): List<FLine> {
    return contours.map { contour ->
        val closed = contourLikelyClosed(contour, xMin, xMax, yMin, yMax, boundaryEpsilon)
        when {
            contour.isEmpty() -> FLine()
            contour.size < 3 -> polylineFrom(contour, closed = closed)
            else -> cubicSplineFrom(contour, closed = closed, tension = tension)
        }
    }
}

fun generateSmoothIsoContours(
    line: FLine,
    pointRadius: Double,
    edgeRadius: Double,
    spatialStep: Double,
    iso: Double = 0.0,
    padding: Double? = null,
    samplesPerCubic: Int = 24,
    pointWeights: List<Double>? = null,
    edgeWeights: List<Double>? = null,
    tension: Float = 1f,
    boundaryEpsilon: Double = spatialStep * 0.75
): List<FLine> {
    if (line.nodes.isEmpty()) return emptyList()
    val bounds = expandedIsoBounds(
        line = line,
        pointRadius = pointRadius,
        edgeRadius = edgeRadius,
        spatialStep = spatialStep,
        padding = padding,
        pointWeights = pointWeights,
        edgeWeights = edgeWeights
    )
    val contours = generateIsoContours(
        line = line,
        pointRadius = pointRadius,
        edgeRadius = edgeRadius,
        spatialStep = spatialStep,
        iso = iso,
        padding = padding,
        samplesPerCubic = samplesPerCubic,
        pointWeights = pointWeights,
        edgeWeights = edgeWeights
    )
    return smoothIsoContours(
        contours = contours,
        xMin = bounds.x,
        xMax = bounds.x + bounds.w,
        yMin = bounds.y,
        yMax = bounds.y + bounds.h,
        tension = tension,
        boundaryEpsilon = boundaryEpsilon
    )
}

fun generateSmoothIsoContours(
    line: FLine,
    radius: Double,
    spatialStep: Double,
    iso: Double = 0.0,
    padding: Double? = null,
    samplesPerCubic: Int = 24,
    pointWeights: List<Double>? = null,
    edgeWeights: List<Double>? = null,
    tension: Float = 1f,
    boundaryEpsilon: Double = spatialStep * 0.75
): List<FLine> = generateSmoothIsoContours(
    line = line,
    pointRadius = radius,
    edgeRadius = radius,
    spatialStep = spatialStep,
    iso = iso,
    padding = padding,
    samplesPerCubic = samplesPerCubic,
    pointWeights = pointWeights,
    edgeWeights = edgeWeights,
    tension = tension,
    boundaryEpsilon = boundaryEpsilon
)

/* ==========================
   Segment stitching
   ========================== */

/**
 * Turn disjoint line segments into polylines by greedily joining endpoints
 * that are closer than a small epsilon.
 *
 * This is O(m^2) in the number of segments, which is fine for
 * moderate grids. If you crank resolution way up and it becomes
 * a bottleneck, a spatial hash / k-d tree would fix it.
 */
private fun stitchSegmentsIntoPolylines(
    segments: List<Vec2Segment>,
    gridScale: Double
): List<List<Vec2>> {
    if (segments.isEmpty()) return emptyList()

    val unused = segments.toMutableList()
    val contours = mutableListOf<MutableList<Vec2>>()

    // Epsilon scaled to grid size
    val eps = gridScale * 1e-6

    fun pointsClose(a: Vec2, b: Vec2): Boolean {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy <= eps * eps
    }

    while (unused.isNotEmpty()) {
        val seg = unused.removeLast()
        val poly = mutableListOf(seg.a, seg.b)

        var extended = true
        while (extended) {
            extended = false
            var i = unused.size - 1
            while (i >= 0) {
                val s = unused[i]
                val start = poly.first()
                val end = poly.last()

                when {
                    pointsClose(s.a, end) -> {
                        poly.add(s.b)
                        unused.removeAt(i)
                        extended = true
                    }
                    pointsClose(s.b, end) -> {
                        poly.add(s.a)
                        unused.removeAt(i)
                        extended = true
                    }
                    pointsClose(s.a, start) -> {
                        poly.add(0, s.b)
                        unused.removeAt(i)
                        extended = true
                    }
                    pointsClose(s.b, start) -> {
                        poly.add(0, s.a)
                        unused.removeAt(i)
                        extended = true
                    }
                }
                i--
            }
        }

        // If the curve is closed, drop duplicated endpoint
        if (poly.size > 2 && pointsClose(poly.first(), poly.last())) {
            poly.removeAt(poly.size - 1)
        }

        contours += poly
    }

    return contours
}

private fun distanceToPoint(x: Double, y: Double, point: Vec2): Double {
    val dx = x - point.x.toDouble()
    val dy = y - point.y.toDouble()
    return sqrt(dx * dx + dy * dy)
}

private fun distanceToSegment(x: Double, y: Double, a: Vec2, b: Vec2): Double {
    val ax = a.x.toDouble()
    val ay = a.y.toDouble()
    val bx = b.x.toDouble()
    val by = b.y.toDouble()

    val abx = bx - ax
    val aby = by - ay
    val ab2 = abx * abx + aby * aby
    if (ab2 <= 1e-12) return distanceToPoint(x, y, a)

    val apx = x - ax
    val apy = y - ay
    val t = ((apx * abx + apy * aby) / ab2).coerceIn(0.0, 1.0)
    val px = ax + t * abx
    val py = ay + t * aby
    val dx = x - px
    val dy = y - py
    return sqrt(dx * dx + dy * dy)
}

private fun pointEdgeGraph2D(line: FLine, samplesPerCubic: Int): PointEdgeGraph2D {
    require(samplesPerCubic >= 1) { "samplesPerCubic must be >= 1" }
    if (line.nodes.isEmpty()) return PointEdgeGraph2D(emptyList(), emptyList())

    val points = mutableListOf<Vec2>()
    val edges = mutableListOf<Pair<Int, Int>>()
    var currentIndex: Int? = null
    var subpathStartIndex: Int? = null
    var currentPoint: Vec2? = null

    fun addPoint(point: Vec2): Int {
        points += Vec2(point)
        return points.lastIndex
    }

    fun connect(from: Int, to: Int) {
        if (from == to) return
        if (pointsAlmostEqual(points[from], points[to])) return
        edges += from to to
    }

    line.nodes.forEach { node ->
        when (node) {
            is FLine.MoveTo -> {
                val point = vec(node.to.x, node.to.y)
                val index = addPoint(point)
                currentIndex = index
                subpathStartIndex = index
                currentPoint = point
            }

            is FLine.LineTo -> {
                val point = vec(node.to.x, node.to.y)
                val index = addPoint(point)
                val previous = currentIndex
                if (previous != null) connect(previous, index)
                else subpathStartIndex = index
                currentIndex = index
                currentPoint = point
            }

            is FLine.CubicTo -> {
                val start = currentPoint
                val previous = currentIndex
                if (start == null || previous == null) {
                    val point = vec(node.to.x, node.to.y)
                    val index = addPoint(point)
                    currentIndex = index
                    subpathStartIndex = index
                    currentPoint = point
                } else {
                    var lastIndex: Int = previous
                    for (sampleIndex in 1..samplesPerCubic) {
                        val t = sampleIndex / samplesPerCubic.toDouble()
                        val point = cubicPoint2D(
                            a = start,
                            c1 = vec(node.c1.x, node.c1.y),
                            c2 = vec(node.c2.x, node.c2.y),
                            b = vec(node.to.x, node.to.y),
                            t = t
                        )
                        val index = addPoint(point)
                        connect(lastIndex, index)
                        lastIndex = index
                    }
                    currentIndex = lastIndex
                    currentPoint = points[lastIndex]
                }
            }

            is FLine.Close -> {
                val start = subpathStartIndex
                val end = currentIndex
                if (start != null && end != null) connect(end, start)
                currentIndex = start
                currentPoint = start?.let { points[it] }
            }
        }
    }

    return PointEdgeGraph2D(points = points, edges = edges)
}

private fun expandedIsoBounds(
    line: FLine,
    pointRadius: Double,
    edgeRadius: Double,
    spatialStep: Double,
    padding: Double?,
    pointWeights: List<Double>?,
    edgeWeights: List<Double>?
): Rect {
    val base = line.fast2DBounds()
    val maxPointExtent = pointRadius * (pointWeights?.maxOrNull() ?: 1.0)
    val maxEdgeExtent = edgeRadius * (edgeWeights?.maxOrNull() ?: 1.0)
    val pad = padding ?: (2.0 * max(maxPointExtent, maxEdgeExtent)) + spatialStep
    return Rect(
        x = base.x - pad,
        y = base.y - pad,
        w = base.w + pad * 2.0,
        h = base.h + pad * 2.0
    )
}

private fun contourLikelyClosed(
    contour: List<Vec2>,
    xMin: Double,
    xMax: Double,
    yMin: Double,
    yMax: Double,
    boundaryEpsilon: Double
): Boolean {
    if (contour.size < 3) return false
    val first = contour.first()
    val last = contour.last()
    return !pointOnBounds(first, xMin, xMax, yMin, yMax, boundaryEpsilon) &&
        !pointOnBounds(last, xMin, xMax, yMin, yMax, boundaryEpsilon)
}

private fun pointOnBounds(
    point: Vec2,
    xMin: Double,
    xMax: Double,
    yMin: Double,
    yMax: Double,
    epsilon: Double
): Boolean {
    val x = point.x.toDouble()
    val y = point.y.toDouble()
    return kotlin.math.abs(x - xMin) <= epsilon ||
        kotlin.math.abs(x - xMax) <= epsilon ||
        kotlin.math.abs(y - yMin) <= epsilon ||
        kotlin.math.abs(y - yMax) <= epsilon
}

private fun pointsAlmostEqual(a: Vec2, b: Vec2, epsilon: Double = 1e-9): Boolean {
    val dx = a.x.toDouble() - b.x.toDouble()
    val dy = a.y.toDouble() - b.y.toDouble()
    return dx * dx + dy * dy <= epsilon * epsilon
}

private fun cubicPoint2D(a: Vec2, c1: Vec2, c2: Vec2, b: Vec2, t: Double): Vec2 {
    val omt = 1.0 - t
    val omt2 = omt * omt
    val omt3 = omt2 * omt
    val t2 = t * t
    val t3 = t2 * t
    return vec(
        a.x.toDouble() * omt3 +
            3.0 * c1.x.toDouble() * t * omt2 +
            3.0 * c2.x.toDouble() * t2 * omt +
            b.x.toDouble() * t3,
        a.y.toDouble() * omt3 +
            3.0 * c1.y.toDouble() * t * omt2 +
            3.0 * c2.y.toDouble() * t2 * omt +
            b.y.toDouble() * t3
    )
}
