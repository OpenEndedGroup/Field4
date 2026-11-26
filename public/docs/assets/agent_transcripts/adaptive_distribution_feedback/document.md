# Field Document: adaptive_distribution_feedback

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Calibrate a distribution with feedback

<!-- field-box id="feedback" index="0" -->

```edn [field-box id="feedback" role="properties"]
{"id" "feedback" "index" 0 "name" "Calibrate a distribution with feedback" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Calibrate a distribution with feedback" "_tombstone" false "frame" #afield/rect [70.0 80.0 415.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "feedback_code_.kt" "lines" 92 "characters" 3430 "sha256" "fbe512c5e59e53b1606ce64d146015c2508dfef385ef2fbcb4bd44249ad74365"}]}
```

```kotlin [field-property box="feedback" name="code" path="feedback_code_.kt"]
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
```

