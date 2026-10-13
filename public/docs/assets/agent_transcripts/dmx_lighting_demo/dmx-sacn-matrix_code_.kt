import afield.dmx.*

// sACN (E1.31) out and back in, in one box: an image shown on a 16 x 8 matrix of RGBW fixtures.
// 128 RGBW fixtures x 4 slots fill universe 10 exactly; the matrix is wired serpentine, as most are.
val patch = Patch(startUniverse = 10)
patch.grid(16, 8, spacing = 10.0, profile = FixtureProfile.RGBW, serpentine = true)

// Any Img will do (imgFromFile("...") for a file); here a small gradient made in place.
val img = Img(64, 32, 3, FloatArray(64 * 32 * 3) { i ->
    val x = (i / 3) % 64 / 63f
    val y = (i / 3) / 64 / 31f
    when (i % 3) {
        0 -> x
        1 -> y
        else -> 1f - x
    }
})

val out = sacnOut("127.0.0.1")   // host = null sends to multicast 239.255.0.10 instead
val node = sacnIn()              // Field as an sACN receiver on UDP 5568

// On the sender's clock: sample the image, then sweep a wave of brightness across it.
patch.animate(out) { t ->
    mapImage(img)
    fixtures.forEach { it.dimmer = 0.55 + 0.45 * sin(t * 3.0 - it.position.x * 0.05) }
}

// What we patched, and universe 10 as it came back over the network, decoded as RGBW fixtures in
// wire order — every other row runs backwards, because the matrix is wired serpentine.
loop {
    if (frameNumber % 6 == 0) {
        showPatch(patch, channel = "matrix", w = 480.0, h = 260.0)
        showUniverse(node, 10, profile = FixtureProfile.RGBW, cols = 16, w = 480.0, h = 260.0)
    }
}
