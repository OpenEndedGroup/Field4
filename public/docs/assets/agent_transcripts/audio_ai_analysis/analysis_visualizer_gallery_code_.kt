import afield.stdlib.*
import afield.stdlib.noir.audioai.*

val gallerySync = "analysis-gallery"
val galleryRate = 8_000
val galleryAudio = FloatArray(galleryRate * 18) { i ->
    val t = i / galleryRate.toDouble()
    val pulse = 0.32 * kotlin.math.exp(-18.0 * (t % 0.5))
    (0.38 * kotlin.math.sin(2.0 * Math.PI * (174.0 + 22.0 * kotlin.math.sin(t * 0.34)) * t) + pulse).toFloat()
}
display("source", AudioVisualizer.renderSamplesHtml(
    galleryAudio, galleryRate,
    AudioVisualizerOptions(width = 1100, waveformHeight = 105, syncGroup = gallerySync),
    label = "model-free synchronized source · play or seek here",
))

val times = (0..180).map { it / 10.0 }
fun series(name: String, label: String, values: List<Double>, unit: String? = null) =
    AnalysisSeries(name, label, unit, times.map { it as Double? }, values.map { it as Double? })

val onset = times.map { t -> 0.08 + 0.82 * Math.pow(kotlin.math.max(0.0, kotlin.math.sin(t * 2.4)), 8.0) }
val beats = (1..35).map { it * 0.5 }
val beatPreview = AnalysisPreview(
    operation = "analysis.beats", durationSeconds = 18.0,
    series = listOf(
        series("onset_strength", "onset strength", onset),
        series("tempo_0_bpm", "tempo · 2 s", times.map { 116.0 + 13.0 * kotlin.math.sin(it * 0.72) }, "bpm"),
        series("tempo_1_bpm", "tempo · 8 s", times.map { 112.0 + 5.0 * kotlin.math.sin(it * 0.23) }, "bpm"),
        series("tempo_0_confidence", "confidence · 2 s", times.map { 0.45 + 0.35 * kotlin.math.sin(it * 0.41) }),
        series("tempo_1_confidence", "confidence · 8 s", times.map { 0.72 + 0.16 * kotlin.math.sin(it * 0.19) }),
    ), beatTimesSeconds = beats,
)
val monoPreview = AnalysisPreview(
    operation = "analysis.pitch_mono", durationSeconds = 18.0,
    series = listOf(
        series("frequency_hz", "pYIN F0", times.map { 174.0 * Math.pow(2.0, (7.0 * kotlin.math.sin(it * 0.34) + 3.0 * kotlin.math.sin(it * 1.1)) / 12.0) }, "Hz"),
        series("voiced_probability", "voiced probability", times.map { 0.55 + 0.42 * kotlin.math.sin(it * 0.57) }),
    ),
)
val notes = (0 until 28).map { i ->
    val start = i * 0.62
    AnalysisNote(start, start + 0.34 + (i % 4) * 0.13, 48 + (i * 5) % 25, 0.42 + (i % 6) * 0.09)
}
val polyPreview = AnalysisPreview(
    operation = "analysis.pitch_poly", durationSeconds = 18.0,
    series = listOf(
        series("maximum_note_activation", "maximum note activation", times.map { 0.38 + 0.5 * kotlin.math.abs(kotlin.math.sin(it * 1.7)) }),
        series("active_pitch_count", "active pitch count", times.map { 1.0 + kotlin.math.floor(4.0 * kotlin.math.abs(kotlin.math.sin(it * 0.52))) }),
    ), notes = notes,
)
display("analysis board", analysisPreviewDashboardHtml(
    listOf(beatPreview, monoPreview, polyPreview),
    width = 1100, panelHeight = 230, syncGroup = gallerySync,
))
