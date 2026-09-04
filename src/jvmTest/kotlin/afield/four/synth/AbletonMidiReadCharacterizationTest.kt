package afield.four.synth

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the exact output of `AbletonSamplePitchAnalysis.readMidi` — the only production MIDI-file
 * reader Field had before `Midi.kt` — so that lifting its private helpers out of `AbletonSet.kt`
 * into the shared MIDI module is a *measurement* rather than a hope.
 *
 * This test was written and run green against `AbletonSet.kt` at c3a920e1f, where the note pairing,
 * `tempoEvents()` and `tickToSeconds` were `private` to that file (declarations at :1050, :1057,
 * :1131 and :1170 of that revision). The refactor then deleted those five declarations and made
 * `readPitchNotes` a caller of `Sequence.readTimedNotes()`. If this test still passes, the lift
 * preserved behaviour; if it fails, it did not.
 *
 * The fixture is hand-assembled Standard MIDI File *bytes*, not a file written by Field's own
 * writer, because the thing being characterised is how Field reads a file it did not write — the
 * production caller reads `basic-pitch` output. The bytes deliberately exercise the four features
 * that a naive reader gets wrong:
 *
 *  - **Running status.** Two of the note-offs omit their status byte entirely and inherit the
 *    previous `0x90`. This is how real sequencers compress a note stream.
 *  - **Zero-velocity note-on as note-off.** Every note-off here is a `NOTE_ON` with velocity 0.
 *  - **Overlapping same-pitch notes.** Two channel-0 middle-C notes are sounding at once, so the
 *    reader must pair them FIFO rather than collapsing both onto the first note-off.
 *  - **A mid-file tempo change**, so `tickToSeconds` has to integrate rather than multiply, and an
 *    **unterminated note**, which must be closed at `tickLength` rather than dropped.
 */
class AbletonMidiReadCharacterizationTest {

    @Test
    fun `readMidi resolves a hand-assembled foreign SMF to exactly three notes`() {
        val dir = createTempDir()
        val midi = File(dir, "characterization.mid")
        midi.writeBytes(foreignSmfBytes())
        val source = File(dir, "source.wav").apply { writeBytes(ByteArray(16)) }

        // Assert the test's own premise before asserting the behaviour: if `MidiSystem` ever stops
        // parsing this fixture (a JDK change, a corrupted write), the premise fails loudly instead
        // of the assertions below quietly becoming a tautology over an empty list.
        val sequence = javax.sound.midi.MidiSystem.getSequence(midi)
        assertEquals(480, sequence.resolution, "fixture PPQ must survive the JDK parser")
        assertEquals(2, sequence.tracks.size, "fixture must parse as a 2-track format-1 file")
        assertEquals(1920L, sequence.tickLength, "fixture length is what the open note is closed at")

        val analysis = AbletonSamplePitchAnalysis.readMidi(source, midi)
        val notes = analysis.notes

        assertEquals(3, notes.size, "expected exactly three paired notes, got ${notes.map { it.midiPitch }}")

        // Ordering is `compareBy(startSeconds).thenByDescending(velocity)`, applied in readPitchNotes.
        val first = notes[0]
        assertEquals(60, first.midiPitch)
        assertEquals(100, first.velocity)
        assertEquals(0, first.channel)
        assertEquals(0.0, first.startSeconds, TOLERANCE)
        assertEquals(1.0, first.endSeconds, TOLERANCE)

        // The second middle C started while the first was still sounding, and must have been paired
        // with the *second* note-off, not the first. Its end crosses the tempo change: 480 ticks at
        // 120 BPM (0.5 s) then 480 ticks at 60 BPM (1.0 s) puts it at 2.0 s, not 1.5 s.
        val second = notes[1]
        assertEquals(60, second.midiPitch)
        assertEquals(80, second.velocity)
        assertEquals(0, second.channel)
        assertEquals(0.5, second.startSeconds, TOLERANCE)
        assertEquals(2.0, second.endSeconds, TOLERANCE)

        // Never given a note-off; closed at tickLength (1920) == 3.0 s.
        val third = notes[2]
        assertEquals(67, third.midiPitch)
        assertEquals(90, third.velocity)
        assertEquals(1, third.channel, "the third note is on channel 1, so channel must survive")
        assertEquals(2.0, third.startSeconds, TOLERANCE)
        assertEquals(3.0, third.endSeconds, TOLERANCE)

        dir.deleteRecursively()
    }

