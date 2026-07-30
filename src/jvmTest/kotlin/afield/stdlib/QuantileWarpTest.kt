package afield.stdlib

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for [quantilewarp.kt], which had none.
 *
 * The load-bearing test is [the fitted warp normalises a held-out sample], and it is out of sample
 * on purpose: the curve is fitted on one log-normal draw and every number checked comes from a
 * second, disjoint draw. [the eight-knot fit tracks the exact inverse-normal warp] compares the
 * PCHIP curve against `Φ⁻¹(F̂(v))` built here out of a binary search over the sorted fitting
 * sample — an oracle `quantilewarp.kt` does not contain, so the comparison tests the interpolation
 * rather than restating it.
 *
 * The reference values for [inverseStandardNormalCdf matches the published normal quantiles] are
 * the standard normal quantiles, not anything this file computes.
 */
class QuantileWarpTest {

    private fun logNormal(n: Int, seed: Long): DoubleArray {
        val rng = java.util.Random(seed)
        return DoubleArray(n) { exp(rng.nextGaussian()) }
    }

    // -------------------------
    // inverseStandardNormalCdf
    // -------------------------

    @Test
    fun `inverseStandardNormalCdf matches the published normal quantiles`() {
        // Acklam's rational approximation: good to about 1.15e-9 in absolute error.
        assertEquals(0.0, inverseStandardNormalCdf(0.5), 1e-9)
        assertEquals(1.2815515655, inverseStandardNormalCdf(0.90), 1e-7)
        assertEquals(1.6448536270, inverseStandardNormalCdf(0.95), 1e-7)
        assertEquals(1.9599639845, inverseStandardNormalCdf(0.975), 1e-7)
        assertEquals(2.3263478740, inverseStandardNormalCdf(0.99), 1e-7)
        assertEquals(3.0902323062, inverseStandardNormalCdf(0.999), 1e-7)
        // antisymmetry across the tail-branch boundary at p = 0.02425
        for (p in listOf(0.001, 0.01, 0.02, 0.024, 0.025, 0.05, 0.2, 0.4)) {
            assertEquals(
                -inverseStandardNormalCdf(p),
                inverseStandardNormalCdf(1.0 - p),
                1e-7,
                "should be antisymmetric at p=$p",
            )
        }
        assertFailsWith<IllegalArgumentException> { inverseStandardNormalCdf(0.0) }
        assertFailsWith<IllegalArgumentException> { inverseStandardNormalCdf(1.0) }
    }

    @Test
    fun `the target distributions are what they say`() {
        assertEquals(0.3, TargetDistributions.Uniform01.quantile(0.3), 1e-12)
        assertEquals(3.0, TargetDistributions.uniform(2.0, 6.0).quantile(0.25), 1e-12)
        assertEquals(6.0, TargetDistributions.uniform(2.0, 6.0).quantile(1.0), 1e-12)
        assertEquals(inverseStandardNormalCdf(0.7), TargetDistributions.UnitNormal.quantile(0.7), 0.0)
        assertFailsWith<IllegalArgumentException> { TargetDistributions.Uniform01.quantile(1.5) }
    }

    // -------------------------
    // config validation
    // -------------------------

    @Test
    fun `the config insists on eight strictly increasing probabilities inside zero and one`() {
        assertFailsWith<IllegalArgumentException> { QuantileWarpConfig(probabilities = doubleArrayOf(0.1, 0.2)) }
        assertFailsWith<IllegalArgumentException> {
            QuantileWarpConfig(probabilities = doubleArrayOf(0.1, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7))
        }
        assertFailsWith<IllegalArgumentException> {
            QuantileWarpConfig(probabilities = DoubleArray(8) { it * 0.1 })   // first is 0.0
        }
        assertFailsWith<IllegalArgumentException> {
            QuantileWarpConfig(probabilities = DoubleArray(8) { 0.3 + it * 0.1 })   // last is 1.0
        }
        assertFailsWith<IllegalArgumentException> { QuantileWarpConfig(historyFrames = 0) }
        assertFailsWith<IllegalArgumentException> { QuantileWarpConfig(smoothingAlpha = 1.5) }
        assertFailsWith<IllegalArgumentException> { QuantileWarpConfig(epsilonAbsolute = 0.0) }
        assertFailsWith<IllegalArgumentException> { AdaptiveQuantileWarp().update(DoubleArray(0)) }

        // the documented defaults, read off quantilewarp.kt rather than assumed
        val c = QuantileWarpConfig()
        assertEquals(8, c.probabilities.size)
        assertEquals(listOf(0.001, 0.01, 0.05, 0.20, 0.50, 0.80, 0.95, 0.99), c.probabilities.toList())
        assertEquals(8, c.historyFrames)
        assertEquals(0.20, c.smoothingAlpha, 0.0)
    }

