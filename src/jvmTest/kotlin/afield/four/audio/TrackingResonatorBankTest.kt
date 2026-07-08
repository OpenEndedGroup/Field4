package afield.four.audio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackingResonatorBankTest {
    private val sampleRate = 48_000f

    @Test
    fun phasorOscillatorAndScalarResonatorCoverReadmeBuildingBlocks() {
        println("Checking README-level Phasor, Oscillator, and scalar Resonator building blocks.")
        val phasor = Phasor(440f, sampleRate)
        repeat(10_000) {
            phasor.update()
        }
        val magnitude = sqrt(phasor.real * phasor.real + phasor.imag * phasor.imag)
        assertTrue(abs(magnitude - 1.0f) < 1e-3f, "phasor magnitude was $magnitude")

        val oscillator = Oscillator(440f, amplitude = 0.5f, sampleRate = sampleRate)
        val oscillatorSamples = FloatArray(256)
        oscillator.renderCosine(oscillatorSamples)
        assertTrue(oscillatorSamples.all { it in -0.5001f..0.5001f })

        val frequency = 440f
        val alpha = 0.012f
        val beta = 0.04f
        val input = sineFrame(4096, frequency, amplitude = 0.75f)
        val scalar = Resonator(frequency, alpha, beta, sampleRate)
        val bank = ResonatorBank(1, floatArrayOf(frequency), floatArrayOf(alpha), floatArrayOf(beta), sampleRate)

        scalar.update(input)
        bank.update(input)

        val bankComplex = FloatArray(2)
        bank.getComplex(bankComplex)
        assertEquals(bankComplex[0], scalar.real, 1e-5f)
        assertEquals(bankComplex[1], scalar.imag, 1e-5f)
        println("  OK: phasor/oscillator behave, and scalar resonator matches one bank lane.")
    }

    @Test
    fun scalarTrackingResonatorLocksToDetunedComponent() {
        println("Checking scalar TrackingResonator follows a detuned input component.")
        val tracking = TrackingResonator(
            naturalFrequency = 435f,
            alpha = 0.01f,
            beta = 0.04f,
            sampleRate = sampleRate,
            gamma = 0.0002f
        )
        val input = sineFrame((sampleRate * 2.5f).toInt(), 440f, amplitude = 0.8f)

        tracking.update(input)

        println("  Scalar tracking frequency: ${tracking.resonantFrequency}")
        assertTrue(abs(tracking.resonantFrequency - 440f) < 0.25f)
        assertTrue(abs(tracking.resonantFrequency - 440f) < abs(435f - 440f))
        println("  OK: scalar tracking resonator locks close to 440 Hz.")
    }

    @Test
    fun arrayBanksSupportSequentialAndConcurrentUpdates() {
        println("Checking README-level array bank sequential/concurrent update parity.")
        val frequencies = floatArrayOf(220f, 440f, 880f, 1760f)
        val alphas = FloatArray(frequencies.size) { 0.012f }
        val betas = FloatArray(frequencies.size) { 0.04f }
        val input = FloatArray(4096) { i ->
            val t = i / sampleRate
            (0.55f * sin((2.0 * PI * 440.0 * t)).toFloat()) +
                (0.25f * sin((2.0 * PI * 880.0 * t)).toFloat())
        }

        val sequential = ResonatorBankArray(frequencies, alphas, betas, sampleRate)
        val concurrent = ResonatorBankArray(frequencies, alphas, betas, sampleRate)
        sequential.update(input)
        concurrent.updateConcurrent(input, taskCount = 2)

        val sequentialComplex = FloatArray(frequencies.size * 2)
        val concurrentComplex = FloatArray(frequencies.size * 2)
        sequential.getComplex(sequentialComplex)
        concurrent.getComplex(concurrentComplex)
        assertFloatArrayEquals(sequentialComplex, concurrentComplex, tolerance = 1e-5f)
        println("  OK: array bank concurrent update matches sequential update.")
    }

    @Test
    fun frameAndStepUpdatesMatch() {
        println("Checking that frame update and per-sample update are numerically equivalent.")
        val frequencies = floatArrayOf(110f, 220f, 440f, 880f, 1760f)
        val alphas = FloatArray(frequencies.size) { 0.015f }
        val betas = FloatArray(frequencies.size) { 0.05f }
        val gammas = FloatArray(frequencies.size) { 0.01f }
        val frame = sineFrame(2048, 440f, amplitude = 0.8f)
        println("  Input: ${frame.size} samples of a 440 Hz sine into ${frequencies.size} resonators.")

        val frameBank = TrackingResonatorBank(frequencies.size, frequencies, alphas, betas, sampleRate, gammas)
        val stepBank = TrackingResonatorBank(frequencies.size, frequencies, alphas, betas, sampleRate, gammas)

        frameBank.update(frame)
        for (sample in frame) {
            stepBank.update(sample)
        }
        stepBank.stabilize()

        val frameAmplitudes = FloatArray(frequencies.size)
        val stepAmplitudes = FloatArray(frequencies.size)
        val framePhases = FloatArray(frequencies.size)
        val stepPhases = FloatArray(frequencies.size)
        val frameFrequencies = FloatArray(frequencies.size)
        val stepFrequencies = FloatArray(frequencies.size)

        frameBank.getAmplitudes(frameAmplitudes)
        stepBank.getAmplitudes(stepAmplitudes)
        frameBank.getPhases(framePhases)
        stepBank.getPhases(stepPhases)
        frameBank.getResonantFrequencies(frameFrequencies)
        stepBank.getResonantFrequencies(stepFrequencies)

        for (i in frequencies.indices) {
            assertEquals(frameAmplitudes[i], stepAmplitudes[i], 1e-5f)
            assertEquals(framePhases[i], stepPhases[i], 1e-4f)
            assertEquals(frameFrequencies[i], stepFrequencies[i], 1e-4f)
        }
        println("  OK: amplitudes, phases, and tracked frequencies match for all resonators.")
    }

    @Test
    fun tracksDetunedComponentsInComplexSound() {
        println("Checking that detuned resonators move toward real components inside a two-tone signal.")
        val naturalFrequencies = floatArrayOf(220f, 435f, 660f, 874f, 1100f)
        val alphas = FloatArray(naturalFrequencies.size) { 0.01f }
        val betas = FloatArray(naturalFrequencies.size) { 0.04f }
        val gammas = FloatArray(naturalFrequencies.size) { 0.0002f }
        val bank = TrackingResonatorBank(
            naturalFrequencies.size,
            naturalFrequencies,
            alphas,
            betas,
            sampleRate,
            gammas
        )

        val input = FloatArray((sampleRate * 2.5f).toInt()) { i ->
            val t = i / sampleRate
            (0.8f * sin((2.0 * PI * 440.0 * t)).toFloat()) +
                (0.35f * sin((2.0 * PI * 880.0 * t)).toFloat()) +
                (((i * 17) % 101) - 50) * 2e-5f
        }

        bank.update(input)

        val tracked = FloatArray(naturalFrequencies.size)
        bank.getResonantFrequencies(tracked)
        println("  Natural frequencies: ${naturalFrequencies.joinToString()}")
        println("  Tracked frequencies: ${tracked.joinToString()}")

        assertTrue(abs(tracked[1] - 440f) < 0.25f, "435 Hz resonator tracked to ${tracked[1]}")
        assertTrue(abs(tracked[3] - 880f) < 0.5f, "874 Hz resonator tracked to ${tracked[3]}")
        assertTrue(abs(tracked[1] - 440f) < abs(naturalFrequencies[1] - 440f))
        assertTrue(abs(tracked[3] - 880f) < abs(naturalFrequencies[3] - 880f))
        println("  OK: the 435 Hz and 874 Hz resonators lock close to the 440 Hz and 880 Hz components.")
    }

    @Test
    fun trackingResonateReturnsSlicesAndTrackedFrequencies() {
        println("Checking offline trackingResonate storage dimensions and last-frame tracked frequencies.")
        val naturalFrequencies = floatArrayOf(430f, 875f)
        val alphas = FloatArray(naturalFrequencies.size) { 0.012f }
        val betas = FloatArray(naturalFrequencies.size) { 0.04f }
        val gammas = FloatArray(naturalFrequencies.size) { 0.001f }
        val hopLength = 512
        val input = FloatArray(hopLength * 12) { i ->
            val t = i / sampleRate
            (0.6f * sin((2.0 * PI * 440.0 * t)).toFloat()) +
                (0.25f * sin((2.0 * PI * 880.0 * t)).toFloat())
        }

        val result = trackingResonate(
            input = input,
            samplingRate = sampleRate,
            naturalFrequencies = naturalFrequencies,
            alphas = alphas,
            betas = betas,
            gammas = gammas,
            hopLength = hopLength
        )

        assertEquals(12, result.numSlices)
        assertEquals(2, result.numResonators)
        assertEquals(result.numSlices * result.numResonators * 2, result.complex.size)
        assertEquals(result.numSlices * result.numResonators, result.resonantFrequencies.size)
        assertEquals(result.numSlices * result.numResonators, result.powers.size)

        val last = (result.numSlices - 1) * result.numResonators
        println(
            "  Last frame tracked frequencies: " +
                "${result.resonantFrequencies[last]}, ${result.resonantFrequencies[last + 1]}"
        )
        assertTrue(
            abs(result.resonantFrequencies[last] - 440f) < 1.5f,
            "430 Hz resonator tracked to ${result.resonantFrequencies[last]}"
        )
        assertTrue(
            abs(result.resonantFrequencies[last + 1] - 880f) < 2.0f,
            "875 Hz resonator tracked to ${result.resonantFrequencies[last + 1]}"
        )
        println("  OK: result arrays are correctly sized and tracking survives hop-sliced analysis.")
    }

    @Test
    fun analysisRoundTripPreservesStorageAndUtilities() {
        println("Checking binary analysis round-trip and query utilities.")
        val analysis = makeSmallAnalysis()
        val bytes = ByteArrayOutputStream()

        analysis.writeTo(bytes)
        val restored = TrackingResonatorAnalysis.readFrom(ByteArrayInputStream(bytes.toByteArray()))

        assertEquals(analysis.sampleRate, restored.sampleRate)
        assertEquals(analysis.hopLength, restored.hopLength)
        assertEquals(analysis.sourceSampleCount, restored.sourceSampleCount)
        assertEquals(analysis.numSlices, restored.numSlices)
        assertEquals(analysis.numResonators, restored.numResonators)
        assertFloatArrayEquals(analysis.naturalFrequencies, restored.naturalFrequencies)
        assertFloatArrayEquals(analysis.resonantFrequencies, restored.resonantFrequencies)
        assertFloatArrayEquals(analysis.powers, restored.powers)
        assertFloatArrayEquals(analysis.complex, restored.complex)

        val partials = restored.topPartials(frame = restored.numSlices - 1, count = 2)
        assertEquals(2, partials.size)
        assertTrue(partials[0].power >= partials[1].power)

        val dominant = restored.resonatorsByMeanPower(count = 1)
        assertEquals(1, dominant.size)
        val track = restored.track(dominant[0])
        assertEquals(restored.numSlices, track.frequencies.size)
        assertEquals(restored.numSlices, track.amplitudes.size)
        println(
            "  OK: restored ${restored.numSlices} frames x ${restored.numResonators} resonators; " +
                "dominant resonator #${dominant[0]} has ${track.frequencies.size} tracked samples."
        )
    }

    @Test
    fun additiveResynthesisProducesFiniteAudio() {
        println("Checking additive resynthesis output health from stored tracking analysis.")
        val analysis = makeSmallAnalysis()
        val stretchedLength = analysis.transformedSampleCount(1.25f)
        val output = analysis.resynthesizeAdditive(
            length = stretchedLength,
            maxPartials = 2,
            gain = 0.5f,
            normalizePeak = 0.8f,
            pitchShiftSemitones = 7.0f,
            timeStretch = 1.25f
        )

        assertEquals(stretchedLength, output.size)
        val peak = output.maxOf { abs(it) }
        val rms = sqrt(output.fold(0.0) { sum, sample -> sum + sample * sample } / output.size)

        assertTrue(output.all { it.isFinite() })
        assertTrue(peak <= 0.8001f, "peak was $peak")
        assertTrue(rms > 0.001, "rms was $rms")
        println("  OK: additive output has ${output.size} samples, peak=$peak, rms=$rms.")
    }

    @Test
    fun meanPowerSelectionCanPreferSeparatedLocalPeaks() {
        println("Checking mean-power peak picking avoids neighboring resonator duplicates.")
        val naturalFrequencies = floatArrayOf(100f, 110f, 120f, 200f, 215f)
        val powers = floatArrayOf(
            1f, 4f, 2f, 5f, 4f,
            1f, 4f, 2f, 5f, 4f
        )
        val complex = FloatArray(2 * 2 * naturalFrequencies.size)
        for (frame in 0 until 2) {
            for (resonator in naturalFrequencies.indices) {
                complex[frame * 2 * naturalFrequencies.size + resonator] = sqrt(powers[frame * naturalFrequencies.size + resonator])
            }
        }
        val analysis = TrackingResonatorAnalysis(
            sampleRate = sampleRate,
            hopLength = 128,
            sourceSampleCount = 256,
            naturalFrequencies = naturalFrequencies,
            resonantFrequencies = FloatArray(2 * naturalFrequencies.size) { naturalFrequencies[it % naturalFrequencies.size] },
            powers = powers,
            complex = complex
        )

        val localPeaks = analysis.resonatorsByMeanPower(
            count = 3,
            localMaximaOnly = true,
            minResonatorSpacing = 1
        )

        assertEquals(listOf(3, 1), localPeaks.toList())
        println("  OK: selected local peak resonators ${localPeaks.joinToString()} instead of adjacent shoulders.")
    }

    @Test
    fun meanPowerSelectionCanTiltTowardHighFrequencies() {
        println("Checking mean-power selection can spend budget on high-frequency content.")
        val naturalFrequencies = floatArrayOf(100f, 1_000f, 10_000f)
        val powers = floatArrayOf(
            5.0f, 3.0f, 1.0f,
            5.0f, 3.0f, 1.0f
        )
        val complex = FloatArray(2 * 2 * naturalFrequencies.size)
        val analysis = TrackingResonatorAnalysis(
            sampleRate = sampleRate,
            hopLength = 128,
            sourceSampleCount = 256,
            naturalFrequencies = naturalFrequencies,
            resonantFrequencies = FloatArray(2 * naturalFrequencies.size) { naturalFrequencies[it % naturalFrequencies.size] },
            powers = powers,
            complex = complex
        )

        assertEquals(0, analysis.resonatorsByMeanPower(count = 1).single())
        assertEquals(
            2,
            analysis.resonatorsByMeanPower(
                count = 1,
                selectionFrequencyTilt = 1.0f,
                selectionReferenceFrequency = 1_000f
            ).single()
        )
        println("  OK: frequency tilt can prioritize high-frequency tracks over stronger low-frequency tracks.")
    }

    private fun makeSmallAnalysis(): TrackingResonatorAnalysis {
        val naturalFrequencies = floatArrayOf(430f, 875f, 1100f)
        val alphas = FloatArray(naturalFrequencies.size) { 0.012f }
        val betas = FloatArray(naturalFrequencies.size) { 0.04f }
        val gammas = FloatArray(naturalFrequencies.size) { 0.001f }
        val hopLength = 256
        val input = FloatArray(hopLength * 10) { i ->
            val t = i / sampleRate
            (0.65f * sin((2.0 * PI * 440.0 * t)).toFloat()) +
                (0.2f * sin((2.0 * PI * 880.0 * t)).toFloat())
        }
        return trackingResonate(
            input = input,
            samplingRate = sampleRate,
            naturalFrequencies = naturalFrequencies,
            alphas = alphas,
            betas = betas,
            gammas = gammas,
            hopLength = hopLength
        )
    }

    private fun sineFrame(count: Int, frequency: Float, amplitude: Float = 1.0f): FloatArray =
        FloatArray(count) { i ->
            (amplitude * sin((2.0 * PI * frequency * i / sampleRate))).toFloat()
        }

    private fun assertFloatArrayEquals(expected: FloatArray, actual: FloatArray, tolerance: Float = 0.0f) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertTrue(abs(expected[i] - actual[i]) <= tolerance, "mismatch at $i: ${expected[i]} != ${actual[i]}")
        }
    }
}
