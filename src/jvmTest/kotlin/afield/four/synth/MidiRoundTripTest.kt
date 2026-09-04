package afield.four.synth

import java.io.File
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Holds down the Standard MIDI File surface in `Midi.kt` and `MidiPat.kt`: a clip written by Field
 * and read back must be the same clip, pitch for pitch, velocity for velocity, tick for tick.
 *
 * Every count assertion here is **floored at a number** rather than compared for inequality, which
 * is the point. A writer that silently emits an empty conductor track, or an importer that drops
 * every note, would satisfy "the round trip did not differ" trivially — both directions would be
 * empty. So each test states a minimum note count and, where relevant, a minimum tempo-event count,
 * and several assert their own premise first so an environment change fails loudly instead of
 * turning the comparison into a tautology over two empty lists.
 *
 * Files are written to a temp directory and deleted in [cleanUp]; nothing here writes into the
 * repository.
 */
class MidiRoundTripTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("afield-midi-roundtrip").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    @Test
    fun `a written clip reads back with identical pitch velocity channel and tick`() {
        val notes = listOf(
            MidiNote(pitch = 60, startBeats = 0.0, durationBeats = 1.0, velocity = 100, channel = 0),
            MidiNote(pitch = 64, startBeats = 1.0, durationBeats = 0.5, velocity = 80, channel = 0),
            MidiNote(pitch = 67, startBeats = 1.5, durationBeats = 0.5, velocity = 40, channel = 0),
            MidiNote(pitch = 72, startBeats = 2.0, durationBeats = 2.0, velocity = 127, channel = 3),
            // Two notes at the same tick on the same channel: a chord. The reader must not merge
            // or reorder these away.
            MidiNote(pitch = 48, startBeats = 4.0, durationBeats = 1.0, velocity = 55, channel = 0),
            MidiNote(pitch = 55, startBeats = 4.0, durationBeats = 1.0, velocity = 55, channel = 0)
        )
        val original = MidiClip.of(notes, bpm = 132.0, name = "roundtrip")

        val file = original.writeTo(File(temp, "roundtrip.mid"))
        assertTrue(file.length() > 0, "writer produced an empty file")

        val read = readMidiClip(file)

        // Premise floors: if either side is empty the comparisons below mean nothing.
        assertTrue(read.notes.size >= 6, "expected at least 6 notes back, got ${read.notes.size}")
        assertEquals(notes.size, read.notes.size, "round trip changed the note count")
        assertEquals(480, read.ppq, "PPQ must survive the round trip")

        // 132 BPM is not exactly representable in whole microseconds-per-quarter (454545 us gives
        // 132.000066 BPM), so the tempo is compared with a tolerance that is tighter than any
        // musically meaningful difference but loose enough for the integer encoding.
        assertTrue(read.tempoMap.isNotEmpty(), "tempo map came back empty")
        assertTrue(
            abs(read.bpmAt(0.0) - 132.0) < 0.01,
            "tempo did not survive: wanted 132.0, got ${read.bpmAt(0.0)}"
        )

        val sortKey = compareBy<MidiNote>({ it.startBeats }, { it.pitch })
        val before = notes.sortedWith(sortKey)
        val after = read.notes.sortedWith(sortKey)
        before.zip(after).forEachIndexed { index, (a, b) ->
            assertEquals(a.pitch, b.pitch, "note $index pitch")
            assertEquals(a.velocity, b.velocity, "note $index velocity")
            assertEquals(a.channel, b.channel, "note $index channel")
            assertEquals(a.startBeats, b.startBeats, 1e-9, "note $index start")
            assertEquals(a.durationBeats, b.durationBeats, 1e-9, "note $index duration")
        }
    }

    @Test
    fun `a multi-track clip keeps its track split and names`() {
        val clip = MidiClip(
            tracks = listOf(
                MidiTrackData(
                    notes = (0..7).map { MidiNote(60 + it, it.toDouble(), 0.9, 90, 0) },
                    name = "lead",
                    program = GeneralMidi.SQUARE_LEAD
                ),
                MidiTrackData(
                    notes = (0..3).map { MidiNote(36, it * 2.0, 1.5, 110, 1) },
                    name = "bass",
                    program = GeneralMidi.ACOUSTIC_BASS
                )
            ),
            tempoMap = listOf(0.0 to 100.0),
            name = "two parts"
        )

        val read = readMidiClip(clip.writeTo(File(temp, "multi.mid")))

        assertTrue(read.tracks.size >= 2, "expected at least 2 note tracks back, got ${read.tracks.size}")
        assertEquals(12, read.notes.size, "expected 8 + 4 notes")
        assertEquals(listOf("lead", "bass"), read.tracks.map { it.name })
        assertEquals(8, read.tracks[0].notes.size)
        assertEquals(4, read.tracks[1].notes.size)
        // Channels distinguish the parts and must not be flattened onto channel 0.
        assertTrue(read.tracks[1].notes.all { it.channel == 1 }, "bass part lost its channel")
    }

    @Test
    fun `a tempo map with three changes survives, and its event count is floored`() {
        val clip = MidiClip(
            tracks = listOf(MidiTrackData(notes = (0..15).map { MidiNote(60, it.toDouble(), 0.5, 90) })),
            tempoMap = listOf(0.0 to 60.0, 4.0 to 120.0, 8.0 to 90.0),
            name = "accelerando"
        )

        val read = readMidiClip(clip.writeTo(File(temp, "tempo.mid")))

        // Floored at 3: the reader prepends the MIDI default (120 BPM at tick 0) to whatever the
        // file states, and this file states 3, so 3 is the floor a writer that dropped its tempo
        // track would fail. Asserting "> 0" would pass on a file with no tempo events at all.
        assertTrue(
            read.tempoMap.size >= 3,
            "expected at least 3 tempo events, got ${read.tempoMap.size}: ${read.tempoMap}"
        )
        assertEquals(60.0, read.bpmAt(0.0), 0.01)
        assertEquals(60.0, read.bpmAt(3.9), 0.01)
        assertEquals(120.0, read.bpmAt(4.0), 0.01)
        assertEquals(90.0, read.bpmAt(12.0), 0.01)

        // The wall-clock length has to reflect the tempo map, not a single tempo: 4 beats at 60 BPM
        // (4.0 s) plus 4 at 120 (2.0 s) plus 8 at 90 (5.333 s) reaches beat 16 at 11.333 s.
        assertEquals(4.0 + 2.0 + 8.0 * 60.0 / 90.0, read.beatsToSeconds(16.0), 1e-3)
    }

    @Test
    fun `two hundred random notes survive a round trip exactly`() {
        // A generated case, so the round trip is exercised over a wider spread of ticks, pitches,
        // velocities and channels than a hand-written fixture reaches. Seeded, so a failure is
        // reproducible rather than a once-a-week mystery.
        val random = Random(20260904)
        val notes = (0 until 200).map {
            val startSixteenths = random.nextInt(0, 256)
            val lengthSixteenths = random.nextInt(1, 9)
            MidiNote(
                pitch = random.nextInt(21, 109),
                startBeats = startSixteenths / 4.0,
                durationBeats = lengthSixteenths / 4.0,
                velocity = random.nextInt(1, 128),
                channel = random.nextInt(0, 16)
            )
        }

        val clip = MidiClip.of(notes, bpm = 120.0, name = "generated")
        val read = readMidiClip(clip.writeTo(File(temp, "generated.mid")))

        assertTrue(read.notes.size >= 200, "expected at least 200 notes back, got ${read.notes.size}")

        // Compare as multisets keyed on every field, because two notes can share a start tick and
        // the reader is not obliged to preserve their relative order.
        fun key(n: MidiNote) = listOf(n.pitch, n.velocity, n.channel, (n.startBeats * 480).toLong(), (n.durationBeats * 480).toLong())
        assertEquals(
            notes.map(::key).sortedBy { it.joinToString(",") },
            read.notes.map(::key).sortedBy { it.joinToString(",") },
            "a generated note did not survive the round trip"
        )
    }

    @Test
    fun `a pattern exports to MIDI and comes back as a pattern`() {
        // `pat` is the mini-notation parser; c5 is MIDI 60 in Tidal's octave convention, which is
        // asserted here rather than assumed because getting it wrong would shift everything an
        // octave and still round-trip cleanly.
        assertEquals(60, patNoteNumber("c5"), "Tidal octave convention: c5 must be MIDI 60")

        val pattern = pat("c5 e5 g5 c6")
        val clip = pattern.toMidiClip(cycles = 2, bpm = 120.0, name = "arpeggio")

        assertTrue(clip.notes.size >= 8, "expected at least 8 notes over 2 cycles, got ${clip.notes.size}")
        assertEquals(listOf(60, 64, 67, 72, 60, 64, 67, 72), clip.notes.map { it.pitch })
        // Four events per cycle at 4 beats per cycle is one beat each.
        assertEquals(1.0, clip.notes[0].durationBeats, 1e-9)
        assertEquals(4.0, clip.notes[4].startBeats, 1e-9, "second cycle must start at beat 4")

        val file = clip.writeTo(File(temp, "pattern.mid"))
        val backAsPattern = readMidiPat(file)
        val backEvents = backAsPattern.events(PatArc(PatQ.ZERO, PatQ.of(2)))

        assertTrue(backEvents.size >= 8, "expected at least 8 events back, got ${backEvents.size}")
        assertEquals(
            listOf(60, 64, 67, 72, 60, 64, 67, 72),
            backEvents.sortedBy { it.arc.start }.map { it.value.toInt() }
        )
        // The annotations the pattern stack actually reads must be present, or the imported
        // pattern is unusable by anything downstream.
        val first = backEvents.minByOrNull { it.arc.start }
        assertNotNull(first)
        assertEquals("60", first.annotations["note"])
        assertNotNull(first.annotations["gain"], "gain is the key the rest of the stack reads")
        assertNotNull(first.annotations["velocity"])
    }

    @Test
    fun `pattern export honours gain as velocity and sustain as length`() {
        val pattern = pat("c5 e5").with(gain(0.5)).with(sustain(0.25))
        val clip = pattern.toMidiClip(cycles = 1, bpm = 120.0)

        assertTrue(clip.notes.size >= 2, "expected at least 2 notes, got ${clip.notes.size}")
        // gain 0.5 -> round(0.5 * 127) == 64.
        assertTrue(
            clip.notes.all { it.velocity == 64 },
            "gain 0.5 should map to velocity 64, got ${clip.notes.map { it.velocity }}"
        )
        // Two events per cycle at 4 beats per cycle is 2 beats each; sustain 0.25 makes them 0.5.
        assertTrue(
            clip.notes.all { abs(it.durationBeats - 0.5) < 1e-9 },
            "sustain 0.25 of a 2-beat arc should be 0.5 beats, got ${clip.notes.map { it.durationBeats }}"
        )
    }

    @Test
    fun `quantize pulls notes onto the grid and swing displaces the odd positions`() {
        val sloppy = listOf(
            MidiNote(60, 0.03, 0.5),
            MidiNote(62, 0.47, 0.5),
            MidiNote(64, 1.06, 0.5),
            MidiNote(65, 1.52, 0.5)
        )

        val errorBefore = gridError(sloppy, 0.5)
        assertTrue(errorBefore > 0.02, "premise: the fixture must actually be off the grid, was $errorBefore")

        val tight = quantize(sloppy, gridBeats = 0.5, strength = 1.0)
        assertTrue(
            gridError(tight, 0.5) < 1e-9,
            "full-strength quantize should land exactly on the grid, error was ${gridError(tight, 0.5)}"
        )
        assertEquals(listOf(0.0, 0.5, 1.0, 1.5), tight.map { it.startBeats })

        // Half strength should move the notes halfway, not all the way: a real "quantize amount".
        val half = quantize(sloppy, gridBeats = 0.5, strength = 0.5)
        assertEquals(0.015, half[0].startBeats, 1e-9)
        assertTrue(
            gridError(half, 0.5) < errorBefore,
            "half-strength quantize must reduce grid error"
        )
        assertTrue(
            gridError(half, 0.5) > 1e-9,
            "half-strength quantize must not fully snap"
        )

        // Swing delays the odd grid positions only: positions 1 and 3 move, 0 and 2 do not.
        val swung = quantize(sloppy, gridBeats = 0.5, strength = 1.0, swing = 1.0 / 3.0)
        assertEquals(0.0, swung[0].startBeats, 1e-9, "even position must not swing")
        assertEquals(0.5 + 0.5 / 3.0, swung[1].startBeats, 1e-9, "odd position must swing")
        assertEquals(1.0, swung[2].startBeats, 1e-9, "even position must not swing")
        assertEquals(1.5 + 0.5 / 3.0, swung[3].startBeats, 1e-9, "odd position must swing")
    }

    @Test
    fun `a foreign file with running status and a tempo change imports as a clip`() {
        // The same hand-assembled bytes AbletonMidiReadCharacterizationTest uses, read through the
        // new public entry point rather than through AbletonSet. This is the "survives a file this
        // code did not write" case: running status, zero-velocity note-offs, an unterminated note.
        val file = File(temp, "foreign.mid")
        file.writeBytes(ForeignSmf.bytes())

        val clip = readMidiClip(file)

        assertTrue(clip.notes.size >= 3, "expected at least 3 notes from the foreign file, got ${clip.notes.size}")
        assertEquals(480, clip.ppq)
        assertTrue(clip.tempoMap.size >= 2, "expected the mid-file tempo change, got ${clip.tempoMap}")
        assertEquals(setOf(60, 67), clip.notes.map { it.pitch }.toSet())
        // The unterminated channel-1 note was closed at the file's end, at beat 4 of 4 (tick 1920).
        val open = clip.notes.first { it.pitch == 67 }
        assertEquals(1, open.channel)
        assertEquals(3.0, open.startBeats, 1e-9)
        assertEquals(1.0, open.durationBeats, 1e-9)
    }

    @Test
    fun `an out-of-range note is refused at construction rather than clamped on write`() {
        // `toSequence` coerces, but silently, so an author whose arithmetic produced 170 would get
        // 127 in the file and never learn the arithmetic was wrong. That is exactly the mistake this
        // file's own piano-roll figure generator made. Constructing the note is where it must fail.
        assertFailsWith<IllegalArgumentException> { MidiNote(60, 0.0, 1.0, velocity = 170) }
        assertFailsWith<IllegalArgumentException> { MidiNote(60, 0.0, 1.0, velocity = -1) }
        assertFailsWith<IllegalArgumentException> { MidiNote(pitch = 128, startBeats = 0.0, durationBeats = 1.0) }
        assertFailsWith<IllegalArgumentException> { MidiNote(pitch = -1, startBeats = 0.0, durationBeats = 1.0) }
        assertFailsWith<IllegalArgumentException> { MidiNote(60, 0.0, 1.0, channel = 16) }

        // The legal extremes must still be constructible, or the guard is too tight.
        MidiNote(0, 0.0, 1.0, velocity = 0, channel = 0)
        MidiNote(127, 0.0, 1.0, velocity = 127, channel = 15)

        // And the message has to name the offending value, since that value is the clue.
        val message = assertFailsWith<IllegalArgumentException> {
            MidiNote(60, 0.0, 1.0, velocity = 170)
        }.message
        assertNotNull(message)
        assertTrue("170" in message, "the error should quote the bad value, was: $message")
    }

    @Test
    fun `a piano roll draws one FLine per note plus a grid`() {
        val clip = MidiClip.of((0..11).map { MidiNote(60 + it, it * 0.5, 0.45, 60 + it * 5) }, bpm = 120.0)
        val lines = clip.toFLines()

        val noteLines = lines.filter { it.properties.containsKey("midi.pitch") }
        assertEquals(12, noteLines.size, "expected exactly one FLine per note")
        assertTrue(lines.size > noteLines.size, "expected grid lines in addition to the notes")

        // Field's y axis grows downward, so a higher pitch must produce a smaller y. Getting this
        // backwards yields an upside-down roll that still looks plausible, so it is asserted.
        val byPitch = noteLines.sortedBy { it.properties["midi.pitch"] as Int }
        val lowestY = byPitch.first().nodes.first().to.y
        val highestY = byPitch.last().nodes.first().to.y
        assertTrue(
            highestY < lowestY,
            "higher pitch must have smaller y (y grows down); got pitch ${byPitch.last().properties["midi.pitch"]} at y=$highestY and pitch ${byPitch.first().properties["midi.pitch"]} at y=$lowestY"
        )

        // Every note line is a closed filled rectangle, so it renders as a bar and not a hairline.
        assertTrue(noteLines.all { it.nodes.size >= 4 }, "a note rectangle needs at least 4 nodes")
    }

    @Test
    fun `MIDI device enumeration reports what is actually present`() {
        val all = MidiDevices.list()
        // The JDK always ships Gervill and the Real Time Sequencer, so an empty list means
        // something is wrong with the environment rather than with the code.
        assertTrue(all.isNotEmpty(), "MidiSystem reported no devices at all")
        assertTrue(
            MidiDevices.outputs().isNotEmpty(),
            "expected at least one MIDI output (Gervill). Saw:\n${MidiDevices.describe()}"
        )
        // Every handle must classify as one direction or the other, or `list()` has a bug.
        assertTrue(
            all.all { it.canReceive || it.canTransmit },
            "a device classified as neither input nor output: ${all.filterNot { it.canReceive || it.canTransmit }}"
        )
    }

    @Test
    fun `a hardware MIDI port round-trips a note, or the test skips with a reason`() {
        val out = MidiDevices.hardware().firstOrNull { it.canReceive }
        val inp = MidiDevices.hardware().firstOrNull { it.canTransmit }

        if (out == null || inp == null) {
            // A clean skip with a stated reason, not a failure and not a silent pass. At 3am on a
            // machine with nothing plugged in this is the expected path.
            println(
                "SKIP: no bidirectional hardware MIDI port on this machine, so a live loopback " +
                    "cannot be tested. Devices present:\n${MidiDevices.describe()}"
            )
            return
        }

        println("live MIDI loopback: sending to \"${out.name}\", listening on \"${inp.name}\"")

        val received = java.util.concurrent.LinkedBlockingQueue<Triple<Int, Int, Int>>()
        val input = MidiDevices.openInput(inp)
        input.onMessage { message, _ ->
            if (message is javax.sound.midi.ShortMessage && message.command == javax.sound.midi.ShortMessage.NOTE_ON) {
                received.add(Triple(message.channel, message.data1, message.data2))
            }
        }

        try {
            MidiDevices.openOutput(out).use { it.noteOn(pitch = 64, velocity = 100, channel = 3) }
            val got = received.poll(5, java.util.concurrent.TimeUnit.SECONDS)
            assertNotNull(got, "sent a note to ${out.name} but nothing arrived on ${inp.name} within 5s")
            assertEquals(3, got.first, "channel must survive the wire")
            assertEquals(64, got.second, "pitch must survive the wire")
            assertEquals(100, got.third, "velocity must survive the wire")
            assertTrue(input.messageCount >= 1, "messageCount should have counted the note")
        } finally {
            // Note: this does not stop the non-daemon `Java Sound MidiInDevice Thread`. That is
            // documented on MidiInput and is why this check is a jvmTest and not a jvm-main smoke.
            input.close()
        }
    }

    @Test
    fun `offline WAV rendering either produces audible audio or states why it cannot`() {
        val reason = midiRenderUnavailableReason()
        if (reason != null) {
            println("SKIP: offline MIDI rendering unavailable in this JVM. $reason")
            return
        }

        // Two beats at 120 BPM is 1.0 s of notes, plus a 0.5 s tail for the release: 1.5 s total.
        val clip = MidiClip.of(
            listOf(
                MidiNote(60, 0.0, 2.0, 110),
                MidiNote(64, 0.0, 2.0, 110),
                MidiNote(67, 0.0, 2.0, 110)
            ),
            bpm = 120.0,
            name = "chord"
        )
        val result = clip.renderWav(File(temp, "chord.wav"), tailSeconds = 0.5)

        assertTrue(result.file.exists(), "no WAV was written")
        // File size cannot distinguish silence from audio — a silent WAV is exactly as large as a
        // loud one — so the assertion is on measured amplitude.
        //
        // The floors below sit well under measurements taken on this machine: this C-major triad at
        // velocity 110 through Gervill's "Emergency GM sound set" renders at peak 0.600 and
        // rms 0.0787 over 1.50 s. A floor of 0.05/0.005 is about a twelfth and a fifteenth of
        // those — loose enough to survive a different soundbank, which is the point, since the
        // "Emergency" set is itself a fallback and a machine with a real one will measure
        // differently, but tight enough that silence or a stuck note fails.
        assertTrue(
            result.peakAmplitude > 0.05,
            "rendered WAV is silent or near-silent: peak ${result.peakAmplitude}, rms ${result.rmsAmplitude}, soundbank ${result.soundbankName}"
        )
        assertTrue(result.rmsAmplitude > 0.005, "rendered WAV has no sustained energy: rms ${result.rmsAmplitude}")
        assertTrue(result.seconds > 1.4, "expected about 1.5s of audio, got ${result.seconds}")
        println(
            "rendered ${"%.2f".format(result.seconds)}s, peak ${"%.3f".format(result.peakAmplitude)}, " +
                "rms ${"%.4f".format(result.rmsAmplitude)}, soundbank ${result.soundbankName}"
        )
    }
}

