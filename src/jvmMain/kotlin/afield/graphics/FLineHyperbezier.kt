package afield.graphics

import afield.stdlib.Vec2
import afield.stdlib.Vec3
import afield.stdlib.vec
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Fits ordinary cubics to Hyperbezier curves, so the nicer curve family is usable everywhere an
 * FLine already accepts a cubic.
 *
 * A hyperbezier is specified exactly the way a cubic Bezier is -- a start, two control points, an
 * end -- but it is a different curve through that polygon: one whose curvature varies smoothly, and
 * which is an exact circular arc or Euler spiral when the arms are "circle-like" (see circleArm).
 * Push the arms out past that and the curve tightens into a corner instead of bulging. What it will
 * not do is the cubic's double inflection, which is what a single cubic can express and a
 * hyperbezier cannot.
 *
 * Nothing downstream of an FLine needs to know any of that: fitHyperbezierCubics hands back a
 * handful of ordinary cubics, G1-continuous by construction because every join uses the
 * hyperbezier's own tangent.
 *
 * This is a plain comment rather than KDoc on purpose: a KDoc block here would attach itself to
 * whatever declaration came next and publish this prose under that symbol's name.
 */

// ==================== FIT RESULTS ====================

/** One cubic of a fit — the two control points and the endpoint, as [FLine.cubicTo] wants them. */
data class HyperbezierCubic(
    /** First control point, off the start of this piece. */
    val control1: Vec3,
    /** Second control point, off the end of this piece. */
    val control2: Vec3,
    /** Endpoint of this piece, and the start of the next. */
    val end: Vec3,
)

/**
 * The cubics [fitHyperbezierCubics] fitted to a hyperbezier, plus how well they did.
 *
 * The pieces run head to tail from [start], each one starting where the previous [HyperbezierCubic.end]
 * left off, so the whole fit appends to a line as a single unbroken run of cubics.
 */
class HyperbezierFit(
    /** Where the curve starts — the `p0` handed to the fit. */
    val start: Vec3,
    /** The fitted cubics, in order. */
    val cubics: List<HyperbezierCubic>,
    /**
     * The largest distance, in world units, from the true hyperbezier to the fitted cubics. Zero
     * when [curve] is null, since there is then no hyperbezier to have missed.
     *
     * This is the error of the fit itself, computed in double precision. The control points it
     * reports on are then stored as single-precision [Vec3], so once a tolerance drops near
     * `1e-7` of the coordinate magnitude — around `2e-5` for coordinates in the hundreds — the
     * rounding to [Vec3] is the larger error and this number is optimistic.
     */
    val maxError: Float,
    /** The tolerance the fit was asked for, in world units. */
    val tolerance: Float,
    /**
     * The curve that was fitted, or null if the solver stalled or the polygon was degenerate — in
     * which case [cubics] is the single cubic that was passed in, unchanged.
     */
    val curve: Hyperbezier?,
) {
    /** False when the fit fell back to the input cubic; see [curve]. */
    val solved: Boolean get() = curve != null

    /** True when the fit came in under the tolerance it was asked for. */
    val toleranceMet: Boolean get() = maxError <= tolerance

    /** How many cubics the fit used. */
    val segmentCount: Int get() = cubics.size

    /** A new line containing just this fit, opening with a `moveTo` to [start]. */
    fun toFLine(): FLine = appendTo(FLine().moveTo(start))

    /**
     * Append this fit to [line] as cubics. If [line] is empty it is opened with a `moveTo` to
     * [start]; otherwise the caller is responsible for [line] already ending at [start].
     */
    fun appendTo(line: FLine): FLine {
        if (line.nodes.isEmpty()) line.moveTo(start)
        for (piece in cubics) line.cubicTo(piece.control1, piece.control2, piece.end)
        return line
    }

    override fun toString(): String =
        "HyperbezierFit(${cubics.size} cubics, maxError=$maxError, solved=$solved)"
}

// ==================== THE FIT ====================

