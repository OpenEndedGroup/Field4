package afield.graphics

import afield.examples.graphics.FLineGeometryExamples
import afield.stdlib.Vec3
import afield.stdlib.vec
import java.awt.geom.Area
import java.awt.geom.Path2D
import java.io.File
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent oracles for the two *semantic* changes on this branch —
 * [FLine.windingNumber] / [FLine.contains] and [FLine.byReversing].
 *
 * [InteriorCloseNodeTest] is demonstration-grade: its corpus and its expectations were written by
 * the same pass that wrote the fixes, so it can only show that the code does what I think it should.
 * These tests use judges that know nothing about my model:
 *
 *  - `java.awt.geom.Path2D.contains` is a completely separate implementation of the nonzero winding
 *    rule, written by someone else two decades ago. The AWT path here is built **directly from the
 *    node list in this file**, not through `FLinesAndJavaShapes.flineToJavaShape` — that converter
 *    is one of the things this branch changed, so routing through it would make the oracle a mirror.
 *  - `java.awt.geom.Area` decides whether a reversal encloses the same region, which does not depend
 *    on any opinion about where a reversed `close()` belongs.
 *  - arc-length resampling decides whether a reversal traces the same points backwards, which does
 *    not depend on the node structure at all.
 *
 * Both oracles were also run against the base tree (`7abe8736`); the counts are in the commit
 * message. The point of that is that the *old* code fails these, so passing them is information.
 */
class CloseNodeSemanticOracleTest {

    // =============================================================================================
    // The independent AWT path. Deliberately hand-built here.
    // =============================================================================================

    /**
     * `line` as a `java.awt.geom.Path2D`, built straight from the nodes.
     *
     * A `Close` becomes `closePath()`, which is what it means. A leading `LineTo`/`CubicTo` becomes
     * the initial `moveTo`, matching the renderer (someSkiko.kt:332-337). Nothing here calls into
     * `afield.graphics`, so a bug in the code under test cannot hide in the oracle.
     */
    private fun awtPathOf(line: FLine): Path2D.Double {
        val p = Path2D.Double(Path2D.WIND_NON_ZERO)
        var open = false
        for (n in line.nodes) {
            when (n) {
                is FLine.MoveTo -> {
                    p.moveTo(n.to.x.toDouble(), n.to.y.toDouble()); open = true
                }
                is FLine.LineTo ->
                    if (open) p.lineTo(n.to.x.toDouble(), n.to.y.toDouble())
                    else { p.moveTo(n.to.x.toDouble(), n.to.y.toDouble()); open = true }
                is FLine.CubicTo ->
                    if (open) p.curveTo(
                        n.c1.x.toDouble(), n.c1.y.toDouble(),
                        n.c2.x.toDouble(), n.c2.y.toDouble(),
                        n.to.x.toDouble(), n.to.y.toDouble()
                    )
                    else { p.moveTo(n.to.x.toDouble(), n.to.y.toDouble()); open = true }
                is FLine.Close -> if (open) p.closePath()
            }
        }
        return p
    }

    // =============================================================================================
    // A randomised corpus, built without reference to what the fix does.
    // =============================================================================================

    /**
     * Random straight-edged contours. Straight-edged on purpose: [FLine.windingNumber] counts a
     * cubic by its chord (its KDoc says so) while AWT integrates the real curve, so on cubics the
     * two are *supposed* to differ and the comparison would be meaningless. Everything the change
     * actually touched — subpath handling, the implicit closure, the `Close` — is exercised by
     * polylines.
     */
    private fun randomContours(seed: Long, count: Int): List<Pair<String, FLine>> {
        val rng = Random(seed)
        fun pt() = vec(rng.nextInt(200) - 100f, rng.nextInt(200) - 100f, 0f)

        return (0 until count).map { i ->
            val subpaths = 1 + rng.nextInt(3)
            val explicitClose = rng.nextBoolean()
            // "draw on after closing", which is what makes a Close interior
            val tailAfterClose = rng.nextBoolean()
            val line = FLine()
            repeat(subpaths) { s ->
                val n = 3 + rng.nextInt(5)
                val start = pt()
                line.moveTo(start)
                repeat(n) { line.lineTo(pt()) }
                if (explicitClose) line.close()
                else if (rng.nextBoolean()) line.lineTo(start) // closed the long way
                if (explicitClose && tailAfterClose && s == 0) {
                    repeat(2) { line.lineTo(pt()) }
                }
            }
            val closes = line.nodes.count { it is FLine.Close }
            "r$i(sub=$subpaths,closes=$closes)" to line
        }
    }

