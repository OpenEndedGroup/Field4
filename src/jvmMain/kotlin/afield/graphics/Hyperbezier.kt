package afield.graphics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The curve family behind [afield.graphics.fitHyperbezierCubics] — a port of Raph Levien's
 * hyperbezier (linebender.org/blog/hyperbezier/, August 2026).
 *
 * A hyperbezier is the plane curve traced at unit speed whose curvature is a rational function of
 * arc length `s`:
 *
 * ```
 *     kappa(s) = (a*s + b) / q(s)^1.5,     q(s) = c*s^2 + d*s + 1
 * ```
 *
 * over `s` in `[0, 1]`, then similarity-transformed so it runs from `(0, 0)` to `(1, 0)` — what the
 * rest of this file calls the **chord frame**. The point of the family is that `q` is a dial
 * between two regimes a single cubic has to compromise between: `q` constant gives circular arcs
 * and Euler spirals *exactly*, while `q` dipping towards zero gives a corner-like curvature spike.
 * It also contains the log-aesthetic curves and the circle involute exactly, and it has at most one
 * inflection — the one thing it gives up relative to a cubic, and the reason
 * [fitHyperbezierCubics] emits several cubics rather than consuming them.
 *
 * `theta(s)`, the integral of `kappa`, has a closed form ([ThetaFn]). The position integral of
 * `e^(i*theta)` does not — it is Fresnel-like — so [positionIntegral] does that part numerically
 * with Gauss-Legendre quadrature.
 *
 * Construct one from a cubic-shaped control polygon with [hyperbezierFor]; it is rarely useful to
 * pick `a`, `b`, `c` and `d` by hand.
 */
class Hyperbezier(
    /** Linear coefficient of the curvature numerator. */
    val a: Double,
    /** Constant coefficient of the curvature numerator. */
    val b: Double,
    /** Quadratic coefficient of the denominator `q`. */
    val c: Double,
    /** Linear coefficient of the denominator `q`. */
    val d: Double,
) {
    private val theta = ThetaFn(a, b, c, d)

    /** The endpoint position integral, before normalization to the chord frame. */
    private val endIntegralX: Double
    private val endIntegralY: Double

    /**
     * Length of the endpoint integral. Everything in the chord frame is the raw curve scaled by
     * `1 / chordScale`, so raw curvatures multiply by this and raw lengths divide by it.
     */
    val chordScale: Double

    /** Angle the raw curve's chord makes with the raw curve's starting tangent. */
    private val chordAngle: Double

    init {
        val i1 = positionIntegral(a, b, c, d, 1.0, theta)
        endIntegralX = i1[0]
        endIntegralY = i1[1]
        chordScale = hypot(endIntegralX, endIntegralY)
        chordAngle = atan2(endIntegralY, endIntegralX)
    }

    /**
     * True when `q` is constant, i.e. the curve is exactly a circular arc (`a == 0`) or an Euler
     * spiral. Control polygons whose arms are exactly the "parabola rule" length [circleArm] land
     * here at every angle.
     */
    val isEulerFamily: Boolean get() = abs(c) < 1e-9 && abs(d) < 1e-9

    /** Total arc length of the curve in the chord frame, where the chord itself has length 1. */
    val arcLength: Double get() = 1.0 / chordScale

    /** The denominator quadratic `q(t) = c*t^2 + d*t + 1`. */
    fun q(t: Double): Double = (c * t + d) * t + 1.0

    /**
     * Position at arc-length fraction [t] in the chord frame: `pointAt(0)` is `(0, 0)` and
     * `pointAt(1)` is `(1, 0)`, to within the accuracy of the quadrature. Returned as
     * `doubleArrayOf(x, y)` — the caller converts to Field's single-precision [Vec2]/[Vec3] once,
     * at the end, rather than losing digits at every step of a fit.
     */
    fun pointAt(t: Double): DoubleArray {
        val z = positionIntegral(a, b, c, d, t, theta)
        val n2 = endIntegralX * endIntegralX + endIntegralY * endIntegralY
        if (n2 < 1e-300) return doubleArrayOf(0.0, 0.0)
        // z / I1, as a complex division: rotate by -arg(I1) and scale by 1/|I1|.
        return doubleArrayOf(
            (z[0] * endIntegralX + z[1] * endIntegralY) / n2,
            (z[1] * endIntegralX - z[0] * endIntegralY) / n2,
        )
    }

    /**
     * Tangent angle at arc-length fraction [t], in radians, measured counter-clockwise from the
     * chord. `tangentAngleAt(0)` and `tangentAngleAt(1)` reproduce the `th0` and `th1` handed to
     * [hyperbezierFor] — that G1 interpolation is what the solver is solving for.
     */
    fun tangentAngleAt(t: Double): Double = theta(t) - chordAngle

    /** Signed curvature at arc-length fraction [t], in the chord frame. */
    fun curvatureAt(t: Double): Double {
        val qq = q(t)
        if (qq <= 0.0) return 0.0
        return (a * t + b) / (qq * sqrt(qq)) * chordScale
    }

    /**
     * Parameters strictly inside `(0, 1)` that are natural places to split the curve when
     * approximating it: the minimum of `q` — the corner, where curvature spikes — and the extrema
     * of curvature. Sorted ascending.
     */
    fun featureParameters(): List<Double> {
        val out = sortedSetOf<Double>()
        if (c > 1e-14) {
            val tv = -d / (2 * c)
            if (tv > 1e-6 && tv < 1 - 1e-6) out.add(tv)
        }
        val k0 = abs(curvatureAt(0.0))
        val k1 = abs(curvatureAt(1.0))
        val reference = max(1e-12, max(k0, k1))
        for (r in curvatureExtrema(a, b, c, d)) {
            // Constant curvature makes every point a formal extremum, the quadratic degenerates,
            // and the root it reports is float noise. Keep only extrema where curvature moves.
            val kr = abs(curvatureAt(r))
            if (max(abs(kr - k0), abs(kr - k1)) > 1e-6 * reference) out.add(r)
        }
        return out.toList()
    }

    override fun toString(): String = "Hyperbezier(a=$a, b=$b, c=$c, d=$d)"
}

