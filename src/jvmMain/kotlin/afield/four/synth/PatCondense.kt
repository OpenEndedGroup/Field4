@file:afield.CompletionCategory("Patterns")

package afield.four.synth

import java.math.BigInteger

/**
 * The shortest mini-notation expression that plays a given rhythm.
 *
 * `PatTranscription.kt` writes a rhythm out flat: [patNotationOf] fits a step grid to a set of onsets
 * and emits one slot per step, `x . . x . . x .`, which is faithful and says nothing about what it is
 * looking at. `bd(3,8)` says the same thing in a third of the characters and names the figure.
 *
 * This file gets from the first spelling to the second. Give it a rhythm, as a mask, a `PatPattern`, a
 * list of events or a [PatTranscription], and it returns the shortest string in Field's own
 * mini-notation whose parse renders to an **identical** event set, together with the structure it
 * found.
 *
 * **Description length is the whole criterion.** There is no notion here of a "better" reading of a
 * rhythm than a shorter one. `x..x.x..` is `bd(3,8)` because that is eight characters against
 * twenty-three, not because Euclidean rhythms are privileged, and a figure that no operator shortens
 * comes back as itself, spelled out. [PatCondenseTest] asserts that last part over generated
 * structureless masks, since a condenser that always found *something* would be finding nothing.
 *
 * **The oracle is Field's own parser.** Every candidate this file emits is handed back to [pat], the
 * same entry point a box calls, re-rendered, and compared event for event against the target. Nothing
 * is accepted on the strength of the reasoning that produced it, and there is no second hand-written
 * evaluator to drift away from the language. The proposers below are allowed to be wrong, because
 * being wrong only costs a rejected candidate.
 *
 * **What it searches.** The candidate grammar is read off `MiniPat.Node` (`TidalPatterns.kt:1127`) —
 * what the parser actually accepts, not what a mini-notation tutorial says. Atoms and `~`; `_` holds
 * and their `@n` spelling; `[ … ]` grouping at every divisor of the span; `x!n` replication; `x*n`
 * repetition; `X(p,s,r)` Euclidean figures and `X(kN)` / `X(gN)` Kolakoski ones over any repeated
 * *term* rather than only a repeated atom; `a, b` stacking, from an interval partition of overlapping
 * events; and `< … >` alternation when the cycles differ. What it does **not** search is listed in
 * `dev_docs/PAT_CONDENSE.md` and pinned as tests, so the list cannot go stale on its own.
 *
 * **It is bounded, and it says so.** A structural search over this grammar does not terminate if you
 * let it enumerate freely, so the range DP keeps [PAT_CONDENSE_BEAM] candidates per span, stops
 * proposing after [PAT_CONDENSE_NODES] spans, refuses a grid finer than [PAT_CONDENSE_MAX_SLOTS]
 * steps, and calls the oracle at most [PAT_CONDENSE_ORACLE_CALLS] times. Every one of those bounds is
 * reported on the result, so a caller can tell a genuine "there is nothing shorter" from a search that
 * ran out of room.
 */

/** Candidates kept per span by the range DP. Raising it widens the search and slows it. */
const val PAT_CONDENSE_BEAM: Int = 4

/** Spans the DP will expand before it falls back to the flat spelling for everything else. */
const val PAT_CONDENSE_NODES: Int = 20_000

/** Finest step grid [patCondense] will fit a cycle onto. Beyond this it refuses rather than guesses. */
const val PAT_CONDENSE_MAX_SLOTS: Int = 256

/** How many candidates are re-parsed and re-rendered before the flat floor is taken. */
const val PAT_CONDENSE_ORACLE_CALLS: Int = 96

/** Cycles [patCondense] will look at when asked to find a target's period for itself. */
const val PAT_CONDENSE_MAX_PERIOD: Int = 8

/**
 * What [patCondense] found.
 *
 * [notation] is always something [pat] parses. When [exact] is true it renders to the target's events
 * exactly, over [cycles] cycles, checked by re-rendering rather than by construction. When it is
 * false, [refusal] says why the search would not sign for the result and [notation] is the closest
 * thing that still parses: usually the flat spelling, or for a target that would not reduce to a grid
 * at all, the source it came from.
 *
 * @param flat the same rhythm spelled one slot per step, which is the denominator [compression]
 *   divides by and the floor the search is never allowed to lose to.
 * @param structure the mini-notation constructs in [notation], read back off its parse rather than
 *   recorded while emitting — so it describes the string that won, not the reasoning that proposed it.
 * @param oracleCalls how many candidates were re-parsed and re-rendered before one was accepted.
 * @param spansExpanded how many spans the range DP expanded; compare against [PAT_CONDENSE_NODES].
 * @param bounded true when a bound was actually hit, so a caller can tell "nothing shorter exists" from
 *   "the search stopped looking".
 */
