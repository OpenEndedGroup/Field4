package afield.examples

import afield.stdlib.BooleanNetwork
import afield.stdlib.findAttractors
import afield.stdlib.kauffmanNetwork
import afield.stdlib.printAttractors
import afield.stdlib.segmentPolarityNetwork
import afield.stdlib.threeNodeNetwork
import afield.stdlib.toggleNetwork

/**
 * Console-oriented examples for the Boolean-network stdlib.
 *
 * The model, attractor analysis, and FLine visualizations now live in
 * `afield.stdlib.booleannetworks.kt`; this file is intentionally just a thin
 * showcase layer.
 */
object BooleanNetworkExplorer {

    @JvmStatic
    fun main(args: Array<String>) {
        println("=".repeat(72))
        println("Boolean network explorer")
        println("=".repeat(72))

        describeNetwork("toggle", toggleNetwork())
        describeNetwork("three-node", threeNodeNetwork())
        describeNetwork("segment polarity", segmentPolarityNetwork())

        println()
        println("Kauffman regime sweep (n=8, seeds=0..11)")
        println("k | mean attractors | mean period | mean fixed points")
        println("--|-----------------|-------------|------------------")
        regimeSweep(n = 8, ks = 1..4, seeds = 0..11).forEach { row ->
            println(
                "${row.k} | ${"%.2f".format(row.meanAttractorCount).padStart(15)} | " +
                    "${"%.2f".format(row.meanPeriod).padStart(11)} | " +
                    "${"%.2f".format(row.meanFixedPointCount).padStart(16)}"
            )
        }
    }

    private fun describeNetwork(name: String, network: BooleanNetwork) {
        val attractors = network.findAttractors()
        println()
        println("$name (${network.size} nodes)")
        println("  attractors=${attractors.size}  basins=${attractors.joinToString { it.basin.size.toString() }}")
        network.printAttractors()
    }
}

data class KauffmanRegimeRow(
    val k: Int,
    val meanAttractorCount: Double,
    val meanPeriod: Double,
    val meanFixedPointCount: Double,
)

fun regimeSweep(
    n: Int,
    ks: IntRange,
    seeds: IntRange,
): List<KauffmanRegimeRow> = ks.map { k ->
    val runs = seeds.map { seed ->
        val attractors = kauffmanNetwork(n = n, k = k, seed = seed.toLong()).findAttractors()
        Triple(
            attractors.size.toDouble(),
            if (attractors.isEmpty()) 0.0 else attractors.map { it.period.toDouble() }.average(),
            attractors.count { it.period == 1 }.toDouble(),
        )
    }
    KauffmanRegimeRow(
        k = k,
        meanAttractorCount = runs.map { it.first }.average(),
        meanPeriod = runs.map { it.second }.average(),
        meanFixedPointCount = runs.map { it.third }.average(),
    )
}
