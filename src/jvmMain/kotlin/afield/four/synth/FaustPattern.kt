@file:afield.CompletionCategory("Synthesis")

package afield.four.synth

import afield.Box
import afield.ReturnValueRegistry
import afield.RunsAt
import afield.controlGroup
import afield.inMain
import afield.registerLaunchJob
import afield.returnValueRegistration
import afield.showSignals
import kotlinx.coroutines.Job
import kotlinx.coroutines.yield
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/*
 A pattern plays a Faust program.

 Two systems that did not know each other existed. On one side Field's pattern language — mini-notation,
 the `Pat` algebra, rational cycle time — which knew how to say *when* and *what pitch* and had only a
 stand-in CPU voice to say it with. On the other side a real Faust compiler, whose output was a single
 always-on DSP with sliders. This file is the join, and it is small on purpose: the pattern language needs
 nothing added to it, and Faust needs nothing recompiled.

 The whole conversion is one function, [faustNotesForArc]: a pattern event's rational arc becomes an integer
 frame on the bank's own clock, and its atom becomes a frequency in Hz. Everything after that is integers,
 which is why the timing can be asserted in samples.
*/

// ═══════════════════════════════════════════════════════════════════════════════════════════════
//  Pattern → notes
// ═══════════════════════════════════════════════════════════════════════════════════════════════

/**
 * The default fraction of its arc a note holds its gate for.
 *
 * **Deliberately not 1.0, and this is the trap worth naming.** At `sustain = 1.0` the gate never falls
 * between adjacent notes, so a repeated pitch is one continuous tone with a single attack — and an onset
 * detector pointed at that signal finds envelope ripple instead of notes. (Exactly that mistake was made in
 * this package on 2026-08-12: 22 "onsets" found in a render of 10 notes.) At 0.9 the gate falls for a tenth
 * of every arc, every note re-attacks, and what you measure is what was notated. Ask for 1.0 explicitly if
 * you want a legato pad, and then do not measure onsets.
 */
const val FAUST_PATTERN_SUSTAIN: Double = 0.9

/**
 * Turn one query of [pattern] into notes on a frame clock.
 *
 * Pitch, in the order it is looked for:
 *
 *  1. the `note` parameter, as a MIDI number — `pat("x*4") with note("0 3 7 12")`;
 *  2. the atom itself through [patNoteNumber], which reads `60`, `c`, `ef3`, `cs4` — Tidal's octave
 *     numbering, so `c5` is middle C;
 *  3. nothing: the event is **skipped** and counted, rather than played at some arbitrary default. A drum
 *     name has no pitch, and inventing one would make `pat("bd sd")` sound like a broken instrument
 *     instead of like the wrong pattern for this voice.
 *
 * `gain` and `sustain` are read off the event's parameters where present, so the whole `with gain(sine())`
 * surface works unchanged.
 *
 * @param frameOrigin the frame that cycle 0 begins on, so a live feeder can keep calling this per cycle
 * @return the notes, and how many events had no pitch — see [FaustPatternNotes]
 */
fun faustNotesForArc(
    pattern: PatPattern<String>,
    arc: PatArc,
    cps: Double,
    sampleRate: Int,
    sustain: Double = FAUST_PATTERN_SUSTAIN,
    gain: Double = 1.0,
    frameOrigin: Long = 0L
): FaustPatternNotes {
    require(cps > 0.0) { "cps must be > 0" }
    val secondsPerCycle = 1.0 / cps
    val notes = mutableListOf<FaustNote>()
    var unpitched = 0

    val events = pattern.events(arc)
        // A note whose onset is in an earlier query belongs to that query; striking it again would double
        // it. This is the whole/part distinction PatEvent.hasOnset exists for.
        .filter { it.hasOnset }
        .sortedWith(compareBy({ it.arc.start }, { it.arc.end }))

    for (event in events) {
        val extent = event.extent
        val midi = event.annotations["note"]?.trim()?.toDoubleOrNull()
            ?: patNoteNumber(event.value)?.toDouble()
        if (midi == null) {
            unpitched++
            continue
        }
        val startFrame = frameOrigin + (extent.start.toDouble() * secondsPerCycle * sampleRate).roundToLong()
        val arcFrames = (extent.dur.toDouble() * secondsPerCycle * sampleRate).roundToLong()
        val held = event.annotations["sustain"]?.trim()?.toDoubleOrNull() ?: sustain
        val lengthFrames = (arcFrames * held.coerceIn(0.0, 1.0)).roundToLong().coerceAtLeast(1L)
        val eventGain = gain * (event.annotations["gain"]?.trim()?.toDoubleOrNull() ?: 1.0)
        notes += FaustNote(
            startFrame = startFrame,
            endFrame = startFrame + lengthFrames,
            hz = patMidiToHz(midi),
            gain = eventGain,
            label = event.value
        )
    }
    return FaustPatternNotes(notes, unpitched)
}

