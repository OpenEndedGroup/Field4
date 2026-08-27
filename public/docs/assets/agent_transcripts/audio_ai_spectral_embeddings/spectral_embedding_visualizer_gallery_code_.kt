import afield.stdlib.*
import afield.stdlib.noir.audioai.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val demoSync = "spectral-space-gallery"
val rate = 8_000
val duration = 14.0
val samples = FloatArray((rate * duration).toInt()) { i ->
    val t = i / rate.toDouble()
    val sweep = 170.0 + 520.0 * (.5 + .5 * kotlin.math.sin(t * .37))
    (.18 * kotlin.math.sin(2 * Math.PI * sweep * t) +
        .07 * kotlin.math.sin(2 * Math.PI * (1250 + 420 * kotlin.math.sin(t * .19)) * t)).toFloat()
}
display("source", AudioVisualizer.renderSamplesHtml(
    samples, rate,
    AudioVisualizerOptions(width = 1100, waveformHeight = 105, syncGroup = demoSync),
    label = "model-free spectral calibration · one cursor",
))
val times = (0..280).map { it * .05 }
fun s(name: String, values: List<Double>) = AnalysisSeries(
    name, name.replace('_', ' '), timesSeconds = times.map { it as Double? },
    values = values.map { it as Double? },
)
fun preview(label: String, phase: Double) = AnalysisPreview(
    operation = "analysis.embed_spectral", durationSeconds = duration,
    series = listOf(
        s("energy_dbfs", times.map { -34.0 + 9 * kotlin.math.sin(it * .43) }),
        s("spectral_flux", times.map { .08 + .6 * Math.pow(kotlin.math.abs(kotlin.math.sin(it * 1.1)), 8.0) }),
        s("spectral_centroid_hz", times.map { 900.0 + 650 * kotlin.math.sin(it * .37) }),
        s("spectral_flatness", times.map { .08 + .06 * kotlin.math.sin(it * .71) }),
    ) + (0..3).map { axis ->
        s("tsne_$axis", times.map { t ->
            kotlin.math.sin(t * (.37 + axis * .13) + phase + axis) + .24 * kotlin.math.sin(t * 2.1)
        })
    },
    analysis = buildJsonObject {
        put("backend", "model-free calibration")
        put("projection_method", "t-SNE")
        put("projection_series_prefix", "tsne_")
        put("projection_distance", label)
        put("projection_sampled_frames", times.size)
    },
)
display("spectral spaces", spectralEmbeddingPreviewDashboardHtml(
    listOf(preview("mel Wasserstein-1", 0.0), preview("MFCC Euclidean", 1.4)),
    width = 1100, phaseHeight = 245, syncGroup = demoSync,
))
