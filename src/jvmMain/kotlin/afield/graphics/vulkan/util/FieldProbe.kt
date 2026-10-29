@file:OptIn(ExperimentalUnsignedTypes::class)

/**
 * Samples values out of a running shader into a GPU buffer and shows them in a box's output as a histogram and summary statistics.
 *
 * A shader includes `fieldProbe` and writes scalar or vector samples into a buffer bound at
 * [FIELD_PROBE_DEFAULT_BINDING]; [ShaderFieldProbe] reads that buffer back off the render thread,
 * builds a [FieldProbeSnapshot] (counts of NaN, infinite and non-zero samples, ranges, summaries)
 * and, with [ShaderFieldProbe.display], publishes it to an output channel at most
 * [ShaderFieldProbe.maxDisplayHz] times a second. [ShaderFieldProbe.ordinalRange] limits which
 * invocations record, and [ShaderFieldProbe.onSnapshot] hands each snapshot to box code.
 */

package afield.graphics.vulkan.util

import afield.document.graph.Box
import afield.runtime.lifecycle.AnimationClock
import afield.ui.output.displayClear
import afield.ui.output.displayClose
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.buffers.BufferParameters
import afield.graphics.vulkan.buffers.VulkanBuffer
import afield.graphics.vulkan.buffers.buffer
import afield.graphics.vulkan.device.Fence
import afield.app.diagnostics.logging.lprintln
import afield.math.control.ScalarSampleBatch
import afield.math.statistics.Histogram
import afield.math.statistics.SummaryStats
import afield.math.statistics.SummaryStatsBox
import afield.math.linalg.Vec2
import afield.math.linalg.Vec3
import afield.math.linalg.Vec4
import afield.math.statistics.Vec2SummaryStats
import afield.math.statistics.Vec3SummaryStats
import afield.math.statistics.Vec4SummaryStats
import afield.math.statistics.buildHistogram
import afield.math.statistics.buildSummaryStats
import afield.math.statistics.buildVec2SummaryStats
import afield.math.statistics.buildVec3SummaryStats
import afield.math.statistics.buildVec4SummaryStats
import afield.math.statistics.histogramToSvg
import afield.math.statistics.summaryStatsBoxToHtml
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import afield.ui.visualization.Table
import afield.ui.output.FieldMarkdown

/** The name of the probe's buffer, which matches the shader's declaration to the buffer the host
 * allocates. */
const val FIELD_PROBE_BUFFER_NAME = "FieldProbeBuffer"

/**
 * The name a shader includes to get the probe GLSL: `#include "fieldProbe"`.
 *
 * What it brings in is an overloaded `fieldProbe(…)` taking a float, an int, a uint or any 2-, 3- or
 * 4-component vector of those, so instrumenting a line is a matter of wrapping the value in a call.
 */
const val FIELD_PROBE_INCLUDE_NAME = "fieldProbe"

/** How many samples a probe keeps unless told otherwise, 1024, enough for a histogram to have a
 * shape, small enough to read back every frame. */
const val FIELD_PROBE_DEFAULT_SAMPLES = 1024

/**
 * The ceiling on [ShaderFieldProbe.sampleCapacity], 262144. A larger capacity is clamped to it rather
 * than rejected.
 *
 * The buffer is always allocated at this size — about 4 MB per frame in flight — and the shader
 * always declares its sample array at it, so a smaller [ShaderFieldProbe.sampleCapacity] buys a
 * shorter read-back rather than a smaller allocation.
 */
const val FIELD_PROBE_MAX_SAMPLES = 1024 * 256

/** Default binding for the probe buffer, 63, just above the statistics buffers at 61 and 62. */
const val FIELD_PROBE_DEFAULT_BINDING = 63

/** Default descriptor set for the probe buffer, 0. */
const val FIELD_PROBE_DEFAULT_SET = 0

private const val FIELD_PROBE_HEADER_INTS = 20
private const val FIELD_PROBE_HEADER_BYTES = FIELD_PROBE_HEADER_INTS * 4

private const val HEADER_ENABLED = 0
private const val HEADER_CAPACITY = 1
private const val HEADER_ORDINAL_START = 2
private const val HEADER_ORDINAL_END = 3
private const val HEADER_INVOCATION_COUNT = 4
private const val HEADER_ACCEPTED_COUNT = 5
private const val HEADER_FINITE_COUNT = 6
private const val HEADER_TYPE_TAG = 7
private const val HEADER_NAN_COUNT = 8
private const val HEADER_INF_COUNT = 9
private const val HEADER_NON_ZERO_COUNT = 10
private const val HEADER_TYPE_MISMATCH_COUNT = 11
private const val HEADER_MIN_X = 12
private const val HEADER_MIN_Y = 13
private const val HEADER_MIN_Z = 14
private const val HEADER_MIN_W = 15
private const val HEADER_MAX_X = 16
private const val HEADER_MAX_Y = 17
private const val HEADER_MAX_Z = 18
private const val HEADER_MAX_W = 19

private const val PROBE_TYPE_UNKNOWN = 0
private const val PROBE_TYPE_SCALAR = 1
private const val PROBE_TYPE_VEC2 = 2
private const val PROBE_TYPE_VEC3 = 3
private const val PROBE_TYPE_VEC4 = 4

private val fieldProbeDisplayExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "field-probe-display").apply { isDaemon = true }
}

private val fieldProbeReadbackExecutor = Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "field-probe-readback").apply { isDaemon = true }
}

private fun orderedUintForFloat(value: Float): UInt {
    val bits = value.toRawBits().toUInt()
    return if ((bits and 0x80000000u) != 0u) bits.inv() else bits or 0x80000000u
}

private fun floatForOrderedUint(ordered: UInt): Float {
    val bits = if ((ordered and 0x80000000u) != 0u) ordered and 0x7fffffffu else ordered.inv()
    return Float.fromBits(bits.toInt())
}

private fun formatNumber(value: Double, decimals: Int): String {
    if (!value.isFinite()) return "NaN"
    return String.format(Locale.US, "%.${decimals}f", value)
}

private fun ByteBuffer.nativeOrder(): ByteBuffer = order(ByteOrder.nativeOrder())

/**
 * One frame's worth of probe data: how many times the shader called `fieldProbe`, what it was
 * holding, and a bounded sample of the values themselves.
 *
 * The counts and the exact ranges cover every call the probe accepted; [samples] is a reservoir of at
 * most [ShaderFieldProbe.sampleCapacity] of them, which is what the histogram and the summary
 * statistics are computed from. So a mean read off [scalarSummary] is the sample mean, while
 * [exactMin] and [exactMax] are the true extremes over everything — a distinction worth keeping
 * straight when a probe reports a range no sample in it reaches.
 *
 * The derived values are computed on first use and cached, so asking for a summary or a histogram
 * twice costs once.
 */
