/**
 * What pitch a rendered pattern is actually sounding, measured from the audio.
 *
 * `PatOnsetDetection` answers *when*. This answers *what*, and the two are not the same question with
 * a different threshold. An energy detector reads the amplitude envelope, so it can only see a note
 * that begins with an attack; a pitch detector reads periodicity, so it can see a note that begins
 * while the previous one is still sounding at full level. That difference is not a nicety. `patVoice`
 * defaults to `sustain = 1.0` (`PatSoundDocs.kt:747`), which means the *default* render of a gapless
 * melodic sequence is one continuous tone with exactly one attack in it, and an energy detector cannot
 * segment it however it is tuned — not because it is badly configured but because the evidence it
 * consumes is not in the signal. [patDetectPitchChangeSamples] is the complement, and
 * `PatPitchTranscriptionTest` measures the pair on the same render so the claim is a number.
 *
 * The estimator is YIN, the cumulative-mean-normalised difference function, computed directly in the
 * time domain. `Spectral.kt:26` has a real FFT and an autocorrelation could be had from it for less
 * arithmetic, but YIN's threshold and its aperiodicity confidence are both defined on the difference
 * function, so keeping those constants over an autocorrelation would be taking the numbers without
 * the derivation they come from. At these corpus sizes the direct form costs seconds.
 *
 * Three things here decide what the detector can resolve.
 *
 * **The window is derived from [PAT_PITCH_MIN_HZ], not fixed.** YIN needs a frame at least twice the
 * longest period it is asked to consider, so the lowest pitch admitted sets the frame length, and the
 * frame length sets the time resolution. A detector told it might hear a 65 Hz note is looking through
 * a 50 ms window whatever it is actually listening to. So `minHz`/`maxHz` are the same kind of
 * parameter as `patDetectOnsetSamples`' `minGapSeconds`: the detector is told what register it is
 * allowed to resolve, and buys sharpness with that promise. Telling it the truth about a treble line
 * shortens its window fourfold; guessing wide costs time resolution and gains nothing, which is why
 * the register is an argument here rather than a constant.
 *
 * **A frame's pitch belongs to the frame's centre, not its start.** A frame spans `window + tauMax`
 * samples, so attributing its estimate to the first of them would report a note as starting up to 50 ms
 * before it did. [PatF0Frame.centerSample] is what every consumer here uses, and a detected pitch
 * change is then localised inside the frame it was noticed in by re-analysing with a window sized to
 * the *new* note's period — the same walk-back move `patDetectOnsetSamples` makes, for the same reason.
 *
 * **Timbre is not polyphony, and the detector has to be able to tell them apart.** A sawtooth is a
 * stack of partials and a triad is a stack of partials; refusing everything harmonically rich would
 * make the refusal worthless. [patHarmonicResidual] measures the fraction of strong spectral peak
 * energy that does *not* sit near an integer multiple of the detected f0, which is near zero for any
 * single note of any timbre and large for a chord. A monophonic detector that reports it cannot name
 * a chord is telling the truth; one that picks the loudest note is not.
 */

@file:afield.CompletionCategory("Patterns")

package afield.four.synth

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/** Analysis hop, in samples. 128 at 44.1 kHz is 2.9 ms. */
const val PAT_PITCH_HOP: Int = 128

/**
 * Lowest fundamental admitted, in Hz. 65 Hz is C2 in Tidal's spelling (MIDI 36).
 *
 * This is the parameter that costs time resolution, because the analysis window is derived from it.
 * Pass the truth about the register being measured rather than accepting this.
 */
const val PAT_PITCH_MIN_HZ: Double = 65.0

/** Highest fundamental admitted, in Hz. 2200 Hz is a little above C7. */
const val PAT_PITCH_MAX_HZ: Double = 2200.0

/**
 * YIN's absolute threshold on the normalised difference function: the first dip below this is taken
 * as the period, in preference to a deeper dip at a longer lag. That preference is what keeps a
 * harmonically rich note from being reported an octave low.
 */
const val PAT_PITCH_YIN_THRESHOLD: Double = 0.15

/** Minimum aperiodicity-complement for a frame to be called voiced. */
const val PAT_PITCH_PERIODICITY: Double = 0.72

/**
 * Fraction of the 95th-percentile frame RMS below which a frame is called unvoiced whatever its
 * periodicity. Silence is extremely periodic if you divide zero by zero carefully enough.
 */
const val PAT_PITCH_LEVEL_FLOOR: Double = 0.06

/** Above this harmonic residual a span is reported as more than one note rather than named. */
const val PAT_POLYPHONY_RESIDUAL: Double = 0.25

/** Semitones of change between adjacent voiced frames that counts as a new note. */
const val PAT_PITCH_MIN_SEMITONES: Double = 0.5

/**
 * One frame's worth of pitch evidence.
 *
 * [hz] and [midi] are populated even when [voiced] is false — they are the best estimate the frame
 * could produce, kept because a rejected estimate is evidence about the rejection. Every consumer in
 * this file filters on [voiced] first, and so should any other.
 *
 * @param centerSample the middle of the analysed span. Not [startSample]: see the file comment. It is
 *   exactly where the estimate belongs only for a pitch at `minHz`. The difference function at the
 *   chosen lag `tau` compares `window + tau` samples, not all `window + tauMax`, so for a higher
 *   pitch the estimate is centred `(tauMax − tau) / 2` samples earlier than this. `FnSoundDocs`
 *   measures that on a rendered glide; see `chain_fn_sound_roundtrip.md`.
 * @param confidence `1 − d'(tau)`, so 1.0 is a perfectly periodic frame and 0.0 is noise.
 */
