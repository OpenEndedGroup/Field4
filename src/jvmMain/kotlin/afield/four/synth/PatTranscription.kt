/*
 Recovered audio, written back out as notation Field can re-render.

 `public/docs/audio_render_analysis.md` draws the loop — notate, render, analyse, revise — and its
 last arrow, "→ revise the generating code", is drawn by hand. This file is that arrow. Given a
 rendered buffer it returns a `pat("…")` string and the `PatPattern` it parses to, so the output of
 analysis is the same kind of object as the input to synthesis and the loop closes without a human in
 the middle of it.

 Nothing in the repo could do this before: there is no mini-notation printer anywhere in Field, in
 either direction. `PatEvent.sourceSpan` can hand back the substring that *produced* an event, which
 is the opposite problem.

 **What is recoverable and what is not.** A step grid is recoverable from onsets; a *cycle* length is
 not. Eight notes a beat apart are equally well "eight steps in one cycle at 0.5 cps" and "four steps
 per cycle over two cycles at 1 cps", and no amount of listening distinguishes them, because the two
 render to identical samples. So [patTranscribe] recovers the grid honestly and then makes the one
 arbitrary choice it must — one cycle, however many steps it found — and reports the [PatTranscription.cps]
 that makes that choice sound right. The notation is faithful to the audio, not to the authoring
 decision that happened to produce it, and that distinction is the interesting part of the result
 rather than a defect in it.

 **A rest and a refusal are different, and mini-notation cannot say so.** `~` means "nothing sounds
 here". There is no token for "something sounds here and a monophonic detector will not pretend to
 name it". So a span whose harmonic residual says chord is dropped from [PatTranscription.notes] into
 [PatTranscription.declined], appears in the emitted string as `~`, and is named explicitly in
 [PatTranscription.warnings]. The emitted notation is what the detector will stand behind; the
 warnings are what it heard and would not sign.
 */
@file:afield.CompletionCategory("Audio")

package afield.four.synth

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** Largest step count [patEstimateStepGrid] will consider fitting into one render. */
const val PAT_GRID_MAX_STEPS: Int = 128

/**
 * A step grid fitted to a set of onsets.
 *
 * @param stepSeconds duration of one step.
 * @param worstResidual the furthest any onset sits from its grid line, as a fraction of a step. This
 *   is the number that says whether the grid is real: a fitted grid with a residual near 0.5 has
 *   found nothing and is reporting the average of two unrelated things.
 * @param steps how many steps span the analysed audio.
 */
data class PatStepGrid(
    val stepSeconds: Double,
    val worstResidual: Double,
    val steps: Int,
    /** The tolerance this grid was actually accepted at. See [patEstimateStepGrid]. */
    val acceptedAt: Double = 0.0
) {
    /** Cycles per second that makes one cycle of [steps] steps last the whole analysed span. */
    val cps: Double get() = if (stepSeconds <= 0.0 || steps <= 0) 1.0 else 1.0 / (steps * stepSeconds)

    fun describe(): String =
        "grid ${"%.4f".format(stepSeconds)} s/step × $steps steps " +
            "(cps ${"%.4f".format(cps)}, worst residual ${"%.3f".format(worstResidual)} step, " +
            "accepted at ${"%.2f".format(acceptedAt)})"
}

/**
 * Fit the *coarsest* step grid every onset lands on.
 *
 * Coarsest, not best-fitting: any onset set that lands on a grid also lands on that grid halved, and
 * a search minimising residual therefore runs away toward an absurdly fine grid that explains
 * everything and says nothing. So the sweep takes the largest step whose worst residual is inside
 * [tolerance] — the Occam choice, and the one that recovers "eight quarter notes" rather than "eight
 * notes somewhere in a 512th-note grid".
 *
 * The step cannot be assumed to be the smallest observed interval, because a note held over two
 * steps leaves no onset at the step between them. That is why this is a sweep and not a minimum.
 *
 * The tolerance **escalates** rather than failing: it is tried at [tolerance], then at 1.5×, 2× and
 * 3× that, and the first level to admit any grid wins. Returning null on a strict first pass is worse
 * than it sounds — it drops the notation back to unquantised, so a case whose onsets are accurate to
 * 16 ms on a 125 ms step (0.13 of a step, just over a 0.12 tolerance) emitted an unquantised string
 * and a warning while a perfectly good grid sat one percent away. [PatStepGrid.acceptedAt] records
 * which level paid for the answer, so a caller can distinguish a confident grid from a tolerated one
 * instead of being told only that there is one.
 *
 * @param tolerance worst allowed distance from a grid line, as a fraction of a step, before escalation.
 */
fun patEstimateStepGrid(
    onsetSeconds: List<Double>,
    totalSeconds: Double,
    tolerance: Double = 0.12,
    maxSteps: Int = PAT_GRID_MAX_STEPS
): PatStepGrid? {
    if (onsetSeconds.size < 2 || totalSeconds <= 0.0) return null
    val sorted = onsetSeconds.sorted()
    val intervals = sorted.zipWithNext { a, b -> b - a }.filter { it > 1e-6 }.sorted()
    if (intervals.isEmpty()) return null
    // The *median* interval, not the smallest. Anchoring the sweep on the smallest observed interval
    // lets a single spurious onset — one extra detection 40 ms after a real one — cap the search above
    // at 40 ms, so the true 125 ms grid is never even considered. Measured: `wide-leaps` emitted a
    // 48-step notation of `48 _ 67 _ _ _ 52 …` because of exactly one such pair. The median survives a
    // few bad intervals, and the sweep reaches past it because a held note makes the typical interval
    // longer than a step rather than shorter.
    val base = intervals[intervals.size / 2]
    val samplesOfSweep = 4000

    for (level in listOf(tolerance, tolerance * 1.5, tolerance * 2.0, tolerance * 3.0)) {
        if (level >= 0.5) break
        // Onsets are measured from zero, not from the first onset: the notation begins at the start of
        // the audio, and a pattern whose first step is a rest has to stay recoverable.
        var best: PatStepGrid? = null
        for (i in 0 until samplesOfSweep) {
            val step = base * (0.10 + (1.20 - 0.10) * i / (samplesOfSweep - 1.0))
            if (step <= 0.0) continue
            val steps = round(totalSeconds / step).toInt()
            if (steps < 1 || steps > maxSteps) continue
            var worst = 0.0
            for (t in sorted) {
                val units = t / step
                val residual = abs(units - round(units))
                if (residual > worst) worst = residual
            }
            if (worst <= level) {
                val candidate = PatStepGrid(step, worst, steps, level)
                if (best == null || candidate.stepSeconds > best.stepSeconds) best = candidate
            }
        }
        if (best != null) return refineStep(best, sorted, totalSeconds)
    }
    return null
}

