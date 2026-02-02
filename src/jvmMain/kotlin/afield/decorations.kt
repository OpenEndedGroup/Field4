@file:Suppress("MemberVisibilityCanBePrivate", "ReplaceJavaStaticMethodWithKotlinAnalog")

package afield

import afield.logger.lprintln
import afield.stdlib.Delegation
import afield.stdlib.Marker
import kotlinx.coroutines.yield
import java.lang.Math.ulp
import java.util.*
import kotlin.math.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid


data class RawDecoration(
    val lineStart: Int,
    val colStart: Int,
    val lineEnd: Int,
    val colEnd: Int,
    val classname: String,
    val classDef: Map<String, String> = emptyMap(),
    val clickCallback: (() -> Unit)? = null
) {
    constructor(
        l: Location, className: String, classDef: Map<String, String> = emptyMap(), clickCallback: (() -> Unit)? = null
    ) : this(
        l.line, l.col, l.line, l.col + 1, className, classDef, clickCallback
    )
}

var Box.code_decorationState by Delegation<String>()
var Box.code_decorationCallbackTable by Delegation<MutableMap<String, () -> Unit>>(defaultValue = { HashMap() })
val Box.decorationState by Delegation<MutableMap<String, RawDecoration>>(
    defaultValue = { mutableMapOf() },
    onGet = { inside, v ->
        inMain {
            yield()
            setDecorations(inside as Box, v.values.toList())
        }
    })

/*
need to gather this from a map and some fancy delegation tricks
 */
@OptIn(ExperimentalUuidApi::class)
fun setDecorations(box: Box, decor: List<RawDecoration>) {

    box.code_decorationCallbackTable.clear()

    val defs = "\"definition\":\"" + decor.flatMap { it.classDef.entries }
        .associateBy { (key, value) -> key }.values.joinToString(separator = " ") { it.value } + "\""

    val callbacks = "\"callbacks\":{" + decor.mapNotNull {
        if (it.clickCallback != null) {
            val uid = Uuid.random().toString()
            box.code_decorationCallbackTable.put(uid, it.clickCallback)
            actionTable[uid] = { selected ->
                it.clickCallback()
            }
            it.classname to uid
        } else null
    }.joinToString(separator = ",") { "\"${it.first}\":\"${it.second}\"" } + "}"

    val inline =
        "\"inline\":[" + decor.joinToString(separator = ",") { "[${it.lineStart},${it.colStart},${it.lineEnd},${it.colEnd},\"${it.classname}\"]" } + "]"
    val r = "{${defs}, ${callbacks}, ${inline}}"


    val message = Message("set-string").payload(Set_string(box.id, "code_decorationState", r))
    box.inside.sessions.forEach { _send(it, message) }

    box.code_decorationState = r
}

data class Location(val inside: Box, val property: String, val line: Int, val col: Int)

fun <K, V> MutableMap<K, in V>.update(key: K, d: V, u: (V) -> V): V {
    val v = u(computeIfAbsent(key) { d } as V)
    put(key, v)
    return v
}

fun Marker.toggle(cb: () -> Unit) {
    val location = locationForTextStringOnStack(".toggle(", this.box, end = true, offset = 1)
    if (location != null) this.box.decorationState.put(
        this.name + "toggle", RawDecoration(
            location, "toggle${this.name}", mapOf(
                "toggle${this.name}" to """
                .toggle${this.name} { border-bottom: 0px solid white; }
                .toggle${this.name}:before {content:'X'; color:white;position:relative; left:-0px; font-size:100%}
                .toggle${this.name}:hover::before {content:'X'; color:red;position:relative; left:-0px; font-size:100%}""".replace(
                    "\n", " "
                )
            ), clickCallback = {
                lprintln("info,decorations", "clicked ${this.name}")
                cb()
            })
    )
}

fun Marker.beep(
    tick: Int = properties.update("beep", 0) { it + 1 }
) {
    val location = locationForTextStringOnStack(".beep(", this.box, end = true, offset = 1)
    val color = if (tick % 2 == 0) "white" else "white"
    val glyph = if (tick % 2 == 0) "🞊" else "🞅"
    val q = tick % 2
    lprintln("debug,decorations", "$location -> ${q + 1}")
    if (location != null) this.box.decorationState.put(
        this.name + "_beep",
        RawDecoration(
            location,
            "d$q",
            mapOf("d$q" to ".d$q { border-bottom: 0px solid ${color};} .d${q}:before {content:'${glyph}'; color:${color};position:relative; left:-0px; font-size:100%}")
        ),
    )
}

