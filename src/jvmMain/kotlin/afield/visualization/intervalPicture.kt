@file:afield.CompletionCategory("Visualization")

package afield

import afield.stdlib.FnCertifiedEnvelope
import afield.stdlib.FnCertifiedRange
import afield.stdlib.FnSearchPiece
import afield.stdlib.FnSearchSide
import afield.stdlib.Functions
import afield.stdlib.certifiedEnvelope
import afield.stdlib.certifiedRange
import java.util.Locale

/**
 * A picture of what interval arithmetic proved about a function, and where it had to work to prove
 * it.
 *
 * ```kotlin
 * with(Functions) {
 *     val f = saw(0.7, amplitude = 0.4) + gaussian(2.31, 0.004, 0.6, Domain.ALL)
 *     f.intervalPicture(0.0, 6.0)   // the last expression of a box draws it
 * }
 * ```
 *
 * Four layers, back to front:
 *
 * - **the search boxes** — every enclosure [certifiedRange] computed, as the rectangle
 *   `[lo, hi] × [range.lo, range.hi]` it certifies, orange for the maximum's search and green for
 *   the minimum's. A box the search kept splitting is drawn stronger than one its bound pruned. Wide
 *   pale boxes are the first, loose enclosures; the stack of thin ones is where the extreme hides.
 * - **the certified band** — [certifiedEnvelope]'s per-bin bound, which no value between samples
 *   can escape.
 * - **the sampled curve** — what a plot would draw, from [samples] evaluations.
 * - **the proof** — the reached extreme at its witness (a dot) and the proven bound (a dashed rule).
 *   The gap between dot and rule is the uncertainty left, and is printed in the title.
 *
 * Under the plot, a strip counts enclosures per x bin: where the proof spent its work. A function
 * whose op has no formula is refused, and the picture says so and draws only the samples, because a
 * band that was guessed would be worse than none.
 */
