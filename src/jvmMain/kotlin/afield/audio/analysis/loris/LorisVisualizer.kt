@file:afield.runtime.completion.CompletionCategory("Visualization")

package afield.audio.analysis.loris

import afield.ui.visualization.AudioVisualizer
import afield.ui.visualization.AudioVisualizerOptions
import afield.ui.visualization.TimeViewerShell
import kotlin.math.*

enum class LorisFrequencyAxis { CENTS, ERB, HZ }
enum class LorisTrackSelection { BAND_BALANCED, STRONGEST }

/** Display limits affect drawing only, never the stored native model or resynthesis. */
data class LorisViewOptions(
    val width: Int = 1000, val height: Int = 380,
    val minimumHz: Double = 0.0, val maximumHz: Double? = null,
    val axis: LorisFrequencyAxis = LorisFrequencyAxis.ERB,
    val selection: LorisTrackSelection = LorisTrackSelection.BAND_BALANCED,
    val maxPartials: Int = 320, val pointsPerPartial: Int = 1500,
    val syncGroup: String = "", val label: String = "Loris partial trajectories",
) {
    init {
        require(width >= 320 && height >= 180)
        require(minimumHz.isFinite() && minimumHz >= 0)
        require(maximumHz == null || maximumHz.isFinite() && maximumHz > minimumHz)
        require(axis != LorisFrequencyAxis.CENTS || minimumHz > 0) { "Cents/log frequency needs a positive lower bound" }
        require(maxPartials in 1..2000 && pointsPerPartial in 2..10000)
        require(maxPartials.toLong() * pointsPerPartial <= 500_000)
    }
}

/** Actual irregular trajectories, with shared waveform time-window, cursor and click-to-seek events. */
fun LorisModel.visualizationHtml(options: LorisViewOptions = LorisViewOptions()): String {
    val maximum = options.maximumHz ?: sampleRate / 2.0
    require(maximum > options.minimumHz)
    val ranked = partials.sortedByDescending { p -> p.points.sumOf { it.amplitude } / p.points.size *
        (p.points.last().timeSeconds - p.points.first().timeSeconds) }
    val selected = if (options.selection == LorisTrackSelection.STRONGEST) ranked.take(options.maxPartials) else {
        // Spread the drawing budget across the full native ERB range, not just loud voiced fundamentals.
        val erbMax = 21.4 * log10(1 + .00437 * sampleRate / 2.0)
        val bands = ranked.groupBy { p ->
            val weight = p.points.sumOf { it.amplitude }.coerceAtLeast(1e-20)
            val center = p.points.sumOf { it.frequencyHz * it.amplitude } / weight
            floor(8 * 21.4 * log10(1 + .00437 * center) / erbMax).toInt().coerceIn(0, 7)
        }
        val picked = bands.toSortedMap().values.flatMap { it.take(max(1, options.maxPartials / 8)) }
            .take(options.maxPartials)
        val ids = picked.map { it.id }.toSet()
        picked + ranked.filterNot { it.id in ids }.take(options.maxPartials - picked.size)
    }
    val tracks = selected.map { p ->
        // Evenly select native points, including both endpoints; do not create pooled timestamps.
        val n = min(p.points.size, options.pointsPerPartial)
        mapOf("id" to p.id, "points" to List(n) { i ->
            val b = p.points[if (n == 1) 0 else (i.toLong() * (p.points.size - 1) / (n - 1)).toInt()]
            listOf(b.timeSeconds, b.frequencyHz, b.amplitude, b.bandwidth, b.phaseRadians)
        })
    }
    val cfg = TimeViewerShell.scriptJson(mapOf("duration" to durationSeconds, "tracks" to tracks,
        "totalTracks" to partials.size, "minimumHz" to options.minimumHz, "maximumHz" to maximum,
        "nyquistHz" to sampleRate / 2.0, "selection" to options.selection.name,
        "axis" to options.axis.name, "group" to options.syncGroup, "label" to options.label,
        "height" to options.height))
    val script = checkNotNull(LorisModel::class.java.getResourceAsStream("/loris-viewer.js"))
        .bufferedReader().use { it.readText() }
    return """
<div class="field-loris-view" style="width:${options.width}px;max-width:100%;box-sizing:border-box;background:#10161d;color:#d8e2eb;border:1px solid #34424e;border-radius:10px;padding:14px;font:12px ui-monospace,monospace">
<div style="display:flex;gap:10px;align-items:center;flex-wrap:wrap"><strong class="lv-title"></strong><span class="lv-count" style="color:#9daebc;flex:1"></span><button class="lv-all" type="button">all time</button></div>
<div style="display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-top:10px;font-size:10px;color:#a4bacb"><button class="lv-full" type="button">full band</button><button class="lv-voice" type="button">80–4000 Hz detail</button><label>axis <select class="lv-axis"><option value="ERB">ERB</option><option value="CENTS">cents/log</option><option value="HZ">Hz</option></select></label><span>local bandwidth</span><span>0</span><span style="width:110px;height:8px;background:linear-gradient(90deg,rgb(83,194,229),rgb(255,164,75));border-radius:3px"></span><span>1</span></div>
<div class="lv-frame" style="position:relative"><svg class="lv-plot" viewBox="0 0 1000 ${options.height}" style="width:100%;display:block;touch-action:none" role="img" aria-label="Partial frequency trajectories; click to seek, wheel to zoom"></svg></div>
<div class="lv-readout" style="min-height:18px;color:#a4bacb;margin-top:6px"></div>
<div style="font-size:10px;color:#8496a4;margin-top:4px">local segment colour: blue = bandwidth 0, amber = bandwidth 1 · not confidence · wheel zoom / drag pan / click seek</div>
<script type="application/json" class="lv-config">$cfg</script><script>$script</script>
</div>
""".trimIndent()
}

/** Adds a real reconstruction player to the synchronized partial map. PCM data itself is unchanged. */
fun LorisResult.visualizationHtml(options: LorisViewOptions = LorisViewOptions()): String {
    require(peak <= 1f) { "Reconstruction peak is $peak: apply an explicit audition gain before 16-bit playback" }
    return model.visualizationHtml(options) + AudioVisualizer.renderSamplesHtml(samples, model.sampleRate,
        options = AudioVisualizerOptions(width = options.width, waveformHeight = 120, syncGroup = options.syncGroup),
        label = "${options.label} · reconstruction · ${model.sampleRate} Hz")
}
