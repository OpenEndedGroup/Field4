package afield.graphics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A `Close` node stores no point (`Node(0)`), so `Node.to` — which is `storage[0]` — throws
 * `IndexOutOfBoundsException` on one. A cursor parked at, or stepping over, a `Close` used to read
 * exactly that, so every arc-length operation blew up on any closed line.
 */
class CursorClosedLineTest {

    private fun closedTriangle(): FLine = FLine()
        .moveTo(0f, 0f)
        .lineTo(100f, 0f)
        .lineTo(100f, 100f)
        .close()

    /** two subpaths, so the cursor has to step *through* a Close and out the far side. */
    private fun twoSubpaths(): FLine = FLine()
        .moveTo(0f, 0f)
        .lineTo(100f, 0f)
        .close()
        .moveTo(200f, 0f)
        .lineTo(300f, 0f)
        .close()

    @Test
    fun cursorAtTheEndOfAClosedLineHasAPosition() {
        val line = closedTriangle()
        val cursor = line.cursor()
        cursor.setD(cursor.lengthD())
        val p = cursor.position()
        // closing the triangle returns the pen to the moveTo
        assertTrue(abs(p.x) < 1e-3 && abs(p.y) < 1e-3, "expected to end back at the moveTo, got $p")
    }

    @Test
    fun closedLineLengthIncludesTheClosingSegment() {
        val length = closedTriangle().cursor().lengthD()
        val expected = 100f + 100f + kotlin.math.sqrt(2f) * 100f
        assertTrue(abs(length - expected) < 0.5f, "expected ~$expected, got $length")
    }

    @Test
    fun cursorWalksAcrossAClose() {
        val line = twoSubpaths()
        val cursor = line.cursor()
        val total = cursor.lengthD()
        // 100 (subpath one out) + 100 (its close, back) + 100 + 100
        assertTrue(abs(total - 400f) < 0.5f, "expected ~400, got $total")
        for (i in 0..40) {
            cursor.setD(total * i / 40f)
            cursor.position() // must not throw
        }
    }

    @Test
    fun sampleByDistanceWorksOnAClosedLine() {
        val pts = closedTriangle().sampleByDistance(10f)
        assertTrue(pts.size > 20, "expected many samples, got ${pts.size}")
    }

    @Test
    fun tangentsAreDefinedAcrossAClose() {
        val line = twoSubpaths()
        val cursor = line.cursor()
        val total = cursor.lengthD()
        for (i in 0..40) {
            cursor.setD(total * i / 40f)
            cursor.tangentForward() // must not throw
        }
    }
}
