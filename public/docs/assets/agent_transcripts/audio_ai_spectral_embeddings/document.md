# Field Document: audio_ai_spectral_embeddings

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 · spectral spaces guide and jobs

<!-- field-box id="spectral_embedding_guide" index="0" -->

```edn [field-box id="spectral_embedding_guide" role="properties"]
{"id" "spectral_embedding_guide" "index" 0 "name" "1 · spectral spaces guide and jobs" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · spectral spaces guide and jobs" "_tombstone" false "frame" #afield/rect [40.0 40.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spectral_embedding_guide_code_.kt" "lines" 12 "characters" 1054 "sha256" "ec81099204a1f9cf7a31dc78a3988b6df56919008030dceb1455eb509a14d859"}]}
```

```kotlin [field-property box="spectral_embedding_guide" name="code" path="spectral_embedding_guide_code_.kt"]
display(
    "guide",
    """
    <section style="width:1040px;max-width:100%;box-sizing:border-box;background:#151a20;color:#dbe3e9;padding:18px;border:1px solid #303c46;border-radius:8px;font:13px/1.5 system-ui,sans-serif">
      <h2 style="margin:0 0 8px;color:#7fcbd7">Short-time spectral spaces</h2>
      <p>One lightweight <code>analysis.embed_spectral</code> job records a 25 Hz trajectory as normalized mel mass, absolute log-mel, MFCC, energy, centroid, flatness, rolloff, and flux. Field owns temporal pooling, distances, MDS/t-SNE, and audition.</p>
      <p><b>Mel Wasserstein</b> measures how far spectral mass moves along a perceptual frequency axis. <b>Log-mel</b> retains detailed band shape. <b>MFCC</b> gives a compact broad spectral envelope. Run box 2 from the committed real result without any service; run box 3 for import → submit → monitor → await → decode → three Smile t-SNE fits → synchronized playback.</p>
    </section>
    """
        .trimIndent(),
)
display("all durable jobs", fieldAudioJobMonitor(heightPixels = 430))
```

## 2. 3 · import, submit, monitor, project near_drowning

<!-- field-box id="analyse_near_drowning_spectral" index="1" -->

```edn [field-box id="analyse_near_drowning_spectral" role="properties"]
{"id" "analyse_near_drowning_spectral" "index" 1 "name" "3 · import, submit, monitor, project near_drowning" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 · import, submit, monitor, project near_drowning" "_tombstone" false "frame" #afield/rect [40.0 190.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "analyse_near_drowning_spectral_code_.kt" "lines" 130 "characters" 5718 "sha256" "581a6dc364df40d73f06bc568959100358c9daea263451cebb221b90a76a637b"}]}
```

