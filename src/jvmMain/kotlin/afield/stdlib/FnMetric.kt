package afield.stdlib

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A pluggable distance metric between a target signal and a candidate, evaluated on a shared
 * even sample grid. **Lower is better**; `0.0` means the candidate matches the target at every
 * sampled point. This is the objective the differentiable fitters ([fitGradient], [fitBest],
 * [fitHybrid]) descend, and it generalises the RMSE they used to hard-code.
 *
 * The three arrays are aligned by index: `xs[i]` is the abscissa, `target[i]` the target's value
 * there, `candidate[i]` the candidate's. Either value may be non-finite (`NaN`/`±∞`) where the
 * corresponding `Fn` is undefined — a metric must ignore any index where *either* is non-finite,
 * matching the NaN-safe grid semantics the fitters have always used. When *no* index is jointly
 * finite there is nothing to compare, so a metric returns [Double.POSITIVE_INFINITY]; the fitter
 * reads that as maximally-bad and freezes the offending gradient rather than descending into a NaN.
 *
 * It is a `fun interface`, so a bespoke metric is just a lambda:
 * ```
 * val leadingEdge = FnMetric { xs, t, c -> abs(c.first() - t.first()) }   // match the onset only
 * fitBest(target, seed, metric = leadingEdge)
 * ```
 * The [companion object][Companion] carries the ready-made family: [rmse] (the default),
 * [mae], [lp], [maxError], [huber], [logCosh] and [correlation], each of which is one special
 * case of "aggregate a per-sample residual, optionally weighted across the domain". RMSE is
 * literally `lp(2.0)`.
 */
