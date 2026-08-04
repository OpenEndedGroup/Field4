/*
 Continuous (signal) patterns, and the discretisers that turn them back into events.

 `PatPattern` in TidalPatterns.kt is an *event* stream: query an arc, get back a list of
 things that happened inside it. A **signal** is the degenerate — and much more useful than
 it looks — case of exactly one event per query, spanning the whole arc, whose value is a
 function of time. Nothing new is needed in the type system: a signal is a `PatPattern<Double>`
 that always answers, so every combinator in the language already applies to it.

 Read the surface from the outside in:

 1. A continuous value:

        val lfo = sine()                 // 0..1, one cycle per pattern cycle
        val swept = saw().range(200, 4000)

 2. Sample it — nothing plays a continuous pattern directly, you discretise it:

        val steps = sine().segment(8)     // exactly 8 events per cycle
        val grid  = struct(euclid(3, 8), pat("bd"))

 3. Or hand it to the parameter algebra in PatAlgebra.kt, which samples it for you:

        pat("bd sd hh sd") with gain(sine().range(0.2, 1.0))

 The sampling convention
 -----------------------

 A continuous pattern queried with arc `a` returns one event spanning `a` whose value is
 `f(mid(a))`, and `mid(a)` is the **exact rational midpoint** `(a.start + a.end) / 2` — the
 conversion to `Double` happens only inside `f`. Three reasons this is the right convention:

 - it is what Tidal does, so ear-knowledge transfers;
 - the midpoint is the only choice under which `segment(n)` samples each segment at that
   segment's own centre, which makes `sine().segment(4)` symmetric about the cycle and stable
   under `rev()` — an asymmetric convention (start, or end) breaks both;
 - it is total. Every arc, however small, has a value, so a continuous pattern can never be
   "missed" by a query the way a zero-width event could be.

 `segment(n)` deliberately does NOT just sample at the query arc's midpoint. It builds the
 `1/n` grid itself, samples the source over each **whole** grid cell, and only then clips the
 result to the query arc — so the values you observe do not depend on how the caller chopped
 up the arc. `PatSignalsTest` asserts exactly that.
 */
package afield.four.synth

import java.math.BigInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

// ── the core: a signal is a pattern that answers every arc ────────────────────────────────

/** The exact rational midpoint of [arc]. Used as the sampling instant for every signal. */
fun patArcMidpoint(arc: PatArc): PatQ = (arc.start + arc.end) / PatQ.of(2)

/**
 * Builds a continuous pattern from a function of cycle time.
 *
 * The function receives the **exact** rational instant it is being asked about as well as its
 * `Double` approximation, so a signal can stay exact if it wants to (`quantise`, `run`, index
 * arithmetic) while trigonometric signals just use the `Double`.
 *
 * Queried with any arc, the result is a single event spanning that whole arc whose value is
 * sampled at [patArcMidpoint]. See the file header for why.
 *
 * The event is **analog** — `whole == null` — because a continuous signal has no note to start: ask
 * for a different window and you get a different event with a different value, so there is nothing
 * for a scheduler to strike once. [segment] is how you give a signal real onsets.
 */
fun signalOf(sample: (exact: PatQ, t: Double) -> Double): PatPattern<Double> = PatPattern { arc ->
    val mid = patArcMidpoint(arc)
    listOf(PatEvent(arc, sample(mid, mid.toDouble()), whole = null))
}

/** Builds a continuous pattern from a plain function of cycle time. `sig { t -> t * t }`. */
fun sig(sample: (t: Double) -> Double): PatPattern<Double> = signalOf { _, t -> sample(t) }

/** Constant continuous signal — the identity of the numeric operator family. */
fun constant(value: Number): PatPattern<Double> = signalOf { _, _ -> value.toDouble() }

// ── the standard signal family (all unipolar 0..1, like Tidal) ────────────────────────────

private fun cyclePhase(t: Double): Double {
    val f = t - floor(t)
    return if (f < 0.0) f + 1.0 else f
}

/** `0.5 + 0.5·sin(2πt)` — one full cycle of a sine per pattern cycle, in 0..1. */
fun sine(): PatPattern<Double> = sig { t -> 0.5 + 0.5 * sin(2.0 * PI * t) }

/** `0.5 + 0.5·cos(2πt)` — [sine] a quarter cycle early, in 0..1. */
fun cosine(): PatPattern<Double> = sig { t -> 0.5 + 0.5 * cos(2.0 * PI * t) }

/** A rising ramp `0 → 1` once per cycle. */
fun saw(): PatPattern<Double> = sig { t -> cyclePhase(t) }

/** A falling ramp `1 → 0` once per cycle. */
fun isaw(): PatPattern<Double> = sig { t -> 1.0 - cyclePhase(t) }

/** Up then down: `0 → 1 → 0` once per cycle. */
fun tri(): PatPattern<Double> = sig { t ->
    val p = cyclePhase(t)
    if (p < 0.5) 2.0 * p else 2.0 - 2.0 * p
}

