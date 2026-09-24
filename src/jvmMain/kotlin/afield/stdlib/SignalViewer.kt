@file:afield.CompletionCategory("Visualization")

package afield.stdlib

import afield.Box
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
    /**
     * Opt-in: the same function as an [Functions.Fn], whose per-bin **certified** bound
     * ([certifiedEnvelope]) is drawn as a band behind the sampled envelope — the bound no feature
     * between samples can escape. Must compute what [value] computes; [certifiedSignalChannel]
     * builds both from one `Fn`. A graph with formula-less ops is refused, and the lane says so.
     */
    val certified: Functions.Fn? = null,
)

/** A lane whose sampled envelope and certified band both come from [fn]. */
fun certifiedSignalChannel(name: String, fn: Functions.Fn, minimum: Double = 0.0, maximum: Double = 1.0): SignalChannel =
    SignalChannel(name, fn::eval, minimum, maximum, certified = fn)

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
    val current: SignalTrace, val comparison: SignalTrace?,
    /** Per-bin certified bounds over the same bins as [current] (one per sample interval in waveform mode). */
    val certified: SignalTrace? = null, val certifiedRefusal: String? = null)
data class SignalWindow(val start: Double, val end: Double, val mode: String,
    val sampleSpacing: Double, val lanes: List<SignalLaneWindow>) {
    /** The wire form: JSON with every non-finite value (an empty or gap bin) written as `null`. */
    fun toJson(): String {
        fun trace(t: SignalTrace?) = t?.let { mapOf("low" to it.low.map { v -> v.takeIf { v.isFinite() } }, "high" to it.high.map { v -> v.takeIf { v.isFinite() } }) }
        return TimeViewerShell.scriptJson(mapOf("start" to start, "end" to end, "mode" to mode, "sampleSpacing" to sampleSpacing,
            "lanes" to lanes.map { mapOf("name" to it.name, "minimum" to it.minimum, "maximum" to it.maximum, "current" to trace(it.current),
                "comparison" to trace(it.comparison), "certified" to trace(it.certified), "certifiedRefusal" to it.certifiedRefusal) }))
    }
}

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
    /**
     * Treat non-finite values as gaps instead of refusing the query. A bin with no finite value is
     * sent as `null`, which the viewer draws as a break; `Fn.plot` uses this for functions with holes.
     */
    val gaps: Boolean = false,
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
                if (!v.isFinite()) { require(gaps) { "Non-finite signal value at $t seconds" }; continue }
                val bin = if (envelope) min(bins - 1, (i.toLong() * bins / intervals).toInt()) else i
                low[bin] = min(low[bin], v); high[bin] = max(high[bin], v)
            }
            return SignalTrace(low.toList(), high.toList())
        }
        fun certify(fn: Functions.Fn, ch: SignalChannel): Pair<SignalTrace?, String?> {
            checkActive()
            val e = fn.certifiedEnvelope(from, to, if (envelope) bins else intervals,
                tolerance = (ch.maximum - ch.minimum) * 1e-3, maxDepth = 6)
            return if (e.certified) SignalTrace(e.low.toList(), e.high.toList()) to null else null to e.refusal
        }
        val lanes = channels.map { ch ->
            val (certified, refusal) = ch.certified?.let { certify(it, ch) } ?: (null to null)
            SignalLaneWindow(ch.name, ch.minimum, ch.maximum, sample(ch.value), ch.comparison?.let(::sample), certified, refusal)
        }
        return SignalWindow(from, to, if (envelope) "envelope" else "waveform", span / intervals, lanes)
    }

    fun renderHtml(options: SignalViewerOptions = SignalViewerOptions()): String {
        require(options.width >= 240 && options.laneHeight in 60..500)
        require(options.initialStart == null || options.initialStart.isFinite())
        require(options.initialDuration == null || (options.initialDuration.isFinite() && options.initialDuration > 0))
        return TimeViewerShell.render(
            config = mapOf(
                "source" to "range", "url" to "/signal-viewer/$id", "start" to start, "end" to end,
                "initialStart" to options.initialStart, "initialDuration" to options.initialDuration,
                "syncGroup" to options.syncGroup, "viewKey" to options.viewKey,
                "label" to options.label, "comparisonLabel" to options.comparisonLabel,
                "laneHeight" to options.laneHeight, "xLabel" to "seconds",
                "lanes" to channels.map { mapOf("name" to it.name, "minimum" to it.minimum, "maximum" to it.maximum) },
            ),
            widthPx = options.width,
        )
    }

    companion object {
        private val sources = mutableMapOf<String, WeakReference<SignalViewer>>()
        fun find(id: String): SignalViewer? = synchronized(sources) { sources[id]?.get() }
    }
}

/** Replace a box output, releasing its previous signal's evaluator references. */
fun Box.displaySignal(viewer: SignalViewer, channel: String = "signal", options: SignalViewerOptions = SignalViewerOptions()): String {
    val html = viewer.renderHtml(options)
    retainSignal(channel, viewer)
    display(channel, html)
    return html
}

/** Keep [viewer] alive for this box's output [channel], closing the source it replaces. */
internal fun Box.retainSignal(channel: String, viewer: SignalViewer) {
    val key = "_signalViewer:$channel"
    val previous = getPropertyRaw(key, null) as? SignalViewer
    setPropertyRaw(key, viewer)
    if (previous !== viewer) previous?.close()
}