data class PatF0Frame(
    val startSample: Int,
    val centerSample: Int,
    val spanSamples: Int,
    val hz: Double,
    val midi: Double,
    val confidence: Double,
    val rms: Double,
    val voiced: Boolean
)

/** MIDI note number of a frequency, as a real number. Inverse of [patMidiToHz]. */
fun patHzToMidi(hz: Double): Double =
    if (hz <= 0.0) Double.NaN else 69.0 + 12.0 * ln(hz / 440.0) / ln(2.0)

/**
 * The analysed span of one frame, in samples: `2·(sampleRate/minHz)` of window plus the longest lag.
 *
 * This is the detector's time resolution and the shortest note it can hope to see, so it is a
 * public function rather than an internal detail — a measurement that quotes a note length without
 * quoting this alongside it is not saying anything.
 */
fun patPitchFrameSpan(sampleRate: Int, minHz: Double = PAT_PITCH_MIN_HZ): Int {
    val tauMax = (sampleRate / minHz).toInt()
    return 2 * tauMax + tauMax
}

/**
 * Frame-wise monophonic F0 by YIN, with an explicit voiced decision.
 *
 * The voiced decision needs two gates and they reject different things. Periodicity alone calls a
 * near-silent stretch of one-bit dither confidently pitched; level alone calls a snare hit a note.
 * Both are relative to the material — the level floor is a fraction of this render's own 95th
 * percentile, not an absolute — because the same melody normalised differently is the same melody.
 *
 * @param minHz the lowest fundamental to consider. This sets [window] and therefore the time
 *   resolution; see the file comment. Telling the truth about the register is worth more here than
 *   any threshold.
 * @param window frame length in samples. Defaults to twice the longest admitted period, which is
 *   YIN's own requirement; shortening it below that makes low notes unresolvable rather than
 *   imprecise.
 */
fun patF0Frames(
    samples: FloatArray,
    sampleRate: Int,
    minHz: Double = PAT_PITCH_MIN_HZ,
    maxHz: Double = PAT_PITCH_MAX_HZ,
    hop: Int = PAT_PITCH_HOP,
    window: Int = 2 * (sampleRate / minHz).toInt(),
    threshold: Double = PAT_PITCH_YIN_THRESHOLD,
    periodicity: Double = PAT_PITCH_PERIODICITY,
    levelFloor: Double = PAT_PITCH_LEVEL_FLOOR
): List<PatF0Frame> {
    require(minHz > 0.0 && maxHz > minHz) { "minHz=$minHz maxHz=$maxHz is not a register" }
    val tauMax = min((sampleRate / minHz).toInt(), samples.size - 1)
    val tauMin = max(2, (sampleRate / maxHz).toInt())
    if (tauMax <= tauMin) return emptyList()
    val span = window + tauMax
    if (samples.size < span) return emptyList()

    val frameCount = (samples.size - span) / hop + 1
    val d = DoubleArray(tauMax + 1)
    val dp = DoubleArray(tauMax + 1)

    // Pass one: the estimate and its confidence, with voicing left undecided because the level gate
    // is relative to the whole render and cannot be known one frame at a time.
    val raw = ArrayList<PatF0Frame>(frameCount)
    for (f in 0 until frameCount) {
        val start = f * hop
        var energy = 0.0
        for (j in 0 until window) energy += samples[start + j].toDouble() * samples[start + j]
        val rms = sqrt(energy / window)

        for (tau in 1..tauMax) {
            var sum = 0.0
            val base = start + tau
            for (j in 0 until window) {
                val diff = samples[start + j] - samples[base + j]
                sum += diff.toDouble() * diff
            }
            d[tau] = sum
        }
        dp[0] = 1.0
        var running = 0.0
        for (tau in 1..tauMax) {
            running += d[tau]
            dp[tau] = if (running <= 0.0) 1.0 else d[tau] * tau / running
        }

        // The first dip below threshold, descended to its own bottom. Scanning up from tauMin and
        // stopping early is what prefers the fundamental over the deeper dip one octave down.
        var best = -1
        var tau = tauMin
        while (tau <= tauMax) {
            if (dp[tau] < threshold) {
                while (tau + 1 <= tauMax && dp[tau + 1] < dp[tau]) tau++
                best = tau
                break
            }
            tau++
        }
        if (best < 0) {
            var lowest = tauMin
            for (t in tauMin..tauMax) if (dp[t] < dp[lowest]) lowest = t
            best = lowest
        }

        val refined = best + parabolicShift(dp, best, tauMin, tauMax)
        val hz = if (refined > 0.0) sampleRate / refined else 0.0
        raw += PatF0Frame(
            startSample = start,
            centerSample = start + span / 2,
            spanSamples = span,
            hz = hz,
            midi = patHzToMidi(hz),
            confidence = (1.0 - dp[best]).coerceIn(0.0, 1.0),
            rms = rms,
            voiced = false
        )
    }

    // Pass two: the level gate, relative to this render's own loud frames.
    val sortedRms = raw.map { it.rms }.sorted()
    val p95 = sortedRms[(sortedRms.size * 0.95).toInt().coerceAtMost(sortedRms.size - 1)]
    val floor = max(1e-5, levelFloor * p95)
    return raw.map { frame ->
        val ok = frame.confidence >= periodicity &&
            frame.rms >= floor &&
            frame.hz in minHz..maxHz
        frame.copy(voiced = ok)
    }
}