/**
 * Re-fit the step *length* without changing the step *count*.
 *
 * The coarse sweep answers "how many steps", and it answers it by taking the largest step inside the
 * tolerance — which systematically biases the step long, because a slightly-too-long step is exactly
 * what a loosened tolerance admits. That bias then **accumulates**: it is invisible at step 1 and is
 * the whole error by step 8.
 *
 * Found by closing the fourth hop and not before. `saw-timbre` reproduced its pitch sequence perfectly
 * on re-render and still failed closure with a 54 ms onset shift, which made no sense against a
 * detector whose own worst onset error on that case is 0.5 ms. The cause was a fitted step of 0.339 s
 * against a true 0.333 s — 1.8% long, harmless per note, and 42 ms of drift by the end of the phrase.
 * A three-hop test cannot see this at all: the note *names* are unaffected.
 *
 * So the count is kept and the length is minimised for worst residual over a narrow band around it.
 */
private fun refineStep(coarse: PatStepGrid, onsets: List<Double>, totalSeconds: Double): PatStepGrid {
    if (coarse.steps <= 1) return coarse
    var best = coarse
    val samples = 2000
    for (i in 0 until samples) {
        val step = coarse.stepSeconds * (0.85 + (1.15 - 0.85) * i / (samples - 1.0))
        if (step <= 0.0) continue
        // The step count is what the coarse pass decided; only its length is up for revision here.
        if (round(totalSeconds / step).toInt() != coarse.steps) continue
        var worst = 0.0
        for (t in onsets) {
            val units = t / step
            val residual = abs(units - round(units))
            if (residual > worst) worst = residual
        }
        if (worst < best.worstResidual) {
            best = PatStepGrid(step, worst, coarse.steps, coarse.acceptedAt)
        }
    }
    return best
}

/**
 * Everything recovered from one buffer: the notes, the grid, and the notation that re-renders them.
 *
 * @param declined spans that sounded but were not named, with [PatDetectedNote.harmonicResidual]
 *   saying why. These are *not* in [notes] and appear as rests in [miniNotation].
 * @param sustainFraction median recovered note length as a fraction of a step, offered for
 *   re-rendering. Biased high and known to be: a note's measured end is the last voiced frame's far
 *   edge, which reaches into the decay, so this is an estimate rather than a measurement and is not
 *   asserted anywhere.
 */
class PatTranscription(
    val notes: List<PatDetectedNote>,
    val declined: List<PatDetectedNote>,
    val frames: List<PatF0Frame>,
    val sampleRate: Int,
    val totalSeconds: Double,
    val grid: PatStepGrid?,
    val miniNotation: String,
    val sustainFraction: Double,
    val warnings: List<String>,
    /** Why nothing was named, when nothing was. [PatPitchVerdict.PITCHED] whenever [notes] is not empty. */
    val diagnosis: PatPitchDiagnosis
) {
    /** Steps in one cycle of [miniNotation]. */
    val steps: Int get() = grid?.steps ?: 0

    /** Cycles per second at which [miniNotation] reproduces the analysed timing. */
    val cps: Double get() = grid?.cps ?: 1.0

    /** The recovered notation as a pattern. One cycle of it is the whole analysed span. */
    val pattern: PatPattern<String> get() = pat(miniNotation)

    /** MIDI numbers in time order, which is the sequence a caller usually wants to compare. */
    val midiSequence: List<Int> get() = notes.sortedBy { it.startSample }.map { it.midi }

    /** The same transcription carrying one more warning. Used by [patTranscribeAuto]. */
    internal fun withExtraWarning(warning: String): PatTranscription = PatTranscription(
        notes, declined, frames, sampleRate, totalSeconds, grid, miniNotation, sustainFraction,
        warnings + warning, diagnosis
    )

    fun describe(): String = buildString {
        append("${notes.size} note(s)")
        if (declined.isNotEmpty()) append(", ${declined.size} declined")
        append(" over ${"%.3f".format(totalSeconds)} s; ")
        append(grid?.describe() ?: "no grid fitted")
        append("\n  pat(\"$miniNotation\") at cps ${"%.4f".format(cps)}")
        if (notes.isNotEmpty()) {
            val cents = notes.map { abs(it.centsOffset) }.sorted()
            append("\n  |cents from equal temperament| median ${"%.1f".format(cents[cents.size / 2])}")
            append(", worst ${"%.1f".format(cents.last())}")
        }
        for (warning in warnings) append("\n  ! $warning")
    }
}

/**
 * Hear a buffer and write down what it played.
 *
 * @param minGapSeconds the smallest interval the material can contain, passed straight through to
 *   [patDetectPitchChangeSamples]. Left null it is derived from the buffer length as a coarse guess,
 *   which is exactly the kind of guess the rest of this package refuses to make silently — so it is
 *   reported in [PatTranscription.warnings] when it happens.
 * @param minHz lowest fundamental to admit. Setting this from what the caller knows about the
 *   material is the single largest lever on accuracy, because it sets the analysis window and
 *   therefore the shortest resolvable note. See [patPitchFrameSpan].
 */
