# Field Document: midi_clip

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. What can this machine see?

<!-- field-box id="midi_devices" index="0" -->

```edn [field-box id="midi_devices" role="properties"]
{"id" "midi_devices" "index" 0 "name" "What can this machine see?" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "What can this machine see?" "_tombstone" false "frame" #afield/rect [60.0 2040.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "midi_devices_code_.kt" "lines" 44 "characters" 1863 "sha256" "0e5faeb003999c9af705f8c6224faac55964fccdf0f1de6ad604b58d36a39b22"}]}
```

```kotlin [field-property box="midi_devices" name="code" path="midi_devices_code_.kt"]
import afield.four.synth.*

// 3 - What MIDI hardware can this machine see, and can Field drive it?
//
// `MidiDevices.list()` reports whatever the JVM sees. The JDK always contributes
// Gervill (a software synthesizer) and the Real Time Sequencer, so the list is never
// empty - which means "the list is non-empty" is not evidence of hardware. `hardware()`
// is the filter that answers that question.
//
// On macOS the IAC driver appears as two entries with the SAME name, one output and one
// input, so a name alone does not identify a device: check the direction.
val all = MidiDevices.list()
val hardware = MidiDevices.hardware()

val rows = all.joinToString("\n") { d ->
    val direction = listOfNotNull(
        if (d.canReceive) "Field can send" else null,
        if (d.canTransmit) "Field can listen" else null
    ).joinToString(" + ")
    "| `${d.name}` | ${d.vendor} | ${d.implementation} | $direction |"
}

markdown(
    "main",
    """
    ### ${all.size} MIDI devices

    | name | vendor | implementation | direction |
    |---|---|---|---|
    $rows

    **Hardware ports:** ${if (hardware.isEmpty()) "none - nothing is plugged in, and only the JDK's software synth and sequencer are present." else hardware.joinToString { it.name } + "."}

    To drive a device, `MidiDevices.openOutput(handle)` gives a `MidiOutput` with
    `noteOn`/`noteOff`/`controlChange`/`programChange` and a blocking `play(clip)`.
    `MidiDevices.openInput(handle)` gives a `MidiInput` whose `knob(cc)` reads a
    controller normalised to 0..1 - that is the shape for driving a box property from a
    knob on a controller.

    One caveat worth knowing before you open an input: doing so starts a non-daemon
    `Java Sound MidiInDevice Thread` that outlives `close()`, so a JVM that has opened a
    MIDI input will not exit on its own.
    """.trimIndent()
)
```

## 2. Hear it

<!-- field-box id="midi_audition" index="1" -->

```edn [field-box id="midi_audition" role="properties"]
{"id" "midi_audition" "index" 1 "name" "Hear it" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Hear it" "_tombstone" false "frame" #afield/rect [60.0 3040.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "midi_audition_code_.kt" "lines" 61 "characters" 2023 "sha256" "173085367b49c2fecbe26fc07a54f1b1ec3979d832a631cbb385fb0f6edaecf2"}]}
```

````kotlin [field-property box="midi_audition" name="code" path="midi_audition_code_.kt"]
import afield.four.synth.*
import java.io.File

// 4 - Hear it. Two routes, and this box tells you which one it took.
//
// Offline rendering pulls the JDK's Gervill synthesizer faster than real time, which
// needs `com.sun.media.sound.AudioSynthesizer.openStream`. `java.desktop` does not
// export that package, so it only works in a JVM launched with
// `--add-exports=java.desktop/com.sun.media.sound=ALL-UNNAMED`. Field's own JVM is not,
// so this box will normally report the live route instead - and say so, rather than
// pretending to have rendered something.
val clip = MidiClip.of(
    listOf(
        MidiNote(60, 0.0, 2.0, 100),
        MidiNote(64, 0.0, 2.0, 95),
        MidiNote(67, 0.0, 2.0, 105),
        MidiNote(72, 0.5, 1.5, 110)
    ),
    bpm = 120.0,
    name = "chord"
)

val reason = midiRenderUnavailableReason()

