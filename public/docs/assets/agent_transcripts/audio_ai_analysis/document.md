# Field Document: audio_ai_analysis

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. 1 · analysis guide and jobs

<!-- field-box id="analysis_guide" index="0" -->

```edn [field-box id="analysis_guide" role="properties"]
{"id" "analysis_guide" "index" 0 "name" "1 · analysis guide and jobs" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · analysis guide and jobs" "_tombstone" false "frame" #afield/rect [40.0 40.0 340.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "analysis_guide_code_.kt" "lines" 10 "characters" 1040 "sha256" "dab10c36384979e9e50799a3912b41a878ae7c458d0b604c2b475ac802593938"}]}
```

```kotlin [field-property box="analysis_guide" name="code" path="analysis_guide_code_.kt"]
import afield.stdlib.noir.audioai.*

display("guide", """
<section style="width:1040px;max-width:100%;box-sizing:border-box;background:#151a20;color:#dbe3e9;padding:18px;border:1px solid #303c46;border-radius:8px;font:13px/1.5 system-ui,sans-serif">
  <h2 style="margin:0 0 8px;color:#7fcbd7">Beat and pitch analysis</h2>
  <p><b>librosa</b> supplies the onset envelope, multi-scale tempograms, beat grid and monophonic pYIN track. <b>Basic Pitch</b> supplies polyphonic note events, activations, pitch bends and MIDI. Essentia is not loaded by this worker.</p>
  <p>Run box 2 for a model-free visualizer tour. Run box 3 for the complete live path: filename lookup → source window → three submitted jobs → WebSocket progress → durable results → synchronized analysis board. The source player and every analysis lane share hover, zoom and playback time; click any plot to seek the recording. Re-running unchanged requests is a cache hit.</p>
</section>
""".trimIndent())
display("all durable jobs", fieldAudioJobMonitor(heightPixels = 430))
```

## 2. 2 · synchronized visualizer gallery

<!-- field-box id="analysis_visualizer_gallery" index="1" -->

```edn [field-box id="analysis_visualizer_gallery" role="properties"]
{"id" "analysis_visualizer_gallery" "index" 1 "name" "2 · synchronized visualizer gallery" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · synchronized visualizer gallery" "_tombstone" false "frame" #afield/rect [420.0 40.0 340.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "analysis_visualizer_gallery_code_.kt" "lines" 54 "characters" 2824 "sha256" "b3dfa73cde5fe498e066d06925a75325b68ab412caa4facaeeef22ce1d055d3e"}]}
```

```kotlin [field-property box="analysis_visualizer_gallery" name="code" path="analysis_visualizer_gallery_code_.kt"]
import afield.stdlib.*
import afield.stdlib.noir.audioai.*

val gallerySync = "analysis-gallery"
val galleryRate = 8_000
val galleryAudio = FloatArray(galleryRate * 18) { i ->
    val t = i / galleryRate.toDouble()
    val pulse = 0.32 * kotlin.math.exp(-18.0 * (t % 0.5))
    (0.38 * kotlin.math.sin(2.0 * Math.PI * (174.0 + 22.0 * kotlin.math.sin(t * 0.34)) * t) + pulse).toFloat()
}
display("source", AudioVisualizer.renderSamplesHtml(
    galleryAudio, galleryRate,
    AudioVisualizerOptions(width = 1100, waveformHeight = 105, syncGroup = gallerySync),
    label = "model-free synchronized source · play or seek here",
))

val times = (0..180).map { it / 10.0 }
fun series(name: String, label: String, values: List<Double>, unit: String? = null) =
    AnalysisSeries(name, label, unit, times.map { it as Double? }, values.map { it as Double? })

val onset = times.map { t -> 0.08 + 0.82 * Math.pow(kotlin.math.max(0.0, kotlin.math.sin(t * 2.4)), 8.0) }
val beats = (1..35).map { it * 0.5 }
val beatPreview = AnalysisPreview(
    operation = "analysis.beats", durationSeconds = 18.0,
    series = listOf(
        series("onset_strength", "onset strength", onset),
        series("tempo_0_bpm", "tempo · 2 s", times.map { 116.0 + 13.0 * kotlin.math.sin(it * 0.72) }, "bpm"),
        series("tempo_1_bpm", "tempo · 8 s", times.map { 112.0 + 5.0 * kotlin.math.sin(it * 0.23) }, "bpm"),
        series("tempo_0_confidence", "confidence · 2 s", times.map { 0.45 + 0.35 * kotlin.math.sin(it * 0.41) }),
        series("tempo_1_confidence", "confidence · 8 s", times.map { 0.72 + 0.16 * kotlin.math.sin(it * 0.19) }),
    ), beatTimesSeconds = beats,
)
val monoPreview = AnalysisPreview(
    operation = "analysis.pitch_mono", durationSeconds = 18.0,
    series = listOf(
        series("frequency_hz", "pYIN F0", times.map { 174.0 * Math.pow(2.0, (7.0 * kotlin.math.sin(it * 0.34) + 3.0 * kotlin.math.sin(it * 1.1)) / 12.0) }, "Hz"),
        series("voiced_probability", "voiced probability", times.map { 0.55 + 0.42 * kotlin.math.sin(it * 0.57) }),
    ),
)
val notes = (0 until 28).map { i ->
    val start = i * 0.62
    AnalysisNote(start, start + 0.34 + (i % 4) * 0.13, 48 + (i * 5) % 25, 0.42 + (i % 6) * 0.09)
}
val polyPreview = AnalysisPreview(
    operation = "analysis.pitch_poly", durationSeconds = 18.0,
    series = listOf(
        series("maximum_note_activation", "maximum note activation", times.map { 0.38 + 0.5 * kotlin.math.abs(kotlin.math.sin(it * 1.7)) }),
        series("active_pitch_count", "active pitch count", times.map { 1.0 + kotlin.math.floor(4.0 * kotlin.math.abs(kotlin.math.sin(it * 0.52))) }),
    ), notes = notes,
)
display("analysis board", analysisPreviewDashboardHtml(
    listOf(beatPreview, monoPreview, polyPreview),
    width = 1100, panelHeight = 230, syncGroup = gallerySync,
))
```

