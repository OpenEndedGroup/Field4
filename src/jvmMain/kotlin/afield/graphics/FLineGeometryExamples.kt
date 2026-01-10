package afield.graphics.examples

import afield.graphics.*
import afield.stdlib.Vec2
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
        val square = FLine().apply {
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

    /**
     * Comprehensive demo that exercises most functionality
     */
    fun comprehensiveDemo(): Map<String, List<FLine>> {
        val results = mutableMapOf<String, List<FLine>>()

        // 1. Create base shapes
        val square = FLine().apply {
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
        val wave = FLine().apply {
            moveTo(0f, 0f)
            for (i in 1..100) {
                val x = i.toFloat()
                val y = kotlin.math.sin(i / 10f) * 20f
                lineTo(x, y)
            }
        }
        results["wave"] = listOf(wave)
        val waveCurvatures = wave.curvatureAnalysis( samples = 50)
        val waveCurvaturePlot = FLine().apply {
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
        val centroidMark = FLine().apply {
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
            println("    ✓ All examples passed")

            // ===== Create test lines for function testing =====
            val testLine = FLine().apply {
                moveTo(0f, 0f)
                for (i in 1..20) {
                    lineTo(i.toFloat() * 5f, kotlin.math.sin(i / 3f) * 10f)
                }
            }

            val testCircle = createCircle(vec(50f, 50f), 30f, 32)
            val testSquare = FLine().apply {
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
            val quadLine = FLine().apply { moveTo(0f, 0f) }
            quadLine.byAppendingQuadratic(vec(50f, 100f, 0f), vec(100f, 0f, 0f))
            val tangentLine = FLine().apply { moveTo(0f, 0f) }
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
            val curve1 = FLine().apply {
                moveTo(0f, 0f)
                for (i in 1..50) lineTo(i.toFloat(), kotlin.math.sin(i / 5f) * 10f)
            }
            val curve2 = FLine().apply {
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
            val controlCurve = FLine().apply {
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
            eulerSpiralBetween(
                start = vec(0f, 0f, 0f),
                startHeading = 0f,
                end = vec(80f, 40f, 0f),
                endHeading = (PI.toFloat() / 3f)
            )
            testLine.byEulerSpiralTo(
                endPoint = vec(120f, 40f, 0f),
                endHeading = (PI.toFloat() / 4f),
                samples = 90,
                iterations = 5,
                dryWet = 0.9f
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
