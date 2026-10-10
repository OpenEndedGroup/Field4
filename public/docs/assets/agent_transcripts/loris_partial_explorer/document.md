# Field Document: loris_partial_explorer

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 4 · a spectral whirlpool in seconds 6–9

<!-- field-box id="loris_spectral_whirlpool" index="0" -->

```edn [field-box id="loris_spectral_whirlpool" role="properties"]
{"id" "loris_spectral_whirlpool" "index" 0 "name" "4 · a spectral whirlpool in seconds 6–9" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 · a spectral whirlpool in seconds 6–9" "_tombstone" false "frame" #afield/rect [1120.0 2200.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "loris_spectral_whirlpool_code_.kt" "lines" 39 "characters" 1719 "sha256" "277a9896bb928649cbbdd28ce7f269d162b6be7f18baa0fda900a19d91551306"}]}
```

```kotlin [field-property box="loris_spectral_whirlpool" name="code" path="loris_spectral_whirlpool_code_.kt"]
import afield.stdlib.path
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

val client = LorisClient()
val chunkStart = 6.0
val chunkDuration = 3.0
client.analyzeWavAsync("%docs/assets/subharmonic_sound/notld-barbara-source.wav".path,
    options = LorisOptions(resolutionHz = 35.0, windowWidthHz = 80.0,
        frequencyDriftHz = 50.0, amplitudeFloorDb = -55.0),
    onProgress = { display("status", it) },
).thenCompose { analyzed ->
    val model = analyzed.model
    val changed = model.copy(partials = model.partials.map { partial ->
        partial.copy(points = partial.points.map { point ->
            val u = (point.timeSeconds - chunkStart) / chunkDuration
            if (u <= 0.0 || u >= 1.0) point else {
                val envelope = sin(PI * u).pow(2)
                val octaves = 1.5 * envelope * sin(2 * PI * 4 * u)
                val shutter = .25 + .75 * (.5 + .5 * sin(2 * PI * 12 * chunkDuration * u))
                point.copy(
                    frequencyHz = (point.frequencyHz * 2.0.pow(octaves)).coerceAtMost(model.sampleRate * .49),
                    amplitude = point.amplitude * (1 - envelope + envelope * shutter),
                    bandwidth = point.bandwidth * (1 - envelope) + .85 * envelope,
                )
            }
        })
    })
    display("status", "Resynthesizing the whirlpool…")
    client.synthesizeAsync(changed)
}.thenAccept { edited ->
    display("edited tracks and sound", edited.visualizationHtml(
        LorisViewOptions(height = 400, syncGroup = "my-whirlpool")))
    display("provenance", edited.provenance)
}.exceptionally { error ->
    display("error", error.cause?.message ?: error.message ?: error.toString())
    null
}
```

## 2. 2 · select tracks, remove noise, lower frequencies

<!-- field-box id="loris_edit_partials" index="1" -->

```edn [field-box id="loris_edit_partials" role="properties"]
{"id" "loris_edit_partials" "index" 1 "name" "2 · select tracks, remove noise, lower frequencies" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · select tracks, remove noise, lower frequencies" "_tombstone" false "frame" #afield/rect [1120.0 40.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "loris_edit_partials_code_.kt" "lines" 26 "characters" 1181 "sha256" "b09926f8ffd9974c25fc087b4735839a93bd7521824e4a752a8755662745e2d5"}]}
```

```kotlin [field-property box="loris_edit_partials" name="code" path="loris_edit_partials_code_.kt"]
import afield.stdlib.path

val client = LorisClient()
val minimumTrackSeconds = 0.03
val frequencyScale = 0.75
val bandwidthScale = 0.0
client.analyzeWavAsync("%docs/assets/subharmonic_sound/notld-barbara-source.wav".path,
    options = LorisOptions(resolutionHz = 35.0, windowWidthHz = 80.0,
        frequencyDriftHz = 50.0, amplitudeFloorDb = -55.0),
    onProgress = { display("status", it) },
).thenCompose { analyzed ->
    val selected = analyzed.model.copy(partials = analyzed.model.partials.filter {
        it.points.last().timeSeconds - it.points.first().timeSeconds >= minimumTrackSeconds
    })
    display("status", "Resynthesizing ${selected.partials.size} selected tracks…")
    client.synthesizeAsync(selected.transformed(frequencyScale = frequencyScale,
        bandwidthScale = bandwidthScale, gain = 0.8))
}.thenAccept { result ->
    display("changed partials and sound", result.visualizationHtml(LorisViewOptions(
        axis = LorisFrequencyAxis.ERB,
        syncGroup = "loris-edited-study")))
    display("provenance", result.provenance)
}.exceptionally { error ->
    display("error", error.cause?.message ?: error.message ?: error.toString())
    null
}
```

## 3. 3 · independent fine-time and fine-frequency passes

<!-- field-box id="loris_two_resolutions" index="2" -->

```edn [field-box id="loris_two_resolutions" role="properties"]
{"id" "loris_two_resolutions" "index" 2 "name" "3 · independent fine-time and fine-frequency passes" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 · independent fine-time and fine-frequency passes" "_tombstone" false "frame" #afield/rect [40.0 2200.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "loris_two_resolutions_code_.kt" "lines" 21 "characters" 954 "sha256" "c311f1b7be73db7fe93e9f8786d5fa176324e7f460107d8a13e0d9f509f32562"}]}
```

