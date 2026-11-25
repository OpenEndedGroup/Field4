val wav = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
// A curve every 256 samples (5.3 ms at 48 kHz) that remembers 384 spectra (2 s).
val curve = audioCurves(wav, AudioCurveSettings(hopSize = 256, history = 384)).first()
val scale = 100 / curve.extent()

var t = 2.0
displayAudioBytes(
    wav.toFile().readBytes(),
    options = AudioVisualizerOptions(initialPositionSeconds = t, onTime = { t = it }),
)

var shown = Double.NaN
loop {
    val frame = curve.frameAt(t)
    if (frame != null && frame.timeSeconds != shown) {
        shown = frame.timeSeconds
        threeLines(frame.flines(scale, depth = 300.0, bands = 16), channel = "curve", height = 560)
    }
}.end { curve.close() }
