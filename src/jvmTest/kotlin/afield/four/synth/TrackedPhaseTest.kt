package afield.four.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TrackedPhaseTest {
    private val sr = 48_000.0
    private val tau = 2 * PI
    private fun tone(n: Int, hz: Double = 110.0) = (0.7 * sin(tau * hz * n / sr)).toFloat()
    private fun rich(n: Int, hz: Double, missing: Boolean = true): Float {
        val t = n / sr
        return ((if (missing) 0.0 else 0.6 * sin(tau * hz * t)) +
            0.7 * sin(tau * 2 * hz * t + 0.31) + 0.48 * sin(tau * 3 * hz * t - 0.77) +
            0.31 * sin(tau * 5 * hz * t + 1.13) + 0.05 * sin(tau * 731 * t)).toFloat()
    }

    @Test fun facadeKeepsTheOriginalQuadratureOutput() {
        // The pre-refactor algorithm, including its held phase agreement during silence.
        val tr = SubharmonicQuadPLL(division = 2.03)
        val h = HilbertPair()
        var omega = tau * 100 / sr
        var phase = 0.0; var sub = 0.0; var env = 0.0; var lock = 0.0
        var lastI = 0.0; var lastQ = 0.0
        val wn = tau * 8 / sr
        val keep = exp(-1 / (0.01 * sr))
        for (n in 0 until 72_000) {
            val x = if (n in 24_000..35_999) 0f else tone(n, if (n < 36_000) 73.42 else 164.81)
            h.process(x.toDouble())
            val i = h.i; val q = h.q; val mag = sqrt(i * i + q * q)
            val target = if (mag > 0.01) mag else 0.0
            val c = exp(-1 / ((if (target > env) 0.005 else 0.06) * sr))
            env = c * env + (1 - c) * target
            if (mag > 0.01) {
                val error = (i * cos(phase) - q * sin(phase)) / mag
                lock = keep * lock + (1 - keep) * (q * cos(phase) + i * sin(phase)) / mag
                if (lock < 0.9 && lastI * lastI + lastQ * lastQ > 0.0001) {
                    val a = kotlin.math.atan2(i * lastQ - q * lastI, q * lastQ + i * lastI)
                    if (a > 0) omega += (1 - exp(-1 / (0.02 * sr))) * (a - omega)
                }
                omega = (omega + wn * wn * error).coerceIn(tau * 35 / sr, tau * 350 / sr)
                phase = wrapTrackedPhase(phase + omega + 2 * 0.707 * wn * error)
            } else phase = wrapTrackedPhase(phase + omega)
            lastI = i; lastQ = q
            sub = wrapTrackedPhase(sub + omega / 2.03)
            assertEquals((sin(sub) * env).toFloat(), tr.process(x), 1e-6f, "sample $n")
        }
    }

    @Test fun fractionalRatiosIntegrateAdvanceAcrossWrapsAndChanges() {
        val state = TrackedPhaseState(sr).apply { omega = 0.7; phaseAdvance = 0.9; level = 0.8 }
        val voice = PhaseVoice(0.5, lock = 0.75)
        val bank = TrackedPhaseBank(voices = mutableListOf(voice))
        var expectedPhase = 0.0
        repeat(2000) { n ->
            voice.ratio = if (n < 1000) 0.5 else sqrt(2.0)
            state.phase = wrapTrackedPhase(state.phase + state.phaseAdvance)
            expectedPhase = wrapTrackedPhase(expectedPhase + voice.ratio * 0.85)
            assertEquals(0.8 * sin(expectedPhase), bank.render(state), 1e-12)
        }
    }

    @Test fun nyquistFadeUsesActualVoiceVelocityIncludingLockCorrection() {
        val state = TrackedPhaseState(sr).apply { omega = 0.1; phaseAdvance = PI; level = 1.0 }
        val voice = PhaseVoice(1.0, renderer = PhaseRenderer { 1.0 })
        val bank = TrackedPhaseBank(voices = mutableListOf(voice))
        assertEquals(0.0, bank.render(state))
        state.phaseAdvance = PI * 0.85
        assertEquals(1.0, bank.render(state))
        state.phaseAdvance = PI * (0.85 + 0.98) / 2
        assertEquals(0.5, bank.render(state), 1e-12)
        voice.ratio = -2.0
        assertEquals(0.0, bank.render(state))
    }

    @Test fun separateOutputsSumToMonoWithTheSameGains() {
        fun bank() = TrackedPhaseBank(voices = TrackedPhaseBank.rationalLattice(), outputGain = 0.4)
        val mono = bank(); val separate = bank(); val out = DoubleArray(7)
        val tracker = QuadPhaseTracker()
        repeat(4000) { n ->
            val state = tracker.process(tone(n))
            val expected = mono.render(state)
            assertEquals(expected, separate.renderVoices(state, out), 1e-12)
            assertEquals(expected, out.sum(), 1e-12)
        }
    }

    @Test fun graphsShareAnalysisOnceAndRetainEachSamplesState() {
        val base = QuadPhaseTracker()
        var samples = 0
        val tracker = object : PhaseTracker by base {
            override fun process(input: Float): TrackedPhaseState { samples++; return base.process(input) }
        }
        val source = SamplesSignal(FloatArray(4096) { tone(it) })
        val analysis = TrackedPhaseAnalysis(source, tracker)
        fun bank() = TrackedPhaseBank(voices = mutableListOf(PhaseVoice(0.5, lock = 0.0)))
        val a = analysis.synthesize(bank()); val b = analysis.synthesize(bank())
        val expected = TrackedPhaseBank(QuadPhaseTracker(), mutableListOf(PhaseVoice(0.5, lock = 0.0)))
        val ctx = SynthContext(sr.toFloat(), 128); val out = FloatArray(128)
        repeat(32) { block ->
            (a + b).render(out, 128, ctx)
            for (i in out.indices) assertEquals(2 * expected.process(tone(block * 128 + i)), out[i], 1e-6f)
            ctx.advanceCycle()
        }
        assertEquals(4096, samples)
        assertFailsWith<IllegalArgumentException> {
            TrackedPhaseAnalysis(Const(0f), QuadPhaseTracker()).render(out, 128, SynthContext(44100f, 128))
        }
    }

    @Test fun harmonicTrackerAcquiresMissingFundamentalsFromWrongOctaves() {
        for (hz in listOf(55.0, 110.0, 220.0)) for (seed in listOf(45.0, 100.0, 250.0)) {
            val tracker = HarmonicPhaseTracker(initialHz = seed)
            repeat(96_000) { tracker.process(rich(it, hz)) }
            assertEquals(hz, tracker.state.hz, 0.75, "$hz Hz from $seed: ${tracker.state}")
            assertTrue(tracker.state.confidence > 0.65, "$hz from $seed confidence ${tracker.state.confidence}")
            assertTrue(tracker.state.activePartials >= 3)
        }
    }

    @Test fun harmonicTrackerReacquiresAfterANoteChangeDespitePreviousLock() {
        val tracker = HarmonicPhaseTracker(initialHz = 110.0)
        repeat(48_000) { tracker.process(rich(it, 110.0)) }
        assertTrue(tracker.state.locked)
        repeat(48_000) { tracker.process(rich(it, 73.42, missing = false)) }
        assertEquals(73.42, tracker.state.hz, 0.75)
    }

    @Test fun pureToneDoesNotAcquireASubharmonicAndDcIsNotConfidentPitch() {
        for (seed in listOf(55.0, 250.0)) {
            val tracker = HarmonicPhaseTracker(initialHz = seed)
            repeat(96_000) { tracker.process(tone(it)) }
            assertEquals(110.0, tracker.state.hz, 0.75)
        }
        val dc = HarmonicPhaseTracker()
        repeat(96_000) { dc.process(0.3f) }
        assertEquals(0.0, dc.coarseHz)
        assertTrue(dc.state.confidence < 0.1)
    }

    @Test fun resetReplaysAndSilenceClosesGateForBothTrackers() {
        for (tracker in listOf(QuadPhaseTracker(), HarmonicPhaseTracker())) {
            val bank = TrackedPhaseBank(tracker, TrackedPhaseBank.harmonics(5))
            val first = FloatArray(12_000) { bank.process(tone(it)) }
            bank.reset()
            for (n in first.indices) assertEquals(first[n], bank.process(tone(n)))
            repeat(96_000) { bank.process(0f) }
            assertTrue(!tracker.state.locked && !tracker.state.gateOpen)
            assertEquals(0.0, tracker.state.confidence)
            assertTrue(tracker.state.level < 1e-5)
            assertSame(tracker.state, tracker.process(Float.NaN))
            assertTrue(tracker.process(Float.POSITIVE_INFINITY).hz.isFinite())
        }
    }

    @Test fun recordedPlucksProduceTheAdvertisedBankSounds() {
        val notes = listOf(0, 2, 4, 3, 0, 1, 4, 2)
        for (lattice in listOf(false, true)) {
            val (graph, tracker) = SubharmonicSoundDocs.phaseBankGraph(lattice)
            val ctx = SynthContext(sr.toFloat(), 128)
            val buffer = FloatArray(128)
            var dryPower = 0.0; var wetPower = 0.0
            val errors = ArrayList<Double>()
            repeat(8 * sr.toInt() / buffer.size) { block ->
                graph.render(buffer, buffer.size, ctx)
                for (sample in buffer) {
                    val power = sample.toDouble() * sample
                    if (block * buffer.size < 4 * sr) dryPower += power else wetPower += power
                }
                val time = (block + 1) * buffer.size / sr
                if (time >= 4 && (time % 0.5) in 0.12..0.25) {
                    val note = notes[(time / 0.5).toInt() % notes.size]
                    errors += abs(tracker.state.hz / (SubharmonicSoundDocs.ARPY_HZ[note] * 0.5) - 1.0)
                }
                ctx.advanceCycle()
            }
            assertTrue(sqrt(dryPower / (4 * sr)) > 0.005, "dry recorded plucks missing")
            assertTrue(sqrt(wetPower / (4 * sr)) > 0.005, "bank rendered silence")
            errors.sort()
            assertTrue(errors[errors.size / 2] < 0.08,
                "recorded pluck median tracking error ${errors[errors.size / 2]}")
        }
    }

    @Test fun invalidConfigurationFailsBeforeAllocatingSearchBuffers() {
        assertFailsWith<IllegalArgumentException> { HarmonicPhaseTracker(coarseDecimation = 0) }
        assertFailsWith<IllegalArgumentException> { HarmonicPhaseTracker(minHz = 0.0) }
        assertFailsWith<IllegalArgumentException> { HarmonicPhaseTracker(sampleRate = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { TrackedPhaseBank(nyquistFadeStart = 0.98, nyquistFadeEnd = 0.85) }
    }
}
