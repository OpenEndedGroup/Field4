@file:afield.CompletionCategory("Audio")
package afield.four.synth

import java.io.File
import java.nio.file.Files

/*
FIELD_TEST
id: synth.midi-file.smoke
runner: jvm-main
entrypoint: afield.four.synth.MidiSmokeTest
tags: [smoke, synth, midi, audio]
timeoutSec: 60
expect:
  exitCode: 0
  stdoutContains:
    - "Midi smoke tests passed"
*/
/**
 * Smoke cover for the Standard MIDI File surface: writes a clip, reads it back, exports a pattern,
 * imports one, draws a roll, enumerates devices and opens a real output port.
 *
 * This deliberately does **not** open a MIDI *input*. Opening a CoreMIDI input spawns a non-daemon
 * `Java Sound MidiInDevice Thread` that survives both `Transmitter.close()` and
 * `MidiDevice.close()`, so a JVM that has opened one never exits naturally — and a `jvm-main` smoke
 * test is required to exit naturally. The live input loopback is covered by
 * `MidiRoundTripTest` instead, where Gradle tears the test JVM down regardless. See [MidiInput].
 *
 * Everything is written to a temp directory that is deleted on the way out, so a run leaves no
 * files in the repository.
 */
object MidiSmokeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests(args.firstOrNull()?.let(::File))) { "Midi smoke tests failed" }
    }

    fun runTests(outputDirectory: File? = null): Boolean {
        val dir = outputDirectory ?: Files.createTempDirectory("afield-midi-smoke").toFile()
        return try {
            testWriteAndRead(dir)
            testPatternRoundTrip(dir)
            testTranscriptionBridgeShape()
            testPianoRollGeometry()
            reportDevices()
            testOutputPort()
            reportRendering(dir)
            // Only when an output directory was named on the command line, so a CI run writes
            // nothing into the repository. This is the committed figure's generator.
            if (outputDirectory != null) writeRollFigure(outputDirectory)
            println("Midi smoke tests passed")
            true
        } catch (t: Throwable) {
            t.printStackTrace()
            false
        } finally {
            if (outputDirectory == null) dir.deleteRecursively()
        }
    }

    private fun testWriteAndRead(dir: File) {
        val notes = listOf(
            MidiNote(60, 0.0, 1.0, 100),
            MidiNote(64, 1.0, 1.0, 90),
            MidiNote(67, 2.0, 2.0, 120, channel = 2)
        )
        val clip = MidiClip.of(notes, bpm = 96.0, name = "smoke")
        val file = clip.writeTo(File(dir, "smoke.mid"))

        require(file.exists() && file.length() > 0) { "writeTo produced no file" }

        val read = readMidiClip(file)
        require(read.notes.size == 3) { "expected 3 notes back, got ${read.notes.size}" }
        require(read.ppq == 480) { "expected PPQ 480, got ${read.ppq}" }
        require(kotlin.math.abs(read.bpmAt(0.0) - 96.0) < 0.01) { "expected 96 BPM, got ${read.bpmAt(0.0)}" }
        require(read.notes.map { it.pitch } == listOf(60, 64, 67)) { "pitches changed: ${read.notes.map { it.pitch }}" }
        require(read.notes.map { it.velocity } == listOf(100, 90, 120)) { "velocities changed: ${read.notes.map { it.velocity }}" }
        require(read.notes[2].channel == 2) { "channel changed: ${read.notes[2].channel}" }
        println("write/read: 3 notes, PPQ ${read.ppq}, ${read.bpmAt(0.0)} BPM, ${file.length()} bytes")
    }

    private fun testPatternRoundTrip(dir: File) {
        require(patNoteNumber("c5") == 60) { "Tidal octave convention broke: c5 should be 60" }

        val clip = pat("c5 e5 g5 c6").toMidiClip(cycles = 2, bpm = 120.0, name = "arp")
        require(clip.notes.size == 8) { "expected 8 notes over 2 cycles, got ${clip.notes.size}" }

        val file = clip.writeTo(File(dir, "pattern.mid"))
        val events = readMidiPat(file).events(PatArc(PatQ.ZERO, PatQ.of(2)))
        require(events.size == 8) { "expected 8 events back, got ${events.size}" }

        val pitches = events.sortedBy { it.arc.start }.map { it.value.toInt() }
        require(pitches == listOf(60, 64, 67, 72, 60, 64, 67, 72)) { "pattern round trip changed pitches: $pitches" }
        println("pattern round trip: 8 events, pitches $pitches")
    }

    private fun testTranscriptionBridgeShape() {
        // Exercises the quantize/swing layer rather than running a full transcription, which needs
        // audio this test does not have. The transcription bridge itself is type-checked by the
        // compiler and covered where audio is available.
        val sloppy = listOf(MidiNote(60, 0.03, 0.5), MidiNote(62, 0.47, 0.5), MidiNote(64, 1.06, 0.5))
        val before = gridError(sloppy, 0.5)
        require(before > 0.02) { "fixture is already on the grid, error $before" }

        val tight = quantize(sloppy, 0.5)
        val after = gridError(tight, 0.5)
        require(after < 1e-9) { "quantize did not land on the grid, error $after" }

        val swung = quantize(sloppy, 0.5, swing = 1.0 / 3.0)
        require(kotlin.math.abs(swung[1].startBeats - (0.5 + 0.5 / 3.0)) < 1e-9) {
            "swing did not displace the odd grid position: ${swung[1].startBeats}"
        }
        println("quantize: grid error ${"%.4f".format(before)} -> ${"%.4f".format(after)}, swing displaces odd steps")
    }

    private fun testPianoRollGeometry() {
        val clip = MidiClip.of((0..11).map { MidiNote(60 + it, it * 0.5, 0.45, 60 + it * 5) }, bpm = 120.0)
        val lines = clip.toFLines()
        val noteLines = lines.filter { it.properties.containsKey("midi.pitch") }

        require(noteLines.size == 12) { "expected 12 note FLines, got ${noteLines.size}" }
        require(lines.size > noteLines.size) { "expected grid lines as well as notes" }

        // y grows down, so the highest pitch must sit at the smallest y.
        val byPitch = noteLines.sortedBy { it.properties["midi.pitch"] as Int }
        val yLow = byPitch.first().nodes.first().to.y
        val yHigh = byPitch.last().nodes.first().to.y
        require(yHigh < yLow) { "piano roll is upside down: pitch 71 at y=$yHigh, pitch 60 at y=$yLow" }
        println("piano roll: ${noteLines.size} note lines, ${lines.size - noteLines.size} grid lines, y grows down")
    }

    private fun reportDevices() {
        val all = MidiDevices.list()
        require(all.isNotEmpty()) { "MidiSystem reported no devices at all" }
        println("MIDI devices (${all.size}):")
        println(MidiDevices.describe())
        val hardware = MidiDevices.hardware()
        println(
            if (hardware.isEmpty()) "  (no hardware ports; only the JDK's software synth and sequencer)"
            else "  hardware ports: ${hardware.joinToString { it.name }}"
        )
    }

    private fun testOutputPort() {
        val out = MidiDevices.outputs().firstOrNull()
        if (out == null) {
            println("SKIP: no MIDI output at all, so an output port cannot be opened")
            return
        }
        // Opening an output and closing it is safe for a jvm-main smoke: an output device does not
        // leave a non-daemon thread behind, which I verified by listing threads after close.
        MidiDevices.openOutput(out).use { output ->
            output.programChange(GeneralMidi.ACOUSTIC_GRAND_PIANO)
            output.noteOn(60, 80)
            output.noteOff(60)
        }
        println("output port: opened \"${out.name}\" (${out.implementation}), sent a note, closed cleanly")
    }

    /**
     * Write the committed piano-roll figure's HTML, through the same `linesToHTML` that
     * `Box.lines` uses — so the figure is Field's real FLine renderer output and not a
     * second drawing of the same data.
     *
     * The clip is deliberately not uniform: a rising melody over a slower bass line, with
     * velocities that climb, so that a roll drawn upside down, with x and y swapped, or with
     * velocity colouring broken is visibly wrong rather than plausibly wrong.
     */
    private fun writeRollFigure(dir: File) {
        val melody = pat("c5 e5 g5 b5 c6 b5 g5 e5").with(sustain(0.8))
        val bass = pat("c3 c3 g3 g3")

        val clip = MidiClip(
            tracks = listOf(
                MidiTrackData(
                    notes = melody.toMidiClip(cycles = 2, beatsPerCycle = 8).notes
                        .mapIndexed { i, n -> n.copy(velocity = 48 + i * 5) },
                    name = "lead"
                ),
                MidiTrackData(
                    notes = bass.toMidiClip(cycles = 2, beatsPerCycle = 8, channel = 1).notes,
                    name = "bass"
                )
            ),
            tempoMap = listOf(0.0 to 104.0),
            name = "figure"
        )

        val lines = clip.toFLines()
        val html = afield.graphics.linesToHTML(lines, w = 900.0, h = 320.0, fitToSize = true)
        dir.mkdirs()
        val out = File(dir, "piano_roll.html")
        out.writeText(html)

        // Print the geometry the figure claims, so the PNG can be checked against numbers rather
        // than against an impression.
        val noteLines = lines.filter { it.properties.containsKey("midi.pitch") }
        println(
            "roll figure: ${out.absolutePath} (${out.length()} bytes), " +
                "${noteLines.size} notes, pitch ${clip.notes.minOf { it.pitch }}..${clip.notes.maxOf { it.pitch }}, " +
                "beats 0..${clip.durationBeats}, velocity ${clip.notes.minOf { it.velocity }}..${clip.notes.maxOf { it.velocity }}"
        )
    }

    private fun reportRendering(dir: File) {
        val reason = midiRenderUnavailableReason()
        if (reason != null) {
            println("offline WAV render: NOT VERIFIED in this JVM. $reason")
            return
        }
        val clip = MidiClip.of(listOf(MidiNote(60, 0.0, 2.0, 110), MidiNote(64, 0.0, 2.0, 110)), bpm = 120.0)
        val result = clip.renderWav(File(dir, "smoke.wav"), tailSeconds = 0.5)
        require(result.peakAmplitude > 0.05) {
            "rendered WAV is silent: peak ${result.peakAmplitude}, soundbank ${result.soundbankName}"
        }
        println(
            "offline WAV render: ${"%.2f".format(result.seconds)}s, peak ${"%.3f".format(result.peakAmplitude)}, " +
                "rms ${"%.4f".format(result.rmsAmplitude)}, soundbank ${result.soundbankName}"
        )
    }
}
