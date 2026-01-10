package afield.graphics

import afield.stdlib.*
import field.graphics.FLinesAndJavaShapes
import field.graphics.FLinesAndJavaShapes.CubicSegment3
import org.joml.Quaternionf
import org.joml.plus
import java.awt.geom.Area
import kotlin.math.*

/**
 * Comprehensive computational geometry utilities for FLine operations implemented as extension functions.
 * Provides transformations, projections, intersections, decompositions, curvature analyses,
 * tangents, osculating planes, offsets, and a rich set of 2D and 3D geometric algorithms.
 *
 * Functions that return new FLine instances use a "by" prefix to indicate non-mutating operations:
 * - `byWaving()` instead of `wave()`
 * - `byOffsetting()` instead of `offset()`
 */

// ==================== 2D TRANSFORMATIONS ====================

/**
 * Rotate an FLine around a point in 2D (preserves Z coordinate).
 * @param angle rotation angle in radians
 * @param center center of rotation (defaults to origin)
 */
fun FLine.byRotating2D(angle: Float, center: Vec2 = vec(0f, 0f)): FLine {
    val cos = cos(angle)
    val sin = sin(angle)
    return this.transform { p ->
        val dx = p.x - center.x
        val dy = p.y - center.y
        vec(
            center.x + dx * cos - dy * sin,
            center.y + dx * sin + dy * cos,
            p.z
        )
    }
}

/**
 * Scale an FLine from a center point.
 * @param scale uniform scale factor
 * @param center center of scaling (defaults to origin)
 */
fun FLine.byScaling2D(scale: Float, center: Vec2 = vec(0f, 0f)): FLine {
    return this.transform { p ->
        vec(
            center.x + (p.x - center.x) * scale,
            center.y + (p.y - center.y) * scale,
            p.z
        )
    }
}

/**
 * Scale an FLine with different factors for X and Y.
 */
fun FLine.byScaling2D(scaleX: Float, scaleY: Float, center: Vec2 = vec(0f, 0f)): FLine {
    return this.transform { p ->
        vec(
            center.x + (p.x - center.x) * scaleX,
            center.y + (p.y - center.y) * scaleY,
            p.z
        )
    }
}

/**
 * Reflect an FLine across a line defined by two points.
 */
fun FLine.byReflecting2D(linePoint1: Vec2, linePoint2: Vec2): FLine {
    val dx = linePoint2.x - linePoint1.x
    val dy = linePoint2.y - linePoint1.y
    val lenSq = dx * dx + dy * dy

    return this.transform { p ->
        val px = p.x - linePoint1.x
        val py = p.y - linePoint1.y
        val t = (px * dx + py * dy) / lenSq
        val closestX = linePoint1.x + t * dx
        val closestY = linePoint1.y + t * dy
        vec(
            2 * closestX - p.x,
            2 * closestY - p.y,
            p.z
        )
    }
}

/**
 * Shear transformation in 2D.
 * @param shearX horizontal shear factor
 * @param shearY vertical shear factor
 */
fun FLine.byShearing2D(shearX: Float, shearY: Float): FLine {
    return this.transform { p ->
        vec(
            p.x + shearX * p.y,
            p.y + shearY * p.x,
            p.z
        )
    }
}

// ==================== 3D TRANSFORMATIONS ====================

/**
 * Rotate an FLine around an arbitrary 3D axis.
 * @param angle rotation angle in radians
 * @param axis rotation axis (will be normalized)
 * @param center center of rotation (defaults to origin)
 */
fun FLine.byRotating3D(angle: Float, axis: Vec3, center: Vec3 = vec(0f, 0f, 0f)): FLine {
    val q = Quaternionf().setAngleAxis(angle, axis.x, axis.y, axis.z)
    return this.transform { p ->
        val translated = Vec3(p).sub(center)
        q.transform(translated)
        translated.add(center)
    }
}

/**
 * Project this FLine onto a plane with perspective.
 * @param cameraPos position of the camera/eye
 * @param planePoint a point on the projection plane
 * @param planeNormal normal of the projection plane
 * @return a new perspective-projected FLine
 */
fun FLine.byPerspectiveProjecting(
    cameraPos: Vec3,
    planePoint: Vec3 = vec(0f, 0f, 0f),
    planeNormal: Vec3 = vec(0f, 0f, 1f)
): FLine {
    val normal = Vec3(planeNormal).normalize()
    val d = -normal.dot(planePoint)

    return this.transform { p ->
        val ray = Vec3(p).sub(cameraPos)
        val t = -(normal.dot(cameraPos) + d) / normal.dot(ray)
        if (t > 0) {
            Vec3(cameraPos).add(ray.mul(t))
        } else {
            Vec3(p) // Point behind camera, keep original
        }
    }
}

/**
 * Project this FLine onto a plane orthographically.
 * @return a new orthographically-projected FLine
 */
fun FLine.byOrthographicProjecting(
    planePoint: Vec3 = vec(0f, 0f, 0f),
    planeNormal: Vec3 = vec(0f, 0f, 1f)
): FLine {
    val normal = Vec3(planeNormal).normalize()

    return this.transform { p ->
        val v = Vec3(p).sub(planePoint)
        val dist = v.dot(normal)
        Vec3(p).sub(normal.mul(dist, Vec3()))
    }
}

// ==================== CURVE OFFSETTING ====================

/**
 * Create an offset curve at a specified distance from the original.
 * Uses normal vectors to offset the curve. For 2D curves, the offset is perpendicular to the curve.
 * @param distance offset distance (positive = right/outward, negative = left/inward)
 * @param samples number of samples per cubic segment
 */
fun FLine.byOffsetting(distance: Float, samples: Int = 50): FLine {
    val result = FLine()
    val cursor = this.cursor(0.01f)
    var first = true

    for (i in 0..samples * (this.nodes.size - 1)) {
        val t = i.toFloat() / (samples * (this.nodes.size - 1))
        cursor.setT(t * (this.nodes.size - 1))

        val pos = cursor.position()
        val normal = cursor.normal2()

        if (normal != null) {
            val offsetPos = vec(
                pos.x + normal.x * distance,
                pos.y + normal.y * distance,
                pos.z
            )

            if (first) {
                result.moveTo(offsetPos)
                first = false
            } else {
                result.lineTo(offsetPos)
            }
        }
    }

    if (this.nodes.last() is FLine.Close) {
        result.close()
    }

    return result
}

/**
 * Create parallel curves on both sides of the original curve.
 * @return Pair of FLines (left offset, right offset)
 */
fun FLine.byCreatingParallelCurves(distance: Float, samples: Int = 50): Pair<FLine, FLine> {
    return Pair(
        byOffsetting(-distance, samples),
        byOffsetting(distance, samples)
    )
}

// ==================== CURVE SMOOTHING ====================

/**
 * Smooth a curve using Gaussian kernel smoothing.
 * @param sigma smoothing strength (larger = smoother)
 * @param samples number of samples to use
 */
fun FLine.byGaussianSmoothing(sigma: Float, samples: Int = 100): FLine {
    val points = this.sampleByNumber(samples)
    val smoothed = mutableListOf<Vec3>()
    val radius = (3 * sigma).toInt().coerceAtLeast(1)

    for (i in points.indices) {
        var sum = Vec3()
        var weightSum = 0f

        for (j in maxOf(0, i - radius)..minOf(points.size - 1, i + radius)) {
            val dist = abs(i - j).toFloat()
            val weight = exp(-(dist * dist) / (2 * sigma * sigma))
            sum.add(Vec3(points[j]).mul(weight))
            weightSum += weight
        }

        smoothed.add(sum.div(weightSum))
    }

    val result = FLine()
    if (smoothed.isNotEmpty()) {
        result.moveTo(smoothed[0])
        for (i in 1 until smoothed.size) {
            result.lineTo(smoothed[i])
        }
    }

    return result
}

/**
 * Simplify a curve using the Ramer-Douglas-Peucker algorithm.
 * @param epsilon maximum distance threshold for point elimination
 */
fun FLine.bySimplifying(epsilon: Float): FLine {
    val points = mutableListOf<Vec3>()
    this.nodes.forEach { node ->
        points.add(Vec3(node.to))
    }

    if (points.size < 3) return this.copy()

    val keep = rdpSimplify(points, epsilon)

    val result = FLine()
    if (keep.isNotEmpty()) {
        result.moveTo(keep[0])
        for (i in 1 until keep.size) {
            result.lineTo(keep[i])
        }
    }

    return result
}

private fun rdpSimplify(points: List<Vec3>, epsilon: Float): List<Vec3> {
    if (points.size < 3) return points

    var maxDist = 0f
    var maxIndex = 0

    for (i in 1 until points.size - 1) {
        val dist = perpendicularDistance(points[i], points[0], points[points.size - 1])
        if (dist > maxDist) {
            maxDist = dist
            maxIndex = i
        }
    }

    return if (maxDist > epsilon) {
        val left = rdpSimplify(points.subList(0, maxIndex + 1), epsilon)
        val right = rdpSimplify(points.subList(maxIndex, points.size), epsilon)
        left.dropLast(1) + right
    } else {
        listOf(points.first(), points.last())
    }
}

private fun perpendicularDistance(point: Vec3, lineStart: Vec3, lineEnd: Vec3): Float {
    val dx = lineEnd.x - lineStart.x
    val dy = lineEnd.y - lineStart.y
    val dz = lineEnd.z - lineStart.z
    val lenSq = dx * dx + dy * dy + dz * dz

    if (lenSq == 0f) return point.distance(lineStart)

    val t = ((point.x - lineStart.x) * dx +
             (point.y - lineStart.y) * dy +
             (point.z - lineStart.z) * dz) / lenSq

    val tClamped = t.coerceIn(0f, 1f)
    val projection = Vec3(
        lineStart.x + tClamped * dx,
        lineStart.y + tClamped * dy,
        lineStart.z + tClamped * dz
    )

    return point.distance(projection)
}

