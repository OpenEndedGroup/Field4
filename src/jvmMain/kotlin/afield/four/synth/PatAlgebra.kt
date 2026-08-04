/*
 The parameter-application algebra: combining patterns of different types, and choosing
 whose rhythm survives.

 This is the piece that turns the event language in TidalPatterns.kt into a composition
 language. Everything else — signals, scales, chords, `off`, `jux` — is built on top of the
 three combinators here.

 Read the surface from the outside in:

 1. Structure choice. When two patterns meet, one of three things can happen to the rhythm:

        pat("0 1").nums() `|+|` pat("10 20 30").nums()   // structure from BOTH: 4 events
        pat("0 1").nums() `|+`  pat("10 20 30").nums()   // structure from LEFT:  2 events
        pat("0 1").nums() `+|`  pat("10 20 30").nums()   // structure from RIGHT: 3 events

    The bars mark where the structure comes from, exactly as in Tidal. Every operator also has
    a prose alias (`addBoth`, `addLeft`, `addRight`) for people who dislike backticks.

 2. Taking a value rather than combining:

        pat("bd sd") `#`  pat("a b c")   // left's rhythm, right's values (Tidal's `|>`)
        pat("bd sd") `|L` pat("a b c")   // left's rhythm, left's values  (Tidal's `|<`)

 3. Named parameters, which is what you actually reach for:

        val voice = pat("bd sd hh sd") with gain(sine().range(0.3, 1.0)) with pan(saw())

    A parameter is stored in the event's existing `annotations` map, so it survives every
    transform in the language, arrives intact in `PatScheduledEvent`, and reads back typed:

        voice.peek(4).forEach { println(it.value to it.num("gain")) }

 Structure choice, precisely
 ---------------------------

 - `appBoth` — the cross product of every pair of intersecting events; each output arc is the
   *intersection*. This is the only one of the three that can create events.

 - `appLeft` — **exactly the left pattern's events survive**, one output per input. Each left
   event queries the right pattern over its own arc and takes the right event containing the
   left arc's start.

 - `appRight` — the mirror image.

 The "query the right over the left's arc, latch at the left's start" rule is deliberate, and
 it is the one place this language knowingly diverges from Tidal. Tidal splits the left event
 into fragments, one per overlapping right event, because its events carry both a `whole` and a
 `part`. `PatEvent` has a single arc, so fragmenting here would silently multiply notes: ask for
 four kicks with a sixteen-step filter sweep and you would get sixty-four kicks. Instead:

 - a **discrete** right pattern latches its value at note onset, which is what a synth voice
   does anyway (parameters are read when the envelope is triggered);
 - a **continuous** right pattern answers the left event's whole arc with a single event whose
   value is the midpoint of that note, so `gain(sine())` gives one gain per note, taken at the
   middle of the note.

 One implementation, both behaviours, and note count is preserved. `PatAlgebraTest` asserts the
 cardinality laws and the midpoint sampling in exact rational cycle space.

 Because `appLeft` never fragments, sub-cycle queries sample the *clipped* fragment's start.
 Every scheduling path in the language (`eventSequence`, `scheduled`, `peek`) queries whole
 cycles, so this only matters if you call `events(...)` with a partial arc by hand.
 */
@file:Suppress("DANGEROUS_CHARACTERS") // `|+|` and friends are JVM method names, never file names.

package afield.four.synth

// ── sampling one value out of a pattern over an arc ────────────────────────────────────────

/**
 * Queries this pattern over [arc] and returns the single event that best represents it: the one
 * containing [instant] (the arc's start by default), else the first.
 *
 * This is the primitive behind [appLeft], [struct] and the whole parameter surface. For a
 * continuous pattern the query returns exactly one event spanning [arc], so the answer is the
 * signal's value at the arc's midpoint.
 */