fun patTranscribe(
    samples: FloatArray,
    sampleRate: Int,
    minGapSeconds: Double? = null,
    minHz: Double = PAT_PITCH_MIN_HZ,
    maxHz: Double = PAT_PITCH_MAX_HZ,
    hop: Int = PAT_PITCH_HOP,
    gridTolerance: Double = 0.12
): PatTranscription {
    val warnings = mutableListOf<String>()
    val totalSeconds = samples.size.toDouble() / sampleRate
    val gap = minGapSeconds ?: (totalSeconds / 32.0).also {
        warnings += "minGapSeconds was not supplied; guessed ${"%.4f".format(it)} s from the buffer " +
            "length, so the refractory gap is not the material's own"
    }

    val frames = patF0Frames(samples, sampleRate, minHz = minHz, maxHz = maxHz, hop = hop)
    if (frames.isEmpty()) {
        warnings += "the buffer is shorter than one analysis frame " +
            "(${patPitchFrameSpan(sampleRate, minHz)} samples at minHz=$minHz)"
        return PatTranscription(
            emptyList(), emptyList(), frames, sampleRate, totalSeconds, null, "~", 1.0, warnings,
            PatPitchDiagnosis(PatPitchVerdict.SILENT, 0, 0, 0)
        )
    }

    val boundaries = patDetectPitchChangeSamples(samples, sampleRate, gap, frames)
    val all = patSegmentNotes(samples, sampleRate, gap, frames, boundaries)
    val declined = all.filter { it.notOneNote }
    val notes = all.filterNot { it.notOneNote }
    val diagnosis = patDiagnoseUnpitched(samples, sampleRate, frames, namedNotes = notes.size)

    if (declined.isNotEmpty()) {
        warnings += "declined to name ${declined.size} of ${all.size} span(s): harmonic residual " +
            declined.joinToString(", ") {
                "${"%.2f".format(it.harmonicResidual)}@${"%.3f".format(it.startSeconds)}s"
            } +
            " — each is not one note at the pitch estimated for it, either because more than one " +
            "pitch is sounding or because the estimate straddles two. They appear as rests in the " +
            "notation"
    }
    if (notes.isEmpty()) {
        // The one case that must never be silent. Returning `pat("~")` with no explanation would be
        // a detector reporting "there was nothing there" about audio it simply could not read.
        warnings += "named no notes at all — ${diagnosis.describe()}"
    }
    // Self-knowledge, available with no ground truth at all: more onsets were located than notes were
    // named, so the notation below is missing notes this detector itself heard.
    //
    // This warning is the whole difference between being wrong and being *confidently* wrong, and it
    // exists because the corpus measured the gap. `legato-scale` emitted a plausible, parseable and
    // incorrect `pat("60 62 65 _ 67 69 71 72 …")` — one note missing, one name 65 cents out — carrying
    // no warning of any kind, so a reader had nothing to go on. A transcriber cannot know that it was
    // wrong. It can always know that it declined to name something it detected, and saying so is what
    // separates "this is the tune" from "this is most of the tune".
    if (boundaries.size > all.size) {
        warnings += "located ${boundaries.size} onset(s) but named ${all.size}: " +
            "${boundaries.size - all.size} span(s) held no pitch steadily enough to name, so the " +
            "notation below is missing notes that were heard — incomplete, not merely imprecise"
    }

    val grid = patEstimateStepGrid(
        notes.map { it.startSeconds }, totalSeconds, tolerance = gridTolerance
    )
    if (grid == null && notes.size >= 2) {
        warnings += "no step grid explained all ${notes.size} onsets even at " +
            "${"%.2f".format(gridTolerance * 3.0)} of a step, so the notation below is unquantised " +
            "and its timing is only as good as the detector's"
    } else if (grid != null && grid.acceptedAt > gridTolerance) {
        warnings += "the step grid needed a tolerance of ${"%.2f".format(grid.acceptedAt)} of a step " +
            "rather than ${"%.2f".format(gridTolerance)} (worst residual " +
            "${"%.3f".format(grid.worstResidual)}), so the quantisation below is tolerated rather " +
            "than confident"
    }

    val sustain = if (grid == null || notes.isEmpty()) 1.0 else {
        val fractions = notes.map { ((it.endSeconds - it.startSeconds) / grid.stepSeconds).coerceIn(0.0, 1.0) }
            .sorted()
        fractions[fractions.size / 2]
    }
    val notation = patNotationOf(notes, grid)

    // The other half of "wrong" versus "confidently wrong", and the one that is actually hard.
    //
    // A note occupying more than one grid step, in a piece where the typical note occupies one, is
    // exactly where two notes were heard as one. `legato-scale` emitted
    // `pat("60 62 65 _ 67 69 71 72 …")` — 64 and 65 merged into a two-step `65 _`, one name 65 cents
    // out — and every other self-check passed it: nothing was declined, the grid fitted, and as many
    // notes were named as onsets were found. It was the only case in the corpus to emit a plausible,
    // parseable, wrong notation with no warning at all, which is the worst thing this file can do.
    //
    // The detector cannot know which name is wrong. It can see that it wrote a held note into an
    // otherwise even grid, and that is enough to tell the reader where to look.
    if (grid != null && notes.size >= 3) {
        val stepsPerNote = notes.map { note ->
            val from = round(note.startSeconds / grid.stepSeconds).toInt()
            val to = round(note.endSeconds / grid.stepSeconds).toInt()
            max(1, to - from)
        }
        val held = stepsPerNote.count { it > 1 }
        val single = stepsPerNote.count { it == 1 }
        if (held > 0 && single > held) {
            warnings += "$held of ${notes.size} named note(s) span more than one step in a grid whose " +
                "other ${single} notes span exactly one. A held note in an even grid is where two " +
                "notes are heard as one, so those names are the least trustworthy in the notation"
        }
    }

    return PatTranscription(
        notes = notes,
        declined = declined,
        frames = frames,
        sampleRate = sampleRate,
        totalSeconds = totalSeconds,
        grid = grid,
        miniNotation = notation,
        sustainFraction = sustain,
        warnings = warnings,
        diagnosis = diagnosis
    )
}