// ==================== INTERSECTIONS ====================

/**
 * Find all self-intersections in a curve.
 * @param tolerance intersection detection tolerance
 * @return list of intersection points
 */
fun FLine.findSelfIntersections(tolerance: Float = 1f): List<Vec3> {
    val intersections = mutableListOf<Vec3>()

    for (i in 1 until this.nodes.size) {
        for (j in i + 2 until this.nodes.size) {
            val ni = this.nodes[i]
            val nj = this.nodes[j]

            if (ni is FLine.CubicTo && nj is FLine.CubicTo) {
                val ci = CubicSegment3(this.nodes[i - 1].to, ni.c1, ni.c2, ni.to)
                val cj = CubicSegment3(this.nodes[j - 1].to, nj.c1, nj.c2, nj.to)

                val found = ci.intersection(cj, tolerance)
                found.forEach { pair ->
                    intersections.add(Vec3(pair.first.a).add(pair.first.d).mul(0.5f))
                }
            }
        }
    }

    return intersections
}

/**
 * Find intersections between two curves.
 */
fun FLine.findIntersectionsWith(other: FLine, tolerance: Float = 1f): List<Vec3> {
    return FLinesAndJavaShapes.intersect(this, other)
}

// ==================== CURVATURE ANALYSIS ====================

/**
 * Calculate curvature at regular intervals along the curve.
 * @param samples number of sample points
 * @return list of (position, curvature) pairs
 */
fun FLine.curvatureAnalysis(samples: Int = 100): List<Pair<Vec3, Float>> {
    val result = mutableListOf<Pair<Vec3, Float>>()
    val cursor = this.cursor(0.01f)

    for (i in 0 until samples) {
        val t = i.toFloat() / (samples - 1) * (this.nodes.size - 1)
        cursor.setT(t)

        val pos = cursor.position()
        val tangent = cursor.tangent()
        val acceleration = cursor.acceleration()

        if (tangent != null) {
            if (acceleration != null && acceleration.lengthSquared() > 1e-12) {
                // Smooth curvature
                val cross = tangent.cross(acceleration, Vec3())
                val tanLen = tangent.length()
                val curvature = if (tanLen > 1e-6) cross.length() / tanLen.pow(3) else 0f
                result.add(Pair(pos, curvature))
            } else {
                // Discrete curvature for polylines
                if (i < samples - 1) {
                    val nextT = (i + 1).toFloat() / (samples - 1) * (this.nodes.size - 1)
                    cursor.setT(nextT)
                    val nextPos = cursor.position()
                    val nextTangent = cursor.tangent()
                    cursor.setT(t)
                    if (nextTangent != null && tangent.lengthSquared() > 1e-12 && nextTangent.lengthSquared() > 1e-12) {
                        val dir1 = Vec3(tangent).normalize()
                        val dir2 = Vec3(nextTangent).normalize()
                        val dot = dir1.dot(dir2)
                        val cosAngle = dot.coerceIn(-1f, 1f)
                        val angle = acos(cosAngle)
                        val dist = pos.distance(nextPos)
                        val curvature = if (dist > 1e-6) angle / dist else 0f
                        result.add(Pair(pos, curvature))
                    } else {
                        result.add(Pair(pos, 0f))
                    }
                } else {
                    // Last point, copy from previous
                    val curvature = if (result.isNotEmpty()) result.last().second else 0f
                    result.add(Pair(pos, curvature))
                }
            }
        }
    }

    // Fill in NaN curvatures from adjacent samples
    for (i in result.indices) {
        if (result[i].second.isNaN()) {
            var replacement = Float.NaN
            // Find next non-NaN
            for (j in i + 1 until result.size) {
                if (!result[j].second.isNaN()) {
                    replacement = result[j].second
                    break
                }
            }
            if (replacement.isNaN() && i > 0) {
                // Find previous
                for (j in i - 1 downTo 0) {
                    if (!result[j].second.isNaN()) {
                        replacement = result[j].second
                        break
                    }
                }
            }
            if (!replacement.isNaN()) {
                result[i] = Pair(result[i].first, replacement)
            }
        }
    }

    return result
}

/**
 * Sample the curve with density proportional to curvature.
 * High curvature areas get more samples.
 * @param minSamples minimum number of samples
 * @param maxSamples maximum number of samples
 */
fun FLine.byCurvatureAdaptiveSampling(minSamples: Int = 20, maxSamples: Int = 200): FLine {
    val curvatures = curvatureAnalysis( minSamples)
    val totalCurvature = curvatures.sumOf { it.second.toDouble() }.toFloat()

    if (totalCurvature == 0f) {
        return this.copy()
    }

    val result = FLine()
    result.moveTo(curvatures.first().first)

    for (i in 1 until curvatures.size) {
        val curvature = curvatures[i].second
        val localSamples = (curvature / totalCurvature * maxSamples).toInt().coerceAtLeast(1)

        for (j in 0..localSamples) {
            val t = (i - 1 + j.toFloat() / localSamples) / (curvatures.size - 1) * (this.nodes.size - 1)
            val cursor = this.cursor()
            cursor.setT(t)
            result.lineTo(cursor.position())
        }
    }

    return result
}

/**
 * Find points of maximum curvature.
 * @param threshold minimum curvature to consider (filters noise)
 * @return list of high-curvature points
 */
fun FLine.findHighCurvaturePoints(threshold: Float = 0.1f, samples: Int = 100): List<Vec3> {
    val curvatures = curvatureAnalysis( samples)
    return curvatures.filter { it.second > threshold }.map { it.first }
}

// ==================== DECOMPOSITION ====================

/**
 * Compute the 2D convex hull of the curve using Graham scan.
 * @return FLine representing the convex hull
 */
fun FLine.byConvexHull2D(): FLine {
    val points = this.nodes.map { Vec2(it.to.x, it.to.y) }.toMutableList()

    if (points.size < 3) return this.copy()

    // Find the point with lowest y-coordinate (and leftmost if tie)
    val start = points.minByOrNull { it.y + it.x * 0.0001f }!!
    points.sortBy { p ->
        val dx = p.x - start.x
        val dy = p.y - start.y
        atan2(dy, dx)
    }

    val hull = mutableListOf<Vec2>()

    for (p in points) {
        while (hull.size >= 2) {
            val cross = crossProduct(hull[hull.size - 2], hull[hull.size - 1], p)
            if (cross <= 0) {
                hull.removeAt(hull.size - 1)
            } else {
                break
            }
        }
        hull.add(p)
    }

    val result = FLine()
    if (hull.isNotEmpty()) {
        result.moveTo(hull[0].x, hull[0].y)
        for (i in 1 until hull.size) {
            result.lineTo(hull[i].x, hull[i].y)
        }
        result.lineTo(hull[0].x, hull[0].y) // Close the hull
    }

    return result
}

private fun crossProduct(o: Vec2, a: Vec2, b: Vec2): Float {
    return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
}

/**
 * Split curve at points of inflection (where curvature changes sign).
 */
fun FLine.bySplittingAtInflections(): List<FLine> {
    val cursor = this.cursor()
    val inflections = cursor.inflectionPointsXY()

    if (inflections.isEmpty()) return listOf(copy())

    return cursor.splitT(inflections)
}

// ==================== TANGENT AND NORMAL OPERATIONS ====================

/**
 * Generate tangent lines at regular intervals.
 * @param samples number of tangent lines to generate
 * @param length length of each tangent line
 */
fun FLine.byCreatingTangentLines(samples: Int = 20, length: Float = 10f): List<FLine> {
    val tangents = mutableListOf<FLine>()
    val cursor = this.cursor(0.01f)

    for (i in 0 until samples) {
        val t = i.toFloat() / (samples - 1) * (this.nodes.size - 1)
        cursor.setT(t)

        val pos = cursor.position()
        val tangent = cursor.tangent()

        if (tangent != null) {
            val normalized = Vec3(tangent).normalize()
            val start = Vec3(pos).sub(normalized.mul(length * 0.5f, Vec3()))
            val end = Vec3(pos).add(normalized.mul(length * 0.5f, Vec3()))

            val line = FLine()
            line.moveTo(start)
            line.lineTo(end)
            tangents.add(line)
        }
    }

    return tangents
}

/**
 * Generate normal lines at regular intervals.
 */
fun FLine.byCreatingNormalLines(samples: Int = 20, length: Float = 10f): List<FLine> {
    val normals = mutableListOf<FLine>()
    val cursor = this.cursor(0.01f)

    for (i in 0 until samples) {
        val t = i.toFloat() / (samples - 1) * (this.nodes.size - 1)
        cursor.setT(t)

        val pos = cursor.position()
        val normal = cursor.normal2()

        if (normal != null) {
            val normalized = Vec2(normal).normalize()
            val start = vec(
                pos.x - normalized.x * length * 0.5f,
                pos.y - normalized.y * length * 0.5f,
                pos.z
            )
            val end = vec(
                pos.x + normalized.x * length * 0.5f,
                pos.y + normalized.y * length * 0.5f,
                pos.z
            )

            val line = FLine()
            line.moveTo(start)
            line.lineTo(end)
            normals.add(line)
        }
    }

    return normals
}

/**
 * Generate osculating circles at points along the curve.
 * The osculating circle has the same tangent and curvature as the curve at that point.
 */