/**
 * Fit cubics to the hyperbezier through the cubic-shaped control polygon `p0`, [control1],
 * [control2], [p3].
 *
 * The polygon is read the way a cubic reads it, so this is a drop-in swap for [FLine.cubicTo]: same
 * four points in, a different — and generally better behaved — curve out. Arms of [circleArm]
 * length give an exact circular arc or Euler spiral; longer arms tighten towards a corner rather
 * than bulging.
 *
 * The curve is subdivided adaptively, splitting first at its own curvature extrema and at the
 * corner (the minimum of the denominator quadratic), until every piece is within [tolerance] or the
 * budget runs out. Typical shapes need two to four cubics at the default tolerance.
 *
 * A non-planar polygon is handled by solving the hyperbezier in the plane that contains the chord
 * and the average of the two arms, then adding the input cubic's own out-of-plane profile back on
 * top. A planar polygon — the usual case — takes that path with a zero offset and is exact.
 *
 * @param tolerance largest allowed deviation from the true curve, as a fraction of the chord
 *   length, so a fit does not change character with scale.
 * @param maxSegments hard cap on the number of cubics. The budget is spent on whichever piece is
 *   worst at the time, so raising it helps precisely the shapes that need it.
 * @param cornerBias how sharply over-long arms steepen into a corner; see [hyperbezierFor].
 * @param armResponse the family's small-angle response; see [hyperbezierFor].
 *
 * @return the fit. If the solver stalls or the polygon is degenerate the result holds the input
 *   cubic unchanged and reports [HyperbezierFit.solved] false, so callers get a usable curve either
 *   way.
 */
fun fitHyperbezierCubics(
    p0: Vec3,
    control1: Vec3,
    control2: Vec3,
    p3: Vec3,
    tolerance: Number = 1e-4,
    maxSegments: Int = 16,
    cornerBias: Number = 1.0,
    armResponse: Number = 2.0,
): HyperbezierFit {
    val start = Vec3(p0)
    val fallback = HyperbezierFit(
        start = start,
        cubics = listOf(HyperbezierCubic(Vec3(control1), Vec3(control2), Vec3(p3))),
        maxError = 0f,
        tolerance = 0f,
        curve = null,
    )

    val origin = start.toDouble3()
    val chordVec = p3.toDouble3().minus3(origin)
    val chordLen = chordVec.length3()
    if (!chordLen.isFinite() || chordLen < 1e-9) return fallback

    val arm0 = control1.toDouble3().minus3(origin)
    val arm1 = p3.toDouble3().minus3(control2.toDouble3())

    // The plane holding the chord and the average arm. Fall back through the individual arms, then
    // to any plane containing the chord, so a straight polygon still resolves to something sane.
    val u = chordVec.scale3(1.0 / chordLen)
    var normal = u.cross3(arm0.plus3(arm1))
    if (normal.length3() < 1e-12) normal = u.cross3(arm0)
    if (normal.length3() < 1e-12) normal = u.cross3(arm1)
    if (normal.length3() < 1e-12) normal = u.cross3(if (abs(u[2]) < 0.9) d3(0.0, 0.0, 1.0) else d3(0.0, 1.0, 0.0))
    if (normal.length3() < 1e-12) return fallback
    val n = normal.scale3(1.0 / normal.length3())
    val v = n.cross3(u)

    // In-plane arms drive the solve; the out-of-plane part is carried by the input cubic's own
    // profile along the normal, and is identically zero for a planar polygon.
    val arm0Out = arm0.dot3(n)
    val arm1Out = control2.toDouble3().minus3(origin).dot3(n)
    val arm0In = arm0.minus3(n.scale3(arm0Out))
    val arm1In = arm1.minus3(n.scale3(arm1.dot3(n)))

    val l0 = arm0In.length3() / chordLen
    val l1 = arm1In.length3() / chordLen
    val th0 = atan2(arm0In.dot3(v), arm0In.dot3(u))
    val th1 = atan2(arm1In.dot3(v), arm1In.dot3(u))

    val curve = hyperbezierFor(th0, th1, l0, l1, cornerBias.toDouble(), armResponse.toDouble())
        ?: return fallback

    val path = HyperbezierPath(curve, origin, u, v, n, chordLen, arm0Out, arm1Out)
    val worldTolerance = max(tolerance.toDouble(), 0.0) * chordLen
    val pieces = fitAdaptive(path, worldTolerance, maxSegments.coerceAtLeast(1))

    var worst = 0.0
    val cubics = ArrayList<HyperbezierCubic>(pieces.size)
    for (piece in pieces) {
        worst = max(worst, piece.error)
        cubics.add(HyperbezierCubic(piece.control1.toVec3(), piece.control2.toVec3(), piece.end.toVec3()))
    }
    // Land the last point exactly on the requested endpoint: the quadrature is good to ~1e-15 of
    // the chord, but "good to 1e-15" and "the same Vec3 the caller passed in" are different
    // promises, and closing a shape depends on the second one.
    if (cubics.isNotEmpty()) {
        val last = cubics.removeAt(cubics.size - 1)
        cubics.add(HyperbezierCubic(last.control1, last.control2, Vec3(p3)))
    }

    return HyperbezierFit(
        start = start,
        cubics = cubics,
        maxError = worst.toFloat(),
        tolerance = worldTolerance.toFloat(),
        curve = curve,
    )
}

