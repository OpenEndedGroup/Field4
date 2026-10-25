// Non-finite samples are excluded from the range and the statistics, and painted magenta.
// Inspect the magenta patch and isolated infinity beside the finite gradient.
Img.generate(160, 120) { x, y ->
    when {
        x in 60..75 && y in 40..55 -> Float.NaN
        x == 120 && y == 30 -> Float.POSITIVE_INFINITY
        else -> (x + y) / 278f
    }
}
