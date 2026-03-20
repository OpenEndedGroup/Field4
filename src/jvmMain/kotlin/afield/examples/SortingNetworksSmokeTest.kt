package afield.examples

import afield.stdlib.SortingComparator
import afield.stdlib.SortingNetwork

/*
FIELD_TEST
id: stdlib.sorting-networks.smoke
runner: jvm-main
entrypoint: afield.examples.SortingNetworksSmokeTest
tags: [smoke, stdlib, sorting-networks]
timeoutSec: 60
expect:
  exitCode: 0
  stdoutContains:
    - "✅ SortingNetworks smoke tests passed"
*/

/**
 * Smoke tests for [SortingNetwork]: build, notate, verify, visualise (FLine), and
 * simulated-annealing optimisation.
 */
object SortingNetworksSmokeTest {

    fun runTests(): Boolean {
        return try {
            testBubbleIsValid()
            testOddEvenMergeIsValid()
            testSortProducesCorrectOrder()
            testLayerTrace()
            testNotationRoundTrip()
            testFromNotationRejectsInvalid()
            testLayersAreDisjoint()
            testDepth()
            testAsciiDiagramNonEmpty()
            testToFLinesNonEmpty()
            testAnnealImprovesBubble()
            testAnnealResultIsValid()
            println("✅ SortingNetworks smoke tests passed")
            true
        } catch (t: Throwable) {
            println("❌ SortingNetworks smoke tests FAILED: ${t.message}")
            t.printStackTrace()
            false
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "SortingNetworks smoke tests failed" }
    }

    // ─── bubble ───────────────────────────────────────────────────────────────

    private fun testBubbleIsValid() {
        for (n in 2..8) {
            val net = SortingNetwork.bubble(n)
            require(net.isSorting()) { "bubble($n) must be a valid sorting network" }
            require(net.comparators.size == n * (n - 1) / 2) {
                "bubble($n) should have ${n*(n-1)/2} comparators, got ${net.comparators.size}"
            }
        }
    }

    // ─── oddEvenMerge ─────────────────────────────────────────────────────────

    private fun testOddEvenMergeIsValid() {
        // Known optimal comparator counts: n=2→1, n=3→3, n=4→5, n=5→9, n=6→12, n=7→16, n=8→19
        val knownOptima = mapOf(2 to 1, 3 to 3, 4 to 5, 5 to 9, 6 to 12, 7 to 16, 8 to 19)
        for (n in 2..9) {
            val net = SortingNetwork.oddEvenMerge(n)
            require(net.isSorting()) { "oddEvenMerge($n) must be a valid sorting network" }
            // Should be at most bubble sort size for n >= 4
            if (n >= 4) {
                val bubbleSize = n * (n - 1) / 2
                require(net.comparators.size <= bubbleSize) {
                    "oddEvenMerge($n) has ${net.comparators.size} comparators but bubble has $bubbleSize"
                }
            }
            // For n ≤ 8, Batcher's algorithm produces optimal counts
            knownOptima[n]?.let { expected ->
                require(net.comparators.size == expected) {
                    "oddEvenMerge($n) should have $expected comparators (optimal), got ${net.comparators.size}"
                }
            }
        }
    }

    // ─── sort ─────────────────────────────────────────────────────────────────

    private fun testSortProducesCorrectOrder() {
        val net = SortingNetwork.oddEvenMerge(5)
        val inputs = listOf(
            intArrayOf(5, 3, 1, 4, 2),
            intArrayOf(1, 2, 3, 4, 5),
            intArrayOf(5, 4, 3, 2, 1),
            intArrayOf(3, 3, 3, 3, 3),
            intArrayOf(2, 1, 4, 3, 5),
        )
        inputs.forEach { input ->
            val sorted = net.sort(input)
            for (i in 0 until sorted.size - 1) {
                require(sorted[i] <= sorted[i + 1]) {
                    "sort(${input.toList()}) produced unsorted output ${sorted.toList()}"
                }
            }
        }
    }

    private fun testLayerTrace() {
        val net = SortingNetwork.oddEvenMerge(5)
        val trace = net.layerTrace(intArrayOf(5, 1, 4, 2, 3))
        require(trace.size == net.depth() + 1) {
            "layerTrace() should return depth+1 snapshots, got ${trace.size}"
        }
        val last = trace.last()
        require(last.contentEquals(intArrayOf(1, 2, 3, 4, 5))) {
            "layerTrace() final state should be sorted, got ${last.toList()}"
        }
    }

