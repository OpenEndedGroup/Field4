val client = LorisClient()
val path = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path

// Each pass owns its worker and output; stopping this box cancels both independently.
for ((name, settings) in
    [
        "fine time" to LorisOptions(resolutionHz = 80.0, windowWidthHz = 160.0, hopSeconds = .003),
        "fine frequency" to LorisOptions(resolutionHz = 20.0, windowWidthHz = 40.0, hopSeconds = .005),
    ]) {
    runAsync(
        onResult = { result: LorisResult ->
            display(
                name,
                result.visualizationHtml(
                    LorisViewOptions(
                        label = name,
                        height = 280,
                        syncGroup = "loris-resolution-comparison",
                    ),
                ),
            )
            display("$name provenance", result.provenance)
        },
        onError = { failure -> display("$name error", failure.message ?: failure.toString()) },
    ) {
        client.analyzeWavAsync(
            path,
            durationSeconds = 6.0,
            options = settings,
            onProgress = progress { text: String -> display("$name status", text) },
        )
    }
}
