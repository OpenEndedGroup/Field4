package afield.graphics

import afield.stdlib.Vec3
import afield.stdlib.vec
import field.graphics.FLinesAndJavaShapes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `FLine.Close` is `Node(0)` — it stores no points — while `Node.to` is `storage[0]`. So reading
 * `.to` on a `Close` throws `IndexOutOfBoundsException`, and *every* function that asks "where does
 * this line currently end?" with `nodes.last().to` blows up on a closed line. Which is to say: on
 * every closed shape and every glyph outline.
 *
 * `nodes.lastOrNull()?.to` is no safer — the `?.` guards a missing *node*, not an empty `storage`.
 *
 * Each test here names the public entry point and the `file:line` of the read.
 */
class ClosedLineNodeAccessTest {

    /** a closed square: MoveTo, LineTo, LineTo, LineTo, Close. */
    private fun closedSquare(): FLine = FLine()
        .moveTo(0f, 0f)
        .lineTo(100f, 0f)
        .lineTo(100f, 100f)
        .lineTo(0f, 100f)
        .close()

    @Test
    fun closeStoresNoPointAndSaysSo() {
        val close = FLine.Close()
        assertNull(close.toOrNull, "a Close has no point of its own")

        val thrown = runCatching { close.to }.exceptionOrNull()
        assertTrue(thrown is IndexOutOfBoundsException, "expected IndexOutOfBoundsException, got $thrown")
        // the bare "Index 0 out of bounds for length 0" tells the reader nothing about what to do
        assertTrue(
            (thrown.message ?: "").contains("Close", ignoreCase = true),
            "the message should name the problem, was: ${thrown.message}"
        )
    }

    @Test
    fun endPointOfResolvesACloseToItsSubpathStart() {
        val line = closedSquare()
        assertEquals(vec(0f, 0f, 0f), line.endPointOf(line.nodes.size - 1))
        assertEquals(vec(0f, 0f, 0f), line.currentPoint)
        // and is unremarkable everywhere else
        assertEquals(vec(100f, 100f, 0f), line.endPointOf(2))
    }

    @Test
    fun currentPointHandlesAnEmptyLine() {
        assertNull(FLine().currentPoint)
    }

    /** FLineGeometry.kt:1202 — `result.moveTo(this.nodes.last().to)` */
    @Test
    fun byReversingWorksOnAClosedLine() {
        val reversed = closedSquare().byReversing()
        assertTrue(reversed.nodes.isNotEmpty(), "reversing a closed square produced nothing")
        // reversal starts where the forward line ended, i.e. back at the moveTo
        assertEquals(vec(0f, 0f, 0f), reversed.nodes[0].to)
    }

    /** FLineGeometry.kt:2290 — `val p0 = nodes.last().to` */
    @Test
    fun arcToWorksOnAClosedLine() {
        val line = closedSquare().arcTo(vec(50f, -50f, 0f), vec(100f, -50f, 0f), 20f)
        assertTrue(line.nodes.size > 5, "arcTo appended nothing")
    }

    /** FLineGeometry.kt:2368 — `val start = nodes.last().to` */
    @Test
    fun circleThroughToWorksOnAClosedLine() {
        val line = closedSquare().circleThroughTo(vec(60f, -40f, 0f), vec(120f, 0f, 0f))
        assertTrue(line.nodes.size > 5, "circleThroughTo appended nothing")
    }

    /** FLineGeometry.kt:2417 — `val start = nodes.last().to` */
    @Test
    fun polarCubicToWorksOnAClosedLine() {
        val line = closedSquare().polarCubicTo(60f, 0.5f, 40f, 1.2f, vec(150f, 20f, 0f))
        assertTrue(line.nodes.size > 5, "polarCubicTo appended nothing")
    }

    /** FLineGeometry.kt:2797 — `val start = nodes.last().to` */
    @Test
    fun biarcToWorksOnAClosedLine() {
        val line = closedSquare().biarcTo(vec(150f, 30f, 0f), vec(1f, -1f, 0f), vec(1f, 1f, 0f))
        assertTrue(line.nodes.size > 5, "biarcTo appended nothing")
    }

