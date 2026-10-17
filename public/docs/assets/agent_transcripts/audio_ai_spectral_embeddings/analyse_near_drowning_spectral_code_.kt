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
                    ]
                    .mapIndexed { index, distance ->
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