/** Sub-sample vertex of the parabola through `dp` at [at] and its two neighbours. */
private fun parabolicShift(dp: DoubleArray, at: Int, lo: Int, hi: Int): Double {
    if (at <= lo || at >= hi) return 0.0
    val s0 = dp[at - 1]
    val s1 = dp[at]
    val s2 = dp[at + 1]
    val denom = 2.0 * (s0 + s2 - 2.0 * s1)
    if (abs(denom) < 1e-15) return 0.0
    return ((s0 - s2) / denom).coerceIn(-1.0, 1.0)
}

/**
 * Semitones of pitch change between adjacent frames, zero wherever either frame is unvoiced.
 *
 * Gating on both frames is the whole point: an unvoiced frame's [PatF0Frame.midi] is a number, and
 * differencing against it produces enormous flux at every note edge and every rest, which reads as a
 * detector that finds everything.
 */
fun patPitchFlux(frames: List<PatF0Frame>): DoubleArray {
    val flux = DoubleArray(frames.size)
    for (i in 1 until frames.size) {
        flux[i] = if (frames[i].voiced && frames[i - 1].voiced) {
            abs(frames[i].midi - frames[i - 1].midi)
        } else 0.0
    }
    return flux
}

/**
 * Where notes start, judged by pitch rather than by level.
 *
 * Two kinds of evidence are merged, because a melody produces both. An **entry** is an
 * unvoiced-to-voiced transition — the first note, and every note after a rest — and it has an
 * amplitude attack, so it is localised the way `patDetectOnsetSamples` localises one. A **change** is
 * a jump of at least [minSemitones] between adjacent voiced frames, which has no attack at all in a
 * legato line, and is localised by re-analysing the neighbourhood with a window sized to the new
 * note's period. Neither localisation is optional: a frame here spans 50 ms at the default register,
 * so reporting the frame's own position would put every onset tens of milliseconds early and the
 * error would be invisible because it would be constant.
 *
 * @param minGapSeconds the smallest interval the *pattern* can produce, exactly as in
 *   [patDetectOnsetSamples]. The detector refuses to report two notes closer than this.
 */
fun patDetectPitchChangeSamples(
    samples: FloatArray,
    sampleRate: Int,
    minGapSeconds: Double,
    frames: List<PatF0Frame> = patF0Frames(samples, sampleRate),
    minSemitones: Double = PAT_PITCH_MIN_SEMITONES
): List<Int> {
    if (frames.size < 3) return emptyList()
    val flux = patPitchFlux(frames)
    val refractoryFrames = (minGapSeconds * 0.45 * sampleRate / PAT_PITCH_HOP).toInt().coerceAtLeast(1)

    val candidates = mutableListOf<Int>()

    // Entries: voicing turns on. Walk back to the attack when there is silence to walk back to, and
    // otherwise ask where the new pitch committed — see [refineAttack] for why that choice is not
    // cosmetic.
    for (i in frames.indices) {
        val wasVoiced = i > 0 && frames[i - 1].voiced
        if (!frames[i].voiced || wasVoiced) continue
        val frame = frames[i]
        candidates += refineAttack(samples, frame.centerSample, frame.spanSamples, sampleRate)
            ?: refinePitchBoundary(
                samples, sampleRate,
                fromMidi = Double.NaN,
                toMidi = frame.midi,
                searchFrom = frame.startSample - frame.spanSamples,
                searchTo = frame.startSample + frame.spanSamples
            )
    }

    // Changes: a local maximum of pitch flux above the admitted step size.
    var lastFrame = -refractoryFrames * 2
    for (i in 1 until flux.size - 1) {
        if (flux[i] < minSemitones) continue
        if (flux[i] < flux[i - 1] || flux[i] < flux[i + 1]) continue
        if (i - lastFrame < refractoryFrames) continue
        lastFrame = i
        candidates += refinePitchBoundary(
            samples, sampleRate,
            fromMidi = frames[i - 1].midi,
            toMidi = frames[i].midi,
            searchFrom = frames[i - 1].startSample,
            searchTo = frames[i].centerSample + frames[i].spanSamples / 2
        )
    }

    val gapSamples = (minGapSeconds * 0.5 * sampleRate).toInt()
    val out = mutableListOf<Int>()
    for (at in candidates.sorted()) if (out.isEmpty() || at - out.last() >= gapSamples) out += at
    return out
}

/**
 * Every note start either criterion can see: the union of the energy onsets and the pitch onsets.
 *
 * The two detectors fail in *opposite* directions and the corpus measures both failures, which is why
 * this exists rather than a recommendation to prefer one.
 *
 * - A **pitch** criterion cannot see a note repeated at the same pitch inside a continuous tone.
 *   There is no pitch change to find, and by construction there never will be.
 * - An **energy** criterion cannot see a note that begins with no attack — a single oscillator whose
 *   frequency steps, which is what a Dough render of a legato line is. There is no amplitude rise to
 *   find, and by construction there never will be.
 *
 * A melody containing both a repeat and a slur therefore defeats either alone and neither together.
 *
 * **When the two collide inside [minGapSeconds], the pitch onset wins — not the earlier one.** The
 * first version of this kept whichever came first, which is the obvious rule and is wrong here, and
 * the corpus said so immediately: merged recall on `detached-scale` was **0.19 against 0.94 for the
 * pitch detector alone**. The cause is that `patDetectOnsetSamples` runs about 92 ms late on sustained
 * material (see [PatCaseResult.energyMatch]), so its onsets are not competing versions of the same
 * instant — they are the previous note's onset arriving after the next note's, displacing a correct
 * detection with a stale one. Preferring the better-localised criterion makes the union a superset of
 * the pitch detector's timing while still contributing the repeats only energy can see. A naive union
 * of two detectors is only sound when both are calibrated, and one of these is not.
 */
