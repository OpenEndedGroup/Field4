/*
 Compositional structure: the transforms that build a piece out of a pattern, and the pitch
 vocabulary — scales, named chords, and voice leading.

 TidalPatterns.kt gives you one line of music and the means to vary it. PatAlgebra.kt lets two
 lines meet. This file is what you reach for when you want *an arrangement*: the same material
 displaced, superimposed, rotated, split across the stereo field, and spelled in a key.

 Read the surface from the outside in:

 1. Displace and layer:

        pat("bd ~ sd ~")
            .off(PatQ.of(1, 8)) { it with gain(0.4) }     // a ghost note an eighth later
            .superimpose { it.fast(2) }
            .jux { it.rev() }                              // left ear forward, right ear reversed

 2. Rotate and hold:

        pat("a b c d").iter(4)      // cycle 0: a b c d, cycle 1: b c d a, …
        pat("a b c d").linger(PatQ.of(1, 2))   // just "a b", twice

 3. Choose per cycle:

        randcat(pat("bd*4"), pat("bd sd"), pat("~ sd"))
        wrandcat(pat("bd*4") to 3.0, pat("~ sd") to 1.0)
        pat("a b c d").shuffle(4)

 4. Spell it in a key:

        run(8).scale("minor", root = 60)                   // eight degrees of C minor
        pat("0 3 4 3").nums().scale("dorian", root = 62)

 5. Chords, and the part Tidal has no answer for — voice leading:

        val progression = chords("c'maj7 a'min7 d'min7 g'dom7")
        progression.voiceLead(center = 60).notesOf()

    `voiceLead` picks each chord's inversion and octave to minimise the total semitone movement
    of the voices from the previous chord. Played as block chords straight from their roots, that
    progression moves **83** semitones; voice-led it moves **9**, and the harmony is unchanged —
    every chord is still the same set of pitch classes. `PatCompositionTest` computes both numbers
    live rather than asserting a remembered one.
 */
package afield.four.synth

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ── displacement and layering ──────────────────────────────────────────────────────────────

/**
 * Stacks this pattern with a copy shifted [by] cycles later and passed through [transform].
 *
 * The canonical use is a quieter echo: `off(PatQ.of(1, 8)) { it with gain(0.4) }`.
 */
fun <A> PatPattern<A>.off(by: PatQ, transform: (PatPattern<A>) -> PatPattern<A>): PatPattern<A> =
    stack(this, transform(delay(by)))

/** [off] with the shift given as a fraction, `off(1, 8) { … }`. */
fun <A> PatPattern<A>.off(numerator: Int, denominator: Int, transform: (PatPattern<A>) -> PatPattern<A>): PatPattern<A> =
    off(PatQ.of(numerator, denominator), transform)

/** Stacks this pattern with a transformed copy of itself, in place. */
fun <A> PatPattern<A>.superimpose(transform: (PatPattern<A>) -> PatPattern<A>): PatPattern<A> =
    stack(this, transform(this))

/** Stacks one transformed copy per [transforms] entry — `superimpose` for more than two voices. */
fun <A> PatPattern<A>.layer(vararg transforms: (PatPattern<A>) -> PatPattern<A>): PatPattern<A> =
    if (transforms.isEmpty()) this else stack(*transforms.map { it(this) }.toTypedArray())

/**
 * Hard-pans this pattern left and a transformed copy right, via the `pan` parameter.
 *
 * `jux { it.rev() }` is the classic: the same material forwards in one ear and backwards in the
 * other. Use [juxBy] for a narrower image.
 */
fun <A> PatPattern<A>.jux(transform: (PatPattern<A>) -> PatPattern<A>): PatPattern<A> = juxBy(1.0, transform)

/** [jux] with a stereo width in 0..1 — `juxBy(0.5) { … }` keeps both copies near the centre. */
fun <A> PatPattern<A>.juxBy(width: Number, transform: (PatPattern<A>) -> PatPattern<A>): PatPattern<A> {
    val w = width.toDouble().coerceIn(0.0, 1.0)
    return stack(
        this with pan(0.5 - w / 2.0),
        transform(this) with pan(0.5 + w / 2.0)
    )
}

// ── rotation and holding ───────────────────────────────────────────────────────────────────

/**
 * Advances the pattern by `1/n` of a cycle on each successive cycle, so it takes [n] cycles to
 * come back round. `pat("a b c d").iter(4)` reads `a b c d`, `b c d a`, `c d a b`, `d a b c`.
 */
