# Field Document: physical_piano

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 6 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. piece

<!-- field-box id="piano_piece" index="0" -->

### Narrative

From `code`

A pattern on the piano. Atoms are note names or MIDI numbers; `,` stacks a chord.

```edn [field-box id="piano_piece" role="properties"]
{"id" "piano_piece" "index" 0 "name" "piece" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "piece" "_tombstone" false "frame" #afield/rect [20.0 760.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "piano_piece_code_.kt" "lines" 5 "characters" 307 "sha256" "a7e0f1d4e36b2870df16eaad9399633cccba474101f9466b0663de1bbf29ebc8"}]}
```

```kotlin [field-property box="piano_piece" name="code" path="piano_piece_code_.kt"]
// A pattern on the piano. Atoms are note names or MIDI numbers; `,` stacks a chord.
val tune = pat("<[c3,g3] [a2,e3] [f2,c3] [g2,d3]> [e4 g4 c5 g4]")
val r = pianoRender(11.0, Piano(sampleRate = 44_100)) { play(tune, cycles = 8, cps = 0.8, startFrame = 0) }
showPianoAudio(r, "piece")
displayOpen("piece")
```

## 2. pedal

<!-- field-box id="piano_pedal" index="1" -->

### Narrative

From `code`

The same staccato arpeggio three times. With the pedal down the dampers stay off every
string, so the struck notes ring on and the unstruck strings answer them. The third take
keeps the pedal down but turns the sympathetic strings off (coupling = 0).

```edn [field-box id="piano_pedal" role="properties"]
{"id" "piano_pedal" "index" 1 "name" "pedal" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "pedal" "_tombstone" false "frame" #afield/rect [760.0 760.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "piano_pedal_code_.kt" "lines" 26 "characters" 1230 "sha256" "4a74763041b87141ccb84a8da0f2dfc3e4c415cca8bdf9a57d771ca6c45d940d"}]}
```

```kotlin [field-property box="piano_pedal" name="code" path="piano_pedal_code_.kt"]
/*
The same staccato arpeggio three times. With the pedal down the dampers stay off every
string, so the struck notes ring on and the unstruck strings answer them. The third take
keeps the pedal down but turns the sympathetic strings off (coupling = 0).
*/
val arpeggio = pat("[c2 g2 e3 c4 g4 e5 ~ ~]")
fun take(pedal: Float, coupling: Float) = pianoRender(5.0, Piano(sampleRate = 44_100, room = Param(0f), pedal = Param(pedal), coupling = Param(coupling))) {
    play(arpeggio, cycles = 1, cps = 0.5, legato = 0.15, startFrame = 0)
}
fun tailDb(r: PianoRender): Double {
    val x = r.mono
    val from = (2.5 * r.sampleRate).toInt()
    val e = (from until x.size).sumOf { x[it].toDouble() * x[it] } / (x.size - from)
    return 10 * kotlin.math.log10(e + 1e-30)
}
val up = take(0f, 1f)
val down = take(1f, 1f)
val dry = take(1f, 0f)
showPianoAudio(up, "pedal up")
showPianoAudio(down, "pedal down")
showPianoAudio(dry, "pedal down, no sympathetic strings")
listOf("pedal up", "pedal down", "pedal down, no sympathetic strings").forEach { displayOpen(it) }
println(
    "level from 2.5 s to 5 s: pedal up %.1f dB, down %.1f dB, down without sympathetic strings %.1f dB"
        .format(tailDb(up), tailDb(down), tailDb(dry)),
)
```

## 3. a4

<!-- field-box id="piano_a4" index="2" -->

### Narrative

From `code`

A4 at velocity 0.5 for eight seconds, without the room: `./coopiano A4 0.5 8` in coopiano.

```edn [field-box id="piano_a4" role="properties"]
{"id" "piano_a4" "index" 2 "name" "a4" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "a4" "_tombstone" false "frame" #afield/rect [20.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "piano_a4_code_.kt" "lines" 9 "characters" 593 "sha256" "789c1bd2e57d62cfab2944b92a98b02cc19a73d66a26e19dbefc482dd63476d9"}]}
```

```kotlin [field-property box="piano_a4" name="code" path="piano_a4_code_.kt"]
// A4 at velocity 0.5 for eight seconds, without the room: `./coopiano A4 0.5 8` in coopiano.
val r = pianoRender(8.0, Piano(sampleRate = 44_100, room = Param(0f))) { noteOn(69, 0.5, 0) }
showPianoAudio(r, "a4")
// The spectrum from 0.5 s to 1.24 s, up to 5 kHz: the partials stretch sharp of 440 * n.
val spec = pianoSpectrumDb(r.mono, startSample = 22_050, fftSize = 32_768)
display("spectrum", pianoPlotSvg(spec.copyOfRange(0, 3_715), "A4, 0.5-1.24 s, 0-5 kHz", xLabel = "Hz", yLabel = "dB"))
displayOpen("a4")
displayOpen("spectrum")
println("peak %.3f, %.1f s".format(r.peak, r.seconds))
```

