package afield.graphics.examples

import afield.graphics.FLine
import afield.graphics.FLineGeometry
import afield.stdlib.*
import kotlin.math.PI

/**
 * Example demonstrations of FLineGeometry utilities.
 * These examples show practical usage of various computational geometry functions.
 */
object FLineGeometryExamples {

    /**
     * Example 1: Create a spiral and analyze its curvature
     */
    fun spiralCurvatureExample(): Pair<FLine, List<Pair<Vec3, Float>>> {
        // Create a spiral
        val spiral = FLine()
        spiral.moveTo(0f, 0f)
        
        for (i in 0..100) {
            val t = i / 100f * 4 * PI.toFloat()
            val r = i / 100f * 50f
            spiral.lineTo(r * kotlin.math.cos(t), r * kotlin.math.sin(t))
        }
        
        // Analyze curvature
        val curvatures = FLineGeometry.curvatureAnalysis(spiral, samples = 100)
        
        return Pair(spiral, curvatures)
    }

    /**
     * Example 2: Create a shape and generate offset versions
     */
    fun offsetShapeExample(): Triple<FLine, FLine, FLine> {
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
        val inner = FLineGeometry.offset(star, distance = -5f, samples = 50)
        val outer = FLineGeometry.offset(star, distance = 5f, samples = 50)
        
        return Triple(star, inner, outer)
    }

    /**
     * Example 3: Boolean operations on shapes
     */
    fun booleanOperationsExample(): Map<String, FLine> {
        // Create two overlapping circles
        val circle1 = createCircle(center = vec(0f, 0f), radius = 50f, segments = 64)
        val circle2 = createCircle(center = vec(40f, 0f), radius = 50f, segments = 64)
        
        return mapOf(
            "circle1" to circle1,
            "circle2" to circle2,
            "union" to FLineGeometry.union(circle1, circle2),
            "intersection" to FLineGeometry.intersection(circle1, circle2),
            "difference" to FLineGeometry.difference(circle1, circle2),
            "xor" to FLineGeometry.exclusiveOr(circle1, circle2)
        )
    }

    /**
     * Example 4: Smooth and simplify a noisy path
     */
    fun smoothAndSimplifyExample(): Triple<FLine, FLine, FLine> {
        // Create a noisy path
        val noisy = FLine()
        noisy.moveTo(0f, 0f)
        
        for (i in 1..100) {
            val x = i.toFloat()
            val y = kotlin.math.sin(i / 10f) * 20f + (Math.random().toFloat() - 0.5f) * 5f
            noisy.lineTo(x, y)
        }
        
        // Smooth it
        val smoothed = FLineGeometry.gaussianSmooth(noisy, sigma = 3f, samples = 100)
        
        // Simplify it
        val simplified = FLineGeometry.simplify(smoothed, epsilon = 2f)
        
        return Triple(noisy, smoothed, simplified)
    }

    /**
     * Example 5: Generate tangent and normal vectors
     */
    fun tangentNormalExample(): Triple<FLine, List<FLine>, List<FLine>> {
        // Create a wavy curve
        val curve = FLine()
        curve.moveTo(0f, 0f)
        
        for (i in 1..100) {
            val x = i.toFloat()
            val y = kotlin.math.sin(i / 10f) * 20f
            curve.lineTo(x, y)
        }
        
        // Generate tangent and normal lines
        val tangents = FLineGeometry.tangentLines(curve, samples = 15, length = 10f)
        val normals = FLineGeometry.normalLines(curve, samples = 15, length = 10f)
        
        return Triple(curve, tangents, normals)
    }

    /**
     * Example 6: Morphing between two shapes
     */
    fun morphingExample(steps: Int = 5): List<FLine> {
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
            morphs.add(FLineGeometry.blend(square, circle, t))
        }
        
