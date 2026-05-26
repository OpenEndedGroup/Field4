package afield.examples.graphics

import afield.graphics.*
import afield.stdlib.Vec2
import afield.stdlib.Vec3
import afield.stdlib.fline
import afield.stdlib.pointed
import afield.stdlib.vec
import kotlin.math.PI

/**
 * Example demonstrations of FLineGeometry utilities.
 * These examples show practical usage of various computational geometry functions.
 */
object FLineGeometryExamples {

    /**
     * Example 1: Create a spiral and analyze its curvature
     */
    fun spiralCurvatureExample(): Map<String, List<FLine>> {
        // Create a spiral
        val spiral = FLine()
        spiral.moveTo(0f, 0f)

        for (i in 0..100) {
            val t = i / 100f * 4 * PI.toFloat()
            val r = i / 100f * 50f
            spiral.lineTo(r * kotlin.math.cos(t), r * kotlin.math.sin(t))
        }

        // Analyze curvature
        val curvatures = spiral.curvatureAnalysis( samples = 100)

        // Plot curvature as a simple chart (x=index, y=curvature scaled)
        val curvaturePlot = FLine()
        curvatures.forEachIndexed { idx, (_, k) ->
            val pt = vec(idx.toFloat(), k * 50f, 0f)
            if (idx == 0) curvaturePlot.moveTo(pt) else curvaturePlot.lineTo(pt)
        }

        return mapOf(
            "spiral" to listOf(spiral),
            "curvature_plot" to listOf(curvaturePlot)
        )
    }

    /**
     * Example 2: Create a shape and generate offset versions
     */
    fun offsetShapeExample(): Map<String, List<FLine>> {
        // Create a star shape
        val star = FLine()
        val points = 5
        val outerRadius = 50f
        val innerRadius = 20f

        star.moveTo(outerRadius, 0f)
        for (i in 1..points * 2) {
            val angle = i * PI.toFloat() / points
            val r = if (i % 2 == 0) outerRadius else innerRadius
            star.lineTo(r * kotlin.math.cos(angle), r * kotlin.math.sin(angle))
        }
        star.lineTo(outerRadius, 0f)

        // Create inner and outer offset versions
        val inner = star.byOffsetting( distance = -0.4f, samples = 50)
        val outer = star.byOffsetting( distance = 0.4f, samples = 50)

        return mapOf(
            "star" to listOf(star),
            "inner" to listOf(inner),
            "outer" to listOf(outer)
        )
    }

    /**
     * Example 3: Boolean operations on shapes
     */
    fun booleanOperationsExample(): Map<String, List<FLine>> {
        // Create two overlapping circles
        val circle1 = createCircle(center = vec(0f, 0f), radius = 50f, segments = 64)
        val circle2 = createCircle(center = vec(40f, 0f), radius = 50f, segments = 64)

        return mapOf(
            "circle1" to listOf(circle1),
            "circle2" to listOf(circle2),
            "union" to listOf(circle1.byUnionWith(circle2)),
            "intersection" to listOf(circle1.byIntersectionWith(circle2)),
            "difference" to listOf(circle1.byDifferenceWith(circle2)),
            "xor" to listOf(circle1.byExclusiveOrWith(circle2))
        )
    }

    /**
     * Example 4: Smooth and simplify a noisy path
     */
    fun smoothAndSimplifyExample(): Map<String, List<FLine>> {
        // Create a noisy path
        val noisy = FLine()
        noisy.moveTo(0f, 0f)

        for (i in 1..100) {
            val x = i.toFloat()
            val y = kotlin.math.sin(i / 10f) * 20f + (Math.random().toFloat() - 0.5f) * 5f
            noisy.lineTo(x, y)
        }

        // Smooth it
        val smoothed = noisy.byGaussianSmoothing( sigma = 3f, samples = 100)

        // Simplify it
        val simplified = smoothed.bySimplifying( epsilon = 2f)

        return mapOf(
            "noisy" to listOf(noisy),
            "smoothed" to listOf(smoothed),
            "simplified" to listOf(simplified)
        )
    }

    /**
     * Example 5: Generate tangent and normal vectors
     */
    fun tangentNormalExample(): Map<String, List<FLine>> {
        // Create a wavy curve
        val curve = FLine()
        curve.moveTo(0f, 0f)

        for (i in 1..100) {
            val x = i.toFloat()
            val y = kotlin.math.sin(i / 10f) * 20f
            curve.lineTo(x, y)
        }

        // Generate tangent and normal lines
        val tangents = curve.byCreatingTangentLines( samples = 15, length = 10f)
        val normals = curve.byCreatingNormalLines( samples = 15, length = 10f)

        return mapOf(
            "curve" to listOf(curve),
            "tangents" to tangents,
            "normals" to normals
        )
    }

    /**
     * Example 6: Morphing between two shapes
     */
    fun morphingExample(steps: Int = 5): Map<String, List<FLine>> {
        // Create two shapes
        val square = fline {
            moveTo(-25f, -25f)
            lineTo(25f, -25f)
            lineTo(25f, 25f)
            lineTo(-25f, 25f)
            lineTo(-25f, -25f)
        }

        val circle = createCircle(center = vec(0f, 0f), radius = 25f, segments = 64)

        // Create intermediate shapes by blending
        val morphs = mutableListOf<FLine>()
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            morphs.add(square.byBlendingWith(circle, t))
        }

