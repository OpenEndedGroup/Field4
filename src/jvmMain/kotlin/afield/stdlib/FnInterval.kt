@file:Suppress("MemberVisibilityCanBePrivate")
@file:afield.CompletionCategory("Function")

package afield.stdlib

import java.util.PriorityQueue

/**
 * Certified bounds for an `Fn` over an interval — the third walker of [FnFormulas], in interval
 * arithmetic, and what turns a sampled envelope into a guarantee.
 *
 * ```kotlin
 * with(Functions) {
 *     val f = sin(0.025, amplitude = 0.2, offset = 0.45) + gaussian(1234.57, 0.002, 0.5, 0.0..3600.0)
 *     f.enclose(1234.0, 1235.0)          // lo..hi that every f(x) on that interval lies inside
 *     f.certifiedRange(0.0, 3600.0)      // the global max, with the x that reaches it
 *     f.certifiedEnvelope(0.0, 3600.0, 1000)  // per-bin bounds a plot cannot slip between
 * }
 * ```
 *
 * ## Why an enclosure, not a sampled envelope
 *
 * `SignalViewer` bins a function by evaluating it on a grid and keeping each bin's min and max. A
 * feature narrower than the grid spacing can sit between two samples and never be drawn, and nothing
 * in the picture says so — a limit `public/docs/function_conversation.md` states about its own plots.
 * An enclosure is a different kind of object: `[lo, hi]` such that **every** value the function
 * takes on the interval lies inside, whether or not any sample landed near it. A spike 10 ms wide
 * in an hour-long signal cannot hide from it, because nothing is sampled.
 *
 * ## One declaration, three walkers
 *
 * [evalFormulaInterval] walks the same [FnExpr] tree that [evalFormula] walks in `Double` and
 * [evalFormulaAD] in tracked arithmetic, so the enclosure is of the function `Fn.eval` computes and
 * not of a third transcription of it. There is still no per-op code: every op with an [FnFormulas]
 * entry is enclosed, and an op without one is **refused by name** ([FnEnclosure.refusal]) — never
 * bounded by a guess. Most of the hand-written `fn { x -> … }` lambdas in the wild are therefore
 * refused; that is the cost of a guarantee, and giving an op a formula is how to lift it (it gains
 * an exact gradient at the same time).
 *
 * ## What exactly is contained
 *
 * The contract is the **`Double` values `Fn.eval` returns** for every double `x` in `[a, b]` — the
 * values a sampler draws — not the real-number function those doubles approximate. That choice is
 * what lets most of the arithmetic go unwidened:
 *
 * - `+ − × ÷` are correctly rounded, and round-to-nearest is **monotone**, so evaluating an
 *   interval's endpoints (or a product's four corners) in ordinary `Double` already brackets every
 *   rounded result in between. No outward rounding is needed, which is why a held constant certifies
 *   as exactly `[1, 1]` rather than as `1 ± ulp`.
 * - `abs`, `floor`, `min`, `max`, `coerceIn` and the branch kinds are exact.
 * - The library transcendentals (`sin`, `exp`, `pow`, `tanh`, `atan2`, …) are *not* correctly
 *   rounded: the Javadoc allows up to 1–2.5 ulps, and promises only that they are semi-monotonic.
 *   Their endpoint values are widened outward by [TRANSCENDENTAL_ULPS] ulps each side, which also
 *   absorbs the rare last-bit disagreement between an interpreted and a JIT-intrinsic `Math.sin`.
 *
 * NaN is tracked separately ([FnRange.maybeNaN]) rather than widened into the range: `log` of a
 * range reaching below zero *may* produce NaN, and saying so is more useful than an infinite bound.
 *
 * ## Tightness and branch-and-bound
 *
 * Plain interval arithmetic is sound but loose: `x - x` over `[0, 1]` encloses as `[-1, 1]`, because
 * the walker does not know the two `x`s are the same number (the *dependency problem*). The bound
 * converges to the true range as the interval shrinks, so everything above [Functions.Fn.enclose]
 * works by subdividing: [certifiedRange] is a best-first branch-and-bound on the maximum and the
 * minimum, the `certified…` predicates split until each piece is decided, and [certifiedEnvelope]
 * refines each bin until its bound is within a tolerance of what that bin's samples show.
 * `FnIntervalTest` measures how loose the one-shot bound is and ratchets the number.
 */

/** Ulps each side a transcendental's endpoint values are widened by. See the file KDoc. */
const val TRANSCENDENTAL_ULPS: Int = 4

/**
 * A set of `Double` values: every number in `[lo, hi]`, plus NaN when [maybeNaN]. Empty (no
 * non-NaN value at all) when `lo > hi`, which is how `log` of an all-negative range is represented.
 */
data class FnRange(val lo: Double, val hi: Double, val maybeNaN: Boolean = false) {

    /** True when no non-NaN value is possible. */
    val isEmpty: Boolean get() = !(lo <= hi)

    /** `hi - lo`, or 0 when empty. */
    val width: Double get() = if (isEmpty) 0.0 else hi - lo

    /** True when this is one number, with no NaN. */
    val isPoint: Boolean get() = lo == hi && !maybeNaN

    /** Whether [value] is a member — NaN is a member exactly when [maybeNaN]. */
    operator fun contains(value: Double): Boolean =
        if (value.isNaN()) maybeNaN else !isEmpty && value >= lo && value <= hi

    /** The smallest range holding both. */
    fun hull(other: FnRange): FnRange = when {
        isEmpty -> other.copy(maybeNaN = other.maybeNaN || maybeNaN)
        other.isEmpty -> copy(maybeNaN = maybeNaN || other.maybeNaN)
        else -> FnRange(minOf(lo, other.lo), maxOf(hi, other.hi), maybeNaN || other.maybeNaN)
    }

    /** This range with [value] added (NaN sets [maybeNaN]). */
    fun including(value: Double): FnRange = if (value.isNaN()) copy(maybeNaN = true) else hull(point(value))

    override fun toString(): String =
        (if (isEmpty) "∅" else "[$lo, $hi]") + if (maybeNaN) " ∪ {NaN}" else ""

    companion object {
        /** Exactly [value]. */
        fun point(value: Double): FnRange = if (value.isNaN()) NAN else FnRange(value, value)

        /** Every double, and NaN. */
        val ENTIRE = FnRange(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, true)

        /** Only NaN. */
        val NAN = FnRange(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, true)

        /** Nothing at all. */
        val EMPTY = FnRange(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, false)
    }
}

/**
 * Thrown inside a walk when no sound bound exists — an op with no formula, an unbounded
 * shape domain, a `clamp` whose range may invert (which throws in `Fn.eval` itself). Caught by
 * [FnIntervals.enclose] and reported as [FnEnclosure.refusal].
 */
class FnIntervalRefusal(message: String) : RuntimeException(message)

/**
 * The declared rule for each [FnExpr] kind — one line saying how the kind is enclosed and whether
 * the bound is exact at the endpoints, widened, or a hull.
 *
 * Kept as data rather than left implicit in the `when` so it can be printed in the design note and
 * so `FnIntervalTest` can hold it against `FnExpr::class.sealedSubclasses` in **both** directions:
 * a kind added to [FnExpr] with no rule fails, and a rule left behind for a kind that is gone fails.
 * (The `when` in [evalFormulaInterval] is exhaustive, so the compiler already refuses a kind with
 * no *code*; this refuses one with no *explanation*.)
 */