fun <A> PatPattern<A>.patSampleOver(arc: PatArc, instant: PatQ = arc.start): PatEvent<A>? {
    val candidates = events(arc)
    if (candidates.isEmpty()) return null
    return candidates.firstOrNull { it.arc.contains(instant) } ?: candidates.first()
}

// ── the three structure-choice combinators ─────────────────────────────────────────────────

/** Structure from **both**: every pair of intersecting events, each output arc the intersection. */
fun <A, B, C> PatPattern<A>.appBoth(other: PatPattern<B>, combine: (A, B) -> C): PatPattern<C> =
    PatPattern { arc ->
        val rights = other.events(arc)
        val out = mutableListOf<PatEvent<C>>()
        for (left in events(arc)) {
            for (right in rights) {
                val inter = left.arc.intersect(right.arc) ?: continue
                out += PatEvent(
                    inter,
                    combine(left.value, right.value),
                    left.sourceSpan,
                    left.annotations + right.annotations,
                    left.additionalSpans + listOfNotNull(right.sourceSpan) + right.additionalSpans,
                    // Structure from both, so the event really runs for as long as both do. Analog
                    // on either side makes the result analog: a signal has no onset to inherit.
                    whole = if (left.whole == null || right.whole == null) null
                            else left.whole.intersect(right.whole) ?: inter
                )
            }
        }
        out
    }

/** Structure from the **left**: exactly the left's events, each taking a right value at its onset. */
fun <A, B, C> PatPattern<A>.appLeft(other: PatPattern<B>, combine: (A, B) -> C): PatPattern<C> =
    PatPattern { arc ->
        events(arc).mapNotNull { left ->
            val right = other.patSampleOver(left.arc) ?: return@mapNotNull null
            PatEvent(
                left.arc,
                combine(left.value, right.value),
                left.sourceSpan,
                left.annotations + right.annotations,
                left.additionalSpans + listOfNotNull(right.sourceSpan) + right.additionalSpans,
                // Structure from the left: the left's events, unchanged in time, whole included.
                whole = left.whole
            )
        }
    }

/** Structure from the **right**: exactly the right's events, each taking a left value at its onset. */
fun <A, B, C> PatPattern<A>.appRight(other: PatPattern<B>, combine: (A, B) -> C): PatPattern<C> =
    PatPattern { arc ->
        other.events(arc).mapNotNull { right ->
            val left = patSampleOver(right.arc) ?: return@mapNotNull null
            PatEvent(
                right.arc,
                combine(left.value, right.value),
                right.sourceSpan ?: left.sourceSpan,
                right.annotations + left.annotations,
                right.additionalSpans + listOfNotNull(left.sourceSpan) + left.additionalSpans,
                // Structure from the right, so the right's whole is the one that survives.
                whole = right.whole
            )
        }
    }

// ── numeric operators: bars mark where the structure comes from ────────────────────────────

/** Structure from both, values added. */
infix fun PatPattern<Double>.`|+|`(other: PatPattern<Double>): PatPattern<Double> = appBoth(other) { a, b -> a + b }

/** Structure from the left, values added. */
infix fun PatPattern<Double>.`|+`(other: PatPattern<Double>): PatPattern<Double> = appLeft(other) { a, b -> a + b }

/** Structure from the right, values added. */
infix fun PatPattern<Double>.`+|`(other: PatPattern<Double>): PatPattern<Double> = appRight(other) { a, b -> a + b }

/** Structure from both, values subtracted. */
infix fun PatPattern<Double>.`|-|`(other: PatPattern<Double>): PatPattern<Double> = appBoth(other) { a, b -> a - b }

/** Structure from the left, values subtracted. */
infix fun PatPattern<Double>.`|-`(other: PatPattern<Double>): PatPattern<Double> = appLeft(other) { a, b -> a - b }

/** Structure from the right, values subtracted. */
infix fun PatPattern<Double>.`-|`(other: PatPattern<Double>): PatPattern<Double> = appRight(other) { a, b -> a - b }

