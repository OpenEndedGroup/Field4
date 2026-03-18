@file:Suppress("MemberVisibilityCanBePrivate")

package afield.stdlib

import java.lang.Math.ulp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
        get() = values.size

    fun setWindow(size: Int): SummaryStatsBuffer {
        maxSize = size
        return this
    }

    fun clear(resetCounters: Boolean = false): SummaryStatsBuffer {
        values.clear()
        if (resetCounters) {
            samplesSeen = 0L
            droppedNonFinite = 0L
            droppedByCapacity = 0L
        }
        return this
    }

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

    fun values(): List<Double> = values.toList()

    fun snapshot(): SummaryStats =
        buildSummaryStats(values, droppedCount = clampLongToInt(droppedNonFinite))

    fun histogram(): Histogram = buildHistogram(values)

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
        widthPx: Int = 480,
        heightPx: Int = 240,
        padLeft: Int = 14,
        padRight: Int = 14,
        padTop: Int = 10,
        padBottom: Int = 22,
        fontFamily: String = "system-ui, -apple-system, Segoe UI, Roboto, sans-serif",
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
        widthPx: Int = 480,
        heightPx: Int = 240,
        padLeft: Int = 40,
        padRight: Int = 64,
        padTop: Int = 10,
        padBottom: Int = 22,
        fontFamily: String = "system-ui, -apple-system, Segoe UI, Roboto, sans-serif",
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
        rawValues = values,
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
        fontFamily: String = "system-ui, -apple-system, Segoe UI, Roboto, sans-serif",
        background: String = "#000000",
        textColor: String = "#0af",
        axisColor: String = "#0af",
        keyTextOpacity: Double = 0.5
    ): String = summaryStatsBoxToHtml(
        summary = summaryBox(),
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
        get() = values.size

    fun setWindow(size: Int): Vec2SummaryStatsBuffer {
        maxSize = size
        return this
    }

    fun clear(resetCounters: Boolean = false): Vec2SummaryStatsBuffer {
        values.clear()
        if (resetCounters) {
            samplesSeen = 0L
            droppedNonFinite = 0L
            droppedByCapacity = 0L
        }
        return this
    }

    fun add(value: Vec2?): Vec2SummaryStatsBuffer {
        samplesSeen++
        val v = value?.let(::Vec2)
        if (v == null || !v.isFiniteVec2()) {
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

    fun values(): List<Vec2> = values.map(::Vec2)

    fun snapshot(): Vec2SummaryStats =
        buildVec2SummaryStats(values, droppedCount = clampLongToInt(droppedNonFinite))

    fun summaryBox(): Vec2SummaryStatsBox {
        val current = values()
        return Vec2SummaryStatsBox(
            values = current,
            stats = buildVec2SummaryStats(current, droppedCount = clampLongToInt(droppedNonFinite)),
            windowSize = maxSize,
            samplesSeen = samplesSeen,
            droppedNonFinite = droppedNonFinite,
            droppedByCapacity = droppedByCapacity
        )
    }

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
        get() = values.size

    fun setWindow(size: Int): Vec3SummaryStatsBuffer {
        maxSize = size
        return this
    }

    fun clear(resetCounters: Boolean = false): Vec3SummaryStatsBuffer {
        values.clear()
        if (resetCounters) {
            samplesSeen = 0L
            droppedNonFinite = 0L
            droppedByCapacity = 0L
        }
        return this
    }

    fun add(value: Vec3?): Vec3SummaryStatsBuffer {
        samplesSeen++
        val v = value?.let(::Vec3)
        if (v == null || !v.isFiniteVec3()) {
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

    fun values(): List<Vec3> = values.map(::Vec3)

    fun snapshot(): Vec3SummaryStats =
        buildVec3SummaryStats(values, droppedCount = clampLongToInt(droppedNonFinite))

    fun summaryBox(): Vec3SummaryStatsBox {
        val current = values()
        return Vec3SummaryStatsBox(
            values = current,
            stats = buildVec3SummaryStats(current, droppedCount = clampLongToInt(droppedNonFinite)),
            windowSize = maxSize,
            samplesSeen = samplesSeen,
            droppedNonFinite = droppedNonFinite,
            droppedByCapacity = droppedByCapacity
        )
    }

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
        get() = values.size

    fun setWindow(size: Int): Vec4SummaryStatsBuffer {
        maxSize = size
        return this
    }

    fun clear(resetCounters: Boolean = false): Vec4SummaryStatsBuffer {
        values.clear()
        if (resetCounters) {
            samplesSeen = 0L
            droppedNonFinite = 0L
            droppedByCapacity = 0L
        }
        return this
    }

    fun add(value: Vec4?): Vec4SummaryStatsBuffer {
        samplesSeen++
        val v = value?.let(::Vec4)
        if (v == null || !v.isFiniteVec4()) {
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

    fun values(): List<Vec4> = values.map(::Vec4)

    fun snapshot(): Vec4SummaryStats =
        buildVec4SummaryStats(values, droppedCount = clampLongToInt(droppedNonFinite))

    fun summaryBox(): Vec4SummaryStatsBox {
        val current = values()
        return Vec4SummaryStatsBox(
            values = current,
            stats = buildVec4SummaryStats(current, droppedCount = clampLongToInt(droppedNonFinite)),
            windowSize = maxSize,
            samplesSeen = samplesSeen,
            droppedNonFinite = droppedNonFinite,
            droppedByCapacity = droppedByCapacity
        )
    }

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

fun buildVec2SummaryStats(
    rawValues: Iterable<Vec2>,
    droppedCount: Int = 0,
    onDrop: ((droppedCount: Int) -> Unit)? = null
): Vec2SummaryStats {
    val all = rawValues.toList()
    val finite = all.filter { it.isFiniteVec2() }
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

fun buildVec3SummaryStats(
    rawValues: Iterable<Vec3>,
    droppedCount: Int = 0,
    onDrop: ((droppedCount: Int) -> Unit)? = null
): Vec3SummaryStats {
    val all = rawValues.toList()
    val finite = all.filter { it.isFiniteVec3() }
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

fun buildVec4SummaryStats(
    rawValues: Iterable<Vec4>,
    droppedCount: Int = 0,
    onDrop: ((droppedCount: Int) -> Unit)? = null
): Vec4SummaryStats {
    val all = rawValues.toList()
    val finite = all.filter { it.isFiniteVec4() }
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
    widthPx: Int = 480,
    heightPx: Int = 240,
    padLeft: Int = 14,
    padRight: Int = 14,
    padTop: Int = 10,
    padBottom: Int = 22,
    fontFamily: String = "system-ui, -apple-system, Segoe UI, Roboto, sans-serif",
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

    fun vTick(x: Double) =
        """<line x1="${x.toFloat()}" y1="${(y0 - 3).toFloat()}" x2="${x.toFloat()}" y2="${(y0 + innerH + 3).toFloat()}" stroke="$tickColor" stroke-width="1" stroke-dasharray="2,2" opacity="0.9"/>"""

    val xMin = xForVal(hist.min)
    val xQ1 = xForVal(hist.q1)
    val xMed = xForVal(hist.median)
    val xQ3 = xForVal(hist.q3)
    val xMax = xForVal(hist.max)

    val axisBaseline =
        """<line x1="${x0.toFloat()}" y1="${(y0 + innerH).toFloat()}" x2="${(x0 + innerW).toFloat()}" y2="${(y0 + innerH).toFloat()}" stroke="$axisColor" stroke-width="1"/>"""

    val topY = (y0 - 6).toFloat()
    val botY = (y0 + innerH + 14).toFloat()

    val edgeInset = 2.0
    val minLabelX = max(x0 + edgeInset, xMin)
    val maxLabelX = min(x0 + innerW - edgeInset, xMax)

    fun dimmedLabel(x: Double, y: Double, anchor: String, key: String, value: Double): String = """
        <text x="${x.toFloat()}" y="${y.toFloat()}" text-anchor="$anchor"
              font-family="$fontFamily" font-size="10" fill="$textColor">
          <tspan fill-opacity="$keyTextOpacity">$key </tspan><tspan>${fmt(value)}</tspan>
        </text>
    """.trimIndent()

    val labelsTop = buildString {
        append(dimmedLabel(minLabelX, topY.toDouble(), "start", "min", hist.min))
        append('\n')
        append(dimmedLabel(xMed, topY.toDouble(), "middle", "median", hist.median))
        append('\n')
        append(dimmedLabel(maxLabelX, topY.toDouble(), "end", "max", hist.max))
    }

    val labelsBottom = buildString {
        append(dimmedLabel(xQ1, botY.toDouble(), "middle", "Q1", hist.q1))
        append('\n')
        append(dimmedLabel(xQ3, botY.toDouble(), "middle", "Q3", hist.q3))
    }

    val topTitle = title?.let {
        """<text x="${(w / 2).toFloat()}" y="${(padTop * 0.9).toFloat()}" text-anchor="middle" font-family="$fontFamily" font-size="12" fill="$textColor">$it</text>"""
    } ?: ""

    return """
        <svg xmlns="http://www.w3.org/2000/svg" width="$widthPx" height="$heightPx" viewBox="0 0 $widthPx $heightPx">
          <rect x="0" y="0" width="$widthPx" height="$heightPx" fill="$background"/>
          $topTitle
          $axisBaseline
          $bars
          ${vTick(xMin)}
          ${vTick(xQ1)}
          ${vTick(xMed)}
          ${vTick(xQ3)}
          ${vTick(xMax)}
          $labelsTop
          $labelsBottom
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
    widthPx: Int = 480,
    heightPx: Int = 240,
    padLeft: Int = 40,
    padRight: Int = 64,
    padTop: Int = 10,
    padBottom: Int = 22,
    fontFamily: String = "system-ui, -apple-system, Segoe UI, Roboto, sans-serif",
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
): String {
    val source = rawValues.toList()
    val values = mutableListOf<Double>()
    val indices = mutableListOf<Int>()
    for (i in source.indices) {
        val v = source[i]
        if (v.isFinite()) {
            indices += i
            values += v
        }
    }
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

    val sorted = values.toDoubleArray().also { it.sort() }
    val yMin0 = sorted.first()
    val yMax0 = sorted.last()
    val (yMin, yMax) = if (yMax0 == yMin0) {
        val d = max(ulp(yMin0) * 8.0, 1e-9)
        yMin0 - d / 2.0 to yMax0 + d / 2.0
    } else yMin0 to yMax0

    val q1 = quantileSorted(sorted, 0.25)
    val med = quantileSorted(sorted, 0.5)
    val q3 = quantileSorted(sorted, 0.75)

    val xMinIndex = indices.first().toDouble()
    val xMaxIndex = indices.last().toDouble()

    fun xForIndex(i: Double): Double {
        val span = (xMaxIndex - xMinIndex).takeIf { it != 0.0 } ?: 1.0
        return x0 + ((i - xMinIndex) / span) * innerW
    }

    fun yForVal(v: Double): Double {
        val t = (v - yMin) / (yMax - yMin)
        return y0 + (1.0 - t) * innerH
    }

    fun fmt(d: Double) = formatNumber(d, decimals)

    val path = buildString {
        values.forEachIndexed { j, v ->
            val xi = indices[j].toDouble()
            val x = xForIndex(xi)
            val y = yForVal(v)
            if (j == 0) append("M ${x.toFloat()} ${y.toFloat()}") else append(" L ${x.toFloat()} ${y.toFloat()}")
        }
    }

    val yMaxY = yForVal(yMax0)
    val yQ3 = yForVal(q3)
    val yMed = yForVal(med)
    val yQ1 = yForVal(q1)
    val yMinY = yForVal(yMin0)
    val hasZeroLine = (0.0 in yMin..yMax)
    val yZero = if (hasZeroLine) yForVal(0.0) else Double.NaN

    val axisBaseline =
        """<line x1="${x0.toFloat()}" y1="${(y0 + innerH).toFloat()}" x2="${(x0 + innerW).toFloat()}" y2="${(y0 + innerH).toFloat()}" stroke="$axisColor" stroke-width="1" opacity="0.6"/>"""

    fun hRef(y: Double, opacity: Double = 0.9, dash: String = refDash) =
        """<line x1="${x0.toFloat()}" y1="${y.toFloat()}" x2="${(x0 + innerW).toFloat()}" y2="${y.toFloat()}" stroke="$tickColor" stroke-width="1" stroke-dasharray="$dash" opacity="$opacity"/>"""

    fun rightLabel(y: Double, key: String, value: Double?): String = """
        <text x="${(x0 + innerW + 4).toFloat()}" y="${(y + 3).toFloat()}" text-anchor="start"
              font-family="$fontFamily" font-size="10" fill="$textColor">
          <tspan fill-opacity="$keyTextOpacity">$key </tspan>""" + (if (value != null) """<tspan>${fmt(value)}</tspan>""" else "") + "</text>"

    fun leftLabel(y: Double, key: String?, value: Double?): String {
        val labelText = if (key == null) {
            if (value != null) "<tspan>${fmt(value)}</tspan>" else ""
        } else {
            """<tspan fill-opacity="$keyTextOpacity">$key </tspan>""" + (if (value != null) """<tspan>${fmt(value)}</tspan>""" else "")
        }
        return """
        <text x="${(x0 - 4).toFloat()}" y="${(y + 3).toFloat()}" text-anchor="end"
              font-family="$fontFamily" font-size="10" fill="$textColor">
          $labelText
        </text>
        """.trimIndent()
    }

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
        ), minLabelSeparationPx, y0, y0 + innerH
    )

    val titleEl = title?.let {
        """<text x="${(w / 2).toFloat()}" y="${(padTop * 0.9).toFloat()}" text-anchor="middle" font-family="$fontFamily" font-size="12" fill="$textColor">$it</text>"""
    } ?: ""

    return """
        <svg xmlns="http://www.w3.org/2000/svg" width="$widthPx" height="$heightPx" viewBox="0 0 $widthPx $heightPx">
          <rect x="0" y="0" width="$widthPx" height="$heightPx" fill="$background"/>
          $titleEl
          $axisBaseline

          ${hRef(yMaxY, opacity = 0.9)} 
          ${hRef(yQ3, opacity = 0.85)} 
          ${hRef(yMed, opacity = 0.95)} 
          ${hRef(yQ1, opacity = 0.85)} 
          ${hRef(yMinY, opacity = 0.9)} 
          ${if (hasZeroLine) hRef(yZero, opacity = 0.7, dash = "1,2") else ""}

          <path d="$path"
                fill="none"
                stroke="$seriesColor"
                stroke-opacity="$seriesOpacity"
                stroke-width="$seriesStrokeWidth"
                vector-effect="non-scaling-stroke"/>

          ${leftLabels.joinToString("\n") { leftLabel(it.y, it.key, it.value) }}
          ${rightLabels.joinToString("\n") { rightLabel(it.y, it.key!!, it.value) }}
        </svg>
    """.trimIndent()
}

fun summaryStatsBoxToHtml(
    summary: SummaryStatsBox,
    widthPx: Int = 480,
    chartHeightPx: Int = 120,
    decimals: Int = 3,
    fontFamily: String = "system-ui, -apple-system, Segoe UI, Roboto, sans-serif",
    background: String = "#000000",
    textColor: String = "#0af",
    axisColor: String = "#0af",
    keyTextOpacity: Double = 0.5
): String {
    val s = summary.stats
    val w = widthPx.coerceAtLeast(220)
    val chartH = chartHeightPx.coerceAtLeast(64)

    val histogramSvg = histogramToSvg(
        hist = summary.histogram,
        widthPx = w,
        heightPx = chartH,
        padTop = 20,
        padBottom = 16,
        decimals = decimals,
        title = "Histogram",
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
        widthPx = w,
        heightPx = chartH,
        padTop = 20,
        padBottom = 16,
        decimals = decimals,
        title = "Series",
        background = background,
        textColor = textColor,
        tickColor = textColor,
        axisColor = axisColor,
        seriesColor = textColor,
        keyTextOpacity = keyTextOpacity
    )

    val kv = listOf(
        "n" to s.count.toString(),
        "seen" to summary.samplesSeen.toString(),
        "dropped(non-finite)" to summary.droppedNonFinite.toString(),
        "window-evicted" to summary.droppedByCapacity.toString(),
        "min" to formatNumber(s.min, decimals),
        "Q1" to formatNumber(s.q1, decimals),
        "median" to formatNumber(s.median, decimals),
        "Q3" to formatNumber(s.q3, decimals),
        "max" to formatNumber(s.max, decimals),
        "range" to formatNumber(s.range, decimals),
        "mean" to formatNumber(s.mean, decimals),
        "stddev" to formatNumber(s.sampleStdDev, decimals),
        "variance" to formatNumber(s.sampleVariance, decimals),
        "IQR" to formatNumber(s.iqr, decimals),
        "MAD" to formatNumber(s.mad, decimals),
        "skew" to formatNumber(s.skewness, decimals),
        "kurtosis(ex)" to formatNumber(s.kurtosisExcess, decimals),
        "cv" to formatNumber(s.coeffOfVariation, decimals)
    )

    val metricsHtml = kv.joinToString("") { (k, v) ->
        """
            <div style="display:flex;gap:4px;white-space:nowrap;">
              <span style="opacity:${keyTextOpacity};">$k</span>
              <span>$v</span>
            </div>
        """.trimIndent()
    }

    return """
        <div style="
            width:${w}px;
            box-sizing:border-box;
            border:1px solid ${axisColor};
            padding:8px;
            background:${background};
            color:${textColor};
            font-family:${fontFamily};
            font-size:11px;
            line-height:1.25;
        ">
            <div style="display:flex;justify-content:space-between;align-items:baseline;margin-bottom:6px;">
              <span style="font-size:12px;">Summary stats</span>
              <span style="opacity:${keyTextOpacity};">window=${summary.windowSize}</span>
            </div>
            <div style="display:flex;flex-wrap:wrap;gap:4px 12px;margin-bottom:8px;">
              $metricsHtml
            </div>
            <div style="display:flex;flex-direction:column;gap:6px;">
              $histogramSvg
              $seriesSvg
            </div>
        </div>
    """.trimIndent()
}

private fun Vec2.isFiniteVec2(): Boolean = x.isFinite() && y.isFinite()
private fun Vec3.isFiniteVec3(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
private fun Vec4.isFiniteVec4(): Boolean = x.isFinite() && y.isFinite() && z.isFinite() && w.isFinite()

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
