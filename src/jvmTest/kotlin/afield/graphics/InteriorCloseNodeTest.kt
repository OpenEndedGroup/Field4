package afield.graphics

import afield.stdlib.Vec3
import afield.stdlib.arrow
import afield.stdlib.vec
import field.graphics.FLinesAndJavaShapes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The *interior* half of the `FLine.Close` landmine field.
 *
 * [ClosedLineNodeAccessTest] covers a `Close` as the **last** node — the `nodes.last().to` pattern,
 * fixed in 0954ebfb by routing those reads through [FLine.currentPoint]. This covers a `Close` with
 * more geometry **after** it, which is the `nodes[i - 1].to` pattern inside a loop, and which that
 * pass explicitly left alone.
 *
 * The corpus is deliberately not synthetic-only: [doubleHeadedArrow] is what
 * `afield.stdlib.arrow(startHead = true, endHead = true)` returns today
 * (StandardDrawing.kt:279 -> `appendHead` :309-322, which ends each head with `target.close()`),
 * so an interior `Close` falls out of a shipped drawing primitive with no user cleverness at all.
 *
 * Note what is **not** a source: AWT. `FLinesAndJavaShapes.javaShapeToFLine` turns `SEG_CLOSE` into
 * a `lineTo` back to the last `moveTo` (:2269-2273), as does `javaShapeToFLineFlat` (:2205-2209),
 * so text and glyph outlines arriving from `java.awt.Shape` carry no `Close` node whatsoever.
 * [awtNeverEmitsACloseNode] pins that, because it is the fact that bounds this whole blast radius.
 */
object InteriorCloseCurves {

    /**
     * `arrow(..., startHead = true, endHead = true)`, exactly as the stdlib builds it:
     * shaft, then a closed head at each end. Nodes:
     * `MoveTo LineTo | MoveTo LineTo LineTo Close | MoveTo LineTo LineTo Close`.
     */
    fun doubleHeadedArrow(): FLine =
        arrow(vec(0.0, 0.0), vec(300.0, 120.0), startHead = true, endHead = true)

    /** two closed contours, the way a glyph with a counter (`o`, `8`, `A`) is drawn. */
    fun twoContourGlyph(): FLine = FLine()
        .moveTo(0f, 0f)
        .cubicTo(Vec3(0f, 180f, 0f), Vec3(60f, 260f, 0f), Vec3(140f, 260f, 0f))
        .cubicTo(Vec3(220f, 260f, 0f), Vec3(280f, 180f, 0f), Vec3(280f, 0f, 0f))
        .lineTo(0f, 0f)
        .close()
        .moveTo(70f, 60f)
        .cubicTo(Vec3(70f, 150f, 0f), Vec3(100f, 190f, 0f), Vec3(140f, 190f, 0f))
        .cubicTo(Vec3(180f, 190f, 0f), Vec3(210f, 150f, 0f), Vec3(210f, 60f, 0f))
        .lineTo(70f, 60f)
        .close()

    /** a closed contour and then an *open* tail, so the node right after the Close is a LineTo. */
    fun closedThenOpenTail(): FLine = FLine()
        .moveTo(0f, 0f)
        .lineTo(100f, 0f)
        .lineTo(100f, 100f)
        .lineTo(0f, 100f)
        .close()
        .moveTo(160f, 20f)
        .lineTo(240f, 60f)
        .lineTo(300f, 20f)

    /** the same, but every segment is a cubic, so the CubicTo branches are exercised too. */
    fun closedThenCubicTail(): FLine = FLine()
        .moveTo(0f, 0f)
        .cubicTo(Vec3(60f, 120f, 0f), Vec3(180f, 120f, 0f), Vec3(240f, 0f, 0f))
        .cubicTo(Vec3(180f, -120f, 0f), Vec3(60f, -120f, 0f), Vec3(0f, 0f, 0f))
        .close()
        .moveTo(300f, 0f)
        .cubicTo(Vec3(340f, 90f, 0f), Vec3(420f, 90f, 0f), Vec3(460f, 0f, 0f))

    /** a Close with nothing before it at all: `nodes[0]` is the Close. */
    fun leadingClose(): FLine = FLine()
        .close()
        .moveTo(10f, 10f)
        .lineTo(90f, 40f)

    /** two Closes in a row, so `nodes[i - 1]` is itself a Close for the second one. */
    fun repeatedCloses(): FLine = FLine()
        .moveTo(0f, 0f)
        .lineTo(80f, 0f)
        .lineTo(40f, 70f)
        .close()
        .close()
        .moveTo(120f, 0f)
        .lineTo(200f, 70f)

