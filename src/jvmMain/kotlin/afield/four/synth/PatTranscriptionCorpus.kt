/*
 A corpus that scores a transcription against the notation that generated the audio.

 The ground truth here costs nothing and is exact, which is unusual enough to be worth stating.
 Transcription research is normally bounded by annotation: someone listens to a recording and writes
 down what they think is in it, and the transcriber is then measured against a human's opinion.
 Field authored the notation, rendered it through its own synthesiser, and still has the notation — so
 the reference is not an annotation of the audio, it *is* the audio's cause. Every number in
 [PatTranscriptionCorpus] is a comparison against a fact.

 [referenceOf] therefore mirrors `patRenderStereo` (`PatSoundDocs.kt:785-797`) line for line rather
 than reimplementing the schedule: same `events(PatArc(ZERO, of(cycles)))`, same sort, same
 `arc.start · secondsPerCycle`, same `(startSec · sampleRate).toInt()` truncation, and deliberately no
 `hasOnset` filter, because the renderer has none either. A reference computed from what the schedule
 *ought* to be would be measuring this file's understanding of the renderer instead of the detector.

 **Half the corpus is designed to fail**, and those cases are the point. A harness that only contains
 material its detector handles reports the detector's tuning, not its range. So there is a legato line
 with no attacks in it, a triad a monophonic detector must refuse rather than name, a line fast enough
 to sit under the analysis window, a noise train with no pitch in it at all, and square and sawtooth
 timbres whose harmonic stacks are the classic octave-error traps. What each case is *for* is written
 into [PatTranscriptionCase.purpose] and printed with its score, so a regression shows up as a
 sentence rather than a delta.

 The `*-untold` cases exist to measure one design claim rather than assert it: that telling the
 detector the register it is listening to — the same contract `patDetectOnsetSamples` has with
 `minGapSeconds` — is worth more than any threshold. The pair differs in nothing but whether
 `minHz`/`maxHz` come from the notation or from the wide default.
 */
@file:afield.CompletionCategory("Audio")

package afield.four.synth

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One notated pattern, rendered and then measured.
 *
 * @param stepsPerCycle top-level steps in [notation]. Stated rather than counted because it is the
 *   material's own smallest interval and therefore the detector's refractory gap, and a parser-free
 *   token count would be wrong for any notation with a subgroup in it.
 * @param tellRegister when true the detector is given the notation's actual pitch range. When false
 *   it gets the wide default, which is the counterfactual the `*-untold` cases measure.
 * @param purpose what this case is for, especially when what it is for is failing.
 */
class PatTranscriptionCase(
    val label: String,
    val notation: String,
    val stepsPerCycle: Int,
    val cycles: Int,
    val cps: Double,
    val voice: PatVoiceSpec,
    val purpose: String,
    val tellRegister: Boolean = true
) {
    val secondsPerCycle: Double get() = 1.0 / cps
    val stepSeconds: Double get() = secondsPerCycle / stepsPerCycle
    val totalSeconds: Double get() = cycles * secondsPerCycle

    /** The pattern, parsed once per call exactly as the renderer would parse it. */
    fun pattern(): PatPattern<String> = pat(notation)
}

/**
 * What a case *did*, as opposed to how it scored.
 *
 * Scores rank; this classifies, and the distinction is the point. A transcriber that emits a plausible,
 * parseable, wrong `pat("…")` is worse than one that returns `UNRESOLVED`, because the wrong answer will
 * be believed and the refusal will not — yet those two land in the same region of any F-measure. So
 * every case is put in exactly one of these buckets and [CONFIDENTLY_WRONG] carries an asserted ceiling.
 */
enum class PatCaseBehaviour {
    /** The notated integer sequence came back exactly, and the emitted notation re-renders to it. */
    RECOVERED_EXACTLY,

    /** Every named note is the right note within 50 cents; some notated notes may be missing. */
    RECOVERED_WITHIN,

    /** Named nothing, or named less than it heard, and said so. Incomplete is fine here; silent is not. */
    REFUSED,

    /**
     * Emitted notation containing a wrong or invented note, with **no warning attached**.
     *
     * The bucket that matters. Wrongness is judged in cents rather than on note names, so the corpus's
     * deliberate 50-cents-sharp case does not land here for a rounding tie it cannot win. What puts a
     * case here is a name closer to some other semitone than to the notated one, or a note matching no
     * notated onset at all, in output that gave the reader no reason to doubt it.
     */
    CONFIDENTLY_WRONG
}

