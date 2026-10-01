package afield.stdlib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The node-parameterised formulas (`FnShapedFormulas`) are transcriptions, so they are pinned the
 * way the rest of `FnFormulas` is: raw bits against `Fn.eval`, for **every** variant a node can
 * carry, at a grid plus the inputs where a careless transcription differs (the clamp edges, the
 * branch points of the piecewise easings, signed zero, NaN). Each variant is also enclosed, and
 * every sample checked against the enclosure.
 */
class FnShapedFormulaAgreementTest {

    private val unit = Functions.Domain(0.0, 1.0)

    private fun xs(): List<Double> {
        val grid = (0..400).map { -0.25 + 1.5 * it / 400.0 }
        val special = listOf(-0.0, 0.0, 0.5, 1.0, 1.0 / 2.75, 2.0 / 2.75, 2.5 / 2.75, Math.nextDown(0.5), Math.nextUp(0.5),
            Math.nextDown(1.0), Double.NaN, 1e-300, -1e-300)
        return grid + special
    }

    private fun check(label: String, fn: Functions.Fn, a: Double = -0.25, b: Double = 1.25): Int {
        var compared = 0
        val enclosure = fn.enclose(a, b)
        assertTrue(enclosure.certified, "$label refused: ${enclosure.refusal}")
        for (x in xs()) {
            val v = fn.eval(x)
            val w = FnAutoDiff.evalByFormula(fn, x)
            // NaN's sign bit is not stable across JIT tiers, so two NaNs agree; every other value to the bit.
            if (!(v.isNaN() && w.isNaN())) assertEquals(v.toRawBits(), w.toRawBits(), "$label at x = $x: eval $v, formula $w")
            if (!x.isNaN() && x in a..b) assertTrue(v in enclosure, "$label: f($x) = $v outside $enclosure")
            compared++
        }
        return compared
    }

    @Test
    fun everyEasingIsBitIdenticalThroughEaseAndTween() = with(Functions) {
        var compared = 0
        // A source that leaves [0, 1] on both sides, so clampInput = false reaches the raw shape.
        val source = identity(Functions.Domain.ALL) * c(1.4, Functions.Domain.ALL) - c(0.2, Functions.Domain.ALL)
        for (e in Functions.Easing.entries) {
            compared += check("ease($e)", source.ease(e))
            compared += check("ease($e, clampInput = false)", source.ease(e, clampInput = false))
            compared += check("tween($e)", tween(c(0.3, Functions.Domain.ALL), sin(1.3, amplitude = 0.4), unit, e), 0.0, 1.0)
        }
        assertEquals(Functions.Easing.entries.size * 3 * xs().size, compared)
        assertTrue(Functions.Easing.entries.size >= 33)
    }

    @Test
    fun everyPulseEdgeAndProfileIsBitIdentical() = with(Functions) {
        var compared = 0
        for (edge in Functions.PulseEdge.entries) for (profile in Functions.PulseProfile.entries) for (bipolar in listOf(false, true)) {
            for ((jitter, rise, fall) in listOf(Triple(0.0, 0.0, 0.0), Triple(0.0, 0.06, 0.09), Triple(0.3, 0.2, 0.3))) {
                val fn = pulseTrain(
                    period = c(0.37, Functions.Domain.ALL), phase = c(0.05, Functions.Domain.ALL), duty = c(0.6, Functions.Domain.ALL),
                    amplitude = c(0.8, Functions.Domain.ALL), offset = c(0.1, Functions.Domain.ALL),
                    rise = c(rise, Functions.Domain.ALL), fall = c(fall, Functions.Domain.ALL), edge = edge, curve = c(2.5, Functions.Domain.ALL),
                    profile = profile, profileSigma = c(0.2, Functions.Domain.ALL), jitter = c(jitter, Functions.Domain.ALL), seed = 7L,
                    bipolar = bipolar, dutyMod = sin(0.7, amplitude = 0.1), ampMod = c(1.0, Functions.Domain.ALL),
                    phaseMod = c(0.0, Functions.Domain.ALL), domain = Functions.Domain.ALL
                )
                compared += check("pulseTrain($edge, $profile, bipolar=$bipolar, jitter=$jitter, rise=$rise)", fn)
            }
        }
        assertEquals(5 * 5 * 2 * 3 * xs().size, compared)
        // The degenerate parameters: zero period, a zero and a negative duty, a zero and a NaN sigma, a zero curve.
        compared = 0
        for (fn in listOf(
            pulseTrain(0.0), pulseTrain(0.5, duty = 0.0), pulseTrain(0.5, duty = -0.3),
            pulseTrain(c(0.5, Functions.Domain.ALL), profile = Functions.PulseProfile.GAUSSIAN, profileSigma = c(0.0, Functions.Domain.ALL), domain = Functions.Domain.ALL),
            pulseTrain(c(0.5, Functions.Domain.ALL), rise = c(0.1, Functions.Domain.ALL), edge = Functions.PulseEdge.EXP, curve = c(0.0, Functions.Domain.ALL), domain = Functions.Domain.ALL),
        )) compared += check("degenerate pulseTrain", fn)
        assertTrue(compared > 0)
    }

    /** Jitter is a per-cycle hash: enclosed exactly when a piece holds few cycles, and the search still certifies. */
    @Test
    fun aJitteredPulseTrainCertifiesItsRange() = with(Functions) {
        val fn = pulseTrain(c(0.5, Functions.Domain.ALL), duty = c(0.4, Functions.Domain.ALL), rise = c(0.05, Functions.Domain.ALL),
            fall = c(0.05, Functions.Domain.ALL), jitter = c(0.5, Functions.Domain.ALL), seed = 3L, domain = Functions.Domain.ALL)
        val range = fn.certifiedRange(0.0, 20.0, tolerance = 1e-6)
        assertTrue(range.certified, range.toString())
        val mx = range.max!!
        assertEquals(1.0, mx.value, 1e-9)
        val mn = range.min!!
        assertEquals(0.0, mn.value, 1e-12)
    }
}
