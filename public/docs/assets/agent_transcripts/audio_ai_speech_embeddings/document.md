# Field Document: audio_ai_speech_embeddings

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. 1 · speech embedding guide and jobs

<!-- field-box id="speech_embedding_guide" index="0" -->

```edn [field-box id="speech_embedding_guide" role="properties"]
{"id" "speech_embedding_guide" "index" 0 "name" "1 · speech embedding guide and jobs" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · speech embedding guide and jobs" "_tombstone" false "frame" #afield/rect [40.0 40.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "speech_embedding_guide_code_.kt" "lines" 10 "characters" 1214 "sha256" "6c384cfa850adef15a479532c6e0c2583402d588bfffbd46a340593f3288dc85"}]}
```

```kotlin [field-property box="speech_embedding_guide" name="code" path="speech_embedding_guide_code_.kt"]
import afield.stdlib.noir.audioai.*

display("guide", """
<section style="width:1040px;max-width:100%;box-sizing:border-box;background:#151a20;color:#dbe3e9;padding:18px;border:1px solid #303c46;border-radius:8px;font:13px/1.5 system-ui,sans-serif">
  <h2 style="margin:0 0 8px;color:#7fcbd7">Speech-reactive learned spaces</h2>
  <p><b>emotion2vec</b> emphasizes vocal affect, prosody and texture. <b>WavLM Base+</b> supplies a broader self-supervised speech representation; layer 6 is a useful acoustic/phonetic compromise. Both produce 50 Hz frame vectors, explicit WebRTC voice activity, energy, novelty, and a compact binary MessagePack artifact.</p>
  <p>Run box 2 to inspect the synchronized visualizer without a worker. Run box 3 for the complete local path: find or import <code>~/Downloads/near_drowning.wav</code> → submit two durable jobs → watch progress → retrieve cached results → pool 500 ms of acoustic evidence every 40 ms (25 Hz) → run fixed-seed cosine t-SNE in Field through Smile → play the recording through axes 0×1 and 2×3. Hover a path to inspect its time; click it to seek the audio.</p>
</section>
""".trimIndent())
display("all durable jobs", fieldAudioJobMonitor(heightPixels = 430))
```

## 2. 3 · import, submit, monitor, audition near_drowning

<!-- field-box id="analyse_near_drowning_speech" index="1" -->

```edn [field-box id="analyse_near_drowning_speech" role="properties"]
{"id" "analyse_near_drowning_speech" "index" 1 "name" "3 · import, submit, monitor, audition near_drowning" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 · import, submit, monitor, audition near_drowning" "_tombstone" false "frame" #afield/rect [40.0 190.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "analyse_near_drowning_speech_code_.kt" "lines" 99 "characters" 4505 "sha256" "708079f878738f0fa7eef0379b7065e9bf14f61370e01a43dbeb50646b70c860"}]}
```

```kotlin [field-property box="analyse_near_drowning_speech" name="code" path="analyse_near_drowning_speech_code_.kt"]
import afield.Box
import afield.stdlib.AudioVisualizerOptions
import afield.stdlib.SmileMdsDistance
import afield.stdlib.noir.audioai.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

val client = FieldAudioClient()
val localPath = Path.of(System.getProperty("user.home"), "Downloads", "near_drowning.wav")
val timeSync = "near-drowning-speech-spaces"
val projectionStartSeconds = 0.0
val projectionDurationSeconds = 10.0
val projectionPoolMillis = 500.0
val projectionHopMillis = 40.0
display("all jobs", fieldAudioJobMonitor(heightPixels = 340))

fun Box.trackedSpeech(label: String, request: ModelOperation): CompletableFuture<AnalysisResult> =
    client.submit(request).thenCompose { accepted ->
        display("$label job", fieldAudioJobStatusHtml(accepted))
        client.watch(
            accepted.jobId,
            onEvent = { event -> display("$label job", fieldAudioJobStatusHtml(accepted, event)) },
            onError = { error -> display("$label stream", "progress stream: ${error.message}") },
        )
        client.awaitAnalysis(accepted).thenApply { result ->
            client.inspect(accepted.jobId).thenAccept { job ->
                display("$label job", fieldAudioJobStatusHtml(job))
            }
            result
        }
    }

client.assetsByFilename("*near_drowning*").thenCompose { matches ->
    when {
        matches.size == 1 -> CompletableFuture.completedFuture(matches.single())
        matches.isEmpty() && Files.isRegularFile(localPath) -> client.importWav(localPath)
        matches.isEmpty() -> CompletableFuture.failedFuture(
            IllegalStateException("near_drowning.wav is neither imported nor present at $localPath")
        )
        else -> CompletableFuture.failedFuture(
            IllegalStateException("near_drowning filename matched ${matches.size} assets")
        )
    }
}.thenCompose { source ->
    val audio = source.representations.firstOrNull { it.role == "canonical_project" }
        ?: source.representations.first { it.mediaType in setOf("audio/wav", "audio/x-wav") }
    val duration = requireNotNull(audio.durationSeconds) { "source audio has no duration" }
    val region = AssetRegion(source.id, startSeconds = 0.0, durationSeconds = duration)
    display("source", audio.audioVisualizerHtml(
        label = "near_drowning.wav · ${"%.2f".format(duration)} s · ${audio.sampleRate} Hz",
        options = AudioVisualizerOptions(
            width = 1100, waveformHeight = 150,
            initialStartSeconds = 0.0,
            initialDurationSeconds = minOf(projectionDurationSeconds, duration),
            initialPositionSeconds = 0.0,
            syncGroup = timeSync,
        ),
    ))

    val results = listOf(
        "emotion2vec" to emotion2VecEmbeddingAnalysis(
            region, requestedBy = "field:speech-embeddings/near-drowning",
        ),
        "WavLM Base+" to wavlmBasePlusEmbeddingAnalysis(
            region, layer = 6, requestedBy = "field:speech-embeddings/near-drowning",
        ),
    ).map { (label, request) -> trackedSpeech(label, request) }
    CompletableFuture.allOf(*results.toTypedArray()).thenApply { results.map { it.join() } }
}.thenCompose { results ->
    display("projection", "fitting 500 ms context windows at 25 Hz from 0–10 s with Field Smile t-SNE…")
    val previews = results.map { result ->
        client.speechEmbeddingSmileTsnePreview(
            result,
            maxFrames = 600,
            startSeconds = projectionStartSeconds,
            durationSeconds = projectionDurationSeconds,
            poolMillis = projectionPoolMillis,
            hopMillis = projectionHopMillis,
            dimensions = 4,
            distance = SmileMdsDistance.COSINE,
            perplexity = 30.0,
            seed = 184921L,
            previewPoints = 1800,
        )
    }
    CompletableFuture.allOf(*previews.toTypedArray()).thenApply {
        previews.map { it.join() }
    }
}.thenAccept { previews ->
    display("projection", "Field Smile t-SNE complete · 500 ms pool · 40 ms hop · ${previews.size} spaces")
    display("speech spaces", speechEmbeddingPreviewDashboardHtml(
        previews, width = 1100, phaseHeight = 270, syncGroup = timeSync,
    ))
}.exceptionally { error ->
    val message = error.cause?.message ?: error.message ?: error.toString()
    display("speech analysis error", """<pre style="color:#f1aaaa;background:#28191b;padding:12px;white-space:pre-wrap">$message</pre>""")
    null
}
```