/** A hyperbezier placed in 3D: the solved planar curve plus the polygon's out-of-plane profile. */
private class HyperbezierPath(
    val curve: Hyperbezier,
    val origin: DoubleArray,
    val u: DoubleArray,
    val v: DoubleArray,
    val n: DoubleArray,
    val chordLen: Double,
    private val out1: Double,
    private val out2: Double,
) {
    private val planar = abs(out1) < 1e-12 && abs(out2) < 1e-12

    /** The input cubic's displacement along the normal, as a scalar cubic with ends pinned to 0. */
    private fun offset(t: Double): Double =
        if (planar) 0.0 else 3 * out1 * t * (1 - t) * (1 - t) + 3 * out2 * t * t * (1 - t)

    private fun offsetDerivative(t: Double): Double =
        if (planar) 0.0 else 3 * out1 * (1 - t) * (1 - 3 * t) + 3 * out2 * t * (2 - 3 * t)

    fun pointAt(t: Double): DoubleArray {
        val p = curve.pointAt(t)
        val x = p[0] * chordLen
        val y = p[1] * chordLen
        val w = offset(t)
        return d3(
            origin[0] + u[0] * x + v[0] * y + n[0] * w,
            origin[1] + u[1] * x + v[1] * y + n[1] * w,
            origin[2] + u[2] * x + v[2] * y + n[2] * w,
        )
    }

    fun unitTangentAt(t: Double): DoubleArray {
        val angle = curve.tangentAngleAt(t)
        // The curve is unit-speed in t before normalization, so the in-plane speed is constant.
        val speed = chordLen / curve.chordScale
        val dx = cos(angle) * speed
        val dy = sin(angle) * speed
        val dw = offsetDerivative(t)
        val dir = d3(
            u[0] * dx + v[0] * dy + n[0] * dw,
            u[1] * dx + v[1] * dy + n[1] * dw,
            u[2] * dx + v[2] * dy + n[2] * dw,
        )
        val len = dir.length3()
        return if (len < 1e-15) u.copyOf() else dir.scale3(1.0 / len)
    }
}

/** One cubic of the subdivision, over `[t0, t1]`, in double precision with its measured error. */
private class FittedPiece(
    val t0: Double,
    val t1: Double,
    val control1: DoubleArray,
    val control2: DoubleArray,
    val end: DoubleArray,
    val error: Double,
)

