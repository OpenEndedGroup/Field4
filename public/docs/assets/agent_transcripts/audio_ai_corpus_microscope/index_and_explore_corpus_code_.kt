import afield.stdlib.noir.audioai.*
import java.util.concurrent.CompletableFuture

val client = FieldAudioClient()
display("job monitor", fieldAudioJobMonitor(heightPixels = 330))

fun tracked(label: String, operation: ModelOperation): CompletableFuture<AnalysisResult> =
    client.submit(operation).thenCompose { accepted ->
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

val lookups = listOf("*Nancy*", "*Candlestick Park*").map { client.assetByFilename(it) }
CompletableFuture.allOf(*lookups.toTypedArray()).thenCompose {
    val assets = lookups.map { it.join() }
    val indexes = assets.mapIndexed { index, asset ->
        val duration = requireNotNull(asset.durationSeconds) { "asset has no duration" }
        val whole = AssetRegion(asset.id, 0.0, duration)
        tracked("source ${index + 1} segments", segmentCorpus(
            whole, requestedBy = "field:corpus-microscope/index",
        )).thenCompose {
            tracked("source ${index + 1} descriptors", embedCorpus(
                whole, requestedBy = "field:corpus-microscope/index",
            ))
        }
    }
    CompletableFuture.allOf(*indexes.toTypedArray()).thenApply { assets.first() }
}.thenCompose { nancy ->
    val query = AssetRegion(nancy.id, startSeconds = 18.0, durationSeconds = 6.0)
    tracked("Nancy neighbour query", findCorpusNeighbours(
        query,
        limit = 12,
        includeSameAsset = false,
        weights = CorpusDistanceWeights(
            harmony = 0.8, pitch = 0.55, texture = 1.35, rhythm = 0.7, dynamics = 0.35,
        ),
        requestedBy = "field:corpus-microscope/query",
    )).thenCompose { result ->
        client.corpusPreview(result.id).thenApply { preview -> nancy to preview }
    }
}.thenAccept { (nancy, preview) ->
    val canonical = nancy.representations.first { it.role == "canonical_project" }
    display("corpus microscope", corpusMicroscopeHtml(
        preview, canonical, width = 1100, cardWidth = 510,
    ))
}.exceptionally { error ->
    val message = error.cause?.message ?: error.message ?: error.toString()
    display("corpus error", """<pre style="color:#f1aaaa;background:#28191b;padding:12px;white-space:pre-wrap">$message</pre>""")
    null
}