fun FLine.byCreatingOsculatingCircles(samples: Int = 20, circleSegments: Int = 32): List<FLine> {
    val circles = mutableListOf<FLine>()
    val cursor = this.cursor(0.01f)

    for (i in 0 until samples) {
        val t = i.toFloat() / (samples - 1) * (this.nodes.size - 1)
        cursor.setT(t)

        val radiusVec = cursor.radiusOfCurvature()

        if (radiusVec != null && radiusVec.isFinite) {
            val pos = cursor.position()
            val radius = radiusVec.length()
            val center = Vec3(pos).add(radiusVec)
            val T = cursor.tangent()

            if (T != null) {
                val N = Vec3(radiusVec).div(radius)  // unit normal
                val B = T.cross(N, Vec3()).normalize()  // unit binormal

                val circle = FLine()
                for (j in 0..circleSegments) {
                    val angle = j.toFloat() / circleSegments * 2 * PI.toFloat()
                    val offset = cos(angle) * N + sin(angle) * B
                    val point = center + radius * offset

                    if (j == 0) {
                        circle.moveTo(point)
                    } else {
                        circle.lineTo(point)
                    }
                }
                circles.add(circle)
            }
        }
    }

    return circles
}

// ==================== BOOLEAN OPERATIONS ====================

/**
 * Union of two curves (using Java2D Area).
 */
fun FLine.byUnionWith(other: FLine): FLine {
    val shape1 = FLinesAndJavaShapes.flineToJavaShape(this)
    val shape2 = FLinesAndJavaShapes.flineToJavaShape(other)
    val area1 = Area(shape1)
    val area2 = Area(shape2)
    area1.add(area2)
    return FLinesAndJavaShapes.javaShapeToFLine(area1)
}

/**
 * Difference of two curves (line1 - line2).
 */
fun FLine.byDifferenceWith(other: FLine): FLine {
    val shape1 = FLinesAndJavaShapes.flineToJavaShape(this)
    val shape2 = FLinesAndJavaShapes.flineToJavaShape(other)
    val area1 = Area(shape1)
    val area2 = Area(shape2)
    area1.subtract(area2)
    return FLinesAndJavaShapes.javaShapeToFLine(area1)
}

/**
 * Intersection of two curves.
 */
fun FLine.byIntersectionWith(other: FLine): FLine {
    val shape1 = FLinesAndJavaShapes.flineToJavaShape(this)
    val shape2 = FLinesAndJavaShapes.flineToJavaShape(other)
    val area1 = Area(shape1)
    val area2 = Area(shape2)
    area1.intersect(area2)
    return FLinesAndJavaShapes.javaShapeToFLine(area1)
}

/**
 * XOR (exclusive or) of two curves.
 */
fun FLine.byExclusiveOrWith(other: FLine): FLine {
    val shape1 = FLinesAndJavaShapes.flineToJavaShape(this)
    val shape2 = FLinesAndJavaShapes.flineToJavaShape(other)
    val area1 = Area(shape1)
    val area2 = Area(shape2)
    area1.exclusiveOr(area2)
    return FLinesAndJavaShapes.javaShapeToFLine(area1)
}

// ==================== GEOMETRIC QUERIES ====================

/**
 * Find the closest point on the curve to a given point.
 * @return Pair of (closest point, t parameter)
 */
fun FLine.closestPoint(point: Vec3): Pair<Vec3, Float> {
    val t = FLinesAndJavaShapes.closestT(this, point)
    val cursor = this.cursor()
    cursor.setT(t)
    return Pair(cursor.position(), t)
}

/**
 * Calculate winding number (for point-in-polygon test).
 * Winding number > 0 means point is inside the curve (if closed).
 * @return winding number
 */
fun FLine.windingNumber(point: Vec2): Int {
    var winding = 0
    var prevPoint: Vec2? = null

    this.nodes.forEach { node ->
        val currentPoint = Vec2(node.to.x, node.to.y)

        if (prevPoint != null) {
            if (prevPoint!!.y <= point.y) {
                if (currentPoint.y > point.y) {
                    if (isLeft(prevPoint!!, currentPoint, point) > 0) {
                        winding++
                    }
                }
            } else {
                if (currentPoint.y <= point.y) {
                    if (isLeft(prevPoint!!, currentPoint, point) < 0) {
                        winding--
                    }
                }
            }
        }

        prevPoint = currentPoint
    }

    return winding
}

private fun isLeft(p0: Vec2, p1: Vec2, p2: Vec2): Float {
    return ((p1.x - p0.x) * (p2.y - p0.y) - (p2.x - p0.x) * (p1.y - p0.y))
}

/**
 * Test if a point is inside a closed curve.
 */
fun FLine.contains(point: Vec2): Boolean {
    return windingNumber(point) != 0
}

/**
 * Calculate the signed area of a 2D closed curve.
 * Positive = counter-clockwise, negative = clockwise.
 */
fun FLine.signedArea2D(): Float {
    var area = 0f
    var prevPoint: Vec2? = null
    var firstPoint: Vec2? = null

    this.nodes.forEach { node ->
        val currentPoint = Vec2(node.to.x, node.to.y)

        if (firstPoint == null) {
            firstPoint = currentPoint
        }

        if (prevPoint != null) {
            area += (prevPoint!!.x * currentPoint.y - currentPoint.x * prevPoint!!.y)
        }

        prevPoint = currentPoint
    }

    // Close the curve
    if (firstPoint != null && prevPoint != null) {
        area += (prevPoint!!.x * firstPoint!!.y - firstPoint!!.x * prevPoint!!.y)
    }

    return area * 0.5f
}

/**
 * Calculate the centroid of a 2D closed curve.
 */
fun FLine.centroid2D(): Vec2 {
    val area = signedArea2D()
    if (abs(area) < 1e-6f) {
        // Degenerate case: return average of points
        var sum = Vec2()
        var count = 0
        this.nodes.forEach {
            sum.add(Vec2(it.to.x, it.to.y))
            count++
        }
        return if (count > 0) sum.div(count.toFloat()) else Vec2()
    }

    var cx = 0f
    var cy = 0f
    var prevPoint: Vec2? = null
    var firstPoint: Vec2? = null

    this.nodes.forEach { node ->
        val currentPoint = Vec2(node.to.x, node.to.y)

        if (firstPoint == null) {
            firstPoint = currentPoint
        }

        if (prevPoint != null) {
            val cross = prevPoint!!.x * currentPoint.y - currentPoint.x * prevPoint!!.y
            cx += (prevPoint!!.x + currentPoint.x) * cross
            cy += (prevPoint!!.y + currentPoint.y) * cross
        }

        prevPoint = currentPoint
    }

    // Close the curve
    if (firstPoint != null && prevPoint != null) {
        val cross = prevPoint!!.x * firstPoint!!.y - firstPoint!!.x * prevPoint!!.y
        cx += (prevPoint!!.x + firstPoint!!.x) * cross
        cy += (prevPoint!!.y + firstPoint!!.y) * cross
    }

    val factor = 1f / (6f * area)
    return vec(cx * factor, cy * factor)
}

// ==================== PATH OPERATIONS ====================

/**
 * Reverse the direction of a curve.
 */
fun FLine.byReversing(): FLine {
    val result = FLine()

    if (this.nodes.isEmpty()) return result

    // Start at the end
    result.moveTo(this.nodes.last().to)

    // Traverse backwards
    for (i in this.nodes.size - 1 downTo 1) {
        val node = this.nodes[i]
        val prevNode = this.nodes[i - 1]

        when (node) {
            is FLine.LineTo -> {
                result.lineTo(prevNode.to)
            }
            is FLine.CubicTo -> {
                // Reverse control points
                result.cubicTo(node.c2, node.c1, prevNode.to)
            }
            is FLine.MoveTo -> {
                result.moveTo(prevNode.to)
            }
            is FLine.Close -> {
                result.close()
            }
        }
    }

    return result
}

/**
 * Concatenate multiple curves into one.
 */
fun concatenate(vararg lines: FLine): FLine {
    val result = FLine()

    lines.forEach { line ->
        result.nodes.forEach { node ->
            result.nodes.add(node.copy())
        }
    }

    return result
}

/**
 * Blend between two curves.
 * @param t blend factor (0 = line1, 1 = line2)
 */
fun FLine.byBlendingWith(other: FLine, t: Float): FLine {
    val samples = maxOf(this.nodes.size, other.nodes.size)
    val points1 = this.sampleByNumber(samples)
    val points2 = other.sampleByNumber(samples)

    val result = FLine()

    for (i in points1.indices) {
        val p1 = points1[i]
        val p2 = if (i < points2.size) points2[i] else points2.last()
        val blended = Vec3(p1).lerp(p2, t)

        if (i == 0) {
            result.moveTo(blended)
        } else {
            result.lineTo(blended)
        }
    }

    return result
}

/**
 * Extract a sub-curve between two t parameters.
 */
fun FLine.byExtracting(tStart: Float, tEnd: Float): FLine {
    val cursor = this.cursor()
    cursor.setT(tStart)
    val split1 = cursor.split()

    if (split1.second == null) return FLine()

    val remaining = split1.second!!
    val cursor2 = remaining.cursor()
    cursor2.setT(tEnd - tStart)
    val split2 = cursor2.split()

    return split2.first ?: FLine()
}

// ==================== ADVANCED CURVE FITTING ====================

/**
 * Fit a circle to a set of points on the curve.
 * @param samples number of points to sample
 * @return Triple of (center, radius, error)
 */