/**
 * Write [notes] out as one cycle of mini-notation on [grid].
 *
 * `~` is an empty step and `_` extends the step before it, which is how a note held across two steps
 * is spelled (`TidalPatterns.kt:1146` — `bd _ _ sd` is `bd@3 sd`). A held note is emitted as its
 * pitch followed by `_` per extra step rather than as `@n`, because `@` re-weights the step and would
 * change where every later note falls; `_` occupies a slot and leaves the grid alone.
 *
 * Pitches are emitted as integers. The cents each note was measured at are in
 * [PatDetectedNote.centsOffset] and are deliberately *not* written into the notation: a fractional
 * atom would re-render the detector's own error as if it were the composer's intent.
 */
fun patNotationOf(notes: List<PatDetectedNote>, grid: PatStepGrid?): String {
    if (grid == null || grid.steps <= 0) {
        if (notes.isEmpty()) return "~"
        return notes.sortedBy { it.startSample }.joinToString(" ") { it.midi.toString() }
    }
    val slots = MutableList(grid.steps) { "~" }
    for (note in notes.sortedBy { it.startSample }) {
        val first = round(note.startSeconds / grid.stepSeconds).toInt()
        if (first !in slots.indices) continue
        if (slots[first] != "~") continue
        slots[first] = note.midi.toString()
        val last = round(note.endSeconds / grid.stepSeconds).toInt().coerceAtMost(grid.steps)
        for (i in (first + 1) until min(last, grid.steps)) {
            if (slots[i] == "~") slots[i] = "_" else break
        }
    }
    // A leading `_` has nothing to extend and the parser would have to guess; make it a rest.
    if (slots.isNotEmpty() && slots[0] == "_") slots[0] = "~"
    return slots.joinToString(" ")
}

/**
 * Re-render a transcription through the same offline path that produced the audio it heard.
 *
 * This is the round trip stated as one call: [patTranscribe] then this returns audio that should
 * agree with the audio the transcription was made from. `PatPitchTranscriptionTest` asserts that
 * transcribing *this* buffer recovers the same notes again, which is the closure property that makes
 * the notation trustworthy rather than merely plausible.
 */
fun patRenderTranscription(
    transcription: PatTranscription,
    wave: PatWave = PatWave.SINE,
    decay: Double = 0.03,
    sampleRate: Int = PatSoundDocs.SR
): FloatArray {
    val voice = patVoice(
        wave = wave,
        decay = decay,
        sustain = transcription.sustainFraction.coerceIn(0.05, 1.0)
    )
    val audio = patRenderStereo(
        transcription.pattern,
        cycles = 1,
        cps = transcription.cps,
        voice = voice,
        sampleRate = sampleRate
    )
    return audio.mono
}

/**
 * Guess the register from the audio, so a caller who cannot declare one is not silently punished.
 *
 * `minHz` sets the analysis window and therefore the shortest resolvable note, so a caller who omits it
 * lands on a 46 ms window and, on a fast line, on onset recall 0.03. That is a correct measurement and
 * an indefensible default: the first thing anyone does is call the function without telling it anything.
 *
 * The way out rests on a distinction the corpus made visible. A window that is too long ruins
 * *segmentation* but not *estimation* — on `fast-line-untold` the wide pass left 329 of 329 frames
 * voiced and its F0 trace followed the tune correctly; the frames knew the pitch and only the note
 * boundaries were lost. So a wide first pass is a poor transcriber and a perfectly good histogram, and
 * one cheap pass can buy the second pass its window.
 *
 * Deliberately asymmetric in what it is willing to get wrong. The low bound comes from a low
 * *percentile* rather than the minimum, and is then dropped a further semitone. An estimate that is too
 * low only costs sharpness; an estimate that is too high makes real low notes unvoiced and deletes them.
 * Octave-down errors in the first pass therefore push this in the safe direction, which is why the
 * cheap statistic is adequate.
 *
 * @param hop coarser than the analysis default on purpose: this pass needs a distribution, not timing,
 *   so it pays a quarter of the frames.
 * @return the register to hand [patTranscribe], or null when too little was voiced to have an opinion.
 */
