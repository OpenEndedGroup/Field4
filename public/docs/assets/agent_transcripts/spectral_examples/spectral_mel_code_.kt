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

// dB relative to the peak, as a [frame][band] matrix.
val dB = Array(spec.numFrames) { f ->
    DoubleArray(numBands) { b -> 20.0 * log10((mel[f][b] / maxMel).coerceAtLeast(1e-4)) }
}

println("frames=${spec.numFrames} bands=$numBands")
showHeatmap(dB, colormap = "cyanorange", width = 1800, height = 720, vmin = -80.0, vmax = 0.0,
    transpose = true, flipY = true)
