import afield.stdlib.AudioVisualizerOptions
import afield.stdlib.noir.audioai.*
import java.util.concurrent.CompletableFuture

val client = FieldAudioClient()
val start = 18.0
val duration = 24.0
val timeSync = "nancy-analysis"
display("job monitor", fieldAudioJobMonitor(heightPixels = 330))

client.assetByFilename("*Nancy*").thenCompose { source ->
    val region = AssetRegion(source.id, startSeconds = start, durationSeconds = duration)
    val canonical = source.representations.first { it.role == "canonical_project" }
    display("source", canonical.audioVisualizerHtml(
        label = "Henry Mancini · Nancy · analysis window",
        options = AudioVisualizerOptions(
            width = 1040, waveformHeight = 150,
            initialStartSeconds = start, initialDurationSeconds = duration,
            initialPositionSeconds = start,
            syncGroup = timeSync,
        ),
    ))

    val requests = listOf(
        "beats" to multiScaleBeatAnalysis(region, requestedBy = "field:audio-analysis/nancy"),
        "monophonic pitch" to monophonicPitchAnalysis(region, requestedBy = "field:audio-analysis/nancy"),
        "polyphonic pitch" to polyphonicPitchAnalysis(region, requestedBy = "field:audio-analysis/nancy"),
    )
    val results = requests.map { (label, request) ->
        client.submit(request).thenCompose { accepted ->
            display("$label job", fieldAudioJobStatusHtml(accepted))
            client.watch(
                accepted.jobId,
                onEvent = { event -> display("$label job", fieldAudioJobStatusHtml(accepted, event)) },
                onError = { error -> display("$label job stream", "progress stream: ${error.message}") },
            )
            client.awaitAnalysis(accepted).thenApply { result ->
                client.inspect(accepted.jobId).thenAccept { job -> display("$label job", fieldAudioJobStatusHtml(job)) }
                result
            }
        }
    }
    CompletableFuture.allOf(*results.toTypedArray()).thenApply {
        results.map { it.join() }
    }
}.thenAccept { results ->
    display("synchronized analysis", analysisDashboardHtml(
        results, width = 1040, panelHeight = 250,
        syncGroup = timeSync, timeOffsetSeconds = start,
    ))
}.exceptionally { error ->
    val message = error.cause?.message ?: error.message ?: error.toString()
    display("analysis error", """<pre style="color:#f1aaaa;background:#28191b;padding:12px;white-space:pre-wrap">$message</pre>""")
    null
}