object FnIntervalRules {
    val rules: Map<String, String> = linkedMapOf(
        "X" to "the x interval itself (moved by an enclosing AtX)",
        "Lit" to "the point [v, v]",
        "Child" to "the child's own enclosure over the same x interval",
        "Alpha" to "(x − min)/length on the op's finite domain: monotone, endpoint-exact; refused when the domain is unbounded",
        "Add" to "endpoint sums; round-to-nearest is monotone so no widening; NaN when +inf and −inf can meet",
        "Sub" to "Add of the negation — fl(a − b) is fl(a + (−b)) exactly; the fract shape t − floor(t) is bounded as one value: endpoint-exact inside a unit cell, [0, 1] across a boundary",
        "Mul" to "min/max of the four corner products (square rule when both factors are the same subexpression; the monotone rule for the t·t·(3 − 2t) smoothstep shape); NaN when 0 can meet ±inf",
        "Div" to "corner quotients when the divisor excludes 0; everything otherwise",
        "Neg" to "[−hi, −lo], exact",
        "Abs" to "exact; folds a range that straddles 0 to [0, max(|lo|, |hi|)]",
        "Floor" to "[floor(lo), floor(hi)], exact — the step inside the interval is covered by monotonicity",
        "Sin" to "endpoint values plus ±1 when a crest/trough (π/2 + 2kπ / −π/2 + 2kπ) lies inside; widened",
        "Cos" to "endpoint values plus ±1 when 2kπ / π + 2kπ lies inside; widened",
        "Exp" to "monotone endpoints, widened, floored at 0",
        "Ln" to "monotone endpoints on the non-negative part, widened; NaN when the range reaches below 0",
        "Tanh" to "monotone endpoints, widened",
        "Atan" to "monotone endpoints, widened",
        "Atan2" to "corners when the box avoids the branch cut (x > 0, or y strictly one sign); [−π, π] otherwise; widened",
        "Hypot" to "monotone in |a| and |b|: [hypot(min|a|, min|b|), hypot(max|a|, max|b|)], widened",
        "Pow" to "integer-exponent cases by parity and sign, non-integer exponents on the non-negative base, corners for a positive base; widened",
        "Min" to "[min(lo), min(hi)], exact",
        "Max" to "[max(lo), max(hi)], exact",
        "Clamp01" to "[clamp(lo), clamp(hi)], exact",
        "CoerceIn" to "monotone in all three; refused when the range may invert, because Fn.eval throws there",
        "IfZero" to "the taken branch when the test is certainly zero or certainly not; the hull of both otherwise",
        "IfEq" to "the taken branch when the comparison is decided (a subexpression against itself: equal unless NaN); the hull of both otherwise",
        "IfLt" to "the taken branch when the comparison is decided (a subexpression against itself: never less); otherwise the hull of both, each arm walked with a narrowed to the side of b that selects it",
        "AtX" to "the body over the enclosure of the moved position",
    )
}

// ---------------------------------------------------------------------------------------------
// Range arithmetic
// ---------------------------------------------------------------------------------------------

/** The per-kind rules, as functions on [FnRange]. Public so a test can exercise one rule on its own. */
object FnRangeMath {

    private const val TAU = Math.PI * 2.0

    /** [r] widened outward by [ulps] ulps at each finite end. */
    fun widen(r: FnRange, ulps: Int = TRANSCENDENTAL_ULPS): FnRange {
        if (r.isEmpty) return r
        var lo = r.lo
        var hi = r.hi
        repeat(ulps) { lo = Math.nextDown(lo); hi = Math.nextUp(hi) }
        return FnRange(lo, hi, r.maybeNaN)
    }

    private inline fun unary(a: FnRange, f: (FnRange) -> FnRange): FnRange =
        if (a.isEmpty) a else f(a).let { if (a.maybeNaN) it.copy(maybeNaN = true) else it }

    private inline fun binary(a: FnRange, b: FnRange, f: () -> FnRange): FnRange =
        if (a.isEmpty || b.isEmpty) FnRange.EMPTY.copy(maybeNaN = a.maybeNaN || b.maybeNaN)
        else f().let { if (a.maybeNaN || b.maybeNaN) it.copy(maybeNaN = true) else it }

    private fun hasInfinity(r: FnRange) = r.lo.isInfinite() || r.hi.isInfinite()
    private fun containsZero(r: FnRange) = !r.isEmpty && r.lo <= 0.0 && r.hi >= 0.0

    fun add(a: FnRange, b: FnRange): FnRange = binary(a, b) {
        val infinitiesMeet = (a.lo == Double.NEGATIVE_INFINITY && b.hi == Double.POSITIVE_INFINITY) ||
            (a.hi == Double.POSITIVE_INFINITY && b.lo == Double.NEGATIVE_INFINITY)
        var lo = a.lo + b.lo
        var hi = a.hi + b.hi
        if (lo.isNaN()) lo = Double.NEGATIVE_INFINITY
        if (hi.isNaN()) hi = Double.POSITIVE_INFINITY
        FnRange(lo, hi, infinitiesMeet)
    }

    fun neg(a: FnRange): FnRange = unary(a) { FnRange(-it.hi, -it.lo) }

    fun sub(a: FnRange, b: FnRange): FnRange = add(a, neg(b))

    fun mul(a: FnRange, b: FnRange): FnRange = binary(a, b) {
        val zeroMeetsInfinity = (hasInfinity(a) && containsZero(b)) || (hasInfinity(b) && containsZero(a))
        val p1 = a.lo * b.lo
        val p2 = a.lo * b.hi
        val p3 = a.hi * b.lo
        val p4 = a.hi * b.hi
        if (p1.isNaN() || p2.isNaN() || p3.isNaN() || p4.isNaN()) FnRange.ENTIRE
        else FnRange(minOf(minOf(p1, p2), minOf(p3, p4)), maxOf(maxOf(p1, p2), maxOf(p3, p4)), zeroMeetsInfinity)
    }

    /** `v * v` for one value drawn from [a] — tighter than [mul] of [a] with itself. */
    fun square(a: FnRange): FnRange = unary(a) {
        val l = it.lo * it.lo
        val h = it.hi * it.hi
        when {
            it.lo >= 0.0 -> FnRange(l, h)
            it.hi <= 0.0 -> FnRange(h, l)
            else -> FnRange(0.0, maxOf(l, h))
        }
    }

    fun div(a: FnRange, b: FnRange): FnRange = binary(a, b) {
        if (b.lo > 0.0 || b.hi < 0.0) {
            val q1 = a.lo / b.lo
            val q2 = a.lo / b.hi
            val q3 = a.hi / b.lo
            val q4 = a.hi / b.hi
            if (q1.isNaN() || q2.isNaN() || q3.isNaN() || q4.isNaN()) FnRange.ENTIRE
            else FnRange(
                minOf(minOf(q1, q2), minOf(q3, q4)), maxOf(maxOf(q1, q2), maxOf(q3, q4)),
                hasInfinity(a) && hasInfinity(b)
            )
        } else {
            FnRange(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, containsZero(a) || hasInfinity(a))
        }
    }

    fun abs(a: FnRange): FnRange = unary(a) {
        when {
            it.lo >= 0.0 -> FnRange(it.lo, it.hi)
            it.hi <= 0.0 -> FnRange(-it.hi, -it.lo)
            else -> FnRange(0.0, maxOf(-it.lo, it.hi))
        }
    }

    fun floor(a: FnRange): FnRange = unary(a) { FnRange(Math.floor(it.lo), Math.floor(it.hi)) }

    /**
     * `t - floor(t)` for one value drawn from [a] — the fractional part, which [sub] of [a] and
     * [floor] of [a] cannot see: over `[0, 10]` those give `[-10, 10]`.
     *
     * Inside one unit cell `floor` is a constant `k`, and `fl(t - k)` is monotone in `t`, so the
     * endpoints are exact. Across a cell boundary the answer is `[0, 1]` — closed at 1, because a
     * tiny negative `t` rounds `t + 1` up to exactly `1.0`. An infinite `t` gives `inf - inf`, NaN.
     */
    fun fract(a: FnRange): FnRange = unary(a) {
        if (hasInfinity(it)) return@unary FnRange(0.0, 1.0, true)
        val k = Math.floor(it.lo)
        if (k == Math.floor(it.hi)) FnRange(it.lo - k, it.hi - k) else FnRange(0.0, 1.0)
    }

