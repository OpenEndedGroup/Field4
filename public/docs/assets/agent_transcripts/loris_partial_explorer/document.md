# Field Document: loris_partial_explorer

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 4 · a spectral whirlpool in seconds 6–9

<!-- field-box id="loris_spectral_whirlpool" index="0" -->

```edn [field-box id="loris_spectral_whirlpool" role="properties"]
{"id" "loris_spectral_whirlpool" "index" 0 "name" "4 · a spectral whirlpool in seconds 6–9" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 · a spectral whirlpool in seconds 6–9" "_tombstone" false "frame" #afield/rect [1120.0 2200.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "loris_spectral_whirlpool_code_.kt" "lines" 65 "characters" 2800 "sha256" "b0d0d7b6ada46c9097058fc516fff2e6f818c9b773d5db5739d87e30d8699249"}]}
```

```kotlin [field-property box="loris_spectral_whirlpool" name="code" path="loris_spectral_whirlpool_code_.kt"]
val client = LorisClient()
val chunkStart = 6.0
val chunkDuration = 3.0
val excursionOctaves = 1.5
val sweepCycles = 4.0
val shutterHz = 12.0
val noiseBlend = 0.85

runAsync(
    onResult = { edited: LorisResult ->
        display("status", "complete: ${edited.model.partials.size} partials")
        display(
            "edited tracks and sound",
            edited.visualizationHtml(
                LorisViewOptions(height = 400, syncGroup = "my-whirlpool"),
            ),
        )
        display("provenance", edited.provenance)
    },
    onError = { failure -> display("error", failure.message ?: failure.toString()) },
) {
    val status = progress<String> { display("status", it) }
    val analysis = client.analyzeWavAsync(
        "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path,
        options = LorisOptions(
            resolutionHz = 35.0,
            windowWidthHz = 80.0,
            frequencyDriftHz = 50.0,
            amplitudeFloorDb = -55.0,
        ),
        onProgress = status,
    )
    onCancel { analysis.cancel(true) }
    analysis.thenCompose { analyzed ->
        val model = analyzed.model
        val changed = model.copy(
            partials =
                model.partials.map { partial ->
                    partial.copy(
                        points =
                            partial.points.map { point ->
                                val u = (point.timeSeconds - chunkStart) / chunkDuration
                                if (u <= 0.0 || u >= 1.0) point
                                else {
                                    // A sin-squared envelope enters and leaves the edited chunk smoothly.
                                    val envelope = sin(PI * u).pow(2)
                                    val octaves = excursionOctaves * envelope * sin(2 * PI * sweepCycles * u)
                                    val shutter =
                                        .25 + .75 * (.5 + .5 * sin(2 * PI * shutterHz * chunkDuration * u))
                                    point.copy(
                                        frequencyHz = (point.frequencyHz * 2.0.pow(octaves)).coerceAtMost(
                                            model.sampleRate * .49,
                                        ),
                                        amplitude = point.amplitude * (1 - envelope + envelope * shutter),
                                        bandwidth = point.bandwidth * (1 - envelope) + noiseBlend * envelope,
                                    )
                                }
                            },
                    )
                },
        )
        status("Resynthesizing the whirlpool…")
        client.synthesizeAsync(changed).also { synthesis -> onCancel { synthesis.cancel(true) } }
    }
}
```

## 2. 2 · select tracks, remove noise, lower frequencies

<!-- field-box id="loris_edit_partials" index="1" -->

```edn [field-box id="loris_edit_partials" role="properties"]
{"id" "loris_edit_partials" "index" 1 "name" "2 · select tracks, remove noise, lower frequencies" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · select tracks, remove noise, lower frequencies" "_tombstone" false "frame" #afield/rect [1120.0 40.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "loris_edit_partials_code_.kt" "lines" 49 "characters" 1800 "sha256" "65dc439b455d3173f51e36f5f3c104c204e21a2c0d6c84adbc6f5180e7f45179"}]}
```

