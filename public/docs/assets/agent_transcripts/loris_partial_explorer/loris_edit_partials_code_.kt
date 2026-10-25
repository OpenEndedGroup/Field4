val client = LorisClient()
val minimumTrackSeconds = 0.03
val frequencyScale = 0.75
val bandwidthScale = 0.0

runAsync(
    onResult = { result: LorisResult ->
        display("status", "complete: ${result.model.partials.size} partials")
        display(
            "changed partials and sound",
            result.visualizationHtml(
                LorisViewOptions(axis = LorisFrequencyAxis.ERB, syncGroup = "loris-edited-study"),
            ),
        )
        display("provenance", result.provenance)
    },
    onError = { failure -> display("error", failure.message ?: failure.toString()) },
) {
    val status = progress<String> { display("status", it) }
    val analysis = client.analyzeWavAsync(
        "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path,
        options = LorisOptions(
            resolutionHz = 35.0,
            windowWidthHz = 80.0,
            frequencyDriftHz = 50.0,
            amplitudeFloorDb = -55.0,
        ),
        onProgress = status,
    )
    // A composed future does not cancel its workers: own each native stage explicitly.
    onCancel { analysis.cancel(true) }
    analysis.thenCompose { analyzed ->
        val selected = analyzed.model.copy(
            partials = analyzed.model.partials.filter {
                it.points.last().timeSeconds - it.points.first().timeSeconds >= minimumTrackSeconds
            },
        )
        status("Resynthesizing ${selected.partials.size} selected tracks…")
        client
            .synthesizeAsync(
                selected.transformed(
                    frequencyScale = frequencyScale,
                    bandwidthScale = bandwidthScale,
                    gain = 0.8,
                ),
            )
            .also { synthesis -> onCancel { synthesis.cancel(true) } }
    }
}
