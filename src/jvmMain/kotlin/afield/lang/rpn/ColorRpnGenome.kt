@file:afield.runtime.completion.CompletionCategory("Search")

package afield.lang.rpn

import afield.study.search.GenomeOps
import afield.study.search.Variation
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The breeding rules for [ColorRpn] programs: what a child of two programs is, and the eight
 * mutations that make one.
 *
 * [ColorRpnBreeding] is the knobs, with the madp GA demo's values as the defaults: how many
 * mutations a child gets, the length it is nudged toward, the chance of splicing a slice out of
 * the other parent, and how often each of the eight mutations is chosen. [ColorRpnGenome] applies
 * them, and because it implements [GenomeOps] the same genome drives both halves of
 * `afield.study.search`: the picture grid of `EyeEvolution.kt`, where you pick the parents, and
 * the scored drivers of `PopulationSearch.kt`, where an [Objective] picks them.
 *
 * A mutation can produce a program that makes no sense, and nothing rejects it. [ColorRpn] reads
 * any token sequence as some picture, which is what lets a search of this space run without a
 * validity test.
 */

/**
 * How the GA demo breeds [ColorRpn] programs. The defaults are the demo's.
 *
 * @property mutationRate scales how many mutations a child gets: about `mutationRate × targetLength
 *   × (0.45..0.85)`, and at least one with probability `mutationRate × 2.5` when that rounds to none
 * @property targetLength the length a child is nudged toward, give or take two tokens (4..96)
 * @property seedSplice the chance that a child, before mutating, takes a slice of one to three
 *   tokens from the other parent
 * @property weights the relative frequency of each mutation, by the names [ColorRpnGenome.mutations] uses
 * @property nudgeLength whether a child is grown or trimmed toward [targetLength] after mutating;
 *   `false` leaves it as long as its mutations made it
 */
data class ColorRpnBreeding(
    val mutationRate: Double = 0.12,
    val targetLength: Int = 18,
    val seedSplice: Double = 0.42,
    val weights: Map<String, Double> = DEFAULT_WEIGHTS,
    val nudgeLength: Boolean = true,
) {
    companion object {
        /**
         * The demo's mix: a number tweaked 33%, a number turned into a variable 19%, an operator
         * swapped 20%, a slice spliced in from the other parent 10%, a slice of itself copied 6%,
         * one or two tokens dropped 6%, a random token added 4.5%, two tokens swapped 1.5%.
         */
        val DEFAULT_WEIGHTS: Map<String, Double> = linkedMapOf(
            "num" to 0.33, "var" to 0.19, "op" to 0.20, "splice" to 0.10,
            "copy" to 0.06, "drop" to 0.06, "add" to 0.045, "swap" to 0.015,
        )
    }
}

/**
 * [ColorRpn] programs as a genome: a list of tokens, bred the way the madp GA demo breeds them.
 *
 * [crossover] is the demo's child: copy one parent at random; with chance
 * [ColorRpnBreeding.seedSplice] splice in one to three tokens from the other; apply a few weighted
 * mutations (each may borrow from the other parent); then nudge the length to
 * [ColorRpnBreeding.targetLength] ± 2, at most 96 tokens. [mutations] are the same eight operators
 * by name, each borrowing from the genome itself, so `PopulationSearch` can drive this genome with
 * a computed objective as well as an eye.
 *
 * Programs that fail to make sense are not rejected: the language reads anything (see [ColorRpn]).
 */
class ColorRpnGenome(val breeding: ColorRpnBreeding = ColorRpnBreeding()) : GenomeOps<List<String>> {

    override fun random(rng: Random): List<String> = randomProgram(breeding.targetLength, rng)

    override val mutations: List<Variation<List<String>>> = breeding.weights.keys.map { name ->
        Variation(name) { g: List<String>, rng: Random -> g.toMutableList().also { apply(name, it, g, rng) } }
    }

    override fun crossover(a: List<String>, b: List<String>, rng: Random): List<String> = child(a, b, rng).tokens

    override fun signature(g: List<String>): String = ColorRpn.compile(g.joinToString(" ")).normalized