    /**
     * True when some `c + 2πk` may lie in `[lo, hi]` — generous by a hair, since including an
     * extremum that is not there only loosens the bound, while missing one would break it.
     */
    private fun mayContainPhase(lo: Double, hi: Double, c: Double): Boolean {
        val slack = 1e-9 + 1e-15 * maxOf(Math.abs(lo), Math.abs(hi))
        val kMin = Math.ceil((lo - c) / TAU - slack)
        val kMax = Math.floor((hi - c) / TAU + slack)
        return kMin <= kMax
    }

    private fun trig(a: FnRange, f: (Double) -> Double, crest: Double, trough: Double): FnRange = unary(a) {
        if (it.lo.isInfinite() || it.hi.isInfinite()) {
            // sin(±inf) is NaN; every finite argument in between reaches both extremes.
            val nan = it.lo == Double.NEGATIVE_INFINITY || it.hi == Double.POSITIVE_INFINITY
            val full = widen(FnRange(-1.0, 1.0), 1)
            return@unary if (it.lo == it.hi) FnRange.NAN else full.copy(maybeNaN = nan)
        }
        if (it.hi - it.lo >= TAU) return@unary widen(FnRange(-1.0, 1.0), 1)
        val s1 = f(it.lo)
        val s2 = f(it.hi)
        var r = widen(FnRange(minOf(s1, s2), maxOf(s1, s2)))
        if (mayContainPhase(it.lo, it.hi, crest)) r = FnRange(r.lo, Math.nextUp(1.0))
        if (mayContainPhase(it.lo, it.hi, trough)) r = FnRange(Math.nextDown(-1.0), r.hi)
        r
    }

    fun sin(a: FnRange): FnRange = trig(a, Math::sin, Math.PI / 2, -Math.PI / 2)

    fun cos(a: FnRange): FnRange = trig(a, Math::cos, 0.0, Math.PI)

    /** A monotone non-decreasing library function, widened. */
    private inline fun monotone(a: FnRange, f: (Double) -> Double): FnRange = unary(a) {
        widen(FnRange(f(it.lo), f(it.hi)))
    }

    fun exp(a: FnRange): FnRange = monotone(a, Math::exp).let { if (it.isEmpty) it else it.copy(lo = maxOf(0.0, it.lo)) }

    fun ln(a: FnRange): FnRange {
        if (a.isEmpty) return a
        if (a.hi < 0.0) return FnRange.NAN
        val nan = a.maybeNaN || a.lo < 0.0
        return widen(FnRange(Math.log(maxOf(0.0, a.lo)), Math.log(a.hi))).copy(maybeNaN = nan)
    }

    fun tanh(a: FnRange): FnRange = monotone(a, Math::tanh)

    fun atan(a: FnRange): FnRange = monotone(a, Math::atan)

    fun atan2(y: FnRange, x: FnRange): FnRange = binary(y, x) {
        if (x.lo > 0.0 || y.lo > 0.0 || y.hi < 0.0) {
            // Off the branch cut (x ≤ 0, y = 0) and away from the origin, atan2 is monotone in each
            // argument separately, so the extremes over the box sit at its corners.
            val c1 = Math.atan2(y.lo, x.lo)
            val c2 = Math.atan2(y.lo, x.hi)
            val c3 = Math.atan2(y.hi, x.lo)
            val c4 = Math.atan2(y.hi, x.hi)
            widen(FnRange(minOf(minOf(c1, c2), minOf(c3, c4)), maxOf(maxOf(c1, c2), maxOf(c3, c4))))
        } else {
            widen(FnRange(-Math.PI, Math.PI))
        }
    }

    fun hypot(a: FnRange, b: FnRange): FnRange {
        val finite = binary(a, b) {
            val aa = abs(a)
            val bb = abs(b)
            widen(FnRange(Math.hypot(aa.lo, bb.lo), Math.hypot(aa.hi, bb.hi))).let { it.copy(lo = maxOf(0.0, it.lo)) }
        }
        // hypot(±inf, NaN) is +inf, not NaN — an infinity on either side wins over a NaN on the
        // other. Found by FnIntervalTest.containmentHoldsFarFromTheOrigin, where exp overflowed.
        val infinityMeetsNaN = (a.maybeNaN && !b.isEmpty && hasInfinity(b)) || (b.maybeNaN && !a.isEmpty && hasInfinity(a))
        return if (infinityMeetsNaN) finite.including(Double.POSITIVE_INFINITY) else finite
    }

    private fun isInteger(v: Double) = v.isFinite() && v == Math.floor(v)

    fun pow(a: FnRange, b: FnRange): FnRange {
        // Math.pow(anything, ±0) is exactly 1, NaN base included.
        val zeroExponent = containsZero(b)
        val base = powRange(a, b)
        return if (zeroExponent && a.maybeNaN) base.including(1.0) else base
    }

    private fun powRange(a: FnRange, b: FnRange): FnRange = binary(a, b) {
        if (b.isPoint && !a.isEmpty) {
            val n = b.lo
            when {
                n == 0.0 -> return@binary FnRange(1.0, 1.0)
                isInteger(n) -> {
                    val pl = Math.pow(a.lo, n)
                    val ph = Math.pow(a.hi, n)
                    val even = Math.abs(n % 2.0) == 0.0
                    return@binary when {
                        n > 0 && !even -> widen(FnRange(pl, ph))
                        n > 0 && even -> when {
                            a.lo >= 0.0 -> widen(FnRange(pl, ph))
                            a.hi <= 0.0 -> widen(FnRange(ph, pl))
                            else -> widen(FnRange(0.0, maxOf(pl, ph))).copy(lo = 0.0)
                        }
                        containsZero(a) -> FnRange.ENTIRE.copy(maybeNaN = false)
                        else -> widen(FnRange(minOf(pl, ph), maxOf(pl, ph)))
                    }
                }
                n.isNaN() -> return@binary FnRange.NAN
                // pow(negative, ±inf) is a signed limit rather than NaN in Java; not worth a case.
                n.isInfinite() -> return@binary FnRange.ENTIRE
                else -> {
                    // A negative base with a non-integer exponent is NaN — except at -inf, which
                    // the Math.pow Javadoc defines as a limit: pow(-inf, 0.5) is +inf, pow(-inf, -0.5)
                    // is +0.
                    val atMinusInfinity = if (a.lo == Double.NEGATIVE_INFINITY) Math.pow(a.lo, n) else Double.NaN
                    if (a.hi < 0.0) {
                        return@binary if (atMinusInfinity.isNaN()) FnRange.NAN else FnRange.point(atMinusInfinity).copy(maybeNaN = true)
                    }
                    val nan = a.lo < 0.0
                    val lo = maxOf(0.0, a.lo)
                    val pl = Math.pow(lo, n)
                    val ph = Math.pow(a.hi, n)
                    return@binary widen(FnRange(minOf(pl, ph), maxOf(pl, ph))).let {
                        FnRange(maxOf(0.0, it.lo), it.hi, nan)
                    }.let { if (atMinusInfinity.isNaN()) it else it.including(atMinusInfinity) }
                }
            }
        }
        if (a.lo > 0.0) {
            val c1 = Math.pow(a.lo, b.lo)
            val c2 = Math.pow(a.lo, b.hi)
            val c3 = Math.pow(a.hi, b.lo)
            val c4 = Math.pow(a.hi, b.hi)
            if (c1.isNaN() || c2.isNaN() || c3.isNaN() || c4.isNaN() || (hasInfinity(b) && 1.0 in a)) {
                FnRange.ENTIRE
            } else {
                widen(FnRange(minOf(minOf(c1, c2), minOf(c3, c4)), maxOf(maxOf(c1, c2), maxOf(c3, c4))))
                    .let { it.copy(lo = maxOf(0.0, it.lo)) }
            }
        } else {
            FnRange.ENTIRE
        }
    }

