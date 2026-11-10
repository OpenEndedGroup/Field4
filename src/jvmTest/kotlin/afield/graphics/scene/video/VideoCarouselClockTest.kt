package afield.graphics.scene.video

import kotlin.test.*

class VideoCarouselClockTest {
    private val period = 8_333_333L
    private val start = 1_000_000_000L

    @Test fun rotationAndFlashUseOneGridAndIgnoreDuplicatePredictions() {
        val clock = VideoCarouselClock(8)
        clock.motion = VideoCarouselClock.Motion(stepped = true, stepFrames = 10, sectorLock = true)
        clock.flashes = List(8) { VideoCarouselClock.Flash(true, 10, 1, 0) }
        assertEquals(0.0, clock.advance(start, period))
        assertTrue(clock.visible(0))
        clock.advance(start + period, period)
        assertFalse(clock.visible(0))
        clock.advance(start + 9 * period, period)
        assertEquals(0.0, clock.angleDegrees)
        assertFalse(clock.visible(0))
        assertEquals(45.0, clock.advance(start + 10 * period, period))
        assertTrue(clock.visible(0))
        assertEquals(45.0, clock.advance(start + 10 * period, period))
        assertEquals(10, clock.tick)
        // One render arriving three refreshes late keeps the physical shutter schedule.
        assertEquals(135.0, clock.advance(start + 30 * period, period))
        assertTrue(clock.visible(0))
    }

    @Test fun fineDriftAndPhaseOffsetsSurviveSteppingAndParameterChanges() {
        val clock = VideoCarouselClock(8)
        clock.motion = VideoCarouselClock.Motion(stepFrames = 10, sectorLock = true, driftTurnsPerSecond = .001)
        clock.flashes = List(8) { VideoCarouselClock.Flash(true, 10, 1, it) }
        clock.setAngle(20.0)
        clock.advance(start, period)
        assertTrue(clock.visible(0))
        assertFalse(clock.visible(1))
        clock.advance(start + period, period)
        assertTrue(clock.visible(1))
        assertEquals(20 + .36 * period / 1e9, clock.angleDegrees, 1e-9)
        clock.motion = clock.motion.copy(sectorsPerStep = -1, driftTurnsPerSecond = 0.0)
        val angle = clock.angleDegrees
        clock.advance(start + 2 * period, period)
        assertEquals(angle, clock.angleDegrees, 1e-9)
        clock.advance(start + 10 * period, period)
        assertEquals((angle - 45 + 360) % 360, clock.angleDegrees, 1e-9)
    }

    @Test fun wallSpeedCanBeQuantizedWithoutChangingItsAverageAndFlashesIgnoreVideoPause() {
        val clock = VideoCarouselClock(8)
        clock.motion = VideoCarouselClock.Motion(turnsPerSecond = 1.0, stepped = true, stepFrames = 4)
        clock.advance(start, period)
        clock.advance(start + 3 * period, period)
        assertEquals(0.0, clock.angleDegrees)
        clock.advance(start + 4 * period, period)
        assertEquals(360.0 * 4 * period / 1e9, clock.angleDegrees, 1e-9)
        assertTrue(clock.visible(0)) // default shutters are open
        assertFalse(VideoCarouselClock.Flash(true, 10, 1).visible(9))
        assertTrue(VideoCarouselClock.Flash(true, 10, 1).visible(10))
        assertTrue(VideoCarouselClock.Flash(true, 10, 1, 2).visible(12))
    }
}
