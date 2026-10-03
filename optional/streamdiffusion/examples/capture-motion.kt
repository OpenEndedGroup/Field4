import afield.controlGroup
import afield.displayOpen
import afield.four.diffusion.*
import afield.four.modules.fromContextWithNameOrNull

// Connect this box beneath a running renderer box before launching it.
val owner: Renderer = fromContext()
prop<SpinningDiffusionDemo>(diffusionDemo)
fromContextWithNameOrNull<SpinningDiffusionDemo>("diffusionDemo")?.close()

// Edit these before launching. identity/invert also use the GPU bridge in both directions.
val backend = "diffusion"
val whiteOnBlack = false
val root = StreamDiffusionSupport.configuredRoot()
val config = root?.let { StreamDiffusionConfig(it, backend = backend, displayReprojection = true, historyBlend = .35, noiseAdvection = .8) }
val support = StreamDiffusionSupport.probe(owner, config)
if (!support.available) {
    print("Streaming diffusion unavailable: ${support.reason}")
    markdown("""
        ## Streaming diffusion is unavailable here

        ${support.reason}

        See [Streaming diffusion](https://openendedgroup.com/Field4/streaming_diffusion/).
        The model environment is optional; you can keep editing this document.
    """.trimIndent())
} else {
    var material = 0 // 0 ink, 1 charcoal, 2 barbed wire
    var strength = .60
    var noiseAdvection = .8
    var adaptPrompt = whiteOnBlack
    var pendingReset = false
    var animate = true
    fun selectedPrompt(): String = when {
        material == 2 && adaptPrompt -> StreamDiffusionPresets.WHITE_WIRE
        material == 2 -> StreamDiffusionPresets.WIRE
        material == 1 && adaptPrompt -> StreamDiffusionPresets.WHITE_CHARCOAL
        material == 1 -> StreamDiffusionPresets.CHARCOAL
        adaptPrompt -> StreamDiffusionPresets.WHITE_INK
        else -> StreamDiffusionPresets.INK
    }
    diffusionDemo = SpinningDiffusionDemo(owner,
        checkNotNull(config).copy(prompt = selectedPrompt(), strength = strength),
        whiteOnBlack = whiteOnBlack)
    val demo = diffusionDemo
    // Real RTT captures for the comparison clips; these readbacks are outside inference.
    val captureDirectory = java.io.File("/tmp/field-diffusion-motion-capture").also { it.mkdirs() }
    controlGroup("diffusion") {
        slider("0: ink / 1: charcoal / 2: wire", min = 0.0, max = 2.0, step = 1.0, initialValue = 0.0) {
            material = it.toInt(); pendingReset = true
        }
        slider("Strength", min = .2, max = .9, step = .05, initialValue = strength) {
            strength = it; pendingReset = true
        }
        checkbox("Adapt prompt for white marks on black", initialValue = adaptPrompt) { adaptPrompt = it; pendingReset = true }
        checkbox("Reproject output to current geometry", initialValue = demo.view.displayReprojection) {
            demo.view.displayReprojection = it
        }
        slider("History blend", min = 0.0, max = .9, step = .05, initialValue = demo.view.historyBlend) {
            demo.view.historyBlend = it
        }
        slider("Noise advection (experimental)", min = 0.0, max = 1.0, step = .05, initialValue = noiseAdvection) {
            noiseAdvection = it; pendingReset = true
        }
        checkbox("Animate", initialValue = true) { animate = it }
        button("Reset causal state / seed") { if (it) pendingReset = true }
    }
    displayOpen("diffusion")
    print("Left: current source. Right: delayed diffusion output. GPU pixel transport. ${demo.view.session.logFile}")
    frames {
        var proofPhase = 0
        var proofFrame = 0
        var proofDue = 0L
        var proofStartPresented = 0L
        var proofRestartAt = 0L
        var proofDone = false
        var lastSave: java.util.concurrent.CompletableFuture<String>? = null
        var count = 0
        var angle = 0.0
        var previous = System.nanoTime()
        while (!owner.isClosed) {
            val now = System.nanoTime()
            if (animate) angle += (now - previous) * 1e-9 * Math.PI * 2 * .12
            previous = now
            demo.angle = angle.toFloat()
            if (pendingReset && demo.view.session.ready) {
                demo.view.reset(selectedPrompt(), strength, 0, noiseAdvection)
                pendingReset = false
            }
            if (demo.view.session.capabilities.optBoolean("tensorrt_active") &&
                demo.view.presentedFrames > proofStartPresented + 16 && now >= proofDue && !proofDone) {
                if (proofPhase < 2) {
                    val stem = captureDirectory.absolutePath + "/" +
                        (if (proofPhase == 0) "motion" else "baseline") + "-%03d".format(proofFrame)
                    demo.source.saveJpgAsync(stem + "-source.jpg", quality = 95)
                    lastSave = demo.view.output.saveJpgAsync(stem + "-output.jpg", quality = 95)
                    proofDue = now + 100_000_000L
                    proofFrame++
                    if (proofFrame == 80) {
                        proofPhase++; proofFrame = 0
                        val enabled = proofPhase == 2
                        demo.view.displayReprojection = enabled
                        demo.view.historyBlend = if (enabled) .35 else 0.0
                        demo.view.reset(selectedPrompt(), strength, 0, if (enabled) .8 else 0.0)
                        proofStartPresented = demo.view.presentedFrames
                        if (enabled) proofRestartAt = now
                    }
                } else if (now > proofRestartAt + 3_000_000_000L && lastSave?.isDone == true) {
                    proofDone = true
                    print("Motion comparison complete; " + demo.view.session.capabilities)
                }
            }
            if (count++ % 120 == 0) print("${demo.view.session.status}; captured=${demo.view.capturedFrames}, shown=${demo.view.presentedFrames}, skipped=${demo.view.skippedCaptures}, displayWarps=${demo.view.displayReprojectionRequests}, historyWarps=${demo.view.historyBlendRequests}, noiseMaps=${demo.view.noiseMotionFrames}; ${demo.view.session.capabilities}")
            if (count % 120 == 1 && demo.view.session.capabilities.optBoolean("tensorrt_active"))
                print("Active TAEHV TensorRT; shown=${demo.view.presentedFrames}")
            frame()
        }
    }.end { demo.close() }
}
