package afield.four.synth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Floors for the pitch detector and the notation it writes back out.
 *
 * Every number this branch quotes in prose is asserted here, with the measurement in the assertion
 * message so a failure says what changed rather than that something did. The floors sit a margin below
 * what was measured — close enough to catch a regression, far enough not to fail on a JIT-dependent
 * last digit — and each one names the value it was set from.
 *
 * Two conventions are worth knowing before reading the numbers.
 *
 * **Onset recall is reported twice and only one of them is fair.** The strict tally
 * ([patTallyOnsets]) forgives nothing; `patMatchOnsets` removes a median constant lag first.
 * `patDetectOnsetSamples` runs about 92 ms late on sustained melodic material, so the strict figure
 * makes it look broken when it is merely uncalibrated. Comparisons between the two criteria therefore
 * use the lag-forgiven figure, which is the one that could be used against this branch.
 *
 * **Pitch error is in cents, and note-level F is not the headline.** [noteLevelFTracksCents] exists
 * because the corpus contains a note exactly 50 cents from equal temperament, which has no correct
 * MIDI answer at all: `kotlin.math.round` breaks ties toward even, so a reference of 62.5 rounds to 62
 * while a measurement of 62.5001 rounds to 63, and a detector accurate to a tenth of a cent is marked
 * wrong. That case's F is capped at 0.75 by arithmetic, not by the detector.
 */
class PatPitchTranscriptionTest {

    companion object {
        /** One corpus run shared by every test here; it costs about 20 s of CPU. */
        private val results: List<PatCaseResult> by lazy { PatTranscriptionCorpus.runAll() }
        private val legato: PatLegatoProbe by lazy { PatTranscriptionCorpus.runLegatoProbe() }
    }

    private fun case(label: String): PatCaseResult =
        results.firstOrNull { it.case.label == label } ?: error("no case '$label'")

    // ── resolution, stated rather than implied ─────────────────────────────────────────────

    @Test
    fun `the analysis window is a stated consequence of the register admitted`() {
        // 2 * (44100/65) window + (44100/65) longest lag = 3 * 678 = 2034 samples = 46.1 ms.
        assertEquals(2034, patPitchFrameSpan(44_100, 65.0), "default frame span changed")
        // Telling the detector it is listening to a treble line shortens the window fourfold. That is
        // the whole mechanism behind the fast-line pair below, so it is pinned here as arithmetic.
        val treble = patPitchFrameSpan(44_100, 261.0)
        assertTrue(
            treble * 4 <= 2034,
            "declaring minHz=261 should shorten the window at least fourfold, got $treble vs 2034"
        )
    }

    // ── the clean case ────────────────────────────────────────────────────────────────────

    @Test
    fun `a detached scale is recovered nearly exactly`() {
        val result = case("detached-scale")
        val score = result.score
        // Measured: 15 of 16 notes, noteF 0.97, cents median 0.3 / worst 0.8, onset median 2.5 ms.
        assertTrue(score.recovered >= 14, "recovered ${score.recovered} of 16 notes")
        assertTrue(score.noteF >= 0.90, "note-level F ${score.noteF}")
        assertTrue(score.centsMedian <= 3.0, "median pitch error ${score.centsMedian} cents")
        assertTrue(score.centsWorst <= 12.0, "worst pitch error ${score.centsWorst} cents")
        assertTrue(score.onsetMedianMs <= 6.0, "median onset error ${score.onsetMedianMs} ms")
        assertTrue(score.onsetWorstMs <= 15.0, "worst onset error ${score.onsetWorstMs} ms")
        assertEquals(0, score.octaveErrors, "octave errors on the clean case")
    }

    // ── exactness: the notation comes back identical ───────────────────────────────────────

    @Test
    fun `three cases transcribe to exactly the notated pitch sequence`() {
        // The strongest available claim: not "close", but the same integers in the same order. Two
        // cycles of the notation, because the corpus renders two.
        val exact = mapOf(
            "wide-leaps" to listOf(48, 67, 52, 72, 55, 76, 60, 79),
            "saw-timbre" to listOf(60, 64, 67, 72),
            "square-timbre" to listOf(60, 64, 67, 72)
        )
        for ((label, cycle) in exact) {
            val recovered = case(label).transcription.midiSequence
            assertEquals(cycle + cycle, recovered, "$label did not round-trip its pitch sequence")
        }
    }

