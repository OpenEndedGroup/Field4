package afield.examples.stdlib

import afield.stdlib.WFC_EAST
import afield.stdlib.WFC_MOORE_NEIGHBORHOOD
import afield.stdlib.WFC_SOUTH
import afield.stdlib.WFC_WEST
import afield.stdlib.WfcNeighborhood
import afield.stdlib.WfcRules
import afield.stdlib.WfcSolver
import afield.stdlib.WfcStatus
import afield.stdlib.sliceIntoOverlappingPatterns2D
import afield.stdlib.solveWithRetries
import afield.stdlib.wfcRules

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
            testLearnedRulesStaySymmetric()
            testHandAuthoredRules()
            testMissingMasksWidenSymmetrically()
            testSolveWithRetriesAndPrepare()
            testSolvedOutputSatisfiesItsRules()
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

    /**
     * Regression test for the mask-widening bug.
     *
     * `fromSampleGrid` widens any `(direction, tile)` mask it never observed to "any tile". A 3x1
     * sample leaves most masks unobserved — nothing is ever north or south of anything, tile 0 never
     * has a west neighbour, tile 2 never has an east one — so this exercises the widening heavily.
     *
     * Widening one direction only produces arcs propagation structurally cannot check, and the solver
     * will then emit adjacent pairs its own rules forbid when read the other way round. The fix
     * widens both directions at once; this asserts the invariant that the fix restores.
     */
    private fun testLearnedRulesStaySymmetric() {
        val rules = WfcRules.fromSampleGrid(
            sampleTileIds = intArrayOf(0, 1, 2),
            sampleWidth = 3,
            sampleHeight = 1,
            tileCount = 3
        )
        val asymmetry = rules.findAsymmetry()
        require(asymmetry == null) { "learned rules are not symmetric: $asymmetry" }

        // The observed constraint must survive the widening rather than being erased by it.
        require(rules.allows(WFC_EAST, 0, 1)) { "east-of-0 should still admit 1" }
        require(rules.allows(WFC_WEST, 1, 0)) { "west-of-1 should still admit 0" }
    }

    /** The hand-authored builder: weights, symmetric allow, and the two query helpers. */
    private fun testHandAuthoredRules() {
        val rules = wfcRules(tileCount = 3) {
            weights(3.0, 1.0, 3.0)
            allow(WFC_EAST, 0, 1)
            allow(WFC_SOUTH, 0, 1)
        }
        require(rules.tileCount == 3)
        require(rules.weights.toList() == listOf(3.0, 1.0, 3.0)) { "weights not applied: ${rules.weights.toList()}" }
        require(rules.findAsymmetry() == null) { "builder produced asymmetric rules" }

        // allow(d, a, b) is directional: it says b may sit east of a, and a may sit west of b.
        require(rules.allows(WFC_EAST, 0, 1)) { "1 should be allowed east of 0" }
        require(rules.allows(WFC_WEST, 1, 0)) { "0 should be allowed west of 1" }
        require(!rules.allows(WFC_EAST, 1, 0)) { "allow should not have added 0 east of 1" }

        // Hand-authored rules are NOT widened: an unmentioned mask stays empty.
        require(rules.allowedCount(WFC_EAST, 2) == 0) { "tile 2 should have no legal east neighbour" }
        require(rules.allowedCount(WFC_EAST, 0) == 1) { "tile 0 should have exactly one legal east neighbour" }
    }

    /** `allowMissingAnything()` must widen without breaking symmetry — the same trap as above. */
    private fun testMissingMasksWidenSymmetrically() {
        val strict = wfcRules(tileCount = 3) {
            allow(WFC_EAST, 0, 1)
            allow(WFC_EAST, 1, 2)
            allow(WFC_SOUTH, 0, 1)
            allow(WFC_SOUTH, 1, 2)
        }
        // Longest fillable row is three cells, so a 6x6 grid cannot be filled at all.
        val stuck = WfcSolver(6, 6, strict, seed = 1L)
        val stuckOutcome = stuck.solveWithRetries(maxAttempts = 5)
        require(!stuckOutcome.solved) { "over-constrained rules unexpectedly solved" }
        require(stuckOutcome.attempts == 5) { "expected all 5 attempts, got ${stuckOutcome.attempts}" }
        require(stuck.status == WfcStatus.Contradiction) { "expected Contradiction, got ${stuck.status}" }

        val relaxed = wfcRules(tileCount = 3) {
            allow(WFC_EAST, 0, 1)
            allow(WFC_EAST, 1, 2)
            allow(WFC_SOUTH, 0, 1)
            allow(WFC_SOUTH, 1, 2)
            allowMissingAnything()
        }
        require(relaxed.findAsymmetry() == null) { "allowMissingAnything() broke symmetry" }
        // Asymmetric widening made this unsolvable in 16 attempts; symmetric widening solves it.
        val ok = WfcSolver(6, 6, relaxed, seed = 1L).solveWithRetries()
        require(ok.solved) { "widened rules did not solve: ${ok.status} after ${ok.attempts} attempts" }
    }

    /** `prepare` must re-apply boundary conditions that `reset()` wipes on every attempt. */
    private fun testSolveWithRetriesAndPrepare() {
        val rules = wfcRules(tileCount = 3) {
            weights(3.0, 1.0, 3.0)
            for (d in listOf(WFC_EAST, WFC_SOUTH)) {
                allow(d, 0, 0); allow(d, 0, 1); allow(d, 1, 0)
                allow(d, 1, 1); allow(d, 1, 2); allow(d, 2, 1)
                allow(d, 2, 2)
            }
        }
        val solver = WfcSolver(12, 8, rules, seed = 4L)
        val outcome = solver.solveWithRetries {
            for (row in 0 until 8) {
                forceTile(0, row, 0)
                forceTile(11, row, 2)
            }
        }
        require(outcome.solved) { "pinned solve failed: ${outcome.status}" }
        val grid = solver.resolvedTileGridOrNull()!!
        for (row in 0 until 8) {
            require(grid[row * 12] == 0) { "left edge not pinned at row $row" }
            require(grid[11 + row * 12] == 2) { "right edge not pinned at row $row" }
        }
        // Both phases present means the interface tile really had to appear.
        require(grid.any { it == 1 }) { "no interface tile in a grid pinned to both phases" }

        // Reproducibility: same seed and same call sequence, same output.
        val again = WfcSolver(12, 8, rules, seed = 4L)
        again.solveWithRetries {
            for (row in 0 until 8) {
                forceTile(0, row, 0)
                forceTile(11, row, 2)
            }
        }
        require(again.resolvedTileGridOrNull()!!.contentEquals(grid)) { "same seed produced different output" }
    }

    /**
     * The invariant the whole solver exists to maintain: every adjacent pair in a solved grid is a
     * pair the rules allow, checked over the rules' own neighborhood.
     */
    private fun testSolvedOutputSatisfiesItsRules() {
        val sample = intArrayOf(
            0, 0, 1, 2,
            0, 1, 2, 2,
            1, 2, 2, 3,
            2, 2, 3, 3
        )
        for (neighborhood in listOf(WfcNeighborhood.VonNeumann, WFC_MOORE_NEIGHBORHOOD)) {
            val rules = WfcRules.fromSampleGrid(sample, 4, 4, tileCount = 4, neighborhood = neighborhood)
            require(rules.findAsymmetry() == null) { "learned rules asymmetric for $neighborhood" }
            val w = 14
            val h = 10
            val solver = WfcSolver(w, h, rules, seed = 99L)
            val outcome = solver.solveWithRetries(maxAttempts = 30)
            require(outcome.solved) { "$neighborhood did not solve in ${outcome.attempts} attempts" }
            val grid = solver.resolvedTileGridOrNull()!!
            for (y in 0 until h) for (x in 0 until w) {
                val a = grid[x + y * w]
                for (d in 0 until rules.neighborhood.directionCount) {
                    val off = rules.neighborhood.offset(d)
                    val nx = x + off.dx
                    val ny = y + off.dy
                    if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                    val b = grid[nx + ny * w]
                    require(rules.allows(d, a, b)) {
                        "$neighborhood: tile $a at ($x, $y) has disallowed neighbour $b in direction $d"
                    }
                }
            }
        }
    }
}