fun patEstimateRegister(
    samples: FloatArray,
    sampleRate: Int,
    minHz: Double = PAT_PITCH_MIN_HZ,
    maxHz: Double = PAT_PITCH_MAX_HZ,
    hop: Int = PAT_PITCH_HOP * 4,
    lowPercentile: Double = 0.02,
    highPercentile: Double = 0.98,
    minVoicedFraction: Double = 0.10,
    frames: List<PatF0Frame> = patF0Frames(samples, sampleRate, minHz = minHz, maxHz = maxHz, hop = hop)
): ClosedFloatingPointRange<Double>? {
    if (frames.isEmpty()) return null
    val voiced = frames.filter { it.voiced && it.hz.isFinite() && it.hz > 0.0 }
    if (voiced.size < 4 || voiced.size < minVoicedFraction * frames.size) return null

    val sorted = voiced.map { it.hz }.sorted()
    fun percentile(q: Double): Double =
        sorted[((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)]

    val semitone = 1.0594630943592953
    val lo = (percentile(lowPercentile) / semitone).coerceAtLeast(minHz)
    val hi = (percentile(highPercentile) * semitone).coerceAtMost(maxHz)
    if (hi <= lo * 1.05) return null
    return lo..hi
}

/**
 * [patTranscribe] with the register estimated from the audio instead of declared.
 *
 * Two passes: [patEstimateRegister] over a wide range to pick the window, then the real transcription
 * inside it. What this recovers of the declared-register result is measured on the corpus by
 * `PatTranscriptionCorpus.runAuto`, and reported rather than assumed — if it did not work this function
 * would still exist, loudly warning instead of quietly guessing.
 *
 * The estimate is always recorded in [PatTranscription.warnings], because a register that was guessed
 * is a different kind of claim from one that was promised, and the caller should be able to tell.
 */
fun patTranscribeAuto(
    samples: FloatArray,
    sampleRate: Int,
    minGapSeconds: Double? = null,
    hop: Int = PAT_PITCH_HOP,
    gridTolerance: Double = 0.12
): PatTranscription {
    fun wideWith(warning: String): PatTranscription = patTranscribe(
        samples, sampleRate, minGapSeconds = minGapSeconds,
        minHz = PAT_PITCH_MIN_HZ, maxHz = PAT_PITCH_MAX_HZ, hop = hop, gridTolerance = gridTolerance
    ).withExtraWarning(warning)

    // One coarse wide pass, used for two decisions: whether narrowing is safe, and what to narrow to.
    val coarse = patF0Frames(
        samples, sampleRate,
        minHz = PAT_PITCH_MIN_HZ, maxHz = PAT_PITCH_MAX_HZ, hop = PAT_PITCH_HOP * 4
    )
    val firstOpinion = patDiagnoseUnpitched(samples, sampleRate, coarse, namedNotes = 0)

    // The gate is the *fraction* of loud frames that are aperiodic, not [patDiagnoseUnpitched]'s
    // verdict. Using the verdict was the second wrong version of this guard: that function exists to
    // explain why nothing was named and assumes nothing was, so on perfectly nameable material it finds
    // the two frames straddling a note boundary, counts a handful of spectral peaks there, and returns
    // POLYPHONIC. Gating on it refused five clean cases — saw, square, detuned, rests and bass all fell
    // to recall 0.00. The fraction separates the corpus cleanly and by a wide margin: 0.86 for the
    // triad and 1.00 for the noise train, against 0.18 for the worst monophonic case.
    val loudFrames = firstOpinion.loudFrames
    val aperiodicFraction =
        if (loudFrames == 0) 1.0 else firstOpinion.loudUnvoicedFrames.toDouble() / loudFrames

    // The guard, and it is not hypothetical: without it this function turned a correct refusal into
    // five confidently-named notes. On `chord-triad` the wide pass leaves a triad mostly unvoiced, the
    // few voiced frames are octave-error debris, and the estimated register came out 65–77 Hz — three
    // semitones wide and nowhere near the music. Narrowing to it produced a short window, a stable-
    // looking run, and five invented notes at onset recall 0.75, replacing an honest POLYPHONIC. So a
    // first pass that already says "this is not one line" is believed, and the register is left wide.
    if (loudFrames == 0 || aperiodicFraction >= 0.5) {
        // Refuse outright rather than fall back to the wide default, which was the first version of
        // this guard and was not enough. With the wide register `chord-triad` does not refuse — it
        // names five notes — so "keep the wide default and let the refusal stand" preserved a refusal
        // that was not there. A caller who cannot declare a register and hands in material the first
        // pass already calls polyphonic gets a refusal, which is the only answer this function can
        // defend.
        return PatTranscription(
            notes = emptyList(),
            declined = emptyList(),
            frames = coarse,
            sampleRate = sampleRate,
            totalSeconds = samples.size.toDouble() / sampleRate,
            grid = null,
            miniNotation = "~",
            sustainFraction = 1.0,
            warnings = listOf(
                "refusing to transcribe without a declared register: " +
                    "${"%.0f".format(aperiodicFraction * 100)}% of the loud frames in a coarse first " +
                    "pass are aperiodic, so this is not one monophonic line — " +
                    firstOpinion.describe() + ". Estimating a monophonic register from material that " +
                    "is not monophonic invents notes, and the wide default does not refuse this " +
                    "material either. Pass minHz/maxHz explicitly if you know this is one line"
            ),
            diagnosis = firstOpinion
        )
    }

    val register = patEstimateRegister(samples, sampleRate, frames = coarse)

    // Is the register's floor a note somebody played, or a fundamental the chord merely implies?
    // See [patEnergyAtF0]: a 4:5:6 triad is genuinely periodic two octaves below anything sounding.
    if (register != null) {
        val loudest = coarse.filter { it.voiced }.maxByOrNull { it.rms }
        // Tested at the pitch the detector actually reported for its loudest voiced frame — NOT at
        // `register.start`, which is a semitone below the lowest note by construction and therefore has
        // no energy in any recording ever made. Testing the floor refused eleven of thirteen cases.
        val energyAtFloor = if (loudest == null) 1.0 else patEnergyAtF0(
            samples,
            start = loudest.startSample,
            // Twelve periods of the reported pitch: long enough to resolve it, short enough to stay
            // inside one note. Passing "up to a second" instead centred the transform half a second
            // past the frame it was supposed to be about, which refused eleven of thirteen cases.
            length = min(
                samples.size - loudest.startSample,
                max(2048, (12.0 * sampleRate / loudest.hz.coerceAtLeast(20.0)).toInt())
            ),
            sampleRate = sampleRate,
            f0 = loudest.hz
        )
        if (energyAtFloor < 0.05) {
            return PatTranscription(
                notes = emptyList(),
                declined = emptyList(),
                frames = coarse,
                sampleRate = sampleRate,
                totalSeconds = samples.size.toDouble() / sampleRate,
                grid = null,
                miniNotation = "~",
                sustainFraction = 1.0,
                warnings = listOf(
                    "refusing to transcribe without a declared register: the loudest voiced frame " +
                        "reports ${"%.0f".format(loudest?.hz ?: 0.0)} Hz, which carries only " +
                        "${"%.1f".format(energyAtFloor * 100)}% of the loudest peak's magnitude, so it " +
                        "is a fundamental this material implies rather than one it plays — the " +
                        "signature of a chord heard as its own virtual root. Pass minHz/maxHz " +
                        "explicitly if you know this is one line"
                ),
                diagnosis = firstOpinion
            )
        }
    }

    if (register == null) {
        return wideWith(
            "could not estimate a register — too little of this buffer was voiced — so the wide " +
                "default ${PAT_PITCH_MIN_HZ.toInt()}–${PAT_PITCH_MAX_HZ.toInt()} Hz was used, and the " +
                "analysis window is at its longest (${patPitchFrameSpan(sampleRate, PAT_PITCH_MIN_HZ)} samples)"
        )
    }
    val transcription = patTranscribe(
        samples, sampleRate, minGapSeconds = minGapSeconds,
        minHz = register.start, maxHz = register.endInclusive, hop = hop, gridTolerance = gridTolerance
    )
    return transcription.withExtraWarning(
        "register was estimated from the audio, not declared: " +
            "${"%.0f".format(register.start)}–${"%.0f".format(register.endInclusive)} Hz, " +
            "giving a ${patPitchFrameSpan(sampleRate, register.start)}-sample window " +
            "(${"%.1f".format(1000.0 * patPitchFrameSpan(sampleRate, register.start) / sampleRate)} ms)"
    )
}

/** What happened when the emitted notation was rendered again and listened to again. */
enum class PatLoopVerdict {
    /** The second render was heard as the same notes, within a tolerance derived from the grid. */
    CLOSED,

    /** The emitted notation parsed and rendered, and produced different notes. The interesting case. */
    DIVERGED,

    /** Nothing was named, so there is no notation to re-render. Not a failure and not a success. */
    REFUSED,

    /** Notes were named but no grid was fitted, so the notation is unquantised and not re-renderable. */
    NO_GRID
}

/**
 * One full turn of notation → sound → notation → **sound**.
 *
 * The three-hop claim ("the recovered integers equal the notated integers") is weaker than it looks,
 * because it compares a list against a list and never asks whether the *string* means what the list
 * meant. A `pat("…")` that parses, renders, and sounds like something else passes it. This closes the
 * fourth hop: the emitted notation is handed back to `patRenderStereo` at the `cps` the transcription
 * reported, the second buffer is analysed by the same detector, and the two note sets are compared.
 * Every hop is a real Field code path.
 *
 * **The criterion is sequence reproduction, and the timing bound is half a step.** Both of those are
 * second attempts, and the first attempt is worth recording because it was a trap.
 *
 * Originally closure required the onset shift to sit inside `worstResidual · stepSeconds + 2 · hop`,
 * on the reasoning that emission quantises onsets onto the grid so the grid's own reported residual
 * bounds the displacement. That formula is **self-defeating**: fixing the grid fit (see [refineStep])
 * cut the real onset shift on `saw-timbre` from 54 ms to 19 ms and simultaneously cut the allowance
 * from 46 ms to 6 ms, because the allowance was mostly made of the error being removed. Accuracy
 * improved and the count of closing cases fell from 3 to 1. A tolerance that tightens as the thing it
 * measures improves is not a tolerance.
 *
 * The quantisation term was also never the dominant one. `|t2 − t1| ≤ |g − t1| + |t2 − g|`, and the
 * second term — the detector's error on the *second* render — is the big one, is 8–29 ms on this
 * corpus, and cannot be predicted from the first render because the re-render's articulation differs.
 *
 * So: closure asks whether the second hearing produced **the same MIDI sequence**, which is what "the
 * loop closed" means musically and which no choice of tolerance can soften, plus whether every note
 * still lands on its own step — a bound of `stepSeconds / 2`, derived from the grid rather than picked,
 * because past half a step a note quantises to a different step and the notation is no longer
 * re-derivable. The grid-quantisation figure is still computed and reported as
 * [quantisationBound], now as an observation rather than a gate.
 *
 * @param centsShift worst pitch disagreement between the two hearings, over notes that paired up.
 *   `NaN` when the sequence differs, because pairing across a dropped note compares unrelated notes and
 *   the resulting "1199 cents" says nothing about pitch accuracy.
 */
data class PatLoopClosure(
    val label: String,
    val verdict: PatLoopVerdict,
    val firstNotes: Int,
    val secondNotes: Int,
    val firstMidi: List<Int>,
    val secondMidi: List<Int>,
    val worstOnsetShift: Double,
    val centsShift: Double,
    val toleranceSeconds: Double,
    /** What the grid's own quantisation would have allowed. Reported, not a gate. See [patCloseLoop]. */
    val quantisationBound: Double,
    val unpaired: Int,
    val why: String
) {
    val closed: Boolean get() = verdict == PatLoopVerdict.CLOSED

    fun describe(): String = buildString {
        append("%-18s".format(label))
        append(" %-9s".format(verdict.name))
        append(" ${firstNotes}→${secondNotes} notes")
        if (verdict == PatLoopVerdict.CLOSED || verdict == PatLoopVerdict.DIVERGED) {
            append("  onset shift ${"%.1f".format(worstOnsetShift * 1000)} ms")
            append(" of ${"%.1f".format(toleranceSeconds * 1000)} ms (grid quantisation was ")
            append("${"%.1f".format(quantisationBound * 1000)} ms)")
            if (centsShift.isFinite()) append("  cents shift ${"%.1f".format(centsShift)}")
        }
        if (why.isNotEmpty()) append("  — $why")
    }
}

/**
 * Render [transcription]'s own notation again and listen to it again.
 *
 * The comparison is against what the detector *heard the first time*, not against the notation that
 * generated the original audio. That is deliberate and it is the only way this measures the emitter:
 * scoring the second hearing against the original notation would fold the detector's accuracy back
 * into the answer and a divergence caused purely by a bad string would be indistinguishable from one
 * caused by a hard render.
 *
 * @param wave the timbre to re-render with. Passing the original case's wave keeps the second hop a
 *   test of the notation rather than a test of whether the detector survives a timbre change.
 */
fun patCloseLoop(
    label: String,
    transcription: PatTranscription,
    wave: PatWave = PatWave.SINE,
    decay: Double = 0.03,
    minGapSeconds: Double? = null,
    minHz: Double = PAT_PITCH_MIN_HZ,
    maxHz: Double = PAT_PITCH_MAX_HZ,
    sampleRate: Int = PatSoundDocs.SR,
    hop: Int = PAT_PITCH_HOP
): PatLoopClosure {
    val firstMidi = transcription.midiSequence
    val empty = emptyList<Int>()

    if (transcription.notes.isEmpty()) {
        return PatLoopClosure(
            label, PatLoopVerdict.REFUSED, 0, 0, empty, empty, 0.0, Double.NaN, 0.0, 0.0, 0,
            "nothing was named, so there is no notation to render again"
        )
    }
    val grid = transcription.grid
        ?: return PatLoopClosure(
            label, PatLoopVerdict.NO_GRID, firstMidi.size, 0, firstMidi, empty, 0.0, Double.NaN, 0.0, 0.0, 0,
            "no grid was fitted, so the emitted notation carries no timing to reproduce"
        )

    val second = patRenderTranscription(transcription, wave = wave, decay = decay, sampleRate = sampleRate)
    val gap = minGapSeconds ?: grid.stepSeconds
    val reheard = patTranscribe(
        second, sampleRate, minGapSeconds = gap, minHz = minHz, maxHz = maxHz, hop = hop
    )
    val secondMidi = reheard.midiSequence

    // Reported, not a gate — see the KDoc for why this made a bad criterion.
    val quantisationBound = grid.worstResidual * grid.stepSeconds + 2.0 * hop / sampleRate
    // The gate: a note must still land on its own step, or the notation is not re-derivable.
    val tolerance = grid.stepSeconds / 2.0

    // Pair the two hearings by nearest onset, each spent once, and **only within one step**. Pairing
    // without a bound was the first version and it lied loudly: when the second hearing loses a note,
    // every later note pairs with its neighbour and the cascade reports a "shift" of 386 ms and a
    // "cents shift" of 1100 on audio whose real disagreement is one dropped note. Those are the same
    // off-by-one cascade that made segmentation report 1700 cents earlier in this branch. An unpairable
    // note is now counted as unpaired, which is the honest description of it.
    val pairingBound = grid.stepSeconds
    val firstNotes = transcription.notes.sortedBy { it.startSample }
    val secondNotes = reheard.notes.sortedBy { it.startSample }
    val used = BooleanArray(secondNotes.size)
    var worstShift = 0.0
    var worstCents = 0.0
    var paired = 0
    var unpaired = 0
    for (note in firstNotes) {
        var bestIndex = -1
        var bestDistance = Double.MAX_VALUE
        for ((i, other) in secondNotes.withIndex()) {
            if (used[i]) continue
            val distance = abs(other.startSeconds - note.startSeconds)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = i
            }
        }
        if (bestIndex < 0 || bestDistance > pairingBound) {
            unpaired++
            continue
        }
        used[bestIndex] = true
        paired++
        if (bestDistance > worstShift) worstShift = bestDistance
        val cents = abs(secondNotes[bestIndex].measuredMidi - note.measuredMidi) * 100.0
        if (cents > worstCents) worstCents = cents
    }

    val reasons = mutableListOf<String>()
    if (secondMidi != firstMidi) {
        reasons += "the second hearing is a different sequence (${firstMidi.size} vs ${secondMidi.size} notes)"
    }
    if (unpaired > 0) reasons += "$unpaired note(s) had no counterpart within one step"
    if (worstShift > tolerance) {
        reasons += "worst onset shift ${"%.1f".format(worstShift * 1000)} ms exceeds half a step " +
            "(${"%.1f".format(tolerance * 1000)} ms), so a note moved to a different step"
    }
    if (paired == 0) reasons += "no note paired up at all"

    return PatLoopClosure(
        label = label,
        verdict = if (reasons.isEmpty()) PatLoopVerdict.CLOSED else PatLoopVerdict.DIVERGED,
        firstNotes = firstMidi.size,
        secondNotes = secondMidi.size,
        firstMidi = firstMidi,
        secondMidi = secondMidi,
        worstOnsetShift = worstShift,
        // Only meaningful when the sequence matched; otherwise pairing compares unrelated notes.
        centsShift = if (paired == 0 || secondMidi != firstMidi) Double.NaN else worstCents,
        toleranceSeconds = tolerance,
        quantisationBound = quantisationBound,
        unpaired = unpaired,
        why = reasons.joinToString("; ")
    )
}

