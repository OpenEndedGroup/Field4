# Field Document: audio_ai_spectral_embeddings

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. 1 · spectral spaces guide and jobs

<!-- field-box id="spectral_embedding_guide" index="0" -->

```edn [field-box id="spectral_embedding_guide" role="properties"]
{"id" "spectral_embedding_guide" "index" 0 "name" "1 · spectral spaces guide and jobs" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · spectral spaces guide and jobs" "_tombstone" false "frame" #afield/rect [40.0 40.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spectral_embedding_guide_code_.kt" "lines" 10 "characters" 1016 "sha256" "2aae92af36f2e5b776749a07b17f205019924637ff3219b81a3f814e6839ef02"}]}
```

```kotlin [field-property box="spectral_embedding_guide" name="code" path="spectral_embedding_guide_code_.kt"]
import afield.stdlib.noir.audioai.*

display("guide", """
<section style="width:1040px;max-width:100%;box-sizing:border-box;background:#151a20;color:#dbe3e9;padding:18px;border:1px solid #303c46;border-radius:8px;font:13px/1.5 system-ui,sans-serif">
  <h2 style="margin:0 0 8px;color:#7fcbd7">Short-time spectral spaces</h2>
  <p>One lightweight <code>analysis.embed_spectral</code> job records a 25 Hz trajectory as normalized mel mass, absolute log-mel, MFCC, energy, centroid, flatness, rolloff, and flux. Field owns temporal pooling, distances, MDS/t-SNE, and audition.</p>
  <p><b>Mel Wasserstein</b> measures how far spectral mass moves along a perceptual frequency axis. <b>Log-mel</b> retains detailed band shape. <b>MFCC</b> gives a compact broad spectral envelope. Run box 2 without any service; run box 3 for import → submit → monitor → await → decode → three Smile t-SNE fits → synchronized playback.</p>
</section>
""".trimIndent())
display("all durable jobs", fieldAudioJobMonitor(heightPixels = 430))
```

## 2. 3 · import, submit, monitor, project near_drowning

<!-- field-box id="analyse_near_drowning_spectral" index="1" -->

```edn [field-box id="analyse_near_drowning_spectral" role="properties"]
{"id" "analyse_near_drowning_spectral" "index" 1 "name" "3 · import, submit, monitor, project near_drowning" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 · import, submit, monitor, project near_drowning" "_tombstone" false "frame" #afield/rect [40.0 190.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "analyse_near_drowning_spectral_code_.kt" "lines" 83 "characters" 3890 "sha256" "803ffebbe0701d17f8a9bb221e28b30fdbf77818dc086e1483aecf0e2e2cc1e3"}]}
```

```kotlin [field-property box="analyse_near_drowning_spectral" name="code" path="analyse_near_drowning_spectral_code_.kt"]
import afield.Box
import afield.stdlib.AudioVisualizerOptions
import afield.stdlib.noir.audioai.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

val client = FieldAudioClient()
val localPath = Path.of(System.getProperty("user.home"), "Downloads", "near_drowning.wav")
val timeSync = "near-drowning-spectral-spaces"
display("all jobs", fieldAudioJobMonitor(heightPixels = 340))

fun Box.trackedSpectral(request: ModelOperation): CompletableFuture<AnalysisResult> =
    client.submit(request).thenCompose { accepted ->
        display("spectral job", fieldAudioJobStatusHtml(accepted))
        client.watch(
            accepted.jobId,
            onEvent = { event -> display("spectral job", fieldAudioJobStatusHtml(accepted, event)) },
            onError = { error -> display("spectral stream", "progress stream: ${error.message}") },
        )
        client.awaitAnalysis(accepted).thenApply { result ->
            client.inspect(accepted.jobId).thenAccept { job ->
                display("spectral job", fieldAudioJobStatusHtml(job))
            }
            result
        }
    }

client.assetsByFilename("near_drowning.wav").thenCompose { matches ->
    when {
        matches.size == 1 -> CompletableFuture.completedFuture(matches.single())
        matches.isEmpty() && Files.isRegularFile(localPath) -> client.importWav(localPath)
        matches.isEmpty() -> CompletableFuture.failedFuture(
            IllegalStateException("near_drowning.wav is neither imported nor present at $localPath")
        )
        else -> CompletableFuture.failedFuture(
            IllegalStateException("near_drowning.wav matched ${matches.size} assets")
        )
    }
}.thenCompose { source ->
    val audio = source.representations.firstOrNull { it.role == "canonical_project" }
        ?: source.representations.first { it.mediaType in setOf("audio/wav", "audio/x-wav") }
    val duration = requireNotNull(audio.durationSeconds) { "source audio has no duration" }
    val region = AssetRegion(source.id, 0.0, duration)
    display("source", audio.audioVisualizerHtml(
        label = "near_drowning.wav · ${"%.2f".format(duration)} s · ${audio.sampleRate} Hz",
        options = AudioVisualizerOptions(
            width = 1100, waveformHeight = 150,
            initialDurationSeconds = minOf(10.0, duration), syncGroup = timeSync,
        ),
    ))
    trackedSpectral(spectralEmbeddingAnalysis(
        region, requestedBy = "field:spectral-embeddings/near-drowning",
    ))
}.thenCompose { result ->
    display("projection", "decoding one artifact · 200 ms pool · 40 ms hop · fitting three t-SNE spaces…")
    client.analysisPreview(result.id, 1800).thenCombineAsync(
        client.spectralEmbeddingFrames(
            result, maxFrames = 600, startSeconds = 0.0, durationSeconds = 10.0,
            poolMillis = 200.0, hopMillis = 40.0,
        )
    ) { preview, frames ->
        listOf(
            SpectralEmbeddingDistance.MEL_WASSERSTEIN,
            SpectralEmbeddingDistance.LOG_MEL_COSINE,
            SpectralEmbeddingDistance.MFCC_EUCLIDEAN,
        ).mapIndexed { index, distance ->
            preview.withSpectralProjection(frames.tsne(
                dimensions = 4, distance = distance, perplexity = 30.0,
                maxIterations = 750, seed = 184921L + index,
            ))
        }
    }
}.thenAccept { previews ->
    display("projection", "complete · one cached job · ${previews.size} Field-owned spaces")
    display("spectral spaces", spectralEmbeddingPreviewDashboardHtml(
        previews, width = 1100, phaseHeight = 270, syncGroup = timeSync,
    ))
}.exceptionally { error ->
    val message = error.cause?.message ?: error.message ?: error.toString()
    display("spectral error", """<pre style="color:#f1aaaa;background:#28191b;padding:12px;white-space:pre-wrap">$message</pre>""")
    null
}
```

