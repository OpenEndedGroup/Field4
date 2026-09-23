@file:afield.CompletionCategory("Visualization")

package afield.stdlib

import afield.Box
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.math.*

/** One lane, with an optional earlier version drawn in dashed grey. Evaluators must be pure. */
data class SignalChannel(
    val name: String,
    val value: (Double) -> Double,
    val minimum: Double = 0.0,
    val maximum: Double = 1.0,
    val comparison: ((Double) -> Double)? = null,
)

data class SignalViewerOptions(
    val width: Int = 1100,
    val laneHeight: Int = 130,
    val label: String = "Signal",
    val comparisonLabel: String = "previous",
    val initialStart: Double? = null,
    val initialDuration: Double? = null,
    /** Same DOM-event timeline used by AudioVisualizer. Units are seconds. */
    val syncGroup: String = "",
    /** Stable across revisions to keep the user's view when the output is replaced. */
    val viewKey: String = "",
)

data class SignalTrace(val low: List<Double>, val high: List<Double>)
data class SignalLaneWindow(val name: String, val minimum: Double, val maximum: Double,
    val current: SignalTrace, val comparison: SignalTrace?)
data class SignalWindow(val start: Double, val end: Double, val mode: String,
    val sampleSpacing: Double, val lanes: List<SignalLaneWindow>)

/**
 * A range-query source for the frontend signal viewer. Like AudioVisualizer, it sends only the
 * visible envelope; unlike recorded audio, functions are evaluated more finely as you zoom in.
 * Envelopes preserve extrema on the sampling grid, not arbitrarily narrow features between samples.
 * Keep this object alive while its HTML is visible, or use Box.displaySignal, which does so for you.
 */
