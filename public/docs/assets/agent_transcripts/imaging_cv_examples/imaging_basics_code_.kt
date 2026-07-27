// An Img is width x height x channels floats, interleaved, origin top-left. This one is
// 2400x1800 — 4.3 megapixels, 52 MB of float RGB — because that is the size at which the
// question "is this fast enough to actually use?" has a meaningful answer.
val card = Img.generateRGB(2400, 1800) { x, y ->
    val u = x / 2399f
    val v = y / 1799f
    val disc = if (hypot(x - 600.0, y - 600.0) <= 340.0) 1f else 0f
    val bar = if (y in 1300..1420 && x in 200..2199) 1f else 0f
    Triple(
        (0.15f + 0.7f * u * (1f - disc) + disc * 0.9f + bar).coerceIn(0f, 1f),
        (0.15f + 0.6f * v + disc * 0.25f + bar).coerceIn(0f, 1f),
        (0.55f - 0.4f * u + disc * 0.1f + bar).coerceIn(0f, 1f)
    )
}

val gray = card.luma()                 // BT.709 perceptual grayscale
val hsv = card.rgbToHsv()              // h, s, v each in 0..1
val small = card.resize(600, 450)      // area-averaged (anti-aliased) downsample
val backUp = small.resize(2400, 1800)  // bilinear upsample: the detail is really gone
val t = gray.otsuThreshold()           // computed, not guessed
val mask = gray.threshold(t)

showImgGrid(
    "source RGB (${card.width}x${card.height})" to card,
    "luma()" to gray,
    "hue channel" to hsv.channel(0),
    "saturation channel" to hsv.channel(1),
    "resize(600,450) then back up" to backUp,
    "thresholdOtsu() @ ${"%.3f".format(t)}" to mask,
    columns = 3,
    cellSide = 260,
)
println("Img ${card.width}x${card.height} x${card.channels} ch, mean ${"%.3f".format(card.mean())}, " +
    "otsu ${"%.3f".format(t)}, foreground ${mask.data.count { it > 0.5f }} px")

// The threshold is a mask; a mask has outlines; outlines are geometry. Everything the
// family produces that is geometry rather than raster ends up in a lines() call.
lines(mask.toContourFLines(minPerimeter = 120, simplifyTolerance = 12.0))