    /**
     * `t·t·(3 − 2t)` evaluated as `Fn.eval` evaluates it, for `t` drawn from [t].
     *
     * The generic rules treat the three `t`s as independent and overshoot 1 by about `2·Δt` near
     * `t = 1` — exactly where a hold begins, so it is the overshoot that matters most. On `[0, 1]`
     * the real cubic is increasing, and each of its three roundings is relative (`2t` is exact), so
     * every computed value lies within a factor `(1 ± u)³` of the real one; the endpoint values
     * widened by 8 ulps cover `(1 + u)⁶` from either side. That is intersected with the generic
     * bound, and a point `t` is evaluated exactly as `eval` would.
     */
    fun cubicSmoothstep(t: FnRange): FnRange {
        fun s(v: Double) = v * v * (3.0 - 2.0 * v)
        if (t.isPoint) return FnRange.point(s(t.lo))
        val generic = mul(square(t), sub(FnRange.point(3.0), mul(FnRange.point(2.0), t)))
        if (t.isEmpty || t.maybeNaN || t.lo < 0.0 || t.hi > 1.0) return generic
        val monotone = widen(FnRange(s(t.lo), s(t.hi)), 8)
        val lo = if (s(t.lo) < 1e-290) 0.0 else maxOf(0.0, monotone.lo)
        return FnRange(maxOf(lo, generic.lo), minOf(monotone.hi, generic.hi))
    }

    /**
     * `u·u·u·(10 + u·(−15 + 6u))`, the quintic smootherstep, evaluated as `Fn.eval` evaluates that
     * graph, for `u` drawn from [u] — intersected with [generic], the bound the ordinary rules give.
     *
     * Why a rule: near `u = 1` the generic bound overshoots 1 by a multiple of `Δu`, and a
     * crossfade `(1 − m)·p + m` built on it can then never be proven `<= 1` near a hold without
     * bisecting to `Δu ~ (1 − u)³`. The real quintic is increasing on `[0, 1]` (its derivative is
     * `30u²(1 − u)²`), so its range is its endpoint values. What is left is the rounding: every
     * computed value is within a factor `1 ± 28u` of the real one (`10 − 15u + 6u² >= 1` bounds the
     * one cancellation, `−15 + 6u` never cancels, and the seven roundings are relative), so the
     * endpoint values widened by 64 ulps — each ulp is more than `u·|v|` — cover it from both sides.
     * Outside `[0, 1]`, or for a point, the generic bound (or the exact value) is used alone.
     */
    fun quinticSmootherstep(u: FnRange, generic: () -> FnRange): FnRange {
        fun s(v: Double) = v * v * v * (10.0 + v * (-15.0 + 6.0 * v))
        if (u.isPoint) return FnRange.point(s(u.lo))
        val g = generic()
        if (u.isEmpty || u.maybeNaN || u.lo < 0.0 || u.hi > 1.0) return g
        val monotone = widen(FnRange(s(u.lo), s(u.hi)), 64)
        val lo = if (s(u.lo) < 1e-290) 0.0 else maxOf(0.0, monotone.lo)
        return FnRange(maxOf(lo, g.lo), minOf(monotone.hi, g.hi))
    }

    fun min(a: FnRange, b: FnRange): FnRange = binary(a, b) { FnRange(minOf(a.lo, b.lo), minOf(a.hi, b.hi)) }

    fun max(a: FnRange, b: FnRange): FnRange = binary(a, b) { FnRange(maxOf(a.lo, b.lo), maxOf(a.hi, b.hi)) }

    fun clamp01(a: FnRange): FnRange = unary(a) { FnRange(it.lo.coerceIn(0.0, 1.0), it.hi.coerceIn(0.0, 1.0)) }

    fun coerceIn(a: FnRange, lo: FnRange, hi: FnRange): FnRange {
        if (lo.isEmpty || hi.isEmpty || lo.maybeNaN || hi.maybeNaN) {
            throw FnIntervalRefusal("clamp bounds may be NaN over this interval ($lo, $hi)")
        }
        if (lo.hi > hi.lo) {
            throw FnIntervalRefusal(
                "clamp's range may invert over this interval (min reaches ${lo.hi}, max falls to ${hi.lo}); " +
                    "Fn.eval throws there, so there is no value to bound"
            )
        }
        if (a.isEmpty) return a
        // coerceIn(v, l, h) = min(max(v, l), h) once l ≤ h: non-decreasing in all three.
        return FnRange(minOf(maxOf(a.lo, lo.lo), hi.lo), minOf(maxOf(a.hi, lo.hi), hi.hi), a.maybeNaN)
    }
}

// ---------------------------------------------------------------------------------------------
// The third walker
// ---------------------------------------------------------------------------------------------

/** How a branch test came out over a whole interval. */
private enum class Decided { THEN, OTHERWISE, BOTH }

/**
 * Walks a formula in interval arithmetic: the [FnRange] of values the formula can take for any `x`
 * in [x] — the third walker of the same [FnExpr] tree [evalFormula] and [evalFormulaAD] walk.
 *
 * [childRange] resolves a role to that child's enclosure over the position range it is asked for;
 * [alpha] supplies [FnExpr.Alpha]. Branch kinds take the decided branch when the test's range
 * settles it and the hull of both otherwise, evaluating each branch only when it can be taken.
 */
