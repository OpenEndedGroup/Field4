package afield.graphics.scene.video

import kotlin.test.*

class VideoMaterialCadenceTest {
    @Test fun capsAtBothRatesOn120HzDisplay() {
        for (rate in listOf(12, 24)) {
            val cadence = VideoMaterialCadence()
            val updates = (0 until 120).count { cadence.tick(it * 1_000_000_000L / 120, rate) }
            assertEquals(rate, updates)
        }
    }

    @Test fun stallsDoNotProduceCatchupBurstsAndChangesAreImmediate() {
        val cadence = VideoMaterialCadence()
        assertTrue(cadence.tick(0, 12))
        assertFalse(cadence.tick(1_000_000, 12))
        assertTrue(cadence.tick(5_000_000_000, 12))
        assertFalse(cadence.tick(5_000_000_001, 12))
        assertTrue(cadence.tick(5_000_000_002, 24))
        assertTrue(cadence.tick(5_000_000_003, 0))
        assertTrue(cadence.tick(5_000_000_004, 0))
        assertTrue(cadence.tick(5_000_000_005, 12))
    }
}
