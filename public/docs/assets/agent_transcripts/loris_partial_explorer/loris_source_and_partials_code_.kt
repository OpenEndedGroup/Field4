import afield.audio.engine.WavFloatArrayLoader

val path = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
val sync = "loris-source-study"
val client = LorisClient()
display("worker", LorisNative.describe())
// Stop or rerun cancels this analysis and suppresses late output from its old run.
runAsync(
    onResult = { analyzed: LorisResult ->
        val wav = WavFloatArrayLoader.loadWavToFloatArray(path.toString())
        val source = AudioVisualizer.renderSamplesHtml(
            wav.samples,
            wav.sampleRate,
            AudioVisualizerOptions(width = 1000, waveformHeight = 95, syncGroup = sync),
            label = "Source",
        )
        display(
            "source and reconstruction",
            source +
                analyzed.visualizationHtml(
                    LorisViewOptions(height = 400, syncGroup = sync),
                ),
        )
        display("provenance", analyzed.provenance)
    },
    onError = { failure -> display("error", failure.message ?: failure.toString()) },
) {
    client.analyzeWavAsync(
        path,
        options = LorisOptions(
            resolutionHz = 35.0,
            windowWidthHz = 80.0,
            frequencyDriftHz = 50.0,
            amplitudeFloorDb = -55.0,
        ),
        onProgress = progress { text: String -> display("status", text) },
    )
}
