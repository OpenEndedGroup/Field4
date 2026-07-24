import afield.four.audio.resonate
import afield.four.audio.resonatorAlphaHeuristics
import afield.util.PngEncoder

val sampleRate = 8000f
val n = 24000
val f0 = 300.0; val f1 = 3000.0
val durS = n / sampleRate.toDouble()
val k = (f1 - f0) / durS
val signal = FloatArray(n) { i ->
    val t = i / sampleRate.toDouble()
    (0.9 * sin(2.0 * PI * (f0 * t + 0.5 * k * t * t)) +
        0.35 * sin(2.0 * PI * 700.0 * t) +
        0.30 * sin(2.0 * PI * 1800.0 * t)).toFloat()
}

val numRes = 96
val minHz = 120.0; val maxHz = 3900.0
val freqs = FloatArray(numRes) { i ->
    kotlin.math.exp(ln(minHz) + (ln(maxHz) - ln(minHz)) * i / (numRes - 1)).toFloat()
}
val alphas = resonatorAlphaHeuristics(freqs, sampleRate)
val hop = 256

val flat = resonate(signal, sampleRate, freqs, alphas, betas = alphas, hopLength = hop)
val slices = flat.size / (2 * numRes)
fun ampAt(s: Int, kk: Int): Float {
    val base = s * 2 * numRes
    val re = flat[base + kk]; val im = flat[base + numRes + kk]
    return kotlin.math.sqrt(re * re + im * im)
}
var maxA = 1e-9f
for (s in 0 until slices) for (kk in 0 until numRes) { val a = ampAt(s, kk); if (a > maxA) maxA = a }

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

fun db(s: Int, kk: Int) = 20.0 * log10((ampAt(s, kk).toDouble() / maxA).coerceAtLeast(1e-4))
fun sample(fx: Double, fy: Double): Double {
    val x0 = fx.toInt(); val x1 = (x0 + 1).coerceAtMost(slices - 1)
    val y0 = fy.toInt(); val y1 = (y0 + 1).coerceAtMost(numRes - 1)
    val tx = fx - x0; val ty = fy - y0
    return (db(x0, y0) * (1 - tx) + db(x1, y0) * tx) * (1 - ty) +
        (db(x0, y1) * (1 - tx) + db(x1, y1) * tx) * ty
}

val w = 1800; val h = 720
val px = IntArray(w * h)
for (xp in 0 until w) {
    val fx = xp.toDouble() / (w - 1) * (slices - 1)
    for (yp in 0 until h) {
        val fy = (h - 1 - yp).toDouble() / (h - 1) * (numRes - 1)
        px[xp + yp * w] = cyanOrange((sample(fx, fy) + 80.0) / 80.0)
    }
}

println("resonators=$numRes slices=$slices freq ${freqs.first().toInt()} -> ${freqs.last().toInt()} Hz")
display("main", PngEncoder.encode(px, w, h))   // display renders PNG bytes as an image
