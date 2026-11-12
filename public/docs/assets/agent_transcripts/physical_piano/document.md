# Field Document: physical_piano

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 13 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. prepared

<!-- field-box id="piano_prepared" index="0" -->

### Narrative

From `code`

A prepared piano: bolts on the middle strings, rubber in the bass, a loose nut in the
treble. The phrase plain, then prepared, and the spectrum of A4 with and without a bolt.

```edn [field-box id="piano_prepared" role="properties"]
{"id" "piano_prepared" "index" 0 "name" "prepared" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "prepared" "_tombstone" false "frame" #afield/rect [20.0 2560.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "piano_prepared_code_.kt" "lines" 28 "characters" 1302 "sha256" "3e964611f6a035b165b9b29d7b3fbc5f3f89125022e656f46a33b374f15008e3"}]}
```

```kotlin [field-property box="piano_prepared" name="code" path="piano_prepared_code_.kt"]
/*
A prepared piano: bolts on the middle strings, rubber in the bass, a loose nut in the
treble. The phrase plain, then prepared, and the spectrum of A4 with and without a bolt.
*/
val tune = pat("<[a2,e3] [f2,c3]> [e4 a4 c5 e5]")
fun take(p: Piano) = pianoRender(6.0, p) { play(tune, cycles = 4, cps = 0.8, startFrame = 0) }
val preparations = [
    PianoPreparation.bolt(52..72, at = 0.28, grams = 4.0),
    PianoPreparation.rubber(36..51, at = 0.13, amount = 25.0),
    PianoPreparation.rattle(73..88, at = 0.2, level = 0.8, gapMm = 0.1),
]
showPianoAudio(take(Piano(sampleRate = 44_100)), "plain")
showPianoAudio(take(Piano(sampleRate = 44_100, preparations = preparations)), "prepared")
fun spectrum(grams: Double, title: String): String {
    val bolt = PianoPreparation.bolt(69..69, at = 0.3, grams = grams)
    val r = pianoRender(1.2, Piano(sampleRate = 44_100, room = Param(0f), preparations = [bolt])) {
        noteOn(69, 0.7, 0)
    }
    return pianoPlotSvg(
        pianoSpectrumDb(r.mono, startSample = 13_230, fftSize = 32_768).copyOfRange(0, 3_715),
        title,
        height = 130,
        xLabel = "0-5 kHz",
        yLabel = "dB",
    )
}
display("bolt", spectrum(0.0, "A4") + spectrum(4.0, "A4, 4 g bolt at x = 0.3"))
["plain", "prepared", "bolt"].forEach { displayOpen(it) }
```

## 2. pedal

<!-- field-box id="piano_pedal" index="1" -->

### Narrative

From `code`

The same staccato arpeggio three times. With the pedal down the dampers stay off every
string, so the struck notes ring on and the unstruck strings answer them. The third take
keeps the pedal down but turns the sympathetic strings off (coupling = 0).

```edn [field-box id="piano_pedal" role="properties"]
{"id" "piano_pedal" "index" 1 "name" "pedal" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "pedal" "_tombstone" false "frame" #afield/rect [760.0 760.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "piano_pedal_code_.kt" "lines" 26 "characters" 1224 "sha256" "7f994e31f2032fe8c1228e3edeaf8736d6fe9f30a08c7636e56e6820da6d3856"}]}
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
["pedal up", "pedal down", "pedal down, no sympathetic strings"].forEach { displayOpen(it) }
println(
    "level from 2.5 s to 5 s: pedal up %.1f dB, down %.1f dB, down without sympathetic strings %.1f dB"
        .format(tailDb(up), tailDb(down), tailDb(dry)),
)
```

## 3. mic pair

<!-- field-box id="piano_mics" index="2" -->

### Narrative

From `code`

Two close microphones, one near the hammers and one further along, panned apart; then two
on single strings of each note, so the unison strings beat between the speakers.

```edn [field-box id="piano_mics" role="properties"]
{"id" "piano_mics" "index" 2 "name" "mic pair" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "mic pair" "_tombstone" false "frame" #afield/rect [20.0 3160.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "piano_mics_code_.kt" "lines" 15 "characters" 895 "sha256" "471bdbf96f927bd293c7ff09f9850b050f4d96b1a077f034331fcac0509d91ef"}]}
```