val report = if (reason == null) {
    val wav = File(System.getProperty("java.io.tmpdir"), "field-midi-demo.wav")
    val r = clip.renderWav(wav, tailSeconds = 1.0)
    """
    ### Rendered offline to a WAV

    | | |
    |---|---|
    | file | `${r.file.absolutePath}` |
    | length | ${"%.2f".format(r.seconds)}s |
    | **peak amplitude** | **${"%.3f".format(r.peakAmplitude)}** |
    | rms amplitude | ${"%.4f".format(r.rmsAmplitude)} |
    | soundbank | ${r.soundbankName} |

    Peak amplitude is the check that matters: a WAV of pure silence is exactly as large
    as a loud one, so file size proves nothing. ${if (r.isSilent) "**This one is silent.**" else "This one is not silent."}
    """.trimIndent()
} else {
    """
    ### Offline rendering is not available in this JVM

    $reason

    So nothing was rendered, and no claim is made about audio. Use the live route
    instead - this sends real MIDI to Gervill, the JDK's built-in synthesizer, and you
    should hear a chord:

    ```kotlin
    clip.playTo("Gervill")
    ```

    Or to any other port `MidiDevices.outputs()` lists in box 3.
    """.trimIndent()
}

showMidiRoll(clip)
markdown("main", report)
````

## 3. Write a MIDI file

<!-- field-box id="midi_write" index="2" -->

```edn [field-box id="midi_write" role="properties"]
{"id" "midi_write" "index" 2 "name" "Write a MIDI file" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Write a MIDI file" "_tombstone" false "frame" #afield/rect [60.0 40.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "midi_write_code_.kt" "lines" 56 "characters" 1787 "sha256" "50572d9f087af8245dea82d68bc335f23b25fae277b3445a6434992215a906b8"}]}
```

```kotlin [field-property box="midi_write" name="code" path="midi_write_code_.kt"]
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
```

## 4. Read it back

<!-- field-box id="midi_read" index="3" -->

```edn [field-box id="midi_read" role="properties"]
{"id" "midi_read" "index" 3 "name" "Read it back" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Read it back" "_tombstone" false "frame" #afield/rect [60.0 1040.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "midi_read_code_.kt" "lines" 39 "characters" 1508 "sha256" "69b817458920277b632c93b693cf3221231c70b2f804d2afc7217ab17be4fa36"}]}
```

```kotlin [field-property box="midi_read" name="code" path="midi_read_code_.kt"]
import afield.four.synth.*
import java.io.File

// 2 - Read it back, and check the round trip actually held.
//
// Run box 1 first. This reads the file with no knowledge of what wrote it: the PPQ,
// tempo map, track split and track names all come out of the file itself.
val file = File(System.getProperty("java.io.tmpdir"), "field-midi-demo.mid")
require(file.isFile) { "run box 1 first - no file at ${file.absolutePath}" }

val clip = readMidiClip(file)

showMidiRoll(clip)

// And back into the pattern algebra, so an imported clip can be composed with.
val asPattern = readMidiPat(file, beatsPerCycle = 8)
val firstCycle = asPattern.events(PatArc(PatQ.ZERO, PatQ.ONE))

markdown(
    "main",
    """
    ### Read ${clip.notes.size} notes back

    | | |
    |---|---|
    | PPQ | ${clip.ppq} |
    | tempo | ${"%.2f".format(clip.bpmAt(0.0))} BPM |
    | tempo events | ${clip.tempoMap.size} |
    | time signature | ${clip.timeSignature.first}/${clip.timeSignature.second} |
    | tracks | ${clip.tracks.map { "${it.name} (${it.notes.size})" }.joinToString()} |
    | pitch range | ${clip.notes.minOf { it.pitch }}..${clip.notes.maxOf { it.pitch }} |
    | velocities | ${clip.notes.map { it.velocity }.distinct().sorted().joinToString()} |

    As a pattern again: ${firstCycle.size} events in cycle 0, pitches
    `${firstCycle.sortedBy { it.arc.start }.map { it.value }.joinToString(" ")}`.

    The roll above is drawn from the *file*, not from the clip box 1 held in memory.
    """.trimIndent()
)
```