fun <A> PatPattern<A>.iter(n: Int): PatPattern<A> = iterStep(n, forward = true)

/** [iter] the other way round. */
fun <A> PatPattern<A>.iterBack(n: Int): PatPattern<A> = iterStep(n, forward = false)

private fun <A> PatPattern<A>.iterStep(n: Int, forward: Boolean): PatPattern<A> {
    val steps = max(1, n)
    return fromCycleIndexed { subArc, tile ->
        val shift = PatQ.of(((tile % steps) + steps) % steps, steps.toLong())
        val moved = delay(if (forward) -shift else shift)
        val base = PatQ.of(tile)
        moved.events(subArc.shift(base)).map { it.shift(-base) }
    }
}

/**
 * Plays only the first [fraction] of each cycle, repeated to fill the cycle.
 *
 * `linger(PatQ.of(1, 4))` on `"a b c d"` gives `a a a a`. Exact: the repeat boundaries are
 * rational, so the last repeat is clipped rather than rounded.
 */
fun <A> PatPattern<A>.linger(fraction: PatQ): PatPattern<A> {
    if (fraction <= PatQ.ZERO || fraction >= PatQ.ONE) return this
    return fromCycleIndexed { subArc, tile ->
        val base = PatQ.of(tile)
        val out = mutableListOf<PatEvent<A>>()
        var offset = PatQ.ZERO
        while (offset < PatQ.ONE) {
            val window = PatArc(offset, minQ(offset + fraction, PatQ.ONE))
            val visible = window.intersect(subArc)
            if (visible != null) {
                val sourceArc = PatArc(base + (visible.start - offset), base + (visible.end - offset))
                out += events(sourceArc).map { event ->
                    PatEvent(
                        PatArc(event.arc.start - base + offset, event.arc.end - base + offset),
                        event.value, event.sourceSpan, event.annotations, event.additionalSpans
                    )
                }
            }
            offset += fraction
        }
        out
    }
}

/** [linger] with the fraction given as `numerator/denominator`. */
fun <A> PatPattern<A>.linger(numerator: Int, denominator: Int): PatPattern<A> =
    linger(PatQ.of(numerator, denominator))

/**
 * Repeats each event [n] times inside its own arc. Tidal's `ply`.
 *
 * This is [stutter] with no gap; the name is here because `ply` is what the Tidal documentation
 * calls it and searching for it should find something.
 */
fun <A> PatPattern<A>.ply(n: Int): PatPattern<A> = stutter(n, PatQ.ZERO)

/**
 * Rotates the *values* of each cycle's events by [n] while leaving the rhythm alone.
 *
 * `pat("a b c d").rot(1)` keeps four evenly spaced events but plays `b c d a`. Compare
 * [iter], which moves the rhythm too.
 */
fun <A> PatPattern<A>.rot(n: Int): PatPattern<A> = mapEventsPerCycle { _, events ->
    if (events.size <= 1) return@mapEventsPerCycle events
    val ordered = events.sortedWith(compareBy({ it.arc.start }, { it.arc.end }))
    val size = ordered.size
    val shift = ((n % size) + size) % size
    ordered.mapIndexed { index, event ->
        val donor = ordered[(index + shift) % size]
        PatEvent(event.arc, donor.value, donor.sourceSpan, donor.annotations, donor.additionalSpans)
    }
}

/**
 * Slices each cycle into [n] equal parts and plays them in a deterministic random permutation
 * that changes every cycle. Unlike [rot] this really is a permutation — no part is dropped or
 * repeated within a cycle.
 */
fun <A> PatPattern<A>.shuffle(n: Int, salt: Int = 0): PatPattern<A> {
    val parts = max(1, n)
    return fromCycleIndexed { subArc, tile ->
        val order = patPermutation(parts, tile, salt)
        val base = PatQ.of(tile)
        val out = mutableListOf<PatEvent<A>>()
        for (slot in 0 until parts) {
            val window = PatArc(PatQ.of(slot, parts), PatQ.of(slot + 1, parts))
            val visible = window.intersect(subArc) ?: continue
            val source = PatArc(PatQ.of(order[slot], parts), PatQ.of(order[slot] + 1, parts))
            val delta = source.start - window.start
            val sourceArc = PatArc(base + visible.start + delta, base + visible.end + delta)
            out += events(sourceArc).map { event ->
                PatEvent(
                    PatArc(event.arc.start - base - delta, event.arc.end - base - delta),
                    event.value, event.sourceSpan, event.annotations, event.additionalSpans
                )
            }
        }
        out
    }
}