fun patDetectNoteOnsets(
    samples: FloatArray,
    sampleRate: Int,
    minGapSeconds: Double,
    frames: List<PatF0Frame> = patF0Frames(samples, sampleRate),
    minSemitones: Double = PAT_PITCH_MIN_SEMITONES
): List<Int> {
    val energy = patDetectOnsetSamples(samples, sampleRate, minGapSeconds)
    val pitch = patDetectPitchChangeSamples(samples, sampleRate, minGapSeconds, frames, minSemitones)
    val gapSamples = (minGapSeconds * 0.5 * sampleRate).toInt()

    // `true` marks a pitch-derived candidate; sorting puts it first at an equal position so it wins.
    val candidates = (pitch.map { it to true } + energy.map { it to false })
        .sortedWith(compareBy({ it.first }, { !it.second }))

    val out = mutableListOf<Pair<Int, Boolean>>()
    for (candidate in candidates) {
        val last = out.lastOrNull()
        if (last == null || candidate.first - last.first >= gapSamples) {
            out += candidate
        } else if (candidate.second && !last.second) {
            out[out.size - 1] = candidate
        }
    }
    return out.map { it.first }
}

/**
 * First sample near [near] whose level crosses a fraction of the local peak, or null when there was
 * no silence to walk back from.
 *
 * The nullable return is the whole point and it was learned by getting it wrong. This is the same
 * walk-back `patDetectOnsetSamples` uses, and there it is correct, because a percussive train is
 * silent between hits. A melodic render is not: `patRenderStereo` gives each note a tail of
 * `decay · 4` seconds (`PatSoundDocs.kt:795`), which at the default `decay = 0.08` is 320 ms and
 * swallows several steps, so the previous note is still at full level when the next one starts. The
 * walk-back then finds its own search window already loud, returns the window's first sample, and
 * reports every onset early by however wide the window was — uniformly, which is the dangerous part,
 * because a constant error looks like a calibration offset rather than a bug. Measured on
 * `bass-register` before this guard: every onset 1025 samples (23 ms) early.
 *
 * So the first millisecond of the search window is probed first, and if it is already above the
 * trigger this function declines and the caller localises by pitch commitment instead.
 */
private fun refineAttack(samples: FloatArray, near: Int, spanSamples: Int, sampleRate: Int): Int? {
    val back = max(spanSamples, (0.010 * sampleRate).toInt())
    val lo = max(0, near - back)
    val hi = min(samples.size, near + (0.010 * sampleRate).toInt())
    if (hi <= lo) return null
    var peak = 0.0f
    for (i in lo until hi) if (abs(samples[i]) > peak) peak = abs(samples[i])
    if (peak <= 1e-6f) return null
    val trigger = 0.05f * peak

    val probe = min(hi, lo + max(16, sampleRate / 1000))
    var already = 0.0f
    for (i in lo until probe) if (abs(samples[i]) > already) already = abs(samples[i])
    if (already > trigger) return null

    for (i in lo until hi) if (abs(samples[i]) > trigger) return i
    return null
}

/**
 * Where inside a straddling frame the pitch actually changed.
 *
 * The move is to slide a *short* window — long enough for the new note's period and no longer —
 * forward across the neighbourhood and take the first position whose estimate has committed to the
 * new pitch. A window starting before the boundary contains both notes and cannot commit; one
 * starting at or after it contains only the new note. So the earliest committed position is the
 * boundary, to the resolution of the slide step, which is far finer than the frame that raised the
 * question.
 *
 * Returns the midpoint of the searched span if nothing commits, which happens when the "change" was
 * an artefact of a frame straddling a rest; the caller's refractory gap then usually drops it.
 *
 * [fromMidi] may be `NaN`, which is how an *entry* asks this question: there is no previous pitch to
 * be leaving, only a new one to be arriving at, and the commitment test is the same either way.
 */
private fun refinePitchBoundary(
    samples: FloatArray,
    sampleRate: Int,
    fromMidi: Double,
    toMidi: Double,
    searchFrom: Int,
    searchTo: Int
): Int {
    val lo = searchFrom.coerceIn(0, max(0, samples.size - 1))
    val hi = searchTo.coerceIn(lo, samples.size)
    if (hi - lo < 64) return lo

    val toHz = patMidiToHz(toMidi)
    if (!toHz.isFinite() || toHz <= 0.0) return (lo + hi) / 2
    val tauTo = (sampleRate / toHz).toInt().coerceAtLeast(2)
    val shortWindow = (2 * tauTo).coerceAtLeast(96)
    val shortTauMax = (tauTo * 2).coerceAtMost(shortWindow)
    val shortTauMin = max(2, tauTo / 2)
    val need = shortWindow + shortTauMax
    val step = max(16, need / 32)

    val tolerance = if (fromMidi.isFinite()) max(0.35, abs(toMidi - fromMidi) * 0.25) else 0.35
    val last = min(samples.size - need, hi)
    var at = lo
    while (at <= last) {
        val hz = yinAt(samples, at, shortWindow, shortTauMin, shortTauMax, sampleRate)
        if (hz > 0.0 && abs(patHzToMidi(hz) - toMidi) <= tolerance) return at
        at += step
    }
    return (lo + hi) / 2
}

