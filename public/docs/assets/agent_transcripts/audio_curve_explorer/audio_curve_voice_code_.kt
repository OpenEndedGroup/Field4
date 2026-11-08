val wav = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
val curve = audioCurves(wav, AudioCurveSettings(dimensions = 3)).first()
val scale = 100 / curve.extent()

// The player keeps the audio clock and tells this box where it is, or where you are scrubbing.
var t = 0.0
displayAudioBytes(wav.toFile().readBytes(), options = AudioVisualizerOptions(onTime = { t = it }))

// A faint cube around where the curve lives, so the view holds still while you orbit.
val room = fline {
    for (z in [-80, 80]) {
        moveTo(-80, -80, z)
        lineTo(80, -80, z)
        lineTo(80, 80, z)
        lineTo(-80, 80, z)
        lineTo(-80, -80, z)
    }
    for (x in [-80, 80]) for (y in [-80, 80]) {
        moveTo(x, y, -80)
        lineTo(x, y, 80)
    }
    color = vec(1, 1, 1, 0.12)
}

var shown = Double.NaN
loop {
    // Before the first complete window, show the first curve.
    val frame = curve.frameAt(t) ?: curve.frame(0)
    if (frame.timeSeconds != shown) {
        shown = frame.timeSeconds
        threeLines(frame.flines(scale, bands = 16, centered = true) + room, channel = "curve", height = 560)
    }
}
    .end { curve.close() }