/** A deterministic permutation of `0 until n` for cycle [cycle] — Fisher-Yates over splitmix64. */
internal fun patPermutation(n: Int, cycle: Long, salt: Int): IntArray {
    val order = IntArray(n) { it }
    var state = splitmix64(cycle xor (salt.toLong() shl 23) xor (n.toLong() shl 41))
    for (i in n - 1 downTo 1) {
        state = splitmix64(state)
        val j = floorMod(state, i + 1)
        val tmp = order[i]; order[i] = order[j]; order[j] = tmp
    }
    return order
}

// ── per-cycle choice ───────────────────────────────────────────────────────────────────────

/** Plays one of [patterns] per cycle, chosen deterministically at random. */
fun <A> randcat(vararg patterns: PatPattern<A>, salt: Int = 0): PatPattern<A> {
    if (patterns.isEmpty()) return silence()
    return fromCycleIndexed { subArc, tile ->
        val index = floorMod(splitmix64(tile xor (salt.toLong() shl 29)), patterns.size)
        val base = PatQ.of(tile)
        patterns[index].events(subArc.shift(base)).map { it.shift(-base) }
    }
}

/** Plays one of [weighted] per cycle, chosen with probability proportional to its weight. */
fun <A> wrandcat(vararg weighted: Pair<PatPattern<A>, Double>, salt: Int = 0): PatPattern<A> {
    val usable = weighted.filter { it.second > 0.0 }
    if (usable.isEmpty()) return silence()
    val total = usable.sumOf { it.second }
    return fromCycleIndexed { subArc, tile ->
        var target = toUnitDouble(splitmix64(tile xor (salt.toLong() shl 31) xor 0x51ED2701L)) * total
        var chosen = usable.last().first
        for ((pattern, weight) in usable) {
            if (target < weight) { chosen = pattern; break }
            target -= weight
        }
        val base = PatQ.of(tile)
        chosen.events(subArc.shift(base)).map { it.shift(-base) }
    }
}

/** A continuous pattern that picks uniformly from [values] — one choice per query arc. */
fun <A> choose(vararg values: A, salt: Int = 0): PatPattern<A> {
    if (values.isEmpty()) return silence()
    return rand(salt).map { u -> values[(u * values.size).toInt().coerceIn(0, values.size - 1)] }
}

/** A continuous pattern that picks from [weighted] proportionally to each weight. */
fun <A> wchoose(vararg weighted: Pair<A, Double>, salt: Int = 0): PatPattern<A> {
    val usable = weighted.filter { it.second > 0.0 }
    if (usable.isEmpty()) return silence()
    val total = usable.sumOf { it.second }
    return rand(salt).map { u ->
        var target = u * total
        var chosen = usable.last().first
        for ((value, weight) in usable) {
            if (target < weight) { chosen = value; break }
            target -= weight
        }
        chosen
    }
}

// ── sample slicing ─────────────────────────────────────────────────────────────────────────

/**
 * Cuts each event into [n] consecutive slices in place, annotating each with the `begin`/`end`
 * fraction of the original it represents.
 *
 * The complement of the existing [striate], which interleaves slice `i` of every event before
 * moving on to slice `i+1`. `chop` keeps each event's slices together, so a sample stays
 * recognisable and only its grain changes.
 */
fun <A> PatPattern<A>.chop(n: Int): PatPattern<A> = mapEventsPerCycle { _, events ->
    val slices = max(1, n)
    buildList {
        for (event in events) {
            val step = event.arc.dur / PatQ.of(slices)
            for (index in 0 until slices) {
                val start = event.arc.start + step * PatQ.of(index)
                add(
                    PatEvent(
                        PatArc(start, start + step),
                        event.value,
                        event.sourceSpan,
                        event.annotations + mapOf(
                            "begin" to patFormatNumber(index.toDouble() / slices),
                            "end" to patFormatNumber((index + 1).toDouble() / slices)
                        ),
                        event.additionalSpans
                    )
                )
            }
        }
    }
}

/**
 * Plays the whole pattern [n] times per cycle, each pass annotated with a different `begin`/`end`
 * slice of the source — Tidal's `striate`.
 *
 * The existing [striate] in `TidalPatterns.kt` does something different despite the name: it
 * slices each event *in place*, exactly like [chop] but without the `begin`/`end` bookkeeping.
 * That behaviour is left alone — callers depend on it — and the interleaving version, which is
 * what makes a sliced break sound like a granular wash rather than a stutter, is this.
 *
 * `pat("a b").striateAcross(2)` reads `a b a b`, the first pair from the first half of each
 * sample and the second pair from the second half; `pat("a b").chop(2)` reads `a a b b`.
 */