data class FieldProbeSnapshot(
    /** How many components each recorded value had: 1 for a scalar, 2, 3 or 4 for a vector, and 0 if
     * nothing was recorded. The first call of a frame fixes this, and later calls of a different
     * width are counted as [typeMismatchCount] instead. */
    val dimensions: Int,
    /** How many times the shader called `fieldProbe` while the probe was enabled, before any
     * filtering: the denominator for how much of the work was looked at. Each call's position in
     * this count is its ordinal. */
    val invocationCount: Long,
    /** How many of those calls got past the ordinal range and the width check, and so contributed to
     * everything below. */
    val acceptedCount: Long,
    /** How many accepted values were finite in every component. Also the index space the reservoir
     * fills, so `min(finiteCount, capacity)` is [sampleCount]. */
    val finiteCount: Long,
    /** How many values are actually in [samples]: [finiteCount] capped at the configured capacity. */
    val sampleCount: Int,
    /** How many accepted values had a NaN component. These are counted and dropped, so they do not
     * reach the ranges or the samples; [anyNaN] is the quick test. */
    val nanCount: Long,
    /** How many accepted values had an infinite component — counted and dropped like the NaNs, and
     * checked after them, so a value that is both is counted only as NaN. */
    val infCount: Long,
    /** How many finite values had at least one non-zero component, counting only the components
     * [dimensions] says are real. A probe whose output should not be zero and whose count here is
     * zero has found something. */
    val nonZeroCount: Long,
    /** How many in-range calls were rejected for recording a different number of components than the
     * first call of the frame did. Normally zero; non-zero means two probes of different widths are
     * sharing one buffer, and only the first width is being measured. */
    val typeMismatchCount: Long,
    /** The true minimum of each component over every accepted finite value, one entry per recorded
     * component. Computed with integer atomics across the whole frame, so these are not limited to
     * the sampled values. When nothing finite was recorded every entry is NaN, and there is one of
     * them. */
    val exactMin: DoubleArray,
    /** The true maximum of each component, on the same terms as [exactMin]. */
    val exactMax: DoubleArray,
    /** The retained values, always as `Vec4` whatever [dimensions] is, with the unused components
     * zero. Up to the capacity they are every finite value in order; past it the shader replaces a
     * hashed slot, which keeps the retained set spread across the frame rather than stopping at the
     * first thousand. Read them through [scalarValues] and the vector forms, which narrow them to the
     * recorded width. */
    val samples: List<Vec4>
) {
    private val smallChangePivot = 0.08
    private val cachedScalarValues: List<Double>? by lazy {
        if (dimensions == PROBE_TYPE_SCALAR) samples.map { it.x.toDouble() } else null
    }
    private val cachedVec2Values: List<Vec2>? by lazy {
        if (dimensions == PROBE_TYPE_VEC2) samples.map { Vec2(it.x, it.y) } else null
    }
    private val cachedVec3Values: List<Vec3>? by lazy {
        if (dimensions == PROBE_TYPE_VEC3) samples.map { Vec3(it.x, it.y, it.z) } else null
    }
    private val cachedVec4Values: List<Vec4>? by lazy {
        if (dimensions == PROBE_TYPE_VEC4) samples.map { Vec4(it.x, it.y, it.z, it.w) } else null
    }
    private val cachedSortedScalarValues: List<Double>? by lazy { cachedScalarValues?.sorted() }
    private val cachedScalarSummary: SummaryStats? by lazy { cachedScalarValues?.let(::buildSummaryStats) }
    private val cachedScalarHistogram: Histogram? by lazy { cachedScalarValues?.let(::buildHistogram) }
    private val cachedVec2Summary: Vec2SummaryStats? by lazy { cachedVec2Values?.let(::buildVec2SummaryStats) }
    private val cachedVec3Summary: Vec3SummaryStats? by lazy { cachedVec3Values?.let(::buildVec3SummaryStats) }
    private val cachedVec4Summary: Vec4SummaryStats? by lazy { cachedVec4Values?.let(::buildVec4SummaryStats) }

    private fun mixChannel(a: Int, b: Int, t: Double): Int =
        (a + ((b - a) * t.coerceIn(0.0, 1.0))).toInt().coerceIn(0, 255)

    private fun colorRamp(from: String, to: String, t: Double): String {
        fun parse(color: String): Triple<Int, Int, Int>? {
            if (!color.startsWith("#") || color.length != 7) return null
            return try {
                Triple(
                    color.substring(1, 3).toInt(16),
                    color.substring(3, 5).toInt(16),
                    color.substring(5, 7).toInt(16)
                )
            } catch (_: NumberFormatException) {
                null
            }
        }
        val a = parse(from) ?: return from
        val b = parse(to) ?: return to
        val u = t.coerceIn(0.0, 1.0)
        return "#%02x%02x%02x".format(
            Locale.US,
            mixChannel(a.first, b.first, u),
            mixChannel(a.second, b.second, u),
            mixChannel(a.third, b.third, u)
        )
    }

    private fun relativeChangeMagnitude(current: Double, previous: Double?): Double {
        if (previous == null || !current.isFinite() || !previous.isFinite()) return 0.0
        val denom = abs(current) + abs(previous)
        if (denom <= 0.0) return 0.0
        return (abs(current - previous) / denom).coerceIn(0.0, 1.0)
    }

    private fun signedDeltaColor(current: Double, previous: Double?, fallback: String): String {
        if (previous == null || !current.isFinite() || !previous.isFinite()) return fallback
        if (current == previous) return fallback
        val amount = relativeChangeMagnitude(current, previous)
        if (amount <= 0.0) return fallback
        val orange = "#ff8a00"
        val target = if (current > previous) "#55ff88" else "#ff4d6d"
        return if (amount <= smallChangePivot) {
            colorRamp(fallback, orange, amount / smallChangePivot)
        } else {
            colorRamp(orange, target, (amount - smallChangePivot) / (1.0 - smallChangePivot))
        }
    }

    private fun deltaStyle(current: Double, previous: Double?, fallback: String = "#0af"): String {
        val color = signedDeltaColor(current, previous, fallback)
        if (color == fallback) return ""
        return "color:$color;"
    }

    private fun deltaStyle(current: Long, previous: Long?, fallback: String = "#0af"): String =
        deltaStyle(current.toDouble(), previous?.toDouble(), fallback)

    /** Whether any accepted value had a NaN component this frame. The first thing to check when a
     * render goes black. */
    val anyNaN: Boolean
        get() = nanCount > 0

    /** Whether any accepted value had an infinite component this frame. */
    val anyInf: Boolean
        get() = infCount > 0

    /** Whether any finite value was not all zeros: the test for "did this code path run and produce
     * anything". */
    val anyNonZero: Boolean
        get() = nonZeroCount > 0

    /** The retained values as plain numbers, or `null` if this frame recorded vectors rather than
     * scalars. Checking for `null` is how you find out what the shader was probing. */
    fun scalarValues(): List<Double>? = cachedScalarValues

    /** The retained values as two-component vectors, or `null` if [dimensions] is not 2. */
    fun vec2Values(): List<Vec2>? = cachedVec2Values

    /** The retained values as three-component vectors, or `null` if [dimensions] is not 3. */
    fun vec3Values(): List<Vec3>? = cachedVec3Values

    /** The retained values as four-component vectors, or `null` if [dimensions] is not 4. Note that
     * [samples] is `Vec4` whatever the width, while this is `null` unless the width really was 4. */
    fun vec4Values(): List<Vec4>? = cachedVec4Values

    /** Count, mean, spread and quartiles over the retained scalar values, or `null` if this frame was
     * not scalar. Computed from the sample rather than from every call, so compare it against
     * [exactMin] and [exactMax] rather than treating it as the whole story. */
    fun scalarSummary(): SummaryStats? = cachedScalarSummary

    /** Per-component summary statistics over the retained two-component values, or `null` if
     * [dimensions] is not 2. */
    fun vec2Summary(): Vec2SummaryStats? = cachedVec2Summary

    /** Per-component summary statistics over the retained three-component values, or `null` if
     * [dimensions] is not 3. */
    fun vec3Summary(): Vec3SummaryStats? = cachedVec3Summary

    /** Per-component summary statistics over the retained four-component values, or `null` if
     * [dimensions] is not 4. */
    fun vec4Summary(): Vec4SummaryStats? = cachedVec4Summary

    /**
     * The value at fraction [alpha] through the sorted scalar samples, interpolating between the two
     * neighbouring samples, with `alpha` clamped to 0..1.
     *
     * Returns NaN for a frame that recorded vectors or recorded nothing. This reads the retained
     * sample, so `percentile(1.0)` is the largest *sampled* value and not [exactMax].
     */
    fun percentile(alpha: Double): Double {
        val values = cachedSortedScalarValues ?: return Double.NaN
        if (values.isEmpty()) return Double.NaN
        if (values.size == 1) return values.first()
        val t = alpha.coerceIn(0.0, 1.0)
        val scaled = t * (values.size - 1)
        val lo = scaled.toInt()
        val hi = min(lo + 1, values.lastIndex)
        val frac = scaled - lo
        return values[lo] * (1.0 - frac) + values[hi] * frac
    }

    /**
     * Renders this snapshot as the HTML card a probe shows in a box's output: the counts, then a
     * histogram and summary for a scalar frame, or a per-component table and a vector-length
     * histogram for a vector one.
     *
     * Pass the snapshot before this one as [previous] and each number is tinted by how far it moved —
     * unchanged in the base colour, through orange for a small change, to green for a rise and red
     * for a fall. A steady probe then reads as a still card, and a number that has begun to move
     * shows up without being read. The remaining parameters set the card's width, chart height,
     * decimal places and the three colours.
     */
    fun summaryHtml(
        previous: FieldProbeSnapshot? = null,
        widthPx: Int = 520,
        chartHeightPx: Int = 120,
        decimals: Int = 4,
        background: String = "#000000",
        textColor: String = "#0af",
        accentColor: String = "#0af"
    ): String {
        val totalWidthPx = widthPx + 24
        val metrics = buildString {
            append(metric("calls", invocationCount.toString(), deltaStyle(invocationCount, previous?.invocationCount)))
            append(metric("in-range", acceptedCount.toString(), deltaStyle(acceptedCount, previous?.acceptedCount)))
            append(metric("finite", finiteCount.toString(), deltaStyle(finiteCount, previous?.finiteCount)))
            append(metric("sampled", sampleCount.toString(), deltaStyle(sampleCount.toLong(), previous?.sampleCount?.toLong())))
            append(metric("nan", nanCount.toString(), deltaStyle(nanCount, previous?.nanCount)))
            append(metric("inf", infCount.toString(), deltaStyle(infCount, previous?.infCount)))
            append(metric("nonzero", nonZeroCount.toString(), deltaStyle(nonZeroCount, previous?.nonZeroCount)))
            if (typeMismatchCount > 0) {
                append(metric("type-mismatch", typeMismatchCount.toString(), deltaStyle(typeMismatchCount, previous?.typeMismatchCount)))
            }
            if (dimensions in PROBE_TYPE_SCALAR..PROBE_TYPE_VEC4) {
                append(metric("dims", dimensions.toString()))
            }
        }

        val detailHtml = when (dimensions) {
            PROBE_TYPE_SCALAR -> scalarValues()?.sorted()?.let { values ->
                val stats = requireNotNull(scalarSummary())
                val box = SummaryStatsBox(
                    values = values,
                    stats = stats,
                    histogram = requireNotNull(cachedScalarHistogram),
                    windowSize = sampleCount,
                    samplesSeen = invocationCount,
                    droppedNonFinite = nanCount + infCount,
                    droppedByCapacity = max(acceptedCount - sampleCount.toLong(), 0L)
                )
                val previousBox = previous?.scalarValues()?.sorted()?.let { previousValues ->
                    SummaryStatsBox(
                        values = previousValues,
                        stats = requireNotNull(previous.scalarSummary()),
                        histogram = requireNotNull(previous.cachedScalarHistogram),
                        windowSize = previous.sampleCount,
                        samplesSeen = previous.invocationCount,
                        droppedNonFinite = previous.nanCount + previous.infCount,
                        droppedByCapacity = max(previous.acceptedCount - previous.sampleCount.toLong(), 0L)
                    )
                }
                val extra = listOf(
                    metric(
                        "exact min",
                        formatNumber(exactMin.getOrElse(0) { Double.NaN }, decimals),
                        deltaStyle(exactMin.getOrElse(0) { Double.NaN }, previous?.exactMin?.getOrNull(0))
                    ),
                    metric("sample mean", formatNumber(stats.mean, decimals), deltaStyle(stats.mean, previousBox?.stats?.mean)),
                    metric("p95", formatNumber(percentile(0.95), decimals), deltaStyle(percentile(0.95), previous?.percentile(0.95))),
                    metric(
                        "exact max",
                        formatNumber(exactMax.getOrElse(0) { Double.NaN }, decimals),
                        deltaStyle(exactMax.getOrElse(0) { Double.NaN }, previous?.exactMax?.getOrNull(0))
                    )
                ).joinToString("")

                """
                <div style="display:flex;flex-wrap:wrap;gap:6px 12px;margin:0 0 8px 0;">$extra</div>
                ${summaryStatsBoxToHtml(
                    summary = box,
                    previousSummary = previousBox,
                    widthPx = widthPx,
                    chartHeightPx = chartHeightPx,
                    decimals = decimals,
                    fontFamily = "Iosevka, monospace",
                    background = background,
                    textColor = textColor,
                    axisColor = accentColor,
                    showFrame = false,
                    showHeader = false,
                    histogramTitle = null,
                    seriesTitle = null,
                    chartPadLeft = 56,
                    chartPadRight = 78,
                    chartPadTop = 20,
                    chartPadBottom = 28
                )}
                """.trimIndent()
            } ?: noDataHtml(widthPx, background, textColor)

            PROBE_TYPE_VEC2 -> vectorSummaryHtml(
                widthPx = widthPx,
                dimensions = 2,
                exactMin = exactMin,
                exactMax = exactMax,
                componentStats = vec2Summary()?.let {
                    listOf("x" to it.x, "y" to it.y)
                } ?: emptyList(),
                lengths = vec2Values()?.map { it.length().toDouble() } ?: emptyList(),
                decimals = decimals,
                background = background,
                textColor = textColor,
                accentColor = accentColor
            )

            PROBE_TYPE_VEC3 -> vectorSummaryHtml(
                widthPx = widthPx,
                dimensions = 3,
                exactMin = exactMin,
                exactMax = exactMax,
                componentStats = vec3Summary()?.let {
                    listOf("x" to it.x, "y" to it.y, "z" to it.z)
                } ?: emptyList(),
                lengths = vec3Values()?.map { it.length().toDouble() } ?: emptyList(),
                decimals = decimals,
                background = background,
                textColor = textColor,
                accentColor = accentColor
            )

            PROBE_TYPE_VEC4 -> vectorSummaryHtml(
                widthPx = widthPx,
                dimensions = 4,
                exactMin = exactMin,
                exactMax = exactMax,
                componentStats = vec4Summary()?.let {
                    listOf("x" to it.x, "y" to it.y, "z" to it.z, "w" to it.w)
                } ?: emptyList(),
                lengths = vec4Values()?.map { it.length().toDouble() } ?: emptyList(),
                decimals = decimals,
                background = background,
                textColor = textColor,
                accentColor = accentColor
            )

            else -> noDataHtml(widthPx, background, textColor)
        }

        return """
            <div style="
                width:${totalWidthPx}px;
                box-sizing:border-box;
                background:${background};
                color:${textColor};
                padding:12px;
                font-family:Iosevka, monospace;
                font-size:11px;
                line-height:1.3;
            ">
                <div style="display:flex;flex-wrap:wrap;gap:6px 12px;margin:0 0 10px 0;">$metrics</div>
                $detailHtml
            </div>
        """.trimIndent()
    }

    private fun metric(key: String, value: String, valueStyle: String = ""): String = """
        <div style="display:flex;gap:4px;white-space:nowrap;">
            <span style="opacity:0.6;">$key</span>
            <span style="$valueStyle">$value</span>
        </div>
    """.trimIndent()

    private fun noDataHtml(widthPx: Int, background: String, textColor: String): String = """
        <div style="
            width:${widthPx}px;
            background:${background};
            color:${textColor};
            padding:12px 0;
            box-sizing:border-box;
            font-family:Iosevka, monospace;
        ">No finite probe samples yet.</div>
    """.trimIndent()

    private fun vectorSummaryHtml(
        widthPx: Int,
        dimensions: Int,
        exactMin: DoubleArray,
        exactMax: DoubleArray,
        componentStats: List<Pair<String, SummaryStats>>,
        lengths: List<Double>,
        decimals: Int,
        background: String,
        textColor: String,
        accentColor: String
    ): String {
        if (componentStats.isEmpty()) return noDataHtml(widthPx, background, textColor)

        val num = { v: Any? -> formatNumber((v as Number).toDouble(), decimals) }
        val components = Table(listOf("component", "exact min", "sample mean", "sample median", "exact max"))
            .rows(componentStats.mapIndexed { index, (name, stats) ->
                listOf(
                    name,
                    exactMin.getOrElse(index) { Double.NaN },
                    stats.mean,
                    stats.median,
                    exactMax.getOrElse(index) { Double.NaN },
                )
            })
            .format("exact min", num)
            .format("sample mean", num)
            .format("sample median", num)
            .format("exact max", num)

        val lengthHistogram = histogramToSvg(
            hist = buildHistogram(lengths),
            widthPx = widthPx,
            heightPx = 160,
            title = null,
            padLeft = 40,
            padRight = 40,
            padTop = 14,
            padBottom = 28,
            fontFamily = "Iosevka, monospace",
            decimals = decimals,
            background = background,
            textColor = textColor,
            tickColor = textColor,
            axisColor = accentColor,
            barFill = textColor,
            barStroke = textColor
        )

        return FieldMarkdown.card(
            """
            {muted:vec${dimensions}}

            ${components.toHTML()}

            $lengthHistogram
            """
        )
    }
}

