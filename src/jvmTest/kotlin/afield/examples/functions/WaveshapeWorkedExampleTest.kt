package afield.examples.functions

import afield.stdlib.graphSignature
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the CPU-only [WaveshapeWorkedExample]: each waveshaper actually imposes its
 * documented transfer curve on a `-2..2` sweep, and the whole computation is
 * bit-for-bit deterministic. No GPU, no window, and — by design — no file output.
 */
class WaveshapeWorkedExampleTest {

    private fun WaveshapeWorkedExample.Result.cell(label: String): WaveshapeWorkedExample.Cell =
        cells.firstOrNull { it.label == label } ?: error("no cell '$label'")

    @Test
    fun shapersImposeTheirTransferCurves() {
        val r = WaveshapeWorkedExample.runExample()
        val eps = 1e-9

        // The nine documented shapers (plus input + both rectify modes) are all present.
        assertEquals(11, r.cells.size, "expected input + shaper cells")

        val sweep = r.cell("input sweep -2..2")
        assertTrue(sweep.ys.first() <= -2.0 + 1e-6, "sweep should start at -2: ${sweep.ys.first()}")
        assertTrue(sweep.ys.last() >= 2.0 - 1e-6, "sweep should end at +2: ${sweep.ys.last()}")

        fun bounded(label: String, lo: Double, hi: Double) {
            val ys = r.cell(label).ys.filter { it.isFinite() }
            assertTrue(ys.isNotEmpty(), "$label produced no finite samples")
            assertTrue(
                ys.all { it in (lo - eps)..(hi + eps) },
                "$label escaped [$lo, $hi]: min=${ys.min()} max=${ys.max()}"
            )
        }
        bounded("fold(0.7)", -0.7, 0.7)
        bounded("clip(0.8)", -0.8, 0.8)
        bounded("clamp(-0.8, 0.8)", -0.8, 0.8)
        bounded("wrapY(-0.8, 0.8)", -0.8, 0.8)
        bounded("chebyshev(3)", -1.0, 1.0)

        // Rectifiers are non-negative; FULL is exactly |input|.
        val rectFull = r.cell("rectify(FULL)")
        assertTrue(rectFull.ys.all { it >= -eps }, "rectify(FULL) went negative")
        for (i in sweep.xs.indices) {
            assertEquals(abs(sweep.ys[i]), rectFull.ys[i], 1e-6, "rectify(FULL) != |input| at i=$i")
        }
        assertTrue(r.cell("rectify(HALF)").ys.all { it >= -eps }, "rectify(HALF) went negative")

        // Quantize/crush stay within the input's own range (staircases of the input).
        val inLo = sweep.ys.min(); val inHi = sweep.ys.max()
        bounded("quantize(6)", inLo, inHi)
        bounded("crush(3)", inLo, inHi)

        // fold/quantize/crush/chebyshev actually change the input (not accidental no-ops).
        for (label in listOf("fold(0.7)", "quantize(6)", "crush(3)", "chebyshev(3)", "clip(0.8)")) {
            val ys = r.cell(label).ys
            assertTrue(
                ys.indices.any { abs(ys[it] - sweep.ys[it]) > 1e-6 },
                "$label left the sweep unchanged — it isn't shaping"
            )
        }

        // Every cell has finite samples (nothing degenerated to all-NaN).
        for (c in r.cells) {
            assertTrue(c.ys.any { it.isFinite() }, "cell '${c.label}' has no finite samples")
        }
    }

    @Test
    fun isBitForBitReproducible() {
        val a = WaveshapeWorkedExample.runExample()
        val b = WaveshapeWorkedExample.runExample()
        assertEquals(a.cells.size, b.cells.size)
        for ((ca, cb) in a.cells.zip(b.cells)) {
            assertEquals(ca.label, cb.label, "cell order changed")
            assertTrue(ca.ys.contentEquals(cb.ys), "cell '${ca.label}' samples not reproducible")
            assertTrue(ca.xs.contentEquals(cb.xs), "cell '${ca.label}' domain sampling not reproducible")
            assertEquals(
                ca.fn.graphSignature(), cb.fn.graphSignature(),
                "cell '${ca.label}' graph signature not reproducible"
            )
        }
    }
}