```kotlin [field-property box="loris_edit_partials" name="code" path="loris_edit_partials_code_.kt"]
val client = LorisClient()
val minimumTrackSeconds = 0.03
val frequencyScale = 0.75
val bandwidthScale = 0.0

runAsync(
    onResult = { result: LorisResult ->
        display("status", "complete: ${result.model.partials.size} partials")
        display(
            "changed partials and sound",
            result.visualizationHtml(
                LorisViewOptions(axis = LorisFrequencyAxis.ERB, syncGroup = "loris-edited-study"),
            ),
        )
        display("provenance", result.provenance)
    },
    onError = { failure -> display("error", failure.message ?: failure.toString()) },
) {
    val status = progress<String> { display("status", it) }
    val analysis = client.analyzeWavAsync(
        "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path,
        options = LorisOptions(
            resolutionHz = 35.0,
            windowWidthHz = 80.0,
            frequencyDriftHz = 50.0,
            amplitudeFloorDb = -55.0,
        ),
        onProgress = status,
    )
    // A composed future does not cancel its workers: own each native stage explicitly.
    onCancel { analysis.cancel(true) }
    analysis.thenCompose { analyzed ->
        val selected = analyzed.model.copy(
            partials = analyzed.model.partials.filter {
                it.points.last().timeSeconds - it.points.first().timeSeconds >= minimumTrackSeconds
            },
        )
        status("Resynthesizing ${selected.partials.size} selected tracks…")
        client
            .synthesizeAsync(
                selected.transformed(
                    frequencyScale = frequencyScale,
                    bandwidthScale = bandwidthScale,
                    gain = 0.8,
                ),
            )
            .also { synthesis -> onCancel { synthesis.cancel(true) } }
    }
}
```

## 3. 3 · independent fine-time and fine-frequency passes

<!-- field-box id="loris_two_resolutions" index="2" -->

```edn [field-box id="loris_two_resolutions" role="properties"]
{"id" "loris_two_resolutions" "index" 2 "name" "3 · independent fine-time and fine-frequency passes" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 · independent fine-time and fine-frequency passes" "_tombstone" false "frame" #afield/rect [40.0 2200.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "loris_two_resolutions_code_.kt" "lines" 33 "characters" 1207 "sha256" "4f4e8a19155d8dc04241a343561f9884086970b9a71cf08897eec45127fded95"}]}
```

```kotlin [field-property box="loris_two_resolutions" name="code" path="loris_two_resolutions_code_.kt"]
val client = LorisClient()
val path = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path

// Each pass owns its worker and output; stopping this box cancels both independently.
for ((name, settings) in
    [
        "fine time" to LorisOptions(resolutionHz = 80.0, windowWidthHz = 160.0, hopSeconds = .003),
        "fine frequency" to LorisOptions(resolutionHz = 20.0, windowWidthHz = 40.0, hopSeconds = .005),
    ]) {
    runAsync(
        onResult = { result: LorisResult ->
            display(
                name,
                result.visualizationHtml(
                    LorisViewOptions(
                        label = name,
                        height = 280,
                        syncGroup = "loris-resolution-comparison",
                    ),
                ),
            )
            display("$name provenance", result.provenance)
        },
        onError = { failure -> display("$name error", failure.message ?: failure.toString()) },
    ) {
        client.analyzeWavAsync(
            path,
            durationSeconds = 6.0,
            options = settings,
            onProgress = progress { text: String -> display("$name status", text) },
        )
    }
}
```

## 4. 1 · source, analyze, partials and reconstruction

<!-- field-box id="loris_source_and_partials" index="3" -->

```edn [field-box id="loris_source_and_partials" role="properties"]
{"id" "loris_source_and_partials" "index" 3 "name" "1 · source, analyze, partials and reconstruction" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · source, analyze, partials and reconstruction" "_tombstone" false "frame" #afield/rect [40.0 40.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "loris_source_and_partials_code_.kt" "lines" 38 "characters" 1352 "sha256" "9e04523a11e141e4f290c95230d7b746f6f85be19d78e9efea58c0e8b8632cd1"}]}
```