/**
 * Fit the whole curve, repeatedly splitting whichever piece is currently worst until they all come
 * in under [tolerance] or the budget of [maxSegments] runs out.
 *
 * Splitting the worst piece — rather than recursing to a fixed depth — is what lets the budget go
 * where the error actually is. It matters for the shapes that motivate this curve family in the
 * first place: short arms put a tight hook at each end and leave the middle nearly straight, and a
 * depth-limited recursion spends half its splits subdividing the part that was already fine.
 */
private fun fitAdaptive(
    path: HyperbezierPath,
    tolerance: Double,
    maxSegments: Int,
): List<FittedPiece> {
    val pieces = ArrayList<FittedPiece>(maxSegments.coerceAtMost(64))
    pieces.add(fitOneCubic(path, 0.0, 1.0))

    while (pieces.size < maxSegments) {
        var worstIndex = -1
        var worstError = tolerance
        for (i in pieces.indices) {
            if (pieces[i].error > worstError) {
                worstError = pieces[i].error
                worstIndex = i
            }
        }
        if (worstIndex < 0) break

        val worst = pieces[worstIndex]
        val split = chooseSplit(path, worst.t0, worst.t1)
        // Nothing left to gain once an interval is too narrow to halve meaningfully.
        if (split <= worst.t0 || split >= worst.t1) break

        pieces[worstIndex] = fitOneCubic(path, worst.t0, split)
        pieces.add(worstIndex + 1, fitOneCubic(path, split, worst.t1))
    }
    return pieces
}

/**
 * Where to cut `[t0, t1]`. The curve's own features — the corner, and the curvature extrema — are
 * preferred when one sits comfortably inside, because those are exactly where a single cubic cannot
 * track it; otherwise the midpoint.
 */
private fun chooseSplit(path: HyperbezierPath, t0: Double, t1: Double): Double {
    val span = t1 - t0
    if (span < 1e-9) return t0
    val midpoint = t0 + 0.5 * span
    return path.curve.featureParameters()
        .filter { it > t0 + 0.2 * span && it < t1 - 0.2 * span }
        .minByOrNull { abs(it - midpoint) }
        ?: midpoint
}

/**
 * How many points along a piece the fit is scored on. One set does all three jobs — seeding the
 * least squares, scoring the polish, and reporting the error — because a search that is scored on
 * one set and reported on another can walk away from a good fit to chase the set it can see. Dense
 * enough that the deviation between neighbouring samples is not where the maximum hides.
 */
private const val FIT_SAMPLES = 49

/**
 * Fit a single cubic to `[t0, t1]`, with the endpoints and end tangents pinned to the curve's own —
 * that pinning is what makes neighbouring pieces meet G1, and it leaves only the two arm lengths
 * free.
 *
 * The arms come from making the cubic's midpoint land on the curve's midpoint. `B(1/2)` is
 * `(4*p0 + 4*p3 + 3*alpha*tan0 - 3*beta*tan1) / 8`, so that condition is a 2x2 linear solve, and on
 * a circular arc it recovers the classic `(4/3)tan(theta/4)` arms exactly. The exactness is the
 * point: it is what makes the deviation fall off as the fourth power of the piece size, so
 * subdivision actually buys accuracy. Least squares over sample points sounds more principled and
 * is much worse here — it lands a fraction of a percent off, and max deviation is steep enough in
 * the arms that 0.2% short on a quarter circle more than doubles it. Collocation is used wherever
 * it is well conditioned, and least squares only picks up the case it cannot handle: a piece
 * straight enough that its midpoint says nothing about the arms.
 */
