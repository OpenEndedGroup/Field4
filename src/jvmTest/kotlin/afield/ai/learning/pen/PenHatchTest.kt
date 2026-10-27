package afield.ai.learning.pen

import afield.geometry.path.FLine
import afield.math.linalg.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PenHatchTest {
    @Test
    fun proposingLeavesThePenWhereItWas() {
        val pen = PenModel.distill.pen(3)
        pen.feed(0.2, 0.1, false)
        val before = pen.rawOutput().toList()
        val x = pen.x0
        repeat(5) { pen.propose() }
        assertEquals(before, pen.rawOutput().toList())
        assertEquals(x, pen.x0)
    }

    @Test
    fun followGuidePrefersTheGapAndPunishesJumpsAndCrossings() {
        val g = FollowGuide(gap = 4.0)
        g.see(listOf(FLine().moveTo(0, 0).lineTo(100, 0)))
        val from = Vec3(50f, 5f, 0f)
        fun at(x: Float, y: Float, lift: Boolean = false) = g.score(from, Vec3(x, y, 0f), lift, false)
        assertTrue(at(52f, 4f) < at(52f, 9f), "on the gap beats wandering away")
        assertTrue(at(52f, 4f) < at(52f, 1f), "on the gap beats crowding the line")
        assertTrue(at(52f, -4f) > 40.0, "a step across the line is a crossing")
        assertTrue(at(70f, 4f) > 40.0, "a jump is not following")
        assertTrue(at(52f, 4f, lift = true) > at(52f, 4f))
    }

    @Test
    fun hatchingALineFollowsItWithoutCrossing() {
        val line = FLine().moveTo(0, 0).lineTo(160, 0)
        val h = Hatcher(listOf(line), PenModel.bundled("echoes"), passes = 6, seed = 2).finish()
        assertEquals(1, h.size)
        val pts = PenCorpus.strokes(h[0]).flatten()
        assertTrue(pts.size > 200, "drew ${pts.size} points")
        // stays near the line, and very rarely on it
        assertTrue(pts.count { kotlin.math.abs(it.y) < 40f } > pts.size * 0.95)
        assertTrue(pts.count { kotlin.math.abs(it.y) < 1f && it.x in 2f..158f } < pts.size * 0.02)
        // and most of it runs alongside ink: the line, then its own earlier passes
        val drawing = line.copy().also { it += h[0] }
        assertTrue(following(drawing) > 0.8, "following ${following(drawing)}")
    }

    @Test
    fun everyStrokeOfAWordIsHatched() {
        val word = HersheyFont.load("futural").text("EL", size = 60.0)
        val hatcher = Hatcher(listOf(word), PenModel.bundled("echoes"), passes = 3, seed = 1)
        val strokes = word.pieces().size
        hatcher.finish()
        assertTrue(hatcher.done)
        assertEquals(strokes, hatcher.hatching.size)
    }
}
