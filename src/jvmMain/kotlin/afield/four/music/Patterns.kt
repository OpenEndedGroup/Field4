package afield.four.music

// ═══════════════════════════════════════════════════════════════════
//  Patterns – sequence and rhythm helpers for live coding
// ═══════════════════════════════════════════════════════════════════

// ── Sequence combinators ──────────────────────────────────────────

/**
 * Repeat a list forever as a lazy [Sequence].
 *
 * ```kotlin
 * cycle(listOf(60.n, 62.n, 64.n, 65.n, 67.n))   // ascending pentatonic, looping
 * cycle(listOf(60.n(0.5), rest(0.5)))             // alternating note / rest
 * ```
 */
fun <T> cycle(items: List<T>): Sequence<T> = sequence {
    require(items.isNotEmpty()) { "cycle() requires a non-empty list" }
    while (true) yieldAll(items)
}

/**
 * Cycle a vararg list of items forever.
 *
 * ```kotlin
 * cycle(60.n, 64.n, 67.n)
 * ```
 */
fun <T> cycle(vararg items: T): Sequence<T> = cycle(items.toList())

/**
 * Concatenate two sequences end-to-end (both must be finite).
 *
 * ```kotlin
 * cycle(listOf(60.n, 62.n)) + cycle(listOf(64.n, 65.n))  // impossible – both infinite
 * finiteSeq1 + finiteSeq2
 * ```
 */
operator fun <T> Sequence<T>.plus(other: Sequence<T>): Sequence<T> = sequence {
    yieldAll(this@plus)
    yieldAll(other)
}

/**
 * Take the first [n] elements of a sequence as a [List].
 *
 * ```kotlin
 * cycle(listOf(60.n, 62.n, 64.n)).take(9)   // 3 full cycles
 * ```
 */
fun <T> Sequence<T>.takeAsList(n: Int): List<T> = take(n).toList()

/**
 * Rotate a list by [n] positions (positive = left-rotate; wraps around).
 *
 * ```kotlin
 * listOf(1, 2, 3, 4).rotated(1)  // [2, 3, 4, 1]
 * listOf(1, 2, 3, 4).rotated(-1) // [4, 1, 2, 3]
 * ```
 */
fun <T> List<T>.rotated(n: Int): List<T> {
    if (isEmpty()) return this
    val shift = ((n % size) + size) % size
    return subList(shift, size) + subList(0, shift)
}

// ── Euclidean rhythms (Bjorklund algorithm) ───────────────────────

/**
 * Compute a Euclidean rhythm: distribute [pulses] onsets as evenly as possible
 * across [steps] time slots using the Bjorklund / Toussaint algorithm.
 *
 * Returns a `List<Boolean>` of length [steps] where `true` = onset.
 * Use [rotate] to phase-shift the pattern.
 *
 * Classic patterns:
 * - `euclid(8, 3)` → Cuban tresillo `[1,0,0,1,0,0,1,0]`
 * - `euclid(8, 5)` → Cuban cinquillo `[1,0,1,1,0,1,1,0]`
 * - `euclid(16, 9)` → West African standard pattern
 * - `euclid(12, 5)` → South African `[1,0,0,1,0,1,0,0,1,0,1,0]`
 *
 * ```kotlin
 * // 3 hits in 8 steps mapped to notes
 * val rhythm = euclid(8, 3).toNoteSequence(onNote = 60.n(0.125), offDuration = 0.125)
 * sched.setPattern(rhythm)
 * ```
 */
fun euclid(steps: Int, pulses: Int, rotate: Int = 0): List<Boolean> {
    require(steps > 0) { "steps must be > 0" }
    require(pulses >= 0) { "pulses must be >= 0" }

    if (pulses == 0) return List(steps) { false }
    if (pulses >= steps) return List(steps) { true }

    // Bjorklund iterative redistribution:
    // Maintain two sets of sub-patterns ("ones" and "zeros").
    // Each iteration, pair each zero-group with a one-group; the larger set's
    // leftovers become the new "zeros" for the next round.
    var ones: MutableList<MutableList<Boolean>> =
        (0 until pulses).map { mutableListOf(true) }.toMutableList()
    var zeros: MutableList<MutableList<Boolean>> =
        (0 until (steps - pulses)).map { mutableListOf(false) }.toMutableList()

    while (zeros.size > 1) {
        val minLen = minOf(ones.size, zeros.size)
        val newOnes: MutableList<MutableList<Boolean>> = ArrayList(minLen)

        for (i in 0 until minLen) {
            val merged = ArrayList<Boolean>(ones[i].size + zeros[i].size)
            merged.addAll(ones[i])
            merged.addAll(zeros[i])
            newOnes.add(merged)
        }

        // The larger list's tail becomes the new "zeros"
        zeros = if (ones.size > zeros.size) {
            ones.subList(minLen, ones.size).toMutableList()
        } else {
            zeros.subList(minLen, zeros.size).toMutableList()
        }
        ones = newOnes
    }

    val flat = (ones + zeros).flatten()
    return if (rotate == 0) flat else flat.rotated(rotate)
}