```kotlin [field-property box="analyse_near_drowning_spectral" name="code" path="analyse_near_drowning_spectral_code_.kt"]
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

val client = FieldAudioClient()
val localPath = Path.of(System.getProperty("user.home"), "Downloads", "near_drowning.wav")
val timeSync = "near-drowning-spectral-spaces"
val poolMillis = 200.0
val hopMillis = 40.0
val perplexity = 30.0
val iterations = 750
val seed = 184921L

display("all jobs", fieldAudioJobMonitor(heightPixels = 340))
runAsync(
    onResult = { previews: List<AnalysisPreview> ->
        display("projection", "complete · one cached job · ${previews.size} Field-owned spaces")
        display(
            "spectral spaces",
            spectralEmbeddingPreviewDashboardHtml(
                previews,
                width = 1100,
                phaseHeight = 270,
                syncGroup = timeSync,
            ),
        )
    },
    onError = { failure ->
        markdown("spectral error", "Analysis failed: ${(failure.message ?: failure.toString()).md}")
    },
) {
    val showSource = progress<String> { display("source", it) }
    val showProjection = progress<String> { display("projection", it) }
    // HTTP continuations run on other threads. New tracked legs still belong to this run.
    val run = RunScope.current()
    val tracked: (ModelOperation) -> CompletableFuture<AnalysisResult> = { request ->
        RunScope.withCurrent(run) {
                client.trackAnalysis(
                    request,
                    onAccepted = { display("spectral job", fieldAudioJobStatusHtml(it)) },
                    onProgress = { job, event -> display("spectral job", fieldAudioJobStatusHtml(job, event)) },
                    onCompleted = { display("spectral job", fieldAudioJobStatusHtml(it)) },
                    onError = {}, // The outer operation reports failure once.
                )
            }
            .also { pending -> onCancel { pending.cancel(true) } }
    }
    val lookup = client.assetsByFilename("near_drowning.wav")
    onCancel { lookup.cancel(true) }
    lookup
        .thenCompose { matches ->
            if (future.isDone) throw CancellationException("run ended")
            when {
                matches.size == 1 -> CompletableFuture.completedFuture(matches.single())
                matches.isEmpty() && Files.isRegularFile(localPath) ->
                    client.importWav(localPath).also { pending -> onCancel { pending.cancel(true) } }
                matches.isEmpty() ->
                    CompletableFuture.failedFuture(
                        IllegalStateException("near_drowning.wav is neither imported nor present at $localPath"),
                    )
                else ->
                    CompletableFuture.failedFuture(
                        IllegalStateException("near_drowning.wav matched ${matches.size} assets"),
                    )
            }
        }
        .thenCompose { source ->
            if (future.isDone) throw CancellationException("run ended")
            val audio =
                source.representations.firstOrNull { it.role == "canonical_project" }
                    ?: source.representations.first { it.mediaType in ["audio/wav", "audio/x-wav"] }
            val duration = requireNotNull(audio.durationSeconds) { "source audio has no duration" }
            showSource(
                audio.audioVisualizerHtml(
                    label = "near_drowning.wav · ${duration.f2} s · ${audio.sampleRate} Hz",
                    options = AudioVisualizerOptions(
                        width = 1100,
                        waveformHeight = 150,
                        initialDurationSeconds = minOf(10.0, duration),
                        syncGroup = timeSync,
                    ),
                ),
            )
            tracked(
                spectralEmbeddingAnalysis(
                    AssetRegion(source.id, 0.0, duration),
                    requestedBy = "field:spectral-embeddings/near-drowning",
                ),
            )
        }
        .thenCompose { result ->
            if (future.isDone) throw CancellationException("run ended")
            showProjection(
                "decoding one artifact · ${poolMillis.f0} ms pool · ${hopMillis.f0} ms hop · fitting three t-SNE spaces…",
            )
            val preview = client.analysisPreview(result.id, 1800)
            val frames = client.spectralEmbeddingFrames(
                result,
                maxFrames = 600,
                startSeconds = 0.0,
                durationSeconds = 10.0,
                poolMillis = poolMillis,
                hopMillis = hopMillis,
            )
            onCancel {
                preview.cancel(true)
                frames.cancel(true)
            }
            preview.thenCombineAsync(frames) { summary, samples ->
                [
                    SpectralEmbeddingDistance.MEL_WASSERSTEIN,
                    SpectralEmbeddingDistance.LOG_MEL_COSINE,
                    SpectralEmbeddingDistance.MFCC_EUCLIDEAN,
                ].mapIndexed { index, distance ->
                    // The fitter has no interrupt hook; stop suppresses output and skips later fits.
                    if (future.isDone) throw CancellationException("run ended")
                    summary.withSpectralProjection(
                        samples.tsne(
                            dimensions = 4,
                            distance = distance,
                            perplexity = perplexity,
                            maxIterations = iterations,
                            seed = seed + index,
                        ),
                    )
                }
            }
        }
}
```

## 3. 2 · real cached spectral projections

<!-- field-box id="spectral_embedding_visualizer_gallery" index="2" -->

```edn [field-box id="spectral_embedding_visualizer_gallery" role="properties"]
{"id" "spectral_embedding_visualizer_gallery" "index" 2 "name" "2 · real cached spectral projections" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · real cached spectral projections" "_tombstone" false "frame" #afield/rect [520.0 40.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spectral_embedding_visualizer_gallery_code_.kt" "lines" 28 "characters" 954 "sha256" "0bcb4072b92d21f29641eb2e7595b2d37a6e63350348752608ac7a8684c8abc7"}]}
```

```kotlin [field-property box="spectral_embedding_visualizer_gallery" name="code" path="spectral_embedding_visualizer_gallery_code_.kt"]
import java.io.File

val json = FieldAudioClient.json
val folder = File("public/docs/assets/audio_ai_spectral_embeddings")
val preview = json.decodeFromString<AnalysisPreview>(File(folder, "result-preview.json").readText())
val frames = decodeSpectralEmbeddingMessagePack(
    File(folder, "frames.msgpack").readBytes(),
    maxFrames = 600,
    durationSeconds = 10.0,
    poolMillis = 200.0,
    hopMillis = 40.0,
)
val previews = [
    SpectralEmbeddingDistance.MEL_WASSERSTEIN,
    SpectralEmbeddingDistance.LOG_MEL_COSINE,
    SpectralEmbeddingDistance.MFCC_EUCLIDEAN,
].mapIndexed { index, distance ->
    preview.withSpectralProjection(
        frames.tsne(
            dimensions = 4,
            distance = distance,
            perplexity = 30.0,
            maxIterations = 750,
            seed = 184921L + index,
        ),
    )
}
display("spectral spaces", spectralEmbeddingPreviewDashboardHtml(previews, width = 1040, phaseHeight = 210))
```