data class PatCondensation(
    val notation: String,
    val flat: String,
    val cycles: Int,
    val exact: Boolean,
    val structure: List<String>,
    val refusal: String? = null,
    val oracleCalls: Int = 0,
    val spansExpanded: Int = 0,
    val bounded: Boolean = false
) {
    /** [notation] length over [flat] length. Below 1 is a win; 1 means the rhythm has no structure to find. */
    val compression: Double
        get() = if (flat.isEmpty()) 1.0 else notation.length.toDouble() / flat.length.toDouble()

    /** The condensed string, parsed. Identical to the target's pattern when [exact]. */
    val pattern: PatPattern<String>
        get() = pat(notation)

    /** The bounds this search ran under, for a caller comparing two results. */
    val limits: String
        get() = "beam $PAT_CONDENSE_BEAM, ≤$PAT_CONDENSE_NODES spans (used $spansExpanded), " +
            "≤$PAT_CONDENSE_MAX_SLOTS slots/cycle, ≤$PAT_CONDENSE_ORACLE_CALLS oracle calls " +
            "(used $oracleCalls)${if (bounded) ", A BOUND WAS HIT" else ""}"

    fun describe(): String = buildString {
        append(if (exact) "\"" + notation + "\"" else "(inexact) \"" + notation + "\"")
        append("  ")
        append("${notation.length}/${flat.length} chars = ")
        append("%.2f".format(compression))
        append(" over $cycles cycle${if (cycles == 1) "" else "s"}")
        if (structure.isNotEmpty()) append(" — ${structure.joinToString(", ")}")
        if (refusal != null) append("\n  refused: $refusal")
    }

    override fun toString(): String = describe()
}

/**
 * Condense a mini-notation string: parse it, then find the shortest string that plays the same thing.
 *
 * `patCondense("x ~ ~ x ~ x ~ ~")` is `x(3,8)`, and `patCondense("x ~ ~ x ~ ~ x ~")` is `x(3,8,5)`,
 * the same figure turned. `patCondense("bd(3,8)")` is `bd(3,8)` — already minimal, and the search
 * says so by not beating it.
 *
 * @param cycles how many cycles must agree. Null asks [patCondense] to find the target's own period,
 *   up to [PAT_CONDENSE_MAX_PERIOD]; a target with no period inside that window is refused rather
 *   than condensed on the strength of its first bar.
 */
fun patCondense(source: String, cycles: Int? = null): PatCondensation =
    patCondense(pat(source), cycles, fallback = source)

/** Condense a boolean step mask, played with [on] on each true step. `patCondense(bjorklund(3,8))` is `x(3,8)`. */
fun patCondense(mask: List<Boolean>, on: String = "x"): PatCondensation =
    patCondense(mask.joinToString(" ") { if (it) on else "~" }, cycles = 1)

/** Condense a live pattern. See [patCondense] for what [cycles] does. */
fun PatPattern<String>.condense(cycles: Int? = null): PatCondensation = patCondense(this, cycles)

/**
 * Close the transcription loop structurally.
 *
 * [patTranscribe] recovers a grid and writes it out flat, which is as far as
 * `PatTranscription.kt` can honestly go from audio alone. This takes that flat string and asks what
 * the shortest spelling of the same bar is, so "notate → render → analyse → revise" ends with
 * notation a person would have written rather than a slot list.
 */
fun patCondense(transcription: PatTranscription): PatCondensation =
    patCondense(transcription.miniNotation, cycles = 1)

/**
 * Draw each of [sources] against its own condensation in this box's output, and return what was found.
 *
 * One pair of lanes per source: the rhythm as given above, the condensation *played* below. If the
 * two lanes of a pair are not the same picture, the search is wrong, and that is easier to see than
 * to read. Returns the [PatCondensation]s so a box can print `notation`, `compression` or `limits`
 * beside the figure.
 *
 * ```
 * showPatCondense("x ~ ~ x ~ x ~ ~", "bd [hh hh] sd [cp cp]").forEach { println(it.describe()) }
 * ```
 */
fun afield.Box.showPatCondense(
    vararg sources: String,
    channel: String = "main"
): List<PatCondensation> {
    val found = sources.map { patCondense(it) }
    if (found.isEmpty()) return found
    val lanes = sources.zip(found).flatMap { (source, condensation) ->
        listOf(source to pat(source), "→ ${condensation.notation}" to pat(condensation.notation))
    }
    val best = found.minOf { it.compression }
    showPatRollOf(
        *lanes.toTypedArray(),
        title = "patCondense",
        subtitle = "given (above) against condensed (below); best ${"%.2f".format(best)} of the flat spelling",
        cycles = found.maxOf { it.cycles },
        channel = channel
    )
    return found
}

