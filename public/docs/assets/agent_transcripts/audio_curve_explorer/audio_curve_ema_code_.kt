val wav = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
// 48 running averages of the spectrum, from one hop up to 20 seconds, longest first.
val settings = AudioCurveSettings(dimensions = 3, history = 48, emaLongestSeconds = 20.0)
val curve = audioCurves(wav, settings).first()
val scale = 100 / curve.extent()

var t = 0.0
displayAudioBytes(wav.toFile().readBytes(), options = AudioVisualizerOptions(onTime = { t = it }))

var shown = Double.NaN
loop {
    val frame = curve.frameAt(t)
    if (frame != null && frame.timeSeconds != shown) {
        shown = frame.timeSeconds
        threeLines(frame.flines(scale, bands = 16), channel = "curve", height = 560)
    }
}
    .end { curve.close() }