    /** Deliberately degenerate and self-intersecting shapes, where winding rules earn their keep. */
    private fun awkwardContours(): List<Pair<String, FLine>> = listOf(
        // a pentagram: self-intersecting, and the middle has winding 2
        "pentagram" to FLine().apply {
            val r = 80f
            val pts = (0 until 5).map { k ->
                val a = (-Math.PI / 2 + 2 * Math.PI * 2 * k / 5).toFloat()
                vec((r * kotlin.math.cos(a)), (r * kotlin.math.sin(a)), 0f)
            }
            moveTo(pts[0]); pts.drop(1).forEach { lineTo(it) }; close()
        },
        // a square with a square hole wound the other way: the hole must read as outside
        "squareWithHole" to FLine()
            .moveTo(-80f, -80f).lineTo(80f, -80f).lineTo(80f, 80f).lineTo(-80f, 80f).close()
            .moveTo(-40f, 40f).lineTo(40f, 40f).lineTo(40f, -40f).lineTo(-40f, -40f).close(),
        // the same hole wound the SAME way: winding 2 inside, so it is NOT a hole
        "squareWithSameWindingInset" to FLine()
            .moveTo(-80f, -80f).lineTo(80f, -80f).lineTo(80f, 80f).lineTo(-80f, 80f).close()
            .moveTo(-40f, -40f).lineTo(40f, -40f).lineTo(40f, 40f).lineTo(-40f, 40f).close(),
        // a closed contour with an open tail hanging off it
        "closedPlusTail" to FLine()
            .moveTo(-50f, -50f).lineTo(50f, -50f).lineTo(50f, 50f).lineTo(-50f, 50f).close()
            .moveTo(70f, 0f).lineTo(95f, 30f).lineTo(95f, -30f),
        // close() then keep drawing, with no moveTo: the interior Close
        "closeThenDrawOn" to FLine()
            .moveTo(-60f, -30f).lineTo(0f, 60f).lineTo(60f, -30f).close()
            .lineTo(90f, 60f).lineTo(20f, 90f),
        // a bowtie, the classic nonzero-vs-evenodd discriminator
        "bowtie" to FLine()
            .moveTo(-60f, -40f).lineTo(60f, 40f).lineTo(60f, -40f).lineTo(-60f, 40f).close(),
    )

    // =============================================================================================
    // Oracle 1 — contains, against java.awt.geom.Path2D
    // =============================================================================================

    /**
     * Every corpus contour, probed on a grid, [FLine.contains] against `Path2D.contains`.
     *
     * Points within [edgeSkin] of an edge are skipped: exactly on a boundary the answer is a
     * convention, not a fact, and disagreeing there would be noise rather than a defect. Everything
     * else must agree exactly.
     */
    @Test
    fun containsAgreesWithAwtOnEveryContour() {
        val corpus = awkwardContours() + randomContours(seed = 20260801L, count = 60)
        val edgeSkin = 0.75
        var compared = 0
        val disagreements = mutableListOf<String>()

        for ((name, line) in corpus) {
            val awt = awtPathOf(line)
            val bounds = awt.bounds2D
            if (bounds.width <= 0 || bounds.height <= 0) continue
            for (ix in 0..11) for (iy in 0..11) {
                val x = bounds.minX + bounds.width * (ix + 0.5) / 12.0
                val y = bounds.minY + bounds.height * (iy + 0.5) / 12.0
                // an inflated/deflated probe disagreeing means we are sitting on an edge
                val nearEdge = awt.contains(x - edgeSkin, y - edgeSkin, edgeSkin * 2, edgeSkin * 2) !=
                    awt.intersects(x - edgeSkin, y - edgeSkin, edgeSkin * 2, edgeSkin * 2)
                if (nearEdge) continue

                val theirs = awt.contains(x, y)
                val ours = line.contains(vec(x.toFloat(), y.toFloat()))
                compared++
                if (ours != theirs) {
                    disagreements += "$name at (%.1f, %.1f): FLine.contains=%s awt=%s"
                        .format(x, y, ours, theirs)
                }
            }
        }

        println("containsAgreesWithAwtOnEveryContour: $compared probes over ${corpus.size} contours, " +
            "${disagreements.size} disagreements")
        assertTrue(compared > 2000, "only $compared probes — the corpus is not exercising anything")
        assertTrue(
            disagreements.isEmpty(),
            "${disagreements.size} of $compared probes disagree with java.awt.geom.Path2D:\n" +
                disagreements.take(25).joinToString("\n")
        )
    }