/**
 * The search proper.
 *
 * @param fallback the string to hand back if the target cannot be reduced to a grid at all. When the
 *   caller had a source string this is that string, so a refusal still returns something playable.
 */
fun patCondense(
    pattern: PatPattern<String>,
    cycles: Int? = null,
    fallback: String? = null
): PatCondensation = when (val reduction = patReduceForCondensing(pattern, cycles)) {
    is PatCondenseReduction.Refused -> PatCondensation(
        notation = fallback ?: "~",
        flat = fallback ?: "~",
        cycles = cycles ?: PAT_CONDENSE_MAX_PERIOD,
        exact = false,
        structure = emptyList(),
        refusal = reduction.why
    )
    is PatCondenseReduction.Ready -> reduction.target.search(fallback)
}

// ── reducing a target to slots ──────────────────────────────────────────────────────────────────

/** Either a target the search can work on, or the reason it will not be attempted. */
private sealed interface PatCondenseReduction {
    class Ready(val target: PatCondenseTarget) : PatCondenseReduction
    class Refused(val why: String) : PatCondenseReduction
}

/**
 * Reduce a live pattern to slots, or say why it cannot be.
 *
 * Two ways out, and they are different failures worth different sentences. A target with no period
 * inside [PAT_CONDENSE_MAX_PERIOD] cycles is *randomised or very slow*, and any condensation of it
 * would be a claim about bars nobody looked at. A target that has a period but will not sit on a
 * grid is *off the slot model* — an analog signal, a whole crossing the bar line, a grid finer than
 * [PAT_CONDENSE_MAX_SLOTS].
 */
private fun patReduceForCondensing(pattern: PatPattern<String>, cycles: Int?): PatCondenseReduction {
    val period = cycles ?: patDetectPeriod(pattern) ?: return PatCondenseReduction.Refused(
        "no period inside $PAT_CONDENSE_MAX_PERIOD cycles — this target is either randomised " +
            "(`?`, `|`, `rand`, `shuffle`) or slower than the window, and a condensation fitted to " +
            "its first bars would be a guess about the rest"
    )
    val signature = (0 until period).map { patCycleSignature(pattern, it.toLong()) }
    val layerings = ArrayList<List<PatCondenseLayering>>(period)
    for (cycle in 0 until period) {
        val found = patLayeringsOf(pattern, cycle.toLong())
        if (found.isEmpty()) {
            return PatCondenseReduction.Refused(
                "cycle $cycle will not sit on a step grid of at most $PAT_CONDENSE_MAX_SLOTS slots — " +
                    "an analog event, a whole that crosses the bar line, or an onset off every grid " +
                    "this size. The rhythm is real; the slot model is what refuses it"
            )
        }
        layerings += found
    }
    return PatCondenseReduction.Ready(PatCondenseTarget(period, layerings, signature))
}

/**
 * The smallest cycle count after which a pattern repeats itself.
 *
 * Checked by comparing cycles `[0,P)` against `[P,2P)` **and** `[2P,3P)`. Two windows rather than
 * one because a coin-flip construct agrees with itself across a single comparison often enough to
 * matter — at P=1 a `?0.5` over eight events lands the same twice in about one run in 256 — and a
 * third window is three more cheap renders.
 */
private fun patDetectPeriod(pattern: PatPattern<String>): Int? {
    val seen = (0 until 3 * PAT_CONDENSE_MAX_PERIOD).map { patCycleSignature(pattern, it.toLong()) }
    for (period in 1..PAT_CONDENSE_MAX_PERIOD) {
        val agrees = (0 until period).all { offset ->
            seen[offset] == seen[offset + period] && seen[offset] == seen[offset + 2 * period]
        }
        if (agrees) return period
    }
    return null
}

/** One reading of a cycle as non-overlapping layers of slot tokens: `[layer][slot]`. */
private typealias PatCondenseLayering = List<List<String>>

/**
 * A target reduced to what the search can work on.
 *
 * A token is an atom (`bd`, `bd:2`), a rest (`~`), or a hold (`_`) continuing the slot before it —
 * the same three things [patNotationOf] emits, which is what makes the flat spelling here and the
 * flat spelling there the same string.
 */
