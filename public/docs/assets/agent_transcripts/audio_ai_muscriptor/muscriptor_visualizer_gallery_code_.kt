import afield.stdlib.*
import afield.stdlib.noir.audioai.*

val sync = "muscriptor-gallery"
val rate = 8_000
val duration = 12.0
val samples = FloatArray((rate * duration).toInt()) { i ->
    val t = i / rate.toDouble()
    val bass = .16 * kotlin.math.sin(2 * Math.PI * 110 * t)
    val upper = .1 * kotlin.math.sin(2 * Math.PI * (220 + 45 * kotlin.math.sin(t * .4)) * t)
    val pulse = .08 * kotlin.math.exp(-24 * (t % .5))
    (bass + upper + pulse).toFloat()
}
display("source", AudioVisualizer.renderSamplesHtml(
    samples, rate,
    AudioVisualizerOptions(width = 1100, waveformHeight = 125, syncGroup = sync),
    label = "model-free score calibration · play, zoom, or seek either view",
))

val times = (0..300).map { it * .04 }
fun series(name: String, values: List<Double>) = AnalysisSeries(
    name, name.replace('_', ' '), timesSeconds = times.map { it as Double? },
    values = values.map { it as Double? },
)
val instruments = listOf("acoustic_bass", "cello", "violin", "drums")
val notes = (0 until 34).map { i ->
    val start = i * .34
    AnalysisNote(
        startSeconds = start,
        endSeconds = start + if (i % 7 == 0) .08 else .48 + .12 * (i % 4),
        midi = if (i % 7 == 0) 36 else 43 + (i * 5) % 36,
        amplitude = 1.0,
        instrument = instruments[i % instruments.size],
    )
}
val preview = AnalysisPreview(
    operation = "analysis.transcribe_music", durationSeconds = duration,
    series = listOf(
        series("active_note_count", times.map { 1.0 + kotlin.math.floor(4 * kotlin.math.abs(kotlin.math.sin(it * .7))) }),
        series("onset_count", times.map { if ((it * 4).toInt() % 4 == 0) 2.0 else 0.0 }),
    ), notes = notes,
)
display("score", analysisPreviewVisualizationHtml(
    preview, width = 1100, height = 360, syncGroup = sync,
))
