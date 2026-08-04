package afield

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun videoOutputCaptureAcceptsVisibleDecodedContent() {
        assertNull(
            videoOutputCaptureProblem(
                VideoOutputCaptureState(
                    boxFound = true,
                    outputAreaFound = true,
                    disclosureCount = 1,
                    openDisclosureCount = 1,
                    nonEmptyContentCount = 1,
                    imageCount = 1,
                    failedImageCount = 0,
                )
            )
        )
    }

    @Test
    fun videoOutputCaptureExplainsMissingOrInvisibleContent() {
        val valid = VideoOutputCaptureState(
            boxFound = true,
            outputAreaFound = true,
            disclosureCount = 1,
            openDisclosureCount = 1,
            nonEmptyContentCount = 1,
            imageCount = 1,
            failedImageCount = 0,
        )

        assertEquals("the box is absent from the page", videoOutputCaptureProblem(valid.copy(boxFound = false)))
        assertEquals("the box has no output area", videoOutputCaptureProblem(valid.copy(outputAreaFound = false)))
        assertEquals("the output area has no disclosures", videoOutputCaptureProblem(valid.copy(disclosureCount = 0)))
        assertEquals("every output disclosure is closed", videoOutputCaptureProblem(valid.copy(openDisclosureCount = 0)))
        assertEquals("every open output disclosure is empty", videoOutputCaptureProblem(valid.copy(nonEmptyContentCount = 0)))
        assertEquals(
            "1 of 1 output images failed to decode",
            videoOutputCaptureProblem(valid.copy(failedImageCount = 1))
        )
    }
}
