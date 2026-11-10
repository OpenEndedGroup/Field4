displayClearAll()

// Each box owns a real surface pipeline. Its vertex/fragment tabs have standard shader reload.
val screenIndex = SCREEN_INDEX
val view = carousel.surfaces[screenIndex]
val screenModule = moduleHere { +view }
view.draw(screenModule)

controlGroup("screen exposure") {
    checkbox("Accumulated image", initialValue = view.accumulationMix > 0f,
        hoverText = "Enable this screen's own temporal exposure. With it off, the screen shows its delayed sharp picture; it still shares the single decoder.") { view.accumulationMix = if (it) 1f else 0f }
    slider("Accumulation mix", min = 0.0, max = 1.0, step = .01, initialValue = view.accumulationMix.toDouble(),
        hoverText = "Blend this screen's delayed sharp input with its independent linear-light exposure. A global score or style button can overwrite this setting while active.") { view.accumulationMix = it.toFloat() }
    slider("Half-life (seconds)", min = 0.0, max = 10.0, step = .01, initialValue = view.accumulationHalfLife,
        hoverText = "Real-time decay of this screen's accumulated history. Only new selected input pictures contribute; repeated headset draws do not add extra weight.") { view.accumulationHalfLife = it }
    slider("Incoming weight", min = 0.0, max = 4.0, step = .01, initialValue = view.accumulationInputWeight,
        hoverText = "Weight of each newly selected delayed picture in this screen's exposure. Zero holds a valid exposure. Clear starts it again without affecting the other screens.") { view.accumulationInputWeight = it }
    button("Clear this exposure", hoverText = "Reset only this screen's accumulated image and paired-exposure clock. The shared decoder and delay ring keep running.") { down -> if (down) view.clearAccumulation() }
}
controlGroup("screen cadence") {
    checkbox("Paired exposure cadence", initialValue = view.exposureCadenceEnabled,
        hoverText = "Hold an accumulated exposure for each soft/dark → clear pair on this screen. Current head poses still render at headset rate. Only this screen's cadence settings change.") { view.exposureCadenceEnabled = it }
    slider("Pairs / second", min = 1.0, max = 30.0, step = .1, initialValue = view.exposurePairsPerSecond,
        hoverText = "Complete soft/dark → clear pairs per real second, independent of video speed. Twelve corresponds to the paired halves of the original 24 fps cadence.") { view.exposurePairsPerSecond = it }
    slider("Blur sigma (pixels)", min = 0.0, max = 16.0, step = .1, initialValue = view.exposureBlurSigma.toDouble(),
        hoverText = "Gaussian sigma in source-video pixels during the soft half. Each eye is filtered separately; zero preserves the full-resolution held image.") { view.exposureBlurSigma = it.toFloat() }
    slider("Soft-half blur mix", min = 0.0, max = 1.0, step = .01, initialValue = view.exposureBlurMix.toDouble(),
        hoverText = "Spatial blur blend during this screen's soft half only. Independent of temporal accumulation mix, and zero during the clear half.") { view.exposureBlurMix = it.toFloat() }
    slider("Soft-half darkening", min = 0.0, max = .5, step = .005, initialValue = view.exposureDarkening.toDouble(),
        hoverText = "Fraction of linear-light brightness removed from this screen's soft half. 0.04 removes four percent; the clear half retains the original brightness.") { view.exposureDarkening = it.toFloat() }
}
displayOpen("screen exposure")
displayOpen("screen cadence")
markdown("screen", "**Screen ${screenIndex + 1}** · initial delay ${view.delayFrames} published frames.\n\nThe live time table is in box 3. The matching accumulation shader box is directly below this one.")
