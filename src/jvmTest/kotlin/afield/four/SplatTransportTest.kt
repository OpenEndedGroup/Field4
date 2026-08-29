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

    // ------------------------------------------------------------------------
    // The .h readout
    // ------------------------------------------------------------------------

    @Test
    fun `the readout names the frame and the total`() {
        val t = SplatTransport(frameCount = 124, fps = 12.0)
        t.seek(31.0)
        val html = t.toHTML()
        assertTrue("frame 31 / 124" in html, "the readout should say which frame of how many:\n$html")
        assertTrue("12fps" in html, "and the source rate")
    }

    @Test
    fun `the play head sits where the position says`() {
        val t = SplatTransport(frameCount = 100)
        fun headX(html: String): Double =
            Regex("<circle cx='([0-9.]+)'").find(html)!!.groupValues[1].toDouble()

        t.seek(0.0)
        val start = headX(t.toHTML())
        t.normalized = 0.5
        val middle = headX(t.toHTML())
        t.seek(99.0)
        val end = headX(t.toHTML())

        assertTrue(start < middle && middle < end, "head should advance: $start, $middle, $end")
        // Half way along the track, within a pixel of the geometric centre.
        assertTrue(Math.abs(middle - (start + end) / 2) < 2.0, "half position should be mid-track")
    }

    @Test
    fun `the readout distinguishes the four states a glance needs`() {
        val t = SplatTransport(frameCount = 50)
        t.playing = true; t.speed = 1.0
        assertTrue("playing" in t.toHTML())
        t.speed = -1.0
        assertTrue("reverse" in t.toHTML(), "a negative speed should read as reverse, not as -1x playing")
        t.speed = 0.0
        assertTrue("held" in t.toHTML(), "speed 0 is not the same state as paused")
        t.playing = false; t.speed = 1.0
        assertTrue("paused" in t.toHTML())
    }

    @Test
    fun `a moving head is coloured differently from a parked one`() {
        val t = SplatTransport(frameCount = 50)
        t.seek(25.0)
        t.playing = true; t.speed = 1.0
        val moving = t.toHTML()
        t.playing = false
        val parked = t.toHTML()
        assertTrue(moving != parked, "playing and paused should not render identically")
        assertTrue("#4a9d7f" in moving, "a moving head should use the active fill")
        assertTrue("#4a9d7f" !in parked, "a parked head should not")
    }

    @Test
    fun `loop and clamp are stated rather than left to be guessed`() {
        val t = SplatTransport(frameCount = 50)
        t.loop = true
        assertTrue("loop" in t.toHTML())
        t.loop = false
        val html = t.toHTML()
        assertTrue("clamp" in html && "&#183; loop" !in html, "clamping should say so:\n$html")
    }

    @Test
    fun `the readout is well formed and locale independent`() {
        val t = SplatTransport(frameCount = 7)
        t.seek(3.0)
        val html = t.toHTML()
        assertEquals(
            html.split("<svg").size, html.split("</svg>").size,
            "unbalanced svg tags"
        )
        assertEquals(html.split("<div").size, html.split("</div>").size, "unbalanced div tags")
        // A comma decimal separator would make the SVG coordinates invalid; String.format with a
        // default locale is the usual way that happens, so the readout pins Locale.ROOT.
        assertFalse(
            Regex("(cx|x1|x2)='[0-9]+,[0-9]+'").containsMatchIn(html),
            "a comma decimal separator escaped into an SVG coordinate:\n$html"
        )
        // Nothing should render a raw NaN or Infinity into the page.
        assertFalse("NaN" in html, "NaN in the readout")
        assertFalse("Infinity" in html, "Infinity in the readout")
    }

    @Test
    fun `a single-frame sequence still renders`() {
        val html = SplatTransport(frameCount = 1).toHTML()
        assertTrue("frame 0 / 1" in html, html)
        assertFalse("NaN" in html, "a one-frame sequence divides by the frame count")
    }
}