    /**
     * The same judge, on a corpus with **no `Close` node anywhere** — every contour closed the long
     * way, with an explicit `lineTo` back to its `moveTo`.
     *
     * This is the test that isolates the *semantic* change from the crash fix, and it is the one
     * worth reading. On the corpus above the old code cannot even produce an answer to compare (it
     * throws on the first `Close`), so "0 disagreements" there only says the new code runs. Here the
     * old code runs perfectly happily and is simply **wrong**: it treated a `moveTo` as an ordinary
     * segment endpoint, so it counted a phantom edge from the end of one contour to the start of
     * the next.
     *
     * Measured against `7abe8736`: **100 of 1764 probes** disagreed with AWT. Now 0. The shape of
     * the error is worth seeing — it is not a near-miss at a boundary:
     *
     *     ringHole at (-137.1, -68.6): FLine.contains=true awt=false
     *
     * a point 57 units clear of a ring whose outer edge is at -80, reported as inside it, because
     * the phantom edge from the outer contour's last point to the inner contour's `moveTo` swept
     * past the test ray. `singleSquareUnclosed` — a `moveTo` and three `lineTo`s, no closing
     * instruction of any kind — was wrong too, for the other half of the change: a winding number
     * is only defined for a closed contour, and the old code never closed one it was not given.
     */
    @Test
    fun containsAgreesWithAwtOnCloseFreeMultiContourShapes() {
        fun ring(outer: Float, inner: Float, sameWinding: Boolean): FLine {
            val f = FLine()
            f.moveTo(-outer, -outer).lineTo(outer, -outer).lineTo(outer, outer)
                .lineTo(-outer, outer).lineTo(-outer, -outer)
            if (sameWinding) f.moveTo(-inner, -inner).lineTo(inner, -inner).lineTo(inner, inner)
                .lineTo(-inner, inner).lineTo(-inner, -inner)
            else f.moveTo(-inner, inner).lineTo(inner, inner).lineTo(inner, -inner)
                .lineTo(-inner, -inner).lineTo(-inner, inner)
            return f
        }
        val corpus = listOf(
            "ringHole" to ring(80f, 40f, sameWinding = false),
            "ringSameWinding" to ring(80f, 40f, sameWinding = true),
            "twoDisjointSquares" to FLine()
                .moveTo(-90f, -30f).lineTo(-30f, -30f).lineTo(-30f, 30f).lineTo(-90f, 30f).lineTo(-90f, -30f)
                .moveTo(30f, -30f).lineTo(90f, -30f).lineTo(90f, 30f).lineTo(30f, 30f).lineTo(30f, -30f),
            "threeStackedBars" to FLine().apply {
                for (k in 0..2) {
                    val y = -70f + k * 60f
                    moveTo(-80f, y).lineTo(80f, y).lineTo(80f, y + 30f).lineTo(-80f, y + 30f).lineTo(-80f, y)
                }
            },
        )

        var compared = 0
        val disagreements = mutableListOf<String>()
        for ((name, line) in corpus) {
            assertTrue(line.nodes.none { it is FLine.Close }, "$name has a Close; this corpus must not")
            val awt = awtPathOf(line)
            val raw = awt.bounds2D
            // INFLATED by 40%, and that margin is the whole point: the old code's error was a
            // phantom edge from the end of one subpath to the start of the next, which mostly
            // misreads points OUTSIDE the shape. A grid confined to `bounds2D` — which is what this
            // test did at first — probes 1722 points and finds nothing.
            val mx = raw.width * 0.4
            val my = raw.height * 0.4
            val b = java.awt.geom.Rectangle2D.Double(raw.minX - mx, raw.minY - my, raw.width + 2 * mx, raw.height + 2 * my)
            for (ix in 0..20) for (iy in 0..20) {
                val x = b.minX + b.width * (ix + 0.5) / 21.0
                val y = b.minY + b.height * (iy + 0.5) / 21.0
                val nearEdge = awt.contains(x - 0.75, y - 0.75, 1.5, 1.5) != awt.intersects(x - 0.75, y - 0.75, 1.5, 1.5)
                if (nearEdge) continue
                compared++
                val ours = line.contains(vec(x.toFloat(), y.toFloat()))
                if (ours != awt.contains(x, y)) {
                    disagreements += "$name at (%.1f, %.1f): FLine.contains=%s awt=%s".format(x, y, ours, awt.contains(x, y))
                }
            }
        }
        println("containsAgreesWithAwtOnCloseFreeMultiContourShapes: $compared probes, " +
            "${disagreements.size} disagreements")
        assertTrue(compared > 800, "only $compared probes")
        assertTrue(
            disagreements.isEmpty(),
            "${disagreements.size} of $compared probes disagree with java.awt.geom.Path2D on shapes " +
                "containing no Close node at all — this is the winding semantics, not the crash:\n" +
                disagreements.take(20).joinToString("\n")
        )
    }

