@file:afield.CompletionCategory("Patterns")

package afield.four.synth

/**
 * Kolakoski rhythms: a bar whose own run lengths are written into it.
 *
 * `euclid(3, 8)` asks for the most even spread of three onsets over eight slots, and answers with a
 * density and a period you chose in advance. A Kolakoski rhythm answers a different question — what
 * does a bar look like when the lengths of its runs are the bar itself? — and keeps answering it,
 * one step at a time, for as many steps as you ask for.
 *
 * The Kolakoski word over {1, 2} begins
 *
 *     1 2 2 1 1 2 1 2 2 1 2 2 1 1 2 1 1 2 2 1 …
 *
 * and it is the only sequence over {1, 2} starting with 1 that is its own run-length encoding: one
 * 1, two 2s, two 1s, one 2, one 1, two 2s, … — reading the runs off gives 1, 2, 2, 1, 1, 2, … back.
 * [kolakoskiWord] generates it; [KolakoskiReading] is the choice of how to hear it.
 *
 *     kolakoski(16)                          // x..xx.x..x..xx.x   — runs, the self-describing one
 *     kolakoski(16, KolakoskiReading.GAPS)   // xx.x.xxx.xx.x.xx   — gaps between onsets
 *
 * `RUNS` reads the terms as alternating on- and off-runs, starting with an on-run, so the rhythm's
 * own run lengths spell out the word that made it. `GAPS` reads each term as the distance to the
 * next onset, the way a Euclidean rhythm's onsets sit on a step grid. Every step is then either an
 * onset or a single rest, which fills in the long gaps the run-length reading leaves: eleven onsets
 * in sixteen steps against the other reading's eight.
 *
 * Both readings are **prefix-stable**: the first sixteen steps of `kolakoski(32)` are exactly
 * `kolakoski(16)`. Turning the step count up extends the figure instead of re-placing it, so the
 * step count is a dial you can turn without the hits you already have jumping around. `euclid`
 * cannot do that, because the step count is part of the question it answers: `euclid(3, 8)` is
 * `x..x.x..`, and the first eight steps of `euclid(3, 16)` are `x.....x.`. `PatKolakoskiTest`
 * asserts both halves of that contrast.
 *
 * The alphabet is a parameter. `kolakoski(16, a = 2, b = 3)` is the self-describing word over
 * {2, 3} — 2 2 3 3 2 2 2 3 3 3 2 2 … — which has no run shorter than two, so that figure never
 * leaves a lone hit or a lone rest: the same self-description, in bigger blocks.
 *
 * @see euclid for the even-distribution rhythm this sits beside.
 */

/** How a Kolakoski word is read as a rhythm. See the file overview for the two figures. */
enum class KolakoskiReading {
    /**
     * Alternating on- and off-run lengths, starting with an on-run: 1, 2, 2, 1, … is one step on,
     * two off, two on, one off. The run lengths of the result are the word again.
     */
    RUNS,

    /**
     * Distances between onsets: 1, 2, 2, 1, … puts onsets at steps 0, 1, 3, 5, 6, … A term of 1 is
     * two adjacent hits, and no gap is longer than the larger of the two letters, so the rests
     * never pile up.
     */
    GAPS
}

/**
 * The first [length] terms of the Kolakoski word over the two-letter alphabet {[a], [b]}.
 *
 * The classic sequence is `kolakoskiWord(n)` over {1, 2}; any other pair of distinct positive
 * letters gives the same self-description over that alphabet, so `kolakoskiWord(12, 2, 3)` is
 * 2, 2, 3, 3, 2, 2, 2, 3, 3, 3, 2, 2. Prefix-stable in [length]: asking for more terms never
 * changes the ones you already had.
 */
fun kolakoskiWord(length: Int, a: Int = 1, b: Int = 2): List<Int> {
    require(a >= 1 && b >= 1) { "Kolakoski run lengths must be positive; got a=$a, b=$b" }
    require(a != b) { "a Kolakoski word needs two distinct letters; got a=b=$a" }
    val wanted = length.coerceAtLeast(0)
    val word = ArrayList<Int>(wanted)
    var read = 0        // the term whose value is the length of the run being written
    var writing = a     // the letter of the run being written; alternates
    while (word.size < wanted) {
        // The self-reference: the run to write is as long as the term at `read` says. The first two
        // runs are read before they are written, and both times `writing` already holds the right
        // answer — word[0] is `a` by definition, and word[1] is `b` whenever the first run is short
        // enough for the question to arise (which is only when a == 1). So the bootstrap needs no
        // seed of its own.
        val runLength = word.getOrElse(read) { writing }
        repeat(runLength) { if (word.size < wanted) word.add(writing) }
        writing = if (writing == a) b else a
        read++
    }
    return word
}