/** Reference note, as the notation that produced the audio says it should be. */
data class PatRefNote(
    val startSample: Int,
    val endSample: Int,
    val midi: Double
)

/**
 * How a transcription compared with the notation that generated the audio.
 *
 * Cents error is reported as median, 90th percentile and worst, over a stated count, because a
 * single averaged figure over a set containing one octave error is not a measurement of anything —
 * it is a number between the thing that worked and the thing that did not.
 *
 * @param octaveErrors matched notes whose pitch is wrong by a whole number of octaves. Counted apart
 *   from other pitch errors because they are a different failure with a different cause: YIN picking
 *   a sub-harmonic dip, not the estimator being imprecise.
 */
data class PatTranscriptionScore(
    val label: String,
    val reference: Int,
    val recovered: Int,
    val declined: Int,
    val onsetMatched: Int,
    val pitchCorrect: Int,
    val octaveErrors: Int,
    val onsetPrecision: Double,
    val onsetRecall: Double,
    val noteF: Double,
    val centsMedian: Double,
    val centsP90: Double,
    val centsWorst: Double,
    val onsetMedianMs: Double,
    val onsetWorstMs: Double
) {
    fun describe(): String = buildString {
        append("%-18s".format(label))
        append(" ref %2d".format(reference))
        append(" got %2d".format(recovered))
        if (declined > 0) append(" (declined %d)".format(declined)) else append("            ")
        append("  onset P/R %.2f/%.2f".format(onsetPrecision, onsetRecall))
        append("  noteF %.2f".format(noteF))
        append("  cents med/p90/worst ")
        append(
            if (onsetMatched == 0) "     —/—/—    "
            else "%5.1f/%5.1f/%5.1f".format(centsMedian, centsP90, centsWorst)
        )
        append("  onset med/worst ")
        append(
            if (onsetMatched == 0) "  —/—  "
            else "%5.1f/%5.1f ms".format(onsetMedianMs, onsetWorstMs)
        )
        if (octaveErrors > 0) append("  OCTAVE×$octaveErrors")
    }
}