    /** A child and the names of the mutations that made it (the demo's tile caption, e.g. `seed+num+op`). */
    class Child(val tokens: List<String>, val notes: List<String>) {
        /** The first four notes joined by `+`, or `mut` when there were none. */
        val summary: String get() = notes.take(4).joinToString("+").ifEmpty { "mut" }
    }

    /** One child of [parentA] and [parentB]; see the class description. */
    fun child(parentA: List<String>, parentB: List<String>, rng: Random): Child {
        val fromA = rng.nextDouble() < 0.5
        var base = (if (fromA) parentA else parentB).toMutableList()
        val donor = if (fromA) parentB else parentA
        val notes = mutableListOf<String>()
        if (base.isEmpty() && donor.isEmpty()) base = mutableListOf("0.5")
        if (rng.nextDouble() < breeding.seedSplice && donor.isNotEmpty()) {
            val slice = randomSlice(donor, rng)
            val start = rng.nextInt(base.size + 1)
            val remove = if (base.isNotEmpty()) rng.nextInt(min(2, base.size - min(start, base.size - 1) + 1)) else 0
            splice(base, start, remove, slice)
            notes += "seed"
        }
        val length = breeding.targetLength.coerceIn(4, 96)
        var count = (breeding.mutationRate * length * (0.45 + rng.nextDouble() * 0.4)).roundToInt()
        if (count < 1 && rng.nextDouble() < breeding.mutationRate * 2.5) count = 1
        repeat(count) { _ -> mutate(base, donor, rng, notes) }
        val target = max(4, (length + (rng.nextDouble() * 2 - 1) * 2).roundToInt())
        if (breeding.nudgeLength) nudgeLength(base, donor, target, rng)
        return Child(base.take(96), notes)
    }

    /** [count] children of the two parents, as the demo's grid has seven. */
    fun children(parentA: List<String>, parentB: List<String>, count: Int, rng: Random): List<Child> =
        List(count) { _ -> child(parentA, parentB, rng) }

    /** A random program of [length] tokens: a third numbers, a third variables, a third operators. */
    fun randomProgram(length: Int, rng: Random): List<String> = List(length) { _ -> randomToken(rng) }

    /** Apply one mutation, chosen by [ColorRpnBreeding.weights], to [tokens] in place, borrowing slices from [donor]. */
    fun mutate(tokens: MutableList<String>, donor: List<String>, rng: Random, notes: MutableList<String>) {
        val total = breeding.weights.values.sum()
        var roll = rng.nextDouble() * total
        var chosen = breeding.weights.keys.last()
        for ((name, w) in breeding.weights) {
            if (roll < w) { chosen = name; break }
            roll -= w
        }
        notes += apply(chosen, tokens, donor, rng)
    }

    /** One named mutation; returns the note the demo records for it. */
    private fun apply(name: String, tokens: MutableList<String>, donor: List<String>, rng: Random): String {
        if (tokens.isEmpty()) tokens += randomToken(rng)
        when (name) {
            "num" -> {
                var idx = pickIndex(tokens, rng) { ColorRpn.number(it) != null }
                if (idx < 0) idx = rng.nextInt(tokens.size + 1)
                if (idx >= tokens.size) tokens.add(idx, randomNumber(rng)) else tokens[idx] = tweakNumber(tokens[idx], rng)
            }
            "var" -> {
                val idx = pickIndex(tokens, rng) { ColorRpn.number(it) != null }
                if (idx >= 0) tokens[idx] = randomVariable(rng) else tokens.add(rng.nextInt(tokens.size + 1), randomVariable(rng))
            }
            "op" -> {
                // as in the demo, "any word" includes the variables, so a variable can become an operator
                val idx = pickIndex(tokens, rng) { it in ColorRpn.operators || it in ColorRpn.variables }
                if (idx >= 0) tokens[idx] = randomOperator(rng) else tokens.add(rng.nextInt(tokens.size + 1), randomOperator(rng))
            }
            "splice" -> {
                val slice = if (donor.isNotEmpty()) randomSlice(donor, rng) else listOf(randomToken(rng))
                val at = rng.nextInt(tokens.size + 1)
                val remove = if (tokens.isNotEmpty()) rng.nextInt(min(2, tokens.size - at + 1)) else 0
                splice(tokens, at, remove, slice)
            }
            "copy" -> {
                val slice = randomSlice(tokens, rng)
                splice(tokens, rng.nextInt(tokens.size + 1), 0, slice)
            }
            "drop" -> {
                if (tokens.size > 1) {
                    val idx = rng.nextInt(tokens.size)
                    val remove = 1 + rng.nextInt(min(2, tokens.size - idx))
                    splice(tokens, idx, remove, emptyList())
                } else {
                    tokens[0] = randomToken(rng)
                }
            }
            "add" -> tokens.add(rng.nextInt(tokens.size + 1), randomToken(rng))
            "swap" -> {
                if (tokens.size > 1) {
                    val i = rng.nextInt(tokens.size)
                    val j = rng.nextInt(tokens.size)
                    val t = tokens[i]; tokens[i] = tokens[j]; tokens[j] = t
                } else {
                    tokens += randomToken(rng)
                    return "grow"
                }
            }
            else -> throw IllegalArgumentException("unknown mutation '$name'; the mutations are ${ColorRpnBreeding.DEFAULT_WEIGHTS.keys}")
        }
        return name
    }

