package afield.math.distribution

import afield.document.graph.Marker
import afield.runtime.lifecycle.RateLimiter
import afield.ui.output._printHere
import afield.ui.output.locationForTextStringOnStack
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Draws a [Progressive] as an SVG: the samples so far as a histogram with the exact pmf or density
 * laid over it (or a kernel estimate, dashed, when no exact one is known), the mean with its
 * standard-error band, and underneath, the running estimate against `log n` settling inside its
 * shrinking ±2 SE band.
 *
 * Discrete samples (at most [maxDiscrete] distinct values) are drawn one bar per value, so a die
 * is six bars and two dice are eleven — not the bins a continuous rule would cut through them.
 */
fun distributionSvg(
    p: Progressive,
    title: String? = null,
    widthPx: Int = 480,
    heightPx: Int = 200,
    maxDiscrete: Int = 80,
    exact: Double? = null,
    color: String = "#0af",
    overlay: String = "#fa0",
    fontFamily: String = "'Iosevka Web', Iosevka, monospace",
): String {
    val s = p.kept()
    val w = widthPx.toDouble()
    val histH = heightPx * 0.55
    val padL = 14.0; val padR = 14.0; val padT = if (title != null) 26.0 else 16.0
    val innerW = w - padL - padR
    val sb = StringBuilder()
    sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$widthPx" height="$heightPx" viewBox="0 0 $widthPx $heightPx" font-family="$fontFamily">""")
    sb.append("""<rect width="$widthPx" height="$heightPx" fill="#000"/>""")
    if (title != null) sb.append(text(padL, 14.0, esc(title), color, 11, "start"))
    if (s.isEmpty()) {
        sb.append(text(w / 2, heightPx / 2.0, "no samples yet", color, 11, "middle")).append("</svg>")
        return sb.toString()
    }
    s.sort()
    val n = s.size
    // distinct values (s is sorted)
    val distinct = ArrayList<Double>()
    val counts = ArrayList<Int>()
    for (v in s) {
        if (distinct.isNotEmpty() && distinct.last() == v) counts[counts.size - 1]++
        else { if (distinct.size > maxDiscrete) break; distinct += v; counts += 1 }
    }
    val discrete = distinct.size <= maxDiscrete
    val pmf = p.exactPmf
    val pdf = if (discrete) null else p.exactPdf

    // x range: the samples, widened to the exact support when we have one, trimmed to 0.1%..99.9%
    var lo: Double; var hi: Double
    if (discrete) {
        lo = distinct.first(); hi = distinct.last()
        if (pmf != null && pmf.size <= maxDiscrete) { lo = min(lo, pmf.keys.min()); hi = max(hi, pmf.keys.max()) }
    } else {
        lo = s[(n * 0.001).toInt()]; hi = s[min(n - 1, (n * 0.999).toInt())]
    }
    val gap = if (discrete) minGap(if (pmf != null && pmf.size <= maxDiscrete) pmf.keys.sorted() else distinct) else 0.0
    if (discrete) { lo -= gap / 2; hi += gap / 2 }
    if (hi <= lo) { lo -= 0.5; hi += 0.5 }
    fun xOf(v: Double) = padL + (v - lo) / (hi - lo) * innerW
    val y0 = padT; val yBase = padT + histH - 18

    // heights in "probability per unit" so bars and density share a scale
    val bars = ArrayList<Triple<Double, Double, Double>>() // x0, x1, height
    if (discrete) {
        val bw = gap * 0.7
        for (i in distinct.indices) bars += Triple(distinct[i] - bw / 2, distinct[i] + bw / 2, counts[i].toDouble() / n)
    } else {
        val k = (sqrt(n.toDouble()) * 1.5).toInt().coerceIn(12, 72)
        val bw = (hi - lo) / k
        val c = IntArray(k)
        for (v in s) { val j = floor((v - lo) / bw).toInt(); if (j in 0 until k) c[j]++ }
        for (j in 0 until k) bars += Triple(lo + j * bw, lo + (j + 1) * bw, c[j] / (n * bw))
    }
    // overlay curve, same units
    val curve: List<Pair<Double, Double>>? = when {
        discrete && pmf != null -> pmf.entries.filter { it.key in lo..hi }.map { it.key to it.value }
        pdf != null -> (0..160).map { val x = lo + (hi - lo) * it / 160.0; x to pdf(x) }
        !discrete -> kde(s, lo, hi)
        else -> null
    }
    val top = max(bars.maxOf { it.third }, curve?.maxOfOrNull { it.second } ?: 0.0).takeIf { it > 0 } ?: 1.0
    fun yOf(h: Double) = yBase - h / top * (yBase - y0)

    for ((a, b, h) in bars) {
        val x = xOf(a); val xb = xOf(b)
        sb.append("""<rect x="${f(x)}" y="${f(yOf(h))}" width="${f(max(0.5, xb - x - if (discrete) 0.0 else 1.0))}" height="${f(yBase - yOf(h))}" fill="$color" fill-opacity="0.25" stroke="$color" stroke-opacity="0.6"/>""")
    }
    if (curve != null) {
        if (discrete) {
            for ((x, h) in curve) {
                sb.append("""<line x1="${f(xOf(x) - gap * 0.35 / (hi - lo) * innerW)}" x2="${f(xOf(x) + gap * 0.35 / (hi - lo) * innerW)}" y1="${f(yOf(h))}" y2="${f(yOf(h))}" stroke="$overlay" stroke-width="2"/>""")
            }
        } else {
            val pts = curve.joinToString(" ") { "${f(xOf(it.first))},${f(yOf(it.second))}" }
            val dash = if (pdf == null) """ stroke-dasharray="4,3"""" else ""
            sb.append("""<polyline points="$pts" fill="none" stroke="$overlay" stroke-width="1.5"$dash/>""")
        }
    }
    sb.append("""<line x1="${f(padL)}" x2="${f(w - padR)}" y1="${f(yBase)}" y2="${f(yBase)}" stroke="$color"/>""")
    // mean and its SE band on the histogram
    val est = p.estimate
    val xm = xOf(est.value)
    val seW = max(1.0, abs(xOf(est.value + est.se) - xm))
    sb.append("""<rect x="${f(xm - seW)}" y="${f(y0)}" width="${f(2 * seW)}" height="${f(yBase - y0)}" fill="#fff" fill-opacity="0.15"/>""")
    sb.append("""<line x1="${f(xm)}" x2="${f(xm)}" y1="${f(y0)}" y2="${f(yBase)}" stroke="#fff" stroke-dasharray="2,2"/>""")
    // axis labels: the ends, and the discrete values when there are few
    val ticks = if (discrete && distinct.size <= 24) (pmf?.keys?.filter { it in lo..hi }?.takeIf { it.size <= 24 } ?: distinct) else listOf(lo, (lo + hi) / 2, hi)
    for (t in ticks) sb.append(text(xOf(t).coerceIn(padL + 6, w - padR - 6), yBase + 12, tick(t), color, 9, "middle", 0.7))
    val legend = when {
        discrete && pmf != null -> "exact pmf"
        pdf != null -> "exact density"
        !discrete -> "kernel estimate"
        else -> ""
    }
    if (legend.isNotEmpty()) sb.append(text(w - padR, padT - 4, legend, overlay, 9, "end"))

    // running estimate strip
    val tr = p.trace
    val sy0 = yBase + 34; val sy1 = heightPx - 18.0
    sb.append(text(padL, yBase + 28, "E = $est" + (exact?.let { "   exact ${fmtShort(it)}" } ?: ""), "#fff", 10, "start"))
    if (tr.size >= 2) {
        val n0 = log10(tr.first().first.toDouble()); val n1 = log10(tr.last().first.toDouble()).let { if (it <= n0) n0 + 1 else it }
        val tail = tr.drop(tr.size / 5)
        var ylo = tail.minOf { it.second - 2.5 * it.third }; var yhi = tail.maxOf { it.second + 2.5 * it.third }
        if (exact != null) { ylo = min(ylo, exact); yhi = max(yhi, exact) }
        if (yhi <= ylo) { ylo -= 0.5; yhi += 0.5 }
        fun sx(nn: Long) = padL + (log10(nn.toDouble()) - n0) / (n1 - n0) * innerW
        fun sy(v: Double) = (sy1 - (v - ylo) / (yhi - ylo) * (sy1 - sy0 - 6)).coerceIn(sy0 + 4, sy1)
        val up = tr.joinToString(" ") { "${f(sx(it.first))},${f(sy(it.second + 2 * it.third))}" }
        val dn = tr.reversed().joinToString(" ") { "${f(sx(it.first))},${f(sy(it.second - 2 * it.third))}" }
        sb.append("""<polygon points="$up $dn" fill="$color" fill-opacity="0.2"/>""")
        if (exact != null) sb.append("""<line x1="${f(padL)}" x2="${f(w - padR)}" y1="${f(sy(exact))}" y2="${f(sy(exact))}" stroke="$overlay" stroke-dasharray="3,3"/>""")
        sb.append("""<polyline points="${tr.joinToString(" ") { "${f(sx(it.first))},${f(sy(it.second))}" }}" fill="none" stroke="$color" stroke-width="1.5"/>""")
        sb.append(text(padL, heightPx - 5.0, "n = ${tr.first().first}", color, 9, "start", 0.7))
        sb.append(text(w - padR, heightPx - 5.0, "n = ${tr.last().first}  (log scale, band ±2 SE)", color, 9, "end", 0.7))
    }
    sb.append("</svg>")
    return sb.toString()
}

