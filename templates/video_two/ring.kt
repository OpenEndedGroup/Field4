import afield.graphics.scene.video.*

displayClearAll()

// One source owns decoding, transport and GPU history. Screens own their exposures and geometry.
prop<StereoVideoCarousel>(carousel) = StereoVideoCarousel(renderer, stereo, count = 8, spacingFrames = 16, automaticDrawing = false)
prop<StereoVideoSurface>(movie) = carousel.surfaces.first() // newest view; existing score/playhead APIs use its clock
val ownedCarousel = carousel
carousel.radius = 2.5f
carousel.fill = .9f
carousel.center.set(0f, 0f, 0f)
carousel.layout()

// Initial look; edit per-screen controls afterwards. These are independent copies of the settings.
carousel.surfaces.forEach { view ->
    view.accumulationMix = 1f
    view.accumulationHalfLife = 4.0
    view.accumulationInputWeight = 1.0
    view.exposureCadenceEnabled = true
    view.exposurePairsPerSecond = 12.0
    view.exposureBlurSigma = 1.3f
    view.exposureBlurMix = .95f
    view.exposureDarkening = .04f
    view.swapEyes = false
}
carousel.source.speed = 8.0

// Reverse time around the fixed screen layout, keeping screen 1 as the newest image.
prop<Boolean>(reverseScreenDelays) = (thisBox.getPropertyRaw("reverseScreenDelays") as? Boolean) ?: true
fun applyRingDelayOrder() {
    val count = carousel.surfaces.size
    carousel.surfaces.forEachIndexed { i, screen ->
        val ageIndex = if (reverseScreenDelays) (count - i) % count else i
        screen.delayFrames = ageIndex * carousel.spacingFrames
    }
}
applyRingDelayOrder()

// Finite numeric fields deliberately have no aesthetic min/max; edits publish atomically on the queue.
val layoutMirrors = mutableListOf<() -> Unit>()
fun afield.ui.output.ControlGroupBuilder.layoutNumber(label: String, read: () -> Float,
    help: String, mirror: Boolean = true, write: (Float) -> Unit) {
    var lastModel = read()
    val handle = stringInput(label, initialValue = lastModel.toString(), hoverText = help) { raw ->
        runCatching {
            val value = raw.trim().toFloatOrNull()
            require(value != null && value.isFinite()) { "Enter a finite number for $label" }
            carousel.editLayout { write(value) }
            lastModel = read()
        }.onFailure { markdown("layout error", it.message ?: "Could not set $label") }
            .onSuccess { displayClear("layout error") }
    }
    if (mirror) layoutMirrors += {
        val value = read()
        if (value != lastModel) { lastModel = value; handle.set(value.toString()) }
    }
}
controlGroup("ring layout") {
    layoutNumber("Global radius (m)", { carousel.radius },
        "Unbounded finite room-space radius. Each screen with a blank radius follows this value; overrides stay independent. Negative radii place screens on the opposite side, zero collapses them.") { carousel.radius = it }
    layoutNumber("Global sector fill", { carousel.fill },
        "Unbounded fraction of one angular sector. One joins neighbouring screens, above one overlaps them; zero collapses width and negative values mirror the geometry. Per-screen blank fills follow this value.") { carousel.fill = it }
    layoutNumber("Set ring angle (degrees)", { carousel.azimuthDegrees },
        "Set the global angle without wrapping or limiting the number entered. Each screen adds its sector and local angle offset. While spinning, motion status shows the changing angle; this field retains the last command.", mirror = false) { carousel.azimuthDegrees = it }
    slider("Delay spacing (frames)", min = 1.0, max = 16.0, step = 1.0, initialValue = carousel.spacingFrames.toDouble(),
        hoverText = "Published-picture offset between neighbouring screens. One gives ages 0–7; sixteen gives 0,16,…,112. Headset redraws do not advance this ring. A spacing edit refills it and resets exposures; warm-up holds the oldest available image.") {
        carousel.spacingFrames = it.toInt()
        applyRingDelayOrder()
    }
    checkbox("Reverse delay direction", initialValue = reverseScreenDelays,
        hoverText = "Reverse the progression of delayed pictures around the ring while keeping screen 1 newest. Enabled gives ages 0,7,6,…,1 times the spacing; off gives 0,1,…,7. Geometry and each screen's saved look stay in place.") {
        reverseScreenDelays = it
        applyRingDelayOrder()
    }
    for ((axis, label) in listOf("x", "y", "z").withIndex()) {
        layoutNumber("Ring centre $label (m)", {
            when (axis) { 0 -> carousel.center.x; 1 -> carousel.center.y; else -> carousel.center.z }
        }, "Unbounded finite world-space coordinate of the ring centre, shared by every screen. Independent of head motion and the Field browser pane.") {
            when (axis) { 0 -> carousel.center.x = it; 1 -> carousel.center.y = it; else -> carousel.center.z = it }
        }
    }
}
displayOpen("ring layout")

// Keep ownership with this launch; the transport box supplies the actual render loop.
var lastLayoutMirror = 0L
loop {
    val now = System.nanoTime()
    if (now - lastLayoutMirror > 250_000_000L) { lastLayoutMirror = now; layoutMirrors.forEach { it() } }
    frame()
}.end { ownedCarousel.close() }
