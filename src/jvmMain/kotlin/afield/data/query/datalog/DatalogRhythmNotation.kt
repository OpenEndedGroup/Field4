package afield.data.query.datalog

import afield.ui.notation.NotationEvent
import afield.ui.notation.NotationSpace
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The drawing as rhythm facts: every mark in [this] space becomes `hit(P, Voice, Mark)`.
 *
 * Read the space with steps across and voices down — `notation.space("drum", x = 0.0..16.0,
 * y = 0.0..3.0)` for a 16-step bar and three lanes — and pass the lane names top to bottom. A mark's
 * left edge picks its step `P` (the cell it starts in, `0 <= P < steps`), the height at its start
 * picks its lane, and its label (or id, for an unnamed mark) is the third column, so `why()` on a
 * note it caused names the mark.
 *
 * `P` is a position in the bar, not an absolute step, so a rule joins it to time with `pos`:
 *
 * ```
 * event(V, 60, 90, T) :- hit(P, V, _), pos(T, P).
 * ```
 *
 * A mark wider than one step is also `span(Voice, A, B, Mark)`, covering steps `A <= P < B`, which a
 * rule can use as a gate: `event(hh, 42, 50, T) :- every(T, 2, 1), pos(T, P), span(hats, A, B, _), P >= A, P < B.`
 * Marks whose start lies outside the space, or in no lane, are skipped.
 */
fun NotationSpace.rhythmHits(voices: List<String>, steps: Int = 16): List<List<Any>> =
    rhythmFacts(events, x, y, voices, steps).first

/** The `span(Voice, A, B, Mark)` rows of [rhythmHits]: marks at least a step wide. */
fun NotationSpace.rhythmSpans(voices: List<String>, steps: Int = 16): List<List<Any>> =
    rhythmFacts(events, x, y, voices, steps).second

/**
 * Feed [space] into this rhythm as `hit/3` and `span/4`, read again each bar — draw, move or erase a
 * mark and the next bar plays it. See [rhythmHits].
 */
fun DlRhythm.notationHits(space: NotationSpace, voices: List<String>): DlRhythm {
    facts("hit") { space.rhythmHits(voices, stepsPerCycle) }
    facts("span") { space.rhythmSpans(voices, stepsPerCycle) }
    return this
}

/** One mark read as rhythm: the pure part of [rhythmHits], testable without a drawing. */
internal data class DrawnNote(val start: Double, val end: Double, val value: Double, val mark: String)

internal fun rhythmFacts(
    events: List<NotationEvent>,
    x: ClosedFloatingPointRange<Double>,
    y: ClosedFloatingPointRange<Double>,
    voices: List<String>,
    steps: Int,
): Pair<List<List<Any>>, List<List<Any>>> =
    rhythmFactsOf(events.map { DrawnNote(it.start, it.end, it.value, it.mark.label?.trim()?.ifEmpty { null } ?: it.mark.id) }, x, y, voices, steps)

internal fun rhythmFactsOf(
    notes: List<DrawnNote>,
    x: ClosedFloatingPointRange<Double>,
    y: ClosedFloatingPointRange<Double>,
    voices: List<String>,
    steps: Int,
): Pair<List<List<Any>>, List<List<Any>>> {
    require(voices.isNotEmpty()) { "rhythmHits needs at least one voice name" }
    val xl = min(x.start, x.endInclusive); val xh = max(x.start, x.endInclusive)
    val yl = min(y.start, y.endInclusive); val yh = max(y.start, y.endInclusive)
    // Steps across the space and lanes down it, whatever units the space was read in.
    fun step(sx: Double) = floor((sx - xl) / (xh - xl) * steps + 1e-9).toLong()
    val hits = ArrayList<List<Any>>()
    val spans = ArrayList<List<Any>>()
    for (n in notes) {
        val lane = floor((n.value - yl) / (yh - yl) * voices.size).toInt()
        if (lane !in voices.indices) continue
        val p = step(n.start)
        if (p !in 0 until steps) continue
        val voice = voices[lane]
        hits += listOf(p, voice, n.mark)
        val endStep = min(step(n.end).coerceAtLeast(p), steps.toLong())
        if (endStep - p >= 1) spans += listOf(voice, p, endStep, n.mark)
    }
    return hits.distinct() to spans.distinct()
}