fun interface FnMetric {
    /**
     * The scalar distance of [candidate] from [target] over the grid [xs]. Lower is better;
     * returns [Double.POSITIVE_INFINITY] when the two share no jointly-finite sample.
     */
    fun distance(xs: DoubleArray, target: DoubleArray, candidate: DoubleArray): Double

    companion object {
        private val LN2 = ln(2.0)

        /**
         * Root-mean-square error — `sqrt(mean((candidate - target)²))` over the jointly-finite
         * grid. The default metric and the exact objective the fitters used before metrics were
         * pluggable, so an omitted `metric =` argument reproduces the old numbers bit-for-bit.
         * Identical to `lp(2.0)`.
         */
        val rmse: FnMetric = lp(2.0)

        /** Mean absolute error — `mean(|candidate - target|)`. Identical to `lp(1.0)`; less
         * swayed by a few large residuals than [rmse]. */
        val mae: FnMetric = lp(1.0)

        /**
         * The Minkowski / Lₚ family: `(mean(|candidate - target|ᵖ))^(1/p)`.
         *
         * `p = 2` is [rmse], `p = 1` is [mae], and larger `p` biases the fit toward flattening
         * the worst-matching points (approaching [maxError] as `p → ∞`). [weight], if given,
         * scales each sample's contribution by `weight(x)` (a weighted power mean) — pass
         * [window] to fit only part of the domain, or any positive lambda to emphasise a region.
         * Non-finite or negative weights drop that sample.
         *
         * @param p the exponent, `>= 1` (values below 1 are not a norm).
         */
        fun lp(p: Double, weight: ((Double) -> Double)? = null): FnMetric {
            require(p >= 1.0) { "lp exponent p must be >= 1 (got $p)" }
            return fromResidual(root = p, weight = weight) { r -> abs(r).pow(p) }
        }

        /**
         * Chebyshev / L∞ distance — `max(|candidate - target|)` over the grid. Minimising it
         * drives down the single worst mismatch, the natural objective for a hard tolerance
         * ("no point may drift more than ε").
         */
        val maxError: FnMetric = FnMetric { xs, target, candidate ->
            var worst = Double.NEGATIVE_INFINITY
            var valid = 0
            for (i in xs.indices) {
                val a = candidate[i]
                val b = target[i]
                if (a.isFinite() && b.isFinite()) {
                    worst = max(worst, abs(a - b))
                    valid++
                }
            }
            if (valid == 0) Double.POSITIVE_INFINITY else worst
        }

        /**
         * The Huber loss — squared for small residuals, linear beyond ±[delta]. A robust
         * middle ground between [rmse] and [mae]: it keeps RMSE's smooth, well-conditioned
         * gradient near the optimum while refusing to let a handful of outliers dominate the
         * fit the way a pure sum-of-squares does. Returns the mean per-sample penalty.
         *
         * @param delta the residual magnitude at which the penalty switches from quadratic to
         *        linear; `> 0`. Smaller = more aggressively robust.
         */
        fun huber(delta: Double = 1.0, weight: ((Double) -> Double)? = null): FnMetric {
            require(delta > 0.0) { "huber delta must be positive (got $delta)" }
            return fromResidual(weight = weight) { r ->
                val a = abs(r)
                if (a <= delta) 0.5 * r * r else delta * (a - 0.5 * delta)
            }
        }

        /**
         * The log-cosh loss — `mean(ln(cosh(residual)))`. Behaves like [rmse] for small
         * residuals and like [mae] for large ones, but is smooth everywhere (unlike [huber],
         * which has no free knob and no kink). A gentle, parameter-free robust default.
         */
        val logCosh: FnMetric = fromResidual { r ->
            // Numerically stable ln(cosh r) = |r| + ln(1 + e^-2|r|) - ln2, safe for large |r|.
            val a = abs(r)
            a + Math.log1p(exp(-2.0 * a)) - LN2
        }

        /**
         * One minus the Pearson correlation between target and candidate, in `[0, 2]`
         * (`0` = perfectly correlated, `1` = uncorrelated, `2` = anti-correlated).
         *
         * Unlike the residual metrics this is **scale- and offset-blind**: it rewards matching
         * the *shape* of the target regardless of amplitude or vertical bias, which is what you
         * want when you care about the gesture and will normalise later. Degenerate cases —
         * fewer than two jointly-finite samples, or a target/candidate that is constant over the
         * grid (zero variance, correlation undefined) — return [Double.POSITIVE_INFINITY].
         */
        val correlation: FnMetric = FnMetric { xs, target, candidate ->
            var sumA = 0.0
            var sumB = 0.0
            var n = 0
            for (i in xs.indices) {
                val a = candidate[i]
                val b = target[i]
                if (a.isFinite() && b.isFinite()) {
                    sumA += a
                    sumB += b
                    n++
                }
            }
            if (n < 2) {
                Double.POSITIVE_INFINITY
            } else {
                val meanA = sumA / n
                val meanB = sumB / n
                var cov = 0.0
                var varA = 0.0
                var varB = 0.0
                for (i in xs.indices) {
                    val a = candidate[i]
                    val b = target[i]
                    if (a.isFinite() && b.isFinite()) {
                        val da = a - meanA
                        val db = b - meanB
                        cov += da * db
                        varA += da * da
                        varB += db * db
                    }
                }
                val denom = sqrt(varA * varB)
                if (denom <= 0.0 || !denom.isFinite()) Double.POSITIVE_INFINITY
                else 1.0 - cov / denom
            }
        }

        /**
         * The general per-residual builder every mean-based metric above is made from:
         * `(mean(weight(x) · penalty(candidate - target)) / mean(weight))^(1/root)`.
         *
         * Supply a [penalty] mapping a signed residual to a non-negative cost; with the default
         * `root = 1.0` the metric is the (weighted) mean penalty. Set [root] to fold in a final
         * n-th root — e.g. [lp] is `fromResidual(root = p) { abs(it).pow(p) }`. This is the
         * escape hatch for a custom robust loss that still wants the shared NaN-safe,
         * weight-aware aggregation.
         */
        fun fromResidual(
            root: Double = 1.0,
            weight: ((Double) -> Double)? = null,
            penalty: (Double) -> Double
        ): FnMetric {
            require(root >= 1.0) { "root must be >= 1 (got $root)" }
            return FnMetric { xs, target, candidate ->
                var sum = 0.0
                var weightSum = 0.0
                var valid = 0
                for (i in xs.indices) {
                    val a = candidate[i]
                    val b = target[i]
                    if (a.isFinite() && b.isFinite()) {
                        val w = weight?.invoke(xs[i]) ?: 1.0
                        if (!w.isFinite() || w < 0.0) continue
                        sum += w * penalty(a - b)
                        weightSum += w
                        valid++
                    }
                }
                if (valid == 0 || weightSum <= 0.0) {
                    Double.POSITIVE_INFINITY
                } else {
                    val mean = sum / weightSum
                    if (root == 1.0) mean else mean.pow(1.0 / root)
                }
            }
        }

        /**
         * A weight lambda for the residual metrics that keeps samples inside [range] at
         * [inside] and drops those outside to [outside] (default `0.0`) — the simplest way to
         * fit only a slice of the domain, e.g. `lp(2.0, weight = FnMetric.window(0.25..0.75))`.
         */
        fun window(
            range: ClosedRange<Double>,
            inside: Double = 1.0,
            outside: Double = 0.0
        ): (Double) -> Double = { x -> if (x in range) inside else outside }
    }
}

/**
 * Builds a loss closure (lower is better) that scores a candidate `Fn` against [target] under
 * this metric, sampling both on an even grid of [samples] points across [domain] with the
 * fitters' NaN-safe `outside = NaN` convention. The target is sampled once and reused, so the
 * returned closure is cheap to call in the finite-difference inner loop.
 */
fun FnMetric.lossAgainst(
    target: Functions.Fn,
    domain: Functions.Domain = Functions.Domain.DEFAULT,
    samples: Int = 128
): (Functions.Fn) -> Double {
    val count = samples.coerceIn(2, 8192)
    val xs = DoubleArray(count) { index -> domain.min + domain.length * index / (count - 1).toDouble() }
    val targetY = DoubleArray(count) { index -> target.evalSafe(xs[index], outside = Double.NaN) }
    return { candidate ->
        val candidateY = DoubleArray(count) { index -> candidate.evalSafe(xs[index], outside = Double.NaN) }
        distance(xs, targetY, candidateY)
    }
}
