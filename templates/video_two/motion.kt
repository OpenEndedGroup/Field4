// Turns/second keeps the coarse range useful at wagonwheel aliases; fine trims the chosen speed.
// These global layout controls are independent of the shared video's playback rate.
prop<Double>(ringSpinCoarse) = (thisBox.getPropertyRaw("ringSpinCoarse") as? Double) ?: 0.0
prop<Double>(ringSpinFine) = (thisBox.getPropertyRaw("ringSpinFine") as? Double) ?: 0.0
var coarseSpinControl: afield.ui.output.ControlValueHandle<Double>? = null
var fineSpinControl: afield.ui.output.ControlValueHandle<Double>? = null
controlGroup("ring motion") {
    coarseSpinControl = slider("Spin coarse (turns/s)", min = -30.0, max = 30.0, step = .01, initialValue = ringSpinCoarse,
        hoverText = "Signed turns per real second. Thirty turns/s reaches beyond the repeated eight-screen wagonwheel aliases at normal headset refresh rates. Add the fine control to trim an alias; zero plus zero stops motion.") { ringSpinCoarse = it }
    fineSpinControl = slider("Spin fine (turns/s)", min = -1.0, max = 1.0, step = .001, initialValue = ringSpinFine,
        hoverText = "Fine signed turns/s added to the coarse speed. 0.001 turns/s is 0.36 degrees/s. This changes geometry rotation, not the movie clock or frame-delay spacing.") { ringSpinFine = it }
    button("Stop ring rotation", hoverText = "Set both speeds to zero and hold the current orientation.") { down ->
        if (down) { ringSpinCoarse = 0.0; ringSpinFine = 0.0; coarseSpinControl?.set(0.0); fineSpinControl?.set(0.0) }
    }
}
displayOpen("ring motion")
var previousSpinTime = System.nanoTime()
var lastSpinStatus = 0L
loop {
    val now = System.nanoTime()
    val seconds = (now - previousSpinTime).coerceAtLeast(0L) / 1e9
    previousSpinTime = now
    val turns = ringSpinCoarse + ringSpinFine
    if (turns != 0.0) {
        val angle = carousel.azimuthDegrees + turns * 360.0 * seconds
        carousel.azimuthDegrees = (((angle % 360.0) + 360.0) % 360.0).toFloat()
        carousel.layout()
    }
    if (now - lastSpinStatus > 250_000_000L) {
        lastSpinStatus = now
        markdown("motion status", "**${turns.f2} turns/s** · ${(turns * 360).f2} degrees/s · ring angle **${carousel.azimuthDegrees.f2}°**.\n\nRotation uses real elapsed time; the screen pattern can alias against headset sampling at high speeds.", open = false)
    }
    frame()
}