private class PatCondenseTarget(
    val cycles: Int,
    /** `[cycle][layering][layer][slot]`. Several layerings per cycle; the oracle picks. */
    val layerings: List<List<PatCondenseLayering>>,
    val signature: List<List<String>>
) {
    /** The flat spelling: every slot written out, layers joined by `,`, cycles by `< >`. */
    fun flat(): String = joinCycles(layerings.map { perCycle -> spell(perCycle.first()) })

    fun search(fallback: String?): PatCondensation {
        var oracleCalls = 0
        var spans = 0
        var bounded = false

        // Per cycle, a beam of whole-cycle strings, cheapest first, over every layering of it.
        val perCycle = layerings.map { readings ->
            val strings = LinkedHashSet<String>()
            for (reading in readings) {
                val searches = reading.map { layer -> PatCondenseSearch(layer).also { it.run() } }
                spans += searches.sumOf { it.spansExpanded }
                bounded = bounded || searches.any { it.bounded }
                // Variant t of every layer at once: the beams stay aligned and the product stays linear.
                for (variant in 0 until PAT_CONDENSE_BEAM) {
                    strings += searches.joinToString(", ") { it.pick(variant) }
                }
                strings += spell(reading)
            }
            strings.sortedWith(PAT_NOTATION_ORDER)
        }

        val flat = flat()
        val candidates = LinkedHashSet<String>()
        val deepest = perCycle.maxOf { it.size }
        for (variant in 0 until deepest) {
            candidates += joinCycles(perCycle.map { it[variant.coerceAtMost(it.size - 1)] })
        }
        candidates += flat
        if (fallback != null) candidates += fallback

        for (candidate in candidates.sortedWith(PAT_NOTATION_ORDER)) {
            if (oracleCalls >= PAT_CONDENSE_ORACLE_CALLS) {
                bounded = true
                break
            }
            oracleCalls++
            if (!reproduces(candidate)) continue
            return PatCondensation(
                notation = candidate,
                flat = flat,
                cycles = cycles,
                exact = true,
                structure = patNotationStructure(candidate),
                oracleCalls = oracleCalls,
                spansExpanded = spans,
                bounded = bounded
            )
        }
        return PatCondensation(
            notation = fallback ?: flat,
            flat = flat,
            cycles = cycles,
            exact = false,
            structure = emptyList(),
            refusal = "no candidate re-rendered to the target's events — not even the flat spelling, " +
                "which means the slot reduction lost something the player can hear",
            oracleCalls = oracleCalls,
            spansExpanded = spans,
            bounded = bounded
        )
    }

    /** The oracle. Re-parse through [pat] — the entry point a box calls — and compare, cycle for cycle. */
    private fun reproduces(candidate: String): Boolean {
        val parsed = try {
            pat(candidate)
        } catch (_: Throwable) {
            return false
        }
        for (cycle in 0 until cycles) {
            val got = try {
                patCycleSignature(parsed, cycle.toLong())
            } catch (_: Throwable) {
                return false
            }
            if (got != signature[cycle]) return false
        }
        return true
    }

    private fun spell(reading: PatCondenseLayering): String =
        reading.joinToString(", ") { it.joinToString(" ") }

    private fun joinCycles(perCycle: List<String>): String {
        if (perCycle.distinct().size == 1) return perCycle[0]
        return perCycle.joinToString(" ", prefix = "<", postfix = ">") { bracketAsTerm(it) }
    }
}

/**
 * One cycle's events, read as layers of slot tokens — as many readings as are available.
 *
 * A `,` in mini-notation is a stack, so a cycle whose events overlap has to be partitioned into
 * layers that do not. Two partitions are produced and **both** are handed on, because which reads
 * shorter depends on the figure: greedy first-fit by start time recovers `bd*4, ~ cp ~ cp` from four
 * kicks against two claps, while grouping by atom recovers a two-voice figure whose voices
 * interleave without ever overlapping. Neither is chosen here. The oracle chooses, by length.
 *
 * Empty when the cycle cannot be read at all.
 */
private fun patLayeringsOf(pattern: PatPattern<String>, cycle: Long): List<PatCondenseLayering> {
    val base = PatQ.of(cycle)
    val events = pattern.events(PatArc(base, base + PatQ.ONE))
        .filter { it.hasOnset }
        .sortedWith(compareBy({ it.extent.start }, { it.extent.end }, { it.value }))
    if (events.isEmpty()) return listOf(listOf(listOf("~")))
    for (event in events) {
        val whole = event.whole ?: return emptyList()      // analog: no slot to put it in
        if (whole.start < base || whole.end > base + PatQ.ONE) return emptyList()
    }

    val partitions = mutableListOf(greedyLayers(events))
    val byValue = events.groupBy { it.value }.values.toList()
    if (byValue.size > 1 && byValue.all { nonOverlapping(it) }) partitions += byValue

    val readings = LinkedHashSet<PatCondenseLayering>()
    for (partition in partitions) {
        val layers = partition.map { slotsOf(it, base) }
        if (layers.any { it == null }) continue
        readings += layers.filterNotNull()
    }
    return readings.toList()
}