    /**
     * The same judge on the specific thing the rewrite was for: a glyph counter must read as
     * outside. Stated separately because it is the claim in the doc, and because a grid sweep can
     * pass while getting the interesting region wrong if the grid happens to miss it.
     */
    @Test
    fun aCounterReadsAsAHole() {
        val ring = FLine()
            .moveTo(-80f, -80f).lineTo(80f, -80f).lineTo(80f, 80f).lineTo(-80f, 80f).close()
            .moveTo(-40f, 40f).lineTo(40f, 40f).lineTo(40f, -40f).lineTo(-40f, -40f).close()
        val awt = awtPathOf(ring)

        for (p in listOf(vec(0f, 0f), vec(20f, 20f), vec(-30f, 10f))) {
            assertTrue(!ring.contains(p), "point $p is in the counter and should read as outside")
            assertEquals(awt.contains(p.x.toDouble(), p.y.toDouble()), ring.contains(p), "at $p")
        }
        for (p in listOf(vec(60f, 0f), vec(0f, 60f), vec(-60f, -60f))) {
            assertTrue(ring.contains(p), "point $p is in the ring body and should read as inside")
            assertEquals(awt.contains(p.x.toDouble(), p.y.toDouble()), ring.contains(p), "at $p")
        }
    }

    // =============================================================================================
    // Oracle 2 — byReversing, against java.awt.geom.Area and against arc length
    // =============================================================================================

    /**
     * A reversal encloses the same region. `java.awt.geom.Area` normalises winding direction, so
     * this is true of a genuine reversal and false of anything that drops or misplaces a segment —
     * and it holds no opinion about where a reversed `close()` goes, which is the part of my model
     * that most needed an outside judge.
     */
    @Test
    fun aReversalEnclosesTheSameRegion() {
        val corpus = awkwardContours() + randomContours(seed = 424242L, count = 40) +
            InteriorCloseCurves.wellFormed()
        var checked = 0
        val wrong = mutableListOf<String>()

        for ((name, line) in corpus) {
            val before = Area(awtPathOf(line))
            val after = Area(awtPathOf(line.byReversing()))
            if (before.isEmpty && after.isEmpty) continue
            checked++

            val symmetricDifference = Area(before).also { it.exclusiveOr(after) }
            // area of the symmetric difference, via its bounds when non-empty
            if (!symmetricDifference.isEmpty) {
                val b = symmetricDifference.bounds2D
                val scale = maxOf(before.bounds2D.width * before.bounds2D.height, 1.0)
                val leak = b.width * b.height / scale
                if (leak > 0.001) wrong += "$name: symmetric difference bounds $b (${"%.3f".format(leak)} of the shape)"
            }
        }
        println("aReversalEnclosesTheSameRegion: $checked shapes, ${wrong.size} wrong")
        assertTrue(checked > 40, "only $checked shapes had any area at all")
        assertTrue(wrong.isEmpty(), "${wrong.size} reversals changed the enclosed region:\n" +
            wrong.take(15).joinToString("\n"))
    }