    /** FLineGeometry.kt:3064 — `val start = this.nodes.last().to` */
    @Test
    fun byAppendingQuadraticWorksOnAClosedLine() {
        val line = closedSquare().byAppendingQuadratic(vec(60f, -60f, 0f), vec(120f, 0f, 0f))
        assertTrue(line.nodes.size > 5, "byAppendingQuadratic appended nothing")
    }

    /** FLineGeometry.kt:3092 — `val start = this.nodes.last().to` */
    @Test
    fun byAppendingArcByTangentWorksOnAClosedLine() {
        val line = closedSquare()
            .byAppendingArcByTangent(vec(120f, 40f, 0f), vec(1f, 0f, 0f), vec(0f, 1f, 0f), 16)
        assertTrue(line.nodes.size > 5, "byAppendingArcByTangent appended nothing")
    }

    /** FLineGeometry.kt:4134 — `nodes.lastOrNull()?.to ?: ...`, which `?.` cannot save */
    @Test
    fun byEulerSpiralToWorksOnAClosedLine() {
        val line = closedSquare().byEulerSpiralTo(vec(160f, 40f, 0f))
        assertTrue(line.nodes.size > 5, "byEulerSpiralTo appended nothing")
    }

    /** FLineGeometry.kt:4175 — `val start = nodes.last().to` */
    @Test
    fun eulerSpiralToWorksOnAClosedLine() {
        val line = closedSquare().eulerSpiralTo(vec(160f, 40f, 0f))
        assertTrue(line.nodes.size > 5, "eulerSpiralTo appended nothing")
    }

    /** FLine.kt:457 — `ListAnchor.End -> nodes.lastOrNull()?.to`, reached via rot/scale */
    @Test
    fun transformingAboutTheEndAnchorWorksOnAClosedLine() {
        val rotated = closedSquare().rot(90f, ListAnchor.End)
        assertTrue(rotated.nodes.isNotEmpty(), "rot about End produced nothing")
        // the End anchor of a closed line is the moveTo it closes back to, which is the origin here,
        // so a rotation about it leaves that corner where it was
        assertEquals(vec(0f, 0f, 0f), rotated.nodes[0].to)
    }

    /** FLinesAndJavaShapes.closestT walks `nodes[i - 1].to` with no Close branch. */
    @Test
    fun closestTWorksOnAClosedLine() {
        val line = closedSquare()
        val t = FLinesAndJavaShapes.closestT(line, vec(50f, 110f, 0f))
        assertTrue(t.isFinite(), "closestT returned $t")
        // nearest point on the square to (50, 110) is on the third edge, node.t in [2, 3]
        assertTrue(t in 2.0f..3.0f, "expected t in the third segment, got $t")
    }

    @Test
    fun cursorClosestToAPointWorksOnAClosedLine() {
        val line = closedSquare()
        val p = line.cursor(vec(50f, 110f, 0f)).position()
        assertTrue(p.x.isFinite() && p.y.isFinite(), "cursor(closestTo) produced $p")
    }

    /** the whole point: a closed glyph-ish outline must survive every one of these. */
    @Test
    fun aClosedCubicOutlineSurvivesTheWholeFamily() {
        fun outline() = FLine()
            .moveTo(0f, 0f)
            .cubicTo(Vec3(0f, 200f, 0f), Vec3(100f, 300f, 0f), Vec3(200f, 300f, 0f))
            .cubicTo(Vec3(300f, 300f, 0f), Vec3(400f, 200f, 0f), Vec3(400f, 0f, 0f))
            .close()

        assertTrue(outline().byReversing().nodes.isNotEmpty())
        assertTrue(outline().sampleByDistance(20f).size > 10)
        assertTrue(outline().byDashing(20f, 10f).size > 3)
        assertTrue(outline().midPoint().x.isFinite())
        assertTrue(outline().byArcLengthParameterizing(20f).nodes.size > 10)
        assertTrue(outline().cursor().lengthD() > 0f)
    }
}