private fun minGap(sorted: List<Double>): Double {
    var g = Double.MAX_VALUE
    for (i in 1 until sorted.size) g = min(g, sorted[i] - sorted[i - 1])
    return if (g == Double.MAX_VALUE || g <= 0) 1.0 else g
}

/** Gaussian kernel estimate with Silverman's bandwidth, on a 160-point grid. */
private fun kde(sorted: DoubleArray, lo: Double, hi: Double): List<Pair<Double, Double>> {
    val n = sorted.size
    val step = max(1, n / 4000)
    val pts = (0 until n step step).map { sorted[it] }
    val m = pts.average()
    val sd = sqrt(pts.sumOf { (it - m) * (it - m) } / max(1, pts.size - 1))
    val iqr = sorted[(n * 0.75).toInt().coerceAtMost(n - 1)] - sorted[(n * 0.25).toInt()]
    val h = (0.9 * min(sd, iqr / 1.34).takeIf { it > 0 }.let { it ?: sd } * pts.size.toDouble().pow(-0.2)).takeIf { it > 0 } ?: 1.0
    val c = 1.0 / (pts.size * h * sqrt(2 * Math.PI))
    return (0..160).map { k ->
        val x = lo + (hi - lo) * k / 160.0
        var acc = 0.0
        for (v in pts) { val z = (x - v) / h; if (abs(z) < 5) acc += exp(-0.5 * z * z) }
        x to acc * c
    }
}