class BoppableString(val s: String, val inside: Marker) : () -> String {
    fun bop(index: Int, color: String = "white", filled: Boolean = true) {
        val location = locationForTextStringOnStack(".bop(", inside.box, end = true, offset = 1)
        val glyph = if (filled) "🞊" else "🞅"
        val className = "d${index}_${inside.name}"
        if (location != null) inside.box.decorationState.put(
            inside.name + "_beep",
            RawDecoration(
                location,
                className,
                mapOf(className to ".${className} { border-bottom: 0px solid ${color};} .${className}:before {content:'${glyph}'; color:${color};position:relative; left:${(index + 2) * 7.5 + 4}px; top:-12px; font-size:100%}")
            ),
        )
    }

    override fun invoke(): String {
        return s
    }
}

fun Marker.bop(
    text: String
): BoppableString {
    return BoppableString(text, this)
}


class LittleHistory {

    var d = LinkedList<Double>()

    var outputRateLimiter = RateLimiter(10.0, 0.2)
    fun add(v: Double, size: Int = 100) {
        d.add(v)
        if (d.size > size) {
            d.removeAt(0)
        }
    }

    fun histogram(): String {
        var dropped = 0
        return histogramToSvg(
            buildHistogram(d, onDrop = { dropped += it }),
            widthPx = 480,
            heightPx = 100,
            padTop = 20,
            padBottom = 15,
            decimals = 2
        )
    }

    fun history(): String {
        return seriesPlotToSvg(
            d, widthPx = 480, heightPx = 100, padTop = 20, padLeft = 50, padRight = 80, padBottom = 15, decimals = 2
        )
    }
}


fun <T> Marker.histogram(value: T, dur: Int = 100): T {
    val location = locationForTextStringOnStack("histogram", this.box, end = true, offset = 1)
    val h = this.properties.computeIfAbsent("__histogram__") {
        LittleHistory()
    }
    (h as LittleHistory).add(value as Double, size = dur)
    h.outputRateLimiter {
        this.box._printHere(location!!.line, h.histogram())
    }
    return value
}

fun <T> Marker.history(value: T, dur: Int = 100): T {
    val location = locationForTextStringOnStack("history", this.box, end = true, offset = 1)
    val h = this.properties.computeIfAbsent("__histogram__") { // intentional
        LittleHistory()
    }
    (h as LittleHistory).add(value as Double, size = dur)
    h.outputRateLimiter {
        this.box._printHere(location!!.line, h.history())
    }
    return value
}


fun locationForTextStringOnStack(
    code: String,
    box: Box? = null,
    st: Array<StackTraceElement> = Exception().stackTrace,
    end: Boolean = false,
    offset: Int = 0
): Location? {
    val found = st.filter {
        val inside = if (box != null) (it.fileName?.contains(box.id) ?: false) else false
        if (box != null && inside) {
            true
        } else {
            false
        }
    }
    return found.firstNotNullOf {
        val ln = it.lineNumber
        val lines = box?.code?.split("\n")
        val index = lines?.drop(ln - 1)?.indexOfFirst { it.contains(code) }
        if (index != null && index != -1) {
            Location(
                box,
                "code",
                index + (ln - 1) + 1,
                lines[index + ln - 1].indexOf(code) + (if (end) code.length else 0) + offset
            )
        } else null
    }
}


// ---------- Public API ----------


data class HistogramBin(val start: Double, val end: Double, val count: Int)
data class Histogram(
    val bins: List<HistogramBin>,
    val min: Double,
    val q1: Double,
    val median: Double,
    val q3: Double,
    val max: Double,
    val binWidth: Double
)