fun FLine.fitCircle(samples: Int = 20): Triple<Vec2, Float, Float> {
    val points = this.sampleByNumber(samples).map { Vec2(it.x, it.y) }

    // Use least squares circle fitting
    var sumX = 0f
    var sumY = 0f
    var sumXX = 0f
    var sumYY = 0f
    var sumXY = 0f
    var sumXXX = 0f
    var sumYYY = 0f
    var sumXXY = 0f
    var sumXYY = 0f

    points.forEach { p ->
        sumX += p.x
        sumY += p.y
        sumXX += p.x * p.x
        sumYY += p.y * p.y
        sumXY += p.x * p.y
        sumXXX += p.x * p.x * p.x
        sumYYY += p.y * p.y * p.y
        sumXXY += p.x * p.x * p.y
        sumXYY += p.x * p.y * p.y
    }

    val n = points.size.toFloat()
    val a = 2 * (sumX * sumX - n * sumXX)
    val b = 2 * (sumX * sumY - n * sumXY)
    val c = 2 * (sumY * sumY - n * sumYY)
    val d = sumXX * sumX - n * sumXXX + sumX * sumYY - n * sumXYY
    val e = sumXX * sumY - n * sumYYY + sumYY * sumY - n * sumXXY

    val denominator = a * c - b * b
    val cx = if (abs(denominator) > 1e-6f) (d * c - b * e) / denominator else sumX / n
    val cy = if (abs(denominator) > 1e-6f) (a * e - b * d) / denominator else sumY / n

    // Calculate radius as average distance to center
    val radius = points.map { sqrt((it.x - cx).pow(2) + (it.y - cy).pow(2)) }.average().toFloat()

    // Calculate error
    val error = points.map { abs(sqrt((it.x - cx).pow(2) + (it.y - cy).pow(2)) - radius) }.average().toFloat()

    return Triple(vec(cx, cy), radius, error)
}

/**
 * Subdivide a curve using De Casteljau's algorithm.
 * This creates a smoother approximation with more control points.
 */
fun FLine.bySubdividing(levels: Int = 1): FLine {
    var result = copy()

    repeat(levels) {
        val newResult = FLine()
        var first = true

        result.nodes.forEachIndexed { i, node ->
            if (i == 0 || node is FLine.MoveTo) {
                newResult.moveTo(node.to)
                first = false
            } else if (node is FLine.LineTo) {
                val prev = result.nodes[i - 1].to
                val mid = Vec3(prev).lerp(node.to, 0.5f)
                newResult.lineTo(mid)
                newResult.lineTo(node.to)
            } else if (node is FLine.CubicTo) {
                val prev = result.nodes[i - 1].to

                // De Casteljau subdivision at t=0.5
                val q0 = Vec3(prev).lerp(node.c1, 0.5f)
                val q1 = Vec3(node.c1).lerp(node.c2, 0.5f)
                val q2 = Vec3(node.c2).lerp(node.to, 0.5f)

                val r0 = Vec3(q0).lerp(q1, 0.5f)
                val r1 = Vec3(q1).lerp(q2, 0.5f)

                val mid = Vec3(r0).lerp(r1, 0.5f)

                newResult.cubicTo(q0, r0, mid)
                newResult.cubicTo(r1, q2, node.to)
            }
        }

        result = newResult
    }

    return result
}

/**
 * Arc-length parameterization: resample the curve with equal spacing along its length.
 * @param spacing desired spacing between points
 */
fun FLine.byArcLengthParameterizing(spacing: Float): FLine {
    return this.copy {
        val samples = sampleByDistance(spacing)
        nodes.clear()

        if (samples.isNotEmpty()) {
            moveTo(samples[0])
            for (i in 1 until samples.size) {
                lineTo(samples[i])
            }
        }
    }
}

/**
 * Create a dashed version of the curve.
 * @param dashLength length of each dash
 * @param gapLength length of each gap
 */
fun FLine.byDashing(dashLength: Float, gapLength: Float): List<FLine> {
    val result = mutableListOf<FLine>()
    val cursor = this.cursor()
    val totalLength = cursor.lengthD()

    var distance = 0f
    var inDash = true
    var currentSegment: FLine? = null

    while (distance < totalLength) {
        cursor.setD(distance)
        val pos = cursor.position()

        if (inDash) {
            if (currentSegment == null) {
                currentSegment = FLine()
                currentSegment.moveTo(pos)
            } else {
                currentSegment.lineTo(pos)
            }

            distance += dashLength
            if (distance >= totalLength) {
                cursor.setD(totalLength)
                currentSegment.lineTo(cursor.position())
            }
            result.add(currentSegment)
            currentSegment = null
            inDash = false
        } else {
            distance += gapLength
            inDash = true
        }
    }

    return result
}

/**
 * Create a wave pattern along the curve.
 * @param amplitude wave amplitude
 * @param frequency wave frequency
 * @param samples number of samples
 */
fun FLine.byWaving(amplitude: Float, frequency: Float, samples: Int = 100): FLine {
    val result = FLine()
    val cursor = this.cursor(0.01f)

    for (i in 0 until samples) {
        val t = i.toFloat() / (samples - 1) * (this.nodes.size - 1)
        cursor.setT(t)

        val pos = cursor.position()
        val normal = cursor.normal2()

        if (normal != null) {
            val offset = amplitude * sin(t * frequency * 2 * PI.toFloat())
            val wavePos = vec(
                pos.x + normal.x * offset,
                pos.y + normal.y * offset,
                pos.z
            )

            if (i == 0) {
                result.moveTo(wavePos)
            } else {
                result.lineTo(wavePos)
            }
        }
    }

    return result
}

/**
 * Create a zigzag pattern along the curve.
 */
fun FLine.byZigzagging(amplitude: Float, segments: Int = 20): FLine {
    val result = FLine()
    val cursor = this.cursor(0.01f)

    for (i in 0..segments) {
        val t = i.toFloat() / segments * (this.nodes.size - 1)
        cursor.setT(t)

        val pos = cursor.position()
        val normal = cursor.normal2()

        if (normal != null) {
            val offset = amplitude * if (i % 2 == 0) 1f else -1f
            val zigPos = vec(
                pos.x + normal.x * offset,
                pos.y + normal.y * offset,
                pos.z
            )

            if (i == 0) {
                result.moveTo(zigPos)
            } else {
                result.lineTo(zigPos)
            }
        }
    }

    return result
}

// ==================== ARCS AND HIGHER-ORDER CURVES ====================

/**
 * Append a circular arc to this FLine in 3D.
 * @param center center point of the arc
 * @param radius radius of the arc
 * @param startAngle start angle in radians
 * @param endAngle end angle in radians
 * @param normal plane normal (defines the plane containing the arc)
 * @param segments number of line segments to approximate the arc
 * @return this FLine (for chaining)
 */
fun FLine.byAppendingArc3D(
    center: Vec3,
    radius: Float,
    startAngle: Float,
    endAngle: Float,
    normal: Vec3 = vec(0f, 0f, 1f),
    segments: Int = 32
): FLine {
    // Create orthonormal basis for the arc plane
    val n = Vec3(normal).normalize()

    // Find arbitrary perpendicular vector
    val arbitrary = if (abs(n.x) < 0.9f) vec(1f, 0f, 0f) else vec(0f, 1f, 0f)
    val u = Vec3(arbitrary).cross(n).normalize()
    val v = Vec3(n).cross(u).normalize()

    // Generate arc points
    val angleStep = (endAngle - startAngle) / segments
    for (i in 0..segments) {
        val angle = startAngle + i * angleStep
        val x = radius * cos(angle)
        val y = radius * sin(angle)

        // Transform to 3D using basis vectors
        val point = Vec3(center)
            .add(u.mul(x, Vec3()))
            .add(v.mul(y, Vec3()))

        if (i == 0 && this.nodes.isEmpty()) {
            this.moveTo(point)
        } else {
            this.lineTo(point)
        }
    }

    return this
}

/**
 * Create a circular arc through three points in 3D.
 * Returns an FLine containing the arc.
 * @param p1 first point
 * @param p2 second point (arc passes through this)
 * @param p3 third point
 * @param segments number of segments to approximate the arc
 */
fun arcThrough3Points(p1: Vec3, p2: Vec3, p3: Vec3, segments: Int = 32): FLine {
    // Find the center and radius of the circle through three points
    // First, find the plane normal
    val v1 = Vec3(p2).sub(p1)
    val v2 = Vec3(p3).sub(p1)
    val normal = Vec3(v1).cross(v2).normalize()

    // Find the circumcenter in 3D
    val a = v1.lengthSquared()
    val b = v2.lengthSquared()
    val c = Vec3(v1).cross(v2).lengthSquared()

    if (c < 1e-6f) {
        // Points are collinear, return a line
        val line = FLine()
        line.moveTo(p1)
        line.lineTo(p2)
        line.lineTo(p3)
        return line
    }

    val s = a * (b - v1.dot(v2)) / (2 * c)
    val t = b * (a - v1.dot(v2)) / (2 * c)

    val center = Vec3(p1)
        .add(Vec3(v1).mul(s))
        .add(Vec3(v2).mul(t))

    val radius = Vec3(p1).sub(center).length()

    // Calculate angles for the arc
    val toP1 = Vec3(p1).sub(center)
    val toP2 = Vec3(p2).sub(center)
    val toP3 = Vec3(p3).sub(center)

    // Create orthonormal basis
    val u = Vec3(toP1).normalize()
    val v = Vec3(normal).cross(u).normalize()

    // Calculate angles in the plane
    fun getAngle(vec: Vec3): Float {
        val projected = Vec3(vec).sub(center)
        val x = projected.dot(u)
        val y = projected.dot(v)
        return atan2(y, x)
    }

    val angle1 = getAngle(p1)
    val angle2 = getAngle(p2)
    val angle3 = getAngle(p3)

    // Determine arc direction and range
    var startAngle = angle1
    var endAngle = angle3

    // Check if we need to go the long way
    val mid = (angle1 + angle3) / 2
    if (abs(angle2 - mid) > PI.toFloat() / 2) {
        endAngle += 2 * PI.toFloat()
    }

    // Generate the arc
    val result = FLine()
    result.byAppendingArc3D(center, radius, startAngle, endAngle, normal, segments)
    return result
}

/**
 * Append a quadratic Bezier curve to this FLine.
 * Converts to cubic Bezier internally.
 * @return this FLine (for chaining)
 */