/**
 * A probe's scalar samples packaged for a controller, with the snapshot they came from kept
 * alongside.
 *
 * The controller wants an array and a clip rate; the snapshot is retained so the caller can still
 * reach the counts and exact ranges that the batch leaves out.
 */
data class FieldProbeScalarBatch(
    /** The snapshot these samples were taken from, with the counts and exact ranges the batch does
     * not carry. */
    val snapshot: FieldProbeSnapshot,
    /** The samples and clip rate, in the form a controller consumes. */
    val batch: ScalarSampleBatch
)

/**
 * Packages this snapshot's scalar samples for a controller, returning `null` when there is not enough
 * to act on.
 *
 * `null` comes back for a frame that recorded vectors rather than scalars, and for one with fewer than
 * [minSamples] retained — which is how a controller is kept from reacting to the first partial frame
 * after a shader reload. Give [clipLower] and [clipUpper] the bounds the value is expected to stay
 * inside and the fraction of samples sitting at either of them is measured as the batch's clip rate,
 * a controller's signal that it is asking for more than the shader can deliver. [clipEpsilon] is the
 * tolerance for counting a sample as resting on a bound, since a clamped float rarely compares equal
 * to the bound exactly. With neither bound given the clip rate is zero.
 */
fun FieldProbeSnapshot.scalarBatch(
    minSamples: Int = 1,
    clipLower: Double? = null,
    clipUpper: Double? = null,
    clipEpsilon: Double = 1.0e-6
): FieldProbeScalarBatch? {
    val scalarValues = scalarValues() ?: return null
    if (scalarValues.size < minSamples) return null

    val clipRate = if (clipLower == null && clipUpper == null) {
        0.0
    } else {
        val clips = scalarValues.count { value ->
            val clippedLow = clipLower?.let { value <= it + clipEpsilon } ?: false
            val clippedHigh = clipUpper?.let { value >= it - clipEpsilon } ?: false
            clippedLow || clippedHigh
        }
        clips.toDouble() / scalarValues.size.toDouble()
    }

    return FieldProbeScalarBatch(
        snapshot = this,
        batch = ScalarSampleBatch(
            samples = scalarValues.toDoubleArray(),
            clipRate = clipRate
        )
    )
}

