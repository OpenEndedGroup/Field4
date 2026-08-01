// showImage displays a ready-made ARGB IntArray directly (encode + display), for when you
// already have a pixel buffer rather than a scalar field.
val w = 512; val h = 256
val px = IntArray(w * h)
for (y in 0 until h) for (x in 0 until w) {
    val r = x * 255 / (w - 1)
    val b = y * 255 / (h - 1)
    px[x + y * w] = (0xFF shl 24) or (r shl 16) or (0x40 shl 8) or b
}
showImage(px, w, h)
