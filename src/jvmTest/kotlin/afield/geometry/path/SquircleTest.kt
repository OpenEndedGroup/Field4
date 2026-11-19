package afield.geometry.path

import afield.math.linalg.Vec2
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Squircle constructions share one gauge; these pin that each passes through `(gauge, gauge)`,
 * the curvature continuity the docs claim for it (from [squircleCurvatureProfile], exact), and
 * Chromium's two-cubic fit's error against the exact superellipse.
 */
class SquircleTest {

    private val g4 = squircleGauge(4.0)

    /** Curvature just before and after each repeated arc length (a join between pieces). */
    private fun joinJumps(profile: List<Pair<Double, Double>>): List<Double> =
        (1 until profile.size).filter { abs(profile[it].first - profile[it - 1].first) < 1e-12 }
            .map { abs(profile[it].second - profile[it - 1].second) }

    /** The largest change in curvature between consecutive samples, away from joins. */
    private fun largestStep(profile: List<Pair<Double, Double>>): Double =
        (1 until profile.size).maxOf { abs(profile[it].second - profile[it - 1].second) }

    private fun along(line: FLine, n: Int = 4000): List<afield.math.linalg.Vec3> {
        val c = line.cursor()
        val total = c.lengthD()
        return (0..n).map { c.setD(total * it / n); val p = c.position(); p }
    }

    private fun profile(style: SquircleStyle, gauge: Double) =
        squircleCurvatureProfile(style, gauge).map { it.x.toDouble() to it.y.toDouble() }

    @Test
    fun gaugeAndExponentInvert() {
        assertEquals(sqrt(0.5), squircleGauge(2.0), 1e-12)
        assertEquals(5.0, squircleExponent(squircleGauge(5.0)), 1e-6)
    }

    @Test
    fun everyStylePassesThroughTheGaugePoint() {
        for (style in SquircleStyle.entries) for (g in listOf(0.75, g4, 0.88)) {
            if (style == SquircleStyle.Clothoid || style == SquircleStyle.Figma || g < 0.88) {
                val line = squircleFrom(Vec2(0f, 0f), 1.0, gauge = g, style = style)
                val miss = along(line).minOf { hypot(it.x - g, it.y - g) }
                assertTrue(miss < 2e-3, "$style at gauge $g misses its midpoint by $miss")
            }
        }
    }

    @Test
    fun superellipseIsCurvatureContinuousAndZeroOnTheAxes() {
        val prof = profile(SquircleStyle.Superellipse, g4)
        assertEquals(0.0, prof.first().second, 1e-6)
        assertEquals(0.0, prof.last().second, 1e-6)
        assertTrue(joinJumps(prof).isEmpty())
    }

    @Test
    fun clothoidCornerIsG2() {
        for (g in listOf(0.74, 0.78, g4)) {
            val prof = profile(SquircleStyle.Clothoid, g)
            assertTrue(joinJumps(prof).all { it < 1e-5 }, "clothoid at $g has a curvature jump")
            assertTrue(largestStep(prof) < 0.05 * prof.maxOf { it.second }, "clothoid at $g steps in curvature")
            assertEquals(0.0, prof.first().second, 1e-6)
        }
    }

    @Test
    fun figmaCornerJumpsInCurvatureWhereTheCubicMeetsTheArc() {
        val prof = profile(SquircleStyle.Figma, 0.78)
        val peak = prof.maxOf { it.second }
        val jumps = joinJumps(prof)
        assertTrue(jumps.any { it > 0.1 * peak }, "expected a curvature jump at the arc join, got $jumps (peak $peak)")
        assertEquals(0.0, prof.first().second, 1e-6)
    }

    @Test
    fun singleCubicIsOnlyG1AgainstTheStraightAxis() {
        // n = 4 has zero curvature on the axes; one cubic through the midpoint does not.
        val prof = profile(SquircleStyle.SuperellipseCubic, g4)
        assertTrue(prof.first().second > 0.05, "cubic end curvature ${prof.first().second}")
    }

    @Test
    fun chromiumFitIsCloseToTheExactSuperellipse() {
        for ((n, bound) in listOf(2.0 to 1.5e-3, 3.0 to 1.5e-3, 4.0 to 1.5e-3, 8.0 to 1.5e-3)) {
            val dev = squircleFrom(Vec2(0f, 0f), 1.0, gauge = squircleGauge(n), style = SquircleStyle.Chromium).superellipseDeviation(n)
            val oneCubic = squircleFrom(Vec2(0f, 0f), 1.0, gauge = squircleGauge(n), style = SquircleStyle.SuperellipseCubic).superellipseDeviation(n)
            println("n=$n chromium=$dev oneCubic=$oneCubic")
            assertTrue(dev < bound, "Chromium fit at n=$n deviates $dev")
            if (n > 2) assertTrue(dev < oneCubic, "Chromium fit at n=$n is no better than one cubic ($dev vs $oneCubic)")
        }
        // the exact sampling is exact to the flattening tolerance
        assertTrue(squircleFrom(Vec2(0f, 0f), 1.0, gauge = g4).superellipseDeviation(4.0) < 1e-4)
    }

    @Test
    fun roundedRectHasFlatSidesAndReachesItsBounds() {
        val r = squircleRectFrom(10.0, 20.0, 300.0, 100.0, radius = 30.0, style = SquircleStyle.Clothoid)
        val pts = along(r)
        assertEquals(10.0, pts.minOf { it.x.toDouble() }, 1e-2); assertEquals(310.0, pts.maxOf { it.x.toDouble() }, 1e-2)
        assertEquals(20.0, pts.minOf { it.y.toDouble() }, 1e-2); assertEquals(120.0, pts.maxOf { it.y.toDouble() }, 1e-2)
        // the bottom side is straight from x = 40 to x = 280
        assertTrue(pts.filter { it.x in 41.0..279.0 && it.y < 60 }.all { abs(it.y - 20) < 1e-3 })
    }
}
