@file:Suppress("MemberVisibilityCanBePrivate")
@file:afield.CompletionCategory("Math")

package afield.stdlib

import java.lang.Math.ulp
import java.util.Locale
import kotlin.collections.ArrayDeque
import kotlin.math.*
import kotlin.math.atan2

/**
 * Descriptive statistics, and the SVG/HTML renderings Field uses to put them in a box output.
 *
 * [buildSummaryStats] reduces a sequence of doubles to a [SummaryStats] — count, quartiles, IQR,
 * MAD, variance, skewness, excess kurtosis — dropping and counting non-finite values as it goes.
 * [buildHistogram] picks a bin width as the smallest of the Freedman–Diaconis, Scott and Sturges
 * estimates, clamped to at most 2000 bins. [SummaryStatsBuffer] wraps both behind a synchronized,
 * capped sliding window you can `add` to sample by sample; there are parallel
 * [Vec2SummaryStats] / [Vec3SummaryStats] / [Vec4SummaryStats] forms that carry per-component
 * stats, vector length and the pairwise component correlations, each with their own buffer.
 *
 * The rendering half — [histogramToSvg], [seriesPlotToSvg] and [summaryStatsBoxToHtml] — builds
 * SVG and HTML by string interpolation, with no drawing library involved, and defaults to
 * Field's cyan-on-black look through a long list of colour and padding parameters. Pass the
 * previous [SummaryStats] and changed numbers are tinted by the sign and relative size of the
 * change. [seriesPlotToSvg] envelope-downsamples to `maxSeriesPoints` (500) before drawing.
 */

data class HistogramBin(val start: Double, val end: Double, val count: Int)

data class Histogram(
    val bins: List<HistogramBin>,
    val min: Double,
    val q1: Double,
    val median: Double,
    val q3: Double,
    val max: Double,
    val binWidth: Double
) {
    val count: Int
        get() = bins.sumOf { it.count }
    val modeBin: HistogramBin?
        get() = bins.maxByOrNull { it.count }
}

data class SummaryStats(
    val count: Int,
    val droppedCount: Int,
    val min: Double,
    val q1: Double,
    val median: Double,
    val q3: Double,
    val max: Double,
    val sum: Double,
    val mean: Double,
    val sampleVariance: Double,
    val sampleStdDev: Double,
    val populationVariance: Double,
    val populationStdDev: Double,
    val iqr: Double,
    val mad: Double,
    val skewness: Double,
    val kurtosisExcess: Double
) {
    val isEmpty: Boolean
        get() = count == 0
    val range: Double
        get() = if (isEmpty || !min.isFinite() || !max.isFinite()) Double.NaN else max - min
    val coeffOfVariation: Double
        get() = if (mean == 0.0 || !mean.isFinite() || !sampleStdDev.isFinite()) Double.NaN else sampleStdDev / mean

    companion object {
        fun empty(droppedCount: Int = 0): SummaryStats = SummaryStats(
            count = 0,
            droppedCount = droppedCount,
            min = Double.NaN,
            q1 = Double.NaN,
            median = Double.NaN,
            q3 = Double.NaN,
            max = Double.NaN,
            sum = 0.0,
            mean = Double.NaN,
            sampleVariance = Double.NaN,
            sampleStdDev = Double.NaN,
            populationVariance = Double.NaN,
            populationStdDev = Double.NaN,
            iqr = Double.NaN,
            mad = Double.NaN,
            skewness = Double.NaN,
            kurtosisExcess = Double.NaN
        )
    }
}

data class SummaryStatsBox(
    val values: List<Double>,
    val stats: SummaryStats,
    val histogram: Histogram,
    val windowSize: Int,
    val samplesSeen: Long,
    val droppedNonFinite: Long,
    val droppedByCapacity: Long
)

private const val STATS_ORANGE_COLOR = "#ff8a00"
private const val STATS_UP_COLOR = "#55ff88"
private const val STATS_DOWN_COLOR = "#ff4d6d"
private const val STATS_SMALL_CHANGE_PIVOT = 0.08

private fun mixChannel(a: Int, b: Int, t: Double): Int =
    (a + ((b - a) * t.coerceIn(0.0, 1.0))).roundToInt().coerceIn(0, 255)