    /**
     * A reversal traces the same points, backwards. Measured per subpath by arc length, which is a
     * statement about the *drawing* and not about the node list — so unlike
     * `reversingTwiceIsTheIdentity` it cannot be satisfied by a self-consistent but wrong model.
     *
     * A **closed** subpath is compared cyclically, and that is not a loosening to make the test
     * pass — it is what the first version of this test taught me. Stated pointwise it failed on
     * eleven subpaths, every one of them closed and every one of them only at sample 0, by exactly
     * one segment: `squareWithHole subpath 0 at 0/10: forward (-80, -80), reversed (-80, 80),
     * 160.00 apart`, which is one side of a 160-unit square. A closed contour is a *cycle*, and the
     * reversal of a cycle is a cycle with no canonical starting point; `byReversing` starts it at
     * the last drawn point rather than at the `moveTo`. So the honest oracle asks whether the two
     * traverse the same cycle in opposite directions, which is the geometric claim, and
     * [theReversalOfAClosedContourStartsOneSegmentRoundTheCycle] pins the phase choice separately
     * so it cannot drift unnoticed. `aReversalEnclosesTheSameRegion` — which has no phase to be
     * confused by — agreed all along, at 54 shapes.
     */
    @Test
    fun aReversalTracesTheSamePointsBackwards() {
        val corpus = awkwardContours() + InteriorCloseCurves.properDrawings()
        var checked = 0
        var cyclic = 0
        val wrong = mutableListOf<String>()
        val samples = 60

        for ((name, line) in corpus) {
            val forward = line.pieces()
            // byReversing emits subpaths last-first, so subpath k pairs with reversed subpath n-1-k
            val backward = line.byReversing().pieces()
            if (forward.size != backward.size) {
                wrong += "$name: ${forward.size} subpaths forward, ${backward.size} back"
                continue
            }
            for (k in forward.indices) {
                val f = forward[k]
                val b = backward[backward.size - 1 - k]
                val lf = f.cursor().lengthD()
                val lb = b.cursor().lengthD()
                if (lf < 1e-3f) continue
                checked++
                if (kotlin.math.abs(lf - lb) / lf > 0.01f) {
                    wrong += "$name subpath $k: length $lf forward, $lb back"
                    continue
                }

                val closed = f.nodes.any { it is FLine.Close }
                if (closed) cyclic++
                val cf = f.cursor()
                val cb = b.cursor()
                val fwd = (0 until samples).map { cf.setD(lf * it / samples); Vec3(cf.position()) }
                // walked backwards, so it should line up with `fwd` once rotated
                val rev = (0 until samples).map { cb.setD(lb * it / samples); Vec3(cb.position()) }
                    .reversed()

                val rotations = if (closed) 0 until samples else 0..0
                var best = Float.MAX_VALUE
                var bestAt = -1
                for (r in rotations) {
                    val worst = (0 until samples).maxOf { fwd[it].distance(rev[(it + r) % samples]) }
                    if (worst < best) { best = worst; bestAt = r }
                }
                val tolerance = 0.02f * maxOf(lf, 1f)
                if (best > tolerance) {
                    wrong += "$name subpath $k: best alignment (rotation $bestAt of $samples) still " +
                        "%.2f off, tolerance %.2f".format(best, tolerance)
                }
            }
        }
        println("aReversalTracesTheSamePointsBackwards: $checked subpaths ($cyclic closed, compared " +
            "cyclically), ${wrong.size} wrong")
        assertTrue(checked >= 12, "only $checked subpaths were long enough to check")
        assertTrue(cyclic >= 5, "only $cyclic closed subpaths — the cyclic case is barely exercised")
        assertTrue(wrong.isEmpty(), "${wrong.size} subpaths do not retrace:\n" + wrong.take(15).joinToString("\n"))
    }

