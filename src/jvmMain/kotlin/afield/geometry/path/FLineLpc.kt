package afield.geometry.path

import afield.audio.synth.ComplexLpcFit
import afield.audio.synth.Lpc
import kotlin.math.hypot

/**
 * Linear prediction of a drawn stroke.
 *
 * The stroke is resampled to [samples] points evenly spaced along its length, and the steps between
 * them, `d[n] = p[n+1] - p[n]`, are read as complex numbers `dx + i·dy`. A complex order-[order]
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
) {
    private val stepRe = DoubleArray(xs.size - 1) { xs[it + 1] - xs[it] }
    private val stepIm = DoubleArray(ys.size - 1) { ys[it + 1] - ys[it] }

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
        for (n in 0 until steps) { g *= damping; ox[n + 1] = ox[n] + dr[n] * g; oy[n + 1] = oy[n] + di[n] * g }
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
     * This stroke's predictor driven by [other]'s residual, starting at ([startX], [startY]),
     * this stroke's first point unless given.
     */
    fun drivenBy(other: StrokeLpc, startX: Double = xs[0], startY: Double = ys[0]): FLine =
        integrate(fit.synthesize(other.residualRe, other.residualIm), startX, startY)

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
        for (n in dr.indices) { ox[n + 1] = ox[n] + dr[n]; oy[n + 1] = oy[n] + di[n] }
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
 * [samples] points evenly spaced along its length. See [StrokeLpc].
 */
fun FLine.lpc(order: Int = 8, samples: Int = 200): StrokeLpc {
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
    val dr = DoubleArray(samples - 1) { xs[it + 1] - xs[it] }
    val di = DoubleArray(samples - 1) { ys[it + 1] - ys[it] }
    val fit = Lpc.burgComplex(dr, di, order)
    val (er, ei) = fit.residual(dr, di)
    return StrokeLpc(fit, xs, ys, er, ei)
}