/** One YIN estimate at one position, for the boundary search. Returns 0.0 when out of range. */
private fun yinAt(
    samples: FloatArray,
    start: Int,
    window: Int,
    tauMin: Int,
    tauMax: Int,
    sampleRate: Int
): Double {
    if (start < 0 || start + window + tauMax > samples.size) return 0.0
    val d = DoubleArray(tauMax + 1)
    for (tau in 1..tauMax) {
        var sum = 0.0
        for (j in 0 until window) {
            val diff = samples[start + j] - samples[start + tau + j]
            sum += diff.toDouble() * diff
        }
        d[tau] = sum
    }
    val dp = DoubleArray(tauMax + 1)
    dp[0] = 1.0
    var running = 0.0
    for (tau in 1..tauMax) {
        running += d[tau]
        dp[tau] = if (running <= 0.0) 1.0 else d[tau] * tau / running
    }
    var best = -1
    var tau = tauMin
    while (tau <= tauMax) {
        if (dp[tau] < PAT_PITCH_YIN_THRESHOLD) {
            while (tau + 1 <= tauMax && dp[tau + 1] < dp[tau]) tau++
            best = tau
            break
        }
        tau++
    }
    if (best < 0) return 0.0
    val refined = best + parabolicShift(dp, best, tauMin, tauMax)
    return if (refined > 0.0) sampleRate / refined else 0.0
}

/**
 * One recovered note.
 *
 * [midi] is decided in cents and then rounded, and [centsOffset] is what the rounding threw away, so
 * a caller can report pitch error in cents rather than in note names. A detector that only ever
 * reported integers would score identically on a note it heard perfectly and one it heard 49 cents
 * flat, which is the difference between an instrument that is in tune and one that is not.
 *
 * @param harmonicResidual fraction of strong spectral peak energy not near a harmonic of [midi].
 *   See [patHarmonicResidual]; above [PAT_POLYPHONY_RESIDUAL] this span should not be named.
 */
data class PatDetectedNote(
    val startSample: Int,
    val endSample: Int,
    val sampleRate: Int,
    val midi: Int,
    val centsOffset: Double,
    val confidence: Double,
    val voicedFrames: Int,
    val harmonicResidual: Double
) {
    val startSeconds: Double get() = startSample.toDouble() / sampleRate
    val endSeconds: Double get() = endSample.toDouble() / sampleRate

    /** The pitch actually measured, before rounding: [midi] plus [centsOffset]. */
    val measuredMidi: Double get() = midi + centsOffset / 100.0

    /**
     * True when this span is not one note at the pitch named for it.
     *
     * Not called `polyphonicSuspected`, which is what it was called until the corpus was
     * read carefully. A high residual has two causes and the spectrum cannot separate them: more than
     * one pitch really is sounding, or the pitch estimate straddles two notes and is therefore a
     * harmonic of neither. On `bass-register` it fires on the second cause, not the first. Either way
     * the correct action is the same — do not name this span — so the flag is useful and its name now
     * says only what is known.
     */
    val notOneNote: Boolean get() = harmonicResidual >= PAT_POLYPHONY_RESIDUAL

    override fun toString(): String =
        "note $midi${"%+.0f".format(centsOffset)}c @ ${"%.3f".format(startSeconds)}–" +
            "${"%.3f".format(endSeconds)}s (conf ${"%.2f".format(confidence)}, " +
            "residual ${"%.2f".format(harmonicResidual)}${if (notOneNote) ", NOT ONE NOTE" else ""})"
}

/**
 * Cut the frame trace into notes at [boundaries].
 *
 * A note's pitch is taken from the **longest stable run** of voiced frames inside its segment, not
 * from the first run and not from all of them. That is the second thing here learned by getting it
 * wrong. A segment begins at a detected boundary, and the frames just after a boundary are the ones
 * whose analysis window still straddles the note before it — so the first voiced run in a segment is
 * frequently the *previous* note's tail, at the previous note's pitch. Measured on `bass-register`
 * before this change: a segment beginning at the true onset of MIDI 43 reported MIDI 36, the whole
 * sequence came out shifted by one note, and the resulting pitch error was **1700 cents median** on a
 * detector whose per-note frequency estimates were in fact accurate to a few cents. A run is "stable"
 * while consecutive frames stay within [stabilitySemitones] of each other, which is what makes the
 * body of a note one run and a transition through it several short ones.
 *
 * Segments whose longest stable run carries fewer than [minVoicedFrames] frames are dropped rather
 * than named from almost no evidence. That produces misses instead of noise on material
 * below the detector's resolution, so a corpus reports recall collapsing rather than precision
 * rotting unnoticed.
 *
 * [PatDetectedNote.startSample] stays at the boundary — the onset estimate — while the pitch comes
 * from the stable run, which is later. Keeping those two apart is what lets a case have good timing
 * and bad pitch, or the reverse, and be told which.
 */
fun patSegmentNotes(
    samples: FloatArray,
    sampleRate: Int,
    minGapSeconds: Double,
    frames: List<PatF0Frame> = patF0Frames(samples, sampleRate),
    boundaries: List<Int> = patDetectPitchChangeSamples(samples, sampleRate, minGapSeconds, frames),
    minVoicedFrames: Int = 3,
    stabilitySemitones: Double = 0.4
): List<PatDetectedNote> {
    if (frames.isEmpty() || boundaries.isEmpty()) return emptyList()
    val notes = mutableListOf<PatDetectedNote>()

    for ((index, start) in boundaries.withIndex()) {
        val nextBoundary = boundaries.getOrNull(index + 1) ?: samples.size
        val inside = frames.indices.filter {
            frames[it].centerSample >= start && frames[it].centerSample < nextBoundary
        }
        val run = longestStableRun(frames, inside, stabilitySemitones)
        if (run.size < minVoicedFrames) continue

        val first = frames[run.first()]
        val last = frames[run.last()]
        val end = min(nextBoundary, last.centerSample + last.spanSamples / 2)
        val pitches = run.map { frames[it].midi }.sorted()
        val medianMidi = pitches[pitches.size / 2]
        if (!medianMidi.isFinite()) continue
        val rounded = round(medianMidi).toInt()
        val confidences = run.map { frames[it].confidence }.sorted()

        notes += PatDetectedNote(
            startSample = start,
            endSample = max(start + 1, end),
            sampleRate = sampleRate,
            midi = rounded,
            centsOffset = (medianMidi - rounded) * 100.0,
            confidence = confidences[confidences.size / 2],
            voicedFrames = run.size,
            // Measured over the stable run, not the whole segment: the segment's edges are where two
            // notes overlap, and a spectrum taken there says "chord" about a monophonic line.
            harmonicResidual = patHarmonicResidual(
                samples,
                start = first.startSample,
                length = max(1, last.startSample + last.spanSamples - first.startSample),
                sampleRate = sampleRate,
                f0 = patMidiToHz(medianMidi)
            )
        )
    }
    return notes
}

