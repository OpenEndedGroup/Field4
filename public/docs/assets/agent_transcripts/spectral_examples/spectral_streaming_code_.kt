val sampleRate = 8000f
val n = 8000
val stream = FloatArray(n) { i ->
    val t = i / sampleRate
    val f = 200f + 1800f * (i.toFloat() / n)
    var s = 0.6f * sin(2.0 * PI * f * t).toFloat() + 0.25f * sin(2.0 * PI * 900.0 * t).toFloat()
    if (i == n / 3 || i == 2 * n / 3) s += 2.0f
    s
}

val brightness = EMAFilter(0.1f) then SpectralCentroidFilter(fftSize = 1024, hopSize = 256, sampleRate = sampleRate)
val onset = SpectralFluxFilter(fftSize = 1024, hopSize = 256, sampleRate = sampleRate)
val level = RmsFilter(windowSize = 512)

val cTrace = FloatArray(n); val fTrace = FloatArray(n); val rTrace = FloatArray(n)
for (i in 0 until n) {
    cTrace[i] = brightness(stream[i])
    fTrace[i] = onset(stream[i])
    rTrace[i] = level(stream[i])
}

// Different scales (Hz vs flux vs 0..1 level): normalize each to compare shape, one call.
showSignals(
    "spectral centroid (Hz)" to cTrace.toList(),
    "spectral flux (onset novelty)" to fTrace.toList(),
    "rolling RMS (level)" to rTrace.toList(),
    channel = "main",
    xTitle = "sample", normalize = true,
)
println("centroid ${"%.0f".format(cTrace[3000])} -> ${"%.0f".format(cTrace[n - 200])} Hz, flux peak ${"%.3f".format(fTrace.maxOrNull() ?: 0f)}")