private fun fitOneCubic(path: HyperbezierPath, t0: Double, t1: Double): FittedPiece {
    val p0 = path.pointAt(t0)
    val p3 = path.pointAt(t1)
    val tan0 = path.unitTangentAt(t0)
    val tan1 = path.unitTangentAt(t1)
    val segChord = p3.minus3(p0).length3()
    val floor = max(segChord * 1e-6, 1e-12)

    val targets = Array(FIT_SAMPLES) { i ->
        path.pointAt(t0 + (t1 - t0) * (i + 1.0) / (FIT_SAMPLES + 1.0))
    }
    val fractions = DoubleArray(FIT_SAMPLES) { (it + 1.0) / (FIT_SAMPLES + 1.0) }

    var alpha = segChord / 3.0
    var beta = segChord / 3.0
    var seeded = false
    run {
        val mid = path.pointAt(0.5 * (t0 + t1))
        val rhs = d3(
            8 * mid[0] - 4 * p0[0] - 4 * p3[0],
            8 * mid[1] - 4 * p0[1] - 4 * p3[1],
            8 * mid[2] - 4 * p0[2] - 4 * p3[2],
        )
        val col1 = tan0.scale3(3.0)
        val col2 = tan1.scale3(-3.0)
        val m11 = col1.dot3(col1)
        val m12 = col1.dot3(col2)
        val m22 = col2.dot3(col2)
        val det = m11 * m22 - m12 * m12
        // det / (m11*m22) is sin^2 of the angle between the end tangents: this goes singular on a
        // straight piece, where the midpoint says nothing about the arms.
        if (det > 1e-9 * m11 * m22) {
            val na = (col1.dot3(rhs) * m22 - col2.dot3(rhs) * m12) / det
            val nb = (m11 * col2.dot3(rhs) - m12 * col1.dot3(rhs)) / det
            if (na.isFinite() && nb.isFinite() && na > floor && nb > floor) {
                alpha = na
                beta = nb
                seeded = true
            }
        }
    }
    if (!seeded) {
        val lsq = leastSquaresArms(p0, p3, tan0, tan1, targets, fractions, floor, segChord)
        alpha = lsq[0]
        beta = lsq[1]
    }

    val control1 = p0.madd3(tan0, alpha)
    val control2 = p3.madd3(tan1, -beta)
    return FittedPiece(
        t0,
        t1,
        control1,
        control2,
        p3,
        maxDeviation(p0, control1, control2, p3, targets, fractions),
    )
}

/**
 * Fallback arms for pieces where midpoint collocation is singular — a straight run, where the end
 * tangents are parallel and the midpoint constrains nothing. Least squares over the sample points,
 * re-projecting the samples onto the cubic between passes. Returns `[arm0, arm1]`.
 */
private fun leastSquaresArms(
    p0: DoubleArray,
    p3: DoubleArray,
    tan0: DoubleArray,
    tan1: DoubleArray,
    targets: Array<DoubleArray>,
    fractions: DoubleArray,
    floor: Double,
    segChord: Double,
): DoubleArray {
    var alpha = segChord / 3.0
    var beta = segChord / 3.0
    val params = fractions.copyOf()
    repeat(3) { pass ->
        var a11 = 0.0
        var a12 = 0.0
        var a22 = 0.0
        var b1 = 0.0
        var b2 = 0.0
        for (i in targets.indices) {
            val s = params[i]
            val om = 1 - s
            val basis1 = 3 * s * om * om
            val basis2 = 3 * s * s * om
            // Everything the arms do not control: the fixed part of the cubic.
            val fixedWeight0 = om * om * om + basis1
            val fixedWeight3 = s * s * s + basis2
            val residual = d3(
                targets[i][0] - (p0[0] * fixedWeight0 + p3[0] * fixedWeight3),
                targets[i][1] - (p0[1] * fixedWeight0 + p3[1] * fixedWeight3),
                targets[i][2] - (p0[2] * fixedWeight0 + p3[2] * fixedWeight3),
            )
            val col1 = tan0.scale3(basis1)
            val col2 = tan1.scale3(-basis2)
            a11 += col1.dot3(col1)
            a12 += col1.dot3(col2)
            a22 += col2.dot3(col2)
            b1 += col1.dot3(residual)
            b2 += col2.dot3(residual)
        }
        val det = a11 * a22 - a12 * a12
        if (abs(det) > 1e-18 * max(1.0, abs(a11 * a22))) {
            val na = (b1 * a22 - b2 * a12) / det
            val nb = (a11 * b2 - a12 * b1) / det
            if (na.isFinite() && nb.isFinite()) {
                alpha = max(na, floor)
                beta = max(nb, floor)
            }
        }
        if (pass < 2) {
            val c1 = p0.madd3(tan0, alpha)
            val c2 = p3.madd3(tan1, -beta)
            for (i in targets.indices) {
                val q = targets[i]
                params[i] = closestParameter(p0, c1, c2, p3, q[0], q[1], q[2], params[i])
            }
        }
    }
    return doubleArrayOf(alpha, beta)
}