/** The notes a pattern query produced, and how many of its events had no pitch to play. */
data class FaustPatternNotes(val notes: List<FaustNote>, val unpitched: Int) {
    val size: Int get() = notes.size
    override fun toString(): String =
        "FaustPatternNotes(${notes.size} notes" + (if (unpitched == 0) "" else ", $unpitched unpitched") + ")"
}

/** Every note in [cycles] whole cycles of [pattern], from frame 0. */
fun faustPatternNotes(
    pattern: PatPattern<String>,
    cycles: Int,
    cps: Double = 1.0,
    sampleRate: Int = 48_000,
    sustain: Double = FAUST_PATTERN_SUSTAIN,
    gain: Double = 1.0
): FaustPatternNotes {
    require(cycles > 0) { "cycles must be > 0" }
    return faustNotesForArc(
        pattern, PatArc(PatQ.ZERO, PatQ.of(cycles)), cps, sampleRate, sustain, gain
    )
}

/**
 * Turn a pattern of numbers into shared-parameter changes for [control].
 *
 * The other half of "a pattern plays it", and the reason it is worth having: a `Pat` pattern is not only a
 * rhythm of notes, it is a rhythm of *anything*. `pat("300 4000 800 6000").nums()` is a cutoff that steps on
 * the sixteenth, and because it goes through the same event timeline as the notes it steps on the **sample**
 * of the sixteenth rather than somewhere inside the block containing it.
 *
 * Continuous patterns work too — `sine().range(300.0, 6000.0).segment(16)` — because `segment` is what turns
 * a signal into events, and events are what this reads. A truly continuous sweep would need a change per
 * sample and is the program's own job (`si.smoo`), not the scheduler's.
 *
 * @param values one value per event; each event's *onset* is when the value is written
 */
fun faustParamAutomation(
    values: PatPattern<Double>,
    control: FaustControl,
    arc: PatArc,
    cps: Double,
    sampleRate: Int,
    frameOrigin: Long = 0L
): List<FaustParamChange> {
    require(cps > 0.0) { "cps must be > 0" }
    val secondsPerCycle = 1.0 / cps
    return values.events(arc)
        .filter { it.hasOnset }
        .sortedBy { it.extent.start }
        .map { event ->
            FaustParamChange(
                frame = frameOrigin +
                    (event.extent.start.toDouble() * secondsPerCycle * sampleRate).roundToLong(),
                control = control,
                value = event.value
            )
        }
}

/** [faustParamAutomation] over [cycles] whole cycles from frame 0. */
fun faustParamAutomation(
    values: PatPattern<Double>,
    control: FaustControl,
    cycles: Int,
    cps: Double = 1.0,
    sampleRate: Int = 48_000
): List<FaustParamChange> {
    require(cycles > 0) { "cycles must be > 0" }
    return faustParamAutomation(values, control, PatArc(PatQ.ZERO, PatQ.of(cycles)), cps, sampleRate)
}

// ═══════════════════════════════════════════════════════════════════════════════════════════════
//  Offline render — no audio device, no GPU, so it is the thing that can be asserted on
// ═══════════════════════════════════════════════════════════════════════════════════════════════

/** A rendered pattern, with the notes that were asked for and what the allocator did with them. */
class FaustPatternRender(
    val left: FloatArray,
    val right: FloatArray,
    val sampleRate: Int,
    val notes: List<FaustNote>,
    val voices: Int,
    /** Events with no pitch, skipped rather than invented. */
    val unpitched: Int,
    val steals: Long,
    val tailCuts: Long,
    val peakConcurrency: Int,
    /** Shared-parameter changes that landed — see [faustParamAutomation]. */
    val automationApplied: Long = 0L
) {
    val frames: Int get() = left.size

    val seconds: Double get() = frames.toDouble() / sampleRate

    /** The mono sum, which is what every measurement here is taken on. */
    fun mono(): FloatArray = FloatArray(frames) { (left[it] + right[it]) * 0.5f }

    val peak: Float
        get() {
            var p = 0f
            for (i in 0 until frames) p = max(p, max(abs(left[i]), abs(right[i])))
            return p
        }

    override fun toString(): String = synthPrettyObject(
        this,
        "frames" to frames,
        "seconds" to "%.3f".format(seconds),
        "notes" to notes.size,
        "voices" to voices,
        "steals" to steals,
        "peakConcurrency" to peakConcurrency
    )
}

/**
 * Render [cycles] cycles of [pattern] through a polyphonic bank of [program], offline.
 *
 * Nothing here opens an audio device or a window, which is what makes this the honest path for a figure in
 * the documentation and the only path a test can assert on. The bank is built, rendered in blocks — the
 * *same* [FaustVoiceBank.render] the audio thread calls, at the same block size, so what is measured is what
 * would be heard — and closed.
 *
 * @param gain written to the program's own `gain` control per note; `null` means **the value the program
 *   declared as that slider's default**, which is the level its author chose and the only level that is
 *   guaranteed to leave headroom for the other voices. A `gain` of 1.0 is full scale for *one* voice, so a
 *   three-note chord at 1.0 clips — which is not a bug in the mixer, it is three fortissimo notes.
 * @param blockFrames render in blocks of this size, so the sub-block scheduling is genuinely exercised
 *   rather than bypassed by one enormous call
 * @param tailSeconds keep rendering after the last note ends, so its release is in the file
 * @param automation shared-parameter changes on the same timeline as the notes — see [faustParamAutomation]
 * @param stealReleaseFrames see [FaustVoiceBank.stealReleaseFrames] — the one knob here with an audible
 *   consequence, and the only way to hear it is to render the same over-subscribed passage twice
 */
