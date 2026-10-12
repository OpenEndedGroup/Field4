@file:afield.CompletionCategory("Graphics")
package afield.paint

import kotlin.math.floor

/**
 * Smooth noise for varying a stroke along itself ([PaintCanvas.stroke]'s varied behaviours): a value
 * from -1 to 1 that wanders as a stroke goes. Keyed on how far along a movement in the world a
 * sample is ([TimedGesture.Sample.s], mm), it lies at the same places along that movement on both
 * canvases of a stereo pair, however long each sees it.
 */
object PaintNoise {
    /**
     * Smooth noise at [mm] along a stroke, -1..1, wandering over about [scaleMm] (a few octaves, each
     * half the last's scale and strength), the same for the same [seed].
     */
    fun along(mm: Double, scaleMm: Double = 60.0, seed: Long = 0, octaves: Int = 3): Double {
        var (sum, amp, norm, x) = listOf(0.0, 1.0, 0.0, mm / scaleMm.coerceAtLeast(1e-9))
        for (o in 0 until octaves.coerceAtLeast(1)) {
            sum += amp * value(x, seed + 7919L * o); norm += amp
            amp *= 0.5; x *= 2.0
        }
        return sum / norm
    }

    /** [along] at a gesture's sample: by its world length, or its time where it has none. */
    fun along(sample: TimedGesture.Sample, scaleMm: Double = 60.0, seed: Long = 0, octaves: Int = 3): Double =
        along(if (sample.s.isFinite()) sample.s else sample.t * 80.0, scaleMm, seed, octaves)

    private fun hash(i: Long, seed: Long): Double {
        var h = i * -0x61c8864680b583ebL + seed * 0x2545F4914F6CDD1DL
        h = (h xor (h ushr 31)) * -0x40a7b892e31b1a47L
        h = h xor (h ushr 29)
        return (h ushr 11).toDouble() / (1L shl 53).toDouble() * 2.0 - 1.0
    }

    private fun value(x: Double, seed: Long): Double {
        val i = floor(x).toLong(); val f = x - i
        val u = f * f * (3 - 2 * f)
        return hash(i, seed) * (1 - u) + hash(i + 1, seed) * u
    }
}
