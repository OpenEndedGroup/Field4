package afield.four

import afield.four.fabricated.SplatTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SplatTransport] is the play head for splat-sequence playback. It is pure arithmetic over a clock,
 * so unlike the rest of the streaming path it can be pinned without a GPU — and it is worth pinning,
 * because the interesting property is a negative one: `advance` must report **no change** on the
 * frames where nothing changed. Getting that wrong costs a fifteen-file decode per display frame and
 * looks like nothing at all.
 */
class SplatTransportTest {

    private val second = 1_000_000_000L

    @Test
    fun `the first advance establishes the clock and reports a change`() {
        val t = SplatTransport(frameCount = 10, fps = 12.0)
        assertTrue(t.advance(0), "the initial frame has never been uploaded, so it must be reported")
        assertEquals(0, t.frame)
        assertFalse(t.advance(0), "no time passed, so nothing changed")
    }

    @Test
    fun `a 12 fps source over a 90 Hz display decodes on one display frame in seven`() {
        val t = SplatTransport(frameCount = 1000, fps = 12.0)
        t.advance(0)
        var changes = 0
        val displayFrames = 90 * 3
        for (i in 1..displayFrames) if (t.advance((i * second * 3) / displayFrames)) changes++

        // Three seconds at 12 fps is 36 source frames, within one either way. NOT asserted exactly:
        // `position` accumulates `elapsed * fps` per display frame, so after 270 additions it sits a
        // few times 1e-15 off an integer -- irrelevant to playback, but enough to move the resolved
        // frame when the run happens to end on a boundary. Pinning the exact figure would be pinning
        // the floating-point accumulation, not the behaviour.
        assertTrue(changes in 35..37, "expected ~36 source frames in three seconds, got $changes")

        // The property that actually matters: the other six display frames in seven must report no
        // change, because each one that does costs a fifteen-file decode to rewrite identical bytes.
        val quiet = displayFrames - changes
        assertTrue(
            quiet >= displayFrames * 5 / 7,
            "only $quiet of $displayFrames display frames avoided a decode"
        )
    }

    @Test
    fun `a sub-frame scrub reports no change`() {
        val t = SplatTransport(frameCount = 100, fps = 12.0)
        t.advance(0)
        assertEquals(0, t.frame)
        assertFalse(t.scrubBy(0.3), "a third of a frame does not change which frame is shown")
        assertFalse(t.scrubBy(0.3), "still inside frame 0")
        assertTrue(t.scrubBy(0.5), "crossing into frame 1 must be reported")
        assertEquals(1, t.frame)
    }

    @Test
    fun `scrubbing backwards past zero wraps when looping and clamps when not`() {
        val looping = SplatTransport(frameCount = 10).apply { loop = true }
        looping.seek(2.0)
        looping.scrubBy(-5.0)
        assertEquals(7, looping.frame, "-3 of 10 should wrap to 7")

        val clamping = SplatTransport(frameCount = 10).apply { loop = false }
        clamping.seek(2.0)
        clamping.scrubBy(-5.0)
        assertEquals(0, clamping.frame, "clamped at the start")
        assertTrue(clamping.playing, "clamping must not silently stop playback")
    }

    @Test
    fun `negative speed runs backwards`() {
        val t = SplatTransport(frameCount = 100, fps = 10.0)
        t.seek(50.0)
        t.advance(0)
        t.speed = -1.0
        // A full second exceeds the default 0.25s stall guard, which would clamp the step -- that
        // behaviour has its own test below, so opt out of it here.
        t.advance(second, maxCatchUpSeconds = 2.0)
        assertEquals(40, t.frame, "one second at -1x and 10 fps is ten frames back")
    }

    @Test
    fun `speed zero pauses without clearing playing`() {
        val t = SplatTransport(frameCount = 100, fps = 12.0)
        t.advance(0)
        t.speed = 0.0
        assertFalse(t.advance(second * 5), "speed 0 should not move the play head")
        assertEquals(0, t.frame)
        assertTrue(t.playing)
    }

    @Test
    fun `a long stall does not fling the play head forwards`() {
        val t = SplatTransport(frameCount = 1000, fps = 12.0)
        t.advance(0)
        // Ten seconds of wall clock -- a breakpoint, a recompile, a GC pause.
        t.advance(second * 10, maxCatchUpSeconds = 0.25)
        assertEquals(3, t.frame, "0.25s at 12fps is 3 frames, not 120")
    }

    @Test
    fun `resetClock discards elapsed time`() {
        val t = SplatTransport(frameCount = 100, fps = 12.0)
        t.advance(0)
        t.resetClock()
        t.advance(second * 10)
        assertEquals(0, t.frame, "after resetClock the next advance re-establishes the clock")
    }

    @Test
    fun `normalized maps the whole sequence onto zero to one`() {
        val t = SplatTransport(frameCount = 200)
        t.normalized = 0.5
        assertEquals(100, t.frame)
        assertEquals(0.5, t.normalized, 1e-9)
        t.normalized = 1.0
        assertEquals(0, t.frame, "1.0 wraps to the start when looping")
    }

    @Test
    fun `invalidate forces one change without moving the play head`() {
        val t = SplatTransport(frameCount = 10, fps = 12.0)
        t.advance(0)
        assertFalse(t.advance(0))
        t.invalidate()
        assertTrue(t.advance(0), "invalidate should make the next advance report a change")
        assertEquals(0, t.frame, "and must not have moved the play head")
    }

    @Test
    fun `a single-frame sequence never reports a change after the first`() {
        val t = SplatTransport(frameCount = 1, fps = 12.0)
        assertTrue(t.advance(0))
        for (i in 1..50) assertFalse(t.advance(i * second), "one frame can only ever be frame 0")
        assertEquals(0, t.frame)
    }

    @Test
    fun `frame is always in range however wild the position`() {
        val t = SplatTransport(frameCount = 7)
        for (target in listOf(-1000.0, -0.5, 0.0, 6.999, 7.0, 1e6)) {
            t.seek(target)
            assertTrue(t.frame in 0 until 7, "position $target resolved to frame ${t.frame}")
        }
        t.seek(Double.NaN)
        assertTrue(t.frame in 0 until 7, "NaN must not escape the range")
    }
}