/** Structure from both, values multiplied. */
infix fun PatPattern<Double>.`|*|`(other: PatPattern<Double>): PatPattern<Double> = appBoth(other) { a, b -> a * b }

/** Structure from the left, values multiplied. */
infix fun PatPattern<Double>.`|*`(other: PatPattern<Double>): PatPattern<Double> = appLeft(other) { a, b -> a * b }

/** Structure from the right, values multiplied. */
infix fun PatPattern<Double>.`*|`(other: PatPattern<Double>): PatPattern<Double> = appRight(other) { a, b -> a * b }

/**
 * Structure from both, values divided (division by zero yields `0`, not an exception).
 *
 * Tidal spells this `|/|`, but `/` cannot appear in a Kotlin identifier, so the three division
 * operators read `|over|` / `|over` / `over|`. Everything else in the family keeps its Tidal
 * spelling exactly.
 */
infix fun PatPattern<Double>.`|over|`(other: PatPattern<Double>): PatPattern<Double> = appBoth(other, ::patSafeDiv)

/** Structure from the left, values divided. Tidal's `|/`. */
infix fun PatPattern<Double>.`|over`(other: PatPattern<Double>): PatPattern<Double> = appLeft(other, ::patSafeDiv)

/** Structure from the right, values divided. Tidal's `/|`. */
infix fun PatPattern<Double>.`over|`(other: PatPattern<Double>): PatPattern<Double> = appRight(other, ::patSafeDiv)

/** Prose alias for the `|over|` operator. */
infix fun PatPattern<Double>.divBoth(other: PatPattern<Double>): PatPattern<Double> = this.`|over|`(other)

/** Prose alias for the `|over` operator. */
infix fun PatPattern<Double>.divLeft(other: PatPattern<Double>): PatPattern<Double> = this.`|over`(other)

/** Prose alias for the `over|` operator. */
infix fun PatPattern<Double>.divRight(other: PatPattern<Double>): PatPattern<Double> = this.`over|`(other)

/** Structure from both, values by modulo. */
infix fun PatPattern<Double>.`|%|`(other: PatPattern<Double>): PatPattern<Double> = appBoth(other, ::patSafeMod)

/** Structure from the left, values by modulo. */
infix fun PatPattern<Double>.`|%`(other: PatPattern<Double>): PatPattern<Double> = appLeft(other, ::patSafeMod)

/** Structure from the right, values by modulo. */
infix fun PatPattern<Double>.`%|`(other: PatPattern<Double>): PatPattern<Double> = appRight(other, ::patSafeMod)

private fun patSafeDiv(a: Double, b: Double): Double = if (b == 0.0) 0.0 else a / b

private fun patSafeMod(a: Double, b: Double): Double {
    if (b == 0.0) return 0.0
    val m = a % b
    return if (m < 0.0 != b < 0.0 && m != 0.0) m + b else m
}

// ── prose aliases, for code that would rather not use backticks ─────────────────────────────

/** Prose alias for the `|+|` operator. */
infix fun PatPattern<Double>.addBoth(other: PatPattern<Double>): PatPattern<Double> = this.`|+|`(other)

/** Prose alias for the `|+` operator. */
infix fun PatPattern<Double>.addLeft(other: PatPattern<Double>): PatPattern<Double> = this.`|+`(other)

/** Prose alias for the `+|` operator. */
infix fun PatPattern<Double>.addRight(other: PatPattern<Double>): PatPattern<Double> = this.`+|`(other)

/** Prose alias for the `|*|` operator. */
infix fun PatPattern<Double>.mulBoth(other: PatPattern<Double>): PatPattern<Double> = this.`|*|`(other)

/** Prose alias for the `|*` operator. */
infix fun PatPattern<Double>.mulLeft(other: PatPattern<Double>): PatPattern<Double> = this.`|*`(other)

/** Prose alias for the `*|` operator. */
infix fun PatPattern<Double>.mulRight(other: PatPattern<Double>): PatPattern<Double> = this.`*|`(other)

