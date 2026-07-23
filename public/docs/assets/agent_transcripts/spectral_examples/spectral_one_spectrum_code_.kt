// Synthesise a rising linear chirp + two steady tones, FFT it, draw the one-sided spectrum.
val sampleRate = 8000.0
val n = 2048
val f0 = 300.0; val f1 = 3000.0
val dur = n / sampleRate
val k = (f1 - f0) / dur
val signal = DoubleArray(n) { i ->
    val t = i / sampleRate
    0.9 * sin(2.0 * PI * (f0 * t + 0.5 * k * t * t)) +
        0.4 * sin(2.0 * PI * 700.0 * t) +
        0.3 * sin(2.0 * PI * 1800.0 * t)
}

val spec = fft(signal, WindowType.HANN, sampleRate)
val mag = spec.magnitudeOneSided()
val freqs = spec.frequencies()
val centroid = spec.centroid()
val rolloff = spec.rolloff(0.85)
val peak = spec.peakFrequency()

// One call puts the magnitude curve on screen vs real Hz, centroid + rolloff as dashed marks.
showSignals(
    "magnitude" to mag.toList(),
    x = freqs.toList(),
    channel = "main",
    xTitle = "frequency (Hz)", yTitle = "magnitude",
    marks = listOf(
        "centroid ${"%.0f".format(centroid)} Hz" to centroid,
        "85% rolloff ${"%.0f".format(rolloff)} Hz" to rolloff,
    ),
)
println("peak=${"%.0f".format(peak)}Hz centroid=${"%.0f".format(centroid)}Hz rolloff=${"%.0f".format(rolloff)}Hz bins=${mag.size}")