        return mapOf(
            "morphs" to morphs
        )
    }

    /**
     * Example 7: Pattern generation (wave, dash, zigzag)
     */
    fun patternExample(): Map<String, List<FLine>> {
        // Create a base curve
        val baseLine = FLine()
        baseLine.moveTo(0f, 0f)
        for (i in 1..100) {
            val x = i.toFloat()
            val y = kotlin.math.sin(i / 20f) * 10f
            baseLine.lineTo(x, y)
        }

        // Generate patterns
        return mapOf(
            "original" to listOf(baseLine),
            "wave" to listOf(baseLine.byWaving( amplitude = 5f, frequency = 3f, samples = 200)),
            "zigzag" to listOf(baseLine.byZigzagging( amplitude = 5f, segments = 30)),
            "dashed" to baseLine.byDashing( dashLength = 5f, gapLength = 3f)
        )
    }

    /**
     * Example 8: 3D transformations and projections
     */
    fun transformations3DExample(): Map<String, List<FLine>> {
        // Create a 3D helix
        val helix = FLine()
        helix.moveTo(0f, 0f, 0f)

        for (i in 1..100) {
            val t = i / 100f * 4 * PI.toFloat()
            val r = 20f
            val x = r * kotlin.math.cos(t)
            val y = r * kotlin.math.sin(t)
            val z = i / 100f * 50f
            helix.lineTo(x, y, z)
        }

        // Apply various transformations
        return mapOf(
            "original" to listOf(helix),
            "rotated" to listOf(helix.byRotating3D(
                angle = PI.toFloat() / 4,
                axis = vec(1f, 0f, 0f))),
            "perspective" to listOf(helix.byPerspectiveProjecting(
                cameraPos = vec(0f, 0f, 100f),
                planePoint = vec(0f, 0f, 0f),
                planeNormal = vec(0f, 0f, 1f))),
            "orthographic" to listOf(helix.byOrthographicProjecting(
                planePoint = vec(0f, 0f, 0f),
                planeNormal = vec(0f, 1f, 1f)))
        )
    }

    /**
     * Example 9: Convex hull computation
     */
    fun convexHullExample(): Map<String, List<FLine>> {
        // Create a random point cloud
        val pointCloud = FLine()
        pointCloud.moveTo(0f, 0f)

        for (i in 1..50) {
            val angle = Math.random().toFloat() * 2 * PI.toFloat()
            val r = (Math.random().toFloat() * 50f + 10f)
            pointCloud.lineTo(r * kotlin.math.cos(angle), r * kotlin.math.sin(angle))
        }

        // Compute convex hull
        val hull = pointCloud.byConvexHull2D()

        return mapOf(
            "points" to listOf(pointCloud),
            "hull" to listOf(hull)
        )
    }

    /**
     * Example 10: Curvature-adaptive sampling
     */
    fun adaptiveSamplingExample(): Map<String, List<FLine>> {
        // Create a curve with varying curvature
        val curve = FLine()
        curve.moveTo(0f, 0f)

        for (i in 1..100) {
            val t = i / 100f
            val x = t * 100f
            // Mix low and high frequency components
            val y = kotlin.math.sin(t * 2 * PI.toFloat()) * 20f +
                    kotlin.math.sin(t * 10 * PI.toFloat()) * 5f
            curve.lineTo(x, y)
        }

        // Create adaptive sampling (more samples where curvature is high)
        val adaptive = curve.byCurvatureAdaptiveSampling(
            minSamples = 20,
            maxSamples = 200)

        return mapOf(
            "curve" to listOf(curve),
            "adaptive" to listOf(adaptive)
        )
    }

    // Helper function to create a circle
    private fun createCircle(center: Vec2, radius: Float, segments: Int): FLine {
        val circle = FLine()
        for (i in 0..segments) {
            val angle = i / segments.toFloat() * 2 * PI.toFloat()
            val x = center.x + radius * kotlin.math.cos(angle)
            val y = center.y + radius * kotlin.math.sin(angle)

            if (i == 0) {
                circle.moveTo(x, y)
            } else {
                circle.lineTo(x, y)
            }
        }
        return circle
    }

    private fun pointMarker(x: Float, y: Float, z: Float = 0f): FLine {
        return fline {
            moveTo(x, y, z)
            pointed = true
        }
    }

    private fun pointMarkers(points: List<Vec3>): List<FLine> {
        return points.map { pointMarker(it.x, it.y, it.z) }
    }

    /**
     * Comprehensive demo that exercises most functionality
     */
    fun comprehensiveDemo(): Map<String, List<FLine>> {
        val results = mutableMapOf<String, List<FLine>>()

        // 1. Create base shapes
        val square = fline {
            moveTo(0f, 0f)
            lineTo(50f, 0f)
            lineTo(50f, 50f)
            lineTo(0f, 50f)
            lineTo(0f, 0f)
        }
        results["square"] = listOf(square)

        // 2. Transform it
        results["rotated"] = listOf(square.byRotating2D( PI.toFloat() / 6))
        results["scaled"] = listOf(square.byScaling2D( 1.5f))
        results["sheared"] = listOf(square.byShearing2D( 0.3f, 0.1f))

        // 3. Create offset versions
        results["offset_inner"] = listOf(square.byOffsetting( -5f))
        results["offset_outer"] = listOf(square.byOffsetting( 5f))

        // 4. Create a wavy curve and analyze it
        val wave = fline {
            moveTo(0f, 0f)
            for (i in 1..100) {
                val x = i.toFloat()
                val y = kotlin.math.sin(i / 10f) * 20f
                lineTo(x, y)
            }
        }
        results["wave"] = listOf(wave)
        val waveCurvatures = wave.curvatureAnalysis( samples = 50)
        val waveCurvaturePlot = fline {
            waveCurvatures.forEachIndexed { idx, (_, k) ->
                val pt = vec(idx.toFloat(), k * 20f, 0f)
                if (idx == 0) moveTo(pt) else lineTo(pt)
            }
        }
        results["wave_curvatures"] = listOf(waveCurvaturePlot)
        results["wave_simplified"] = listOf(wave.bySimplifying( epsilon = 2f))

        // 5. Boolean operations
        val circle1 = createCircle(vec(25f, 25f), 30f, 64)
        val circle2 = createCircle(vec(40f, 25f), 30f, 64)
        results["union"] = listOf(circle1.byUnionWith(circle2))
        results["intersection"] = listOf(circle1.byIntersectionWith(circle2))

        // 6. Geometric queries
        val centroid = square.centroid2D()
        val centroidMark = fline {
            moveTo(centroid.x, centroid.y)
            lineTo(centroid.x + 0.5f, centroid.y + 0.5f)
        }
        results["centroid"] = listOf(centroidMark)

        return results
    }

    /**
     * Example 11: Arc construction and higher-order curves
     */
    fun arcExample(): Map<String, List<FLine>> {
        val results = mutableMapOf<String, List<FLine>>()

        // Create an arc through three points
        val arc1 = arcThrough3Points(
            p1 = vec(0f, 0f, 0f),
            p2 = vec(50f, 50f, 0f),
            p3 = vec(100f, 0f, 0f),
            segments = 32
        )
        results["arc_through_3_points"] = listOf(arc1)

        // Create a circular arc in 3D
        val arc2 = FLine()
        arc2.byAppendingArc3D(
            center = vec(50f, 50f, 0f),
            radius = 30f,
            startAngle = 0f,
            endAngle = PI.toFloat(),
            normal = vec(0f, 0f, 1f),
            segments = 32
        )
        results["circular_arc"] = listOf(arc2)

        // Create a spiral using multiple arcs
        val spiral = FLine()
        for (i in 0..10) {
            val radius = 10f + i * 3f
            val startAngle = i * PI.toFloat() / 2
            val endAngle = (i + 1) * PI.toFloat() / 2
            spiral.byAppendingArc3D(
                center = vec(0f, 0f, 0f),
                radius = radius,
                startAngle = startAngle,
                endAngle = endAngle,
                normal = vec(0f, 0f, 1f),
                segments = 8
            )
        }
        results["spiral"] = listOf(spiral)

        return results
    }

    /**
     * Example 12: Noise utilities comparison
     */
    fun noiseComparisonExample(): Map<String, List<FLine>> {
        val results = mutableMapOf<String, List<FLine>>()

        // Create a base line
        val baseLine = FLine()
        baseLine.moveTo(0f, 50f)
        for (i in 1..100) {
            baseLine.lineTo(i.toFloat(), 50f)
        }
        results["original"] = listOf(baseLine)

        // Apply different noise types
        results["gaussian_noise"] = listOf(baseLine.byGaussianNoising(
            amplitude = 5f,
            seed = 12345L,
            samples = 100
        ))

        results["oriented_gaussian"] = listOf(baseLine.byOrientedGaussianNoising(
            normalAmplitude = 10f,
            tangentAmplitude = 2f,
            seed = 12345L,
            samples = 100
        ))

        results["perlin_noise"] = listOf(baseLine.byPerlinNoising(
            amplitude = 8f,
            frequency = 0.5f,
            octaves = 4,
            seed = 12345L,
            samples = 100
        ))

        return results
    }

    /**
     * Example 13: Curve combination operations
     */
    fun curveCombinationExample(): Map<String, List<FLine>> {
        val results = mutableMapOf<String, List<FLine>>()

        // Create two curves
        val curve1 = FLine()
        curve1.moveTo(0f, 50f)
        for (i in 1..100) {
            curve1.lineTo(i.toFloat(), 50f + kotlin.math.sin(i / 10f) * 20f)
        }
        results["curve1"] = listOf(curve1)

        val curve2 = FLine()
        curve2.moveTo(0f, 0f)
        for (i in 1..100) {
            curve2.lineTo(i.toFloat(), kotlin.math.cos(i / 8f) * 15f)
        }
        results["curve2"] = listOf(curve2)

        // Combine them in different ways
        results["add"] = listOf(curve1.byAddingWith(curve2))
        results["subtract"] = listOf(curve1.bySubtractingWith(curve2))
        results["multiply"] = listOf(curve1.byMultiplyingWith(curve2))
        results["project"] = listOf(curve1.byProjectingOnto(curve2))

        // Geometric inversion
        val circle = createCircle(vec(50f, 25f), 30f, 64)
        results["circle"] = listOf(circle)
        results["inverted_circle"] = listOf(circle.byInverting(
            center = vec(50f, 25f),
            radius = 40f
        ))

        return results
    }

    /**
     * Example 14: Parameterization system
     */
    fun parameterizationExample(): Map<String, List<FLine>> {
        val results = mutableMapOf<String, List<FLine>>()

        // Create a wavy base curve
        val base = FLine()
        base.moveTo(0f, 50f)
        for (i in 1..100) {
            val x = i.toFloat()
            val y = 50f + kotlin.math.sin(i / 10f) * 20f
            base.lineTo(x, y)
        }
        results["original"] = listOf(base)

        // Apply offset with linear parameter (increases from 2 to 15)
        results["offset_linear"] = listOf(base.byOffsettingParameterized(
            LinearParameter(start = 2f, end = 15f),
            samples = 100
        ))

        // Apply offset with sine wave parameter
        results["offset_sine"] = listOf(base.byOffsettingParameterized(
                        SineParameter(
                amplitude = 8f,
                frequency = 3f,
                offset = 5f
            ),
            samples = 100
        ))

        // Apply offset based on curvature
        results["offset_curvature"] = listOf(base.byOffsettingParameterized(
            CurvatureParameter(
                line = base,
                minValue = 2f,
                maxValue = 20f,
                maxCurvature = 0.5f
            ),
            samples = 100
        ))

        // Apply noise with varying amplitude
        results["noise_linear"] = listOf(base.byNoisingParameterized(
            LinearParameter(start = 10f, end = 1f),
            seed = 12345L,
            samples = 100
        ))

        // Apply scaling based on distance from a point
        results["scale_distance"] = listOf(base. byScalingParameterized(
            DistanceParameter(
                point = vec(50f, 50f, 0f),
                minValue = 0.5f,
                maxValue = 2f,
                maxDistance = 100f
            ),
            center = vec(50f, 50f),
            samples = 100
        ))

        // Create a control curve and use it to drive offset
        val controlCurve = FLine()
        controlCurve.moveTo(0f, 2f)
        controlCurve.lineTo(50f, 15f)
        controlCurve.lineTo(100f, 2f)
        results["control_curve"] = listOf(controlCurve)

        results["offset_curve_driven"] = listOf(base.byOffsettingParameterized(
            CurveParameter(
                curve = controlCurve,
                scale = 1f,
                offset = 0f
            ),
            samples = 100
        ))

        return results
    }

    /**
     * Example 15: Complex composition with new features
     */
    fun complexCompositionExample(): Map<String, List<FLine>> {
        // Create a base spiral using arcs
        val spiral = FLine()
        for (i in 0..8) {
            val radius = 10f + i * 5f
            val startAngle = i * PI.toFloat() / 2
            val endAngle = (i + 1) * PI.toFloat() / 2
            spiral.byAppendingArc3D(

                center = vec(100f, 100f, 0f),
                radius = radius,
                startAngle = startAngle,
                endAngle = endAngle,
                normal = vec(0f, 0f, 1f),
                segments = 12
            )
        }

        // Apply Perlin noise to make it organic
        val noisy = spiral.byPerlinNoising(
            amplitude = 3f,
            frequency = 1f,
            octaves = 3
        )

        // Apply parameterized offset that increases with curve parameter
        val offset = noisy.byOffsettingParameterized(
            LinearParameter(start = 2f, end = 8f)
        )

        // Add some oriented Gaussian noise
        val final = offset.byOrientedGaussianNoising(

            normalAmplitude = 1.5f,
            tangentAmplitude = 0.5f
        )

        return mapOf(
            "spiral" to listOf(spiral),
            "noisy" to listOf(noisy),
            "offset" to listOf(offset),
            "final" to listOf(final)
        )
    }

    /**
     * Example 16: 2D transformations around explicit pivots and axes
     */
    fun transformations2DExample(): Map<String, List<FLine>> {
        val base = fline {
            moveTo(0f, 0f)
            lineTo(60f, 0f)
            lineTo(60f, 30f)
            lineTo(0f, 30f)
            lineTo(0f, 0f)
        }

        val pivot = vec(30f, 15f)
        val mirrorA = vec(-10f, -10f)
        val mirrorB = vec(80f, 40f)
        val mirrorAxis = fline {
            moveTo(mirrorA)
            lineTo(mirrorB)
        }

        return mapOf(
            "base" to listOf(base),
            "pivot" to listOf(pointMarker(pivot.x, pivot.y)),
            "mirror_axis" to listOf(mirrorAxis),
            "rotated" to listOf(base.byRotating2D(PI.toFloat() / 6f, pivot)),
            "scaled_uniform" to listOf(base.byScaling2D(1.4f, pivot)),
            "scaled_nonuniform" to listOf(base.byScaling2D(1.6f, 0.6f, pivot)),
            "reflected" to listOf(base.byReflecting2D(mirrorA, mirrorB))
        )
    }

    /**
     * Example 17: Parallel curve generation
     */
    fun parallelCurvesExample(): Map<String, List<FLine>> {
        val base = fline {
            moveTo(0f, 0f)
            for (i in 1..80) {
                val x = i.toFloat() * 2f
                val y = kotlin.math.sin(i / 8f) * 12f
                lineTo(x, y)
            }
        }

        val (left, right) = base.byCreatingParallelCurves(distance = 6f, samples = 120)

        return mapOf(
            "base" to listOf(base),
            "parallel_left" to listOf(left),
            "parallel_right" to listOf(right)
        )
    }

    /**
     * Example 18: Intersections and self-intersections
     */
    fun intersectionsExample(): Map<String, List<FLine>> {
        val selfIntersecting = fline {
            moveTo(-40f, -20f)
            lineTo(40f, 20f)
            lineTo(-40f, 20f)
            lineTo(40f, -20f)
        }
        val selfIntersections = pointMarkers(selfIntersecting.findSelfIntersections(1f))

        val circle = createCircle(vec(0f, 0f), 35f, 64)
        val line = fline {
            moveTo(-60f, -10f)
            lineTo(60f, 10f)
        }
        val crossings = pointMarkers(line.findIntersectionsWith(circle, 1f))

        return mapOf(
            "self_intersecting" to listOf(selfIntersecting),
            "self_intersections" to selfIntersections,
            "circle" to listOf(circle),
            "line" to listOf(line),
            "line_circle_intersections" to crossings
        )
    }

    /**
     * Example 19: Curvature features and inflections
     */
    fun curvatureFeaturesExample(): Map<String, List<FLine>> {
        val curve = fline {
            moveTo(0f, 0f)
            for (i in 1..120) {
                val x = i.toFloat()
                val y = kotlin.math.sin(i / 6f) * 18f + kotlin.math.sin(i / 20f) * 6f
                lineTo(x, y)
            }
        }

        val highCurvaturePoints = pointMarkers(curve.findHighCurvaturePoints(threshold = 0.2f, samples = 120))
        val inflectionPieces = curve.bySplittingAtInflections()

        return mapOf(
            "curve" to listOf(curve),
            "high_curvature_points" to highCurvaturePoints,
            "inflection_pieces" to inflectionPieces
        )
    }

    /**
     * Example 20: Osculating circles along a curve
     */
    fun osculatingCirclesExample(): Map<String, List<FLine>> {
        val points = (0..80).map { i ->
            val x = i.toFloat() * 2f
            val y = kotlin.math.sin(i / 10f) * 20f
            vec(x, y, 0f)
        }

        // Smooth polyline into cubic segments (Catmull-Rom -> Bezier).
        fun catmullRomToBezier(pts: List<Vec3>): FLine {
            if (pts.isEmpty()) return FLine()
            val curve = FLine()
            curve.moveTo(pts.first())

            val n = pts.size
            for (i in 0 until n - 1) {
                val p0 = if (i > 0) pts[i - 1] else pts[i]
                val p1 = pts[i]
                val p2 = pts[i + 1]
                val p3 = if (i + 2 < n) pts[i + 2] else pts[i + 1]

                val c1 = Vec3(p1).add(Vec3(p2).sub(p0).mul(1f / 6f))
                val c2 = Vec3(p2).sub(Vec3(p3).sub(p1).mul(1f / 6f))
                curve.cubicTo(c1, c2, p2)
            }

            return curve
        }

        val curve = catmullRomToBezier(points)
        val circles = curve.byCreatingOsculatingCircles(samples = 10, circleSegments = 24)

        return mapOf(
            "curve" to listOf(curve),
            "osculating_circles" to circles
        )
    }

    /**
     * Example 21: Geometric queries and point tests
     */
    fun geometricQueriesExample(): Map<String, List<FLine>> {
        val square = fline {
            moveTo(0f, 0f)
            lineTo(60f, 0f)
            lineTo(60f, 60f)
            lineTo(0f, 60f)
            lineTo(0f, 0f)
        }

        val probe = vec(80f, 20f, 0f)
        val (closest, _) = square.closestPoint(probe)
        val closestLine = fline {
            moveTo(probe.x, probe.y, probe.z)
            lineTo(closest.x, closest.y, closest.z)
        }

        val insidePoint = vec(20f, 20f, 0f)
        val outsidePoint = vec(80f, 20f, 0f)
        val insidePoint2D = vec(20f, 20f)
        val outsidePoint2D = vec(80f, 20f)
        val insideContains = square.contains(insidePoint2D)
        val outsideContains = square.contains(outsidePoint2D)
        val insideWinding = square.windingNumber(insidePoint2D)
        val outsideWinding = square.windingNumber(outsidePoint2D)
        val signedArea = square.signedArea2D()

        val insideIndicator = fline {
            val length = if (insideContains) 6f else 0f
            moveTo(insidePoint.x, insidePoint.y + 6f)
            lineTo(insidePoint.x + length, insidePoint.y + 6f)
        }
        val outsideIndicator = fline {
            val length = if (outsideContains) 6f else 0f
            moveTo(outsidePoint.x, outsidePoint.y + 6f)
            lineTo(outsidePoint.x + length, outsidePoint.y + 6f)
        }
        val insideWindingBar = fline {
            moveTo(insidePoint.x, insidePoint.y - 8f)
            lineTo(insidePoint.x + insideWinding.toFloat() * 6f, insidePoint.y - 8f)
        }
        val outsideWindingBar = fline {
            moveTo(outsidePoint.x, outsidePoint.y - 8f)
            lineTo(outsidePoint.x + outsideWinding.toFloat() * 6f, outsidePoint.y - 8f)
        }
        val areaBar = fline {
            moveTo(0f, -12f)
            lineTo(signedArea / 100f, -12f)
        }
        val centroid = square.centroid2D()

        return mapOf(
            "square" to listOf(square),
            "probe_point" to listOf(pointMarker(probe.x, probe.y, probe.z)),
            "closest_point" to listOf(pointMarker(closest.x, closest.y, closest.z)),
            "closest_connection" to listOf(closestLine),
            "inside_point" to listOf(pointMarker(insidePoint.x, insidePoint.y, insidePoint.z)),
            "outside_point" to listOf(pointMarker(outsidePoint.x, outsidePoint.y, outsidePoint.z)),
            "inside_contains" to listOf(insideIndicator),
            "outside_contains" to listOf(outsideIndicator),
            "inside_winding" to listOf(insideWindingBar),
            "outside_winding" to listOf(outsideWindingBar),
            "signed_area" to listOf(areaBar),
            "centroid" to listOf(pointMarker(centroid.x, centroid.y))
        )
    }

    /**
     * Example 22: Path operations (reverse, concatenate, extract)
     */
    fun pathOperationsExample(): Map<String, List<FLine>> {
        val base = fline {
            moveTo(0f, 0f)
            lineTo(40f, 20f)
            lineTo(80f, -10f)
            lineTo(120f, 30f)
        }

        val reversed = base.byReversing()
        val extracted = base.byExtracting(0.25f, 0.75f)
        val extractedMarkers = pointMarkers(extracted.sampleByNumber(2))
        val circle = createCircle(vec(160f, 10f), 20f, 40)
        val concatenated = concatenate(base, circle)

        val baseStart = pointMarker(0f, 0f)
        val baseEnd = pointMarker(120f, 30f)
        val reversedStart = pointMarker(120f, 30f)
        val reversedEnd = pointMarker(0f, 0f)

        return mapOf(
            "base" to listOf(base),
            "base_start" to listOf(baseStart),
            "base_end" to listOf(baseEnd),
            "reversed" to listOf(reversed),
            "reversed_start" to listOf(reversedStart),
            "reversed_end" to listOf(reversedEnd),
            "extracted" to listOf(extracted),
            "extracted_ends" to extractedMarkers,
            "concatenated" to listOf(concatenated),
            "concat_circle" to listOf(circle)
        )
    }

    /**
     * Example 23: Comprehensive curve and line fitting operations
     */
    fun curveFittingExample(): Map<String, List<FLine>> {
        // Circle fitting
        val noisyCircle = createCircle(vec(0f, 0f), 30f, 32).byGaussianNoising(
            amplitude = 2f,
            seed = 1234L,
            samples = 64
        )
        val (fitCenter, fitRadius, circleError) = noisyCircle.fitCircle(32)
        val fittedCircle = createCircle(fitCenter, fitRadius, 64)
        
        // Line fitting in 2D
        val noisyLine = fline {
            moveTo(-50f, -20f)
            for (i in 1..20) {
                val x = -50f + i * 5f
                val y = -20f + i * 2f + (kotlin.random.Random.nextFloat() - 0.5f) * 5f
                lineTo(x, y)
            }
        }
        val (lineStart, lineEnd, lineError) = noisyLine.fitLine2D(samples = 20)
        val fittedLine = fline {
            moveTo(lineStart.x, lineStart.y)
            lineTo(lineEnd.x, lineEnd.y)
        }
        
        // Polynomial fitting
        val curvedPath = fline {
            moveTo(0f, 0f)
            for (i in 1..20) {
                val x = i * 3f
                val y = 0.01f * x * x - 0.5f * x + 10f + (kotlin.random.Random.nextFloat() - 0.5f) * 2f
                lineTo(x, y)
            }
        }
        val (polyCoeffs, polyError) = curvedPath.fitPolynomial(degree = 2, samples = 20)
        val fittedPoly = fline {
            moveTo(0f, polyCoeffs[0])
            for (i in 1..60) {
                val x = i.toFloat()
                val y = polyCoeffs[0] + polyCoeffs[1] * x + polyCoeffs[2] * x * x
                lineTo(x, y)
            }
        }
        
        // Ellipse fitting
        val noisyEllipse = fline {
            for (i in 0..40) {
                val angle = i / 40f * 2 * PI.toFloat()
                val x = 40f * kotlin.math.cos(angle) + (kotlin.random.Random.nextFloat() - 0.5f) * 3f
                val y = 20f * kotlin.math.sin(angle) + (kotlin.random.Random.nextFloat() - 0.5f) * 3f
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        val ellipseFit = noisyEllipse.fitEllipse(samples = 40)
        // Draw fitted ellipse
        val fittedEllipse = fline {
            for (i in 0..64) {
                val angle = i / 64f * 2 * PI.toFloat()
                val localX = ellipseFit.semiMajorAxis * kotlin.math.cos(angle)
                val localY = ellipseFit.semiMinorAxis * kotlin.math.sin(angle)
                // Rotate and translate
                val rotCos = kotlin.math.cos(ellipseFit.rotationAngle)
                val rotSin = kotlin.math.sin(ellipseFit.rotationAngle)
                val x = ellipseFit.center.x + localX * rotCos - localY * rotSin
                val y = ellipseFit.center.y + localX * rotSin + localY * rotCos
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        
        // Bezier curve fitting
        val wavyPath = fline {
            moveTo(0f, 50f)
            for (i in 1..10) {
                val x = i * 12f
                val y = 50f + kotlin.math.sin(i / 2f) * 20f
                lineTo(x, y)
            }
        }
        val (bezierPoints, bezierError) = wavyPath.fitBezierCubic(samples = 10)
        val fittedBezier = fline {
            moveTo(bezierPoints[0])
            cubicTo(bezierPoints[1], bezierPoints[2], bezierPoints[3])
        }
        
        // Piecewise linear fitting
        val complexCurve = fline {
            moveTo(0f, 0f)
            for (i in 1..50) {
                val x = i * 3f
                val y = kotlin.math.sin(i / 5f) * 15f + kotlin.math.cos(i / 10f) * 8f
                lineTo(x, y)
            }
        }
        val piecewiseSegments = complexCurve.fitPiecewiseLinear(
            maxSegments = 8,
            curvatureThreshold = 0.08f,
            samples = 50
        )
        
        // Points to FLine conversion with different strategies
        val points = listOf(
            vec(0f, 0f, 0f),
            vec(20f, 30f, 0f),
            vec(40f, 35f, 0f),
            vec(60f, 25f, 0f),
            vec(80f, 35f, 0f),
            vec(100f, 20f, 0f)
        )
        val linearFit = pointsToFLine(points, "linear")
        val quadraticFit = pointsToFLine(points, "quadratic")
        val bezierFit = pointsToFLine(points, "bezier")
        val adaptiveFit = pointsToFLine(points, "adaptive")
        
        // Subdivide and arc-length parameterization
        val polyline = fline {
            moveTo(0f, 0f)
            for (i in 1..12) {
                lineTo(i * 10f, kotlin.math.sin(i / 2f) * 15f)
            }
        }
        val subdivided = polyline.bySubdividing(2)
        val arcLength = polyline.byArcLengthParameterizing(5f)

        return mapOf(
            "noisy_circle" to listOf(noisyCircle),
            "fitted_circle" to listOf(fittedCircle),
            "fit_center" to listOf(pointMarker(fitCenter.x, fitCenter.y)),
            "noisy_line" to listOf(noisyLine),
            "fitted_line" to listOf(fittedLine),
            "curved_path" to listOf(curvedPath),
            "fitted_polynomial" to listOf(fittedPoly),
            "noisy_ellipse" to listOf(noisyEllipse),
            "fitted_ellipse" to listOf(fittedEllipse),
            "ellipse_center" to listOf(pointMarker(ellipseFit.center.x, ellipseFit.center.y)),
            "wavy_path" to listOf(wavyPath),
            "fitted_bezier" to listOf(fittedBezier),
            "complex_curve" to listOf(complexCurve),
            "piecewise_segments" to piecewiseSegments,
            "input_points" to points.map { pointMarker(it.x, it.y, it.z) },
            "linear_fit" to listOf(linearFit),
            "quadratic_fit" to listOf(quadraticFit),
            "bezier_fit" to listOf(bezierFit),
            "adaptive_fit" to listOf(adaptiveFit),
            "polyline" to listOf(polyline),
            "subdivided" to listOf(subdivided),
            "arc_length_param" to listOf(arcLength)
        )
    }

    /**
     * Example 24: Higher-order curves (quadratic, tangential arc, B-spline)
     */
    fun higherOrderCurvesExample(): Map<String, List<FLine>> {
        val quad = fline { moveTo(0f, 0f) }
        quad.byAppendingQuadratic(vec(50f, 80f, 0f), vec(100f, 0f, 0f))
        val quadControl = pointMarker(50f, 80f)
        val quadEnd = pointMarker(100f, 0f)

        val arcByTangent = fline { moveTo(0f, 0f) }
        arcByTangent.byAppendingArcByTangent(
            endPoint = vec(100f, 0f, 0f),
            startTangent = vec(1f, 1f, 0f),
            endTangent = vec(1f, -1f, 0f),
            segments = 32
        )
        val startTangent = fline {
            moveTo(0f, 0f)
            lineTo(15f, 15f)
        }
        val endTangent = fline {
            moveTo(100f, 0f)
            lineTo(115f, -15f)
        }

        val controls = listOf(
            vec(0f, 0f, 0f),
            vec(30f, 40f, 0f),
            vec(70f, 60f, 0f),
            vec(110f, 20f, 0f),
            vec(140f, 50f, 0f)
        )
        val controlPolyline = fline {
            moveTo(controls.first())
            controls.drop(1).forEach { lineTo(it) }
        }
        val controlMarkers = controls.map { pointMarker(it.x, it.y, it.z) }
        val spline = bsplineFrom(controls, degree = 3, samples = 80, closed = false)
        val smoothed = controlPolyline.byBSplineSmoothed(
            degree = 4,
            controlSampleCount = 12,
            samples = 80,
            dryWet = 0.8f
        )

        return mapOf(
            "quad" to listOf(quad),
            "quad_control" to listOf(quadControl),
            "quad_end" to listOf(quadEnd),
            "arc_by_tangent" to listOf(arcByTangent),
            "tangent_start" to listOf(startTangent),
            "tangent_end" to listOf(endTangent),
            "spline_controls" to listOf(controlPolyline),
            "spline_control_points" to controlMarkers,
            "bspline" to listOf(spline),
            "bspline_smoothed" to listOf(smoothed)
        )
    }

    /**
     * Example 25: Euler spiral interpolation
     */
    fun eulerSpiralExample(): Map<String, List<FLine>> {
        val start = vec(0f, 0f, 0f)
        val end = vec(120f, 40f, 0f)
        val startDir = vec(1f, 0f, 0f)
        val endDir = vec(kotlin.math.cos(PI.toFloat() / 3f), kotlin.math.sin(PI.toFloat() / 3f), 0f)
        val spiral = eulerSpiralBetween3D(
            start = start,
            startDirection = startDir,
            end = end,
            endDirection = endDir,
            samples = 120
        )

        val base = fline { moveTo(start) }
        val spiralTo = base.byEulerSpiralTo(
            endPoint = vec(120f, -20f, 0f),
            endDirection = vec(kotlin.math.cos(PI.toFloat() / 4f), kotlin.math.sin(PI.toFloat() / 4f), 0f),
            samples = 100
        )

        return mapOf(
            "euler_spiral_between" to listOf(spiral),
            "euler_spiral_to" to listOf(spiralTo),
            "start_point" to listOf(pointMarker(start.x, start.y, start.z)),
            "end_point" to listOf(pointMarker(end.x, end.y, end.z))
        )
    }

    /**
     * Example 26: Curvature-driven evolution (flow)
     */
    fun curvatureFlowExample(): Map<String, List<FLine>> {
        val base = fline {
            moveTo(0f, 0f)
            for (i in 1..90) {
                val x = i.toFloat()
                val y = kotlin.math.sin(i / 7f) * 18f + kotlin.math.sin(i / 2f) * 3f
                lineTo(x, y)
            }
        }

        val flowed = base.byCurvatureFlow(
            step = 0.2f,
            iterations = 5,
            samples = 120,
            anchorEnds = true,
            dryWet = 0.7f
        )

        return mapOf(
            "base" to listOf(base),
            "flowed" to listOf(flowed)
        )
    }

    /**
     * Example 27: Gaussian Process interpolation
     */
    fun gaussianProcessExample(): Map<String, List<FLine>> {
        val base = fline {
            moveTo(0f, 0f)
            for (i in 1..40) {
                val x = i.toFloat() * 3f
                val y = kotlin.math.sin(i / 4f) * 12f + (Math.random().toFloat() - 0.5f) * 4f
                lineTo(x, y)
            }
        }

        val interpolated = base.byGaussianProcessInterpolating(
            trainingSamples = 24,
            samples = 120,
            lengthScale = 0.2f,
            variance = 1f,
            noise = 1e-3f,
            dryWet = 0.85f,
            drawSamples = false
        )

        return mapOf(
            "training_curve" to listOf(base),
            "interpolated" to listOf(interpolated)
        )
    }

    /**
     * Example 28: Smile cubic spline interpolation.
     */
    fun smileSplineExample(): Map<String, List<FLine>> {
        val base = fline {
            moveTo(0f, 0f)
            for (i in 1..36) {
                val x = i.toFloat() * 3f
                val y = kotlin.math.sin(i / 4f) * 10f + kotlin.math.cos(i / 7f) * 4f
                lineTo(x, y)
            }
        }

        val smooth = base.bySmileSplineInterpolating(
            trainingSamples = 24,
            samples = 140,
            dryWet = 0.9f
        )

        return mapOf(
            "training_curve" to listOf(base),
            "smile_spline" to listOf(smooth)
        )
    }

    /**
     * Example 29: Smile Gaussian Process interpolation.
     */
    fun smileGaussianProcessExample(): Map<String, List<FLine>> {
        val base = fline {
            moveTo(0f, 0f)
            for (i in 1..48) {
                val x = i.toFloat() * 2.5f
                val y = kotlin.math.sin(i / 6f) * 15f + (Math.random().toFloat() - 0.5f) * 4f
                lineTo(x, y)
            }
        }

        val interpolated = base.bySmileGaussianProcessInterpolating(
            trainingSamples = 28,
            samples = 150,
            lengthScale = 0.2f,
            noise = 1e-3f,
            dryWet = 0.85f
        )

        return mapOf(
            "training_curve" to listOf(base),
            "smile_gp" to listOf(interpolated)
        )
    }

    /**
     * Example 30: Custom Akima interpolation.
     */
    fun akimaInterpolationExample(): Map<String, List<FLine>> {
        val base = fline {
            moveTo(0f, 0f)
            for (i in 1..32) {
                val x = i.toFloat() * 3.5f
                val y = kotlin.math.sin(i / 3f) * 8f + kotlin.math.sin(i / 9f) * 6f
                lineTo(x, y)
            }
        }

        val interpolated = base.byAkimaInterpolating(
            trainingSamples = 20,
            samples = 150,
            dryWet = 0.9f
        )

        return mapOf(
            "training_curve" to listOf(base),
            "akima" to listOf(interpolated)
        )
    }

    /**
     * Example 31: Filtering comparisons
     */
    fun filteringExample(): Map<String, List<FLine>> {
        val jittery = fline {
            moveTo(0f, 0f)
            for (i in 1..120) {
                val x = i.toFloat()
                val y = kotlin.math.sin(i / 6f) * 15f + (Math.random().toFloat() - 0.5f) * 6f
                lineTo(x, y)
            }
        }

        val ema = jittery.byEmaFiltering(alpha = 0.25f, samples = 120, dryWet = 0.8f)
        val oneEuro = jittery.byOneEuroFiltering(
            minCutoffHz = 1.5f,
            beta = 0.02f,
            dCutoffHz = 1f,
            fpsHint = 90f,
            samples = 120,
            dryWet = 0.8f
        )
        val alphaBeta = jittery.byAlphaBetaFiltering(
            alpha = 0.3f,
            beta = 0.1f,
            fpsHint = 120f,
            samples = 120,
            dryWet = 0.8f
        )
        val oneDollar = jittery.byOneDollarFiltering(
            targetSamples = 48,
            targetSize = 150f,
            rotateToZero = true,
            dryWet = 0.9f
        )

        return mapOf(
            "jittery" to listOf(jittery),
            "ema" to listOf(ema),
            "one_euro" to listOf(oneEuro),
            "alpha_beta" to listOf(alphaBeta),
            "one_dollar" to listOf(oneDollar)
        )
    }

    /**
     * Comprehensive smoke test that exercises all examples and every function in FLineGeometry
     * at least once to check for sanity and exceptions.
     *
     * This function is designed to catch runtime errors, incorrect parameter usage, and
     * basic functionality issues across the entire FLineGeometry API.
     *
     * @return true if all tests pass without exceptions
     */
    fun comprehensiveSmokeTest(): Boolean {
        try {
            println("Starting comprehensive FLineGeometry smoke test...")

            // ===== Call all existing examples =====
            println("  Testing all examples...")
            spiralCurvatureExample()
            offsetShapeExample()
            booleanOperationsExample()
            smoothAndSimplifyExample()
            tangentNormalExample()
            morphingExample()
            patternExample()
            transformations3DExample()
            convexHullExample()
            adaptiveSamplingExample()
            arcExample()
            noiseComparisonExample()
            curveCombinationExample()
            parameterizationExample()
            complexCompositionExample()
            transformations2DExample()
            parallelCurvesExample()
            intersectionsExample()
            curvatureFeaturesExample()
            osculatingCirclesExample()
            geometricQueriesExample()
            pathOperationsExample()
            curveFittingExample()
            higherOrderCurvesExample()
            eulerSpiralExample()
            curvatureFlowExample()
            gaussianProcessExample()
            smileSplineExample()
            smileGaussianProcessExample()
            akimaInterpolationExample()
            filteringExample()
            println("    ✓ All examples passed")

            // ===== Create test lines for function testing =====
            val testLine = fline {
                moveTo(0f, 0f)
                for (i in 1..20) {
                    lineTo(i.toFloat() * 5f, kotlin.math.sin(i / 3f) * 10f)
                }
            }

            val testCircle = createCircle(vec(50f, 50f), 30f, 32)
            val testSquare = fline {
                moveTo(0f, 0f)
                lineTo(50f, 0f)
                lineTo(50f, 50f)
                lineTo(0f, 50f)
                lineTo(0f, 0f)
            }

            // ===== 2D Transformations =====
            println("  Testing 2D transformations...")
            testLine.byRotating2D(PI.toFloat() / 4, vec(50f, 50f))
            testLine.byScaling2D(1.5f, vec(50f, 50f))
            testLine.byScaling2D(2f, 0.5f, vec(50f, 50f))
            testLine.byReflecting2D(vec(0f, 0f), vec(100f, 100f))
            testLine.byShearing2D(0.3f, 0.2f)
            println("    ✓ 2D transformations passed")

            // ===== 3D Transformations =====
            println("  Testing 3D transformations...")
            testLine.byRotating3D(PI.toFloat() / 4, vec(0f, 0f, 1f), vec(0f, 0f, 0f))
            testLine.byPerspectiveProjecting(vec(0f, 0f, 100f), vec(0f, 0f, 0f), vec(0f, 0f, 1f))
            testLine.byOrthographicProjecting(vec(0f, 0f, 0f), vec(0f, 0f, 1f))
            println("    ✓ 3D transformations passed")

            // ===== Curve Operations =====
            println("  Testing curve operations...")
            testLine.byOffsetting(5f, 50)
            testLine.byCreatingParallelCurves(5f, 50)
            testLine.byGaussianSmoothing(2f, 100)
            testLine.bySimplifying(2f)
            println("    ✓ Curve operations passed")

            // ===== Intersections =====
            println("  Testing intersections...")
            testLine.findSelfIntersections(1f)
            testLine.findIntersectionsWith(testCircle, 1f)
            println("    ✓ Intersections passed")

            // ===== Curvature Analysis =====
            println("  Testing curvature analysis...")
            testLine.curvatureAnalysis(50)
            testLine.byCurvatureAdaptiveSampling(10, 100)
            testLine.findHighCurvaturePoints(0.1f, 50)
            println("    ✓ Curvature analysis passed")

            // ===== Decomposition =====
            println("  Testing decomposition...")
            testCircle.byConvexHull2D()
            testLine.bySplittingAtInflections()
            println("    ✓ Decomposition passed")

            // ===== Tangent and Normal Operations =====
            println("  Testing tangent and normal operations...")
            testLine.byCreatingTangentLines(10, 5f)
            testLine.byCreatingNormalLines(10, 5f)
            testLine.byCreatingOsculatingCircles(10, 16)
            println("    ✓ Tangent and normal operations passed")

            // ===== Boolean Operations =====
            println("  Testing boolean operations...")
            val circle1 = createCircle(vec(0f, 0f), 30f, 32)
            val circle2 = createCircle(vec(20f, 0f), 30f, 32)
            circle1.byUnionWith(circle2)
            circle1.byDifferenceWith(circle2)
            circle1.byIntersectionWith(circle2)
            circle1.byExclusiveOrWith(circle2)
            println("    ✓ Boolean operations passed")

            // ===== Geometric Queries =====
            println("  Testing geometric queries...")
            testLine.closestPoint(vec(50f, 50f, 0f))
            testSquare.windingNumber(vec(25f, 25f))
            testSquare.contains(vec(25f, 25f))
            testSquare.signedArea2D()
            testSquare.centroid2D()
            println("    ✓ Geometric queries passed")

            // ===== Path Operations =====
            println("  Testing path operations...")
            testLine.byReversing()
            concatenate(testLine, testCircle, testSquare)
            testLine.byBlendingWith(testCircle, 0.5f)
            testLine.byExtracting(0.2f, 0.8f)
            println("    ✓ Path operations passed")

            // ===== Advanced Curve Fitting =====
            println("  Testing advanced curve fitting...")
            testCircle.fitCircle(20)
            testLine.fitLine2D(20)
            testLine.fitLine3D(20)
            testLine.fitPolynomial(degree = 2, samples = 20)
            testLine.fitPolynomial(degree = 3, samples = 20)
            testCircle.fitEllipse(samples = 30)
            testLine.fitBezierCubic(samples = 20)
            testLine.fitPiecewiseLinear(maxSegments = 5, curvatureThreshold = 0.05f, samples = 50)
            
            // Test pointsToFLine with various strategies
            val testPoints = listOf(
                vec(0f, 0f, 0f),
                vec(10f, 5f, 0f),
                vec(20f, 8f, 0f),
                vec(30f, 6f, 0f),
                vec(40f, 10f, 0f)
            )
            val testPoints2D = listOf(
                vec(0f, 0f),
                vec(10f, 5f),
                vec(20f, 8f),
                vec(30f, 6f)
            )
            pointsToFLine(testPoints, "linear")
            pointsToFLine(testPoints, "quadratic")
            pointsToFLine(testPoints, "cubic")
            pointsToFLine(testPoints, "bezier")
            pointsToFLine(testPoints, "adaptive")
            pointsToFLine(testPoints2D, "linear")  // Test Vec2 overload
            
            testLine.bySubdividing(2)
            testLine.byArcLengthParameterizing(5f)
            println("    ✓ Advanced curve fitting passed")

            // ===== Pattern Generation =====
            println("  Testing pattern generation...")
            testLine.byDashing(10f, 5f)
            testLine.byWaving(5f, 2f, 100)
            testLine.byZigzagging(5f, 20)
            println("    ✓ Pattern generation passed")

            // ===== Arcs and Higher-Order Curves =====
            println("  Testing arcs and higher-order curves...")
            val arcLine = FLine()
            arcLine.byAppendingArc3D(vec(0f, 0f, 0f), 30f, 0f, PI.toFloat(), vec(0f, 0f, 1f), 32)
            arcThrough3Points(vec(0f, 0f, 0f), vec(50f, 50f, 0f), vec(100f, 0f, 0f), 32)
            val quadLine = fline { moveTo(0f, 0f) }
            quadLine.byAppendingQuadratic(vec(50f, 100f, 0f), vec(100f, 0f, 0f))
            val tangentLine = fline { moveTo(0f, 0f) }
            tangentLine.byAppendingArcByTangent(vec(100f, 0f, 0f), vec(1f, 1f, 0f), vec(1f, -1f, 0f), 32)
            println("    ✓ Arcs and higher-order curves passed")

            // ===== Noise Utilities =====
            println("  Testing noise utilities...")
            testLine.byGaussianNoising(3f, 12345L, 50)
            testLine.byOrientedGaussianNoising(5f, 2f, 12345L, 50)
            testLine.byPerlinNoising(5f, 1f, 3, 12345L, 50)
            println("    ✓ Noise utilities passed")

            // ===== Curve Combination =====
            println("  Testing curve combination...")
            val curve1 = fline {
                moveTo(0f, 0f)
                for (i in 1..50) lineTo(i.toFloat(), kotlin.math.sin(i / 5f) * 10f)
            }
            val curve2 = fline {
                moveTo(0f, 0f)
                for (i in 1..50) lineTo(i.toFloat(), kotlin.math.cos(i / 4f) * 8f)
            }
            curve1.byAddingWith(curve2, 50)
            curve1.bySubtractingWith(curve2, 50)
            curve1.byMultiplyingWith(curve2, 50)
            curve1.byProjectingOnto(curve2, 50)
            testCircle.byInverting(vec(50f, 50f), 40f, 50)
            println("    ✓ Curve combination passed")

            // ===== Parameterization System =====
            println("  Testing parameterization system...")
            val constantParam = ConstantParameter(10f)
            val linearParam = LinearParameter(5f, 20f)
            val controlCurve = fline {
                moveTo(0f, 5f)
                lineTo(50f, 15f)
                lineTo(100f, 5f)
            }
            val curveParam = CurveParameter(controlCurve, 1f, 0f)
            val distParam = DistanceParameter(vec(50f, 50f, 0f), 5f, 20f, 100f)
            val curvParam = CurvatureParameter(testLine, 2f, 10f, 0.5f)
            val sineParam = SineParameter(5f, 2f, 0f, 10f)
            val lambdaParam = parameterFromLambda { t, pos -> 5f + t * 10f }

            // Test parameterized operations
            testLine.byOffsettingParameterized(linearParam, 50)
            testLine.byNoisingParameterized(sineParam, 12345L, 50)
            testLine.byScalingParameterized(curvParam, vec(50f, 50f), 50)
            println("    ✓ Parameterization system passed")

            // ===== Higher-order curves and resampling =====
            println("  Testing higher-order curves and resampling...")
            val splineControls = listOf(
                vec(0f, 0f, 0f),
                vec(20f, 40f, 0f),
                vec(60f, 60f, 0f),
                vec(100f, 30f, 0f),
                vec(140f, 50f, 0f)
            )
            bsplineFrom(splineControls, degree = 3, samples = 64, closed = false)
            testLine.byBSplineSmoothed(
                degree = 5,
                controlSampleCount = 12,
                samples = 80,
                dryWet = 0.8f,
                wetModulator = LinearParameter(0.2f, 1.1f)
            )
            println("    ✓ Higher-order curves and resampling passed")

            // ===== Euler spiral interpolation =====
            println("  Testing Euler spirals...")
            eulerSpiralBetween3D(
                start = vec(0f, 0f, 0f),
                startDirection = vec(1f, 0f, 0f),
                end = vec(80f, 40f, 0f),
                endDirection = vec(kotlin.math.cos(PI.toFloat() / 3f), kotlin.math.sin(PI.toFloat() / 3f), 0f)
            )
            testLine.byEulerSpiralTo(
                endPoint = vec(120f, 40f, 0f),
                endDirection = vec(kotlin.math.cos(PI.toFloat() / 4f), kotlin.math.sin(PI.toFloat() / 4f), 0f),
                samples = 90,
                iterations = 5
            )
            println("    ✓ Euler spirals passed")

            // ===== Curvature-driven evolution =====
            println("  Testing curvature flow...")
            testLine.byCurvatureFlow(
                step = 0.2f,
                iterations = 4,
                samples = 90,
                anchorEnds = true,
                dryWet = 0.7f,
                wetModulator = LinearParameter(0.5f, 1.2f)
            )
            println("    ✓ Curvature flow passed")

            // ===== Gaussian Process interpolation =====
            println("  Testing Gaussian Process interpolation...")
            testLine.byGaussianProcessInterpolating(
                trainingSamples = 24,
                samples = 80,
                lengthScale = 0.2f,
                variance = 1f,
                noise = 1e-3f,
                dryWet = 0.8f,
                drawSamples = false
            )
            println("    ✓ Gaussian Process interpolation passed")

            // ===== Smile interpolation/regression =====
            println("  Testing Smile interpolation/regression...")
            testLine.bySmileSplineInterpolating(
                trainingSamples = 20,
                samples = 100,
                dryWet = 0.8f
            )
            testLine.bySmileGaussianProcessInterpolating(
                trainingSamples = 20,
                samples = 100,
                lengthScale = 0.2f,
                noise = 1e-3f,
                dryWet = 0.8f
            )
            testLine.byAkimaInterpolating(
                trainingSamples = 20,
                samples = 100,
                dryWet = 0.8f
            )
            println("    ✓ Smile interpolation/regression passed")

            // ===== Filtered geometry =====
            println("  Testing filtered geometry...")
            testLine.byEmaFiltering(alpha = 0.25f, samples = 80, dryWet = 0.6f)
            testLine.byOneEuroFiltering(
                minCutoffHz = 1.5f,
                beta = 0.02f,
                dCutoffHz = 1f,
                fpsHint = 90f,
                samples = 80,
                dryWet = 0.7f
            )
            testLine.byAlphaBetaFiltering(
                alpha = 0.3f,
                beta = 0.1f,
                fpsHint = 120f,
                samples = 80,
                dryWet = 0.6f
            )
            testLine.byOneDollarFiltering(
                targetSamples = 48,
                targetSize = 150f,
                rotateToZero = true,
                dryWet = 0.9f
            )
            println("    ✓ Filtered geometry passed")

            // ===== Test all parameter types evaluate correctly =====
            println("  Testing parameter evaluation...")
            constantParam.evaluate(0.5f, vec(10f, 10f, 0f))
            linearParam.evaluate(0.5f, vec(10f, 10f, 0f))
            curveParam.evaluate(0.5f, vec(10f, 10f, 0f))
            distParam.evaluate(0.5f, vec(10f, 10f, 0f))
            curvParam.evaluate(0.5f, vec(10f, 10f, 0f))
            sineParam.evaluate(0.5f, vec(10f, 10f, 0f))
            lambdaParam.evaluate(0.5f, vec(10f, 10f, 0f))
            println("    ✓ Parameter evaluation passed")

            println("✅ Comprehensive smoke test PASSED - all functions executed without exceptions")
            return true

        } catch (e: Exception) {
            println("❌ Smoke test FAILED with exception: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
}
