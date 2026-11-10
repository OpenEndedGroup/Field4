package afield.graphics.scene.video

import kotlin.test.*

class VideoHistoryCursorTest {
    @Test fun warmupHoldsOldestThenEveryAgeTracksItsOwnSlotThroughWraps() {
        val cursor = VideoHistoryCursor(8)
        val pictures = LongArray(8) { -1 }
        assertNull(cursor.slot(0))
        for (serial in 0L..39L) {
            pictures[cursor.advance()] = serial
            for (age in 0..7) assertEquals((serial - age).coerceAtLeast(0), pictures[cursor.slot(age)!!])
        }
        assertEquals(8, cursor.count)
        cursor.clear()
        assertEquals(0, cursor.count)
        assertNull(cursor.slot(7))
        pictures[cursor.advance()] = 100
        assertEquals(100, pictures[cursor.slot(7)!!]) // never samples the previous loop/seek
    }

    @Test fun fourFrameSpacingSelectsEightIndependentTimesWithinTwentyNinePictures() {
        val cursor = VideoHistoryCursor(29)
        val pictures = LongArray(29)
        for (serial in 0L..100L) pictures[cursor.advance()] = serial
        assertEquals((0..7).map { 100L - it * 4 }, (0..7).map { pictures[cursor.slot(it * 4)!!] })
        assertFailsWith<IllegalArgumentException> { cursor.slot(29) }
        assertFailsWith<IllegalArgumentException> { VideoHistoryCursor(0) }
    }
}
