package afield.graphics.scene.video

import kotlin.test.*

class VideoExposureCadenceTest {
    @Test fun twelvePairsHaveTwentyFourAlternatingHalves() {
        val clock = VideoExposureCadence()
        val phases = (0 until 120).map { clock.phase(it * 1_000_000_000L / 120, true, 12.0) }
        assertEquals((0L..11L).toList(), phases.map { it.pair }.distinct())
        for (pair in 0L..11L) {
            assertEquals(listOf(true, false), phases.filter { it.pair == pair }.map { it.softened }.distinct())
        }
    }

    @Test fun pauseHoldsPhaseAndResumeDoesNotJump() {
        val clock = VideoExposureCadence()
        clock.phase(0, true, 12.0)
        val paused = clock.phase(50_000_000, false, 12.0)
        assertFalse(paused.softened)
        assertEquals(paused, clock.phase(9_000_000_000, false, 12.0))
        assertEquals(paused, clock.phase(10_000_000_000, true, 12.0))
        assertEquals(VideoExposureCadence.Phase(1, true), clock.phase(10_040_000_000, true, 12.0))
    }

    @Test fun stallsSkipPairsAndResetStartsSoftHalf() {
        val clock = VideoExposureCadence()
        clock.phase(0, true, 12.0)
        assertEquals(VideoExposureCadence.Phase(60, true), clock.phase(5_000_000_000, true, 12.0))
        clock.reset()
        assertEquals(VideoExposureCadence.Phase(0, true), clock.phase(6_000_000_000, true, 12.0))
        for (rate in listOf(0.0, Double.NaN, 31.0)) {
            assertFailsWith<IllegalArgumentException> { clock.phase(0, true, rate) }
        }
    }
}
