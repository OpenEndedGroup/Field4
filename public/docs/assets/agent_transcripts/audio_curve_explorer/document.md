# Field Document: audio_curve_explorer

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 · a film voice as a moving 3D curve — play or scrub

<!-- field-box id="audio_curve_voice" index="0" -->

```edn [field-box id="audio_curve_voice" role="properties"]
{"id" "audio_curve_voice" "index" 0 "name" "1 · a film voice as a moving 3D curve — play or scrub" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · a film voice as a moving 3D curve — play or scrub" "_tombstone" false "frame" #afield/rect [40.0 40.0 460.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "audio_curve_voice_code_.kt" "lines" 34 "characters" 1162 "sha256" "3ccbb4e52f392dc981872d1816aab3ca62efb6f4b650082cbf7e7f2067a4ac7b"}]}
```

```kotlin [field-property box="audio_curve_voice" name="code" path="audio_curve_voice_code_.kt"]
val wav = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
val curve = audioCurves(wav, AudioCurveSettings(dimensions = 3)).first()
val scale = 100 / curve.extent()

// The player keeps the audio clock and tells this box where it is, or where you are scrubbing.
var t = 0.0
displayAudioBytes(wav.toFile().readBytes(), options = AudioVisualizerOptions(onTime = { t = it }))

// A faint cube around where the curve lives, so the view holds still while you orbit.
val room = fline {
    for (z in [-80, 80]) {
        moveTo(-80, -80, z)
        lineTo(80, -80, z)
        lineTo(80, 80, z)
        lineTo(-80, 80, z)
        lineTo(-80, -80, z)
    }
    for (x in [-80, 80]) for (y in [-80, 80]) {
        moveTo(x, y, -80)
        lineTo(x, y, 80)
    }
    color = vec(1, 1, 1, 0.12)
}

var shown = Double.NaN
loop {
    // Before the first complete window, show the first curve.
    val frame = curve.frameAt(t) ?: curve.frame(0)
    if (frame.timeSeconds != shown) {
        shown = frame.timeSeconds
        threeLines(frame.flines(scale, bands = 16, centered = true) + room, channel = "curve", height = 560)
    }
}
    .end { curve.close() }
```

## 2. 2 · stereo music, one curve per channel

<!-- field-box id="audio_curve_music" index="1" -->

```edn [field-box id="audio_curve_music" role="properties"]
{"id" "audio_curve_music" "index" 1 "name" "2 · stereo music, one curve per channel" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · stereo music, one curve per channel" "_tombstone" false "frame" #afield/rect [1240.0 40.0 460.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "audio_curve_music_code_.kt" "lines" 25 "characters" 941 "sha256" "5369061964d9d8afd7be63eabd5fb04de7c25c36a7ab4cd2009584fe5a1f1335"}]}
```

```kotlin [field-property box="audio_curve_music" name="code" path="audio_curve_music_code_.kt"]
val wav = "%docs/assets/audio_ai_muscriptor/source.wav".path
val curves = audioCurves(wav)
val colors = [vec(0.39, 0.78, 0.86, 1.0), vec(0.96, 0.70, 0.36, 1.0)]

var t = 3.0
displayAudioBytes(
    wav.toFile().readBytes(),
    options = AudioVisualizerOptions(initialPositionSeconds = t, onTime = { t = it }),
)

var shown = Double.NaN
loop {
    val frames = curves.map { it.frameAt(t) }
    val time = frames.firstOrNull()?.timeSeconds
    if (time != null && time != shown) {
        shown = time
        // Left channel on the left, right on the right; each channel has its own axes.
        val lines = frames.flatMapIndexed { i, frame ->
            val offset = vec(if (i == 0) -110.0 else 110.0, 0.0, 0.0)
            frame!!.flines(80 / curves[i].extent(), depth = 150.0, color = colors[i]).map { it + offset }
        }
        threeLines(lines, channel = "curves", height = 520)
    }
}
    .end { curves.forEach { it.close() } }
```

## 3. 4 · averages from one hop to 20 s instead of a window

<!-- field-box id="audio_curve_ema" index="2" -->

```edn [field-box id="audio_curve_ema" role="properties"]
{"id" "audio_curve_ema" "index" 2 "name" "4 · averages from one hop to 20 s instead of a window" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 · averages from one hop to 20 s instead of a window" "_tombstone" false "frame" #afield/rect [3640.0 40.0 460.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "audio_curve_ema_code_.kt" "lines" 18 "characters" 714 "sha256" "3203a3c54983db94da12adc763fde5b6b182c37b4479f060c5f56f88efe77c42"}]}
```

```kotlin [field-property box="audio_curve_ema" name="code" path="audio_curve_ema_code_.kt"]
val wav = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
// 48 running averages of the spectrum, from one hop up to 20 seconds, longest first.
val settings = AudioCurveSettings(dimensions = 3, history = 48, emaLongestSeconds = 20.0)
val curve = audioCurves(wav, settings).first()
val scale = 100 / curve.extent()

var t = 0.0
displayAudioBytes(wav.toFile().readBytes(), options = AudioVisualizerOptions(onTime = { t = it }))

var shown = Double.NaN
loop {
    val frame = curve.frameAt(t)
    if (frame != null && frame.timeSeconds != shown) {
        shown = frame.timeSeconds
        threeLines(frame.flines(scale, bands = 16), channel = "curve", height = 560)
    }
}
    .end { curve.close() }
```

