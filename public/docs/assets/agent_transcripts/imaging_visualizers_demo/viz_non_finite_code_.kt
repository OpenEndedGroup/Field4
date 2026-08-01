// Non-finite samples are excluded from the range and the statistics, and painted magenta.
// The old behaviour: one NaN poisoned min()/max(), normalized() produced an all-NaN
// image, the PNG quantiser turned NaN into 0, and the thumbnail went silently black.
Img.generate(160, 120) { x, y ->
    when {
        x in 60..75 && y in 40..55 -> Float.NaN
        x == 120 && y == 30 -> Float.POSITIVE_INFINITY
        else -> (x + y) / 278f
    }
}
