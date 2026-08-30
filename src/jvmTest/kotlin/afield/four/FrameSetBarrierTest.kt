package afield.four

import afield.four.texture.video.FrameSetBarrier
import afield.four.texture.video.VideoFrameIndex
import kotlin.test.*

class FrameSetBarrierTest {
    @Test fun `slow geometry holds the complete previous set instead of sliding video ahead`() {
        val gate = FrameSetBarrier<Int>()
        val uploaded = mutableListOf<Int>()
        gate.select(0, 0)
        assertTrue(gate.presentIfReady({ true }, uploaded::add))
        gate.select(1, 0)
        for (clockFrame in 2..8) {
            assertEquals(1, gate.select(clockFrame, 0))
            assertFalse(gate.presentIfReady({ false }, uploaded::add))
            assertEquals(0, gate.presented)
        }
        assertTrue(gate.presentIfReady({ it == 1 }, uploaded::add))
        assertEquals(listOf(0, 1), uploaded)
        assertEquals(8, gate.select(8, 0)) // skip whole sets, never individual layers
    }

    @Test fun `explicit seek supersedes pending work but ordinary loop completion does not`() {
        val gate = FrameSetBarrier<Int>()
        assertEquals(123, gate.select(123, 0))
        assertEquals(123, gate.select(0, 0))
        assertEquals(48, gate.select(48, 1))
        assertFalse(gate.presentIfReady({ it == 123 }) { error("stale frame upload") })
        assertTrue(gate.presentIfReady({ it == 48 }) {})
        assertNull(gate.select(48, 1))
        gate.reset()
        assertEquals(48, gate.select(48, 1))
    }

    @Test fun `a failed upload does not claim a presented set`() {
        val gate = FrameSetBarrier<Int>()
        gate.select(4, 0)
        assertFailsWith<IllegalStateException> {
            gate.presentIfReady({ true }) { error("upload failed") }
        }
        assertNull(gate.presented)
        assertEquals(4, gate.pending)
    }

    @Test fun `CFR requests floor subframes and decoded PTS round floating noise only`() {
        for (frame in 0L..123L) {
            assertEquals(frame, VideoFrameIndex.requested(frame / 24.0, 24.0))
            assertEquals(frame, VideoFrameIndex.requested((frame + 0.99) / 24.0, 24.0))
            assertEquals(frame, VideoFrameIndex.decoded(frame / 24.0, 24.0))
        }
        assertEquals(47L, VideoFrameIndex.requested((48.0 - 0.001) / 24.0, 24.0))
        assertEquals(0L, VideoFrameIndex.requested(-1.0, 24.0))
    }
}