    @Test
    fun `dominantPitchAtSeconds picks the loudest note covering an instant`() {
        val dir = createTempDir()
        val midi = File(dir, "characterization.mid")
        midi.writeBytes(foreignSmfBytes())
        val source = File(dir, "source.wav").apply { writeBytes(ByteArray(16)) }

        val analysis = AbletonSamplePitchAnalysis.readMidi(source, midi)

        // At 0.75 s both channel-0 notes are sounding (0.0-1.0 at velocity 100, 0.5-2.0 at 80).
        // The velocity-100 note wins. This is the behaviour AbletonSet's sample-pitch lookup
        // depends on, so it is pinned here rather than left to the note ordering alone.
        assertEquals(100, analysis.dominantPitchAtSeconds(0.75)?.velocity)

        // At 2.5 s only the unterminated channel-1 note is live.
        assertEquals(67, analysis.dominantPitchAtSeconds(2.5)?.midiPitch)

        // Past the end of the file, nothing.
        assertTrue(analysis.dominantPitchAtSeconds(9.0) == null)

        dir.deleteRecursively()
    }

    private fun createTempDir(): File =
        java.nio.file.Files.createTempDirectory("afield-midi-characterization").toFile()

    /**
     * A two-track, format-1 Standard MIDI File at 480 PPQ, assembled byte by byte.
     *
     * Track 0 (conductor): 120 BPM at tick 0, 60 BPM at tick 960.
     * Track 1 (notes), with running status on the two middle-C note-offs:
     *   tick 0     NOTE_ON  ch0 60 vel100
     *   tick 480   NOTE_ON  ch0 60 vel80    (overlaps the note above)
     *   tick 960   NOTE_ON  ch0 60 vel0     (running status; closes the tick-0 note, FIFO)
     *   tick 1440  NOTE_ON  ch0 60 vel0     (running status; closes the tick-480 note)
     *   tick 1440  NOTE_ON  ch1 67 vel90    (never closed)
     *   tick 1920  end of track
     */
    private fun foreignSmfBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray(Charsets.US_ASCII))
        out.writeInt32(6)
        out.writeInt16(1)   // format 1
        out.writeInt16(2)   // two tracks
        out.writeInt16(480) // division: ticks per quarter note

        val conductor = ByteArrayOutputStream().apply {
            writeVlq(0); write(0xFF); write(0x51); write(3)
            write(0x07); write(0xA1); write(0x20)         // 500000 us/quarter == 120 BPM
            writeVlq(960); write(0xFF); write(0x51); write(3)
            write(0x0F); write(0x42); write(0x40)         // 1000000 us/quarter == 60 BPM
            writeVlq(0); write(0xFF); write(0x2F); write(0)
        }
        out.writeChunk("MTrk", conductor.toByteArray())

        val notes = ByteArrayOutputStream().apply {
            writeVlq(0); write(0x90); write(60); write(100)
            writeVlq(480); write(0x90); write(60); write(80)
            writeVlq(480); write(60); write(0)             // running status
            writeVlq(480); write(60); write(0)             // running status
            writeVlq(0); write(0x91); write(67); write(90)
            writeVlq(480); write(0xFF); write(0x2F); write(0)
        }
        out.writeChunk("MTrk", notes.toByteArray())

        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeChunk(id: String, body: ByteArray) {
        write(id.toByteArray(Charsets.US_ASCII))
        writeInt32(body.size)
        write(body)
    }

    private fun ByteArrayOutputStream.writeInt32(value: Int) {
        write((value ushr 24) and 0xFF); write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF); write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeInt16(value: Int) {
        write((value ushr 8) and 0xFF); write(value and 0xFF)
    }

    /** MIDI variable-length quantity: seven bits per byte, high bit set on all but the last. */
    private fun ByteArrayOutputStream.writeVlq(value: Int) {
        val stack = ArrayDeque<Int>()
        var v = value
        stack.addLast(v and 0x7F)
        v = v ushr 7
        while (v > 0) {
            stack.addLast((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        while (stack.isNotEmpty()) write(stack.removeLast())
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
