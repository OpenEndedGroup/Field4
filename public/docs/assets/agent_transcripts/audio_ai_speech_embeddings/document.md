# Field Document: audio_ai_speech_embeddings

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 · speech embedding guide and jobs

<!-- field-box id="speech_embedding_guide" index="0" -->

```edn [field-box id="speech_embedding_guide" role="properties"]
{"id" "speech_embedding_guide" "index" 0 "name" "1 · speech embedding guide and jobs" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · speech embedding guide and jobs" "_tombstone" false "frame" #afield/rect [40.0 40.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "speech_embedding_guide_code_.kt" "lines" 12 "characters" 1221 "sha256" "a208058fe04dacce848a27abede2bea50b9eeb326f601aef7ec213ccb23c43d4"}]}
```

```kotlin [field-property box="speech_embedding_guide" name="code" path="speech_embedding_guide_code_.kt"]
display(
    "guide",
    """
    <section style="width:1040px;max-width:100%;box-sizing:border-box;background:#151a20;color:#dbe3e9;padding:18px;border:1px solid #303c46;border-radius:8px;font:13px/1.5 system-ui,sans-serif">
      <h2 style="margin:0 0 8px;color:#7fcbd7">Speech-reactive learned spaces</h2>
      <p><b>emotion2vec</b> emphasizes vocal affect, prosody and texture. <b>WavLM Base+</b> supplies a broader self-supervised speech representation; layer 6 is a useful acoustic/phonetic compromise. Both produce 50 Hz frame vectors, explicit WebRTC voice activity, energy, novelty, and a compact binary MessagePack artifact.</p>
      <p>Run box 2 to inspect the synchronized visualizer without a worker. Run box 3 for the complete local path: find or import <code>~/Downloads/near_drowning.wav</code> → submit two durable jobs → watch progress → retrieve cached results → pool 500 ms of acoustic evidence every 40 ms (25 Hz) → run fixed-seed cosine t-SNE in Field through Smile → play the recording through axes 0×1 and 2×3. Hover a path to inspect its time; click it to seek the audio.</p>
    </section>
    """
        .trimIndent(),
)
display("all durable jobs", fieldAudioJobMonitor(heightPixels = 430))
```

## 2. 3 · import, submit, monitor, audition near_drowning

<!-- field-box id="analyse_near_drowning_speech" index="1" -->

```edn [field-box id="analyse_near_drowning_speech" role="properties"]
{"id" "analyse_near_drowning_speech" "index" 1 "name" "3 · import, submit, monitor, audition near_drowning" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 · import, submit, monitor, audition near_drowning" "_tombstone" false "frame" #afield/rect [40.0 190.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "analyse_near_drowning_speech_code_.kt" "lines" 114 "characters" 5212 "sha256" "3ff7839be5fb513812d0ea6064cdbe10794508dc41dd3a6d5a68300b8c28db2e"}]}
```