fun evalFormulaInterval(
    expr: FnExpr,
    x: FnRange,
    alpha: (FnRange) -> FnRange,
    childRange: (role: String, at: FnRange) -> FnRange,
    known: Map<FnExpr, FnRange> = emptyMap()
): FnRange {
    if (known.isNotEmpty()) known[expr]?.let { return it }
    fun walk(e: FnExpr, facts: Map<FnExpr, FnRange> = known): FnRange = evalFormulaInterval(e, x, alpha, childRange, facts)
    fun branch(decided: Decided, then: FnExpr, otherwise: FnExpr): FnRange = when (decided) {
        Decided.THEN -> walk(then)
        Decided.OTHERWISE -> walk(otherwise)
        Decided.BOTH -> walk(then).hull(walk(otherwise))
    }
    return when (expr) {
        is FnExpr.X -> x
        is FnExpr.Lit -> FnRange.point(expr.value)
        is FnExpr.Child -> childRange(expr.role, x)
        is FnExpr.Alpha -> alpha(x)
        is FnExpr.Add -> FnRangeMath.add(walk(expr.a), walk(expr.b))
        // `t - floor(t)` is one value's fractional part, not a difference of two unrelated numbers.
        is FnExpr.Sub -> if (expr.b is FnExpr.Floor && (expr.b as FnExpr.Floor).a == expr.a) {
            FnRangeMath.fract(walk(expr.a))
        } else {
            FnRangeMath.sub(walk(expr.a), walk(expr.b))
        }
        // The same subexpression twice is the same double twice, so its product is a square.
        is FnExpr.Mul -> smoothstepArgument(expr)?.let { FnRangeMath.cubicSmoothstep(walk(it)) }
            ?: if (expr.a == expr.b) FnRangeMath.square(walk(expr.a)) else FnRangeMath.mul(walk(expr.a), walk(expr.b))
        is FnExpr.Div -> FnRangeMath.div(walk(expr.a), walk(expr.b))
        is FnExpr.Neg -> FnRangeMath.neg(walk(expr.a))
        is FnExpr.Abs -> FnRangeMath.abs(walk(expr.a))
        is FnExpr.Floor -> FnRangeMath.floor(walk(expr.a))
        is FnExpr.Sin -> FnRangeMath.sin(walk(expr.a))
        is FnExpr.Cos -> FnRangeMath.cos(walk(expr.a))
        is FnExpr.Exp -> FnRangeMath.exp(walk(expr.a))
        is FnExpr.Ln -> FnRangeMath.ln(walk(expr.a))
        is FnExpr.Tanh -> FnRangeMath.tanh(walk(expr.a))
        is FnExpr.Atan -> FnRangeMath.atan(walk(expr.a))
        is FnExpr.Atan2 -> FnRangeMath.atan2(walk(expr.y), walk(expr.x))
        is FnExpr.Hypot -> FnRangeMath.hypot(walk(expr.a), walk(expr.b))
        is FnExpr.Pow -> FnRangeMath.pow(walk(expr.a), walk(expr.b))
        is FnExpr.Min -> FnRangeMath.min(walk(expr.a), walk(expr.b))
        is FnExpr.Max -> FnRangeMath.max(walk(expr.a), walk(expr.b))
        is FnExpr.Clamp01 -> FnRangeMath.clamp01(walk(expr.a))
        is FnExpr.CoerceIn -> FnRangeMath.coerceIn(walk(expr.a), walk(expr.lo), walk(expr.hi))
        is FnExpr.IfZero -> {
            val t = walk(expr.test)
            // `t == 0.0` is false for NaN, so NaN always takes `otherwise`.
            val maybeZero = 0.0 in t
            val maybeNonZero = t.maybeNaN || t.isEmpty || t.lo != 0.0 || t.hi != 0.0
            branch(decide(maybeZero, maybeNonZero), expr.then, expr.otherwise)
        }
        is FnExpr.IfEq -> {
            val a = walk(expr.a)
            if (expr.a == expr.b) {
                // One subexpression compared with itself is one double: equal unless it is NaN.
                branch(decide(!a.isEmpty, a.maybeNaN), expr.then, expr.otherwise)
            } else {
                val b = walk(expr.b)
                val maybeEqual = !a.isEmpty && !b.isEmpty && a.lo <= b.hi && b.lo <= a.hi
                val maybeUnequal = a.maybeNaN || b.maybeNaN || !a.isPoint || !b.isPoint || a.lo != b.lo
                branch(decide(maybeEqual, maybeUnequal), expr.then, expr.otherwise)
            }
        }
        is FnExpr.IfLt -> if (expr.a == expr.b) walk(expr.otherwise) else {
            val a = walk(expr.a)
            val b = walk(expr.b)
            val maybeLess = !a.isEmpty && !b.isEmpty && a.lo < b.hi
            val maybeNotLess = a.maybeNaN || b.maybeNaN || a.isEmpty || b.isEmpty || a.hi >= b.lo
            when (decide(maybeLess, maybeNotLess)) {
                Decided.THEN -> walk(expr.then)
                Decided.OTHERWISE -> walk(expr.otherwise)
                // Each arm is walked knowing the comparison that selects it: wherever `then` runs,
                // the value of `a` is below `b.hi`, and wherever `otherwise` runs it is at least
                // `b.lo` (or NaN). Without this, triangle's `if (t < 0.5) 2t else 2 - 2t` bounds
                // each arm over all of `t` and the wave encloses as [-1, 3].
                Decided.BOTH -> {
                    val below = FnRange(a.lo, minOf(a.hi, Math.nextDown(b.hi)))
                    val atOrAbove = FnRange(maxOf(a.lo, b.lo), a.hi, a.maybeNaN)
                    walk(expr.then, known + (expr.a to below)).hull(walk(expr.otherwise, known + (expr.a to atOrAbove)))
                }
            }
        }
        // A moved position invalidates every fact learned at the old one.
        is FnExpr.AtX -> evalFormulaInterval(expr.body, walk(expr.newX), alpha, childRange)
    }
}

/**
 * `t` when [expr] is `t * t * (3 - 2t)` — the exact shape of `FnFormulas`' smoothstep helper — else
 * null. Recognised structurally, so it applies wherever that shape appears rather than per op.
 */
private fun smoothstepArgument(expr: FnExpr.Mul): FnExpr? {
    val square = expr.a as? FnExpr.Mul ?: return null
    if (square.a != square.b) return null
    val t = square.a
    val rest = expr.b as? FnExpr.Sub ?: return null
    if (rest.a != FnExpr.Lit(3.0)) return null
    val twice = rest.b as? FnExpr.Mul ?: return null
    return if (twice.a == FnExpr.Lit(2.0) && twice.b == t) t else null
}

private fun decide(maybeThen: Boolean, maybeOtherwise: Boolean): Decided = when {
    maybeThen && maybeOtherwise -> Decided.BOTH
    maybeThen -> Decided.THEN
    else -> Decided.OTHERWISE
}

// ---------------------------------------------------------------------------------------------
// Enclosing an Fn
// ---------------------------------------------------------------------------------------------

/**
 * A certified bound on an `Fn` over `[a, b]`, or a refusal saying why there is none.
 *
 * When [certified], every value `Fn.eval(x)` returns for a double `x` in `[a, b]` lies in
 * `[lo, hi]`, or is NaN and [maybeNaN] is set. When not, [lo] and [hi] are NaN — never a guess —
 * and [refusal] names the reason, with [unsupportedOps] listing any ops that have no formula.
 */
data class FnEnclosure(
    val a: Double,
    val b: Double,
    val lo: Double,
    val hi: Double,
    val maybeNaN: Boolean,
    val certified: Boolean,
    val refusal: String? = null,
    val unsupportedOps: List<String> = emptyList()
) {
    /** Whether [value] is inside the certified bound. False for every value when not [certified]. */
    operator fun contains(value: Double): Boolean =
        certified && (if (value.isNaN()) maybeNaN else value >= lo && value <= hi)

    /** `hi - lo`, or NaN when refused. */
    val width: Double get() = hi - lo

    /** This enclosure, or an exception carrying its [refusal]. */
    fun requireCertified(): FnEnclosure {
        if (!certified) throw FnIntervalRefusal(refusal ?: "not certified")
        return this
    }

    override fun toString(): String =
        if (certified) "FnEnclosure([$a, $b] -> [$lo, $hi]${if (maybeNaN) " ∪ {NaN}" else ""})"
        else "FnEnclosure([$a, $b] refused: $refusal)"
}

/** The interval walk over an `Fn` graph: support checks and the one-shot enclosure. */
object FnIntervals {

    /**
     * The distinct ops in [fn] that have no formula, in first-seen order — exactly the ops an
     * enclosure refuses. A node carrying `numbers["value"]` is a constant and always supported.
     */
    fun unsupportedOps(fn: Functions.Fn): List<String> {
        val out = LinkedHashSet<String>()
        fun walk(f: Functions.Fn) {
            val node = f.graphNode()
            if (node.numbers.containsKey("value")) return
            if (FnFormulas.valueOf(node.op) == null) out += node.op
            for (edge in node.children) walk(edge.fn)
        }
        walk(fn)
        return out.toList()
    }

    /** True when every node of [fn] has a formula or is a constant. */
    fun supports(fn: Functions.Fn): Boolean = unsupportedOps(fn).isEmpty()

    /**
     * The enclosure of [fn] over the position range [x]. Throws [FnIntervalRefusal] when no sound
     * bound exists; [enclose] is the non-throwing form.
     */
    fun range(fn: Functions.Fn, x: FnRange): FnRange = Walk().range(fn, x)

    /**
     * One enclosure's worth of memo. A graph is a DAG — the workbench rewrite reaches its
     * background eleven times per channel and each `u` eight times — and a subgraph asked for the
     * same position range twice gives the same answer, so it is computed once. Keyed by identity,
     * because `Fn` is a data class whose `equals` would walk the whole graph.
     */
    private class Walk {
        private val ranges = java.util.IdentityHashMap<Functions.Fn, HashMap<FnRange, FnRange>>()
        private val quintics = java.util.IdentityHashMap<Functions.Fn, Any>()
        private val none = Any()

        fun range(fn: Functions.Fn, x: FnRange): FnRange {
            val node = fn.graphNode()
            node.numbers["value"]?.let { return FnRange.point(it) }
            val memo = ranges.getOrPut(fn) { HashMap(2) }
            memo[x]?.let { return it }
            val formula = FnFormulas.valueOf(node.op)
                ?: throw FnIntervalRefusal(refusalFor(listOf(node.op)))
            val r = if (node.op == "multiply") {
                val left = childOf(fn, "left")
                // The same Fn on both sides of a product is the same double twice: a square. The
                // graph-level twin of the Mul(a, a) rule inside a formula.
                if (left != null && left === childOf(fn, "right")) FnRangeMath.square(range(left, x))
                else {
                    val u = quintics.getOrPut(fn) { quinticArgument(fn) ?: none }
                    if (u is Functions.Fn) FnRangeMath.quinticSmootherstep(range(u, x), generic = { formulaRange(fn, formula, x) })
                    else formulaRange(fn, formula, x)
                }
            } else formulaRange(fn, formula, x)
            memo[x] = r
            return r
        }