    /**
     * The phase choice made explicit, because the test above deliberately cannot see it.
     *
     * `byReversing` starts a reversed closed subpath at its **last drawn point**, not at the
     * `moveTo` it will close back to. That is a free choice — a cycle has no canonical start — and
     * it is the one that makes the operation a structural involution while keeping the `Close`:
     *
     *   `moveTo p0, S1..Sn, close`  ->  `moveTo pn, Sn'..S1', close`
     *
     * The alternative (start at `p0`, emit the closing segment as an explicit `lineTo pn`) keeps the
     * start point but produces a subpath with **no `Close` node at all** — closed only implicitly —
     * and so is not an involution: reversing it again gives a different instruction sequence. Since
     * the `Close` is what `Cursor.segmentIsClose`, `FLine.pieces` and
     * `Dyn4jScene.flattenClosedFLineLoops` (via `SEG_CLOSE`) all branch on, keeping it wins.
     */
    @Test
    fun theReversalOfAClosedContourStartsOneSegmentRoundTheCycle() {
        val square = FLine().moveTo(0f, 0f).lineTo(200f, 0f).lineTo(200f, 200f).lineTo(0f, 200f).close()
        val reversed = square.byReversing()

        // starts at the last drawn corner, p3, not at the moveTo
        assertEquals(vec(0f, 200f, 0f), reversed.endPointOf(0))
        // and is still a closed contour of the same shape
        assertTrue(reversed.nodes.last() is FLine.Close)
        assertEquals(square.nodes.size, reversed.nodes.size)
        // the phase offset is exactly one segment of the cycle
        val perimeter = square.cursor().lengthD()
        assertTrue(kotlin.math.abs(perimeter - reversed.cursor().lengthD()) < 1e-2f)
        assertTrue(
            kotlin.math.abs(square.endPointOf(0).distance(reversed.endPointOf(0)) - 200f) < 1e-2f,
            "expected the start to move by one 200-unit side"
        )
        // and the region is untouched, which is the part that actually matters.
        //
        // Via the symmetric difference, NOT `==`. `java.awt.geom.Area` declares
        // `public boolean equals(Area)` and never overrides `Object.equals(Object)`, so Java picks
        // the geometric overload statically while Kotlin's `==` falls through to identity — this
        // assertion passed as `before == after` only because it was comparing two distinct objects
        // and failing, which is how I noticed. Anything that reads `==` on an Area is wrong.
        val before = Area(awtPathOf(square))
        val after = Area(awtPathOf(reversed))
        val difference = Area(before).also { it.exclusiveOr(after) }
        assertTrue(difference.isEmpty, "reversal changed the enclosed region by ${difference.bounds2D}")
    }

    // =============================================================================================
    // Caller audit — every production caller of the two changed functions, shown rather than argued
    // =============================================================================================

    /**
     * A canonical dump of what every non-test caller of [FLine.byReversing], [FLine.windingNumber]
     * and [FLine.contains] in the repo actually produces. Compared against `caller_audit.txt`, which
     * was generated by running this same method with the graphics sources checked out at
     * `7abe8736` — so a passing test is a byte-for-byte statement that the semantic changes are
     * invisible to every one of them.
     *
     * The callers, enumerated:
     *  - `FLineGeometryExamples.pathOperationsExample()` (:846) — the docs gallery's reverse figure
     *  - `FLineGeometryExamples.geometricQueriesExample()` (:789-792) — contains/windingNumber, whose
     *    results are drawn as indicator line lengths, so the dump captures them
     *  - `FLineGeometryExamples.runAllExamples()`'s sweep (:1419-1427) — same inputs as those two
     *  - `public/docs/fline_examples.md:1958` and its box code in
     *    `public/docs/assets/fline_examples/metadata.edn:34`, which is a *rendering* path: it has a
     *    committed screenshot at `public/docs/assets/reverse_blend_extract.png`. Its input line is
     *    reproduced below exactly as the box writes it.
     */
    @Test
    fun everyProductionCallerProducesExactlyWhatItDidBefore() {
        val actual = callerAuditDump()

        val golden = File(repoRoot(), GOLDEN_REL)
        if (System.getenv("AFIELD_CALLER_AUDIT_WRITE") != null) {
            golden.parentFile.mkdirs()
            golden.writeText(actual)
            println("wrote ${golden.absolutePath} (${actual.lines().size} lines)")
            return
        }
        assertTrue(golden.isFile, "missing golden at ${golden.absolutePath}")
        val expected = golden.readText()
        if (expected != actual) {
            val e = expected.lines()
            val a = actual.lines()
            val firstDiff = (0 until maxOf(e.size, a.size)).first { e.getOrNull(it) != a.getOrNull(it) }
            throw AssertionError(
                "a production caller of byReversing/windingNumber/contains changed its output.\n" +
                    "first difference at line ${firstDiff + 1}:\n" +
                    "  golden (7abe8736): ${e.getOrNull(firstDiff)}\n" +
                    "  now:               ${a.getOrNull(firstDiff)}"
            )
        }
        println("caller audit: ${actual.lines().size} lines identical to 7abe8736")
    }