/**
 * Hand-assembled Standard MIDI File bytes, shared by the tests that need a file Field's own writer
 * did not produce. See `AbletonMidiReadCharacterizationTest` for the layout in full.
 */
internal object ForeignSmf {
    fun bytes(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("MThd".toByteArray(Charsets.US_ASCII))
        out.int32(6); out.int16(1); out.int16(2); out.int16(480)

        val conductor = java.io.ByteArrayOutputStream().apply {
            vlq(0); write(0xFF); write(0x51); write(3); write(0x07); write(0xA1); write(0x20)
            vlq(960); write(0xFF); write(0x51); write(3); write(0x0F); write(0x42); write(0x40)
            vlq(0); write(0xFF); write(0x2F); write(0)
        }
        out.chunk("MTrk", conductor.toByteArray())

        val notes = java.io.ByteArrayOutputStream().apply {
            vlq(0); write(0x90); write(60); write(100)
            vlq(480); write(0x90); write(60); write(80)
            vlq(480); write(60); write(0)
            vlq(480); write(60); write(0)
            vlq(0); write(0x91); write(67); write(90)
            vlq(480); write(0xFF); write(0x2F); write(0)
        }
        out.chunk("MTrk", notes.toByteArray())
        return out.toByteArray()
    }

    private fun java.io.ByteArrayOutputStream.chunk(id: String, body: ByteArray) {
        write(id.toByteArray(Charsets.US_ASCII)); int32(body.size); write(body)
    }

    private fun java.io.ByteArrayOutputStream.int32(v: Int) {
        write((v ushr 24) and 0xFF); write((v ushr 16) and 0xFF); write((v ushr 8) and 0xFF); write(v and 0xFF)
    }

    private fun java.io.ByteArrayOutputStream.int16(v: Int) {
        write((v ushr 8) and 0xFF); write(v and 0xFF)
    }

    private fun java.io.ByteArrayOutputStream.vlq(value: Int) {
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
}
