import afield.util.PngEncoder

val sampleRate = 8000.0
val n = 24000
val f0 = 300.0; val f1 = 3000.0
val dur = n / sampleRate
val k = (f1 - f0) / dur
val signal = DoubleArray(n) { i ->
    val t = i / sampleRate
    0.9 * sin(2.0 * PI * (f0 * t + 0.5 * k * t * t)) +
        0.35 * sin(2.0 * PI * 700.0 * t) +
        0.30 * sin(2.0 * PI * 1800.0 * t)
}

val spec = stft(signal, fftSize = 1024, hopSize = 256, window = WindowType.HANN, sampleRate = sampleRate)
val mags = spec.magnitudes()
val maxMag = spec.maxMagnitude().coerceAtLeast(1e-9)
val centroid = spec.centroidTrace()
val nyquist = sampleRate / 2.0

// Cyan -> orange colormap (dark -> cyan mids -> orange peaks), returns ARGB.
fun cyanOrange(v: Double): Int {
    val x = v.coerceIn(0.0, 1.0)
    val (r, g, b) = if (x < 0.5) {
        val t = x / 0.5
        Triple(10 + t * (60 - 10), 14 + t * (190 - 14), 20 + t * (230 - 20))
    } else {
        val t = (x - 0.5) / 0.5
        Triple(60 + t * (255 - 60), 190 + t * (150 - 190), 230 + t * (50 - 230))
    }
    return (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
}

val nf = spec.numFrames; val nb = spec.numBins
fun db(f: Int, b: Int) = 20.0 * log10((mags[f][b] / maxMag).coerceAtLeast(1e-4))
fun sample(fx: Double, fy: Double): Double {
    val x0 = fx.toInt(); val x1 = (x0 + 1).coerceAtMost(nf - 1)
    val y0 = fy.toInt(); val y1 = (y0 + 1).coerceAtMost(nb - 1)
    val tx = fx - x0; val ty = fy - y0
    return (db(x0, y0) * (1 - tx) + db(x1, y0) * tx) * (1 - ty) +
        (db(x0, y1) * (1 - tx) + db(x1, y1) * tx) * ty
}

val w = 1800; val h = 840
val px = IntArray(w * h)
for (xp in 0 until w) {
    val fx = xp.toDouble() / (w - 1) * (nf - 1)
    for (yp in 0 until h) {
        val fy = (h - 1 - yp).toDouble() / (h - 1) * (nb - 1)
        px[xp + yp * w] = cyanOrange((sample(fx, fy) + 80.0) / 80.0)
    }
}
for (f in 0 until spec.numFrames) {
    val xp = (f.toDouble() / (spec.numFrames - 1).coerceAtLeast(1) * (w - 1)).toInt()
    val yp = (h - 1 - (centroid[f] / nyquist).coerceIn(0.0, 1.0) * (h - 1)).toInt()
    for (dy in -1..1) { val yy = yp + dy; if (yy in 0 until h) px[xp + yy * w] = (0xFF shl 24) or 0xFFFFFF }
}

println("frames=${spec.numFrames} bins=${spec.numBins} centroid ${"%.0f".format(centroid.first())} -> ${"%.0f".format(centroid.last())} Hz")
display("main", PngEncoder.encode(px, w, h))   // display renders PNG bytes as an image