/** `0` for the first half of each cycle, `1` for the second — exactly, on the rational midpoint. */
fun square(): PatPattern<Double> = signalOf { exact, _ ->
    val p = exact - PatQ.of(qFloor(exact))
    if (p < PatQ.of(1, 2)) 0.0 else 1.0
}

/** A one-shot linear rise `0 → 1` across the first cycle, then held at `1`. */
fun envL(): PatPattern<Double> = sig { t -> t.coerceIn(0.0, 1.0) }

/** A one-shot linear fall `1 → 0` across the first cycle, then held at `0`. */
fun envLR(): PatPattern<Double> = sig { t -> 1.0 - t.coerceIn(0.0, 1.0) }

/**
 * Deterministic continuous randomness in 0..1, hashed from the **exact** rational instant.
 *
 * Because the hash reads `n` and `d` of the reduced fraction rather than a `Double`, the same
 * musical position always produces the same number, on any machine, at any query granularity.
 */
fun rand(salt: Int = 0): PatPattern<Double> = signalOf { exact, _ ->
    toUnitDouble(splitmix64(patQHash(exact) xor (salt.toLong() * 0x9E3779B97F4A7C15uL.toLong())))
}

/** [rand] quantised to `0 until n` (as a `Double`, so it composes with the numeric algebra). */
fun irand(n: Int, salt: Int = 0): PatPattern<Double> {
    val count = max(1, n)
    return rand(salt).map { u -> floor(u * count).coerceIn(0.0, (count - 1).toDouble()) }
}

/**
 * Smooth value noise: `rand` sampled at integer cycles, smoothstep-interpolated between them.
 * Unlike [rand] it is continuous, so `perlin().segment(64)` reads as a slow drift rather than hash.
 */
fun perlin(salt: Int = 0): PatPattern<Double> = sig { t ->
    val i = floor(t)
    val f = t - i
    val a = toUnitDouble(splitmix64(i.toLong() * 0x2545F4914F6CDD1DuL.toLong() xor salt.toLong()))
    val b = toUnitDouble(splitmix64((i.toLong() + 1L) * 0x2545F4914F6CDD1DuL.toLong() xor salt.toLong()))
    val s = f * f * (3.0 - 2.0 * f)
    a + (b - a) * s
}

/** Hash of an exact rational — the reduced numerator and denominator, so it is granularity-free. */
private fun patQHash(q: PatQ): Long {
    var h = -0x340d631b7bdddcdbL
    for (part in listOf(q.n, q.d)) {
        for (b in part.toByteArray()) {
            h = (h xor b.toLong()) * 0x100000001B3L
        }
        h = (h xor 0x5DEECE66DL) * 0x100000001B3L
    }
    return h
}

// ── discrete generators that read like signals ────────────────────────────────────────────

/** `0 1 2 … n-1` as `n` events per cycle, exactly on the `1/n` grid. */
fun run(n: Int): PatPattern<Double> {
    val count = max(1, n)
    return fromCycle { subArc ->
        buildList {
            for (index in 0 until count) {
                val cell = PatArc(PatQ.of(index, count), PatQ.of(index + 1, count))
                val inter = cell.intersect(subArc) ?: continue
                add(PatEvent(inter, index.toDouble(), whole = cell))
            }
        }
    }
}

/** The `1/n` grid as a boolean pattern — the structure source for [struct] and [segment]. */
fun grid(n: Int): PatPattern<Boolean> = euclid(max(1, n), max(1, n))

// ── discretisers ──────────────────────────────────────────────────────────────────────────

/**
 * Slices each cycle into [n] equal cells and takes one value per cell from this pattern.
 *
 * The source is sampled over the **whole cell**, not over whatever arc the caller happened to
 * ask about, and only the emitted event is clipped — so `segment` is query-granularity
 * independent. For a continuous pattern that means each cell gets the value at its own centre.
 */
fun <A> PatPattern<A>.segment(n: Int): PatPattern<A> {
    val count = max(1, n)
    val cellWidth = PatQ.of(1, count)
    return PatPattern { absArc ->
        val firstCell = qFloorDiv(absArc.start, cellWidth)
        val lastCell = qCeilDiv(absArc.end, cellWidth) - 1
        val out = mutableListOf<PatEvent<A>>()
        for (cell in firstCell..lastCell) {
            val cellArc = PatArc(cellWidth * PatQ.of(cell), cellWidth * PatQ.of(cell + 1))
            val visible = cellArc.intersect(absArc) ?: continue
            val source = events(cellArc)
            val chosen = source.firstOrNull { it.arc.contains(patArcMidpoint(cellArc)) }
                ?: source.firstOrNull()
                ?: continue
            // The cell is the new event, so `cellArc` is its whole even when the query clips it.
            // This is exactly what discretising a signal means: analog in, real onsets out.
            out += PatEvent(visible, chosen.value, chosen.sourceSpan, chosen.annotations, chosen.additionalSpans, whole = cellArc)
        }
        out
    }
}