/** A case's behaviour, and the evidence for the classification. */
data class PatCaseVerdict(
    val label: String,
    val behaviour: PatCaseBehaviour,
    val wrongNames: Int,
    val spuriousNotes: Int,
    val missingNotes: Int,
    val warnings: Int,
    val why: String
) {
    fun describe(): String =
        "%-18s %-18s".format(label, behaviour.name) +
            "  wrong $wrongNames  invented $spuriousNotes  missing $missingNotes  warnings $warnings" +
            (if (why.isEmpty()) "" else "  — $why")
}

/** Everything one case produced: the audio, the truth, what was heard, and how the two compare. */
class PatCaseResult(
    val case: PatTranscriptionCase,
    val sampleRate: Int,
    val samples: FloatArray,
    val reference: List<PatRefNote>,
    val transcription: PatTranscription,
    val score: PatTranscriptionScore,
    /** Onsets the energy detector found, tallied over the whole schedule. */
    val energyOnsets: List<Int>,
    val energyTally: PatOnsetTally,
    /**
     * The same energy onsets scored with their constant lag forgiven, via `patMatchOnsets`.
     *
     * Both numbers are reported because the strict one alone would have been a rigged comparison, and
     * this branch nearly shipped it. `patDetectOnsetSamples` walks each coarse hit back to a level
     * crossing, which assumes silence before the attack; on sustained melodic material there is none,
     * and its onsets came out **+92 ms late, uniformly** on `detached-scale`. Under the strict tally
     * that reads as recall 0.06 — the energy detector looking useless. Forgive the constant offset and
     * the same onsets score **recall 1.00, worst error 12.2 ms**: the detector was working fine and
     * the tally was measuring a calibration offset. Only where the *shape* carries no information —
     * the flat-envelope Dough legato probe — does the energy criterion genuinely fail, and that is the
     * comparison worth making.
     */
    val energyMatch: PatOnsetMatch,
    /** Onsets the pitch detector found, on the same buffer, against the same schedule. */
    val pitchOnsets: List<Int>,
    val pitchTally: PatOnsetTally,
    val pitchMatch: PatOnsetMatch,
    /** The union of the two, which is what [patDetectNoteOnsets] returns. */
    val mergedOnsets: List<Int>,
    val mergedTally: PatOnsetTally,
    /** The fourth hop: the emitted notation rendered again and heard again. */
    val closure: PatLoopClosure,
    /** What this case did, as opposed to how it scored. */
    val verdict: PatCaseVerdict
) {
    val voicedFrames: Int get() = transcription.frames.count { it.voiced }

    fun describe(): String = buildString {
        appendLine(score.describe())
        if (reference.isEmpty()) {
            appendLine("    no pitched reference note: onset recall is undefined for this case")
        } else {
            appendLine(
                "    onset recall strict — energy ${"%.2f".format(energyTally.recall)} " +
                    "(${energyOnsets.size} found), pitch ${"%.2f".format(pitchTally.recall)} " +
                    "(${pitchOnsets.size}), merged ${"%.2f".format(mergedTally.recall)} " +
                    "(${mergedOnsets.size})"
            )
            appendLine(
                "    onset recall, constant lag forgiven — energy ${"%.2f".format(energyMatch.recall)} " +
                    "(lag ${"%+.0f".format(energyMatch.offset * 1000)} ms), " +
                    "pitch ${"%.2f".format(pitchMatch.recall)} " +
                    "(lag ${"%+.0f".format(pitchMatch.offset * 1000)} ms)"
            )
        }
        appendLine("    voiced ${voicedFrames}/${transcription.frames.size} frames; " +
            "verdict ${transcription.diagnosis.verdict}")
        appendLine("    pat(\"${transcription.miniNotation}\") at cps ${"%.3f".format(transcription.cps)}")
        appendLine("    behaviour ${verdict.behaviour}; loop ${closure.describe().trim()}")
        for (warning in transcription.warnings) appendLine("    ! $warning")
    }
}

/**
 * A legato line `patRenderStereo` cannot produce: one oscillator, continuous phase, stepped frequency.
 *
 * This exists because of a measurement that contradicted the hypothesis it was meant to confirm.
 * `patVoice`'s `sustain` defaults to 1.0, so a gapless Pat render was expected to be one unbroken tone
 * that an energy detector could not segment. It is not. `patRenderStereo` adds *one voice per event*
 * and gives each its own attack ramp from zero (`PatSoundDocs.kt:837`), so a "legato" Pat render still
 * has an amplitude rise at every note and the energy detector found 17 onsets for 16 notes at recall
 * 0.87 — no collapse at all. The claimed experiment simply cannot be run on that renderer.
 *
 * It can be run here. The phase accumulates across the frequency change and the amplitude never moves,
 * so the RMS envelope is flat by construction and there is nothing for an energy criterion to find
 * except the first note's entry from silence. That is the honest form of the comparison, and it is
 * strictly stronger than the one that was assumed, because the audio really does have the property the
 * argument needs.
 *
 * @param midiSteps one pitch per step; a null entry is a rest.
 */
