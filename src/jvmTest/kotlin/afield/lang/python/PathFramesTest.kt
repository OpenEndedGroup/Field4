package afield.lang.python

import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.graphics.render.filled
import afield.graphics.render.stroked
import afield.graphics.render.thickness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [PathFrames]'s contract, with Kotlin standing in for `fieldmanim.bake`: the same flat arrays it
 * publishes, so no JEP or Manim is needed.
 */
class PathFramesTest {

    private fun unique(prefix: String) = "$prefix-${System.nanoTime()}"

    /**
     * Two snapshots on a 4 x 2 unit frame: a filled closed unit square, then the same square
     * stroked at Manim width 4 plus an open line. Three frames, the last two sharing snapshot 1.
     */
    private fun publishTwoSnapshots(frames: PathFrames) {
        fun cubicLine(x0: Float, y0: Float, x1: Float, y1: Float) =
            floatArrayOf(x0, y0, x0, y0, x1, y1, x1, y1).drop(2).toFloatArray() // handle, handle, anchor
        val square = floatArrayOf(-0.5f, -0.5f) +
            cubicLine(-0.5f, -0.5f, 0.5f, -0.5f) + cubicLine(0.5f, -0.5f, 0.5f, 0.5f) +
            cubicLine(0.5f, 0.5f, -0.5f, 0.5f) + cubicLine(-0.5f, 0.5f, -0.5f, -0.5f)
        val line = floatArrayOf(-2f, 1f) + cubicLine(-2f, 1f, 2f, -1f)
        frames.publish(
            fps = 10.0, unitWidth = 4.0, unitHeight = 2.0,
            background = floatArrayOf(0f, 0f, 0f, 1f),
            points = square + square + line,
            subpaths = intArrayOf(13, 1, 13, 1, 4, 0),
            styles = floatArrayOf(1f, 0f, 0f, 1f, 0f, 1f, 0f, 1f, 0f, 1f, 4f, 0f, 0f, 0f, 1f, 1f, 2f, 0f),
            pathSubpaths = intArrayOf(1, 1, 1),
            snapshotPaths = intArrayOf(1, 2),
            frameSnapshots = intArrayOf(0, 1, 1),
            beatStarts = intArrayOf(0, 1),
            beatNames = "FadeIn\nWait",
        )
    }

    private fun FLine.endpoints() = nodes.filter { it !is FLine.Close }.map { it.to.x.toDouble() to it.to.y.toDouble() }

    @Test
    fun framesAreAttachedByNameAndTimeIsHeldOrWrapped() {
        val name = unique("paths")
        val frames = NumpyShared.pathFrames(name)
        assertSame(frames, NumpyShared.pathFrames(name))
        assertTrue(frames.at(0.0).isEmpty(), "nothing published yet")

        publishTwoSnapshots(frames)
        assertEquals(3, frames.frameCount)
        assertEquals(2, frames.snapshotCount)
        assertEquals(0.3, frames.duration, 1e-9)

        assertEquals(2, frames.at(0.0).size, "background and the filled square")
        assertEquals(3, frames.at(0.15).size, "background, stroked square, line")
        assertEquals(3, frames.at(99.0).size, "held at the last frame after the end")
        assertEquals(2, frames.at(0.3, loop = true).size, "wrapped round to frame 0")
        assertSame(frames.frame(1), frames.frame(2), "frames sharing a snapshot share their lines")
    }

    @Test
    fun manimCoordinatesMapOntoPixelsYDownAndStrokesScaleLikeCairo() {
        val frames = NumpyShared.pathFrames(unique("paths"))
        publishTwoSnapshots(frames)

        val (background, square) = frames.frame(0, width = 400.0, height = 200.0)
        assertTrue(background.filled && !background.stroked)
        assertEquals(listOf(0.0 to 0.0, 400.0 to 0.0, 400.0 to 200.0, 0.0 to 200.0), background.endpoints())
        assertTrue(background.nodes.last() is FLine.Close)

        assertTrue(square.filled && !square.stroked)
        assertEquals(1f, square.color.x)
        // (-0.5, -0.5) in a 4 x 2 frame centred on the origin, y up, is (150, 150) on 400 x 200, y down.
        assertEquals(150.0 to 150.0, square.endpoints().first())
        assertEquals(250.0 to 50.0, square.endpoints()[2])

        val (_, stroked, line) = frames.frame(1, width = 400.0, height = 200.0)
        assertTrue(!stroked.filled)
        // stroke_width 4 is 0.04 frame units; at 100 px a unit that is 4 px.
        assertEquals(4.0, stroked.thickness!!, 1e-6)
        assertEquals(2.0, line.thickness!!, 1e-6)
        assertEquals(listOf(0.0 to 0.0, 400.0 to 200.0), line.endpoints())
        assertEquals(2, frames.frame(1, background = false).size)
    }