```kotlin [field-property box="analyse_near_drowning_speech" name="code" path="analyse_near_drowning_speech_code_.kt"]
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await

val client = cached { FieldAudioClient() }
val localPath = Path.of(System.getProperty("user.home"), "Downloads", "near_drowning.wav")
val timeSync = "near-drowning-speech-spaces"
val projectionStartSeconds = 0.0
val projectionDurationSeconds = 10.0
val projectionPoolMillis: Double? = 500.0
val projectionHopMillis: Double? = 40.0
display("all jobs", fieldAudioJobMonitor(heightPixels = 340))

go {
    try {
        val matches = client.assetsByFilename("*near_drowning*").await()
        val source = when {
            matches.size == 1 -> matches.single()
            matches.isEmpty() && Files.isRegularFile(localPath) -> client.importWav(localPath).await()
            matches.isEmpty() ->
                throw IllegalStateException("near_drowning.wav is neither imported nor present at $localPath")
            else -> throw IllegalStateException("near_drowning filename matched ${matches.size} assets")
        }
        val audio =
            source.representations.firstOrNull { it.role == "canonical_project" }
                ?: source.representations.first { it.mediaType in setOf("audio/wav", "audio/x-wav") }
        val duration = requireNotNull(audio.durationSeconds) { "source audio has no duration" }
        val region = AssetRegion(source.id, startSeconds = 0.0, durationSeconds = duration)
        display(
            "source",
            audio.audioVisualizerHtml(
                label = "near_drowning.wav · ${duration.f2} s · ${audio.sampleRate} Hz",
                options = AudioVisualizerOptions(
                    width = 1100,
                    waveformHeight = 150,
                    initialStartSeconds = 0.0,
                    initialDurationSeconds = minOf(projectionDurationSeconds, duration),
                    initialPositionSeconds = 0.0,
                    syncGroup = timeSync,
                ),
            ),
        )
        val requests = [
            "emotion2vec" to
                emotion2VecEmbeddingAnalysis(region, requestedBy = "field:speech-embeddings/near-drowning"),
            "WavLM Base+" to
                wavlmBasePlusEmbeddingAnalysis(
                    region,
                    layer = 6,
                    requestedBy = "field:speech-embeddings/near-drowning",
                ),
        ]
        val results = coroutineScope {
            requests
                .map { (label, request) ->
                    async {
                        client
                            .trackAnalysis(
                                request,
                                onAccepted = { display("$label job", fieldAudioJobStatusHtml(it)) },
                                onProgress = { job, event ->
                                    display("$label job", fieldAudioJobStatusHtml(job, event))
                                },
                                onCompleted = { display("$label job", fieldAudioJobStatusHtml(it)) },
                                onError = {}, // The surrounding coroutine reports the combined failure.
                            )
                            .await()
                    }
                }
                .awaitAll()
        }
        val projectionDescription =
            "start ${projectionStartSeconds}s · duration ${projectionDurationSeconds}s · pool ${projectionPoolMillis?.let { "${it} ms" } ?: "raw"} · hop ${projectionHopMillis?.let { "${it} ms" } ?: "native"}"
        display("projection", "Fitting Field Smile t-SNE · $projectionDescription…")
        val previews = coroutineScope {
            results
                .map { result ->
                    async {
                        client
                            .speechEmbeddingSmileTsnePreview(
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
                            .await()
                    }
                }
                .awaitAll()
        }
        display("projection", "Field Smile t-SNE complete · $projectionDescription · ${previews.size} spaces")
        display(
            "speech spaces",
            speechEmbeddingPreviewDashboardHtml(
                previews,
                width = 1100,
                phaseHeight = 270,
                syncGroup = timeSync,
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        markdown("speech analysis error", "Speech analysis failed: ${(failure.message ?: failure.toString()).md}")
    }
}
```

## 3. 2 · real cached synchronized MDS gallery

<!-- field-box id="speech_embedding_visualizer_gallery" index="2" -->

```edn [field-box id="speech_embedding_visualizer_gallery" role="properties"]
{"id" "speech_embedding_visualizer_gallery" "index" 2 "name" "2 · real cached synchronized MDS gallery" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · real cached synchronized MDS gallery" "_tombstone" false "frame" #afield/rect [520.0 40.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "speech_embedding_visualizer_gallery_code_.kt" "lines" 8 "characters" 372 "sha256" "07a54eb48972a0486bcaabc84da484d1b17ddc5db96105cdb93beb9017c41bf3"}]}
```

```kotlin [field-property box="speech_embedding_visualizer_gallery" name="code" path="speech_embedding_visualizer_gallery_code_.kt"]
import java.io.File

val json = FieldAudioClient.json
val folder = File("public/docs/assets/audio_ai_speech_embeddings")
val previews = ["emotion2vec", "wavlm_base_plus"].map {
    json.decodeFromString<AnalysisPreview>(File(folder, "$it-preview.json").readText())
}
display("speech spaces", speechEmbeddingPreviewDashboardHtml(previews, width = 1040, phaseHeight = 220))
```