private fun parseHexColor(color: String): Triple<Int, Int, Int>? {
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

private fun colorRamp(from: String, to: String, t: Double): String {
    val a = parseHexColor(from) ?: return from
    val b = parseHexColor(to) ?: return to
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
    val target = if (current > previous) STATS_UP_COLOR else STATS_DOWN_COLOR
    return if (amount <= STATS_SMALL_CHANGE_PIVOT) {
        colorRamp(fallback, STATS_ORANGE_COLOR, amount / STATS_SMALL_CHANGE_PIVOT)
    } else {
        colorRamp(
            STATS_ORANGE_COLOR,
            target,
            (amount - STATS_SMALL_CHANGE_PIVOT) / (1.0 - STATS_SMALL_CHANGE_PIVOT)
        )
    }
}

private fun deltaStyle(current: Double, previous: Double?, fallback: String = "#0af"): String {
    val color = signedDeltaColor(current, previous, fallback)
    if (color == fallback) return ""
    return "color:$color;"
}

private fun deltaStyle(current: Long, previous: Long?, fallback: String = "#0af"): String =
    deltaStyle(current.toDouble(), previous?.toDouble(), fallback)

private fun deltaColor(current: Double, previous: Double?, fallback: String): String {
    return signedDeltaColor(current, previous, fallback)
}

private fun statMetric(key: String, value: String, style: String = "", keyTextOpacity: Double = 0.5): String = """
    <div style="display:grid;grid-template-columns:minmax(0,1fr) minmax(3.2em,max-content);gap:4px;white-space:nowrap;min-width:0;align-items:baseline;">
      <span style="opacity:${keyTextOpacity};text-align:right;overflow:hidden;text-overflow:ellipsis;">$key</span>
      <span style="$style;font-variant-numeric:tabular-nums;min-width:3.2em;">$value</span>
    </div>
""".trimIndent()

private fun svgTitleBackdrop(
    title: String?,
    widthPx: Int,
    padTop: Int,
    fontFamily: String,
    textColor: String
): String {
    if (title == null) return ""
    val titleWidth = (title.length * 7 + 18).coerceAtLeast(72)
    val x = ((widthPx - titleWidth) / 2.0).toFloat()
    val y = (padTop * 0.9 - 14.0).toFloat()
    return """
        <foreignObject x="$x" y="$y" width="$titleWidth" height="22">
          <div xmlns="http://www.w3.org/1999/xhtml" style="
              height:22px;
              display:flex;
              align-items:center;
              justify-content:center;
              color:$textColor;
              font-family:$fontFamily;
              font-size:12px;
              line-height:22px;
              background:rgba(0,0,0,0.88);
              backdrop-filter:blur(5px);
              -webkit-backdrop-filter:blur(5px);
          ">$title</div>
        </foreignObject>
    """.trimIndent()
}

data class Vec2SummaryStats(
    val x: SummaryStats,
    val y: SummaryStats,
    val length: SummaryStats,
    val angleRadians: SummaryStats,
    val correlationXY: Double
) {
    val count: Int
        get() = x.count
    val droppedCount: Int
        get() = x.droppedCount
    val mean: Vec2
        get() = Vec2(x.mean.toFloat(), y.mean.toFloat())
    val median: Vec2
        get() = Vec2(x.median.toFloat(), y.median.toFloat())
    val min: Vec2
        get() = Vec2(x.min.toFloat(), y.min.toFloat())
    val max: Vec2
        get() = Vec2(x.max.toFloat(), y.max.toFloat())
}

data class Vec3SummaryStats(
    val x: SummaryStats,
    val y: SummaryStats,
    val z: SummaryStats,
    val length: SummaryStats,
    val correlationXY: Double,
    val correlationXZ: Double,
    val correlationYZ: Double
) {
    val count: Int
        get() = x.count
    val droppedCount: Int
        get() = x.droppedCount
    val mean: Vec3
        get() = Vec3(x.mean.toFloat(), y.mean.toFloat(), z.mean.toFloat())
    val median: Vec3
        get() = Vec3(x.median.toFloat(), y.median.toFloat(), z.median.toFloat())
    val min: Vec3
        get() = Vec3(x.min.toFloat(), y.min.toFloat(), z.min.toFloat())
    val max: Vec3
        get() = Vec3(x.max.toFloat(), y.max.toFloat(), z.max.toFloat())
}

data class Vec4SummaryStats(
    val x: SummaryStats,
    val y: SummaryStats,
    val z: SummaryStats,
    val w: SummaryStats,
    val length: SummaryStats,
    val correlationXY: Double,
    val correlationXZ: Double,
    val correlationXW: Double,
    val correlationYZ: Double,
    val correlationYW: Double,
    val correlationZW: Double
) {
    val count: Int
        get() = x.count
    val droppedCount: Int
        get() = x.droppedCount
    val mean: Vec4
        get() = Vec4(x.mean.toFloat(), y.mean.toFloat(), z.mean.toFloat(), w.mean.toFloat())
    val median: Vec4
        get() = Vec4(x.median.toFloat(), y.median.toFloat(), z.median.toFloat(), w.median.toFloat())
    val min: Vec4
        get() = Vec4(x.min.toFloat(), y.min.toFloat(), z.min.toFloat(), w.min.toFloat())
    val max: Vec4
        get() = Vec4(x.max.toFloat(), y.max.toFloat(), z.max.toFloat(), w.max.toFloat())
}

data class Vec2SummaryStatsBox(
    val values: List<Vec2>,
    val stats: Vec2SummaryStats,
    val windowSize: Int,
    val samplesSeen: Long,
    val droppedNonFinite: Long,
    val droppedByCapacity: Long
)

data class Vec3SummaryStatsBox(
    val values: List<Vec3>,
    val stats: Vec3SummaryStats,
    val windowSize: Int,
    val samplesSeen: Long,
    val droppedNonFinite: Long,
    val droppedByCapacity: Long
)

data class Vec4SummaryStatsBox(
    val values: List<Vec4>,
    val stats: Vec4SummaryStats,
    val windowSize: Int,
    val samplesSeen: Long,
    val droppedNonFinite: Long,
    val droppedByCapacity: Long
)

/**
 * Rolling numeric summary + histogram buffer.
 *
 * Keeps only finite numbers and maintains a capped sliding window.
 */
class SummaryStatsBuffer(
    maxSize: Int = 100
) {
    private val values = ArrayDeque<Double>()

    var maxSize: Int = maxSize.coerceAtLeast(1)
        set(value) {
            field = value.coerceAtLeast(1)
            trimToWindow()
        }

    var samplesSeen: Long = 0L
        private set

    var droppedNonFinite: Long = 0L
        private set

    var droppedByCapacity: Long = 0L
        private set

    val size: Int
        @Synchronized
        get() = values.size

    @Synchronized
    fun setWindow(size: Int): SummaryStatsBuffer {
        maxSize = size
        return this
    }

    @Synchronized
    fun clear(resetCounters: Boolean = false): SummaryStatsBuffer {
        values.clear()
        if (resetCounters) {
            samplesSeen = 0L
            droppedNonFinite = 0L
            droppedByCapacity = 0L
        }
        return this
    }

    /**
     * Adds a numeric sample and returns this buffer for chaining.
     *
     * Null and non-finite values are counted as dropped; finite values are
     * converted to `Double`, appended, and trimmed to the current window.
     */
    @Synchronized
    fun add(value: Number?): SummaryStatsBuffer {
        samplesSeen++
        val v = value?.toDouble()
        if (v == null || !v.isFinite()) {
            droppedNonFinite++
            return this
        }
        values.addLast(v)
        trimToWindow()
        return this
    }

    fun add(value: Double): SummaryStatsBuffer = add(value as Number)

    fun addAll(values: Iterable<Number?>): SummaryStatsBuffer {
        values.forEach { add(it) }
        return this
    }

    @Synchronized
    fun values(): List<Double> = values.toList()

    @Synchronized
    fun snapshot(): SummaryStats =
        buildSummaryStats(values.toList(), droppedCount = clampLongToInt(droppedNonFinite))

    @Synchronized
    fun histogram(): Histogram = buildHistogram(values.toList())

    @Synchronized
    fun summaryBox(): SummaryStatsBox {
        val current = values.toList()
        return SummaryStatsBox(
            values = current,
            stats = buildSummaryStats(current, droppedCount = clampLongToInt(droppedNonFinite)),
            histogram = buildHistogram(current),
            windowSize = maxSize,
            samplesSeen = samplesSeen,
            droppedNonFinite = droppedNonFinite,
            droppedByCapacity = droppedByCapacity
        )
    }

    fun histogramSvg(
        previousStats: SummaryStats? = null,
        widthPx: Int = 480,
        heightPx: Int = 240,
        padLeft: Int = 14,
        padRight: Int = 14,
        padTop: Int = 10,
        padBottom: Int = 22,
        fontFamily: String = "'Iosevka Web', Iosevka, monospace",
        title: String? = null,
        showCountsOnHover: Boolean = true,
        decimals: Int = 3,
        background: String = "#000000",
        textColor: String = "#0af",
        tickColor: String = "#0af",
        axisColor: String = "#0af",
        barFill: String = "#0af",
        barFillOpacity: Double = 0.2,
        barStroke: String = "#0af",
        barStrokeOpacity: Double = 0.5,
        barStrokeWidth: Double = 1.5,
        keyTextOpacity: Double = 0.5
    ): String = histogramToSvg(
        hist = histogram(),
        previousStats = previousStats,
        widthPx = widthPx,
        heightPx = heightPx,
        padLeft = padLeft,
        padRight = padRight,
        padTop = padTop,
        padBottom = padBottom,
        fontFamily = fontFamily,
        title = title,
        showCountsOnHover = showCountsOnHover,
        decimals = decimals,
        background = background,
        textColor = textColor,
        tickColor = tickColor,
        axisColor = axisColor,
        barFill = barFill,
        barFillOpacity = barFillOpacity,
        barStroke = barStroke,
        barStrokeOpacity = barStrokeOpacity,
        barStrokeWidth = barStrokeWidth,
        keyTextOpacity = keyTextOpacity
    )

    fun seriesSvg(
        previousStats: SummaryStats? = null,
        widthPx: Int = 480,
        heightPx: Int = 240,
        padLeft: Int = 40,
        padRight: Int = 64,
        padTop: Int = 10,
        padBottom: Int = 22,
        fontFamily: String = "'Iosevka Web', Iosevka, monospace",
        title: String? = null,
        decimals: Int = 3,
        background: String = "#000000",
        textColor: String = "#0af",
        tickColor: String = "#0af",
        axisColor: String = "#0af",
        seriesColor: String = "#0af",
        seriesOpacity: Double = 0.85,
        seriesStrokeWidth: Double = 1.5,
        refDash: String = "3,2",
        keyTextOpacity: Double = 0.5,
        minLabelSeparationPx: Double = 12.0
    ): String = seriesPlotToSvg(
        rawValues = values(),
        previousStats = previousStats,
        widthPx = widthPx,
        heightPx = heightPx,
        padLeft = padLeft,
        padRight = padRight,
        padTop = padTop,
        padBottom = padBottom,
        fontFamily = fontFamily,
        title = title,
        decimals = decimals,
        background = background,
        textColor = textColor,
        tickColor = tickColor,
        axisColor = axisColor,
        seriesColor = seriesColor,
        seriesOpacity = seriesOpacity,
        seriesStrokeWidth = seriesStrokeWidth,
        refDash = refDash,
        keyTextOpacity = keyTextOpacity,
        minLabelSeparationPx = minLabelSeparationPx
    )

    fun summaryHtml(
        widthPx: Int = 480,
        chartHeightPx: Int = 120,
        decimals: Int = 3,
        fontFamily: String = "'Iosevka Web', Iosevka, monospace",
        background: String = "#000000",
        textColor: String = "#0af",
        axisColor: String = "#0af",
        keyTextOpacity: Double = 0.5,
        previousSummary: SummaryStatsBox? = null
    ): String = summaryStatsBoxToHtml(
        summary = summaryBox(),
        previousSummary = previousSummary,
        widthPx = widthPx,
        chartHeightPx = chartHeightPx,
        decimals = decimals,
        fontFamily = fontFamily,
        background = background,
        textColor = textColor,
        axisColor = axisColor,
        keyTextOpacity = keyTextOpacity
    )

    private fun trimToWindow() {
        while (values.size > maxSize) {
            values.removeFirst()
            droppedByCapacity++
        }
    }
}

class Vec2SummaryStatsBuffer(
    maxSize: Int = 100
) {
    private val values = ArrayDeque<Vec2>()

    var maxSize: Int = maxSize.coerceAtLeast(1)
        set(value) {
            field = value.coerceAtLeast(1)
            trimToWindow()
        }

    var samplesSeen: Long = 0L
        private set

    var droppedNonFinite: Long = 0L
        private set

    var droppedByCapacity: Long = 0L
        private set

    val size: Int
        @Synchronized
        get() = values.size

    @Synchronized
    fun setWindow(size: Int): Vec2SummaryStatsBuffer {
        maxSize = size
        return this
    }

    @Synchronized
    fun clear(resetCounters: Boolean = false): Vec2SummaryStatsBuffer {
        values.clear()
        if (resetCounters) {
            samplesSeen = 0L
            droppedNonFinite = 0L
            droppedByCapacity = 0L
        }
        return this
    }

    @Synchronized
    fun add(value: Vec2?): Vec2SummaryStatsBuffer {
        samplesSeen++
        val v = value?.let(::Vec2)
        if (v == null || !v.isFinite()) {
            droppedNonFinite++
            return this
        }
        values.addLast(v)
        trimToWindow()
        return this
    }

    fun addAll(values: Iterable<Vec2?>): Vec2SummaryStatsBuffer {
        values.forEach { add(it) }
        return this
    }

    @Synchronized
    fun values(): List<Vec2> = values.map(::Vec2)

    @Synchronized
    fun snapshot(): Vec2SummaryStats =
        buildVec2SummaryStats(values.map(::Vec2), droppedCount = clampLongToInt(droppedNonFinite))

    @Synchronized
    fun summaryBox(): Vec2SummaryStatsBox {
        val current = values.map(::Vec2)
        return Vec2SummaryStatsBox(
            values = current,
            stats = buildVec2SummaryStats(current, droppedCount = clampLongToInt(droppedNonFinite)),
            windowSize = maxSize,
            samplesSeen = samplesSeen,
            droppedNonFinite = droppedNonFinite,
            droppedByCapacity = droppedByCapacity
        )
    }

    @Synchronized
    fun lengthHistogram(): Histogram = buildHistogram(values.asSequence().map { it.length().toDouble() }.toList())

    fun lengthHistogramSvg(
        widthPx: Int = 480,
        heightPx: Int = 240,
        title: String? = "Vec2 length",
        decimals: Int = 3
    ): String = histogramToSvg(
        hist = lengthHistogram(),
        widthPx = widthPx,
        heightPx = heightPx,
        title = title,
        decimals = decimals
    )

    private fun trimToWindow() {
        while (values.size > maxSize) {
            values.removeFirst()
            droppedByCapacity++
        }
    }
}

class Vec3SummaryStatsBuffer(
    maxSize: Int = 100
) {
    private val values = ArrayDeque<Vec3>()

    var maxSize: Int = maxSize.coerceAtLeast(1)
        set(value) {
            field = value.coerceAtLeast(1)
            trimToWindow()
        }

    var samplesSeen: Long = 0L
        private set

    var droppedNonFinite: Long = 0L
        private set

    var droppedByCapacity: Long = 0L
        private set

    val size: Int
        @Synchronized
        get() = values.size

    @Synchronized
    fun setWindow(size: Int): Vec3SummaryStatsBuffer {
        maxSize = size
        return this
    }

    @Synchronized
    fun clear(resetCounters: Boolean = false): Vec3SummaryStatsBuffer {
        values.clear()
        if (resetCounters) {
            samplesSeen = 0L
            droppedNonFinite = 0L
            droppedByCapacity = 0L
        }
        return this
    }

    @Synchronized
    fun add(value: Vec3?): Vec3SummaryStatsBuffer {
        samplesSeen++
        val v = value?.let(::Vec3)
        if (v == null || !v.isFinite()) {
            droppedNonFinite++
            return this
        }
        values.addLast(v)
        trimToWindow()
        return this
    }

    fun addAll(values: Iterable<Vec3?>): Vec3SummaryStatsBuffer {
        values.forEach { add(it) }
        return this
    }

    @Synchronized
    fun values(): List<Vec3> = values.map(::Vec3)

    @Synchronized
    fun snapshot(): Vec3SummaryStats =
        buildVec3SummaryStats(values.map(::Vec3), droppedCount = clampLongToInt(droppedNonFinite))

    @Synchronized
    fun summaryBox(): Vec3SummaryStatsBox {
        val current = values.map(::Vec3)
        return Vec3SummaryStatsBox(
            values = current,
            stats = buildVec3SummaryStats(current, droppedCount = clampLongToInt(droppedNonFinite)),
            windowSize = maxSize,
            samplesSeen = samplesSeen,
            droppedNonFinite = droppedNonFinite,
            droppedByCapacity = droppedByCapacity
        )
    }

    @Synchronized
    fun lengthHistogram(): Histogram = buildHistogram(values.asSequence().map { it.length().toDouble() }.toList())

    fun lengthHistogramSvg(
        widthPx: Int = 480,
        heightPx: Int = 240,
        title: String? = "Vec3 length",
        decimals: Int = 3
    ): String = histogramToSvg(
        hist = lengthHistogram(),
        widthPx = widthPx,
        heightPx = heightPx,
        title = title,
        decimals = decimals
    )

    private fun trimToWindow() {
        while (values.size > maxSize) {
            values.removeFirst()
            droppedByCapacity++
        }
    }
}

class Vec4SummaryStatsBuffer(
    maxSize: Int = 100
) {
    private val values = ArrayDeque<Vec4>()

    var maxSize: Int = maxSize.coerceAtLeast(1)
        set(value) {
            field = value.coerceAtLeast(1)
            trimToWindow()
        }

    var samplesSeen: Long = 0L
        private set

    var droppedNonFinite: Long = 0L
        private set

    var droppedByCapacity: Long = 0L
        private set

    val size: Int
        @Synchronized
        get() = values.size

    @Synchronized
    fun setWindow(size: Int): Vec4SummaryStatsBuffer {
        maxSize = size
        return this
    }

    @Synchronized
    fun clear(resetCounters: Boolean = false): Vec4SummaryStatsBuffer {
        values.clear()
        if (resetCounters) {
            samplesSeen = 0L
            droppedNonFinite = 0L
            droppedByCapacity = 0L
        }
        return this
    }

    @Synchronized
    fun add(value: Vec4?): Vec4SummaryStatsBuffer {
        samplesSeen++
        val v = value?.let(::Vec4)
        if (v == null || !v.isFinite()) {
            droppedNonFinite++
            return this
        }
        values.addLast(v)
        trimToWindow()
        return this
    }

    fun addAll(values: Iterable<Vec4?>): Vec4SummaryStatsBuffer {
        values.forEach { add(it) }
        return this
    }

    @Synchronized
    fun values(): List<Vec4> = values.map(::Vec4)

    @Synchronized
    fun snapshot(): Vec4SummaryStats =
        buildVec4SummaryStats(values.map(::Vec4), droppedCount = clampLongToInt(droppedNonFinite))

    @Synchronized
    fun summaryBox(): Vec4SummaryStatsBox {
        val current = values.map(::Vec4)
        return Vec4SummaryStatsBox(
            values = current,
            stats = buildVec4SummaryStats(current, droppedCount = clampLongToInt(droppedNonFinite)),
            windowSize = maxSize,
            samplesSeen = samplesSeen,
            droppedNonFinite = droppedNonFinite,
            droppedByCapacity = droppedByCapacity
        )
    }

    @Synchronized
    fun lengthHistogram(): Histogram = buildHistogram(values.asSequence().map { it.length().toDouble() }.toList())

    fun lengthHistogramSvg(
        widthPx: Int = 480,
        heightPx: Int = 240,
        title: String? = "Vec4 length",
        decimals: Int = 3
    ): String = histogramToSvg(
        hist = lengthHistogram(),
        widthPx = widthPx,
        heightPx = heightPx,
        title = title,
        decimals = decimals
    )

    private fun trimToWindow() {
        while (values.size > maxSize) {
            values.removeFirst()
            droppedByCapacity++
        }
    }
}

/**
 * Builds descriptive summary statistics from the finite values in [rawValues].
 *
 * Non-finite inputs are excluded, added to [droppedCount], and reported via [onDrop]
 * when any values are dropped. Returns an empty summary if no finite values remain.
 */
fun buildSummaryStats(
    rawValues: Iterable<Double>,
    droppedCount: Int = 0,
    onDrop: ((droppedCount: Int) -> Unit)? = null
): SummaryStats {
    val all = rawValues.toList()
    val filtered = all.filter { it.isFinite() }.toDoubleArray()
    val dropped = droppedCount + (all.size - filtered.size)
    if (dropped > 0) onDrop?.invoke(dropped)

    val n = filtered.size
    if (n == 0) {
        return SummaryStats.empty(droppedCount = dropped)
    }

    filtered.sort()
    val min = filtered.first()
    val max = filtered.last()
    val q1 = quantileSorted(filtered, 0.25)
    val median = quantileSorted(filtered, 0.5)
    val q3 = quantileSorted(filtered, 0.75)
    val iqr = q3 - q1

    var sum = 0.0
    for (v in filtered) sum += v
    val mean = sum / n.toDouble()

    var m2 = 0.0
    var m3 = 0.0
    var m4 = 0.0
    for (v in filtered) {
        val d = v - mean
        val d2 = d * d
        m2 += d2
        m3 += d2 * d
        m4 += d2 * d2
    }

    val populationVariance = m2 / n.toDouble()
    val sampleVariance = if (n > 1) m2 / (n - 1).toDouble() else 0.0
    val populationStdDev = sqrt(populationVariance.coerceAtLeast(0.0))
    val sampleStdDev = sqrt(sampleVariance.coerceAtLeast(0.0))

    val mad = filtered
        .map { abs(it - median) }
        .sorted()
        .toDoubleArray()
        .let { quantileSorted(it, 0.5) }

    val skewness = if (populationStdDev > 0.0 && n > 2) {
        (m3 / n.toDouble()) / populationStdDev.pow(3)
    } else {
        0.0
    }
    val kurtosisExcess = if (populationStdDev > 0.0 && n > 3) {
        (m4 / n.toDouble()) / populationStdDev.pow(4) - 3.0
    } else {
        0.0
    }

    return SummaryStats(
        count = n,
        droppedCount = dropped,
        min = min,
        q1 = q1,
        median = median,
        q3 = q3,
        max = max,
        sum = sum,
        mean = mean,
        sampleVariance = sampleVariance,
        sampleStdDev = sampleStdDev,
        populationVariance = populationVariance,
        populationStdDev = populationStdDev,
        iqr = iqr,
        mad = mad,
        skewness = skewness,
        kurtosisExcess = kurtosisExcess
    )
}

/**
 * Summarizes finite 2D vectors by component, length, angle, and X/Y correlation.
 *
 * Non-finite vectors are excluded, added to [droppedCount], and reported via [onDrop]
 * when any values are dropped. Empty input yields empty component stats and `NaN` correlation.
 */
fun buildVec2SummaryStats(
    rawValues: Iterable<Vec2>,
    droppedCount: Int = 0,
    onDrop: ((droppedCount: Int) -> Unit)? = null
): Vec2SummaryStats {
    val all = rawValues.toList()
    val finite = all.filter { it.isFinite() }
    val dropped = droppedCount + (all.size - finite.size)
    if (dropped > 0) onDrop?.invoke(dropped)

    if (finite.isEmpty()) {
        val empty = SummaryStats.empty(droppedCount = dropped)
        return Vec2SummaryStats(
            x = empty,
            y = empty,
            length = empty,
            angleRadians = empty,
            correlationXY = Double.NaN
        )
    }

    val n = finite.size
    val xs = DoubleArray(n)
    val ys = DoubleArray(n)
    val lengths = DoubleArray(n)
    val angles = DoubleArray(n)
    for (i in 0 until n) {
        val v = finite[i]
        xs[i] = v.x.toDouble()
        ys[i] = v.y.toDouble()
        lengths[i] = v.length().toDouble()
        angles[i] = atan2(v.y.toDouble(), v.x.toDouble())
    }

    return Vec2SummaryStats(
        x = buildSummaryStats(xs.toList(), droppedCount = dropped),
        y = buildSummaryStats(ys.toList(), droppedCount = dropped),
        length = buildSummaryStats(lengths.toList(), droppedCount = dropped),
        angleRadians = buildSummaryStats(angles.toList(), droppedCount = dropped),
        correlationXY = correlation(xs, ys)
    )
}

/**
 * Summarizes finite 3D vectors by component, length, and pairwise correlations.
 *
 * Non-finite vectors are excluded, added to [droppedCount], and reported via [onDrop]
 * when any values are dropped. Empty input yields empty component stats and `NaN` correlations.
 */
fun buildVec3SummaryStats(
    rawValues: Iterable<Vec3>,
    droppedCount: Int = 0,
    onDrop: ((droppedCount: Int) -> Unit)? = null
): Vec3SummaryStats {
    val all = rawValues.toList()
    val finite = all.filter { it.isFinite() }
    val dropped = droppedCount + (all.size - finite.size)
    if (dropped > 0) onDrop?.invoke(dropped)

    if (finite.isEmpty()) {
        val empty = SummaryStats.empty(droppedCount = dropped)
        return Vec3SummaryStats(
            x = empty,
            y = empty,
            z = empty,
            length = empty,
            correlationXY = Double.NaN,
            correlationXZ = Double.NaN,
            correlationYZ = Double.NaN
        )
    }

    val n = finite.size
    val xs = DoubleArray(n)
    val ys = DoubleArray(n)
    val zs = DoubleArray(n)
    val lengths = DoubleArray(n)
    for (i in 0 until n) {
        val v = finite[i]
        xs[i] = v.x.toDouble()
        ys[i] = v.y.toDouble()
        zs[i] = v.z.toDouble()
        lengths[i] = v.length().toDouble()
    }

    return Vec3SummaryStats(
        x = buildSummaryStats(xs.toList(), droppedCount = dropped),
        y = buildSummaryStats(ys.toList(), droppedCount = dropped),
        z = buildSummaryStats(zs.toList(), droppedCount = dropped),
        length = buildSummaryStats(lengths.toList(), droppedCount = dropped),
        correlationXY = correlation(xs, ys),
        correlationXZ = correlation(xs, zs),
        correlationYZ = correlation(ys, zs)
    )
}

/**
 * Summarizes finite 4D vectors by component, length, and pairwise component correlations.
 *
 * Non-finite vectors are excluded, added to [droppedCount], and reported via [onDrop]
 * when any values are dropped. Empty input yields empty component stats and `NaN` correlations.
 */
fun buildVec4SummaryStats(
    rawValues: Iterable<Vec4>,
    droppedCount: Int = 0,
    onDrop: ((droppedCount: Int) -> Unit)? = null
): Vec4SummaryStats {
    val all = rawValues.toList()
    val finite = all.filter { it.isFinite() }
    val dropped = droppedCount + (all.size - finite.size)
    if (dropped > 0) onDrop?.invoke(dropped)

    if (finite.isEmpty()) {
        val empty = SummaryStats.empty(droppedCount = dropped)
        return Vec4SummaryStats(
            x = empty,
            y = empty,
            z = empty,
            w = empty,
            length = empty,
            correlationXY = Double.NaN,
            correlationXZ = Double.NaN,
            correlationXW = Double.NaN,
            correlationYZ = Double.NaN,
            correlationYW = Double.NaN,
            correlationZW = Double.NaN
        )
    }

    val n = finite.size
    val xs = DoubleArray(n)
    val ys = DoubleArray(n)
    val zs = DoubleArray(n)
    val ws = DoubleArray(n)
    val lengths = DoubleArray(n)
    for (i in 0 until n) {
        val v = finite[i]
        xs[i] = v.x.toDouble()
        ys[i] = v.y.toDouble()
        zs[i] = v.z.toDouble()
        ws[i] = v.w.toDouble()
        lengths[i] = v.length().toDouble()
    }

    return Vec4SummaryStats(
        x = buildSummaryStats(xs.toList(), droppedCount = dropped),
        y = buildSummaryStats(ys.toList(), droppedCount = dropped),
        z = buildSummaryStats(zs.toList(), droppedCount = dropped),
        w = buildSummaryStats(ws.toList(), droppedCount = dropped),
        length = buildSummaryStats(lengths.toList(), droppedCount = dropped),
        correlationXY = correlation(xs, ys),
        correlationXZ = correlation(xs, zs),
        correlationXW = correlation(xs, ws),
        correlationYZ = correlation(ys, zs),
        correlationYW = correlation(ys, ws),
        correlationZW = correlation(zs, ws)
    )
}

/**
 * Compute histogram bins and summary statistics.
 *
 * Non-finite values are dropped (and optionally counted via [onDrop]).
 */
fun buildHistogram(
    rawValues: Iterable<Double>,
    onDrop: ((droppedCount: Int) -> Unit)? = null
): Histogram {
    val all = rawValues.toList()
    val values = all.filter { it.isFinite() }.toDoubleArray()
    val dropped = all.size - values.size
    if (dropped > 0) onDrop?.invoke(dropped)

    val n = values.size
    if (n == 0) {
        return Histogram(emptyList(), Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
    }

    values.sort()
    val min = values.first()
    val max = values.last()
    val range = max - min
    val q1 = quantileSorted(values, 0.25)
    val median = quantileSorted(values, 0.5)
    val q3 = quantileSorted(values, 0.75)

    if (range == 0.0) {
        val w = max(ulp(min) * 8.0, 1e-9)
        val b = HistogramBin(min - w / 2.0, min + w / 2.0, n)
        return Histogram(listOf(b), min, q1, median, q3, max, w)
    }

    val hFD = freedmanDiaconisWidth(values)
    val hScott = scottWidth(values)
    val kSturges = ceil(log2(n.toDouble()) + 1.0).toInt().coerceAtLeast(1)
    val hSturges = range / kSturges
    val hCandidate =
        listOf(hFD, hScott, hSturges).filter { it.isFinite() && it > 0.0 }.minOrNull() ?: range / sqrt(n.toDouble())

    val minWidth = range / 2000.0
    val h = max(hCandidate, minWidth)
    val k = ((range / h).roundToInt()).coerceIn(1, 2000)
    val width = range / k

    val starts = DoubleArray(k) { i -> min + i * width }
    val bins = MutableList(k) { i ->
        val s = starts[i]
        val e = if (i == k - 1) max else s + width
        HistogramBin(s, e, 0)
    }.toMutableList()

    for (x in values) {
        val idx = if (x == max) k - 1
        else ((x - min) / width).toInt().coerceIn(0, k - 1)
        val b = bins[idx]
        bins[idx] = b.copy(count = b.count + 1)
    }

    return Histogram(bins, min, q1, median, q3, max, width)
}

/**
 * Render a histogram to an SVG string.
 *
 * The chart includes bin bars and reference ticks at min/Q1/median/Q3/max.
 */
fun histogramToSvg(
    hist: Histogram,
    previousStats: SummaryStats? = null,
    widthPx: Int = 480,
    heightPx: Int = 240,
    padLeft: Int = 14,
    padRight: Int = 14,
    padTop: Int = 10,
    padBottom: Int = 22,
    fontFamily: String = "'Iosevka Web', Iosevka, monospace",
    title: String? = null,
    showCountsOnHover: Boolean = true,
    decimals: Int = 3,
    background: String = "#000000",
    textColor: String = "#0af",
    tickColor: String = "#0af",
    axisColor: String = "#0af",
    barFill: String = "#0af",
    barFillOpacity: Double = 0.2,
    barStroke: String = "#0af",
    barStrokeOpacity: Double = 0.5,
    barStrokeWidth: Double = 1.5,
    keyTextOpacity: Double = 0.5
): String {
    if (hist.bins.isEmpty() || !hist.min.isFinite() || !hist.max.isFinite()) {
        return """
            <svg xmlns="http://www.w3.org/2000/svg" width="$widthPx" height="$heightPx" viewBox="0 0 $widthPx $heightPx">
              <rect x="0" y="0" width="$widthPx" height="$heightPx" fill="$background"/>
              <text x="${widthPx / 2}" y="${heightPx / 2}" text-anchor="middle" dominant-baseline="middle"
                    font-family="$fontFamily" font-size="12" fill="$textColor">No data</text>
            </svg>
        """.trimIndent()
    }

    val w = widthPx.toDouble()
    val h = heightPx.toDouble()
    val innerW = (w - padLeft - padRight).coerceAtLeast(1.0)
    val innerH = (h - padTop - padBottom).coerceAtLeast(1.0)
    val x0 = padLeft.toDouble()
    val y0 = padTop.toDouble()

    val maxCount = hist.bins.maxOf { it.count }.coerceAtLeast(1)
    val barW = innerW / hist.bins.size

    fun xForVal(v: Double): Double {
        val t = if (hist.max == hist.min) 0.5 else (v - hist.min) / (hist.max - hist.min)
        return x0 + t.coerceIn(0.0, 1.0) * innerW
    }

    fun fmt(d: Double): String = formatNumber(d, decimals)

    val bars = buildString {
        hist.bins.forEachIndexed { i, b ->
            val c = b.count
            if (c <= 0) return@forEachIndexed
            val barHeight = innerH * (c.toDouble() / maxCount.toDouble())
            val x = x0 + i * barW
            val gw = max(0.0, barW - 1.0)
            val drawHeight = barHeight.coerceAtLeast(0.5)

            append(
                """<rect x="${x.toFloat()}" y="${(y0 + innerH - drawHeight).toFloat()}" width="${gw.toFloat()}" height="${drawHeight.toFloat()}" """
            )
            append("""fill="$barFill" fill-opacity="${barFillOpacity}" """)
            append("""stroke="$barStroke" stroke-opacity="$barStrokeOpacity" stroke-width="$barStrokeWidth" vector-effect="non-scaling-stroke">""")
            if (showCountsOnHover) {
                append("<title>${c} in [${fmt(b.start)}, ${fmt(b.end)}]</title>")
            }
            append("</rect>\n")
        }
    }

    fun vTick(x: Double, color: String) =
        """<line x1="${x.toFloat()}" y1="${(y0 - 3).toFloat()}" x2="${x.toFloat()}" y2="${(y0 + innerH + 3).toFloat()}" stroke="$color" stroke-width="1" stroke-dasharray="2,2" opacity="0.9"/>"""

    val xMin = xForVal(hist.min)
    val xQ1 = xForVal(hist.q1)
    val xMed = xForVal(hist.median)
    val xQ3 = xForVal(hist.q3)
    val xMax = xForVal(hist.max)

    val axisBaseline =
        """<line x1="${x0.toFloat()}" y1="${(y0 + innerH).toFloat()}" x2="${(x0 + innerW).toFloat()}" y2="${(y0 + innerH).toFloat()}" stroke="$axisColor" stroke-width="1"/>"""

    val topY = (y0 - 6).toFloat()
    val botY = (y0 + innerH + 14).toFloat()
    val botYAlt = (y0 + innerH + 30).toFloat()

    val edgeInset = 2.0
    val minLabelX = max(x0 + edgeInset, xMin)
    val maxLabelX = min(x0 + innerW - edgeInset, xMax)

    fun dimmedLabel(x: Double, y: Double, anchor: String, key: String, value: Double, color: String): String = """
        <text x="${x.toFloat()}" y="${y.toFloat()}" text-anchor="$anchor"
              font-family="$fontFamily" font-size="10" fill="$color">
          <tspan fill-opacity="$keyTextOpacity">$key </tspan><tspan>${fmt(value)}</tspan>
        </text>
    """.trimIndent()

    val minColor = deltaColor(hist.min, previousStats?.min, tickColor)
    val q1Color = deltaColor(hist.q1, previousStats?.q1, tickColor)
    val medianColor = deltaColor(hist.median, previousStats?.median, tickColor)
    val q3Color = deltaColor(hist.q3, previousStats?.q3, tickColor)
    val maxColor = deltaColor(hist.max, previousStats?.max, tickColor)

    val labelsTop = buildString {
        append(dimmedLabel(minLabelX, topY.toDouble(), "start", "min", hist.min, minColor))
        append('\n')
        append(dimmedLabel(xMed, topY.toDouble(), "middle", "median", hist.median, medianColor))
        append('\n')
        append(dimmedLabel(maxLabelX, topY.toDouble(), "end", "max", hist.max, maxColor))
    }

    val labelsBottom = buildString {
        append(dimmedLabel(xQ1, botY.toDouble(), "middle", "Q1", hist.q1, q1Color))
        append('\n')
        append(dimmedLabel(xQ3, botYAlt.toDouble()-10, "middle", "Q3", hist.q3, q3Color))
    }

    val topTitle = svgTitleBackdrop(title, widthPx, padTop, fontFamily, textColor)

    return """
        <svg xmlns="http://www.w3.org/2000/svg" width="$widthPx" height="$heightPx" viewBox="0 0 $widthPx $heightPx">
          <rect x="0" y="0" width="$widthPx" height="$heightPx" fill="$background"/>
          $axisBaseline
          $bars
          ${vTick(xMin, minColor)}
          ${vTick(xQ1, q1Color)}
          ${vTick(xMed, medianColor)}
          ${vTick(xQ3, q3Color)}
          ${vTick(xMax, maxColor)}
          $labelsTop
          $labelsBottom
          $topTitle
        </svg>
    """.trimIndent()
}

/**
 * Render a time-series plot to SVG.
 *
 * The plot shows a polyline with reference lines for min/Q1/median/Q3/max
 * and an optional zero line when the range crosses 0.
 */
fun seriesPlotToSvg(
    rawValues: Iterable<Double>,
    previousStats: SummaryStats? = null,
    widthPx: Int = 480,
    heightPx: Int = 240,
    padLeft: Int = 40,
    padRight: Int = 64,
    padTop: Int = 10,
    padBottom: Int = 22,
    fontFamily: String = "'Iosevka Web', Iosevka, monospace",
    title: String? = null,
    decimals: Int = 3,
    background: String = "#000000",
    textColor: String = "#0af",
    tickColor: String = "#0af",
    axisColor: String = "#0af",
    seriesColor: String = "#0af",
    seriesOpacity: Double = 0.85,
    seriesStrokeWidth: Double = 1.5,
    refDash: String = "3,2",
    keyTextOpacity: Double = 0.5,
    minLabelSeparationPx: Double = 12.0,
    maxSeriesPoints: Int = 500
): String {
    val values = rawValues.filter { it.isFinite() }
    if (values.isEmpty()) {
        return """
            <svg xmlns="http://www.w3.org/2000/svg" width="$widthPx" height="$heightPx" viewBox="0 0 $widthPx $heightPx">
              <rect x="0" y="0" width="$widthPx" height="$heightPx" fill="$background"/>
              <text x="${widthPx / 2}" y="${heightPx / 2}" text-anchor="middle" dominant-baseline="middle"
                    font-family="$fontFamily" font-size="12" fill="$textColor">No data</text>
            </svg>
        """.trimIndent()
    }

    val w = widthPx.toDouble()
    val h = heightPx.toDouble()
    val innerW = (w - padLeft - padRight).coerceAtLeast(1.0)
    val innerH = (h - padTop - padBottom).coerceAtLeast(1.0)
    val x0 = padLeft.toDouble()
    val y0 = padTop.toDouble()

    val sorted = values.sorted().toDoubleArray()
    val yMin0 = sorted.first()
    val yMax0 = sorted.last()
    val (yMin, yMax) = if (yMax0 == yMin0) {
        val d = max(ulp(yMin0) * 8.0, 1e-9)
        yMin0 - d / 2.0 to yMax0 + d / 2.0
    } else yMin0 to yMax0

    val q1 = quantileSorted(sorted, 0.25)
    val med = quantileSorted(sorted, 0.5)
    val q3 = quantileSorted(sorted, 0.75)

    val xMaxIndex = (values.lastIndex).coerceAtLeast(1).toDouble()

    fun xForIndex(i: Double): Double {
        return x0 + (i / xMaxIndex) * innerW
    }

    fun yForVal(v: Double): Double {
        val t = (v - yMin) / (yMax - yMin)
        return y0 + (1.0 - t) * innerH
    }

    fun fmt(d: Double) = formatNumber(d, decimals)

    data class IndexedValue(val index: Int, val value: Double)
    data class PlotPoint(val x: Double, val y: Double)

    fun envelopeDownsample(source: List<Double>, limit: Int): List<IndexedValue> {
        if (source.size <= limit || limit < 4) {
            return source.mapIndexed(::IndexedValue)
        }

        val interiorCount = source.size - 2
        val bucketCount = min((limit - 2) / 2, interiorCount)
        val sampled = ArrayList<IndexedValue>(limit)
        sampled += IndexedValue(0, source.first())

        repeat(bucketCount) { bucket ->
            val start = 1 + bucket * interiorCount / bucketCount
            val endExclusive = 1 + (bucket + 1) * interiorCount / bucketCount
            if (start >= endExclusive) return@repeat

            var minIndex = start
            var maxIndex = start
            for (index in start + 1 until endExclusive) {
                if (source[index] < source[minIndex]) minIndex = index
                if (source[index] > source[maxIndex]) maxIndex = index
            }

            if (minIndex <= maxIndex) {
                sampled += IndexedValue(minIndex, source[minIndex])
                if (maxIndex != minIndex) sampled += IndexedValue(maxIndex, source[maxIndex])
            } else {
                sampled += IndexedValue(maxIndex, source[maxIndex])
                sampled += IndexedValue(minIndex, source[minIndex])
            }
        }

        sampled += IndexedValue(source.lastIndex, source.last())
        return sampled
    }

    val points = envelopeDownsample(values, maxSeriesPoints.coerceAtLeast(4)).map { sample ->
        PlotPoint(xForIndex(sample.index.toDouble()), yForVal(sample.value))
    }
    val seriesPath = if (points.isNotEmpty()) {
        buildString {
            append("M ${points.first().x.toFloat()} ${points.first().y.toFloat()}")
            points.drop(1).forEach { p -> append(" L ${p.x.toFloat()} ${p.y.toFloat()}") }
        }
    } else ""

    val yMaxY = yForVal(yMax0)
    val yQ3 = yForVal(q3)
    val yMed = yForVal(med)
    val yQ1 = yForVal(q1)
    val yMinY = yForVal(yMin0)
    val hasZeroLine = (0.0 in yMin..yMax)
    val yZero = if (hasZeroLine) yForVal(0.0) else Double.NaN

    val axisBaseline =
        """<line x1="${x0.toFloat()}" y1="${(y0 + innerH).toFloat()}" x2="${(x0 + innerW).toFloat()}" y2="${(y0 + innerH).toFloat()}" stroke="$axisColor" stroke-width="1" opacity="0.6"/>"""

    fun hRef(y: Double, color: String, opacity: Double = 0.9, dash: String = refDash) =
        """<line x1="${x0.toFloat()}" y1="${y.toFloat()}" x2="${(x0 + innerW).toFloat()}" y2="${y.toFloat()}" stroke="$color" stroke-width="1" stroke-dasharray="$dash" opacity="$opacity"/>"""

    fun rightLabel(y: Double, key: String, value: Double?, color: String): String = """
        <text x="${(w - 6.0).toFloat()}" y="${(y + 3).toFloat()}" text-anchor="end"
              font-family="$fontFamily" font-size="10" fill="$color">
          <tspan fill-opacity="$keyTextOpacity">$key </tspan>""" + (if (value != null) """<tspan>${fmt(value)}</tspan>""" else "") + "</text>"

    fun leftLabel(y: Double, key: String?, value: Double?, color: String): String {
        val labelText = if (key == null) {
            if (value != null) "<tspan>${fmt(value)}</tspan>" else ""
        } else {
            """<tspan fill-opacity="$keyTextOpacity">$key </tspan>""" + (if (value != null) """<tspan>${fmt(value)}</tspan>""" else "")
        }
        return """
        <text x="6" y="${(y + 3).toFloat()}" text-anchor="start"
              font-family="$fontFamily" font-size="10" fill="$color">
          $labelText
        </text>
        """.trimIndent()
    }

    val minColor = deltaColor(yMin0, previousStats?.min, tickColor)
    val q1Color = deltaColor(q1, previousStats?.q1, tickColor)
    val medianColor = deltaColor(med, previousStats?.median, tickColor)
    val q3Color = deltaColor(q3, previousStats?.q3, tickColor)
    val maxColor = deltaColor(yMax0, previousStats?.max, tickColor)

    data class L(val y: Double, val key: String?, val value: Double?)

    fun layoutLabels(labels: List<L>, minSep: Double, yMinPix: Double, yMaxPix: Double): List<L> {
        if (labels.isEmpty()) return labels
        val sortedLs = labels.sortedBy { it.y }.toMutableList()
        var lastY = -1e18
        for (i in sortedLs.indices) {
            var y = sortedLs[i].y
            if (y - lastY < minSep) y = lastY + minSep
            y = y.coerceIn(yMinPix + 6.0, yMaxPix - 6.0)
            sortedLs[i] = sortedLs[i].copy(y = y)
            lastY = y
        }
        return sortedLs
    }

    val leftLabels = layoutLabels(
        buildList {
            add(L(yQ1, "Q1", q1))
            if (hasZeroLine) add(L(yZero, "0", null))
            add(L(yQ3, "Q3", q3))
        }, minLabelSeparationPx, y0, y0 + innerH
    )
    val rightLabels = layoutLabels(
        listOf(
            L(yMinY, "min", yMin0), L(yMed, "median", med), L(yMaxY, "max", yMax0)
        ), minLabelSeparationPx + 2.0, y0, y0 + innerH
    )

    val titleEl = svgTitleBackdrop(title, widthPx, padTop, fontFamily, textColor)

    return """
        <svg xmlns="http://www.w3.org/2000/svg" width="$widthPx" height="$heightPx" viewBox="0 0 $widthPx $heightPx">
          <rect x="0" y="0" width="$widthPx" height="$heightPx" fill="$background"/>
          $axisBaseline

          ${hRef(yMaxY, maxColor, opacity = 0.9)} 
          ${hRef(yQ3, q3Color, opacity = 0.85)} 
          ${hRef(yMed, medianColor, opacity = 0.95)} 
          ${hRef(yQ1, q1Color, opacity = 0.85)} 
          ${hRef(yMinY, minColor, opacity = 0.9)} 
          ${if (hasZeroLine) hRef(yZero, tickColor, opacity = 0.7, dash = "1,2") else ""}

          <path d="$seriesPath" fill="none" stroke="$seriesColor" stroke-opacity="$seriesOpacity"
                stroke-width="$seriesStrokeWidth" stroke-linecap="round" stroke-linejoin="round"
                vector-effect="non-scaling-stroke"/>

          ${leftLabels.joinToString("\n") {
            val color = when (it.key) {
                "Q1" -> q1Color
                "Q3" -> q3Color
                else -> tickColor
            }
            leftLabel(it.y, it.key, it.value, color)
          }}
          ${rightLabels.joinToString("\n") {
            val color = when (it.key) {
                "min" -> minColor
                "median" -> medianColor
                "max" -> maxColor
                else -> tickColor
            }
            rightLabel(it.y, it.key!!, it.value, color)
          }}
          $titleEl
        </svg>
    """.trimIndent()
}

fun summaryStatsBoxToHtml(
    summary: SummaryStatsBox,
    previousSummary: SummaryStatsBox? = null,
    widthPx: Int = 480,
    chartHeightPx: Int = 120,
    decimals: Int = 3,
    fontFamily: String = "'Iosevka Web', Iosevka, monospace",
    background: String = "#000000",
    textColor: String = "#0af",
    axisColor: String = "#0af",
    keyTextOpacity: Double = 0.5,
    showFrame: Boolean = false,
    showHeader: Boolean = true,
    histogramTitle: String? = "Histogram",
    seriesTitle: String? = "Series",
    chartPadLeft: Int = 28,
    chartPadRight: Int = 28,
    chartPadTop: Int = 22,
    chartPadBottom: Int = 26
): String {
    val s = summary.stats
    val p = previousSummary?.stats
    val w = widthPx.coerceAtLeast(220)
    val panelPadding = 3
    val chartW = (w - panelPadding * 2).coerceAtLeast(214)
    val chartH = chartHeightPx.coerceAtLeast(64)

    val histogramSvg = histogramToSvg(
        hist = summary.histogram,
        previousStats = p,
        widthPx = chartW,
        heightPx = chartH,
        padLeft = chartPadLeft,
        padRight = chartPadRight,
        padTop = chartPadTop,
        padBottom = chartPadBottom,
        decimals = decimals,
        title = histogramTitle,
        fontFamily = fontFamily,
        background = background,
        textColor = textColor,
        tickColor = textColor,
        axisColor = axisColor,
        barFill = textColor,
        barStroke = textColor,
        keyTextOpacity = keyTextOpacity
    )

    val seriesSvg = seriesPlotToSvg(
        rawValues = summary.values,
        previousStats = p,
        widthPx = chartW,
        heightPx = chartH,
        padLeft = chartPadLeft,
        padRight = chartPadRight,
        padTop = chartPadTop,
        padBottom = chartPadBottom,
        decimals = decimals,
        title = seriesTitle,
        fontFamily = fontFamily,
        background = background,
        textColor = textColor,
        tickColor = textColor,
        axisColor = axisColor,
        seriesColor = textColor,
        keyTextOpacity = keyTextOpacity
    )

    val kv = listOf(
        statMetric("n", s.count.toString(), deltaStyle(s.count.toLong(), p?.count?.toLong()), keyTextOpacity),
        statMetric("seen", summary.samplesSeen.toString(), deltaStyle(summary.samplesSeen, previousSummary?.samplesSeen), keyTextOpacity),
        statMetric(
            "dropped(non-finite)",
            summary.droppedNonFinite.toString(),
            deltaStyle(summary.droppedNonFinite, previousSummary?.droppedNonFinite),
            keyTextOpacity
        ),
        statMetric(
            "window-evicted",
            summary.droppedByCapacity.toString(),
            deltaStyle(summary.droppedByCapacity, previousSummary?.droppedByCapacity),
            keyTextOpacity
        ),
        statMetric("range", formatNumber(s.range, decimals), deltaStyle(s.range, p?.range), keyTextOpacity),
        statMetric("mean", formatNumber(s.mean, decimals), deltaStyle(s.mean, p?.mean), keyTextOpacity),
        statMetric("stddev", formatNumber(s.sampleStdDev, decimals), deltaStyle(s.sampleStdDev, p?.sampleStdDev), keyTextOpacity),
        statMetric("variance", formatNumber(s.sampleVariance, decimals), deltaStyle(s.sampleVariance, p?.sampleVariance), keyTextOpacity),
        statMetric("IQR", formatNumber(s.iqr, decimals), deltaStyle(s.iqr, p?.iqr), keyTextOpacity),
        statMetric("MAD", formatNumber(s.mad, decimals), deltaStyle(s.mad, p?.mad), keyTextOpacity),
        statMetric("skew", formatNumber(s.skewness, decimals), deltaStyle(s.skewness, p?.skewness), keyTextOpacity),
        statMetric(
            "kurtosis(ex)",
            formatNumber(s.kurtosisExcess, decimals),
            deltaStyle(s.kurtosisExcess, p?.kurtosisExcess),
            keyTextOpacity
        ),
        statMetric(
            "cv",
            formatNumber(s.coeffOfVariation, decimals),
            deltaStyle(s.coeffOfVariation, p?.coeffOfVariation),
            keyTextOpacity
        )
    )
    val metricsHtml = kv.joinToString("")

    val borderStyle = if (showFrame) "border:1px solid ${axisColor};" else ""
    val headerHtml = if (showHeader) {
        """
            <div style="display:flex;justify-content:space-between;align-items:baseline;margin-bottom:6px;">
              <span style="font-size:12px;">Summary stats</span>
              <span style="opacity:${keyTextOpacity};">window=${summary.windowSize}</span>
            </div>
        """.trimIndent()
    } else ""

    return """
        <div style="
            width:${w}px;
            box-sizing:border-box;
            ${borderStyle}
            padding:${panelPadding}px;
            background:${background};
            color:${textColor};
            font-family:${fontFamily};
            font-size:11px;
            line-height:1.25;
        ">
            $headerHtml
            <div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:4px 18px;margin-bottom:8px;align-items:baseline;">
              $metricsHtml
            </div>
            <div style="display:flex;flex-direction:column;gap:10px;">
              $histogramSvg
              $seriesSvg
            </div>
        </div>
    """.trimIndent()
}


private fun correlation(a: DoubleArray, b: DoubleArray): Double {
    if (a.size != b.size || a.size < 2) return Double.NaN

    val n = a.size
    val meanA = a.average()
    val meanB = b.average()

    var covSum = 0.0
    var varASum = 0.0
    var varBSum = 0.0
    for (i in 0 until n) {
        val da = a[i] - meanA
        val db = b[i] - meanB
        covSum += da * db
        varASum += da * da
        varBSum += db * db
    }

    val denom = kotlin.math.sqrt(varASum * varBSum)
    if (denom <= 0.0 || !denom.isFinite()) return Double.NaN
    return covSum / denom
}

private fun clampLongToInt(v: Long): Int =
    if (v > Int.MAX_VALUE.toLong()) Int.MAX_VALUE else if (v < Int.MIN_VALUE.toLong()) Int.MIN_VALUE else v.toInt()

/**
 * Freedman–Diaconis rule of thumb for histogram bin width.
 */
private fun freedmanDiaconisWidth(sorted: DoubleArray): Double {
    val n = sorted.size
    if (n < 2) return Double.NaN
    val q1 = quantileSorted(sorted, 0.25)
    val q3 = quantileSorted(sorted, 0.75)
    val iqr = q3 - q1
    if (!(iqr > 0.0)) return Double.NaN
    return 2.0 * iqr * n.toDouble().pow(-1.0 / 3.0)
}

/**
 * Scott's rule of thumb for histogram bin width.
 */
private fun scottWidth(sorted: DoubleArray): Double {
    val n = sorted.size
    if (n < 2) return Double.NaN
    val mean = sorted.average()
    val varSum = sorted.fold(0.0) { acc, v -> acc + (v - mean) * (v - mean) }
    val sigma = sqrt(varSum / (n - 1))
    if (!(sigma > 0.0)) return Double.NaN
    return 3.5 * sigma * n.toDouble().pow(-1.0 / 3.0)
}

/** Linear interpolation of the empirical quantile for sorted data. */
private fun quantileSorted(sorted: DoubleArray, p: Double): Double {
    require(p in 0.0..1.0) { "p must be in [0,1]" }
    val n = sorted.size
    if (n == 0) return Double.NaN
    if (n == 1) return sorted[0]
    val x = (n - 1) * p
    val i = floor(x).toInt()
    val frac = x - i
    return if (i >= n - 1) sorted[n - 1] else sorted[i] * (1 - frac) + sorted[i + 1] * frac
}

/**
 * Format a number for chart labels.
 */
private fun formatNumber(v: Double, decimals: Int): String {
    if (!v.isFinite()) return v.toString()
    val abs = abs(v)
    return when {
        abs >= 1e6 || (abs != 0.0 && abs < 1e-3) -> "%.${decimals}e".format(v)
        else -> "%.${decimals}f".format(v)
    }
}