    /**
     * A `close()` and then *drawing on* without a `moveTo` first. `close()` leaves the pen at the
     * start of the subpath it closed, so this is a legal thing to write — and it is the only shape
     * that puts a `Close` directly at `nodes[i - 1]` for a `LineTo`, which is where several of
     * these functions read.
     */
    fun closeThenLineTo(): FLine = FLine()
        .moveTo(0f, 0f)
        .lineTo(80f, 0f)
        .lineTo(40f, 70f)
        .close()
        .lineTo(200f, 40f)
        .lineTo(260f, 120f)

    /** the same, with a cubic after the Close — the CubicTo half of the same read. */
    fun closeThenCubicTo(): FLine = FLine()
        .moveTo(0f, 0f)
        .cubicTo(Vec3(40f, 120f, 0f), Vec3(140f, 120f, 0f), Vec3(180f, 0f, 0f))
        .close()
        .cubicTo(Vec3(60f, -80f, 0f), Vec3(200f, -80f, 0f), Vec3(260f, 20f, 0f))
        .cubicTo(Vec3(300f, 100f, 0f), Vec3(360f, 100f, 0f), Vec3(400f, 20f, 0f))

    /**
     * No leading `moveTo` at all, so `nodes[0]` is itself a drawing instruction. `FLine().lineTo()`
     * is legal and the renderer treats a leading `LineTo` as a `moveTo` (someSkiko.kt:332-337), but
     * `intersect`/`intersectX`/`intersectY` looped from 0 and read `nodes.get(-1)` on it.
     */
    fun noLeadingMoveTo(): FLine = FLine()
        .lineTo(0f, 0f)
        .lineTo(120f, 90f)
        .lineTo(240f, 0f)

    /** three-dimensional, because several of these functions are 3-D and z must not be dropped. */
    fun closedThenTailInZ(): FLine = FLine()
        .moveTo(0f, 0f, 0f)
        .lineTo(100f, 0f, 40f)
        .lineTo(50f, 90f, 80f)
        .close()
        .moveTo(150f, 0f, 20f)
        .cubicTo(Vec3(180f, 60f, 40f), Vec3(220f, 60f, 60f), Vec3(250f, 0f, 80f))

    fun all(): List<Pair<String, FLine>> = listOf(
        "doubleHeadedArrow" to doubleHeadedArrow(),
        "twoContourGlyph" to twoContourGlyph(),
        "closedThenOpenTail" to closedThenOpenTail(),
        "closedThenCubicTail" to closedThenCubicTail(),
        "leadingClose" to leadingClose(),
        "repeatedCloses" to repeatedCloses(),
        "closeThenLineTo" to closeThenLineTo(),
        "closeThenCubicTo" to closeThenCubicTo(),
        "noLeadingMoveTo" to noLeadingMoveTo(),
        "closedThenTailInZ" to closedThenTailInZ()
    )

    /** the subset that is a well-formed drawing: no leading Close, no repeated Close. */
    fun wellFormed(): List<Pair<String, FLine>> =
        all().filter { it.first != "leadingClose" && it.first != "repeatedCloses" }

    /**
     * [wellFormed] minus the two curves that [FLine.byReversing] documents as normalising: a
     * subpath with no leading `moveTo` gains one, and a `Close` that is not the last instruction of
     * its subpath comes back as an explicit straight segment. Both keep the geometry exactly (see
     * `reversingIsAGeometricInvolution`); neither keeps the instruction *sequence*.
     */
    fun properDrawings(): List<Pair<String, FLine>> = wellFormed().filter { (_, line) ->
        line.nodes.firstOrNull() is FLine.MoveTo &&
            line.nodes.withIndex().none { (i, n) ->
                n is FLine.Close && i + 1 < line.nodes.size && line.nodes[i + 1] !is FLine.MoveTo
            }
    }

