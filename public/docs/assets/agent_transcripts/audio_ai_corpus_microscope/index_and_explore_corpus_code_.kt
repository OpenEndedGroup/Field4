import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

val client = FieldAudioClient()
val sourceNames = ["*Nancy*", "*Candlestick Park*"]
val queryStartSeconds = 18.0
val queryDurationSeconds = 6.0
val neighbourCount = 12
val weights = CorpusDistanceWeights(harmony = 0.8, pitch = 0.55, texture = 1.35, rhythm = 0.7, dynamics = 0.35)

display("job monitor", fieldAudioJobMonitor(heightPixels = 330))
runAsync<Pair<AudioAsset, CorpusPreview>>(
    onResult = { (nancy, preview) ->
        val canonical = nancy.representations.first { it.role == "canonical_project" }
        display("corpus microscope", corpusMicroscopeHtml(preview, canonical, width = 1100, cardWidth = 510))
    },
    onError = { failure -> markdown("corpus error", "Analysis failed: ${(failure.message ?: failure.toString()).md}") },
) {
    // Preserve ownership when a future continuation starts the next tracked job.
    val run = RunScope.current()
    val tracked: (String, ModelOperation) -> CompletableFuture<AnalysisResult> = { label, request ->
        RunScope.withCurrent(run) {
                client.trackAnalysis(
                    request,
                    onAccepted = { display("$label job", fieldAudioJobStatusHtml(it)) },
                    onProgress = { job, event -> display("$label job", fieldAudioJobStatusHtml(job, event)) },
                    onCompleted = { display("$label job", fieldAudioJobStatusHtml(it)) },
                    onError = {}, // The outer operation reports failure once.
                )
            }
            .also { pending -> onCancel { pending.cancel(true) } }
    }
    val lookups = sourceNames.map { client.assetByFilename(it) }
    onCancel { lookups.forEach { it.cancel(true) } }
    CompletableFuture.allOf(*lookups.toTypedArray())
        .thenCompose {
            if (future.isDone) throw CancellationException("run ended")
            val assets = lookups.map { it.join() }
            val indexes = assets.mapIndexed { index, asset ->
                val duration = requireNotNull(asset.durationSeconds) { "asset has no duration" }
                val whole = AssetRegion(asset.id, 0.0, duration)
                tracked(
                    "source ${index + 1} segments",
                    segmentCorpus(
                        whole,
                        requestedBy = "field:corpus-microscope/index",
                    ),
                ).thenCompose {
                    tracked(
                        "source ${index + 1} descriptors",
                        embedCorpus(
                            whole,
                            requestedBy = "field:corpus-microscope/index",
                        ),
                    )
                }
            }
            CompletableFuture.allOf(*indexes.toTypedArray()).thenApply { assets.first() }
        }
        .thenCompose { nancy ->
            if (future.isDone) throw CancellationException("run ended")
            val query = AssetRegion(nancy.id, startSeconds = queryStartSeconds, durationSeconds = queryDurationSeconds)
            tracked(
                "Nancy neighbour query",
                findCorpusNeighbours(
                    query,
                    limit = neighbourCount,
                    includeSameAsset = false,
                    weights = weights,
                    requestedBy = "field:corpus-microscope/query",
                ),
            ).thenCompose { result ->
                if (future.isDone) throw CancellationException("run ended")
                client
                    .corpusPreview(result.id)
                    .also { pending -> onCancel { pending.cancel(true) } }
                    .thenApply { preview -> nancy to preview }
            }
        }
}