    @Test
    fun `identity01 is the identity between its knots`() {
        val id = QuantileWarpState.identity01()
        assertEquals(0.5, id.evaluate(0.5), 1e-12)
        assertEquals(0.05, id.evaluate(0.05), 1e-12)
        assertEquals(0, id.historySampleCount)
        assertEquals(16, id.packedPushConstants.size, "8 input + 8 target knots pack into four vec4s")
    }

    // -------------------------
    // the fit
    // -------------------------

    @Test
    fun `the fitted warp normalises a held-out sample`() {
        val fit = logNormal(4000, 5)
        val warp = AdaptiveQuantileWarp()
        val state = warp.update(fit)
        assertEquals(4000, state.historySampleCount)

        // a SECOND, disjoint draw: nothing below is in-sample
        val held = logNormal(4000, 5150)
        val before = buildSummaryStats(held.toList())
        val after = buildSummaryStats(held.map { state.evaluate(it) })

        assertTrue(before.skewness > 3.0, "the input really is violently right-skewed: ${before.skewness}")
        assertTrue(abs(after.skewness) < 0.25, "the warped sample should be near-symmetric: ${after.skewness}")
        assertTrue(abs(after.mean) < 0.08, "mean should land near 0: ${after.mean}")
        assertTrue(abs(after.sampleStdDev - 1.0) < 0.08, "sd should land near 1: ${after.sampleStdDev}")
        assertTrue(abs(after.kurtosisExcess) < 0.6, "excess kurtosis should collapse: ${after.kurtosisExcess}")

        // the target knots are exactly the unit-normal quantiles of the eight probabilities
        val expected = QuantileWarpConfig().probabilities.map { inverseStandardNormalCdf(it) }
        assertEquals(expected, state.targetKnots.toList())
        // and the input knots are strictly increasing
        for (i in 1 until state.inputKnots.size) {
            assertTrue(state.inputKnots[i] > state.inputKnots[i - 1], "knots must be strictly increasing")
        }
    }

    @Test
    fun `the eight-knot fit tracks the exact inverse-normal warp`() {
        val fit = logNormal(4000, 5)
        val state = AdaptiveQuantileWarp().update(fit)
        val sorted = fit.sortedArray()

        fun empiricalCdf(v: Double): Double {
            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (sorted[mid] <= v) lo = mid + 1 else hi = mid
            }
            return lo / sorted.size.toDouble()
        }