fun FLine.byAppendingQuadratic(controlPoint: Vec3, endPoint: Vec3): FLine {
    if (this.nodes.isEmpty()) {
        this.moveTo(endPoint)
        return this
    }

    val start = this.nodes.last().to

    // Convert quadratic to cubic
    // C1 = start + 2/3 * (control - start)
    // C2 = end + 2/3 * (control - end)
    val c1 = Vec3(start).add(Vec3(controlPoint).sub(start).mul(2f / 3f))
    val c2 = Vec3(endPoint).add(Vec3(controlPoint).sub(endPoint).mul(2f / 3f))

    this.cubicTo(c1, c2, endPoint)
    return this
}

/**
 * Append a circular arc to this FLine by specifying tangent at start and end.
 * Uses the arc that best fits the tangent constraints.
 * @return this FLine (for chaining)
 */
fun FLine.byAppendingArcByTangent(
    endPoint: Vec3,
    startTangent: Vec3,
    endTangent: Vec3,
    segments: Int = 32
): FLine {
    if (this.nodes.isEmpty()) {
        this.moveTo(endPoint)
        return this
    }

    val start = this.nodes.last().to

    // Find center as intersection of perpendiculars to tangents
    val startPerp = Vec3(startTangent).normalize()
    val endPerp = Vec3(endTangent).normalize()

    // Use midpoint and tangents to estimate center
    val mid = Vec3(start).add(endPoint).mul(0.5f)
    val toMid = Vec3(mid).sub(start)

    // Estimate radius from distance and tangent
    val distance = Vec3(endPoint).sub(start).length()
    val radius = distance * 0.5f / sin(atan2(toMid.cross(startPerp, Vec3()).length(), toMid.dot(startPerp)))

    // Calculate center
    val perpToStart = Vec3(startPerp).cross(toMid).normalize()
    val center = Vec3(start).add(perpToStart.mul(radius))

    // Generate arc
    val normal = Vec3(startPerp).cross(endPerp).normalize()
    val startAngle = atan2(
        Vec3(start).sub(center).dot(perpToStart),
        Vec3(start).sub(center).dot(startPerp)
    )
    val endAngle = atan2(
        Vec3(endPoint).sub(center).dot(perpToStart),
        Vec3(endPoint).sub(center).dot(startPerp)
    )

    this.byAppendingArc3D(center, radius, startAngle, endAngle, normal, segments)
    return this
}

// ==================== NOISE UTILITIES ====================

/**
 * Apply Gaussian noise to a curve.
 * @param line the curve to noise
 * @param amplitude noise amplitude
 * @param seed random seed for reproducibility
 * @param samples number of points to sample and reconstruct
 */
fun FLine.byGaussianNoising(amplitude: Float, seed: Long = System.currentTimeMillis(), samples: Int = 100): FLine {
    val random = java.util.Random(seed)
    val points = this.sampleByNumber(samples)

    val result = FLine()
    points.forEachIndexed { i, point ->
        // Generate Gaussian noise (Box-Muller transform)
        val u1 = random.nextFloat()
        val u2 = random.nextFloat()
        val r = sqrt(-2.0 * ln(u1.toDouble())).toFloat()
        val theta = 2.0 * PI * u2

        val noiseX = r * cos(theta).toFloat() * amplitude
        val noiseY = r * sin(theta).toFloat() * amplitude
        val noiseZ = (random.nextGaussian().toFloat()) * amplitude

        val noisedPoint = vec(
            point.x + noiseX,
            point.y + noiseY,
            point.z + noiseZ
        )

        if (i == 0) {
            result.moveTo(noisedPoint)
        } else {
            result.lineTo(noisedPoint)
        }
    }

    return result
}

/**
 * Apply direction-sensitive (oriented) Gaussian noise to this curve.
 * Noise is applied primarily along the normal direction.
 * @param normalAmplitude noise amplitude in normal direction
 * @param tangentAmplitude noise amplitude in tangent direction
 * @param seed random seed for reproducibility
 * @param samples number of points to sample
 * @return a new noised FLine
 */
fun FLine.byOrientedGaussianNoising(
    normalAmplitude: Float,
    tangentAmplitude: Float = 0f,
    seed: Long = System.currentTimeMillis(),
    samples: Int = 100
): FLine {
    val random = java.util.Random(seed)
    val cursor = this.cursor(0.01f)
    val result = FLine()

    for (i in 0 until samples) {
        val t = i.toFloat() / (samples - 1) * (this.nodes.size - 1)
        cursor.setT(t)

        val pos = cursor.position()
        val tangent = cursor.tangent()
        val normal = cursor.normal2()

        // Generate Gaussian noise
        val u1 = random.nextFloat()
        val u2 = random.nextFloat()
        val r = sqrt(-2.0 * ln(u1.toDouble())).toFloat()
        val theta = 2.0 * PI * u2

        val normalNoise = r * cos(theta).toFloat() * normalAmplitude
        val tangentNoise = r * sin(theta).toFloat() * tangentAmplitude

        val noisedPoint = if (normal != null && tangent != null) {
            val normalVec = Vec3(normal.x, normal.y, 0f).normalize()
            val tangentVec = Vec3(tangent).normalize()

            Vec3(pos)
                .add(normalVec.mul(normalNoise, Vec3()))
                .add(tangentVec.mul(tangentNoise, Vec3()))
        } else {
            Vec3(pos)
        }

        if (i == 0) {
            result.moveTo(noisedPoint)
        } else {
            result.lineTo(noisedPoint)
        }
    }

    return result
}

/**
 * Apply Perlin noise to this curve.
 * @param amplitude noise amplitude
 * @param frequency noise frequency (higher = more variation)
 * @param octaves number of noise octaves (more = more detail)
 * @param seed random seed for reproducibility
 * @param samples number of points to sample
 * @return a new noised FLine
 */
fun FLine.byPerlinNoising(
    amplitude: Float,
    frequency: Float = 1f,
    octaves: Int = 4,
    seed: Long = System.currentTimeMillis(),
    samples: Int = 100
): FLine {
    val perlin = PerlinNoise(seed)
    val cursor = this.cursor(0.01f)
    val result = FLine()

    for (i in 0 until samples) {
        val t = i.toFloat() / (samples - 1) * (this.nodes.size - 1)
        cursor.setT(t)

        val pos = cursor.position()
        val normal = cursor.normal2()

        // Generate Perlin noise at this position
        val noiseX = perlin.octaveNoise(pos.x * frequency, pos.y * frequency, 0.1f, octaves)
        val noiseY = perlin.octaveNoise(pos.x * frequency, pos.y * frequency, 0.2f, octaves)
        val noiseZ = perlin.octaveNoise(pos.x * frequency, pos.y * frequency, 0.3f, octaves)

        val noisedPoint = if (normal != null) {
            // Apply noise primarily in normal direction
            val normalVec = Vec3(normal.x, normal.y, 0f).normalize()
            Vec3(pos).add(normalVec.mul(noiseX * amplitude, Vec3()))
        } else {
            vec(
                pos.x + noiseX * amplitude,
                pos.y + noiseY * amplitude,
                pos.z + noiseZ * amplitude
            )
        }

        if (i == 0) {
            result.moveTo(noisedPoint)
        } else {
            result.lineTo(noisedPoint)
        }
    }

    return result
}

/**
 * Simple Perlin noise implementation for internal use.
 */
private class PerlinNoise(seed: Long) {
    private val random = kotlin.random.Random(seed)
    private val permutation = IntArray(256) { it }.apply { shuffle(random) }
    private val p = IntArray(512) { permutation[it % 256] }

    fun noise(x: Float, y: Float, z: Float): Float {
        val xi = floor(x).toInt() and 255
        val yi = floor(y).toInt() and 255
        val zi = floor(z).toInt() and 255

        val xf = x - floor(x)
        val yf = y - floor(y)
        val zf = z - floor(z)

        val u = fade(xf)
        val v = fade(yf)
        val w = fade(zf)

        val aaa = p[p[p[xi] + yi] + zi]
        val aba = p[p[p[xi] + yi + 1] + zi]
        val aab = p[p[p[xi] + yi] + zi + 1]
        val abb = p[p[p[xi] + yi + 1] + zi + 1]
        val baa = p[p[p[xi + 1] + yi] + zi]
        val bba = p[p[p[xi + 1] + yi + 1] + zi]
        val bab = p[p[p[xi + 1] + yi] + zi + 1]
        val bbb = p[p[p[xi + 1] + yi + 1] + zi + 1]

        var x1 = lerp(grad(aaa, xf, yf, zf), grad(baa, xf - 1, yf, zf), u)
        var x2 = lerp(grad(aba, xf, yf - 1, zf), grad(bba, xf - 1, yf - 1, zf), u)
        val y1 = lerp(x1, x2, v)

        x1 = lerp(grad(aab, xf, yf, zf - 1), grad(bab, xf - 1, yf, zf - 1), u)
        x2 = lerp(grad(abb, xf, yf - 1, zf - 1), grad(bbb, xf - 1, yf - 1, zf - 1), u)
        val y2 = lerp(x1, x2, v)

        return lerp(y1, y2, w)
    }

    fun octaveNoise(x: Float, y: Float, z: Float, octaves: Int): Float {
        var total = 0f
        var frequency = 1f
        var amplitude = 1f
        var maxValue = 0f

        for (i in 0 until octaves) {
            total += noise(x * frequency, y * frequency, z * frequency) * amplitude
            maxValue += amplitude
            amplitude *= 0.5f
            frequency *= 2f
        }

        return total / maxValue
    }

    private fun fade(t: Float) = t * t * t * (t * (t * 6 - 15) + 10)

    private fun lerp(a: Float, b: Float, t: Float) = a + t * (b - a)

    private fun grad(hash: Int, x: Float, y: Float, z: Float): Float {
        return when (hash and 15) {
            0 -> x + y
            1 -> -x + y
            2 -> x - y
            3 -> -x - y
            4 -> x + z
            5 -> -x + z
            6 -> x - z
            7 -> -x - z
            8 -> y + z
            9 -> -y + z
            10 -> y - z
            11 -> -y - z
            12 -> y + x
            13 -> -y + z
            14 -> y - x
            else -> -y - z
        }
    }
}

