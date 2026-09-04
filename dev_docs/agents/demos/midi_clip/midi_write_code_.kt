import afield.four.synth.*
import java.io.File

// 1 - Write a MIDI file a DAW will open.
//
// The pattern is ordinary mini-notation. `toMidiClip` renders it over two cycles, and
// one cycle is one bar of 4/4 by default, so this is eight bars-worth of quarter notes
// at 104 BPM. Tidal's octave convention applies: c5 is middle C, MIDI 60.
val melody = pat("c5 e5 g5 b5 c6 b5 g5 e5")
    .with(gain(0.8))
    .with(sustain(0.85))

val bass = pat("c3 c3 g3 g3")
    .with(gain(0.95))

val clip = MidiClip(
    tracks = listOf(
        MidiTrackData(
            notes = melody.toMidiClip(cycles = 2, beatsPerCycle = 8).notes,
            name = "lead",
            program = GeneralMidi.ELECTRIC_PIANO
        ),
        MidiTrackData(
            notes = bass.toMidiClip(cycles = 2, beatsPerCycle = 8, channel = 1).notes,
            name = "bass",
            program = GeneralMidi.ACOUSTIC_BASS
        )
    ),
    tempoMap = listOf(0.0 to 104.0),
    name = "field-demo",
    timeSignature = 4 to 4
)

val out = File(System.getProperty("java.io.tmpdir"), "field-midi-demo.mid")
clip.writeTo(out)

// The roll is FLines - real geometry, not a picture of geometry. Notes are coloured by
// velocity, and there is a rule at every C so the eye can find an octave.
showMidiRoll(clip)

markdown(
    "main",
    """
    ### Wrote ${clip.notes.size} notes to a Standard MIDI File

    | | |
    |---|---|
    | file | `${out.absolutePath}` |
    | bytes | ${out.length()} |
    | tracks | ${clip.tracks.size} (${clip.tracks.joinToString { it.name ?: "?" }}) |
    | tempo | ${clip.bpmAt(0.0)} BPM |
    | length | ${"%.2f".format(clip.durationSeconds)}s / ${clip.durationBeats} beats |

    Format 1, with a real conductor track. Open it in any DAW, or run box 2.
    """.trimIndent()
)