## 3. 2 · model-free synchronized spectral gallery

<!-- field-box id="spectral_embedding_visualizer_gallery" index="2" -->

```edn [field-box id="spectral_embedding_visualizer_gallery" role="properties"]
{"id" "spectral_embedding_visualizer_gallery" "index" 2 "name" "2 · model-free synchronized spectral gallery" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · model-free synchronized spectral gallery" "_tombstone" false "frame" #afield/rect [520.0 40.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spectral_embedding_visualizer_gallery_code_.kt" "lines" 48 "characters" 2174 "sha256" "bd5627fa13901b883caa567f04a4aed1599e50a368577d0a5ecaadb0048ea151"}]}
```

```kotlin [field-property box="spectral_embedding_visualizer_gallery" name="code" path="spectral_embedding_visualizer_gallery_code_.kt"]
import afield.stdlib.*
import afield.stdlib.noir.audioai.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val demoSync = "spectral-space-gallery"
val rate = 8_000
val duration = 14.0
val samples = FloatArray((rate * duration).toInt()) { i ->
    val t = i / rate.toDouble()
    val sweep = 170.0 + 520.0 * (.5 + .5 * kotlin.math.sin(t * .37))
    (.18 * kotlin.math.sin(2 * Math.PI * sweep * t) +
        .07 * kotlin.math.sin(2 * Math.PI * (1250 + 420 * kotlin.math.sin(t * .19)) * t)).toFloat()
}
display("source", AudioVisualizer.renderSamplesHtml(
    samples, rate,
    AudioVisualizerOptions(width = 1100, waveformHeight = 105, syncGroup = demoSync),
    label = "model-free spectral calibration · one cursor",
))
val times = (0..280).map { it * .05 }
fun s(name: String, values: List<Double>) = AnalysisSeries(
    name, name.replace('_', ' '), timesSeconds = times.map { it as Double? },
    values = values.map { it as Double? },
)
fun preview(label: String, phase: Double) = AnalysisPreview(
    operation = "analysis.embed_spectral", durationSeconds = duration,
    series = listOf(
        s("energy_dbfs", times.map { -34.0 + 9 * kotlin.math.sin(it * .43) }),
        s("spectral_flux", times.map { .08 + .6 * Math.pow(kotlin.math.abs(kotlin.math.sin(it * 1.1)), 8.0) }),
        s("spectral_centroid_hz", times.map { 900.0 + 650 * kotlin.math.sin(it * .37) }),
        s("spectral_flatness", times.map { .08 + .06 * kotlin.math.sin(it * .71) }),
    ) + (0..3).map { axis ->
        s("tsne_$axis", times.map { t ->
            kotlin.math.sin(t * (.37 + axis * .13) + phase + axis) + .24 * kotlin.math.sin(t * 2.1)
        })
    },
    analysis = buildJsonObject {
        put("backend", "model-free calibration")
        put("projection_method", "t-SNE")
        put("projection_series_prefix", "tsne_")
        put("projection_distance", label)
        put("projection_sampled_frames", times.size)
    },
)
display("spectral spaces", spectralEmbeddingPreviewDashboardHtml(
    listOf(preview("mel Wasserstein-1", 0.0), preview("MFCC Euclidean", 1.4)),
    width = 1100, phaseHeight = 245, syncGroup = demoSync,
))
```