## 4. 5 · the sounds heard so far, remembered on a sheet and a lattice

<!-- field-box id="audio_curve_maps" index="3" -->

```edn [field-box id="audio_curve_maps" role="properties"]
{"id" "audio_curve_maps" "index" 3 "name" "5 · the sounds heard so far, remembered on a sheet and a lattice" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 · the sounds heard so far, remembered on a sheet and a lattice" "_tombstone" false "frame" #afield/rect [4840.0 40.0 460.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "audio_curve_maps_code_.kt" "lines" 61 "characters" 2311 "sha256" "f0f7114143f0dc2b29b0016c6873ee75d57730c6da5a9552c4a3312e2d172144"}]}
```

```kotlin [field-property box="audio_curve_maps" name="code" path="audio_curve_maps_code_.kt"]
val wav = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
// A sheet of 12 × 12 prototypes and a lattice of 6 × 6 × 6, each learning with a 0.5 s time constant.
val sheet = audioCurves(wav, AudioCurveSettings(dimensions = 3, somLearnSeconds = 0.5, somGrid = [12, 12])).first()
val lattice = audioCurves(wav, AudioCurveSettings(dimensions = 3, somLearnSeconds = 0.5, somGrid = [6, 6, 6])).first()

// Start six seconds in, so a learned map shows before you press play.
var t = 6.0
displayAudioBytes(
    wav.toFile().readBytes(),
    options = AudioVisualizerOptions(initialPositionSeconds = t, onTime = { t = it }),
)

// A faint room around each map, so the view holds still while the maps turn.
fun room(cx: Double) = fline {
    for (z in [-80.0, 80.0]) {
        moveTo(cx - 80, -80.0, z)
        lineTo(cx + 80, -80.0, z)
        lineTo(cx + 80, 80.0, z)
        lineTo(cx - 80, 80.0, z)
        lineTo(cx - 80, -80.0, z)
    }
    for (x in [cx - 80, cx + 80]) for (y in [-80.0, 80.0]) {
        moveTo(x, y, -80.0)
        lineTo(x, y, 80.0)
    }
    color = vec(1, 1, 1, 0.1)
}
val rooms = [room(-100.0), room(100.0)]

// Turn a map slowly about the vertical axis as the sound plays, then move it into its room.
fun place(lines: List<FLine>, turn: Double, cx: Double) = lines.map { line ->
    line.transform { p -> vec(p.x * cos(turn) + p.z * sin(turn) + cx, p.y, -p.x * sin(turn) + p.z * cos(turn)) }
}

var shown = Double.NaN
var first = true
loop {
    val a = sheet.frameAt(t) ?: sheet.frame(0)
    if (a.timeSeconds != shown) {
        shown = a.timeSeconds
        val b = lattice.frameAt(t) ?: lattice.frame(0)
        val turn = 0.3 * t
        val left = place(
            a.flines(55 / a.spread(), centered = true, stretch = true, trail = sheet.recentMatches(t, 0.5)),
            turn,
            -100.0,
        )
        val right = place(
            b.flines(55 / b.spread(), centered = true, stretch = true, trail = lattice.recentMatches(t, 0.5)),
            turn,
            100.0,
        )
        // Frame the view once; after that the rooms hold it still while the maps turn.
        threeLines(left + right + rooms, channel = "maps", height = 560, autoFrame = first)
        first = false
    }
}
    .end {
        sheet.close()
        lattice.close()
    }
```

## 5. 3 · finer hop, longer memory

<!-- field-box id="audio_curve_dense" index="4" -->

```edn [field-box id="audio_curve_dense" role="properties"]
{"id" "audio_curve_dense" "index" 4 "name" "3 · finer hop, longer memory" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 · finer hop, longer memory" "_tombstone" false "frame" #afield/rect [2440.0 40.0 460.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "audio_curve_dense_code_.kt" "lines" 20 "characters" 714 "sha256" "68ea008f087b7a5b2bbfc1054835f3b6190ff28151b1cf0a716703afc6d4233f"}]}
```

```kotlin [field-property box="audio_curve_dense" name="code" path="audio_curve_dense_code_.kt"]
val wav = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
// A curve every 256 samples (5.3 ms at 48 kHz) that remembers 384 spectra (2 s).
val curve = audioCurves(wav, AudioCurveSettings(hopSize = 256, history = 384)).first()
val scale = 100 / curve.extent()

var t = 2.0
displayAudioBytes(
    wav.toFile().readBytes(),
    options = AudioVisualizerOptions(initialPositionSeconds = t, onTime = { t = it }),
)

var shown = Double.NaN
loop {
    val frame = curve.frameAt(t)
    if (frame != null && frame.timeSeconds != shown) {
        shown = frame.timeSeconds
        threeLines(frame.flines(scale, depth = 300.0, bands = 16), channel = "curve", height = 560)
    }
}
    .end { curve.close() }
```