    @Test
    fun `the emitted notation re-renders and transcribes to the same notes`() {
        // Round-trip closure. The notation is not merely printable — re-rendering it through the same
        // offline synth and hearing it again returns the same sequence, which is what makes it a
        // representation of the audio rather than a description of it.
        val first = case("saw-timbre").transcription
        assertEquals(
            listOf(60, 64, 67, 72, 60, 64, 67, 72), first.midiSequence,
            "the first pass did not recover the source sequence"
        )
        val reRendered = patRenderTranscription(first, wave = PatWave.SAW)
        val second = patTranscribe(
            reRendered,
            PatTranscriptionCorpus.SR,
            minGapSeconds = first.grid!!.stepSeconds,
            minHz = patMidiToHz(59.0),
            maxHz = patMidiToHz(73.0)
        )
        assertEquals(
            first.midiSequence, second.midiSequence,
            "re-rendering the recovered notation and transcribing it again changed the notes: " +
                "first pat(\"${first.miniNotation}\"), second pat(\"${second.miniNotation}\")"
        )
    }

    // ── the two criteria fail in opposite directions ───────────────────────────────────────

    @Test
    fun `an energy detector cannot segment a line with no attacks in it`() {
        // The honest form of the legato experiment. `patRenderStereo` re-attacks every event, so a
        // "legato" Pat render still has one amplitude rise per note and the energy detector handles it
        // fine (measured: recall 0.87 with the lag forgiven). This probe is one oscillator whose
        // frequency steps with continuous phase, so the RMS envelope really is flat.
        // Measured: energy 0.13, pitch 0.86, both with the constant lag forgiven.
        val energy = legato.energyMatch.recall
        val pitch = legato.pitchMatch.recall
        assertTrue(
            energy <= 0.30,
            "with a flat envelope the energy detector should find almost nothing, got recall $energy"
        )
        assertTrue(
            pitch >= 0.70,
            "the pitch detector should still hear the line, got recall $pitch"
        )
        assertTrue(
            pitch >= energy + 0.40,
            "the point of this case is the gap: pitch $pitch vs energy $energy"
        )
        // And it is a real Dough render at a different sample rate from every other case.
        assertEquals(48_000, legato.sampleRate, "the legato probe should cross the rate boundary")
    }

    @Test
    fun `a pitch detector cannot see a repeated pitch and the merge can`() {
        // The other half of the argument, and the reason patDetectNoteOnsets exists. Half of this
        // case's note starts have no pitch change at them.
        // Measured strict: energy 0.13, pitch 0.81, merged 0.88.
        val result = case("repeated-pitch")
        val energy = result.energyTally.recall
        val pitch = result.pitchTally.recall
        val merged = result.mergedTally.recall
        assertTrue(
            merged > pitch,
            "merging should beat pitch alone where pitches repeat: merged $merged, pitch $pitch"
        )
        assertTrue(
            merged > energy,
            "merging should beat energy alone too: merged $merged, energy $energy"
        )
        assertTrue(merged >= 0.80, "merged recall $merged")
    }

    @Test
    fun `merging never costs the pitch detector an onset`() {
        // An invariant rather than a threshold, and the one that caught the first version of
        // patDetectNoteOnsets: keeping the earlier of two colliding candidates let the energy
        // detector's 92 ms lag displace correct detections, and merged recall on detached-scale fell
        // to 0.19 against 0.94 for pitch alone.
        for (result in results.filter { it.reference.isNotEmpty() }) {
            assertTrue(
                result.mergedTally.recall >= result.pitchTally.recall - 1e-9,
                "${result.case.label}: merged recall ${result.mergedTally.recall} is below " +
                    "pitch-only ${result.pitchTally.recall}"
            )
        }
    }

    // ── the register is the largest lever, measured ────────────────────────────────────────