    /**
     * The public geometry surface, as callable units.
     *
     * Anything that reads a node's end point belongs here. Where a function needs a second
     * argument it gets a fixed one — the point is whether it *survives* an interior `Close`, not
     * what it computes.
     */
    fun operations(): List<Pair<String, (FLine) -> Any?>> = listOf(
        "bySimplifying" to { l: FLine -> l.bySimplifying(2f) },
        "byConvexHull2D" to { l: FLine -> l.byConvexHull2D() },
        "byGaussianSmoothing" to { l: FLine -> l.byGaussianSmoothing(2f) },
        "findSelfIntersections" to { l: FLine -> l.findSelfIntersections() },
        "windingNumber" to { l: FLine -> l.windingNumber(afield.stdlib.vec(50f, 50f)) },
        "contains" to { l: FLine -> l.contains(afield.stdlib.vec(50f, 50f)) },
        "signedArea2D" to { l: FLine -> l.signedArea2D() },
        "centroid2D" to { l: FLine -> l.centroid2D() },
        "byReversing" to { l: FLine -> l.byReversing() },
        "bySubdividing" to { l: FLine -> l.bySubdividing(1) },
        "byDashing" to { l: FLine -> l.byDashing(20f, 10f) },
        "byArcLengthParameterizing" to { l: FLine -> l.byArcLengthParameterizing(15f) },
        "sampleByDistance" to { l: FLine -> l.sampleByDistance(15f) },
        "byFiltering" to { l: FLine -> l.byFiltering { _, _ -> true } },
        "fast2DBounds" to { l: FLine -> l.fast2DBounds() },
        "midPoint" to { l: FLine -> l.midPoint() },
        "cursorLength" to { l: FLine -> l.cursor().lengthD() },
        "closestT" to { l: FLine -> FLinesAndJavaShapes.closestT(l, vec(50f, 110f, 0f)) },
        "intersectSelf" to { l: FLine -> FLinesAndJavaShapes.intersect(l, l) },
        "intersectX" to { l: FLine -> FLinesAndJavaShapes.intersectX(l, 50f) },
        "intersectY" to { l: FLine -> FLinesAndJavaShapes.intersectY(l, 50f) },
        "tcbTo" to { l: FLine -> l.copy().tcbTo(vec(400f, 200f, 0f)) },
        "smoothTo" to { l: FLine -> l.copy().smoothTo(vec(400f, 200f, 0f)) },
        "biarcTo" to { l: FLine -> l.copy().biarcTo(vec(400f, 200f, 0f)) },
        "extremalInnerPoints" to { l: FLine -> l.cursor().extremalInnerPoints() },
        "inflectionPointsXY" to { l: FLine -> l.cursor().inflectionPointsXY() },
        "flineToJavaShape" to { l: FLine -> FLinesAndJavaShapes.flineToJavaShape_notThickened(l) },
        "byUnionWith" to { l: FLine -> l.byUnionWith(l.copy()) },
        "drawToSVG" to { l: FLine -> drawToSVG(listOf(l), 200.0, 200.0) }
    )
}

class InteriorCloseNodeTest {

    /**
     * The audit, run rather than asserted by inspection: every operation against every curve, with
     * the throwables collected. This is the test that was red first; the per-site tests below say
     * what each fix was *for*.
     */
    @Test
    fun noGeometryOperationThrowsOnAnInteriorClose() {
        val failures = mutableListOf<String>()
        for ((opName, op) in InteriorCloseCurves.operations()) {
            for ((curveName, curve) in InteriorCloseCurves.all()) {
                val thrown = runCatching { op(curve.copy()) }.exceptionOrNull() ?: continue
                failures += "$opName($curveName): ${thrown::class.simpleName}: ${thrown.message}"
            }
        }
        failures.forEach { println("FAIL $it") }
        assertTrue(
            failures.isEmpty(),
            "${failures.size} operation/curve pairs throw on an interior Close:\n" +
                failures.joinToString("\n")
        )
    }