/**
 * The longest run of consecutive voiced frames that holds one pitch.
 *
 * Breaks on an unvoiced frame, on a gap in frame index, and on a jump of [stability] semitones or
 * more. Returns frame indices into [frames], empty when nothing qualifies.
 */
private fun longestStableRun(
    frames: List<PatF0Frame>,
    indices: List<Int>,
    stability: Double
): List<Int> {
    var best = emptyList<Int>()
    var current = mutableListOf<Int>()

    fun close() {
        if (current.size > best.size) best = current.toList()
        current = mutableListOf()
    }

    for (i in indices) {
        val frame = frames[i]
        if (!frame.voiced || !frame.midi.isFinite()) {
            close()
            continue
        }
        val previous = current.lastOrNull()
        val continues = previous != null &&
            i == previous + 1 &&
            abs(frame.midi - frames[previous].midi) < stability
        if (continues) current += i else { close(); current = mutableListOf(i) }
    }
    close()
    return best
}

/** What a buffer is, when a monophonic detector could not name notes in it. */
enum class PatPitchVerdict {
    /** Notes were named. Nothing to explain. */
    PITCHED,

    /** Loud, aperiodic, and spectrally *sparse*: a small number of discrete partials — a chord. */
    POLYPHONIC,

    /** Loud, aperiodic, and spectrally *dense*: noise or percussion, with no fundamental to find. */
    UNPITCHED,

    /**
     * Frames carried a pitch, but no span held one long enough to name.
     *
     * The material is faster than the analysis window — which is set by `minHz`, so this verdict very
     * often means the register was not declared rather than that the audio is hard. It is a separate
     * verdict from [UNPITCHED] because the remedy is different and knowable: tell the detector the
     * register. Measured on `fast-line-untold`, 329 of 329 frames were voiced and nothing was named.
     */
    UNRESOLVED,

    /** Nothing above the level floor. */
    SILENT
}

/**
 * Why nothing could be named, distinguished by spectral sparsity.
 *
 * @param spectralPeaks discrete peaks counted in the loudest aperiodic stretch. This is the
 *   discriminator: a triad is a handful of peaks, a noise burst is dozens.
 */
data class PatPitchDiagnosis(
    val verdict: PatPitchVerdict,
    val loudFrames: Int,
    val loudUnvoicedFrames: Int,
    val spectralPeaks: Int
) {
    fun describe(): String = when (verdict) {
        PatPitchVerdict.PITCHED -> "pitched"
        PatPitchVerdict.POLYPHONIC ->
            "POLYPHONIC: $loudUnvoicedFrames of $loudFrames loud frames are aperiodic and the " +
                "spectrum there has only $spectralPeaks discrete peaks. That is more than one note " +
                "at once, which a monophonic detector cannot name and should not guess at"
        PatPitchVerdict.UNPITCHED ->
            "UNPITCHED: $loudUnvoicedFrames of $loudFrames loud frames are aperiodic and the " +
                "spectrum there has $spectralPeaks peaks, which is broadband. This is noise or " +
                "percussion and there is no fundamental to recover"
        PatPitchVerdict.UNRESOLVED ->
            "UNRESOLVED: ${loudFrames - loudUnvoicedFrames} of $loudFrames loud frames did carry a " +
                "pitch, but no span held one long enough to name. The notes are shorter than the " +
                "analysis window, which is set by minHz — declaring the register is the fix"
        PatPitchVerdict.SILENT -> "SILENT: no frame reached the level floor"
    }
}

/**
 * Tell a chord apart from a noise burst, when neither can be named.
 *
 * Both look identical to the voicing gate — loud and aperiodic — and reporting them the same way
 * would waste the one thing a refusal is good for, which is saying *what* was refused. A triad of
 * sines has three peaks; a snare has a continuum. So the longest loud-but-unvoiced stretch is
 * transformed and its discrete peaks counted, and the verdict is sparsity.
 *
 * This is a second opinion, not the primary refusal. The primary refusal is the voicing gate, and it
 * turns out to do nearly all the work: measured on this package's `chord-triad` case, a triad left
 * **71 of 915 frames voiced** — three simultaneous sines in a 3:4:5-ish ratio are not periodic at any
 * single lag, so YIN rejects them without ever consulting a spectrum. [patHarmonicResidual] was
 * designed to be the chord detector and is in practice mostly a straddle detector.
 */