/**
 * Closed form of `theta(t) = integral of (a*s+b)/q^1.5 ds` from `0` to `t`, in the gauge `q(0) = 1`.
 *
 * The result has the shape `(A*t + B)/sqrt(q) + const`. The third branch is an algebraic
 * rearrangement of the same quantity that avoids catastrophic cancellation when `d^2` is close to
 * `4c` — the denominator quadratic is then a near-perfect square, which includes the `q == 1`
 * Euler/circle case that the first branch handles exactly.
 */
internal class ThetaFn(
    private val a: Double,
    private val b: Double,
    private val c: Double,
    private val d: Double,
) {
    private companion object {
        const val POLYNOMIAL = 0
        const val CLOSED_FORM = 1
        const val CANCELLATION_SAFE = 2
    }

    private val mode: Int
    private val numerA: Double
    private val numerB: Double

    init {
        if (abs(c) < 1e-12 && abs(d) < 1e-12) {
            mode = POLYNOMIAL
            numerA = 0.0
            numerB = 0.0
        } else {
            val disc = d * d - 4 * c
            val scale = max(1.0, max(abs(d * d), abs(4 * c)))
            if (abs(disc) > 0.02 * scale) {
                mode = CLOSED_FORM
                numerB = (4 * a - 2 * b * d) / disc
                numerA = b + 0.5 * numerB * d
            } else {
                mode = CANCELLATION_SAFE
                numerA = 0.0
                numerB = 0.0
            }
        }
    }

    operator fun invoke(t: Double): Double = when (mode) {
        POLYNOMIAL -> b * t + 0.5 * a * t * t
        CLOSED_FORM -> {
            val qq = (c * t + d) * t + 1.0
            (numerA * t + numerB) / sqrt(qq) - numerB
        }
        else -> {
            val qq = (c * t + d) * t + 1.0
            val sq = sqrt(qq)
            val h = 2 * c * t + d
            (t / sq) * (b + t * (4 * a - 2 * b * d) *
                (1 + (h * h + 4 * c) / (d * h + 4 * c * sq)) / (4 * (sq + 1) * (sq + 1)))
        }
    }
}

/** Gauss-Legendre nodes and weights on `[-1, 1]`, by Newton iteration on Legendre polynomials. */
private object GaussLegendre32 {
    const val N = 32
    val x = DoubleArray(N)
    val w = DoubleArray(N)