fun faustPatternRender(
    program: FaustProgram,
    pattern: PatPattern<String>,
    cycles: Int,
    cps: Double = 1.0,
    voices: Int = 8,
    sampleRate: Int = 48_000,
    sustain: Double = FAUST_PATTERN_SUSTAIN,
    gain: Double? = null,
    mixGain: Double = 1.0,
    blockFrames: Int = 128,
    tailSeconds: Double = 0.25,
    automation: List<FaustParamChange> = emptyList(),
    stealReleaseFrames: Long = FaustVoiceBank.RETRIGGER_FRAMES
): FaustPatternRender {
    val controls = FaustPolyControls.of(program)
    val noteGain = gain ?: controls.gain?.init ?: 1.0
    val queried = faustPatternNotes(pattern, cycles, cps, sampleRate, sustain, noteGain)
    val patternFrames = (cycles / cps * sampleRate).roundToLong()
    val lastNote = queried.notes.maxOfOrNull { it.endFrame } ?: 0L
    val frames = (max(patternFrames, lastNote) + (tailSeconds * sampleRate).roundToLong())
        .coerceIn(1L, sampleRate.toLong() * 600L).toInt()

    val left = FloatArray(frames)
    val right = FloatArray(frames)
    val bank = FaustVoiceBank(program, sampleRate, voices, mixGain, stealReleaseFrames)
    try {
        bank.schedule(queried.notes)
        bank.automate(automation)
        val bl = FloatArray(blockFrames)
        val br = FloatArray(blockFrames)
        var done = 0
        while (done < frames) {
            val n = min(blockFrames, frames - done)
            bank.render(bl, br, n)
            System.arraycopy(bl, 0, left, done, n)
            System.arraycopy(br, 0, right, done, n)
            done += n
        }
        return FaustPatternRender(
            left, right, sampleRate, queried.notes, voices, queried.unpitched,
            bank.steals, bank.tailCuts, bank.peakConcurrency, bank.automationApplied
        )
    } finally {
        bank.close()
    }
}

/** [faustPatternRender] from Faust source, compiling it first. */
fun faustPatternRender(
    source: String,
    pattern: PatPattern<String>,
    cycles: Int,
    cps: Double = 1.0,
    voices: Int = 8,
    sampleRate: Int = 48_000,
    sustain: Double = FAUST_PATTERN_SUSTAIN,
    gain: Double? = null,
    name: String = "faustpoly",
    stealReleaseFrames: Long = FaustVoiceBank.RETRIGGER_FRAMES
): FaustPatternRender {
    val (body, _) = stripFaustHeader(source)
    return faustPatternRender(
        FaustEngine.compile(name, body), pattern, cycles, cps, voices, sampleRate, sustain, gain,
        stealReleaseFrames = stealReleaseFrames
    )
}

// ═══════════════════════════════════════════════════════════════════════════════════════════════
//  Measuring what came out — onsets, pitch, spectrum
// ═══════════════════════════════════════════════════════════════════════════════════════════════

/**
 * A peak-following envelope: instant attack, exponential release.
 *
 * Instant attack because the thing being looked for *is* the attack; an attack-smoothed envelope would put
 * the onset later than it happened, by an amount that depends on the smoothing rather than on the music.
 */
fun faustEnvelope(x: FloatArray, sampleRate: Int, releaseMs: Double = 30.0): FloatArray {
    val coef = exp(-1.0 / (releaseMs / 1000.0 * sampleRate)).toFloat()
    val env = FloatArray(x.size)
    var e = 0f
    for (i in x.indices) {
        val a = abs(x[i])
        e = if (a > e) a else e * coef
        env[i] = e
    }
    return env
}

/**
 * The frame of every note attack in [x].
 *
 * A **burst** is a run of samples that is not quiet, allowing quiet stretches shorter than [minGapMs] inside
 * it; the onset is the burst's first sample. That is the whole algorithm, and it is deliberately not a
 * threshold crossing on a smoothed envelope, because the reported frame then depends on the smoothing rather
 * than on the music — and it is deliberately not period-dependent, because a sawtooth's `|x|` passes through
 * zero once a cycle and any envelope release long enough to bridge that is also long enough to bridge the
 * gap between two notes.
 *
 * That mistake was made here on the first run of `synth.faust.poly`: a 30ms envelope release needed 166ms of
 * silence to fall below the close threshold, the gaps were 71ms, and the detector reported **1** onset for
 * 14 notes. The gap-based version reports 14.
 *
 * **The signal has to have onsets for this to mean anything.** Between two notes there must be real silence,
 * which means the gate has to fall (see [FAUST_PATTERN_SUSTAIN]) and the program's release has to finish
 * inside the gap. [faustSilentGaps] is the check for that, and `synth.faust.poly` runs it *before* it
 * believes any onset count — because an onset detector pointed at a continuous tone will always find
 * something.
 *
 * @param openFraction a burst is only a note if it reaches this fraction of the signal's peak
 * @param closeFraction below this fraction of the peak counts as quiet
 * @param minGapMs quiet shorter than this is a waveform zero crossing, not a gap between notes
 */
