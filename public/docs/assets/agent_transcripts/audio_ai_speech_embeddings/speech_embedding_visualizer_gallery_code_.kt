import afield.stdlib.*
import afield.stdlib.noir.audioai.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val demoSync = "speech-space-gallery"
val demoRate = 8_000
val demoDuration = 18.0
val demoAudio = FloatArray((demoRate * demoDuration).toInt()) { i ->
    val t = i / demoRate.toDouble()
    val phrase = .5 + .5 * kotlin.math.sin(t * .72)
    val carrier = kotlin.math.sin(2.0 * Math.PI * (118.0 + 34.0 * phrase) * t)
    val formant = kotlin.math.sin(2.0 * Math.PI * (410.0 + 130.0 * kotlin.math.sin(t * .31)) * t)
    (.22 * carrier + .08 * phrase * formant).toFloat()
}
display("source", AudioVisualizer.renderSamplesHtml(
    demoAudio, demoRate,
    AudioVisualizerOptions(width = 1100, waveformHeight = 105, syncGroup = demoSync),
    label = "model-free calibration source · one playhead for every portrait",
))

val times = (0..360).map { it * .05 }
fun speechSeries(name: String, values: List<Double>) = AnalysisSeries(
    name = name, label = name.replace('_', ' '),
    timesSeconds = times.map { it as Double? }, values = values.map { it as Double? },
)
val voice = times.map { t -> (.5 + .5 * kotlin.math.sin(t * 1.45)).coerceIn(0.0, 1.0) }
val energy = times.map { t -> -42.0 + 25.0 * voice[(t / .05).toInt().coerceAtMost(360)] }
val novelty = times.map { t -> .08 + .5 * Math.pow(kotlin.math.abs(kotlin.math.sin(t * 1.9)), 9.0) }
fun preview(backend: String, phase: Double, radial: Double) = AnalysisPreview(
    operation = "analysis.embed_speech", durationSeconds = demoDuration,
    series = listOf(
        speechSeries("voice_activity", voice),
        speechSeries("energy_dbfs", energy),
        speechSeries("embedding_novelty", novelty),
        speechSeries("mds_0", times.map { t -> kotlin.math.cos(t * .55 + phase) * (.55 + radial * voice[(t / .05).toInt()]) + .18 * kotlin.math.cos(t * 2.7) }),
        speechSeries("mds_1", times.map { t -> kotlin.math.sin(t * .55 + phase) * (.55 + radial * voice[(t / .05).toInt()]) + .16 * kotlin.math.sin(t * 2.1) }),
        speechSeries("mds_2", times.map { t -> kotlin.math.cos(t * 1.07 + .28 * kotlin.math.sin(t * .23 + phase)) * (.35 + .55 * voice[(t / .05).toInt()]) }),
        speechSeries("mds_3", times.map { t -> kotlin.math.sin(t * .83 + phase) * (.30 + .48 * voice[(t / .05).toInt()]) }),
    ),
    analysis = buildJsonObject {
        put("backend", backend)
        if (backend.contains("wavlm")) put("wavlm_layer", 6)
    },
)
display("learned spaces", speechEmbeddingPreviewDashboardHtml(
    listOf(
        preview("analysis.emotion2vec · synthetic calibration", 0.0, .32),
        preview("analysis.wavlm_base_plus · synthetic calibration", 1.2, .18),
    ),
    width = 1100, phaseHeight = 245, syncGroup = demoSync,
))
