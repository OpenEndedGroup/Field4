package afield.geometry.path

import afield.audio.synth.ComplexLpcFit
import afield.audio.synth.Lpc
import kotlin.math.hypot

/**
 * Linear prediction of a drawn stroke.
 *
 * The stroke is resampled to [samples] points evenly spaced along its length, and the steps between
 * them, `d[n] = p[n+1] - p[n]`, less their mean (the stroke's overall drift, kept in [StrokeLpc.meanStepX]
 * and [StrokeLpc.meanStepY] and added back on the way out), are read as complex numbers `dx + i·dy`. A complex order-[order]
 * predictor ([Lpc.burgComplex]) is fitted to that sequence: each step is predicted as a complex
 * weighted sum of the steps before it, so the fit captures *how the hand turns* — a steady curl, a
 * wobble, a zigzag — independent of where the stroke is or which way it faces. What the predictor
 * cannot explain is the [residualRe]/[residualIm] sequence: the hand's surprises.
 *
 * From the fit: [continued] keeps drawing past the end with no surprises (the predictor's free
 * response), [resynthesized] rebuilds the stroke exactly from its residual, and [drivenBy] runs this
 * stroke's predictor on another stroke's residual — cross-synthesis, the scribble version of the
 * talking synth.
 */
class StrokeLpc(
    /** The fitted complex predictor over steps. */
    val fit: ComplexLpcFit,
    /** The arc-length-resampled stroke, x coordinates. */
    val xs: DoubleArray,
    /** The arc-length-resampled stroke, y coordinates. */
    val ys: DoubleArray,
    /** Real part of the step residual. */
    val residualRe: DoubleArray,
    /** Imaginary part of the step residual. */
    val residualIm: DoubleArray,
    /** The mean step, x part: the stroke's overall drift, taken out before fitting. */
    val meanStepX: Double = 0.0,
    /** The mean step, y part. */
    val meanStepY: Double = 0.0,
) {
    // Steps with the mean drift removed: what the predictor models.
    private val stepRe = DoubleArray(xs.size - 1) { xs[it + 1] - xs[it] - meanStepX }
    private val stepIm = DoubleArray(ys.size - 1) { ys[it + 1] - ys[it] - meanStepY }

    /** The resampled stroke as an [FLine]. */
    fun resampled(): FLine = polyline(xs, ys)

    /**
     * The stroke followed by [steps] more predicted steps with zero excitation. When [includeStroke]
     * is false only the continuation is returned, starting at the stroke's last point. [damping]
     * below 1 shrinks each predicted step, which keeps a predictor whose free response grows
     * from leaving the page.
     */
    fun continued(steps: Int, includeStroke: Boolean = true, damping: Double = 1.0): FLine {
        val p = fit.order
        val seedR = stepRe.copyOfRange((stepRe.size - p).coerceAtLeast(0), stepRe.size)
        val seedI = stepIm.copyOfRange((stepIm.size - p).coerceAtLeast(0), stepIm.size)
        val (dr, di) = fit.synthesize(DoubleArray(steps), DoubleArray(steps), seedR, seedI)
        val ox = DoubleArray(steps + 1); val oy = DoubleArray(steps + 1)
        ox[0] = xs.last(); oy[0] = ys.last()
        var g = 1.0
        for (n in 0 until steps) { g *= damping; ox[n + 1] = ox[n] + dr[n] * g + meanStepX; oy[n + 1] = oy[n] + di[n] * g + meanStepY }
        return if (includeStroke) polyline(xs + ox.copyOfRange(1, ox.size), ys + oy.copyOfRange(1, oy.size)) else polyline(ox, oy)
    }

    /**
     * Rebuild the stroke from its residual through the all-pole filter, scaling the residual by
     * [residualScale] (1 is exact; 0 keeps only the first step's impulse response; above 1
     * exaggerates the hand's surprises).
     */
    fun resynthesized(residualScale: Double = 1.0): FLine {
        val er = DoubleArray(residualRe.size) { if (it == 0) residualRe[0] else residualRe[it] * residualScale }
        val ei = DoubleArray(residualIm.size) { if (it == 0) residualIm[0] else residualIm[it] * residualScale }
        return integrate(fit.synthesize(er, ei))
    }

    /**
     * This stroke's predictor driven by [other]'s residual, starting at ([startX], [startY]), this
     * stroke's first point unless given. The borrowed residual loses its first `order` samples (the
     * other fit's warm-up) and its mean; the predictor's poles are pulled in by [bandwidth]
     * (`a_k · bandwidth^k`), because a predictor fitted to a near-perfect loop rings almost forever;
     * and the result is scaled so its steps are as large as this stroke's. The drift of this stroke
     * is added back, so the output travels the way this stroke did, turning the way this stroke
     * turns, with the other stroke's irregularities.
     */
    fun drivenBy(other: StrokeLpc, startX: Double = xs[0], startY: Double = ys[0], bandwidth: Double = 0.97): FLine {
        val p = fit.order
        val q = other.fit.order
        val n = other.residualRe.size
        val mr = (q until n).sumOf { other.residualRe[it] } / (n - q).coerceAtLeast(1)
        val mi = (q until n).sumOf { other.residualIm[it] } / (n - q).coerceAtLeast(1)
        val er = DoubleArray(n) { if (it < q) 0.0 else other.residualRe[it] - mr }
        val ei = DoubleArray(n) { if (it < q) 0.0 else other.residualIm[it] - mi }
        var g = 1.0
        val aRe = DoubleArray(p + 1); val aIm = DoubleArray(p + 1)
        for (k in 0..p) { aRe[k] = fit.aRe[k] * g; aIm[k] = fit.aIm[k] * g; g *= bandwidth }
        val (dr, di) = ComplexLpcFit(aRe, aIm, fit.kRe, fit.kIm, fit.error).synthesize(er, ei)
        fun rms(r: DoubleArray, i: DoubleArray) = kotlin.math.sqrt(r.indices.sumOf { r[it] * r[it] + i[it] * i[it] } / r.size.coerceAtLeast(1))
        val scale = rms(stepRe, stepIm) / rms(dr, di).coerceAtLeast(1e-12)
        for (t in dr.indices) { dr[t] *= scale; di[t] *= scale }
        return integrate(dr to di, startX, startY)
    }

    /**
     * The residual drawn as a stroke of its own: the hand's surprises integrated from this stroke's
     * first point, with no predictor between them, and the drift added back. Where the predictor
     * reads the stroke well this is a nearly straight line along the drift with a kink at each
     * surprise; where it reads it badly it looks like the stroke. The first `order` steps (the
     * warm-up, which has no full history to predict from) are left out.
     */
    fun residualStroke(): FLine {
        val p = fit.order
        val er = DoubleArray(residualRe.size) { if (it < p) 0.0 else residualRe[it] }
        val ei = DoubleArray(residualIm.size) { if (it < p) 0.0 else residualIm[it] }
        return integrate(er to ei)
    }

    /**
     * The same fit made from only the first [points] resampled points of this stroke, at the same
     * order: what the predictor would have learned had the pen stopped there. Continue the result
     * with [continued] and draw it against the rest of [resampled] to see whether the prediction
     * guesses what the hand did next.
     */
    fun head(points: Int): StrokeLpc = span(0, points)

    /**
     * The same fit made from resampled points [from] (inclusive) to [until] (exclusive) only, at the
     * same order and with the same drift handling. A window that slides along the stroke follows a
     * hand that changes what it is doing, which a fit of everything so far ([head]) is slow to do.
     */
    fun span(from: Int, until: Int): StrokeLpc {
        require(from >= 0 && until <= xs.size && until - from >= fit.order + 3) { "span $from until $until must lie in 0..${xs.size} and hold at least ${fit.order + 3} points" }
        return fitStroke(xs.copyOfRange(from, until), ys.copyOfRange(from, until), fit.order, meanStepX != 0.0 || meanStepY != 0.0)
    }

    /**
     * The ratio of residual energy to step energy after the first `order` steps (which have no full
     * history to predict from): near 0 for a stroke the predictor reads well, near 1 for noise.
     */
    val residualRatio: Double
        get() {
            var e = 0.0; var s = 0.0
            for (i in fit.order until stepRe.size) { e += residualRe[i] * residualRe[i] + residualIm[i] * residualIm[i]; s += stepRe[i] * stepRe[i] + stepIm[i] * stepIm[i] }
            return if (s > 0) e / s else 0.0
        }

    private fun integrate(steps: Pair<DoubleArray, DoubleArray>, x0: Double = xs[0], y0: Double = ys[0]): FLine {
        val (dr, di) = steps
        val ox = DoubleArray(dr.size + 1); val oy = DoubleArray(dr.size + 1)
        ox[0] = x0; oy[0] = y0
        for (n in dr.indices) { ox[n + 1] = ox[n] + dr[n] + meanStepX; oy[n + 1] = oy[n] + di[n] + meanStepY }
        return polyline(ox, oy)
    }

    private fun polyline(px: DoubleArray, py: DoubleArray): FLine {
        val f = FLine()
        for (i in px.indices) if (i == 0) f.moveTo(px[i], py[i]) else f.lineTo(px[i], py[i])
        return f
    }
}