## 3. 3 · submit, monitor, await, visualize Nancy

<!-- field-box id="analyse_nancy" index="2" -->

```edn [field-box id="analyse_nancy" role="properties"]
{"id" "analyse_nancy" "index" 2 "name" "3 · submit, monitor, await, visualize Nancy" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 · submit, monitor, await, visualize Nancy" "_tombstone" false "frame" #afield/rect [40.0 140.0 340.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "analyse_nancy_code_.kt" "lines" 55 "characters" 2494 "sha256" "830593624ed4d0c9c212c71dc1e9b118c9480b74f85df5bc111fd4c2af8e6051"}]}
```

```kotlin [field-property box="analyse_nancy" name="code" path="analyse_nancy_code_.kt"]
import afield.stdlib.AudioVisualizerOptions
import afield.stdlib.noir.audioai.*
import java.util.concurrent.CompletableFuture

val client = FieldAudioClient()
val start = 18.0
val duration = 24.0
val timeSync = "nancy-analysis"
display("job monitor", fieldAudioJobMonitor(heightPixels = 330))

client.assetByFilename("*Nancy*").thenCompose { source ->
    val region = AssetRegion(source.id, startSeconds = start, durationSeconds = duration)
    val canonical = source.representations.first { it.role == "canonical_project" }
    display("source", canonical.audioVisualizerHtml(
        label = "Henry Mancini · Nancy · analysis window",
        options = AudioVisualizerOptions(
            width = 1040, waveformHeight = 150,
            initialStartSeconds = start, initialDurationSeconds = duration,
            initialPositionSeconds = start,
            syncGroup = timeSync,
        ),
    ))

    val requests = listOf(
        "beats" to multiScaleBeatAnalysis(region, requestedBy = "field:audio-analysis/nancy"),
        "monophonic pitch" to monophonicPitchAnalysis(region, requestedBy = "field:audio-analysis/nancy"),
        "polyphonic pitch" to polyphonicPitchAnalysis(region, requestedBy = "field:audio-analysis/nancy"),
    )
    val results = requests.map { (label, request) ->
        client.submit(request).thenCompose { accepted ->
            display("$label job", fieldAudioJobStatusHtml(accepted))
            client.watch(
                accepted.jobId,
                onEvent = { event -> display("$label job", fieldAudioJobStatusHtml(accepted, event)) },
                onError = { error -> display("$label job stream", "progress stream: ${error.message}") },
            )
            client.awaitAnalysis(accepted).thenApply { result ->
                client.inspect(accepted.jobId).thenAccept { job -> display("$label job", fieldAudioJobStatusHtml(job)) }
                result
            }
        }
    }
    CompletableFuture.allOf(*results.toTypedArray()).thenApply {
        results.map { it.join() }
    }
}.thenAccept { results ->
    display("synchronized analysis", analysisDashboardHtml(
        results, width = 1040, panelHeight = 250,
        syncGroup = timeSync, timeOffsetSeconds = start,
    ))
}.exceptionally { error ->
    val message = error.cause?.message ?: error.message ?: error.toString()
    display("analysis error", """<pre style="color:#f1aaaa;background:#28191b;padding:12px;white-space:pre-wrap">$message</pre>""")
    null
}
```