// ── take-value operators, for any value type ───────────────────────────────────────────────

/*
 The take-value family. Tidal writes these `|>` `>|` `|<` `<|`, where the bar says which side the
 *structure* comes from and the arrow says which side the *value* comes from. `<` and `>` are
 illegal in a Kotlin identifier, so the arrow becomes an `L` or an `R`:

     Tidal   here      structure   value
     |>      `|R`      left        right     (Tidal also spells this `#`; so does this file)
     >|      `R|`      right       right
     |<      `|L`      left        left
     <|      `L|`      right       left

 The bar keeps its Tidal meaning in every case, so the muscle memory that matters transfers.
 */

/** Left's rhythm, right's values. Tidal's `|>`, also spelled `#`. */
infix fun <A, B> PatPattern<A>.`|R`(other: PatPattern<B>): PatPattern<B> = appLeft(other) { _, b -> b }

/** Left's rhythm, right's values — Tidal's `#`, the operator you reach for most. */
infix fun <A, B> PatPattern<A>.`#`(other: PatPattern<B>): PatPattern<B> = this.`|R`(other)

/** Right's rhythm, right's values. Tidal's `>|`. */
infix fun <A, B> PatPattern<A>.`R|`(other: PatPattern<B>): PatPattern<B> = appRight(other) { _, b -> b }

/** Left's rhythm, left's values — the right pattern only gates whether an event exists. Tidal's `|<`. */
infix fun <A, B> PatPattern<A>.`|L`(other: PatPattern<B>): PatPattern<A> = appLeft(other) { a, _ -> a }

/** Right's rhythm, left's values — resample this pattern onto someone else's grid. Tidal's `<|`. */
infix fun <A, B> PatPattern<A>.`L|`(other: PatPattern<B>): PatPattern<A> = appRight(other) { a, _ -> a }

/** Prose alias for the `|R` / `#` operator — "keep my rhythm, take their values". */
infix fun <A, B> PatPattern<A>.valuesFrom(other: PatPattern<B>): PatPattern<B> = this.`|R`(other)

/** Prose alias for the `L|` operator — "keep my values, take their rhythm". */
infix fun <A, B> PatPattern<A>.rhythmFrom(other: PatPattern<B>): PatPattern<A> = this.`L|`(other)

// ── named parameters, riding on PatEvent.annotations ───────────────────────────────────────

/**
 * A named parameter pattern waiting to be attached to some atoms.
 *
 * Deliberately not a new event type: [with] resolves it through [appLeft] and writes the result
 * into the event's existing `annotations` map, so parameters survive `rev`, `fast`, `every`,
 * scheduling, and the editor-highlighting span bookkeeping without any of those knowing about it.
 */
class PatParam internal constructor(val key: String, internal val values: PatPattern<String>) {
    override fun toString(): String = "PatParam($key)"
}

/** A named parameter whose values come from a numeric pattern. */
fun param(key: String, values: PatPattern<Double>): PatParam = PatParam(key, values.texts())

/** A named parameter whose values come from mini-notation or any string pattern. */
fun paramOf(key: String, values: PatPattern<String>): PatParam = PatParam(key, values)

/** A named parameter with one constant value for every event. */
fun param(key: String, value: Number): PatParam = PatParam(key, pure(patFormatNumber(value.toDouble())))

/** A named parameter parsed from mini-notation. `param("gain", "0.3 1 0.6 1")`. */
fun param(key: String, source: String): PatParam = PatParam(key, pat(source))

/**
 * Attaches [params] to this pattern's events.
 *
 * Structure comes from the left — the atoms — always. Each event samples every parameter over
 * its own arc, so a continuous parameter is read at the note's midpoint and a discrete one is
 * latched at its onset. Events for which a parameter has no value keep their other parameters
 * and simply lack that key.
 */