    init {
        for (i in 0 until N) {
            var t = cos(PI * (i + 0.75) / (N + 0.5))
            for (iteration in 0 until 100) {
                val dt = t.let {
                    var p0 = 1.0
                    var p1 = it
                    for (k in 2..N) {
                        val p2 = ((2 * k - 1) * it * p1 - (k - 1) * p0) / k
                        p0 = p1
                        p1 = p2
                    }
                    p1 / (N * (it * p1 - p0) / (it * it - 1))
                }
                t -= dt
                if (abs(dt) < 1e-15) break
            }
            var p0 = 1.0
            var p1 = t
            for (k in 2..N) {
                val p2 = ((2 * k - 1) * t * p1 - (k - 1) * p0) / k
                p0 = p1
                p1 = p2
            }
            val dp = N * (t * p1 - p0) / (t * t - 1)
            x[i] = t
            w[i] = 2 / ((1 - t * t) * dp * dp)
        }
    }
}

/** Wrap an angle into `(-pi, pi]`. */
internal fun wrapAngle(angle: Double): Double {
    val turn = 2 * PI
    return ((angle + PI) % turn + turn) % turn - PI
}

/**
 * Roots of `kappa'(t)` inside `(0, 1)` — a quadratic. Used to pick subdivision points where a
 * cubic has the hardest time tracking the curve.
 */
internal fun curvatureExtrema(a: Double, b: Double, c: Double, d: Double): List<Double> {
    val qa = -4 * a * c
    val qb = -(a * d + 6 * b * c)
    val qc = 2 * a - 3 * b * d
    val roots = when {
        abs(qa) < 1e-14 -> if (abs(qb) > 1e-14) listOf(-qc / qb) else emptyList()
        else -> {
            val disc = qb * qb - 4 * qa * qc
            if (disc < 0) emptyList() else {
                val s = sqrt(disc)
                listOf((-qb - s) / (2 * qa), (-qb + s) / (2 * qa))
            }
        }
    }
    return roots.filter { it > 0 && it < 1 }.sorted()
}

/**
 * Quadrature cuts: the vertex of the denominator quadratic, and only when it is a minimum
 * (`c > 0`). Gauss-Legendre nodes cluster quadratically at panel ends, so putting the `q`-minimum
 * on a panel boundary resolves the curvature spike sitting there. For `c < 0` the vertex is a
 * maximum and any dips are at the interval ends, which the clustering already covers.
 */
private fun cutPoints(c: Double, d: Double, t: Double): DoubleArray {
    if (c > 1e-14) {
        val tv = -d / (2 * c)
        if (tv > 1e-9 && tv < t - 1e-9) return doubleArrayOf(0.0, tv, t)
    }
    return doubleArrayOf(0.0, t)
}

/** Re-express the curve on `[t0, t1]` as a fresh, well-conditioned `[0, 1]` chart. */
private fun subsegment(a: Double, b: Double, c: Double, d: Double, t0: Double, t1: Double): DoubleArray {
    val dt = t1 - t0
    val e = (c * t0 + d) * t0 + 1
    val s = 1 / e
    val ps = dt * s * sqrt(s)
    return doubleArrayOf(a * dt * ps, (b + a * t0) * ps, c * dt * dt * s, (d + 2 * c * t0) * dt * s)
}

/**
 * The position integral `z(t) = integral of e^(i*theta) ds` from `0` to `t`, by Gauss-Legendre on
 * each cut interval. Intervals that do not start at zero are re-charted through [subsegment] so
 * each gets its own well-conditioned `theta`.
 */
internal fun positionIntegral(
    a: Double,
    b: Double,
    c: Double,
    d: Double,
    t: Double,
    theta: ThetaFn,
): DoubleArray {
    val cuts = cutPoints(c, d, t)
    var zx = 0.0
    var zy = 0.0
    for (i in 0 until cuts.size - 1) {
        val w0 = cuts[i]
        val w1 = cuts[i + 1]
        if (w1 - w0 < 1e-15) continue
        var sx = 0.0
        var sy = 0.0
        if (w0 == 0.0) {
            for (k in 0 until GaussLegendre32.N) {
                val th = theta(0.5 * w1 * (GaussLegendre32.x[k] + 1))
                sx += GaussLegendre32.w[k] * cos(th)
                sy += GaussLegendre32.w[k] * sin(th)
            }
        } else {
            val sp = subsegment(a, b, c, d, w0, w1)
            val thetaSub = ThetaFn(sp[0], sp[1], sp[2], sp[3])
            val offset = theta(w0)
            for (k in 0 until GaussLegendre32.N) {
                val th = offset + thetaSub(0.5 * (GaussLegendre32.x[k] + 1))
                sx += GaussLegendre32.w[k] * cos(th)
                sy += GaussLegendre32.w[k] * sin(th)
            }
        }
        zx += (w1 - w0) * 0.5 * sx
        zy += (w1 - w0) * 0.5 * sy
    }
    return doubleArrayOf(zx, zy)
}

