val delay = 3
val changeAt = 180
val steps = 480
val grid = DoubleArray(128) { it / 127.0 - 0.5 }
val quantiles = UniformQuantileFeatureExtractor()

fun quantileRms(errors: DoubleArray) = sqrt(errors.sumOf { it * it } / errors.size)

fun runStudy(biasAfterChange: Double) {
    val config = ControllerConfig(
        parameterNames = ["offset", "span correction"],
        paramMin = doubleArrayOf(-0.35, -0.5),
        paramMax = doubleArrayOf(0.35, 0.5),
        initialParams = doubleArrayOf(0.0, 0.0),
        maxStep = doubleArrayOf(0.006, 0.012),
        ditherAmplitude = doubleArrayOf(0.008, 0.024),
        ditherOmega = doubleArrayOf(0.17, 0.29),
        ditherPhase = doubleArrayOf(0.0, 0.0),
        observationDelayFrames = delay,
    )
    val controller = AdaptiveDistributionController(config, quantiles)
    val inFlight = ArrayDeque(List(delay) { config.initialParams })
    val fixedError = mutableListOf<Double>()
    val feedbackError = mutableListOf<Double>()
    val offset = mutableListOf<Double>()
    val spanCorrection = mutableListOf<Double>()

    repeat(steps) { step ->
        val changed = step >= changeAt
        val bias = if (changed) biasAfterChange else -0.06
        val span = if (changed) 0.62 else 0.88
        val applied = inFlight.removeFirst()

        fun stream(offset: Double, spanCorrection: Double) =
            DoubleArray(grid.size) { sample ->
                0.5 + bias + offset + (span + spanCorrection) * grid[sample]
            }

        val raw = stream(applied[0], applied[1])
        val clipped = raw.count { it < 0.0 || it > 1.0 }.toDouble() / raw.size
        val observed = raw.map { it.coerceIn(0.0, 1.0) }.toDoubleArray()
        val fixed = stream(0.0, 0.0).map { it.coerceIn(0.0, 1.0) }.toDoubleArray()
        val next = controller.pushSamples(observed, clipRate = clipped)
        check(next.diagnostics.pairedAppliedParams.contentEquals(applied))
        inFlight.addLast(next.appliedParams)

        fixedError += quantileRms(quantiles.errorFromSamples(fixed))
        feedbackError += quantileRms(next.diagnostics.rawFeatureError)
        offset += applied[0]
        spanCorrection += applied[1]
    }

    val ticks = (0 until steps).map { it.toDouble() }
    showSignals(
        "Fixed" to fixedError,
        "Loop" to feedbackError,
        x = ticks,
        xTitle = "Observation",
        marks = ["Step" to changeAt],
        width = 600,
        height = 255,
        title = "Quantile error (RMS)",
        channel = "error",
    )
    showSignals(
        "Offset" to offset,
        "Span correction" to spanCorrection,
        x = ticks,
        xTitle = "Observation",
        marks = ["Step" to changeAt],
        width = 600,
        height = 255,
        title = "Applied controls",
        channel = "correction",
    )
    val fixedTail = fixedError.takeLast(60).average()
    val feedbackTail = feedbackError.takeLast(60).average()
    markdown(
        "result",
        "Input changes at observation $changeAt. Mean RMS over the final 60 observations · fixed **${fixedTail.f4}** · feedback **${feedbackTail.f4}**.",
    )
    println("Final 60 · fixed ${fixedTail.f4} · feedback ${feedbackTail.f4}")
}

slider("Input offset after change", min = -0.18, max = 0.24, step = 0.01, initialValue = 0.15) {
    runStudy(it)
}
runStudy(0.15)
displayOpen("controls")
displayOpen("error")
displayOpen("result")
displayClose("correction")