fun buildHistogram(
    rawValues: List<Double>, onDrop: ((droppedCount: Int) -> Unit)? = null
): Histogram {
    val values = rawValues.filter { it.isFinite() }.toDoubleArray()
    val dropped = rawValues.size - values.size
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
    // Dark theme colors and bar styles
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
            val barHeight = innerH * (c.toDouble() / maxCount.toDouble())
            val x = x0 + i * barW
            val y = y0 + (innerH - barHeight)
            val gw = max(0.0, barW - 1.0) // small gap

            append(
                """<rect x="${x.toFloat()}" y="${y.toFloat()}" width="${gw.toFloat()}" height="${barHeight.toFloat() / 1000}" """
            )
            append("""fill="$barFill" fill-opacity="${barFillOpacity}" """)
            append("""stroke="$barStroke" stroke-opacity="$barStrokeOpacity" stroke-width="$barStrokeWidth">""")
            if (showCountsOnHover) {
                append("<title>${c} in [${fmt(b.start)}, ${fmt(b.end)}]</title>")
            }
            append("</rect>\n")

            append(
                """<rect x="${x.toFloat()}" y="${y.toFloat()}" width="${gw.toFloat()}" height="${barHeight.toFloat()}" """
            )
            append("""fill="$barFill" fill-opacity="${barFillOpacity}" """)
//            append("""stroke="$barStroke" stroke-opacity="$barStrokeOpacity" stroke-width="$barStrokeWidth">""")
            if (showCountsOnHover) {
                append("<title>${c} in [${fmt(b.start)}, ${fmt(b.end)}]</title>")
            }
            append("</rect>\n")
        }
    }

    // Vertical ticks (dashed) spanning plot area
    fun vTick(x: Double) =
        """<line x1="${x.toFloat()}" y1="${(y0 - 3).toFloat()}" x2="${x.toFloat()}" y2="${(y0 + innerH + 3).toFloat()}" stroke="$tickColor" stroke-width="1" stroke-dasharray="2,2" opacity="0.9"/>"""

    val xMin = xForVal(hist.min)
    val xQ1 = xForVal(hist.q1)
    val xMed = xForVal(hist.median)
    val xQ3 = xForVal(hist.q3)
    val xMax = xForVal(hist.max)

    val axisBaseline =
        """<line x1="${x0.toFloat()}" y1="${(y0 + innerH).toFloat()}" x2="${(x0 + innerW).toFloat()}" y2="${(y0 + innerH).toFloat()}" stroke="$axisColor" stroke-width="1"/>"""

    // Labels: min/median/max on top; Q1/Q3 below.
    val topY = (y0 - 6).toFloat()
    val botY = (y0 + innerH + 14).toFloat()

    // Avoid clipping: min left-anchored slightly inside; max right-anchored slightly inside
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

// ---------- Internals ----------

private fun freedmanDiaconisWidth(sorted: DoubleArray): Double {
    val n = sorted.size
    if (n < 2) return Double.NaN
    val q1 = quantileSorted(sorted, 0.25)
    val q3 = quantileSorted(sorted, 0.75)
    val iqr = q3 - q1
    if (!(iqr > 0.0)) return Double.NaN
    return 2.0 * iqr * n.toDouble().pow(-1.0 / 3.0)
}

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

private fun formatNumber(v: Double, decimals: Int): String {
    if (!v.isFinite()) return v.toString()
    val abs = kotlin.math.abs(v)
    return when {
        abs >= 1e6 || (abs != 0.0 && abs < 1e-3) -> "%.${decimals}e".format(v)

        else -> "%.${decimals}f".format(v)
    }
}