/**
 * A box's shader probe: the buffer a running shader writes samples into, the read-back that brings
 * them off the render thread, and the output card they are shown on.
 *
 * Reached through `Box.fieldProbeHere` rather than constructed, since the probe has to be registered
 * before `#include "fieldProbe"` can resolve. The configuration methods return the probe, so the
 * usual shape is one chained expression — pick the invocations to watch with [ordinalRange], send the
 * card somewhere with [display], and take each frame's numbers with [onSnapshot].
 *
 * Reading never blocks on the GPU, and what that costs is currency: a graphics shader's samples are
 * read on a later frame's pass over the same swapchain image, and a compute shader's on a background
 * thread once the frame's fence has signalled. Either way [latestSnapshot] trails the picture on
 * screen.
 */
class ShaderFieldProbe(
    /** The box that owns this probe: whose output channel the card goes to, and whose name a failed
     * read-back is reported against. */
    val box: Box
) {
    private data class DisplayWork(
        val channel: String,
        val snapshot: FieldProbeSnapshot,
        val generation: Long
    )

    /**
     * How many samples are kept and read back each frame, clamped to 1..[FIELD_PROBE_MAX_SAMPLES].
     *
     * This bounds the read-back and the histogram, not the allocation, which is always at the
     * maximum. Raising it costs a longer copy every frame; the counts and exact ranges do not depend
     * on it.
     */
    var sampleCapacity: Int = FIELD_PROBE_DEFAULT_SAMPLES
        set(value) {
            field = value.coerceIn(1, FIELD_PROBE_MAX_SAMPLES)
        }

    /** The buffer binding the probe's GLSL declares. Change it if the shader's own bindings reach
     * 63. */
    var binding: Int = FIELD_PROBE_DEFAULT_BINDING

    /** The descriptor set the probe's buffer is bound in. */
    var descriptorSet: Int = FIELD_PROBE_DEFAULT_SET

    /**
     * Whether the shader records at all. True by default.
     *
     * The shader checks this in the buffer header, so turning it off costs a branch per call rather
     * than a recompile, and leaves the probe's instrumentation in place to be turned back on.
     */
    var enabled: Boolean = true

    /**
     * How often the output card may be redrawn, in times a second. Low by default, since building and
     * sending the card is the expensive part of a probe.
     *
     * It also sets the read-back rate for a probe that only displays, which is what [maxReadHz] is for
     * overriding; a probe with a callback reads every frame regardless.
     */
    var maxDisplayHz: Double = 5.0

    /**
     * How often the GPU-to-CPU read may run, for every kind of probe: callback, [readLatest] or
     * display. Unset, a display-only probe reads at [maxDisplayHz] and one with a callback or no
     * display channel reads every frame. `0.0` reads every frame.
     */
    var maxReadHz: Double? = null

    /** The first invocation ordinal that records, set through [ordinalRange] or [configure]. */
    var ordinalStart: UInt = 0u
        private set

    /** One past the last invocation ordinal that records. Read-only; set it through [ordinalRange] or
     * [configure]. */
    var ordinalEndExclusive: UInt = UInt.MAX_VALUE
        private set

    /**
     * The most recent completed read-back, or `null` before the first one lands.
     *
     * Written from whichever thread completed the read and declared volatile, so box code may read it
     * from anywhere. It is replaced wholesale rather than mutated, so a snapshot taken out of it stays
     * coherent for as long as it is held.
     */
    @Volatile
    var latestSnapshot: FieldProbeSnapshot? = null
        private set

    private var bufferParameters: BufferParameters? = null
    private var rendererRef: Renderer? = null
    private var pendingPerImage: BooleanArray = BooleanArray(0)
    private var computeReadbacks: Array<Future<*>?> = emptyArray()
    private var displayChannel: String? = null
    private var snapshotCallback: ((FieldProbeSnapshot) -> Unit)? = null
    private var continuousSnapshotReads: Boolean = false
    private var previousDisplayedSnapshot: FieldProbeSnapshot? = null
    private var lastDisplayDispatchNanos: Long = 0L
    private var lastSnapshotReadNanos: Long = 0L
    private var hasDisplayDispatch: Boolean = false
    private var hasSnapshotRead: Boolean = false
    private val pendingDisplayWork = AtomicReference<DisplayWork?>(null)
    private val displayWorkerScheduled = AtomicBoolean(false)
    @Volatile
    private var displayGeneration: Long = 0L

    /**
     * Sets several of the probe's settings at once and returns the probe, for configuring it in one
     * named-argument call.
     *
     * Every parameter defaults to the current value, so naming one changes one. This is the only way
     * to set [ordinalStart] and [ordinalEndExclusive] other than [ordinalRange].
     */
    fun configure(
        samples: Int = sampleCapacity,
        binding: Int = this.binding,
        descriptorSet: Int = this.descriptorSet,
        enabled: Boolean = this.enabled,
        ordinalStart: UInt = this.ordinalStart,
        ordinalEndExclusive: UInt = this.ordinalEndExclusive
    ): ShaderFieldProbe {
        sampleCapacity = samples
        this.binding = binding
        this.descriptorSet = descriptorSet
        this.enabled = enabled
        this.ordinalStart = ordinalStart
        this.ordinalEndExclusive = ordinalEndExclusive
        return this
    }

    /**
     * Records only the calls whose ordinal falls in `[start, endExclusive)`, and returns the probe for
     * chaining.
     *
     * A call's ordinal is its position in the frame's sequence of `fieldProbe` calls, handed out by an
     * atomic counter — so it is not the invocation id and the mapping from ordinal to pixel or
     * work-item is not stable between frames. What a narrow range does buy is volume: a probe inside a
     * fragment shader over a full window is called millions of times a frame, and `ordinalRange(0u,
     * 4096u)` cuts that to the first few thousand. Filtered calls still count toward
     * [FieldProbeSnapshot.invocationCount] and nothing else.
     *
     * To look at a specific invocation, branch on `gl_GlobalInvocationID` in the shader instead.
     */
    fun ordinalRange(start: UInt = 0u, endExclusive: UInt = UInt.MAX_VALUE): ShaderFieldProbe {
        ordinalStart = start
        ordinalEndExclusive = endExclusive
        return this
    }

    /**
     * The same as the two-argument [ordinalRange], written as a Kotlin range, so `0u..4095u` says what
     * `0u, 4096u` does.
     *
     * The range is inclusive of its end, and is converted. An empty range turns the probe into one
     * that records nothing.
     */
    fun ordinalRange(range: UIntRange): ShaderFieldProbe {
        if (range.isEmpty()) {
            ordinalStart = 1u
            ordinalEndExclusive = 1u
            return this
        }
        ordinalStart = range.first
        ordinalEndExclusive = if (range.last == UInt.MAX_VALUE) UInt.MAX_VALUE else range.last + 1u
        return this
    }

    /**
     * Hands every completed read-back to [callback], and returns the probe for chaining.
     *
     * Registering one makes the probe read every frame instead of at the display rate, which is what
     * a controller driven from probe values needs. The callback runs on whichever thread completed the
     * read — the render thread for a graphics shader, a read-back thread for a compute one — and never
     * on box code's own, so anything it touches has to tolerate that. An exception from it is logged
     * and swallowed rather than taking the frame down. One callback at a time: a second call replaces
     * the first.
     */
    fun onSnapshot(callback: (FieldProbeSnapshot) -> Unit): ShaderFieldProbe {
        snapshotCallback = callback
        return this
    }

    /** Set the box output channel that receives probe summaries, and return this probe for chaining. */
    fun display(channel: String = "probe"): ShaderFieldProbe {
        displayChannel = channel
        return this
    }

    /**
     * Turns the probe off and takes down what it registered: the display channel, the callback, the
     * last snapshot and any read-back still queued.
     *
     * This is what a box calls when it stops, so a probe does not keep drawing to a channel after the
     * code that made it is gone. [closeOutput] closes the output panel, and [clearOutput] empties it
     * first, which is the difference between the card disappearing and the card going blank. The
     * buffer is not released; re-enabling the probe starts it recording again.
     */
    fun deregister(closeOutput: Boolean = true, clearOutput: Boolean = false): ShaderFieldProbe {
        enabled = false
        pendingPerImage.fill(false)
        latestSnapshot = null
        previousDisplayedSnapshot = null
        lastDisplayDispatchNanos = 0L
        lastSnapshotReadNanos = 0L
        hasDisplayDispatch = false
        hasSnapshotRead = false
        pendingDisplayWork.set(null)
        displayGeneration += 1L

        val channel = displayChannel
        if (channel != null) {
            if (clearOutput) {
                box.displayClear(channel)
            }
            if (closeOutput) {
                box.displayClose(channel)
            }
        }

        resetLiveCodingRegistrations()
        return this
    }

    internal fun resetLiveCodingRegistrations() {
        displayChannel = null
        snapshotCallback = null
        continuousSnapshotReads = false
        previousDisplayedSnapshot = null
        lastDisplayDispatchNanos = 0L
        lastSnapshotReadNanos = 0L
        hasDisplayDispatch = false
        hasSnapshotRead = false
        pendingDisplayWork.set(null)
        displayGeneration += 1L
    }

    /**
     * Establish a clean display/readback generation at an offline movie boundary.
     *
     * Compute readbacks finish asynchronously. Without a generation boundary, a snapshot queued
     * before frame-indexed capture can arrive after the output card is cleared and become movie
     * frame zero. Its value then depends on how many live renderer frames happened during setup.
     * Keep the configured display/callback registrations, but invalidate queued work and make the
     * next completed capture-frame readback authoritative.
     */
    internal fun resetFrameSynchronousCapture() {
        pendingPerImage.fill(false)
        latestSnapshot = null
        previousDisplayedSnapshot = null
        lastDisplayDispatchNanos = 0L
        lastSnapshotReadNanos = 0L
        hasDisplayDispatch = false
        hasSnapshotRead = false
        pendingDisplayWork.set(null)
        displayGeneration += 1L
    }

    internal fun requireContinuousSnapshotReads() {
        continuousSnapshotReads = true
    }

    private fun shouldReadSnapshot(now: Long): Boolean {
        if (continuousSnapshotReads) return true
        val hz = maxReadHz ?: if (snapshotCallback != null || displayChannel == null) 0.0 else maxDisplayHz
        if (hz <= 0.0) return true
        val minIntervalNanos = (1_000_000_000.0 / hz).toLong()
        return !hasSnapshotRead || now < lastSnapshotReadNanos || now - lastSnapshotReadNanos >= minIntervalNanos
    }

    private fun dispatchSnapshot(snapshot: FieldProbeSnapshot, generation: Long = displayGeneration) {
        if (generation != displayGeneration) return
        displayChannel?.takeIf { snapshot.sampleCount > 0 }?.let { channel ->
            val now = (AnimationClock.nowSeconds() * 1_000_000_000.0).toLong()
            val minIntervalNanos = if (maxDisplayHz > 0.0) (1_000_000_000.0 / maxDisplayHz).toLong() else 0L
            if (minIntervalNanos == 0L || !hasDisplayDispatch || now < lastDisplayDispatchNanos ||
                now - lastDisplayDispatchNanos >= minIntervalNanos
            ) {
                lastDisplayDispatchNanos = now
                hasDisplayDispatch = true
                enqueueDisplay(DisplayWork(channel, snapshot, generation))
            }
        }

        snapshotCallback?.let { callback ->
            runCatching { callback(snapshot) }.onFailure {
                lprintln(
                    "error,graphics",
                    "field probe callback failed for '${box.name}' (${box.id}): ${it.message}"
                )
            }
        }
    }

    private fun enqueueDisplay(work: DisplayWork) {
        if (AnimationClock.isFrameIndexed()) {
            renderDisplayWork(work)
            return
        }
        pendingDisplayWork.set(work)
        if (displayWorkerScheduled.compareAndSet(false, true)) {
            fieldProbeDisplayExecutor.execute(::drainDisplayWork)
        }
    }

    private fun renderDisplayWork(work: DisplayWork) {
        if (work.generation != displayGeneration) return

        val previous = previousDisplayedSnapshot
        val html = runCatching {
            work.snapshot.summaryHtml(previous = previous)
        }.onFailure {
            lprintln(
                "error,graphics",
                "field probe statistics failed for '${box.name}' (${box.id}): ${it.message}"
            )
        }.getOrNull() ?: return

        if (work.generation != displayGeneration) return
        runCatching {
            box.display(work.channel, html)
        }.onFailure {
            lprintln(
                "error,graphics",
                "field probe display callback failed for '${box.name}' (${box.id}): ${it.message}"
            )
        }
        previousDisplayedSnapshot = work.snapshot
    }

    private fun drainDisplayWork() {
        try {
            while (true) {
                val work = pendingDisplayWork.getAndSet(null) ?: break
                renderDisplayWork(work)
            }
        } finally {
            displayWorkerScheduled.set(false)
            if (pendingDisplayWork.get() != null && displayWorkerScheduled.compareAndSet(false, true)) {
                fieldProbeDisplayExecutor.execute(::drainDisplayWork)
            }
        }
    }

    internal fun includeSource(): String {
        val setValue = descriptorSet
        val bindingValue = binding
        val capacityValue = FIELD_PROBE_MAX_SAMPLES
        return """
            layout(set = $setValue, binding = $bindingValue, std430) buffer $FIELD_PROBE_BUFFER_NAME {
                uint enabled;
                uint capacity;
                uint ordinalStart;
                uint ordinalEndExclusive;

                uint invocationCount;
                uint acceptedCount;
                uint finiteCount;
                uint typeTag;

                uint nanCount;
                uint infCount;
                uint nonZeroCount;
                uint typeMismatchCount;

                uint minOrderedX;
                uint minOrderedY;
                uint minOrderedZ;
                uint minOrderedW;

                uint maxOrderedX;
                uint maxOrderedY;
                uint maxOrderedZ;
                uint maxOrderedW;

                vec4 samples[$capacityValue];
            } fieldProbeBuffer;

            const uint FIELD_PROBE_TYPE_SCALAR = 1u;
            const uint FIELD_PROBE_TYPE_VEC2 = 2u;
            const uint FIELD_PROBE_TYPE_VEC3 = 3u;
            const uint FIELD_PROBE_TYPE_VEC4 = 4u;

            uint fieldProbeOrderedUint(float value) {
                uint bits = floatBitsToUint(value);
                return ((bits & 0x80000000u) != 0u) ? ~bits : (bits | 0x80000000u);
            }

            uint fieldProbeHash(uint value) {
                value ^= value >> 16;
                value *= 0x7feb352du;
                value ^= value >> 15;
                value *= 0x846ca68bu;
                value ^= value >> 16;
                return value;
            }

            bool fieldProbeAnyNaN(vec4 value) {
                bvec4 flags = isnan(value);
                return flags.x || flags.y || flags.z || flags.w;
            }

            bool fieldProbeAnyInf(vec4 value) {
                bvec4 flags = isinf(value);
                return flags.x || flags.y || flags.z || flags.w;
            }

            bool fieldProbeAnyNonZero(vec4 value, uint dimensions) {
                if (dimensions <= 1u) return value.x != 0.0;
                if (dimensions == 2u) return any(notEqual(value.xy, vec2(0.0)));
                if (dimensions == 3u) return any(notEqual(value.xyz, vec3(0.0)));
                return any(notEqual(value, vec4(0.0)));
            }

            bool fieldProbeEnabled() {
                return fieldProbeBuffer.enabled != 0u && fieldProbeBuffer.capacity != 0u;
            }

            bool fieldProbeAcceptOrdinal(uint ordinal) {
                if (ordinal < fieldProbeBuffer.ordinalStart) return false;
                return ordinal < fieldProbeBuffer.ordinalEndExclusive;
            }

            void fieldProbeWriteReservoir(uint ordinal, vec4 probeValue) {
                uint cap = fieldProbeBuffer.capacity;
                if (cap == 0u) return;
                if (ordinal < cap) {
                    fieldProbeBuffer.samples[ordinal] = probeValue;
                    return;
                }
                uint slot = fieldProbeHash(ordinal) % (ordinal + 1u);
                if (slot < cap) {
                    fieldProbeBuffer.samples[slot] = probeValue;
                }
            }

            void fieldProbeRecord(vec4 probeValue, uint dimensions) {
                if (!fieldProbeEnabled()) return;

                uint ordinal = atomicAdd(fieldProbeBuffer.invocationCount, 1u);
                if (!fieldProbeAcceptOrdinal(ordinal)) return;

                uint previousType = atomicCompSwap(fieldProbeBuffer.typeTag, 0u, dimensions);
                if (previousType != 0u && previousType != dimensions) {
                    atomicAdd(fieldProbeBuffer.typeMismatchCount, 1u);
                    return;
                }

                atomicAdd(fieldProbeBuffer.acceptedCount, 1u);

                if (fieldProbeAnyNaN(probeValue)) {
                    atomicAdd(fieldProbeBuffer.nanCount, 1u);
                    return;
                }

                if (fieldProbeAnyInf(probeValue)) {
                    atomicAdd(fieldProbeBuffer.infCount, 1u);
                    return;
                }

                if (fieldProbeAnyNonZero(probeValue, dimensions)) {
                    atomicAdd(fieldProbeBuffer.nonZeroCount, 1u);
                }

                atomicMin(fieldProbeBuffer.minOrderedX, fieldProbeOrderedUint(probeValue.x));
                atomicMin(fieldProbeBuffer.minOrderedY, fieldProbeOrderedUint(probeValue.y));
                atomicMin(fieldProbeBuffer.minOrderedZ, fieldProbeOrderedUint(probeValue.z));
                atomicMin(fieldProbeBuffer.minOrderedW, fieldProbeOrderedUint(probeValue.w));

                atomicMax(fieldProbeBuffer.maxOrderedX, fieldProbeOrderedUint(probeValue.x));
                atomicMax(fieldProbeBuffer.maxOrderedY, fieldProbeOrderedUint(probeValue.y));
                atomicMax(fieldProbeBuffer.maxOrderedZ, fieldProbeOrderedUint(probeValue.z));
                atomicMax(fieldProbeBuffer.maxOrderedW, fieldProbeOrderedUint(probeValue.w));

                uint finiteOrdinal = atomicAdd(fieldProbeBuffer.finiteCount, 1u);
                fieldProbeWriteReservoir(finiteOrdinal, probeValue);
            }

            void fieldProbe(float value) { fieldProbeRecord(vec4(value, 0.0, 0.0, 0.0), FIELD_PROBE_TYPE_SCALAR); }
            void fieldProbe(int value) { fieldProbe(float(value)); }
            void fieldProbe(uint value) { fieldProbe(float(value)); }
            void fieldProbe(vec2 value) { fieldProbeRecord(vec4(value, 0.0, 0.0), FIELD_PROBE_TYPE_VEC2); }
            void fieldProbe(vec3 value) { fieldProbeRecord(vec4(value, 0.0), FIELD_PROBE_TYPE_VEC3); }
            void fieldProbe(vec4 value) { fieldProbeRecord(value, FIELD_PROBE_TYPE_VEC4); }
            void fieldProbe(ivec2 value) { fieldProbe(vec2(value)); }
            void fieldProbe(ivec3 value) { fieldProbe(vec3(value)); }
            void fieldProbe(ivec4 value) { fieldProbe(vec4(value)); }
            void fieldProbe(uvec2 value) { fieldProbe(vec2(value)); }
            void fieldProbe(uvec3 value) { fieldProbe(vec3(value)); }
            void fieldProbe(uvec4 value) { fieldProbe(vec4(value)); }
        """.trimIndent()
    }

    internal fun beforeShaderUse(renderer: Renderer, imageIndex: Int) {
        ensureRenderer(renderer)
        val group = ensureBufferParameters(renderer).toBuffer(renderer)
        awaitComputeReadback(imageIndex)

        if (imageIndex in pendingPerImage.indices && pendingPerImage[imageIndex]) {
            try {
                val now = (AnimationClock.nowSeconds() * 1_000_000_000.0).toLong()
                if (shouldReadSnapshot(now)) {
                    val snapshot = readSnapshot(renderer, imageIndex)
                    lastSnapshotReadNanos = now
                    hasSnapshotRead = true
                    latestSnapshot = snapshot
                    dispatchSnapshot(snapshot)
                }
            } finally {
                pendingPerImage[imageIndex] = false
            }
        }

        val target = group.getAt(imageIndex, write = true)
        resetHeader(target)
    }

    internal fun afterShaderUse(renderer: Renderer, imageIndex: Int) {
        ensureRenderer(renderer)
        if (enabled && imageIndex in pendingPerImage.indices) {
            pendingPerImage[imageIndex] = true
        }
    }

    internal fun afterComputeShaderUse(renderer: Renderer, imageIndex: Int) {
        ensureRenderer(renderer)
        if (!enabled || imageIndex !in computeReadbacks.indices) return

        val now = (AnimationClock.nowSeconds() * 1_000_000_000.0).toLong()
        if (!shouldReadSnapshot(now)) return
        lastSnapshotReadNanos = now
        hasSnapshotRead = true

        val generation = displayGeneration
        renderer.afterCurrentComputeFrameSubmitted { fence ->
            val readback = Runnable {
                runCatching {
                    fence.fenceWait()
                    val snapshot = readSnapshot(renderer, imageIndex)
                    if (generation == displayGeneration) {
                        latestSnapshot = snapshot
                        dispatchSnapshot(snapshot, generation)
                    }
                }.onFailure {
                    lprintln(
                        "error,graphics",
                        "field probe readback failed for '${box.name}' (${box.id}): ${it.message}"
                    )
                }
            }
            if (AnimationClock.isFrameIndexed()) {
                readback.run()
                computeReadbacks[imageIndex] = null
                null
            } else {
                fieldProbeReadbackExecutor.submit(readback).also { computeReadbacks[imageIndex] = it }
            }
        }
    }

    internal fun bufferForImage(renderer: Renderer, imageIndex: Int): VulkanBuffer {
        ensureRenderer(renderer)
        return ensureBufferParameters(renderer).toBuffer(renderer).getAt(imageIndex, write = false)
    }

    internal fun bufferParameters(renderer: Renderer): BufferParameters {
        ensureRenderer(renderer)
        return ensureBufferParameters(renderer)
    }

    /** The most recent completed read-back, or `null` before the first one. The same value as
     * [latestSnapshot], as a call for code that reads better that way. */
    fun readLatest(): FieldProbeSnapshot? = latestSnapshot

    /**
     * [readLatest] put through [scalarBatch], so a controller can take the latest samples in one call.
     *
     * `null` when there is no snapshot yet, when the frame recorded vectors, or when fewer than
     * [minSamples] were retained. The clip arguments mean what they do on [scalarBatch].
     */
    fun readLatestScalarBatch(
        minSamples: Int = 1,
        clipLower: Double? = null,
        clipUpper: Double? = null,
        clipEpsilon: Double = 1.0e-6
    ): FieldProbeScalarBatch? =
        latestSnapshot?.scalarBatch(
            minSamples = minSamples,
            clipLower = clipLower,
            clipUpper = clipUpper,
            clipEpsilon = clipEpsilon
        )

    /**
     * Reads the probe buffer for one swapchain image and builds a snapshot from it, without waiting
     * for the GPU.
     *
     * The read-back paths call this themselves at a point where that image's work is known to have
     * finished; calling it directly means choosing [imageIndex] and knowing that, which is why
     * [latestSnapshot] is what box code wants. Only the first [sampleCapacity] samples are copied,
     * whatever the buffer holds.
     */
    fun readSnapshot(renderer: Renderer, imageIndex: Int): FieldProbeSnapshot {
        ensureRenderer(renderer)
        val target = ensureBufferParameters(renderer).toBuffer(renderer).getAt(imageIndex, write = false)
        val header = UIntArray(FIELD_PROBE_HEADER_INTS)
        val values = mutableListOf<Vec4>()

        val readSize = FIELD_PROBE_HEADER_BYTES.toLong() + sampleCapacity.toLong() * 16L
        target.mapAndReadBytes(readSize) {
            val data = duplicate().nativeOrder()
            repeat(FIELD_PROBE_HEADER_INTS) { index ->
                header[index] = data.getInt(index * 4).toUInt()
            }

            val finiteCount = header[HEADER_FINITE_COUNT].toLong()
            val configuredCapacity = header[HEADER_CAPACITY].toLong()
            val sampleCount = min(finiteCount, configuredCapacity).toInt()
            var offset = FIELD_PROBE_HEADER_BYTES
            repeat(sampleCount) {
                val x = data.getFloat(offset)
                val y = data.getFloat(offset + 4)
                val z = data.getFloat(offset + 8)
                val w = data.getFloat(offset + 12)
                values += Vec4(x, y, z, w)
                offset += 16
            }
        }

        val dimensions = header[HEADER_TYPE_TAG].toInt().coerceIn(PROBE_TYPE_UNKNOWN, PROBE_TYPE_VEC4)
        val finiteCount = header[HEADER_FINITE_COUNT].toLong()
        val exactMin = DoubleArray(dimensions.coerceAtLeast(1)) { component ->
            if (finiteCount == 0L) Double.NaN
            else floatForOrderedUint(header[HEADER_MIN_X + component]).toDouble()
        }
        val exactMax = DoubleArray(dimensions.coerceAtLeast(1)) { component ->
            if (finiteCount == 0L) Double.NaN
            else floatForOrderedUint(header[HEADER_MAX_X + component]).toDouble()
        }

        return FieldProbeSnapshot(
            dimensions = dimensions,
            invocationCount = header[HEADER_INVOCATION_COUNT].toLong(),
            acceptedCount = header[HEADER_ACCEPTED_COUNT].toLong(),
            finiteCount = finiteCount,
            sampleCount = values.size,
            nanCount = header[HEADER_NAN_COUNT].toLong(),
            infCount = header[HEADER_INF_COUNT].toLong(),
            nonZeroCount = header[HEADER_NON_ZERO_COUNT].toLong(),
            typeMismatchCount = header[HEADER_TYPE_MISMATCH_COUNT].toLong(),
            exactMin = exactMin,
            exactMax = exactMax,
            samples = values
        )
    }

    private fun ensureRenderer(renderer: Renderer) {
        if (rendererRef !== renderer) {
            computeReadbacks.forEach { it?.get() }
            rendererRef = renderer
            pendingPerImage = BooleanArray(renderer.numImages)
            computeReadbacks = arrayOfNulls(renderer.numImages)
        }
    }

    private fun awaitComputeReadback(imageIndex: Int) {
        if (imageIndex !in computeReadbacks.indices) return
        computeReadbacks[imageIndex]?.get()
        computeReadbacks[imageIndex] = null
    }

    private fun ensureBufferParameters(renderer: Renderer): BufferParameters {
        val existing = bufferParameters
        if (existing != null && existing.result?.renderer === renderer) {
            return existing
        }

        return buffer(from = null, name = FIELD_PROBE_BUFFER_NAME) {
            size = FIELD_PROBE_HEADER_BYTES.toLong() + FIELD_PROBE_MAX_SAMPLES.toLong() * 16L
            storage()
            // The GPU writes this buffer (atomics + reservoir) and the CPU reads it back every
            // frame. streamReads() requests HOST_VISIBLE | HOST_CACHED so the readback lands in
            // cached memory; plain hosted() gives write-combined memory whose CPU reads are
            // extremely slow on discrete GPUs. Flushes (header reset) and invalidates (readback)
            // are already issued by mapAndWrite/mapAndReadBytes, so non-coherent cached memory
            // stays correct.
            streamReads()
            perFrame = true
        }.also {
            bufferParameters = it
            it.toBuffer(renderer)
        }
    }

    private fun resetHeader(target: VulkanBuffer) {
        target.mapAndWrite_bytes {
            val data = duplicate().nativeOrder()
            data.putInt(HEADER_ENABLED * 4, if (enabled) 1 else 0)
            data.putInt(HEADER_CAPACITY * 4, sampleCapacity)
            data.putInt(HEADER_ORDINAL_START * 4, ordinalStart.toInt())
            data.putInt(HEADER_ORDINAL_END * 4, ordinalEndExclusive.toInt())
            data.putInt(HEADER_INVOCATION_COUNT * 4, 0)
            data.putInt(HEADER_ACCEPTED_COUNT * 4, 0)
            data.putInt(HEADER_FINITE_COUNT * 4, 0)
            data.putInt(HEADER_TYPE_TAG * 4, 0)
            data.putInt(HEADER_NAN_COUNT * 4, 0)
            data.putInt(HEADER_INF_COUNT * 4, 0)
            data.putInt(HEADER_NON_ZERO_COUNT * 4, 0)
            data.putInt(HEADER_TYPE_MISMATCH_COUNT * 4, 0)

            val minSentinel = orderedUintForFloat(Float.POSITIVE_INFINITY).toInt()
            val maxSentinel = orderedUintForFloat(Float.NEGATIVE_INFINITY).toInt()

            data.putInt(HEADER_MIN_X * 4, minSentinel)
            data.putInt(HEADER_MIN_Y * 4, minSentinel)
            data.putInt(HEADER_MIN_Z * 4, minSentinel)
            data.putInt(HEADER_MIN_W * 4, minSentinel)
            data.putInt(HEADER_MAX_X * 4, maxSentinel)
            data.putInt(HEADER_MAX_Y * 4, maxSentinel)
            data.putInt(HEADER_MAX_Z * 4, maxSentinel)
            data.putInt(HEADER_MAX_W * 4, maxSentinel)
        }
    }
}