    private fun nudgeLength(tokens: MutableList<String>, donor: List<String>, target: Int, rng: Random) {
        while (tokens.size < target) {
            if (rng.nextDouble() < 0.25 && donor.isNotEmpty()) splice(tokens, rng.nextInt(tokens.size + 1), 0, randomSlice(donor, rng))
            else tokens.add(rng.nextInt(tokens.size + 1), randomToken(rng))
            if (tokens.size > target + 4) break
        }
        while (tokens.size > target && tokens.size > 1) {
            val start = rng.nextInt(tokens.size)
            val count = 1 + rng.nextInt(min(3, tokens.size - start))
            splice(tokens, start, count, emptyList())
        }
        if (tokens.isEmpty()) tokens += randomToken(rng)
    }

    private fun splice(tokens: MutableList<String>, at: Int, remove: Int, insert: List<String>) {
        repeat(remove) { _ -> if (at < tokens.size) tokens.removeAt(at) }
        tokens.addAll(min(at, tokens.size), insert)
    }

    private fun randomSlice(tokens: List<String>, rng: Random): List<String> {
        if (tokens.isEmpty()) return emptyList()
        val start = rng.nextInt(tokens.size)
        val span = 1 + rng.nextInt(min(3, tokens.size))
        return tokens.subList(start, min(tokens.size, start + span)).toList()
    }

    private fun pickIndex(tokens: List<String>, rng: Random, predicate: (String) -> Boolean): Int {
        val matches = tokens.indices.filter { predicate(tokens[it]) }
        return if (matches.isEmpty()) -1 else matches[rng.nextInt(matches.size)]
    }

    private fun tweakNumber(token: String, rng: Random): String {
        var n = ColorRpn.number(token) ?: return randomNumber(rng)
        val mode = rng.nextDouble()
        n = when {
            mode < 0.34 -> n + (rng.nextDouble() * 2 - 1) * 0.18
            mode < 0.67 -> n * (0.55 + rng.nextDouble() * 1.4)
            rng.nextDouble() < 0.7 -> rng.nextDouble()
            else -> rng.nextDouble() * 4 - 2
        }
        return ColorRpn.format(n)
    }

    private fun randomNumber(rng: Random): String {
        val roll = rng.nextDouble()
        val v = when {
            roll < 0.55 -> rng.nextDouble()
            roll < 0.8 -> rng.nextDouble() * 4 - 2
            else -> (if (rng.nextDouble() < 0.5) -1 else 1) * (1 + rng.nextDouble() * 12)
        }
        return ColorRpn.format(v)
    }

    private fun randomVariable(rng: Random) = ColorRpn.variables[rng.nextInt(ColorRpn.variables.size)]

    private fun randomOperator(rng: Random) = ColorRpn.operators[rng.nextInt(ColorRpn.operators.size)]

    private fun randomToken(rng: Random): String = when (rng.nextInt(3)) {
        0 -> randomNumber(rng)
        1 -> randomVariable(rng)
        else -> randomOperator(rng)
    }
}