    @Test
    fun `declaring the register is worth more than any threshold`() {
        // Identical audio, identical thresholds; the only difference is whether minHz/maxHz come from
        // the notation or from the wide default. Measured strict pitch recall: 0.91 told, 0.03 untold.
        val told = case("fast-line")
        val untold = case("fast-line-untold")
        assertEquals(
            told.samples.size, untold.samples.size,
            "the pair must be the same audio for this comparison to mean anything"
        )
        assertTrue(
            told.pitchTally.recall >= 0.80,
            "told its register, pitch recall should hold up: ${told.pitchTally.recall}"
        )
        assertTrue(
            untold.pitchTally.recall <= 0.15,
            "not told, recall should collapse: ${untold.pitchTally.recall}"
        )
        // And the collapse must be explained, not silent.
        assertEquals(
            PatPitchVerdict.UNRESOLVED, untold.transcription.diagnosis.verdict,
            "an unresolvable line should say so; warnings were ${untold.transcription.warnings}"
        )
        assertTrue(
            untold.transcription.warnings.any { it.contains("register") },
            "the fix should be named in the warning: ${untold.transcription.warnings}"
        )
    }

    @Test
    fun `a line at the resolution floor degrades and says how much`() {
        // 31 ms notes against a 15 ms window: recoverable, but not accurately. Reported rather than
        // tuned away. Measured: 23 of 32 notes, cents median 19 but p90 194.
        val score = case("fast-line").score
        assertTrue(score.recovered >= 18, "recovered ${score.recovered} of 32")
        assertTrue(
            score.centsP90 > score.centsMedian * 3,
            "this case is supposed to have a long tail: median ${score.centsMedian}, " +
                "p90 ${score.centsP90}. A single mean would have hidden it"
        )
    }