fun faustOnsets(
    x: FloatArray,
    sampleRate: Int,
    openFraction: Double = 0.02,
    closeFraction: Double = 0.002,
    minGapMs: Double = 3.0
): List<Int> {
    if (x.isEmpty()) return emptyList()
    var peak = 0f
    for (v in x) peak = max(peak, abs(v))
    if (peak <= 0f) return emptyList()

    val open = (peak * openFraction).toFloat()
    val close = (peak * closeFraction).toFloat()
    val minGap = (minGapMs / 1000.0 * sampleRate).toInt().coerceAtLeast(1)

    val onsets = mutableListOf<Int>()
    var burstStart = -1
    var burstPeak = 0f
    // Start as though a long silence had just ended, so a signal that begins mid-attack still has an onset.
    var quietRun = minGap
    for (i in x.indices) {
        val a = abs(x[i])
        if (a <= close) {
            if (quietRun < minGap) quietRun++
            if (burstStart >= 0 && quietRun >= minGap) {
                if (burstPeak > open) onsets += burstStart
                burstStart = -1
                burstPeak = 0f
            }
        } else {
            if (burstStart < 0 && quietRun >= minGap) burstStart = i
            if (burstStart >= 0 && a > burstPeak) burstPeak = a
            quietRun = 0
        }
    }
    if (burstStart >= 0 && burstPeak > open) onsets += burstStart
    return onsets
}

/**
 * Maximal runs of near-silence in [x], as `first..last` frame ranges.
 *
 * The proof that a render has attacks at all. A pattern rendered at `sustain = 1.0` has *no* gaps, and every
 * onset an onset detector reports on it is a fiction.
 */
fun faustSilentGaps(
    x: FloatArray,
    sampleRate: Int,
    threshold: Double = 1e-4,
    minMs: Double = 3.0
): List<IntRange> {
    val minFrames = (minMs / 1000.0 * sampleRate).toInt().coerceAtLeast(1)
    val gaps = mutableListOf<IntRange>()
    var start = -1
    val t = threshold.toFloat()
    for (i in x.indices) {
        val quiet = abs(x[i]) <= t
        if (quiet) {
            if (start < 0) start = i
        } else if (start >= 0) {
            if (i - start >= minFrames) gaps += start until i
            start = -1
        }
    }
    if (start >= 0 && x.size - start >= minFrames) gaps += start until x.size
    return gaps
}

/**
 * The fundamental of `x[from until from+count]`, in Hz, by normalised autocorrelation.
 *
 * The **smallest** lag whose correlation is within [octaveTolerance] of the best is taken, not the best lag
 * itself: a sawtooth correlates almost perfectly with itself at twice its period too, and the naive answer
 * is an octave down about half the time.
 *
 * @return the fundamental, or `NaN` when the window is too short or silent
 */
fun faustFundamentalHz(
    x: FloatArray,
    from: Int,
    count: Int,
    sampleRate: Int,
    minHz: Double = 40.0,
    maxHz: Double = 3000.0,
    octaveTolerance: Double = 0.92
): Double {
    val minLag = (sampleRate / maxHz).toInt().coerceAtLeast(2)
    val maxLag = (sampleRate / minHz).toInt()
    val window = count - maxLag
    if (from < 0 || from + count > x.size || window < minLag * 4) return Double.NaN

    val r = DoubleArray(maxLag + 1)
    var best = 0.0
    for (lag in minLag..maxLag) {
        var num = 0.0
        var e0 = 0.0
        var e1 = 0.0
        for (i in 0 until window) {
            val a = x[from + i].toDouble()
            val b = x[from + i + lag].toDouble()
            num += a * b
            e0 += a * a
            e1 += b * b
        }
        r[lag] = if (e0 > 0.0 && e1 > 0.0) num / sqrt(e0 * e1) else 0.0
        if (r[lag] > best) best = r[lag]
    }
    if (best <= 0.1) return Double.NaN

    var chosen = -1
    for (lag in minLag..maxLag) {
        if (r[lag] >= best * octaveTolerance &&
            (lag == minLag || r[lag] >= r[lag - 1]) &&
            (lag == maxLag || r[lag] >= r[lag + 1])
        ) {
            chosen = lag
            break
        }
    }
    if (chosen < 0) return Double.NaN

    // Parabolic interpolation between the three correlations around the peak: a lag is an integer, a
    // period is not, and at 261.63Hz one sample of lag is 1.4Hz.
    var refined = chosen.toDouble()
    if (chosen > minLag && chosen < maxLag) {
        val a = r[chosen - 1]
        val b = r[chosen]
        val c = r[chosen + 1]
        val denom = 2.0 * (2.0 * b - a - c)
        if (abs(denom) > 1e-12) refined += (a - c) / denom
    }
    return sampleRate / refined
}