/**
 * Score [recovered] against the notation's own [reference].
 *
 * Matching is nearest-onset-within-[toleranceSeconds], each recovered note used at most once, walked
 * in reference order. A recovered note matched on time but wrong in pitch counts for onset recall and
 * against note-level F — the two questions are kept separate on purpose, because the interesting
 * failures in this package are the ones where timing is right and pitch is not, and a single combined
 * score hides exactly those.
 *
 * No constant offset is removed, unlike [patMatchOnsets]. That is a deliberate difference: this
 * compares an offline render against the schedule that generated it inside one process, so there is
 * no recording latency to forgive, and a systematic lag here would be a real bug in the detector's
 * localisation rather than a property of a sound card.
 */
fun patScoreTranscription(
    label: String,
    reference: List<PatRefNote>,
    recovered: List<PatDetectedNote>,
    declined: Int,
    sampleRate: Int,
    toleranceSeconds: Double
): PatTranscriptionScore {
    val used = BooleanArray(recovered.size)
    val onsetErrors = mutableListOf<Double>()
    val centsErrors = mutableListOf<Double>()
    var pitchCorrect = 0
    var octaves = 0

    for (ref in reference.sortedBy { it.startSample }) {
        val refStart = ref.startSample.toDouble() / sampleRate
        var bestIndex = -1
        var bestDistance = Double.MAX_VALUE
        for ((i, note) in recovered.withIndex()) {
            if (used[i]) continue
            val distance = abs(note.startSeconds - refStart)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = i
            }
        }
        if (bestIndex < 0 || bestDistance > toleranceSeconds) continue
        used[bestIndex] = true
        val note = recovered[bestIndex]
        onsetErrors += bestDistance
        val cents = (note.measuredMidi - ref.midi) * 100.0
        centsErrors += abs(cents)
        val wanted = round(ref.midi).toInt()
        if (note.midi == wanted) {
            pitchCorrect++
        } else if ((note.midi - wanted) % 12 == 0) {
            octaves++
        }
    }

    val matched = onsetErrors.size
    val precision = if (recovered.isEmpty()) 0.0 else matched.toDouble() / recovered.size
    val recall = if (reference.isEmpty()) 0.0 else matched.toDouble() / reference.size
    val pitchPrecision = if (recovered.isEmpty()) 0.0 else pitchCorrect.toDouble() / recovered.size
    val pitchRecall = if (reference.isEmpty()) 0.0 else pitchCorrect.toDouble() / reference.size
    val f = if (pitchPrecision + pitchRecall <= 0.0) 0.0 else
        2.0 * pitchPrecision * pitchRecall / (pitchPrecision + pitchRecall)

    val centsSorted = centsErrors.sorted()
    val onsetSorted = onsetErrors.sorted()
    fun percentile(values: List<Double>, q: Double): Double =
        if (values.isEmpty()) Double.NaN
        else values[((values.size - 1) * q).toInt().coerceIn(0, values.size - 1)]

    return PatTranscriptionScore(
        label = label,
        reference = reference.size,
        recovered = recovered.size,
        declined = declined,
        onsetMatched = matched,
        pitchCorrect = pitchCorrect,
        octaveErrors = octaves,
        onsetPrecision = precision,
        onsetRecall = recall,
        noteF = f,
        centsMedian = percentile(centsSorted, 0.5),
        centsP90 = percentile(centsSorted, 0.9),
        centsWorst = centsSorted.lastOrNull() ?: Double.NaN,
        onsetMedianMs = percentile(onsetSorted, 0.5) * 1000.0,
        onsetWorstMs = (onsetSorted.lastOrNull() ?: Double.NaN) * 1000.0
    )
}