        private fun formulaRange(fn: Functions.Fn, formula: FnExpr, x: FnRange): FnRange {
            val node = fn.graphNode()
            return evalFormulaInterval(
                formula,
                x,
                alpha = { at -> alphaRange(fn, at) },
                childRange = { role, at ->
                    val edge = node.children.firstOrNull { it.role == role }
                        ?: throw FnIntervalRefusal("op ${node.op} is missing its '$role' child")
                    range(edge.fn, at)
                }
            )
        }
    }

    private fun childOf(fn: Functions.Fn, role: String): Functions.Fn? =
        fn.graphNode().children.firstOrNull { it.role == role }?.fn

    private fun constOf(fn: Functions.Fn?): Double? = fn?.graphNode()?.numbers?.get("value")

    /**
     * `u` when [fn] is the graph `u·u·u·(10 + u·(−15 + 6u))` — the quintic `smootherstep` the
     * function workbench writes by hand as `smooth01` — with the *same* `Fn` for every `u`, else null.
     */
    private fun quinticArgument(fn: Functions.Fn): Functions.Fn? {
        fun op(f: Functions.Fn?) = f?.graphNode()?.op
        val cube = childOf(fn, "left")?.takeIf { op(it) == "multiply" } ?: return null
        val square = childOf(cube, "left")?.takeIf { op(it) == "multiply" } ?: return null
        val u = childOf(square, "left") ?: return null
        if (childOf(square, "right") !== u || childOf(cube, "right") !== u) return null
        val poly = childOf(fn, "right")?.takeIf { op(it) == "add" } ?: return null
        if (constOf(childOf(poly, "left")) != 10.0) return null
        val outer = childOf(poly, "right")?.takeIf { op(it) == "multiply" } ?: return null
        if (childOf(outer, "left") !== u) return null
        val inner = childOf(outer, "right")?.takeIf { op(it) == "add" } ?: return null
        if (constOf(childOf(inner, "left")) != -15.0) return null
        val six = childOf(inner, "right")?.takeIf { op(it) == "multiply" } ?: return null
        return if (constOf(childOf(six, "left")) == 6.0 && childOf(six, "right") === u) u else null
    }

    /** The certified bound on [fn] over `[a, b]`, or a refusal naming why there is none. */
    fun enclose(fn: Functions.Fn, a: Double, b: Double): FnEnclosure {
        require(!a.isNaN() && !b.isNaN() && a <= b) { "enclose needs a <= b, got [$a, $b]" }
        val missing = unsupportedOps(fn)
        if (missing.isNotEmpty()) {
            return FnEnclosure(a, b, Double.NaN, Double.NaN, false, false, refusalFor(missing), missing)
        }
        return try {
            val r = range(fn, FnRange(a, b))
            if (r.isEmpty) FnEnclosure(a, b, Double.NaN, Double.NaN, r.maybeNaN, true)
            else FnEnclosure(a, b, r.lo, r.hi, r.maybeNaN, true)
        } catch (refusal: FnIntervalRefusal) {
            FnEnclosure(a, b, Double.NaN, Double.NaN, false, false, refusal.message)
        }
    }

    internal fun refusalFor(ops: List<String>): String =
        "no certified bound: ${ops.joinToString(", ") { "'$it'" }} ${if (ops.size == 1) "has" else "have"} " +
            "no value formula in FnFormulas, so the interval walk cannot see inside " +
            (if (ops.size == 1) "it" else "them") +
            (if (ops.any { it == "opaque" || it == "lambda" }) " (a hand-written fn { x -> … } lambda is opaque by construction)" else "") +
            ". Build the signal from formula-bearing ops, or give the op a formula"

    private fun alphaRange(fn: Functions.Fn, x: FnRange): FnRange {
        val domain = fn.domain
        if (!domain.isFinite) {
            throw FnIntervalRefusal(
                "the x-window of op '${fn.graphNode().op}' is $domain, which is not finite; Functions " +
                    "samples such an op over a private preview window, and the walk will not guess at it"
            )
        }
        val length = domain.length
        if (length == 0.0) return if (x.isEmpty && !x.maybeNaN) x else FnRange(0.0, 0.0)
        if (x.isEmpty) return x
        // Sub then divide by a positive constant: both correctly rounded, both monotone.
        return FnRange((x.lo - domain.min) / length, (x.hi - domain.min) / length, x.maybeNaN)
    }
}

/** The certified bound on this function over `[a, b]` — see [FnIntervals.enclose]. */
fun Functions.Fn.enclose(a: Double, b: Double): FnEnclosure = FnIntervals.enclose(this, a, b)

// ---------------------------------------------------------------------------------------------
// Branch-and-bound
// ---------------------------------------------------------------------------------------------

/**
 * One side of a certified range: the true extreme lies between [value] (reached, at [witness]) and
 * [bound] (proven). [certified] when that gap is within the requested tolerance.
 */
data class FnExtremum(
    /** The best value actually returned by `Fn.eval`, at [witness]. */
    val value: Double,
    /** An x at which `Fn.eval` returns [value]. */
    val witness: Double,
    /** The proven bound: no value on the interval is beyond it. */
    val bound: Double,
    /** `|bound - value|` is within the tolerance asked for. */
    val certified: Boolean,
    /** How many enclosures the search computed. */
    val enclosures: Int
) {
    /** How far apart the reached value and the proven bound are. */
    val gap: Double get() = Math.abs(bound - value)
}

/**
 * The global minimum and maximum of an `Fn` over `[a, b]`, each bracketed between a reached value
 * and a proven bound. [refusal] is set (and both sides are null) when the function cannot be
 * enclosed at all.
 */
data class FnCertifiedRange(
    val a: Double,
    val b: Double,
    val min: FnExtremum?,
    val max: FnExtremum?,
    /** Some x in the interval may evaluate to NaN; the extremes are of the non-NaN values. */
    val maybeNaN: Boolean,
    val refusal: String? = null
) {
    /** True when both extremes are certified to the tolerance. */
    val certified: Boolean get() = refusal == null && min?.certified == true && max?.certified == true

    override fun toString(): String =
        if (refusal != null) "FnCertifiedRange([$a, $b] refused: $refusal)"
        else "FnCertifiedRange([$a, $b]: min ${min!!.value} at ${min.witness} (≥ ${min.bound}), " +
            "max ${max!!.value} at ${max.witness} (≤ ${max.bound})${if (certified) "" else ", not converged"})"
}

private class Piece(val lo: Double, val hi: Double, val range: FnRange)

/** Which extreme a branch-and-bound search was looking for. */
enum class FnSearchSide { MIN, MAX }

/**
 * One enclosure a [certifiedRange] search computed: every value on `[lo, hi]` is in [range].
 * [kept] when the piece could still hold a better extreme than the one already reached, so the
 * search queued it for splitting; a piece that is not kept was pruned by its bound.
 */
data class FnSearchPiece(val lo: Double, val hi: Double, val range: FnRange, val side: FnSearchSide, val kept: Boolean)

private fun midpoint(lo: Double, hi: Double): Double = lo + (hi - lo) * 0.5

/**
 * The maximum of [f] over `[a, b]` (or the minimum, via [sign] = -1), by best-first
 * branch-and-bound: always split the piece whose enclosure promises the most, and stop when the best
 * value reached is within [tolerance] of the best any remaining piece could hold.
 */
