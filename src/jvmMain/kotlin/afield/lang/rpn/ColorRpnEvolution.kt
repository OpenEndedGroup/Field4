@file:afield.runtime.completion.CompletionCategory("Search")

package afield.lang.rpn

import afield.document.graph.Box
import afield.study.search.EyeEvolution
import afield.study.search.EyeSetting
import afield.study.search.Offspring
import afield.study.search.evolveByEye

/**
 * The madp GA demo in a box: two [ColorRpn] parents and seven children in a 3×3 grid, bred by
 * [ColorRpnGenome]; click two tiles and they become the parents of the next generation.
 *
 * ```kotlin
 * val ga = colorRpnEvolution(
 *     """
 *     x 9 * sin 0.18 +
 *     y 0.55 +
 *     0.62
 *     hsl
 *     """,
 *     """
 *     a 14 * cos 0.14 +
 *     r 0.78 * 0.12 +
 *     0.48
 *     hsl
 *     """,
 * )
 * ```
 *
 * The toolbar holds the demo's **mut** (mutation rate), **len** (target length) and **res** (the
 * resolution each tile renders at), **Reroll** (new children of the same parents), **Fresh
 * Parents** (two random programs) and **Start over** (back to [parentA] and [parentB]). The
 * current parents are printed in full under the grid, ready to copy back into the box; re-running
 * the box starts again from the code's parents.
 *
 * Tiles render on the CPU at [resolution]² (128, 192, 256 or 320).
 *
 * @param breeding the breeding rules; [ColorRpnBreeding] holds the demo's
 * @param seed seeds the breeding, for a reproducible run
 */
fun Box.colorRpnEvolution(
    parentA: String,
    parentB: String,
    breeding: ColorRpnBreeding = ColorRpnBreeding(),
    resolution: Int = 256,
    seed: Long? = null,
    channel: String = "ga",
): EyeEvolution<List<String>> {
    var genome = ColorRpnGenome(breeding)
    var size = resolution
    val settings = listOf(
        EyeSetting("mut", breeding.mutationRate, 0.0, 1.0, step = 0.01, label = "mut") { v ->
            genome = ColorRpnGenome(genome.breeding.copy(mutationRate = v))
        },
        EyeSetting("len", breeding.targetLength.toDouble(), 4.0, 96.0, label = "len") { v ->
            genome = ColorRpnGenome(genome.breeding.copy(targetLength = v.toInt()))
        },
        EyeSetting("res", resolution.toDouble(), 64.0, 512.0, choices = RESOLUTIONS.map { it.toDouble() }, label = "res") { v -> size = v.toInt() },
    )
    return evolveByEye(
        parents = listOf(ColorRpn.tokens(parentA.trimIndent()), ColorRpn.tokens(parentB.trimIndent())),
        breed = { ps, rng -> genome.child(ps[0], ps.getOrElse(1) { ps[0] }, rng).let { Offspring(it.tokens, it.summary) } },
        picture = { tokens -> ColorRpn.compile(tokens.joinToString(" ")).png(size) },
        text = { tokens -> ColorRpn.pretty(tokens) },
        meta = { tokens -> "${ColorRpn.compile(tokens.joinToString(" ")).tokens.size}t" },
        fresh = { rng -> genome.randomProgram(genome.breeding.targetLength, rng) },
        channel = channel,
        title = "ga",
        settings = settings,
        seed = seed,
    )
}

private val RESOLUTIONS = listOf(128, 192, 256, 320)