/** Largest distance from any of [targets] to the cubic, each seeded from its own [fractions] entry. */
private fun maxDeviation(
    p0: DoubleArray,
    c1: DoubleArray,
    c2: DoubleArray,
    p3: DoubleArray,
    targets: Array<DoubleArray>,
    fractions: DoubleArray,
): Double {
    var worst = 0.0
    for (i in targets.indices) {
        val q = targets[i]
        worst = max(worst, deviationTo(p0, c1, c2, p3, q[0], q[1], q[2], fractions[i]))
    }
    return worst
}

/** Distance from `(qx, qy, qz)` to the cubic, via its closest point. */
private fun deviationTo(
    p0: DoubleArray,
    c1: DoubleArray,
    c2: DoubleArray,
    p3: DoubleArray,
    qx: Double,
    qy: Double,
    qz: Double,
    initial: Double,
): Double {
    val s = closestParameter(p0, c1, c2, p3, qx, qy, qz, initial)
    val om = 1 - s
    val w0 = om * om * om
    val w1 = 3 * s * om * om
    val w2 = 3 * s * s * om
    val w3 = s * s * s
    val dx = p0[0] * w0 + c1[0] * w1 + c2[0] * w2 + p3[0] * w3 - qx
    val dy = p0[1] * w0 + c1[1] * w1 + c2[1] * w2 + p3[1] * w3 - qy
    val dz = p0[2] * w0 + c1[2] * w1 + c2[2] * w2 + p3[2] * w3 - qz
    return sqrt(dx * dx + dy * dy + dz * dz)
}

/**
 * Newton steps towards the parameter of the point on the cubic closest to `(qx, qy, qz)`, starting
 * from [initial].
 *
 * Written out on scalars rather than through the [DoubleArray] helpers above. This is the innermost
 * loop of the whole fit — the polish calls it tens of thousands of times per piece — and allocating
 * three vectors per Newton step to hold the position and its two derivatives dominated the cost.
 */
private fun closestParameter(
    p0: DoubleArray,
    c1: DoubleArray,
    c2: DoubleArray,
    p3: DoubleArray,
    qx: Double,
    qy: Double,
    qz: Double,
    initial: Double,
): Double {
    var s = initial.coerceIn(0.0, 1.0)
    for (iteration in 0 until 4) {
        val om = 1 - s
        val w0 = om * om * om
        val w1 = 3 * s * om * om
        val w2 = 3 * s * s * om
        val w3 = s * s * s
        val dx = p0[0] * w0 + c1[0] * w1 + c2[0] * w2 + p3[0] * w3 - qx
        val dy = p0[1] * w0 + c1[1] * w1 + c2[1] * w2 + p3[1] * w3 - qy
        val dz = p0[2] * w0 + c1[2] * w1 + c2[2] * w2 + p3[2] * w3 - qz

        val v0 = 3 * om * om
        val v1 = 6 * s * om
        val v2 = 3 * s * s
        val ex = (c1[0] - p0[0]) * v0 + (c2[0] - c1[0]) * v1 + (p3[0] - c2[0]) * v2
        val ey = (c1[1] - p0[1]) * v0 + (c2[1] - c1[1]) * v1 + (p3[1] - c2[1]) * v2
        val ez = (c1[2] - p0[2]) * v0 + (c2[2] - c1[2]) * v1 + (p3[2] - c2[2]) * v2

        val fx = 6 * om * (c2[0] - 2 * c1[0] + p0[0]) + 6 * s * (p3[0] - 2 * c2[0] + c1[0])
        val fy = 6 * om * (c2[1] - 2 * c1[1] + p0[1]) + 6 * s * (p3[1] - 2 * c2[1] + c1[1])
        val fz = 6 * om * (c2[2] - 2 * c1[2] + p0[2]) + 6 * s * (p3[2] - 2 * c2[2] + c1[2])

        val denom = (ex * ex + ey * ey + ez * ez) + (dx * fx + dy * fy + dz * fz)
        if (abs(denom) < 1e-18) return s
        val step = (dx * ex + dy * ey + dz * ez) / denom
        if (!step.isFinite()) return s
        val next = (s - step).coerceIn(0.0, 1.0)
        if (abs(next - s) < 1e-14) return next
        s = next
    }
    return s
}