private fun searchExtremum(
    fn: Functions.Fn,
    a: Double,
    b: Double,
    tolerance: Double,
    maxEnclosures: Int,
    sign: Double,
    stop: (best: Double, topUpper: Double) -> Boolean = { _, _ -> false },
    onEnclosure: ((FnSearchPiece) -> Unit)? = null
): FnExtremum {
    fun enclosed(lo: Double, hi: Double, r: FnRange, kept: Boolean) {
        onEnclosure?.invoke(FnSearchPiece(lo, hi, r, if (sign > 0) FnSearchSide.MAX else FnSearchSide.MIN, kept))
    }
    fun upper(r: FnRange) = if (r.isEmpty) Double.NEGATIVE_INFINITY else if (sign > 0) r.hi else -r.lo
    var best = Double.NEGATIVE_INFINITY
    var witness = a
    fun probe(x: Double) {
        val v = runCatching { fn.eval(x) }.getOrNull() ?: return
        if (v.isNaN()) return
        if (sign * v > best) { best = sign * v; witness = x }
    }
    probe(a); probe(b); probe(midpoint(a, b))
    val queue = PriorityQueue<Piece>(compareByDescending { upper(it.range) })
    queue += Piece(a, b, FnIntervals.range(fn, FnRange(a, b)))
    enclosed(a, b, queue.peek().range, true)
    var enclosures = 1
    var settledBound = Double.NEGATIVE_INFINITY
    while (queue.isNotEmpty()) {
        val top = queue.peek()
        if (upper(top.range) - best <= tolerance || enclosures >= maxEnclosures || stop(best, upper(top.range))) break
        queue.poll()
        val mid = midpoint(top.lo, top.hi)
        if (mid <= top.lo || mid >= top.hi) {
            // Cannot be split further in Double: the piece holds exactly the doubles `lo` and `hi`,
            // so its bound is the larger of their point enclosures — which are tight — and final.
            if (top.lo == top.hi) settledBound = maxOf(settledBound, upper(top.range))
            else for (end in listOf(top.lo, top.hi)) {
                settledBound = maxOf(settledBound, upper(FnIntervals.range(fn, FnRange(end, end))))
                enclosures++
            }
            continue
        }
        for ((lo, hi) in listOf(top.lo to mid, mid to top.hi)) {
            val r = FnIntervals.range(fn, FnRange(lo, hi))
            enclosures++
            probe(midpoint(lo, hi))
            val kept = upper(r) > best + tolerance
            enclosed(lo, hi, r, kept)
            if (kept) queue += Piece(lo, hi, r)
            else settledBound = maxOf(settledBound, upper(r))
        }
    }
    val bound = maxOf(settledBound, queue.peek()?.let { upper(it.range) } ?: Double.NEGATIVE_INFINITY, best)
    return FnExtremum(sign * best, witness, sign * bound, bound - best <= tolerance, enclosures)
}

/**
 * The certified global minimum and maximum of this function over `[a, b]`, each to within
 * [tolerance]: the true extreme is at least as far out as the value reached at the witness, and no
 * further out than the proven bound.
 *
 * [maxEnclosures] caps the work per side; a search that hits it returns what it has with
 * `certified = false` and the remaining gap, rather than pretending.
 *
 * [onEnclosure], when given, is told about every piece either search encloses, in the order it
 * encloses them — the raw material of [intervalPicture], which draws where the proof spent its work.
 */
fun Functions.Fn.certifiedRange(
    a: Double,
    b: Double,
    tolerance: Double = 1e-6,
    maxEnclosures: Int = 200_000,
    onEnclosure: ((FnSearchPiece) -> Unit)? = null
): FnCertifiedRange {
    val whole = enclose(a, b)
    if (!whole.certified) return FnCertifiedRange(a, b, null, null, false, whole.refusal)
    return try {
        FnCertifiedRange(
            a, b,
            min = searchExtremum(this, a, b, tolerance, maxEnclosures, -1.0, onEnclosure = onEnclosure),
            max = searchExtremum(this, a, b, tolerance, maxEnclosures, 1.0, onEnclosure = onEnclosure),
            maybeNaN = whole.maybeNaN
        )
    } catch (refusal: FnIntervalRefusal) {
        FnCertifiedRange(a, b, null, null, false, refusal.message)
    }
}

/** The answer to a certified yes/no question about a function on an interval. */
enum class FnVerdictKind {
    /** Proven for every x in the interval. */
    PROVEN,
    /** Disproven by a witness x at which `Fn.eval` breaks the claim. */
    REFUTED,
    /** Neither, within the work budget — or the function could not be enclosed. */
    UNDECIDED
}

/** A certified verdict, with the witness that refutes it (or proves an existence claim). */
data class FnVerdict(
    val kind: FnVerdictKind,
    val claim: String,
    /** The x that decides it, when there is one. */
    val witness: Double? = null,
    /** `Fn.eval(witness)`. */
    val witnessValue: Double? = null,
    val enclosures: Int = 0,
    val refusal: String? = null
) {
    val proven: Boolean get() = kind == FnVerdictKind.PROVEN

    override fun toString(): String = "$kind: $claim" +
        (witness?.let { " (witness x=$it, f=$witnessValue)" } ?: "") + (refusal?.let { " — $it" } ?: "")
}

/**
 * Splits `[a, b]` until each piece is settled by its enclosure ([settled] true) or a probe value
 * breaks the claim ([breaks] true). Shared by the universal predicates.
 */
private fun proveForAll(
    fn: Functions.Fn,
    a: Double,
    b: Double,
    claim: String,
    maxEnclosures: Int,
    settled: (FnRange) -> Boolean,
    breaks: (Double) -> Boolean
): FnVerdict {
    val missing = FnIntervals.unsupportedOps(fn)
    if (missing.isNotEmpty()) return FnVerdict(FnVerdictKind.UNDECIDED, claim, refusal = FnIntervals.refusalFor(missing))
    fun check(x: Double): FnVerdict? {
        val v = runCatching { fn.eval(x) }.getOrDefault(Double.NaN)
        return if (breaks(v)) FnVerdict(FnVerdictKind.REFUTED, claim, x, v) else null
    }
    check(a)?.let { return it }
    check(b)?.let { return it }
    val stack = ArrayDeque<Pair<Double, Double>>()
    stack.addLast(a to b)
    var enclosures = 0
    try {
        while (stack.isNotEmpty()) {
            val (lo, hi) = stack.removeLast()
            val r = FnIntervals.range(fn, FnRange(lo, hi))
            enclosures++
            if (settled(r)) continue
            val mid = midpoint(lo, hi)
            if (mid <= lo || mid >= hi) {
                // Two adjacent doubles and nothing between: decide each as a point, which is tight.
                // (Both were probed when their parents were split or as the interval's ends.)
                enclosures += 2
                if (settled(FnIntervals.range(fn, FnRange(lo, lo))) && settled(FnIntervals.range(fn, FnRange(hi, hi)))) continue
                check(lo)?.let { return it.copy(enclosures = enclosures) }
                check(hi)?.let { return it.copy(enclosures = enclosures) }
                return FnVerdict(FnVerdictKind.UNDECIDED, claim, enclosures = enclosures,
                    refusal = "unsettled at the adjacent doubles $lo and $hi")
            }
            check(mid)?.let { return it.copy(enclosures = enclosures) }
            if (enclosures >= maxEnclosures) {
                return FnVerdict(FnVerdictKind.UNDECIDED, claim, enclosures = enclosures,
                    refusal = "unsettled near [$lo, $hi] after $enclosures enclosures")
            }
            stack.addLast(mid to hi)
            stack.addLast(lo to mid)
        }
    } catch (refusal: FnIntervalRefusal) {
        return FnVerdict(FnVerdictKind.UNDECIDED, claim, enclosures = enclosures, refusal = refusal.message)
    }
    return FnVerdict(FnVerdictKind.PROVEN, claim, enclosures = enclosures)
}

