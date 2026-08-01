// Each rung is drawn at its real fraction of the base resolution, bottom-aligned, so the
// halving is the visible fact rather than a number in a caption.
Img.generateRGB(240, 180) { x, y ->
    val disc = if (hypot(x - 172.0, y - 62.0) <= 30.0) 1f else 0f
    val rect = if (x in 30..90 && y in 26..76) 1f else 0f
    Triple(
        (0.10f + 0.30f * (x / 239f) + 0.8f * rect).coerceIn(0f, 1f),
        (0.12f + 0.22f * (y / 179f) + 0.7f * rect + 0.6f * disc).coerceIn(0f, 1f),
        (0.34f - 0.20f * (x / 239f) + 0.8f * disc).coerceIn(0f, 1f)
    )
}.gaussianPyramid(5)
