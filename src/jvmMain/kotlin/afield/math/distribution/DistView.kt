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
 *
 * For a posterior ([Dist.given]) the bars are weighted by the evidence, the footer gives the
 * effective sample size, and [prior], when given, is drawn as a faint outline behind them, so you
 * see what the evidence changed.
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
    prior: Progressive? = null,
): String {
    val raw = p.kept()
    val rawLw = p.keptLogWeights()
    // weighted draws, sorted by value, with zero-weight draws dropped
    val (s, wts) = sortedWeighted(raw, rawLw)
    val w = widthPx.toDouble()
    val histH = heightPx * 0.55
    val padL = 14.0; val padR = 14.0; val padT = if (title != null) 26.0 else 16.0
    val innerW = w - padL - padR
    val sb = StringBuilder()
    sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$widthPx" height="$heightPx" viewBox="0 0 $widthPx $heightPx" font-family="$fontFamily">""")
    sb.append("""<rect width="$widthPx" height="$heightPx" fill="#000"/>""")
    if (title != null) sb.append(text(padL, 14.0, esc(title), color, 11, "start"))
    if (s.isEmpty()) {
        val msg = if (raw.isEmpty()) "no samples yet" else "no sample consistent with the evidence yet (${raw.size} drawn)"
        sb.append(text(w / 2, heightPx / 2.0, msg, color, 11, "middle")).append("</svg>")
        return sb.toString()
    }
    val n = s.size
    val totalW = wts?.sum() ?: n.toDouble()
    // distinct values (s is sorted), with their total weight
    val distinct = ArrayList<Double>()
    val counts = ArrayList<Double>()
    for (i in s.indices) {
        val v = s[i]; val wi = wts?.get(i) ?: 1.0
        if (distinct.isNotEmpty() && distinct.last() == v) counts[counts.size - 1] += wi
        else { if (distinct.size > maxDiscrete) break; distinct += v; counts += wi }
    }
    val discrete = distinct.size <= maxDiscrete
    val pmf = try { p.exactPmf } catch (_: EvidenceImpossibleException) { null }
    val pdf = if (discrete) null else p.exactPdf

    // x range: the samples, widened to the exact support when we have one, trimmed to 0.1%..99.9%
    var lo: Double; var hi: Double
    if (discrete) {
        lo = distinct.first(); hi = distinct.last()
        if (pmf != null && pmf.size <= maxDiscrete) { lo = min(lo, pmf.keys.min()); hi = max(hi, pmf.keys.max()) }
    } else if (wts != null) {
        // a posterior: frame its own mass, not the prior draws that carry almost no weight
        lo = weightedAt(s, wts, totalW, 0.001); hi = weightedAt(s, wts, totalW, 0.999)
        val pad = (hi - lo) * 0.15
        lo = max(lo - pad, s.first()); hi = min(hi + pad, s.last())
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
    val binCount = (sqrt(n.toDouble()) * 1.5).toInt().coerceIn(12, 72)
    if (discrete) {
        val bw = gap * 0.7
        for (i in distinct.indices) bars += Triple(distinct[i] - bw / 2, distinct[i] + bw / 2, counts[i] / totalW)
    } else {
        bars += histogramBars(s, wts, lo, hi, binCount)
    }
    // the prior, as a faint outline on the same bins, when this is a posterior
    val priorBars: List<Triple<Double, Double, Double>>? = prior?.let { pr ->
        val ps = pr.kept().also { it.sort() }
        if (ps.isEmpty()) null
        else if (discrete) {
            val bw = gap * 0.7
            val m = HashMap<Double, Int>()
            for (v in ps) m.merge(v, 1, Int::plus)
            m.entries.filter { it.key in lo..hi }.map { Triple(it.key - bw / 2, it.key + bw / 2, it.value.toDouble() / ps.size) }
        } else histogramBars(ps, null, lo, hi, binCount)
    }
    // overlay curve, same units
    val curve: List<Pair<Double, Double>>? = when {
        discrete && pmf != null -> pmf.entries.filter { it.key in lo..hi }.map { it.key to it.value }
        pdf != null -> (0..160).map { val x = lo + (hi - lo) * it / 160.0; x to pdf(x) }
        !discrete -> kde(s, wts, lo, hi)
        else -> null
    }
    val top = maxOf(bars.maxOf { it.third }, curve?.maxOfOrNull { it.second } ?: 0.0, priorBars?.maxOfOrNull { it.third } ?: 0.0).takeIf { it > 0 } ?: 1.0
    fun yOf(h: Double) = yBase - h / top * (yBase - y0)

    if (priorBars != null) {
        if (discrete) {
            for ((a, b, h) in priorBars) {
                if (h <= 0) continue
                sb.append("""<rect x="${f(xOf(a))}" y="${f(yOf(h))}" width="${f(max(0.5, xOf(b) - xOf(a)))}" height="${f(yBase - yOf(h))}" fill="none" stroke="#fff" stroke-opacity="0.4" stroke-dasharray="2,2"/>""")
            }
        } else {
            // one stepped outline across the bins
            val pts = StringBuilder()
            pts.append(f(xOf(priorBars.first().first))).append(',').append(f(yBase))
            for ((a, b, h) in priorBars) {
                pts.append(' ').append(f(xOf(a))).append(',').append(f(yOf(h)))
                pts.append(' ').append(f(xOf(b))).append(',').append(f(yOf(h)))
            }
            pts.append(' ').append(f(xOf(priorBars.last().second))).append(',').append(f(yBase))
            sb.append("""<polyline points="$pts" fill="none" stroke="#fff" stroke-opacity="0.45" stroke-dasharray="3,2"/>""")
        }
    }

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
        discrete && pmf != null -> if (p.weighted) "exact posterior pmf" else "exact pmf"
        pdf != null -> "exact density"
        !discrete -> "kernel estimate"
        else -> ""
    }
    if (legend.isNotEmpty()) sb.append(text(w - padR, padT - 4, legend, overlay, 9, "end"))
    if (priorBars != null) sb.append(text(w - padR - 5.6 * legend.length - 12, padT - 4, "prior (dashed)", "#fff", 9, "end", 0.6))

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
        val essNote = if (p.weighted) ", ess ${essShort(est.ess)}" else ""
        sb.append(text(w - padR, heightPx - 5.0, "n = ${tr.last().first}$essNote  (log scale, band ±2 SE)", color, 9, "end", 0.7))
    }
    sb.append("</svg>")
    return sb.toString()
}