fun patSteppedSine(
    midiSteps: List<Double?>,
    stepSeconds: Double,
    amplitude: Float = 0.5f
): Signal = object : Signal() {
    private var phase = 0.0
    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        val dt = ctx.inverseSampleRate.toDouble()
        val t0 = ctx.time.toDouble()
        for (i in 0 until numFrames) {
            val t = t0 + i * dt
            val midi = midiSteps.getOrNull((t / stepSeconds).toInt())
            if (midi == null) {
                output[i] = 0f
                continue
            }
            output[i] = (amplitude * kotlin.math.sin(2.0 * Math.PI * phase)).toFloat()
            phase += patMidiToHz(midi) * dt
            phase -= kotlin.math.floor(phase)
        }
    }
}

/** The legato probe's audio, truth and three onset tallies. */
class PatLegatoProbe(
    val midiSteps: List<Double?>,
    val stepSeconds: Double,
    val sampleRate: Int,
    val samples: FloatArray,
    val reference: List<PatRefNote>,
    val transcription: PatTranscription,
    val score: PatTranscriptionScore,
    val energyOnsets: List<Int>,
    val energyTally: PatOnsetTally,
    val energyMatch: PatOnsetMatch,
    val pitchOnsets: List<Int>,
    val pitchTally: PatOnsetTally,
    val pitchMatch: PatOnsetMatch,
    val mergedOnsets: List<Int>,
    val mergedTally: PatOnsetTally,
    val closure: PatLoopClosure
) {
    fun describe(): String = buildString {
        appendLine(score.describe())
        appendLine(
            "    onset recall strict — energy ${"%.2f".format(energyTally.recall)} " +
                "(${energyOnsets.size} found), pitch ${"%.2f".format(pitchTally.recall)} " +
                "(${pitchOnsets.size}), merged ${"%.2f".format(mergedTally.recall)} (${mergedOnsets.size})"
        )
        appendLine(
            "    onset recall, constant lag forgiven — energy ${"%.2f".format(energyMatch.recall)} " +
                "(lag ${"%+.0f".format(energyMatch.offset * 1000)} ms), " +
                "pitch ${"%.2f".format(pitchMatch.recall)} " +
                "(lag ${"%+.0f".format(pitchMatch.offset * 1000)} ms)"
        )
        appendLine("    pat(\"${transcription.miniNotation}\") at cps ${"%.3f".format(transcription.cps)}")
        appendLine("    loop ${closure.describe().trim()}")
        for (warning in transcription.warnings) appendLine("    ! $warning")
    }
}

/**
 * The corpus, its renderer, and its scorer.
 *
 * Everything is offline, deterministic and CPU-only: `patRenderStereo` plus this file's own analysis.
 * No Field window, no GPU, no audio device, and in particular no `services/field-audio-ai` — the
 * Python analyser that `RenderedAudioAnalysis.kt` talks to is not needed and not reachable from here.
 */
object PatTranscriptionCorpus {

    /** Sample rate of every case, matching `patRenderStereo`'s default. */
    const val SR: Int = PatSoundDocs.SR

    /** Onset tolerance for scoring: half a step of the fastest case, and no looser anywhere. */
    const val ONSET_TOLERANCE_SECONDS: Double = 0.030

    private fun sine(sustain: Double, decay: Double = 0.03) =
        patVoice(wave = PatWave.SINE, sustain = sustain, decay = decay)

