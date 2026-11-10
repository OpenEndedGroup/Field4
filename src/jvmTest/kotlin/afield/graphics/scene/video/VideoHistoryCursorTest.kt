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

    @Test fun eightScreensSelectIndependentTimesAtFourAndEightFrameSpacing() {
        for (spacing in listOf(4, 8)) {
            val capacity = 7 * spacing + 1
            val cursor = VideoHistoryCursor(capacity)
            val pictures = LongArray(capacity)
            for (serial in 0L..200L) pictures[cursor.advance()] = serial
            assertEquals((0..7).map { 200L - it * spacing },
                (0..7).map { pictures[cursor.slot(it * spacing)!!] })
            assertFailsWith<IllegalArgumentException> { cursor.slot(capacity) }
        }
        assertFailsWith<IllegalArgumentException> { VideoHistoryCursor(0) }
    }
}