fun patDiagnoseUnpitched(
    samples: FloatArray,
    sampleRate: Int,
    frames: List<PatF0Frame>,
    namedNotes: Int,
    sparsePeakLimit: Int = 12
): PatPitchDiagnosis {
    if (frames.isEmpty()) return PatPitchDiagnosis(PatPitchVerdict.SILENT, 0, 0, 0)
    val sortedRms = frames.map { it.rms }.sorted()
    val p95 = sortedRms[(sortedRms.size * 0.95).toInt().coerceAtMost(sortedRms.size - 1)]
    val floor = max(1e-5, PAT_PITCH_LEVEL_FLOOR * p95)
    val loud = frames.filter { it.rms >= floor }
    if (loud.isEmpty()) return PatPitchDiagnosis(PatPitchVerdict.SILENT, 0, 0, 0)
    val loudUnvoiced = loud.count { !it.voiced }
    if (namedNotes > 0) {
        return PatPitchDiagnosis(PatPitchVerdict.PITCHED, loud.size, loudUnvoiced, 0)
    }

    // The longest run of loud-but-unvoiced frames is where the evidence is.
    var bestStart = -1
    var bestLength = 0
    var runStart = -1
    for (i in frames.indices) {
        val interesting = frames[i].rms >= floor && !frames[i].voiced
        if (interesting) {
            if (runStart < 0) runStart = i
            val length = i - runStart + 1
            if (length > bestLength) {
                bestLength = length
                bestStart = runStart
            }
        } else runStart = -1
    }
    // No loud-but-unvoiced stretch at all, yet nothing was named: the frames were readable and the
    // segments were not. Saying SILENT here (as this did until the corpus caught it) is a plain lie
    // about a buffer whose every frame was voiced.
    if (bestStart < 0) return PatPitchDiagnosis(PatPitchVerdict.UNRESOLVED, loud.size, loudUnvoiced, 0)

    val from = frames[bestStart].startSample
    val to = frames[bestStart + bestLength - 1].let { it.startSample + it.spanSamples }
    val peaks = patSpectralPeakCount(samples, from, to - from, sampleRate)
    val verdict =
        if (peaks in 1..sparsePeakLimit) PatPitchVerdict.POLYPHONIC else PatPitchVerdict.UNPITCHED
    return PatPitchDiagnosis(verdict, loud.size, loudUnvoiced, peaks)
}

/**
 * Discrete spectral peaks in a span: local maxima above a tenth of the loudest, merged when adjacent.
 *
 * Merging matters. A Hann-windowed sine is three bins wide, so counting bare local maxima would call
 * one partial three peaks and every chord broadband.
 */
fun patSpectralPeakCount(
    samples: FloatArray,
    start: Int,
    length: Int,
    sampleRate: Int,
    maxFftSize: Int = 4096
): Int {
    if (length <= 0) return 0
    var fftSize = 1024
    while (fftSize * 2 <= min(length, maxFftSize)) fftSize *= 2
    if (samples.size < fftSize) return 0
    val center = (start + length / 2).coerceIn(0, max(0, samples.size - 1))
    val from = (center - fftSize / 2).coerceIn(0, max(0, samples.size - fftSize))

    val buffer = FloatArray(2 * fftSize)
    for (i in 0 until fftSize) {
        val hann = 0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / (fftSize - 1))
        buffer[2 * i] = (samples[from + i] * hann).toFloat()
        buffer[2 * i + 1] = 0f
    }
    FFT(fftSize).forward(buffer)

    val bins = fftSize / 2
    val magnitude = DoubleArray(bins)
    for (k in 0 until bins) {
        val re = buffer[2 * k].toDouble()
        val im = buffer[2 * k + 1].toDouble()
        magnitude[k] = sqrt(re * re + im * im)
    }
    var loudest = 0.0
    for (k in 1 until bins - 1) if (magnitude[k] > loudest) loudest = magnitude[k]
    if (loudest <= 0.0) return 0
    val floor = 0.1 * loudest

    var peaks = 0
    var lastPeakBin = -10
    for (k in 2 until bins - 2) {
        val m = magnitude[k]
        if (m < floor) continue
        if (m < magnitude[k - 1] || m < magnitude[k + 1]) continue
        if (k - lastPeakBin <= 3) continue
        peaks++
        lastPeakBin = k
    }
    return peaks
}

/**
 * Is there actually energy at [f0], or is [f0] a fundamental nobody played?
 *
 * The classic missing-fundamental problem, and this corpus produces it without being asked to. A major
 * triad in near-just intonation is a 4:5:6 ratio — 261, 329 and 392 Hz are 4×, 5× and 6× of 65.3 Hz —
 * so a periodicity detector allowed to consider lags that long is *correct* to report 65 Hz. The chord
 * really is periodic at 65 Hz. Nobody played a 65 Hz note.
 *
 * Measured, before this existed: `patTranscribeAuto` estimated a register of 65–77 Hz for `chord-triad`
 * and named five confident notes around MIDI 36. Every other guard passed it, because nothing was
 * wrong with the periodicity — the aperiodic-fraction gate saw a mostly-voiced buffer and the harmonic
 * residual saw a perfect harmonic stack. It was a coherent answer to a question nobody asked.
 *
 * The discriminator is spectral, not temporal: a played fundamental has energy in its own bin, and a
 * virtual one does not. Returns the magnitude near [f0] relative to the loudest peak in the span, so
 * a small value means the fundamental is inferred rather than heard.
 *
 * Note the control this has to survive: `bass-register` plays MIDI 36, which *is* 65 Hz, and must come
 * back with a high ratio. The test separates "a low note" from "no low note" rather than distrusting
 * low notes.
 */