```kotlin [field-property box="loris_source_and_partials" name="code" path="loris_source_and_partials_code_.kt"]
import afield.audio.engine.WavFloatArrayLoader

val path = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
val sync = "loris-source-study"
val client = LorisClient()
display("worker", LorisNative.describe())
// Stop or rerun cancels this analysis and suppresses late output from its old run.
runAsync(
    onResult = { analyzed: LorisResult ->
        val wav = WavFloatArrayLoader.loadWavToFloatArray(path.toString())
        val source = AudioVisualizer.renderSamplesHtml(
            wav.samples,
            wav.sampleRate,
            AudioVisualizerOptions(width = 1000, waveformHeight = 95, syncGroup = sync),
            label = "Source",
        )
        display(
            "source and reconstruction",
            source +
                analyzed.visualizationHtml(
                    LorisViewOptions(height = 400, syncGroup = sync),
                ),
        )
        display("provenance", analyzed.provenance)
    },
    onError = { failure -> display("error", failure.message ?: failure.toString()) },
) {
    client.analyzeWavAsync(
        path,
        options = LorisOptions(
            resolutionHz = 35.0,
            windowWidthHz = 80.0,
            frequencyDriftHz = 50.0,
            amplitudeFloorDb = -55.0,
        ),
        onProgress = progress { text: String -> display("status", text) },
    )
}
```

## 5. 5 · label, align and morph two phrases

<!-- field-box id="loris_label_align_morph" index="4" -->

```edn [field-box id="loris_label_align_morph" role="properties"]
{"id" "loris_label_align_morph" "index" 4 "name" "5 · label, align and morph two phrases" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 · label, align and morph two phrases" "_tombstone" false "frame" #afield/rect [40.0 4400.0 480.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "loris_label_align_morph_code_.kt" "lines" 53 "characters" 2261 "sha256" "b479b6f98559627bb1505a8dd07f489e4e49c69f298e08807a3d942439173442"}]}
```

```kotlin [field-property box="loris_label_align_morph" name="code" path="loris_label_align_morph_code_.kt"]
val client = LorisClient()

runAsync(
    onResult = { morphed: LorisResult ->
        display("status", "complete: ${morphed.model.partials.size} morphed partials")
        display("morph", morphed.visualizationHtml(LorisViewOptions(maximumHz = 5000.0, syncGroup = "my-morph")))
    },
    onError = { failure -> display("error", failure.message ?: failure.toString()) },
) {
    val status = progress<String> { display("status", it) }
    val showLabels = progress<String> { display("labels", it) }
    val analysis = client.analyzeWavAsync(
        "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path,
        options = LorisOptions(
            resolutionHz = 35.0,
            windowWidthHz = 80.0,
            frequencyDriftHz = 50.0,
            amplitudeFloorDb = -55.0,
        ),
        onProgress = status,
    )
    onCancel { analysis.cancel(true) }
    analysis
        .thenCompose { analyzed ->
            status("Labeling the two phrases…")
            val film = analyzed.model
            val first = client.distillAsync(film.crop(1.6, 3.6), LorisReference.Estimated(150.0, 450.0))
            val second = client.distillAsync(film.crop(11.5, 13.5), LorisReference.Estimated(60.0, 200.0))
            onCancel {
                first.cancel(true)
                second.cancel(true)
            }
            first.thenCombine(second) { a, b -> a.model to b.model }
        }
        .thenCompose { (a, b) ->
            showLabels("A ${a.labelCounts()}  B ${b.labelCounts()}")
            val end = a.durationSeconds
            status("Aligning the loudest points…")
            // Both crops are two seconds long; align their loudest points before morphing.
            val alignment = client.dilateAsync(
                b,
                [0.0, b.loudestTimeSeconds(), end],
                [0.0, a.loudestTimeSeconds(), end],
            )
            onCancel { alignment.cancel(true) }
            alignment.thenCompose { aligned ->
                status("Morphing the aligned phrases…")
                client
                    .morphAsync(a.dropUnlabeled(), aligned.model.dropUnlabeled(), LorisEnvelope.ramp(0.4, 1.6))
                    .also { morph -> onCancel { morph.cancel(true) } }
            }
        }
}
```