    val CASES: List<PatTranscriptionCase> = listOf(
        PatTranscriptionCase(
            label = "detached-scale",
            notation = "60 62 64 65 67 69 71 72",
            stepsPerCycle = 8, cycles = 2, cps = 1.0,
            voice = sine(sustain = 0.85),
            purpose = "the clean case: eight detached notes, 125 ms apart, well inside the analysis " +
                "window. If this is not near-exact nothing else means anything."
        ),
        PatTranscriptionCase(
            label = "legato-scale",
            notation = "60 62 64 65 67 69 71 72",
            stepsPerCycle = 8, cycles = 2, cps = 1.0,
            voice = sine(sustain = 1.0),
            purpose = "DESIGNED TO BREAK THE ENERGY DETECTOR. patVoice's own default sustain = 1.0, " +
                "so this is one continuous tone with one attack in it. An energy detector can only " +
                "report envelope ripple here; a pitch detector sees eight notes."
        ),
        PatTranscriptionCase(
            label = "repeated-pitch",
            notation = "60 60 64 64 67 67 72 72",
            stepsPerCycle = 8, cycles = 2, cps = 1.0,
            voice = sine(sustain = 1.0),
            purpose = "DESIGNED TO BREAK THE PITCH DETECTOR, which is the other half of the argument. " +
                "Each pitch is struck twice, so half the note starts have no pitch change at them and " +
                "a pitch criterion cannot see them however it is tuned. The energy criterion can. " +
                "This is why patDetectNoteOnsets merges the two rather than preferring one."
        ),
        PatTranscriptionCase(
            label = "rests-punctuated",
            notation = "60 ~ 64 ~ 67 ~ 72 ~",
            stepsPerCycle = 8, cycles = 2, cps = 1.0,
            voice = sine(sustain = 0.9),
            purpose = "notes separated by rests, so every onset is an unvoiced-to-voiced entry and " +
                "no onset is a pitch change. Exercises the other half of the merge."
        ),
        PatTranscriptionCase(
            label = "wide-leaps",
            notation = "48 67 52 72 55 76 60 79",
            stepsPerCycle = 8, cycles = 2, cps = 1.0,
            voice = sine(sustain = 0.85),
            purpose = "leaps of more than an octave in both directions, over three octaves of range, " +
                "to check the boundary search is not tuned to small steps."
        ),
        PatTranscriptionCase(
            label = "bass-register",
            notation = "36 43 38 45 40 47 41 48",
            stepsPerCycle = 8, cycles = 2, cps = 1.0,
            voice = sine(sustain = 0.9),
            purpose = "MIDI 36 is 65 Hz, exactly the default minHz, so the analysis window is at its " +
                "longest and the time resolution at its worst. The register that costs the most."
        ),
        PatTranscriptionCase(
            label = "detuned-cents",
            notation = "60.25 62.5 64.75 67.5",
            stepsPerCycle = 4, cycles = 2, cps = 0.75,
            voice = sine(sustain = 0.85),
            purpose = "notes deliberately 25, 50, 75 and 50 cents sharp. Validates the cents axis " +
                "itself: a detector that only reported integers would score perfectly on this and " +
                "be telling us nothing."
        ),
        PatTranscriptionCase(
            label = "saw-timbre",
            notation = "60 64 67 72",
            stepsPerCycle = 4, cycles = 2, cps = 0.75,
            voice = patVoice(wave = PatWave.SAW, sustain = 0.85, decay = 0.03),
            purpose = "a full harmonic stack on one note. The control for the polyphony refusal: if " +
                "harmonic residual condemns this it is measuring richness, not chords."
        ),
        PatTranscriptionCase(
            label = "square-timbre",
            notation = "60 64 67 72",
            stepsPerCycle = 4, cycles = 2, cps = 0.75,
            voice = patVoice(wave = PatWave.SQUARE, sustain = 0.85, decay = 0.03),
            purpose = "odd harmonics only, the classic octave-error trap for autocorrelation methods."
        ),
        PatTranscriptionCase(
            label = "chord-triad",
            notation = "[60,64,67] ~ [62,65,69] ~",
            stepsPerCycle = 4, cycles = 2, cps = 0.75,
            voice = sine(sustain = 0.85),
            purpose = "MUST BE REFUSED, NOT NAMED. Three simultaneous pitches. A monophonic " +
                "detector's correct output is a declared inability, and recall of 1/3 is the " +
                "honest ceiling."
        ),
        PatTranscriptionCase(
            label = "percussive-noise",
            notation = "sd hh cp hh",
            stepsPerCycle = 4, cycles = 2, cps = 1.0,
            voice = patVoice(),
            purpose = "MUST FIND NO PITCH. patRenderStereo's drum map makes sd/hh/cp noise bursts " +
                "regardless of the voice's wave, so there is no fundamental to find and the right " +
                "answer is zero voiced frames — which is also where the energy detector wins."
        ),
        PatTranscriptionCase(
            label = "fast-line",
            notation = "60 62 64 65 67 69 71 72 72 71 69 67 65 64 62 60",
            stepsPerCycle = 16, cycles = 2, cps = 2.0,
            voice = sine(sustain = 0.9, decay = 0.01),
            purpose = "31 ms per note, told its register (min 261 Hz) so the window shrinks to fit. " +
                "This is the resolution floor being probed from the good side."
        ),
        PatTranscriptionCase(
            label = "fast-line-untold",
            notation = "60 62 64 65 67 69 71 72 72 71 69 67 65 64 62 60",
            stepsPerCycle = 16, cycles = 2, cps = 2.0,
            voice = sine(sustain = 0.9, decay = 0.01),
            tellRegister = false,
            purpose = "the same audio as fast-line, analysed with the wide default register. The " +
                "window is then longer than the notes and recall should collapse. This pair is the " +
                "whole argument for deriving the window from minHz."
        )
    )