    private fun callerAuditDump(): String = buildString {
        fun emit(label: String, lines: List<FLine>) {
            appendLine("## $label")
            lines.forEachIndexed { i, l ->
                appendLine("  line $i: ${l.nodes.size} nodes")
                l.nodes.forEachIndexed { j, n ->
                    val pts = n.storage.joinToString(" ") { "%.4f,%.4f,%.4f".format(it.x, it.y, it.z) }
                    appendLine("    $j ${n::class.simpleName} $pts")
                }
            }
        }

        appendLine("# FLineGeometryExamples.pathOperationsExample  (FLineGeometryExamples.kt:846)")
        FLineGeometryExamples.pathOperationsExample().toSortedMap().forEach { (k, v) -> emit(k, v) }

        appendLine("# FLineGeometryExamples.geometricQueriesExample  (FLineGeometryExamples.kt:789-792)")
        FLineGeometryExamples.geometricQueriesExample().toSortedMap().forEach { (k, v) -> emit(k, v) }

        // The other half of the `_flineToJavaShape` fan-out. The four boolean ops
        // (FLineGeometry.kt:1007/1019/1031/1043) go through `flineToJavaShape`, which is the same
        // converter that used to drop the Close — so this is the "does anything on main render
        // differently now" question, answered by dumping it rather than by arguing about it.
        appendLine("# FLineGeometryExamples.booleanOperationsExample  (FLineGeometryExamples.kt:79)")
        FLineGeometryExamples.booleanOperationsExample().toSortedMap().forEach { (k, v) -> emit(k, v) }

        // public/docs/assets/fline_examples/metadata.edn:34 — the "Reverse blend extract" box,
        // whose rendered output is committed at public/docs/assets/reverse_blend_extract.png.
        appendLine("# docs box 'Reverse blend extract'  (fline_examples.md:1958)")
        val a = FLine().moveTo(60, 190).cubicTo(
            Vec3(120f, 20f, 0f), Vec3(260f, 280f, 0f), Vec3(360f, 120f, 0f)
        )
        emit("a", listOf(a))
        emit("b = a.byReversing()", listOf(a.byReversing()))
        emit("mid = a.byBlendingWith(b, 0.5)", listOf(a.byBlendingWith(a.byReversing(), t = 0.5f)))
        emit("snippet = a.byExtracting(0.8, 1.9)", listOf(a.byExtracting(tStart = 0.8f, tEnd = 1.9f)))

        // the same square those examples use, probed the way they probe it
        appendLine("# contains / windingNumber values the examples compute")
        val square = FLine().moveTo(0f, 0f).lineTo(60f, 0f).lineTo(60f, 60f).lineTo(0f, 60f).lineTo(0f, 0f)
        for (p in listOf(vec(20f, 20f), vec(80f, 20f), vec(25f, 25f))) {
            appendLine("  square.windingNumber($p) = ${square.windingNumber(p)}")
            appendLine("  square.contains($p) = ${square.contains(p)}")
        }
        appendLine("  square.signedArea2D() = ${"%.4f".format(square.signedArea2D())}")
        appendLine("  square.centroid2D() = ${square.centroid2D()}")
    }

    private fun repoRoot(): File {
        var d = File(System.getProperty("user.dir")).absoluteFile
        while (!File(d, "settings.gradle.kts").isFile) {
            d = d.parentFile ?: error("no repo root above ${System.getProperty("user.dir")}")
        }
        return d
    }

    private companion object {
        const val GOLDEN_REL = "src/jvmTest/resources/afield/graphics/close_node_caller_audit.txt"
    }
}
