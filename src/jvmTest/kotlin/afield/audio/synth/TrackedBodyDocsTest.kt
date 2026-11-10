package afield.audio.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackedBodyDocsTest {
    /** The numbers chain_tracked_body.md's `heard` capture shows, from the same recording. */
    @Test
    fun renderMatchesThePagesPianoRoll() {
        val (frames, aspect) = TrackedBodyDocs.frames()
        val notes = TrackedBodyDocs.notes(TrackedBodyDocs.hits(frames, aspect))
        assertEquals(61, notes.size)
        assertEquals(49.0, notes.minOf { it.note })
        assertEquals(82.0, notes.maxOf { it.note })
        val audio = TrackedBodyDocs.render()
        assertEquals(14.0, audio.left.size.toDouble() / PatSoundDocs.SR, 0.05)
        assertTrue(audio.left.maxOf { kotlin.math.abs(it) } > 0.01f)
    }
}