    fun caseNamed(label: String): PatTranscriptionCase =
        CASES.firstOrNull { it.label == label } ?: error("unknown case '$label'; known: ${CASES.map { it.label }}")

    /** Renders one case through the real offline renderer and folds it to mono. */
    fun render(case: PatTranscriptionCase, sampleRate: Int = SR): FloatArray =
        patRenderStereo(case.pattern(), case.cycles, case.cps, case.voice, sampleRate).mono

    /**
     * The notes the renderer was actually asked to play.
     *
     * Mirrors `patRenderStereo`'s scheduling exactly; see the file comment for why that matters.
     * Atoms that are not numbers produce no reference note, which is how the drum case correctly
     * reports a reference of zero pitched notes.
     */
    fun referenceOf(case: PatTranscriptionCase, sampleRate: Int = SR): List<PatRefNote> {
        val secondsPerCycle = case.secondsPerCycle
        return case.pattern()
            .events(PatArc(PatQ.ZERO, PatQ.of(case.cycles)))
            .sortedWith(compareBy({ it.arc.start }, { it.arc.end }))
            .mapNotNull { event ->
                val midi = event.annotations["note"]?.toDoubleOrNull()
                    ?: event.value.trim().toDoubleOrNull()
                    ?: return@mapNotNull null
                val startSec = event.arc.start.toDouble() * secondsPerCycle
                val arcSec = (event.arc.end - event.arc.start).toDouble() * secondsPerCycle
                val hold = arcSec * case.voice.sustain.coerceIn(0.0, 1.0)
                PatRefNote(
                    startSample = (startSec * sampleRate).toInt(),
                    endSample = ((startSec + hold) * sampleRate).toInt(),
                    midi = midi
                )
            }
    }

    /**
     * Register handed to the detector for one case.
     *
     * Widened by a semitone either side of the notation's own extremes, not by an octave: the promise
     * is "this material stays inside this range", and a promise padded until it is safe is the wide
     * default with extra steps.
     */
    fun registerOf(case: PatTranscriptionCase, reference: List<PatRefNote>): Pair<Double, Double> {
        if (!case.tellRegister) return PAT_PITCH_MIN_HZ to PAT_PITCH_MAX_HZ
        val range = reference.pitchRange() ?: return PAT_PITCH_MIN_HZ to PAT_PITCH_MAX_HZ
        val lo = max(PAT_PITCH_MIN_HZ, patMidiToHz(range.start - 1.0))
        val hi = min(PAT_PITCH_MAX_HZ, patMidiToHz(range.endInclusive + 1.0))
        return if (hi > lo * 1.05) lo to hi else PAT_PITCH_MIN_HZ to PAT_PITCH_MAX_HZ
    }

