@file:OptIn(ExperimentalUnsignedTypes::class)

package afield.four.util

import afield.Box
import afield.AnimationClock
import afield.displayClear
import afield.displayClose
import afield.four.Renderer
import afield.four.buffers.BufferParameters
import afield.four.buffers.VulkanBuffer
import afield.four.buffers.buffer
import afield.four.device.Fence
import afield.logger.lprintln
import afield.stdlib.ScalarSampleBatch
import afield.stdlib.Histogram
import afield.stdlib.SummaryStats
import afield.stdlib.SummaryStatsBox
import afield.stdlib.Vec2
import afield.stdlib.Vec3
import afield.stdlib.Vec4
import afield.stdlib.Vec2SummaryStats
import afield.stdlib.Vec3SummaryStats
import afield.stdlib.Vec4SummaryStats
import afield.stdlib.buildHistogram
import afield.stdlib.buildSummaryStats
import afield.stdlib.buildVec2SummaryStats
import afield.stdlib.buildVec3SummaryStats
import afield.stdlib.buildVec4SummaryStats
import afield.stdlib.histogramToSvg
import afield.stdlib.summaryStatsBoxToHtml
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
import afield.Table
import afield.FieldMarkdown

const val FIELD_PROBE_BUFFER_NAME = "FieldProbeBuffer"
const val FIELD_PROBE_INCLUDE_NAME = "fieldProbe"
const val FIELD_PROBE_DEFAULT_SAMPLES = 1024
const val FIELD_PROBE_MAX_SAMPLES = 1024 * 256
const val FIELD_PROBE_DEFAULT_BINDING = 63
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

data class FieldProbeSnapshot(
    val dimensions: Int,
    val invocationCount: Long,
    val acceptedCount: Long,
    val finiteCount: Long,
    val sampleCount: Int,
    val nanCount: Long,
    val infCount: Long,
    val nonZeroCount: Long,
    val typeMismatchCount: Long,
    val exactMin: DoubleArray,
    val exactMax: DoubleArray,
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

    val anyNaN: Boolean
        get() = nanCount > 0

    val anyInf: Boolean
        get() = infCount > 0

    val anyNonZero: Boolean
        get() = nonZeroCount > 0

    fun scalarValues(): List<Double>? = cachedScalarValues

    fun vec2Values(): List<Vec2>? = cachedVec2Values

    fun vec3Values(): List<Vec3>? = cachedVec3Values

    fun vec4Values(): List<Vec4>? = cachedVec4Values

    fun scalarSummary(): SummaryStats? = cachedScalarSummary

    fun vec2Summary(): Vec2SummaryStats? = cachedVec2Summary

    fun vec3Summary(): Vec3SummaryStats? = cachedVec3Summary

    fun vec4Summary(): Vec4SummaryStats? = cachedVec4Summary

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

data class FieldProbeScalarBatch(
    val snapshot: FieldProbeSnapshot,
    val batch: ScalarSampleBatch
)

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

class ShaderFieldProbe(
    val box: Box
) {
    private data class DisplayWork(
        val channel: String,
        val snapshot: FieldProbeSnapshot,
        val generation: Long
    )

    var sampleCapacity: Int = FIELD_PROBE_DEFAULT_SAMPLES
        set(value) {
            field = value.coerceIn(1, FIELD_PROBE_MAX_SAMPLES)
        }

    var binding: Int = FIELD_PROBE_DEFAULT_BINDING
    var descriptorSet: Int = FIELD_PROBE_DEFAULT_SET
    var enabled: Boolean = true
    var maxDisplayHz: Double = 5.0

    var ordinalStart: UInt = 0u
        private set
    var ordinalEndExclusive: UInt = UInt.MAX_VALUE
        private set

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

    fun ordinalRange(start: UInt = 0u, endExclusive: UInt = UInt.MAX_VALUE): ShaderFieldProbe {
        ordinalStart = start
        ordinalEndExclusive = endExclusive
        return this
    }

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

    fun onSnapshot(callback: (FieldProbeSnapshot) -> Unit): ShaderFieldProbe {
        snapshotCallback = callback
        return this
    }

    /** Set the box output channel that receives probe summaries, and return this probe for chaining. */
    fun display(channel: String = "probe"): ShaderFieldProbe {
        displayChannel = channel
        return this
    }

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
        if (continuousSnapshotReads || snapshotCallback != null || displayChannel == null) return true
        if (maxDisplayHz <= 0.0) return true
        val minIntervalNanos = (1_000_000_000.0 / maxDisplayHz).toLong()
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

    fun readLatest(): FieldProbeSnapshot? = latestSnapshot

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