/**
 * The "parabola rule" arm length for a tangent that leaves the chord at [angle] radians: the arm at
 * which a cubic's midpoint coincides exactly with a circular arc's apex, at every angle. Arms are
 * measured against this, so an arm of `circleArm(theta)` means "circle-like", and
 * [hyperbezierFor] returns an exact arc or Euler spiral there.
 *
 * From Levien's "Cleaner parallel curves with Euler spirals" (2021).
 */
fun circleArm(angle: Double): Double = 2.0 / (3.0 * max(1e-3, 1.0 + cos(angle)))

/**
 * Given the denominator quadratic, solve `(a, b)` so the curve leaves at [th0] and arrives at
 * [th1].
 *
 * `theta` is linear in `(a, b)`, so the net-turn condition `theta(1) = th1 - th0` eliminates `b`
 * exactly — and pins the winding class, which is what keeps stray full loops out of the solution
 * set. What is left is a scalar Newton solve on `a`, whose residual is the start-tangent error read
 * off the argument of the endpoint position integral.
 *
 * Two initial guesses are tried in order: the ghost cubic's midpoint tangent (which, with the
 * net-turn condition, is a closed-form 2x2), then the small-angle Euler estimate. A converged
 * solution whose interior `theta` wanders outside the principal band — a hidden +-2pi round trip —
 * is rejected and the next guess tried.
 *
 * Returns `[a, b]`, or `null` if neither guess converged.
 */
private fun fitNumerator(
    c: Double,
    d: Double,
    th0: Double,
    th1: Double,
    l0: Double,
    l1: Double,
): DoubleArray? {
    val thetaF = ThetaFn(1.0, 0.0, c, d)
    val thetaG = ThetaFn(0.0, 1.0, c, d)
    val f1 = thetaF(1.0)
    val g1 = thetaG(1.0)
    val netTurn = th1 - th0
    if (abs(g1) < 1e-30) return null

    fun bOf(a: Double) = (netTurn - a * f1) / g1

    // Start-tangent residual: how far the curve's own chord direction is from where th0 wants it.
    fun residual(a: Double): Double? {
        val b = bOf(a)
        val i1 = positionIntegral(a, b, c, d, 1.0, ThetaFn(a, b, c, d))
        if (hypot(i1[0], i1[1]) < 1e-12) return null
        return wrapAngle(-atan2(i1[1], i1[0]) - th0)
    }

    val inits = ArrayList<Double>(2)
    run {
        val dx = 2 - l0 * cos(th0) - l1 * cos(th1)
        val dy = -l0 * sin(th0) - l1 * sin(th1)
        val raw = if (hypot(dx, dy) > 1e-9) atan2(-dy, dx) else th0 + 0.5 * netTurn
        val midTangent = 0.5 * netTurn + wrapAngle(raw - th0 - 0.5 * netTurn)
        val fh = thetaF(0.5)
        val gh = thetaG(0.5)
        val det = fh * g1 - gh * f1
        if (abs(det) > 1e-10 * max(max(abs(fh * g1), abs(gh * f1)), 1e-300)) {
            inits.add((midTangent * g1 - gh * netTurn) / det)
        }
    }
    inits.add(6 * (th0 + th1))

    for (a0 in inits) {
        var a = a0
        var r = residual(a) ?: continue
        for (iteration in 0 until 30) {
            if (abs(r) < 1e-11) break
            val h = 1e-7 * max(1.0, abs(a))
            val rp = residual(a + h)
            if (rp == null) { r = 1.0; break }
            val dr = (rp - r) / h
            if (dr == 0.0) { r = 1.0; break }
            val step = -r / dr
            var lambda = 1.0
            var improved = false
            for (ls in 0 until 12) {
                val rn = residual(a + lambda * step)
                if (rn != null && abs(rn) < abs(r)) {
                    a += lambda * step
                    r = rn
                    improved = true
                    break
                }
                lambda *= 0.5
            }
            if (!improved) break
        }
        if (abs(r) >= 1e-8) continue

        val b = bOf(a)
        // Reject solutions whose net turn is right but which sneak a round trip in on the way.
        if (abs(a) > 1e-14) {
            val tx = -b / a
            if (tx > 0 && tx < 1) {
                val excursion = a * thetaF(tx) + b * thetaG(tx)
                val lo = min(0.0, netTurn) - PI - 0.3
                val hi = max(0.0, netTurn) + PI + 0.3
                if (excursion <= lo || excursion >= hi) continue
            }
        }
        return doubleArrayOf(a, b)
    }
    return null
}

