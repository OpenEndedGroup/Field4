@file:afield.CompletionCategory("Patterns")
package afield.four.synth

import afield.MarkType
import afield.NotationEvent
import afield.NotationSpace
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A drawn score, as a pattern: the bridge from `notation.space(...)` to everything that plays a
 * [PatPattern] — [patRenderStereo] offline, a `PatSynth` live.
 *
 * ```
 * val score = notation.space("score", x = 0.0..8.0, y = 84.0..48.0)   // 8 beats, MIDI 84 at the top
 * val tune = score.toPattern()                                         // one cycle = the whole score
 * showPatAudio(tune, cycles = 2, cps = 0.25)
 * ```
 *
 * The space's x axis is time and its y axis is MIDI pitch, and the whole x range is **one cycle**,
 * so `cps` sets the tempo: an eight-beat score at `cps = 0.25` is 120 beats a minute. Each mark
 * becomes notes by what kind of mark it is:
 *
 *  * a **rectangle, ellipse or diamond** is one note from its left edge to its right, at the pitch of
 *    its centre — a piano roll;
 *  * a **stroke or line that stays level** (its two ends within [levelTolerance] semitones) is one
 *    held note at the pitch it starts on;
 *  * a **stroke or line that moves** is read by cutting it: every [glideStep] beats along it, a
 *    `vertical` cut through that mark gives the pitch there, and that pitch sounds until the next
 *    cut. So a glissando is played as a run of short notes, as fine as [glideStep], because the
 *    voice holds one pitch per note;
 *  * **texts, frames and arrows** are left out: they are how a person labels a score, not notes.
 *
 * Pitches are not rounded: a note drawn a third of the way between two lines plays a third of a
 * semitone sharp. Round them yourself with `withValue` if the score should be tempered.
 */
fun NotationSpace.toPattern(glideStep: Double = 0.125, levelTolerance: Double = 0.5): PatPattern<String> =
    patTimed(
        notes(glideStep, levelTolerance).map { TimedNote(it.start - min(x.start, x.endInclusive), it.end - min(x.start, x.endInclusive), it.pitch, it.gain ?: 1.0) },
        beats = abs(x.endInclusive - x.start),
    )

/**
 * One note of a [patTimed] pattern: it sounds from [start] to [end], in beats from the start of
 * the cycle, at MIDI [note] (fractional notes are microtones), with [gain] 0..1.
 */
data class TimedNote(val start: Double, val end: Double, val note: Double, val gain: Double = 1.0)

/**
 * A pattern from notes with explicit times: one cycle is [beats] long and repeats, so a note at
 * beat 2 of an eight-beat cycle plays at a quarter of every cycle.
 *
 * ```
 * val tune = patTimed(listOf(TimedNote(0.0, 1.0, 60.0), TimedNote(1.0, 1.5, 64.0, gain = 0.5)), beats = 4.0)
 * showPatAudio(tune, cycles = 2, cps = 0.5)
 * ```
 *
 * This is the constructor to reach for when the times come from somewhere other than
 * mini-notation — a drawing, a MIDI file, a simulation. Times are snapped to a 960-per-beat grid
 * so every note lands on an exact rational time; the atom is the MIDI note and `gain` is an
 * annotation, which is what [patRenderStereo]'s voice reads.
 */
fun patTimed(notes: List<TimedNote>, beats: Double): PatPattern<String> {
    require(beats > 0) { "beats must be > 0" }
    val ticks = Math.round(beats * 960)
    val placed = notes.map { n ->
        val a = PatQ.of(Math.round(n.start * 960), ticks)
        val b = PatQ.of(Math.round(n.end * 960), ticks)
        Triple(a, if (b > a) b else a + PatQ.of(1, ticks), n)
    }
    return PatPattern { arc ->
        val out = ArrayList<PatEvent<String>>()
        for (cycle in floor(arc.start.toDouble()).toLong()..floor(arc.end.toDouble()).toLong()) {
            val c = PatQ.of(cycle)
            for ((a, b, n) in placed) {
                val whole = PatArc(a + c, b + c)
                val part = whole.intersect(arc) ?: continue
                out += PatEvent(part, "%.3f".format(Locale.ROOT, n.note), whole = whole,
                    annotations = mapOf("gain" to "%.3f".format(Locale.ROOT, n.gain)))
            }
        }
        out
    }
}

/**
 * One note a drawn score plays: [start] and [end] in the space's x units, [pitch] in its y units.
 * [gain] is the pen pressure where the mark recorded one (0..1), else null.
 */
class DrawnNote(val start: Double, val end: Double, val pitch: Double, val gain: Double?, val event: NotationEvent) {
    val duration: Double get() = end - start
    override fun toString(): String = "DrawnNote(%.3f..%.3f at %.2f)".format(start, end, pitch)
}

/** The notes [toPattern] plays, in start order — the same reading, before it becomes a pattern. */
fun NotationSpace.notes(glideStep: Double = 0.125, levelTolerance: Double = 0.5): List<DrawnNote> {
    require(glideStep > 0) { "glideStep must be > 0" }
    val out = ArrayList<DrawnNote>()
    for (e in events) {
        val kind = e.mark.kind
        if (kind == MarkType.TEXT || kind == MarkType.FRAME || kind == MarkType.ARROW) continue
        if (e.end <= e.start) continue
        val linear = kind == MarkType.STROKE || kind == MarkType.LINE
        if (!linear || abs(e.endValue - e.value) <= levelTolerance) {
            out += DrawnNote(e.start, e.end, e.value, pressureAt(e, (e.start + e.end) / 2), e)
            continue
        }
        var t = e.start
        while (t < e.end - 1e-9) {
            val next = min(t + glideStep, e.end)
            // The cut at the start of each step; at the very end of a stroke a cut can miss by
            // rounding, so fall back to the cut a hair inside it.
            val hit = vertical(t).firstOrNull { it.mark.id == e.mark.id && !it.isSpan }
                ?: vertical(max(e.start, t - 1e-6) + 1e-6).firstOrNull { it.mark.id == e.mark.id && !it.isSpan }
            if (hit != null) out += DrawnNote(t, next, hit.value, hit.pressure, e)
            t = next
        }
    }
    return out.sortedWith(compareBy({ it.start }, { it.pitch }))
}

private fun NotationSpace.pressureAt(e: NotationEvent, at: Double): Double? =
    vertical(at).firstOrNull { it.mark.id == e.mark.id }?.pressure