private fun nonOverlapping(events: List<PatEvent<String>>): Boolean {
    val sorted = events.sortedBy { it.extent.start }
    for (index in 1 until sorted.size) {
        if (sorted[index].extent.start < sorted[index - 1].extent.end) return false
    }
    return true
}

/** First-fit interval partition: each event joins the first layer whose last event has already ended. */
private fun greedyLayers(events: List<PatEvent<String>>): List<List<PatEvent<String>>> {
    val layers = mutableListOf<MutableList<PatEvent<String>>>()
    for (event in events) {
        val layer = layers.firstOrNull { it.last().extent.end <= event.extent.start }
        if (layer == null) layers += mutableListOf(event) else layer += event
    }
    return layers
}

/**
 * One layer's events as slot tokens on the coarsest grid that holds them.
 *
 * The grid's denominator is the lcm of every boundary's denominator, and it is then divided through
 * by the gcd of the boundaries themselves — without that second step `bd sd` arrives as twelve slots
 * whenever some *other* layer needed twelfths, and the search spends its beam re-discovering that
 * `bd _ _ _ _ _ sd _ _ _ _ _` is two steps.
 */
private fun slotsOf(events: List<PatEvent<String>>, base: PatQ): List<String>? {
    if (events.isEmpty()) return listOf("~")
    val spans = events.map { event ->
        val whole = event.whole ?: return null
        Pair(whole.start - base, whole.end - base)
    }
    var denominator = BigInteger.ONE
    for ((start, end) in spans) {
        denominator = lcm(denominator, start.d)
        denominator = lcm(denominator, end.d)
    }
    if (denominator > BigInteger.valueOf(PAT_CONDENSE_MAX_SLOTS.toLong())) return null
    val total = denominator.toInt()

    val boundaries = mutableListOf(0L, total.toLong())
    val cuts = spans.map { (start, end) ->
        val a = start.n * (denominator / start.d)
        val b = end.n * (denominator / end.d)
        boundaries += a.toLong()
        boundaries += b.toLong()
        Pair(a.toInt(), b.toInt())
    }
    var common = 0L
    for (boundary in boundaries) common = gcdLong(common, boundary)
    val divisor = if (common <= 0L) 1 else common.toInt()

    val slots = arrayOfNulls<String>(total / divisor)
    for ((index, cut) in cuts.withIndex()) {
        val from = cut.first / divisor
        val to = cut.second / divisor
        if (to <= from || to > slots.size) return null
        if (slots[from] != null) return null
        slots[from] = events[index].value
        for (slot in (from + 1) until to) {
            if (slots[slot] != null) return null
            slots[slot] = "_"
        }
    }
    return slots.map { it ?: "~" }
}

// ── the range DP ────────────────────────────────────────────────────────────────────────────────

/** One candidate spelling of a span, and whether it can stand as a sequence item without brackets. */
private class PatCand(val text: String, val atomic: Boolean)

/**
 * Shortest first; among equals, fewest `@`/`!`; then alphabetical, so a run is reproducible.
 *
 * The middle term is not taste. `@` and `!` are the two operators that reach *out* of their term and
 * re-weight the sequence around them, so of two spellings the same length the one with fewer of them
 * survives being pasted into a longer line — and `bd*4` and `bd!4` are exactly the same length.
 */
private val PAT_NOTATION_ORDER: Comparator<String> =
    compareBy({ it.length }, { it.count { c -> c == '@' || c == '!' } }, { it })

private val PAT_CAND_ORDER: Comparator<PatCand> = compareBy(PAT_NOTATION_ORDER) { it.text }

/**
 * A description-length-minimising search over one layer's slots.
 *
 * The recursion is over **spans that a mini-notation split can actually produce**: a group divides
 * its span into equal parts, so only spans whose length divides the whole and whose start is aligned
 * to it are reachable. That is what keeps the search finite without a heuristic — the alternative,
 * an arbitrary substring DP, proposes `[bd sd] hh` for three equal steps, which the grammar cannot
 * mean.
 *
 * Unequal parts are reachable through weights: `bd@3 cp` is four slots split three-and-one, and this
 * search finds it by splitting into four, then collapsing the `bd _ _` that results.
 */
private class PatCondenseSearch(private val cells: List<String>) {
    private val memo = HashMap<Int, List<PatCand>>()
    var spansExpanded: Int = 0
        private set
    var bounded: Boolean = false
        private set
    private var top: List<PatCand> = emptyList()

    fun run() {
        top = seq(0, cells.size)
    }