fun <A> PatPattern<A>.with(vararg params: PatParam): PatPattern<A> {
    if (params.isEmpty()) return this
    return PatPattern { arc ->
        events(arc).map { event ->
            var annotations = event.annotations
            var spans = event.additionalSpans
            for (p in params) {
                val sampled = p.values.patSampleOver(event.arc) ?: continue
                annotations = annotations + (p.key to sampled.value)
                sampled.sourceSpan?.let { spans = spans + it }
            }
            event.copy(annotations = annotations, additionalSpans = spans)
        }
    }
}

/** Single-parameter form, so `with` reads as an infix operator: `pat("bd sd") with gain(sine())`. */
infix fun <A> PatPattern<A>.with(param: PatParam): PatPattern<A> = with(*arrayOf(param))

/** Removes named parameters from every event. */
fun <A> PatPattern<A>.without(vararg keys: String): PatPattern<A> = PatPattern { arc ->
    events(arc).map { it.copy(annotations = it.annotations - keys.toSet()) }
}

/** Reads a parameter back as a number, or [default] when absent or unparseable. */
fun <A> PatScheduledEvent<A>.num(key: String, default: Double = 0.0): Double =
    annotations[key]?.trim()?.toDoubleOrNull() ?: default

/** Reads a parameter back as an integer, or [default] when absent or unparseable. */
fun <A> PatScheduledEvent<A>.int(key: String, default: Int = 0): Int =
    annotations[key]?.trim()?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.let(Math::round)?.toInt() } ?: default

/** Reads a parameter back as a number, or [default] when absent or unparseable. */
fun <A> PatEvent<A>.num(key: String, default: Double = 0.0): Double =
    annotations[key]?.trim()?.toDoubleOrNull() ?: default

// ── the well-known parameter names ─────────────────────────────────────────────────────────

/** Amplitude, conventionally 0..1. */
fun gain(values: PatPattern<Double>): PatParam = param("gain", values)

/** Amplitude from mini-notation. */
fun gain(source: String): PatParam = param("gain", pat(source).nums())

/** Amplitude, constant. */
fun gain(value: Number): PatParam = param("gain", value)

/** Stereo position, 0 = left, 1 = right. */
fun pan(values: PatPattern<Double>): PatParam = param("pan", values)

/** Stereo position from mini-notation. */
fun pan(source: String): PatParam = param("pan", pat(source).nums())

/** Stereo position, constant. */
fun pan(value: Number): PatParam = param("pan", value)

/** Playback rate / transposition ratio. */
fun speed(values: PatPattern<Double>): PatParam = param("speed", values)

/** Playback rate, constant. */
fun speed(value: Number): PatParam = param("speed", value)

/** Filter cutoff in Hz — reach for `rangex` rather than `range` when sweeping it. */
fun cutoff(values: PatPattern<Double>): PatParam = param("cutoff", values)

/** Filter cutoff, constant. */
fun cutoff(value: Number): PatParam = param("cutoff", value)

/** Filter resonance, conventionally 0..1. */
fun resonance(values: PatPattern<Double>): PatParam = param("resonance", values)

/** Reverb amount, conventionally 0..1. */
fun room(values: PatPattern<Double>): PatParam = param("room", values)

/** Reverb amount, constant. */
fun room(value: Number): PatParam = param("room", value)

/** Waveshaper drive, conventionally 0..1. */
fun shape(values: PatPattern<Double>): PatParam = param("shape", values)

/** Sample index within a named bank — the parameter behind mini-notation's `bd:3`. */
fun n(values: PatPattern<Double>): PatParam = param("n", values)

/** Sample index, constant. */
fun n(value: Number): PatParam = param("n", value)

/** MIDI note number as a parameter, for atoms that are names rather than numbers. */
fun note(values: PatPattern<Double>): PatParam = param("note", values)

/** Event length as a fraction of its arc, 0..1. */
fun sustain(values: PatPattern<Double>): PatParam = param("sustain", values)

/** Event length as a fraction of its arc, constant. */
fun sustain(value: Number): PatParam = param("sustain", value)
