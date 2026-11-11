import afield.graphics.scene.video.*

displayClearAll()

// One source owns decoding, transport and GPU history. Screens own their exposures and geometry.
prop<StereoVideoCarousel>(carousel) =
    StereoVideoCarousel(renderer, stereo, count = 8, spacingFrames = 16, automaticDrawing = false)
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

// Broad slider ranges retain playful signed layouts; current values extend them on first construction.
val layoutMirrors = mutableListOf<() -> Unit>()
fun afield.ui.output.ControlGroupBuilder.layoutSlider(
    label: String,
    min: Double,
    max: Double,
    step: Double,
    read: () -> Float,
    help: String,
    mirror: Boolean = true,
    write: (Float) -> Unit,
) {
    val initial = read().toDouble()
    val handle = slider(
        label,
        min = minOf(min, initial),
        max = maxOf(max, initial),
        step = step,
        initialValue = initial,
        hoverText = help,
    ) { value ->
        runCatching { carousel.editLayout { write(value.toFloat()) } }
            .onFailure { markdown("layout error", it.message ?: "Could not set $label") }
            .onSuccess { displayClear("layout error") }
    }
    if (mirror) layoutMirrors += { handle.set(read().toDouble()) }
}
controlGroup("ring layout") {
    layoutSlider(
        "Global radius (m)",
        -50.0,
        50.0,
        .01,
        { carousel.radius },
        "Signed room-space radius, from -50 to +50 metres. Each screen with a blank radius follows this value; overrides stay independent. Negative radii place screens on the opposite side, zero collapses them.",
    ) {
        carousel.radius = it
    }
    layoutSlider(
        "Global sector fill",
        -16.0,
        16.0,
        .01,
        { carousel.fill },
        "Signed fraction of one angular sector, from -16 to +16. One joins neighbouring screens, above one overlaps them; zero collapses width and negative values mirror the geometry. Per-screen blank fills follow this value.",
    ) {
        carousel.fill = it
    }
    layoutSlider(
        "Set ring angle (degrees)",
        -1440.0,
        1440.0,
        .1,
        { carousel.azimuthDegrees },
        "Set the global angle across four turns in either direction. Each screen adds its sector and local angle offset. While spinning, motion status shows the changing angle; this field retains the last command.",
        mirror = false,
    ) {
        carousel.azimuthDegrees = it
    }
    slider(
        "Delay spacing (frames)",
        min = 1.0,
        max = 16.0,
        step = 1.0,
        initialValue = carousel.spacingFrames.toDouble(),
        hoverText =
            "Published-picture offset between neighbouring screens. One gives ages 0–7; sixteen gives 0,16,…,112. Headset redraws do not advance this ring. A spacing edit refills it and resets exposures; warm-up holds the oldest available image.",
    ) {
        carousel.spacingFrames = it.toInt()
        applyRingDelayOrder()
    }
    checkbox(
        "Reverse delay direction",
        initialValue = reverseScreenDelays,
        hoverText =
            "Reverse the progression of delayed pictures around the ring while keeping screen 1 newest. Enabled gives ages 0,7,6,…,1 times the spacing; off gives 0,1,…,7. Geometry and each screen's saved look stay in place.",
    ) {
        reverseScreenDelays = it
        applyRingDelayOrder()
    }
    for ((axis, label) in listOf("x", "y", "z").withIndex()) {
        layoutSlider(
            "Ring centre $label (m)",
            -50.0,
            50.0,
            .01,
            {
                when (axis) {
                    0 -> carousel.center.x
                    1 -> carousel.center.y
                    else -> carousel.center.z
                }
            },
            "Signed world-space coordinate from -50 to +50 metres of the ring centre, shared by every screen. Independent of head motion and the Field browser pane.",
        ) {
            when (axis) {
                0 -> carousel.center.x = it
                1 -> carousel.center.y = it
                else -> carousel.center.z = it
            }
        }
    }
}
displayOpen("ring layout")

// Keep ownership with this launch; the transport box supplies the actual render loop.
var lastLayoutMirror = 0L
loop {
    val now = System.nanoTime()
    if (now - lastLayoutMirror > 250_000_000L) {
        lastLayoutMirror = now
        layoutMirrors.forEach { it() }
    }
    frame()
}
    .end { ownedCarousel.close() }