fun patEnergyAtF0(
    samples: FloatArray,
    start: Int,
    length: Int,
    sampleRate: Int,
    f0: Double,
    maxFftSize: Int = 8192
): Double {
    if (!f0.isFinite() || f0 <= 0.0 || length <= 0) return 1.0
    var fftSize = 2048
    while (fftSize * 2 <= min(length, maxFftSize)) fftSize *= 2
    if (samples.size < fftSize) return 1.0
    // Resolving a 65 Hz fundamental from its 130 Hz neighbour needs the window to be long enough that
    // one bin is well under f0; below that the answer is meaningless, so say so by declining to judge.
    if (sampleRate.toDouble() / fftSize > f0 * 0.5) return 1.0

    val center = (start + length / 2).coerceIn(0, max(0, samples.size - 1))
    val from = (center - fftSize / 2).coerceIn(0, max(0, samples.size - fftSize))
    if (samples.size - from < fftSize) return 1.0

    val buffer = FloatArray(2 * fftSize)
    for (i in 0 until fftSize) {
        val hann = 0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / (fftSize - 1))
        buffer[2 * i] = (samples[from + i] * hann).toFloat()
        buffer[2 * i + 1] = 0f
    }
    FFT(fftSize).forward(buffer)

    val bins = fftSize / 2
    val magnitude = DoubleArray(bins)
    for (k in 0 until bins) {
        val re = buffer[2 * k].toDouble()
        val im = buffer[2 * k + 1].toDouble()
        magnitude[k] = sqrt(re * re + im * im)
    }
    val binHz = sampleRate.toDouble() / fftSize
    var loudest = 0.0
    for (k in 1 until bins - 1) if (magnitude[k] > loudest) loudest = magnitude[k]
    if (loudest <= 0.0) return 1.0

    // Widen by two bins either side: a Hann main lobe is three bins and f0 rarely lands on a bin centre.
    val target = (f0 / binHz).toInt()
    var atF0 = 0.0
    for (k in (target - 2).coerceAtLeast(1)..(target + 2).coerceAtMost(bins - 2)) {
        if (magnitude[k] > atF0) atF0 = magnitude[k]
    }
    return atF0 / loudest
}

/**
 * How much of this span's strong spectral peak energy is *not* a harmonic of [f0].
 *
 * Measured on a Hann-windowed FFT of the middle of the span, because a note's attack and decay are
 * where its spectrum is least like itself. Peaks are taken as local maxima above a fraction of the
 * loudest peak; each is called harmonic when it sits within [centsTolerance] of some integer
 * multiple of [f0]. The result is energy-weighted, so a single weak inharmonic peak does not condemn
 * a note and a second note of comparable loudness does.
 *
 * Zero when there is nothing to measure, which is the answer that lets a caller name the note — the
 * alternative, refusing on absent evidence, would decline every short note. In particular a span
 * shorter than [minPeriods] periods of [f0] returns zero, because its frequency resolution is coarser
 * than the intervals it would have to separate: a 23 ms window cannot tell a 65 Hz note from a 65 Hz
 * note plus a 90 Hz one, and a detector that guessed there would be refusing at random. This is the
 * one place in the file where the right answer is "no opinion".
 *
 * The transform is sized to the span rather than fixed, so the analysed window stays inside the note
 * it is asking about. A 4096-point window is 93 ms at 44.1 kHz — longer than a note in half this
 * package's corpus — and reaching outside the note guarantees finding the neighbour it overlaps.
 */
fun patHarmonicResidual(
    samples: FloatArray,
    start: Int,
    length: Int,
    sampleRate: Int,
    f0: Double,
    maxFftSize: Int = 4096,
    centsTolerance: Double = 60.0,
    minPeriods: Int = 6
): Double {
    if (!f0.isFinite() || f0 <= 0.0 || length <= 0) return 0.0
    val period = sampleRate / f0
    if (length < minPeriods * period) return 0.0

    var fftSize = 1024
    while (fftSize * 2 <= min(length, maxFftSize)) fftSize *= 2
    if (fftSize < 1024 || samples.size < fftSize) return 0.0
    val center = (start + length / 2).coerceIn(0, max(0, samples.size - 1))
    val from = (center - fftSize / 2).coerceIn(0, max(0, samples.size - fftSize))
    if (samples.size - from < fftSize) return 0.0

    val buffer = FloatArray(2 * fftSize)
    for (i in 0 until fftSize) {
        val hann = 0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / (fftSize - 1))
        buffer[2 * i] = (samples[from + i] * hann).toFloat()
        buffer[2 * i + 1] = 0f
    }
    FFT(fftSize).forward(buffer)

    val bins = fftSize / 2
    val magnitude = DoubleArray(bins)
    for (k in 0 until bins) {
        val re = buffer[2 * k].toDouble()
        val im = buffer[2 * k + 1].toDouble()
        magnitude[k] = sqrt(re * re + im * im)
    }
    val binHz = sampleRate.toDouble() / fftSize
    val lowBin = max(1, (f0 * 0.6 / binHz).toInt())
    val highBin = min(bins - 2, (min(6000.0, sampleRate / 2.0) / binHz).toInt())
    if (highBin <= lowBin) return 0.0

    var loudest = 0.0
    for (k in lowBin..highBin) if (magnitude[k] > loudest) loudest = magnitude[k]
    if (loudest <= 0.0) return 0.0
    val peakFloor = 0.08 * loudest

    var harmonicEnergy = 0.0
    var inharmonicEnergy = 0.0
    for (k in lowBin..highBin) {
        val m = magnitude[k]
        if (m < peakFloor) continue
        if (m < magnitude[k - 1] || m < magnitude[k + 1]) continue
        val hz = k * binHz
        val ratio = hz / f0
        val nearest = round(ratio).coerceAtLeast(1.0)
        val cents = 1200.0 * ln(hz / (nearest * f0)) / ln(2.0)
        val energy = m * m
        if (abs(cents) <= centsTolerance) harmonicEnergy += energy else inharmonicEnergy += energy
    }
    val total = harmonicEnergy + inharmonicEnergy
    return if (total <= 0.0) 0.0 else inharmonicEnergy / total
}
