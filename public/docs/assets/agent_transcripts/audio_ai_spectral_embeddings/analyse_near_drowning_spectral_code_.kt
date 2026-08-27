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