/**
 * The largest sample-to-sample step in `x[from until from+count]`, as a fraction of the window's own peak.
 *
 * Scale-free, which is the whole point: a click is a *step* in the waveform, and comparing raw deltas
 * between two windows at different pitches or different envelope levels compares the wrong thing — that
 * mistake produced a confident 1.90x that meant nothing. A value near 1.0 means the largest single-sample
 * jump is as big as the whole local peak: not a slope, a discontinuity.
 *
 * Use it as a ratio against the same measurement taken a few thousand samples later, once the note has
 * settled. See [FaustVoiceBank.stealReleaseFrames], which is the knob it exists to measure.
 */
fun faustNormalisedStep(x: FloatArray, from: Int, count: Int): Double {
    var step = 0f
    var peak = 0f
    for (i in max(1, from) until min(x.size, from + count)) {
        step = max(step, abs(x[i] - x[i - 1]))
        peak = max(peak, abs(x[i]))
    }
    return if (peak <= 0f) 0.0 else step.toDouble() / peak.toDouble()
}

/** One spectral peak: where it is and how strong. */
data class FaustPeak(val hz: Double, val magnitude: Double) {
    override fun toString(): String = "${"%.1f".format(hz)}Hz@${"%.4f".format(magnitude)}"
}

/**
 * The spectral peaks of `x[from…]`, Hann-windowed, in the band `[minHz, maxHz]`.
 *
 * This is the measurement that can tell polyphony from monophony, and nothing else here can: three notes
 * struck together put three fundamentals in one spectrum, and a single voice cannot do that however it is
 * driven. Restricting the band below the second harmonic of the lowest note keeps the answer unambiguous —
 * every peak in the band is somebody's fundamental.
 *
 * @param size FFT size, rounded down to a power of two
 * @param relThreshold keep peaks at least this fraction of the strongest peak in the band
 */
fun faustSpectrumPeaks(
    x: FloatArray,
    from: Int,
    size: Int,
    sampleRate: Int,
    minHz: Double = 20.0,
    maxHz: Double = 20_000.0,
    relThreshold: Double = 0.1
): List<FaustPeak> {
    var n = Integer.highestOneBit(size)
    if (from + n > x.size) n = Integer.highestOneBit(x.size - from)
    if (n < 64) return emptyList()

    val data = FloatArray(2 * n)
    for (i in 0 until n) {
        val w = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
        data[2 * i] = (x[from + i] * w).toFloat()
        data[2 * i + 1] = 0f
    }
    FFT(n).forward(data)

    val binHz = sampleRate.toDouble() / n
    val mag = DoubleArray(n / 2) { k ->
        val re = data[2 * k].toDouble()
        val im = data[2 * k + 1].toDouble()
        sqrt(re * re + im * im) / n
    }

    val loBin = (minHz / binHz).toInt().coerceAtLeast(1)
    val hiBin = (maxHz / binHz).toInt().coerceAtMost(n / 2 - 2)
    if (hiBin <= loBin) return emptyList()

    var strongest = 0.0
    for (k in loBin..hiBin) strongest = max(strongest, mag[k])
    if (strongest <= 0.0) return emptyList()

    val peaks = mutableListOf<FaustPeak>()
    for (k in loBin..hiBin) {
        if (mag[k] < strongest * relThreshold) continue
        if (mag[k] < mag[k - 1] || mag[k] < mag[k + 1]) continue
        // Parabolic interpolation on the magnitudes, so a peak is located to a fraction of a bin.
        val a = mag[k - 1]
        val b = mag[k]
        val c = mag[k + 1]
        val denom = 2.0 * (2.0 * b - a - c)
        val delta = if (abs(denom) > 1e-18) (a - c) / denom else 0.0
        peaks += FaustPeak((k + delta) * binHz, b)
    }
    return peaks.sortedByDescending { it.magnitude }
}

// ═══════════════════════════════════════════════════════════════════════════════════════════════
//  Live: a box that is a polyphonic instrument, and a box that is a pattern playing one
// ═══════════════════════════════════════════════════════════════════════════════════════════════

/**
 * A polyphonic Faust program, and the handle a box holds it by.
 *
 * The same arrangement as [FaustLaunch] and for the same reason — deliberately not a [StereoRenderable], so
 * that the generic audio return-value handler cannot claim it and leak the GraalVM context — with one
 * addition: it may own a feeder thread turning a pattern into notes, and that has to stop when the box does.
 */