/**
 * Certifies that `f(x) <= limit + slack` for every x in `[a, b]` (and never NaN), or refutes that
 * same claim with a witness.
 *
 * [slack] exists because a bound the function *attains* — a crossfade that reaches exactly 1 on a
 * hold — is usually approached tangentially, and an interval bound over any piece touching the
 * tangency sits a little above it however small the piece. Proving `<= 1` exactly there is often
 * not possible by bisection; proving `<= 1 + 1e-12` is quick. Ask with `slack = 0.0` to find out
 * whether the exact claim is *false*: a witness above the limit by an ulp is a refutation.
 */
fun Functions.Fn.certifiedNeverExceeds(
    limit: Double,
    a: Double,
    b: Double,
    slack: Double = 0.0,
    maxEnclosures: Int = 200_000
): FnVerdict =
    proveForAll(this, a, b, "f(x) <= $limit${if (slack > 0) " (+ $slack)" else ""} on [$a, $b]", maxEnclosures,
        settled = { !it.maybeNaN && (it.isEmpty || it.hi <= limit + slack) },
        breaks = { it.isNaN() || it > limit + slack })

/** Certifies that `f(x) >= limit - slack` for every x in `[a, b]` (and never NaN), or refutes that claim with a witness. */
fun Functions.Fn.certifiedNeverBelow(
    limit: Double,
    a: Double,
    b: Double,
    slack: Double = 0.0,
    maxEnclosures: Int = 200_000
): FnVerdict =
    proveForAll(this, a, b, "f(x) >= $limit${if (slack > 0) " (- $slack)" else ""} on [$a, $b]", maxEnclosures,
        settled = { !it.maybeNaN && (it.isEmpty || it.lo >= limit - slack) },
        breaks = { it.isNaN() || it < limit - slack })

/**
 * Certifies that `f(x) == value` **exactly** for every x in `[a, b]`, or refutes it with a witness.
 *
 * Exactness is meaningful here because the arithmetic rules are unwidened: a held constant, a clamp
 * pinned against its bound, a product with an exact zero all enclose as the point itself. Anything
 * passing through a transcendental is widened by ulps and can only ever be UNDECIDED or REFUTED.
 */
fun Functions.Fn.certifiedEquals(value: Double, a: Double, b: Double, maxEnclosures: Int = 200_000): FnVerdict =
    proveForAll(this, a, b, "f(x) == $value on [$a, $b]", maxEnclosures,
        settled = { !it.maybeNaN && it.lo == value && it.hi == value },
        breaks = { it != value })

/**
 * Certifies that `f(x) >= level` somewhere in `[a, b]` — PROVEN with the witness that reaches it,
 * REFUTED when the proven maximum is below [level].
 */
fun Functions.Fn.certifiedReaches(level: Double, a: Double, b: Double, maxEnclosures: Int = 200_000): FnVerdict {
    val claim = "f(x) >= $level somewhere on [$a, $b]"
    val whole = enclose(a, b)
    if (!whole.certified) return FnVerdict(FnVerdictKind.UNDECIDED, claim, refusal = whole.refusal)
    return try {
        val max = searchExtremum(this, a, b, 0.0, maxEnclosures, 1.0, stop = { best, top -> best >= level || top < level })
        when {
            max.value >= level -> FnVerdict(FnVerdictKind.PROVEN, claim, max.witness, max.value, max.enclosures)
            max.bound < level -> FnVerdict(FnVerdictKind.REFUTED, claim, max.witness, max.value, max.enclosures)
            else -> FnVerdict(FnVerdictKind.UNDECIDED, claim, max.witness, max.value, max.enclosures,
                "the maximum is between ${max.value} and ${max.bound}")
        }
    } catch (refusal: FnIntervalRefusal) {
        FnVerdict(FnVerdictKind.UNDECIDED, claim, refusal = refusal.message)
    }
}

/**
 * Per-bin certified bounds: bin `i` covers `[edge(i), edge(i+1)]` (closed, so neighbours share an
 * edge) and **every** value the function takes there lies in `[low[i], high[i]]`.
 *
 * [samplesInside] is the sampled envelope the bounds were refined against — the same grid a plot
 * would draw — so the two can be drawn together and compared.
 */
data class FnCertifiedEnvelope(
    val start: Double,
    val end: Double,
    val low: DoubleArray,
    val high: DoubleArray,
    val certified: Boolean,
    val refusal: String? = null,
    /** Enclosures computed in total. */
    val enclosures: Int = 0,
    /** Bins still wider than `observed + tolerance` when the depth cap was reached. */
    val unconverged: Int = 0
) {
    val bins: Int get() = low.size

    /** The left edge of bin [i]; `edge(bins)` is [end]. */
    fun edge(i: Int): Double = if (i >= bins) end else start + (end - start) * i / bins

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Certified per-bin bounds for this function over `[a, b]` in [bins] equal bins.
 *
 * Each bin starts as one enclosure and is bisected — up to [maxDepth] times — while its bound exceeds
 * the spread of the values seen at the piece edges by more than [tolerance]. Refinement only ever
 * tightens: every piece's bound is itself certified, so their union still contains every value.
 * A refused function returns `certified = false` with empty arrays and the reason.
 */
fun Functions.Fn.certifiedEnvelope(
    a: Double,
    b: Double,
    bins: Int,
    tolerance: Double = 1e-3,
    maxDepth: Int = 8
): FnCertifiedEnvelope {
    require(bins in 1..100_000) { "bins must be in 1..100000" }
    require(a < b && a.isFinite() && b.isFinite()) { "certifiedEnvelope needs a finite a < b" }
    val missing = FnIntervals.unsupportedOps(this)
    if (missing.isNotEmpty()) {
        return FnCertifiedEnvelope(a, b, DoubleArray(0), DoubleArray(0), false, FnIntervals.refusalFor(missing))
    }
    val low = DoubleArray(bins)
    val high = DoubleArray(bins)
    var enclosures = 0
    var unconverged = 0
    val fn = this
    fun sampled(x: Double): Double = runCatching { fn.eval(x) }.getOrDefault(Double.NaN)
    fun refine(lo: Double, hi: Double, flo: Double, fhi: Double, depth: Int): FnRange {
        val r = FnIntervals.range(fn, FnRange(lo, hi))
        enclosures++
        val seenLo = minOf(flo, fhi)
        val seenHi = maxOf(flo, fhi)
        val excess = if (r.isEmpty || seenLo.isNaN() || seenHi.isNaN()) 0.0 else (r.hi - seenHi) + (seenLo - r.lo)
        val mid = midpoint(lo, hi)
        if (excess <= tolerance) return r
        if (mid <= lo || mid >= hi) {
            // Two adjacent doubles: their point enclosures are the tightest honest bound.
            enclosures += 2
            return if (lo == hi) r else FnIntervals.range(fn, FnRange(lo, lo)).hull(FnIntervals.range(fn, FnRange(hi, hi)))
        }
        if (depth >= maxDepth) { unconverged++; return r }
        val fmid = sampled(mid)
        val left = refine(lo, mid, flo, fmid, depth + 1)
        val right = refine(mid, hi, fmid, fhi, depth + 1)
        return left.hull(right)
    }
    return try {
        var fLeft = sampled(a)
        for (i in 0 until bins) {
            val lo = a + (b - a) * i / bins
            val hi = if (i == bins - 1) b else a + (b - a) * (i + 1) / bins
            val fRight = sampled(hi)
            val before = unconverged
            val r = refine(lo, hi, fLeft, fRight, 0)
            if (unconverged > before) unconverged = before + 1
            low[i] = if (r.isEmpty) Double.NaN else r.lo
            high[i] = if (r.isEmpty) Double.NaN else r.hi
            fLeft = fRight
        }
        FnCertifiedEnvelope(a, b, low, high, true, null, enclosures, unconverged)
    } catch (refusal: FnIntervalRefusal) {
        FnCertifiedEnvelope(a, b, DoubleArray(0), DoubleArray(0), false, refusal.message, enclosures)
    }
}
