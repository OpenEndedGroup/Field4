package afield.examples.stdlib

import afield.Colormaps
import afield.stdlib.WFC_ALLOWED_COLOR
import afield.stdlib.WFC_CONTRADICTION_COLOR
import afield.stdlib.WFC_DISALLOWED_COLOR
import afield.stdlib.WFC_EAST
import afield.stdlib.WFC_MOORE_NEIGHBORHOOD
import afield.stdlib.WFC_SOUTH
import afield.stdlib.WFC_UNDECIDED_COLOR
import afield.stdlib.WFC_UNDEFINED_COLOR
import afield.stdlib.WFC_WEST
import afield.stdlib.WfcNeighborhood
import afield.stdlib.WfcRules
import afield.stdlib.WfcSolver
import afield.stdlib.WfcStatus
import afield.stdlib.entropyGrid
import afield.stdlib.entropyHeatmap
import afield.stdlib.fullSetEntropy
import afield.stdlib.possibilityCountHeatmap
import afield.stdlib.sliceIntoOverlappingPatterns2D
import afield.stdlib.solveWithRetries
import afield.stdlib.wfcRules
import afield.stdlib.wfcRulesHtml
import afield.stdlib.wfcSolverHtml
import afield.stdlib.weightsImg
import afield.stdlib.wfcTileColor
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.ln

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
            testInlineViewMatchesItsCaption()
            testContradictionReadsAsTheMarkerColor()
            testUndefinedEntropyIsMarkedNotClamped()
            testDegenerateDomainIsDrawnFlat()
            testRulesViewMarksTheThreeWaysRulesBreak()
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

    // ==========================================================================================
    // The inline view (WfcSolver's ToHTML), verified by decoding the PNGs it actually emits.
    //
    // "It compiles" says nothing about a picture. Every assertion below pulls the base64 out of the
    // emitted HTML, decodes it, and reads the pixel for a named cell — so what is checked is what a
    // human would see, and the caption's claim about the colour scale is checked against the pixels
    // rather than trusted.
    // ==========================================================================================

    /** Fully permissive rules over [tileCount] tiles: any tile may sit next to any other. */
    private fun permissiveRules(tileCount: Int) = wfcRules(tileCount) {
        for (a in 0 until tileCount) for (b in 0 until tileCount) {
            allow(WFC_EAST, a, b)
            allow(WFC_SOUTH, a, b)
        }
    }

    private val base64Png = Regex("data:image/png;base64,([A-Za-z0-9+/=]+)")

    /** Every inline image in [html], decoded — index 0 is possibilities, 1 entropy, 2 decided tiles. */
    private fun decodePanels(html: String): List<BufferedImage> =
        base64Png.findAll(html).map { m ->
            val bytes = Base64.getDecoder().decode(m.groupValues[1])
            ImageIO.read(ByteArrayInputStream(bytes))
                ?: error("emitted data URI is not a decodable image (${bytes.size} bytes)")
        }.toList()

    private fun rgbAt(img: BufferedImage, x: Int, y: Int): Int = img.getRGB(x, y) and 0xffffff

    private fun hex(rgb: Int) = "#%06x".format(rgb)

    /** Channel-wise comparison with a tolerance, so a rounding step is not a test failure. */
    private fun near(actual: Int, expected: Int, tol: Int = 2): Boolean {
        for (shift in intArrayOf(16, 8, 0)) {
            val a = (actual shr shift) and 0xff
            val e = (expected shr shift) and 0xff
            if (kotlin.math.abs(a - e) > tol) return false
        }
        return true
    }

    /** Colormap [name] at [t], packed the way the panels quantise it. */
    private fun ramp(name: String, t: Double): Int {
        val c = Colormaps.sample(name, t)
        fun q(v: Double) = (v.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt().coerceIn(0, 255)
        return (q(c.r) shl 16) or (q(c.g) shl 8) or q(c.b)
    }

    private fun requireColor(img: BufferedImage, x: Int, y: Int, expected: Int, what: String) {
        val actual = rgbAt(img, x, y)
        require(near(actual, expected)) {
            "$what: cell ($x, $y) read ${hex(actual)}, expected ${hex(expected)}"
        }
    }

    /**
     * The headline claim: in the picture a box actually shows, a decided cell reads as the *decided*
     * end of both scales and an untouched cell reads as the *untouched* end — and the panels are one
     * pixel per solver cell, so cell coordinates and pixel coordinates are the same thing.
     */
    private fun testInlineViewMatchesItsCaption() {
        val rules = permissiveRules(4)
        val solver = WfcSolver(8, 6, rules, seed = 7L)

        // Before anything is decided every cell is at the open end of both scales.
        val fresh = decodePanels(solver.toHTML())
        require(fresh.size == 3) { "expected 3 panels, got ${fresh.size}" }
        for (panel in fresh) {
            require(panel.width == 8 && panel.height == 6) {
                "panels must be one pixel per cell; got ${panel.width}x${panel.height} for 8x6"
            }
        }
        requireColor(fresh[0], 3, 2, ramp("viridis", 1.0), "untouched cell in the possibilities panel")
        requireColor(fresh[1], 3, 2, ramp("magma", 1.0), "untouched cell in the entropy panel")
        requireColor(fresh[2], 3, 2, WFC_UNDECIDED_COLOR, "undecided cell in the decided-tiles panel")
        require("0/48 cells decided (0.0%)" in solver.toHTML()) {
            "fresh solver did not report 0% decided: ${solver.toHTML().take(400)}"
        }

        // Decide exactly one cell. Rules are fully permissive, so nothing else moves.
        require(solver.forceTile(0, 0, 2)) { "forceTile hit a contradiction under permissive rules" }
        val html = solver.toHTML()
        val panels = decodePanels(html)
        requireColor(panels[0], 0, 0, ramp("viridis", 0.0), "decided cell in the possibilities panel")
        requireColor(panels[1], 0, 0, ramp("magma", 0.0), "decided cell in the entropy panel")
        requireColor(panels[2], 0, 0, wfcTileColor(2), "decided cell in the decided-tiles panel")
        requireColor(panels[0], 7, 5, ramp("viridis", 1.0), "still-untouched cell, possibilities panel")
        requireColor(panels[1], 7, 5, ramp("magma", 1.0), "still-untouched cell, entropy panel")

        // A decided cell must not read as the *bright* end of either scale, which is what an
        // inverted or auto-ranged scale would do.
        require(!near(rgbAt(panels[0], 0, 0), ramp("viridis", 1.0), tol = 24)) {
            "decided cell is indistinguishable from an untouched one in the possibilities panel"
        }

        // The caption states the scale, and the numbers in it are the numbers that were used.
        val count = solver.possibilityCountHeatmap()
        require(count.lo == 1f && count.hi == 4f) { "count domain should be 1..4, got ${count.lo}..${count.hi}" }
        for (phrase in listOf(
            "tiles still possible in that cell", "viridis 1 (decided)", "4 (untouched)",
            "weighted Shannon entropy in nats", "a stable hue per id",
            "1/48 cells decided (2.1%)", "47 untouched"
        )) require(phrase in html) { "the inline view no longer says \"$phrase\"" }

        // ToHTML and the free function are the same view, so the docs page and a live box agree.
        require((solver as afield.ToHTML).toHTML() == wfcSolverHtml(solver)) {
            "WfcSolver.toHTML() diverged from wfcSolverHtml()"
        }

        // Uniform weights over four tiles: entropy of an untouched cell is ln 4.
        require(kotlin.math.abs(rules.fullSetEntropy() - ln(4.0)) < 1e-9) {
            "fullSetEntropy should be ln 4 for four uniform tiles, got ${rules.fullSetEntropy()}"
        }
        val entropy = solver.entropyGrid()
        require(kotlin.math.abs(entropy[0]) < 1e-6) { "decided cell entropy should be 0, got ${entropy[0]}" }
        require(kotlin.math.abs(entropy[8 * 5 + 7] - ln(4.0).toFloat()) < 1e-5) {
            "untouched cell entropy should be ln 4, got ${entropy[8 * 5 + 7]}"
        }
    }

    /**
     * A contradiction has to be *visible*. A cell with no possibilities left sits at 0 on a
     * `1..tileCount` scale, so a naive clamp paints it at the "decided" end and the one failure mode
     * worth seeing reads as success. It must read as the marker colour instead.
     */
    private fun testContradictionReadsAsTheMarkerColor() {
        // Tile 0 may only ever sit beside tile 0, tile 1 only beside tile 1.
        val rules = wfcRules(tileCount = 2) {
            allow(WFC_EAST, 0, 0)
            allow(WFC_EAST, 1, 1)
        }
        val solver = WfcSolver(2, 1, rules, seed = 1L)
        require(solver.forceTile(0, 0, 0))
        require(solver.resolvedTileAt(1, 0) == 0) { "east neighbour should have been forced to 0" }
        require(!solver.forceTile(1, 0, 1)) { "forcing an illegal neighbour should have contradicted" }
        require(solver.status == WfcStatus.Contradiction) { "expected Contradiction, got ${solver.status}" }
        require(solver.possibilityCountAt(0, 0) == 0) { "the contradicted cell should have no possibilities" }

        val count = solver.possibilityCountHeatmap()
        require(count.nonFiniteCount == 1) { "one cell should be off the count scale, got ${count.nonFiniteCount}" }
        require(count.lo == 1f) { "the count domain must stay fixed at 1..N, got lo=${count.lo}" }

        val html = solver.toHTML()
        val panels = decodePanels(html)
        requireColor(panels[0], 0, 0, WFC_CONTRADICTION_COLOR, "contradicted cell, possibilities panel")
        requireColor(panels[1], 0, 0, WFC_UNDEFINED_COLOR, "contradicted cell, entropy panel")
        requireColor(panels[2], 0, 0, WFC_CONTRADICTION_COLOR, "contradicted cell, decided-tiles panel")
        require(!near(rgbAt(panels[0], 0, 0), ramp("viridis", 0.0), tol = 24)) {
            "the contradicted cell was painted at the decided end of the ramp"
        }
        // The surviving cell is decided, and reads as itself.
        requireColor(panels[2], 1, 0, wfcTileColor(1), "the surviving decided cell")
        for (phrase in listOf("1 contradicted", "a contradiction", "Contradiction")) {
            require(phrase in html) { "the inline view no longer says \"$phrase\"" }
        }
    }

    /**
     * `fromSampleGrid` gives weight 0 to any tile the sample never contained, so a cell whose only
     * survivor is such a tile has *undefined* entropy. NaN must be excluded from the range and
     * painted distinctly — not silently clamped onto the "decided" end, which is where
     * `(NaN - lo) / span` lands after a `coerceIn`.
     */
    private fun testUndefinedEntropyIsMarkedNotClamped() {
        val rules = WfcRules.fromSampleGrid(
            sampleTileIds = intArrayOf(0, 1, 0, 1),
            sampleWidth = 4,
            sampleHeight = 1,
            tileCount = 3,
            wrapX = true
        )
        require(rules.weights[2] == 0.0) { "tile 2 never appears, so its weight should be 0" }
        // Four tiles' worth of weight split 2/2/0 -> ln 4 - ln 2 = ln 2.
        require(kotlin.math.abs(rules.fullSetEntropy() - ln(2.0)) < 1e-9) {
            "fullSetEntropy should be ln 2 here, got ${rules.fullSetEntropy()}"
        }

        val solver = WfcSolver(4, 2, rules, seed = 3L)
        require(solver.forceTile(0, 0, 2)) { "pinning the zero-weight tile should not contradict" }
        require(solver.possibilityCountAt(0, 0) == 1) { "the pinned cell should have exactly one possibility" }

        val grid = solver.entropyGrid()
        require(grid[0].isNaN()) { "entropy of a zero-weight-only cell should be NaN, got ${grid[0]}" }
        val heat = solver.entropyHeatmap()
        require(heat.nonFiniteCount == 1) { "expected 1 non-finite cell, got ${heat.nonFiniteCount}" }
        require(!heat.flat && kotlin.math.abs(heat.hi - ln(2.0).toFloat()) < 1e-5) {
            "the entropy domain should be 0..ln 2, got ${heat.lo}..${heat.hi}"
        }

        val html = solver.toHTML()
        val panels = decodePanels(html)
        requireColor(panels[1], 0, 0, WFC_UNDEFINED_COLOR, "undefined-entropy cell")
        require(!near(rgbAt(panels[1], 0, 0), ramp("magma", 0.0), tol = 24)) {
            "NaN entropy was clamped onto the decided end of the ramp"
        }
        // The count panel still shows it as decided, because one possibility really is one.
        requireColor(panels[0], 0, 0, ramp("viridis", 0.0), "count panel for the pinned cell")
        require("undefined" in html && "excluded from the range" in html) {
            "the caption does not admit to the undefined cell"
        }
    }

    /**
     * A degenerate domain — here a one-tile rule set, where "1 possibility" is simultaneously the
     * floor and the ceiling — must be declared flat and drawn mid-ramp, not divided by zero.
     */
    private fun testDegenerateDomainIsDrawnFlat() {
        val rules = permissiveRules(1)
        val solver = WfcSolver(3, 3, rules, seed = 2L)
        val count = solver.possibilityCountHeatmap()
        require(count.flat) { "a 1..1 domain should be flat" }
        require(count.finiteCount == 9 && count.nonFiniteCount == 0) {
            "all nine cells are on the scale: ${count.finiteCount}/${count.nonFiniteCount}"
        }
        val entropyHeat = solver.entropyHeatmap()
        require(entropyHeat.flat && entropyHeat.nonFiniteCount == 0) {
            "a single uniform tile gives entropy 0 everywhere: $entropyHeat"
        }

        val html = solver.toHTML()
        val panels = decodePanels(html)
        val mid = ramp("viridis", 128 / 255.0)
        requireColor(panels[0], 1, 1, mid, "flat domain should be drawn mid-ramp")
        require(!near(rgbAt(panels[0], 1, 1), ramp("viridis", 0.0), tol = 24)) {
            "a flat domain was painted at an end of the ramp"
        }
        require("the range is flat" in html) { "the caption does not declare the flat range" }
    }

    /**
     * `WfcRules`' own view exists for three failures that are invisible in a `toString()` and each
     * cost an afternoon: an empty mask, an asymmetry, and a zero weight. Each has to be marked in the
     * pixels, not merely mentioned in prose.
     */
    private fun testRulesViewMarksTheThreeWaysRulesBreak() {
        // A strict 0 -> 1 -> 2 chain. Hand-authored rules are not widened, so "nothing may sit east
        // of tile 2" stands: allowed[EAST][2] is empty.
        val strict = wfcRules(tileCount = 3) {
            weights(3.0, 1.0, 2.0)
            allow(WFC_EAST, 0, 1)
            allow(WFC_EAST, 1, 2)
            allow(WFC_SOUTH, 0, 1)
            allow(WFC_SOUTH, 1, 2)
        }
        require(strict.allowedCount(WFC_EAST, 2) == 0) { "tile 2 should have no legal east neighbour" }

        val html = strict.toHTML()
        require(html == wfcRulesHtml(strict)) { "WfcRules.toHTML() diverged from wfcRulesHtml()" }
        val panels = decodePanels(html)
        // Four von Neumann directions plus the weights strip.
        require(panels.size == 5) { "expected 4 direction matrices + 1 weights strip, got ${panels.size}" }
        for (d in 0 until 4) {
            require(panels[d].width == 3 && panels[d].height == 3) {
                "direction $d matrix should be 3x3, got ${panels[d].width}x${panels[d].height}"
            }
        }
        val east = panels[WFC_EAST]
        // Row a, column b. The one allowed pair in EAST from tile 0 is tile 1.
        requireColor(east, 1, 0, WFC_ALLOWED_COLOR, "allowed pair (east of 0 is 1)")
        requireColor(east, 0, 0, WFC_DISALLOWED_COLOR, "disallowed pair (east of 0 is not 0)")
        requireColor(east, 2, 0, WFC_DISALLOWED_COLOR, "disallowed pair (east of 0 is not 2)")
        // Tile 2's whole row is an empty mask, and reads as the contradiction marker rather than as
        // three ordinary "not allowed" cells — the difference between strict and unsatisfiable.
        for (b in 0 until 3) {
            requireColor(east, b, 2, WFC_CONTRADICTION_COLOR, "empty-mask row cell ($b, 2)")
        }
        require("empty mask" in html && "symmetric" in html) {
            "the rules view does not report the empty mask and the symmetry check"
        }
        // 4 authored pairs x 2 (symmetric) = 8 of 3*3*4 = 36 -> 22.2%.
        require("22.2% of pairs allowed" in html) { "density line wrong: ${html.take(300)}" }

        // Weights: three positive weights, so the strip is a ramp with nothing off scale.
        val weights = strict.weightsImg()
        require(weights.nonFiniteCount == 0 && weights.hi == 3f) { "weights strip: $weights" }
        requireColor(panels[4], 0, 0, ramp("viridis", 1.0), "the heaviest tile in the weights strip")

        // A learned model gives weight 0 to a tile its sample never contained. That tile can never be
        // chosen, which is categorically different from "rare", so it comes off the ramp.
        val learned = WfcRules.fromSampleGrid(
            sampleTileIds = intArrayOf(0, 1, 0, 1),
            sampleWidth = 4,
            sampleHeight = 1,
            tileCount = 3,
            wrapX = true
        )
        val learnedHtml = learned.toHTML()
        val learnedPanels = decodePanels(learnedHtml)
        require(learnedPanels.size == 5) { "expected 5 panels, got ${learnedPanels.size}" }
        requireColor(learnedPanels[4], 2, 0, WFC_CONTRADICTION_COLOR, "the zero-weight tile")
        require(!near(rgbAt(learnedPanels[4], 2, 0), ramp("viridis", 0.0), tol = 24)) {
            "a zero weight was drawn at the bottom of the ramp instead of off it"
        }
        require("weight 0" in learnedHtml && "can never be chosen" in learnedHtml) {
            "the rules view does not admit to the zero-weight tile"
        }
        require("symmetric" in learnedHtml && "asymmetric" !in learnedHtml) {
            "learned rules should report as symmetric"
        }
    }
}