// ==================== FLINE HELPERS ====================

/**
 * A new line tracing the hyperbezier through the control polygon `p0`, [control1], [control2],
 * [p3], as cubics.
 *
 * @see fitHyperbezierCubics for what the parameters mean and how the fit behaves.
 */
fun hyperbezierBetween(
    p0: Vec3,
    control1: Vec3,
    control2: Vec3,
    p3: Vec3,
    tolerance: Number = 1e-4,
    maxSegments: Int = 16,
    cornerBias: Number = 1.0,
    armResponse: Number = 2.0,
): FLine = fitHyperbezierCubics(p0, control1, control2, p3, tolerance, maxSegments, cornerBias, armResponse).toFLine()

/**
 * Append a hyperbezier from the current end to [endPoint], as cubics — the drop-in swap for
 * [FLine.cubicTo].
 *
 * @see fitHyperbezierCubics for what the parameters mean and how the fit behaves.
 */
fun FLine.hyperbezierTo(
    control1: Vec3,
    control2: Vec3,
    endPoint: Vec3,
    tolerance: Number = 1e-4,
    maxSegments: Int = 16,
    cornerBias: Number = 1.0,
    armResponse: Number = 2.0,
): FLine {
    if (breakNext) {
        breakNext = false
        return moveTo(endPoint)
    }
    if (nodes.isEmpty()) return moveTo(endPoint)
    val start = currentPoint ?: return moveTo(endPoint)
    val fit = fitHyperbezierCubics(start, control1, control2, endPoint, tolerance, maxSegments, cornerBias, armResponse)
    for (piece in fit.cubics) cubicTo(piece.control1, piece.control2, piece.end)
    return this
}

/**
 * A copy of this line with a hyperbezier appended from its end to [endPoint], as cubics.
 *
 * @see fitHyperbezierCubics for what the parameters mean and how the fit behaves.
 */
fun FLine.byHyperbezierTo(
    control1: Vec3,
    control2: Vec3,
    endPoint: Vec3,
    tolerance: Number = 1e-4,
    maxSegments: Int = 16,
    cornerBias: Number = 1.0,
    armResponse: Number = 2.0,
): FLine = this.copy().hyperbezierTo(control1, control2, endPoint, tolerance, maxSegments, cornerBias, armResponse)

/**
 * Numeric overload of [hyperbezierTo] using the active 2D plane.
 */
fun FLine.hyperbezierTo(
    x0: Number,
    y0: Number,
    x1: Number,
    y1: Number,
    x: Number,
    y: Number,
    tolerance: Number = 1e-4,
    maxSegments: Int = 16,
): FLine = hyperbezierTo(
    planePoint(x0, y0, 0),
    planePoint(x1, y1, 0),
    planePoint(x, y, 0),
    tolerance,
    maxSegments,
)

/**
 * 2D overload of [hyperbezierTo] using the active 2D plane.
 */
fun FLine.hyperbezierTo(
    control1: Vec2,
    control2: Vec2,
    endPoint: Vec2,
    tolerance: Number = 1e-4,
    maxSegments: Int = 16,
): FLine = hyperbezierTo(
    planePoint(control1.x, control1.y, 0),
    planePoint(control2.x, control2.y, 0),
    planePoint(endPoint.x, endPoint.y, 0),
    tolerance,
    maxSegments,
)

