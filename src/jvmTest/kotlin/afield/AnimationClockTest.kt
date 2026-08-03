package afield

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnimationClockTest {
    @Test
    fun frameIndexedClockAdvancesByExactlyOnePlaybackFrame() {
        val token = AnimationClock.beginFrameIndexed(24)
        try {
            assertTrue(AnimationClock.isFrameIndexed())
            val start = AnimationClock.nowSeconds()
            assertEquals(0.0, start, 0.0)
            repeat(17) { AnimationClock.advance(token) }
            assertEquals(17.0 / 24.0, AnimationClock.nowSeconds() - start, 1e-9)
        } finally {
            AnimationClock.endFrameIndexed(token)
        }
        assertFalse(AnimationClock.isFrameIndexed())
    }

    @Test
    fun videoFrameCountIsDurationTimesPlaybackRate() {
        assertEquals(180, videoFrameCount(durationSec = 9.0, fps = 20))
        assertEquals(1, videoFrameCount(durationSec = 0.0, fps = 20))
        assertEquals(8, videoFrameCount(durationSec = 0.25, fps = 30))
    }

    @Test
    fun rateLimiterPublishesSynchronouslyOnVirtualFrameBoundaries() {
        val token = AnimationClock.beginFrameIndexed(20)
        try {
            val published = mutableListOf<Int>()
            val limiter = RateLimiter(rate = 10.0, maxLatency = 0.2)
            limiter { published += 0 }
            limiter { published += 1 }
            AnimationClock.advance(token)
            limiter { published += 2 }
            AnimationClock.advance(token)
            limiter { published += 3 }

            assertEquals(listOf(0, 3), published)
        } finally {
            AnimationClock.endFrameIndexed(token)
        }
    }
}