// ==================== CURVE COMBINATION UTILITIES ====================

/**
 * Add two curves point-wise.
 * Samples both curves and adds corresponding points.
 */
fun FLine.byAddingWith(other: FLine, samples: Int = 100): FLine {
    val points1 = this.sampleByNumber(samples)
    val points2 = other.sampleByNumber(samples)

    val result = FLine()
    for (i in points1.indices) {
        val p1 = points1[i]
        val p2 = if (i < points2.size) points2[i] else points2.last()
        val sum = Vec3(p1).add(p2)

        if (i == 0) {
            result.moveTo(sum)
        } else {
            result.lineTo(sum)
        }
    }

    return result
}

/**
 * Subtract one curve from another point-wise.
 */
fun FLine.bySubtractingWith(other: FLine, samples: Int = 100): FLine {
    val points1 = this.sampleByNumber(samples)
    val points2 = other.sampleByNumber(samples)

    val result = FLine()
    for (i in points1.indices) {
        val p1 = points1[i]
        val p2 = if (i < points2.size) points2[i] else points2.last()
        val diff = Vec3(p1).sub(p2)

        if (i == 0) {
            result.moveTo(diff)
        } else {
            result.lineTo(diff)
        }
    }

    return result
}

/**
 * Project one curve onto another.
 * For each point on line1, finds the closest point on line2.
 */
fun FLine.byProjectingOnto(other: FLine, samples: Int = 100): FLine {
    val points1 = this.sampleByNumber(samples)

    val result = FLine()
    points1.forEachIndexed { i, point ->
        val (closest, _) = other.closestPoint( point)

        if (i == 0) {
            result.moveTo(closest)
        } else {
            result.lineTo(closest)
        }
    }

    return result
}

/**
 * Invert a curve through a point (geometric inversion).
 * Each point is inverted through a circle centered at 'center' with given 'radius'.
 */
fun FLine.byInverting(center: Vec2 = vec(0f, 0f), radius: Float = 100f, samples: Int = 100): FLine {
    val points = this.sampleByNumber(samples)
    val radiusSq = radius * radius

    val result = FLine()
    points.forEachIndexed { i, point ->
        val dx = point.x - center.x
        val dy = point.y - center.y
        val distSq = dx * dx + dy * dy

        if (distSq < 1e-6f) {
            // Point at center, map to infinity (skip)
            return@forEachIndexed
        }

        val factor = radiusSq / distSq
        val inverted = vec(
            center.x + dx * factor,
            center.y + dy * factor,
            point.z
        )

        if (i == 0 || result.nodes.isEmpty()) {
            result.moveTo(inverted)
        } else {
            result.lineTo(inverted)
        }
    }

    return result
}

/**
 * Multiply two curves point-wise (component-wise multiplication of coordinates).
 */
fun FLine.byMultiplyingWith(other: FLine, samples: Int = 100): FLine {
    val points1 = this.sampleByNumber(samples)
    val points2 = other.sampleByNumber(samples)

    val result = FLine()
    for (i in points1.indices) {
        val p1 = points1[i]
        val p2 = if (i < points2.size) points2[i] else points2.last()
        val product = vec(p1.x * p2.x, p1.y * p2.y, p1.z * p2.z)

        if (i == 0) {
            result.moveTo(product)
        } else {
            result.lineTo(product)
        }
    }

    return result
}

// ==================== PARAMETERIZATION UTILITIES ====================

/**
 * Interface for parameter functions that can vary along a curve.
 * This allows any numeric parameter to be a function of position along the curve.
 */
interface ParameterFunction {
    fun evaluate(t: Float, position: Vec3): Float
}

/**
 * Constant parameter (doesn't vary).
 */
class ConstantParameter(val value: Float) : ParameterFunction {
    override fun evaluate(t: Float, position: Vec3) = value
}

/**
 * Linear interpolation from start to end value.
 */
class LinearParameter(val start: Float, val end: Float) : ParameterFunction {
    override fun evaluate(t: Float, position: Vec3) = start + (end - start) * t
}

/**
 * Parameter driven by another curve's Y coordinate.
 */
class CurveParameter(val curve: FLine, val scale: Float = 1f, val offset: Float = 0f) : ParameterFunction {
    private val cursor = curve.cursor()

    override fun evaluate(t: Float, position: Vec3): Float {
        cursor.setT(t * (curve.nodes.size - 1))
        return cursor.position().y * scale + offset
    }
}

/**
 * Parameter driven by distance from a point.
 */
class DistanceParameter(
    val point: Vec3,
    val minValue: Float,
    val maxValue: Float,
    val maxDistance: Float
) : ParameterFunction {
    override fun evaluate(t: Float, position: Vec3): Float {
        val dist = position.distance(point)
        val normalized = (dist / maxDistance).coerceIn(0f, 1f)
        return minValue + (maxValue - minValue) * normalized
    }
}

/**
 * Parameter driven by curvature.
 */
class CurvatureParameter(
    val line: FLine,
    val minValue: Float,
    val maxValue: Float,
    val maxCurvature: Float = 1f
) : ParameterFunction {
    private val cursor = line.cursor()

    override fun evaluate(t: Float, position: Vec3): Float {
        cursor.setT(t * (line.nodes.size - 1))
        val tangent = cursor.tangent()
        val acceleration = cursor.acceleration()

        if (tangent != null && acceleration != null) {
            val cross = tangent.cross(acceleration, Vec3())
            val curvature = cross.length() / tangent.length().pow(3)
            val normalized = (curvature / maxCurvature).coerceIn(0f, 1f)
            return minValue + (maxValue - minValue) * normalized
        }

        return minValue
    }
}

/**
 * Sine wave parameter.
 */
class SineParameter(
    val amplitude: Float,
    val frequency: Float,
    val phase: Float = 0f,
    val offset: Float = 0f
) : ParameterFunction {
    override fun evaluate(t: Float, position: Vec3) =
        offset + amplitude * sin(t * frequency * 2 * PI.toFloat() + phase)
}

/**
 * Apply a parameterized offset to a curve.
 * The offset distance varies along the curve according to the parameter function.
 */
fun FLine.byOffsettingParameterized(
    distanceFunc: ParameterFunction,
    samples: Int = 100
): FLine {
    val result = FLine()
    val cursor = this.cursor(0.01f)

    for (i in 0 until samples) {
        val t = i.toFloat() / (samples - 1)
        cursor.setT(t * (this.nodes.size - 1))

        val pos = cursor.position()
        val normal = cursor.normal2()
        val distance = distanceFunc.evaluate(t, pos)

        if (normal != null) {
            val offsetPos = vec(
                pos.x + normal.x * distance,
                pos.y + normal.y * distance,
                pos.z
            )

            if (i == 0) {
                result.moveTo(offsetPos)
            } else {
                result.lineTo(offsetPos)
            }
        }
    }

    return result
}

/**
 * Apply parameterized noise to a curve.
 * The noise amplitude varies according to the parameter function.
 */
fun FLine.byNoisingParameterized(
    amplitudeFunc: ParameterFunction,
    seed: Long = System.currentTimeMillis(),
    samples: Int = 100
): FLine {
    val random = java.util.Random(seed)
    val cursor = this.cursor(0.01f)
    val result = FLine()

    for (i in 0 until samples) {
        val t = i.toFloat() / (samples - 1)
        cursor.setT(t * (this.nodes.size - 1))

        val pos = cursor.position()
        val normal = cursor.normal2()
        val amplitude = amplitudeFunc.evaluate(t, pos)

        // Generate Gaussian noise
        val u1 = random.nextFloat()
        val u2 = random.nextFloat()
        val r = sqrt(-2.0 * ln(u1.toDouble())).toFloat()
        val theta = 2.0 * PI * u2

        val noise = r * cos(theta).toFloat() * amplitude

        val noisedPoint = if (normal != null) {
            val normalVec = Vec3(normal.x, normal.y, 0f)
            Vec3(pos).add(normalVec.mul(noise, Vec3()))
        } else {
            Vec3(pos)
        }

        if (i == 0) {
            result.moveTo(noisedPoint)
        } else {
            result.lineTo(noisedPoint)
        }
    }

    return result
}

/**
 * Apply parameterized scaling to a curve around a center point.
 */
fun FLine.byScalingParameterized(
    scaleFunc: ParameterFunction,
    center: Vec2 = vec(0f, 0f),
    samples: Int = 100
): FLine {
    val points = this.sampleByNumber(samples)
    val result = FLine()

    points.forEachIndexed { i, point ->
        val t = i.toFloat() / (samples - 1)
        val scale = scaleFunc.evaluate(t, point)

        val scaled = vec(
            center.x + (point.x - center.x) * scale,
            center.y + (point.y - center.y) * scale,
            point.z
        )

        if (i == 0) {
            result.moveTo(scaled)
        } else {
            result.lineTo(scaled)
        }
    }

    return result
}

/**
 * Create a parameter function from a simple lambda.
 */
fun parameterFromLambda(func: (t: Float, pos: Vec3) -> Float): ParameterFunction {
    return object : ParameterFunction {
        override fun evaluate(t: Float, position: Vec3) = func(t, position)
    }
}

// ==================== ADVANCED / EXPERIMENTAL CURVES ====================

/**
 * Blend two sampled point sets with an optional parameterized dry/wet control.
 * Values outside 0..1 are allowed for intentional overshoot.
 */
private fun mixSamples(
    base: List<Vec3>,
    processed: List<Vec3>,
    dryWet: Float,
    modulator: ParameterFunction? = null
): List<Vec3> {
    val count = min(base.size, processed.size)
    val blended = ArrayList<Vec3>(count)
    for (i in 0 until count) {
        val t = if (count <= 1) 0f else i.toFloat() / (count - 1)
        val wet = modulator?.evaluate(t, processed[i]) ?: dryWet
        val dry = 1f - wet
        blended.add(Vec3(base[i]).mul(dry).add(Vec3(processed[i]).mul(wet)))
    }
    return blended
}