```kotlin [field-property box="loris_two_resolutions" name="code" path="loris_two_resolutions_code_.kt"]
import afield.stdlib.path
import java.util.concurrent.CompletableFuture

val client = LorisClient()
val path = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
val passes = listOf(
    "fine time" to LorisOptions(resolutionHz = 80.0, windowWidthHz = 160.0, hopSeconds = .003),
    "fine frequency" to LorisOptions(resolutionHz = 20.0, windowWidthHz = 40.0, hopSeconds = .005),
).map { (name, settings) ->
    client.analyzeWavAsync(path, durationSeconds = 6.0, options = settings,
        onProgress = { display("$name status", it) },
    ).thenAccept { result ->
        display(name, result.visualizationHtml(LorisViewOptions(label = name,
            height = 280, syncGroup = "loris-resolution-comparison")))
        display("$name provenance", result.provenance)
    }
}
CompletableFuture.allOf(*passes.toTypedArray()).exceptionally { error ->
    display("error", error.cause?.message ?: error.message ?: error.toString())
    null
}
```

## 4. 1 · source, analyze, partials and reconstruction

<!-- field-box id="loris_source_and_partials" index="3" -->

```edn [field-box id="loris_source_and_partials" role="properties"]
{"id" "loris_source_and_partials" "index" 3 "name" "1 · source, analyze, partials and reconstruction" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · source, analyze, partials and reconstruction" "_tombstone" false "frame" #afield/rect [40.0 40.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "loris_source_and_partials_code_.kt" "lines" 24 "characters" 1114 "sha256" "ad884809ae492c1627af9a9d4c5ba32d52cd295f9b5b585642742c574ab48b6a"}]}
```

```kotlin [field-property box="loris_source_and_partials" name="code" path="loris_source_and_partials_code_.kt"]
import afield.stdlib.AudioVisualizer
import afield.stdlib.AudioVisualizerOptions
import afield.four.audio.WavFloatArrayLoader
import afield.stdlib.path

val path = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
val sync = "loris-source-study"
val client = LorisClient()
display("worker", LorisNative.describe())
client.analyzeWavAsync(path,
    options = LorisOptions(resolutionHz = 35.0, windowWidthHz = 80.0,
        frequencyDriftHz = 50.0, amplitudeFloorDb = -55.0),
    onProgress = { display("status", it) },
).thenAccept { analyzed ->
    val wav = WavFloatArrayLoader.loadWavToFloatArray(path.toString())
    val source = AudioVisualizer.renderSamplesHtml(wav.samples, wav.sampleRate,
        AudioVisualizerOptions(width = 1000, waveformHeight = 95, syncGroup = sync), label = "Source")
    display("source and reconstruction", source + analyzed.visualizationHtml(
        LorisViewOptions(height = 400, syncGroup = sync)))
    display("provenance", analyzed.provenance)
}.exceptionally { error ->
    display("error", error.cause?.message ?: error.message ?: error.toString())
    null
}
```

## 5. 5 · label, align and morph two phrases

<!-- field-box id="loris_label_align_morph" index="4" -->

```edn [field-box id="loris_label_align_morph" role="properties"]
{"id" "loris_label_align_morph" "index" 4 "name" "5 · label, align and morph two phrases" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 · label, align and morph two phrases" "_tombstone" false "frame" #afield/rect [40.0 4400.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "loris_label_align_morph_code_.kt" "lines" 22 "characters" 1155 "sha256" "0da62ded1f51d07b03110c3af656642e450281fcdbf7888343f4ff44f5f9aaca"}]}
```

```kotlin [field-property box="loris_label_align_morph" name="code" path="loris_label_align_morph_code_.kt"]
import afield.stdlib.path

val client = LorisClient()
client.analyzeWavAsync(
    "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path,
    options = LorisOptions(resolutionHz = 35.0, windowWidthHz = 80.0,
        frequencyDriftHz = 50.0, amplitudeFloorDb = -55.0),
    onProgress = { display("status", it) },
).thenAccept { analyzed ->
    val film = analyzed.model
    val a = client.distill(film.crop(1.6, 3.6), LorisReference.Estimated(150.0, 450.0)).model
    val b = client.distill(film.crop(11.5, 13.5), LorisReference.Estimated(60.0, 200.0)).model
    display("labels", "A ${a.labelCounts()}  B ${b.labelCounts()}")
    val end = a.durationSeconds
    val aligned = client.dilate(b, listOf(0.0, b.loudestTimeSeconds(), end),
        listOf(0.0, a.loudestTimeSeconds(), end), durationSeconds = end).model
    val morphed = client.morph(a.dropUnlabeled(), aligned.dropUnlabeled(), LorisEnvelope.ramp(0.4, 1.6))
    display("morph", morphed.visualizationHtml(LorisViewOptions(maximumHz = 5000.0, syncGroup = "my-morph")))
}.exceptionally { error ->
    display("error", error.cause?.message ?: error.message ?: error.toString())
    null
}
```