private fun minGap(sorted: List<Double>): Double {
    var g = Double.MAX_VALUE
    for (i in 1 until sorted.size) g = min(g, sorted[i] - sorted[i - 1])
    return if (g == Double.MAX_VALUE || g <= 0) 1.0 else g
}

/** Gaussian kernel estimate with Silverman's bandwidth, on a 160-point grid; weighted when [wts] is given. */
private fun kde(sorted: DoubleArray, wts: DoubleArray?, lo: Double, hi: Double): List<Pair<Double, Double>> {
    val n = sorted.size
    val step = max(1, n / 4000)
    val idx = (0 until n step step).toList()
    val pts = idx.map { sorted[it] }
    val pw = idx.map { wts?.get(it) ?: 1.0 }
    val wsum = pw.sum()
    val m = pts.indices.sumOf { pts[it] * pw[it] } / wsum
    val sd = sqrt(pts.indices.sumOf { pw[it] * (pts[it] - m) * (pts[it] - m) } / wsum)
    val iqr = sorted[(n * 0.75).toInt().coerceAtMost(n - 1)] - sorted[(n * 0.25).toInt()]
    val effN = if (wts == null) pts.size.toDouble() else wsum * wsum / pw.sumOf { it * it }
    val h = (0.9 * min(sd, iqr / 1.34).takeIf { it > 0 }.let { it ?: sd } * effN.pow(-0.2)).takeIf { it > 0 } ?: 1.0
    val c = 1.0 / (wsum * h * sqrt(2 * Math.PI))
    return (0..160).map { k ->
        val x = lo + (hi - lo) * k / 160.0
        var acc = 0.0
        for (i in pts.indices) { val z = (x - pts[i]) / h; if (abs(z) < 5) acc += pw[i] * exp(-0.5 * z * z) }
        x to acc * c
    }
}

/** Density-scaled bars over `[lo, hi]` in [k] bins, weighted when [wts] is given. */
private fun histogramBars(s: DoubleArray, wts: DoubleArray?, lo: Double, hi: Double, k: Int): List<Triple<Double, Double, Double>> {
    val bw = (hi - lo) / k
    val c = DoubleArray(k)
    var total = 0.0
    for (i in s.indices) {
        val wi = wts?.get(i) ?: 1.0
        total += wi
        val j = floor((s[i] - lo) / bw).toInt()
        if (j in 0 until k) c[j] += wi
    }
    return (0 until k).map { j -> Triple(lo + j * bw, lo + (j + 1) * bw, c[j] / (total * bw)) }
}