class FnIntervalPicture(
    val fn: Functions.Fn,
    val a: Double,
    val b: Double,
    val range: FnCertifiedRange,
    val envelope: FnCertifiedEnvelope,
    val pieces: List<FnSearchPiece>,
    val samples: Int = 1200,
    val title: String? = null,
) : ToHTML {

    var width: Double = 900.0
    var height: Double = 440.0

    /** At most this many search boxes are drawn — the first ones, which are the widest. */
    var maxBoxes: Int = 3000

    override fun toHTML(): String = svg()

    fun svg(): String {
        val left = 58.0
        val right = width - 16.0
        val top = 50.0
        val stripHeight = 54.0
        val bottom = height - 30.0
        val stripTop = bottom - stripHeight
        val plotBottom = stripTop - 26.0

        val xs = DoubleArray(samples) { a + (b - a) * it / (samples - 1) }
        val ys = DoubleArray(samples) { runCatching { fn.eval(xs[it]) }.getOrDefault(Double.NaN) }

        // The y domain comes from what the reader should look at: samples, band and bounds — never
        // from the loose first enclosures, which would flatten everything else to a line.
        var yLo = Double.POSITIVE_INFINITY
        var yHi = Double.NEGATIVE_INFINITY
        fun take(v: Double) { if (v.isFinite()) { yLo = minOf(yLo, v); yHi = maxOf(yHi, v) } }
        ys.forEach(::take)
        if (envelope.certified) { envelope.low.forEach(::take); envelope.high.forEach(::take) }
        range.min?.let { take(it.bound) }
        range.max?.let { take(it.bound) }
        if (!yLo.isFinite()) { yLo = -1.0; yHi = 1.0 }
        if (yHi - yLo < 1e-12) { yLo -= 0.5; yHi += 0.5 }
        val pad = (yHi - yLo) * 0.08
        yLo -= pad; yHi += pad

        fun px(x: Double) = left + (x - a) / (b - a) * (right - left)
        fun py(y: Double) = plotBottom - (y - yLo) / (yHi - yLo) * (plotBottom - top)
        fun f(v: Double) = String.format(Locale.ROOT, "%.2f", v)

        val out = StringBuilder()
        out.append("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 ${f(width)} ${f(height)}' ")
            .append("style='width:100%;height:auto;background:#101317;font-family:ui-monospace,Menlo,monospace'>")
        out.append("<rect width='${f(width)}' height='${f(height)}' fill='#101317'/>")

        // Grid and ticks.
        val xTicks = niceTicks(a, b, 8)
        val yTicks = niceTicks(yLo, yHi, 6)
        val xStep = if (xTicks.size > 1) xTicks[1] - xTicks[0] else 0.0
        val yStep = if (yTicks.size > 1) yTicks[1] - yTicks[0] else 0.0
        for (t in xTicks) if (t in a..b) {
            out.append("<line x1='${f(px(t))}' x2='${f(px(t))}' y1='${f(top)}' y2='${f(plotBottom)}' stroke='#262b32'/>")
            out.append("<text x='${f(px(t))}' y='${f(bottom + 18)}' fill='#8a949e' font-size='11' text-anchor='middle'>${tickLabel(t, xStep)}</text>")
        }
        for (t in yTicks) if (t in yLo..yHi) {
            out.append("<line x1='${f(left)}' x2='${f(right)}' y1='${f(py(t))}' y2='${f(py(t))}' stroke='#262b32'/>")
            out.append("<text x='${f(left - 6)}' y='${f(py(t) + 4)}' fill='#8a949e' font-size='11' text-anchor='end'>${tickLabel(t, yStep)}</text>")
        }

        // A nested <svg> clips to its own viewport with no id, so the picture still clips when a box
        // output's <base> sends `url(#id)` references somewhere else, which blanked the plot.
        out.append("<svg x='${f(left)}' y='${f(top)}' width='${f(right - left)}' height='${f(plotBottom - top)}' ")
            .append("viewBox='${f(left)} ${f(top)} ${f(right - left)} ${f(plotBottom - top)}' overflow='hidden'>")
        // 1. The search boxes.
        for (p in pieces.take(maxBoxes)) {
            if (p.range.isEmpty) continue
            val color = if (p.side == FnSearchSide.MAX) "#ff9e4a" else "#8fff9a"
            val lo = p.range.lo.coerceIn(yLo - (yHi - yLo), yHi + (yHi - yLo))
            val hi = p.range.hi.coerceIn(yLo - (yHi - yLo), yHi + (yHi - yLo))
            val x0 = px(p.lo)
            val w = maxOf(px(p.hi) - x0, 0.6)
            // Coarse pieces are outlines only: filled, the first few enclosures would wash over the
            // whole plot. The fill is kept for the narrow pieces, where it reads as the search's heat.
            val narrow = p.hi - p.lo < (b - a) / 16
            val fill = if (!narrow) "0" else if (p.kept) "0.10" else "0.04"
            val stroke = if (p.kept) "0.6" else "0.2"
            out.append("<rect x='${f(x0)}' y='${f(py(hi))}' width='${f(w)}' height='${f(maxOf(py(lo) - py(hi), 0.6))}' ")
                .append("fill='$color' fill-opacity='$fill' stroke='$color' stroke-opacity='$stroke' stroke-width='0.6'/>")
        }
        // 2. The certified band.
        if (envelope.certified && envelope.bins > 0) {
            val upper = (0 until envelope.bins).joinToString(" ") { i ->
                "${f(px(envelope.edge(i)))},${f(py(envelope.high[i]))} ${f(px(envelope.edge(i + 1)))},${f(py(envelope.high[i]))}"
            }
            val lower = (envelope.bins - 1 downTo 0).joinToString(" ") { i ->
                "${f(px(envelope.edge(i + 1)))},${f(py(envelope.low[i]))} ${f(px(envelope.edge(i)))},${f(py(envelope.low[i]))}"
            }
            out.append("<polygon points='$upper $lower' fill='#7ad9ff' fill-opacity='0.22' stroke='none'/>")
        }
        // 3. The sampled curve, broken at NaN.
        val path = StringBuilder()
        var pen = false
        for (i in 0 until samples) {
            if (!ys[i].isFinite()) { pen = false; continue }
            path.append(if (pen) " L" else " M").append(f(px(xs[i]))).append(',').append(f(py(ys[i])))
            pen = true
        }
        out.append("<path d='$path' fill='none' stroke='#7ad9ff' stroke-width='1.3'/>")
        // 4. The proof: witness dots and proven-bound rules.
        for ((ext, color) in listOf(range.max to "#ff9e4a", range.min to "#8fff9a")) {
            if (ext == null) continue
            out.append("<line x1='${f(left)}' x2='${f(right)}' y1='${f(py(ext.bound))}' y2='${f(py(ext.bound))}' stroke='$color' stroke-dasharray='5 4' stroke-width='1'/>")
            out.append("<circle cx='${f(px(ext.witness))}' cy='${f(py(ext.value))}' r='4' fill='none' stroke='$color' stroke-width='1.6'/>")
        }
        out.append("</svg>")
        out.append("<rect x='${f(left)}' y='${f(top)}' width='${f(right - left)}' height='${f(plotBottom - top)}' fill='none' stroke='#3a414a'/>")

        // The work strip: enclosures per x bin, square-root scaled so a hot spot does not hide the rest.
        val bins = 150
        val counts = IntArray(bins)
        for (p in pieces) {
            val mid = (p.lo + p.hi) * 0.5
            val i = (((mid - a) / (b - a)) * bins).toInt().coerceIn(0, bins - 1)
            counts[i]++
        }
        val peak = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
        val binWidth = (right - left) / bins
        for (i in 0 until bins) if (counts[i] > 0) {
            val h = Math.sqrt(counts[i].toDouble() / peak) * stripHeight
            out.append("<rect x='${f(left + i * binWidth)}' y='${f(bottom - h)}' width='${f(maxOf(binWidth - 0.5, 0.5))}' height='${f(h)}' fill='#c58cff' fill-opacity='0.75'/>")
        }
        out.append("<rect x='${f(left)}' y='${f(stripTop)}' width='${f(right - left)}' height='${f(stripHeight)}' fill='none' stroke='#3a414a'/>")
        out.append("<text x='${f(left)}' y='${f(stripTop - 6)}' fill='#8a949e' font-size='11'>")
            .append("where the proof worked: ${pieces.size} enclosures (peak $peak in one bin)</text>")

        // Title: what was proven, or why nothing was.
        val heading = esc(title ?: "certified range on [${g(a)}, ${g(b)}]")
        val claim = if (range.refusal != null) {
            "not certified: ${esc(range.refusal)}"
        } else {
            val mx = range.max!!
            val mn = range.min!!
            "max ∈ [${g(mx.value)}, ${g(mx.bound)}] at x = ${g(mx.witness)} · min ∈ [${g(mn.bound)}, ${g(mn.value)}] at x = ${g(mn.witness)}" +
                if (range.certified) "" else " · not converged"
        }
        out.append("<text x='${f(left)}' y='19' fill='#d6dde4' font-size='13'>$heading</text>")
        out.append("<text x='${f(left)}' y='37' fill='#9aa4ae' font-size='11'>$claim</text>")
        out.append("</svg>")
        return out.toString()
    }

    private fun g(v: Double) = String.format(Locale.ROOT, "%.6g", v)
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/**
 * Proves this function's range on `[a, b]` and draws the proof — see [FnIntervalPicture].
 *
 * [tolerance] is how close the reached extreme and the proven bound must come; [bins] is the
 * resolution of the certified band. Both searches are recorded, so the boxes show the work of the
 * minimum and the maximum together.
 */
fun Functions.Fn.intervalPicture(
    a: Double,
    b: Double,
    tolerance: Double = 1e-6,
    bins: Int = 240,
    title: String? = null,
    maxEnclosures: Int = 50_000,
): FnIntervalPicture {
    val pieces = ArrayList<FnSearchPiece>()
    val range = certifiedRange(a, b, tolerance, maxEnclosures) { pieces += it }
    val envelope = certifiedEnvelope(a, b, bins, tolerance = 1e-3 * maxOf(1e-12, (range.max?.value ?: 1.0) - (range.min?.value ?: 0.0)))
    return FnIntervalPicture(this, a, b, range, envelope, pieces, title = title)
}

/** Draws [fn]'s [intervalPicture] into [channel] and returns it. */
fun Box.showIntervals(
    fn: Functions.Fn,
    a: Double,
    b: Double,
    channel: String = "intervals",
    tolerance: Double = 1e-6,
    title: String? = null,
): FnIntervalPicture {
    val picture = fn.intervalPicture(a, b, tolerance, title = title)
    display(channel, picture)
    return picture
}
