import afield.media.video.diffusion.*

// Connect this box beneath a running renderer box before launching it.
val owner: Renderer = fromContext()
prop<SpinningDiffusionDemo>(diffusionDemo)
fromContextWithNameOrNull<SpinningDiffusionDemo>("diffusionDemo")?.close()

// Edit these before launching. identity/invert also use the GPU bridge in both directions.
val backend = "diffusion"
val whiteOnBlack = false
val root = StreamDiffusionSupport.configuredRoot()
val config = root?.let {
    StreamDiffusionConfig(it, backend = backend, displayReprojection = true, historyBlend = .35, noiseAdvection = .8)
}
val support = StreamDiffusionSupport.probe(owner, config)
if (!support.available) {
    print("Streaming diffusion unavailable: ${support.reason}")
    markdown(
        """
        ## Streaming diffusion is unavailable here

        ${support.reason}

        See [Streaming diffusion](https://openendedgroup.com/Field4/streaming_diffusion/).
        The model environment is optional; you can keep editing this document.
    """
            .trimIndent(),
    )
} else {
    var material = 1 // 0 ink, 1 charcoal, 2 barbed wire
    var promptOverride = "" // Any text prompt; blank selects the material preset.
    var strength = .85
    var noiseAdvection = .8
    var adaptPrompt = whiteOnBlack
    var pendingReset = false
    var animate = true
    fun selectedPrompt(): String = promptOverride.trim().ifEmpty {
        when {
            material == 2 && adaptPrompt -> StreamDiffusionPresets.WHITE_WIRE
            material == 2 -> StreamDiffusionPresets.WIRE
            material == 1 && adaptPrompt -> StreamDiffusionPresets.WHITE_CHARCOAL
            material == 1 -> StreamDiffusionPresets.CHARCOAL
            adaptPrompt -> StreamDiffusionPresets.WHITE_INK
            else -> StreamDiffusionPresets.INK
        }
    }
    diffusionDemo = SpinningDiffusionDemo(
        owner,
        checkNotNull(config).copy(prompt = selectedPrompt(), strength = strength),
        whiteOnBlack = whiteOnBlack,
    )
    val demo = diffusionDemo
    controlGroup("diffusion") {
        slider("0: ink / 1: charcoal / 2: wire", min = 0.0, max = 2.0, step = 1.0, initialValue = material.toDouble()) {
            material = it.toInt()
            pendingReset = true
        }
        text("Prompt override (blank uses preset)", initialValue = promptOverride) {
            promptOverride = it
            pendingReset = true
        }
        slider("Strength", min = .2, max = .9, step = .05, initialValue = strength) {
            strength = it
            pendingReset = true
        }
        checkbox("Adapt prompt for white marks on black", initialValue = adaptPrompt) {
            adaptPrompt = it
            pendingReset = true
        }
        checkbox("Reproject output to current geometry", initialValue = demo.view.displayReprojection) {
            demo.view.displayReprojection = it
        }
        slider("History blend", min = 0.0, max = .9, step = .05, initialValue = demo.view.historyBlend) {
            demo.view.historyBlend = it
        }
        slider("Noise advection (experimental)", min = 0.0, max = 1.0, step = .05, initialValue = noiseAdvection) {
            noiseAdvection = it
            pendingReset = true
        }
        checkbox("Animate", initialValue = true) { animate = it }
        button("Reset causal state / seed") { if (it) pendingReset = true }
    }
    displayOpen("diffusion")
    print("Left: current source. Right: delayed diffusion output. GPU pixel transport. ${demo.view.session.logFile}")
    frames {
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
            if (count++ % 120 == 0)
                print(
                    "${demo.view.session.status}; captured=${demo.view.capturedFrames}, shown=${demo.view.presentedFrames}, skipped=${demo.view.skippedCaptures}, displayWarps=${demo.view.displayReprojectionRequests}, historyWarps=${demo.view.historyBlendRequests}, noiseMaps=${demo.view.noiseMotionFrames}; ${demo.view.session.capabilities}",
                )
            if (count % 120 == 1 && demo.view.session.capabilities.optBoolean("tensorrt_active"))
                print("Active TAEHV TensorRT; shown=${demo.view.presentedFrames}")
            frame()
        }
    }
        .end { demo.close() }
}