```kotlin [field-property box="piano_mics" name="code" path="piano_mics_code_.kt"]
// Two close microphones, one near the hammers and one further along, panned apart; then two
// on single strings of each note, so the unison strings beat between the speakers.
val tune = pat("<[a2,e3] [f2,c3]> [e4 a4 c5 e5]")
fun take(p: Piano) = pianoRender(6.0, p) { play(tune, cycles = 4, cps = 0.8, startFrame = 0) }
val pair = [PianoMic(position = Param(0.08f), pan = Param(-0.8f)), PianoMic(position = Param(0.33f), pan = Param(0.8f))]
val strings = [
    PianoMic(position = Param(0.12f), pan = Param(-1f), string = 0),
    PianoMic(position = Param(0.12f), pan = Param(1f), string = 2),
]
showPianoAudio(take(Piano(sampleRate = 44_100, dry = Param(1f), room = Param(0.12f), mics = pair)), "pair")
showPianoAudio(
    take(Piano(sampleRate = 44_100, dry = Param(1f), room = Param(0.12f), mics = strings)),
    "strings 0 and 2",
)
["pair", "strings 0 and 2"].forEach { displayOpen(it) }
```

## 4. through

<!-- field-box id="piano_through" index="3" -->

### Narrative

From `code`

One piano played into the bridge of another whose pedal is down: the second piano's
strings answer every chord. Then into a prepared piano, whose strings answer in its own
tuning. `toMono()` turns any stereo source into a signal.

```edn [field-box id="piano_through" role="properties"]
{"id" "piano_through" "index" 3 "name" "through" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "through" "_tombstone" false "frame" #afield/rect [760.0 3160.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "piano_through_code_.kt" "lines" 22 "characters" 1035 "sha256" "58559abe033cd16d6486d10332f46d7abafe826b6cb7ff81006ede8cdf968b49"}]}
```

```kotlin [field-property box="piano_through" name="code" path="piano_through_code_.kt"]
/*
One piano played into the bridge of another whose pedal is down: the second piano's
strings answer every chord. Then into a prepared piano, whose strings answer in its own
tuning. `toMono()` turns any stereo source into a signal.
*/
fun through(preparations: List<PianoPreparation>): PianoRender {
    val player = Piano(sampleRate = 44_100, room = Param(0f), dry = Param(1f), micPosition = Param(0.1f))
    player.play(pat("<[a2,e3] [f2,c3]> [e4 a4 c5 e5]"), cycles = 4, cps = 0.8, startFrame = 0)
    val resonator = Piano(
        sampleRate = 44_100,
        pedal = Param(1f),
        coupling = Param(6f),
        soundboard = Param(0.5f),
        room = Param(0.35f),
        preparations = preparations,
        bridgeInput = Param(0f).modulate(player.toMono() * 4f),
    )
    return pianoRender(8.0, resonator)
}
showPianoAudio(through(emptyList()), "through")
showPianoAudio(through([PianoPreparation.bolt(36..72, at = 0.28, grams = 6.0)]), "through prepared")
["through", "through prepared"].forEach { displayOpen(it) }
```

## 5. wobble

<!-- field-box id="piano_wobble" index="4" -->

### Narrative

From `code`

Two of Piano's inputs driven by oscillators. The string stiffness swings by 0.9 either side
of normal 110 times a second, which puts sidebands on every ringing partial. The hammer
hardness drifts between a tenth and twice normal every few seconds, read at each strike.

