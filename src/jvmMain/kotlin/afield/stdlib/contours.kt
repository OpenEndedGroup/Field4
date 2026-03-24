package afield.stdlib

import afield.stdlib.Vec2
import afield.stdlib.vec
import kotlin.math.ceil
import kotlin.math.max

private data class Vec2Segment(val a: Vec2, val b: Vec2)

/**
 * Scalar field f(x, y) -> value.
 * You can use a lambda or method reference, e.g. rbf::interpolate2D.
 */
typealias ScalarField2D = (Double, Double) -> Double

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