    /**
     * The claim that bounds the blast radius: AWT shapes never carry a `Close` node, so no glyph
     * outline that arrives through `javaShapeToFLine` is on this path at all. Tested at four
     * shapes, including a two-contour one (a ring is the AWT analogue of a glyph with a counter).
     */
    @Test
    fun awtNeverEmitsACloseNode() {
        val shapes = listOf<Pair<String, java.awt.Shape>>(
            "rect" to java.awt.geom.Rectangle2D.Double(0.0, 0.0, 100.0, 60.0),
            "ellipse" to java.awt.geom.Ellipse2D.Double(0.0, 0.0, 100.0, 60.0),
            "roundRect" to java.awt.geom.RoundRectangle2D.Double(0.0, 0.0, 100.0, 60.0, 12.0, 12.0),
            "ringTwoContours" to java.awt.geom.Area(java.awt.geom.Ellipse2D.Double(0.0, 0.0, 100.0, 100.0))
                .also { it.subtract(java.awt.geom.Area(java.awt.geom.Ellipse2D.Double(25.0, 25.0, 50.0, 50.0))) }
        )
        for ((name, shape) in shapes) {
            val line = FLinesAndJavaShapes.javaShapeToFLine(shape)
            assertTrue(line.nodes.isNotEmpty(), "$name converted to nothing")
            assertTrue(
                line.nodes.none { it is FLine.Close },
                "$name produced a Close node, which javaShapeToFLine is not supposed to emit " +
                    "(FLinesAndJavaShapes.kt:2269-2273 turns SEG_CLOSE into a lineTo)"
            )
        }
        // and the flattening variant, same rule (:2205-2209)
        val flat = FLinesAndJavaShapes.javaShapeToFLineFlat(
            java.awt.geom.Ellipse2D.Double(0.0, 0.0, 100.0, 60.0), 0.5f, 8
        )
        assertTrue(flat.nodes.none { it is FLine.Close }, "javaShapeToFLineFlat produced a Close")
    }

    /** the fixture really is what the shipped primitive produces, interior `Close` and all. */
    @Test
    fun theStdlibArrowReallyDoesEmitAnInteriorClose() {
        val a = InteriorCloseCurves.doubleHeadedArrow()
        val closeAt = a.nodes.indexOfFirst { it is FLine.Close }
        assertTrue(closeAt >= 0, "arrow(startHead, endHead) emitted no Close at all: ${a.nodes}")
        assertTrue(
            closeAt < a.nodes.size - 1,
            "the Close is the last node, so this is not an *interior* Close: ${a.nodes}"
        )
        assertEquals(2, a.nodes.count { it is FLine.Close }, "expected one Close per arrowhead")
    }

    // ---------------------------------------------------------------------------------------
    // Per-site tests. Each names the function and the read that used to throw.
    // ---------------------------------------------------------------------------------------

    /** FLineGeometry.kt:674 — `CubicSegment3(this.nodes[i - 1].to, ...)` */
    @Test
    fun findSelfIntersectionsSurvivesAnInteriorClose() {
        val found = InteriorCloseCurves.closedThenCubicTail().findSelfIntersections()
        assertTrue(found.all { it.x.isFinite() }, "non-finite intersection in $found")
    }

    /** FLineGeometry.kt:1301 — `result.lineTo(prevNode.to)` and friends. */
    @Test
    fun byReversingSurvivesAnInteriorClose() {
        val line = InteriorCloseCurves.closedThenOpenTail()
        val reversed = line.byReversing()
        // a reversal keeps the instruction count and the node multiset of end points
        assertEquals(line.nodes.size, reversed.nodes.size, "byReversing changed the node count")
        assertEquals(
            line.nodes.count { it is FLine.Close },
            reversed.nodes.count { it is FLine.Close },
            "byReversing lost a Close"
        )
        // reversal begins where the forward line ended: the open tail's last point
        assertEquals(vec(300f, 20f, 0f), reversed.nodes[0].to)
    }

    /**
     * The structural claim behind the [byReversing] rewrite, tested on every well-formed curve in
     * the corpus rather than at one point: reversing twice is the identity. The old backwards walk
     * could not manage this on anything closed — it emitted the `close()` where the *first*
     * instruction of the reversed subpath belongs, so the contour came apart and the second
     * reversal had different geometry to work with.
     */
    @Test
    fun reversingTwiceIsTheIdentity() {
        val curves = InteriorCloseCurves.properDrawings()
        assertTrue(curves.size >= 3, "only ${curves.size} proper drawings in the corpus")
        for ((name, line) in curves) {
            val back = line.byReversing().byReversing()
            assertEquals(
                line.nodes.map { it::class.simpleName },
                back.nodes.map { it::class.simpleName },
                "$name: reversing twice changed the instruction sequence"
            )
            for (i in line.nodes.indices) {
                val a = line.endPointOf(i)
                val b = back.endPointOf(i)
                assertTrue(
                    a.distance(b) < 1e-3f,
                    "$name node $i: reversing twice moved the end point from $a to $b"
                )
            }
        }
    }