## 4. wobble

<!-- field-box id="piano_wobble" index="3" -->

### Narrative

From `code`

Two of Piano's inputs driven by oscillators. The string stiffness swings by 0.9 either side
of normal 110 times a second, which puts sidebands on every ringing partial. The hammer
hardness drifts between a tenth and twice normal every few seconds, read at each strike.

```edn [field-box id="piano_wobble" role="properties"]
{"id" "piano_wobble" "index" 3 "name" "wobble" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "wobble" "_tombstone" false "frame" #afield/rect [20.0 1360.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "piano_wobble_code_.kt" "lines" 17 "characters" 908 "sha256" "60557111031264120c8895b0bedf3b77835b85b9a0fe55bcbb4aab23e2fbf00a"}]}
```

```kotlin [field-property box="piano_wobble" name="code" path="piano_wobble_code_.kt"]
/*
Two of Piano's inputs driven by oscillators. The string stiffness swings by 0.9 either side
of normal 110 times a second, which puts sidebands on every ringing partial. The hammer
hardness drifts between a tenth and twice normal every few seconds, read at each strike.
*/
val stiffness = Param(1f).modulate(SineOsc(freq = Param(110f)) * 0.9f)
val hammer = Param(1.05f).modulate(SineOsc(freq = Param(0.15f)) * 0.95f)
val piano = Piano(sampleRate = 44_100, inharmonicity = stiffness, hardness = hammer)
val r = pianoRender(10.0, piano) { play(pat("[a2,e3,a3] ~ [d3,a3,f4] ~"), cycles = 4, cps = 0.4, startFrame = 0) }
showPianoAudio(r, "wobble")
val spec = pianoSpectrumDb(r.mono, startSample = 44_100, fftSize = 32_768)
display(
    "wobble spectrum",
    pianoPlotSvg(spec.copyOfRange(0, 3_715), "1.0-1.74 s, 0-5 kHz", xLabel = "Hz", yLabel = "dB"),
)
displayOpen("wobble")
displayOpen("wobble spectrum")
```

## 5. live

<!-- field-box id="piano_live" index="4" -->

### Narrative

From `code`

The piano on the audio engine. Move the sliders while it plays: each one is a Param, read
every sample, so a slider and an oscillator are the same kind of input.

```edn [field-box id="piano_live" role="properties"]
{"id" "piano_live" "index" 4 "name" "live" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "live" "_tombstone" false "frame" #afield/rect [760.0 1360.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "piano_live_code_.kt" "lines" 19 "characters" 881 "sha256" "dfbbc972a7c95672d686ad6dc8a8227abd695130f9340d59cff8d2e152fc814b"}]}
```

```kotlin [field-property box="piano_live" name="code" path="piano_live_code_.kt"]
// The piano on the audio engine. Move the sliders while it plays: each one is a Param, read
// every sample, so a slider and an oscillator are the same kind of input.
val pedal = Param(0f)
val stiffness = Param(1f)
val board = Param(1f)
slider("pedal", 0.0, 1.0, initialValue = 0.0, channel = "pedal", onUpdate = { pedal.base = it.toFloat() })
slider(
    "string stiffness",
    0.0,
    6.0,
    initialValue = 1.0,
    channel = "stiffness",
    onUpdate = { stiffness.base = it.toFloat() },
)
slider("soundboard", 0.0, 2.0, initialValue = 1.0, channel = "soundboard", onUpdate = { board.base = it.toFloat() })
listOf("pedal", "stiffness", "soundboard").forEach { displayOpen(it) }
val piano = Piano(pedal = pedal, inharmonicity = stiffness, soundboard = board)
piano.play(pat("<[c3,g3] [a2,e3] [f2,c3] [g2,d3]> [e4 g4 c5 g4]"), cycles = 32, cps = 0.8)
audio(piano, gain = 1f)
```

## 6. string

<!-- field-box id="piano_string" index="5" -->

### Narrative

From `code`

One of C3's strings, struck at velocity 0.8, drawn at five moments after the hammer
leaves it. The kink travelling along it is the hammer's blow.

```edn [field-box id="piano_string" role="properties"]
{"id" "piano_string" "index" 5 "name" "string" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "string" "_tombstone" false "frame" #afield/rect [760.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "piano_string_code_.kt" "lines" 13 "characters" 481 "sha256" "27fcbb66f6b147b004ea2f19b4b3f6287e6f29f1fc682dd3c819c79a00369309"}]}
```

```kotlin [field-property box="piano_string" name="code" path="piano_string_code_.kt"]
// One of C3's strings, struck at velocity 0.8, drawn at five moments after the hammer
// leaves it. The kink travelling along it is the hammer's blow.
val times = listOf(0.0, 0.0005, 0.001, 0.002, 0.004)
val plots =
    times.joinToString("") { t ->
        pianoPlotSvg(
            pianoStringShape(48, 0.8, t, points = 300),
            "C3 string, %.1f ms after contact".format(t * 1000),
            height = 110,
        )
    }
display("shape", plots)
displayOpen("shape")
```

