package afield.graphics.texture.video

import kotlin.test.*

class VideoRateChangeTest {
    private data class Picture(val time: Double, var releases: Int = 0)
    private fun queue(capacity: Int = 8) = VideoFrameQueue<Picture>({ it.time }, { it.releases++ }, capacity)

    @Test fun slowingReanchorsToDisplayedImageAndPacesAlreadyDecodedPictures() {
        var now = 0L
        val clock = SystemClock { now }
        clock.rate = 8.0
        clock.play()
        now = 5_000_000_000
        assertEquals(40.0, clock.getCurrentTime())
        // The decoder only reached second 10: thirty seconds of clock debt must be discarded.
        val frames = queue()
        val pictures = (1..8).map { Picture(10.0 + it / 24.0).also(frames::add) }
        clock.setRateAt(1.0, 10.0)
        assertEquals(10.0, clock.getCurrentTime())
        var shown = 10.0
        val changedAt = now
        // At 120 display Hz, the old burst is now presented at its 24 fps timestamps, not drained
        // eight pictures in eight headset frames. Taken pictures remain caller-owned.
        for (i in 0..12) {
            now = changedAt + i * 1_000_000_000L / 120
            frames.take(clock.getCurrentTime() + .5 / 24, latest = false)?.let {
                shown = it.time
                it.releases++
            }
            assertTrue(shown <= clock.getCurrentTime() + .5 / 24)
        }
        assertTrue(shown in 10.08..10.126, "visible position $shown")
        frames.clear()
        assertTrue(pictures.all { it.releases == 1 })
    }

    @Test fun cappedPublicationSelectsNewestDuePictureAndRetainsFutureOnes() {
        val frames = queue()
        val pictures = (1..5).map { Picture(it.toDouble()).also(frames::add) }
        assertEquals(pictures[1], frames.take(2.5, latest = true))
        assertEquals(1L, frames.superseded)
        assertNull(frames.take(2.6, latest = true))
        assertEquals(0, pictures[2].releases)
        assertEquals(pictures[3], frames.take(4.0, latest = true))
        assertEquals(2L, frames.superseded)
        assertEquals(pictures[4], frames.take(null, latest = false)) // explicit seek bypass
        assertFalse(frames.isNotEmpty())
    }

    @Test fun boundedQueueReleasesOverflowAndClearExactlyOnce() {
        val frames = queue(capacity = 2)
        val pictures = (0..3).map { Picture(it.toDouble()).also(frames::add) }
        assertEquals(2L, frames.dropped)
        frames.clear()
        frames.clear()
        assertTrue(pictures.all { it.releases == 1 })
    }

    @Test fun ordinaryRateSetterRemainsContinuousAndExplicitAnchorPreservesPause() {
        var now = 0L
        val clock = SystemClock { now }
        clock.play()
        now = 2_000_000_000
        clock.rate = 8.0
        assertEquals(2.0, clock.getCurrentTime())
        now = 3_000_000_000
        assertEquals(10.0, clock.getCurrentTime())
        clock.pause()
        clock.setRateAt(1.0, 7.0)
        now = 8_000_000_000
        assertFalse(clock.isPlaying())
        assertEquals(7.0, clock.getCurrentTime())
        clock.play()
        now = 9_000_000_000
        assertEquals(8.0, clock.getCurrentTime())
        assertFailsWith<IllegalArgumentException> { clock.setRateAt(Double.NaN, 2.0) }
        assertFailsWith<IllegalArgumentException> { clock.setRateAt(1.0, Double.NaN) }
    }
}
