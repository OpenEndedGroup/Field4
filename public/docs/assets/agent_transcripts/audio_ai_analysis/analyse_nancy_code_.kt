import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

val client = cached { FieldAudioClient() }
val start = 18.0
val duration = 24.0
val timeSync = "nancy-analysis"
display("all jobs", fieldAudioJobMonitor(heightPixels = 330))

runAsync(
    onResult = { results: List<AnalysisResult> ->
        displayClear("all jobs")
        for (label in ["beats", "monophonic pitch", "polyphonic pitch"]) displayClear("$label job")
        displayOpen("source")
        display(
            "analysis",
            analysisDashboardHtml(
                results,
                width = 1040,
                panelHeight = 250,
                syncGroup = timeSync,
                timeOffsetSeconds = start,
            ),
        )
        displayOpen("analysis")
    },
    onError = { failure ->
        markdown("analysis error", "Analysis failed: ${(failure.message ?: failure.toString()).md}")
    },
) {
    val showSource = progress<String> { display("source", it) }
    // HTTP continuations run elsewhere; every analysis still belongs to this box run.
    val run = RunScope.current()
    val lookup = client.assetByFilename("*Nancy*")
    onCancel { lookup.cancel(true) }
    lookup.thenCompose { source ->
        if (future.isDone) throw CancellationException("run ended")

        val region = AssetRegion(source.id, start, duration)
        val canonical =
            source.representations.firstOrNull { it.role == "canonical_project" }
                ?: source.representations.first { it.mediaType in ["audio/wav", "audio/x-wav"] }
        showSource(
            canonical.audioVisualizerHtml(
                label = "Nancy · 18–42 s",
                options = AudioVisualizerOptions(
                    width = 1040,
                    waveformHeight = 110,
                    initialStartSeconds = start,
                    initialDurationSeconds = duration,
                    initialPositionSeconds = start,
                    syncGroup = timeSync,
                ),
            ),
        )

        val requests = [
            "beats" to multiScaleBeatAnalysis(region, requestedBy = "field:analysis-example"),
            "monophonic pitch" to monophonicPitchAnalysis(region, requestedBy = "field:analysis-example"),
            "polyphonic pitch" to polyphonicPitchAnalysis(region, requestedBy = "field:analysis-example"),
        ]
        val analyses = requests.map { (label, request) ->
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
        CompletableFuture.allOf(*analyses.toTypedArray()).thenApply { analyses.map { it.join() } }
    }
}
