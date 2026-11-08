val wav = "%docs/assets/audio_ai_muscriptor/source.wav".path
val curves = audioCurves(wav)
val colors = [vec(0.39, 0.78, 0.86, 1.0), vec(0.96, 0.70, 0.36, 1.0)]

var t = 3.0
displayAudioBytes(
    wav.toFile().readBytes(),
    options = AudioVisualizerOptions(initialPositionSeconds = t, onTime = { t = it }),
)

var shown = Double.NaN
loop {
    val frames = curves.map { it.frameAt(t) }
    val time = frames.firstOrNull()?.timeSeconds
    if (time != null && time != shown) {
        shown = time
        // Left channel on the left, right on the right; each channel has its own axes.
        val lines = frames.flatMapIndexed { i, frame ->
            val offset = vec(if (i == 0) -110.0 else 110.0, 0.0, 0.0)
            frame!!.flines(80 / curves[i].extent(), depth = 150.0, color = colors[i]).map { it + offset }
        }
        threeLines(lines, channel = "curves", height = 520)
    }
}
    .end { curves.forEach { it.close() } }