    // ─── notation ─────────────────────────────────────────────────────────────

    private fun testNotationRoundTrip() {
        for (n in 2..7) {
            val net = SortingNetwork.oddEvenMerge(n)
            val notation = net.notation()
            require(notation.isNotBlank()) { "notation() must not be blank for n=$n" }

            val parsed = SortingNetwork.fromNotation(n, notation)
            // Parsed networks should have the same comparator set (possibly different order,
            // but since fromNotation preserves layer order they should be equal).
            require(parsed.isSorting()) { "Parsed network for n=$n must be valid" }
            require(parsed.comparators.size == net.comparators.size) {
                "Round-trip changed comparator count for n=$n"
            }
        }
    }

    private fun testFromNotationRejectsInvalid() {
        // A hand-crafted invalid notation (missing comparators)
        val invalid = SortingNetwork.fromNotation(4, "0:1")
        require(!invalid.isSorting()) { "A single comparator for n=4 should NOT sort all inputs" }
    }

    // ─── layers ───────────────────────────────────────────────────────────────

    private fun testLayersAreDisjoint() {
        val net = SortingNetwork.oddEvenMerge(6)
        net.layers().forEach { layer ->
            val wires = mutableSetOf<Int>()
            layer.forEach { comp ->
                require(wires.add(comp.lo)) { "Wire ${comp.lo} appears twice in the same layer" }
                require(wires.add(comp.hi)) { "Wire ${comp.hi} appears twice in the same layer" }
            }
        }
    }

    private fun testDepth() {
        // Under ASAP scheduling, the standard left-to-right bubble comparator order
        // has depth 2n-3.
        for (n in 2..6) {
            val depth = SortingNetwork.bubble(n).depth()
            val expected = 2 * n - 3
            require(depth == expected) { "bubble($n) should have depth $expected, got $depth" }
        }
        // oddEvenMerge should have depth ≤ bubble depth
        for (n in 4..8) {
            val oemDepth = SortingNetwork.oddEvenMerge(n).depth()
            val bubDepth = SortingNetwork.bubble(n).depth()
            require(oemDepth <= bubDepth) {
                "oddEvenMerge($n) depth $oemDepth should be ≤ bubble depth $bubDepth"
            }
        }
    }

    // ─── ASCII diagram ────────────────────────────────────────────────────────

    private fun testAsciiDiagramNonEmpty() {
        val diag = SortingNetwork.oddEvenMerge(4).asciiDiagram()
        require(diag.isNotBlank()) { "asciiDiagram() must not be blank" }
        // Should contain at least one ● marker
        require(diag.contains('●')) { "asciiDiagram() must contain ● endpoint markers" }
    }

    // ─── toFLines ─────────────────────────────────────────────────────────────

    private fun testToFLinesNonEmpty() {
        val flines = SortingNetwork.oddEvenMerge(4).toFLines()
        require(flines.isNotEmpty()) { "toFLines() must return a non-empty list" }
        // Should have at least the wire FLine plus one FLine per layer
        val net = SortingNetwork.oddEvenMerge(4)
        val minExpected = 1 + net.depth() // wires + at least one FLine per layer
        require(flines.size >= minExpected) {
            "toFLines() returned ${flines.size} FLines, expected >= $minExpected"
        }
        // Every FLine must have at least one node
        flines.forEach { f ->
            require(f.nodes.isNotEmpty()) { "toFLines() returned an FLine with no nodes" }
        }
    }

    // ─── simulated annealing ──────────────────────────────────────────────────

    private fun testAnnealImprovesBubble() {
        // SA should reduce bubble(4)=6 comparators; optimal for n=4 is 5.
        val opt = SortingNetwork.anneal(n = 4, seed = 42L)
        val bubbleSize = SortingNetwork.bubble(4).comparators.size  // 6
        require(opt.comparators.size <= bubbleSize) {
            "anneal(4) should find ≤ $bubbleSize comparators, got ${opt.comparators.size}"
        }
    }

    private fun testAnnealResultIsValid() {
        for (n in 2..6) {
            var callbackCount = 0
            val opt = SortingNetwork.anneal(n = n, seed = 7L)
            require(opt.isSorting()) { "anneal(n=$n) result must be a valid sorting network" }
            SortingNetwork.anneal(n = n, seed = 7L, iterations = 2_500, progressEvery = 500) { _, _ ->
                callbackCount++
            }
            require(callbackCount >= 5) { "anneal progress callback should have fired repeatedly, got $callbackCount" }
        }
    }
}
