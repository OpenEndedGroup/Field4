package afield.four.synth

import afield.four.synth.FnSoundDocs.Encoding
import afield.four.synth.FnSoundDocs.Route
import afield.stdlib.certifiedRange
import afield.stdlib.enclose
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The numbers `chain_fn_sound_roundtrip.md` prints, held with floors a little outside what they
 * measured on 2026-09-26 (in brackets), so a regression in the bridge, the renderer, the WAV
 * round-trip or either analysis moves one of them out.
 */
class FnSoundRoundTripTest {

    private companion object {
        /** All four trips, once per class: the WAVs go to a temp dir that is gone before any test reads a number. */
        val trips by lazy {
            val dir = Files.createTempDirectory("fn-sound-roundtrip").toFile()
            try {
                Encoding.values().flatMap { e -> Route.values().map { r -> FnSoundDocs.roundTrip(e, r, dir) } }
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    private fun trip(e: Encoding, r: Route) = trips.single { it.encoding == e && it.route == r }

    @Test
    fun fnSignalEvaluatesTheFunctionAtEverySample() {
        val fn = FnSoundDocs.contour()
        val samples = doughRenderMono(fn.toSignal(), 0.5f, FnSoundDocs.SR)
        val worst = samples.indices.maxOf { abs(samples[it] - fn(it.toDouble() / FnSoundDocs.SR).toFloat()) }
        // Float time accumulation in SynthContext is the only difference; well under a 16-bit step.
        assertTrue(worst < 1e-5f, "FnSignal strayed from fn(i / SR) by $worst")
    }

    @Test
    fun loudnessPerSampleComesBackOnTimeAndInsideTheCertifiedBand() {
        val t = trip(Encoding.LOUDNESS, Route.PER_SAMPLE)
        assertEquals(4492, t.measured.size)
        assertEquals(0, t.bestLagSamples)
        assertTrue(t.rmsError < 0.0015, "rms ${t.rmsError} [0.00112]")
        assertTrue(t.maxAbsError < 0.035, "max ${t.maxAbsError} [0.0281, at the blip]")
        assertTrue(abs(t.maxErrorAtSeconds - FnSoundDocs.BLIP_AT) < 0.01, "worst error at ${t.maxErrorAtSeconds}")
        assertTrue(t.insideCount >= 4480, "inside ${t.insideCount} [4490]")
        // One 16-bit step, in f's units, is 1/32768/LOUDNESS_GAIN = 3.8e-5.
        assertTrue(t.worstOutside < 3.8e-5, "worst outside ${t.worstOutside} [1.06e-6]")
        assertTrue(t.blip.survival in 0.85..0.92, "blip ${t.blip.survival} [0.887]")
    }

    @Test
    fun theParamRouteIsExactlyOneBlockLate() {
        val t = trip(Encoding.LOUDNESS, Route.PER_BLOCK)
        assertEquals(FnSoundDocs.BLOCK, t.bestLagSamples)
        assertTrue(t.rmsError > 4 * t.rmsErrorAtBestLag, "rms ${t.rmsError} vs ${t.rmsErrorAtBestLag} [0.00692 vs 0.00155]")
        assertTrue(t.insideCount < t.measured.size / 2, "inside ${t.insideCount} [1931 of 4492]")
    }

    @Test
    fun yinAttributesItsEstimateLateAndTheSupportCentreAccountsForIt() {
        val sample = trip(Encoding.PITCH, Route.PER_SAMPLE)
        val block = trip(Encoding.PITCH, Route.PER_BLOCK)
        assertEquals(1120, sample.measured.size)
        assertEquals(sample.frames.size, sample.measured.size)
        assertTrue(sample.bestLagSamples in 40..55, "lag ${sample.bestLagSamples} [47]")
        assertTrue(abs(sample.bestLagFromSupportSamples) <= 3, "support lag ${sample.bestLagFromSupportSamples} [1]")
        assertTrue(block.bestLagFromSupportSamples - sample.bestLagFromSupportSamples in 124..134,
            "block route adds ${block.bestLagFromSupportSamples - sample.bestLagFromSupportSamples} [130]")
        assertTrue(sample.rmsError < 0.005, "rms ${sample.rmsError} [0.00397]")
        assertEquals(sample.measured.size, sample.insideCount)
        assertEquals(block.measured.size, block.insideCount)
        assertTrue(sample.blip.survival in 0.64..0.72, "blip ${sample.blip.survival} [0.684]")
    }

    @Test
    fun theContourCertifies() {
        val fn = FnSoundDocs.contour()
        // One enclosure of the sum is loose (the four terms' maxima add to 1.33); branch-and-bound
        // closes it to what the function reaches.
        assertTrue(fn.enclose(0.0, FnSoundDocs.SECONDS).certified)
        val range = fn.certifiedRange(0.0, FnSoundDocs.SECONDS, tolerance = 1e-9)
        assertTrue(range.certified, "did not converge: ${range.refusal}")
        assertTrue(range.min!!.bound >= 0.19 && range.max!!.bound <= 0.95, "f's range ${range.min.bound}..${range.max!!.bound}")
    }
}