fun <A> PatPattern<A>.striateAcross(n: Int): PatPattern<A> {
    val slices = max(1, n)
    return cat(
        *Array(slices) { index ->
            this with param("begin", index.toDouble() / slices) with param("end", (index + 1).toDouble() / slices)
        }
    )
}

// ── pitch: scales ──────────────────────────────────────────────────────────────────────────

/**
 * Named scales, as semitone offsets from the root.
 *
 * Degrees outside `0 until size` wrap with an octave transposition, so degree `-1` is the
 * scale's leading tone an octave down and degree `size` is the root an octave up. That is what
 * makes `run(16).scale("minor")` two octaves rather than a stuck top note.
 */
val PAT_SCALES: Map<String, List<Int>> = linkedMapOf(
    "chromatic" to (0..11).toList(),
    "major" to listOf(0, 2, 4, 5, 7, 9, 11),
    "ionian" to listOf(0, 2, 4, 5, 7, 9, 11),
    "minor" to listOf(0, 2, 3, 5, 7, 8, 10),
    "aeolian" to listOf(0, 2, 3, 5, 7, 8, 10),
    "dorian" to listOf(0, 2, 3, 5, 7, 9, 10),
    "phrygian" to listOf(0, 1, 3, 5, 7, 8, 10),
    "lydian" to listOf(0, 2, 4, 6, 7, 9, 11),
    "mixolydian" to listOf(0, 2, 4, 5, 7, 9, 10),
    "locrian" to listOf(0, 1, 3, 5, 6, 8, 10),
    "harmonicMinor" to listOf(0, 2, 3, 5, 7, 8, 11),
    "melodicMinor" to listOf(0, 2, 3, 5, 7, 9, 11),
    "majPent" to listOf(0, 2, 4, 7, 9),
    "minPent" to listOf(0, 3, 5, 7, 10),
    "blues" to listOf(0, 3, 5, 6, 7, 10),
    "wholeTone" to listOf(0, 2, 4, 6, 8, 10),
    "octatonic" to listOf(0, 2, 3, 5, 6, 8, 9, 11),
    "egyptian" to listOf(0, 2, 5, 7, 10),
    "ritusen" to listOf(0, 2, 5, 7, 9),
)

/** The semitone offsets of a named scale, or the chromatic scale if the name is unknown. */
fun patScaleSteps(name: String): List<Int> =
    PAT_SCALES[name] ?: PAT_SCALES.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    ?: PAT_SCALES.getValue("chromatic")

/** Maps one scale degree (any integer, positive or negative) to a semitone offset. */
fun patScaleDegree(steps: List<Int>, degree: Int): Int {
    if (steps.isEmpty()) return degree
    val size = steps.size
    val index = ((degree % size) + size) % size
    val octave = Math.floorDiv(degree, size)
    return steps[index] + 12 * octave
}

/**
 * Reads this pattern's values as scale degrees and returns MIDI note numbers.
 *
 * `run(8).scale("minor", root = 60)` is an ascending C minor scale; `pat("0 4 2 -1").nums()
 * .scale("majPent", root = 57)` is a pentatonic figure that dips below its root.
 */
fun PatPattern<Double>.scale(name: String, root: Int = 60): PatPattern<Double> {
    val steps = patScaleSteps(name)
    return map { degree -> (root + patScaleDegree(steps, Math.round(degree).toInt())).toDouble() }
}

/** Tidal's argument order: `scale("minor", run(8))`. */
fun scale(name: String, degrees: PatPattern<Double>, root: Int = 60): PatPattern<Double> =
    degrees.scale(name, root)

// ── pitch: chords ──────────────────────────────────────────────────────────────────────────

