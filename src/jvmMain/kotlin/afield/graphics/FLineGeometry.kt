package afield.graphics

import afield.stdlib.*
import field.graphics.FLinesAndJavaShapes
import field.graphics.FLinesAndJavaShapes.CubicSegment2
import field.graphics.FLinesAndJavaShapes.CubicSegment3
import org.joml.*
import java.awt.geom.Area
import kotlin.math.*

/**
 * Comprehensive computational geometry utilities for FLine operations.
 * Provides transformations, projections, intersections, decompositions, curvature analyses,
 * tangents, osculating planes, offsets, and a rich set of 2D and 3D geometric algorithms.
 */
object FLineGeometry {

    // ==================== 2D TRANSFORMATIONS ====================

    /**
     * Rotate an FLine around a point in 2D (preserves Z coordinate).
     * @param angle rotation angle in radians
     * @param center center of rotation (defaults to origin)
     */
    fun rotate2D(line: FLine, angle: Float, center: Vec2 = vec(0f, 0f)): FLine {
        val cos = cos(angle)
        val sin = sin(angle)
        return line.transform { p ->
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
    fun scale2D(line: FLine, scale: Float, center: Vec2 = vec(0f, 0f)): FLine {
        return line.transform { p ->
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
    fun scale2D(line: FLine, scaleX: Float, scaleY: Float, center: Vec2 = vec(0f, 0f)): FLine {
        return line.transform { p ->
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
    fun reflect2D(line: FLine, linePoint1: Vec2, linePoint2: Vec2): FLine {
        val dx = linePoint2.x - linePoint1.x
        val dy = linePoint2.y - linePoint1.y
        val lenSq = dx * dx + dy * dy
        
        return line.transform { p ->
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
    fun shear2D(line: FLine, shearX: Float, shearY: Float): FLine {
        return line.transform { p ->
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
    fun rotate3D(line: FLine, angle: Float, axis: Vec3, center: Vec3 = vec(0f, 0f, 0f)): FLine {
        val q = Quaternionf().setAngleAxis(angle, axis.x, axis.y, axis.z)
        return line.transform { p ->
            val translated = Vec3(p).sub(center)
            q.transform(translated)
            translated.add(center)
        }
    }

    /**
     * Perspective projection onto a plane.
     * @param cameraPos position of the camera/eye
     * @param planePoint a point on the projection plane
     * @param planeNormal normal of the projection plane
     */
    fun perspectiveProject(
        line: FLine,
        cameraPos: Vec3,
        planePoint: Vec3 = vec(0f, 0f, 0f),
        planeNormal: Vec3 = vec(0f, 0f, 1f)
    ): FLine {
        val normal = Vec3(planeNormal).normalize()
        val d = -normal.dot(planePoint)
        
        return line.transform { p ->
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
     * Orthographic projection onto a plane.
     */
    fun orthographicProject(
        line: FLine,
        planePoint: Vec3 = vec(0f, 0f, 0f),
        planeNormal: Vec3 = vec(0f, 0f, 1f)
    ): FLine {
        val normal = Vec3(planeNormal).normalize()
        
        return line.transform { p ->
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
    fun offset(line: FLine, distance: Float, samples: Int = 50): FLine {
        val result = FLine()
        val cursor = line.cursor(0.01f)
        var first = true
        
        for (i in 0..samples * (line.nodes.size - 1)) {
            val t = i.toFloat() / (samples * (line.nodes.size - 1))
            cursor.setT(t * (line.nodes.size - 1))
            
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
        
        return result
    }

    /**
     * Create parallel curves on both sides of the original curve.
     * @return Pair of FLines (left offset, right offset)
     */
    fun parallelCurves(line: FLine, distance: Float, samples: Int = 50): Pair<FLine, FLine> {
        return Pair(
            offset(line, -distance, samples),
            offset(line, distance, samples)
        )
    }

    // ==================== CURVE SMOOTHING ====================

    /**
     * Smooth a curve using Gaussian kernel smoothing.
     * @param sigma smoothing strength (larger = smoother)
     * @param samples number of samples to use
     */
    fun gaussianSmooth(line: FLine, sigma: Float, samples: Int = 100): FLine {
        val points = line.sampleByNumber(samples)
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
    fun simplify(line: FLine, epsilon: Float): FLine {
        val points = mutableListOf<Vec3>()
        line.nodes.forEach { node ->
            points.add(Vec3(node.to))
        }
        
        if (points.size < 3) return line.copy()
        
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
    fun findSelfIntersections(line: FLine, tolerance: Float = 1f): List<Vec3> {
        val intersections = mutableListOf<Vec3>()
        
        for (i in 1 until line.nodes.size) {
            for (j in i + 2 until line.nodes.size) {
                val ni = line.nodes[i]
                val nj = line.nodes[j]
                
                if (ni is FLine.CubicTo && nj is FLine.CubicTo) {
                    val ci = CubicSegment3(line.nodes[i - 1].to, ni.c1, ni.c2, ni.to)
                    val cj = CubicSegment3(line.nodes[j - 1].to, nj.c1, nj.c2, nj.to)
                    
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
    fun findIntersections(line1: FLine, line2: FLine, tolerance: Float = 1f): List<Vec3> {
        return FLinesAndJavaShapes.intersect(line1, line2)
    }

    // ==================== CURVATURE ANALYSIS ====================

    /**
     * Calculate curvature at regular intervals along the curve.
     * @param samples number of sample points
     * @return list of (position, curvature) pairs
     */
    fun curvatureAnalysis(line: FLine, samples: Int = 100): List<Pair<Vec3, Float>> {
        val result = mutableListOf<Pair<Vec3, Float>>()
        val cursor = line.cursor(0.01f)
        
        for (i in 0 until samples) {
            val t = i.toFloat() / (samples - 1) * (line.nodes.size - 1)
            cursor.setT(t)
            
            val pos = cursor.position()
            val tangent = cursor.tangent()
            val acceleration = cursor.acceleration()
            
            if (tangent != null && acceleration != null) {
                val cross = tangent.cross(acceleration, Vec3())
                val curvature = cross.length() / tangent.length().pow(3)
                result.add(Pair(pos, curvature))
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
    fun curvatureAdaptiveSample(line: FLine, minSamples: Int = 20, maxSamples: Int = 200): FLine {
        val curvatures = curvatureAnalysis(line, minSamples)
        val totalCurvature = curvatures.sumOf { it.second.toDouble() }.toFloat()
        
        if (totalCurvature == 0f) {
            return line.copy()
        }
        
        val result = FLine()
        result.moveTo(curvatures.first().first)
        
        for (i in 1 until curvatures.size) {
            val curvature = curvatures[i].second
            val localSamples = (curvature / totalCurvature * maxSamples).toInt().coerceAtLeast(1)
            
            for (j in 0..localSamples) {
                val t = (i - 1 + j.toFloat() / localSamples) / (curvatures.size - 1) * (line.nodes.size - 1)
                val cursor = line.cursor()
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
    fun findHighCurvaturePoints(line: FLine, threshold: Float = 0.1f, samples: Int = 100): List<Vec3> {
        val curvatures = curvatureAnalysis(line, samples)
        return curvatures.filter { it.second > threshold }.map { it.first }
    }

    // ==================== DECOMPOSITION ====================

    /**
     * Compute the 2D convex hull of the curve using Graham scan.
     * @return FLine representing the convex hull
     */
    fun convexHull2D(line: FLine): FLine {
        val points = line.nodes.map { Vec2(it.to.x, it.to.y) }.toMutableList()
        
        if (points.size < 3) return line.copy()
        
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
    fun splitAtInflections(line: FLine): List<FLine> {
        val cursor = line.cursor()
        val inflections = cursor.inflectionPointsXY()
        
        if (inflections.isEmpty()) return listOf(line.copy())
        
        return cursor.splitT(inflections)
    }

    // ==================== TANGENT AND NORMAL OPERATIONS ====================

    /**
     * Generate tangent lines at regular intervals.
     * @param samples number of tangent lines to generate
     * @param length length of each tangent line
     */
    fun tangentLines(line: FLine, samples: Int = 20, length: Float = 10f): List<FLine> {
        val tangents = mutableListOf<FLine>()
        val cursor = line.cursor(0.01f)
        
        for (i in 0 until samples) {
            val t = i.toFloat() / (samples - 1) * (line.nodes.size - 1)
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
    fun normalLines(line: FLine, samples: Int = 20, length: Float = 10f): List<FLine> {
        val normals = mutableListOf<FLine>()
        val cursor = line.cursor(0.01f)
        
        for (i in 0 until samples) {
            val t = i.toFloat() / (samples - 1) * (line.nodes.size - 1)
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
    fun osculatingCircles(line: FLine, samples: Int = 20, circleSegments: Int = 32): List<FLine> {
        val circles = mutableListOf<FLine>()
        val cursor = line.cursor(0.01f)
        
        for (i in 0 until samples) {
            val t = i.toFloat() / (samples - 1) * (line.nodes.size - 1)
            cursor.setT(t)
            
            val radiusVec = cursor.radiusOfCurvature()
            
            if (radiusVec != null && radiusVec.isFinite) {
                val pos = cursor.position()
                val radius = radiusVec.length()
                val center = Vec3(pos).add(radiusVec)
                
                val circle = FLine()
                for (j in 0..circleSegments) {
                    val angle = j.toFloat() / circleSegments * 2 * PI.toFloat()
                    val x = center.x + radius * cos(angle)
                    val y = center.y + radius * sin(angle)
                    
                    if (j == 0) {
                        circle.moveTo(x, y, pos.z)
                    } else {
                        circle.lineTo(x, y, pos.z)
                    }
                }
                circles.add(circle)
            }
        }
        
        return circles
    }

    // ==================== BOOLEAN OPERATIONS ====================

    /**
     * Union of two curves (using Java2D Area).
     */
    fun union(line1: FLine, line2: FLine): FLine {
        val shape1 = FLinesAndJavaShapes.flineToJavaShape(line1)
        val shape2 = FLinesAndJavaShapes.flineToJavaShape(line2)
        val area1 = Area(shape1)
        val area2 = Area(shape2)
        area1.add(area2)
        return FLinesAndJavaShapes.javaShapeToFLine(area1)
    }

    /**
     * Difference of two curves (line1 - line2).
     */
    fun difference(line1: FLine, line2: FLine): FLine {
        val shape1 = FLinesAndJavaShapes.flineToJavaShape(line1)
        val shape2 = FLinesAndJavaShapes.flineToJavaShape(line2)
        val area1 = Area(shape1)
        val area2 = Area(shape2)
        area1.subtract(area2)
        return FLinesAndJavaShapes.javaShapeToFLine(area1)
    }

    /**
     * Intersection of two curves.
     */
    fun intersection(line1: FLine, line2: FLine): FLine {
        val shape1 = FLinesAndJavaShapes.flineToJavaShape(line1)
        val shape2 = FLinesAndJavaShapes.flineToJavaShape(line2)
        val area1 = Area(shape1)
        val area2 = Area(shape2)
        area1.intersect(area2)
        return FLinesAndJavaShapes.javaShapeToFLine(area1)
    }

    /**
     * XOR (exclusive or) of two curves.
     */
    fun exclusiveOr(line1: FLine, line2: FLine): FLine {
        val shape1 = FLinesAndJavaShapes.flineToJavaShape(line1)
        val shape2 = FLinesAndJavaShapes.flineToJavaShape(line2)
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
    fun closestPoint(line: FLine, point: Vec3): Pair<Vec3, Float> {
        val t = FLinesAndJavaShapes.closestT(line, point)
        val cursor = line.cursor()
        cursor.setT(t)
        return Pair(cursor.position(), t)
    }

    /**
     * Calculate winding number (for point-in-polygon test).
     * Winding number > 0 means point is inside the curve (if closed).
     * @return winding number
     */
    fun windingNumber(line: FLine, point: Vec2): Int {
        var winding = 0
        var prevPoint: Vec2? = null
        
        line.nodes.forEach { node ->
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
    fun contains(line: FLine, point: Vec2): Boolean {
        return windingNumber(line, point) != 0
    }

    /**
     * Calculate the signed area of a 2D closed curve.
     * Positive = counter-clockwise, negative = clockwise.
     */
    fun signedArea2D(line: FLine): Float {
        var area = 0f
        var prevPoint: Vec2? = null
        var firstPoint: Vec2? = null
        
        line.nodes.forEach { node ->
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
    fun centroid2D(line: FLine): Vec2 {
        val area = signedArea2D(line)
        if (abs(area) < 1e-6f) {
            // Degenerate case: return average of points
            var sum = Vec2()
            var count = 0
            line.nodes.forEach {
                sum.add(Vec2(it.to.x, it.to.y))
                count++
            }
            return if (count > 0) sum.div(count.toFloat()) else Vec2()
        }
        
        var cx = 0f
        var cy = 0f
        var prevPoint: Vec2? = null
        var firstPoint: Vec2? = null
        
        line.nodes.forEach { node ->
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
    fun reverse(line: FLine): FLine {
        val result = FLine()
        
        if (line.nodes.isEmpty()) return result
        
        // Start at the end
        result.moveTo(line.nodes.last().to)
        
        // Traverse backwards
        for (i in line.nodes.size - 1 downTo 1) {
            val node = line.nodes[i]
            val prevNode = line.nodes[i - 1]
            
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
            line.nodes.forEach { node ->
                result.nodes.add(node.copy())
            }
        }
        
        return result
    }

    /**
     * Blend between two curves.
     * @param t blend factor (0 = line1, 1 = line2)
     */
    fun blend(line1: FLine, line2: FLine, t: Float): FLine {
        val samples = maxOf(line1.nodes.size, line2.nodes.size)
        val points1 = line1.sampleByNumber(samples)
        val points2 = line2.sampleByNumber(samples)
        
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
    fun extract(line: FLine, tStart: Float, tEnd: Float): FLine {
        val cursor = line.cursor()
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
    fun fitCircle(line: FLine, samples: Int = 20): Triple<Vec2, Float, Float> {
        val points = line.sampleByNumber(samples).map { Vec2(it.x, it.y) }
        
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
    fun subdivide(line: FLine, levels: Int = 1): FLine {
        var result = line.copy()
        
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
    fun arcLengthParameterize(line: FLine, spacing: Float): FLine {
        return line.copy {
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
    fun dash(line: FLine, dashLength: Float, gapLength: Float): List<FLine> {
        val result = mutableListOf<FLine>()
        val cursor = line.cursor()
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
    fun wave(line: FLine, amplitude: Float, frequency: Float, samples: Int = 100): FLine {
        val result = FLine()
        val cursor = line.cursor(0.01f)
        
        for (i in 0 until samples) {
            val t = i.toFloat() / (samples - 1) * (line.nodes.size - 1)
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
    fun zigzag(line: FLine, amplitude: Float, segments: Int = 20): FLine {
        val result = FLine()
        val cursor = line.cursor(0.01f)
        
        for (i in 0..segments) {
            val t = i.toFloat() / segments * (line.nodes.size - 1)
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
}
