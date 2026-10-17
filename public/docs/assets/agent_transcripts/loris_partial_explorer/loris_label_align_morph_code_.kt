val client = LorisClient()

runAsync(
    onResult = { morphed: LorisResult ->
        display("status", "complete: ${morphed.model.partials.size} morphed partials")
        display("morph", morphed.visualizationHtml(LorisViewOptions(maximumHz = 5000.0, syncGroup = "my-morph")))
    },
    onError = { failure -> display("error", failure.message ?: failure.toString()) },
) {
    val status = progress<String> { display("status", it) }
    val showLabels = progress<String> { display("labels", it) }
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
    analysis
        .thenCompose { analyzed ->
            status("Labeling the two phrases…")
            val film = analyzed.model
            val first = client.distillAsync(film.crop(1.6, 3.6), LorisReference.Estimated(150.0, 450.0))
            val second = client.distillAsync(film.crop(11.5, 13.5), LorisReference.Estimated(60.0, 200.0))
            onCancel {
                first.cancel(true)
                second.cancel(true)
            }
            first.thenCombine(second) { a, b -> a.model to b.model }
        }
        .thenCompose { (a, b) ->
            showLabels("A ${a.labelCounts()}  B ${b.labelCounts()}")
            val end = a.durationSeconds
            status("Aligning the loudest points…")
            // Both crops are two seconds long; align their loudest points before morphing.
            val alignment = client.dilateAsync(
                b,
                [0.0, b.loudestTimeSeconds(), end],
                [0.0, a.loudestTimeSeconds(), end],
            )
            onCancel { alignment.cancel(true) }
            alignment.thenCompose { aligned ->
                status("Morphing the aligned phrases…")
                client
                    .morphAsync(a.dropUnlabeled(), aligned.model.dropUnlabeled(), LorisEnvelope.ramp(0.4, 1.6))
                    .also { morph -> onCancel { morph.cancel(true) } }
            }
        }
}