    /** The [index]-th best whole-layer spelling, clamped to the beam that was actually found. */
    fun pick(index: Int): String {
        if (top.isEmpty()) return cells.joinToString(" ")
        return top[index.coerceAtMost(top.size - 1)].text
    }

    /** Candidates for `cells[from, to)` as the entire content of a span. */
    private fun seq(from: Int, to: Int): List<PatCand> {
        val key = from * (cells.size + 1) + to
        memo[key]?.let { return it }
        if (spansExpanded >= PAT_CONDENSE_NODES) {
            bounded = true
            return listOf(PatCand(flatOf(from, to), false))
        }
        spansExpanded++

        val length = to - from
        if (cells[from] == "_") return listOf(PatCand(flatOf(from, to), false))
        if (length == 1) {
            val only = listOf(PatCand(cells[from], true))
            memo[key] = only
            return only
        }

        val out = mutableListOf<PatCand>()
        if ((from until to).all { cells[it] == "~" }) out += PatCand("~", true)
        // One event held across the whole span is just its atom: an item's own value fills its item.
        // Without this the search never finds `bd hh*2 sd`, because it cannot see that the three
        // slots `hh _ _` are the *unit* `hh` rather than the elongation `hh@3`, and `@` re-weights
        // the parent while a bare atom does not.
        if (cells[from] != "~" && (from + 1 until to).all { cells[it] == "_" }) {
            out += PatCand(cells[from], true)
        }
        out += repeatCands(from, to)
        out += rhythmCands(from, to)
        out += splitCands(from, to)
        if (out.isEmpty()) out += PatCand(flatOf(from, to), false)

        val best = out
            .distinctBy { it.text }
            .sortedWith(PAT_CAND_ORDER)
            .take(PAT_CONDENSE_BEAM)
        memo[key] = best
        return best
    }

    /** Candidates for the same span used as one item of a parent sequence — bracketed where it must be. */
    private fun unit(from: Int, to: Int): List<PatCand> =
        seq(from, to)
            .map { if (it.atomic) it else PatCand("[${it.text}]", true) }
            .distinctBy { it.text }
            .sortedWith(PAT_CAND_ORDER)
            .take(PAT_CONDENSE_BEAM)

    /** `x*n` — the span is `n` copies of its own first `length/n` slots. */
    private fun repeatCands(from: Int, to: Int): List<PatCand> {
        val length = to - from
        val out = mutableListOf<PatCand>()
        for (times in divisorsAbove1(length)) {
            val period = length / times
            var repeats = true
            for (offset in period until length) {
                if (cells[from + offset] != cells[from + offset % period]) {
                    repeats = false
                    break
                }
            }
            if (!repeats) continue
            for (inner in unit(from, from + period)) out += PatCand("${inner.text}*$times", true)
        }
        return out
    }

    /**
     * `X(p,s,r)` and `X(kN)` / `X(gN)` — the span is one repeated thing on a named figure.
     *
     * **`X` is a term, not an atom.** `bd(3,8)` and `[bd cp](3,8)` are both reachable, because
     * `euclidFill` is built on `mask`, and `mask` *squeezes a whole cycle of its argument into each
     * true step* (`TidalPatterns.kt:876`) rather than clipping it. So on the slot grid the figure is
     * `s` blocks of equal width, each either all rests or cell-for-cell the same block, and the
     * content of that block is whatever the DP can spell it as. Reading the family off one slot at a
     * time would find `bd(3,8)` and miss everything above it.
     *
     * The block width is swept over every divisor of the span, so the same scan covers a one-slot
     * atom (`width == 1`) and a sixteenth-note pair. A block that begins with a hold is rejected: `_`
     * has nothing to continue at the start of a group.
     *
     * The masks are rebuilt from [bjorklund] and [kolakoskiMask] and rotated locally rather than
     * regenerated per offset. The emitted offset sense is the parser's — `MiniPat.compileNode` passes
     * `rotation = -offset` — and if that sign is ever wrong the oracle drops the candidate rather
     * than letting it out with the figure mis-turned.
     */
    private fun rhythmCands(from: Int, to: Int): List<PatCand> {
        val length = to - from
        if (length < 2) return emptyList()
        val out = mutableListOf<PatCand>()

        for (width in 1..length) {
            if (length % width != 0) continue
            val steps = length / width
            if (steps < 2) continue

            var body: List<String>? = null
            var bodyAt = -1
            var usable = true
            val target = ArrayList<Boolean>(steps)
            for (index in 0 until steps) {
                val start = from + index * width
                val block = (0 until width).map { cells[start + it] }
                if (block.all { it == "~" }) {
                    target += false
                    continue
                }
                if (block[0] == "_") {
                    usable = false
                    break
                }
                if (body == null) {
                    body = block
                    bodyAt = start
                } else if (body != block) {
                    usable = false
                    break
                }
                target += true
            }
            if (!usable || body == null) continue
            val pulses = target.count { it }
            if (pulses == 0 || pulses == steps) continue

            val spellings = if (width == 1) listOf(PatCand(cells[bodyAt], true)) else unit(bodyAt, bodyAt + width)
            if (spellings.isEmpty()) continue

            val named = mutableListOf<String>()
            val euclidean = bjorklund(pulses, steps)
            for (offset in 0 until steps) {
                if (rotated(euclidean, -offset) != target) continue
                named += if (offset == 0) "($pulses,$steps)" else "($pulses,$steps,$offset)"
                break
            }
            for (reading in KolakoskiReading.entries) {
                val letter = if (reading == KolakoskiReading.RUNS) "k" else "g"
                val base = kolakoskiMask(steps, reading)
                for (offset in 0 until steps) {
                    if (rotated(base, -offset) != target) continue
                    named += if (offset == 0) "($letter$steps)" else "($letter$steps,$offset)"
                    break
                }
            }
            for (suffix in named) {
                for (spelling in spellings) out += PatCand(spelling.text + suffix, true)
            }
        }
        return out
    }

