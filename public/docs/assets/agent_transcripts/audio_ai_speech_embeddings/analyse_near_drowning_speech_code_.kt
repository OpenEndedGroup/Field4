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
