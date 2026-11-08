package afield.graphics.scene.video

import kotlin.test.*

class VideoAccumulationWeightsTest {
    @Test fun weightedMeanAndClear() {
        val weights = VideoAccumulationWeights()
        assertEquals(1f, weights.add(0.0, 1.0, 2.0))
        // Half of the old mass remains after one half-life; new sample has equal mass.
        assertEquals(.5f, weights.add(1.0, 1.0, 1.0))
        assertEquals(0f, weights.add(0.1, 1.0, 0.0))
        weights.clear()
        assertEquals(0f, weights.add(0.0, 1.0, 0.0))
        assertEquals(1f, weights.add(0.0, 1.0, 1.0))
    }

    @Test fun longExposureDoesNotLoseSmallIncomingWeight() {
        val weights = VideoAccumulationWeights()
        repeat(100_000) { weights.add(1.0 / 120, 60.0, 1.0) }
        val fraction = weights.add(1.0 / 120, 60.0, 1.0)
        assertTrue(fraction in 0.00009f..0.00010f)
        assertEquals(1f, weights.add(.1, 0.0, 1.0))
    }
}