private fun List<Vec3>.toFLine(): FLine {
    val f = FLine()
    if (this.isNotEmpty()) {
        f.moveTo(this[0])
        for (i in 1 until this.size) {
            f.lineTo(this[i])
        }
    }
    return f
}

private fun normalizedArcLengthParams(points: List<Vec3>): DoubleArray {
    val n = points.size
    val params = DoubleArray(n)
    if (n == 0) return params
    var total = 0.0
    for (i in 1 until n) {
        total += points[i].distance(points[i - 1]).toDouble()
        params[i] = total
    }
    if (total > 0.0) {
        for (i in params.indices) params[i] /= total
    }
    return params
}

private fun alphaFromCutoff(fcHz: Double, dt: Double): Double {
    val dtc = dt.coerceAtLeast(1e-6)
    val tau = 1.0 / (2.0 * PI * fcHz.coerceAtLeast(1e-6))
    return 1.0 / (1.0 + tau / dtc)
}

private fun normalizeAngle(angle: Float): Float {
    var a = angle
    val twoPi = (2f * PI.toFloat())
    while (a > PI) a -= twoPi
    while (a < -PI) a += twoPi
    return a
}

// ---------- Higher-order B-splines ----------

/**
 * Evaluate a uniform open B-spline of arbitrary degree using Cox-de Boor.
 */
fun bsplineFrom(
    controlPoints: List<Vec3>,
    degree: Int = 3,
    samples: Int = 200,
    closed: Boolean = false
): FLine {
    if (controlPoints.size < degree + 1) return FLine()

    val cp = if (closed) {
        val wrap = controlPoints.take(degree)
        controlPoints + wrap
    } else controlPoints

    val n = cp.size - 1
    val p = degree
    val knotCount = n + p + 2
    val knots = FloatArray(knotCount)

    for (i in 0..p) knots[i] = 0f
    val interior = n - p
    for (j in 1..interior) {
        knots[p + j] = j.toFloat() / (interior + 1).toFloat()
    }
    for (i in (p + interior + 1) until knotCount) knots[i] = 1f

    fun basis(u: Float): FloatArray {
        val N = FloatArray(n + 1)
        val temp = FloatArray(n + 1)
        for (i in 0..n) {
            val inRange = (knots[i] <= u && u < knots[i + 1]) || (u == 1f && i == n)
            N[i] = if (inRange) 1f else 0f
        }
        for (k in 2..p + 1) {
            for (i in 0..n) {
                val denom1 = knots[i + k - 1] - knots[i]
                val denom2 = knots[i + k] - knots[i + 1]
                val term1 = if (denom1 > 1e-6f) (u - knots[i]) / denom1 * N[i] else 0f
                val term2 = if (denom2 > 1e-6f) (knots[i + k] - u) / denom2 * N.getOrElse(i + 1) { 0f } else 0f
                temp[i] = term1 + term2
            }
            System.arraycopy(temp, 0, N, 0, n + 1)
        }
        return N
    }

    val pts = ArrayList<Vec3>(samples)
    for (s in 0 until samples) {
        val u = if (samples == 1) 0f else s.toFloat() / (samples - 1)
        val N = basis(u.coerceIn(0f, 1f))
        val pnt = Vec3()
        for (i in 0..n) {
            pnt.add(Vec3(cp[i]).mul(N[i]))
        }
        pts.add(pnt)
    }

    return pts.toFLine()
}

/**
 * Reinterpret an existing curve through a higher-order B-spline fit.
 * @param degree spline degree (3=cubic, 4=quartic, 5=quintic)
 * @param controlSampleCount how many samples from the original to treat as control points
 * @param dryWet mix factor against the original samples (can exceed 1 for exaggeration)
 */
fun FLine.byBSplineSmoothed(
    degree: Int = 4,
    controlSampleCount: Int = 24,
    samples: Int = 200,
    dryWet: Float = 1f,
    wetModulator: ParameterFunction? = null,
    closed: Boolean = false
): FLine {
    val cps = this.sampleByNumber(controlSampleCount)
    if (cps.isEmpty()) return this.copy()
    val spline = bsplineFrom(cps, degree, samples, closed)
    val blended = mixSamples(this.sampleByNumber(samples), spline.sampleByNumber(samples), dryWet, wetModulator)
    return blended.toFLine()
}

// ---------- Euler spiral interpolation ----------

private fun eulerSpiralCore(
    start: Vec3,
    startHeading: Float,
    end: Vec3,
    endHeading: Float,
    samples: Int,
    iterations: Int
): List<Vec3> {
    val targetVec = Vec3(end).sub(start)
    val targetDist = targetVec.length().coerceAtLeast(1e-6f)
    val deltaHeading = normalizeAngle(endHeading - startHeading)
    var length = targetDist

    fun integrate(L: Float): List<Vec3> {
        val pts = ArrayList<Vec3>(samples)
        val ds = L / (samples - 1).toFloat()
        val curvatureRate = 2f * deltaHeading / (L * L).coerceAtLeast(1e-6f)
        var heading = startHeading
        var pos = Vec3(start)
        pts.add(Vec3(pos))
        var sAcc = 0f
        for (i in 1 until samples) {
            val curvature = curvatureRate * sAcc
            heading += curvature * ds
            pos = vec(
                pos.x + cos(heading) * ds,
                pos.y + sin(heading) * ds,
                start.z + (end.z - start.z) * (i.toFloat() / (samples - 1))
            )
            pts.add(pos)
            sAcc += ds
        }
        return pts
    }

    var pts = integrate(length)
    repeat(iterations) {
        val predictedDist = Vec3(pts.last()).sub(start).length().coerceAtLeast(1e-6f)
        val scale = targetDist / predictedDist
        length *= scale
        pts = integrate(length)
    }

    val residual = Vec3(end).sub(pts.last())
    return pts.mapIndexed { i, p ->
        val t = if (pts.size <= 1) 0f else i.toFloat() / (pts.size - 1)
        Vec3(p).add(Vec3(residual).mul(t))
    }
}

/**
 * Generate a clothoid (Euler spiral) bridging two poses in 2D, blending toward the exact end point.
 * @param start starting point
 * @param startHeading heading at start in radians
 * @param end end point
 * @param endHeading heading at end in radians
 * @param dryWet mix against a straight interpolation between start and end
 */
fun eulerSpiralBetween(
    start: Vec3,
    startHeading: Float,
    end: Vec3,
    endHeading: Float,
    samples: Int = 200,
    iterations: Int = 6,
    dryWet: Float = 1f,
    wetModulator: ParameterFunction? = null
): FLine {
    if (samples < 2) {
        val f = FLine()
        f.moveTo(start)
        f.lineTo(end)
        return f
    }
    val spiral = eulerSpiralCore(start, startHeading, end, endHeading, samples, iterations)
    val straight = (0 until samples).map { i ->
        val t = i.toFloat() / (samples - 1)
        vec(
            start.x + (end.x - start.x) * t,
            start.y + (end.y - start.y) * t,
            start.z + (end.z - start.z) * t
        )
    }
    val blended = mixSamples(straight, spiral, dryWet, wetModulator)
    return blended.toFLine()
}

/**
 * Build an Euler spiral from the end of this curve toward an end point.
 * If no explicit heading is supplied, the tangent of the last segment is used.
 */
fun FLine.byEulerSpiralTo(
    endPoint: Vec3,
    endHeading: Float? = null,
    samples: Int = 200,
    iterations: Int = 6,
    dryWet: Float = 1f,
    wetModulator: ParameterFunction? = null
): FLine {
    val start = if (nodes.isNotEmpty()) nodes.last().to else vec(0f, 0f, 0f)
    val startHeading = if (nodes.size >= 2) {
        val prev = nodes[nodes.size - 2].to
        atan2(start.y - prev.y, start.x - prev.x)
    } else 0f
    val targetHeading = endHeading ?: atan2(endPoint.y - start.y, endPoint.x - start.x)
    return eulerSpiralBetween(start, startHeading, endPoint, targetHeading, samples, iterations, dryWet, wetModulator)
}

// ---------- Curvature-driven evolution ----------

/**
 * Iterate mean-curvature flow on a sampled polyline.
 * @param step how far to move toward the discrete Laplacian each iteration
 * @param iterations number of passes to apply
 * @param anchorEnds keep the first/last points fixed while evolving
 */
fun FLine.byCurvatureFlow(
    step: Float = 0.25f,
    iterations: Int = 5,
    samples: Int = 200,
    anchorEnds: Boolean = true,
    dryWet: Float = 1f,
    wetModulator: ParameterFunction? = null
): FLine {
    val base = this.sampleByNumber(samples).toMutableList()
    if (base.size < 3) return this.copy()

    var pts = base
    repeat(iterations) {
        val updated = MutableList(pts.size) { Vec3() }
        for (i in pts.indices) {
            updated[i] = if (anchorEnds && (i == 0 || i == pts.lastIndex)) {
                pts[i]
            } else {
                val prev = pts[i - 1]
                val next = pts[i + 1]
                val lap = Vec3(prev).add(next).mul(0.5f).sub(pts[i])
                Vec3(pts[i]).add(Vec3(lap).mul(step))
            }
        }
        pts = updated
    }

    val blended = mixSamples(base, pts, dryWet, wetModulator)
    return blended.toFLine()
}

// ---------- Gaussian Process interpolation ----------

private fun cholesky(matrix: Array<DoubleArray>): Array<DoubleArray>? {
    val n = matrix.size
    val L = Array(n) { DoubleArray(n) }
    for (i in 0 until n) {
        for (j in 0..i) {
            var sum = matrix[i][j]
            for (k in 0 until j) sum -= L[i][k] * L[j][k]
            if (i == j) {
                if (sum <= 0.0 || sum.isNaN()) return null
                L[i][j] = sqrt(sum)
            } else {
                L[i][j] = sum / L[j][j]
            }
        }
    }
    return L
}