    @Test
    fun eachPlayIsABeatAndThePlayerPlaysOnlyTheNextOne() {
        val frames = NumpyShared.pathFrames(unique("paths"))
        publishTwoSnapshots(frames)
        assertEquals(listOf("FadeIn", "Wait"), frames.beats.map { it.name })
        assertEquals(0.1, frames.beats[0].end, 1e-9)
        assertEquals(0.3, frames.beatEnd(2), 1e-9)

        val play = frames.player()
        play.toBeat(2)
        assertEquals(0.3, play.now(), 1e-9, "two beats ahead is a jump, not a performance")
        play.toBeat(1)
        assertEquals(0.1, play.now(), 1e-9, "going back shows the earlier beat at once")
        play.toBeat(2)
        val t = play.now()
        assertTrue(t >= 0.1 && t < 0.3, "the next beat plays from where it is, at the scene's speed; at $t")
        play.toBeat(99)
        assertEquals(2, play.beat, "clamped to the beats there are")
    }

    @Test
    fun inconsistentArraysAreRefusedWithTheirCounts() {
        val frames = NumpyShared.pathFrames(unique("paths"))
        val e = assertFailsWith<IllegalArgumentException> {
            frames.publish(30.0, 4.0, 2.0, floatArrayOf(0f, 0f, 0f, 1f), FloatArray(8), intArrayOf(5, 0),
                FloatArray(6), intArrayOf(1), intArrayOf(1), intArrayOf(0))
        }
        assertTrue("5 points" in e.message!!, e.message)
        assertTrue(frames.at(0.0).isEmpty(), "a refused publish leaves the old animation in place")
    }

    @Test
    fun taggedPartsAreAskedForByNameInTheScenesOwnUnits() {
        val frames = NumpyShared.pathFrames(unique("tagged"))
        // One frame, two open lines in x, y, z: "rod" from (0, 0, 1) to (2, 0, 1), and an untagged one.
        val rod = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 2f, 0f, 1f, 2f, 0f, 1f)
        val other = floatArrayOf(-1f, -1f, 0f, -1f, -1f, 0f, -1f, 1f, 0f, -1f, 1f, 0f)
        frames.publish(
            fps = 10.0, unitWidth = 4.0, unitHeight = 2.0,
            background = floatArrayOf(0f, 0f, 0f, 0f),
            points = rod + other,
            subpaths = intArrayOf(4, 0, 4, 0),
            styles = floatArrayOf(1f, 1f, 1f, 1f, 4f, 0f, 1f, 1f, 1f, 1f, 4f, 0f),
            pathSubpaths = intArrayOf(1, 1),
            snapshotPaths = intArrayOf(2),
            frameSnapshots = intArrayOf(0),
            pathTags = intArrayOf(0, -1),
            tagNames = "rod",
            dims = 3,
        )
        assertEquals(listOf("rod"), frames.tags)
        assertTrue(frames.hasDepth)
        assertEquals(2, frames.units(0.0).size)
        val only = frames.units(0.0, "rod").single()
        assertEquals(1.0, only.nodes.last().to.z.toDouble(), 1e-6, "z is kept")
        assertEquals(.04, only.thickness!!.toDouble(), 1e-6, "stroke width in units")
        val c = frames.center("rod", 0.0)!!
        assertEquals(1f, c.x, 1e-6f); assertEquals(0f, c.y, 1e-6f); assertEquals(1f, c.z, 1e-6f)
        assertEquals(null, frames.center("missing", 0.0))
        assertTrue(frames.units(0.0, "missing").isEmpty())
        // The 2D canvas drawing reads x, y from the triples just as from pairs.
        val drawn = frames.at(0.0, 400.0, 200.0, background = false)
        assertEquals(2, drawn.size)
        assertEquals(400.0, drawn[0].nodes.last().to.x.toDouble(), 1e-6)
        assertFailsWith<IllegalArgumentException> {
            frames.publish(10.0, 4.0, 2.0, floatArrayOf(0f, 0f, 0f, 0f), rod, intArrayOf(4, 0), floatArrayOf(1f, 1f, 1f, 1f, 4f, 0f),
                intArrayOf(1), intArrayOf(1), intArrayOf(0), IntArray(0), "", intArrayOf(3), "rod", 3)
        }
    }
}