/** Named chords, as semitone offsets from the chord's root. */
val PAT_CHORDS: Map<String, List<Int>> = linkedMapOf(
    "major" to listOf(0, 4, 7),
    "maj" to listOf(0, 4, 7),
    "minor" to listOf(0, 3, 7),
    "min" to listOf(0, 3, 7),
    "aug" to listOf(0, 4, 8),
    "dim" to listOf(0, 3, 6),
    "dim7" to listOf(0, 3, 6, 9),
    "sus2" to listOf(0, 2, 7),
    "sus4" to listOf(0, 5, 7),
    "six" to listOf(0, 4, 7, 9),
    "min6" to listOf(0, 3, 7, 9),
    "maj7" to listOf(0, 4, 7, 11),
    "min7" to listOf(0, 3, 7, 10),
    "dom7" to listOf(0, 4, 7, 10),
    "sevenSus4" to listOf(0, 5, 7, 10),
    "halfDim" to listOf(0, 3, 6, 10),
    "add9" to listOf(0, 4, 7, 14),
    "maj9" to listOf(0, 4, 7, 11, 14),
    "min9" to listOf(0, 3, 7, 10, 14),
    "nine" to listOf(0, 4, 7, 10, 14),
    "eleven" to listOf(0, 4, 7, 10, 14, 17),
    "thirteen" to listOf(0, 4, 7, 10, 14, 17, 21),
    "power" to listOf(0, 7),
    "one" to listOf(0),
)

/** The semitone offsets of a named chord, or a bare root if the name is unknown. */
fun patChordSteps(name: String): List<Int> =
    PAT_CHORDS[name] ?: PAT_CHORDS.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    ?: listOf(0)

private val PAT_NOTE_LETTERS = mapOf('c' to 0, 'd' to 2, 'e' to 4, 'f' to 5, 'g' to 7, 'a' to 9, 'b' to 11)

/**
 * Reads a MIDI note from a token: a bare number (`60`), or a note name with optional accidentals
 * and octave (`c`, `cs`, `ef4`, `a3`).
 *
 * `s`/`#` sharpen and `f` flattens, repeatably. The octave is Tidal's, not scientific pitch
 * notation's: `c5` is MIDI 60 and 5 is the default, so a bare `c` is middle C, `c4` is 48 and
 * `a` is 69.
 */
fun patNoteNumber(token: String): Int? {
    val text = token.trim()
    if (text.isEmpty()) return null
    text.toIntOrNull()?.let { return it }
    text.toDoubleOrNull()?.let { return Math.round(it).toInt() }
    var index = 0
    val letter = PAT_NOTE_LETTERS[text[index].lowercaseChar()] ?: return null
    index++
    var accidental = 0
    while (index < text.length) {
        when (text[index].lowercaseChar()) {
            's', '#' -> accidental++
            'f' -> accidental--
            else -> break
        }
        index++
    }
    val octaveText = text.substring(index)
    val octave = if (octaveText.isEmpty()) 5 else octaveText.toIntOrNull() ?: return null
    return letter + accidental + 12 * octave
}

/**
 * Parses a chord progression from mini-notation, `root'name` per atom.
 *
 * `chords("c'maj7 a'min7 d'min7 g'dom7")` and `chords("60'maj 65'min")` are both fine; a bare
 * root with no `'` is a major triad. Every mini-notation feature still applies, so
 * `chords("<c'maj7 a'min7> f'maj7")` alternates the first chord every other cycle.
 */
fun chords(source: String): PatPattern<List<Int>> = pat(source).chordsOf()

/** Reads each string atom of this pattern as `root'name` and returns its MIDI notes. */
fun PatPattern<String>.chordsOf(): PatPattern<List<Int>> = PatPattern { arc ->
    events(arc).mapNotNull { event ->
        val notes = patParseChord(event.value) ?: return@mapNotNull null
        PatEvent(event.arc, notes, event.sourceSpan, event.annotations, event.additionalSpans)
    }
}

/** Parses one `root'name` token into MIDI notes, or `null` when the root is unreadable. */
fun patParseChord(token: String): List<Int>? {
    val text = token.trim()
    val tick = text.indexOf('\'')
    val rootText = if (tick < 0) text else text.substring(0, tick)
    val name = if (tick < 0) "major" else text.substring(tick + 1).ifBlank { "major" }
    val root = patNoteNumber(rootText) ?: return null
    return patChordSteps(name).map { root + it }
}

/** Builds a chord pattern by naming one chord over a pattern of roots. */
fun PatPattern<Double>.chord(name: String): PatPattern<List<Int>> {
    val steps = patChordSteps(name)
    return map { root -> steps.map { Math.round(root).toInt() + it } }
}

/** Flattens a chord pattern into simultaneous single-note events — a block chord. */
fun PatPattern<List<Int>>.notesOf(): PatPattern<Double> = PatPattern { arc ->
    events(arc).flatMap { event ->
        event.value.map { note ->
            PatEvent(event.arc, note.toDouble(), event.sourceSpan, event.annotations, event.additionalSpans)
        }
    }
}