    /**
     * The weaker claim that holds for every well-formed curve including the two that
     * [FLine.byReversing] normalises: the reversal draws the same picture. Measured as the same set
     * of visited points and the same total drawn length.
     *
     * A *multi*set would be the wrong invariant and it is worth saying why, because I tried it
     * first: the point a `Close` resolves to is the start of its subpath, and reversal moves the
     * subpath's start to the other end, so a closed triangle's three corners come out with a
     * different one of them doubled. The picture is the same; the bookkeeping is not.
     */
    @Test
    fun reversingIsAGeometricInvolution() {
        fun signature(l: FLine) = l.nodes.indices
            .map { l.endPointOf(it) }
            .map { "%.3f,%.3f,%.3f".format(it.x, it.y, it.z) }
            .distinct()
            .sorted()

        for ((name, line) in InteriorCloseCurves.wellFormed()) {
            val reversed = line.byReversing()
            assertEquals(signature(line), signature(reversed), "$name: reversal moved a point")
            val a = line.cursor().lengthD()
            val b = reversed.cursor().lengthD()
            assertTrue(
                a == 0f && b == 0f || kotlin.math.abs(a - b) / kotlin.math.max(a, b) < 0.02f,
                "$name: reversal changed the drawn length from $a to $b"
            )
        }
    }

    /**
     * The normalisation itself, stated rather than left implicit: a `Close` in the middle of a
     * subpath cannot survive a reversal as a `Close`, because in the reversed traversal the pen is
     * not at the subpath start when it reaches that segment.
     */
    @Test
    fun anInteriorCloseReversesIntoAStraightSegment() {
        val line = InteriorCloseCurves.closeThenLineTo()
        val reversed = line.byReversing()
        assertEquals(line.nodes.size, reversed.nodes.size, "node count should still be preserved")
        assertEquals(1, line.nodes.count { it is FLine.Close })
        assertEquals(0, reversed.nodes.count { it is FLine.Close }, "the Close became a segment")
        // and the geometry is untouched: reversing again gets the same points back
        assertEquals(
            line.nodes.indices.map { line.endPointOf(it) }.reversed().drop(1),
            reversed.nodes.indices.map { reversed.endPointOf(it) }.drop(1)
        )
    }

    /** and a closed contour must still be closed after one reversal, at every curve that has one. */
    @Test
    fun reversingKeepsEveryContourClosed() {
        val closedCurves = InteriorCloseCurves.properDrawings().filter { (_, l) -> l.nodes.any { it is FLine.Close } }
        assertTrue(closedCurves.size >= 3, "only ${closedCurves.size} closed curves in the corpus")
        for ((name, line) in closedCurves) {
            val reversed = line.byReversing()
            assertEquals(
                line.nodes.count { it is FLine.Close },
                reversed.nodes.count { it is FLine.Close },
                "$name lost a Close under reversal"
            )
            assertEquals(
                line.nodes.count { it is FLine.MoveTo },
                reversed.nodes.count { it is FLine.MoveTo },
                "$name changed subpath count under reversal"
            )
            assertEquals(line.nodes.size, reversed.nodes.size, "$name changed node count under reversal")
        }
    }

    /** FLineGeometry.kt:2130 — `val prev = result.nodes[i - 1].to`, plus a dropped Close. */
    @Test
    fun bySubdividingSurvivesAnInteriorCloseAndKeepsIt() {
        val line = InteriorCloseCurves.twoContourGlyph()
        val subdivided = line.bySubdividing(1)
        assertEquals(
            line.nodes.count { it is FLine.Close },
            subdivided.nodes.count { it is FLine.Close },
            "bySubdividing silently dropped a Close, so the contours are no longer closed"
        )
        assertEquals(
            line.nodes.count { it is FLine.MoveTo },
            subdivided.nodes.count { it is FLine.MoveTo },
            "bySubdividing changed the subpath count"
        )
    }

    /** FLineGeometry.kt:2831 — `val prevPrev = prevNode.to` with a Close two back. */
    @Test
    fun tcbToSurvivesACloseTwoNodesBack() {
        // MoveTo LineTo LineTo Close MoveTo LineTo  -> tcbTo sees last = LineTo, prev = MoveTo,
        // but on `repeatedCloses` the node two back from the end is the Close itself.
        val line = FLine().moveTo(0f, 0f).lineTo(80f, 0f).lineTo(40f, 70f).close().lineTo(200f, 40f)
        val out = line.tcbTo(vec(300f, 90f, 0f))
        assertTrue(out.nodes.size > 5, "tcbTo appended nothing")
        assertTrue(out.currentPoint!!.distance(vec(300f, 90f, 0f)) < 1e-3f)
    }