        var worstCentral = 0.0
        for (i in 2..98) {
            val p = i / 100.0
            val v = sorted[(p * (sorted.size - 1)).toInt()]
            val exact = inverseStandardNormalCdf(empiricalCdf(v).coerceIn(1e-4, 1.0 - 1e-4))
            worstCentral = maxOf(worstCentral, abs(state.evaluate(v) - exact))
        }
        // The docs page publishes 0.053 here. Eight knots cannot do better than about this.
        assertTrue(worstCentral < 0.08, "8-knot PCHIP vs exact warp over p in 0.02..0.98: $worstCentral")
        assertTrue(worstCentral > 1e-6, "if this were ~0 the oracle would be the implementation")
    }

    @Test
    fun `the fitted curve is strictly increasing well outside the fitted range`() {
        val state = AdaptiveQuantileWarp().update(logNormal(4000, 5))
        var v = -5.0
        var previous = state.evaluate(v)
        while (v < 60.0) {
            v += 0.01
            val current = state.evaluate(v)
            assertTrue(current > previous, "monotonicity broke at $v: $previous -> $current")
            previous = current
        }
    }

    /**
     * The sharp edge the docs page warns about: outside the knots `evaluate` extrapolates linearly
     * from the endpoint tangent (`quantilewarp.kt:584`), and nothing clamps the result.
     */
    @Test
    fun `outside the fitted range evaluate leaves the target distribution entirely`() {
        val state = AdaptiveQuantileWarp().update(logNormal(4000, 5))
        val lowestKnot = state.inputKnots.first()
        assertTrue(lowestKnot > 0.0, "a log-normal's lowest knot is positive: $lowestKnot")
        val below = state.evaluate(0.0)
        val wayBelow = state.evaluate(-1.0)
        assertTrue(below < state.targetKnots.first(), "below the knots we fall past the lowest target: $below")
        assertTrue(wayBelow < -10.0, "and it is unbounded, not clamped: $wayBelow")
        // linear, so halving the distance halves the excursion
        val slope = (state.evaluate(-1.0) - state.evaluate(-2.0)) / 1.0
        assertEquals(slope, (state.evaluate(-3.0) - state.evaluate(-4.0)) / 1.0, 1e-9)
    }

    /**
     * `smoothingAlpha` defaults to 0.20 (`quantilewarp.kt:73`) and each `update` lerps the previous
     * knots that fraction of the way to the new ones (`quantilewarp.kt:193`). So a step change in
     * the signal is followed geometrically, and the remaining gap after k updates is (1-alpha)^k.
     */
    @Test
    fun `successive updates approach the new knots geometrically`() {
        val rng = java.util.Random(77)
        val warp = AdaptiveQuantileWarp(QuantileWarpConfig(historyFrames = 1))
        var state = warp.update(DoubleArray(4000) { rng.nextGaussian() })
        assertTrue(abs(state.inputKnots[4]) < 0.1, "median knot starts near 0: ${state.inputKnots[4]}")

        val step = 10.0
        for (k in 1..12) {
            state = warp.update(DoubleArray(4000) { step + rng.nextGaussian() })
            val remaining = step - state.inputKnots[4]
            val predicted = step * 0.8.pow(k)
            assertTrue(
                abs(remaining - predicted) < 0.15 + 0.06 * predicted,
                "after $k updates the gap should be ${step}*0.8^$k = $predicted, was $remaining",
            )
        }
        // ...and it has NOT arrived after twelve updates, which is the point
        assertTrue(state.inputKnots[4] < step, "smoothing must still be lagging: ${state.inputKnots[4]}")
    }

    @Test
    fun `update merges the last historyFrames batches before taking quantiles`() {
        val warp = AdaptiveQuantileWarp(QuantileWarpConfig(historyFrames = 3))
        assertNull(warp.currentStateOrNull())
        assertEquals(100, warp.update(DoubleArray(100) { it.toDouble() }).historySampleCount)
        assertEquals(200, warp.update(DoubleArray(100) { it.toDouble() }).historySampleCount)
        assertEquals(300, warp.update(DoubleArray(100) { it.toDouble() }).historySampleCount)
        assertEquals(300, warp.update(DoubleArray(100) { it.toDouble() }).historySampleCount, "capped at 3 frames")
        assertTrue(warp.currentStateOrNull() != null)

        warp.reset()
        assertNull(warp.currentStateOrNull())
        assertEquals(100, warp.update(DoubleArray(100) { it.toDouble() }).historySampleCount, "history cleared too")
    }

    @Test
    fun `a degenerate constant signal still yields an invertible curve`() {
        // makeStrictlyIncreasing (quantilewarp.kt:560) is what keeps this from dividing by zero.
        val state = AdaptiveQuantileWarp().update(DoubleArray(500) { 3.0 })
        for (i in 1 until state.inputKnots.size) {
            assertTrue(state.inputKnots[i] > state.inputKnots[i - 1], "knots forced apart: ${state.inputKnots.toList()}")
        }
        assertTrue(state.evaluate(3.0).isFinite())
        assertTrue(state.evaluate(3.1) > state.evaluate(3.0))
    }

    @Test
    fun `the packed push constants are the two knot arrays in order`() {
        val state = AdaptiveQuantileWarp().update(logNormal(1000, 19))
        val packed = state.packedPushConstants
        assertEquals(16, packed.size)
        for (i in 0 until 8) {
            assertEquals(state.inputKnots[i].toFloat(), packed[i], 1e-6f, "input knot $i")
            assertEquals(state.targetKnots[i].toFloat(), packed[8 + i], 1e-6f, "target knot $i")
        }
        assertEquals(packed[0], state.xLo().x, 0.0f)
        assertEquals(packed[4], state.xHi().x, 0.0f)
        assertEquals(packed[8], state.yLo().x, 0.0f)
        assertEquals(packed[12], state.yHi().x, 0.0f)

        val seen = LinkedHashMap<String, Float>()
        state.applyToPushConstants({ name, value -> seen[name] = value.x })
        assertEquals(listOf("warpXLo", "warpXHi", "warpYLo", "warpYHi"), seen.keys.toList())
        assertEquals(packed[0], seen["warpXLo"])
        assertEquals(packed[12], seen["warpYHi"])
    }
}
