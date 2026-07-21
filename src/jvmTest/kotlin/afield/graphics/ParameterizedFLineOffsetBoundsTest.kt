package afield.graphics

import afield.stdlib.Vec3
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression coverage for [byOffsettingParameterized] and [byNoisingParameterized].
 *
 * Both used to sample the source curve's raw (un-normalised) `normal2()` and offset along it,
 * so a long/steep segment or a degenerate normal at a join/endpoint flung a few samples to
 * huge or non-finite coordinates. `byNoisingParameterized` additionally used an unbounded
 * Box-Muller Gaussian (`r = sqrt(-2 ln u1)`), which explodes to Inf when `u1 ~ 0`. Either
 * produced the degenerate radial "star" once the result was fit-to-size.
 *
 * These tests assert every emitted node is finite and stays within a generous bounding box
 * derived from the source curve's extent plus the requested offset/amplitude.
 */
class ParameterizedFLineOffsetBoundsTest {

    /** A multi-segment base curve with steep, varying tangents (to stress un-normalised normals). */
    private fun baseCurve(): FLine {
        val base = FLine()
        base.moveTo(0f, 50f)
        for (i in 1..100) {
            val x = i.toFloat()
            // Large amplitude / high frequency => tangent magnitude varies a lot segment to segment.
            val y = 50f + kotlin.math.sin(i / 3f) * 40f
            base.lineTo(x, y)
        }
        return base
    }

    private fun allPoints(line: FLine): List<Vec3> = line.nodes.flatMap { it.storage }

    private fun extentOf(points: List<Vec3>): Float {
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        for (p in points) {
            minX = minOf(minX, p.x); minY = minOf(minY, p.y)
            maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y)
        }
        return max(maxX - minX, maxY - minY)
    }

    private fun assertAllFiniteAndBounded(result: FLine, base: FLine, maxOffset: Float) {
        val basePts = allPoints(base)
        val extent = extentOf(basePts)
        // Centre of the source curve. Any base point is at most `extent` from the centre, so a
        // correctly-displaced sample is at most `extent + maxOffset` from it. The un-normalised
        // normal used to blow this out by roughly the local tangent magnitude (~13x here), and a
        // near-zero Gaussian draw sent points to Inf, so this bound is a genuine regression guard.
        val cx = basePts.map { it.x }.average().toFloat()
        val cy = basePts.map { it.y }.average().toFloat()
        val bound = extent + maxOffset

        val pts = allPoints(result)
        assertTrue(pts.isNotEmpty(), "result had no nodes")
        for (p in pts) {
            assertTrue(
                p.x.isFinite() && p.y.isFinite() && p.z.isFinite(),
                "non-finite output node: $p"
            )
            val dx = kotlin.math.abs(p.x - cx)
            val dy = kotlin.math.abs(p.y - cy)
            assertTrue(
                dx <= bound && dy <= bound,
                "output node out of range: $p (|dx|=$dx |dy|=$dy bound=$bound, extent=$extent)"
            )
        }
    }

    @Test
    fun offsettingStaysFiniteAndBounded() {
        val base = baseCurve()
        val maxOffset = 15f
        val result = base.byOffsettingParameterized(LinearParameter(start = 2f, end = maxOffset), samples = 100)
        assertAllFiniteAndBounded(result, base, maxOffset)
    }

    @Test
    fun noisingStaysFiniteAndBounded() {
        val base = baseCurve()
        val maxAmplitude = 10f
        // Sweep seeds so we exercise draws where u1 lands near 0 (the old Inf case).
        for (seed in 0L until 40L) {
            val result = base.byNoisingParameterized(
                LinearParameter(start = maxAmplitude, end = 1f),
                seed = seed,
                samples = 100
            )
            // With r capped at 4, the max normal displacement is 4 * amplitude.
            assertAllFiniteAndBounded(result, base, maxAmplitude * 4f)
        }
    }
}
