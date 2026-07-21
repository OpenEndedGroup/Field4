package afield.stdlib

import kotlin.math.cosh
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit-level checks of the [FnMetric] family (verified against hand-computed distances on small
 * arrays) plus integration checks that a custom metric actually drives [fitGradient] / [fitBest]
 * and that the default path reproduces the historic RMSE objective bit-for-bit.
 */
class FnMetricTest {

    // Four residuals of {±1, ±2} against a flat-zero target — every closed-form value below is
    // derived from these by hand.
    private val xs = doubleArrayOf(0.0, 1.0, 2.0, 3.0)
    private val target = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
    private val candidate = doubleArrayOf(1.0, -1.0, 2.0, -2.0)

    @Test
    fun rmseMatchesTheClosedFormAndEqualsLp2() {
        val expected = sqrt((1.0 + 1.0 + 4.0 + 4.0) / 4.0) // sqrt(2.5)
        assertEquals(expected, FnMetric.rmse.distance(xs, target, candidate), 1e-12)
        assertEquals(expected, FnMetric.lp(2.0).distance(xs, target, candidate), 1e-12)
    }

    @Test
    fun maeEqualsLp1AndMeanAbsolute() {
        val expected = (1.0 + 1.0 + 2.0 + 2.0) / 4.0 // 1.5
        assertEquals(expected, FnMetric.mae.distance(xs, target, candidate), 1e-12)
        assertEquals(expected, FnMetric.lp(1.0).distance(xs, target, candidate), 1e-12)
    }

    @Test
    fun maxErrorIsTheChebyshevExtreme() {
        assertEquals(2.0, FnMetric.maxError.distance(xs, target, candidate), 1e-12)
        // Larger p walks Lp from the mean (mae, 1.5) toward the max (2.0).
        assertTrue(FnMetric.lp(8.0).distance(xs, target, candidate) > FnMetric.mae.distance(xs, target, candidate))
        assertTrue(FnMetric.lp(8.0).distance(xs, target, candidate) < FnMetric.maxError.distance(xs, target, candidate))
    }

    @Test
    fun huberIsQuadraticThenLinear() {
        // |r|<=1 -> 0.5 r^2 (=0.5, 0.5);  |r|>1 -> delta*(|r|-0.5*delta) (=1.5, 1.5); mean = 1.0.
        assertEquals(1.0, FnMetric.huber(delta = 1.0).distance(xs, target, candidate), 1e-12)
        // A huge delta makes Huber degenerate to 0.5*mean(r^2) (pure quadratic everywhere).
        val bigDelta = FnMetric.huber(delta = 1e6).distance(xs, target, candidate)
        assertEquals(0.5 * (1.0 + 1.0 + 4.0 + 4.0) / 4.0, bigDelta, 1e-6)
    }

    @Test
    fun logCoshMatchesItsMean() {
        val expected = (ln(cosh(1.0)) * 2 + ln(cosh(2.0)) * 2) / 4.0
        assertEquals(expected, FnMetric.logCosh.distance(xs, target, candidate), 1e-9)
    }

    @Test
    fun correlationIsBlindToAffineRescaling() {
        val n = 64
        val gxs = DoubleArray(n) { it.toDouble() }
        val shape = DoubleArray(n) { sin(it * 0.3) }
        val rescaled = DoubleArray(n) { 2.5 * shape[it] + 3.0 } // same shape, different amp/offset
        // Perfectly correlated -> distance ~ 0, even though RMSE against it is large.
        assertEquals(0.0, FnMetric.correlation.distance(gxs, shape, rescaled), 1e-9)
        assertTrue(FnMetric.rmse.distance(gxs, shape, rescaled) > 1.0)
        // Anti-correlated -> distance ~ 2.
        val flipped = DoubleArray(n) { -shape[it] }
        assertEquals(2.0, FnMetric.correlation.distance(gxs, shape, flipped), 1e-9)
    }

    @Test
    fun weightingRestrictsToARegion() {
        // Keep only x in [-0.5, 1.5] (indices 0 and 1); weighted RMSE over {1, -1} = 1.0.
        val windowed = FnMetric.lp(2.0, weight = FnMetric.window(-0.5..1.5))
        assertEquals(1.0, windowed.distance(xs, target, candidate), 1e-12)
    }

    @Test
    fun degenerateComparisonsReturnPositiveInfinity() {
        val allNaN = doubleArrayOf(Double.NaN, Double.NaN, Double.NaN, Double.NaN)
        assertEquals(Double.POSITIVE_INFINITY, FnMetric.rmse.distance(xs, allNaN, candidate))
        assertEquals(Double.POSITIVE_INFINITY, FnMetric.maxError.distance(xs, allNaN, candidate))
        // Correlation needs a non-constant series: a flat candidate has no variance.
        val flat = doubleArrayOf(5.0, 5.0, 5.0, 5.0)
        assertEquals(Double.POSITIVE_INFINITY, FnMetric.correlation.distance(xs, candidate, flat))
    }

    @Test
    fun defaultFitGradientMatchesAnExplicitRmseMetricBitForBit() = with(Functions) {
        val tgt = sin(1.5, amplitude = 0.8, offset = 0.2)
        val seed = sin(1.0)
        val dom = Functions.Domain(0.0, 1.0)
        val implicit = fitGradient(tgt, seed, domain = dom, iterations = 100, lr = 0.05, rngSeed = 3L)
        val explicit = fitGradient(tgt, seed, domain = dom, iterations = 100, lr = 0.05, metric = FnMetric.rmse, rngSeed = 3L)
        assertEquals(implicit.losses, explicit.losses, "explicit RMSE must reproduce the default objective")
        assertTrue(implicit.parameters.contentEquals(explicit.parameters))
    }

    @Test
    fun aCustomMetricActuallyDrivesTheFitAndIsDeterministic() = with(Functions) {
        val tgt = sin(1.5, amplitude = 0.8, offset = 0.2)
        val seed = sin(1.0)
        val dom = Functions.Domain(0.0, 1.0)
        val a = fitGradient(tgt, seed, domain = dom, iterations = 150, lr = 0.05, metric = FnMetric.huber(0.5), rngSeed = 11L)
        val b = fitGradient(tgt, seed, domain = dom, iterations = 150, lr = 0.05, metric = FnMetric.huber(0.5), rngSeed = 11L)
        assertTrue(a.improved, "the fit should reduce the Huber distance")
        assertTrue(a.finalError < a.initialError)
        assertEquals(a.losses, b.losses, "same seed/metric must give an identical trajectory")
    }

    @Test
    fun fitBestWithAMeanAbsoluteMetricStillEscapesToTheGlobalBasin() = with(Functions) {
        val tgt = cos(2) * sin(1.5, amplitude = 0.8, offset = 0.2)
        val seed = cos(3.4) * sin(1.0, amplitude = 2.0)
        val run = fitBest(tgt, seed, restarts = 16, iterations = 400, lr = 0.05, metric = FnMetric.mae, rngSeed = 0L)
        assertTrue(run.improved)
        assertTrue(run.finalError < 0.05, "fitBest under MAE should reach the global basin; got ${run.finalError}")
    }
}