    /**
     * Split the span into `m` equal parts for every divisor `m`, then collapse.
     *
     * Two collapses run over the resulting token list, and both the collapsed and the uncollapsed
     * spelling are proposed because the two are the same length at `n = 2` and the oracle is the
     * cheaper tiebreak:
     *
     *  - a part that is a bare `_` folds into the token before it as `@n` (`TidalPatterns.kt:1208`
     *    — `bd _ _ sd` *is* `bd@3 sd`, so this is a rename, not a re-timing);
     *  - a run of identical adjacent tokens folds to `x!n`, which `expandReplicatedItems` unfolds
     *    back into that many sibling items.
     *
     * `_` itself is never folded with `!`: the parser treats `_` as a standalone marker whenever the
     * next character is one of `STANDALONE_FOLLOWERS`, and `!` is in that set, so `_!2` consumes the
     * `_` as a hold and then fails on a bare `!`.
     */
    private fun splitCands(from: Int, to: Int): List<PatCand> {
        val length = to - from
        val out = mutableListOf<PatCand>()
        for (parts in divisorsAbove1(length)) {
            val width = length / parts
            if (width > 1 && (0 until parts).any { cells[from + it * width] == "_" }) continue
            for (variant in 0 until PAT_CONDENSE_BEAM) {
                val tokens = ArrayList<String>(parts)
                var ok = true
                for (part in 0 until parts) {
                    val start = from + part * width
                    if (width == 1) {
                        tokens += cells[start]
                    } else {
                        val choices = unit(start, start + width)
                        if (choices.isEmpty()) {
                            ok = false
                            break
                        }
                        tokens += choices[variant.coerceAtMost(choices.size - 1)].text
                    }
                }
                if (!ok) continue
                val stages = listOfNotNull(tokens, foldHolds(tokens)).distinct()
                for (stage in stages) {
                    for (spelling in listOf(stage, foldReplication(stage)).distinct()) {
                        val atomic = spelling.size == 1 && !suffixed(spelling[0])
                        out += PatCand(spelling.joinToString(" "), atomic)
                    }
                }
            }
        }
        return out
    }

    private fun flatOf(from: Int, to: Int): String = (from until to).joinToString(" ") { cells[it] }
}

/** `x _ _` → `x@3`. Null when a hold has nothing to attach to, which the caller then drops. */
private fun foldHolds(tokens: List<String>): List<String>? {
    val out = mutableListOf<String>()
    val holds = mutableListOf<Int>()
    for (token in tokens) {
        if (token == "_") {
            if (out.isEmpty()) return null
            holds[holds.size - 1] = holds.last() + 1
        } else {
            out += token
            holds += 1
        }
    }
    if (holds.all { it == 1 }) return tokens
    return out.mapIndexed { index, token -> if (holds[index] > 1) "$token@${holds[index]}" else token }
}

/** `x x x` → `x!3`. Never applied to `_`, which the parser reads as a marker rather than a term. */
private fun foldReplication(tokens: List<String>): List<String> {
    val out = mutableListOf<String>()
    var index = 0
    while (index < tokens.size) {
        var run = 1
        while (index + run < tokens.size && tokens[index + run] == tokens[index]) run++
        if (run > 1 && tokens[index] != "_") out += "${tokens[index]}!$run" else {
            repeat(run) { out += tokens[index] }
        }
        index += run
    }
    return out
}