/**
 * How many of [scheduledSamples] some onset in [observedSamples] landed on.
 *
 * This exists because `patMatchOnsets` answers a subtly different question and reusing it here would
 * have produced a flattering number. Its recall denominator is `scheduled.filter { it <= train.last()
 * + tolerance }` (`PatOnsetDetection.kt:171`) — the schedule *up to the last observed onset* — which is
 * exactly right for its own job, comparing a recording that may have been cut short. It is wrong for
 * measuring a detector that stopped finding things: a detector that reports one onset out of 32 and
 * then gives up scores **recall 1.00** under that denominator, and this branch observed precisely that
 * on `fast-line-untold` before the two were separated. Here the denominator is the whole schedule.
 *
 * Also unlike `patMatchOnsets`, no constant offset is removed; see [patScoreTranscription].
 */
data class PatOnsetTally(val observed: Int, val scheduled: Int, val matched: Int) {
    val recall: Double get() = if (scheduled == 0) 0.0 else matched.toDouble() / scheduled
    val precision: Double get() = if (observed == 0) 0.0 else matched.toDouble() / observed

    fun describe(label: String): String =
        "$label: $matched/$scheduled matched from $observed observed " +
            "(P ${"%.2f".format(precision)}, R ${"%.2f".format(recall)})"
}

/** Greedy nearest-within-tolerance tally, each observed onset spent at most once. */
fun patTallyOnsets(
    observedSamples: List<Int>,
    scheduledSamples: List<Int>,
    toleranceSamples: Int
): PatOnsetTally {
    val used = BooleanArray(observedSamples.size)
    var matched = 0
    for (want in scheduledSamples.sorted()) {
        var bestIndex = -1
        var bestDistance = Int.MAX_VALUE
        for ((i, at) in observedSamples.withIndex()) {
            if (used[i]) continue
            val distance = abs(at - want)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = i
            }
        }
        if (bestIndex >= 0 && bestDistance <= toleranceSamples) {
            used[bestIndex] = true
            matched++
        }
    }
    return PatOnsetTally(observedSamples.size, scheduledSamples.size, matched)
}

/** Largest [PatRefNote] pitch in a reference set, or null when nothing is pitched. */
internal fun List<PatRefNote>.pitchRange(): ClosedFloatingPointRange<Double>? {
    if (isEmpty()) return null
    val lo = minOf { it.midi }
    val hi = maxOf { it.midi }
    return lo..max(hi, lo + 1e-9)
}