fun seriesPlotToSvg(
    rawValues: List<Double>,
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
    // --- data filter ---
    val values = mutableListOf<Double>()
    val indices = mutableListOf<Int>()
    for (i in rawValues.indices) {
        val v = rawValues[i]
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

    // Y range and quantiles
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

    // series polyline
    val path = buildString {
        values.forEachIndexed { j, v ->
            val xi = indices[j].toDouble()
            val x = xForIndex(xi)
            val y = yForVal(v)
            if (j == 0) append("M ${x.toFloat()} ${y.toFloat()}") else append(" L ${x.toFloat()} ${y.toFloat()}")
        }
    }

    // positions
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

    // label renderers
    fun rightLabel(y: Double, key: String, value: Double?): String = """
        <text x="${(x0 + innerW + 4).toFloat()}" y="${(y + 3).toFloat()}" text-anchor="start"
              font-family="$fontFamily" font-size="10" fill="$textColor">
          <tspan fill-opacity="$keyTextOpacity">$key </tspan>""" + (if (value != null) """<tspan>${fmt(value)}</tspan>""" else "") + "</text>"


    fun leftLabel(y: Double, key: String?, value: Double?): String {
        val labelText = if (key == null) {
            // just the value, full opacity
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

          <!-- Reference lines -->
          ${hRef(yMaxY, opacity = 0.9)} 
          ${hRef(yQ3, opacity = 0.85)} 
          ${hRef(yMed, opacity = 0.95)} 
          ${hRef(yQ1, opacity = 0.85)} 
          ${hRef(yMinY, opacity = 0.9)} 
          ${if (hasZeroLine) hRef(yZero, opacity = 0.7, dash = "1,2") else ""}

          <!-- Series -->
          <path d="$path"
                fill="none"
                stroke="$seriesColor"
                stroke-opacity="$seriesOpacity"
                stroke-width="$seriesStrokeWidth"
                vector-effect="non-scaling-stroke"/>

          <!-- Left labels (Q1, median, zero if present) -->
          ${leftLabels.joinToString("\n") { leftLabel(it.y, it.key, it.value) }}

          <!-- Right labels (min, Q3, max) -->
          ${rightLabels.joinToString("\n") { rightLabel(it.y, it.key!!, it.value) }}
        </svg>
    """.trimIndent()
}

/**
 * Returns a "live number" - a number that is read directly from the source code at this marker's location.
 * When the source code changes, the returned value changes immediately without recompilation or reevaluation.
 * 
 * **Important**: The argument must be a literal numeric value (e.g., `10.0`, `42`, `1e-5`). 
 * Expressions like `1 + 2`, `Math.PI`, or variable references are not supported and will cause 
 * the function to return the default value.
 * 
 * Usage example:
 * ```
 * val m = __m("myMarker")
 * val speed = m.liveNumber(10.0)  // Initially returns 10.0
 * // When user changes "10.0" to "20.0" in the code editor, next call returns 20.0 without recompilation
 * ```
 * 
 * @param defaultValue The default value to return if the source cannot be found or parsed
 * @return The numeric value extracted from the source code, or defaultValue if extraction fails
 */
fun Marker.liveNumber(defaultValue: Double = 0.0): Double {
    // Find the location where liveNumber was called in the source code
    val location = locationForTextStringOnStack(".liveNumber(", this.box, end = true, offset = 0)
    
    if (location == null) {
        lprintln("warn,decorations", "liveNumber: Could not find location for marker ${this.name}")
        return defaultValue
    }
    
    // Get the source code and split it into lines
    val lines = this.box.code?.split("\n") ?: return defaultValue
    
    // Get the line where liveNumber is called
    val lineIndex = location.line - 1
    if (lineIndex < 0 || lineIndex >= lines.size) {
        lprintln("warn,decorations", "liveNumber: Line index out of bounds: $lineIndex")
        return defaultValue
    }
    
    val line = lines[lineIndex]
    
    // Use the column information from location to find the exact position
    // location.col points to just after ".liveNumber(" due to end=true in locationForTextStringOnStack
    val startPos = location.col
    
    // Verify we're at the expected position (column is 0-based)
    if (startPos < 0 || startPos > line.length) {
        lprintln("warn,decorations", "liveNumber: Column position out of bounds: ${location.col}")
        return defaultValue
    }
    
    var endPos = startPos
    var parenDepth = 1
    
    // Find the matching closing parenthesis, handling nested parentheses
    while (endPos < line.length && parenDepth > 0) {
        when (line[endPos]) {
            '(' -> parenDepth++
            ')' -> parenDepth--
        }
        if (parenDepth > 0) endPos++
    }
    
    if (parenDepth != 0) {
        lprintln("warn,decorations", "liveNumber: Could not find matching closing parenthesis")
        return defaultValue
    }
    
    // Extract the argument text and trim whitespace
    val argText = line.substring(startPos, endPos).trim()
    
    // If argument is empty, return default
    if (argText.isEmpty()) {
        return defaultValue
    }
    
    // Try to parse the argument as a double
    // This handles integers, floats, and scientific notation (e.g., 1.0, 42, 1e-5)
    val parsedValue = argText.toDoubleOrNull()
    if (parsedValue == null) {
        lprintln("warn,decorations", "liveNumber: Could not parse argument as number: '$argText' - only literal numeric values are supported")
        return defaultValue
    }
    
    lprintln("debug,decorations", "liveNumber: Extracted value $parsedValue from source (marker: ${this.name})")
    return parsedValue
}

