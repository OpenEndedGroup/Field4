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
        if (best != null) return best
    }
    return null
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
