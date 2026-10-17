import java.util.concurrent.CompletableFuture

val client = FieldAudioClient()
val start = 18.0
val duration = 24.0
val timeSync = "nancy-analysis"
display("all jobs", fieldAudioJobMonitor(heightPixels = 330))

client.assetByFilename("*Nancy*").thenCompose { source ->
    val region = AssetRegion(source.id, start, duration)
    val canonical = source.representations.first { it.role == "canonical_project" }
    display("source", canonical.audioVisualizerHtml(
        label = "Nancy · 18–42 s",
        options = AudioVisualizerOptions(
            width = 1040, waveformHeight = 110,
            initialStartSeconds = start,
            initialDurationSeconds = duration,
            initialPositionSeconds = start,
            syncGroup = timeSync,
        ),
    ))

    val requests = [
        "beats" to multiScaleBeatAnalysis(region, requestedBy = "field:analysis-example"),
        "monophonic pitch" to monophonicPitchAnalysis(region, requestedBy = "field:analysis-example"),
        "polyphonic pitch" to polyphonicPitchAnalysis(region, requestedBy = "field:analysis-example"),
    ]
    val futures = requests.map { (label, request) ->
        client.submit(request).thenCompose { accepted ->
            display("$label job", fieldAudioJobStatusHtml(accepted))
            client.watch(accepted.jobId) { event ->
                display("$label job", fieldAudioJobStatusHtml(accepted, event))
            }
            client.awaitAnalysis(accepted)
        }
    }
    CompletableFuture.allOf(*futures.toTypedArray()).thenApply {
        futures.map { it.join() }
    }
}.thenAccept { results ->
    displayClear("all jobs")
    for (label in ["beats", "monophonic pitch", "polyphonic pitch"]) displayClear("$label job")
    displayOpen("source")
    display("analysis", analysisDashboardHtml(
        results, width = 1040, panelHeight = 250,
        syncGroup = timeSync, timeOffsetSeconds = start,
    ))
    displayOpen("analysis")
}.exceptionally { error ->
    display("analysis error", error.cause?.message ?: error.message ?: error.toString())
    null
}