    /** FLineGeometry.kt:1110 — `windingNumber` read `node.to` with no Close branch at all. */
    @Test
    fun windingNumberAndContainsSurviveAClose() {
        // its two siblings signedArea2D (:1161) and centroid2D (:1212) always skipped a Close;
        // windingNumber did not, and `contains` is nothing but a call to it.
        val glyph = InteriorCloseCurves.twoContourGlyph()
        assertTrue(glyph.contains(afield.stdlib.vec(140f, 30f)), "point in the outer contour")
        // and on a plain trailing Close too, which is the ordinary point-in-polygon case
        val square = FLine().moveTo(0f, 0f).lineTo(100f, 0f).lineTo(100f, 100f).lineTo(0f, 100f).close()
        assertTrue(square.contains(afield.stdlib.vec(50f, 50f)), "point inside a closed square")
        assertTrue(!square.contains(afield.stdlib.vec(500f, 50f)), "point outside a closed square")
    }

    /** FLineGeometry.kt:1192 — centroid2D's *degenerate* branch had no Close guard. */
    @Test
    fun centroid2DSurvivesACloseInItsDegenerateBranch() {
        // zero signed area sends centroid2D down the "average of the points" branch
        val degenerate = FLine().moveTo(10f, 10f).lineTo(90f, 10f).lineTo(10f, 10f).close()
        val c = degenerate.centroid2D()
        assertTrue(c.x.isFinite() && c.y.isFinite(), "centroid2D returned $c")
    }