/** Alias for [segment], for readers coming from Tidal's `discretise`. */
fun <A> PatPattern<A>.discretise(n: Int): PatPattern<A> = segment(n)

/**
 * Takes rhythm from [structure] (its `true` events) and values from [values].
 *
 * `struct(euclid(3, 8), pat("bd"))` is a Euclidean bass drum; `struct(pat("t f t t"), pat("a b"))`
 * reads the boolean pattern as a mask. Value lookup is [appLeft], so a continuous `values`
 * pattern is sampled at the midpoint of each structural event.
 */
fun <A> struct(structure: PatPattern<Boolean>, values: PatPattern<A>): PatPattern<A> = PatPattern { arc ->
    structure.events(arc).mapNotNull { structural ->
        if (!structural.value) return@mapNotNull null
        val valued = values.patSampleOver(structural.arc) ?: return@mapNotNull null
        PatEvent(
            structural.arc,
            valued.value,
            valued.sourceSpan ?: structural.sourceSpan,
            structural.annotations + valued.annotations,
            structural.additionalSpans + valued.additionalSpans,
            // Rhythm comes from `structure`, so its whole is the one that matters — a sampled
            // continuous `values` contributes no onset of its own.
            whole = structural.whole
        )
    }
}

/** Boolean structure from mini-notation `t`/`f` (also `true`/`false`, `1`/`0`, `x`/`~`). */
fun bools(source: String): PatPattern<Boolean> = pat(source).map { token ->
    when (token.lowercase()) {
        "t", "true", "1", "x" -> true
        else -> false
    }
}

// ── numeric shaping ───────────────────────────────────────────────────────────────────────

/** Maps a 0..1 pattern onto `[lo, hi]`. Values outside 0..1 extrapolate, deliberately. */
fun PatPattern<Double>.range(lo: Number, hi: Number): PatPattern<Double> {
    val a = lo.toDouble()
    val b = hi.toDouble()
    return map { v -> a + v * (b - a) }
}

/** Maps a 0..1 pattern onto `[lo, hi]` exponentially — the right shape for a filter cutoff. */
fun PatPattern<Double>.rangex(lo: Number, hi: Number): PatPattern<Double> {
    val a = lo.toDouble().coerceAtLeast(1e-9)
    val b = hi.toDouble().coerceAtLeast(1e-9)
    return map { v -> a * Math.pow(b / a, v) }
}

/** Re-centres a 0..1 pattern onto -1..1. */
fun PatPattern<Double>.bipolar(): PatPattern<Double> = map { v -> v * 2.0 - 1.0 }

/** Rounds values to the nearest multiple of [step]. `sine().quantise(0.25)` gives 5 levels. */
fun PatPattern<Double>.quantise(step: Number): PatPattern<Double> {
    val s = step.toDouble()
    if (s <= 0.0) return this
    return map { v -> Math.round(v / s).toDouble() * s }
}

/** Rounds values to whole numbers — the bridge from a signal into MIDI pitch or a sample index. */
fun PatPattern<Double>.rounded(): PatPattern<Int> = map { v -> Math.round(v).toInt() }

/** Reads mini-notation atoms as numbers, dropping anything unparseable. */
fun PatPattern<String>.nums(): PatPattern<Double> = PatPattern { arc ->
    events(arc).mapNotNull { e ->
        e.value.trim().toDoubleOrNull()?.let { v -> e.withValue(v) }
    }
}

/** Renders numeric atoms back to strings, so a numeric pattern can drive `notes` / `with`. */
fun PatPattern<Double>.texts(): PatPattern<String> = map { patFormatNumber(it) }

/**
 * Canonical, locale-free short form for a parameter value. `1.0` → `1`, `0.25` → `0.25`.
 *
 * Named parameters live in `PatEvent.annotations`, which is a `Map<String, String>`, so every
 * numeric parameter round-trips through text. Nine decimal places is the chosen resolution: far
 * finer than any audible parameter (a cutoff in Hz, a gain in 0..1) and still short enough that
 * an event prints readably in a box output.
 */
fun patFormatNumber(v: Double): String {
    if (!v.isFinite()) return "0"
    if (v == Math.rint(v) && Math.abs(v) < 1e15) return v.toLong().toString()
    return java.math.BigDecimal(v).setScale(9, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString()
}

// ── exact rational grid helpers ───────────────────────────────────────────────────────────

/** `floor(value / step)` in exact rational arithmetic. */
internal fun qFloorDiv(value: PatQ, step: PatQ): Long = qFloor(value / step)

/** `ceil(value / step)` in exact rational arithmetic. */
internal fun qCeilDiv(value: PatQ, step: PatQ): Long = qCeil(value / step)

/** True when [q] is an exact integer. */
internal fun qIsInteger(q: PatQ): Boolean = q.d == BigInteger.ONE
