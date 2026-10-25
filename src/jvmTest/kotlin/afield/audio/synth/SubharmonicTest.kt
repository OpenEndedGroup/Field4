package afield.audio.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The properties `subharmonic_sound.md` states about the trackers in `Subharmonic.kt`, on
 * synthetic sines where the right answer is known exactly.
 */
class SubharmonicTest {

    private val sr = 48_000.0

    private fun sine(hz: Double, seconds: Double, level: Double = 0.8) =
        FloatArray((seconds * sr).toInt()) { (level * sin(2 * PI * hz * it / sr)).toFloat() }

    private fun trackedAfter(tracker: SubharmonicTracker, input: FloatArray): Double {
        tracker.process(input)
        return tracker.trackedHz
    }

    @Test
    fun hilbertPairStaysWithinADegreeOfQuadratureFrom30HzTo5kHz() {
        for (hz in listOf(30.0, 55.0, 110.0, 440.0, 1000.0, 5000.0)) {
            val h = HilbertPair()
            val x = sine(hz, 0.5)
            // after settling, atan2(i, q) should advance exactly with the true phase: measure how far
            // its offset from the true phase wanders over the second half of the signal
            var offset = Double.NaN
            var worst = 0.0
            for (n in x.indices) {
                h.process(x[n].toDouble())
                if (n < x.size / 2) continue
                val d = atan2(h.i, h.q) - 2 * PI * hz * n / sr
                if (offset.isNaN()) offset = d
                val drift = abs(Math.IEEEremainder(d - offset, 2 * PI))
                worst = maxOf(worst, drift)
            }
            assertTrue(Math.toDegrees(worst) < 2.0, "analytic phase wobbles by ${Math.toDegrees(worst)}° at $hz Hz")
        }
    }

    @Test
    fun zeroCrossingSmoothingIsInSecondsAtAnyPitch() {
        // jump from 80 Hz to 160 Hz; with a 30 ms tracking time it is there within ~150 ms
        val tr = SubharmonicZeroCross(trackingTimeSeconds = 0.03)
        tr.process(sine(80.0, 0.5))
        val after = trackedAfter(tr, sine(160.0, 0.15))
        assertTrue(abs(after - 160.0) < 2.0, "zero-crossing tracker at $after Hz 150 ms after a jump to 160 Hz")
    }

    @Test
    fun quadraturePllTracksTheSameAtAnyLevel() {
        for (level in listOf(0.8, 0.2, 0.05)) {
            val hz = trackedAfter(SubharmonicQuadPLL(), sine(73.42, 1.0, level))
            assertTrue(abs(hz - 73.42) < 0.3, "quad PLL at level $level tracked $hz Hz")
        }
    }

    @Test
    fun theOscillatorRunsAtTheTrackedFrequencyOverTheDivision() {
        val tr = SubharmonicQuadPLL(division = 3.0)
        val out = tr.process(sine(150.0, 2.0))
        // count upward zero crossings over the last second: the generated tone is 50 Hz
        var crossings = 0
        for (n in out.size / 2 + 1 until out.size) if (out[n - 1] <= 0f && out[n] > 0f) crossings++
        assertTrue(crossings in 49..51, "generated $crossings cycles in a second, expected 50")
    }

    @Test
    fun voicesStackSeveralDivisionsOnOneTracker() {
        val tr = SubharmonicQuadPLL().apply { voices = listOf(SubVoice(2.0, 0.7), SubVoice(4.0, 0.3)) }
        val out = tr.process(sine(200.0, 1.0))
        assertTrue(out.drop(out.size / 2).maxOf { abs(it) } > 0.3f, "stacked voices produced no output")
    }
}