    /** FLineGeometry.kt:590 / :831 — whole-line reads with no Close branch. */
    @Test
    fun bySimplifyingAndByConvexHullSurviveAClose() {
        for ((name, line) in InteriorCloseCurves.all()) {
            assertTrue(line.bySimplifying(2f).nodes.size >= 0, "$name bySimplifying")
            assertTrue(line.byConvexHull2D().nodes.size >= 0, "$name byConvexHull2D")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Blast radius. Most of the functions above are called only from FLineGeometryExamples, the
    // docs gallery, on open lines. These are the ones with a real caller that reaches them with a
    // closed line, so these are the ones where the bug was doing damage rather than waiting.
    // ---------------------------------------------------------------------------------------

    /**
     * `byJavaOffsetting` (FLineThickening.kt:154) strokes through
     * `flineToJavaShape_notThickened`, which dropped the `Close`. So AWT was handed an *open*
     * three-sided path and stroked three sides: thickening a square closed with `close()` came back
     * missing its fourth edge entirely. Measured as area, because "missing an edge" is exactly the
     * kind of claim that deserves a number.
     */
    @Test
    fun thickeningAClosedSquareKeepsAllFourSides() {
        val square = FLine().moveTo(0f, 0f).lineTo(200f, 0f).lineTo(200f, 200f).lineTo(0f, 200f).close()
        val thick = square.byJavaOffsetting(20f)
        val area = java.awt.geom.Area(FLinesAndJavaShapes.flineToJavaShape_notThickened(thick))

        // the stroked outline of a closed 200x200 square at width 20 is a 20-unit-wide band all the
        // way round: roughly 4 * 200 * 20 = 16000 plus the corners. Three sides would be ~12000.
        val bounds = area.bounds2D
        assertTrue(bounds.width > 215 && bounds.height > 215, "stroked bounds $bounds are not the full square")

        // the decisive measure: the band has a hole in the middle. Three sides do not enclose one.
        assertTrue(
            !area.isSingular,
            "the thickened outline is a single region, so it is a U rather than a closed band — " +
                "the fourth side was dropped"
        )
        // and the hole is where the square's interior is
        assertTrue(!area.contains(100.0, 100.0), "the band should not cover the square's middle")
        assertTrue(area.contains(100.0, 0.0), "the band should cover the bottom edge")
        assertTrue(area.contains(0.0, 100.0), "the band should cover the LEFT edge, the one that went missing")
        println("thickened closed square: bounds=$bounds singular=${area.isSingular}")
    }

    /**
     * `FLine.byThickening` (FLine.kt:412) is the *second* public API with the same defect, and the
     * more prominent one — it is a member of `FLine` itself rather than an extension in a
     * thickening file. It goes through `flineToJavaShape` (FLinesAndJavaShapes.kt:1920), which
     * strokes `_flineToJavaShape`'s output, so it lost the same edge for the same reason.
     *
     * Recorded here because the blast-radius answer for both is the same and slightly surprising:
     * **neither has a single caller in the repo**, `src/personal` included. The defect is a real
     * one on a real public API, and it shipped for nine months — `_flineToJavaShape` has had no
     * `Close` branch since 52a1d563e (2025-11-03), the first commit of this build layout — without
     * anything in the tree exercising it.
     */
    @Test
    fun byThickeningHasTheSameDefectAndTheSameFix() {
        val square = FLine().moveTo(0f, 0f).lineTo(200f, 0f).lineTo(200f, 200f).lineTo(0f, 200f).close()
        val area = java.awt.geom.Area(FLinesAndJavaShapes.flineToJavaShape_notThickened(square.byThickening(20f)))
        assertTrue(!area.isSingular, "byThickening produced a U rather than a closed band")
        assertTrue(area.contains(0.0, 100.0), "the left edge is missing from byThickening's output")

        // and the same square closed the long way must thicken identically — the invariant a reader
        // would expect, and the one the old code did not have
        val longWay = FLine().moveTo(0f, 0f).lineTo(200f, 0f).lineTo(200f, 200f).lineTo(0f, 200f).lineTo(0f, 0f)
        val longArea = java.awt.geom.Area(FLinesAndJavaShapes.flineToJavaShape_notThickened(longWay.byThickening(20f)))
        val difference = java.awt.geom.Area(area).also { it.exclusiveOr(longArea) }
        assertTrue(
            difference.isEmpty,
            "`close()` and an explicit lineTo back should thicken identically; they differ by " +
                difference.bounds2D
        )
    }

    /**
     * The same converter feeds the four boolean ops (FLineGeometry.kt:1006-1042) via
     * `flineToJavaShape`. A union of two `close()`-terminated squares was a union of two open
     * paths, and `java.awt.geom.Area` fills an open path by implicitly closing it — so the result
     * happened to survive, which is why nobody noticed. Pinned at three points so it stays that way
     * now that the Close is real.
     */
    @Test
    fun booleanOpsOnClosedSquaresAgreeWithTheirOwnGeometry() {
        fun square(x: Float) = FLine().moveTo(x, 0f).lineTo(x + 100f, 0f)
            .lineTo(x + 100f, 100f).lineTo(x, 100f).close()

        val union = square(0f).byUnionWith(square(50f))
        val inter = square(0f).byIntersectionWith(square(50f))
        val diff = square(0f).byDifferenceWith(square(50f))

        fun area(l: FLine): Double {
            val a = java.awt.geom.Area(FLinesAndJavaShapes.flineToJavaShape_notThickened(l))
            val b = a.bounds2D
            return b.width * b.height
        }
        // union spans x in [0, 150], intersection [50, 100], difference [0, 50]; all 100 tall
        assertTrue(kotlin.math.abs(area(union) - 15000.0) < 500.0, "union area ${area(union)}")
        assertTrue(kotlin.math.abs(area(inter) - 5000.0) < 500.0, "intersection area ${area(inter)}")
        assertTrue(kotlin.math.abs(area(diff) - 5000.0) < 500.0, "difference area ${area(diff)}")
    }

    /**
     * `Dyn4jScene.flattenClosedFLineLoops` (Dyn4jScene.kt:239) is the one caller whose *name* says
     * it needs the Close, and it is the interesting negative result: it reads `SEG_CLOSE` off the
     * PathIterator, so the dropped Close meant it never saw one — but its final
     * `flush(requireClosed = false)` (:307) accepted the subpath anyway, and a dyn4j polygon is
     * implicitly closed, so it got the same four vertices either way. A landmine that turns out not
     * to be armed at this call site, which is worth writing down rather than fixing decoratively.
     */
    @Test
    fun theDyn4jLoopFlattenerWasNeverActuallyBroken() {
        val square = FLine().moveTo(0f, 0f).lineTo(200f, 0f).lineTo(200f, 200f).lineTo(0f, 200f).close()
        val loops = afield.four.physics.Dyn4jScene.flattenClosedFLineLoops(square)
        assertEquals(1, loops.size, "expected one loop, got ${loops.size}")
        assertEquals(4, loops[0].size, "expected four vertices, got ${loops[0].size}")
    }

    // ---------------------------------------------------------------------------------------
    // The declared population. A sweep is only worth anything if the next person who adds a
    // node-type branch has to declare themselves too.
    // ---------------------------------------------------------------------------------------

    /**
     * Every source file that branches on `FLine.MoveTo` / `LineTo` / `CubicTo` must also mention
     * `FLine.Close` — or be listed here with a reason. A `when` over three of the four node types
     * either throws on the fourth or silently drops it, and both of those are bugs this branch
     * spent the night on.
     *
     * The exemptions are the whole point of the table: each one is a claim someone had to write
     * down, and a new file cannot join the list by accident.
     */
    @Test
    fun everyFileThatBranchesOnNodeTypeHasConsideredClose() {
        val exempt = mapOf(
            "FLineSampling.kt" to
                "isPointSetLike (:338) is a predicate, not a read — and pointLikeNodes (:341-344) " +
                    "filters `storage.isNotEmpty()` before `.to`, which is `toOrNull` by another name. " +
                    "That filter is load-bearing: `moveTo(a).moveTo(b).close()` is point-set-like.",
            "PolynomialTreeFittingSmokeTest.kt" to
                "lengthOf (:363-364) is the classic nodes[i-1].to loop, but its only inputs come from " +
                    "PolynomialTreeFitting.toLeafFLines, whose emitters (PolynomialTreeFitting.kt:61-62, " +
                    ":1533-1540, :1546-1550) call only moveTo/lineTo. `close()` appears nowhere in that file.",
            "FLineInstructionTrajectoryTrackerSmokeTest.kt" to
                "reads .last().to / .first().to on FLineSideways.trajectories(), whose tracks are built " +
                    "only with moveTo/lineTo at FLineInstructionTrajectories.kt:263-281.",
            "ArcLengthCostTest.kt" to
                "a cost benchmark over locally-built cubic corpora; it constructs every line it measures " +
                    "and none of them calls close().",
        )

        val root = repoRoot()
        // Repository source only. `src/personal` is the human's gitignored sketch tree: it is absent
        // from a worktree and present on the real checkout, so walking it makes this guard pass here
        // and fail there. That is exactly how 2026-07-28's `checkKDocAttachment` shipped green at 625
        // files and went red at 735 on merge (fixed the same way, in 639b4fa5). Concretely, today one
        // linearizer in that tree branches on MoveTo/LineTo with an `else -> Unit` and never names
        // Close — see the note in dev_docs/fline_close_nodes.md, which records it as a
        // deliberately-unfixed adjacent finding, not as something this guard owns. Described rather
        // than cited, because a path into a tree CI does not have is not a citation anybody can
        // follow; `PersonalBoundaryTest` makes that a rule.
        val sourceRoots = listOf("jvmMain", "jvmTest", "commonMain", "jsMain")
            .map { File(root, "src/$it") }
            .filter { it.isDirectory }
        val branchers = sourceRoots.asSequence()
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                val t = f.readText()
                t.contains("is FLine.LineTo") || t.contains("is FLine.CubicTo") || t.contains("is FLine.MoveTo")
            }
            .toList()

        assertTrue(branchers.size >= 20, "only ${branchers.size} files branch on node type — the scan is broken")

        val undeclared = branchers
            .filter { !it.readText().contains("FLine.Close") }
            .map { it.name }
            .filter { it !in exempt }

        assertTrue(
            undeclared.isEmpty(),
            "${undeclared.size} file(s) branch on FLine node type without a Close branch and without " +
                "an entry in this test's exemption table: ${undeclared.joinToString(", ")}. Either add " +
                "the `is FLine.Close ->` branch, or add the file here with a reason it cannot receive one."
        )

        // and a stale exemption is a lie too
        val stale = exempt.keys.filter { name -> branchers.none { it.name == name } }
        assertTrue(stale.isEmpty(), "exemptions for files that no longer branch on node type: $stale")
        val cured = exempt.keys.filter { name -> branchers.any { it.name == name && it.readText().contains("FLine.Close") } }
        assertTrue(cured.isEmpty(), "these files now handle Close, so drop their exemption: $cured")

        println("node-type branchers: ${branchers.size} files, ${exempt.size} declared exempt")
    }

    private fun repoRoot(): File {
        var d = File(System.getProperty("user.dir")).absoluteFile
        while (!File(d, "settings.gradle.kts").isFile) {
            d = d.parentFile ?: error("no repo root above ${System.getProperty("user.dir")}")
        }
        return d
    }

    /** FLine.kt:431/438 — `byFiltering` set `at = node.to` for every node. */
    @Test
    fun byFilteringSurvivesAnInteriorClose() {
        val line = InteriorCloseCurves.closedThenOpenTail()
        val kept = line.byFiltering { _, _ -> true }
        assertEquals(line.nodes.size, kept.nodes.size, "keep-everything changed the node count")
    }
}
