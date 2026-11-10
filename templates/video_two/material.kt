import afield.ui.output.ControlValueHandle

displayClearAll()

// Each box owns a real surface pipeline. Its vertex/fragment tabs have standard shader reload.
val screenIndex = SCREEN_INDEX
val view = carousel.surfaces[screenIndex]
val screenModule = moduleHere { +view }
view.draw(screenModule)

val controlMirrors = mutableListOf<() -> Unit>()

controlGroup("screen exposure") {
    checkbox("Accumulated image", initialValue = view.accumulationMix > 0f,
        hoverText = "Enable this screen's own temporal exposure. With it off, the screen shows its delayed sharp picture; it still shares the single decoder.") { view.accumulationMix = if (it) 1f else 0f }.also { h -> controlMirrors += { h.set(view.accumulationMix > 0f) } }
    slider("Accumulation mix", min = 0.0, max = 1.0, step = .01, initialValue = view.accumulationMix.toDouble(),
        hoverText = "Blend this screen's delayed sharp input with its independent linear-light exposure. A global score or style button can overwrite this setting while active.") { view.accumulationMix = it.toFloat() }.also { h -> controlMirrors += { h.set(view.accumulationMix.toDouble()) } }
    slider("Half-life (seconds)", min = 0.0, max = 10.0, step = .01, initialValue = view.accumulationHalfLife,
        hoverText = "Real-time decay of this screen's accumulated history. Only new selected input pictures contribute; repeated headset draws do not add extra weight.") { view.accumulationHalfLife = it }.also { h -> controlMirrors += { h.set(view.accumulationHalfLife) } }
    slider("Incoming weight", min = 0.0, max = 4.0, step = .01, initialValue = view.accumulationInputWeight,
        hoverText = "Weight of each newly selected delayed picture in this screen's exposure. Zero holds a valid exposure. Clear starts it again without affecting the other screens.") { view.accumulationInputWeight = it }.also { h -> controlMirrors += { h.set(view.accumulationInputWeight) } }
    button("Clear this exposure", hoverText = "Reset only this screen's accumulated image and paired-exposure clock. The shared decoder and delay ring keep running.") { down -> if (down) view.clearAccumulation() }
}
controlGroup("screen cadence") {
    checkbox("Paired exposure cadence", initialValue = view.exposureCadenceEnabled,
        hoverText = "Hold an accumulated exposure for each soft/dark → clear pair on this screen. Current head poses still render at headset rate. Only this screen's cadence settings change.") { view.exposureCadenceEnabled = it }.also { h -> controlMirrors += { h.set(view.exposureCadenceEnabled) } }
    slider("Pairs / second", min = 1.0, max = 30.0, step = .1, initialValue = view.exposurePairsPerSecond,
        hoverText = "Complete soft/dark → clear pairs per real second, independent of video speed. Twelve corresponds to the paired halves of the original 24 fps cadence.") { view.exposurePairsPerSecond = it }.also { h -> controlMirrors += { h.set(view.exposurePairsPerSecond) } }
    slider("Blur sigma (pixels)", min = 0.0, max = 16.0, step = .1, initialValue = view.exposureBlurSigma.toDouble(),
        hoverText = "Gaussian sigma in source-video pixels during the soft half. Each eye is filtered separately; zero preserves the full-resolution held image.") { view.exposureBlurSigma = it.toFloat() }.also { h -> controlMirrors += { h.set(view.exposureBlurSigma.toDouble()) } }
    slider("Soft-half blur mix", min = 0.0, max = 1.0, step = .01, initialValue = view.exposureBlurMix.toDouble(),
        hoverText = "Spatial blur blend during this screen's soft half only. Independent of temporal accumulation mix, and zero during the clear half.") { view.exposureBlurMix = it.toFloat() }.also { h -> controlMirrors += { h.set(view.exposureBlurMix.toDouble()) } }
    slider("Soft-half darkening", min = 0.0, max = .5, step = .005, initialValue = view.exposureDarkening.toDouble(),
        hoverText = "Fraction of linear-light brightness removed from this screen's soft half. 0.04 removes four percent; the clear half retains the original brightness.") { view.exposureDarkening = it.toFloat() }.also { h -> controlMirrors += { h.set(view.exposureDarkening.toDouble()) } }
}
displayOpen("screen exposure")
displayOpen("screen cadence")
markdown("screen", "**Screen ${screenIndex + 1}** · initial delay ${view.delayFrames} published frames.\n\nThe live time table is in box 3. The matching accumulation shader box is directly below this one.")


controlGroup("shared looks") {
    stringInput("Look name", initialValue = screenLooks.selectedName,
        hoverText = "The same document-wide name used by the shared-look box. Editing this name updates the other screens' name fields too.") { screenLooks.selectedName = it }
        .also { h -> controlMirrors += { h.set(screenLooks.selectedName) } }
    button("Store from this screen", hoverText = "Capture this screen's current appearance plus the shared speed into the common pool. An existing name gets another version; other screens stay unchanged.") { down ->
        if (down) runCatching { screenLooks.store(screenLooks.selectedName, screenIndex) }
            .onSuccess { markdown("look action", screenLooks.lastAction) }
            .onFailure { markdown("look action", "Could not store look: ${it.message}") }
    }
    button("Recall onto this screen", hoverText = "Apply the common named look to this screen only. The shared-look box's Restore playback speed option controls whether the saved speed also takes effect.") { down ->
        if (down) runCatching { screenLookRecall.recall(screenLooks.selectedName, listOf(screenIndex), delaySeconds = 0.0) }
            .onSuccess { markdown("look action", screenLookRecall.status) }
            .onFailure { markdown("look action", "Could not recall look: ${it.message}") }
    }
}
screenLooks.watchControls(screenIndex) { controlMirrors.forEach { it() } }
displayOpen("shared looks")