```edn [field-box id="piano_wobble" role="properties"]
{"id" "piano_wobble" "index" 4 "name" "wobble" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "wobble" "_tombstone" false "frame" #afield/rect [20.0 1360.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "piano_wobble_code_.kt" "lines" 17 "characters" 908 "sha256" "60557111031264120c8895b0bedf3b77835b85b9a0fe55bcbb4aab23e2fbf00a"}]}
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

## 6. live

<!-- field-box id="piano_live" index="5" -->

### Narrative

From `code`

The piano on the audio engine. Move the sliders while it plays: each one is a Param, read
every sample, so a slider and an oscillator are the same kind of input.

```edn [field-box id="piano_live" role="properties"]
{"id" "piano_live" "index" 5 "name" "live" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "live" "_tombstone" false "frame" #afield/rect [760.0 1360.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "piano_live_code_.kt" "lines" 20 "characters" 1005 "sha256" "115795f6306892778db9c64e5871e8dc34ae765488edb3f7b4a878359ecc292f"}]}
```

```kotlin [field-property box="piano_live" name="code" path="piano_live_code_.kt"]
// The piano on the audio engine. Move the sliders while it plays: each one is a Param, read
// every sample, so a slider and an oscillator are the same kind of input.
val pedal = Param(0f)
val stiffness = Param(1f)
val board = Param(1f)
slider("pedal", 0.0, 1.0, initialValue = 0.0, channel = "pedal", onUpdate = { pedal.base = it.toFloat() })
// stiffness is exponential: the slider runs -1..2.5, the strings x0.1..x316
slider(
    "string stiffness (log10)",
    -1.0,
    2.5,
    initialValue = 0.0,
    channel = "stiffness",
    onUpdate = { stiffness.base = Math.pow(10.0, it).toFloat() },
)
slider("soundboard", 0.0, 2.0, initialValue = 1.0, channel = "soundboard", onUpdate = { board.base = it.toFloat() })
["pedal", "stiffness", "soundboard"].forEach { displayOpen(it) }
val piano = Piano(pedal = pedal, inharmonicity = stiffness, soundboard = board, pinFundamental = Param(1f))
piano.play(pat("<[c3,g3] [a2,e3] [f2,c3] [g2,d3]> [e4 g4 c5 g4]"), cycles = 32, cps = 0.8)
audio(piano, gain = 1f)
```

## 7. string

<!-- field-box id="piano_string" index="6" -->

### Narrative

From `code`

One of C3's strings, struck at velocity 0.8, drawn at five moments after the hammer
leaves it. The kink travelling along it is the hammer's blow.

```edn [field-box id="piano_string" role="properties"]
{"id" "piano_string" "index" 6 "name" "string" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "string" "_tombstone" false "frame" #afield/rect [760.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "piano_string_code_.kt" "lines" 13 "characters" 475 "sha256" "0c4a370dca77a7dd8a44b458cf55742e6aba01a9c83a948e4cec9286976e40c3"}]}
```

```kotlin [field-property box="piano_string" name="code" path="piano_string_code_.kt"]
// One of C3's strings, struck at velocity 0.8, drawn at five moments after the hammer
// leaves it. The kink travelling along it is the hammer's blow.
val times = [0.0, 0.0005, 0.001, 0.002, 0.004]
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

## 8. close live

<!-- field-box id="piano_close_live" index="7" -->

### Narrative

From `code`

A close-mic'd piano on the audio engine. Every slider is a Param read on the audio thread.

```edn [field-box id="piano_close_live" role="properties"]
{"id" "piano_close_live" "index" 7 "name" "close live" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "close live" "_tombstone" false "frame" #afield/rect [760.0 1960.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "piano_close_live_code_.kt" "lines" 15 "characters" 1008 "sha256" "8baa764a57cccdb1bb69d034ecf599a9c845f5ce887ecb7e906fe6ceeac343fe"}]}
```

```kotlin [field-property box="piano_close_live" name="code" path="piano_close_live_code_.kt"]
// A close-mic'd piano on the audio engine. Every slider is a Param read on the audio thread.
val dry = Param(1f)
val mic = Param(0.12f)
val thump = Param(1f)
val keys = Param(1f)
val room = Param(0f)
slider("board .. close mic", 0.0, 1.0, initialValue = 1.0, channel = "dry", onUpdate = { dry.base = it.toFloat() })
slider("mic position", 0.0, 1.0, initialValue = 0.12, channel = "mic", onUpdate = { mic.base = it.toFloat() })
slider("thump", 0.0, 4.0, initialValue = 1.0, channel = "thump", onUpdate = { thump.base = it.toFloat() })
slider("key noise", 0.0, 4.0, initialValue = 1.0, channel = "keys", onUpdate = { keys.base = it.toFloat() })
slider("room", 0.0, 1.0, initialValue = 0.0, channel = "room", onUpdate = { room.base = it.toFloat() })
["dry", "mic", "thump", "keys", "room"].forEach { displayOpen(it) }
val piano = Piano(dry = dry, micPosition = mic, thump = thump, keyNoise = keys, room = room)
piano.play(pat("<[a2,e3] [f2,c3]> [e4 a4 c5 e5]"), cycles = 32, cps = 0.8)
audio(piano, gain = 1f)
```

## 9. close

<!-- field-box id="piano_close" index="8" -->

### Narrative

From `code`

The same phrase twice: as the audience hears it, through the soundboard and room, and
through a microphone a few centimetres over the strings, 8% of the way along them, with
the hammer thump and the keys turned up and the room off.

```edn [field-box id="piano_close" role="properties"]
{"id" "piano_close" "index" 8 "name" "close" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "close" "_tombstone" false "frame" #afield/rect [20.0 1960.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "piano_close_code_.kt" "lines" 30 "characters" 1124 "sha256" "097c5f33681b6f7c035a7542936b73dbb85ce90a40b8d1807fea9ead49fc1aee"}]}
```

```kotlin [field-property box="piano_close" name="code" path="piano_close_code_.kt"]
/*
The same phrase twice: as the audience hears it, through the soundboard and room, and
through a microphone a few centimetres over the strings, 8% of the way along them, with
the hammer thump and the keys turned up and the room off.
*/
val tune = pat("<[a2,e3] [f2,c3]> [e4 a4 c5 e5]")
fun take(p: Piano) = pianoRender(6.0, p) { play(tune, cycles = 4, cps = 0.8, startFrame = 0) }
val board = take(Piano(sampleRate = 44_100))
val close = take(
    Piano(
        sampleRate = 44_100,
        dry = Param(1f),
        room = Param(0f),
        micPosition = Param(0.08f),
        thump = Param(1f),
        keyNoise = Param(1f),
        damperNoise = Param(2f),
    ),
)
showPianoAudio(board, "soundboard")
showPianoAudio(close, "close mic")
fun spectrum(r: PianoRender, title: String) = pianoPlotSvg(
    pianoSpectrumDb(r.mono, startSample = 22_050, fftSize = 32_768).copyOfRange(0, 3_715),
    title,
    height = 140,
    xLabel = "0-5 kHz",
    yLabel = "dB",
)
display("spectra", spectrum(board, "soundboard") + spectrum(close, "close mic at 0.08"))
["soundboard", "close mic", "spectra"].forEach { displayOpen(it) }
```

## 10. piece

<!-- field-box id="piano_piece" index="9" -->

### Narrative

From `code`

A pattern on the piano. Atoms are note names or MIDI numbers; `,` stacks a chord.

```edn [field-box id="piano_piece" role="properties"]
{"id" "piano_piece" "index" 9 "name" "piece" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "piece" "_tombstone" false "frame" #afield/rect [20.0 760.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "piano_piece_code_.kt" "lines" 5 "characters" 307 "sha256" "a7e0f1d4e36b2870df16eaad9399633cccba474101f9466b0663de1bbf29ebc8"}]}
```

```kotlin [field-property box="piano_piece" name="code" path="piano_piece_code_.kt"]
// A pattern on the piano. Atoms are note names or MIDI numbers; `,` stacks a chord.
val tune = pat("<[c3,g3] [a2,e3] [f2,c3] [g2,d3]> [e4 g4 c5 g4]")
val r = pianoRender(11.0, Piano(sampleRate = 44_100)) { play(tune, cycles = 8, cps = 0.8, startFrame = 0) }
showPianoAudio(r, "piece")
displayOpen("piece")
```

## 11. a4

<!-- field-box id="piano_a4" index="10" -->

### Narrative

From `code`

A4 at velocity 0.5 for eight seconds, without the room: `./coopiano A4 0.5 8` in coopiano.

```edn [field-box id="piano_a4" role="properties"]
{"id" "piano_a4" "index" 10 "name" "a4" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "a4" "_tombstone" false "frame" #afield/rect [20.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "piano_a4_code_.kt" "lines" 9 "characters" 593 "sha256" "789c1bd2e57d62cfab2944b92a98b02cc19a73d66a26e19dbefc482dd63476d9"}]}
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

## 12. prepared live

<!-- field-box id="piano_prepared_live" index="11" -->

### Narrative

From `code`

A prepared piano on the audio engine: one bolt and one rattle across the keyboard, the
soft and middle pedals. Slide the bolt while a chord rings.

```edn [field-box id="piano_prepared_live" role="properties"]
{"id" "piano_prepared_live" "index" 11 "name" "prepared live" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "prepared live" "_tombstone" false "frame" #afield/rect [760.0 2560.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "piano_prepared_live_code_.kt" "lines" 29 "characters" 1578 "sha256" "f14e39bbe81617b2841ffec3a64a538d99f8b442f3a5cc6b879de7a3b0cb8689"}]}
```

```kotlin [field-property box="piano_prepared_live" name="code" path="piano_prepared_live_code_.kt"]
// A prepared piano on the audio engine: one bolt and one rattle across the keyboard, the
// soft and middle pedals. Slide the bolt while a chord rings.
val at = Param(0.3f)
val grams = Param(3f)
val rubber = Param(0f)
val rattle = Param(0f)
val gap = Param(0.1f)
val soft = Param(0f)
val sost = Param(0f)
slider("bolt position", 0.0, 1.0, initialValue = 0.3, channel = "at", onUpdate = { at.base = it.toFloat() })
// grams are exponential: the slider runs -1..2, the bolt 0.1 g..100 g
slider(
    "bolt grams (log10)",
    -1.0,
    2.0,
    initialValue = 0.48,
    channel = "grams",
    onUpdate = { grams.base = Math.pow(10.0, it).toFloat() },
)
slider("rubber", 0.0, 100.0, initialValue = 0.0, channel = "rubber", onUpdate = { rubber.base = it.toFloat() })
slider("rattle", 0.0, 4.0, initialValue = 0.0, channel = "rattle", onUpdate = { rattle.base = it.toFloat() })
slider("rattle gap (mm)", 0.0, 3.0, initialValue = 0.1, channel = "gap", onUpdate = { gap.base = it.toFloat() })
slider("una corda", 0.0, 1.0, initialValue = 0.0, channel = "soft", onUpdate = { soft.base = it.toFloat() })
slider("sostenuto", 0.0, 1.0, initialValue = 0.0, channel = "sost", onUpdate = { sost.base = it.toFloat() })
["at", "grams", "rubber", "rattle", "gap", "soft", "sost"].forEach { displayOpen(it) }
val prep = PianoPreparation(21..108, position = at, mass = grams, damping = rubber, rattle = rattle, gap = gap)
val piano = Piano(preparations = [prep], unaCorda = soft, sostenuto = sost)
piano.play(pat("<[a2,e3] [f2,c3]> [e4 a4 c5 e5]"), cycles = 32, cps = 0.8)
audio(piano, gain = 1f)
```

## 13. midi

<!-- field-box id="piano_midi" index="12" -->

### Narrative

From `code`

A MIDI clip written in code, with the sustain pedal, rendered to a WAV in one call.

```edn [field-box id="piano_midi" role="properties"]
{"id" "piano_midi" "index" 12 "name" "midi" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "midi" "_tombstone" false "frame" #afield/rect [20.0 3760.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "piano_midi_code_.kt" "lines" 19 "characters" 781 "sha256" "3af41402d583888471eda75555120075abb67f1d41d728615e2391b330366238"}]}
```

```kotlin [field-property box="piano_midi" name="code" path="piano_midi_code_.kt"]
// A MIDI clip written in code, with the sustain pedal, rendered to a WAV in one call.
val notes =
    listOf(48, 55, 60, 64, 67, 72).mapIndexed { i, p ->
        MidiNote(p, startBeats = i * 0.5, durationBeats = 0.4, velocity = 80)
    }
val pedal = [
    MidiControlChange(controller = 64, value = 127, beats = 0.0),
    MidiControlChange(controller = 64, value = 0, beats = 3.5),
]
val clip = MidiClip(tracks = [MidiTrackData(notes = notes, controlChanges = pedal)])
val wav = java.io.File.createTempFile("piano-midi", ".wav")
val r = pianoRenderMidi(
    clip,
    wav,
    piano = Piano(sampleRate = 44_100, preparations = [PianoPreparation.bolt(60..72, at = 0.3, grams = 3.0)]),
)
println("wrote ${wav.path}: ${r.seconds.f1} s")
showPianoAudio(r, "midi")
displayOpen("midi")
```