        return morphs
    }

    /**
     * Example 7: Pattern generation (wave, dash, zigzag)
     */
    fun patternExample(): Map<String, Any> {
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
            "original" to baseLine,
            "wave" to FLineGeometry.wave(baseLine, amplitude = 5f, frequency = 3f, samples = 200),
            "zigzag" to FLineGeometry.zigzag(baseLine, amplitude = 5f, segments = 30),
            "dashed" to FLineGeometry.dash(baseLine, dashLength = 5f, gapLength = 3f)
        )
    }

    /**
     * Example 8: 3D transformations and projections
     */
    fun transformations3DExample(): Map<String, FLine> {
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
            "original" to helix,
            "rotated" to FLineGeometry.rotate3D(helix, 
                angle = PI.toFloat() / 4, 
                axis = vec(1f, 0f, 0f)),
            "perspective" to FLineGeometry.perspectiveProject(helix,
                cameraPos = vec(0f, 0f, 100f),
                planePoint = vec(0f, 0f, 0f),
                planeNormal = vec(0f, 0f, 1f)),
            "orthographic" to FLineGeometry.orthographicProject(helix,
                planePoint = vec(0f, 0f, 0f),
                planeNormal = vec(0f, 1f, 1f))
        )
    }

    /**
     * Example 9: Convex hull computation
     */
    fun convexHullExample(): Pair<FLine, FLine> {
        // Create a random point cloud
        val pointCloud = FLine()
        pointCloud.moveTo(0f, 0f)
        
        for (i in 1..50) {
            val angle = Math.random().toFloat() * 2 * PI.toFloat()
            val r = (Math.random().toFloat() * 50f + 10f)
            pointCloud.lineTo(r * kotlin.math.cos(angle), r * kotlin.math.sin(angle))
        }
        
        // Compute convex hull
        val hull = FLineGeometry.convexHull2D(pointCloud)
        
        return Pair(pointCloud, hull)
    }

    /**
     * Example 10: Curvature-adaptive sampling
     */
    fun adaptiveSamplingExample(): Pair<FLine, FLine> {
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
        val adaptive = FLineGeometry.curvatureAdaptiveSample(curve, 
            minSamples = 20, 
            maxSamples = 200)
        
        return Pair(curve, adaptive)
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
    fun comprehensiveDemo(): Map<String, Any> {
        val results = mutableMapOf<String, Any>()
        
        // 1. Create base shapes
        val square = FLine().apply {
            moveTo(0f, 0f)
            lineTo(50f, 0f)
            lineTo(50f, 50f)
            lineTo(0f, 50f)
            lineTo(0f, 0f)
        }
        results["square"] = square
        
        // 2. Transform it
        results["rotated"] = FLineGeometry.rotate2D(square, PI.toFloat() / 6)
        results["scaled"] = FLineGeometry.scale2D(square, 1.5f)
        results["sheared"] = FLineGeometry.shear2D(square, 0.3f, 0.1f)
        
        // 3. Create offset versions
        results["offset_inner"] = FLineGeometry.offset(square, -5f)
        results["offset_outer"] = FLineGeometry.offset(square, 5f)
        
        // 4. Create a wavy curve and analyze it
        val wave = FLine().apply {
            moveTo(0f, 0f)
            for (i in 1..100) {
                val x = i.toFloat()
                val y = kotlin.math.sin(i / 10f) * 20f
                lineTo(x, y)
            }
        }
        results["wave"] = wave
        results["wave_curvatures"] = FLineGeometry.curvatureAnalysis(wave, samples = 50)
        results["wave_simplified"] = FLineGeometry.simplify(wave, epsilon = 2f)
        
        // 5. Boolean operations
        val circle1 = createCircle(vec(25f, 25f), 30f, 64)
        val circle2 = createCircle(vec(40f, 25f), 30f, 64)
        results["union"] = FLineGeometry.union(circle1, circle2)
        results["intersection"] = FLineGeometry.intersection(circle1, circle2)
        
        // 6. Geometric queries
        results["centroid"] = FLineGeometry.centroid2D(square)
        results["area"] = FLineGeometry.signedArea2D(square)
        results["contains_point"] = FLineGeometry.contains(square, vec(25f, 25f))
        
        return results
    }
}