class FaustPolyLaunch internal constructor(
    val program: FaustProgram,
    val bank: FaustVoiceBank,
    internal val node: FaustPolyNode,
    val gain: Float
) : Closeable {

    private val closed = AtomicBoolean(false)
    private val feeders = mutableListOf<FaustPatternFeeder>()

    /**
     * The controls a person can move: everything the program declares **except** `freq`, `gate` and `gain`,
     * which the notes drive. A `cutoff` slider on a polyphonic instrument moves all eight voices at once.
     */
    val sharedControls: List<FaustControl>
        get() {
            val perNote = setOfNotNull(
                bank.controls.freq.index, bank.controls.gate.index, bank.controls.gain?.index
            )
            return program.ui.writable.filter { it.index !in perNote }
        }

    /** Set a shared control by label on every voice. */
    operator fun set(label: String, value: Double) {
        bank[label] = value
    }

    /** Play one note now, or [afterSeconds] from now. */
    fun play(hz: Double, seconds: Double = 0.25, gain: Double = 1.0, afterSeconds: Double = 0.0) =
        bank.play(hz, seconds, gain, afterSeconds)

    /** Play a MIDI note number — `launch.note(60)` is middle C. */
    fun note(midi: Number, seconds: Double = 0.25, gain: Double = 1.0, afterSeconds: Double = 0.0) =
        bank.play(patMidiToHz(midi.toDouble()), seconds, gain, afterSeconds)

    /** Play a chord, every note struck on the same frame — the thing a monophonic path cannot do. */
    fun chord(midis: List<Number>, seconds: Double = 0.5, gain: Double = 1.0) {
        val start = bank.frame
        val length = (seconds * bank.sampleRate).roundToLong().coerceAtLeast(1L)
        bank.schedule(
            midis.map {
                FaustNote(start, start + length, patMidiToHz(it.toDouble()), gain, it.toString())
            }
        )
    }

    /**
     * Keep feeding this bank from [pattern], one cycle at a time, staying [aheadSeconds] ahead of the audio.
     *
     * The feeder runs on its own daemon thread and the *frames* are the clock, so being early costs nothing
     * and is not a source of jitter: a note's frame is computed once, from rational cycle time, and the
     * audio thread starts it on that exact sample however early it arrived.
     *
     * @param automate shared controls patterned by label alongside the notes —
     *   `automate = mapOf("cutoff" to pat("400 3000 900 6000").nums())`. An unknown label is a printed
     *   warning rather than fatal, because live-coding a program is how controls get renamed and a typo
     *   should not silence the instrument.
     */
    fun play(
        pattern: PatPattern<String>,
        cps: Double = 1.0,
        sustain: Double = FAUST_PATTERN_SUSTAIN,
        gain: Double? = null,
        aheadSeconds: Double = 0.4,
        automate: Map<String, PatPattern<Double>> = emptyMap()
    ): FaustPatternFeeder {
        val resolved = automate.mapNotNull { (label, values) ->
            val control = program.ui.writable.firstOrNull {
                it.label == label || it.qualifiedLabel == label || it.address == label
            }
            if (control == null) {
                println("Faust: '${program.name}' declares no control '$label' — not automating it")
                null
            } else {
                control to values
            }
        }.toMap()
        val feeder = FaustPatternFeeder(
            bank, pattern, cps, sustain, gain ?: bank.controls.gain?.init ?: 1.0, aheadSeconds, resolved
        )
        synchronized(feeders) { feeders += feeder }
        feeder.start()
        return feeder
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(feeders) { feeders.forEach { it.stop() } }
        bank.close()
    }

    override fun toString(): String = synthPrettyObject(
        this,
        "program" to program.name,
        "voices" to bank.voices,
        "shared" to sharedControls.map { it.label },
        "notesPlayed" to bank.notesPlayed,
        "gain" to gain
    )
}

/**
 * Turns a pattern into notes for a [FaustVoiceBank], one cycle at a time, ahead of the audio thread.
 *
 * The pattern query is not on the audio thread — a `PatPattern` query allocates, and a cycle of a big
 * composed pattern is not a 2.67ms job — but that costs no accuracy, because what it produces is *frames*.
 * The audio thread's only remaining job is to start a note on the frame it was given, which is integer
 * comparison. Being 400ms early and being exactly on time are the same sound.
 */