/** Spreads each chord's notes evenly across its own arc, lowest first. Tidal's `arpg`. */
fun PatPattern<List<Int>>.arpeggiate(descending: Boolean = false): PatPattern<Double> = PatPattern { arc ->
    events(arc).flatMap { event ->
        val notes = if (descending) event.value.sortedDescending() else event.value.sorted()
        if (notes.isEmpty()) return@flatMap emptyList()
        val step = event.arc.dur / PatQ.of(notes.size)
        notes.mapIndexed { index, note ->
            val start = event.arc.start + step * PatQ.of(index)
            val end = if (index == notes.size - 1) event.arc.end else start + step
            PatEvent(PatArc(start, end), note.toDouble(), event.sourceSpan, event.annotations, event.additionalSpans)
        }
    }
}

/**
 * Chooses each chord's inversion and octave to minimise how far the voices have to move from
 * the previous chord.
 *
 * This is the piece Tidal has no answer for: `chords("c'maj7 a'min7 d'min7 g'dom7")` played as
 * written jumps the whole hand around, because every chord is spelled from its own root. Voice
 * leading keeps the common tones where they are.
 *
 * The search is small and exhaustive — every rotation of the chord (each rotated-off note lifted
 * an octave) at every octave transposition that keeps the voicing inside [center] ± [spread] —
 * scored by the total semitone distance between the sorted candidate and the sorted previous
 * voicing. The first chord of the sequence is led towards [center] itself, so the result is
 * stable rather than dependent on where the progression happens to start.
 *
 * Chords are led in scheduled order within each cycle; the pattern remains a pure function of
 * the queried cycle, so it is still safe to query out of order or in parallel.
 */
fun PatPattern<List<Int>>.voiceLead(center: Int = 60, spread: Int = 12): PatPattern<List<Int>> =
    mapEventsPerCycle { _, events ->
        if (events.isEmpty()) return@mapEventsPerCycle events
        val ordered = events.sortedWith(compareBy({ it.arc.start }, { it.arc.end }))
        var previous: List<Int> = List(max(1, ordered.first().value.size)) { center }
        ordered.map { event ->
            val led = patLeadVoicing(event.value, previous, center, spread)
            if (led.isNotEmpty()) previous = led
            PatEvent(event.arc, led, event.sourceSpan, event.annotations, event.additionalSpans)
        }
    }

/** The single-chord kernel behind [voiceLead]; exposed so tests can measure it directly. */
fun patLeadVoicing(chord: List<Int>, previous: List<Int>, center: Int, spread: Int): List<Int> {
    if (chord.isEmpty()) return chord
    val sortedChord = chord.sorted()
    var best: List<Int>? = null
    var bestScore = Double.MAX_VALUE
    for (rotation in sortedChord.indices) {
        val rotated = sortedChord.drop(rotation).map { it } + sortedChord.take(rotation).map { it + 12 }
        for (octave in -3..3) {
            val candidate = rotated.map { it + 12 * octave }.sorted()
            if (candidate.first() < center - spread || candidate.last() > center + spread) continue
            val score = patVoicingDistance(candidate, previous) +
                0.01 * abs(candidate.average() - center)
            if (score < bestScore) { bestScore = score; best = candidate }
        }
    }
    return best ?: sortedChord
}

/**
 * Total semitone movement between two voicings: the sorted voices are paired up, and any extra
 * voice in either chord is charged against the nearest voice of the other.
 */
fun patVoicingDistance(a: List<Int>, b: List<Int>): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val x = a.sorted()
    val y = b.sorted()
    var total = 0.0
    for (i in 0 until min(x.size, y.size)) total += abs(x[i] - y[i]).toDouble()
    for (i in min(x.size, y.size) until x.size) total += y.minOf { abs(x[i] - it) }.toDouble()
    for (i in min(x.size, y.size) until y.size) total += x.minOf { abs(y[i] - it) }.toDouble()
    return total
}

/** Total voice movement across a chord sequence — the number [voiceLead] minimises. */
fun patTotalVoiceMovement(voicings: List<List<Int>>): Double {
    var total = 0.0
    for (i in 1 until voicings.size) total += patVoicingDistance(voicings[i], voicings[i - 1])
    return total
}

// ── small helpers ──────────────────────────────────────────────────────────────────────────

internal fun minQ(a: PatQ, b: PatQ): PatQ = if (a < b) a else b

internal fun maxQ(a: PatQ, b: PatQ): PatQ = if (a > b) a else b
