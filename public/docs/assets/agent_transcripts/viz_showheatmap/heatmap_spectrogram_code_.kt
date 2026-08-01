// A synthetic [frame][bin] magnitude spectrogram with a rising ridge. transpose=true puts
// time on x and frequency on y; flipY keeps low frequency at the bottom; logScale gives a
// dB-like view. This is exactly the shape the spectral docs hand-rolled by hand before.
val frames = 120; val bins = 80
val spec = Array(frames) { f ->
    DoubleArray(bins) { bin ->
        val ridge = (bins * (0.15 + 0.7 * f / frames)).toInt()
        val d = (bin - ridge).toDouble()
        (0.001 + exp(-d * d / 12.0)) * (1.0 + 0.3 * sin(f * 0.3))
    }
}
showHeatmap(spec, colormap = "magma", width = 900, transpose = true, flipY = true, logScale = true)