/**
 * [steps] steps of a Kolakoski rhythm as a boolean mask — the [bjorklund] of this family.
 *
 * [rotation] turns the figure the same way `euclid`'s does: a positive rotation moves every onset
 * that many steps later, wrapping round the bar.
 */
fun kolakoskiMask(
    steps: Int,
    reading: KolakoskiReading = KolakoskiReading.RUNS,
    a: Int = 1,
    b: Int = 2,
    rotation: Int = 0
): List<Boolean> {
    val stepCount = steps.coerceAtLeast(1)
    // Every term is worth at least one step under either reading, so `stepCount` terms is always
    // enough word to fill the bar.
    val word = kolakoskiWord(stepCount, a, b)
    val mask = MutableList(stepCount) { false }
    when (reading) {
        KolakoskiReading.RUNS -> {
            var at = 0
            var on = true
            for (term in word) {
                if (at >= stepCount) break
                if (on) for (index in at until minOf(at + term, stepCount)) mask[index] = true
                at += term
                on = !on
            }
        }
        KolakoskiReading.GAPS -> {
            var at = 0
            var read = 0
            while (at < stepCount) {
                mask[at] = true
                at += word.getOrElse(read) { 1 }
                read++
            }
        }
    }
    val turn = ((rotation % stepCount) + stepCount) % stepCount
    if (turn == 0) return mask
    return List(stepCount) { index -> mask[((index - turn) % stepCount + stepCount) % stepCount] }
}

/**
 * A Kolakoski rhythm over [steps] steps of the cycle: `true` on its onsets, `false` between them.
 *
 * `struct(kolakoski(16), pat("bd"))` is a bass drum on the figure, one hit per onset. See
 * [KolakoskiReading] for the two readings and the file overview for what the alphabet does.
 */
fun kolakoski(
    steps: Int,
    reading: KolakoskiReading = KolakoskiReading.RUNS,
    a: Int = 1,
    b: Int = 2,
    rotation: Int = 0
): PatPattern<Boolean> = kolakoskiMask(steps, reading, a, b, rotation).asSteps()

/** `true` exactly where [kolakoski] is `false`, on the same step grid. */
fun kolakoskiInv(
    steps: Int,
    reading: KolakoskiReading = KolakoskiReading.RUNS,
    a: Int = 1,
    b: Int = 2,
    rotation: Int = 0
): PatPattern<Boolean> = kolakoskiMask(steps, reading, a, b, rotation).map { !it }.asSteps()

/**
 * [on] on the Kolakoski onsets — what `bd(k16)` compiles to.
 *
 * Built on [mask], so a multi-step [on] is *squeezed* into each onset rather than clipped to it, one
 * whole cycle per step. [euclidFill] has the same convention and `PatKolakoskiTest` asserts the two
 * together; reach for [struct] when you want one hit per onset regardless of what [on] contains.
 */
fun <A> kolakoskiFill(
    steps: Int,
    reading: KolakoskiReading = KolakoskiReading.RUNS,
    a: Int = 1,
    b: Int = 2,
    rotation: Int = 0,
    on: PatPattern<A>
): PatPattern<A> = mask(kolakoski(steps, reading, a, b, rotation), on)

/** [on] on the Kolakoski onsets, [off] on every other step — a full bar from one figure. */
fun <A> kolakoskiFull(
    steps: Int,
    on: PatPattern<A>,
    off: PatPattern<A>,
    reading: KolakoskiReading = KolakoskiReading.RUNS,
    a: Int = 1,
    b: Int = 2,
    rotation: Int = 0
): PatPattern<A> = stack(
    mask(kolakoski(steps, reading, a, b, rotation), on),
    mask(kolakoskiInv(steps, reading, a, b, rotation), off)
)

/**
 * A step mask as a pattern: one whole-step event per `true`, none per `false`.
 *
 * The mask is computed once and closed over, so a query costs a scan of the bar rather than a
 * regeneration of the word.
 */
private fun List<Boolean>.asSteps(): PatPattern<Boolean> {
    val stepCount = size.coerceAtLeast(1)
    val mask = this
    return fromCycle { subArc ->
        buildList {
            for (index in 0 until stepCount) {
                if (index < mask.size && mask[index]) {
                    val window = PatArc(PatQ.of(index, stepCount), PatQ.of(index + 1, stepCount))
                    val inter = window.intersect(subArc) ?: continue
                    // The step lasts `window`; `inter` is only as much of it as this query can see.
                    add(PatEvent(inter, true, whole = window))
                }
            }
        }
    }
}