private fun solveWithCholesky(L: Array<DoubleArray>, b: DoubleArray): DoubleArray {
    val n = L.size
    val y = DoubleArray(n)
    for (i in 0 until n) {
        var sum = b[i]
        for (k in 0 until i) sum -= L[i][k] * y[k]
        y[i] = sum / L[i][i]
    }
    val x = DoubleArray(n)
    for (i in n - 1 downTo 0) {
        var sum = y[i]
        for (k in i + 1 until n) sum -= L[k][i] * x[k]
        x[i] = sum / L[i][i]
    }
    return x
}

private fun solveLower(L: Array<DoubleArray>, b: DoubleArray): DoubleArray {
    val n = L.size
    val y = DoubleArray(n)
    for (i in 0 until n) {
        var sum = b[i]
        for (k in 0 until i) sum -= L[i][k] * y[k]
        y[i] = sum / L[i][i]
    }
    return y
}

/**
 * RBF-kernel Gaussian Process interpolation along arc length, applied independently to XYZ.
 * @param trainingSamples how many points to use from the source curve as observations
 * @param samples how many points to output
 * @param lengthScale kernel length scale (in normalized arc-length units)
 * @param variance kernel variance
 * @param noise observation noise added to the diagonal
 * @param drawSamples if true, draw a single sample from the posterior instead of using the mean
 */
fun FLine.byGaussianProcessInterpolating(
    trainingSamples: Int = 48,
    samples: Int = 200,
    lengthScale: Float = 0.15f,
    variance: Float = 1f,
    noise: Float = 1e-3f,
    dryWet: Float = 1f,
    wetModulator: ParameterFunction? = null,
    drawSamples: Boolean = false,
    seed: Long? = null
): FLine {
    val obs = this.sampleByNumber(trainingSamples)
    if (obs.isEmpty()) return this.copy()

    val tObs = normalizedArcLengthParams(obs)
    val n = obs.size
    val ls2 = (lengthScale.toDouble()).pow(2.0).coerceAtLeast(1e-8)
    val varScale = variance.toDouble()
    val noiseVar = noise.toDouble().pow(2.0)

    fun k(a: Double, b: Double): Double {
        val d2 = (a - b) * (a - b)
        return varScale * kotlin.math.exp(-0.5 * d2 / ls2)
    }

    val K = Array(n) { DoubleArray(n) }
    for (i in 0 until n) {
        for (j in 0 until n) {
            K[i][j] = k(tObs[i], tObs[j])
        }
        K[i][i] += noiseVar
    }

    val jittered = K.map { it.copyOf() }.toTypedArray()
    for (i in 0 until n) jittered[i][i] += 1e-8
    val L = cholesky(jittered) ?: return this.copy()

    val alphaX = solveWithCholesky(L, DoubleArray(n) { obs[it].x.toDouble() })
    val alphaY = solveWithCholesky(L, DoubleArray(n) { obs[it].y.toDouble() })
    val alphaZ = solveWithCholesky(L, DoubleArray(n) { obs[it].z.toDouble() })

    val rng = if (drawSamples) java.util.Random(seed ?: System.nanoTime()) else null

    val query = ArrayList<Vec3>(samples)
    for (i in 0 until samples) {
        val t = if (samples == 1) 0.0 else i.toDouble() / (samples - 1)
        val kStar = DoubleArray(n) { idx -> k(t, tObs[idx]) }
        val meanX = kStar.indices.sumOf { idx -> kStar[idx] * alphaX[idx] }
        val meanY = kStar.indices.sumOf { idx -> kStar[idx] * alphaY[idx] }
        val meanZ = kStar.indices.sumOf { idx -> kStar[idx] * alphaZ[idx] }

        var varStar = varScale
        val v = solveLower(L, kStar)
        for (vComp in v) varStar -= vComp * vComp
        val std = sqrt(varStar.coerceAtLeast(0.0))
        val jitter = if (drawSamples && rng != null) std * rng.nextGaussian() else 0.0

        query.add(vec(meanX.toFloat() + jitter.toFloat(), meanY.toFloat() + jitter.toFloat(), meanZ.toFloat() + jitter.toFloat()))
    }

    val blended = mixSamples(this.sampleByNumber(samples), query, dryWet, wetModulator)
    return blended.toFLine()
}

// ---------- Filtered geometry (EMA, One Euro, alpha-beta / 1$) ----------

/**
 * Simple exponential moving average over sampled points.
 */
fun FLine.byEmaFiltering(
    alpha: Float = 0.18f,
    samples: Int = 200,
    dryWet: Float = 1f,
    wetModulator: ParameterFunction? = null
): FLine {
    val points = this.sampleByNumber(samples)
    if (points.isEmpty()) return this.copy()
    val filtered = MutableList(points.size) { Vec3() }
    var ema = Vec3(points.first())
    filtered[0] = Vec3(ema)
    for (i in 1 until points.size) {
        val raw = points[i]
        ema = vec(
            ema.x + (raw.x - ema.x) * alpha,
            ema.y + (raw.y - ema.y) * alpha,
            ema.z + (raw.z - ema.z) * alpha
        )
        filtered[i] = ema
    }
    val blended = mixSamples(points, filtered, dryWet, wetModulator)
    return blended.toFLine()
}

/**
 * One-Euro filter on 3D positions with a fixed dt inferred from an FPS hint.
 */
fun FLine.byOneEuroFiltering(
    minCutoffHz: Float = 1.5f,
    beta: Float = 0.03f,
    dCutoffHz: Float = 1f,
    fpsHint: Float = 60f,
    samples: Int = 200,
    dryWet: Float = 1f,
    wetModulator: ParameterFunction? = null
): FLine {
    val points = this.sampleByNumber(samples)
    if (points.isEmpty()) return this.copy()
    val dt = (1f / fpsHint.coerceAtLeast(1e-3f)).toDouble()
    var dxHat = vec(0f, 0f, 0f)
    var xHat = Vec3(points.first())
    val filtered = mutableListOf<Vec3>()
    filtered.add(Vec3(xHat))

    for (i in 1 until points.size) {
        val raw = points[i]
        val dx = Vec3(raw).sub(points[i - 1]).mul((1f / dt.toFloat()))
        val ad = alphaFromCutoff(dCutoffHz.toDouble(), dt).toFloat()
        dxHat = vec(
            dxHat.x + ad * (dx.x - dxHat.x),
            dxHat.y + ad * (dx.y - dxHat.y),
            dxHat.z + ad * (dx.z - dxHat.z)
        )
        val speed = dxHat.length()
        val fc = (minCutoffHz + beta * speed).toDouble()
        val a = alphaFromCutoff(fc, dt).toFloat()
        xHat = vec(
            xHat.x + a * (raw.x - xHat.x),
            xHat.y + a * (raw.y - xHat.y),
            xHat.z + a * (raw.z - xHat.z)
        )
        filtered.add(Vec3(xHat))
    }

    val blended = mixSamples(points, filtered, dryWet, wetModulator)
    return blended.toFLine()
}

/**
 * Lightweight constant-velocity alpha-beta filter applied to a curve.
 */
fun FLine.byAlphaBetaFiltering(
    alpha: Float = 0.25f,
    beta: Float = 0.08f,
    fpsHint: Float = 90f,
    samples: Int = 200,
    dryWet: Float = 1f,
    wetModulator: ParameterFunction? = null
): FLine {
    val points = this.sampleByNumber(samples)
    if (points.isEmpty()) return this.copy()
    val dt = (1f / fpsHint.coerceAtLeast(1e-3f))
    var x = Vec3(points.first())
    var v = vec(0f, 0f, 0f)
    val filtered = mutableListOf<Vec3>()
    filtered.add(Vec3(x))
    for (i in 1 until points.size) {
        val pred = x().add(Vec3(v).mul(dt))
        val residual = Vec3(points[i]).sub(pred)
        x = Vec3(pred).add(Vec3(residual).mul(alpha))
        v = Vec3(v).add(Vec3(residual).mul(beta / dt))
        filtered.add(Vec3(x))
    }
    val blended = mixSamples(points, filtered, dryWet, wetModulator)
    return blended.toFLine()
}

/**
 * One-Dollar style normalization/resampling (rotation + scale + re-sample).
 * Useful as a "filter" to regularize noisy gesture paths.
 */
fun FLine.byOneDollarFiltering(
    targetSamples: Int = 64,
    targetSize: Float = 200f,
    rotateToZero: Boolean = true,
    dryWet: Float = 1f,
    wetModulator: ParameterFunction? = null
): FLine {
    val points = this.sampleByNumber(targetSamples)
    if (points.isEmpty()) return this.copy()

    val centroid = points.fold(Vec3()) { acc, p -> acc.add(p) }.div(points.size.toFloat())
    var normed = points.map { Vec3(it).sub(centroid) }

    if (rotateToZero && normed.size > 1) {
        val first = normed.first()
        val angle = atan2(first.y, first.x)
        val c = cos(-angle)
        val s = sin(-angle)
        normed = normed.map {
            vec(
                it.x * c - it.y * s,
                it.x * s + it.y * c,
                it.z
            )
        }
    }

    val bboxX = normed.minOf { it.x } to normed.maxOf { it.x }
    val bboxY = normed.minOf { it.y } to normed.maxOf { it.y }
    val scaleDenom = max(bboxX.second - bboxX.first, bboxY.second - bboxY.first).coerceAtLeast(1e-4f)
    val scale = targetSize / scaleDenom
    normed = normed.map { vec(it.x * scale, it.y * scale, it.z) }

    val blended = mixSamples(this.sampleByNumber(normed.size), normed, dryWet, wetModulator)
    return blended.toFLine()
}