## 3. 2 · model-free synchronized MDS gallery

<!-- field-box id="speech_embedding_visualizer_gallery" index="2" -->

```edn [field-box id="speech_embedding_visualizer_gallery" role="properties"]
{"id" "speech_embedding_visualizer_gallery" "index" 2 "name" "2 · model-free synchronized MDS gallery" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · model-free synchronized MDS gallery" "_tombstone" false "frame" #afield/rect [520.0 40.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "speech_embedding_visualizer_gallery_code_.kt" "lines" 52 "characters" 2758 "sha256" "80a0338c4915ae633de19fc7f5b73d94fa13f99e4c8e7ac0cd6f535603aed07d"}]}
```

```kotlin [field-property box="speech_embedding_visualizer_gallery" name="code" path="speech_embedding_visualizer_gallery_code_.kt"]
import afield.stdlib.*
import afield.stdlib.noir.audioai.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val demoSync = "speech-space-gallery"
val demoRate = 8_000
val demoDuration = 18.0
val demoAudio = FloatArray((demoRate * demoDuration).toInt()) { i ->
    val t = i / demoRate.toDouble()
    val phrase = .5 + .5 * kotlin.math.sin(t * .72)
    val carrier = kotlin.math.sin(2.0 * Math.PI * (118.0 + 34.0 * phrase) * t)
    val formant = kotlin.math.sin(2.0 * Math.PI * (410.0 + 130.0 * kotlin.math.sin(t * .31)) * t)
    (.22 * carrier + .08 * phrase * formant).toFloat()
}
display("source", AudioVisualizer.renderSamplesHtml(
    demoAudio, demoRate,
    AudioVisualizerOptions(width = 1100, waveformHeight = 105, syncGroup = demoSync),
    label = "model-free calibration source · one playhead for every portrait",
))

val times = (0..360).map { it * .05 }
fun speechSeries(name: String, values: List<Double>) = AnalysisSeries(
    name = name, label = name.replace('_', ' '),
    timesSeconds = times.map { it as Double? }, values = values.map { it as Double? },
)
val voice = times.map { t -> (.5 + .5 * kotlin.math.sin(t * 1.45)).coerceIn(0.0, 1.0) }
val energy = times.map { t -> -42.0 + 25.0 * voice[(t / .05).toInt().coerceAtMost(360)] }
val novelty = times.map { t -> .08 + .5 * Math.pow(kotlin.math.abs(kotlin.math.sin(t * 1.9)), 9.0) }
fun preview(backend: String, phase: Double, radial: Double) = AnalysisPreview(
    operation = "analysis.embed_speech", durationSeconds = demoDuration,
    series = listOf(
        speechSeries("voice_activity", voice),
        speechSeries("energy_dbfs", energy),
        speechSeries("embedding_novelty", novelty),
        speechSeries("mds_0", times.map { t -> kotlin.math.cos(t * .55 + phase) * (.55 + radial * voice[(t / .05).toInt()]) + .18 * kotlin.math.cos(t * 2.7) }),
        speechSeries("mds_1", times.map { t -> kotlin.math.sin(t * .55 + phase) * (.55 + radial * voice[(t / .05).toInt()]) + .16 * kotlin.math.sin(t * 2.1) }),
        speechSeries("mds_2", times.map { t -> kotlin.math.cos(t * 1.07 + .28 * kotlin.math.sin(t * .23 + phase)) * (.35 + .55 * voice[(t / .05).toInt()]) }),
        speechSeries("mds_3", times.map { t -> kotlin.math.sin(t * .83 + phase) * (.30 + .48 * voice[(t / .05).toInt()]) }),
    ),
    analysis = buildJsonObject {
        put("backend", backend)
        if (backend.contains("wavlm")) put("wavlm_layer", 6)
    },
)
display("learned spaces", speechEmbeddingPreviewDashboardHtml(
    listOf(
        preview("analysis.emotion2vec · synthetic calibration", 0.0, .32),
        preview("analysis.wavlm_base_plus · synthetic calibration", 1.2, .18),
    ),
    width = 1100, phaseHeight = 245, syncGroup = demoSync,
))
```

