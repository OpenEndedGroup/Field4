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

controlGroup("ring layout") {
    slider("Radius (metres)", min = .75, max = 6.0, step = .01, initialValue = carousel.radius.toDouble(),
        hoverText = "Distance from the ring centre to the middle of every screen. Width and curvature follow the radius, so each screen remains part of the same cylinder.") {
        carousel.radius = it.toFloat(); carousel.layout()
    }
    slider("Screen sector fill", min = .2, max = 1.0, step = .01, initialValue = carousel.fill.toDouble(),
        hoverText = "Fraction of each 45-degree sector occupied by its screen. One joins the screens into a cylinder; 0.9 leaves a small gap. Positive curvature faces inward.") {
        carousel.fill = it.toFloat(); carousel.layout()
    }
    slider("Ring rotation (degrees)", min = -180.0, max = 180.0, step = .1, initialValue = carousel.azimuthDegrees.toDouble(),
        hoverText = "Rotate the whole screen arrangement around gravity's up axis. The ring remains fixed in room space, rather than following each head movement.") {
        carousel.azimuthDegrees = it.toFloat(); carousel.layout()
    }
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
        slider("Ring centre $label (m)", min = -5.0, max = 5.0, step = .01, initialValue = when (axis) { 0 -> carousel.center.x.toDouble(); 1 -> carousel.center.y.toDouble(); else -> carousel.center.z.toDouble() },
            hoverText = "World-space centre of the screen cylinder. Default zero surrounds the origin. This is independent of the optional Field browser pane.") {
            when (axis) { 0 -> carousel.center.x = it.toFloat(); 1 -> carousel.center.y = it.toFloat(); else -> carousel.center.z = it.toFloat() }
            carousel.layout()
        }
    }
}
displayOpen("ring layout")

// Keep ownership with this launch; the transport box supplies the actual render loop.
loop { frame() }.end { ownedCarousel.close() }
