import afield.four.audio.resonate
import afield.four.audio.resonatorAlphaHeuristics

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

// dB relative to the peak, as a [slice][resonator] matrix.
val dB = Array(slices) { s ->
    DoubleArray(numRes) { kk -> 20.0 * log10((ampAt(s, kk).toDouble() / maxA).coerceAtLeast(1e-4)) }
}

println("resonators=$numRes slices=$slices freq ${freqs.first().toInt()} -> ${freqs.last().toInt()} Hz")
showHeatmap(dB, colormap = "cyanorange", width = 1800, height = 720, vmin = -80.0, vmax = 0.0,
    transpose = true, flipY = true)
