import afield.util.PngEncoder

val sampleRate = 8000.0
val n = 24000
val up0 = 200.0; val up1 = 3200.0
val dn0 = 3200.0; val dn1 = 200.0
val dur = n / sampleRate
val ku = (up1 - up0) / dur
val kd = (dn1 - dn0) / dur
val signal = DoubleArray(n) { i ->
    val t = i / sampleRate
    0.7 * sin(2.0 * PI * (up0 * t + 0.5 * ku * t * t)) +
        0.7 * sin(2.0 * PI * (dn0 * t + 0.5 * kd * t * t))
}

val spec = stft(signal, fftSize = 1024, hopSize = 256, window = WindowType.HANN, sampleRate = sampleRate)
val numBands = 48
val mel = Array(spec.numFrames) { Mel.filterBank(spec.magnitude(it), sampleRate, numBands, 0.0, sampleRate / 2.0) }
var maxMel = 1e-9
for (row in mel) for (v in row) if (v > maxMel) maxMel = v

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

val nf = spec.numFrames
fun db(f: Int, b: Int) = 20.0 * log10((mel[f][b] / maxMel).coerceAtLeast(1e-4))
fun sample(fx: Double, fy: Double): Double {
    val x0 = fx.toInt(); val x1 = (x0 + 1).coerceAtMost(nf - 1)
    val y0 = fy.toInt(); val y1 = (y0 + 1).coerceAtMost(numBands - 1)
    val tx = fx - x0; val ty = fy - y0
    return (db(x0, y0) * (1 - tx) + db(x1, y0) * tx) * (1 - ty) +
        (db(x0, y1) * (1 - tx) + db(x1, y1) * tx) * ty
}

val w = 1800; val h = 720
val px = IntArray(w * h)
for (xp in 0 until w) {
    val fx = xp.toDouble() / (w - 1) * (nf - 1)
    for (yp in 0 until h) {
        val fy = (h - 1 - yp).toDouble() / (h - 1) * (numBands - 1)
        px[xp + yp * w] = cyanOrange((sample(fx, fy) + 80.0) / 80.0)
    }
}

println("frames=${spec.numFrames} bands=$numBands")
display("main", PngEncoder.encode(px, w, h))   // display renders PNG bytes as an image