    /** Renders, transcribes and scores one case, and runs the energy detector on the same buffer. */
    fun run(case: PatTranscriptionCase, sampleRate: Int = SR): PatCaseResult {
        val samples = render(case, sampleRate)
        val reference = referenceOf(case, sampleRate)
        val (minHz, maxHz) = registerOf(case, reference)
        val gap = case.stepSeconds

        val transcription = patTranscribe(
            samples, sampleRate,
            minGapSeconds = gap,
            minHz = minHz,
            maxHz = maxHz
        )
        val score = patScoreTranscription(
            label = case.label,
            reference = reference,
            recovered = transcription.notes,
            declined = transcription.declined.size,
            sampleRate = sampleRate,
            toleranceSeconds = ONSET_TOLERANCE_SECONDS
        )

        val scheduled = reference.map { it.startSample }.distinct().sorted()
        val tolerance = (ONSET_TOLERANCE_SECONDS * sampleRate).toInt()
        val energyOnsets = patDetectOnsetSamples(samples, sampleRate, gap)
        val pitchOnsets = patDetectPitchChangeSamples(samples, sampleRate, gap, transcription.frames)
        val mergedOnsets = patDetectNoteOnsets(samples, sampleRate, gap, transcription.frames)

        val closure = patCloseLoop(
            label = case.label,
            transcription = transcription,
            wave = case.voice.wave,
            decay = case.voice.decay,
            minGapSeconds = gap,
            minHz = minHz,
            maxHz = maxHz,
            sampleRate = sampleRate
        )

        return PatCaseResult(
            case = case,
            sampleRate = sampleRate,
            samples = samples,
            reference = reference,
            transcription = transcription,
            score = score,
            energyOnsets = energyOnsets,
            energyTally = patTallyOnsets(energyOnsets, scheduled, tolerance),
            energyMatch = forgiveLag(energyOnsets, scheduled, sampleRate),
            pitchOnsets = pitchOnsets,
            pitchTally = patTallyOnsets(pitchOnsets, scheduled, tolerance),
            pitchMatch = forgiveLag(pitchOnsets, scheduled, sampleRate),
            mergedOnsets = mergedOnsets,
            mergedTally = patTallyOnsets(mergedOnsets, scheduled, tolerance),
            closure = closure,
            verdict = classify(case, reference, transcription, closure, sampleRate)
        )
    }

    /**
     * Put one case in exactly one behaviour bucket.
     *
     * Wrongness is measured in **cents against the notated pitch**, so a name is only wrong when it is
     * nearer some other semitone than the one that was played. That threshold is what keeps
     * `detuned-cents` — whose notes sit exactly 50 cents from equal temperament, where no correct name
     * exists — out of [PatCaseBehaviour.CONFIDENTLY_WRONG] for an arithmetic tie it cannot win.
     *
     * `REFUSED` is checked before `CONFIDENTLY_WRONG`, and that ordering is the whole design: a case
     * that warned about what it could not name has told the reader how much to trust it, so it is not
     * confidently anything. Being incomplete and saying so is a different act from being wrong quietly.
     */
    fun classify(
        case: PatTranscriptionCase,
        reference: List<PatRefNote>,
        transcription: PatTranscription,
        closure: PatLoopClosure,
        sampleRate: Int
    ): PatCaseVerdict {
        val warnings = transcription.warnings.size
        val named = transcription.notes.sortedBy { it.startSample }

        if (named.isEmpty()) {
            return PatCaseVerdict(
                case.label, PatCaseBehaviour.REFUSED, 0, 0, reference.size, warnings,
                "named nothing; ${transcription.diagnosis.verdict}"
            )
        }

        // Pair each named note with the nearest notated onset, each spent once, then judge in cents.
        val refs = reference.sortedBy { it.startSample }.toMutableList()
        var wrong = 0
        var spurious = 0
        for (note in named) {
            val bestIndex: Int? = refs.indices.minByOrNull { i ->
                abs(refs[i].startSample.toDouble() / sampleRate - note.startSeconds)
            }
            if (bestIndex == null) { spurious++; continue }
            val ref = refs[bestIndex]
            val distance = abs(ref.startSample.toDouble() / sampleRate - note.startSeconds)
            if (distance > ONSET_TOLERANCE_SECONDS) { spurious++; continue }
            refs.removeAt(bestIndex)
            if (abs(note.measuredMidi - ref.midi) * 100.0 > 50.0) wrong++
        }
        val missing = refs.size

        val notatedCycle = case.pattern()
            .events(PatArc(PatQ.ZERO, PatQ.of(case.cycles)))
            .sortedWith(compareBy({ it.arc.start }, { it.arc.end }))
            .mapNotNull { it.value.trim().toDoubleOrNull()?.let { midi -> Math.round(midi).toInt() } }

        return when {
            // Exact means exact, and it also has to survive being rendered again.
            wrong == 0 && spurious == 0 && missing == 0 &&
                transcription.midiSequence == notatedCycle && closure.closed ->
                PatCaseVerdict(
                    case.label, PatCaseBehaviour.RECOVERED_EXACTLY, 0, 0, 0, warnings,
                    "the notated sequence, and it closes the loop"
                )

            wrong == 0 && spurious == 0 ->
                PatCaseVerdict(
                    case.label, PatCaseBehaviour.RECOVERED_WITHIN, 0, 0, missing, warnings,
                    if (missing == 0) "every named note within 50 cents"
                    else "every named note within 50 cents; $missing notated note(s) not named"
                )

            warnings > 0 ->
                PatCaseVerdict(
                    case.label, PatCaseBehaviour.REFUSED, wrong, spurious, missing, warnings,
                    "$wrong wrong and $spurious invented, but the output warns the reader"
                )

            else ->
                PatCaseVerdict(
                    case.label, PatCaseBehaviour.CONFIDENTLY_WRONG, wrong, spurious, missing, warnings,
                    "emitted pat(\"${transcription.miniNotation}\") with $wrong wrong and " +
                        "$spurious invented note(s) and NO warning"
                )
        }
    }