/** The kept draws sorted by value, with their normalised weights (null if unweighted); zero-weight draws dropped. */
private fun sortedWeighted(s: DoubleArray, lw: DoubleArray?): Pair<DoubleArray, DoubleArray?> {
    if (lw == null) return s.copyOf().also { it.sort() } to null
    var m = Double.NEGATIVE_INFINITY
    for (v in lw) if (v > m) m = v
    if (m == Double.NEGATIVE_INFINITY || m.isNaN()) return DoubleArray(0) to DoubleArray(0)
    val idx = s.indices.filter { lw[it] - m > -745 }.sortedBy { s[it] }
    return DoubleArray(idx.size) { s[idx[it]] } to DoubleArray(idx.size) { exp(lw[idx[it]] - m) }
}

/** The value at cumulative weight fraction [q] of sorted draws [s] with weights [w] summing to [total]. */
private fun weightedAt(s: DoubleArray, w: DoubleArray, total: Double, q: Double): Double {
    var c = 0.0
    for (i in s.indices) { c += w[i]; if (c >= q * total) return s[i] }
    return s.last()
}

private fun essShort(e: Double): String = if (e >= 100) e.toLong().toString() else "%.3g".format(e)

private fun f(v: Double) = "%.1f".format(v)
private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
private fun text(x: Double, y: Double, t: String, color: String, size: Int, anchor: String, opacity: Double = 1.0) =
    """<text x="${f(x)}" y="${f(y)}" fill="$color" fill-opacity="$opacity" font-size="$size" text-anchor="$anchor">${esc(t)}</text>"""

private class LiveDist(val description: String, val p: Progressive, val prior: Progressive?) {
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
        val prior = if (Plan(listOf(d)).hasEvidence) Progressive(d.prior(), seed = 2L) else null
        live = LiveDist(desc, Progressive(d), prior)
        this.properties[key] = live
    }
    val p = live.p
    p.draw(batch)
    live.prior?.draw(batch)
    live.limiter {
        location?.let { this.box._printHere(it.line, distributionSvg(p, title = title, exact = exact, prior = live.prior)) }
    }
    return if (p.weighted && !p.weightedMoments.anyAccepted) Estimate(Double.NaN, Double.NaN, p.drawn, 0.0) else p.estimate
}

private class LiveHeard(val param: DistParam, val p: Progressive) {
    var seen = 0L
    val limiter = RateLimiter(10.0, 0.2)
}

/**
 * The inline visualizer for what a [DistParam] is playing: each call adds the values drawn since
 * the last call and redraws their histogram under the exact pmf or density of the parameter's
 * distribution. The bars are the sound's own values, not a separate sample, so they show what you
 * are hearing settle onto the distribution you wrote.
 *
 * ```kotlin
 * val pitch = categorical(listOf(220, 275, 330), listOf(3, 1, 2)).param(everySeconds = 0.125)
 * loop {
 *     __m("heard").distribution(pitch)
 *     frame()
 * }
 * ```
 */
fun Marker.distribution(param: DistParam, title: String? = null, exact: Double? = null): Estimate? {
    val location = locationForTextStringOnStack("distribution", this.box, end = true, offset = 1)
    val key = "__distribution_heard__"
    var live = this.properties[key] as? LiveHeard
    if (live == null || live.param !== param) {
        live = LiveHeard(param, Progressive(param.dist, suppliedDraws = true))
        this.properties[key] = live
    }
    val total = param.drawCount
    val fresh = (total - live.seen).coerceIn(0L, 8192L).toInt()
    if (fresh > 0) {
        val all = param.played()
        live.p.addDraws(all.copyOfRange(all.size - minOf(fresh, all.size), all.size))
        live.seen = total
    }
    live.limiter {
        location?.let { this.box._printHere(it.line, distributionSvg(live.p, title = title ?: "heard: ${param.dist.describe()}", exact = exact)) }
    }
    return if (live.p.drawn > 0) live.p.estimate else null
}

private fun tick(v: Double): String = if (v == floor(v) && abs(v) < 1e9) v.toLong().toString() else if (abs(v) < 1e-3) "0" else "%.3g".format(v)