class FaustPatternFeeder internal constructor(
    private val bank: FaustVoiceBank,
    val pattern: PatPattern<String>,
    val cps: Double,
    val sustain: Double,
    val gain: Double,
    val aheadSeconds: Double,
    /** Shared controls patterned alongside the notes. */
    val automated: Map<FaustControl, PatPattern<Double>> = emptyMap()
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    /** Cycles fed so far. */
    @Volatile
    var cyclesFed: Long = 0L
        private set

    /** Events the pattern produced that carried no pitch, and so were skipped. */
    @Volatile
    var unpitched: Long = 0L
        private set

    internal fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = kotlin.concurrent.thread(isDaemon = true, name = "faust-pattern-feeder") {
            val origin = bank.frame
            val framesPerCycle = bank.sampleRate / cps
            var cycle = 0L
            val ahead = (aheadSeconds * bank.sampleRate)
            while (running.get()) {
                val horizon = bank.frame + ahead
                var fed = 0
                while (running.get() && origin + cycle * framesPerCycle <= horizon && fed < 64) {
                    val arc = PatArc(PatQ.of(cycle), PatQ.of(cycle + 1))
                    val queried = faustNotesForArc(
                        pattern, arc, cps, bank.sampleRate, sustain, gain, origin
                    )
                    bank.schedule(queried.notes)
                    for ((control, values) in automated) {
                        bank.automate(
                            faustParamAutomation(values, control, arc, cps, bank.sampleRate, origin)
                        )
                    }
                    if (queried.unpitched > 0) unpitched += queried.unpitched
                    cycle++
                    cyclesFed = cycle
                    fed++
                }
                runCatching { Thread.sleep(20L) }.onFailure { return@thread }
            }
        }
    }

    /** Stop feeding. Notes already scheduled still play; [FaustVoiceBank.allNotesOff] silences those. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        thread = null
    }

    val isRunning: Boolean get() = running.get()

    override fun toString(): String = synthPrettyObject(
        this, "cps" to cps, "cyclesFed" to cyclesFed, "running" to isRunning
    )
}

/**
 * A box that is a polyphonic instrument.
 *
 * ```kotlin
 * val inst = faustPoly("""
 * #faust
 * import("stdfaust.lib");
 * freq   = hslider("freq [unit:Hz]", 220, 40, 4000, 0.001);
 * gain   = hslider("gain", 0.3, 0, 1, 0.001);
 * gate   = button("gate");
 * cutoff = hslider("cutoff [unit:Hz]", 3000, 60, 12000, 1);
 * env = en.adsr(0.004, 0.09, 0.55, 0.06, gate);
 * process = os.sawtooth(freq) : fi.lowpass(3, cutoff) * env * gain <: _,_;
 * """, voices = 8)
 *
 * inst.play(pat("c e g <b a>").fast(2), cps = 1.0)
 * inst
 * ```
 *
 * Returning the launch as the box's last expression ties the sound to the box exactly as [faust] does: the
 * box stays lit while it plays, and stopping it stops the sound, the feeder threads and the DSP contexts.
 *
 * The program must declare `freq` and `gate` — Faust's own polyphonic convention — or this throws with a
 * message naming what it did declare. See [FaustPolyControls].
 *
 * @param voices how many instances of the program to run; the cost per block is roughly linear in this and
 *   `synth.faust.poly` prints the curve
 * @param controls generate a control group for the *shared* controls (everything except `freq`/`gate`/`gain`)
 * @param stealReleaseFrames how long a stolen voice holds its gate down before the incoming note attacks.
 *   The default starts the new note on time and **clicks** when the bank runs out of voices, by a measured
 *   amount; `(0.03 * 48000).toLong()` trades that click for a stolen note 30ms late. This is the one knob
 *   here with an audible consequence, so it is reachable from the one-liner rather than only from
 *   [FaustVoiceBank] — see its KDoc for both measurements.
 */
fun Box.faustPoly(
    source: String,
    voices: Int = 8,
    gain: Float = 1f,
    mixGain: Double = 1.0,
    controls: Boolean = true,
    channel: String = "controls",
    stealReleaseFrames: Long = FaustVoiceBank.RETRIGGER_FRAMES,
    lineOffset: Int = -1
): FaustPolyLaunch {
    val (body, headerLines) = stripFaustHeader(source)
    val offset = if (lineOffset >= 0) lineOffset else inferFaustLineOffset(this, source)

    val program = try {
        FaustEngine.compile(faustPolyModuleName(this), body)
    } catch (e: FaustCompileError) {
        val boxLine = offset + headerLines + ((e.line ?: 1) - 1)
        runCatching { error(e.faustMessage, "code", boxLine) }
        runCatching { println("Faust: ${e.raw.trim()}") }
        throw e
    }

    val engine = FieldAudioEngine.shared()
    val bank = FaustVoiceBank(
        program, engine.sampleRate, voices, mixGain, stealReleaseFrames = stealReleaseFrames
    )
    val launch = FaustPolyLaunch(program, bank, FaustPolyNode(bank), gain)

    if (controls && launch.sharedControls.isNotEmpty()) {
        faustPolyControls(launch, channel)
    }
    return launch
}

/**
 * Build the control group for a polyphonic launch: the shared controls only.
 *
 * `freq`, `gate` and `gain` are deliberately absent. They are per-note, written by the allocator on the
 * sample a note begins, and a slider that fought the notes for them would look broken. Everything else — a
 * cutoff, a resonance, a detune — is shared across the whole bank, which is what a synthesiser's front panel
 * is.
 */
fun Box.faustPolyControls(launch: FaustPolyLaunch, channel: String = "controls") {
    controlGroup(channel) {
        for (control in launch.sharedControls) {
            when {
                control.isToggle || control.isMomentary -> checkbox(control.displayLabel, control.init != 0.0) { on ->
                    launch.bank.setAll(control, if (on) 1.0 else 0.0)
                }

                else -> slider(
                    label = control.displayLabel,
                    min = control.min,
                    max = control.max,
                    step = if (control.step > 0.0) control.step else (control.max - control.min) / 200.0,
                    initialValue = control.init
                ) { value ->
                    launch.bank.setAll(control, value)
                }
            }
        }
    }
}

