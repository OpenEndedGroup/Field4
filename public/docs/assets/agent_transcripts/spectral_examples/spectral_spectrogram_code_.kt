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

// dB relative to the peak, as a [frame][bin] matrix.
val dB = Array(spec.numFrames) { f ->
    DoubleArray(spec.numBins) { b -> 20.0 * log10((mags[f][b] / maxMag).coerceAtLeast(1e-4)) }
}
// Spectral centroid as a normalized (x, y) overlay: x = time fraction, y = fraction of Nyquist.
val trace = centroid.indices.map { f ->
    (f.toDouble() / (spec.numFrames - 1).coerceAtLeast(1)) to (centroid[f] / nyquist).coerceIn(0.0, 1.0)
}

println("frames=${spec.numFrames} bins=${spec.numBins} centroid ${"%.0f".format(centroid.first())} -> ${"%.0f".format(centroid.last())} Hz")
showHeatmap(dB, colormap = "cyanorange", width = 1800, height = 840, vmin = -80.0, vmax = 0.0,
    transpose = true, flipY = true, overlay = trace, overlayThickness = 3)
