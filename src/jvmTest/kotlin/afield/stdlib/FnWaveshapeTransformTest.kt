package afield.stdlib

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CPU-sampling verification of the waveshaping / distortion `FnTransform` family
 * (`fold` / `rectify` / `quantize`). These assert the EXACT shaping behaviour on a
 * value sweep, so the transforms are pinned to their math, not merely to "it compiles".
 */
class FnWaveshapeTransformTest {

    /** A signal whose value sweeps linearly from [lo] to [hi] across the unit domain. */
    private fun Functions.sweep(lo: Double, hi: Double): Functions.Fn {
        val d = Functions.Domain.of(0.0, 1.0)
        return (identity(d) * (hi - lo)) + lo
    }

    private fun Functions.sampleValues(fn: Functions.Fn, n: Int): DoubleArray {
        val d = fn.domain
        return DoubleArray(n) { i -> fn.evalSafe(d.min + d.length * i / (n - 1).toDouble()) }
    }

    @Test
    fun fullWaveRectifyIsAbsoluteValue() = with(Functions) {
        val input = sweep(-2.0, 2.0)
        val rectified = input / rectify(Functions.RectifyMode.FULL)
        val n = 201
        val xs = sampleValues(input, n)
        val ys = sampleValues(rectified, n)
        for (i in 0 until n) {
            assertEquals(abs(xs[i]), ys[i], 1e-9, "full-wave rectify must be |x| at x=${xs[i]}")
            assertTrue(ys[i] >= -1e-12, "full-wave rectify is never negative")
        }
    }

    @Test
    fun halfWaveRectifyClampsNegativesToZero() = with(Functions) {
        val input = sweep(-2.0, 2.0)
        val rectified = input / rectify(Functions.RectifyMode.HALF)
        val n = 201
        val xs = sampleValues(input, n)
        val ys = sampleValues(rectified, n)
        for (i in 0 until n) {
            val expected = if (xs[i] < 0.0) 0.0 else xs[i]
            assertEquals(expected, ys[i], 1e-9, "half-wave rectify must clamp negatives at x=${xs[i]}")
        }
    }

    @Test
    fun foldReflectsAboveThresholdStaysInBandAndIsContinuous() = with(Functions) {
        val th = 0.7
        // A sweep that overshoots the threshold on both sides, several folds deep.
        val input = sweep(-5.0, 5.0)
        val folded = input through fold(th)
        val n = 4001
        val xs = sampleValues(input, n)
        val ys = sampleValues(folded, n)

        // 1. Bounded: the folder keeps the signal within +/- threshold everywhere, even
        //    where the input is far outside it. (A value above threshold comes back down.)
        for (i in 0 until n) {
            assertTrue(
                ys[i] in (-th - 1e-9)..(th + 1e-9),
                "fold must stay within [-th, th]; got ${ys[i]} at x=${xs[i]}"
            )
        }

        // 2. Pass-through below threshold: while |x| <= th the signal is unchanged.
        for (i in 0 until n) {
            if (abs(xs[i]) <= th - 1e-3) {
                assertEquals(xs[i], ys[i], 1e-6, "fold is identity within the threshold band at x=${xs[i]}")
            }
        }

        // 3. Actually folds: a value well above threshold is pulled back down, not passed.
        val hi = folded.evalSafe(input.domain.max) // input == 5.0 here, far above th
        assertTrue(abs(hi) <= th + 1e-9 && abs(hi - 5.0) > th, "input 5.0 must be folded back into the band, got $hi")

        // 4. Continuous: no jumps. Input step ~10/4000, triangle slope 1, so output steps
        //    stay tiny. (The naive modulo folder this replaced jumped by ~2*th here.)
        var maxJump = 0.0
        for (i in 1 until n) maxJump = maxOf(maxJump, abs(ys[i] - ys[i - 1]))
        assertTrue(maxJump < 0.05, "fold must be continuous; largest sample-to-sample jump was $maxJump")
    }

    @Test
    fun quantizeSnapsToExactlyNLevels() = with(Functions) {
        val input = sweep(-1.0, 1.0)
        for (levels in listOf(2, 3, 4, 6, 8)) {
            val q = input / quantize(levels)
            val ys = sampleValues(q, 1000)
            val distinct = ys.map { (it * 1e6).toLong() }.toSet()
            assertEquals(
                levels, distinct.size,
                "quantize($levels) must produce exactly $levels distinct output levels"
            )
            for (y in ys) assertTrue(y in -1.0000001..1.0000001, "quantized level in range: $y")
        }
    }

    @Test
    fun transformsAreDeterministicAndReproducible() = with(Functions) {
        val a = sweep(-3.0, 3.0) / fold(0.7) / rectify(Functions.RectifyMode.FULL) / quantize(5)
        val b = sweep(-3.0, 3.0) / fold(0.7) / rectify(Functions.RectifyMode.FULL) / quantize(5)
        val ya = sampleValues(a, 500)
        val yb = sampleValues(b, 500)
        for (i in ya.indices) assertEquals(ya[i], yb[i], 0.0, "identical chains must sample identically")
    }
}