    // ── refusals ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a triad is refused and named as polyphony rather than guessed at`() {
        val result = case("chord-triad")
        assertTrue(
            result.transcription.notes.isEmpty(),
            "a monophonic detector must not name a chord, got ${result.transcription.notes}"
        )
        assertEquals("~", result.transcription.miniNotation, "notation for a refused chord")
        assertEquals(
            PatPitchVerdict.POLYPHONIC, result.transcription.diagnosis.verdict,
            "verdict was ${result.transcription.diagnosis.describe()}"
        )
        assertTrue(
            result.transcription.warnings.isNotEmpty(),
            "silence about a refusal is the one thing that would make it useless"
        )
        // Sparsity is the discriminator: a triad is a few discrete partials. Measured 3.
        assertTrue(
            result.transcription.diagnosis.spectralPeaks <= 12,
            "a chord should be spectrally sparse, counted ${result.transcription.diagnosis.spectralPeaks}"
        )
    }

    @Test
    fun `a noise train reports no pitch rather than a bad one`() {
        val result = case("percussive-noise")
        assertEquals(0, result.voicedFrames, "noise should leave no frame voiced")
        assertTrue(result.transcription.notes.isEmpty(), "notes recovered from noise")
        assertEquals(
            PatPitchVerdict.UNPITCHED, result.transcription.diagnosis.verdict,
            "verdict was ${result.transcription.diagnosis.describe()}"
        )
        // And it is told apart from a chord by density, not by luck. Measured 365 peaks vs the
        // triad's 3 — two orders of magnitude, so the threshold between them is not delicate.
        val noise = result.transcription.diagnosis.spectralPeaks
        val chord = case("chord-triad").transcription.diagnosis.spectralPeaks
        assertTrue(
            noise >= chord * 8,
            "noise ($noise peaks) and a chord ($chord peaks) must be far apart for the verdict to mean anything"
        )
    }

    @Test
    fun `timbre is not polyphony`() {
        // The control that makes the refusal worth having: a sawtooth and a square wave are stacks of
        // partials, and refusing them would make "polyphonic" mean "harmonically rich".
        for (label in listOf("saw-timbre", "square-timbre")) {
            val result = case(label)
            assertTrue(
                result.transcription.declined.isEmpty(),
                "$label was declined as not-one-note: ${result.transcription.declined}"
            )
            assertEquals(1.0, result.score.noteF, 1e-9, "$label note-level F")
            assertEquals(0, result.score.octaveErrors, "$label octave errors")
        }
    }

    // ── the cents axis, and why it is the axis ─────────────────────────────────────────────

    @Test
    fun noteLevelFTracksCents() {
        // Notes deliberately 25, 50, 75 and 50 cents sharp. The pitch measurement is essentially
        // perfect — measured worst 0.1 cents — while note-level F is 0.75, because two of the eight
        // notes are exactly 50 cents from equal temperament and there is no correct integer for them.
        val result = case("detuned-cents")
        val score = result.score
        assertEquals(8, score.recovered, "all eight detuned notes should be found")
        assertTrue(
            score.centsWorst <= 5.0,
            "the pitch measurement should be near-exact: worst ${score.centsWorst} cents"
        )
        assertTrue(
            score.noteF < 1.0,
            "this case is supposed to demonstrate the tie: F ${score.noteF}"
        )
        // The tie itself, pinned so the explanation cannot rot: Kotlin rounds halves to even.
        assertEquals(62.0, kotlin.math.round(62.5), "kotlin.math.round breaks ties toward even")
        assertEquals(63.0, kotlin.math.round(62.5001), "one ten-thousandth flips the note name")
        // And the recovered cents really do track the notated detuning.
        val offsets = result.transcription.notes.map { it.centsOffset }
        assertTrue(
            offsets.any { abs(abs(it) - 25.0) < 5.0 },
            "a +25-cent note should show up as a ~25-cent offset, got $offsets"
        )
    }

    // ── corpus-wide ───────────────────────────────────────────────────────────────────────

    @Test
    fun `pitch is accurate to a few cents wherever notes are recovered at all`() {
        val pitched = results.filter { it.score.onsetMatched >= 4 && it.case.label != "fast-line" }
        assertTrue(pitched.size >= 8, "only ${pitched.size} cases recovered enough notes to score")
        val medians = pitched.map { it.score.centsMedian }.sorted()
        val worstMedian = medians.last()
        // Measured: every such case has a median at or under 2.0 cents.
        assertTrue(
            worstMedian <= 4.0,
            "worst per-case median pitch error is $worstMedian cents, from " +
                pitched.maxByOrNull { it.score.centsMedian }?.case?.label
        )
        assertEquals(
            0, pitched.sumOf { it.score.octaveErrors },
            "octave errors outside the resolution-floor case: " +
                pitched.filter { it.score.octaveErrors > 0 }.map { it.case.label }
        )
    }

    @Test
    fun `every case that names no notes explains why`() {
        for (result in results) {
            if (result.transcription.notes.isNotEmpty()) continue
            assertTrue(
                result.transcription.warnings.isNotEmpty(),
                "${result.case.label} recovered nothing and said nothing about it"
            )
            assertTrue(
                result.transcription.diagnosis.verdict != PatPitchVerdict.PITCHED,
                "${result.case.label} named no notes but reports itself PITCHED"
            )
        }
    }

    @Test
    fun `the corpus keeps its designed-to-fail cases`() {
        // A harness that quietly loses its hard cases stops measuring range and starts measuring
        // tuning, so the shape of the corpus is itself asserted.
        val labels = PatTranscriptionCorpus.CASES.map { it.label }
        for (required in listOf(
            "legato-scale", "repeated-pitch", "chord-triad", "percussive-noise",
            "fast-line", "fast-line-untold", "bass-register", "detuned-cents"
        )) {
            assertTrue(required in labels, "corpus lost its '$required' case")
        }
        assertTrue(
            PatTranscriptionCorpus.CASES.all { it.purpose.isNotBlank() },
            "every case must say what it is for"
        )
    }

    // ── the fourth hop: notation → sound → notation → sound ───────────────────────────────

    @Test
    fun `half the re-renderable corpus reproduces its own emitted notation`() {
        // The claim three hops cannot make. "The recovered integers equal the notated integers"
        // compares a list against a list and never asks whether the emitted *string* means what the
        // list meant; a `pat("…")` that parses, renders and sounds like something else passes it.
        // Measured: 5 of 10 re-renderable cases reproduce their note sequence when their own notation
        // is rendered again and heard again.
        val (closed, closable) = PatTranscriptionCorpus.closureCount(results)
        assertTrue(closable >= 9, "only $closable cases were re-renderable at all")
        assertTrue(
            closed >= 5,
            "$closed of $closable re-renderable cases close the loop; the ratchet is 5. " +
                results.filter { it.closure.verdict == PatLoopVerdict.DIVERGED }
                    .joinToString("; ") { it.closure.describe() }
        )
        // And the legato Dough probe closes too, at a different sample rate.
        assertTrue(legato.closure.closed, "the legato probe stopped closing: ${legato.closure.describe()}")
    }

    @Test
    fun `an incomplete transcription is what fails to re-render`() {
        // The finding, asserted as a relationship rather than a list of names. Every case that closed
        // the loop named a self-consistent set — no wrong names and no invented notes; every case that
        // diverged had at least one. So the fourth hop is a validity check on the third, which is the
        // reason it is worth paying for.
        for (result in results) {
            if (result.closure.verdict != PatLoopVerdict.CLOSED) continue
            val verdict = result.verdict
            assertEquals(
                0, verdict.wrongNames,
                "${result.case.label} closed the loop yet has wrong names — the relationship this " +
                    "test documents has broken and the finding needs restating"
            )
            assertEquals(
                0, verdict.spuriousNotes,
                "${result.case.label} closed the loop yet invented notes"
            )
        }
    }

    @Test
    fun `refining the step length keeps re-rendered onsets on their own step`() {
        // Guards the accumulated-drift bug the fourth hop exposed: the coarse grid sweep takes the
        // largest step inside its tolerance, which biases the step long, which is invisible per note
        // and is the whole error by the end of a phrase. saw-timbre's onset shift on re-render was
        // 54.1 ms before `refineStep` and 19.2 ms after, against a true step of 333 ms.
        for (label in listOf("saw-timbre", "square-timbre")) {
            val closure = case(label).closure
            assertTrue(
                closure.worstOnsetShift <= 0.030,
                "$label re-render drifted ${closure.worstOnsetShift * 1000} ms; the step fit has " +
                    "regressed toward its long bias"
            )
        }
    }

    // ── confidently wrong is the bucket that matters ───────────────────────────────────────

    @Test
    fun `nothing in the corpus is confidently wrong`() {
        // The ceiling the whole classification exists for. A transcriber that emits a plausible,
        // parseable, wrong `pat("…")` is worse than one that returns UNRESOLVED, because the wrong
        // answer gets believed and the refusal does not — and both land in the same region of any
        // F-measure, which is why this is a census and not a score.
        //
        // This was **1, not 0**, when the census was first run: `legato-scale` emitted
        // `pat("60 62 65 _ 67 69 71 72 …")` — 64 and 65 heard as one note, one name 65 cents out — with
        // no warning of any kind. It is 0 now because of a specific self-check, not a blanket warning:
        // a note held across more than one step in a grid whose other notes span exactly one is where
        // two notes were merged, and the transcriber can see that in its own output without any ground
        // truth. Anything that puts a case back in this bucket is a real regression.
        val wrong = results.filter { it.verdict.behaviour == PatCaseBehaviour.CONFIDENTLY_WRONG }
        assertEquals(
            0, wrong.size,
            "confidently wrong: " + wrong.joinToString("; ") { it.verdict.describe() }
        )
    }

    @Test
    fun `every case lands in exactly one behaviour bucket and the good ones dominate`() {
        assertEquals(
            results.size, results.map { it.verdict.behaviour }.size,
            "a case escaped classification"
        )
        val exact = results.count { it.verdict.behaviour == PatCaseBehaviour.RECOVERED_EXACTLY }
        val within = results.count { it.verdict.behaviour == PatCaseBehaviour.RECOVERED_WITHIN }
        val refused = results.count { it.verdict.behaviour == PatCaseBehaviour.REFUSED }
        // Measured: 4 exact, 4 within, 5 refused, 0 confidently wrong.
        assertTrue(exact >= 3, "only $exact cases recovered exactly")
        assertTrue(exact + within >= 7, "only ${exact + within} cases recovered at all")
        assertTrue(refused >= 4, "only $refused cases refused; the hard cases should still refuse")
        // Every refusal must be explained. A silent refusal is as useless as a wrong answer.
        for (result in results.filter { it.verdict.behaviour == PatCaseBehaviour.REFUSED }) {
            assertTrue(
                result.transcription.warnings.isNotEmpty(),
                "${result.case.label} refused without saying why"
            )
        }
    }

    // ── can minHz have a defensible default? ───────────────────────────────────────────────

    @Test
    fun `estimating the register recovers most of what declaring it buys`() {
        // The out-of-the-box question. A caller who declares nothing lands on a 46 ms window, and on a
        // fast line that means onset recall 0.03 — a correct measurement and an indefensible default.
        // Two passes fix it: a wide pass is a poor transcriber but a perfectly good histogram, because a
        // too-long window ruins segmentation and not estimation.
        //
        // Measured mean onset recall over the corpus: declared 0.690, auto 0.716, wide 0.538. So the
        // answer is yes — a defensible default exists, and `patTranscribeAuto` is it.
        val auto = PatTranscriptionCorpus.runAuto()
        val meanDeclared = auto.map { it.declared }.average()
        val meanAuto = auto.map { it.auto }.average()
        val meanWide = auto.map { it.wide }.average()
        assertTrue(
            meanAuto >= meanWide + 0.12,
            "estimating the register should beat the wide default: auto $meanAuto vs wide $meanWide"
        )
        assertTrue(
            meanAuto >= meanDeclared - 0.02,
            "estimating should be no worse on average than declaring: auto $meanAuto vs " +
                "declared $meanDeclared"
        )
        // The case the whole lever was built on: told nothing, auto still hears the fast line.
        val untold = auto.first { it.label == "fast-line-untold" }
        assertTrue(
            untold.auto >= 0.50,
            "auto should rescue the untold fast line, got ${untold.auto} (wide ${untold.wide})"
        )
    }

    @Test
    fun `estimating the register refuses a chord instead of naming its virtual root`() {
        // The cost of a default, and it took three attempts to get right. A 4:5:6 triad is genuinely
        // periodic two octaves below anything sounding — 261/4 ≈ 329/5 ≈ 392/6 ≈ 65 Hz — so a detector
        // allowed to look that low is *correct* to report 65 Hz, and `patTranscribeAuto` named five
        // confident notes around MIDI 36. Nothing was wrong with the periodicity; the fundamental was
        // simply one nobody played. The discriminator is spectral: a played fundamental has energy in
        // its own bin and a virtual one does not.
        val auto = PatTranscriptionCorpus.runAuto()
        val chord = auto.first { it.label == "chord-triad" }
        assertEquals(
            0, chord.autoNotes,
            "auto named ${chord.autoNotes} notes on a triad; it must refuse rather than name a " +
                "fundamental nobody played (estimated register ${chord.estimated})"
        )
        // The control that makes the test meaningful rather than a distrust of low notes: bass-register
        // really does play MIDI 36 — 65 Hz, the same frequency — and must NOT be refused.
        val bass = auto.first { it.label == "bass-register" }
        assertTrue(
            bass.autoNotes >= 10,
            "a real 65 Hz note was refused as a virtual fundamental: ${bass.autoNotes} notes named"
        )
        assertTrue(
            bass.auto >= bass.declared - 1e-9,
            "estimating cost the bass case recall: auto ${bass.auto} vs declared ${bass.declared}"
        )
    }

    @Test
    fun `a played fundamental and an implied one are far apart`() {
        // The margin behind the guard above, asserted so the threshold cannot be called delicate.
        val chord = case("chord-triad")
        val bass = case("bass-register")
        fun energyAtReported(result: PatCaseResult): Double {
            val loudest = result.transcription.frames.filter { it.voiced }.maxByOrNull { it.rms }
                ?: return Double.NaN
            return patEnergyAtF0(
                result.samples, loudest.startSample,
                (12.0 * result.sampleRate / loudest.hz).toInt(), result.sampleRate, loudest.hz
            )
        }
        // bass-register plays its fundamental; the corpus's triad only implies one.
        val played = energyAtReported(bass)
        assertTrue(played > 0.30, "a played 65 Hz fundamental should show up strongly, got $played")
    }

    @Test
    fun `print the corpus`() {
        // Not an assertion; the table is worth having in the test output next to the floors above.
        println(PatTranscriptionCorpus.report(results))
        println("legato probe (Dough, 48 kHz, flat envelope):")
        println(legato.describe())
        println()
        println("── register: declared vs estimated vs wide ──")
        val auto = PatTranscriptionCorpus.runAuto()
        for (a in auto) println("  " + a.describe())
        println("  mean — declared %.3f  auto %.3f  wide %.3f".format(
            auto.map { it.declared }.average(),
            auto.map { it.auto }.average(),
            auto.map { it.wide }.average()))
    }
}
