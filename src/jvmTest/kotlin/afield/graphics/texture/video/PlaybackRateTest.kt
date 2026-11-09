package afield.graphics.texture.video

import kotlin.test.*

class PlaybackRateTest {
    @Test fun rateChangesAndPausePreservePosition() {
        var now = 0L
        val clock = SystemClock { now }
        clock.play()
        now += 1_000_000_000
        assertEquals(1.0, clock.getCurrentTime())
        clock.rate = 8.0
        assertEquals(1.0, clock.getCurrentTime())
        now += 500_000_000
        assertEquals(5.0, clock.getCurrentTime())
        clock.pause()
        clock.rate = 2.0
        now += 1_000_000_000
        assertEquals(5.0, clock.getCurrentTime())
        clock.seek(20.0)
        clock.play()
        now += 500_000_000
        assertEquals(21.0, clock.getCurrentTime())
        assertFailsWith<IllegalArgumentException> { clock.rate = 0.0 }
    }
}