    /** `patMatchOnsets` in sample space: the same onsets with their median constant lag removed. */
    private fun forgiveLag(onsets: List<Int>, scheduled: List<Int>, sampleRate: Int): PatOnsetMatch =
        patMatchOnsets(
            onsets.map { it.toDouble() / sampleRate },
            scheduled.map { it.toDouble() / sampleRate },
            ONSET_TOLERANCE_SECONDS
        )

    /**
     * The no-attack legato comparison, on audio that really has no attacks in it.
     *
     * Rendered through `doughRenderMono` at Dough's own 48 kHz, which also carries the whole analysis
     * path across a sample-rate boundary — everything else in this corpus is 44.1 kHz, and a detector
     * with a rate assumption baked in would pass every other case and fail this one.
     */
    fun runLegatoProbe(
        midiSteps: List<Double?> = listOf(60.0, 62.0, 64.0, 65.0, 67.0, 69.0, 71.0, 72.0),
        stepSeconds: Double = 0.25,
        sampleRate: Int = 48_000
    ): PatLegatoProbe {
        val seconds = midiSteps.size * stepSeconds
        val samples = doughRenderMono(
            patSteppedSine(midiSteps, stepSeconds), seconds.toFloat(), sampleRate
        )
        val reference = midiSteps.mapIndexedNotNull { index, midi ->
            if (midi == null) null else PatRefNote(
                startSample = (index * stepSeconds * sampleRate).toInt(),
                endSample = ((index + 1) * stepSeconds * sampleRate).toInt(),
                midi = midi
            )
        }
        val pitched = reference.map { it.midi }
        val minHz = max(PAT_PITCH_MIN_HZ, patMidiToHz((pitched.minOrNull() ?: 60.0) - 1.0))
        val maxHz = min(PAT_PITCH_MAX_HZ, patMidiToHz((pitched.maxOrNull() ?: 72.0) + 1.0))

        val transcription = patTranscribe(
            samples, sampleRate, minGapSeconds = stepSeconds, minHz = minHz, maxHz = maxHz
        )
        val score = patScoreTranscription(
            label = "legato-dough",
            reference = reference,
            recovered = transcription.notes,
            declined = transcription.declined.size,
            sampleRate = sampleRate,
            toleranceSeconds = ONSET_TOLERANCE_SECONDS
        )
        val scheduled = reference.map { it.startSample }.distinct().sorted()
        val tolerance = (ONSET_TOLERANCE_SECONDS * sampleRate).toInt()
        val energyOnsets = patDetectOnsetSamples(samples, sampleRate, stepSeconds)
        val pitchOnsets = patDetectPitchChangeSamples(samples, sampleRate, stepSeconds, transcription.frames)
        val mergedOnsets = patDetectNoteOnsets(samples, sampleRate, stepSeconds, transcription.frames)

        return PatLegatoProbe(
            midiSteps = midiSteps,
            stepSeconds = stepSeconds,
            sampleRate = sampleRate,
            samples = samples,
            reference = reference,
            transcription = transcription,
            score = score,
            energyOnsets = energyOnsets,
            energyTally = patTallyOnsets(energyOnsets, scheduled, tolerance),
            energyMatch = forgiveLag(energyOnsets, scheduled, sampleRate),
            pitchOnsets = pitchOnsets,
            pitchTally = patTallyOnsets(pitchOnsets, scheduled, tolerance),
            pitchMatch = forgiveLag(pitchOnsets, scheduled, sampleRate),
            mergedOnsets = mergedOnsets,
            mergedTally = patTallyOnsets(mergedOnsets, scheduled, tolerance),
            closure = patCloseLoop(
                label = "legato-dough",
                transcription = transcription,
                wave = PatWave.SINE,
                decay = 0.03,
                minGapSeconds = stepSeconds,
                minHz = minHz,
                maxHz = maxHz,
                sampleRate = sampleRate
            )
        )
    }

    /** Runs every case. Around a minute of CPU; there is no cache and no shared state. */
    fun runAll(sampleRate: Int = SR): List<PatCaseResult> = CASES.map { run(it, sampleRate) }