/**
 * Map a Euclidean rhythm (`List<Boolean>`) to a [Sequence]<[Note]> that cycles forever.
 *
 * Each `true` slot produces [onNote]; each `false` slot produces a rest of
 * [offDuration] beats.  When [stepDuration] is specified it overrides the note's
 * own duration for `true` slots (useful to ensure every step is the same width).
 *
 * ```kotlin
 * // 3-in-8 pattern, each step = 1/8 beat
 * euclid(8, 3).toNoteSequence(60.n(0.125))
 *
 * // Custom on/off notes
 * euclid(16, 7).toNoteSequence(
 *     onNote = 36.n(0.0625),
 *     offDuration = 0.0625
 * )
 * ```
 */
fun List<Boolean>.toNoteSequence(
    onNote: Note,
    offDuration: Double = onNote.duration
): Sequence<Note> = cycle(map { hit -> if (hit) onNote else rest(offDuration) })

// ── Scale helpers ─────────────────────────────────────────────────

/**
 * Common diatonic and modal scale interval sets (semitones from root).
 * Combine with [Int.scale] to build a list of MIDI pitches.
 *
 * ```kotlin
 * val root = 60  // C4
 * val notes = root.scale(Scales.minor)   // [60, 62, 63, 65, 67, 68, 70]
 * cycle(notes.map { it.n(0.5) })
 * ```
 */
object Scales {
    val major            = listOf(0, 2, 4, 5, 7, 9, 11)
    val minor            = listOf(0, 2, 3, 5, 7, 8, 10)
    val pentatonicMajor  = listOf(0, 2, 4, 7, 9)
    val pentatonicMinor  = listOf(0, 3, 5, 7, 10)
    val dorian           = listOf(0, 2, 3, 5, 7, 9, 10)
    val phrygian         = listOf(0, 1, 3, 5, 7, 8, 10)
    val lydian           = listOf(0, 2, 4, 6, 7, 9, 11)
    val mixolydian       = listOf(0, 2, 4, 5, 7, 9, 10)
    val locrian          = listOf(0, 1, 3, 5, 6, 8, 10)
    val wholeTone        = listOf(0, 2, 4, 6, 8, 10)
    val diminished       = listOf(0, 2, 3, 5, 6, 8, 9, 11)
    val augmented        = listOf(0, 3, 4, 7, 8, 11)
    val blues            = listOf(0, 3, 5, 6, 7, 10)
    val chromatic        = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
}

/**
 * Build a list of MIDI pitches from this root MIDI note and the given
 * [scale] interval set, spanning one octave from this pitch.
 *
 * ```kotlin
 * 60.scale(Scales.major)          // [60, 62, 64, 65, 67, 69, 71]
 * 69.scale(Scales.pentatonicMinor) // A minor pentatonic
 * ```
 */
fun Int.scale(intervals: List<Int>): List<Int> = intervals.map { this + it }

/**
 * Get the MIDI pitch at a (possibly negative or wrapping) [degree] within
 * a scale interval set.  Octave displacement is calculated automatically.
 *
 * ```kotlin
 * val ionian = Scales.major
 * ionian.degree(0, root = 60)   // 60 (C4)
 * ionian.degree(7, root = 60)   // 72 (C5 – one octave up)
 * ionian.degree(-1, root = 60)  // 59 (B3 – one below root)
 * ```
 */
fun List<Int>.degree(d: Int, root: Int): Int {
    val sz = size
    val octave = Math.floorDiv(d, sz)
    val index = Math.floorMod(d, sz)
    return root + this[index] + octave * 12
}

// ── Generator-based sequence bridge ─────────────────────────────

/**
 * Wrap a [afield.Generator] as a lazy [Sequence]<[Note]>.
 *
 * The generator is started immediately and consumed lazily as elements are
 * requested.  Use this to feed generator-based melodies into [NoteScheduler]:
 *
 * ```kotlin
 * import afield.generator
 *
 * val gen = generator<Unit, Note> {
 *     val pitches = listOf(60, 62, 64, 67, 69)
 *     var i = 0
 *     while (true) {
 *         yield(Note(pitches[i++ % pitches.size], duration = 0.5))
 *     }
 * }
 * sched.setPattern(gen.asNoteSequence())
 * ```
 */
fun afield.Generator<Unit, Note>.asNoteSequence(): Sequence<Note> = sequence {
    var out = start()
    while (out != null) {
        yield(out)
        out = next(Unit)
    }
}