private fun f(v: Double) = "%.1f".format(v)
private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
private fun text(x: Double, y: Double, t: String, color: String, size: Int, anchor: String, opacity: Double = 1.0) =
    """<text x="${f(x)}" y="${f(y)}" fill="$color" fill-opacity="$opacity" font-size="$size" text-anchor="$anchor">${esc(t)}</text>"""

private class LiveDist(val description: String, val p: Progressive) {
    val limiter = RateLimiter(10.0, 0.2)
}

/**
 * An inline visualizer for a random variable: each call draws [batch] more samples of [d] and
 * redraws, next to this line in the editor, the histogram, its exact pmf or density, and the
 * running estimate of E(d) settling with its standard error. Call it from a `loop { }` and watch
 * the estimate converge; call it once for a single batch.
 *
 * The samples accumulate across calls for as long as [d] describes the same graph; edit the
 * expression and re-run, and it starts again. Returns the current estimate.
 *
 * ```kotlin
 * loop {
 *     __m("two dice").distribution(die(6) + die(6), exact = 7.0)
 *     frame()
 * }
 * ```
 */
fun Marker.distribution(d: Dist, batch: Int = 2_000, title: String? = null, exact: Double? = null): Estimate {
    val location = locationForTextStringOnStack("distribution", this.box, end = true, offset = 1)
    val key = "__distribution__"
    val desc = d.describe()
    var live = this.properties[key] as? LiveDist
    if (live == null || live.description != desc) {
        live = LiveDist(desc, Progressive(d))
        this.properties[key] = live
    }
    val p = live.p
    p.draw(batch)
    live.limiter {
        location?.let { this.box._printHere(it.line, distributionSvg(p, title = title, exact = exact)) }
    }
    return p.estimate
}

private fun tick(v: Double): String = if (v == floor(v) && abs(v) < 1e9) v.toLong().toString() else if (abs(v) < 1e-3) "0" else "%.3g".format(v)