class SignalViewer(
    channels: List<SignalChannel>,
    val start: Double,
    val end: Double,
    /** Largest requested sampling interval. A query is capped at 500,000 intervals per lane. */
    val sampleStep: Double = 0.02,
) : AutoCloseable {
    val channels = channels.toList()
    val id: String = UUID.randomUUID().toString()
    init {
        require(start.isFinite() && end.isFinite() && end > start && (end - start).isFinite())
        require(sampleStep.isFinite() && sampleStep > 0)
        require(channels.size in 1..8)
        require(channels.all { it.minimum.isFinite() && it.maximum.isFinite() && it.maximum > it.minimum })
        synchronized(sources) {
            sources.entries.removeIf { it.value.get() == null }
            sources[id] = WeakReference(this)
        }
    }

    override fun close() { synchronized(sources) { sources.remove(id) } }

    /** Bounded response size; checkActive permits a server request to cancel between sample batches. */
    fun window(from: Double, to: Double, points: Int, checkActive: () -> Unit = {}): SignalWindow {
        require(from.isFinite() && to.isFinite() && from >= start && to <= end && to > from) { "Window outside signal domain" }
        require(points in 16..4096) { "points must be in 16..4096" }
        val span = to - from
        val envelope = span / sampleStep > points * 2
        val intervals = if (envelope) ceil(span / sampleStep).coerceAtMost(500_000.0).toInt()
            else points * 2
        val bins = if (envelope) points else intervals + 1
        fun sample(f: (Double) -> Double): SignalTrace {
            val low = DoubleArray(bins) { Double.POSITIVE_INFINITY }
            val high = DoubleArray(bins) { Double.NEGATIVE_INFINITY }
            for (i in 0..intervals) {
                if (i % 1024 == 0) checkActive()
                val t = if (i == intervals) to else from + span * i / intervals
                val v = f(t)
                require(v.isFinite()) { "Non-finite signal value at $t seconds" }
                val bin = if (envelope) min(bins - 1, (i.toLong() * bins / intervals).toInt()) else i
                low[bin] = min(low[bin], v); high[bin] = max(high[bin], v)
            }
            return SignalTrace(low.toList(), high.toList())
        }
        val lanes = channels.map { ch -> SignalLaneWindow(ch.name, ch.minimum, ch.maximum,
            sample(ch.value), ch.comparison?.let(::sample)) }
        return SignalWindow(from, to, if (envelope) "envelope" else "waveform", span / intervals, lanes)
    }

    fun renderHtml(options: SignalViewerOptions = SignalViewerOptions()): String {
        require(options.width >= 240 && options.laneHeight in 60..500)
        require(options.initialStart == null || options.initialStart.isFinite())
        require(options.initialDuration == null || (options.initialDuration.isFinite() && options.initialDuration > 0))
        val config = mapper.writeValueAsString(mapOf(
            "url" to "/signal-viewer/$id", "start" to start, "end" to end,
            "initialStart" to options.initialStart, "initialDuration" to options.initialDuration,
            "syncGroup" to options.syncGroup, "viewKey" to options.viewKey,
            "label" to options.label, "comparisonLabel" to options.comparisonLabel,
            "laneHeight" to options.laneHeight, "lanes" to channels.map { it.name },
        )).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
        return """
            <div class="field-signal-viewer" data-afield-intrinsic-width="${options.width}" style="width:${options.width}px;max-width:100%;box-sizing:border-box" aria-busy="true">
            <style>
            .field-signal-viewer{background:#080808;color:#ddd;font:12px/1.5 Iosevka,monospace;padding:12px;border:1px solid #333}
            .field-signal-viewer .fsv-title{margin-bottom:8px;overflow-wrap:anywhere}
            .field-signal-viewer .fsv-stage{position:relative;touch-action:none;user-select:none}
            .field-signal-viewer canvas{width:100%;display:block;cursor:crosshair;outline-offset:2px}
            .field-signal-viewer .fsv-toolbar{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-top:8px}
            .field-signal-viewer button{background:#111;color:#ddd;border:1px solid #555;font:inherit;padding:2px 10px;cursor:pointer}
            .field-signal-viewer button:hover{border-color:#ddd}
            .field-signal-viewer :focus-visible{outline:1px solid #fff}
            .field-signal-viewer .fsv-range{flex:1}
            .field-signal-viewer .fsv-status,.field-signal-viewer .fsv-help{color:#999;font-size:11px}
            .field-signal-viewer .fsv-readout{min-height:18px;white-space:pre-wrap}
            </style>
            <div class="fsv-title"></div>
            <div class="fsv-stage"><canvas tabindex="0" role="img" aria-label="Signal waveforms. Wheel to zoom, drag to pan. Plus and minus zoom, arrows pan, Home shows all."></canvas></div>
            <div class="fsv-toolbar"><button data-action="in" aria-label="Zoom in">+</button><button data-action="out" aria-label="Zoom out">−</button><button data-action="all">all</button><span class="fsv-range"></span><span class="fsv-status" role="status" aria-live="polite"></span></div>
            <div class="fsv-readout"></div><div class="fsv-help">wheel / pinch to zoom · drag / shift-wheel to pan · double-click to show all</div>
            <script type="application/json" class="fsv-config">$config</script>
            <script>$runtime</script></div>
        """.trimIndent()
    }

    companion object {
        private val sources = mutableMapOf<String, WeakReference<SignalViewer>>()
        private val mapper = jacksonObjectMapper()
        private val runtime: String get() {
            return checkNotNull(SignalViewer::class.java.getResourceAsStream("/signal-viewer.js"))
                .bufferedReader().use { it.readText() }
        }
        fun find(id: String): SignalViewer? = synchronized(sources) { sources[id]?.get() }
    }
}

/** Replace a box output, releasing its previous signal's evaluator references. */
fun Box.displaySignal(viewer: SignalViewer, channel: String = "signal", options: SignalViewerOptions = SignalViewerOptions()): String {
    val key = "_signalViewer:$channel"
    val html = viewer.renderHtml(options)
    val previous = getPropertyRaw(key, null) as? SignalViewer
    setPropertyRaw(key, viewer)
    display(channel, html)
    if (previous !== viewer) previous?.close()
    return html
}