/**
 * True when a token carries `@` or `!` at its top level, which makes it unusable as a bare item.
 *
 * Both re-weight the *parent* sequence — `@n` widens the item's slot and `!n` splits it into n
 * siblings — so `pat("x a@2 y")` is four slots while `pat("x [a@2] y")` is three. The brackets are
 * not cosmetic and dropping them is the one way this search can silently mis-time a figure; it is
 * also why every candidate goes through the oracle.
 */
private fun suffixed(token: String): Boolean {
    var depth = 0
    for (char in token) {
        when (char) {
            '[', '<', '{', '(' -> depth++
            ']', '>', '}', ')' -> depth--
            '@', '!' -> if (depth == 0) return true
        }
    }
    return false
}

/** Wrap [text] so it can sit inside `< … >`, where each alternative is read as a single term. */
private fun bracketAsTerm(text: String): String {
    if (text.isEmpty()) return "~"
    var depth = 0
    for (char in text) {
        when (char) {
            '[', '<', '{', '(' -> depth++
            ']', '>', '}', ')' -> depth--
            ' ', ',' -> if (depth == 0) return "[$text]"
        }
    }
    return if (suffixed(text)) "[$text]" else text
}

// ── shared helpers ──────────────────────────────────────────────────────────────────────────────

/**
 * One cycle of [pattern], canonicalised for comparison.
 *
 * Part, whole and value, relative to the cycle's own start, plus the annotations an atom's `:n`
 * suffix carries — everything a player can hear or read off an event. Source spans are deliberately
 * not in it: `bd(3,8)` and `bd ~ ~ bd ~ ~ bd ~` cite different substrings of different strings and
 * sound identical, and it is the sound this file is asked to preserve.
 */
internal fun patCycleSignature(pattern: PatPattern<String>, cycle: Long): List<String> {
    val base = PatQ.of(cycle)
    return pattern.events(PatArc(base, base + PatQ.ONE))
        .map { event ->
            val whole = event.whole
            val extent = if (whole == null) "analog" else "${whole.start - base}..${whole.end - base}"
            val annotations = event.annotations.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
            "${event.arc.start - base}..${event.arc.end - base}|$extent|${event.value}|$annotations"
        }
        .sorted()
}

/** The mini-notation constructs in [source], read off its parse. */
internal fun patNotationStructure(source: String): List<String> {
    val found = sortedSetOf<String>()
    fun walk(node: MiniPat.Node) {
        when (node) {
            is MiniPat.Atom -> found += "atom"
            is MiniPat.Rest -> found += "rest"
            is MiniPat.Seq -> {
                found += "sequence"
                node.items.forEach(::walk)
            }
            is MiniPat.Stk -> {
                found += "stack"
                node.layers.forEach(::walk)
            }
            is MiniPat.Choice -> {
                found += "alternation"
                node.alts.forEach(::walk)
            }
            is MiniPat.RandomChoice -> {
                found += "choice"
                node.alts.forEach(::walk)
            }
            is MiniPat.Poly -> {
                found += "polymeter"
                node.parts.forEach(::walk)
            }
            is MiniPat.Repeat -> {
                found += "replicate"
                walk(node.expr)
            }
            is MiniPat.Fast -> {
                found += "repeat"
                walk(node.expr)
            }
            is MiniPat.Slow -> {
                found += "slow"
                walk(node.expr)
            }
            is MiniPat.Elongate -> {
                found += "elongate"
                walk(node.expr)
            }
            is MiniPat.Euclid -> {
                found += "euclid"
                walk(node.expr)
            }
            is MiniPat.Kolakoski -> {
                found += if (node.reading == KolakoskiReading.RUNS) "kolakoski" else "kolakoskiGaps"
                walk(node.expr)
            }
            is MiniPat.Degrade -> {
                found += "degrade"
                walk(node.expr)
            }
            is MiniPat.Expr -> found += "expression"
        }
    }
    return try {
        walk(MiniPat.parseToAst(source))
        found.toList()
    } catch (_: Throwable) {
        emptyList()
    }
}

private fun rotated(mask: List<Boolean>, steps: Int): List<Boolean> {
    val size = mask.size
    if (size == 0) return mask
    val amount = ((steps % size) + size) % size
    return List(size) { index -> mask[((index - amount) % size + size) % size] }
}

private fun divisorsAbove1(length: Int): List<Int> =
    (2..length).filter { length % it == 0 }

private fun lcm(a: BigInteger, b: BigInteger): BigInteger =
    if (a.signum() == 0 || b.signum() == 0) BigInteger.ZERO else a / a.gcd(b) * b

private fun gcdLong(a: Long, b: Long): Long {
    var x = if (a < 0) -a else a
    var y = if (b < 0) -b else b
    while (y != 0L) {
        val t = x % y
        x = y
        y = t
    }
    return x
}
