# Field Document: audio_curve_explorer

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 · a film voice as a moving 3D curve — play or scrub

<!-- field-box id="audio_curve_voice" index="0" -->

```edn [field-box id="audio_curve_voice" role="properties"]
{"id" "audio_curve_voice" "index" 0 "name" "1 · a film voice as a moving 3D curve — play or scrub" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · a film voice as a moving 3D curve — play or scrub" "_tombstone" false "frame" #afield/rect [40.0 40.0 460.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "audio_curve_voice_code_.kt" "lines" 26 "characters" 1110 "sha256" "12e2101fbdc440576d75e2486b18b4d2c76bf68b4644b9b3e3d6940c04270701"}]}
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
        moveTo(-80, -80, z); lineTo(80, -80, z); lineTo(80, 80, z); lineTo(-80, 80, z); lineTo(-80, -80, z)
    }
    for (x in [-80, 80]) for (y in [-80, 80]) { moveTo(x, y, -80); lineTo(x, y, 80) }
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
}.end { curve.close() }
```

## 2. 2 · stereo music, one curve per channel

<!-- field-box id="audio_curve_music" index="1" -->

```edn [field-box id="audio_curve_music" role="properties"]
{"id" "audio_curve_music" "index" 1 "name" "2 · stereo music, one curve per channel" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · stereo music, one curve per channel" "_tombstone" false "frame" #afield/rect [1240.0 40.0 460.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "audio_curve_music_code_.kt" "lines" 24 "characters" 936 "sha256" "3791e95eeb424fb02753a43f7d9bfd9f2d952a959edc13db706de288d944e052"}]}
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
}.end { curves.forEach { it.close() } }
```

## 3. 4 · averages from one hop to 20 s instead of a window

<!-- field-box id="audio_curve_ema" index="2" -->

```edn [field-box id="audio_curve_ema" role="properties"]
{"id" "audio_curve_ema" "index" 2 "name" "4 · averages from one hop to 20 s instead of a window" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 · averages from one hop to 20 s instead of a window" "_tombstone" false "frame" #afield/rect [3640.0 40.0 460.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "audio_curve_ema_code_.kt" "lines" 17 "characters" 709 "sha256" "0dcc6a73201d2e621e2c4fdc2001861ef3d82a790c873b5ec73ae4d287f5bba0"}]}
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
}.end { curve.close() }
```

## 4. 3 · finer hop, longer memory

<!-- field-box id="audio_curve_dense" index="3" -->

```edn [field-box id="audio_curve_dense" role="properties"]
{"id" "audio_curve_dense" "index" 3 "name" "3 · finer hop, longer memory" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 · finer hop, longer memory" "_tombstone" false "frame" #afield/rect [2440.0 40.0 460.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "audio_curve_dense_code_.kt" "lines" 19 "characters" 709 "sha256" "adf11d72c73f313df0c38dc6c6b9f01ce322180565920de886a1ff02625c8556"}]}
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
}.end { curve.close() }
```