/**
 * A smooth hyperbezier spline through [points], as cubics.
 *
 * Tangents are estimated Catmull-Rom style from each point's neighbours, giving a control polygon
 * per span, and each span is then fitted as a hyperbezier. Against the equivalent Catmull-Rom cubic
 * spline the difference shows up where the curvature would otherwise swing hardest: round corners
 * stay round, and tight corners tighten instead of overshooting.
 *
 * @param tension scales the estimated tangents. `1.0` is the usual Catmull-Rom arm; smaller is
 *   tighter and straighter between points, larger is looser and, past the [circleArm] length, more
 *   corner-like.
 * @param closed join the last point back to the first.
 *
 * @see fitHyperbezierCubics for the remaining parameters.
 */
fun hyperbezierThrough(
    points: List<Vec3>,
    tension: Number = 1.0,
    closed: Boolean = false,
    tolerance: Number = 1e-4,
    maxSegments: Int = 16,
    cornerBias: Number = 1.0,
    armResponse: Number = 2.0,
): FLine {
    val line = FLine()
    if (points.isEmpty()) return line
    line.moveTo(points[0])
    if (points.size == 1) return line
    if (points.size == 2 && !closed) return line.lineTo(points[1])

    val k = tension.toDouble() / 6.0
    val count = points.size
    val spans = if (closed) count else count - 1

    fun at(i: Int): Vec3 = if (closed) points[((i % count) + count) % count] else points[i.coerceIn(0, count - 1)]

    for (i in 0 until spans) {
        val prev = at(i - 1)
        val a = at(i)
        val b = at(i + 1)
        val next = at(i + 2)
        val c1 = vec(
            a.x + ((b.x - prev.x) * k).toFloat(),
            a.y + ((b.y - prev.y) * k).toFloat(),
            a.z + ((b.z - prev.z) * k).toFloat(),
        )
        val c2 = vec(
            b.x - ((next.x - a.x) * k).toFloat(),
            b.y - ((next.y - a.y) * k).toFloat(),
            b.z - ((next.z - a.z) * k).toFloat(),
        )
        line.hyperbezierTo(c1, c2, Vec3(b), tolerance, maxSegments, cornerBias, armResponse)
    }
    if (closed) line.close()
    return line
}

// ==================== SMALL DOUBLE-PRECISION VECTOR HELPERS ====================
// Fitting runs in double precision throughout and converts to Field's single-precision Vec3 once,
// at the end: a cubic fit that rounds to float at every step loses most of the accuracy the
// tolerance is asking for.

private fun d3(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)

private fun Vec3.toDouble3() = doubleArrayOf(x.toDouble(), y.toDouble(), z.toDouble())

private fun DoubleArray.toVec3() = vec(this[0], this[1], this[2])

private fun DoubleArray.minus3(o: DoubleArray) = d3(this[0] - o[0], this[1] - o[1], this[2] - o[2])

private fun DoubleArray.plus3(o: DoubleArray) = d3(this[0] + o[0], this[1] + o[1], this[2] + o[2])

private fun DoubleArray.scale3(s: Double) = d3(this[0] * s, this[1] * s, this[2] * s)

private fun DoubleArray.madd3(dir: DoubleArray, s: Double) =
    d3(this[0] + dir[0] * s, this[1] + dir[1] * s, this[2] + dir[2] * s)

private fun DoubleArray.dot3(o: DoubleArray) = this[0] * o[0] + this[1] * o[1] + this[2] * o[2]

private fun DoubleArray.cross3(o: DoubleArray) = d3(
    this[1] * o[2] - this[2] * o[1],
    this[2] * o[0] - this[0] * o[2],
    this[0] * o[1] - this[1] * o[0],
)

private fun DoubleArray.length3() = sqrt(this.dot3(this))
