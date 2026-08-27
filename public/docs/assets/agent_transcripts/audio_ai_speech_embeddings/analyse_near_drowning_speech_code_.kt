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
