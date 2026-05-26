package afield.examples.stdlib

import afield.stdlib.WFC_MOORE_NEIGHBORHOOD
import afield.stdlib.WfcNeighborhood
import afield.stdlib.WfcRules
import afield.stdlib.WfcSolver
import afield.stdlib.WfcStatus
import afield.stdlib.sliceIntoOverlappingPatterns2D

/*
FIELD_TEST
id: stdlib.wfc.smoke
runner: jvm-main
entrypoint: afield.examples.stdlib.WfcSmokeTest
tags: [smoke, stdlib, wfc]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "WFC smoke tests passed"
*/
object WfcSmokeTest {

    fun runTests(): Boolean {
        return try {
            testMooreNeighborhoodPropagation()
            testOverlappingFloatPatterns()
            println("WFC smoke tests passed")
            true
        } catch (t: Throwable) {
            println("WFC smoke tests failed: ${t.message}")
            t.printStackTrace()
            false
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "WFC smoke tests failed" }
    }

    private fun testMooreNeighborhoodPropagation() {
        val sample = intArrayOf(
            0, 1,
            1, 0
        )
        val rules = WfcRules.fromSampleGrid(
            sampleTileIds = sample,
            sampleWidth = 2,
            sampleHeight = 2,
            tileCount = 2,
            wrapX = true,
            wrapY = true,
            neighborhood = WFC_MOORE_NEIGHBORHOOD
        )
        require(rules.neighborhood.directionCount == 8)

        val solver = WfcSolver(2, 2, rules, seed = 1L)
        require(solver.forceTile(0, 0, 0))
        require(solver.resolvedTileAt(1, 0) == 1) { "east neighbor should be forced by Moore rules" }
        require(solver.resolvedTileAt(0, 1) == 1) { "south neighbor should be forced by Moore rules" }
        require(solver.resolvedTileAt(1, 1) == 0) { "diagonal neighbor should be forced by Moore rules" }
    }

    private fun testOverlappingFloatPatterns() {
        val sample = floatArrayOf(0f, 1f, 0f, 1f)
        val patterns = sample.sliceIntoOverlappingPatterns2D(
            width = 4,
            height = 1,
            patternWidth = 2,
            patternHeight = 1,
            wrapX = true,
            wrapY = false
        )
        require(patterns.tileCount == 2) { "Expected two unique overlapping patterns" }

        val solver = WfcSolver(4, 1, patterns.toRules(WfcNeighborhood.VonNeumann), seed = 1L)
        require(solver.forceTile(0, 0, patterns.sampleIds[0]))
        require(solver.run() == WfcStatus.Solved)

        val rendered = patterns.render(solver.resolvedTileGridOrNull()!!, outWidth = 4, outHeight = 1)
        require(rendered.contentEquals(sample)) {
            "Expected overlapping render ${sample.contentToString()}, got ${rendered.contentToString()}"
        }
    }
}