    /** One case's onset recall when the register is estimated instead of declared. */
    class PatAutoResult(
        val label: String,
        val declared: Double,
        val auto: Double,
        val wide: Double,
        val estimated: ClosedFloatingPointRange<Double>?,
        val autoNotes: Int,
        val declaredNotes: Int
    ) {
        fun describe(): String =
            "%-18s declared %.2f  auto %.2f  wide %.2f".format(label, declared, auto, wide) +
                "  notes ${autoNotes}/${declaredNotes}" +
                (estimated?.let {
                    "  estimated ${"%.0f".format(it.start)}–${"%.0f".format(it.endInclusive)} Hz"
                } ?: "  no estimate")
    }

    /**
     * Onset recall three ways on the same audio: register declared, register estimated, register wide.
     *
     * This is the measurement that decides whether `minHz` can have a defensible default. `declared` is
     * the ceiling — the notation's own range handed straight to the detector. `wide` is the floor a
     * caller lands on today by omitting the parameter. `auto` is [patTranscribeAuto], and the question is
     * how much of the gap it closes.
     */
    fun runAuto(sampleRate: Int = SR): List<PatAutoResult> = CASES.map { case ->
        val samples = render(case, sampleRate)
        val reference = referenceOf(case, sampleRate)
        val scheduled = reference.map { it.startSample }.distinct().sorted()
        val tolerance = (ONSET_TOLERANCE_SECONDS * sampleRate).toInt()
        val gap = case.stepSeconds
        val (minHz, maxHz) = registerOf(case, reference)

        fun recallOf(t: PatTranscription): Double =
            patTallyOnsets(t.notes.map { it.startSample }, scheduled, tolerance).recall

        val declared = patTranscribe(samples, sampleRate, gap, minHz, maxHz)
        val auto = patTranscribeAuto(samples, sampleRate, gap)
        val wide = patTranscribe(samples, sampleRate, gap, PAT_PITCH_MIN_HZ, PAT_PITCH_MAX_HZ)

        PatAutoResult(
            label = case.label,
            declared = recallOf(declared),
            auto = recallOf(auto),
            wide = recallOf(wide),
            estimated = patEstimateRegister(samples, sampleRate),
            autoNotes = auto.notes.size,
            declaredNotes = declared.notes.size
        )
    }

    /** The corpus as a printable table, purposes included. */
    fun report(results: List<PatCaseResult>): String = buildString {
        appendLine("pat pitch-transcription corpus — ${results.size} cases at $SR Hz")
        appendLine("frame span at default minHz: ${patPitchFrameSpan(SR)} samples " +
            "(${"%.1f".format(1000.0 * patPitchFrameSpan(SR) / SR)} ms)")
        appendLine()
        for (result in results) {
            append(result.describe())
            appendLine("    purpose: ${result.case.purpose}")
            appendLine()
        }
        appendLine(census(results))
    }

    /**
     * The two corpus-level numbers a human should read first: what each case *did*, and how many
     * survived being rendered from their own emitted notation.
     */
    fun census(results: List<PatCaseResult>): String = buildString {
        appendLine("── behaviour ─────────────────────────────────────────────────────────────────")
        for (behaviour in PatCaseBehaviour.entries) {
            val hits = results.filter { it.verdict.behaviour == behaviour }
            appendLine("%-18s %2d  %s".format(behaviour.name, hits.size, hits.joinToString(", ") { it.case.label }))
        }
        appendLine()
        appendLine("── notation → sound → notation → sound ───────────────────────────────────────")
        for (result in results) appendLine("  " + result.closure.describe())
        val closable = results.filter {
            it.closure.verdict == PatLoopVerdict.CLOSED || it.closure.verdict == PatLoopVerdict.DIVERGED
        }
        appendLine(
            "  ${closable.count { it.closed() }} of ${closable.size} re-renderable cases close the loop" +
                "  (${results.count { it.closure.verdict == PatLoopVerdict.REFUSED }} refused, " +
                "${results.count { it.closure.verdict == PatLoopVerdict.NO_GRID }} without a grid)"
        )
    }

    /** How many re-renderable cases reproduced their own notation, and out of how many. */
    fun closureCount(results: List<PatCaseResult>): Pair<Int, Int> {
        val closable = results.filter {
            it.closure.verdict == PatLoopVerdict.CLOSED || it.closure.verdict == PatLoopVerdict.DIVERGED
        }
        return closable.count { it.closure.closed } to closable.size
    }

    private fun PatCaseResult.closed(): Boolean = closure.closed
}