/**
 * Render a pattern through a polyphonic Faust program and put it in this box's output — a player, a
 * waveform, and the note count.
 *
 * The one-liner that makes the whole thing visible without an audio device, and the honest way to put a
 * polyphonic Faust figure in the documentation.
 *
 * @param automate shared controls patterned by label —
 *   `mapOf("cutoff" to pat("400 3000 900 6000").nums())`
 * @param stealReleaseFrames as [faustPoly]: the default starts a stolen note on time and clicks, and
 *   `(0.03 * 48000).toLong()` trades the click for lateness. Reachable here because this is the figure a
 *   reader is looking at when they want to *hear* the difference — render it twice and compare.
 * @return the render, so a box can measure it too — [faustOnsets], [faustFundamentalHz], [faustSpectrumPeaks]
 */
fun Box.showFaustPattern(
    source: String,
    pattern: PatPattern<String>,
    cycles: Int = 2,
    cps: Double = 1.0,
    voices: Int = 8,
    sustain: Double = FAUST_PATTERN_SUSTAIN,
    gain: Double? = null,
    automate: Map<String, PatPattern<Double>> = emptyMap(),
    stealReleaseFrames: Long = FaustVoiceBank.RETRIGGER_FRAMES,
    channel: String = "main",
    maxPoints: Int = 2400
): FaustPatternRender {
    val (body, headerLines) = stripFaustHeader(source)
    val offset = inferFaustLineOffset(this, source)
    val program = try {
        FaustEngine.compile(faustPolyModuleName(this), body)
    } catch (e: FaustCompileError) {
        runCatching { error(e.faustMessage, "code", offset + headerLines + ((e.line ?: 1) - 1)) }
        throw e
    }

    val automation = automate.flatMap { (label, values) ->
        val control = program.ui.writable.firstOrNull {
            it.label == label || it.qualifiedLabel == label || it.address == label
        }
        if (control == null) {
            println("Faust: '${program.name}' declares no control '$label' — not automating it")
            emptyList()
        } else {
            faustParamAutomation(values, control, cycles, cps, 48_000)
        }
    }

    val render = faustPatternRender(
        program, pattern, cycles = cycles, cps = cps, voices = voices, sampleRate = 48_000,
        sustain = sustain, gain = gain, automation = automation,
        stealReleaseFrames = stealReleaseFrames
    )
    display(
        channel,
        afield.four.audio.WavFloatArrayWriter.encodeStereo16(
            render.left, render.right, render.sampleRate, normalizePeak = 0.9f
        )
    )

    val stride = (render.frames / maxPoints).coerceAtLeast(1)
    val indices = 0 until render.frames step stride
    showSignals(
        "left" to indices.map { render.left[it] },
        "right" to indices.map { render.right[it] },
        x = indices.map { it.toDouble() / render.sampleRate },
        channel = channel,
        title = "${program.name} — ${render.notes.size} notes, ${render.voices} voices, " +
            "peak concurrency ${render.peakConcurrency}" +
            (if (render.automationApplied == 0L) "" else ", ${render.automationApplied} param changes"),
        xTitle = "seconds",
        yTitle = "amplitude"
    )
    return render
}

/** Faust uses the module name as an identifier and as its diagnostic prefix; keep it legal and stable. */
private fun faustPolyModuleName(box: Box): String {
    val id = box.id.filter { it.isLetterOrDigit() || it == '_' }
    return if (id.isEmpty()) "faustpolybox" else "poly_${id.take(40)}"
}

/**
 * Teach Field that a [FaustPolyLaunch] is something a box can be running.
 *
 * [registerFaustLaunchReturnValue]'s shape exactly, and it exists separately for the same reason: closing
 * the audio patch is not enough, because the bank owns a GraalVM context and possibly a feeder thread.
 */
@RunsAt(returnValueRegistration, priority = 10)
fun registerFaustPolyLaunchReturnValue() {
    ReturnValueRegistry.handlers += handler@{ inside, returnValue, strand, generation, launchEpoch, notify ->
        val launch = returnValue as? FaustPolyLaunch ?: return@handler false

        val handle = FieldAudioEngine.shared().register(audio(launch.node, gain = launch.gain))

        inMain {
            val job = kotlin.coroutines.coroutineContext[Job]!!
            if (!inside.registerLaunchJob(job, launchEpoch)) {
                handle.close()
                launch.close()
                notify(false, strand, generation)
                return@inMain
            }
            notify(true, strand, generation)
            try {
                while (inside.runGeneration[strand] == generation) {
                    yield()
                }
            } finally {
                handle.close()
                launch.close()
                inside.jobs.remove(job)
                notify(false, strand, generation)
            }
        }
        true
    }
}
