import afield.document.workspace.path

val client = LorisClient()
val minimumTrackSeconds = 0.03
val frequencyScale = 0.75
val bandwidthScale = 0.0
client.analyzeWavAsync("%docs/assets/subharmonic_sound/notld-barbara-source.wav".path,
    options = LorisOptions(resolutionHz = 35.0, windowWidthHz = 80.0,
        frequencyDriftHz = 50.0, amplitudeFloorDb = -55.0),
    onProgress = { display("status", it) },
).thenCompose { analyzed ->
    val selected = analyzed.model.copy(partials = analyzed.model.partials.filter {
        it.points.last().timeSeconds - it.points.first().timeSeconds >= minimumTrackSeconds
    })
    display("status", "Resynthesizing ${selected.partials.size} selected tracks…")
    client.synthesizeAsync(selected.transformed(frequencyScale = frequencyScale,
        bandwidthScale = bandwidthScale, gain = 0.8))
}.thenAccept { result ->
    display("changed partials and sound", result.visualizationHtml(LorisViewOptions(
        axis = LorisFrequencyAxis.ERB,
        syncGroup = "loris-edited-study")))
    display("provenance", result.provenance)
}.exceptionally { error ->
    display("error", error.cause?.message ?: error.message ?: error.toString())
    null
}