/**
 * The control-polygon to hyperbezier map: turn a cubic-Bezier-shaped control polygon, expressed in
 * the chord frame, into the curve that replaces it.
 *
 * @param th0 angle of the outgoing tangent at the start, measured counter-clockwise from the chord.
 * @param th1 angle of the incoming tangent at the end, same convention.
 * @param l0 length of the start arm as a fraction of the chord.
 * @param l1 length of the end arm as a fraction of the chord.
 * @param cornerBias how sharply arms longer than [circleArm] steepen into a corner. `1.0` is the
 *   blog's default; larger values reach corner-like shapes at shorter arms. Purely an aesthetic
 *   dial — it does not affect the `u = v = 1` circle/Euler case.
 * @param armResponse baseline slope of `ln q` in `ln u`, the family's small-angle response. `2.0`
 *   matches a cubic Bezier exactly in the small-angle limit and is better at high tension; `2.2`
 *   fits cubics slightly better across the mid range.
 *
 * @return the solved curve, or `null` if the Newton solve stalled — which callers should treat as
 *   "keep the cubic you already had" rather than as an error.
 */
fun hyperbezierFor(
    th0: Double,
    th1: Double,
    l0: Double,
    l1: Double,
    cornerBias: Double = 1.0,
    armResponse: Double = 2.0,
): Hyperbezier? {
    // Arms normalized by the parabola rule, with a guard so u fades to 0 continuously as a tangent
    // approaches +-180 degrees. That fade is what makes swinging a control point through the back
    // direction continuous: the +-2pi winding ambiguity collapses into a sub-pixel curl.
    val u = l0 * 1.5 * max(1 + cos(th0), 1e-12)
    val v = l1 * 1.5 * max(1 + cos(th1), 1e-12)

    // A smooth p-norm clamp on ln q: corner headroom above, deep enough below that the residual
    // seam at +-180 degrees is ~3e-5 of the chord. Note softClamp(0) == 0, so u == v == 1 gives
    // q0 == q1 == 1 -- an exact Euler spiral or circle at every angle.
    val limitAbove = min(8.0, 5 + 0.75 * cornerBias)
    fun softClamp(value: Double): Double {
        val x = max(value, -60.0)
        val l = if (x >= 0) limitAbove else 15.0
        return x / (1 + (x / l).pow(4)).pow(0.25)
    }

    val lu = ln(max(u, 1e-26))
    val lv = ln(max(v, 1e-26))
    val q0 = exp(softClamp(armResponse * lu + cornerBias * max(lu, 0.0).pow(3)))
    val q1 = exp(softClamp(armResponse * lv + cornerBias * max(lv, 0.0).pow(3)))

    // This gauge fixes the family's one-parameter scaling freedom by normalizing
    // integral of q^-1.5 over [0,1] to 1, with (q0, qm, q1) the Bernstein weights of q. The
    // antiderivative of q^-1.5 is elementary and the condition telescopes to this closed form.
    val qm = 1 / sqrt(q0) + 1 / sqrt(q1) - sqrt(q0 * q1)

    // Convert to the q(0) = 1 gauge the rest of the file uses, then solve the angles.
    val c = (q0 - 2 * qm + q1) / q0
    val d = 2 * (qm - q0) / q0
    val ab = fitNumerator(c, d, th0, th1, l0, l1) ?: return null
    return Hyperbezier(ab[0], ab[1], c, d)
}