/**
 * Fit an order-[order] complex linear predictor to this stroke's steps, after resampling it to
 * [samples] points evenly spaced along its length. See [StrokeLpc]. With [removeDrift] (the default)
 * the mean step is taken out before the fit and added back to everything drawn from it, which suits
 * a stroke that travels, like handwriting; turn it off for a closed or circling one, whose
 * continuation should come back round rather than carry on in the average direction.
 *
 * With [alongLength] false the stroke is not resampled: its own vertices are used as they are and
 * [samples] is ignored. A pen stroke from the drawing layer records a point per pen sample, so this
 * keeps the hand's timing (fast strokes have long steps, slow ones short) and predicts it as well;
 * a stroke built in code from a parameter `t` keeps that parameter, so a sum of `k` rotating
 * steps is predicted exactly at order `k`. Resampling along the length, the default, is what
 * makes two strokes drawn at different speeds comparable.
 */
fun FLine.lpc(order: Int = 8, samples: Int = 200, removeDrift: Boolean = true, alongLength: Boolean = true): StrokeLpc {
    if (!alongLength) {
        val points = nodes.map { it.to }
        require(points.size > order + 2) { "need more vertices (${points.size}) than order + 2 (${order + 2})" }
        return fitStroke(DoubleArray(points.size) { points[it].x.toDouble() }, DoubleArray(points.size) { points[it].y.toDouble() }, order, removeDrift)
    }
    require(samples > order + 2) { "need more samples ($samples) than order + 2 (${order + 2})" }
    val dense = samplePoints(samples * 8)
    require(dense.size >= 2) { "stroke has fewer than two points" }
    val cum = DoubleArray(dense.size)
    for (i in 1 until dense.size) cum[i] = cum[i - 1] + hypot((dense[i].x - dense[i - 1].x).toDouble(), (dense[i].y - dense[i - 1].y).toDouble())
    val total = cum.last()
    val xs = DoubleArray(samples); val ys = DoubleArray(samples)
    var j = 0
    for (s in 0 until samples) {
        val target = total * s / (samples - 1)
        while (j < dense.size - 2 && cum[j + 1] < target) j++
        val span = cum[j + 1] - cum[j]
        val u = if (span > 0) ((target - cum[j]) / span).coerceIn(0.0, 1.0) else 0.0
        xs[s] = dense[j].x + (dense[j + 1].x - dense[j].x) * u
        ys[s] = dense[j].y + (dense[j + 1].y - dense[j].y) * u
    }
    return fitStroke(xs, ys, order, removeDrift)
}

internal fun fitStroke(xs: DoubleArray, ys: DoubleArray, order: Int, removeDrift: Boolean): StrokeLpc {
    val samples = xs.size
    val mx = if (removeDrift) (xs.last() - xs[0]) / (samples - 1) else 0.0
    val my = if (removeDrift) (ys.last() - ys[0]) / (samples - 1) else 0.0
    val dr = DoubleArray(samples - 1) { xs[it + 1] - xs[it] - mx }
    val di = DoubleArray(samples - 1) { ys[it + 1] - ys[it] - my }
    val fit = Lpc.burgComplex(dr, di, order)
    val (er, ei) = fit.residual(dr, di)
    return StrokeLpc(fit, xs, ys, er, ei, mx, my)
}
