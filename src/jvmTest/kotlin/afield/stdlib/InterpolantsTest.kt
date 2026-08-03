package afield.stdlib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Suppress("UNCHECKED_CAST")
class InterpolantsTest {

    @Test
    fun frameClockAdvancesOncePerAuthoritativeFrame() {
        var frame = 0L
        val clock = frameClock(24.0) { frame }

        assertEquals(0.0, clock(), 0.0)
        assertEquals(0.0, clock(), 0.0, "re-reading one frame must not advance time")
        frame = 12
        assertEquals(0.5, clock(), 1e-12)
        frame = 24
        assertEquals(1.0, clock(), 1e-12)
    }

    @Test
    fun timeInterpolationCanRunOnRenderedFramesInsteadOfWallTime() {
        var frame = 100L
        val eased = smoothTo<Float>(
            time = 1.0,
            steps = null,
            clock = frameClock(4.0) { frame },
        ) { 1.0f }
        (eased as NeedsStartingValue<Float>).startAt(0.0f)

        assertEquals(0.0f, eased(), 0.0f)
        assertEquals(0.0f, eased(), 0.0f, "two evaluations in one frame must agree")
        frame = 101
        assertEquals(0.25f, eased(), 1e-6f)
        frame = 102
        assertEquals(0.5f, eased(), 1e-6f)
        frame = 104
        assertEquals(1.0f, eased(), 0.0f)
    }

    @Test
    fun startAtRestartsFromTheCurrentFrame() {
        var frame = 0
        val eased = smoothTo<Float>(
            time = 1.0,
            steps = null,
            clock = frameClock(10.0) { frame },
        ) { 1.0f }
        val restartable = eased as NeedsStartingValue<Float>

        restartable.startAt(0.0f)
        assertEquals(0.0f, eased(), 0.0f)
        frame = 5
        assertEquals(0.5f, eased(), 1e-6f)

        restartable.startAt(0.25f)
        assertEquals(0.25f, eased(), 0.0f)
        frame = 10
        assertEquals(0.625f, eased(), 1e-6f)
    }

    @Test
    fun stepModeDoesNotReadAnInjectedClock() {
        val eased = smoothTo<Float>(steps = 2, clock = { error("step mode read the clock") }) { 1.0f }
        (eased as NeedsStartingValue<Float>).startAt(0.0f)

        assertEquals(0.0f, eased(), 0.0f)
        assertEquals(0.5f, eased(), 1e-6f)
        assertEquals(1.0f, eased(), 0.0f)
    }

    @Test
    fun frameClockRejectsAnInvalidRate() {
        assertFailsWith<IllegalArgumentException> { frameClock(0.0) { 0 } }
        assertFailsWith<IllegalArgumentException> { frameClock(Double.NaN) { 0 } }
    }
}
