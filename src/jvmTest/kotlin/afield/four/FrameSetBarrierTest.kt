package afield.four

import afield.four.texture.video.FrameSetBarrier
import afield.four.texture.video.FrameSetPacer
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

    @Test fun `next set prepares for a full interval and is not replaced by a clock that ran ahead`() {
        val pacer = FrameSetPacer(124)
        val first = checkNotNull(pacer.select(10, true, 8.0, true, 0, 0))
        assertEquals(10, first.frame)
        assertTrue(first.isDue(0))
        pacer.markPresented(10, 0)

        val next = checkNotNull(pacer.select(10, true, 8.0, true, 0, 1))
        assertEquals(11, next.frame)
        assertFalse(next.isDue(124_999_999))
        assertTrue(next.isDue(125_000_000))
        assertEquals(next, pacer.select(17, true, 8.0, true, 0, 300_000_000))

        // A late frame is still useful. Once it arrives, cadence resumes from that presentation
        // rather than instantly chasing the external clock and starving frame 12.
        pacer.markPresented(11, 300_000_000)
        val afterLate = checkNotNull(pacer.select(17, true, 8.0, true, 0, 300_000_001))
        assertEquals(12, afterLate.frame)
        assertEquals(425_000_000, afterLate.notBeforeNanos)
    }

    @Test fun `ordinary render quantization does not accumulate into playback drift`() {
        val pacer = FrameSetPacer(124)
        pacer.select(0, true, 8.0, true, 0, 0)
        pacer.markPresented(0, 0)
        val one = checkNotNull(pacer.select(0, true, 8.0, true, 0, 1))
        assertEquals(125_000_000, one.notBeforeNanos)
        pacer.markPresented(1, 137_000_000) // one 60 Hz render frame late
        val two = checkNotNull(pacer.select(1, true, 8.0, true, 0, 137_000_001))
        assertEquals(250_000_000, two.notBeforeNanos, "the 12ms miss must not recur forever")
    }

    @Test fun `seek supersedes read ahead while pause and reverse preserve sequential playback`() {
        val pacer = FrameSetPacer(10)
        pacer.select(8, true, 5.0, true, 0, 0)
        pacer.markPresented(8, 0)
        assertEquals(9, pacer.select(8, true, 5.0, true, 0, 1)?.frame)
        assertEquals(3, pacer.select(3, true, 5.0, true, 1, 2)?.frame)
        pacer.markPresented(3, 2)

        assertNull(pacer.select(3, false, 5.0, true, 1, 3))
        assertEquals(2, pacer.select(3, true, -5.0, true, 1, 4)?.frame)
        pacer.markPresented(2, 200_000_004)
        assertEquals(1, pacer.select(9, true, -5.0, true, 1, 200_000_005)?.frame)
    }
}
