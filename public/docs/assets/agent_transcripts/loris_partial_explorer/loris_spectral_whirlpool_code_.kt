val client = LorisClient()
val chunkStart = 6.0
val chunkDuration = 3.0
val excursionOctaves = 1.5
val sweepCycles = 4.0
val shutterHz = 12.0
val noiseBlend = 0.85

runAsync(
    onResult = { edited: LorisResult ->
        display("status", "complete: ${edited.model.partials.size} partials")
        display(
            "edited tracks and sound",
            edited.visualizationHtml(
                LorisViewOptions(height = 400, syncGroup = "my-whirlpool"),
            ),
        )
        display("provenance", edited.provenance)
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
    onCancel { analysis.cancel(true) }
    analysis.thenCompose { analyzed ->
        val model = analyzed.model
        val changed = model.copy(
            partials =
                model.partials.map { partial ->
                    partial.copy(
                        points =
                            partial.points.map { point ->
                                val u = (point.timeSeconds - chunkStart) / chunkDuration
                                if (u <= 0.0 || u >= 1.0) point
                                else {
                                    // A sin-squared envelope enters and leaves the edited chunk smoothly.
                                    val envelope = sin(PI * u).pow(2)
                                    val octaves = excursionOctaves * envelope * sin(2 * PI * sweepCycles * u)
                                    val shutter =
                                        .25 + .75 * (.5 + .5 * sin(2 * PI * shutterHz * chunkDuration * u))
                                    point.copy(
                                        frequencyHz = (point.frequencyHz * 2.0.pow(octaves)).coerceAtMost(
                                            model.sampleRate * .49,
                                        ),
                                        amplitude = point.amplitude * (1 - envelope + envelope * shutter),
                                        bandwidth = point.bandwidth * (1 - envelope) + noiseBlend * envelope,
                                    )
                                }
                            },
                    )
                },
        )
        status("Resynthesizing the whirlpool…")
        client.synthesizeAsync(changed).also { synthesis -> onCancel { synthesis.cancel(true) } }
    }
}
