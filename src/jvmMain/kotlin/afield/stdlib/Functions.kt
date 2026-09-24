@file:afield.CompletionCategory("Math")

package afield.stdlib

import afield.Keyframe
import afield.KeyframeEvaluator
import afield.Box
import afield.graphics.FLine
import kotlin.math.*

/**
 * The `Fn` toolkit: one-dimensional signals as values you can build, compose, sample and draw.
 *
 * Nearly everything lives inside the [Functions] object, and most of it as extension functions,
 * so callers normally work inside `with(Functions) { ... }`. A `Functions.Fn` is an evaluator
 * plus the `Functions.Domain` it is meant to be sampled over (default `0.0..1.0`). Sources —
 * `sin`, `saw`, `triangle`, `square`, the `pulseTrain` / `pulseBurst` family, the noise and
 * `fbm` generators, and keyframe interpolants such as `catmullRomSpline` and `monotoneCubic` —
 * combine through arithmetic operators, through chainable `Fn` extensions (`stretch`, `repeat`,
 * `fold`, `quantize`, `echo`, `convolve`, `blur`, ...), and through reusable `FnTransform`
 * pipelines applied with `/`. Arrangement in time is `timeline { }` and the `List<Fn>`
 * combinators (`sequence`, `randomPermute`, `morph`, `stack`, `average`); those rearrange `Fn`s
 * and timeline chunks and hand the result back to the same evaluator rather than introducing a
 * second one.
 *
 * Every combinator also records an `FnGraphNode` recipe alongside the closure (see
 * `FunctionGraph.kt`), which is what makes graphs inspectable and rewritable rather than opaque
 * lambdas. On the output side: `toDoubleArray` and the `sample*` helpers for numbers, `toFLine`
 * for drawing, `plotHtml` for a self-contained plot, and `Fn.plot`, which displays into the
 * active `Box` and throws when there is none (`Box.plot` takes the `Fn` explicitly instead).
 *
 * [RbfKernel], [RbfKernels] and [RbfInterpolator] at the top of the file are the scattered-data
 * fitter behind `Functions.rbf`; it solves a dense n×n system per construction, so it is sized
 * for handfuls of control points, not thousands.
 */

/**
 * A radial basis function: how strongly a control point still counts at distance `r` from the x
 * being evaluated.
 *
 * A `fun interface`, so a bespoke kernel is just a lambda. `r` is always non-negative — the fitter
 * passes `abs(x - xi)` — and a kernel is *not* required to decay: the polynomial and thin-plate
 * kernels grow without bound with distance, which is normal for interpolation and only surprising
 * if you were expecting a bump-shaped weight. [RbfKernels] holds the ready-made family.
 */
fun interface RbfKernel {
    /** The kernel's value at radius [r]. [r] is never negative; the result may be any sign. */
    fun eval(r: Double): Double
}

/**
 * The ready-made [RbfKernel] family behind `Functions.rbf`: four parameter-free kernels as values,
 * and four shape-parameter kernels as factories.
 *
 * [thinPlate] is the default for `rbf` and the usual choice for smooth scattered-data
 * interpolation. The `epsilon` / `c` shape parameters are in the same units as x, so a kernel tuned
 * for control points a few units apart behaves very differently on points a thousand apart —
 * a badly-scaled shape parameter is the most common cause of a wild-looking fit.
 */
object RbfKernels {
    /** `r` itself: interpolation degenerates to straight segments between neighbouring points. */
    val linear = RbfKernel { r -> r }

    /** `r²`. Grows quickly with distance, so distant control points pull hard on the fit. */
    val quadratic = RbfKernel { r -> r * r }

    /** `r³`. The stiffest of the polynomial kernels; smooth, but prone to overshoot far from data. */
    val cubic = RbfKernel { r -> r * r * r }

    /**
     * The thin-plate spline kernel `r²·ln r`, with the removable singularity at `r = 0` defined as
     * zero. The default for `Functions.rbf`, and the one to reach for first: it needs no shape
     * parameter, so it cannot be mis-scaled. Note that it is *negative* for `r < 1`.
     */
    val thinPlate = RbfKernel { r -> if (r == 0.0) 0.0 else r * r * ln(r) }

    /**
     * A Gaussian bump `exp(-(εr)²)` — the one genuinely local kernel here, so influence dies away
     * within roughly `1/ε` of each control point.
     *
     * [epsilon] is an inverse width in x units: too large and the fit collapses to spikes at the
     * control points with near-zero in between; too small and the interpolation matrix becomes
     * badly conditioned.
     */
    fun gaussian(epsilon: Double = 1.0) = RbfKernel { r ->
        val v = epsilon * r
        kotlin.math.exp(-(v * v))
    }

    /** `sqrt(r² + c²)` — a softened [linear], rounded off near `r = 0` over a width of about [c]. */
    fun multiquadric(c: Double = 1.0) = RbfKernel { r -> sqrt(r * r + c * c) }

    /** `1 / sqrt(r² + c²)` — decays with distance, so the fit stays local-ish; [c] sets the width. */
    fun inverseMultiquadric(c: Double = 1.0) = RbfKernel { r -> 1.0 / sqrt(r * r + c * c) }

    /** `1 / (r² + c²)` — the fastest-decaying of the inverse kernels; [c] sets the width. */
    fun inverseQuadratic(c: Double = 1.0) = RbfKernel { r -> 1.0 / (r * r + c * c) }
}

private fun solveRbfLinearSystem(aIn: Array<DoubleArray>, bIn: DoubleArray): DoubleArray {
    val n = bIn.size
    if (n == 0) return DoubleArray(0)
    val a = Array(n) { i -> aIn[i].clone() }
    val b = bIn.clone()
    val eps = 1e-12
    for (i in 0 until n) {
        var pivot = i
        var max = kotlin.math.abs(a[i][i])
        for (r in i + 1 until n) {
            val v = kotlin.math.abs(a[r][i])
            if (v > max) {
                max = v
                pivot = r
            }
        }
        if (max < eps) continue
        if (pivot != i) {
            val tmp = a[i]; a[i] = a[pivot]; a[pivot] = tmp
            val tb = b[i]; b[i] = b[pivot]; b[pivot] = tb
        }
        val inv = 1.0 / a[i][i]
        for (j in i until n) a[i][j] *= inv
        b[i] *= inv
        for (r in 0 until n) {
            if (r == i) continue
            val factor = a[r][i]
            if (factor == 0.0) continue
            for (j in i until n) a[r][j] -= factor * a[i][j]
            b[r] -= factor * b[i]
        }
    }
    return b
}

/**
 * The scattered-data fitter behind `Functions.rbf`: given control points anywhere on the x axis,
 * solve once for the kernel weights that reproduce them, then evaluate anywhere.
 *
 * Construction is **eager and cubic** — it builds and solves a dense n×n system in the constructor —
 * so this is sized for handfuls of control points, not thousands. With `smoothing = 0.0` the result
 * passes exactly through every point; a positive [smoothing] is added to the matrix diagonal as a
 * ridge term, which trades exactness for a calmer curve. If [base] is given, the fit is applied to
 * the *residual* against it, so the base function supplies the trend and the kernels only correct it.
 *
 * Two things to know. Each [Functions.Sample]'s `w` scales the right-hand side only, not the matrix
 * row, so `w = 0` asks the fit to pass through zero at that x rather than ignoring the point. And a
 * singular system — most easily produced by two control points at the same x — is not reported: the
 * solver skips the degenerate pivot and leaves that weight at whatever the elimination had reached,
 * so the fit silently misses those points instead of throwing.
 */
class RbfInterpolator(
    private val points: List<Functions.Sample>,
    private val kernel: RbfKernel,
    private val smoothing: Double = 0.0,
    private val base: Functions.Fn? = null
) {
    private val xs = DoubleArray(points.size) { points[it].x }
    private val weights: DoubleArray

    init {
        val n = points.size
        if (n == 0) {
            weights = DoubleArray(0)
        } else {
            val a = Array(n) { DoubleArray(n) }
            val b = DoubleArray(n)
            for (i in 0 until n) {
                val xi = xs[i]
                val target = points[i].y - (base?.eval(xi) ?: 0.0)
                b[i] = target * points[i].w
                for (j in 0 until n) {
                    a[i][j] = kernel.eval(kotlin.math.abs(xi - xs[j]))
                }
                a[i][i] += smoothing
            }
            weights = solveRbfLinearSystem(a, b)
        }
    }

    /**
     * The fitted value at [x] — the base function (if any) plus every control point's weighted
     * kernel contribution.
     *
     * Defined for every real x, inside the control points' span or not: there is no clamping and no
     * domain check here, so far outside the data the answer is whatever the kernel extrapolates to,
     * which for the polynomial and thin-plate kernels diverges. Cost is linear in the number of
     * control points per call.
     */
    fun eval(x: Double): Double {
        var sum = base?.eval(x) ?: 0.0
        for (i in weights.indices) {
            sum += weights[i] * kernel.eval(kotlin.math.abs(x - xs[i]))
        }
        return sum
    }

    override fun toString(): String = prettySummary(
        "RbfInterpolator",
        "points" to points.size,
        "kernel" to kernel,
        "smoothing" to prettyNumber(smoothing),
        "base" to base
    )
}

object Functions {
    private const val TAU = Math.PI * 2.0

    // Closed range for 1D functions. Supports infinite and empty ranges.
    /**
     * The interval an [Fn] is meant to be sampled over: **closed at both ends**, possibly infinite,
     * possibly empty.
     *
     * `Domain(0.0, 1.0)` contains both 0 and 1 — [contains] and [containsInclusive] are the same
     * thing, there is no half-open variant. Emptiness is encoded rather than represented separately:
     * any domain whose `max < min` (or either end NaN) is empty, which is what [EMPTY] — literally
     * `Domain(1.0, 0.0)` — is. That matters because [intersect] of two disjoint domains produces
     * such a value rather than null, and because almost every transformer here returns an empty
     * domain *unchanged* instead of trying to move it.
     *
     * Domains are values: nothing mutates, every operation returns a new one. Use [ALL] for
     * functions that are genuinely unbounded (the oscillators default to it); samplers that need a
     * finite window to walk will preview such a domain over a small fallback rather than fail.
     */
    public data class Domain(val min: Double, val max: Double) : ClosedRange<Double> {
        /** [min], under the name `ClosedRange` uses. */
        override val start: Double
            get() = min

        /** [max], under the name `ClosedRange` uses — and inclusive, as that name promises. */
        override val endInclusive: Double
            get() = max

        // avoid jvm name collision with ClosedRange.isEmpty()
        /**
         * True when this domain holds no points at all: `max < min`, or either end NaN.
         *
         * The leading underscore is not style, it is a JVM signature clash with
         * `ClosedRange.isEmpty()`; read it as the ordinary `isEmpty`. Almost every method here
         * short-circuits on it, so an empty domain propagates rather than throwing.
         */
        val _isEmpty: Boolean
            get() = min.isNaN() || max.isNaN() || max < min

        /**
         * True only for a domain that is non-empty *and* bounded at both ends — the condition for
         * being able to walk it at a fixed sample count or step. [ALL] is not finite.
         */
        val isFinite: Boolean
            get() = min.isFinite() && max.isFinite() && !_isEmpty

        /**
         * `max - min`, and exactly `0.0` for an empty domain rather than a negative number.
         *
         * A point domain also has length zero, so length alone does not distinguish "one point" from
         * "no points" — ask [_isEmpty] for that. Infinite for [ALL].
         */
        val length: Double
            get() = if (_isEmpty) 0.0 else max - min

        /** The midpoint, or NaN if the domain is empty. Infinite domains give a NaN or infinite centre. */
        val center: Double
            get() = if (_isEmpty) Double.NaN else (min + max) * 0.5

        /** Whether [x] lies in the domain, **both endpoints included**. Always false when empty. */
        fun containsInclusive(x: Double): Boolean = !_isEmpty && x >= min && x <= max

        /** Whether [value] lies in the domain, endpoints included — an alias of [containsInclusive]. */
        override fun contains(value: Double): Boolean = containsInclusive(value)

        /** Grows the domain by [amount] at *each* end, so the length increases by twice that. An empty domain is returned unchanged. */
        fun expand(amount: Double): Domain = if (_isEmpty) this else Domain(min - amount, max + amount)

        /**
         * Shrinks the domain by [amount] at each end. Over-shrinking is not clamped: it produces a
         * `max < min` domain, which is simply empty. An empty domain is returned unchanged.
         */
        fun shrink(amount: Double): Domain = if (_isEmpty) this else Domain(min + amount, max - amount)

        /** Slides both ends by [dx], keeping the length. An empty domain is returned unchanged. */
        fun shift(dx: Double): Domain = if (_isEmpty) this else Domain(min + dx, max + dx)

        /**
         * Scales the domain by [s] about the point [about] — which defaults to the **origin**, not to
         * the domain's own centre, so `scale(2.0)` on `1.0..2.0` gives `2.0..4.0`.
         *
         * A negative [s] flips the interval; the result is re-ordered so `min <= max` either way. An
         * empty domain is returned unchanged.
         */
        fun scale(s: Double, about: Double = 0.0): Domain {
            if (_isEmpty) return this
            val a = (min - about) * s + about
            val b = (max - about) * s + about
            return if (a <= b) Domain(a, b) else Domain(b, a)
        }

        /**
         * The x at normalized position [alpha]: 0 gives [min], 1 gives [max]. NaN for an empty domain.
         *
         * Not clamped — an alpha outside 0..1 extrapolates past the ends — and NaN or infinite for an
         * unbounded domain, which is why the samplers resolve infinite domains to a finite preview
         * window before calling this.
         */
        fun lerp(alpha: Double): Double = if (_isEmpty) Double.NaN else min + length * alpha

        /**
         * The inverse of [lerp]: where [x] sits in the domain as a fraction, 0 at [min] and 1 at [max].
         *
         * Not clamped, so an x outside the domain gives a negative alpha or one above 1. Degenerate
         * cases collapse to `0.0` rather than NaN: both an empty domain and a single-point domain
         * (which has no width to divide by) answer zero everywhere.
         */
        fun alphaOf(x: Double): Double = if (_isEmpty || length == 0.0) 0.0 else (x - min) / length

        /**
         * [x] pulled to the nearest point in the domain — the CLAMP edge behaviour. An empty domain
         * has nothing to clamp to, so it returns [x] untouched.
         */
        fun clamp(x: Double): Double {
            if (_isEmpty) return x
            return when {
                x < min -> min
                x > max -> max
                else -> x
            }
        }

        /**
         * [x] folded into the domain by repetition — the WRAP edge behaviour, so leaving [max] re-enters
         * at [min] with a discontinuity.
         *
         * Negative offsets wrap correctly (unlike a bare `%`). Returns [x] untouched when there is
         * nothing to wrap into: an empty domain, a point domain, or an infinite one.
         */
        fun wrap(x: Double): Double {
            if (_isEmpty) return x
            val len = length
            if (!len.isFinite() || len == 0.0) return x
            val t = (x - min) % len
            val u = if (t < 0.0) t + len else t
            return min + u
        }

        /**
         * [x] folded into the domain by mirroring — the MIRROR edge behaviour, so the value turns
         * around at each end instead of jumping, giving a continuous ping-pong of period `2 * length`.
         *
         * Returns [x] untouched for an empty, point, or infinite domain.
         */
        fun reflect(x: Double): Double {
            if (_isEmpty) return x
            val len = length
            if (!len.isFinite() || len == 0.0) return x
            val two = len * 2.0
            val t = (x - min) % two
            val u = if (t < 0.0) t + two else t
            return if (u <= len) min + u else max - (u - len)
        }

        /**
         * The overlap of the two domains — the rule the arithmetic operators use, so `f + g` is
         * defined only where both are.
         *
         * Disjoint domains do not give null or throw: the result is a `max < min` domain, which is
         * simply empty. If either input is empty that empty one is returned.
         */
        fun intersect(other: Domain): Domain {
            if (this._isEmpty) return this
            if (other._isEmpty) return other
            return Domain(max(min, other.min), min(max, other.max))
        }

        /**
         * The smallest domain covering both — the rule the timeline and list combinators use to work
         * out their overall extent.
         *
         * This is a bounding hull, not a set union: a gap between two disjoint domains is swallowed.
         * An empty operand is ignored, so it acts as an identity here.
         */
        fun union(other: Domain): Domain {
            if (this._isEmpty) return other
            if (other._isEmpty) return this
            return Domain(min(min, other.min), max(max, other.max))
        }

        companion object {
            /** The whole real line. What the oscillators and other unbounded generators default to. */
            val ALL = Domain(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)

            /** `0.0..1.0`, both ends included — the normalized interval most of this toolkit assumes. */
            val UNIT = Domain(0.0, 1.0)

            /** The domain user-facing constructors default to. Currently the same value as [UNIT]. */
            val DEFAULT = UNIT

            /** A canonical empty domain (`Domain(1.0, 0.0)`). Any `max < min` domain is equally empty. */
            val EMPTY = Domain(1.0, 0.0)

            /** A domain from two endpoints in either order — unlike the constructor, this sorts them, so it never accidentally produces an empty domain. */
            fun of(a: Double, b: Double): Domain = if (a <= b) Domain(a, b) else Domain(b, a)

            /** The single-point domain `x..x`: not empty, but of zero [length]. */
            fun point(x: Double): Domain = Domain(x, x)

            /** A domain of half-width [radius] either side of [center], so its [length] is twice the radius. A negative radius produces an empty domain. */
            fun centered(center: Double, radius: Double): Domain = Domain(center - radius, center + radius)
        }

        override fun toString(): String = when {
            _isEmpty -> "Domain.EMPTY"
            this == ALL -> "Domain.ALL"
            this == DEFAULT -> "Domain.DEFAULT"
            min == max -> "Domain(${prettyNumber(min)})"
            else -> "Domain(${prettyNumber(min)}..${prettyNumber(max)})"
        }
    }

    /**
     * Human-facing domain notation.
     *
     * In a `Functions` scope, `0.0..1.0` resolves to a [Domain], which keeps
     * timeline code readable. Kotlin's built-in `0..1` is still an `IntRange`
     * because member operators win, so public DSL entry points also accept
     * numeric [ClosedRange] values and convert them with [toDomain].
     */
    operator fun Double.rangeTo(end: Double): Domain = Domain.of(this, end)

    /** `a..b` as a [Domain] inside a `Functions` scope. The endpoints are sorted, so a descending range is not empty. */
    operator fun Double.rangeTo(end: Number): Domain = Domain.of(this, end.toDouble())

    /** `a..b` as a [Domain] inside a `Functions` scope. The endpoints are sorted, so a descending range is not empty. */
    operator fun Number.rangeTo(end: Double): Domain = Domain.of(this.toDouble(), end)

    /** Converts any numeric [ClosedRange] — including Kotlin's `IntRange`, which member operators would otherwise claim — into a [Domain]. Throws if the endpoints are not numbers. */
    fun domain(range: ClosedRange<*>): Domain = range.toDomain()

    /**
     * This numeric range as a [Domain], with the endpoints sorted.
     *
     * The bridge every DSL entry point uses for its `ClosedRange` overload, so `0..1` (an `IntRange`)
     * and `0.0..1.0` arrive as the same domain. Throws [IllegalArgumentException] if an endpoint is
     * not a [Number].
     */
    fun ClosedRange<*>.toDomain(): Domain =
        Domain.of(rangeEndpoint(start), rangeEndpoint(endInclusive))

    private fun rangeEndpoint(value: Any?): Double =
        (value as? Number)?.toDouble()
            ?: throw IllegalArgumentException("Functions domains need numeric range endpoints, got $value")

    /**
     * What happens when a sampler asks for an x outside an [Fn]'s domain.
     *
     * `ZERO` is the default everywhere and does not literally mean zero — it means "use the caller's
     * `outside` value", which merely defaults to `0.0`. The other three re-enter the domain instead:
     * `CLAMP` holds the nearest endpoint, `WRAP` repeats the domain (discontinuous at the seam), and
     * `MIRROR` folds back and forth (continuous, period twice the domain length).
     */
    enum class EdgeMode { ZERO, CLAMP, WRAP, MIRROR }

    /**
     * How a table of samples is reconstructed between its entries, for `toFn` and [resample].
     *
     * `NEAREST` gives a staircase, `LINEAR` straight segments, `CUBIC` a Catmull-Rom curve through the
     * samples. `CUBIC` needs neighbours that a sample near either end does not have, and duplicates the
     * edge sample rather than extrapolating.
     */
    enum class Interpolation { NEAREST, LINEAR, CUBIC }

    /**
     * The shape of a pulse's rise and fall ramps in the `pulseTrain` family.
     *
     * `HARD` is an instantaneous step, `LINEAR` a straight ramp, `SMOOTH` a smoothstep, `SINE` a raised
     * cosine, and `EXP` an exponential whose bend is set by that factory's separate `curve` argument.
     * These shape only the *edges*; the body of the pulse is a [PulseProfile].
     */
    enum class PulseEdge { HARD, LINEAR, SMOOTH, SINE, EXP }

    /**
     * The envelope across the *body* of one pulse in the `pulseTrain` family, evaluated over the duty
     * portion of each cycle.
     *
     * `RECT` is flat (the classic rectangular pulse); `TRIANGLE`, `SINE` and `HANN` taper to zero at
     * both ends of the pulse; `GAUSSIAN` is a bump whose width comes from that factory's separate
     * `profileSigma` argument, and never quite reaches zero. Profile and [PulseEdge] multiply, so a
     * tapering profile with soft edges tapers twice.
     */
    enum class PulseProfile { RECT, TRIANGLE, SINE, HANN, GAUSSIAN }

    /**
     * How time is paced when `stretch` maps one interval onto another: `LINEAR` at constant speed,
     * `SMOOTHSTEP` and `SMOOTHERSTEP` easing in and out so the source is played slowly at the ends and
     * quickly through the middle.
     *
     * The endpoints still line up exactly in all three cases; only the traversal between them differs.
     */
    enum class StretchShape { LINEAR, SMOOTHSTEP, SMOOTHERSTEP }

    /** Rectifier polarity for the [rectify] waveshaping transform: half-wave clamps the
     * negative excursion to zero; full-wave reflects it (|x|). */
    enum class RectifyMode { HALF, FULL }

    /**
     * Named motion curves for [tween], [Fn.ease], and other alpha-shaping helpers — the common
     * normalized-time shapes for procedural motion and value tweening.
     *
     * The `_IN`/`_OUT`/`_IN_OUT` families follow the conventional Penner easings so procedural
     * animation reads the same here as in other motion toolkits.
     */
    enum class Easing {
        LINEAR,
        QUAD_IN,
        QUAD_OUT,
        QUAD_IN_OUT,
        CUBIC_IN,
        CUBIC_OUT,
        CUBIC_IN_OUT,
        QUART_IN,
        QUART_OUT,
        QUART_IN_OUT,
        QUINT_IN,
        QUINT_OUT,
        QUINT_IN_OUT,
        SINE_IN,
        SINE_OUT,
        SINE_IN_OUT,
        EXPO_IN,
        EXPO_OUT,
        EXPO_IN_OUT,
        CIRC_IN,
        CIRC_OUT,
        CIRC_IN_OUT,
        BACK_IN,
        BACK_OUT,
        BACK_IN_OUT,
        ELASTIC_IN,
        ELASTIC_OUT,
        ELASTIC_IN_OUT,
        BOUNCE_IN,
        BOUNCE_OUT,
        BOUNCE_IN_OUT,
        SMOOTHSTEP,
        SMOOTHERSTEP
    }

    // Common enum entries are re-exported here so `with(Functions)` reads like a
    // small DSL (`blend = ADD`, `profile = HANN`) instead of exposing every
    // internal enum path. Ambiguous entries such as CLAMP/WRAP stay qualified.
    /** [PulseEdge.HARD], re-exported so `edge = HARD` reads as DSL inside `with(Functions)`. */
    val HARD: PulseEdge get() = PulseEdge.HARD

    /** [PulseEdge.SMOOTH], re-exported so `edge = SMOOTH` reads as DSL inside `with(Functions)`. */
    val SMOOTH: PulseEdge get() = PulseEdge.SMOOTH

    /** [PulseEdge.EXP], re-exported so `edge = EXP` reads as DSL inside `with(Functions)`. */
    val EXP: PulseEdge get() = PulseEdge.EXP

    /** [PulseProfile.RECT], re-exported so `profile = RECT` reads as DSL inside `with(Functions)`. */
    val RECT: PulseProfile get() = PulseProfile.RECT

    /**
     * [PulseProfile.TRIANGLE], re-exported so `profile = TRIANGLE` reads as DSL — note that this is the
     * pulse *profile*, not the [triangle] oscillator that shares the word.
     */
    val TRIANGLE: PulseProfile get() = PulseProfile.TRIANGLE

    /**
     * [PulseProfile.HANN], re-exported so `profile = HANN` reads as DSL — the pulse profile, distinct
     * from the standalone [hannWindow] function.
     */
    val HANN: PulseProfile get() = PulseProfile.HANN

    /**
     * [PulseProfile.GAUSSIAN], re-exported so `profile = GAUSSIAN` reads as DSL — the pulse profile,
     * distinct from the standalone [gaussian] bump function.
     */
    val GAUSSIAN: PulseProfile get() = PulseProfile.GAUSSIAN

    /** [Interpolation.NEAREST], re-exported so `interpolation = NEAREST` reads as DSL. */
    val NEAREST: Interpolation get() = Interpolation.NEAREST

    /** [Interpolation.CUBIC], re-exported so `interpolation = CUBIC` reads as DSL. */
    val CUBIC: Interpolation get() = Interpolation.CUBIC

    /**
     * A one-dimensional signal together with the interval on which it is meant to
     * be sampled.
     *
     * Earlier drafts split this into "domain" and "ROI". That sounded flexible
     * but made composition ambiguous: arithmetic wanted intersection, timeline
     * placement wanted a destination interval, and samplers had to guess which
     * range was authoritative. For now the public model is simpler:
     * an [Fn] has one [domain]. When a timeline remaps a function, the chunk owns
     * the second interval explicitly as its placement [TimelineChunk.domain].
     *
     * User-facing constructors default to [Domain.DEFAULT] (`0.0..1.0`). Use
     * [Domain.ALL] explicitly for functions that should be unbounded or merely
     * act as non-constraining parameter signals.
     */
    data class Fn(
        val domain: Domain = Domain.DEFAULT,
        val name: String? = null,
        val graph: FnGraphNode? = null,
        private val evalFn: (Double) -> Double
    ) {
        /**
         * The value of this function at [x], computed on the spot — nothing here is cached.
         *
         * The domain is **not** checked: an x outside it is simply passed to the underlying closure,
         * which will usually answer something (an oscillator keeps oscillating), but for a
         * table-backed or interpolating function may be extrapolated nonsense. Use [evalSafe] when the
         * domain is meant to be respected. Cost is the cost of the whole graph beneath this node, so
         * evaluating a deep stack in a tight loop is genuinely expensive.
         */
        fun eval(x: Double): Double = evalFn(x)

        /** `f(x)` — call syntax for [eval], with the same lack of domain checking. */
        operator fun invoke(x: Double): Double = evalFn(x)

        /**
         * The value at [x], with an explicit policy for x outside the domain — the domain-respecting
         * counterpart to [eval] and what every sampler in this file uses internally.
         *
         * Inside the domain (endpoints included) this is exactly [eval]. Outside, [edge] decides:
         * `ZERO` returns [outside] verbatim, `CLAMP` evaluates at the nearest endpoint, `WRAP` and
         * `MIRROR` fold x back in. Note that an empty domain contains nothing, so it always yields
         * [outside] under the default edge mode.
         */
        fun evalSafe(x: Double, outside: Double = 0.0, edge: EdgeMode = EdgeMode.ZERO): Double {
            if (domain.containsInclusive(x)) return evalFn(x)
            return when (edge) {
                EdgeMode.ZERO -> outside
                EdgeMode.CLAMP -> evalFn(domain.clamp(x))
                EdgeMode.WRAP -> evalFn(domain.wrap(x))
                EdgeMode.MIRROR -> evalFn(domain.reflect(x))
            }
        }

        /**
         * A copy of this function that claims [newDomain], **relabelling only** — the evaluator is
         * reused untouched, so the same x still gives the same value.
         *
         * This is what widens or narrows the window a sampler will walk, not what rescales the signal:
         * for that (playing the whole source across a new interval) use `stretch`. Returns a new [Fn];
         * nothing mutates. Recorded in the graph as an `over` node.
         */
        fun withDomain(newDomain: Domain): Fn = Fn(
            newDomain,
            name,
            FnGraphNode(
                op = "over",
                label = "over",
                children = listOf(FnGraphEdge("source", this)),
                strings = mapOf("domain" to newDomain.toString()),
                rebuild = { children -> children[0].withDomain(newDomain) }
            ),
            evalFn
        )
        /** A copy claiming this numeric range as its domain, relabelling only — see [withDomain]. */
        fun withDomain(newDomain: ClosedRange<*>): Fn = withDomain(Functions.domain(newDomain))

        /**
         * A copy carrying [newName], which is what plot titles, `toString` and the graph renderers
         * display. Purely cosmetic — it changes no value — and passing null clears the name.
         */
        fun withName(newName: String?): Fn = Fn(
            domain,
            newName,
            FnGraphNode(
                op = "name",
                label = newName ?: "name",
                children = listOf(FnGraphEdge("source", this)),
                strings = mapOf("name" to (newName ?: "")),
                rebuild = { children -> children[0].withName(newName) }
            ),
            evalFn
        )

        override fun toString(): String = prettySummary(
            "Fn",
            "name" to (name ?: "anonymous"),
            "domain" to domain
        )
    }

    private fun blend(a: Double, b: Double, t: Double): Double = a + (b - a) * t
    private fun clamp01(x: Double): Double = x.coerceIn(0.0, 1.0)
    private fun smoothstep(t: Double): Double = t * t * (3.0 - 2.0 * t)
    private fun smootherstep(t: Double): Double = t * t * t * (t * (t * 6.0 - 15.0) + 10.0)
    private fun powIn(t: Double, p: Double): Double = t.pow(p)
    private fun powOut(t: Double, p: Double): Double = 1.0 - (1.0 - t).pow(p)
    private fun powInOut(t: Double, p: Double): Double =
        if (t < 0.5) 0.5 * (2.0 * t).pow(p) else 1.0 - 0.5 * (2.0 - 2.0 * t).pow(p)

    private fun bounceOut(t: Double): Double {
        val n = 7.5625
        val d = 2.75
        return when {
            t < 1.0 / d -> n * t * t
            t < 2.0 / d -> (t - 1.5 / d).let { n * it * it + 0.75 }
            t < 2.5 / d -> (t - 2.25 / d).let { n * it * it + 0.9375 }
            else -> (t - 2.625 / d).let { n * it * it + 0.984375 }
        }
    }

    private fun shapeAlpha(t: Double, easing: Easing): Double {
        val c1 = 1.70158
        val c2 = c1 * 1.525
        val c3 = c1 + 1.0
        val c4 = TAU / 3.0
        val c5 = TAU / 4.5
        return when (easing) {
            Easing.LINEAR -> t
            Easing.QUAD_IN -> powIn(t, 2.0)
            Easing.QUAD_OUT -> powOut(t, 2.0)
            Easing.QUAD_IN_OUT -> powInOut(t, 2.0)
            Easing.CUBIC_IN -> powIn(t, 3.0)
            Easing.CUBIC_OUT -> powOut(t, 3.0)
            Easing.CUBIC_IN_OUT -> powInOut(t, 3.0)
            Easing.QUART_IN -> powIn(t, 4.0)
            Easing.QUART_OUT -> powOut(t, 4.0)
            Easing.QUART_IN_OUT -> powInOut(t, 4.0)
            Easing.QUINT_IN -> powIn(t, 5.0)
            Easing.QUINT_OUT -> powOut(t, 5.0)
            Easing.QUINT_IN_OUT -> powInOut(t, 5.0)
            Easing.SINE_IN -> 1.0 - kotlin.math.cos(t * Math.PI / 2.0)
            Easing.SINE_OUT -> kotlin.math.sin(t * Math.PI / 2.0)
            Easing.SINE_IN_OUT -> -(kotlin.math.cos(Math.PI * t) - 1.0) * 0.5
            Easing.EXPO_IN -> if (t == 0.0) 0.0 else 2.0.pow(10.0 * t - 10.0)
            Easing.EXPO_OUT -> if (t == 1.0) 1.0 else 1.0 - 2.0.pow(-10.0 * t)
            Easing.EXPO_IN_OUT -> when {
                t == 0.0 -> 0.0
                t == 1.0 -> 1.0
                t < 0.5 -> 2.0.pow(20.0 * t - 10.0) / 2.0
                else -> (2.0 - 2.0.pow(-20.0 * t + 10.0)) / 2.0
            }
            Easing.CIRC_IN -> 1.0 - kotlin.math.sqrt(1.0 - t * t)
            Easing.CIRC_OUT -> kotlin.math.sqrt(1.0 - (t - 1.0) * (t - 1.0))
            Easing.CIRC_IN_OUT -> if (t < 0.5)
                (1.0 - kotlin.math.sqrt(1.0 - (2.0 * t) * (2.0 * t))) / 2.0
            else
                (kotlin.math.sqrt(1.0 - (-2.0 * t + 2.0) * (-2.0 * t + 2.0)) + 1.0) / 2.0
            Easing.BACK_IN -> c3 * t * t * t - c1 * t * t
            Easing.BACK_OUT -> 1.0 + c3 * (t - 1.0).pow(3.0) + c1 * (t - 1.0).pow(2.0)
            Easing.BACK_IN_OUT -> if (t < 0.5)
                ((2.0 * t) * (2.0 * t) * ((c2 + 1.0) * 2.0 * t - c2)) / 2.0
            else
                ((2.0 * t - 2.0) * (2.0 * t - 2.0) * ((c2 + 1.0) * (t * 2.0 - 2.0) + c2) + 2.0) / 2.0
            Easing.ELASTIC_IN -> when {
                t == 0.0 -> 0.0
                t == 1.0 -> 1.0
                else -> -2.0.pow(10.0 * t - 10.0) * kotlin.math.sin((t * 10.0 - 10.75) * c4)
            }
            Easing.ELASTIC_OUT -> when {
                t == 0.0 -> 0.0
                t == 1.0 -> 1.0
                else -> 2.0.pow(-10.0 * t) * kotlin.math.sin((t * 10.0 - 0.75) * c4) + 1.0
            }
            Easing.ELASTIC_IN_OUT -> when {
                t == 0.0 -> 0.0
                t == 1.0 -> 1.0
                t < 0.5 -> -(2.0.pow(20.0 * t - 10.0) * kotlin.math.sin((20.0 * t - 11.125) * c5)) / 2.0
                else -> (2.0.pow(-20.0 * t + 10.0) * kotlin.math.sin((20.0 * t - 11.125) * c5)) / 2.0 + 1.0
            }
            Easing.BOUNCE_IN -> 1.0 - bounceOut(1.0 - t)
            Easing.BOUNCE_OUT -> bounceOut(t)
            Easing.BOUNCE_IN_OUT -> if (t < 0.5)
                (1.0 - bounceOut(1.0 - 2.0 * t)) / 2.0
            else
                (1.0 + bounceOut(2.0 * t - 1.0)) / 2.0
            Easing.SMOOTHSTEP -> smoothstep(t)
            Easing.SMOOTHERSTEP -> smootherstep(t)
        }
    }
    private fun easeAlpha(alpha: Double, easing: Easing): Double = shapeAlpha(alpha.coerceIn(0.0, 1.0), easing)
    private fun fract(x: Double): Double = x - floor(x)
    private fun stretchAlpha(alpha: Double, shape: StretchShape): Double =
        when (shape) {
            StretchShape.LINEAR -> alpha
            StretchShape.SMOOTHSTEP -> smoothstep(alpha)
            StretchShape.SMOOTHERSTEP -> smootherstep(alpha)
        }

    private fun requireStretchDomain(domain: Domain, label: String) {
        require(domain.isFinite) { "$label domain must be finite for stretch; got $domain" }
    }

    private fun intersectDomains(base: Domain, vararg fns: Fn?): Domain {
        var d = base
        for (f in fns) {
            if (f != null) d = d.intersect(f.domain)
        }
        return d
    }

    private fun edgeRamp(t: Double, edge: PulseEdge, curve: Double): Double {
        val u = t.coerceIn(0.0, 1.0)
        return when (edge) {
            PulseEdge.HARD -> if (u >= 1.0) 1.0 else 0.0
            PulseEdge.LINEAR -> u
            PulseEdge.SMOOTH -> smoothstep(u)
            PulseEdge.SINE -> 0.5 - 0.5 * kotlin.math.cos(Math.PI * u)
            PulseEdge.EXP -> {
                if (curve == 0.0) u
                else {
                    val top = kotlin.math.exp(curve * u) - 1.0
                    val bot = kotlin.math.exp(curve) - 1.0
                    if (bot == 0.0) u else top / bot
                }
            }
        }
    }

    private fun pulseProfileValue(profile: PulseProfile, u: Double, sigma: Double): Double {
        val t = u.coerceIn(0.0, 1.0)
        return when (profile) {
            PulseProfile.RECT -> 1.0
            PulseProfile.TRIANGLE -> if (t < 0.5) t * 2.0 else 2.0 - t * 2.0
            PulseProfile.SINE -> kotlin.math.sin(Math.PI * t)
            PulseProfile.HANN -> 0.5 - 0.5 * kotlin.math.cos(TAU * t)
            PulseProfile.GAUSSIAN -> {
                val s = if (sigma <= 0.0) 0.15 else sigma
                val z = (t - 0.5) / s
                kotlin.math.exp(-0.5 * z * z)
            }
        }
    }

    private fun pulseShape(
        t: Double,
        duty: Double,
        rise: Double,
        fall: Double,
        edge: PulseEdge,
        curve: Double,
        profile: PulseProfile,
        profileSigma: Double
    ): Double {
        if (duty <= 0.0) return 0.0
        val d = clamp01(duty)
        val tt = t.coerceIn(0.0, 1.0)
        if (tt >= d) return 0.0
        var r = rise.coerceAtLeast(0.0)
        var f = fall.coerceAtLeast(0.0)
        val totalEdge = r + f
        if (totalEdge > d && totalEdge > 0.0) {
            val scale = d / totalEdge
            r *= scale
            f *= scale
        }
        val env = when {
            r > 0.0 && tt < r -> edgeRamp(tt / r, edge, curve)
            f > 0.0 && tt > d - f -> 1.0 - edgeRamp((tt - (d - f)) / f, edge, curve)
            else -> 1.0
        }
        val u = if (d == 0.0) 0.0 else (tt / d).coerceIn(0.0, 1.0)
        return env * pulseProfileValue(profile, u, profileSigma)
    }

    private fun combinedDomain(a: Fn, b: Fn): Domain = a.domain.intersect(b.domain)

    private fun Domain.safeShift(dx: Double): Domain = if (this._isEmpty) this else this.shift(dx)
    private fun Domain.safeScale(scale: Double, about: Double = 0.0): Domain = if (this._isEmpty) this else this.scale(scale, about)

    private fun fnEdge(role: String, fn: Fn): FnGraphEdge = FnGraphEdge(role, fn)
    private fun itemEdges(fns: List<Fn>, prefix: String = "item"): List<FnGraphEdge> =
        fns.mapIndexed { index, fn -> fnEdge("$prefix$index", fn) }

    private fun childAt(children: List<Fn>, index: Int, fallback: Fn): Fn =
        children.getOrNull(index) ?: fallback

    private fun itemChildren(children: List<Fn>, count: Int): List<Fn> =
        children.take(count)

    private fun node(
        op: String,
        label: String = op,
        children: List<FnGraphEdge> = emptyList(),
        numbers: Map<String, Double> = emptyMap(),
        strings: Map<String, String> = emptyMap(),
        booleans: Map<String, Boolean> = emptyMap(),
        longs: Map<String, Long> = emptyMap(),
        tags: Set<String> = emptySet(),
        rebuild: FnGraphRebuilder? = null
    ): FnGraphNode =
        FnGraphNode(op, label, children, numbers, strings, booleans, longs, tags, rebuild)

    private fun graphFn(
        domain: Domain,
        name: String?,
        graph: FnGraphNode,
        eval: (Double) -> Double
    ): Fn = Fn(domain, name, graph, eval)

    private fun graphFn(
        domain: Domain,
        name: String?,
        op: String,
        label: String = op,
        children: List<FnGraphEdge> = emptyList(),
        numbers: Map<String, Double> = emptyMap(),
        strings: Map<String, String> = emptyMap(),
        booleans: Map<String, Boolean> = emptyMap(),
        longs: Map<String, Long> = emptyMap(),
        tags: Set<String> = emptySet(),
        rebuild: FnGraphRebuilder? = null,
        eval: (Double) -> Double
    ): Fn = graphFn(
        domain,
        name,
        node(op, label, children, numbers, strings, booleans, longs, tags, rebuild),
        eval
    )

    /**
     * Wraps an arbitrary Kotlin lambda as an [Fn] — the escape hatch for anything the library's own
     * combinators cannot express.
     *
     * The lambda is called lazily, once per sample, and is trusted completely: it may be discontinuous,
     * unbounded, or slow. The one real cost is structural. The node this produces is tagged *opaque*,
     * because a closure cannot describe how to rebuild itself, so the graph-rewrite and evolution
     * machinery can inspect it but never edit inside it, and a deep copy keeps the original closure
     * rather than reconstructing it. Prefer a real combinator where one exists.
     */
    fun fn(
        domain: Domain = Domain.DEFAULT,
        name: String? = null,
        eval: (Double) -> Double
    ): Fn = graphFn(
        domain,
        name,
        node("lambda", label = name ?: "lambda", tags = setOf("opaque"))
    ) { x -> eval(x) }

    /** Wraps an arbitrary Kotlin lambda as an [Fn] over the given numeric range — see [fn]; the node is opaque to graph rewriting. */
    fun fn(
        domain: ClosedRange<*>,
        name: String? = null,
        eval: (Double) -> Double
    ): Fn = fn(domain.toDomain(), name, eval)

    // Base functions
    /**
     * A constant function: [value] everywhere, over [domain] (`0.0..1.0` by default).
     *
     * The workhorse literal of the toolkit, and the one to reach for when the constant is a *signal* in
     * its own right. Note the deliberate asymmetry with [Number.c]: `c(0.5)` is a real constant
     * *function* and therefore carries the default bounded domain, which will shrink the domain of
     * anything it is combined with; `0.5.c` is a parameter signal over [Domain.ALL] and will not. The
     * factories in this file take the second route, **not** this one: a `Number` passed where an [Fn]
     * is expected is wrapped as an unbounded parameter, precisely so that configuring a function does
     * not clip it. So `sin(frequency = 2.0)` and `sin(frequency = 2.0.c)` agree, while
     * `sin(frequency = c(2.0))` clips the oscillator to `0.0..1.0`.
     */
    fun c(value: Number, domain: Domain = Domain.DEFAULT): Fn {
        val v = value.toDouble()
        return graphFn(
            domain,
            "const",
            node(
                "const",
                numbers = mapOf("value" to v),
                tags = setOf("number"),
                rebuild = { c(v, domain) }
            )
        ) { v }
    }

    private fun param(value: Number): Fn {
        val v = value.toDouble()
        return graphFn(
            Domain.ALL,
            "param",
            node(
                "param",
                numbers = mapOf("value" to v),
                tags = setOf("number", "parameter"),
                rebuild = { param(v) }
            )
        ) { v }
    }

    private fun asParam(value: Any, parameterName: String): Fn = when (value) {
        is Fn -> value
        is Number -> param(value)
        else -> error("$parameterName must be an Fn or Number, got ${value::class.simpleName ?: "unknown"}")
    }

    private fun asOptionalParam(value: Any?, parameterName: String): Fn? =
        value?.let { asParam(it, parameterName) }

    // `c(0.5)` is a real public constant and therefore has the default domain.
    // `0.5.c` is intentionally a parameter signal: it should not shrink the
    // domain of the function it is configuring.
    /**
     * This number as a **parameter signal**: a constant over [Domain.ALL], so using it to configure a
     * function will not shrink that function's domain.
     *
     * That unbounded domain is the whole point of the distinction from [c]. Domains intersect when
     * functions combine, so `sin(frequency = c(2.0))` would clip the oscillator to `0.0..1.0`, whereas
     * `sin(frequency = 2.0.c)` leaves it alone. Reach for `2.0.c` whenever the number is a knob rather
     * than a signal in its own right.
     */
    val Number.c: Fn
        get() = param(this)

    /** A constant function: [value] everywhere, over the given numeric range. See [c]. */
    fun c(value: Number, domain: ClosedRange<*>): Fn =
        c(value, domain.toDomain())

    /** A constant function: [value] everywhere. A spelled-out alias of [c], for code that would rather read as prose. */
    fun constant(value: Number, domain: Domain = Domain.DEFAULT): Fn =
        c(value, domain)

    /** A constant function: [value] everywhere. A spelled-out alias of [c], for code that would rather read as prose. */
    fun constant(value: Number, domain: ClosedRange<*>): Fn =
        c(value, domain)

    /** The constant zero over [domain] — the additive identity, and what the list combinators return for an empty list. */
    fun zero(domain: Domain = Domain.DEFAULT): Fn = c(0.0, domain)

    /** The constant one over [domain] — the multiplicative identity, handy as the `1` in expressions like `one(d) - f`. */
    fun one(domain: Domain = Domain.DEFAULT): Fn = c(1.0, domain)

    /** The constant zero over the given numeric range. */
    fun zero(domain: ClosedRange<*>): Fn = zero(domain.toDomain())

    /** The constant one over the given numeric range. */
    fun one(domain: ClosedRange<*>): Fn = one(domain.toDomain())

    /**
     * `f(x) = x`: the raw coordinate as a signal, named `x` in graphs and plots.
     *
     * The thing to modulate with when you want a parameter to depend on position — `sin(frequency =
     * identity() * 8.0)` sweeps frequency across the domain. Over the default `0.0..1.0` it coincides
     * with `ramp(0.0, 1.0)`, but it keeps that identity for any domain rather than renormalizing.
     */
    fun identity(domain: Domain = Domain.DEFAULT): Fn =
        graphFn(
            domain,
            "x",
            node("identity", label = "x", rebuild = { identity(domain) })
        ) { x -> x }

    fun identity(domain: ClosedRange<*>): Fn =
        identity(domain.toDomain())

    /**
     * A **value ramp**: a straight line whose value goes from [start] to [end] as x
     * sweeps the function's [domain]. `ramp(1.0, 6.0) over (0.0..1.0)` is 1 at x=0 and
     * 6 at x=1; `ramp(0.0, 1.0)` is the identity over the default 0..1 domain. This is
     * the linear sibling of [tween] (which eases the same endpoints). An unbounded
     * domain is previewed over a finite window via [Domain.finiteSweep], so a ramp on
     * `Domain.ALL` stays finite to enumerate.
     *
     * For the inverse operation — *normalizing* an x-window down to 0..1 — use [remap].
     */
    fun ramp(
        start: Fn = param(0.0),
        end: Fn = param(1.0),
        domain: Domain = Domain.DEFAULT
    ): Fn {
        val d = intersectDomains(domain, start, end)
        return graphFn(
            d,
            "ramp",
            node(
                "ramp",
                children = listOf(fnEdge("start", start), fnEdge("end", end)),
                rebuild = { children -> ramp(children[0], children[1], domain) }
            )
        ) { x ->
            blend(start.eval(x), end.eval(x), domain.finiteSweep().alphaOf(x))
        }
    }

    /**
     * A **value ramp**: a straight line whose value travels from `start` to `end` as x sweeps the
     * domain. Endpoints may be plain numbers or signals; the `Any` overloads throw for anything that is
     * neither. For the opposite reading — normalizing an x-window onto 0..1 — use [remap].
     */
    fun ramp(
        start: Fn = param(0.0),
        end: Fn = param(1.0),
        domain: ClosedRange<*>
    ): Fn = ramp(start, end, domain.toDomain())

    /**
     * A **value ramp**: a straight line whose value travels from `start` to `end` as x sweeps the
     * domain. Endpoints may be plain numbers or signals; the `Any` overloads throw for anything that is
     * neither. For the opposite reading — normalizing an x-window onto 0..1 — use [remap].
     */
    fun ramp(start: Number, end: Number, domain: Domain = Domain.DEFAULT): Fn =
        ramp(param(start), param(end), domain)

    /**
     * A **value ramp**: a straight line whose value travels from `start` to `end` as x sweeps the
     * domain. Endpoints may be plain numbers or signals; the `Any` overloads throw for anything that is
     * neither. For the opposite reading — normalizing an x-window onto 0..1 — use [remap].
     */
    fun ramp(start: Number, end: Number, domain: ClosedRange<*>): Fn =
        ramp(start, end, domain.toDomain())

    /**
     * A **value ramp**: a straight line whose value travels from `start` to `end` as x sweeps the
     * domain. Endpoints may be plain numbers or signals; the `Any` overloads throw for anything that is
     * neither. For the opposite reading — normalizing an x-window onto 0..1 — use [remap].
     */
    fun ramp(start: Any, end: Any, domain: Domain = Domain.DEFAULT): Fn =
        ramp(asParam(start, "start"), asParam(end, "end"), domain)

    /**
     * A **value ramp**: a straight line whose value travels from `start` to `end` as x sweeps the
     * domain. Endpoints may be plain numbers or signals; the `Any` overloads throw for anything that is
     * neither. For the opposite reading — normalizing an x-window onto 0..1 — use [remap].
     */
    fun ramp(start: Any, end: Any, domain: ClosedRange<*>): Fn =
        ramp(start, end, domain.toDomain())

    /**
     * A **domain normalizer**: maps the x-window `[from, to]` onto `0..1`, i.e.
     * `(x - from) / (to - from)`. `remap(0.0, 10.0)` turns an x in 0..10 into a 0..1
     * alpha; `remap(0.0, 1.0)` is the identity. This is the position-of-x reading —
     * distinct from [ramp], which produces a value that *travels* from one number to
     * another across the domain.
     */
    fun remap(
        from: Fn = param(0.0),
        to: Fn = param(1.0),
        domain: Domain = Domain.DEFAULT
    ): Fn {
        val d = intersectDomains(domain, from, to)
        return graphFn(
            d,
            "remap",
            node(
                "remap",
                children = listOf(fnEdge("from", from), fnEdge("to", to)),
                rebuild = { children -> remap(children[0], children[1], domain) }
            )
        ) { x ->
            val s = from.eval(x)
            val e = to.eval(x)
            val len = e - s
            if (len == 0.0) 0.0 else (x - s) / len
        }
    }

    /**
     * A **domain normalizer**: `(x - from) / (to - from)`, so an x in the window `from..to` reads out as
     * a 0..1 alpha. Not clamped — x outside the window gives an alpha outside 0..1 — and a zero-width
     * window answers `0.0` rather than dividing by zero. The value-travelling counterpart is [ramp].
     */
    fun remap(
        from: Fn = param(0.0),
        to: Fn = param(1.0),
        domain: ClosedRange<*>
    ): Fn = remap(from, to, domain.toDomain())

    /**
     * A **domain normalizer**: `(x - from) / (to - from)`, so an x in the window `from..to` reads out as
     * a 0..1 alpha. Not clamped — x outside the window gives an alpha outside 0..1 — and a zero-width
     * window answers `0.0` rather than dividing by zero. The value-travelling counterpart is [ramp].
     */
    fun remap(from: Number, to: Number, domain: Domain = Domain.DEFAULT): Fn =
        remap(param(from), param(to), domain)

    /**
     * A **domain normalizer**: `(x - from) / (to - from)`, so an x in the window `from..to` reads out as
     * a 0..1 alpha. Not clamped — x outside the window gives an alpha outside 0..1 — and a zero-width
     * window answers `0.0` rather than dividing by zero. The value-travelling counterpart is [ramp].
     */
    fun remap(from: Number, to: Number, domain: ClosedRange<*>): Fn =
        remap(from, to, domain.toDomain())

    /**
     * A **domain normalizer**: `(x - from) / (to - from)`, so an x in the window `from..to` reads out as
     * a 0..1 alpha. Not clamped — x outside the window gives an alpha outside 0..1 — and a zero-width
     * window answers `0.0` rather than dividing by zero. The value-travelling counterpart is [ramp].
     */
    fun remap(from: Any, to: Any, domain: Domain = Domain.DEFAULT): Fn =
        remap(asParam(from, "from"), asParam(to, "to"), domain)

    /**
     * A **domain normalizer**: `(x - from) / (to - from)`, so an x in the window `from..to` reads out as
     * a 0..1 alpha. Not clamped — x outside the window gives an alpha outside 0..1 — and a zero-width
     * window answers `0.0` rather than dividing by zero. The value-travelling counterpart is [ramp].
     */
    fun remap(from: Any, to: Any, domain: ClosedRange<*>): Fn =
        remap(from, to, domain.toDomain())

    /**
     * A hard step: `0` below [edge], `1` from [edge] onwards — the Heaviside function, inclusive at the
     * threshold itself.
     *
     * [edge] may be a signal, in which case it is sampled at the same x being tested, so a moving
     * threshold is compared against the position it occupies right there. For a soft version use
     * [smoothStep].
     */
    fun step(edge: Fn = param(0.0), domain: Domain = Domain.DEFAULT): Fn {
        val d = intersectDomains(domain, edge)
        return graphFn(
            d,
            "step",
            node(
                "step",
                children = listOf(fnEdge("edge", edge)),
                rebuild = { children -> step(children[0], domain) }
            )
        ) { x -> if (x >= edge.eval(x)) 1.0 else 0.0 }
    }

    /** A hard step: `0` below `edge`, `1` from `edge` onwards, the threshold itself included. The soft version is [smoothStep]. */
    fun step(edge: Fn = param(0.0), domain: ClosedRange<*>): Fn =
        step(edge, domain.toDomain())

    /** A hard step: `0` below `edge`, `1` from `edge` onwards, the threshold itself included. The soft version is [smoothStep]. */
    fun step(edge: Number, domain: Domain = Domain.DEFAULT): Fn =
        step(param(edge), domain)

    /** A hard step: `0` below `edge`, `1` from `edge` onwards, the threshold itself included. The soft version is [smoothStep]. */
    fun step(edge: Number, domain: ClosedRange<*>): Fn =
        step(edge, domain.toDomain())

    /**
     * A soft step in **x**: `0` up to `edge0`, `1` from `edge1` on, and the classic `3t² - 2t³` ease
     * between them.
     *
     * The two edges are sorted internally, so passing them the wrong way round produces a rising step
     * rather than a falling one; if they coincide it degenerates to a hard [step]. Output is clamped to
     * 0..1 outside the transition, and both edges may be signals. Note that this shapes position — to
     * ease a *value* that is already normalized, use `Fn.ease`.
     */
    fun smoothStep(edge0: Fn = param(0.0), edge1: Fn = param(1.0), domain: Domain = Domain.DEFAULT): Fn {
        val d = intersectDomains(domain, edge0, edge1)
        return graphFn(
            d,
            "smoothstep",
            node(
                "smoothstep",
                children = listOf(fnEdge("edge0", edge0), fnEdge("edge1", edge1)),
                rebuild = { children -> smoothStep(children[0], children[1], domain) }
            )
        ) { x ->
            val a = edge0.eval(x)
            val b = edge1.eval(x)
            val e0 = min(a, b)
            val e1 = max(a, b)
            if (e1 == e0) {
                if (x < e0) 0.0 else 1.0
            } else {
                val t = clamp01((x - e0) / (e1 - e0))
                smoothstep(t)
            }
        }
    }

    /** A soft step in x: `0` up to `edge0`, `1` from `edge1` on, `3t² - 2t³` between. The edges are sorted, so their order does not matter; equal edges give a hard [step]. */
    fun smoothStep(edge0: Fn = param(0.0), edge1: Fn = param(1.0), domain: ClosedRange<*>): Fn =
        smoothStep(edge0, edge1, domain.toDomain())

    /** A soft step in x: `0` up to `edge0`, `1` from `edge1` on, `3t² - 2t³` between. The edges are sorted, so their order does not matter; equal edges give a hard [step]. */
    fun smoothStep(edge0: Number, edge1: Number, domain: Domain = Domain.DEFAULT): Fn =
        smoothStep(param(edge0), param(edge1), domain)

    /** A soft step in x: `0` up to `edge0`, `1` from `edge1` on, `3t² - 2t³` between. The edges are sorted, so their order does not matter; equal edges give a hard [step]. */
    fun smoothStep(edge0: Number, edge1: Number, domain: ClosedRange<*>): Fn =
        smoothStep(edge0, edge1, domain.toDomain())

    /** A soft step in x: `0` up to `edge0`, `1` from `edge1` on, `3t² - 2t³` between. The edges are sorted, so their order does not matter; equal edges give a hard [step]. */
    fun smoothStep(edge0: Any, edge1: Any, domain: Domain = Domain.DEFAULT): Fn =
        smoothStep(asParam(edge0, "edge0"), asParam(edge1, "edge1"), domain)

    /** A soft step in x: `0` up to `edge0`, `1` from `edge1` on, `3t² - 2t³` between. The edges are sorted, so their order does not matter; equal edges give a hard [step]. */
    fun smoothStep(edge0: Any, edge1: Any, domain: ClosedRange<*>): Fn =
        smoothStep(edge0, edge1, domain.toDomain())

    /**
     * Interpolates from [from] to [to] across [domain] with a named motion curve.
     * The endpoints may themselves be signals, which keeps tweening compositional.
     */
    fun tween(
        from: Fn = param(0.0),
        to: Fn = param(1.0),
        domain: Domain = Domain.DEFAULT,
        easing: Easing = Easing.SMOOTHSTEP
    ): Fn {
        val d = intersectDomains(domain, from, to)
        return graphFn(
            d,
            "tween",
            "tween",
            children = listOf(fnEdge("from", from), fnEdge("to", to)),
            strings = mapOf("easing" to easing.name),
            rebuild = { children -> tween(children[0], children[1], domain, easing) }
        ) { x ->
            val t = easeAlpha(domain.finiteSweep().alphaOf(x), easing)
            blend(from.eval(x), to.eval(x), t)
        }
    }

    /**
     * Interpolates from [from] to [to] across the domain with a named [Easing] — the eased sibling of
     * [ramp], and the usual way to write a piece of procedural motion. An unbounded domain is previewed
     * over a finite window, so the alpha stays meaningful.
     */
    fun tween(
        from: Number,
        to: Number,
        domain: Domain = Domain.DEFAULT,
        easing: Easing = Easing.SMOOTHSTEP
    ): Fn = tween(param(from), param(to), domain, easing)

    /**
     * Interpolates from [from] to [to] across the domain with a named [Easing] — the eased sibling of
     * [ramp], and the usual way to write a piece of procedural motion. An unbounded domain is previewed
     * over a finite window, so the alpha stays meaningful.
     */
    fun tween(
        from: Number,
        to: Number,
        domain: ClosedRange<*>,
        easing: Easing = Easing.SMOOTHSTEP
    ): Fn = tween(from, to, domain.toDomain(), easing)

    /** Shapes a normalized signal through [easing], optionally clamping it to 0..1 first. */
    fun Fn.ease(easing: Easing = Easing.SMOOTHSTEP, clampInput: Boolean = true): Fn =
        graphFn(
            domain,
            name?.let { "ease($it)" } ?: "ease",
            "ease",
            children = listOf(fnEdge("source", this)),
            strings = mapOf("easing" to easing.name),
            booleans = mapOf("clampInput" to clampInput),
            rebuild = { children -> children[0].ease(easing, clampInput) }
        ) { x ->
            val value = eval(x)
            if (clampInput) easeAlpha(value, easing)
            else shapeAlpha(value, easing)
        }

    /**
     * A sine oscillator: `offset + amplitude · sin(2π · frequency · x + phase)`.
     *
     * [frequency] is in **cycles per unit x** — one turn per unit at `frequency = 1.0`, so it is only
     * Hz if x happens to be seconds. [phase] is in **radians**, which is the trap in this family: the
     * [saw] / [triangle] / [square] oscillators take a phase measured in x units instead. Every
     * parameter may itself be an [Fn], which is how vibrato, tremolo and FM are written here.
     *
     * The domain defaults to [Domain.ALL] and is intersected with the parameters' domains, so passing a
     * bounded constant (`c(2.0)` rather than `2.0` or `2.0.c`) will silently clip the oscillator.
     */
    fun sin(
        frequency: Fn = param(1.0),
        phase: Fn = param(0.0),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        domain: Domain = Domain.ALL
    ): Fn {
        val d = intersectDomains(domain, frequency, phase, amplitude, offset)
        return graphFn(
            d,
            "sin",
            node(
                "sin",
                children = listOf(
                    fnEdge("frequency", frequency),
                    fnEdge("phase", phase),
                    fnEdge("amplitude", amplitude),
                    fnEdge("offset", offset)
                ),
                rebuild = { children -> sin(children[0], children[1], children[2], children[3], domain) }
            )
        ) { x ->
            offset.eval(x) + amplitude.eval(x) * kotlin.math.sin(TAU * frequency.eval(x) * x + phase.eval(x))
        }
    }

    /** A sine oscillator, `offset + amplitude · sin(2π · frequency · x + phase)`: [frequency] in cycles per unit x, [phase] in radians. Any parameter may be a signal. */
    fun sin(
        frequency: Fn = param(1.0),
        phase: Fn = param(0.0),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        domain: ClosedRange<*>
    ): Fn = sin(frequency, phase, amplitude, offset, domain.toDomain())

    /**
     * A cosine oscillator: `offset + amplitude · cos(2π · frequency · x + phase)` — [sin] shifted a
     * quarter turn, so it starts at its peak rather than at zero.
     *
     * [frequency] is in **cycles per unit x** and [phase] in **radians**, unlike the
     * [saw] / [triangle] / [square] family whose phase is measured in x units. Any parameter may be a
     * signal. The domain defaults to [Domain.ALL] and is intersected with the parameters' domains.
     */
    fun cos(
        frequency: Fn = param(1.0),
        phase: Fn = param(0.0),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        domain: Domain = Domain.ALL
    ): Fn {
        val d = intersectDomains(domain, frequency, phase, amplitude, offset)
        return graphFn(
            d,
            "cos",
            node(
                "cos",
                children = listOf(
                    fnEdge("frequency", frequency),
                    fnEdge("phase", phase),
                    fnEdge("amplitude", amplitude),
                    fnEdge("offset", offset)
                ),
                rebuild = { children -> cos(children[0], children[1], children[2], children[3], domain) }
            )
        ) { x ->
            offset.eval(x) + amplitude.eval(x) * kotlin.math.cos(TAU * frequency.eval(x) * x + phase.eval(x))
        }
    }

    /** A cosine oscillator, `offset + amplitude · cos(2π · frequency · x + phase)`: [frequency] in cycles per unit x, [phase] in radians. Any parameter may be a signal. */
    fun cos(
        frequency: Fn = param(1.0),
        phase: Fn = param(0.0),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        domain: ClosedRange<*>
    ): Fn = cos(frequency, phase, amplitude, offset, domain.toDomain())

    /**
     * A rising sawtooth: a straight ramp from `-amplitude` to `+amplitude` across each [period], then an
     * instantaneous jump back down.
     *
     * Bipolar, and specified by **period, not frequency** — [period] is a width in x units, and [phase]
     * is an offset in those same units (so a phase of one period is a whole cycle). That is the opposite
     * convention to [sin], whose phase is in radians. The period's sign is ignored, and a period of zero
     * degenerates to just [offset]. Any parameter may be a signal.
     */
    fun saw(
        period: Fn = param(1.0),
        phase: Fn = param(0.0),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        domain: Domain = Domain.ALL
    ): Fn {
        val d = intersectDomains(domain, period, phase, amplitude, offset)
        return graphFn(
            d,
            "saw",
            node(
                "saw",
                children = listOf(
                    fnEdge("period", period),
                    fnEdge("phase", phase),
                    fnEdge("amplitude", amplitude),
                    fnEdge("offset", offset)
                ),
                rebuild = { children -> saw(children[0], children[1], children[2], children[3], domain) }
            )
        ) { x ->
            val p = kotlin.math.abs(period.eval(x))
            if (p == 0.0) offset.eval(x)
            else {
                val t = (x - phase.eval(x)) / p
                val f = fract(t)
                offset.eval(x) + amplitude.eval(x) * (f * 2.0 - 1.0)
            }
        }
    }

    /** A rising sawtooth from `-amplitude` to `+amplitude` across each [period], then a jump back. [period] is a width in x units and [phase] an offset in those units, not radians. */
    fun saw(
        period: Fn = param(1.0),
        phase: Fn = param(0.0),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        domain: ClosedRange<*>
    ): Fn = saw(period, phase, amplitude, offset, domain.toDomain())

    /**
     * A triangle wave: linear up from `-amplitude` to `+amplitude` over the first half of each [period],
     * linear back down over the second — continuous everywhere, unlike [saw].
     *
     * Bipolar, and specified by **period, not frequency**: [period] is a width in x units and [phase] an
     * offset in those same units, not radians as in [sin]. The period's sign is ignored, and a period of
     * zero degenerates to just [offset]. Any parameter may be a signal.
     */
    fun triangle(
        period: Fn = param(1.0),
        phase: Fn = param(0.0),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        domain: Domain = Domain.ALL
    ): Fn {
        val d = intersectDomains(domain, period, phase, amplitude, offset)
        return graphFn(
            d,
            "triangle",
            node(
                "triangle",
                children = listOf(
                    fnEdge("period", period),
                    fnEdge("phase", phase),
                    fnEdge("amplitude", amplitude),
                    fnEdge("offset", offset)
                ),
                rebuild = { children -> triangle(children[0], children[1], children[2], children[3], domain) }
            )
        ) { x ->
            val p = kotlin.math.abs(period.eval(x))
            if (p == 0.0) offset.eval(x)
            else {
                val t = fract((x - phase.eval(x)) / p)
                val v = if (t < 0.5) t * 2.0 else 2.0 - t * 2.0
                offset.eval(x) + amplitude.eval(x) * (v * 2.0 - 1.0)
            }
        }
    }

    /** A triangle wave: linear up then linear down across each [period], continuous throughout. [period] is a width in x units and [phase] an offset in those units, not radians. */
    fun triangle(
        period: Fn = param(1.0),
        phase: Fn = param(0.0),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        domain: ClosedRange<*>
    ): Fn = triangle(period, phase, amplitude, offset, domain.toDomain())

    /**
     * A square wave: `+amplitude` for the first [duty] fraction of each [period] and `-amplitude` for the
     * rest.
     *
     * **Bipolar, not a 0/1 gate** — for a unipolar pulse with shaped edges reach for [pulseTrain]
     * instead. [duty] is a fraction clamped to 0..1 (0.5 gives the symmetric wave), [period] is a width
     * in x units, and [phase] an offset in those same units rather than radians. A period of zero
     * degenerates to just [offset]. Any parameter may be a signal, so a swept duty is pulse-width
     * modulation.
     */
    fun square(
        period: Fn = param(1.0),
        phase: Fn = param(0.0),
        duty: Fn = param(0.5),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        domain: Domain = Domain.ALL
    ): Fn {
        val d = intersectDomains(domain, period, phase, duty, amplitude, offset)
        return graphFn(
            d,
            "square",
            node(
                "square",
                children = listOf(
                    fnEdge("period", period),
                    fnEdge("phase", phase),
                    fnEdge("duty", duty),
                    fnEdge("amplitude", amplitude),
                    fnEdge("offset", offset)
                ),
                rebuild = { children -> square(children[0], children[1], children[2], children[3], children[4], domain) }
            )
        ) { x ->
            val p = kotlin.math.abs(period.eval(x))
            if (p == 0.0) offset.eval(x)
            else {
                val t = fract((x - phase.eval(x)) / p)
                val dval = clamp01(duty.eval(x))
                offset.eval(x) + if (t < dval) amplitude.eval(x) else -amplitude.eval(x)
            }
        }
    }

    /** A bipolar square wave: `+amplitude` for the first [duty] fraction of each [period], `-amplitude` for the rest. [period] and [phase] are in x units; [duty] is clamped to 0..1. */
    fun square(
        period: Fn = param(1.0),
        phase: Fn = param(0.0),
        duty: Fn = param(0.5),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        domain: ClosedRange<*>
    ): Fn = square(period, phase, duty, amplitude, offset, domain.toDomain())

    /**
     * The general pulse generator: one shaped pulse per [period], with control over width, edges,
     * body profile and per-cycle jitter. Where [square] gives you a bipolar two-level wave, this gives
     * you an envelope you can actually design.
     *
     * **Unipolar by default** — the output runs from [offset] to `offset + amplitude`, which is what
     * makes it useful as a gate or an envelope. Set `bipolar = true` to map that onto
     * `offset ± amplitude` instead.
     *
     * The units are the thing to get right. [period] and [phase] are widths and offsets in x units (not
     * a frequency and not radians — see [pulseTrainHz] if you would rather specify frequency). [duty] is
     * the fraction of each period the pulse occupies, clamped to 0..1. [rise] and [fall], though, are
     * absolute widths **in x units**, converted internally to fractions of the period; if together they
     * would exceed the duty window they are scaled down proportionally to fit, so asking for edges
     * longer than the pulse gives you a pulse that is all edge. [edge] shapes those ramps
     * ([curve] only bites for `EXP`), while [profile] shapes the body ([profileSigma] only bites for
     * `GAUSSIAN`) — the two multiply, so a tapering profile with soft edges tapers twice.
     *
     * [jitter] displaces each cycle by up to half a jitter-fraction of a period either way, chosen by
     * hashing the cycle index against [seed], so it is random-looking but exactly reproducible.
     * [dutyMod], [ampMod] and [phaseMod] are additive, multiplicative and additive modulation inputs
     * layered on top of the corresponding parameters, which is how PWM and tremolo are patched in. A
     * [period] of zero degenerates to just [offset].
     */
    fun pulseTrain(
        period: Fn = param(1.0),
        phase: Fn = param(0.0),
        duty: Fn = param(0.5),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        rise: Fn = param(0.0),
        fall: Fn = param(0.0),
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Fn = param(2.0),
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Fn = param(0.15),
        jitter: Fn = param(0.0),
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Fn = param(0.0),
        ampMod: Fn = param(1.0),
        phaseMod: Fn = param(0.0),
        domain: Domain = Domain.ALL
    ): Fn {
        val d = intersectDomains(domain, period, phase, duty, amplitude, offset, rise, fall, curve, profileSigma, jitter, dutyMod, ampMod, phaseMod)
        return graphFn(
            d,
            "pulsetrain",
            node(
                "pulsetrain",
                children = listOf(
                    fnEdge("period", period),
                    fnEdge("phase", phase),
                    fnEdge("duty", duty),
                    fnEdge("amplitude", amplitude),
                    fnEdge("offset", offset),
                    fnEdge("rise", rise),
                    fnEdge("fall", fall),
                    fnEdge("curve", curve),
                    fnEdge("profileSigma", profileSigma),
                    fnEdge("jitter", jitter),
                    fnEdge("dutyMod", dutyMod),
                    fnEdge("ampMod", ampMod),
                    fnEdge("phaseMod", phaseMod)
                ),
                strings = mapOf("edge" to edge.name, "profile" to profile.name),
                booleans = mapOf("bipolar" to bipolar),
                longs = mapOf("seed" to seed),
                rebuild = { children ->
                    pulseTrain(
                        children[0],
                        children[1],
                        children[2],
                        children[3],
                        children[4],
                        children[5],
                        children[6],
                        edge,
                        children[7],
                        profile,
                        children[8],
                        children[9],
                        seed,
                        bipolar,
                        children[10],
                        children[11],
                        children[12],
                        domain
                    )
                }
            )
        ) { x ->
            val p = kotlin.math.abs(period.eval(x))
            if (p == 0.0) return@graphFn offset.eval(x)
            val phaseShift = phase.eval(x) + phaseMod.eval(x)
            val raw = (x - phaseShift) / p
            val cycle = floor(raw)
            var local = (x - phaseShift) - cycle * p
            val jit = jitter.eval(x)
            if (jit != 0.0) {
                val j = (hashToUnit(seed, cycle.toLong()) - 0.5) * jit * p
                local -= j
            }
            local = ((local % p) + p) % p
            val t = local / p
            val dval = (duty.eval(x) + dutyMod.eval(x)).coerceIn(0.0, 1.0)
            val riseFrac = (rise.eval(x) / p).coerceIn(0.0, 1.0)
            val fallFrac = (fall.eval(x) / p).coerceIn(0.0, 1.0)
            val shaped = pulseShape(t, dval, riseFrac, fallFrac, edge, curve.eval(x), profile, profileSigma.eval(x))
            val amp = amplitude.eval(x) * ampMod.eval(x)
            val off = offset.eval(x)
            if (bipolar) off + (shaped * 2.0 - 1.0) * amp else off + shaped * amp
        }
    }

    /**
     * The general pulse generator: one shaped pulse per [period], unipolar from [offset] to
     * `offset + amplitude` unless [bipolar] is set. [period], [phase], [rise] and [fall] are all in x
     * units; [duty] is a fraction clamped to 0..1. See the primary overload for the full conventions.
     */
    fun pulseTrain(
        period: Fn = param(1.0),
        phase: Fn = param(0.0),
        duty: Fn = param(0.5),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        rise: Fn = param(0.0),
        fall: Fn = param(0.0),
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Fn = param(2.0),
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Fn = param(0.15),
        jitter: Fn = param(0.0),
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Fn = param(0.0),
        ampMod: Fn = param(1.0),
        phaseMod: Fn = param(0.0),
        domain: ClosedRange<*>
    ): Fn = pulseTrain(period, phase, duty, amplitude, offset, rise, fall, edge, curve, profile, profileSigma, jitter, seed, bipolar, dutyMod, ampMod, phaseMod, domain.toDomain())

    /**
     * The general pulse generator: one shaped pulse per [period], unipolar from [offset] to
     * `offset + amplitude` unless [bipolar] is set. [period], [phase], [rise] and [fall] are all in x
     * units; [duty] is a fraction clamped to 0..1. See the primary overload for the full conventions.
     */
    fun pulseTrain(
        period: Number,
        phase: Number = 0.0,
        duty: Number = 0.5,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        rise: Number = 0.0,
        fall: Number = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Number = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Number = 0.15,
        jitter: Number = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Number = 0.0,
        ampMod: Number = 1.0,
        phaseMod: Number = 0.0,
        domain: Domain = Domain.ALL
    ): Fn = pulseTrain(param(period), param(phase), param(duty), param(amplitude), param(offset), param(rise), param(fall), edge, param(curve), profile, param(profileSigma), param(jitter), seed, bipolar, param(dutyMod), param(ampMod), param(phaseMod), domain)

    /**
     * The general pulse generator: one shaped pulse per [period], unipolar from [offset] to
     * `offset + amplitude` unless [bipolar] is set. [period], [phase], [rise] and [fall] are all in x
     * units; [duty] is a fraction clamped to 0..1. See the primary overload for the full conventions.
     */
    fun pulseTrain(
        period: Number,
        phase: Number = 0.0,
        duty: Number = 0.5,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        rise: Number = 0.0,
        fall: Number = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Number = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Number = 0.15,
        jitter: Number = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Number = 0.0,
        ampMod: Number = 1.0,
        phaseMod: Number = 0.0,
        domain: ClosedRange<*>
    ): Fn = pulseTrain(period, phase, duty, amplitude, offset, rise, fall, edge, curve, profile, profileSigma, jitter, seed, bipolar, dutyMod, ampMod, phaseMod, domain.toDomain())

    /**
     * The general pulse generator, taking each parameter as either a number or an [Fn] and throwing for
     * anything else: one shaped pulse per [period], unipolar from [offset] to `offset + amplitude`
     * unless [bipolar] is set. [period], [phase], [rise] and [fall] are in x units; [duty] is a fraction
     * clamped to 0..1. See the primary overload for the full conventions.
     */
    fun pulseTrain(
        period: Any,
        phase: Any = 0.0,
        duty: Any = 0.5,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        rise: Any = 0.0,
        fall: Any = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Any = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Any = 0.15,
        jitter: Any = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Any = 0.0,
        ampMod: Any = 1.0,
        phaseMod: Any = 0.0,
        domain: Domain = Domain.ALL
    ): Fn = pulseTrain(
        asParam(period, "period"),
        asParam(phase, "phase"),
        asParam(duty, "duty"),
        asParam(amplitude, "amplitude"),
        asParam(offset, "offset"),
        asParam(rise, "rise"),
        asParam(fall, "fall"),
        edge,
        asParam(curve, "curve"),
        profile,
        asParam(profileSigma, "profileSigma"),
        asParam(jitter, "jitter"),
        seed,
        bipolar,
        asParam(dutyMod, "dutyMod"),
        asParam(ampMod, "ampMod"),
        asParam(phaseMod, "phaseMod"),
        domain
    )

    /**
     * The general pulse generator, taking each parameter as either a number or an [Fn] and throwing for
     * anything else: one shaped pulse per [period], unipolar from [offset] to `offset + amplitude`
     * unless [bipolar] is set. [period], [phase], [rise] and [fall] are in x units; [duty] is a fraction
     * clamped to 0..1. See the primary overload for the full conventions.
     */
    fun pulseTrain(
        period: Any,
        phase: Any = 0.0,
        duty: Any = 0.5,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        rise: Any = 0.0,
        fall: Any = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Any = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Any = 0.15,
        jitter: Any = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Any = 0.0,
        ampMod: Any = 1.0,
        phaseMod: Any = 0.0,
        domain: ClosedRange<*>
    ): Fn = pulseTrain(period, phase, duty, amplitude, offset, rise, fall, edge, curve, profile, profileSigma, jitter, seed, bipolar, dutyMod, ampMod, phaseMod, domain.toDomain())

    /**
     * [pulseTrain] specified by absolute pulse **width** rather than duty fraction — the form you want
     * when the pulse should keep its length while the period changes.
     *
     * [width] is in x units, and the duty is derived as `width / period` clamped to 0..1, so a width
     * larger than the period simply saturates at a fully-open gate. Everything else behaves exactly as
     * in [pulseTrain], and a [period] of zero still gives just [offset]. One graph caveat: the derived
     * duty child is a synthesised node whose own rebuild recipe is dropped, so a rewrite can replace it
     * but cannot keep rewriting inside it indefinitely.
     */
    fun pulseTrainWidth(
        period: Fn = param(1.0),
        width: Fn = param(0.5),
        phase: Fn = param(0.0),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        rise: Fn = param(0.0),
        fall: Fn = param(0.0),
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Fn = param(2.0),
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Fn = param(0.15),
        jitter: Fn = param(0.0),
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Fn = param(0.0),
        ampMod: Fn = param(1.0),
        phaseMod: Fn = param(0.0),
        domain: Domain = Domain.ALL
    ): Fn {
        val dd = intersectDomains(domain, period, width)
        val dutyFn = graphFn(
            dd,
            "duty",
            "dutyFromWidth",
            children = listOf(fnEdge("period", period), fnEdge("width", width)),
            rebuild = { children ->
                graphFn(
                    intersectDomains(domain, children[0], children[1]),
                    "duty",
                    "dutyFromWidth",
                    children = listOf(fnEdge("period", children[0]), fnEdge("width", children[1])),
                    tags = setOf("derived"),
                    rebuild = null
                ) { x ->
                    val p = kotlin.math.abs(children[0].eval(x))
                    if (p == 0.0) 0.0 else (children[1].eval(x) / p).coerceIn(0.0, 1.0)
                }
            },
            tags = setOf("derived")
        ) { x ->
            val p = kotlin.math.abs(period.eval(x))
            if (p == 0.0) 0.0 else (width.eval(x) / p).coerceIn(0.0, 1.0)
        }
        return pulseTrain(
            period = period,
            phase = phase,
            duty = dutyFn,
            amplitude = amplitude,
            offset = offset,
            rise = rise,
            fall = fall,
            edge = edge,
            curve = curve,
            profile = profile,
            profileSigma = profileSigma,
            jitter = jitter,
            seed = seed,
            bipolar = bipolar,
            dutyMod = dutyMod,
            ampMod = ampMod,
            phaseMod = phaseMod,
            domain = domain
        )
    }

    /**
     * [pulseTrain] specified by absolute pulse [width] in x units instead of a duty fraction; the duty is
     * derived as `width / period`, clamped to 0..1, so an over-wide pulse saturates rather than wrapping.
     * See [pulseTrain] for the remaining conventions.
     */
    fun pulseTrainWidth(
        period: Number,
        width: Number = 0.5,
        phase: Number = 0.0,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        rise: Number = 0.0,
        fall: Number = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Number = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Number = 0.15,
        jitter: Number = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Number = 0.0,
        ampMod: Number = 1.0,
        phaseMod: Number = 0.0,
        domain: Domain = Domain.ALL
    ): Fn = pulseTrainWidth(param(period), param(width), param(phase), param(amplitude), param(offset), param(rise), param(fall), edge, param(curve), profile, param(profileSigma), param(jitter), seed, bipolar, param(dutyMod), param(ampMod), param(phaseMod), domain)

    /**
     * [pulseTrain] specified by absolute pulse [width] in x units instead of a duty fraction; the duty is
     * derived as `width / period`, clamped to 0..1, so an over-wide pulse saturates rather than wrapping.
     * See [pulseTrain] for the remaining conventions.
     */
    fun pulseTrainWidth(
        period: Number,
        width: Number = 0.5,
        phase: Number = 0.0,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        rise: Number = 0.0,
        fall: Number = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Number = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Number = 0.15,
        jitter: Number = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Number = 0.0,
        ampMod: Number = 1.0,
        phaseMod: Number = 0.0,
        domain: ClosedRange<*>
    ): Fn = pulseTrainWidth(period, width, phase, amplitude, offset, rise, fall, edge, curve, profile, profileSigma, jitter, seed, bipolar, dutyMod, ampMod, phaseMod, domain.toDomain())

    /**
     * [pulseTrain] specified by absolute pulse [width] in x units instead of a duty fraction, taking each
     * parameter as either a number or an [Fn] and throwing for anything else. The duty is derived as
     * `width / period`, clamped to 0..1. See [pulseTrain] for the remaining conventions.
     */
    fun pulseTrainWidth(
        period: Any,
        width: Any = 0.5,
        phase: Any = 0.0,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        rise: Any = 0.0,
        fall: Any = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Any = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Any = 0.15,
        jitter: Any = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Any = 0.0,
        ampMod: Any = 1.0,
        phaseMod: Any = 0.0,
        domain: Domain = Domain.ALL
    ): Fn = pulseTrainWidth(
        asParam(period, "period"),
        asParam(width, "width"),
        asParam(phase, "phase"),
        asParam(amplitude, "amplitude"),
        asParam(offset, "offset"),
        asParam(rise, "rise"),
        asParam(fall, "fall"),
        edge,
        asParam(curve, "curve"),
        profile,
        asParam(profileSigma, "profileSigma"),
        asParam(jitter, "jitter"),
        seed,
        bipolar,
        asParam(dutyMod, "dutyMod"),
        asParam(ampMod, "ampMod"),
        asParam(phaseMod, "phaseMod"),
        domain
    )

    /**
     * [pulseTrain] specified by absolute pulse [width] in x units instead of a duty fraction, taking each
     * parameter as either a number or an [Fn] and throwing for anything else. The duty is derived as
     * `width / period`, clamped to 0..1. See [pulseTrain] for the remaining conventions.
     */
    fun pulseTrainWidth(
        period: Any,
        width: Any = 0.5,
        phase: Any = 0.0,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        rise: Any = 0.0,
        fall: Any = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Any = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Any = 0.15,
        jitter: Any = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Any = 0.0,
        ampMod: Any = 1.0,
        phaseMod: Any = 0.0,
        domain: ClosedRange<*>
    ): Fn = pulseTrainWidth(period, width, phase, amplitude, offset, rise, fall, edge, curve, profile, profileSigma, jitter, seed, bipolar, dutyMod, ampMod, phaseMod, domain.toDomain())

    /**
     * [pulseTrain] driven by **frequency** instead of period, for when repetition rate is the natural way
     * to think about it.
     *
     * The period is derived as `1 / frequency`, so [frequency] is in cycles per unit x — Hz only if x is
     * seconds. A frequency of zero yields a zero period and therefore just [offset], rather than an
     * infinity. Everything else, including the x-unit meaning of [rise] and [fall], is exactly
     * [pulseTrain].
     */
    fun pulseTrainHz(
        frequency: Fn = param(1.0),
        phase: Fn = param(0.0),
        duty: Fn = param(0.5),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        rise: Fn = param(0.0),
        fall: Fn = param(0.0),
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Fn = param(2.0),
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Fn = param(0.15),
        jitter: Fn = param(0.0),
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Fn = param(0.0),
        ampMod: Fn = param(1.0),
        phaseMod: Fn = param(0.0),
        domain: Domain = Domain.ALL
    ): Fn {
        val dd = intersectDomains(domain, frequency)
        val periodFn = graphFn(
            dd,
            "period",
            "periodFromFrequency",
            children = listOf(fnEdge("frequency", frequency)),
            tags = setOf("derived"),
            rebuild = { children ->
                graphFn(
                    intersectDomains(domain, children[0]),
                    "period",
                    "periodFromFrequency",
                    children = listOf(fnEdge("frequency", children[0])),
                    tags = setOf("derived"),
                    rebuild = null
                ) { x ->
                    val f = children[0].eval(x)
                    if (f == 0.0) 0.0 else 1.0 / f
                }
            }
        ) { x ->
            val f = frequency.eval(x)
            if (f == 0.0) 0.0 else 1.0 / f
        }
        return pulseTrain(
            period = periodFn,
            phase = phase,
            duty = duty,
            amplitude = amplitude,
            offset = offset,
            rise = rise,
            fall = fall,
            edge = edge,
            curve = curve,
            profile = profile,
            profileSigma = profileSigma,
            jitter = jitter,
            seed = seed,
            bipolar = bipolar,
            dutyMod = dutyMod,
            ampMod = ampMod,
            phaseMod = phaseMod,
            domain = domain
        )
    }

    /**
     * [pulseTrain] driven by [frequency] in cycles per unit x rather than by period; the period is derived
     * as `1 / frequency`, and a frequency of zero degenerates to just [offset]. See [pulseTrain] for the
     * remaining conventions.
     */
    fun pulseTrainHz(
        frequency: Number,
        phase: Number = 0.0,
        duty: Number = 0.5,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        rise: Number = 0.0,
        fall: Number = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Number = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Number = 0.15,
        jitter: Number = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Number = 0.0,
        ampMod: Number = 1.0,
        phaseMod: Number = 0.0,
        domain: Domain = Domain.ALL
    ): Fn = pulseTrainHz(param(frequency), param(phase), param(duty), param(amplitude), param(offset), param(rise), param(fall), edge, param(curve), profile, param(profileSigma), param(jitter), seed, bipolar, param(dutyMod), param(ampMod), param(phaseMod), domain)

    /**
     * [pulseTrain] driven by [frequency] in cycles per unit x rather than by period; the period is derived
     * as `1 / frequency`, and a frequency of zero degenerates to just [offset]. See [pulseTrain] for the
     * remaining conventions.
     */
    fun pulseTrainHz(
        frequency: Number,
        phase: Number = 0.0,
        duty: Number = 0.5,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        rise: Number = 0.0,
        fall: Number = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Number = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Number = 0.15,
        jitter: Number = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Number = 0.0,
        ampMod: Number = 1.0,
        phaseMod: Number = 0.0,
        domain: ClosedRange<*>
    ): Fn = pulseTrainHz(frequency, phase, duty, amplitude, offset, rise, fall, edge, curve, profile, profileSigma, jitter, seed, bipolar, dutyMod, ampMod, phaseMod, domain.toDomain())

    /**
     * [pulseTrain] driven by [frequency] in cycles per unit x rather than by period, taking each parameter
     * as either a number or an [Fn] and throwing for anything else. The period is derived as
     * `1 / frequency`, and a frequency of zero degenerates to just [offset].
     */
    fun pulseTrainHz(
        frequency: Any,
        phase: Any = 0.0,
        duty: Any = 0.5,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        rise: Any = 0.0,
        fall: Any = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Any = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Any = 0.15,
        jitter: Any = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Any = 0.0,
        ampMod: Any = 1.0,
        phaseMod: Any = 0.0,
        domain: Domain = Domain.ALL
    ): Fn = pulseTrainHz(
        asParam(frequency, "frequency"),
        asParam(phase, "phase"),
        asParam(duty, "duty"),
        asParam(amplitude, "amplitude"),
        asParam(offset, "offset"),
        asParam(rise, "rise"),
        asParam(fall, "fall"),
        edge,
        asParam(curve, "curve"),
        profile,
        asParam(profileSigma, "profileSigma"),
        asParam(jitter, "jitter"),
        seed,
        bipolar,
        asParam(dutyMod, "dutyMod"),
        asParam(ampMod, "ampMod"),
        asParam(phaseMod, "phaseMod"),
        domain
    )

    /**
     * [pulseTrain] driven by [frequency] in cycles per unit x rather than by period, taking each parameter
     * as either a number or an [Fn] and throwing for anything else. The period is derived as
     * `1 / frequency`, and a frequency of zero degenerates to just [offset].
     */
    fun pulseTrainHz(
        frequency: Any,
        phase: Any = 0.0,
        duty: Any = 0.5,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        rise: Any = 0.0,
        fall: Any = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Any = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Any = 0.15,
        jitter: Any = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Any = 0.0,
        ampMod: Any = 1.0,
        phaseMod: Any = 0.0,
        domain: ClosedRange<*>
    ): Fn = pulseTrainHz(frequency, phase, duty, amplitude, offset, rise, fall, edge, curve, profile, profileSigma, jitter, seed, bipolar, dutyMod, ampMod, phaseMod, domain.toDomain())

    /**
     * [pulseTrain] with a repeating per-cycle amplitude pattern — an accent rhythm rather than an even
     * train.
     *
     * Cycle *n* is scaled by `pattern[n mod pattern.size]`, so `doubleArrayOf(1.0, 0.0, 0.5, 0.0)` plays
     * loud, silent, half, silent and repeats. Cycle numbering follows x and wraps correctly for negative
     * x. An **empty pattern returns the constant zero** over the domain rather than an unpatterned train,
     * which is worth knowing if the pattern is computed. Everything else behaves as [pulseTrain].
     */
    fun pulseTrainPattern(
        pattern: DoubleArray,
        period: Fn = param(1.0),
        phase: Fn = param(0.0),
        duty: Fn = param(0.5),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        rise: Fn = param(0.0),
        fall: Fn = param(0.0),
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Fn = param(2.0),
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Fn = param(0.15),
        jitter: Fn = param(0.0),
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Fn = param(0.0),
        ampMod: Fn = param(1.0),
        phaseMod: Fn = param(0.0),
        domain: Domain = Domain.ALL
    ): Fn {
        if (pattern.isEmpty()) return zero(domain)
        val d = intersectDomains(domain, period, phase, duty, amplitude, offset, rise, fall, curve, profileSigma, jitter, dutyMod, ampMod, phaseMod)
        return graphFn(
            d,
            "pulsepattern",
            "pulsepattern",
            children = listOf(
                fnEdge("period", period),
                fnEdge("phase", phase),
                fnEdge("duty", duty),
                fnEdge("amplitude", amplitude),
                fnEdge("offset", offset),
                fnEdge("rise", rise),
                fnEdge("fall", fall),
                fnEdge("curve", curve),
                fnEdge("profileSigma", profileSigma),
                fnEdge("jitter", jitter),
                fnEdge("dutyMod", dutyMod),
                fnEdge("ampMod", ampMod),
                fnEdge("phaseMod", phaseMod)
            ),
            strings = mapOf("edge" to edge.name, "profile" to profile.name, "patternSize" to pattern.size.toString()),
            booleans = mapOf("bipolar" to bipolar),
            longs = mapOf("seed" to seed),
            rebuild = { children ->
                pulseTrainPattern(
                    pattern,
                    children[0],
                    children[1],
                    children[2],
                    children[3],
                    children[4],
                    children[5],
                    children[6],
                    edge,
                    children[7],
                    profile,
                    children[8],
                    children[9],
                    seed,
                    bipolar,
                    children[10],
                    children[11],
                    children[12],
                    domain
                )
            }
        ) { x ->
            val p = kotlin.math.abs(period.eval(x))
            if (p == 0.0) return@graphFn offset.eval(x)
            val phaseShift = phase.eval(x) + phaseMod.eval(x)
            val raw = (x - phaseShift) / p
            val cycle = floor(raw).toLong()
            var local = (x - phaseShift) - cycle * p
            val jit = jitter.eval(x)
            if (jit != 0.0) {
                val j = (hashToUnit(seed, cycle) - 0.5) * jit * p
                local -= j
            }
            local = ((local % p) + p) % p
            val t = local / p
            val dval = (duty.eval(x) + dutyMod.eval(x)).coerceIn(0.0, 1.0)
            val riseFrac = (rise.eval(x) / p).coerceIn(0.0, 1.0)
            val fallFrac = (fall.eval(x) / p).coerceIn(0.0, 1.0)
            val shaped = pulseShape(t, dval, riseFrac, fallFrac, edge, curve.eval(x), profile, profileSigma.eval(x))
            val size = pattern.size.toLong()
            val idx = ((cycle % size) + size) % size
            val amp = amplitude.eval(x) * pattern[idx.toInt()] * ampMod.eval(x)
            val off = offset.eval(x)
            if (bipolar) off + (shaped * 2.0 - 1.0) * amp else off + shaped * amp
        }
    }

    /**
     * [pulseTrain] with a repeating per-cycle amplitude pattern: cycle *n* is scaled by
     * `pattern[n mod pattern.size]`. An empty pattern returns the constant zero over the domain. See
     * [pulseTrain] for the remaining conventions.
     */
    fun pulseTrainPattern(
        pattern: DoubleArray,
        period: Number,
        phase: Number = 0.0,
        duty: Number = 0.5,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        rise: Number = 0.0,
        fall: Number = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Number = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Number = 0.15,
        jitter: Number = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Number = 0.0,
        ampMod: Number = 1.0,
        phaseMod: Number = 0.0,
        domain: Domain = Domain.ALL
    ): Fn = pulseTrainPattern(pattern, param(period), param(phase), param(duty), param(amplitude), param(offset), param(rise), param(fall), edge, param(curve), profile, param(profileSigma), param(jitter), seed, bipolar, param(dutyMod), param(ampMod), param(phaseMod), domain)

    /**
     * [pulseTrain] with a repeating per-cycle amplitude pattern: cycle *n* is scaled by
     * `pattern[n mod pattern.size]`. An empty pattern returns the constant zero over the domain. See
     * [pulseTrain] for the remaining conventions.
     */
    fun pulseTrainPattern(
        pattern: DoubleArray,
        period: Number,
        phase: Number = 0.0,
        duty: Number = 0.5,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        rise: Number = 0.0,
        fall: Number = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Number = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Number = 0.15,
        jitter: Number = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Number = 0.0,
        ampMod: Number = 1.0,
        phaseMod: Number = 0.0,
        domain: ClosedRange<*>
    ): Fn = pulseTrainPattern(pattern, period, phase, duty, amplitude, offset, rise, fall, edge, curve, profile, profileSigma, jitter, seed, bipolar, dutyMod, ampMod, phaseMod, domain.toDomain())

    /**
     * [pulseTrain] with a repeating per-cycle amplitude pattern, taking each parameter as either a number
     * or an [Fn] and throwing for anything else: cycle *n* is scaled by `pattern[n mod pattern.size]`, and
     * an empty pattern returns the constant zero over the domain.
     */
    fun pulseTrainPattern(
        pattern: DoubleArray,
        period: Any,
        phase: Any = 0.0,
        duty: Any = 0.5,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        rise: Any = 0.0,
        fall: Any = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Any = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Any = 0.15,
        jitter: Any = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Any = 0.0,
        ampMod: Any = 1.0,
        phaseMod: Any = 0.0,
        domain: Domain = Domain.ALL
    ): Fn = pulseTrainPattern(
        pattern,
        asParam(period, "period"),
        asParam(phase, "phase"),
        asParam(duty, "duty"),
        asParam(amplitude, "amplitude"),
        asParam(offset, "offset"),
        asParam(rise, "rise"),
        asParam(fall, "fall"),
        edge,
        asParam(curve, "curve"),
        profile,
        asParam(profileSigma, "profileSigma"),
        asParam(jitter, "jitter"),
        seed,
        bipolar,
        asParam(dutyMod, "dutyMod"),
        asParam(ampMod, "ampMod"),
        asParam(phaseMod, "phaseMod"),
        domain
    )

    /**
     * [pulseTrain] with a repeating per-cycle amplitude pattern, taking each parameter as either a number
     * or an [Fn] and throwing for anything else: cycle *n* is scaled by `pattern[n mod pattern.size]`, and
     * an empty pattern returns the constant zero over the domain.
     */
    fun pulseTrainPattern(
        pattern: DoubleArray,
        period: Any,
        phase: Any = 0.0,
        duty: Any = 0.5,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        rise: Any = 0.0,
        fall: Any = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Any = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Any = 0.15,
        jitter: Any = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Any = 0.0,
        ampMod: Any = 1.0,
        phaseMod: Any = 0.0,
        domain: ClosedRange<*>
    ): Fn = pulseTrainPattern(pattern, period, phase, duty, amplitude, offset, rise, fall, edge, curve, profile, profileSigma, jitter, seed, bipolar, dutyMod, ampMod, phaseMod, domain.toDomain())

    /**
     * A train of narrow Gaussian bumps — [pulseTrainWidth] pre-set to a `GAUSSIAN` profile with smooth
     * edges, which is as close as a continuously-sampleable function gets to a Dirac comb.
     *
     * One bump every [period], each [width] wide in x units — the default 0.02 is narrow.
     * [sigma] is the bump's relative width *within* that window, and because a Gaussian never actually
     * reaches zero the bumps always have faint tails. [jitter] displaces each bump by a fraction of a
     * period, deterministically from [seed]. Unipolar: the output runs from [offset] to
     * `offset + amplitude`.
     */
    fun impulseTrain(
        period: Fn = param(1.0),
        phase: Fn = param(0.0),
        width: Fn = param(0.02),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        sigma: Fn = param(0.15),
        jitter: Fn = param(0.0),
        seed: Long = 0L,
        domain: Domain = Domain.ALL
    ): Fn = pulseTrainWidth(
        period = period,
        width = width,
        phase = phase,
        amplitude = amplitude,
        offset = offset,
        edge = PulseEdge.SMOOTH,
        curve = param(2.0),
        profile = PulseProfile.GAUSSIAN,
        profileSigma = sigma,
        jitter = jitter,
        seed = seed,
        domain = domain
    )

    /**
     * A train of narrow Gaussian bumps, one [width] wide (in x units) every [period] — [pulseTrainWidth]
     * pre-set to a `GAUSSIAN` profile. Unipolar, from [offset] to `offset + amplitude`; the bumps never
     * quite reach zero between them.
     */
    fun impulseTrain(
        period: Number,
        phase: Number = 0.0,
        width: Number = 0.02,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        sigma: Number = 0.15,
        jitter: Number = 0.0,
        seed: Long = 0L,
        domain: Domain = Domain.ALL
    ): Fn = impulseTrain(param(period), param(phase), param(width), param(amplitude), param(offset), param(sigma), param(jitter), seed, domain)

    /**
     * A train of narrow Gaussian bumps, one [width] wide (in x units) every [period] — [pulseTrainWidth]
     * pre-set to a `GAUSSIAN` profile. Unipolar, from [offset] to `offset + amplitude`; the bumps never
     * quite reach zero between them.
     */
    fun impulseTrain(
        period: Number,
        phase: Number = 0.0,
        width: Number = 0.02,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        sigma: Number = 0.15,
        jitter: Number = 0.0,
        seed: Long = 0L,
        domain: ClosedRange<*>
    ): Fn = impulseTrain(period, phase, width, amplitude, offset, sigma, jitter, seed, domain.toDomain())

    /**
     * A train of narrow Gaussian bumps, one [width] wide (in x units) every [period], taking each
     * parameter as either a number or an [Fn] and throwing for anything else. Unipolar, from [offset] to
     * `offset + amplitude`.
     */
    fun impulseTrain(
        period: Any,
        phase: Any = 0.0,
        width: Any = 0.02,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        sigma: Any = 0.15,
        jitter: Any = 0.0,
        seed: Long = 0L,
        domain: Domain = Domain.ALL
    ): Fn = impulseTrain(
        asParam(period, "period"),
        asParam(phase, "phase"),
        asParam(width, "width"),
        asParam(amplitude, "amplitude"),
        asParam(offset, "offset"),
        asParam(sigma, "sigma"),
        asParam(jitter, "jitter"),
        seed,
        domain
    )

    /**
     * A train of narrow Gaussian bumps, one [width] wide (in x units) every [period], taking each
     * parameter as either a number or an [Fn] and throwing for anything else. Unipolar, from [offset] to
     * `offset + amplitude`.
     */
    fun impulseTrain(
        period: Any,
        phase: Any = 0.0,
        width: Any = 0.02,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        sigma: Any = 0.15,
        jitter: Any = 0.0,
        seed: Long = 0L,
        domain: ClosedRange<*>
    ): Fn = impulseTrain(period, phase, width, amplitude, offset, sigma, jitter, seed, domain.toDomain())

    /**
     * A **finite** burst of [count] pulses beginning at [start] — a [pulseTrain] gated to a window instead
     * of running forever.
     *
     * The window is `start` to `start + period * count`, inclusive at both ends, and the pulses inside it
     * are phase-locked to [start] (the [phase] argument is added on top of that). Outside the window the
     * value is [offset], which is only zero if you left [offset] at its default. A [count] of zero or less
     * gives the constant zero over the domain, and a [period] of zero gives just [offset].
     */
    fun pulseBurst(
        count: Int,
        period: Fn = param(1.0),
        start: Fn = param(0.0),
        phase: Fn = param(0.0),
        duty: Fn = param(0.5),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        rise: Fn = param(0.0),
        fall: Fn = param(0.0),
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Fn = param(2.0),
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Fn = param(0.15),
        domain: Domain = Domain.ALL
    ): Fn {
        if (count <= 0) return zero(domain)
        val d = intersectDomains(domain, period, start, phase, duty, amplitude, offset, rise, fall, curve, profileSigma)
        val base = pulseTrain(
            period = period,
            phase = start + phase,
            duty = duty,
            amplitude = amplitude,
            offset = offset,
            rise = rise,
            fall = fall,
            edge = edge,
            curve = curve,
            profile = profile,
            profileSigma = profileSigma,
            domain = domain
        )
        return graphFn(
            d,
            "pulseburst",
            "pulseburst",
            children = listOf(
                fnEdge("period", period),
                fnEdge("start", start),
                fnEdge("phase", phase),
                fnEdge("duty", duty),
                fnEdge("amplitude", amplitude),
                fnEdge("offset", offset),
                fnEdge("rise", rise),
                fnEdge("fall", fall),
                fnEdge("curve", curve),
                fnEdge("profileSigma", profileSigma),
                fnEdge("base", base)
            ),
            strings = mapOf("edge" to edge.name, "profile" to profile.name),
            longs = mapOf("count" to count.toLong()),
            rebuild = { children ->
                pulseBurst(count, children[0], children[1], children[2], children[3], children[4], children[5], children[6], children[7], edge, children[8], profile, children[9], domain)
            }
        ) { x ->
            val p = kotlin.math.abs(period.eval(x))
            if (p == 0.0) return@graphFn offset.eval(x)
            val s = start.eval(x)
            val end = s + p * count
            if (x < s || x > end) offset.eval(x) else base.eval(x)
        }
    }

    /**
     * A finite burst of [count] pulses beginning at [start], with [offset] everywhere outside the window
     * `start .. start + period * count`. A [count] of zero or less gives the constant zero over the domain.
     * See [pulseTrain] for the shaping conventions.
     */
    fun pulseBurst(
        count: Int,
        period: Number,
        start: Number = 0.0,
        phase: Number = 0.0,
        duty: Number = 0.5,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        rise: Number = 0.0,
        fall: Number = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Number = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Number = 0.15,
        domain: Domain = Domain.ALL
    ): Fn = pulseBurst(count, param(period), param(start), param(phase), param(duty), param(amplitude), param(offset), param(rise), param(fall), edge, param(curve), profile, param(profileSigma), domain)

    /**
     * A finite burst of [count] pulses beginning at [start], with [offset] everywhere outside the window
     * `start .. start + period * count`. A [count] of zero or less gives the constant zero over the domain.
     * See [pulseTrain] for the shaping conventions.
     */
    fun pulseBurst(
        count: Int,
        period: Number,
        start: Number = 0.0,
        phase: Number = 0.0,
        duty: Number = 0.5,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        rise: Number = 0.0,
        fall: Number = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Number = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Number = 0.15,
        domain: ClosedRange<*>
    ): Fn = pulseBurst(count, period, start, phase, duty, amplitude, offset, rise, fall, edge, curve, profile, profileSigma, domain.toDomain())

    /**
     * A finite burst of [count] pulses beginning at [start], taking each parameter as either a number or an
     * [Fn] and throwing for anything else. [offset] applies everywhere outside the window
     * `start .. start + period * count`; a [count] of zero or less gives the constant zero.
     */
    fun pulseBurst(
        count: Int,
        period: Any,
        start: Any = 0.0,
        phase: Any = 0.0,
        duty: Any = 0.5,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        rise: Any = 0.0,
        fall: Any = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Any = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Any = 0.15,
        domain: Domain = Domain.ALL
    ): Fn = pulseBurst(
        count,
        asParam(period, "period"),
        asParam(start, "start"),
        asParam(phase, "phase"),
        asParam(duty, "duty"),
        asParam(amplitude, "amplitude"),
        asParam(offset, "offset"),
        asParam(rise, "rise"),
        asParam(fall, "fall"),
        edge,
        asParam(curve, "curve"),
        profile,
        asParam(profileSigma, "profileSigma"),
        domain
    )

    /**
     * A finite burst of [count] pulses beginning at [start], taking each parameter as either a number or an
     * [Fn] and throwing for anything else. [offset] applies everywhere outside the window
     * `start .. start + period * count`; a [count] of zero or less gives the constant zero.
     */
    fun pulseBurst(
        count: Int,
        period: Any,
        start: Any = 0.0,
        phase: Any = 0.0,
        duty: Any = 0.5,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        rise: Any = 0.0,
        fall: Any = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Any = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Any = 0.15,
        domain: ClosedRange<*>
    ): Fn = pulseBurst(count, period, start, phase, duty, amplitude, offset, rise, fall, edge, curve, profile, profileSigma, domain.toDomain())

    /**
     * A Gaussian bump: `amplitude · exp(-½ ((x - mean) / sigma)²)` — peak [amplitude] at [mean], down to
     * about 0.61 of the peak one [sigma] away, and never quite zero anywhere.
     *
     * The obvious envelope for "one event, softly", and the shape behind [gaussianWindow] and the
     * `GAUSSIAN` pulse profile. Two conventions to note. A [sigma] of zero yields the constant **zero**
     * rather than an infinite spike. And the domain here defaults to `0.0..1.0`, not [Domain.ALL], so with
     * the default `mean = 0.0` you see only the bump's right-hand half — centre it (`mean = 0.5`) or widen
     * the domain.
     */
    fun gaussian(
        mean: Fn = param(0.0),
        sigma: Fn = param(1.0),
        amplitude: Fn = param(1.0),
        domain: Domain = Domain.DEFAULT
    ): Fn {
        val d = intersectDomains(domain, mean, sigma, amplitude)
        return graphFn(
            d,
            "gaussian",
            "gaussian",
            children = listOf(fnEdge("mean", mean), fnEdge("sigma", sigma), fnEdge("amplitude", amplitude)),
            rebuild = { children -> gaussian(children[0], children[1], children[2], domain) }
        ) { x ->
            val s = sigma.eval(x)
            if (s == 0.0) 0.0 else {
                val m = mean.eval(x)
                val a = amplitude.eval(x)
                a * kotlin.math.exp(-0.5 * ((x - m) / s).pow(2.0))
            }
        }
    }

    /**
     * A Gaussian bump: `amplitude · exp(-½ ((x - mean) / sigma)²)`, peaking at [mean] and never quite
     * reaching zero. A [sigma] of zero gives the constant zero, and the domain defaults to `0.0..1.0`
     * rather than the whole line.
     */
    fun gaussian(
        mean: Number,
        sigma: Number = 1.0,
        amplitude: Number = 1.0,
        domain: Domain = Domain.DEFAULT
    ): Fn = gaussian(param(mean), param(sigma), param(amplitude), domain)

    /**
     * A Gaussian bump: `amplitude · exp(-½ ((x - mean) / sigma)²)`, peaking at [mean] and never quite
     * reaching zero. A [sigma] of zero gives the constant zero, and the domain defaults to `0.0..1.0`
     * rather than the whole line.
     */
    fun gaussian(
        mean: Number,
        sigma: Number = 1.0,
        amplitude: Number = 1.0,
        domain: ClosedRange<*>
    ): Fn = gaussian(mean, sigma, amplitude, domain.toDomain())

    /**
     * A Gaussian bump peaking at [mean], taking each parameter as either a number or an [Fn] and throwing
     * for anything else. A [sigma] of zero gives the constant zero; the bump never quite reaches zero
     * elsewhere.
     */
    fun gaussian(
        mean: Any,
        sigma: Any = 1.0,
        amplitude: Any = 1.0,
        domain: Domain = Domain.DEFAULT
    ): Fn = gaussian(asParam(mean, "mean"), asParam(sigma, "sigma"), asParam(amplitude, "amplitude"), domain)

    /**
     * A Gaussian bump peaking at [mean], taking each parameter as either a number or an [Fn] and throwing
     * for anything else. A [sigma] of zero gives the constant zero; the bump never quite reaches zero
     * elsewhere.
     */
    fun gaussian(
        mean: Any,
        sigma: Any = 1.0,
        amplitude: Any = 1.0,
        domain: ClosedRange<*>
    ): Fn = gaussian(mean, sigma, amplitude, domain.toDomain())

    // Array helpers
    /**
     * Turns a table of samples into a continuous [Fn], reading between the entries with the chosen
     * [interpolation].
     *
     * The samples are laid out **evenly** across [domain], which defaults to `0.0 .. size - 1` — one unit
     * of x per array entry, so the domain is not `0..1` unless you ask for it. Both endpoints are hit
     * exactly. Outside the domain the result is [outside] flat, with no clamping or extrapolation and no
     * regard for [EdgeMode]; an empty array is [outside] everywhere. `CUBIC` interpolation duplicates the
     * end samples where it has no neighbour to work with, so the curve flattens slightly at the edges.
     */
    fun DoubleArray.toFn(
        domain: Domain = Domain.of(0.0, (size - 1).toDouble()),
        interpolation: Interpolation = Interpolation.LINEAR,
        outside: Double = 0.0
    ): Fn {
        val samples = this
        val count = samples.size
        val dx = if (count > 1) domain.length / (count - 1).toDouble() else 1.0
        val safeDomain = if (domain._isEmpty) Domain.EMPTY else domain
        return graphFn(
            safeDomain,
            "array",
            "array",
            numbers = mapOf("outside" to outside),
            strings = mapOf("interpolation" to interpolation.name, "samples" to count.toString()),
            rebuild = { samples.toFn(domain, interpolation, outside) }
        ) { x ->
            if (count == 0) return@graphFn outside
            if (!safeDomain.containsInclusive(x)) return@graphFn outside
            val idx = if (dx == 0.0) 0.0 else (x - safeDomain.min) / dx
            sampleArray(samples, idx, interpolation, outside)
        }
    }

    /** A table of samples as a continuous [Fn], spread evenly across the given range and reading [outside] beyond it. See the [Domain] overload of `toFn`. */
    fun DoubleArray.toFn(
        domain: ClosedRange<*>,
        interpolation: Interpolation = Interpolation.LINEAR,
        outside: Double = 0.0
    ): Fn = toFn(domain.toDomain(), interpolation, outside)

    /**
     * A list of numbers as a continuous [Fn], spread evenly across [domain] (by default `0.0 .. size - 1`)
     * and reading [outside] beyond it. Copies the list into a `DoubleArray` first, so later mutation of the
     * list does not affect the function.
     */
    fun List<Number>.toFn(
        domain: Domain = Domain.of(0.0, (size - 1).toDouble()),
        interpolation: Interpolation = Interpolation.LINEAR,
        outside: Double = 0.0
    ): Fn = this.map { it.toDouble() }.toDoubleArray().toFn(domain, interpolation, outside)

    /**
     * A list of numbers as a continuous [Fn], spread evenly across the given range and reading [outside]
     * beyond it. Copies the list first, so later mutation of the list does not affect the function.
     */
    fun List<Number>.toFn(
        domain: ClosedRange<*>,
        interpolation: Interpolation = Interpolation.LINEAR,
        outside: Double = 0.0
    ): Fn = toFn(domain.toDomain(), interpolation, outside)

    private fun sampleArray(samples: DoubleArray, index: Double, interpolation: Interpolation, outside: Double): Double {
        val n = samples.size
        if (n == 0) return outside
        return when (interpolation) {
            Interpolation.NEAREST -> {
                val i = index.roundToInt()
                if (i < 0 || i >= n) outside else samples[i]
            }
            Interpolation.LINEAR -> {
                val i = floor(index).toInt()
                val t = index - i
                val a = samples.getOrNull(i) ?: return outside
                val b = samples.getOrNull(i + 1) ?: return a
                blend(a, b, t)
            }
            Interpolation.CUBIC -> {
                val i = floor(index).toInt()
                val t = index - i
                val p0 = samples.getOrNull(i - 1) ?: samples.getOrNull(i) ?: return outside
                val p1 = samples.getOrNull(i) ?: return outside
                val p2 = samples.getOrNull(i + 1) ?: p1
                val p3 = samples.getOrNull(i + 2) ?: p2
                catmullRom(p0, p1, p2, p3, t)
            }
        }
    }

    private fun catmullRom(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5 * (
            2.0 * p1 +
                (-p0 + p2) * t +
                (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 +
                (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3
            )
    }

    /**
     * Evaluates this function at an explicit list of coordinates, returning one value per entry in the
     * same order.
     *
     * Domain-respecting: each sample goes through `evalSafe`, so [edge] and [outside] decide what happens
     * for an x outside the domain. Use this when the sample positions are irregular — for an even grid,
     * [toDoubleArray] is more direct.
     */
    fun Fn.sampleAt(xs: DoubleArray, edge: EdgeMode = EdgeMode.ZERO, outside: Double = 0.0): DoubleArray =
        DoubleArray(xs.size) { i -> evalSafe(xs[i], outside, edge) }

    /**
     * Samples this function at [count] evenly-spaced points over [sampleDomain] — the workhorse for
     * getting numbers out.
     *
     * With `includeEnd = true` (the default) the samples span the domain **inclusively**, so the first is
     * at `min`, the last exactly at `max`, and the spacing is `length / (count - 1)`. With `includeEnd =
     * false` the spacing is `length / count` and the last sample stops one step short of `max`, which is
     * what you want for anything that will be tiled or wrapped.
     *
     * Sampling respects the domain via [edge] and [outside]. A [count] of zero or less gives an empty
     * array; an empty domain gives [count] copies of [outside]; and an unbounded domain is previewed over a
     * small finite window rather than failing.
     */
    fun Fn.toDoubleArray(
        count: Int,
        sampleDomain: Domain = domain,
        includeEnd: Boolean = true,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0
    ): DoubleArray {
        if (count <= 0) return DoubleArray(0)
        if (sampleDomain._isEmpty) return DoubleArray(count) { outside }
        val sweep = sampleDomain.finiteSweep()
        val len = sweep.length
        val steps = if (includeEnd) max(1, count - 1) else count
        val step = if (steps == 0) 0.0 else len / steps.toDouble()
        return DoubleArray(count) { i ->
            val x = sweep.min + step * i.toDouble()
            evalSafe(x, outside, edge)
        }
    }

    /** Samples this function at [count] evenly-spaced points over the given range; `includeEnd` decides whether the last sample lands exactly on the upper bound. */
    fun Fn.toDoubleArray(
        count: Int,
        sampleDomain: ClosedRange<*>,
        includeEnd: Boolean = true,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0
    ): DoubleArray = toDoubleArray(count, sampleDomain.toDomain(), includeEnd, edge, outside)

    /**
     * Samples this function every [step] units across [sampleDomain], letting the domain's width decide how
     * many samples that is.
     *
     * The spacing really is [step]: a step that does not divide the width leaves the last sample short of
     * `max` rather than stretching the spacing to reach it, so the resolution you asked for is the
     * resolution you get. The sign of [step] is ignored — sampling always runs upward from `min`. A step
     * of zero gives an empty array, and note the asymmetry with the count-based overload: here an
     * **empty domain also gives an empty array**, rather than a run of [outside] values. `includeEnd`
     * adds the sample landing on `max` when the width divides evenly.
     */
    fun Fn.toDoubleArray(
        step: Double,
        sampleDomain: Domain = domain,
        includeEnd: Boolean = true,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0
    ): DoubleArray {
        if (step == 0.0) return DoubleArray(0)
        if (sampleDomain._isEmpty) return DoubleArray(0)
        val sweep = sampleDomain.finiteSweep()
        val len = sweep.length
        val s = kotlin.math.abs(step)
        // The count comes from the step, and then so do the *positions*. Handing the count to the
        // count-based overload instead — which spaces its samples `len / (count - 1)` apart to land on
        // `max` — silently re-scaled the step whenever it did not divide the width evenly: a step of 0.3
        // across 0..1 sampled at 0, 1/3, 2/3, 1 rather than 0, 0.3, 0.6, 0.9.
        val whole = floor(len / s + STEP_COUNT_EPSILON)
        val count = (if (includeEnd) whole + 1.0 else whole).toInt()
        if (count <= 0) return DoubleArray(0)
        return DoubleArray(count) { i ->
            // `min + s * i` can overshoot `max` by an ulp on the last sample of an evenly-dividing step,
            // which under the default EdgeMode.ZERO would answer `outside` instead of the endpoint value.
            evalSafe((sweep.min + s * i.toDouble()).coerceAtMost(sweep.max), outside, edge)
        }
    }

    /**
     * Relative slack when turning a step into a sample count, so that a width which divides evenly in
     * exact arithmetic (`1.0 / 0.1` is `10.000000000000002` in binary floating point) is not one sample
     * short of the endpoint.
     */
    private const val STEP_COUNT_EPSILON = 1e-9

    /** Samples this function every [step] units across the given range; the step's sign is ignored and a step of zero gives an empty array. */
    fun Fn.toDoubleArray(
        step: Double,
        sampleDomain: ClosedRange<*>,
        includeEnd: Boolean = true,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0
    ): DoubleArray = toDoubleArray(step, sampleDomain.toDomain(), includeEnd, edge, outside)

    /**
     * Sample this function at [count] uniformly-spaced points and return the
     * results as `List<Vec2>` where each entry is `Vec2(time, value)`.
     *
     * Handy for feeding into [KeyframeEvaluator.setOverlay] or any API that
     * expects a list of 2D points.
     */
    fun Fn.toVec2List(
        count: Int,
        sampleDomain: Domain = domain,
        includeEnd: Boolean = true,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0
    ): List<Vec2> {
        if (count <= 0) return emptyList()
        if (sampleDomain._isEmpty) return List(count) { Vec2(0f, outside.toFloat()) }
        val sweep = sampleDomain.finiteSweep()
        val len = sweep.length
        val steps = if (includeEnd) max(1, count - 1) else count
        val step = if (steps == 0) 0.0 else len / steps.toDouble()
        return List(count) { i ->
            val x = sweep.min + step * i.toDouble()
            Vec2(x.toFloat(), evalSafe(x, outside, edge).toFloat())
        }
    }

    /**
     * Samples this function at [count] evenly-spaced points over the given range as `Vec2(x, value)` pairs.
     * Note that the coordinates are narrowed to `Float` on the way out, so this is for feeding drawing and
     * overlay APIs rather than for numerics.
     */
    fun Fn.toVec2List(
        count: Int,
        sampleDomain: ClosedRange<*>,
        includeEnd: Boolean = true,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0
    ): List<Vec2> = toVec2List(count, sampleDomain.toDomain(), includeEnd, edge, outside)

    private data class PlotSample(val x: Double, val y: Double?)

    private fun Domain.plotSafe(default: Domain = Domain.DEFAULT): Domain {
        if (!isFinite) return default
        return when {
            length > 0.0 -> this
            min.isFinite() -> Domain.of(min - 0.5, min + 0.5)
            else -> default
        }
    }

    /**
     * Resolves an implicit sample domain to a finite window for enumeration.
     *
     * Oscillators and other unbounded functions default to [Domain.ALL], which
     * has no finite extent to walk at a fixed count or step. When a sampler is
     * handed such a domain (typically because the caller passed no explicit
     * window) it previews over [plotSafe]'s finite fallback. Empty and finite
     * domains — including point domains — pass through unchanged, so existing
     * behavior for bounded functions is preserved exactly.
     */
    private fun Domain.finiteSweep(): Domain = when {
        _isEmpty -> this
        isFinite -> this
        else -> plotSafe()
    }

    private fun yPlotDomain(samples: List<PlotSample>, requested: Domain?): Domain {
        if (requested != null && requested.isFinite) return requested.plotSafe()
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (sample in samples) {
            val y = sample.y ?: continue
            if (!y.isFinite()) continue
            if (y < lo) lo = y
            if (y > hi) hi = y
        }
        if (!lo.isFinite() || !hi.isFinite()) return Domain.of(-1.0, 1.0)
        if (lo == hi) {
            val pad = max(1.0, abs(lo) * 0.1)
            return Domain.of(lo - pad, hi + pad)
        }
        val pad = (hi - lo) * 0.08
        return Domain.of(lo - pad, hi + pad)
    }

    /**
     * Renders an interactive plot for an [Fn].
     *
     * The plot samples the whole finite [domain] by default. Infinite functions
     * need a finite [sampleDomain], or they fall back to a small 0..1 preview.
     * The samples travel inside the returned HTML, so the plot needs no server
     * route: it draws the same after a Field restart, in a scratch output or in a
     * docs capture. It is drawn by Field's one time-axis viewer (Plotly), the
     * same component as [afield.stdlib.SignalViewer] and the audio player; wheel
     * zooms x at the pointer, drag pans, alt-wheel zooms y, and ↕ fits y to the
     * visible window. `Box.plot` and `Fn.plot` additionally resample the function
     * live as you zoom in; this bare HTML zooms into its embedded samples only.
     */
    fun Fn.plotHtml(
        sampleDomain: Domain? = null,
        samples: Int = 1024,
        heightPx: Int = 360,
        title: String = name ?: "Fn",
        xLabel: String = "x",
        yLabel: String = "f(x)",
        yDomain: Domain? = null,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = Double.NaN,
        background: String = "#000000",
        primaryColor: String = "#0af",
        secondaryColor: String = "#f80",
        fontFamily: String = "\"Iosevka Web\", \"IBM Plex Mono\", monospace"
    ): String = plotViewerHtml(sampleDomain, samples, heightPx, title, xLabel, yLabel, yDomain, edge, outside,
        background, primaryColor, secondaryColor, fontFamily, live = null)

    /** The evaluator a plot samples: non-finite values and throws become gaps (NaN). */
    private fun Fn.plotEvaluator(edge: EdgeMode, outside: Double): (Double) -> Double = { x ->
        runCatching { evalSafe(x, outside = outside, edge = edge) }.getOrElse { Double.NaN }
    }

    private fun Fn.plotViewerHtml(
        sampleDomain: Domain?, samples: Int, heightPx: Int, title: String, xLabel: String, yLabel: String,
        yDomain: Domain?, edge: EdgeMode, outside: Double, background: String, primaryColor: String,
        secondaryColor: String, fontFamily: String,
        // Keeps a range-query source for this plot; null for self-contained HTML.
        live: ((SignalViewer) -> Unit)?,
    ): String {
        val plotDomain = (sampleDomain ?: domain).plotSafe()
        val count = samples.coerceIn(2, 32768)
        val step = plotDomain.length / (count - 1).toDouble()
        val evaluate = plotEvaluator(edge, outside)
        val plotSamples = List(count) { i ->
            val x = plotDomain.min + step * i.toDouble()
            PlotSample(x, evaluate(x).takeIf { it.isFinite() })
        }
        val valueDomain = yPlotDomain(plotSamples, yDomain)
        val finiteCount = plotSamples.count { it.y != null }
        val safeTitle = title.ifBlank { name ?: "Fn" }
        val values = DoubleArray(count) { plotSamples[it].y ?: Double.NaN }
        val url = live?.let { retain ->
            val viewer = SignalViewer(
                listOf(SignalChannel(safeTitle, evaluate, valueDomain.min, valueDomain.max)),
                plotDomain.min, plotDomain.max, sampleStep = step, gaps = true,
            )
            retain(viewer)
            "/signal-viewer/${viewer.id}"
        }
        return TimeViewerShell.render(
            config = mapOf(
                "source" to "embedded", "url" to url,
                "window" to TimeViewerShell.embeddedWindow(plotDomain.min, plotDomain.max,
                    listOf(safeTitle to values), valueDomain.min, valueDomain.max),
                "start" to plotDomain.min, "end" to plotDomain.max,
                "label" to safeTitle, "xLabel" to xLabel, "xUnit" to "", "xName" to xLabel, "yName" to yLabel,
                "readoutIdle" to "$xLabel –   $yLabel –",
                "samplesChip" to "$finiteCount/$count samples",
                "plotHeight" to heightPx.coerceAtLeast(220), "zeroLines" to true,
                "margin" to mapOf("l" to 66, "r" to 18, "t" to 14),
                "colors" to mapOf("primary" to primaryColor, "secondary" to secondaryColor),
                "lanes" to listOf(mapOf("name" to safeTitle, "yZoom" to true, "yTitle" to yLabel,
                    "color" to primaryColor, "width" to 2.0)),
            ),
            widthPx = null,
            accent = primaryColor,
            background = background,
            fontFamily = fontFamily,
        )
    }

    /**
     * Renders an interactive plot of this function as a self-contained HTML iframe, sampling over the given
     * range. Non-finite samples — including anything that throws while being evaluated, which is caught and
     * turned into a gap — are drawn as breaks in the curve rather than spikes.
     */
    fun Fn.plotHtml(
        sampleDomain: ClosedRange<*>,
        samples: Int = 1024,
        heightPx: Int = 360,
        title: String = name ?: "Fn",
        xLabel: String = "x",
        yLabel: String = "f(x)",
        yDomain: Domain? = null,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = Double.NaN,
        background: String = "#000000",
        primaryColor: String = "#0af",
        secondaryColor: String = "#f80",
        fontFamily: String = "\"Iosevka Web\", \"IBM Plex Mono\", monospace"
    ): String =
        plotHtml(
            sampleDomain = sampleDomain.toDomain() as Domain?,
            samples = samples,
            heightPx = heightPx,
            title = title,
            xLabel = xLabel,
            yLabel = yLabel,
            yDomain = yDomain,
            edge = edge,
            outside = outside,
            background = background,
            primaryColor = primaryColor,
            secondaryColor = secondaryColor,
            fontFamily = fontFamily
        )

    /**
     * Renders an interactive plot of this function as a self-contained HTML iframe over the given x range,
     * with an explicit y range instead of one fitted to the data. Non-finite samples are drawn as gaps.
     */
    fun Fn.plotHtml(
        sampleDomain: ClosedRange<*>,
        samples: Int = 1024,
        heightPx: Int = 360,
        title: String = name ?: "Fn",
        xLabel: String = "x",
        yLabel: String = "f(x)",
        yDomain: ClosedRange<*>,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = Double.NaN,
        background: String = "#000000",
        primaryColor: String = "#0af",
        secondaryColor: String = "#f80",
        fontFamily: String = "\"Iosevka Web\", \"IBM Plex Mono\", monospace"
    ): String =
        plotHtml(
            sampleDomain = sampleDomain.toDomain() as Domain?,
            samples = samples,
            heightPx = heightPx,
            title = title,
            xLabel = xLabel,
            yLabel = yLabel,
            yDomain = yDomain.toDomain() as Domain?,
            edge = edge,
            outside = outside,
            background = background,
            primaryColor = primaryColor,
            secondaryColor = secondaryColor,
            fontFamily = fontFamily
        )

    /**
     * Draws this function into the **currently executing box**, on the given output [channel], and returns
     * the function unchanged so it can sit mid-chain.
     *
     * Convenient inside a box and useless outside one: with no active box context this throws
     * [IllegalStateException] rather than doing nothing. Reach for `Box.plot(fn)` when you have the
     * box in hand instead of relying on the ambient one. Re-plotting on the same channel replaces the
     * previous output.
     */
    @JvmName("plotCurrentBox")
    fun Fn.plot(
        channel: String = "plot",
        sampleDomain: Domain? = null,
        samples: Int = 1024,
        heightPx: Int = 360,
        title: String = name ?: "Fn",
        xLabel: String = "x",
        yLabel: String = "f(x)",
        yDomain: Domain? = null,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = Double.NaN
    ): Fn {
        val box = Box.context.get()
            ?: throw IllegalStateException("Fn.plot requires an active Box context")
        return box.plot(
            fn = this,
            channel = channel,
            sampleDomain = sampleDomain,
            samples = samples,
            heightPx = heightPx,
            title = title,
            xLabel = xLabel,
            yLabel = yLabel,
            yDomain = yDomain,
            edge = edge,
            outside = outside
        )
    }

    /**
     * Draws this function into the currently executing box over an explicit x range, returning the function
     * unchanged. Throws [IllegalStateException] if there is no active box context.
     */
    @JvmName("plotCurrentBoxRange")
    fun Fn.plot(
        sampleDomain: ClosedRange<*>,
        channel: String = "plot",
        samples: Int = 1024,
        heightPx: Int = 360,
        title: String = name ?: "Fn",
        xLabel: String = "x",
        yLabel: String = "f(x)",
        yDomain: Domain? = null,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = Double.NaN
    ): Fn =
        plot(
            channel = channel,
            sampleDomain = sampleDomain.toDomain() as Domain?,
            samples = samples,
            heightPx = heightPx,
            title = title,
            xLabel = xLabel,
            yLabel = yLabel,
            yDomain = yDomain,
            edge = edge,
            outside = outside
        )

    /**
     * Draws this function into the currently executing box over explicit x *and* y ranges, returning the
     * function unchanged. Throws [IllegalStateException] if there is no active box context.
     */
    @JvmName("plotCurrentBoxRangeYRange")
    fun Fn.plot(
        sampleDomain: ClosedRange<*>,
        channel: String = "plot",
        samples: Int = 1024,
        heightPx: Int = 360,
        title: String = name ?: "Fn",
        xLabel: String = "x",
        yLabel: String = "f(x)",
        yDomain: ClosedRange<*>,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = Double.NaN
    ): Fn =
        plot(
            channel = channel,
            sampleDomain = sampleDomain.toDomain() as Domain?,
            samples = samples,
            heightPx = heightPx,
            title = title,
            xLabel = xLabel,
            yLabel = yLabel,
            yDomain = yDomain.toDomain() as Domain?,
            edge = edge,
            outside = outside
        )

    /**
     * Draws [fn] into **this** box on the given output [channel], returning [fn] unchanged.
     *
     * The explicit-receiver form, and the one to prefer: unlike `Fn.plot`, it needs no ambient box context
     * and so cannot throw for the want of one. Re-plotting on the same channel replaces the previous output.
     */
    fun Box.plot(
        fn: Fn,
        channel: String = "plot",
        sampleDomain: Domain? = null,
        samples: Int = 1024,
        heightPx: Int = 360,
        title: String = fn.name ?: "Fn",
        xLabel: String = "x",
        yLabel: String = "f(x)",
        yDomain: Domain? = null,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = Double.NaN
    ): Fn {
        // Same pixels as plotHtml, plus a live source kept on this box: zooming in resamples.
        val html = fn.plotViewerHtml(sampleDomain, samples, heightPx, title, xLabel, yLabel, yDomain, edge, outside,
            background = "#000000", primaryColor = "#0af", secondaryColor = "#f80",
            fontFamily = "\"Iosevka Web\", \"IBM Plex Mono\", monospace",
            live = { retainSignal(channel, it) })
        display(channel, html)
        return fn
    }

    /** Draws [fn] into this box over an explicit x range, returning [fn] unchanged. Needs no ambient box context. */
    fun Box.plot(
        fn: Fn,
        sampleDomain: ClosedRange<*>,
        channel: String = "plot",
        samples: Int = 1024,
        heightPx: Int = 360,
        title: String = fn.name ?: "Fn",
        xLabel: String = "x",
        yLabel: String = "f(x)",
        yDomain: Domain? = null,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = Double.NaN
    ): Fn =
        plot(
            fn = fn,
            channel = channel,
            sampleDomain = sampleDomain.toDomain() as Domain?,
            samples = samples,
            heightPx = heightPx,
            title = title,
            xLabel = xLabel,
            yLabel = yLabel,
            yDomain = yDomain,
            edge = edge,
            outside = outside
        )

    /** Draws [fn] into this box over explicit x *and* y ranges, returning [fn] unchanged. Needs no ambient box context. */
    fun Box.plot(
        fn: Fn,
        sampleDomain: ClosedRange<*>,
        channel: String = "plot",
        samples: Int = 1024,
        heightPx: Int = 360,
        title: String = fn.name ?: "Fn",
        xLabel: String = "x",
        yLabel: String = "f(x)",
        yDomain: ClosedRange<*>,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = Double.NaN
    ): Fn =
        plot(
            fn = fn,
            channel = channel,
            sampleDomain = sampleDomain.toDomain() as Domain?,
            samples = samples,
            heightPx = heightPx,
            title = title,
            xLabel = xLabel,
            yLabel = yLabel,
            yDomain = yDomain.toDomain() as Domain?,
            edge = edge,
            outside = outside
        )

    /**
     * Plots [fn] into the currently executing box — the prefix spelling of `Fn.plot`, for when `plot(f)`
     * reads better than `f.plot()`. Returns [fn] unchanged, and throws [IllegalStateException] if there is
     * no active box context.
     */
    fun plot(
        fn: Fn,
        channel: String = "plot",
        sampleDomain: Domain? = null,
        samples: Int = 1024,
        heightPx: Int = 360,
        title: String = fn.name ?: "Fn",
        xLabel: String = "x",
        yLabel: String = "f(x)",
        yDomain: Domain? = null,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = Double.NaN
    ): Fn = fn.plot(
        channel = channel,
        sampleDomain = sampleDomain,
        samples = samples,
        heightPx = heightPx,
        title = title,
        xLabel = xLabel,
        yLabel = yLabel,
        yDomain = yDomain,
        edge = edge,
        outside = outside
    )

    /**
     * Plots [fn] into the currently executing box over an explicit x range. Returns [fn] unchanged; throws
     * [IllegalStateException] if there is no active box context.
     */
    fun plot(
        fn: Fn,
        sampleDomain: ClosedRange<*>,
        channel: String = "plot",
        samples: Int = 1024,
        heightPx: Int = 360,
        title: String = fn.name ?: "Fn",
        xLabel: String = "x",
        yLabel: String = "f(x)",
        yDomain: Domain? = null,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = Double.NaN
    ): Fn =
        plot(
            fn = fn,
            channel = channel,
            sampleDomain = sampleDomain.toDomain() as Domain?,
            samples = samples,
            heightPx = heightPx,
            title = title,
            xLabel = xLabel,
            yLabel = yLabel,
            yDomain = yDomain,
            edge = edge,
            outside = outside
        )

    /**
     * Plots [fn] into the currently executing box over explicit x *and* y ranges. Returns [fn] unchanged;
     * throws [IllegalStateException] if there is no active box context.
     */
    fun plot(
        fn: Fn,
        sampleDomain: ClosedRange<*>,
        channel: String = "plot",
        samples: Int = 1024,
        heightPx: Int = 360,
        title: String = fn.name ?: "Fn",
        xLabel: String = "x",
        yLabel: String = "f(x)",
        yDomain: ClosedRange<*>,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = Double.NaN
    ): Fn =
        plot(
            fn = fn,
            channel = channel,
            sampleDomain = sampleDomain.toDomain() as Domain?,
            samples = samples,
            heightPx = heightPx,
            title = title,
            xLabel = xLabel,
            yLabel = yLabel,
            yDomain = yDomain.toDomain() as Domain?,
            edge = edge,
            outside = outside
        )

    // Streaming helpers
    /**
     * A movable read head over an [Fn]: remembers where it is and how far to move, so a caller can pull
     * values one at a time instead of asking for an array.
     *
     * The one **mutable** thing in this file — [x] and [step] are `var`s, and every `next` advances the head
     * — so a cursor is not safe to share across threads and not a value you can pass around casually. It
     * starts at the beginning of the function's domain (or, for an unbounded domain, of the finite preview
     * window) with a step of 1.0, which is rarely the step you want for a function living on `0.0..1.0`.
     *
     * Sampling is *not* domain-checked: walk past the end and you get whatever the underlying evaluator
     * says, not [Domain] edge handling.
     */
    class FunctionCursor(
        val fn: Fn,
        var x: Double = fn.domain.finiteSweep().min,
        var step: Double = 1.0
    ) {
        /** Samples at the current position without advancing. */
        fun peek(): Double = fn.eval(x)

        /** The value at the current position, then advances by [step]. The value returned is the one *before* the move. */
        fun next(): Double {
            val value = fn.eval(x)
            x += step
            return value
        }

        /** Returns the sampled coordinate and value, then advances once. */
        fun nextWithX(): Pair<Double, Double> {
            val sampleX = x
            return sampleX to next()
        }

        /**
         * The next [count] values, advancing one [step] between each — so the head ends up [count] steps
         * further on. A count of zero returns an empty array and moves nothing; a negative count throws.
         */
        fun next(count: Int): DoubleArray {
            require(count >= 0) { "count must be non-negative" }
            val out = DoubleArray(count)
            for (i in 0 until count) {
                out[i] = next()
            }
            return out
        }

        /** Moves by [count] cursor steps without evaluating the function. */
        fun skip(count: Int = 1): FunctionCursor {
            x += step * count
            return this
        }

        /** Jumps the head to an absolute [position], ignoring [step], and returns this cursor for chaining. Not clamped to the domain. */
        fun seek(position: Double): FunctionCursor {
            x = position
            return this
        }

        /** Sends the head back to the start of the function's domain — or to an explicit [position]. Leaves [step] alone. */
        fun reset(position: Double = fn.domain.finiteSweep().min): FunctionCursor = seek(position)

        /**
         * A second, independent cursor over the same function, by default at this one's position and step.
         * Useful for looking ahead without disturbing where you are, since [next] on the original would move
         * it.
         */
        fun copy(x: Double = this.x, step: Double = this.step): FunctionCursor = FunctionCursor(fn, x, step)

        override fun toString(): String = prettySummary(
            "FunctionCursor",
            "fn" to fn,
            "x" to prettyNumber(x),
            "step" to prettyNumber(step)
        )
    }

    /**
     * A [FunctionCursor] over this function, positioned at the start of its domain with a step of 1.0 unless
     * told otherwise. Note that a step of 1.0 across a `0.0..1.0` function covers the whole thing in one
     * move — set [step] explicitly.
     */
    fun Fn.cursor(start: Double = domain.finiteSweep().min, step: Double = 1.0): FunctionCursor = FunctionCursor(this, start, step)

    /**
     * An **infinite** lazy sequence of values, starting at [start] and advancing by [step] forever.
     *
     * It never terminates and never checks the domain, so it must be bounded by the caller (`take`,
     * `takeWhile`, …) — collecting it directly will hang. The default step of 1.0 is coarse for a function
     * living on `0.0..1.0`.
     */
    fun Fn.stream(start: Double = domain.finiteSweep().min, step: Double = 1.0): Sequence<Double> = sequence {
        var x = start
        while (true) {
            yield(eval(x))
            x += step
        }
    }

    /**
     * A **finite** lazy sequence of values across [domain], one every [step] — the terminating counterpart to
     * the `start`-based overload.
     *
     * A negative [step] walks *downward* from `max` to `min`, which is the only way to read a function
     * backwards here. `includeEnd` decides whether the far endpoint is emitted when the width divides
     * evenly. Yields nothing at all for an empty domain, an infinite one, or a step of zero — rather than
     * looping forever.
     */
    fun Fn.stream(domain: Domain, step: Double = 1.0, includeEnd: Boolean = true): Sequence<Double> = sequence {
        if (domain._isEmpty) return@sequence
        if (!domain.length.isFinite() || step == 0.0) return@sequence
        if (step > 0.0) {
            var x = domain.min
            val end = domain.max
            if (includeEnd) {
                while (x <= end) {
                    yield(eval(x))
                    x += step
                }
            } else {
                while (x < end) {
                    yield(eval(x))
                    x += step
                }
            }
        } else {
            var x = domain.max
            val end = domain.min
            if (includeEnd) {
                while (x >= end) {
                    yield(eval(x))
                    x += step
                }
            } else {
                while (x > end) {
                    yield(eval(x))
                    x += step
                }
            }
        }
    }

    /**
     * An **infinite** lazy sequence of `(x, value)` pairs from [start], advancing by [step] — [stream] when
     * you need to know where each sample came from. Never terminates, so bound it with `take`.
     */
    fun Fn.streamWithX(start: Double = domain.finiteSweep().min, step: Double = 1.0): Sequence<Pair<Double, Double>> = sequence {
        var x = start
        while (true) {
            yield(x to eval(x))
            x += step
        }
    }

    /**
     * How a timeline chunk maps a position on the timeline into its source function's own coordinates.
     *
     * `DIRECT` is one-for-one, so the source plays at its natural rate and a chunk longer than the source
     * simply runs off the end (where the chunk's `edge` takes over). `STRETCH` fits the source's whole domain
     * to the chunk's, time-scaling it. `CLAMP`, `WRAP` and `MIRROR` are `DIRECT` with the over-run folded
     * back in — freeze on the last value, loop, or ping-pong respectively.
     */
    enum class ChunkMapMode { DIRECT, STRETCH, CLAMP, WRAP, MIRROR }

    /**
     * How a timeline chunk combines with whatever earlier chunks have already contributed at that x.
     *
     * `REPLACE` crossfades toward the chunk by its weight, so a weight of 1 overwrites and a windowed chunk
     * blends. `ADD` sums the weighted chunk on top. `MULTIPLY` scales the running value by the chunk, fading
     * toward 1 (a no-op) as the weight drops. Chunks are applied in the order they were placed.
     */
    enum class ChunkBlend { REPLACE, ADD, MULTIPLY }

    /** [ChunkBlend.ADD], re-exported so `blend = ADD` reads as DSL inside `with(Functions)`. */
    val ADD: ChunkBlend get() = ChunkBlend.ADD

    /** [ChunkBlend.REPLACE], re-exported so `blend = REPLACE` reads as DSL inside `with(Functions)`. */
    val REPLACE: ChunkBlend get() = ChunkBlend.REPLACE

    /** [ChunkBlend.MULTIPLY], re-exported so `blend = MULTIPLY` reads as DSL inside `with(Functions)`. */
    val MULTIPLY: ChunkBlend get() = ChunkBlend.MULTIPLY

    /**
     * One placement on a timeline: which function, which slice of it, where that slice goes, and how it is
     * mapped and blended once it gets there.
     *
     * This is the record [TimelineBuilder] accumulates and [TimelineGraph] plays back. `sourceDomain` is the
     * part of the source being used and `domain` is the destination interval on the timeline — the two need
     * not be the same length, which is what [ChunkMapMode] is for. `window` is evaluated on a normalized
     * 0..1 alpha across the chunk (so a `hannWindow()` fades it in and out), while `gain` is evaluated in
     * *timeline* coordinates; the two multiply into the blend weight.
     */
    data class TimelineChunk(
        val source: Fn,
        val sourceDomain: Domain = source.domain,
        val domain: Domain,
        val mapMode: ChunkMapMode = ChunkMapMode.DIRECT,
        val blend: ChunkBlend = ChunkBlend.REPLACE,
        val edge: EdgeMode = EdgeMode.ZERO,
        val outside: Double = 0.0,
        val window: Fn? = null,
        val gain: Fn? = null,
        val name: String? = source.name
    ) {
        private fun alpha(globalX: Double): Double {
            val len = domain.length
            if (!len.isFinite() || len == 0.0) return 0.0
            return ((globalX - domain.min) / len).coerceIn(0.0, 1.0)
        }

        /** Whether this chunk occupies the timeline position [globalX], both ends of its destination interval included. */
        fun contains(globalX: Double): Boolean = domain.containsInclusive(globalX)

        private fun mapToSource(globalX: Double): Double {
            val linear = sourceDomain.min + (globalX - domain.min)
            return when (mapMode) {
                ChunkMapMode.DIRECT -> linear
                ChunkMapMode.STRETCH -> sourceDomain.lerp(alpha(globalX))
                ChunkMapMode.CLAMP -> sourceDomain.clamp(linear)
                ChunkMapMode.WRAP -> sourceDomain.wrap(linear)
                ChunkMapMode.MIRROR -> sourceDomain.reflect(linear)
            }
        }

        /**
         * The blend weight of this chunk at timeline position [globalX]: the window sampled at the chunk's own
         * 0..1 alpha, times the gain sampled in timeline coordinates.
         *
         * Exactly `0.0` outside the chunk, and `1.0` inside it when neither window nor gain was given. The
         * result is *not* clamped here — a gain above one is honoured — though `REPLACE` and `MULTIPLY`
         * blending will clamp it when they use it.
         */
        fun weightAt(globalX: Double): Double {
            if (!contains(globalX)) return 0.0
            val shaped = window?.evalSafe(alpha(globalX), outside = 0.0, edge = EdgeMode.CLAMP) ?: 1.0
            val g = gain?.eval(globalX) ?: 1.0
            return shaped * g
        }

        /**
         * The source's contribution at timeline position [globalX], or **null** if this chunk has nothing to
         * say there — either [globalX] is outside it, or its `sourceDomain` is empty.
         *
         * Null is meaningfully different from zero: [TimelineGraph] uses it to decide whether *any* chunk was
         * active, and falls back to its `outside` value when none was. The mapped source coordinate is read
         * through `evalSafe`, so the chunk's own `edge` and `outside` govern any over-run.
         */
        fun sampleAt(globalX: Double): Double? {
            if (!contains(globalX) || sourceDomain._isEmpty) return null
            val sourceX = mapToSource(globalX)
            return source.evalSafe(sourceX, outside = outside, edge = edge)
        }

        override fun toString(): String = prettySummary(
            "TimelineChunk",
            "name" to (name ?: source.name ?: "chunk"),
            "domain" to domain,
            "source" to sourceDomain,
            "map" to mapMode,
            "blend" to blend,
            "window" to window,
            "gain" to gain
        )
    }

    /**
     * A finished arrangement: a list of [TimelineChunk]s that can be evaluated as one signal, or turned into
     * an [Fn].
     *
     * Chunks are applied in list order, each blending onto the running value per its own [ChunkBlend], and
     * they may freely overlap. Where **no** chunk is active the result is [outside] — and note that [outside]
     * is also the starting value the first chunk blends onto, so a non-zero outside shows through an `ADD`
     * arrangement as a DC offset. Immutable: the chunk list is copied on construction.
     */
    class TimelineGraph(
        chunks: List<TimelineChunk>,
        val outside: Double = 0.0,
        val defaultDomain: Domain = Domain.point(0.0)
    ) {
        /** The placements, in the order they are blended. A copy taken at construction, so it never changes underfoot. */
        val chunks: List<TimelineChunk> = chunks.toList()

        /** The extent of the whole arrangement: the hull of every chunk's destination interval, or [defaultDomain] if there are no chunks. Gaps between chunks are inside this domain, and read as [outside]. */
        val domain: Domain = run {
            var d = Domain.EMPTY
            for (chunk in this.chunks) {
                d = if (d._isEmpty) chunk.domain else d.union(chunk.domain)
            }
            if (d._isEmpty) defaultDomain else d
        }

        private fun applyBlend(base: Double, sample: Double, weight: Double, blendMode: ChunkBlend): Double =
            when (blendMode) {
                ChunkBlend.REPLACE -> blend(base, sample, clamp01(weight))
                ChunkBlend.ADD -> base + sample * weight
                ChunkBlend.MULTIPLY -> base * blend(1.0, sample, clamp01(weight))
            }

        /**
         * The arranged value at timeline position [x]: every chunk that covers [x], blended in placement
         * order. Returns [outside] where no chunk covers [x] — including gaps in the middle of the timeline.
         */
        fun eval(x: Double): Double {
            var value = outside
            var active = false
            for (chunk in chunks) {
                val sample = chunk.sampleAt(x) ?: continue
                val weight = chunk.weightAt(x)
                value = applyBlend(value, sample, weight, chunk.blend)
                active = true
            }
            return if (active) value else outside
        }

        /** `graph(x)` — call syntax for [eval]. */
        operator fun invoke(x: Double): Double = eval(x)

        private fun graphChildren(): List<FnGraphEdge> = buildList {
            chunks.forEachIndexed { index, chunk ->
                add(fnEdge("chunk$index.source", chunk.source))
                chunk.window?.let { add(fnEdge("chunk$index.window", it)) }
                chunk.gain?.let { add(fnEdge("chunk$index.gain", it)) }
            }
        }

        private fun rebuildWithChildren(children: List<Fn>): TimelineGraph {
            var cursor = 0
            val rebuilt = chunks.map { chunk ->
                val source = childAt(children, cursor++, chunk.source)
                val window = if (chunk.window == null) null else childAt(children, cursor++, chunk.window)
                val gain = if (chunk.gain == null) null else childAt(children, cursor++, chunk.gain)
                chunk.copy(source = source, window = window, gain = gain)
            }
            return TimelineGraph(rebuilt, outside, defaultDomain)
        }

        /**
         * This arrangement as an ordinary [Fn], so it can be composed, sampled and drawn like anything else.
         *
         * Defaults to the arrangement's own [domain]; pass a narrower one to crop or a wider one to expose the
         * [outside] value beyond the chunks. Every chunk's source, window and gain becomes a graph child, so
         * the result is rewritable rather than opaque.
         */
        fun asFn(
            name: String = "timeline",
            domain: Domain = this.domain
        ): Fn = graphFn(
            domain,
            name,
            "timeline",
            children = graphChildren(),
            numbers = mapOf("outside" to outside, "chunks" to chunks.size.toDouble()),
            strings = mapOf("defaultDomain" to defaultDomain.toString()),
            rebuild = { children -> rebuildWithChildren(children).asFn(name, domain) }
        ) { x -> eval(x) }

        /**
         * This arrangement as an [Fn] **delayed** by [offset]: reading at x returns what the timeline holds at
         * `x - offset`, and the reported domain slides with it.
         *
         * The mechanism behind look-back and feedback effects — a positive offset lets a later part of an
         * arrangement listen to an earlier one. Because everything here stays a pure function there is no
         * latch state and no runaway loop; the cost is that the earlier material is genuinely re-evaluated on
         * every sample.
         */
        fun tap(offset: Double = 0.0, name: String = "timelineTap"): Fn {
            val tapDomain = if (domain._isEmpty) domain else domain.shift(offset)
            return graphFn(
                tapDomain,
                name,
                "timelineTap",
                children = graphChildren(),
                numbers = mapOf("offset" to offset, "outside" to outside, "chunks" to chunks.size.toDouble()),
                rebuild = { children -> rebuildWithChildren(children).tap(offset, name) }
            ) { x -> eval(x - offset) }
        }

        override fun toString(): String = prettySummary(
            "TimelineGraph",
            "chunks" to chunks.size,
            "domain" to domain,
            "outside" to prettyNumber(outside)
        )
    }

    /**
     * The receiver inside `timeline { }`: a moving cursor, a set of named marks, and the placement verbs
     * ([Fn.place], [Fn.overlap], [Fn.splice], [repeatToFit], [stretchToFit]) that lay functions out in time.
     *
     * The model is a tape head. Each placement lands at the [cursor] by default and moves it to the end of
     * what was placed, so consecutive `place()` calls butt up against each other; [advance], [rewind],
     * [moveTo] and [moveToMark] reposition it by hand when you want a gap or an overlap. This is genuinely
     * mutable and single-threaded — build the arrangement, then call [build] to freeze it into an [Fn].
     *
     * A placement's duration defaults to the source's own domain length, falling back to `1.0` when that is
     * zero or infinite (which is what an oscillator will give you), and is always taken as a magnitude, so a
     * negative duration does not run backwards.
     */
    class TimelineBuilder(
        start: Double = 0.0,
        private val outside: Double = 0.0
    ) {
        private val chunks = mutableListOf<TimelineChunk>()
        private val marks = LinkedHashMap<String, Double>()
        private val startAt = start

        /** Where the next placement will land, in timeline coordinates. Read-only from outside; use [moveTo], [advance] or [rewind] to change it. */
        var cursor: Double = start
            private set

        private fun defaultDurationFor(domain: Domain): Double {
            val len = domain.length
            return if (len.isFinite() && len > 0.0) len else 1.0
        }

        private fun normalizeDuration(duration: Double?, sourceDomain: Domain): Double {
            val d = duration ?: defaultDurationFor(sourceDomain)
            return if (!d.isFinite()) defaultDurationFor(sourceDomain) else kotlin.math.abs(d)
        }

        /** Remembers the current [cursor] under [name] so [moveToMark] can come back to it, and returns that position. Re-marking an existing name overwrites it. */
        fun mark(name: String): Double {
            marks[name] = cursor
            return cursor
        }

        /** Remembers an explicit position under [name] without moving the cursor, and returns it. Re-marking an existing name overwrites it. */
        fun mark(name: String, at: Double): Double {
            marks[name] = at
            return at
        }

        /** The position stored under [name], or null if nothing was marked with that name. */
        fun marker(name: String): Double? = marks[name]

        /** Moves the cursor to an absolute timeline position and returns it. Positions before the timeline's start are allowed. */
        fun moveTo(x: Double): Double {
            cursor = x
            return cursor
        }

        /**
         * Moves the cursor to the mark [name], plus an optional [offset], and returns the new position.
         * **An unknown name is not an error** — the cursor simply stays where it is and the offset is applied
         * to that, so a typo shifts your arrangement rather than failing loudly.
         */
        fun moveToMark(name: String, offset: Double = 0.0): Double {
            cursor = (marks[name] ?: cursor) + offset
            return cursor
        }

        /** Moves the cursor [by] units later (or earlier, if negative) and returns the new position — the way to leave a gap between placements. */
        fun advance(by: Double): Double {
            cursor += by
            return cursor
        }

        /** Moves the cursor [by] units earlier and returns the new position. Exactly `advance(-by)`. */
        fun rewind(by: Double): Double = advance(-by)

        private fun insertChunk(
            source: Fn,
            at: Double = cursor,
            duration: Double? = null,
            sourceDomain: Domain = source.domain,
            mapMode: ChunkMapMode = ChunkMapMode.DIRECT,
            blend: ChunkBlend = ChunkBlend.REPLACE,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this.outside,
            window: Fn? = null,
            gain: Any? = null,
            advanceCursor: Boolean = true
        ): TimelineChunk {
            val dur = normalizeDuration(duration, sourceDomain)
            val target = Domain.of(at, at + dur)
            val chunk = TimelineChunk(
                source = source,
                sourceDomain = sourceDomain,
                domain = target,
                mapMode = mapMode,
                blend = blend,
                edge = edge,
                outside = outside,
                window = window,
                gain = asOptionalParam(gain, "gain")
            )
            chunks += chunk
            if (advanceCursor) cursor = target.max
            return chunk
        }

        override fun toString(): String = prettySummary(
            "TimelineBuilder",
            "cursor" to prettyNumber(cursor),
            "chunks" to chunks.size,
            "marks" to marks.keys.toList(),
            "outside" to prettyNumber(outside)
        )

        /**
         * Places [source] so that it **loops** to fill [duration], however long the source itself is.
         *
         * The same as `place(mapMode = WRAP)`: the source keeps its natural rate and simply wraps round its own
         * domain as often as needed, so the joins are hard cuts unless the source happens to meet itself. Use
         * [stretchToFit] instead to fill the duration by time-scaling a single pass. Advances the cursor by
         * default, and returns the placement.
         */
        fun repeatToFit(
            source: Fn,
            duration: Double,
            at: Double = cursor,
            sourceDomain: Domain = source.domain,
            blend: ChunkBlend = ChunkBlend.REPLACE,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this.outside,
            window: Fn? = null,
            gain: Any? = null,
            advanceCursor: Boolean = true
        ): TimelineChunk = insertChunk(
            source = source,
            at = at,
            duration = duration,
            sourceDomain = sourceDomain,
            mapMode = ChunkMapMode.WRAP,
            blend = blend,
            edge = edge,
            outside = outside,
            window = window,
            gain = gain,
            advanceCursor = advanceCursor
        )

        /**
         * Places [source] **time-scaled** so exactly one pass of it fills [duration].
         *
         * The same as `place(mapMode = STRETCH)`: one traversal, faster or slower than the source's natural
         * rate. Contrast [repeatToFit], which loops the source at its own rate instead. Advances the cursor by
         * default, and returns the placement.
         */
        fun stretchToFit(
            source: Fn,
            duration: Double,
            at: Double = cursor,
            sourceDomain: Domain = source.domain,
            blend: ChunkBlend = ChunkBlend.REPLACE,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this.outside,
            window: Fn? = null,
            gain: Any? = null,
            advanceCursor: Boolean = true
        ): TimelineChunk = insertChunk(
            source = source,
            at = at,
            duration = duration,
            sourceDomain = sourceDomain,
            mapMode = ChunkMapMode.STRETCH,
            blend = blend,
            edge = edge,
            outside = outside,
            window = window,
            gain = gain,
            advanceCursor = advanceCursor
        )

        private fun insertOverlap(
            source: Fn,
            overlap: Number,
            at: Double = cursor,
            duration: Double? = null,
            sourceDomain: Domain = source.domain,
            mapMode: ChunkMapMode = ChunkMapMode.DIRECT,
            blend: ChunkBlend = ChunkBlend.REPLACE,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this.outside,
            window: Fn = hannWindow(Domain.DEFAULT),
            gain: Any? = null
        ): TimelineChunk = insertOverlap(
            source = source,
            overlap = param(overlap),
            at = at,
            duration = duration,
            sourceDomain = sourceDomain,
            mapMode = mapMode,
            blend = blend,
            edge = edge,
            outside = outside,
            window = window,
            gain = gain
        )

        private fun insertOverlap(
            source: Fn,
            overlap: Fn,
            at: Double = cursor,
            duration: Double? = null,
            sourceDomain: Domain = source.domain,
            mapMode: ChunkMapMode = ChunkMapMode.DIRECT,
            blend: ChunkBlend = ChunkBlend.REPLACE,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this.outside,
            window: Fn = hannWindow(Domain.DEFAULT),
            gain: Any? = null
        ): TimelineChunk {
            val dur = normalizeDuration(duration, sourceDomain)
            val ov = sequenceOverlapAt(overlap, at, dur)
            return insertChunk(
                source = source,
                at = at - ov,
                duration = dur,
                sourceDomain = sourceDomain,
                mapMode = mapMode,
                blend = blend,
                edge = edge,
                outside = outside,
                window = window,
                gain = gain,
                advanceCursor = true
            )
        }

        private fun insertSplice(
            source: Fn,
            lookBack: Number,
            duration: Double? = null,
            sourceDomain: Domain = source.domain,
            fadeIn: Number = 0.15,
            fadeOut: Number = fadeIn,
            mapMode: ChunkMapMode = ChunkMapMode.STRETCH,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this.outside,
            gain: Any? = null,
            advanceCursor: Boolean = false
        ): TimelineChunk = insertSplice(
            source = source,
            lookBack = param(lookBack),
            duration = duration,
            sourceDomain = sourceDomain,
            fadeIn = param(fadeIn),
            fadeOut = param(fadeOut),
            mapMode = mapMode,
            edge = edge,
            outside = outside,
            gain = gain,
            advanceCursor = advanceCursor
        )

        private fun insertSplice(
            source: Fn,
            lookBack: Fn,
            duration: Double? = null,
            sourceDomain: Domain = source.domain,
            fadeIn: Fn = param(0.15),
            fadeOut: Fn = fadeIn,
            mapMode: ChunkMapMode = ChunkMapMode.STRETCH,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this.outside,
            gain: Any? = null,
            advanceCursor: Boolean = false
        ): TimelineChunk {
            val dur = normalizeDuration(duration, sourceDomain)
            val look = abs(lookBack.paramAt(cursor)).coerceAtLeast(0.0)
            val start = cursor - look
            return insertChunk(
                source = source,
                at = start,
                duration = dur,
                sourceDomain = sourceDomain,
                mapMode = mapMode,
                blend = ChunkBlend.REPLACE,
                edge = edge,
                outside = outside,
                window = spliceWindow(
                    fadeIn = fadeIn.paramAt(start).coerceIn(0.0, 1.0),
                    fadeOut = fadeOut.paramAt(start + dur).coerceIn(0.0, 1.0)
                ),
                gain = gain,
                advanceCursor = advanceCursor
            )
        }

        /**
         * Freezes the placements made **so far** into an [Fn], optionally delayed by [offset].
         *
         * A genuine snapshot: chunks placed after this call do not appear in it, which is what makes it usable
         * mid-build as an input to later material. Does not move the cursor or otherwise disturb the build.
         */
        fun snapshot(offset: Double = 0.0, name: String = "timelineSnapshot"): Fn {
            val graph = buildGraph()
            return graph.tap(offset, name)
        }

        /**
         * The arrangement so far, delayed by [delay] — a signal that, at any x, reads what the timeline held
         * [delay] earlier. The idiom for echo and call-and-response between parts of one arrangement, and a
         * [snapshot] under a more suggestive name.
         */
        fun lookBack(delay: Double, name: String = "timelineLookBack"): Fn =
            snapshot(offset = delay, name = name)

        /**
         * The basic placement verb: puts this function on the timeline at [at] (the cursor by default) and, by
         * default, moves the cursor to the end of it.
         *
         * [duration] defaults to the source's own domain length, or `1.0` if that is zero or infinite.
         * [sourceDomain] chooses which slice of the source is used; [mapMode] decides how it is fitted to the
         * destination; [window] fades it (evaluated on a 0..1 alpha across the placement) and [gain] scales it
         * (evaluated in timeline coordinates). Pass `advanceCursor = false` to layer something without
         * disturbing where the tape head sits. Returns the [TimelineChunk] it created.
         */
        fun Fn.place(
            at: Double = cursor,
            duration: Double? = null,
            sourceDomain: Domain = domain,
            mapMode: ChunkMapMode = ChunkMapMode.DIRECT,
            blend: ChunkBlend = ChunkBlend.REPLACE,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this@TimelineBuilder.outside,
            window: Fn? = null,
            gain: Any? = null,
            advanceCursor: Boolean = true
        ): TimelineChunk = this@TimelineBuilder.insertChunk(
            source = this,
            at = at,
            duration = duration,
            sourceDomain = sourceDomain,
            mapMode = mapMode,
            blend = blend,
            edge = edge,
            outside = outside,
            window = window,
            gain = gain,
            advanceCursor = advanceCursor
        )

        /**
         * Places this function starting [amount] units **before** [at], so it crossfades with whatever is
         * already there, windowed by a Hann curve unless you say otherwise.
         *
         * The overlap is clamped to the placement's own duration, so asking to overlap by more than the material
         * lasts is honoured only as far as it can be. Note that the effective overlap is decided by sampling
         * around the join, which matters when [amount] is a signal. Always advances the cursor, and returns the
         * placement.
         */
        fun Fn.overlap(
            amount: Number,
            at: Double = cursor,
            duration: Double? = null,
            sourceDomain: Domain = domain,
            mapMode: ChunkMapMode = ChunkMapMode.DIRECT,
            blend: ChunkBlend = ChunkBlend.REPLACE,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this@TimelineBuilder.outside,
            window: Fn = hannWindow(Domain.DEFAULT),
            gain: Any? = null
        ): TimelineChunk = this@TimelineBuilder.insertOverlap(
            source = this,
            overlap = amount,
            at = at,
            duration = duration,
            sourceDomain = sourceDomain,
            mapMode = mapMode,
            blend = blend,
            edge = edge,
            outside = outside,
            window = window,
            gain = gain
        )

        /**
         * Places this function starting [amount] units before [at], crossfading with what is already there under
         * a Hann window by default. With a signal-valued [amount] the overlap actually used is sampled around
         * the join itself, so a modulated overlap varies from join to join. Always advances the cursor.
         */
        fun Fn.overlap(
            amount: Fn,
            at: Double = cursor,
            duration: Double? = null,
            sourceDomain: Domain = domain,
            mapMode: ChunkMapMode = ChunkMapMode.DIRECT,
            blend: ChunkBlend = ChunkBlend.REPLACE,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this@TimelineBuilder.outside,
            window: Fn = hannWindow(Domain.DEFAULT),
            gain: Any? = null
        ): TimelineChunk = this@TimelineBuilder.insertOverlap(
            source = this,
            overlap = amount,
            at = at,
            duration = duration,
            sourceDomain = sourceDomain,
            mapMode = mapMode,
            blend = blend,
            edge = edge,
            outside = outside,
            window = window,
            gain = gain
        )

        /**
         * Drops this function in as an **insert** that starts [lookBack] units before the cursor, fading in and
         * out so it sits over the existing material rather than butting against it.
         *
         * Time-scaled (`STRETCH`) by default and `REPLACE`-blended, with a [spliceWindow] built from [fadeIn] and
         * [fadeOut] — each a fraction of the insert's length, clamped to 0..1. Unlike every other placement verb
         * here, this **does not advance the cursor** by default, so the following placement still lands where you
         * were. Returns the placement.
         */
        fun Fn.splice(
            lookBack: Number,
            duration: Double? = null,
            sourceDomain: Domain = domain,
            fadeIn: Number = 0.15,
            fadeOut: Number = fadeIn,
            mapMode: ChunkMapMode = ChunkMapMode.STRETCH,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this@TimelineBuilder.outside,
            gain: Any? = null,
            advanceCursor: Boolean = false
        ): TimelineChunk = this@TimelineBuilder.insertSplice(
            source = this,
            lookBack = lookBack,
            duration = duration,
            sourceDomain = sourceDomain,
            fadeIn = fadeIn,
            fadeOut = fadeOut,
            mapMode = mapMode,
            edge = edge,
            outside = outside,
            gain = gain,
            advanceCursor = advanceCursor
        )

        /**
         * Drops this function in as an insert starting [lookBack] units before the cursor, time-scaled and faded
         * at both ends. Signal-valued arguments are sampled where they matter — the look-back at the cursor, the
         * fades at the insert's own start and end. **Does not advance the cursor** by default.
         */
        fun Fn.splice(
            lookBack: Fn,
            duration: Double? = null,
            sourceDomain: Domain = domain,
            fadeIn: Fn = param(0.15),
            fadeOut: Fn = fadeIn,
            mapMode: ChunkMapMode = ChunkMapMode.STRETCH,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this@TimelineBuilder.outside,
            gain: Any? = null,
            advanceCursor: Boolean = false
        ): TimelineChunk = this@TimelineBuilder.insertSplice(
            source = this,
            lookBack = lookBack,
            duration = duration,
            sourceDomain = sourceDomain,
            fadeIn = fadeIn,
            fadeOut = fadeOut,
            mapMode = mapMode,
            edge = edge,
            outside = outside,
            gain = gain,
            advanceCursor = advanceCursor
        )

        /** The placements made so far, as a copy — safe to hold on to while the build continues. */
        fun chunks(): List<TimelineChunk> = chunks.toList()

        /**
         * Freezes the placements so far into a [TimelineGraph], for when you want the chunk structure rather than
         * an [Fn]. An arrangement with no chunks gets a single-point domain at the builder's start.
         */
        fun buildGraph(): TimelineGraph = TimelineGraph(
            chunks = chunks,
            outside = outside,
            defaultDomain = Domain.point(startAt)
        )

        /**
         * Freezes the arrangement into an [Fn] spanning every placement — the usual last line of a `timeline { }`
         * block. Pass [domain] to crop or extend that span; gaps and anything beyond the chunks read as the
         * builder's `outside` value.
         */
        fun build(name: String = "timeline", domain: Domain? = null): Fn {
            val graph = buildGraph()
            return graph.asFn(name = name, domain = domain ?: graph.domain)
        }
    }

    /**
     * A fade-in / fade-out window: `1.0` through the middle, smoothstepped down to `0.0` at whichever ends you
     * asked to fade.
     *
     * [fadeIn] and [fadeOut] are **fractions of the domain**, not x distances, and are clamped to 0..1 — so
     * `0.15` fades over the first and last fifteen percent. A fade of zero means no fade at that end, giving a
     * hard edge. Unlike [hannWindow] this is flat in between, which is what makes it right for splicing material
     * in without dipping its middle.
     */
    fun spliceWindow(
        fadeIn: Double = 0.15,
        fadeOut: Double = fadeIn,
        domain: Domain = Domain.DEFAULT
    ): Fn {
        val fi = fadeIn.coerceAtLeast(0.0).coerceAtMost(1.0)
        val fo = fadeOut.coerceAtLeast(0.0).coerceAtMost(1.0)
        return graphFn(
            domain,
            "spliceWindow",
            "spliceWindow",
            numbers = mapOf("fadeIn" to fadeIn, "fadeOut" to fadeOut),
            rebuild = { spliceWindow(fadeIn, fadeOut, domain) }
        ) { x ->
            val t = clamp01(domain.finiteSweep().alphaOf(x))
            val inW = if (fi == 0.0 || t >= fi) 1.0 else smoothstep(t / fi)
            val outStart = 1.0 - fo
            val outW = if (fo == 0.0 || t <= outStart) 1.0 else smoothstep((1.0 - t) / fo)
            (inW * outW).coerceIn(0.0, 1.0)
        }
    }

    /**
     * Runs a [TimelineBuilder] block and returns the resulting [TimelineGraph] — [timeline] when you want the
     * chunk structure to inspect or re-blend, rather than a finished [Fn].
     */
    fun timelineGraph(
        start: Double = 0.0,
        outside: Double = 0.0,
        init: TimelineBuilder.() -> Unit
    ): TimelineGraph {
        val builder = TimelineBuilder(start = start, outside = outside)
        builder.init()
        return builder.buildGraph()
    }

    /**
     * Arranges functions in time and returns the result as one [Fn] — the main entry point for composition
     * across x, as opposed to the arithmetic that stacks signals on top of each other.
     *
     * Inside the block you have a [TimelineBuilder]: place material with `place()`, `overlap()`, `splice()`,
     * `repeatToFit()` and `stretchToFit()`, and move the cursor with `advance()`, `mark()` and `moveToMark()`.
     * The resulting domain spans everything placed, and [outside] is what gaps and the world beyond the
     * arrangement read as.
     */
    fun timeline(
        start: Double = 0.0,
        outside: Double = 0.0,
        name: String = "timeline",
        domain: Domain? = null,
        init: TimelineBuilder.() -> Unit
    ): Fn {
        val builder = TimelineBuilder(start = start, outside = outside)
        builder.init()
        return builder.build(name = name, domain = domain)
    }

    private data class SequencePart(
        val fn: Fn,
        val sourceDomain: Domain,
        val duration: Double
    )

    private data class SequencePlacement(
        val part: SequencePart,
        val start: Double,
        val fadeIn: Double,
        val fadeOut: Double
    )

    private fun sequenceDuration(domain: Domain): Double {
        val len = domain.length
        return if (len.isFinite() && len > 0.0) len else 1.0
    }

    private fun List<Fn>.sequenceParts(): List<SequencePart> =
        map { fn ->
            // Oscillators (and other unbounded fns) default to Domain.ALL. The slot
            // sampler maps a local coordinate into the source via sourceDomain.lerp(alpha),
            // which is +/-inf/NaN for an unbounded domain — so the source is evaluated at
            // infinity and every sequence-family combinator (sequence/shuffleLoop/palindrome/
            // roundRobin/randomSequence/...) collapses to `outside`. Preview an unbounded
            // source over a finite window here, the same finiteSweep() guard the other
            // implicit-domain samplers use; finite domains pass through unchanged.
            val sweep = fn.domain.finiteSweep()
            SequencePart(fn, sweep, sequenceDuration(sweep))
        }

    // Composition parameters are functions too. Literal overloads are just
    // adapters to constant Fns; parametric overloads sample near the place where
    // the parameter matters, e.g. around a join to choose that join's overlap.
    private fun Fn.paramAt(x: Double, outside: Double = 0.0): Double =
        if (domain._isEmpty) outside else evalSafe(x, outside = outside, edge = EdgeMode.CLAMP)

    private fun sequenceStep(duration: Double, overlap: Double): Double =
        (duration - overlap.coerceAtLeast(0.0).coerceAtMost(duration * 0.999)).coerceAtLeast(1e-9)

    private fun sequenceOverlapAt(overlap: Fn, boundary: Double, maxOverlap: Double): Double {
        if (maxOverlap <= 0.0) return 0.0
        val first = overlap.paramAt(boundary).coerceIn(0.0, maxOverlap)
        val midpoint = boundary - first * 0.5
        return overlap.paramAt(midpoint).coerceIn(0.0, maxOverlap)
    }

    private fun sequenceFadeWeight(local: Double, duration: Double, fadeIn: Double, fadeOut: Double): Double {
        val inFade = fadeIn.coerceAtLeast(0.0).coerceAtMost(duration * 0.5)
        val outFade = fadeOut.coerceAtLeast(0.0).coerceAtMost(duration * 0.5)
        val inW = if (inFade > 0.0 && local < inFade) smootherstep((local / inFade).coerceIn(0.0, 1.0)) else 1.0
        val outW = if (outFade > 0.0 && local > duration - outFade) smootherstep(((duration - local) / outFade).coerceIn(0.0, 1.0)) else 1.0
        return inW * outW
    }

    private fun SequencePlacement.sample(globalX: Double, edge: EdgeMode, outside: Double): Double? {
        val duration = part.duration
        val local = globalX - start
        if (local < 0.0 || local > duration) return null
        val alpha = if (duration <= 0.0) 0.0 else (local / duration).coerceIn(0.0, 1.0)
        val sourceX = part.sourceDomain.lerp(alpha)
        val value = part.fn.evalSafe(sourceX, outside = outside, edge = edge)
        return value * sequenceFadeWeight(local, duration, fadeIn, fadeOut)
    }

    private fun sequencePlacements(parts: List<SequencePart>, overlap: Fn, loop: Boolean, startOffset: Double = 0.0): Pair<List<SequencePlacement>, Double> {
        var cursor = 0.0
        val placements = ArrayList<SequencePlacement>(parts.size)
        var previousOverlap = 0.0
        for (i in parts.indices) {
            val part = parts[i]
            val boundary = startOffset + cursor + part.duration
            val nextOverlap = if (loop || i < parts.lastIndex) sequenceOverlapAt(overlap, boundary, part.duration) else 0.0
            placements += SequencePlacement(
                part = part,
                start = cursor,
                fadeIn = if (loop || i > 0) previousOverlap else 0.0,
                fadeOut = nextOverlap
            )
            cursor += sequenceStep(part.duration, nextOverlap)
            previousOverlap = nextOverlap
        }
        if (loop && placements.isNotEmpty()) {
            placements[0] = placements[0].copy(fadeIn = previousOverlap)
        }
        val finiteEnd = placements.lastOrNull()?.let { it.start + it.part.duration } ?: 0.0
        return placements to if (loop) cursor.coerceAtLeast(1e-9) else finiteEnd
    }

    private fun sampleSequencePlacements(
        placements: List<SequencePlacement>,
        x: Double,
        edge: EdgeMode,
        outside: Double
    ): Double {
        var value = 0.0
        var active = false
        for (placement in placements) {
            val sample = placement.sample(x, edge, outside) ?: continue
            value += sample
            active = true
        }
        return if (active) value else outside
    }

    private fun permutationOrder(size: Int, seed: Long, cycle: Long = 0L): List<Int> =
        (0 until size).sortedWith(compareBy<Int> { hashToUnit(seed + cycle * 0x9E3779B97F4A7C15uL.toLong(), it.toLong()) }.thenBy { it })

    private fun sequenceLoopValue(
        parts: List<SequencePart>,
        seed: Long?,
        x: Double,
        overlap: Fn,
        edge: EdgeMode,
        outside: Double
    ): Double {
        if (x < 0.0 || parts.isEmpty()) return outside
        val orderedForPeriod = parts
        val (_, period) = sequencePlacements(orderedForPeriod, overlap, loop = true)
        val cycle = floor(x / period).toLong()
        var value = 0.0
        var active = false
        for (offset in -1L..1L) {
            val c = cycle + offset
            val ordered = if (seed == null) parts else permutationOrder(parts.size, seed, c).map { parts[it] }
            val cycleStart = c * period
            val (placements, _) = sequencePlacements(ordered, overlap, loop = true, startOffset = cycleStart)
            for (placement in placements) {
                val shifted = placement.copy(start = placement.start + cycleStart)
                val sample = shifted.sample(x, edge, outside) ?: continue
                value += sample
                active = true
            }
        }
        return if (active) value else outside
    }

    private fun randomChoice(size: Int, seed: Long, slot: Long): Int =
        floor(hashToUnit(seed, slot) * size.toDouble()).toInt().coerceIn(0, size - 1)

    private fun weightedChoice(weights: DoubleArray, total: Double, seed: Long, slot: Long): Int {
        val target = hashToUnit(seed, slot) * total
        var acc = 0.0
        for (i in weights.indices) {
            acc += weights[i]
            if (target <= acc) return i
        }
        return weights.lastIndex
    }

    private fun randomSlotSequenceValue(
        parts: List<SequencePart>,
        seed: Long,
        x: Double,
        slot: Fn,
        overlap: Fn,
        edge: EdgeMode,
        outside: Double,
        weights: DoubleArray? = null,
        totalWeight: Double = 0.0
    ): Double {
        if (x < 0.0 || parts.isEmpty()) return outside
        val slotDuration = slot.paramAt(x, parts.maxOf { it.duration }).coerceAtLeast(1e-9)
        val localOverlap = sequenceOverlapAt(overlap, x, slotDuration)
        val step = sequenceStep(slotDuration, localOverlap)
        val slotIndex = floor(x / step).toLong()
        var value = 0.0
        var active = false
        for (i in (slotIndex - 1)..(slotIndex + 1)) {
            if (i < 0L) continue
            val choice = if (weights == null) randomChoice(parts.size, seed, i) else weightedChoice(weights, totalWeight, seed, i)
            val placement = SequencePlacement(
                part = parts[choice].copy(duration = slotDuration),
                start = i * step,
                fadeIn = if (i > 0L) localOverlap else 0.0,
                fadeOut = localOverlap
            )
            val sample = placement.sample(x, edge, outside) ?: continue
            value += sample
            active = true
        }
        return if (active) value else outside
    }

    /**
     * Places each function once in list order. A positive [overlap] turns hard
     * joins into additive crossfades at the joins.
     */
    fun List<Fn>.sequence(
        overlap: Number = 0.0,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "sequence"
    ): Fn = sequence(param(overlap), edge, outside, name)

    /**
     * Places each function once, in list order, with a signal-valued [overlap] so each join can be crossfaded
     * by a different amount — the overlap in force at a join is sampled around that join.
     *
     * Each function gets a slot as long as its own domain (or `1.0` if that is empty or unbounded) and is
     * time-scaled into it. The result **always starts at x = 0**, whatever the sources' own domains were.
     * Overlapping joins are additive under a smootherstep fade, so two overlapping slots sum where they meet.
     * An empty list gives a zero function over an empty domain.
     */
    fun List<Fn>.sequence(
        overlap: Fn,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "sequence"
    ): Fn {
        val parts = sequenceParts()
        if (parts.isEmpty()) return zero(Domain.EMPTY).withName(name)
        val (placements, end) = sequencePlacements(parts, overlap, loop = false)
        val domain = Domain.of(0.0, end)
        return graphFn(
            domain,
            name,
            "sequence",
            children = itemEdges(this) + fnEdge("overlap", overlap),
            numbers = mapOf("items" to size.toDouble(), "outside" to outside),
            strings = mapOf("edge" to edge.name),
            rebuild = { children -> itemChildren(children, size).sequence(childAt(children, size, overlap), edge, outside, name) }
        ) { x -> sampleSequencePlacements(placements, x, edge, outside) }
    }

    /**
     * Plays the list in order and then **repeats forever**, wrapping the last join back onto the first item so
     * the loop is seamless when [overlap] is positive.
     *
     * Slot durations come from each function's own domain as in [sequence], and the result starts at x = 0 with
     * an unbounded domain by default — pass a finite `domain` if you want it bounded. Below the start it reads
     * [outside]. An empty list gives a zero function over an empty domain.
     */
    fun List<Fn>.sequenceLoop(
        overlap: Number = 0.0,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "sequenceLoop"
    ): Fn = sequenceLoop(param(overlap), domain, edge, outside, name)

    /**
     * Plays the list in order and repeats forever, wrapping the final join onto the first item, with a
     * signal-valued [overlap] sampled around each join. Unbounded above by default; below the start it reads
     * [outside]. An empty list gives a zero function over an empty domain.
     */
    fun List<Fn>.sequenceLoop(
        overlap: Fn,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "sequenceLoop"
    ): Fn {
        val parts = sequenceParts()
        if (parts.isEmpty()) return zero(Domain.EMPTY).withName(name)
        return graphFn(
            domain,
            name,
            "sequenceLoop",
            children = itemEdges(this) + fnEdge("overlap", overlap),
            numbers = mapOf("items" to size.toDouble(), "outside" to outside),
            strings = mapOf("edge" to edge.name),
            rebuild = { children -> itemChildren(children, size).sequenceLoop(childAt(children, size, overlap), domain, edge, outside, name) }
        ) { x -> sequenceLoopValue(parts, seed = null, x = x, overlap = overlap, edge = edge, outside = outside) }
    }

    /**
     * Plays every function exactly once, in an order shuffled from [seed] — a permutation, so nothing is
     * repeated and nothing dropped.
     *
     * Deterministic: the same seed and the same list always give the same order. Otherwise identical to
     * [sequence], including the slot durations and the additive crossfades at the joins. [shuffle] is the same
     * function under a friendlier name; [randomPermuteLoop] re-shuffles on every pass instead of stopping after
     * one.
     */
    fun List<Fn>.randomPermute(
        seed: Long = 0L,
        overlap: Number = 0.0,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "randomPermute"
    ): Fn = randomPermute(seed, param(overlap), edge, outside, name)

    /**
     * Plays every function exactly once in an order shuffled deterministically from [seed], with a
     * signal-valued [overlap] sampled around each join. A permutation — nothing repeated, nothing dropped.
     */
    fun List<Fn>.randomPermute(
        seed: Long = 0L,
        overlap: Fn,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "randomPermute"
    ): Fn {
        val parts = sequenceParts()
        if (parts.isEmpty()) return zero(Domain.EMPTY).withName(name)
        val ordered = permutationOrder(parts.size, seed).map { parts[it].fn }
        val sequenced = ordered.sequence(overlap, edge, outside, name)
        return graphFn(
            sequenced.domain,
            name,
            "randomPermute",
            children = itemEdges(this) + fnEdge("overlap", overlap),
            numbers = mapOf("items" to size.toDouble(), "outside" to outside),
            strings = mapOf("edge" to edge.name),
            longs = mapOf("seed" to seed),
            rebuild = { children -> itemChildren(children, size).randomPermute(seed, childAt(children, size, overlap), edge, outside, name) }
        ) { x -> sequenced.eval(x) }
    }

    /**
     * Loops forever, **re-shuffling the order on every pass** — so each cycle contains all the material exactly
     * once, but in a different arrangement.
     *
     * The permutation for cycle *n* is derived from [seed] and *n*, so the whole infinite stream is reproducible.
     * The cycle length is fixed (computed from the unshuffled slots), which keeps the loop period stable while
     * the contents move around. Reads [outside] below the start; an empty list gives a zero function over an
     * empty domain.
     */
    fun List<Fn>.randomPermuteLoop(
        seed: Long = 0L,
        overlap: Number = 0.0,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "randomPermuteLoop"
    ): Fn = randomPermuteLoop(seed, param(overlap), domain, edge, outside, name)

    /**
     * Loops forever, re-shuffling the order on every pass from [seed] and the cycle number, with a signal-valued
     * [overlap] sampled around each join. Every cycle contains all the material once; the cycle length stays
     * fixed as the contents are reordered.
     */
    fun List<Fn>.randomPermuteLoop(
        seed: Long = 0L,
        overlap: Fn,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "randomPermuteLoop"
    ): Fn {
        val parts = sequenceParts()
        if (parts.isEmpty()) return zero(Domain.EMPTY).withName(name)
        return graphFn(
            domain,
            name,
            "randomPermuteLoop",
            children = itemEdges(this) + fnEdge("overlap", overlap),
            numbers = mapOf("items" to size.toDouble(), "outside" to outside),
            strings = mapOf("edge" to edge.name),
            longs = mapOf("seed" to seed),
            rebuild = { children -> itemChildren(children, size).randomPermuteLoop(seed, childAt(children, size, overlap), domain, edge, outside, name) }
        ) { x -> sequenceLoopValue(parts, seed = seed, x = x, overlap = overlap, edge = edge, outside = outside) }
    }

    /**
     * Fills an endless run of **fixed-length slots**, choosing an item at random for each one — so items repeat,
     * items get skipped, and the rhythm stays regular.
     *
     * This is the difference from [randomPermuteLoop]: there the material is reordered but each pass is complete,
     * here every slot is an independent draw. [slot] is the slot length in x units, defaulting to the longest
     * item's duration, and every chosen item is time-scaled to fit it. The choice for slot *n* is hashed from
     * [seed] and *n*, so the stream is reproducible and randomly seekable. Reads [outside] below the start.
     */
    fun List<Fn>.randomSequence(
        seed: Long = 0L,
        overlap: Number = 0.0,
        slot: Number? = null,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "randomSequence"
    ): Fn = randomSequence(seed, param(overlap), slot?.let { param(it) }, domain, edge, outside, name)

    /**
     * Fills an endless run of slots, choosing an item at random for each, with a signal-valued [slot] length —
     * so the pulse of the sequence can itself be modulated. Items repeat and are skipped freely; each choice is
     * hashed from [seed] and the slot index, and each chosen item is time-scaled to the slot.
     */
    fun List<Fn>.randomSequence(
        seed: Long = 0L,
        overlap: Number = 0.0,
        slot: Fn,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "randomSequence"
    ): Fn = randomSequence(seed, param(overlap), slot, domain, edge, outside, name)

    /**
     * Fills an endless run of fixed-length slots, choosing an item at random for each, with a signal-valued
     * [overlap] sampled around each join. Items repeat and are skipped freely; every chosen item is time-scaled
     * to the slot length.
     */
    fun List<Fn>.randomSequence(
        seed: Long = 0L,
        overlap: Fn,
        slot: Number,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "randomSequence"
    ): Fn = randomSequence(seed, overlap, param(slot), domain, edge, outside, name)

    /**
     * Fills an endless run of slots, choosing an item at random for each, with both [overlap] and [slot] length
     * as signals. [slot] defaults to the longest item's duration; each chosen item is time-scaled to fit, and
     * each choice is hashed from [seed] and the slot index so the stream is reproducible.
     */
    fun List<Fn>.randomSequence(
        seed: Long = 0L,
        overlap: Fn,
        slot: Fn? = null,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "randomSequence"
    ): Fn {
        val parts = sequenceParts()
        if (parts.isEmpty()) return zero(Domain.EMPTY).withName(name)
        val slotFn = slot ?: param(parts.maxOf { it.duration })
        return graphFn(
            domain,
            name,
            "randomSequence",
            children = itemEdges(this) + listOf(fnEdge("overlap", overlap), fnEdge("slot", slotFn)),
            numbers = mapOf("items" to size.toDouble(), "outside" to outside),
            strings = mapOf("edge" to edge.name),
            longs = mapOf("seed" to seed),
            rebuild = { children ->
                itemChildren(children, size).randomSequence(
                    seed,
                    childAt(children, size, overlap),
                    childAt(children, size + 1, slotFn),
                    domain,
                    edge,
                    outside,
                    name
                )
            }
        ) { x ->
            randomSlotSequenceValue(parts, seed, x, slotFn, overlap, edge, outside)
        }
    }

    /**
     * [randomSequence] with a thumb on the scale: each item's chance of being drawn is proportional to its
     * weight.
     *
     * Weights line up positionally with the list, may be plain numbers or signals (sampled at the x being
     * evaluated, so the odds can drift over the piece), and are relative — they need not sum to one. A weight
     * that is missing, non-finite or not positive counts as zero, and **if every weight is zero the draw falls
     * back to a uniform choice** rather than going silent.
     */
    fun List<Fn>.weightedSequence(
        weights: List<Any>,
        seed: Long = 0L,
        overlap: Number = 0.0,
        slot: Number? = null,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "weightedSequence"
    ): Fn = weightedSequence(weights, seed, param(overlap), slot?.let { param(it) }, domain, edge, outside, name)

    /**
     * [randomSequence] with per-item weights deciding the odds, and a signal-valued [overlap]. Weights are
     * positional and relative, may be numbers or signals, and count as zero when missing or not positive; if all
     * of them are zero the draw falls back to a uniform choice.
     */
    fun List<Fn>.weightedSequence(
        weights: List<Any>,
        seed: Long = 0L,
        overlap: Fn,
        slot: Fn? = null,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "weightedSequence"
    ): Fn {
        val parts = sequenceParts()
        if (parts.isEmpty()) return zero(Domain.EMPTY).withName(name)
        val weightFns = Array(parts.size) { i ->
            when (val w = weights.getOrNull(i)) {
                is Fn -> w
                is Number -> param(w.toDouble())
                else -> param(0.0)
            }
        }
        val slotFn = slot ?: param(parts.maxOf { it.duration })
        return graphFn(
            domain,
            name,
            "weightedSequence",
            children = itemEdges(this) + listOf(fnEdge("overlap", overlap), fnEdge("slot", slotFn)) + weightFns.mapIndexed { i, fn -> fnEdge("weight$i", fn) },
            numbers = mapOf("items" to size.toDouble(), "outside" to outside),
            strings = mapOf("edge" to edge.name),
            longs = mapOf("seed" to seed),
            rebuild = { children ->
                val rebuiltWeights = children.drop(size + 2).take(size)
                itemChildren(children, size).weightedSequence(
                    rebuiltWeights,
                    seed,
                    childAt(children, size, overlap),
                    childAt(children, size + 1, slotFn),
                    domain,
                    edge,
                    outside,
                    name
                )
            }
        ) { x ->
            val localWeights = DoubleArray(parts.size) { i -> weightFns[i].paramAt(x).takeIf { it.isFinite() && it > 0.0 } ?: 0.0 }
            val localTotal = localWeights.sum()
            if (localTotal <= 0.0) {
                randomSlotSequenceValue(parts, seed, x, slotFn, overlap, edge, outside)
            } else {
                randomSlotSequenceValue(parts, seed, x, slotFn, overlap, edge, outside, localWeights, localTotal)
            }
        }
    }

    /**
     * [randomSequence] with per-item weights deciding the odds. Weights line up positionally with the list and
     * are relative rather than normalized; a weight of zero (or a missing one) excludes that item, and if every
     * weight is zero the draw falls back to a uniform choice.
     */
    fun List<Fn>.weightedSequence(
        weights: DoubleArray,
        seed: Long = 0L,
        overlap: Number = 0.0,
        slot: Number? = null,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "weightedSequence"
    ): Fn = weightedSequence(weights.toList(), seed, overlap, slot, domain, edge, outside, name)

    /**
     * [randomSequence] with per-item weights deciding the odds, and a signal-valued [overlap]. Weights are
     * positional and relative; a zero or missing weight excludes that item, and if all are zero the draw falls
     * back to a uniform choice.
     */
    fun List<Fn>.weightedSequence(
        weights: DoubleArray,
        seed: Long = 0L,
        overlap: Fn,
        slot: Fn? = null,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "weightedSequence"
    ): Fn = weightedSequence(weights.toList(), seed, overlap, slot, domain, edge, outside, name)

    /**
     * Plays every function once in a shuffled order — the friendlier name for [randomPermute], with identical
     * behaviour. Deterministic from [seed], and note that it shuffles *once*: use [shuffleLoop] to keep going.
     */
    fun List<Fn>.shuffle(
        seed: Long = 0L,
        overlap: Number = 0.0,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "shuffle"
    ): Fn = shuffle(seed, param(overlap), edge, outside, name)

    /**
     * Plays every function once in a shuffled order, with a signal-valued [overlap] — an alias of
     * [randomPermute] with identical behaviour.
     */
    fun List<Fn>.shuffle(
        seed: Long = 0L,
        overlap: Fn,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "shuffle"
    ): Fn = randomPermute(seed, overlap, edge, outside, name)

    /**
     * Loops forever, re-shuffling on every pass — the friendlier name for [randomPermuteLoop], with identical
     * behaviour. Each cycle plays all the material once, in a fresh order derived from [seed] and the cycle
     * number.
     */
    fun List<Fn>.shuffleLoop(
        seed: Long = 0L,
        overlap: Number = 0.0,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "shuffleLoop"
    ): Fn = shuffleLoop(seed, param(overlap), domain, edge, outside, name)

    /**
     * Loops forever, re-shuffling on every pass, with a signal-valued [overlap] — an alias of
     * [randomPermuteLoop] with identical behaviour.
     */
    fun List<Fn>.shuffleLoop(
        seed: Long = 0L,
        overlap: Fn,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "shuffleLoop"
    ): Fn = randomPermuteLoop(seed, overlap, domain, edge, outside, name)

    /**
     * Plays the list forwards and then backwards, once — an out-and-back gesture rather than a loop.
     *
     * By default the turning point is *not* repeated: `[a, b, c]` plays `a b c b a`, since playing `c` twice in a row
     * at the top of the arc rarely reads as intended. Pass `includeEndsTwice = true` for `a b c c b a`. Either way the
     * gesture ends where it started, so an out-and-back of n items is 2n-1 slots long (2n with the ends doubled).
     * Lists of one or two items always double their ends, there being no interior to drop. The result is a
     * [sequence], so the same slot durations and additive crossfades apply.
     */
    fun List<Fn>.palindrome(
        overlap: Number = 0.0,
        includeEndsTwice: Boolean = false,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "palindrome"
    ): Fn = palindrome(param(overlap), includeEndsTwice, edge, outside, name)

    /**
     * Plays the list forwards then backwards once, with a signal-valued [overlap]. The turning point — the last item —
     * is not repeated unless [includeEndsTwice] is set, so `[a, b, c]` gives `a b c b a`, and lists of one or two items
     * always double their ends.
     */
    fun List<Fn>.palindrome(
        overlap: Fn,
        includeEndsTwice: Boolean = false,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "palindrome"
    ): Fn {
        if (isEmpty()) return zero(Domain.EMPTY).withName(name)
        // Drop only the turning point — the last item, which the return leg would otherwise repeat
        // immediately — and keep the first, so the gesture actually comes home: `[a, b, c]` -> a b c b a.
        // `dropLast(1).drop(1).asReversed()` dropped both ends and stopped a step short (a b c b).
        val tail = if (includeEndsTwice || size <= 2) asReversed() else asReversed().drop(1)
        val sequenced = (this + tail).sequence(overlap, edge, outside, name)
        return graphFn(
            sequenced.domain,
            name,
            "palindrome",
            children = itemEdges(this) + fnEdge("overlap", overlap),
            numbers = mapOf("items" to size.toDouble(), "outside" to outside),
            strings = mapOf("edge" to edge.name),
            booleans = mapOf("includeEndsTwice" to includeEndsTwice),
            rebuild = { children -> itemChildren(children, size).palindrome(childAt(children, size, overlap), includeEndsTwice, edge, outside, name) }
        ) { x -> sequenced.eval(x) }
    }

    /**
     * Cycles through the list **in order**, one item per fixed-length [slot], forever — the deterministic
     * counterpart to [randomSequence].
     *
     * Unlike [sequenceLoop], every item gets the same slot length regardless of its own domain, and is
     * time-scaled to fit; unlike [randomSequence], the order is strict rotation. [slot] is in x units. The cycle
     * starts at the domain's lower bound, and anything before that reads [outside].
     */
    fun List<Fn>.roundRobin(
        slot: Number,
        overlap: Number = 0.0,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "roundRobin"
    ): Fn = roundRobin(param(slot), param(overlap), domain, edge, outside, name)

    /**
     * Cycles through the list in order, one item per [slot], forever, with [slot] and [overlap] as signals — so
     * the rotation's pulse can itself be modulated. Every item is time-scaled to the current slot length;
     * anything before the domain's lower bound reads [outside].
     */
    fun List<Fn>.roundRobin(
        slot: Fn,
        overlap: Fn = param(0.0),
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "roundRobin"
    ): Fn {
        val parts = sequenceParts()
        if (parts.isEmpty()) return zero(Domain.EMPTY).withName(name)
        return graphFn(
            domain,
            name,
            "roundRobin",
            children = itemEdges(this) + listOf(fnEdge("slot", slot), fnEdge("overlap", overlap)),
            numbers = mapOf("items" to size.toDouble(), "outside" to outside),
            strings = mapOf("edge" to edge.name),
            rebuild = { children ->
                itemChildren(children, size).roundRobin(
                    childAt(children, size, slot),
                    childAt(children, size + 1, overlap),
                    domain,
                    edge,
                    outside,
                    name
                )
            }
        ) { x ->
            if (x < domain.min) return@graphFn outside
            val slotDuration = slot.paramAt(x, parts.maxOf { it.duration }).coerceAtLeast(1e-9)
            val localOverlap = sequenceOverlapAt(overlap, x, slotDuration)
            val step = sequenceStep(slotDuration, localOverlap)
            val base = x - domain.min
            val slotIndex = floor(base / step).toLong()
            var value = 0.0
            var active = false
            for (i in (slotIndex - 1)..(slotIndex + 1)) {
                if (i < 0L) continue
                val index = ((i % parts.size) + parts.size) % parts.size
                val placement = SequencePlacement(
                    part = parts[index.toInt()].copy(duration = slotDuration),
                    start = domain.min + i * step,
                    fadeIn = if (i > 0L) localOverlap else 0.0,
                    fadeOut = localOverlap
                )
                val sample = placement.sample(x, edge, outside) ?: continue
                value += sample
                active = true
            }
            if (active) value else outside
        }
    }

    /**
     * Sums every function in the list at each x — layering rather than sequencing, and the list form of `a + b + c`.
     *
     * The domain is the **union** of the members' domains, so a member that is silent (or rather, absent) over part
     * of that span contributes [outside] there under the given [edge] mode, not its extrapolated value. An empty
     * list gives a zero function over an empty domain. Beware the obvious: stacking many signals of amplitude one
     * gives a signal of amplitude many — see [average] if you want the level kept.
     */
    fun List<Fn>.stack(
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "stack"
    ): Fn {
        if (isEmpty()) return zero(Domain.EMPTY).withName(name)
        val d = fold(Domain.EMPTY) { acc, fn -> acc.union(fn.domain) }
        return graphFn(
            d,
            name,
            "stack",
            children = itemEdges(this),
            numbers = mapOf("items" to size.toDouble(), "outside" to outside),
            strings = mapOf("edge" to edge.name),
            rebuild = { children -> itemChildren(children, size).stack(edge, outside, name) }
        ) { x -> sumOf { it.evalSafe(x, outside = outside, edge = edge) } }
    }

    /**
     * The mean of every function in the list at each x — [stack] divided by the list size, so layering many signals
     * keeps the level of one.
     *
     * The divisor is the **whole list size**, not the number of members actually defined at that x, so over a span
     * where only some members reach the average is correspondingly quieter rather than renormalized. An empty list
     * gives a zero function over an empty domain.
     */
    fun List<Fn>.average(
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "average"
    ): Fn {
        if (isEmpty()) return zero(Domain.EMPTY).withName(name)
        val stacked = stack(edge, outside, name)
        return graphFn(
            stacked.domain,
            name,
            "average",
            children = itemEdges(this),
            numbers = mapOf("items" to size.toDouble(), "outside" to outside),
            strings = mapOf("edge" to edge.name),
            rebuild = { children -> itemChildren(children, size).average(edge, outside, name) }
        ) { x -> stacked.eval(x) / size.toDouble() }
    }

    /**
     * Switches between the functions: at each x, [selector] names an index and that member alone is heard.
     *
     * The selector's value is **rounded** to the nearest whole number and clamped to the list, so it never goes out
     * of range — but it also switches abruptly, with an audible step wherever it crosses a half-integer. Use [morph]
     * for the crossfading version. The domain is the union of the members' and the selector's, and members are read
     * with [outside] / [edge] outside their own.
     */
    fun List<Fn>.select(
        selector: Fn,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "select"
    ): Fn {
        if (isEmpty()) return zero(Domain.EMPTY).withName(name)
        val d = fold(selector.domain) { acc, fn -> acc.union(fn.domain) }
        return graphFn(
            d,
            name,
            "select",
            children = itemEdges(this) + fnEdge("selector", selector),
            numbers = mapOf("items" to size.toDouble(), "outside" to outside),
            strings = mapOf("edge" to edge.name),
            rebuild = { children -> itemChildren(children, size).select(childAt(children, size, selector), edge, outside, name) }
        ) { x ->
            val index = selector.eval(x).roundToInt().coerceIn(0, lastIndex)
            this[index].evalSafe(x, outside = outside, edge = edge)
        }
    }

    /**
     * Crossfades along the list: [selector] gives a *fractional* index, and the two neighbouring members are blended
     * by its fractional part with a smootherstep ease — the continuous counterpart to [select].
     *
     * The selector is clamped to `0 .. lastIndex`, so it cannot run off either end, and a selector of exactly 2.0
     * gives member two untouched. A single-member list is returned as-is. The domain is the union of the members' and
     * the selector's.
     */
    fun List<Fn>.morph(
        selector: Fn,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "morph"
    ): Fn {
        if (isEmpty()) return zero(Domain.EMPTY).withName(name)
        if (size == 1) return first().withName(name)
        val d = fold(selector.domain) { acc, fn -> acc.union(fn.domain) }
        return graphFn(
            d,
            name,
            "morph",
            children = itemEdges(this) + fnEdge("selector", selector),
            numbers = mapOf("items" to size.toDouble(), "outside" to outside),
            strings = mapOf("edge" to edge.name),
            rebuild = { children -> itemChildren(children, size).morph(childAt(children, size, selector), edge, outside, name) }
        ) { x ->
            val pos = selector.eval(x).coerceIn(0.0, lastIndex.toDouble())
            val i0 = floor(pos).toInt().coerceIn(0, lastIndex)
            val i1 = (i0 + 1).coerceIn(0, lastIndex)
            val t = smootherstep(pos - i0.toDouble())
            blend(
                this[i0].evalSafe(x, outside = outside, edge = edge),
                this[i1].evalSafe(x, outside = outside, edge = edge),
                t
            )
        }
    }

    /**
     * A **reusable** function-to-function operation: a transform you can name, hold on to, compose with others,
     * and then apply to any number of sources.
     *
     * The difference from calling an `Fn` extension directly is only reuse — `gain(0.5)` builds a transform, while
     * `f.scaleY(0.5)` applies one on the spot. Transforms chain with [then] or `/` and are applied with `f / t`,
     * `f through t` or `t(f)`. Nothing is evaluated when a transform is built or composed; the work happens when
     * it meets a source, and even then only lazily, when the resulting function is sampled.
     */
    data class FnTransform(
        val name: String,
        private val applyFn: (Fn) -> Fn
    ) {
        /** Applies this transform to [source], returning the new function. `t(f)` — the prefix spelling of `f / t`. */
        operator fun invoke(source: Fn): Fn = applyFn(source)

        /**
         * Composes left-to-right: `a then b` applies `a` first, then `b` to its result — reading order, not
         * mathematical composition order. The names concatenate, so the pipeline is legible in a plot title.
         */
        infix fun then(next: FnTransform): FnTransform =
            FnTransform("$name then ${next.name}") { source -> next(this(source)) }

        /** Composes left-to-right, exactly [then], so a pipeline can be written `a / b / c`. */
        operator fun div(next: FnTransform): FnTransform = then(next)
    }

    /** Relabels the function's domain to `domain` without touching its values, exactly `withDomain`. To *rescale* the function into a new interval use `stretch`. */
    infix fun Fn.over(domain: Domain): Fn = withDomain(domain)

    /** `f over (0.0..2.0)` — relabels the function's domain without touching its values, exactly `withDomain`. To *rescale* the function into a new interval use `stretch`. */
    infix fun Fn.over(range: ClosedRange<*>): Fn =
        over(range.toDomain())

    /** `f stretch (0.0..2.0)` — replays this function's whole current domain across the target interval, rescaling time rather than relabelling it. Requires both domains to be finite. */
    infix fun Fn.stretch(domain: Domain): Fn =
        stretch(domain, from = this.domain)

    /** `f stretch (0.0..2.0)` — replays this function's whole current domain across the target interval, rescaling time rather than relabelling it. Requires both domains to be finite. */
    infix fun Fn.stretch(range: ClosedRange<*>): Fn =
        stretch(range.toDomain())

    /** `f through gain(0.5)` — applies an [FnTransform], for when the infix word reads better than the slash. */
    infix fun Fn.through(transform: FnTransform): Fn = transform(this)

    /** `f pipe gain(0.5)` — applies an [FnTransform]; a synonym of [through]. */
    infix fun Fn.pipe(transform: FnTransform): Fn = transform(this)

    /**
     * `f / gain(0.5)` — applies an [FnTransform] to this function.
     *
     * The house spelling for a transform pipeline. Do not confuse it with `f / g`, dividing one function by
     * another: the right-hand type decides which operator you get, so a transform on the right is application and a
     * function on the right is arithmetic.
     */
    operator fun Fn.div(transform: FnTransform): Fn = transform(this)

    /** A transform that widens a function's domain to the whole real line, so it stops constraining anything it is combined with. Values are untouched. */
    val all: FnTransform = FnTransform("all") { source ->
        source.withDomain(Domain.ALL)
    }

    /**
     * A reusable transform that replays a source's whole domain across [to], rescaling time — the [FnTransform] form of
     * `Fn.stretch`.
     *
     * [from] defaults to whatever domain the source turns out to have, which is what makes one transform applicable to
     * several sources. [shape] paces the traversal: `LINEAR` at constant speed, the smoothstep variants easing in and out.
     * Both domains must be finite — this throws for an unbounded source, so widen or bound it first.
     */
    fun stretch(to: Domain, from: Domain? = null, shape: StretchShape = StretchShape.LINEAR): FnTransform =
        FnTransform("stretch") { source ->
            source.stretch(to, from = from ?: source.domain, shape = shape)
        }

    /** A reusable transform replaying a source's domain across the given range, rescaling time. Both domains must be finite or it throws. */
    fun stretch(to: ClosedRange<*>, from: Domain? = null, shape: StretchShape = StretchShape.LINEAR): FnTransform =
        stretch(to.toDomain(), from, shape)

    /** [stretch] with a smoothstep pacing: the endpoints still line up, but the source is played slowly at the ends and quickly through the middle. */
    fun stretchSmooth(to: Domain, from: Domain? = null): FnTransform =
        stretch(to, from, StretchShape.SMOOTHSTEP)

    /** [stretch] with a smoothstep pacing: the endpoints still line up, but the source is played slowly at the ends and quickly through the middle. */
    fun stretchSmooth(to: ClosedRange<*>, from: Domain? = null): FnTransform =
        stretchSmooth(to.toDomain(), from)

    /** [stretch] with a smootherstep pacing — like [stretchSmooth] but with zero acceleration as well as zero velocity at the ends. */
    fun stretchSmoother(to: Domain, from: Domain? = null): FnTransform =
        stretch(to, from, StretchShape.SMOOTHERSTEP)

    /** [stretch] with a smootherstep pacing — like [stretchSmooth] but with zero acceleration as well as zero velocity at the ends. */
    fun stretchSmoother(to: ClosedRange<*>, from: Domain? = null): FnTransform =
        stretchSmoother(to.toDomain(), from)

    /**
     * [stretch] with the pacing given by a function rather than a named shape: [warp] maps normalized progress through the
     * target onto normalized progress through the source.
     *
     * So `warp` is read on 0..1 and should return 0..1 — an identity ramp reproduces a plain linear stretch, and anything
     * monotonic gives an arbitrary time-warp. It is evaluated with **clamped** edges, so a warp defined over less than
     * 0..1 holds its nearest endpoint value for the rest of the traversal (which stalls the source there) rather than
     * reverting to un-warped progress. Nothing forces it to be monotonic either: a warp that goes backwards will play
     * the source backwards for a while.
     */
    fun stretchBy(to: Domain, warp: Fn, from: Domain? = null): FnTransform =
        FnTransform("stretchBy") { source ->
            source.stretchBy(to, warp, from = from ?: source.domain)
        }

    /** [stretch] onto the given range with the pacing given by [warp], a function read on 0..1 mapping target progress to source progress. */
    fun stretchBy(to: ClosedRange<*>, warp: Fn, from: Domain? = null): FnTransform =
        stretchBy(to.toDomain(), warp, from)

    /** A reusable transform that delays a source by [amount], positive being later. A constant delay carries the domain with it. */
    fun delay(amount: Number): FnTransform = delay(param(amount))

    /** A reusable transform delaying a source by [amount]; a signal-valued amount makes it a time warp rather than a delay, and leaves the domain alone. */
    fun delay(amount: Fn): FnTransform = FnTransform("delay") { source ->
        source.delay(amount)
    }

    /** A reusable transform scaling a source's values by [amount] — `f / gain(0.5)` is `f * 0.5`. */
    fun gain(amount: Number): FnTransform = gain(param(amount))

    /** A reusable transform scaling a source's values by [amount]; a signal-valued amount is an amplitude envelope. Note that a *bounded* amount will crop the source's domain. */
    fun gain(amount: Fn): FnTransform = FnTransform("gain") { source ->
        source * amount
    }

    /** A reusable transform adding [amount] to a source's values — a DC offset. */
    fun bias(amount: Number): FnTransform = bias(param(amount))

    /** A reusable transform adding [amount] to a source's values. A signal-valued amount is a drifting offset; a bounded one will crop the source's domain. */
    fun bias(amount: Fn): FnTransform = FnTransform("bias") { source ->
        source + amount
    }

    /** A reusable transform hard-clipping a source symmetrically to `±threshold`. Named to avoid colliding with `Fn.clip`. */
    fun clipAt(threshold: Number): FnTransform = clipAt(param(threshold))

    /** A reusable transform hard-clipping a source symmetrically to `±threshold`, which may itself be a signal. */
    fun clipAt(threshold: Fn): FnTransform = FnTransform("clip") { source ->
        source.clip(threshold)
    }

    /** A reusable transform saturating a source through a normalized `tanh` curve; [amount] is drive, not a ceiling. Named to avoid colliding with `Fn.softClip`. */
    fun softClipBy(amount: Number): FnTransform = softClipBy(param(amount))

    /** A reusable transform saturating a source through a normalized `tanh` curve, with a possibly signal-valued drive [amount]. */
    fun softClipBy(amount: Fn): FnTransform = FnTransform("softClip") { source ->
        source.softClip(amount)
    }

    /** A reusable sample-and-hold: reads the source only every [step] and holds each value until the next. Stateless, so it stays freely sampleable. */
    fun hold(step: Number): FnTransform = hold(param(step))

    /** A reusable sample-and-hold on a grid of [step], which may itself be a signal. Stateless, so it stays freely sampleable. */
    fun hold(step: Fn): FnTransform = FnTransform("hold") { source ->
        source.sampleHold(step)
    }

    /**
     * Time-scale transforms use musical language: `faster(2.0)` samples the
     * source twice as quickly, while `slower(2.0)` stretches it by two.
     */
    fun faster(factor: Number = 2.0, about: Number = 0.0): FnTransform =
        faster(param(factor), param(about))

    fun faster(factor: Fn, about: Fn = param(0.0)): FnTransform =
        FnTransform("faster") { source ->
            source.faster(factor, about)
        }

    fun slower(factor: Number = 2.0, about: Number = 0.0): FnTransform =
        slower(param(factor), param(about))

    fun slower(factor: Fn, about: Fn = param(0.0)): FnTransform =
        FnTransform("slower") { source ->
            source.slower(factor, about)
        }

    fun named(name: String): FnTransform = FnTransform("named($name)") { source ->
        source.withName(name)
    }

    fun lowPass(window: Number, samples: Int = 64): FnTransform = lowPass(param(window), samples)

    fun lowPass(window: Fn, samples: Int = 64): FnTransform =
        FnTransform("lowPass") { source ->
            source.movingAverage(window, samples).withName("lowPass")
        }

    fun highPass(window: Number, samples: Int = 64): FnTransform = highPass(param(window), samples)

    fun highPass(window: Fn, samples: Int = 64): FnTransform =
        FnTransform("highPass") { source ->
            (source - source.movingAverage(window, samples)).withName("highPass")
        }

    /**
     * A stateless phase color, not a recursive DSP all-pass. That distinction is
     * intentional here: an [Fn] should stay pure and freely sampleable.
     */
    fun allPass(delay: Number, feedback: Number = 0.5, repeats: Int = 3): FnTransform =
        allPass(param(delay), param(feedback), repeats)

    fun allPass(delay: Any, feedback: Any = 0.5, repeats: Int = 3): FnTransform =
        allPass(asParam(delay, "delay"), asParam(feedback, "feedback"), repeats)

    fun allPass(delay: Fn, feedback: Fn = param(0.5), repeats: Int = 3): FnTransform =
        FnTransform("allPass") { source ->
            val fb = feedback.mapY("allPassFeedback") { it.coerceIn(-0.99, 0.99) }
            var acc = source * (one(source.domain) - fb.abs())
            var gain = fb
            for (i in 1..max(1, repeats)) {
                acc += source.delay(delay * i.toDouble()) * gain
                gain *= fb * -1.0
            }
            acc.withName("allPass")
        }

    fun turbulence(amount: Number, frequency: Number = 8.0, octaves: Int = 4, seed: Long = 0L): FnTransform =
        turbulence(param(amount), param(frequency), octaves, seed)

    fun turbulence(amount: Number, frequency: Fn, octaves: Int = 4, seed: Long = 0L): FnTransform =
        turbulence(param(amount), frequency, octaves, seed)

    fun turbulence(amount: Fn, frequency: Number = 8.0, octaves: Int = 4, seed: Long = 0L): FnTransform =
        turbulence(amount, param(frequency), octaves, seed)

    fun turbulence(amount: Fn, frequency: Fn = param(8.0), octaves: Int = 4, seed: Long = 0L): FnTransform =
        FnTransform("turbulence") { source ->
            val mod = fbm(seed = seed, frequency = frequency, amplitude = param(1.0), octaves = octaves, domain = source.domain)
            source.warp(mod.abs(), amount).withName("turbulence")
        }

    /**
     * Stateless sample-and-hold driven by an external gate. Because this is a
     * pure function, it searches backward for the latest open gate rather than
     * storing latch state between calls.
     */
    fun gateHold(gate: Fn, step: Number, threshold: Number = 0.5, lookBack: Number = 1.0): FnTransform =
        gateHold(gate, param(step), param(threshold), param(lookBack))

    fun gateHold(gate: Fn, step: Any, threshold: Any = 0.5, lookBack: Any = 1.0): FnTransform =
        gateHold(gate, asParam(step, "step"), asParam(threshold, "threshold"), asParam(lookBack, "lookBack"))

    fun gateHold(gate: Fn, step: Fn, threshold: Fn = param(0.5), lookBack: Fn = param(1.0)): FnTransform =
        FnTransform("gateHold") { source ->
            val d = source.domain.intersect(gate.domain)
            graphFn(
                d,
                "gateHold",
                "gateHold",
                children = listOf(
                    fnEdge("source", source),
                    fnEdge("gate", gate),
                    fnEdge("step", step),
                    fnEdge("threshold", threshold),
                    fnEdge("lookBack", lookBack)
                ),
                rebuild = { children -> (childAt(children, 0, source) / gateHold(childAt(children, 1, gate), childAt(children, 2, step), childAt(children, 3, threshold), childAt(children, 4, lookBack))) }
            ) { x ->
                val safeStep = abs(step.paramAt(x, 1.0)).coerceAtLeast(1e-9)
                val safeLookBack = abs(lookBack.paramAt(x, 1.0))
                val gateThreshold = threshold.paramAt(x, 0.5)
                var t = x
                while (t >= d.min && x - t <= safeLookBack) {
                    if (gate.evalSafe(t) >= gateThreshold) return@graphFn source.evalSafe(t)
                    t -= safeStep
                }
                0.0
            }
        }

    fun crush(bits: Int): FnTransform = FnTransform("crush($bits)") { source ->
        source.bitCrush(bits).withName("crush")
    }

    /**
     * Waveshaping / distortion transforms — the value-domain (west-coast) counterparts
     * to the time-domain [hold] / [crush] pair. Each is a thin factory over a pure value
     * shaper (see [Fn.fold], [Fn.rectify], [Fn.abs], [Fn.quantize]) so it composes in
     * `through` / `pipe` / `/` chains and surfaces in the type-aware infix completion.
     * They shape the signal's VALUE, distinct from `hold`/`stepHold` which clock its time.
     */
    fun fold(threshold: Number = 1.0): FnTransform = fold(param(threshold))

    fun fold(threshold: Fn): FnTransform = FnTransform("fold") { source ->
        source.fold(threshold).withName("fold")
    }

    fun rectify(mode: RectifyMode = RectifyMode.FULL): FnTransform =
        FnTransform("rectify(${mode.name.lowercase()})") { source ->
            source.rectify(mode).withName("rectify")
        }

    fun quantize(levels: Int): FnTransform = FnTransform("quantize($levels)") { source ->
        source.quantize(levels).withName("quantize")
    }

    fun chebyshev(order: Int = 2): FnTransform = chebyshev(param(order))

    fun chebyshev(order: Number): FnTransform = chebyshev(param(order.toDouble()))

    fun chebyshev(order: Fn): FnTransform = FnTransform("chebyshev") { source ->
        source.chebyshev(order).withName("chebyshev")
    }

    fun copyPaste(from: Domain, to: Domain, stretch: Boolean = true, edge: EdgeMode = EdgeMode.CLAMP): FnTransform =
        FnTransform("copyPaste") { source ->
            val d = source.domain.union(to)
            graphFn(
                d,
                "copyPaste",
                "copyPaste",
                children = listOf(fnEdge("source", source)),
                strings = mapOf("from" to from.toString(), "to" to to.toString(), "edge" to edge.name),
                booleans = mapOf("stretch" to stretch),
                rebuild = { children -> (childAt(children, 0, source) / copyPaste(from, to, stretch, edge)) }
            ) { x ->
                if (!to.containsInclusive(x)) return@graphFn source.evalSafe(x, edge = edge)
                val sourceX = if (stretch) {
                    from.lerp(to.alphaOf(x).coerceIn(0.0, 1.0))
                } else {
                    from.min + (x - to.min)
                }
                source.evalSafe(sourceX, edge = edge)
            }
        }

    fun copyPaste(from: ClosedRange<*>, to: ClosedRange<*>, stretch: Boolean = true, edge: EdgeMode = EdgeMode.CLAMP): FnTransform =
        copyPaste(from.toDomain(), to.toDomain(), stretch, edge)

    fun mirror(about: Number? = null): FnTransform = FnTransform("mirror") { source ->
        val center = about?.toDouble() ?: if (source.domain.isFinite) source.domain.center else 0.0
        source.reverse(param(center)).withName("mirror")
    }

    fun mirror(about: Fn): FnTransform = FnTransform("mirror") { source ->
        source.reverse(about).withName("mirror")
    }

    fun repeatEvery(period: Number, origin: Number = 0.0, domain: Domain = Domain.ALL): FnTransform =
        repeatEvery(param(period), param(origin), domain)

    fun repeatEvery(period: Any, origin: Any = 0.0, domain: Domain = Domain.ALL): FnTransform =
        repeatEvery(asParam(period, "period"), asParam(origin, "origin"), domain)

    fun repeatEvery(period: Number, origin: Number = 0.0, domain: ClosedRange<*>): FnTransform =
        repeatEvery(param(period), param(origin), domain.toDomain())

    fun repeatEvery(period: Any, origin: Any = 0.0, domain: ClosedRange<*>): FnTransform =
        repeatEvery(asParam(period, "period"), asParam(origin, "origin"), domain.toDomain())

    fun repeatEvery(period: Fn, origin: Fn = param(0.0), domain: Domain = Domain.ALL): FnTransform =
        FnTransform("repeatEvery") { source ->
            source.repeat(period, origin, domainOverride = domain).withName("repeatEvery")
        }

    fun repeatEvery(period: Fn, origin: Fn = param(0.0), domain: ClosedRange<*>): FnTransform =
        repeatEvery(period, origin, domain.toDomain())

    fun mirrorEvery(period: Number, origin: Number = 0.0, domain: Domain = Domain.ALL): FnTransform =
        mirrorEvery(param(period), param(origin), domain)

    fun mirrorEvery(period: Any, origin: Any = 0.0, domain: Domain = Domain.ALL): FnTransform =
        mirrorEvery(asParam(period, "period"), asParam(origin, "origin"), domain)

    fun mirrorEvery(period: Number, origin: Number = 0.0, domain: ClosedRange<*>): FnTransform =
        mirrorEvery(param(period), param(origin), domain.toDomain())

    fun mirrorEvery(period: Any, origin: Any = 0.0, domain: ClosedRange<*>): FnTransform =
        mirrorEvery(asParam(period, "period"), asParam(origin, "origin"), domain.toDomain())

    fun mirrorEvery(period: Fn, origin: Fn = param(0.0), domain: Domain = Domain.ALL): FnTransform =
        FnTransform("mirrorEvery") { source ->
            source.mirrorRepeat(period, origin, domainOverride = domain).withName("mirrorEvery")
        }

    fun mirrorEvery(period: Fn, origin: Fn = param(0.0), domain: ClosedRange<*>): FnTransform =
        mirrorEvery(period, origin, domain.toDomain())

    fun randomRepeat(
        period: Number,
        seed: Long = 0L,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        sourceDomain: Domain? = null,
        edge: EdgeMode = EdgeMode.CLAMP
    ): FnTransform = randomRepeat(param(period), seed, domain, sourceDomain, edge)

    fun randomRepeat(
        period: Number,
        seed: Long = 0L,
        domain: ClosedRange<*>,
        sourceDomain: Domain? = null,
        edge: EdgeMode = EdgeMode.CLAMP
    ): FnTransform = randomRepeat(param(period), seed, domain.toDomain(), sourceDomain, edge)

    fun randomRepeat(
        period: Fn,
        seed: Long = 0L,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        sourceDomain: Domain? = null,
        edge: EdgeMode = EdgeMode.CLAMP
    ): FnTransform = FnTransform("randomRepeat") { source ->
        val src = sourceDomain ?: source.domain
        graphFn(
            domain,
            "randomRepeat",
            "randomRepeat",
            children = listOf(fnEdge("source", source), fnEdge("period", period)),
            strings = mapOf("sourceDomain" to src.toString(), "edge" to edge.name),
            longs = mapOf("seed" to seed),
            rebuild = { children -> (childAt(children, 0, source) / randomRepeat(childAt(children, 1, period), seed, domain, src, edge)) }
        ) { x ->
            if (src._isEmpty || !src.length.isFinite()) return@graphFn source.evalSafe(x, edge = edge)
            val p = abs(period.paramAt(x, 1.0)).coerceAtLeast(1e-9)
            val slot = floor((x - domain.min) / p).toLong()
            val local = ((x - domain.min) % p + p) % p
            val maxStart = (src.length - p).coerceAtLeast(0.0)
            val start = src.min + hashToUnit(seed, slot) * maxStart
            source.evalSafe(start + local.coerceAtMost(src.length), edge = edge)
        }
    }

    fun randomRepeat(
        period: Fn,
        seed: Long = 0L,
        domain: ClosedRange<*>,
        sourceDomain: Domain? = null,
        edge: EdgeMode = EdgeMode.CLAMP
    ): FnTransform = randomRepeat(period, seed, domain.toDomain(), sourceDomain, edge)

    fun randomCopyPaste(
        grain: Number,
        seed: Long = 0L,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        sourceDomain: Domain? = null,
        density: Number = 1.0,
        edge: EdgeMode = EdgeMode.CLAMP,
        outside: Double = 0.0
    ): FnTransform = randomCopyPaste(param(grain), seed, domain, sourceDomain, param(density), edge, outside)

    fun randomCopyPaste(
        grain: Number,
        seed: Long = 0L,
        domain: ClosedRange<*>,
        sourceDomain: Domain? = null,
        density: Number = 1.0,
        edge: EdgeMode = EdgeMode.CLAMP,
        outside: Double = 0.0
    ): FnTransform = randomCopyPaste(param(grain), seed, domain.toDomain(), sourceDomain, param(density), edge, outside)

    fun randomCopyPaste(
        grain: Any,
        seed: Long = 0L,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        sourceDomain: Domain? = null,
        density: Any = 1.0,
        edge: EdgeMode = EdgeMode.CLAMP,
        outside: Double = 0.0
    ): FnTransform = randomCopyPaste(asParam(grain, "grain"), seed, domain, sourceDomain, asParam(density, "density"), edge, outside)

    fun randomCopyPaste(
        grain: Any,
        seed: Long = 0L,
        domain: ClosedRange<*>,
        sourceDomain: Domain? = null,
        density: Any = 1.0,
        edge: EdgeMode = EdgeMode.CLAMP,
        outside: Double = 0.0
    ): FnTransform = randomCopyPaste(grain, seed, domain.toDomain(), sourceDomain, density, edge, outside)

    fun randomCopyPaste(
        grain: Fn,
        seed: Long = 0L,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        sourceDomain: Domain? = null,
        density: Fn = param(1.0),
        edge: EdgeMode = EdgeMode.CLAMP,
        outside: Double = 0.0
    ): FnTransform = FnTransform("randomCopyPaste") { source ->
        val src = sourceDomain ?: source.domain.takeIf { it.isFinite } ?: Domain.DEFAULT
        graphFn(
            domain,
            "randomCopyPaste",
            "randomCopyPaste",
            children = listOf(fnEdge("source", source), fnEdge("grain", grain), fnEdge("density", density)),
            numbers = mapOf("outside" to outside),
            strings = mapOf("sourceDomain" to src.toString(), "edge" to edge.name),
            longs = mapOf("seed" to seed),
            rebuild = { children ->
                (childAt(children, 0, source) / randomCopyPaste(
                    childAt(children, 1, grain),
                    seed,
                    domain,
                    src,
                    childAt(children, 2, density),
                    edge,
                    outside
                ))
            }
        ) { x ->
            if (src._isEmpty || !src.length.isFinite()) return@graphFn outside
            val g = abs(grain.paramAt(x, 1.0)).coerceAtLeast(1e-9)
            val fill = density.paramAt(x, 1.0).coerceIn(0.0, 1.0)
            val slot = floor((x - domain.min) / g).toLong()
            if (hashToUnit(seed + 17L, slot) > fill) return@graphFn outside
            val local = ((x - domain.min) % g + g) % g
            val maxStart = (src.length - g).coerceAtLeast(0.0)
            val start = src.min + hashToUnit(seed, slot) * maxStart
            source.evalSafe(start + local.coerceAtMost(src.length), outside = outside, edge = edge)
        }
    }

    fun randomCopyPaste(
        grain: Fn,
        seed: Long = 0L,
        domain: ClosedRange<*>,
        sourceDomain: Domain? = null,
        density: Fn = param(1.0),
        edge: EdgeMode = EdgeMode.CLAMP,
        outside: Double = 0.0
    ): FnTransform = randomCopyPaste(grain, seed, domain.toDomain(), sourceDomain, density, edge, outside)

    fun sin(
        frequency: Number,
        phase: Number = 0.0,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        domain: Domain = Domain.ALL
    ): Fn =
        sin(frequency = param(frequency), phase = param(phase), amplitude = param(amplitude), offset = param(offset), domain = domain)

    fun sin(
        frequency: Number,
        phase: Number = 0.0,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        domain: ClosedRange<*>
    ): Fn =
        sin(frequency, phase, amplitude, offset, domain.toDomain())

    fun sin(
        frequency: Any,
        phase: Any = 0.0,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        domain: Domain = Domain.ALL
    ): Fn =
        sin(frequency = asParam(frequency, "frequency"), phase = asParam(phase, "phase"), amplitude = asParam(amplitude, "amplitude"), offset = asParam(offset, "offset"), domain = domain)

    fun sin(
        frequency: Any,
        phase: Any = 0.0,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        domain: ClosedRange<*>
    ): Fn =
        sin(frequency, phase, amplitude, offset, domain.toDomain())

    fun cos(
        frequency: Number,
        phase: Number = 0.0,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        domain: Domain = Domain.ALL
    ): Fn =
        cos(frequency = param(frequency), phase = param(phase), amplitude = param(amplitude), offset = param(offset), domain = domain)

    fun cos(
        frequency: Number,
        phase: Number = 0.0,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        domain: ClosedRange<*>
    ): Fn =
        cos(frequency, phase, amplitude, offset, domain.toDomain())

    fun cos(
        frequency: Any,
        phase: Any = 0.0,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        domain: Domain = Domain.ALL
    ): Fn =
        cos(frequency = asParam(frequency, "frequency"), phase = asParam(phase, "phase"), amplitude = asParam(amplitude, "amplitude"), offset = asParam(offset, "offset"), domain = domain)

    fun cos(
        frequency: Any,
        phase: Any = 0.0,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        domain: ClosedRange<*>
    ): Fn =
        cos(frequency, phase, amplitude, offset, domain.toDomain())

    fun saw(
        period: Number,
        phase: Number = 0.0,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        domain: Domain = Domain.ALL
    ): Fn =
        saw(period = param(period), phase = param(phase), amplitude = param(amplitude), offset = param(offset), domain = domain)

    fun saw(
        period: Number,
        phase: Number = 0.0,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        domain: ClosedRange<*>
    ): Fn =
        saw(period, phase, amplitude, offset, domain.toDomain())

    fun saw(
        period: Any,
        phase: Any = 0.0,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        domain: Domain = Domain.ALL
    ): Fn =
        saw(period = asParam(period, "period"), phase = asParam(phase, "phase"), amplitude = asParam(amplitude, "amplitude"), offset = asParam(offset, "offset"), domain = domain)

    fun saw(
        period: Any,
        phase: Any = 0.0,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        domain: ClosedRange<*>
    ): Fn =
        saw(period, phase, amplitude, offset, domain.toDomain())

    fun triangle(
        period: Number,
        phase: Number = 0.0,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        domain: Domain = Domain.ALL
    ): Fn =
        triangle(period = param(period), phase = param(phase), amplitude = param(amplitude), offset = param(offset), domain = domain)

    fun triangle(
        period: Number,
        phase: Number = 0.0,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        domain: ClosedRange<*>
    ): Fn =
        triangle(period, phase, amplitude, offset, domain.toDomain())

    fun triangle(
        period: Any,
        phase: Any = 0.0,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        domain: Domain = Domain.ALL
    ): Fn =
        triangle(period = asParam(period, "period"), phase = asParam(phase, "phase"), amplitude = asParam(amplitude, "amplitude"), offset = asParam(offset, "offset"), domain = domain)

    fun triangle(
        period: Any,
        phase: Any = 0.0,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        domain: ClosedRange<*>
    ): Fn =
        triangle(period, phase, amplitude, offset, domain.toDomain())

    fun square(
        period: Number,
        phase: Number = 0.0,
        duty: Number = 0.5,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        domain: Domain = Domain.ALL
    ): Fn =
        square(period = param(period), phase = param(phase), duty = param(duty), amplitude = param(amplitude), offset = param(offset), domain = domain)

    fun square(
        period: Number,
        phase: Number = 0.0,
        duty: Number = 0.5,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        domain: ClosedRange<*>
    ): Fn =
        square(period, phase, duty, amplitude, offset, domain.toDomain())

    fun square(
        period: Any,
        phase: Any = 0.0,
        duty: Any = 0.5,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        domain: Domain = Domain.ALL
    ): Fn =
        square(period = asParam(period, "period"), phase = asParam(phase, "phase"), duty = asParam(duty, "duty"), amplitude = asParam(amplitude, "amplitude"), offset = asParam(offset, "offset"), domain = domain)

    fun square(
        period: Any,
        phase: Any = 0.0,
        duty: Any = 0.5,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        domain: ClosedRange<*>
    ): Fn =
        square(period, phase, duty, amplitude, offset, domain.toDomain())

    private fun timelineStartFor(source: Fn): Double =
        if (source.domain.isFinite) source.domain.min else 0.0

    /**
     * `a then b` — plays this function and then [next], butted end to end with no crossfade.
     *
     * A two-item [timeline] in one operator. The arrangement starts at this function's own domain minimum (or at zero if
     * that is unbounded), and each function occupies a slot as long as its own domain, falling back to `1.0` where that is
     * empty or infinite. For a crossfaded join use `overlapping`.
     */
    infix fun Fn.then(next: Fn): Fn =
        TimelineBuilder(start = timelineStartFor(this)).apply {
            this@then.place()
            next.place()
        }.build(name = "then")

    /**
     * The half-finished middle of `a overlapping 0.2 with b` — it carries the first function and the overlap until the
     * `with` arrives.
     *
     * Not something to hold on to: nothing is arranged until [with] is called, and the value on its own does nothing.
     */
    data class OverlapStart(val first: Fn, val amount: Fn) {
        /** Completes the phrase, arranging the two functions with the recorded overlap. Equivalent to `first.overlapWith(next, amount)`. */
        infix fun with(next: Fn): Fn = first.overlapWith(next, amount)
    }

    /** `a overlapping 0.2 with b` — begins a crossfaded join; the overlap is in x units, and nothing happens until `with` supplies the second function. */
    infix fun Fn.overlapping(amount: Number): OverlapStart = OverlapStart(this, param(amount))

    /** `a overlapping f with b` — begins a crossfaded join with a signal-valued overlap, sampled around the join itself. Nothing happens until `with` supplies the second function. */
    infix fun Fn.overlapping(amount: Fn): OverlapStart = OverlapStart(this, amount)

    /** Plays this function then [next], their join crossfaded over [amount] x units — the call-syntax form of `a overlapping amount with next`. */
    fun Fn.overlapping(amount: Number, next: Fn): Fn = overlapWith(next, amount)

    /** Plays this function then [next] with a signal-valued crossfade [amount] — the call-syntax form of `a overlapping amount with next`. */
    fun Fn.overlapping(amount: Fn, next: Fn): Fn = overlapWith(next, amount)

    /**
     * Plays this function then [next], with [next] starting [amount] units early so the two crossfade under a Hann [window].
     *
     * The overlap is in x units and is limited by the length of the material, so asking for more than [next] lasts gets you
     * as much as there is. [blend] decides how the overlapping region combines — `REPLACE` (the default) crossfades, `ADD`
     * sums.
     */
    fun Fn.overlapWith(
        next: Fn,
        amount: Number,
        blend: ChunkBlend = ChunkBlend.REPLACE,
        window: Fn = hannWindow(Domain.DEFAULT)
    ): Fn = overlapWith(next, param(amount), blend, window)

    /**
     * Plays this function then [next] starting [amount] units early, crossfading under a Hann [window]. With a signal-valued
     * [amount] the overlap actually used is sampled around the join itself. [blend] decides how the overlapping region
     * combines.
     */
    fun Fn.overlapWith(
        next: Fn,
        amount: Fn,
        blend: ChunkBlend = ChunkBlend.REPLACE,
        window: Fn = hannWindow(Domain.DEFAULT)
    ): Fn = TimelineBuilder(start = timelineStartFor(this)).apply {
        this@overlapWith.place()
        next.overlap(amount = amount, blend = blend, window = window)
    }.build(name = "overlap")

    /**
     * Samples this function into a drawable [FLine] polyline: `x` becomes `xOffset + x * xScale` and the value becomes
     * `yOffset + value * yScale`.
     *
     * The bridge from signal to graphics, and the reason a function stack can be designed by eye. Two conventions matter.
     * The mapping is applied as written, with **no y flip** — in a screen space where y grows downward, pass a negative
     * [yScale] to get a graph the right way up. And sampling here is *not* domain-checked: values come from raw
     * evaluation, so a [sampleDomain] wider than the function's own may pick up extrapolated nonsense. A [samples] of zero
     * or less, or an empty domain, gives an empty line; an unbounded domain is previewed over a finite window.
     */
    fun Fn.toFLine(
        sampleDomain: Domain = domain,
        samples: Int = 256,
        includeEnd: Boolean = true,
        xScale: Double = 1.0,
        yScale: Double = 1.0,
        xOffset: Double = 0.0,
        yOffset: Double = 0.0
    ): FLine {
        val line = FLine()
        if (samples <= 0 || sampleDomain._isEmpty) return line
        val sweep = sampleDomain.finiteSweep()
        val len = sweep.length
        val steps = if (includeEnd) max(1, samples - 1) else samples
        val step = if (steps == 0) 0.0 else len / steps.toDouble()
        for (i in 0 until samples) {
            val x = sweep.min + step * i.toDouble()
            val y = eval(x)
            val px = xOffset + x * xScale
            val py = yOffset + y * yScale
            if (i == 0) line.moveTo(px, py) else line.lineTo(px, py)
        }
        return line
    }

    /**
     * Samples this function over the given range into a drawable [FLine], mapping x through `xOffset + x * xScale` and the
     * value through `yOffset + value * yScale`. No y flip is applied — negate [yScale] for a y-down screen space.
     */
    fun Fn.toFLine(
        sampleDomain: ClosedRange<*>,
        samples: Int = 256,
        includeEnd: Boolean = true,
        xScale: Double = 1.0,
        yScale: Double = 1.0,
        xOffset: Double = 0.0,
        yOffset: Double = 0.0
    ): FLine =
        toFLine(
            sampleDomain = sampleDomain.toDomain(),
            samples = samples,
            includeEnd = includeEnd,
            xScale = xScale,
            yScale = yScale,
            xOffset = xOffset,
            yOffset = yOffset
        )

    // Algebra and composition
    /**
     * Adds two functions pointwise — mixing, in signal terms.
     *
     * The result's domain is the **intersection** of the two, so adding something bounded to an unbounded
     * oscillator crops the oscillator. That is the single most common surprise in this file: use `2.0.c` or `all`
     * rather than `c(2.0)` for values that are meant to be knobs. Lazy, like everything else here — nothing is
     * evaluated until the sum is sampled.
     */
    operator fun Fn.plus(other: Fn): Fn {
        val domain = combinedDomain(this, other)
        return graphFn(
            domain,
            "(+)",
            node("add", "+", listOf(fnEdge("left", this), fnEdge("right", other)), rebuild = { it[0] + it[1] })
        ) { x -> this.eval(x) + other.eval(x) }
    }

    /** Subtracts [other] from this function pointwise. The result's domain is the intersection of the two. */
    operator fun Fn.minus(other: Fn): Fn {
        val domain = combinedDomain(this, other)
        return graphFn(
            domain,
            "(-)",
            node("subtract", "-", listOf(fnEdge("left", this), fnEdge("right", other)), rebuild = { it[0] - it[1] })
        ) { x -> this.eval(x) - other.eval(x) }
    }

    /**
     * Multiplies two functions pointwise — windowing, enveloping and ring modulation are all this operator. The
     * result's domain is the intersection of the two.
     */
    operator fun Fn.times(other: Fn): Fn {
        val domain = combinedDomain(this, other)
        return graphFn(
            domain,
            "(*)",
            node("multiply", "*", listOf(fnEdge("left", this), fnEdge("right", other)), rebuild = { it[0] * it[1] })
        ) { x -> this.eval(x) * other.eval(x) }
    }

    /**
     * Divides this function by [other] pointwise. The result's domain is the intersection of the two.
     *
     * No guard against a zero divisor: where [other] is zero the result is an infinity or NaN, which sampling and
     * plotting will show as a gap rather than an error. Note the overload next door — a transform on the right of
     * `/` means "apply this transform" instead.
     */
    operator fun Fn.div(other: Fn): Fn {
        val domain = combinedDomain(this, other)
        return graphFn(
            domain,
            "(/)",
            node("divide", "/", listOf(fnEdge("left", this), fnEdge("right", other)), rebuild = { it[0] / it[1] })
        ) { x -> this.eval(x) / other.eval(x) }
    }

    /** Adds a constant to every value — a DC offset. The number becomes an unbounded parameter signal, so this never shrinks the domain. */
    operator fun Fn.plus(value: Number): Fn = this + param(value)

    /** Subtracts a constant from every value. The number becomes an unbounded parameter signal, so this never shrinks the domain. */
    operator fun Fn.minus(value: Number): Fn = this - param(value)

    /** Scales every value by a constant — gain. The number becomes an unbounded parameter signal, so this never shrinks the domain. */
    operator fun Fn.times(value: Number): Fn = this * param(value)

    /** Divides every value by a constant. The number becomes an unbounded parameter signal, so this never shrinks the domain. */
    operator fun Fn.div(value: Number): Fn = this / param(value)

    /** `2.0 + f` — a constant plus a function, which is the same as `f + 2.0`. */
    operator fun Number.plus(f: Fn): Fn = f + this

    /** `1.0 - f` — a constant minus a function, in that order (not the same as `f - 1.0`), which is how an inverted envelope is written. */
    operator fun Number.minus(f: Fn): Fn = param(this) - f

    /** `2.0 * f` — a constant times a function, which is the same as `f * 2.0`. */
    operator fun Number.times(f: Fn): Fn = f * this

    /** `1.0 / f` — a constant divided by a function, in that order; the way to write a reciprocal. Unguarded where `f` is zero. */
    operator fun Number.div(f: Fn): Fn = param(this) / f

    /**
     * Applies an arbitrary lambda to this function's **value**, leaving its domain alone — the general-purpose
     * waveshaper.
     *
     * The escape hatch when no named shaper fits, and lazy like everything else: the lambda runs once per sample. The
     * cost is structural, as with [fn] — the node is tagged as a lambda, so a rewrite can replace the whole thing but
     * cannot see inside the transform.
     */
    fun Fn.mapY(newName: String? = name, transform: (Double) -> Double): Fn =
        graphFn(
            domain,
            newName,
            node(
                "mapY",
                label = newName ?: "mapY",
                children = listOf(fnEdge("source", this)),
                tags = setOf("lambda"),
                rebuild = { children -> children[0].mapY(newName, transform) }
            )
        ) { x -> transform(eval(x)) }

    /**
     * Applies an arbitrary lambda to the **coordinate** before evaluating — the general-purpose time warp, the
     * counterpart to [mapY].
     *
     * The lambda maps the x being asked about to the x actually looked up, so it runs "backwards" from how a time
     * shift reads: to delay by two, return `x - 2`. [newDomain] declares where the result is valid, since only the
     * caller knows what the warp did to the extent. The node is opaque to rewriting, as with [mapY].
     */
    fun Fn.mapX(newName: String? = name, newDomain: Domain = domain, transform: (Double) -> Double): Fn =
        graphFn(
            newDomain,
            newName,
            node(
                "mapX",
                label = newName ?: "mapX",
                children = listOf(fnEdge("source", this)),
                tags = setOf("lambda"),
                rebuild = { children -> children[0].mapX(newName, newDomain, transform) }
            )
        ) { x -> eval(transform(x)) }

    private fun Fn.stretchMapped(
        from: Domain,
        to: Domain,
        newName: String?,
        warp: (Double) -> Double
    ): Fn {
        requireStretchDomain(from, "source")
        requireStretchDomain(to, "target")
        return graphFn(
            to,
            newName,
            node(
                "stretch",
                label = newName ?: "stretch",
                children = listOf(fnEdge("source", this)),
                strings = mapOf("from" to from.toString(), "to" to to.toString()),
                tags = setOf("lambda"),
                rebuild = { children -> children[0].stretchMapped(from, to, newName, warp) }
            )
        ) { x ->
            val alpha = to.alphaOf(x)
            eval(from.lerp(warp(alpha)))
        }
    }

    /**
     * Remap this function's existing coordinate domain onto [to].
     *
     * Unlike [over], which only relabels the public domain, stretch changes the
     * x-coordinate lookup so the whole source interval is played across the
     * target interval.
     */
    fun Fn.stretch(
        to: Domain,
        from: Domain = domain,
        shape: StretchShape = StretchShape.LINEAR
    ): Fn = stretchMapped(from, to, name) { alpha ->
        stretchAlpha(alpha, shape)
    }

    fun Fn.stretch(
        to: ClosedRange<*>,
        from: Domain = domain,
        shape: StretchShape = StretchShape.LINEAR
    ): Fn = stretch(to.toDomain(), from, shape)

    fun Fn.stretchFrom(
        from: Domain,
        to: Domain,
        shape: StretchShape = StretchShape.LINEAR
    ): Fn = stretch(to, from, shape)

    fun Fn.stretchFrom(
        from: ClosedRange<*>,
        to: ClosedRange<*>,
        shape: StretchShape = StretchShape.LINEAR
    ): Fn = stretch(to.toDomain(), from.toDomain(), shape)

    fun Fn.stretchSmooth(to: Domain, from: Domain = domain): Fn =
        stretch(to, from, StretchShape.SMOOTHSTEP)

    fun Fn.stretchSmooth(to: ClosedRange<*>, from: Domain = domain): Fn =
        stretchSmooth(to.toDomain(), from)

    fun Fn.stretchSmoother(to: Domain, from: Domain = domain): Fn =
        stretch(to, from, StretchShape.SMOOTHERSTEP)

    fun Fn.stretchSmoother(to: ClosedRange<*>, from: Domain = domain): Fn =
        stretchSmoother(to.toDomain(), from)

    /**
     * [stretch] onto [to] with the pacing given by [warp] rather than a named [StretchShape] — see the
     * [Functions.stretchBy] transform for what a warp is and how it is read.
     *
     * `outside` is not passed to [Fn.evalSafe] here: under [EdgeMode.CLAMP] it is
     * unreachable, so an `outside = alpha` argument only *looks* like an un-warped fallback.
     */
    fun Fn.stretchBy(to: Domain, warp: Fn, from: Domain = domain): Fn =
        stretchMapped(from, to, name) { alpha ->
            warp.evalSafe(alpha, edge = EdgeMode.CLAMP)
        }

    fun Fn.stretchBy(to: ClosedRange<*>, warp: Fn, from: Domain = domain): Fn =
        stretchBy(to.toDomain(), warp, from)

    fun Fn.stretchBy(to: Domain, from: Domain = domain, warp: (Double) -> Double): Fn =
        stretchMapped(from, to, name, warp)

    fun Fn.stretchBy(to: ClosedRange<*>, from: Domain = domain, warp: (Double) -> Double): Fn =
        stretchBy(to.toDomain(), from, warp)

    /**
     * The literal value of a compile-time constant [Fn] (a bare `param`/`const` with no
     * inputs), or null if it varies with x. Used to translate the domain under a constant
     * [shift]: `f.delay(2.0)` should move f's support forward by 2, not leave it in place.
     */
    private fun Fn.literalValue(): Double? {
        val g = graphNode()
        if (g.children.isNotEmpty()) return null
        if ("number" !in g.tags) return null
        return g.numbers["value"]?.takeIf { it.isFinite() }
    }

    /**
     * Slides this function along x by [dx]: positive values move it **later** (`f.shift(2.0)` evaluates `f(x - 2)`).
     *
     * A constant shift carries the domain with it, so the function's support genuinely moves. A *signal-valued* [dx]
     * has no single offset to move by, so the domain falls back to the intersection of the operands' domains and the
     * effect becomes a time warp rather than a delay. [delay] is the same operation under a name that suits audio
     * better.
     */
    fun Fn.shift(dx: Fn = param(0.0)): Fn {
        // A constant delay slides the whole signal, so its domain slides with it. A signal-valued
        // dx has no single offset, so we fall back to intersecting the operands' domains.
        val constant = dx.literalValue()
        val d = if (constant != null) domain.safeShift(constant) else intersectDomains(domain, dx)
        return graphFn(
            d,
            name,
            node("shift", children = listOf(fnEdge("source", this), fnEdge("dx", dx)), rebuild = { it[0].shift(it[1]) })
        ) { x -> eval(x - dx.eval(x)) }
    }

    /** Slides this function along x by [dx], positive being later. A constant shift moves the domain with it. */
    fun Fn.shift(dx: Number): Fn = shift(param(dx))

    /**
     * Scales this function along x about the point [about] — `scaleX(2.0)` makes it twice as wide, and therefore
     * half as fast.
     *
     * [about] defaults to the **origin**, not to the function's own centre, so scaling something that lives on
     * `1.0..2.0` also moves it. A scale of zero collapses everything onto the value at [about] rather than dividing
     * by zero. The domain is not rescaled to match — this changes the lookup, not the declared extent. See [faster]
     * and [slower] for the reciprocal-free spellings.
     */
    fun Fn.scaleX(scale: Fn = param(1.0), about: Fn = param(0.0)): Fn {
        val d = intersectDomains(domain, scale, about)
        return graphFn(
            d,
            name,
            node(
                "scaleX",
                children = listOf(fnEdge("source", this), fnEdge("scale", scale), fnEdge("about", about)),
                rebuild = { it[0].scaleX(it[1], it[2]) }
            )
        ) { x ->
            val a = about.eval(x)
            val s = scale.eval(x)
            if (s == 0.0) eval(a) else eval((x - a) / s + a)
        }
    }

    /** Scales this function along x about [about] — bigger is wider and slower. [about] defaults to the origin, not the function's centre; a scale of zero collapses to the value at [about]. */
    fun Fn.scaleX(scale: Number, about: Number = 0.0): Fn =
        scaleX(param(scale), param(about))

    /** Scales this function along x about [about], taking either argument as a number or an [Fn] and throwing for anything else. [about] defaults to the origin. */
    fun Fn.scaleX(scale: Any, about: Any = 0.0): Fn =
        scaleX(asParam(scale, "scale"), asParam(about, "about"))

    /** Plays this function [factor] times faster about [about] — `faster(2.0)` runs at double speed. The factor's magnitude is used and floored just above zero, so it can never invert or stall. */
    fun Fn.faster(factor: Number = 2.0, about: Number = 0.0): Fn {
        return faster(param(factor), param(about))
    }

    /** Plays this function [factor] times faster about [about], taking either argument as a number or an [Fn] and throwing for anything else. */
    fun Fn.faster(factor: Any, about: Any = 0.0): Fn =
        faster(asParam(factor, "factor"), asParam(about, "about"))

    /**
     * Plays this function [factor] times faster about [about] — the musician's spelling of `scaleX(1 / factor)`.
     *
     * The factor is taken as a magnitude and clamped just above zero before being inverted, so a zero or negative
     * factor cannot stall or reverse the function; use [reverse] for that. A signal-valued factor makes this a
     * continuous tape-speed warp. [about] defaults to the origin.
     */
    fun Fn.faster(factor: Fn, about: Fn = param(0.0)): Fn {
        val safe = factor.mapY("fasterFactor") { abs(it).coerceAtLeast(1e-9) }
        return scaleX(one(safe.domain) / safe, about).withName("faster")
    }

    /** Plays this function [factor] times slower about [about] — `slower(2.0)` stretches it by two. The factor's magnitude is used and floored just above zero. */
    fun Fn.slower(factor: Number = 2.0, about: Number = 0.0): Fn {
        return slower(param(factor), param(about))
    }

    /** Plays this function [factor] times slower about [about], taking either argument as a number or an [Fn] and throwing for anything else. */
    fun Fn.slower(factor: Any, about: Any = 0.0): Fn =
        slower(asParam(factor, "factor"), asParam(about, "about"))

    /**
     * Plays this function [factor] times slower about [about] — the musician's spelling of `scaleX(factor)`, and the
     * inverse of [faster].
     *
     * The factor is taken as a magnitude and clamped just above zero, so it cannot reverse or freeze the function. A
     * signal-valued factor makes this a continuous tape-speed warp. [about] defaults to the origin, not the
     * function's centre.
     */
    fun Fn.slower(factor: Fn, about: Fn = param(0.0)): Fn {
        val safe = factor.mapY("slowerFactor") { abs(it).coerceAtLeast(1e-9) }
        return scaleX(safe, about).withName("slower")
    }

    /**
     * Scales this function's **values** by [scale] — gain. Identical to `f * scale`, spelled as a method for chaining;
     * a signal-valued scale is an amplitude envelope.
     */
    fun Fn.scaleY(scale: Fn = param(1.0)): Fn {
        val d = intersectDomains(domain, scale)
        return graphFn(
            d,
            name,
            node("scaleY", children = listOf(fnEdge("source", this), fnEdge("scale", scale)), rebuild = { it[0].scaleY(it[1]) })
        ) { x -> eval(x) * scale.eval(x) }
    }

    /** Scales this function's values by [scale] — gain. The same as `f * scale`, spelled as a method for chaining. */
    fun Fn.scaleY(scale: Number): Fn = scaleY(param(scale))

    /**
     * Shifts this function's **values** by [offset] — a DC bias, moving the signal up or down without touching its
     * timing. Identical to `f + offset`, spelled as a method for chaining.
     */
    fun Fn.offsetY(offset: Fn = param(0.0)): Fn {
        val d = intersectDomains(domain, offset)
        return graphFn(
            d,
            name,
            node("offsetY", children = listOf(fnEdge("source", this), fnEdge("offset", offset)), rebuild = { it[0].offsetY(it[1]) })
        ) { x -> eval(x) + offset.eval(x) }
    }

    /** Shifts this function's values by [offset] — a DC bias. The same as `f + offset`, spelled as a method for chaining. */
    fun Fn.offsetY(offset: Number): Fn = offsetY(param(offset))

    /**
     * Plays this function **backwards**, mirrored about [about] — which defaults to the centre of its own domain, so a
     * bounded function reverses in place.
     *
     * Note that default: on an unbounded function the domain centre is not a finite number, so pass [about]
     * explicitly there. The declared domain is not moved, only the lookup, and reversing twice about the same point
     * gets you back where you started.
     */
    fun Fn.reverse(about: Fn = param(domain.center)): Fn {
        val d = intersectDomains(domain, about)
        return graphFn(
            d,
            name,
            node("reverse", children = listOf(fnEdge("source", this), fnEdge("about", about)), rebuild = { it[0].reverse(it[1]) })
        ) { x ->
            val a = about.eval(x)
            eval(a - (x - a))
        }
    }

    /** Plays this function backwards, mirrored about the coordinate [about]. Unlike the no-argument form there is no reliance on a finite domain centre. */
    fun Fn.reverse(about: Number): Fn = reverse(param(about))

    // The four value transforms below used to be built with `mapY { … }`, which buried the whole
    // function in an opaque `(Double) -> Double` closure and — because `mapY` labels its node with
    // the *child's* name — did not even record in the graph that an abs (or an exp) had happened.
    // That made them invisible twice over: the rewrite/evolve layer could replace the node but
    // never reason about it, and the exact-gradient path refused them for the same reason it
    // rightly refuses a user's lambda. `log` was a real node and `exp` was not, so a fit could
    // descend through one and not its inverse — an asymmetry with no mathematical content at all.
    // Each is now a first-class node in the `log`/`pow` shape, so `OpRegistry` can transmute it and
    // `FnFormulas` can carry its derivative. `sign` is the deliberate exception: it gets the node
    // (so the graph records it) but no formula — see FnNonDifferentiable.

    /** Negates every value, turning the signal upside down. This flips it in **value**; [reverse] flips it in time. Differentiable, with derivative `-1` everywhere. */
    fun Fn.flip(): Fn = graphFn(
        domain,
        name,
        node("flip", children = listOf(fnEdge("source", this)), rebuild = { it[0].flip() })
    ) { x -> -eval(x) }

    /**
     * The absolute value of the signal — full-wave rectification, folding the negative half upward.
     * The same shape as `rectify(FULL)`, and like it a first-class, rewritable graph node.
     *
     * Differentiable away from zero, and **zero at exactly zero** at the kink: this library splits a
     * tie evenly, and `|x| = max(x, -x)` with an even split gives `0.5·1 + 0.5·(-1) = 0`. That is
     * also precisely what a central difference reports there, so the analytic gradient and the
     * finite-difference oracle agree at the corner too. See the kink convention in `autodiff.kt`.
     */
    fun Fn.abs(): Fn = graphFn(
        domain,
        name,
        node("abs", children = listOf(fnEdge("source", this)), rebuild = { it[0].abs() })
    ) { x -> kotlin.math.abs(eval(x)) }

    /**
     * The greatest integer at or below every value — a staircase.
     *
     * A first-class node **and** differentiable, which reads like a contradiction and is not: the
     * derivative of a step function is zero everywhere it exists, and zero is what this reports. On
     * its own that makes it a dead end for a fit, exactly as [sign] is. Inside something else it is
     * the opposite of a dead end: period arithmetic is `t - p·floor(t / p)`, and the wrapped time
     * that falls out *is* a differentiable function of the period on either side of a wrap. That is
     * why `FnNonDifferentiable` had been naming `FnExpr.Floor` as the highest-value missing piece in
     * the whole backlog — the piecewise oscillators need nothing else — and it is what lets RectSL's
     * `loop-time` and `pingpong-time` lower into an `Fn` at all.
     */
    fun Fn.floor(): Fn = graphFn(
        domain,
        name,
        node("floor", children = listOf(fnEdge("source", this)), rebuild = { it[0].floor() })
    ) { x -> kotlin.math.floor(eval(x)) }

    /**
     * The sign of the signal: `-1`, `0` or `+1`. A hard three-level comparator — everything about the
     * magnitude is discarded.
     *
     * A first-class node, so a rewrite can see it, but **not** differentiable: its
     * derivative is zero wherever it exists, so a gradient threaded through it would be exactly
     * zero and a fit would report convergence without ever having moved. `FnGradientMode.EXACT`
     * refuses instead of returning that, and says why.
     */
    fun Fn.sign(): Fn = graphFn(
        domain,
        name,
        node("sign", children = listOf(fnEdge("source", this)), rebuild = { it[0].sign() })
    ) { x -> kotlin.math.sign(eval(x)) }

    // ---- the angle family --------------------------------------------------------------------
    // Three ops that arrived together, in 2026-08-19, because RectSL's `->face-travel` could not be
    // differentiated without all three. A heading is `atan2` of a central finite difference, and
    // `RectSLFn.kt`'s refusal catalogue had been calling that "the whole of the residual refusal
    // rate on the gallery" — 116 of 2754 tracks, and every remaining refusal in the corpus.
    //
    // The interesting one is that `atan2` is a *primitive* rather than `atan(y/x)` with a quadrant
    // fix-up. See `FnExpr.Atan2`: the fix-up is half a turn wrong at a negative zero, and no
    // arithmetic downstream can recover a sign the division has already discarded.

    /**
     * The arc tangent of every value, in radians, in `(-pi/2, pi/2)`.
     *
     * Differentiable everywhere, with derivative `1/(1+v²)` — worth saying plainly, because the two
     * ops immediately above it are the opposite. [sign] has no derivative worth reporting and
     * [floor]'s is zero almost everywhere; this one is smooth at every double and bounded, so it is
     * the natural way to squash an unbounded signal into a range without a hard clip.
     *
     * For the angle of a *vector* use [atan2], which is quadrant-correct and stays well conditioned
     * as the x component crosses zero.
     */
    fun Fn.atan(): Fn = graphFn(
        domain,
        name,
        node("atan", children = listOf(fnEdge("source", this)), rebuild = { it[0].atan() })
    ) { x -> kotlin.math.atan(eval(x)) }

    /**
     * The quadrant-correct angle of the vector whose **y** component is this signal and whose **x**
     * component is [x] — `atan2(this, x)`, in radians, in `[-pi, pi]`.
     *
     * The receiver is the *y* component, matching `Math.atan2`'s argument order rather than reading
     * left to right; `dy.atan2(dx)` is the spelling to keep in your head.
     *
     * Differentiable, with `∂/∂y = x/(x²+y²)` and `∂/∂x = -y/(x²+y²)`. Both stay finite as `x`
     * crosses zero, which `atan(y/x)` does not — and the composed form is not merely worse
     * conditioned but *wrong* at a negative zero, half a turn out. `FnExpr.Atan2` carries the
     * argument and the measurement.
     */
    fun Fn.atan2(x: Fn = param(1.0)): Fn {
        val d = intersectDomains(domain, x)
        return graphFn(
            d,
            name,
            node(
                "atan2",
                children = listOf(fnEdge("source", this), fnEdge("x", x)),
                rebuild = { it[0].atan2(it[1]) }
            )
        ) { at -> kotlin.math.atan2(eval(at), x.eval(at)) }
    }

    /** The angle of the vector `(x, this)`, in radians. See the [Fn] overload. */
    fun Fn.atan2(x: Number): Fn = atan2(param(x))

    /**
     * The length of the vector whose components are this signal and [y] — `sqrt(this² + y²)`,
     * computed as `Math.hypot` so it neither overflows for large components nor underflows for tiny
     * ones.
     *
     * Spelled as its own op rather than as `(a.pow(2) + b.pow(2)).pow(0.5)` because the two are
     * *different functions* by up to an ulp, and a length is very often compared against a
     * threshold. RectSL's heading widens its sampling window while the velocity's length is at or
     * below `1e-9`; an ulp there is a different branch, which is a visibly different rotation.
     *
     * Differentiable, with the unit vector as its partials, and zero at the origin where there is no
     * direction to report.
     */
    fun Fn.hypot(y: Fn = param(0.0)): Fn {
        val d = intersectDomains(domain, y)
        return graphFn(
            d,
            name,
            node(
                "hypot",
                children = listOf(fnEdge("source", this), fnEdge("y", y)),
                rebuild = { it[0].hypot(it[1]) }
            )
        ) { at -> kotlin.math.hypot(eval(at), y.eval(at)) }
    }

    /** The length of the vector `(this, y)`. See the [Fn] overload. */
    fun Fn.hypot(y: Number): Fn = hypot(param(y))

    /**
     * Raises every value to the power [exponent], which may itself be a signal.
     *
     * Unguarded: a negative value under a fractional exponent gives NaN, and a negative value under a negative
     * exponent can give an infinity. On a 0..1 envelope an exponent above one pulls it downward (a slower attack) and
     * one below pushes it up.
     */
    fun Fn.pow(exponent: Fn = param(1.0)): Fn {
        val d = intersectDomains(domain, exponent)
        return graphFn(
            d,
            name,
            node("pow", children = listOf(fnEdge("source", this), fnEdge("exponent", exponent)), rebuild = { it[0].pow(it[1]) })
        ) { x -> eval(x).pow(exponent.eval(x)) }
    }

    /** Raises every value to the power [exponent]. Unguarded, so negative values under fractional exponents give NaN. */
    fun Fn.pow(exponent: Number): Fn = pow(param(exponent))

    /**
     * `e` to the power of every value. Grows fast: a signal reaching 10 comes out above 22000, so
     * expect to scale afterwards.
     *
     * A first-class node and the differentiable counterpart of [log] — its derivative is itself.
     */
    fun Fn.exp(): Fn = graphFn(
        domain,
        name,
        node("exp", children = listOf(fnEdge("source", this)), rebuild = { it[0].exp() })
    ) { x -> kotlin.math.exp(eval(x)) }

    /**
     * The logarithm of every value, in base [base] (natural by default), computed as `ln(v) / ln(base)`.
     *
     * Unguarded in the usual ways: a value of zero gives negative infinity, a negative value NaN, and a [base] of 1
     * divides by zero. Fine for compressing a positive signal; check the range first if the signal can cross zero.
     */
    fun Fn.log(base: Fn = param(Math.E)): Fn {
        val d = intersectDomains(domain, base)
        return graphFn(
            d,
            name,
            node("log", children = listOf(fnEdge("source", this), fnEdge("base", base)), rebuild = { it[0].log(it[1]) })
        ) { x -> ln(eval(x)) / ln(base.eval(x)) }
    }

    /** The logarithm of every value in base [base]. Zero gives negative infinity and negative values NaN. */
    fun Fn.log(base: Number): Fn = log(param(base))

    /**
     * Holds every value inside `min .. max`, hard — an asymmetric limiter, defaulting to the `-1 .. 1` range the rest
     * of the shaping family assumes.
     *
     * Clips with a corner, so anything that reaches a bound flattens against it and gains harmonics. Use [softClip]
     * for a rounded knee, or [fold] to reflect rather than flatten. Both bounds may be signals; passing them the wrong
     * way round is not corrected here, and Kotlin's `coerceIn` will throw for an inverted range.
     */
    fun Fn.clamp(min: Fn = param(-1.0), max: Fn = param(1.0)): Fn {
        val d = intersectDomains(domain, min, max)
        return graphFn(
            d,
            name,
            node(
                "clamp",
                children = listOf(fnEdge("source", this), fnEdge("min", min), fnEdge("max", max)),
                rebuild = { it[0].clamp(it[1], it[2]) }
            )
        ) { x -> eval(x).coerceIn(min.eval(x), max.eval(x)) }
    }

    /** Holds every value inside `min .. max`, hard-clipped at both bounds. An inverted range throws rather than being sorted. */
    fun Fn.clamp(min: Number, max: Number): Fn = clamp(param(min), param(max))

    /** Holds every value inside `min .. max`, taking either bound as a number or an [Fn] and throwing for anything else. */
    fun Fn.clamp(min: Any, max: Any): Fn = clamp(asParam(min, "min"), asParam(max, "max"))

    /**
     * Hard-clips the signal symmetrically to `±threshold` — [clamp] for the common case where the limit is the same
     * either side.
     *
     * A corner, not a knee: the flattened peaks are what give clipping its brightness. [softClip] rounds it,
     * [saturate] rounds it without a hard ceiling, and [fold] reflects instead of flattening.
     */
    fun Fn.clip(threshold: Fn = param(1.0)): Fn {
        val d = intersectDomains(domain, threshold)
        return graphFn(
            d,
            name,
            node("clip", children = listOf(fnEdge("source", this), fnEdge("threshold", threshold)), rebuild = { it[0].clip(it[1]) })
        ) { x ->
            val t = threshold.eval(x)
            eval(x).coerceIn(-t, t)
        }
    }

    /** Hard-clips the signal symmetrically to `±threshold`, with a corner rather than a knee. */
    fun Fn.clip(threshold: Number): Fn = clip(param(threshold))

    /**
     * Rectifies the signal: half-wave clamps the negative excursion to zero, full-wave reflects it upward (`|x|`).
     *
     * Note the default. The no-argument form is **half**-wave, kept that way for the sake of code written before this
     * became a first-class node — whereas the `Functions.rectify(mode)` transform defaults to full-wave. Full-wave
     * doubles the apparent frequency of a symmetric signal and leaves a DC offset behind.
     */
    fun Fn.rectify(mode: RectifyMode = RectifyMode.HALF): Fn {
        // First-class waveshaper (like `clip`/`fold`/`chebyshev`) so the graph-rewrite /
        // evolve / transmutation system can reach it. The mode rides as a string tag on
        // the node — exactly how `withEdge` carries its `EdgeMode` — and is restored on
        // rebuild. No-arg stays half-wave to preserve the historical meaning of the
        // extension; the public `Functions.rectify(mode)` FnTransform routes both modes
        // through here (FULL = |x|, the old `abs()` behaviour, now a real `rectify` node).
        return graphFn(
            domain,
            name,
            node(
                "rectify",
                children = listOf(fnEdge("source", this)),
                strings = mapOf("mode" to mode.name),
                rebuild = { it[0].rectify(mode) }
            )
        ) { x ->
            val v = eval(x)
            when (mode) {
                RectifyMode.HALF -> if (v < 0.0) 0.0 else v
                RectifyMode.FULL -> kotlin.math.abs(v)
            }
        }
    }

    /**
     * Saturates the signal through a `tanh` curve, normalized so that an input of ±1 still comes out at ±1 — a soft
     * knee where [clip] has a corner.
     *
     * [amount] is drive, not a threshold: larger values bend the curve harder and push the signal toward a square,
     * while small values approach a straight line. Because of the normalization the range is preserved for a signal
     * already inside ±1, which is what makes it safe to stack. An [amount] of zero collapses the signal to zero.
     */
    fun Fn.softClip(amount: Fn = param(1.0)): Fn {
        val d = intersectDomains(domain, amount)
        return graphFn(
            d,
            name,
            node("softClip", children = listOf(fnEdge("source", this), fnEdge("amount", amount)), rebuild = { it[0].softClip(it[1]) })
        ) { x ->
            val a = amount.eval(x)
            if (a == 0.0) 0.0 else tanh(eval(x) * a) / tanh(a)
        }
    }

    /** Saturates the signal through a normalized `tanh` curve; [amount] is drive rather than a threshold, and ±1 in still gives ±1 out. An amount of zero collapses the signal to zero. */
    fun Fn.softClip(amount: Number): Fn = softClip(param(amount))

    /**
     * A west-coast wavefolder: passes the signal through untouched while it stays inside `±threshold`, and **reflects**
     * it back each time it crosses — so pushing harder adds folds rather than flattening the peaks.
     *
     * Continuous, odd-symmetric, always bounded to `±threshold`, and equal to the input near zero. The interesting
     * knob is not the threshold but the gain going in: `(f * 4.0).fold(1.0)` folds four times as much as `f.fold(1.0)`.
     * A threshold of zero or less gives the constant zero.
     */
    fun Fn.fold(threshold: Fn = param(1.0)): Fn {
        val d = intersectDomains(domain, threshold)
        return graphFn(
            d,
            name,
            node("fold", children = listOf(fnEdge("source", this), fnEdge("threshold", threshold)), rebuild = { it[0].fold(it[1]) })
        ) { x ->
            val th = threshold.eval(x)
            if (th <= 0.0) 0.0 else {
                // West-coast wavefolder: pass the signal through unchanged while it stays
                // within [-th, th], reflecting it back each time it crosses the threshold.
                // asin(sin(.)) is exactly that triangle — continuous, odd-symmetric, bounded
                // to [-th, th], and equal to the input near zero.
                val value = eval(x)
                (2.0 * th / PI) * kotlin.math.asin(kotlin.math.sin(PI * value / (2.0 * th)))
            }
        }
    }

    /** A wavefolder: the signal passes through inside `±threshold` and reflects back each time it crosses. Drive it with gain rather than by lowering the threshold; a threshold of zero or less gives the constant zero. */
    fun Fn.fold(threshold: Number): Fn = fold(param(threshold))

    /**
     * The harmonic-generating Chebyshev waveshaper: pushing a unit-amplitude cosine through order *n* yields the *n*-th
     * harmonic.
     *
     * Two things follow from the trig form it is evaluated in. The drive is **clamped to ±1** first, since the
     * polynomial diverges outside the unit interval — so scale the signal into range before this, not after. And
     * [order] is genuinely continuous, not stepwise: an animated order glides between harmonics rather than jumping
     * between them. Negative orders are clamped away, being mirror images of the positive ones.
     */
    fun Fn.chebyshev(order: Fn = param(2.0)): Fn {
        val d = intersectDomains(domain, order)
        return graphFn(
            d,
            name,
            node("chebyshev", children = listOf(fnEdge("source", this), fnEdge("order", order)), rebuild = { it[0].chebyshev(it[1]) })
        ) { x ->
            // Canonical harmonic-generating waveshaper: pushing a unit-amplitude cosine
            // through the order-n Chebyshev polynomial Tₙ yields the n-th harmonic, since
            // Tₙ(cos θ) = cos(nθ). We evaluate it in exactly that trig form —
            // Tₙ(v) = cos(n·arccos v) — after clamping the drive to [-1, 1] (the standard
            // shaper guard, as with `fold`/`softClip`; arccos is only defined there and Tₙ
            // diverges outside the unit interval anyway). The trig form is exact for
            // integer n AND continuous in n, so `order` is CONTINUOUS control: a
            // swept/animated order glides smoothly between harmonics instead of stepping
            // through integer bands. Order is clamped to ≥ 0 (Tₙ is even in n, so a
            // negative order would only mirror the positive one).
            val n = order.eval(x).coerceAtLeast(0.0)
            val v = eval(x).coerceIn(-1.0, 1.0)
            kotlin.math.cos(n * kotlin.math.acos(v))
        }
    }

    /** The Chebyshev waveshaper of whole-number [order]: a unit cosine in gives that harmonic out. The drive is clamped to ±1 first. */
    fun Fn.chebyshev(order: Int): Fn = chebyshev(param(order))

    /** The Chebyshev waveshaper of [order], which need not be a whole number — the shaper is continuous in the order. The drive is clamped to ±1 first. */
    fun Fn.chebyshev(order: Number): Fn = chebyshev(param(order.toDouble()))

    /**
     * Wraps every value into `min .. max` by repetition instead of clipping it — the value-domain sibling of
     * `Domain.wrap`.
     *
     * Where [clamp] flattens an over-range signal, this jumps it to the other end, which is discontinuous by design and
     * exactly what you want for phase accumulation or a glitchy shaper you actually want. The bounds are sorted internally,
     * so their order does not matter; a zero-width range passes values through untouched.
     */
    fun Fn.wrapY(min: Fn = param(-1.0), max: Fn = param(1.0)): Fn {
        val d = intersectDomains(domain, min, max)
        return graphFn(
            d,
            name,
            node(
                "wrapY",
                children = listOf(fnEdge("source", this), fnEdge("min", min), fnEdge("max", max)),
                rebuild = { it[0].wrapY(it[1], it[2]) }
            )
        ) { x ->
            val range = Domain.of(min.eval(x), max.eval(x))
            range.wrap(eval(x))
        }
    }

    /** Wraps every value into `min .. max` by repetition rather than clipping — discontinuous by design. The bounds are sorted, so their order does not matter. */
    fun Fn.wrapY(min: Number, max: Number): Fn = wrapY(param(min), param(max))

    /** Wraps every value into `min .. max` by repetition, taking either bound as a number or an [Fn] and throwing for anything else. */
    fun Fn.wrapY(min: Any, max: Any): Fn = wrapY(asParam(min, "min"), asParam(max, "max"))

    /**
     * Bakes an out-of-domain policy into the function itself, so plain [Fn.eval] behaves the way [Fn.evalSafe] would
     * have with these arguments.
     *
     * Useful when a function is about to be handed to something that will not pass an [EdgeMode] along — a
     * `withEdge(CLAMP)` function holds its endpoint values forever instead of extrapolating, and a `withEdge(WRAP)`
     * one repeats. The declared domain is unchanged; only what happens outside it is.
     */
    fun Fn.withEdge(edge: EdgeMode, outside: Fn = param(0.0)): Fn {
        val d = intersectDomains(domain, outside)
        return graphFn(
            d,
            name,
            node(
                "withEdge",
                children = listOf(fnEdge("source", this), fnEdge("outside", outside)),
                strings = mapOf("edge" to edge.name),
                rebuild = { it[0].withEdge(edge, it[1]) }
            )
        ) { x -> evalSafe(x, outside.eval(x), edge) }
    }

    /** Bakes an out-of-domain policy into the function, so plain evaluation clamps, wraps, mirrors or returns [outside] without the caller having to ask. */
    fun Fn.withEdge(edge: EdgeMode, outside: Number): Fn = withEdge(edge, param(outside))

    /**
     * Snaps the signal to [levels] evenly-spaced steps — a staircase quantiser.
     *
     * It works in the `-1 .. 1` range: values are normalized from that span, floored to a step, and mapped back, so a
     * signal outside ±1 is clamped to the top or bottom step. Fewer than two levels gives the constant zero. Compare
     * [bitCrush], which is the same idea counted in bits, and `quantizeX`, which steps *time* rather than value.
     */
    fun Fn.quantize(levels: Fn): Fn {
        // First-class staircase quantiser. `levels` rides as a numeric `param` child so the
        // node round-trips and is transmutable; behaviour is identical to the old opaque
        // form (snap the [-1,1] signal to N evenly spaced levels).
        val d = intersectDomains(domain, levels)
        return graphFn(
            d,
            name,
            node("quantize", children = listOf(fnEdge("source", this), fnEdge("levels", levels)), rebuild = { it[0].quantize(it[1]) })
        ) { x ->
            val n = levels.eval(x).roundToInt()
            if (n <= 1) 0.0 else {
                val t = ((eval(x) + 1.0) * 0.5).coerceIn(0.0, 1.0)
                val q = floor(t * (n - 1)).toInt()
                (q.toDouble() / (n - 1)) * 2.0 - 1.0
            }
        }
    }

    /** Snaps the signal to [levels] evenly-spaced steps across `-1 .. 1`. Fewer than two levels gives the constant zero; values beyond ±1 land on the end steps. */
    fun Fn.quantize(levels: Int): Fn = quantize(param(levels))

    /**
     * Snaps the signal to `2^bits - 1` steps across `-1 .. 1` — [quantize] counted in bits, for when the digital-audio
     * idiom is what you mean.
     *
     * Because the count is exponential, the audible difference lives in the low numbers: 3 bits is coarse, 8 already
     * near-transparent. Zero bits or fewer gives the constant zero, and a signal outside ±1 lands on the end steps.
     */
    fun Fn.bitCrush(bits: Fn): Fn {
        // First-class bit-crusher (graph op "crush", matching the `crush(bits)` transform).
        // `bits` rides as a numeric `param` child; behaviour is identical to the old opaque
        // form (snap to 2^bits - 1 steps across [-1,1]).
        val d = intersectDomains(domain, bits)
        return graphFn(
            d,
            name,
            node("crush", children = listOf(fnEdge("source", this), fnEdge("bits", bits)), rebuild = { it[0].bitCrush(it[1]) })
        ) { x ->
            val n = bits.eval(x).roundToInt()
            if (n <= 0) 0.0 else {
                val steps = (1 shl n) - 1
                val t = ((eval(x) + 1.0) * 0.5).coerceIn(0.0, 1.0)
                val q = floor(t * steps).toInt()
                (q.toDouble() / steps.toDouble()) * 2.0 - 1.0
            }
        }
    }

    /** Snaps the signal to `2^bits - 1` steps across `-1 .. 1`. Zero bits or fewer gives the constant zero. */
    fun Fn.bitCrush(bits: Int): Fn = bitCrush(param(bits))

    /**
     * Blends this function with [other] by [amount] — 0 is all of this one, 1 is all of [other], 0.5 an even mix.
     *
     * A value-domain crossfade applied at every x, unlike [crossfade], which switches over at a place along x. The
     * amount is **not clamped**, so values outside 0..1 extrapolate past either function; a signal-valued amount makes
     * this a continuous morph.
     */
    fun Fn.mix(other: Fn, amount: Fn = param(0.5)): Fn =
        this * (param(1.0) - amount) + other * amount

    /** Blends this function with [other] by [amount]: 0 is all of this one, 1 all of [other]. Not clamped, so values outside 0..1 extrapolate. */
    fun Fn.mix(other: Fn, amount: Number): Fn = mix(other, param(amount))

    /**
     * Hands over from this function to [other] **at a place along x**: this one before [center], [other] after, with a
     * smootherstep transition [width] wide straddling the centre.
     *
     * A width of zero or less is a hard cut exactly at [center] (this function is used up to it, [other] from it on).
     * Contrast [mix], which blends by amount everywhere rather than switching at a position. Both [center] and [width]
     * may be signals.
     */
    fun Fn.crossfade(other: Fn, center: Fn = param(0.0), width: Fn = param(0.0)): Fn {
        val domain = combinedDomain(this, other).intersect(center.domain).intersect(width.domain)
        return graphFn(
            domain,
            "crossfade",
            node(
                "crossfade",
                children = listOf(fnEdge("left", this), fnEdge("right", other), fnEdge("center", center), fnEdge("width", width)),
                rebuild = { it[0].crossfade(it[1], it[2], it[3]) }
            )
        ) { x ->
            val c = center.eval(x)
            val w = width.eval(x)
            if (w <= 0.0) {
                if (x < c) this.eval(x) else other.eval(x)
            } else {
                val t = clamp01((x - (c - w * 0.5)) / w)
                val ww = smootherstep(t)
                blend(this.eval(x), other.eval(x), ww)
            }
        }
    }

    /** Hands over from this function to [other] at [center], easing across a transition [width] wide. A width of zero or less is a hard cut. */
    fun Fn.crossfade(other: Fn, center: Number, width: Number): Fn =
        crossfade(other, param(center), param(width))

    /** Hands over from this function to [other] at [center] over a transition [width] wide, taking either as a number or an [Fn] and throwing for anything else. */
    fun Fn.crossfade(other: Fn, center: Any, width: Any): Fn =
        crossfade(other, asParam(center, "center"), asParam(width, "width"))

    /**
     * Gates the function to [range]: inside it (both ends included) the original values, outside it [outside].
     *
     * Note what this does *not* do — the function's declared domain is left alone, so `cut` narrows what you hear
     * without narrowing what a sampler will walk. [trim] and [slice] are the same operation under different names.
     */
    fun Fn.cut(range: Domain, outside: Fn = param(0.0)): Fn {
        val d = intersectDomains(domain, outside)
        return graphFn(
            d,
            name,
            node(
                "cut",
                children = listOf(fnEdge("source", this), fnEdge("outside", outside)),
                strings = mapOf("range" to range.toString()),
                rebuild = { it[0].cut(range, it[1]) }
            )
        ) { x -> if (range.containsInclusive(x)) eval(x) else outside.eval(x) }
    }

    /** Gates the function to [range], reading [outside] beyond it. The declared domain is unchanged. */
    fun Fn.cut(range: Domain, outside: Number): Fn = cut(range, param(outside))

    /** Gates the function to [range], reading [outside] beyond it. The declared domain is unchanged. */
    fun Fn.cut(range: ClosedRange<*>, outside: Fn = param(0.0)): Fn =
        cut(range.toDomain(), outside)

    /** Gates the function to [range], reading [outside] beyond it. The declared domain is unchanged. */
    fun Fn.cut(range: ClosedRange<*>, outside: Number): Fn =
        cut(range.toDomain(), param(outside))

    /** Gates the function to [range], reading [outside] beyond it — a synonym of [cut]. The declared domain is unchanged. */
    fun Fn.trim(range: Domain, outside: Fn = param(0.0)): Fn = cut(range, outside)

    /** Gates the function to [range], reading [outside] beyond it — a synonym of [cut]. The declared domain is unchanged. */
    fun Fn.trim(range: Domain, outside: Number): Fn = trim(range, param(outside))

    /** Gates the function to [range], reading [outside] beyond it — a synonym of [cut]. The declared domain is unchanged. */
    fun Fn.trim(range: ClosedRange<*>, outside: Fn = param(0.0)): Fn =
        trim(range.toDomain(), outside)

    /** Gates the function to [range], reading [outside] beyond it — a synonym of [cut]. The declared domain is unchanged. */
    fun Fn.trim(range: ClosedRange<*>, outside: Number): Fn =
        trim(range.toDomain(), param(outside))

    /** Gates the function to [range], reading [outside] beyond it — a synonym of [cut]. The declared domain is unchanged. */
    fun Fn.slice(range: Domain, outside: Fn = param(0.0)): Fn = cut(range, outside)

    /** Gates the function to [range], reading [outside] beyond it — a synonym of [cut]. The declared domain is unchanged. */
    fun Fn.slice(range: Domain, outside: Number): Fn = slice(range, param(outside))

    /** Gates the function to [range], reading [outside] beyond it — a synonym of [cut]. The declared domain is unchanged. */
    fun Fn.slice(range: ClosedRange<*>, outside: Fn = param(0.0)): Fn =
        slice(range.toDomain(), outside)

    /** Gates the function to [range], reading [outside] beyond it — a synonym of [cut]. The declared domain is unchanged. */
    fun Fn.slice(range: ClosedRange<*>, outside: Number): Fn =
        slice(range.toDomain(), param(outside))

    /**
     * Substitutes [replacement] for this function over [range], keeping the original everywhere else — a patch, where
     * [cut] is a gate.
     *
     * The result's domain is the **union** of the two, so a replacement reaching past the original extends it. Nothing
     * is faded at the seams: expect a discontinuity at each end of [range] unless the two functions happen to agree
     * there.
     */
    fun Fn.replace(range: Domain, replacement: Fn): Fn {
        val domain = this.domain.union(replacement.domain)
        return graphFn(
            domain,
            "replace",
            node(
                "replace",
                children = listOf(fnEdge("source", this), fnEdge("replacement", replacement)),
                strings = mapOf("range" to range.toString()),
                rebuild = { it[0].replace(range, it[1]) }
            )
        ) { x -> if (range.containsInclusive(x)) replacement.eval(x) else eval(x) }
    }

    /** Substitutes [replacement] for this function over [range] and keeps the original elsewhere. The result's domain is the union of the two, and the seams are not faded. */
    fun Fn.replace(range: ClosedRange<*>, replacement: Fn): Fn =
        replace(range.toDomain(), replacement)

    /**
     * Multiplies this function by [window] — spelled as a method to make the intent ("apply an envelope") obvious.
     * Exactly `this * window`, which means the result's domain is the **intersection** of the two, so a bounded window
     * crops an unbounded signal.
     */
    fun Fn.window(window: Fn): Fn = this * window

    /**
     * Amplitude modulation: scales this function by `bias + depth * mod`.
     *
     * The [bias] of 1 is what makes this tremolo rather than ring modulation — the carrier stays audible and the
     * modulator only varies its level. Drop the bias to zero (or use [ringMod]) for the harsher, carrier-cancelling
     * version. With `depth = 1` and a bipolar modulator the level swings between 0 and 2, so expect to pull the gain
     * down or the depth to 0.5.
     */
    fun Fn.amplitudeMod(mod: Fn, depth: Fn = param(1.0), bias: Fn = param(1.0)): Fn {
        val d = intersectDomains(domain, mod, depth, bias)
        return graphFn(
            d,
            "am",
            node(
                "amplitudeMod",
                "am",
                listOf(fnEdge("source", this), fnEdge("mod", mod), fnEdge("depth", depth), fnEdge("bias", bias)),
                rebuild = { it[0].amplitudeMod(it[1], it[2], it[3]) }
            )
        ) { x -> eval(x) * (bias.eval(x) + depth.eval(x) * mod.eval(x)) }
    }

    /** Amplitude modulation: scales this function by `bias + depth * mod`. The default bias of 1 keeps the carrier present, making this tremolo rather than ring modulation. */
    fun Fn.amplitudeMod(mod: Fn, depth: Number, bias: Number = 1.0): Fn =
        amplitudeMod(mod, param(depth), param(bias))

    /** Amplitude modulation by `bias + depth * mod`, taking [depth] and [bias] as numbers or [Fn]s and throwing for anything else. */
    fun Fn.amplitudeMod(mod: Fn, depth: Any, bias: Any = 1.0): Fn =
        amplitudeMod(mod, asParam(depth, "depth"), asParam(bias, "bias"))

    /**
     * Ring modulation: multiplies this function by `depth * mod`, with **no bias term** — so wherever the modulator
     * crosses zero the carrier disappears entirely.
     *
     * That is the difference from [amplitudeMod], and the reason ring modulation sounds metallic rather than
     * pulsing: the carrier's own frequency is cancelled and replaced by sum and difference tones.
     */
    fun Fn.ringMod(mod: Fn, depth: Fn = param(1.0)): Fn {
        val d = intersectDomains(domain, mod, depth)
        return graphFn(
            d,
            "rm",
            node(
                "ringMod",
                "rm",
                listOf(fnEdge("source", this), fnEdge("mod", mod), fnEdge("depth", depth)),
                rebuild = { it[0].ringMod(it[1], it[2]) }
            )
        ) { x -> eval(x) * (depth.eval(x) * mod.eval(x)) }
    }

    /** Ring modulation: multiplies this function by `depth * mod` with no bias, so the carrier vanishes wherever the modulator crosses zero. */
    fun Fn.ringMod(mod: Fn, depth: Number): Fn = ringMod(mod, param(depth))

    /**
     * Warps **time** by the modulator: evaluates this function at `x + depth * mod(x)` instead of at x.
     *
     * The time-domain counterpart to [ringMod]'s value-domain multiplication, and the mechanism behind vibrato, phase
     * modulation and [turbulence]. Note that the modulator is sampled at the *original* x, not the warped one, so this
     * is a single displacement rather than a recursive one. Nothing prevents the warp from running the lookup
     * backwards if [depth] is large enough.
     */
    fun Fn.warp(mod: Fn, depth: Fn = param(1.0)): Fn {
        val d = intersectDomains(domain, mod, depth)
        return graphFn(
            d,
            "warp",
            node(
                "warp",
                children = listOf(fnEdge("source", this), fnEdge("mod", mod), fnEdge("depth", depth)),
                rebuild = { it[0].warp(it[1], it[2]) }
            )
        ) { x -> eval(x + depth.eval(x) * mod.eval(x)) }
    }

    /** Warps time by the modulator, evaluating this function at `x + depth * mod(x)` — vibrato and phase modulation in one operator. */
    fun Fn.warp(mod: Fn, depth: Number): Fn = warp(mod, param(depth))

    /**
     * Quantises the **coordinate** to a grid of [step], so the function is only ever read at grid positions — a
     * staircase in time rather than in value.
     *
     * Rounds **down** to the grid (a floor, not a nearest), and the grid is anchored at the origin. Because the grid
     * position can fall outside the domain, [mode] says what to do then — `CLAMP` by default rather than the usual
     * `ZERO`. A step of zero passes x straight through. Very close to [sampleHold], which adds an offset and always
     * reads the raw evaluator.
     */
    fun Fn.quantizeX(step: Fn = param(1.0), mode: EdgeMode = EdgeMode.CLAMP): Fn {
        val d = intersectDomains(domain, step)
        return graphFn(
            d,
            name,
            node(
                "quantizeX",
                children = listOf(fnEdge("source", this), fnEdge("step", step)),
                strings = mapOf("mode" to mode.name),
                rebuild = { it[0].quantizeX(it[1], mode) }
            )
        ) { x ->
            val s = step.eval(x)
            if (s == 0.0) eval(x) else {
                val q = floor(x / s) * s
                evalSafe(q, edge = mode)
            }
        }
    }

    /** Quantises the coordinate to a grid of [step], flooring to the grid so the function reads as a staircase in time. A step of zero passes x through unchanged. */
    fun Fn.quantizeX(step: Number, mode: EdgeMode = EdgeMode.CLAMP): Fn =
        quantizeX(param(step), mode)

    /**
     * Sample-and-hold: reads the function only at multiples of [step] (offset by [offset]) and holds each value until
     * the next one — the classic stepped-random or arpeggio effect.
     *
     * The grid is anchored at [offset] and rounds **down**, so the value at a grid point is held forward, not
     * backward. A [step] of zero passes the function through untouched. Purely stateless: nothing is latched between
     * calls, so it can be sampled in any order. [hold] is the transform spelling.
     */
    fun Fn.sampleHold(step: Fn = param(1.0), offset: Fn = param(0.0)): Fn {
        val d = intersectDomains(domain, step, offset)
        return graphFn(
            d,
            "samplehold",
            node(
                "sampleHold",
                "samplehold",
                listOf(fnEdge("source", this), fnEdge("step", step), fnEdge("offset", offset)),
                rebuild = { it[0].sampleHold(it[1], it[2]) }
            )
        ) { x ->
            val s = step.eval(x)
            if (s == 0.0) eval(x) else {
                val o = offset.eval(x)
                val q = floor((x - o) / s) * s + o
                eval(q)
            }
        }
    }

    /** Sample-and-hold: reads the function only at multiples of [step] from [offset] and holds each value until the next. Stateless; a step of zero is a no-op. */
    fun Fn.sampleHold(step: Number, offset: Number = 0.0): Fn =
        sampleHold(param(step), param(offset))

    /** Sample-and-hold on a grid of [step] from [offset], taking either as a number or an [Fn] and throwing for anything else. */
    fun Fn.sampleHold(step: Any, offset: Any = 0.0): Fn =
        sampleHold(asParam(step, "step"), asParam(offset, "offset"))

    /**
     * Phase modulation — displaces the read position by `depth * mod(x)`. **Exactly [warp]**, under the name the synth
     * literature uses; there is no separate integration step here.
     */
    fun Fn.phaseMod(mod: Fn, depth: Fn = param(1.0)): Fn = warp(mod, depth)

    /** Phase modulation — displaces the read position by `depth * mod(x)`. Exactly [warp], under a synth-flavoured name. */
    fun Fn.phaseMod(mod: Fn, depth: Number): Fn = phaseMod(mod, param(depth))

    /**
     * Frequency modulation — displaces the read position by `depth * mod(x)`.
     *
     * Be aware that this is **the same operation as [warp] and [phaseMod]**, not true frequency modulation: proper FM
     * would displace by the modulator's *integral*. For a slow modulator the two are indistinguishable; for a fast one
     * they are not.
     */
    fun Fn.frequencyMod(mod: Fn, depth: Fn = param(1.0)): Fn = warp(mod, depth)

    /** Frequency modulation — displaces the read position by `depth * mod(x)`. The same operation as [warp] and [phaseMod], not an integrating FM. */
    fun Fn.frequencyMod(mod: Fn, depth: Number): Fn = frequencyMod(mod, param(depth))

    /**
     * A gentle saturator, `v / (1 + |v * amount|)` — compresses large values toward ±1 without ever quite reaching a
     * ceiling, so nothing is ever truly flat-topped.
     *
     * Softer than [softClip] and, unlike it, **not normalized**: a signal already inside ±1 comes out quieter, so
     * expect to make the gain back up. An [amount] of zero collapses the signal to zero rather than leaving it alone.
     */
    fun Fn.saturate(amount: Fn = param(1.0)): Fn {
        val d = intersectDomains(domain, amount)
        return graphFn(
            d,
            name,
            node("saturate", children = listOf(fnEdge("source", this), fnEdge("amount", amount)), rebuild = { it[0].saturate(it[1]) })
        ) { x ->
            val a = amount.eval(x)
            val v = eval(x)
            if (a == 0.0) 0.0 else v / (1.0 + kotlin.math.abs(v * a))
        }
    }

    /** A gentle saturator, `v / (1 + |v * amount|)`: compresses toward ±1 without a hard ceiling, and without normalizing, so it also loses level. An amount of zero gives zero. */
    fun Fn.saturate(amount: Number): Fn = saturate(param(amount))

    /**
     * Displaces the read position by a random amount of up to half [amount] either way — a deterministic wobble in
     * time.
     *
     * The randomness is hashed from [seed] and `floor(x)`, so the displacement is **constant across each unit-wide
     * band of x** and jumps at every integer. That is fine for coarse humanization and wrong if you wanted smooth
     * drift — for that, warp with a noise function instead.
     */
    fun Fn.jitterX(amount: Fn = param(0.0), seed: Long = 0L): Fn {
        val d = intersectDomains(domain, amount)
        return graphFn(
            d,
            "jitter",
            node(
                "jitterX",
                "jitter",
                listOf(fnEdge("source", this), fnEdge("amount", amount)),
                longs = mapOf("seed" to seed),
                rebuild = { it[0].jitterX(it[1], seed) }
            )
        ) { x ->
            val h = hashToUnit(seed, floor(x).toLong()) - 0.5
            eval(x + h * amount.eval(x))
        }
    }

    /** Displaces the read position by up to half [amount] either way, hashed from [seed] and `floor(x)` — so the displacement is constant within each unit band of x. */
    fun Fn.jitterX(amount: Number, seed: Long = 0L): Fn = jitterX(param(amount), seed)

    /**
     * Loops this function every [period] from [origin], forever — a periodic tiling of whatever the function does in one
     * period's worth of x.
     *
     * The lookup wraps, so what repeats is the slice `origin .. origin + period`, not necessarily the function's whole
     * domain; the joins are hard cuts unless the slice happens to meet itself. The result claims [domainOverride] —
     * the whole line by default, which is what makes the repetition unbounded. [origin] defaults to the start of the
     * function's own domain, and a [period] of zero leaves the function alone.
     */
    fun Fn.repeat(
        period: Fn = param(1.0),
        origin: Fn = param(domain.finiteSweep().min),
        domainOverride: Domain = Domain.ALL
    ): Fn {
        val d = intersectDomains(domainOverride, period, origin)
        return graphFn(
            d,
            "repeat",
            node(
                "repeat",
                children = listOf(fnEdge("source", this), fnEdge("period", period), fnEdge("origin", origin)),
                strings = mapOf("domainOverride" to domainOverride.toString()),
                rebuild = { it[0].repeat(it[1], it[2], domainOverride) }
            )
        ) { x ->
            val p = kotlin.math.abs(period.eval(x))
            if (p == 0.0) return@graphFn eval(x)
            val o = origin.eval(x)
            val wrapped = o + ((x - o) % p + p) % p
            eval(wrapped)
        }
    }

    /** Loops this function every [period] from [origin], forever, with hard cuts at the joins. The result claims [domainOverride] — the whole line by default. */
    fun Fn.repeat(period: Number, origin: Number = domain.finiteSweep().min, domainOverride: Domain = Domain.ALL): Fn =
        repeat(param(period), param(origin), domainOverride)

    /** Loops this function every [period] from [origin], taking either as a number or an [Fn] and throwing for anything else. */
    fun Fn.repeat(period: Any, origin: Any = domain.finiteSweep().min, domainOverride: Domain = Domain.ALL): Fn =
        repeat(asParam(period, "period"), asParam(origin, "origin"), domainOverride)

    /**
     * Tiles this function every [period] from [origin], **alternating forwards and backwards** — so the result is
     * continuous at the joins where plain [repeat] would cut.
     *
     * The full pattern is therefore `2 * period` long even though the reflection happens every [period]. Claims
     * [domainOverride], the whole line by default; a [period] of zero leaves the function alone.
     */
    fun Fn.mirrorRepeat(
        period: Fn = param(1.0),
        origin: Fn = param(domain.finiteSweep().min),
        domainOverride: Domain = Domain.ALL
    ): Fn {
        val d = intersectDomains(domainOverride, period, origin)
        return graphFn(
            d,
            "mirror",
            node(
                "mirrorRepeat",
                "mirror",
                listOf(fnEdge("source", this), fnEdge("period", period), fnEdge("origin", origin)),
                strings = mapOf("domainOverride" to domainOverride.toString()),
                rebuild = { it[0].mirrorRepeat(it[1], it[2], domainOverride) }
            )
        ) { x ->
            val len = kotlin.math.abs(period.eval(x))
            if (len == 0.0) return@graphFn eval(x)
            val o = origin.eval(x)
            val two = len * 2.0
            val t = ((x - o) % two + two) % two
            val local = if (t <= len) o + t else o + (two - t)
            eval(local)
        }
    }

    /** Tiles this function every [period] from [origin], alternating forwards and backwards so the joins are continuous. The full pattern is twice the period long. */
    fun Fn.mirrorRepeat(period: Number, origin: Number = domain.finiteSweep().min, domainOverride: Domain = Domain.ALL): Fn =
        mirrorRepeat(param(period), param(origin), domainOverride)

    /** Tiles this function every [period] from [origin] with alternating direction, taking either as a number or an [Fn] and throwing for anything else. */
    fun Fn.mirrorRepeat(period: Any, origin: Any = domain.finiteSweep().min, domainOverride: Domain = Domain.ALL): Fn =
        mirrorRepeat(asParam(period, "period"), asParam(origin, "origin"), domainOverride)

    /**
     * Repeats this function exactly [count] times from [origin] and is **zero outside** that span — the finite
     * counterpart to [repeat].
     *
     * [period] defaults to the function's own domain length, so `f.tile(3)` gives three copies of f back to back.
     * A [count] of zero or less gives the constant zero, and a period of zero makes it zero everywhere. Note the
     * asymmetry with [pulseBurst]: outside the span this is genuinely zero, not an offset value.
     *
     * The declared domain is the tiled span, so plotting or sampling the result covers all [count] copies. A
     * signal-valued [period] or [origin] has no single span to declare, and there the source's own domain is kept.
     */
    fun Fn.tile(count: Int, period: Fn = param(domain.finiteSweep().length), origin: Fn = param(domain.finiteSweep().min)): Fn {
        if (count <= 0) return zero(domain)
        val base = repeat(period, origin, domainOverride = Domain.ALL)
        val literalPeriod = period.literalValue()?.let { kotlin.math.abs(it) }
        val literalOrigin = origin.literalValue()
        // The span the copies occupy *is* this function's extent — it is zero everywhere else by construction.
        // Declaring the source's domain instead (the old behaviour) meant `f.tile(3)` claimed to be one copy
        // long, so plotting or sampling it showed a third of the material it had just built.
        val d = if (literalPeriod != null && literalOrigin != null &&
            literalPeriod.isFinite() && literalPeriod > 0.0 && literalOrigin.isFinite()
        ) {
            Domain.of(literalOrigin, literalOrigin + literalPeriod * count.toDouble())
        } else {
            intersectDomains(domain, period, origin)
        }
        return graphFn(
            d,
            "tile",
            node(
                "tile",
                children = listOf(fnEdge("source", this), fnEdge("period", period), fnEdge("origin", origin)),
                longs = mapOf("count" to count.toLong()),
                rebuild = { it[0].tile(count, it[1], it[2]) }
            )
        ) { x ->
            val p = kotlin.math.abs(period.eval(x))
            if (p == 0.0) return@graphFn 0.0
            val o = origin.eval(x)
            val end = o + p * count
            if (x < o || x > end) 0.0 else base.eval(x)
        }
    }

    /** Repeats this function [count] times every [period] from [origin], and is zero outside that span. A count of zero or less gives the constant zero. */
    fun Fn.tile(count: Int, period: Number, origin: Number = domain.finiteSweep().min): Fn =
        tile(count, param(period), param(origin))

    /** Repeats this function [count] times from [origin] and is zero outside, taking [period] and [origin] as numbers or [Fn]s and throwing for anything else. */
    fun Fn.tile(count: Int, period: Any, origin: Any = domain.finiteSweep().min): Fn =
        tile(count, asParam(period, "period"), asParam(origin, "origin"))

    /**
     * Substitutes [value] for the function beyond [left] units below its domain and [right] units above it — a border of
     * silence around the material, with the original passed through in between.
     *
     * The padding widths are measured **outward from the function's own domain**, and note the sense of the test: inside
     * the padded band the original evaluator is still called, so what you get there is whatever it extrapolates, not
     * [value]. Only beyond the padding does [value] take over. The declared domain is not widened to include the
     * padding.
     */
    fun Fn.pad(left: Fn = param(0.0), right: Fn = param(0.0), value: Fn = param(0.0)): Fn {
        val d = intersectDomains(domain, left, right, value)
        return graphFn(
            d,
            name,
            node(
                "pad",
                children = listOf(fnEdge("source", this), fnEdge("left", left), fnEdge("right", right), fnEdge("value", value)),
                rebuild = { it[0].pad(it[1], it[2], it[3]) }
            )
        ) { x ->
            val l = left.eval(x)
            val rr = right.eval(x)
            if (x < domain.min - l || x > domain.max + rr) value.eval(x) else eval(x)
        }
    }

    /** Substitutes [value] beyond [left] units below the domain and [right] above it; inside that padded band the original evaluator still applies. */
    fun Fn.pad(left: Number, right: Number = 0.0, value: Number = 0.0): Fn =
        pad(param(left), param(right), param(value))

    /** Substitutes [value] beyond the padded band, taking each argument as a number or an [Fn] and throwing for anything else. */
    fun Fn.pad(left: Any, right: Any = 0.0, value: Any = 0.0): Fn =
        pad(asParam(left, "left"), asParam(right, "right"), asParam(value, "value"))

    /** Delays the function by [amount], positive being later — exactly [shift], under the name that suits time-series and audio. A constant delay moves the domain with it. */
    fun Fn.delay(amount: Fn = param(0.0)): Fn = shift(amount)

    /** Delays the function by [amount], positive being later — exactly [shift]. A constant delay moves the domain with it. */
    fun Fn.delay(amount: Number): Fn = delay(param(amount))

    /**
     * Sums [repeats] progressively quieter copies of the function, each [delay] further on — a feed-forward echo.
     *
     * Not a feedback delay line: there is no recursion and no state, just a finite sum, so the tail length is fixed by
     * [repeats] rather than by how long [decay] takes to die away. The cost is that every sample evaluates the source
     * `repeats + 1` times, which compounds through a stack. A [decay] above 1 makes the echoes grow rather than fade.
     */
    fun Fn.echo(delay: Fn = param(1.0), decay: Fn = param(0.5), repeats: Int = 4): Fn {
        val d = intersectDomains(domain, delay, decay)
        return graphFn(
            d,
            "echo",
            node(
                "echo",
                children = listOf(fnEdge("source", this), fnEdge("delay", delay), fnEdge("decay", decay)),
                longs = mapOf("repeats" to repeats.toLong()),
                rebuild = { it[0].echo(it[1], it[2], repeats) }
            )
        ) { x ->
            val dly = delay.eval(x)
            val dec = decay.eval(x)
            var sum = eval(x)
            var gain = 1.0
            for (i in 1..repeats) {
                gain *= dec
                sum += eval(x - dly * i) * gain
            }
            sum
        }
    }

    /** Sums [repeats] progressively quieter copies, each [delay] further on — a finite feed-forward echo with no feedback or state. A decay above 1 grows instead of fading. */
    fun Fn.echo(delay: Number, decay: Number = 0.5, repeats: Int = 4): Fn =
        echo(param(delay), param(decay), repeats)

    /** A finite feed-forward echo of [repeats] copies, taking [delay] and [decay] as numbers or [Fn]s and throwing for anything else. */
    fun Fn.echo(delay: Any, decay: Any = 0.5, repeats: Int = 4): Fn =
        echo(asParam(delay, "delay"), asParam(decay, "decay"), repeats)

    /**
     * The numerical derivative, by central difference over ±[step].
     *
     * Approximate, and the accuracy is yours to manage: too large a [step] smooths real detail away, too small a one
     * loses precision to floating-point cancellation. The domain shrinks by [step] at each end, since the difference
     * needs room either side. Discontinuities come out as tall spikes rather than infinities.
     */
    fun Fn.derivative(step: Double = 1e-3): Fn {
        val newDomain = domain.shrink(step)
        return graphFn(
            newDomain,
            "d/dx",
            node(
                "derivative",
                "d/dx",
                listOf(fnEdge("source", this)),
                numbers = mapOf("step" to step),
                rebuild = { it[0].derivative(step) }
            )
        ) { x ->
            (eval(x + step) - eval(x - step)) / (2.0 * step)
        }
    }

    /**
     * The running integral from [origin] to x, by rectangle summation in steps of [step].
     *
     * **Expensive, and the cost grows with distance**: each evaluation walks the whole way from [origin], so at the
     * default step of 0.001 a sample one unit away costs a thousand evaluations of the source, and sampling a plot of it
     * costs that again for every point. Integrating backwards from [origin] works and yields the negated area. Accuracy
     * is first-order only — halving [step] roughly halves the error, and doubles the cost.
     */
    fun Fn.integral(step: Double = 1e-3, origin: Double = domain.finiteSweep().min): Fn =
        graphFn(
            domain,
            "integral",
            node(
                "integral",
                children = listOf(fnEdge("source", this)),
                numbers = mapOf("step" to step, "origin" to origin),
                rebuild = { it[0].integral(step, origin) }
            )
        ) { x ->
            if (step == 0.0) return@graphFn 0.0
            var acc = 0.0
            val dir = if (x >= origin) 1.0 else -1.0
            val stepSize = step * dir
            var t = origin
            val steps = ((x - origin) / stepSize).toInt()
            repeat(kotlin.math.abs(steps)) {
                acc += eval(t) * stepSize
                t += stepSize
            }
            val remainder = x - t
            if (remainder != 0.0) acc += eval(t) * remainder
            acc
        }

    /**
     * Convolves this function with [kernel], approximated by [samples] rectangles across the kernel's domain.
     *
     * The result's domain is the sum of the two domains (the usual widening), or the whole line if either is unbounded.
     * Costs [samples] evaluations of *both* functions per output sample, so a 64-tap convolution inside a plot is
     * already tens of thousands of evaluations. A kernel domain that is empty or unbounded yields zero.
     *
     * With `normalize = false` this is an integral, so the result scales with the kernel's area — the right thing for an
     * impulse response. With `normalize = true` it is the kernel-weighted **mean** of the source instead, which keeps
     * the level for a blur: convolving a constant returns that constant, whatever the kernel's width or [samples].
     * ([blur] is the same idea with a Gaussian built in.)
     */
    fun Fn.convolve(
        kernel: Fn,
        samples: Int = 64,
        kernelDomain: Domain = kernel.domain,
        normalize: Boolean = false
    ): Fn {
        val resultDomain = if (domain.isFinite && kernel.domain.isFinite) {
            Domain(domain.min + kernel.domain.min, domain.max + kernel.domain.max)
        } else Domain.ALL
        return graphFn(
            resultDomain,
            "convolve",
            "convolve",
            children = listOf(fnEdge("source", this), fnEdge("kernel", kernel)),
            numbers = mapOf("samples" to samples.toDouble()),
            strings = mapOf("kernelDomain" to kernelDomain.toString()),
            booleans = mapOf("normalize" to normalize),
            rebuild = { children -> children[0].convolve(children[1], samples, kernelDomain, normalize) }
        ) { x ->
            if (samples <= 0) return@graphFn 0.0
            val kd = kernelDomain
            if (kd._isEmpty) return@graphFn 0.0
            val len = kd.length
            if (!len.isFinite()) return@graphFn 0.0
            val step = if (samples == 1) 0.0 else len / (samples - 1).toDouble()
            var sum = 0.0
            var weight = 0.0
            for (i in 0 until samples) {
                val t = kd.min + step * i.toDouble()
                val k = kernel.eval(t)
                sum += eval(x - t) * k
                weight += k
            }
            // Un-normalized this is an integral, so the rectangle width belongs in it. Normalized it is
            // the kernel-weighted *mean* of the source, which must not carry the step: dividing the
            // already-scaled sum by the unscaled weight left the result a factor of `step` small — a
            // 63x drop at the default samples = 64 across a unit-wide kernel.
            if (normalize) return@graphFn if (weight == 0.0) 0.0 else sum / weight
            if (step == 0.0) sum else sum * step
        }
    }

    /**
     * Cross-correlates this function with [kernel] — [convolve] with the kernel reversed about its own centre, which is
     * the difference between "how does this respond to that" and "how much does this look like that".
     *
     * Never normalized, so the result scales with both signals' amplitudes; peaks mark where the pattern matches best
     * rather than giving a bounded similarity score. Same cost profile as [convolve].
     */
    fun Fn.correlate(
        kernel: Fn,
        samples: Int = 64,
        kernelDomain: Domain = kernel.domain
    ): Fn = convolve(kernel.reverse(param(kernelDomain.center)), samples, kernelDomain, normalize = false)

    /**
     * A box smoother: the mean of the function over a [window]-wide span centred on each x.
     *
     * The window is **centred**, so this smooths symmetrically and does not lag the way a causal filter would — and it
     * therefore reads [window]/2 into the future, which matters if the source is only defined up to a point. A window of
     * zero passes the function through unchanged, and so does a [samples] of one. The mean is a trapezoid estimate, so
     * it is exact for a constant however few samples you take: smoothing a flat signal cannot change its level, which
     * is what makes `f - f.movingAverage(w)` a usable high-pass. Costs [samples] evaluations per output sample;
     * [lowPass] is the transform spelling, and [blur] is the Gaussian-weighted alternative.
     */
    fun Fn.movingAverage(window: Fn = param(1.0), samples: Int = 64): Fn {
        val d = intersectDomains(domain, window)
        return graphFn(
            d,
            "movingAverage",
            "movingAverage",
            children = listOf(fnEdge("source", this), fnEdge("window", window)),
            numbers = mapOf("samples" to samples.toDouble()),
            rebuild = { children -> children[0].movingAverage(children[1], samples) }
        ) { x ->
            if (samples <= 0) return@graphFn 0.0
            val w = window.eval(x)
            if (w == 0.0) return@graphFn eval(x)
            // One probe can only sensibly be the centre, and the trapezoid rule below would divide by zero.
            if (samples == 1) return@graphFn eval(x)
            val half = w * 0.5
            val step = w / (samples - 1).toDouble()
            // Trapezoid, not a rectangle sum: the probes include *both* endpoints of the window, so counting
            // each of them fully and dividing by the window width answered `samples / (samples - 1)` times the
            // mean — 2x at samples = 2, and 1.6% high at the default 64, which no "mean" should ever be. Half
            // weights at the ends make it exact for a constant at any sample count, as `blur` already was.
            var sum = 0.0
            var t = -half
            for (i in 0 until samples) {
                val v = eval(x + t)
                sum += if (i == 0 || i == samples - 1) v * 0.5 else v
                t += step
            }
            sum / (samples - 1).toDouble()
        }
    }

    /** A centred box smoother: the mean over a [window]-wide span around each x. A window of zero is a no-op; costs [samples] evaluations per sample. */
    fun Fn.movingAverage(window: Number, samples: Int = 64): Fn =
        movingAverage(param(window), samples)

    /**
     * A Gaussian smoother of standard deviation [sigma] — softer than [movingAverage], with no ringing, and the usual
     * choice for taking the edge off a curve.
     *
     * The kernel is truncated at ±3 sigma and its weights are normalized, so the level is preserved. Centred, so it
     * reads symmetrically into the future as well as the past. A [sigma] of zero passes the function through unchanged.
     * Costs [samples] evaluations of the source per output sample.
     */
    fun Fn.blur(sigma: Fn = param(1.0), samples: Int = 64): Fn {
        val d = intersectDomains(domain, sigma)
        return graphFn(
            d,
            "blur",
            "blur",
            children = listOf(fnEdge("source", this), fnEdge("sigma", sigma)),
            numbers = mapOf("samples" to samples.toDouble()),
            rebuild = { children -> children[0].blur(children[1], samples) }
        ) { x ->
            if (samples <= 0) return@graphFn 0.0
            val s = sigma.eval(x)
            if (s == 0.0) return@graphFn eval(x)
            val half = s * 3.0
            val step = if (samples == 1) 0.0 else (half * 2.0) / (samples - 1).toDouble()
            var sum = 0.0
            var weight = 0.0
            var t = -half
            for (i in 0 until samples) {
                val w = kotlin.math.exp(-0.5 * (t / s).pow(2.0))
                sum += eval(x + t) * w
                weight += w
                t += step
            }
            if (weight == 0.0) 0.0 else sum / weight
        }
    }

    /** A Gaussian smoother of standard deviation [sigma], truncated at ±3 sigma and normalized so the level is kept. A sigma of zero is a no-op. */
    fun Fn.blur(sigma: Number, samples: Int = 64): Fn =
        blur(param(sigma), samples)

    // Windows
    /**
     * The Hann window: a raised cosine reaching **zero at both ends** of [domain] and 1 at the centre.
     *
     * The default fade for anything that has to be spliced or overlapped, since its ends meet zero smoothly. Multiply it
     * onto a signal (`f * hannWindow(d)`, or `f.window(...)`) to shape it. Evaluated on the normalized position within
     * [domain] and clamped, so outside the domain it holds at zero or one rather than going negative. Where you want a
     * flat middle rather than a single peak, use [spliceWindow].
     */
    fun hannWindow(domain: Domain = Domain.DEFAULT): Fn =
        graphFn(domain, "hann", "hannWindow", rebuild = { hannWindow(domain) }) { x ->
            val t = clamp01(domain.finiteSweep().alphaOf(x))
            0.5 - 0.5 * kotlin.math.cos(TAU * t)
        }

    /** The Hann window over the given range: a raised cosine, zero at both ends and 1 at the centre. */
    fun hannWindow(domain: ClosedRange<*>): Fn =
        hannWindow(domain.toDomain())

    /**
     * The Hamming window: like [hannWindow], but stopping at **0.08 rather than zero** at the ends.
     *
     * That non-zero pedestal is the whole difference — better sidelobe behaviour for spectral work, and a small
     * discontinuity if you use it to splice, where Hann would have faded away cleanly.
     */
    fun hammingWindow(domain: Domain = Domain.DEFAULT): Fn =
        graphFn(domain, "hamming", "hammingWindow", rebuild = { hammingWindow(domain) }) { x ->
            val t = clamp01(domain.finiteSweep().alphaOf(x))
            0.54 - 0.46 * kotlin.math.cos(TAU * t)
        }

    /** The Hamming window over the given range — a raised cosine that stops at 0.08 rather than zero at the ends. */
    fun hammingWindow(domain: ClosedRange<*>): Fn =
        hammingWindow(domain.toDomain())

    /**
     * The Blackman window: a three-term cosine taper, narrower in the middle than [hannWindow] and with markedly lower
     * spectral sidelobes.
     *
     * Reaches zero at both ends. The trade is the usual one — cleaner spectrum, wider main lobe, so it discards more of
     * the material it is applied to.
     */
    fun blackmanWindow(domain: Domain = Domain.DEFAULT): Fn =
        graphFn(domain, "blackman", "blackmanWindow", rebuild = { blackmanWindow(domain) }) { x ->
            val t = clamp01(domain.finiteSweep().alphaOf(x))
            0.42 - 0.5 * kotlin.math.cos(TAU * t) + 0.08 * kotlin.math.cos(TAU * 2.0 * t)
        }

    /** The Blackman window over the given range — a three-term cosine taper reaching zero at both ends, with low spectral sidelobes. */
    fun blackmanWindow(domain: ClosedRange<*>): Fn =
        blackmanWindow(domain.toDomain())

    /**
     * A Gaussian window centred in [domain], with [sigma] measured as a **fraction of the domain** rather than in x
     * units.
     *
     * At the default `sigma = 0.4` the ends have fallen to about 0.46 — nowhere near zero, because a Gaussian never
     * reaches it. That is the trap: for splicing or overlap-add use a smaller sigma or reach for [hannWindow], which
     * genuinely closes. Not clamped outside the domain, so the tails continue to decay there. A sigma of zero gives the
     * constant zero.
     */
    fun gaussianWindow(domain: Domain = Domain.DEFAULT, sigma: Fn = param(0.4)): Fn {
        val d = intersectDomains(domain, sigma)
        return graphFn(
            d,
            "gausswin",
            "gaussianWindow",
            children = listOf(fnEdge("sigma", sigma)),
            strings = mapOf("shapeDomain" to domain.toString()),
            rebuild = { children -> gaussianWindow(domain, children[0]) }
        ) { x ->
            val t = domain.finiteSweep().alphaOf(x) - 0.5
            val s = sigma.eval(x)
            if (s == 0.0) 0.0 else kotlin.math.exp(-0.5 * (t / s).pow(2.0))
        }
    }

    /** A Gaussian window centred in [domain], with [sigma] as a fraction of the domain's width. It never reaches zero at the ends — use [hannWindow] if you need it to. */
    fun gaussianWindow(domain: Domain = Domain.DEFAULT, sigma: Number): Fn =
        gaussianWindow(domain, param(sigma))

    /** A Gaussian window centred in the given range, with [sigma] as a fraction of its width. It never reaches zero at the ends. */
    fun gaussianWindow(domain: ClosedRange<*>, sigma: Fn = param(0.4)): Fn =
        gaussianWindow(domain.toDomain(), sigma)

    /** A Gaussian window centred in the given range, with [sigma] as a fraction of its width. It never reaches zero at the ends. */
    fun gaussianWindow(domain: ClosedRange<*>, sigma: Number): Fn =
        gaussianWindow(domain.toDomain(), param(sigma))

    // Noise and stochastic processes
    private fun mix64(z: Long): Long {
        var x = z
        x = (x xor (x ushr 30)) * -4658895280553007687L
        x = (x xor (x ushr 27)) * -7723592293110705685L
        return x xor (x ushr 31)
    }

    private fun hashToUnit(seed: Long, x: Long): Double {
        val h = mix64(x + seed)
        val bits = h ushr 11
        return bits.toDouble() / (1L shl 53).toDouble()
    }

    private fun hashToSigned(seed: Long, x: Long): Double = hashToUnit(seed, x) * 2.0 - 1.0

    private data class SampleStats(val min: Double, val max: Double, val mean: Double, val variance: Double)

    private fun sampleStatsRandom(fn: Fn, domain: Domain, samples: Int, seed: Long): SampleStats? {
        if (samples <= 0 || domain._isEmpty) return null
        val sweep = domain.finiteSweep()
        val len = sweep.length
        var minV = Double.POSITIVE_INFINITY
        var maxV = Double.NEGATIVE_INFINITY
        var mean = 0.0
        var m2 = 0.0
        var n = 0
        for (i in 0 until samples) {
            val x = sweep.min + hashToUnit(seed, i.toLong()) * len
            val v = fn.eval(x)
            if (!v.isFinite()) continue
            if (v < minV) minV = v
            if (v > maxV) maxV = v
            n++
            val delta = v - mean
            mean += delta / n
            val delta2 = v - mean
            m2 += delta * delta2
        }
        if (n == 0 || !minV.isFinite() || !maxV.isFinite()) return null
        val variance = if (n > 1) m2 / (n - 1).toDouble() else 0.0
        return SampleStats(minV, maxV, mean, variance)
    }

    private fun valueNoiseAt(seed: Long, frequency: Double, x: Double): Double {
        val xf = x * frequency
        val i = floor(xf).toLong()
        val t = smootherstep(fract(xf))
        val a = hashToSigned(seed, i)
        val b = hashToSigned(seed, i + 1)
        return blend(a, b, t)
    }

    private fun perlinNoiseAt(seed: Long, frequency: Double, x: Double): Double {
        val xf = x * frequency
        val i0 = floor(xf).toLong()
        val i1 = i0 + 1
        val t = xf - floor(xf)
        val g0 = if (hashToSigned(seed, i0) >= 0.0) 1.0 else -1.0
        val g1 = if (hashToSigned(seed, i1) >= 0.0) 1.0 else -1.0
        val v0 = g0 * t
        val v1 = g1 * (t - 1.0)
        val u = smootherstep(t)
        return blend(v0, v1, u)
    }

    /**
     * White noise: an independent random value in `-amplitude .. amplitude` for each cell of width `1 / frequency`,
     * with a hard jump between cells.
     *
     * [frequency] is a **cell count per unit x**, not a spectral bandwidth — raising it makes the noise finer, not
     * brighter in any filtered sense. Piecewise constant and therefore discontinuous by nature; [valueNoise] is the
     * smooth relative. Wholly deterministic: the value at a given x depends only on [seed] and the cell index, so it is
     * reproducible and randomly seekable, with no state and no generator to advance. Domain defaults to `0.0..1.0`.
     */
    fun whiteNoise(
        seed: Long = 0L,
        frequency: Fn = param(1.0),
        amplitude: Fn = param(1.0),
        domain: Domain = Domain.DEFAULT
    ): Fn {
        val d = intersectDomains(domain, frequency, amplitude)
        return graphFn(
            d,
            "white",
            "whiteNoise",
            children = listOf(fnEdge("frequency", frequency), fnEdge("amplitude", amplitude)),
            longs = mapOf("seed" to seed),
            rebuild = { children -> whiteNoise(seed, children[0], children[1], domain) }
        ) { x ->
            val freq = frequency.eval(x)
            val amp = amplitude.eval(x)
            val i = floor(x * freq).toLong()
            hashToSigned(seed, i) * amp
        }
    }

    /** White noise: an independent random value in `±amplitude` per cell of width `1 / frequency`, jumping between cells. Deterministic in [seed] and position, so it is reproducible and seekable. */
    fun whiteNoise(
        seed: Long = 0L,
        frequency: Number,
        amplitude: Number = 1.0,
        domain: Domain = Domain.DEFAULT
    ): Fn = whiteNoise(seed, param(frequency), param(amplitude), domain)

    /** White noise: an independent random value in `±amplitude` per cell of width `1 / frequency`, jumping between cells. Deterministic in [seed] and position, so it is reproducible and seekable. */
    fun whiteNoise(
        seed: Long = 0L,
        frequency: Number,
        amplitude: Number = 1.0,
        domain: ClosedRange<*>
    ): Fn = whiteNoise(seed, frequency, amplitude, domain.toDomain())

    /** White noise: an independent random value in `±amplitude` per cell of width `1 / frequency`, jumping between cells. Deterministic in [seed] and position, so it is reproducible and seekable. */
    fun whiteNoise(
        seed: Long = 0L,
        frequency: Fn = param(1.0),
        amplitude: Fn = param(1.0),
        domain: ClosedRange<*>
    ): Fn = whiteNoise(seed, frequency, amplitude, domain.toDomain())

    /** White noise per cell of width `1 / frequency`, taking [frequency] and [amplitude] as numbers or [Fn]s and throwing for anything else. */
    fun whiteNoise(
        seed: Long = 0L,
        frequency: Any,
        amplitude: Any = 1.0,
        domain: Domain = Domain.DEFAULT
    ): Fn = whiteNoise(seed, asParam(frequency, "frequency"), asParam(amplitude, "amplitude"), domain)

    /** White noise per cell of width `1 / frequency`, taking [frequency] and [amplitude] as numbers or [Fn]s and throwing for anything else. */
    fun whiteNoise(
        seed: Long = 0L,
        frequency: Any,
        amplitude: Any = 1.0,
        domain: ClosedRange<*>
    ): Fn = whiteNoise(seed, frequency, amplitude, domain.toDomain())

    /**
     * Smooth value noise: a random value per lattice cell of width `1 / frequency`, **interpolated** between cells with
     * a smootherstep, so the result is continuous and gently wandering rather than jumping.
     *
     * The default workhorse for organic variation, and the basis of [fbm]. Range is roughly `±amplitude`, reaching the
     * extremes only where a lattice point happens to. [frequency] is lattice cells per unit x, so it sets the wavelength
     * of the wobble. Deterministic in [seed] and position — no state, freely seekable. [perlinNoise] is the
     * gradient-based alternative, which looks subtly different and is quieter. Domain defaults to `0.0..1.0`.
     */
    fun valueNoise(
        seed: Long = 0L,
        frequency: Fn = param(1.0),
        amplitude: Fn = param(1.0),
        domain: Domain = Domain.DEFAULT
    ): Fn {
        val d = intersectDomains(domain, frequency, amplitude)
        return graphFn(
            d,
            "valuenoise",
            "valueNoise",
            children = listOf(fnEdge("frequency", frequency), fnEdge("amplitude", amplitude)),
            longs = mapOf("seed" to seed),
            rebuild = { children -> valueNoise(seed, children[0], children[1], domain) }
        ) { x ->
            val freq = frequency.eval(x)
            val amp = amplitude.eval(x)
            valueNoiseAt(seed, freq, x) * amp
        }
    }

    /** Smooth value noise: a random value per lattice cell of width `1 / frequency`, interpolated between cells so the result is continuous. Range roughly `±amplitude`; deterministic in [seed] and position. */
    fun valueNoise(
        seed: Long = 0L,
        frequency: Number,
        amplitude: Number = 1.0,
        domain: Domain = Domain.DEFAULT
    ): Fn = valueNoise(seed, param(frequency), param(amplitude), domain)

    /** Smooth value noise: a random value per lattice cell of width `1 / frequency`, interpolated between cells so the result is continuous. Range roughly `±amplitude`; deterministic in [seed] and position. */
    fun valueNoise(
        seed: Long = 0L,
        frequency: Number,
        amplitude: Number = 1.0,
        domain: ClosedRange<*>
    ): Fn = valueNoise(seed, frequency, amplitude, domain.toDomain())

    /** Smooth value noise: a random value per lattice cell of width `1 / frequency`, interpolated between cells so the result is continuous. Range roughly `±amplitude`; deterministic in [seed] and position. */
    fun valueNoise(
        seed: Long = 0L,
        frequency: Fn = param(1.0),
        amplitude: Fn = param(1.0),
        domain: ClosedRange<*>
    ): Fn = valueNoise(seed, frequency, amplitude, domain.toDomain())

    /** Smooth value noise on a lattice of width `1 / frequency`, taking [frequency] and [amplitude] as numbers or [Fn]s and throwing for anything else. */
    fun valueNoise(
        seed: Long = 0L,
        frequency: Any,
        amplitude: Any = 1.0,
        domain: Domain = Domain.DEFAULT
    ): Fn = valueNoise(seed, asParam(frequency, "frequency"), asParam(amplitude, "amplitude"), domain)

    /** Smooth value noise on a lattice of width `1 / frequency`, taking [frequency] and [amplitude] as numbers or [Fn]s and throwing for anything else. */
    fun valueNoise(
        seed: Long = 0L,
        frequency: Any,
        amplitude: Any = 1.0,
        domain: ClosedRange<*>
    ): Fn = valueNoise(seed, frequency, amplitude, domain.toDomain())

    /**
     * One-dimensional Perlin-style **gradient** noise: each lattice point of width `1 / frequency` carries a random
     * slope rather than a random height, so the curve passes through zero at every lattice point and bulges between them.
     *
     * The practical differences from [valueNoise] are worth knowing. It is **quieter** — the useful range is closer to
     * `±amplitude/2` — and it never strays far from zero, which makes it well behaved as a modulation source but a poor
     * choice when you want the full range. This being one dimension, the "gradients" are only ±1, so the result is
     * simpler than a 2D Perlin field. Deterministic in [seed] and position; domain defaults to `0.0..1.0`.
     */
    fun perlinNoise(
        seed: Long = 0L,
        frequency: Fn = param(1.0),
        amplitude: Fn = param(1.0),
        domain: Domain = Domain.DEFAULT
    ): Fn {
        val d = intersectDomains(domain, frequency, amplitude)
        return graphFn(
            d,
            "perlin",
            "perlinNoise",
            children = listOf(fnEdge("frequency", frequency), fnEdge("amplitude", amplitude)),
            longs = mapOf("seed" to seed),
            rebuild = { children -> perlinNoise(seed, children[0], children[1], domain) }
        ) { x ->
            val freq = frequency.eval(x)
            val amp = amplitude.eval(x)
            perlinNoiseAt(seed, freq, x) * amp
        }
    }

    /** Perlin-style gradient noise on a lattice of width `1 / frequency`: zero at every lattice point, bulging between them, and quieter than [valueNoise] at the same amplitude. */
    fun perlinNoise(
        seed: Long = 0L,
        frequency: Number,
        amplitude: Number = 1.0,
        domain: Domain = Domain.DEFAULT
    ): Fn = perlinNoise(seed, param(frequency), param(amplitude), domain)

    /** Perlin-style gradient noise on a lattice of width `1 / frequency`: zero at every lattice point, bulging between them, and quieter than [valueNoise] at the same amplitude. */
    fun perlinNoise(
        seed: Long = 0L,
        frequency: Number,
        amplitude: Number = 1.0,
        domain: ClosedRange<*>
    ): Fn = perlinNoise(seed, frequency, amplitude, domain.toDomain())

    /** Perlin-style gradient noise on a lattice of width `1 / frequency`: zero at every lattice point, bulging between them, and quieter than [valueNoise] at the same amplitude. */
    fun perlinNoise(
        seed: Long = 0L,
        frequency: Fn = param(1.0),
        amplitude: Fn = param(1.0),
        domain: ClosedRange<*>
    ): Fn = perlinNoise(seed, frequency, amplitude, domain.toDomain())

    /** Perlin-style gradient noise on a lattice of width `1 / frequency`, taking [frequency] and [amplitude] as numbers or [Fn]s and throwing for anything else. */
    fun perlinNoise(
        seed: Long = 0L,
        frequency: Any,
        amplitude: Any = 1.0,
        domain: Domain = Domain.DEFAULT
    ): Fn = perlinNoise(seed, asParam(frequency, "frequency"), asParam(amplitude, "amplitude"), domain)

    /** Perlin-style gradient noise on a lattice of width `1 / frequency`, taking [frequency] and [amplitude] as numbers or [Fn]s and throwing for anything else. */
    fun perlinNoise(
        seed: Long = 0L,
        frequency: Any,
        amplitude: Any = 1.0,
        domain: ClosedRange<*>
    ): Fn = perlinNoise(seed, frequency, amplitude, domain.toDomain())

    /**
     * Fractal Brownian motion: [octaves] layers of [valueNoise] summed, each layer [lacunarity] times finer and [gain]
     * times quieter than the last — noise with detail at several scales at once.
     *
     * The result is **normalized by the summed layer weights and then scaled by [amplitude]**, so it stays within
     * roughly `±amplitude` however many octaves you add; adding octaves buys detail, not level. The defaults (lacunarity 2, gain 0.5) are the classic
     * halving pair, giving the familiar 1/f look; raising the gain toward 1 makes it rougher, lowering it smoother.
     * Cost is linear in [octaves], and an octave count below one is treated as one. Each layer uses a different seed
     * derived from [seed], so the whole thing is deterministic. Domain defaults to `0.0..1.0`.
     */
    fun fbm(
        seed: Long = 0L,
        frequency: Fn = param(1.0),
        amplitude: Fn = param(1.0),
        octaves: Int = 5,
        lacunarity: Fn = param(2.0),
        gain: Fn = param(0.5),
        domain: Domain = Domain.DEFAULT
    ): Fn {
        val d = intersectDomains(domain, frequency, amplitude, lacunarity, gain)
        return graphFn(
            d,
            "fbm",
            "fbm",
            children = listOf(
                fnEdge("frequency", frequency),
                fnEdge("amplitude", amplitude),
                fnEdge("lacunarity", lacunarity),
                fnEdge("gain", gain)
            ),
            numbers = mapOf("octaves" to octaves.toDouble()),
            longs = mapOf("seed" to seed),
            rebuild = { children -> fbm(seed, children[0], children[1], octaves, children[2], children[3], domain) }
        ) { x ->
            // The layer weights are accumulated at unit amplitude and the whole normalized sum is
            // scaled by `amplitude` at the end. Accumulating *from* `amplitude` instead — which is what
            // this did until 2026-07-28 — cancels it out of `sum / norm` exactly, so the parameter was
            // inert while four KDoc blocks promised it set the output range.
            val outputAmplitude = amplitude.eval(x)
            var amp = 1.0
            var freq = frequency.eval(x)
            val lac = lacunarity.eval(x)
            val g = gain.eval(x)
            var sum = 0.0
            var norm = 0.0
            for (i in 0 until max(1, octaves)) {
                val v = valueNoiseAt(seed + i, freq, x)
                sum += v * amp
                norm += amp
                amp *= g
                freq *= lac
            }
            if (norm == 0.0) 0.0 else outputAmplitude * sum / norm
        }
    }

    /** Fractal Brownian motion: [octaves] layers of [valueNoise], each [lacunarity] times finer and [gain] times quieter. Normalized, so the range stays near `±amplitude` however many octaves you add. */
    fun fbm(
        seed: Long = 0L,
        frequency: Number,
        amplitude: Number = 1.0,
        octaves: Int = 5,
        lacunarity: Number = 2.0,
        gain: Number = 0.5,
        domain: Domain = Domain.DEFAULT
    ): Fn = fbm(seed, param(frequency), param(amplitude), octaves, param(lacunarity), param(gain), domain)

    /** Fractal Brownian motion: [octaves] layers of [valueNoise], each [lacunarity] times finer and [gain] times quieter. Normalized, so the range stays near `±amplitude` however many octaves you add. */
    fun fbm(
        seed: Long = 0L,
        frequency: Number,
        amplitude: Number = 1.0,
        octaves: Int = 5,
        lacunarity: Number = 2.0,
        gain: Number = 0.5,
        domain: ClosedRange<*>
    ): Fn = fbm(seed, frequency, amplitude, octaves, lacunarity, gain, domain.toDomain())

    /** Fractal Brownian motion over [octaves] layers of [valueNoise], taking the numeric parameters as numbers or [Fn]s and throwing for anything else. */
    fun fbm(
        seed: Long = 0L,
        frequency: Any,
        amplitude: Any = 1.0,
        octaves: Int = 5,
        lacunarity: Any = 2.0,
        gain: Any = 0.5,
        domain: Domain = Domain.DEFAULT
    ): Fn = fbm(seed, asParam(frequency, "frequency"), asParam(amplitude, "amplitude"), octaves, asParam(lacunarity, "lacunarity"), asParam(gain, "gain"), domain)

    /** Fractal Brownian motion over [octaves] layers of [valueNoise], taking the numeric parameters as numbers or [Fn]s and throwing for anything else. */
    fun fbm(
        seed: Long = 0L,
        frequency: Any,
        amplitude: Any = 1.0,
        octaves: Int = 5,
        lacunarity: Any = 2.0,
        gain: Any = 0.5,
        domain: ClosedRange<*>
    ): Fn = fbm(seed, frequency, amplitude, octaves, lacunarity, gain, domain.toDomain())

    /** Fractal Brownian motion: [octaves] layers of [valueNoise], each [lacunarity] times finer and [gain] times quieter. Normalized, so the range stays near `±amplitude` however many octaves you add. */
    fun fbm(
        seed: Long = 0L,
        frequency: Fn = param(1.0),
        amplitude: Fn = param(1.0),
        octaves: Int = 5,
        lacunarity: Fn = param(2.0),
        gain: Fn = param(0.5),
        domain: ClosedRange<*>
    ): Fn = fbm(seed, frequency, amplitude, octaves, lacunarity, gain, domain.toDomain())

    /**
     * Fractal Brownian motion built from [perlinNoise] layers instead of [valueNoise] ones — [fbm] with a different grain.
     *
     * Same structure and same normalization, so the range stays near `±amplitude` however many [octaves] you stack. The
     * character differs: because each Perlin layer is zero at its lattice points and quieter overall, the sum reads
     * smoother and less blocky, at the cost of a narrower effective range. Cost is linear in [octaves]; deterministic in
     * [seed]. Domain defaults to `0.0..1.0`.
     */
    fun fbmPerlin(
        seed: Long = 0L,
        frequency: Fn = param(1.0),
        amplitude: Fn = param(1.0),
        octaves: Int = 5,
        lacunarity: Fn = param(2.0),
        gain: Fn = param(0.5),
        domain: Domain = Domain.DEFAULT
    ): Fn {
        val d = intersectDomains(domain, frequency, amplitude, lacunarity, gain)
        return graphFn(
            d,
            "fbmPerlin",
            "fbmPerlin",
            children = listOf(
                fnEdge("frequency", frequency),
                fnEdge("amplitude", amplitude),
                fnEdge("lacunarity", lacunarity),
                fnEdge("gain", gain)
            ),
            numbers = mapOf("octaves" to octaves.toDouble()),
            longs = mapOf("seed" to seed),
            rebuild = { children -> fbmPerlin(seed, children[0], children[1], octaves, children[2], children[3], domain) }
        ) { x ->
            // The layer weights are accumulated at unit amplitude and the whole normalized sum is
            // scaled by `amplitude` at the end. Accumulating *from* `amplitude` instead — which is what
            // this did until 2026-07-28 — cancels it out of `sum / norm` exactly, so the parameter was
            // inert while four KDoc blocks promised it set the output range.
            val outputAmplitude = amplitude.eval(x)
            var amp = 1.0
            var freq = frequency.eval(x)
            val lac = lacunarity.eval(x)
            val g = gain.eval(x)
            var sum = 0.0
            var norm = 0.0
            for (i in 0 until max(1, octaves)) {
                val v = perlinNoiseAt(seed + i, freq, x)
                sum += v * amp
                norm += amp
                amp *= g
                freq *= lac
            }
            if (norm == 0.0) 0.0 else outputAmplitude * sum / norm
        }
    }

    /** Fractal Brownian motion built from [perlinNoise] layers: [octaves] of them, each [lacunarity] times finer and [gain] times quieter. Normalized, so the range stays near `±amplitude`. */
    fun fbmPerlin(
        seed: Long = 0L,
        frequency: Number,
        amplitude: Number = 1.0,
        octaves: Int = 5,
        lacunarity: Number = 2.0,
        gain: Number = 0.5,
        domain: Domain = Domain.DEFAULT
    ): Fn = fbmPerlin(seed, param(frequency), param(amplitude), octaves, param(lacunarity), param(gain), domain)

    /** Fractal Brownian motion built from [perlinNoise] layers: [octaves] of them, each [lacunarity] times finer and [gain] times quieter. Normalized, so the range stays near `±amplitude`. */
    fun fbmPerlin(
        seed: Long = 0L,
        frequency: Number,
        amplitude: Number = 1.0,
        octaves: Int = 5,
        lacunarity: Number = 2.0,
        gain: Number = 0.5,
        domain: ClosedRange<*>
    ): Fn = fbmPerlin(seed, frequency, amplitude, octaves, lacunarity, gain, domain.toDomain())

    /** Fractal Brownian motion over [octaves] layers of [perlinNoise], taking the numeric parameters as numbers or [Fn]s and throwing for anything else. */
    fun fbmPerlin(
        seed: Long = 0L,
        frequency: Any,
        amplitude: Any = 1.0,
        octaves: Int = 5,
        lacunarity: Any = 2.0,
        gain: Any = 0.5,
        domain: Domain = Domain.DEFAULT
    ): Fn = fbmPerlin(seed, asParam(frequency, "frequency"), asParam(amplitude, "amplitude"), octaves, asParam(lacunarity, "lacunarity"), asParam(gain, "gain"), domain)

    /** Fractal Brownian motion over [octaves] layers of [perlinNoise], taking the numeric parameters as numbers or [Fn]s and throwing for anything else. */
    fun fbmPerlin(
        seed: Long = 0L,
        frequency: Any,
        amplitude: Any = 1.0,
        octaves: Int = 5,
        lacunarity: Any = 2.0,
        gain: Any = 0.5,
        domain: ClosedRange<*>
    ): Fn = fbmPerlin(seed, frequency, amplitude, octaves, lacunarity, gain, domain.toDomain())

    /** Fractal Brownian motion built from [perlinNoise] layers: [octaves] of them, each [lacunarity] times finer and [gain] times quieter. Normalized, so the range stays near `±amplitude`. */
    fun fbmPerlin(
        seed: Long = 0L,
        frequency: Fn = param(1.0),
        amplitude: Fn = param(1.0),
        octaves: Int = 5,
        lacunarity: Fn = param(2.0),
        gain: Fn = param(0.5),
        domain: ClosedRange<*>
    ): Fn = fbmPerlin(seed, frequency, amplitude, octaves, lacunarity, gain, domain.toDomain())

    /**
     * A random walk: the accumulated sum of one random increment of up to `±amplitude` per [step] of x — Brownian
     * motion, wandering without any tendency to return.
     *
     * Unbounded by construction, so its excursion grows with the square root of distance and it is not a drop-in
     * substitute for a noise generator. **The cost is the thing to watch**: because it stays a pure function it re-sums
     * every increment from the origin on each evaluation, so a sample at x costs `x / step` hashes — sampling far from
     * zero, or with a small step, is quadratic in practice. A [step] of zero gives zero. Deterministic in [seed].
     */
    fun randomWalk(
        seed: Long = 0L,
        step: Fn = param(1.0),
        amplitude: Fn = param(1.0),
        domain: Domain = Domain.DEFAULT
    ): Fn {
        val d = intersectDomains(domain, step, amplitude)
        return graphFn(
            d,
            "randomwalk",
            "randomWalk",
            children = listOf(fnEdge("step", step), fnEdge("amplitude", amplitude)),
            longs = mapOf("seed" to seed),
            rebuild = { children -> randomWalk(seed, children[0], children[1], domain) }
        ) { x ->
            val st = step.eval(x)
            if (st == 0.0) return@graphFn 0.0
            val amp = amplitude.eval(x)
            val n = floor(x / st).toInt()
            var acc = 0.0
            if (n >= 0) {
                for (i in 0..n) {
                    acc += hashToSigned(seed, i.toLong()) * amp
                }
            } else {
                var i = 0
                while (i >= n) {
                    acc -= hashToSigned(seed, i.toLong()) * amp
                    i--
                }
            }
            acc
        }
    }

    /** A random walk: the accumulated sum of one increment of up to `±amplitude` per [step] of x. Unbounded, and costs `x / step` hashes per evaluation — expensive far from zero. */
    fun randomWalk(
        seed: Long = 0L,
        step: Number,
        amplitude: Number = 1.0,
        domain: Domain = Domain.DEFAULT
    ): Fn = randomWalk(seed, param(step), param(amplitude), domain)

    /** A random walk: the accumulated sum of one increment of up to `±amplitude` per [step] of x. Unbounded, and costs `x / step` hashes per evaluation — expensive far from zero. */
    fun randomWalk(
        seed: Long = 0L,
        step: Number,
        amplitude: Number = 1.0,
        domain: ClosedRange<*>
    ): Fn = randomWalk(seed, step, amplitude, domain.toDomain())

    /** A random walk: the accumulated sum of one increment of up to `±amplitude` per [step] of x. Unbounded, and costs `x / step` hashes per evaluation — expensive far from zero. */
    fun randomWalk(
        seed: Long = 0L,
        step: Fn = param(1.0),
        amplitude: Fn = param(1.0),
        domain: ClosedRange<*>
    ): Fn = randomWalk(seed, step, amplitude, domain.toDomain())

    /** A random walk accumulating one increment per [step], taking [step] and [amplitude] as numbers or [Fn]s and throwing for anything else. */
    fun randomWalk(
        seed: Long = 0L,
        step: Any,
        amplitude: Any = 1.0,
        domain: Domain = Domain.DEFAULT
    ): Fn = randomWalk(seed, asParam(step, "step"), asParam(amplitude, "amplitude"), domain)

    /** A random walk accumulating one increment per [step], taking [step] and [amplitude] as numbers or [Fn]s and throwing for anything else. */
    fun randomWalk(
        seed: Long = 0L,
        step: Any,
        amplitude: Any = 1.0,
        domain: ClosedRange<*>
    ): Fn = randomWalk(seed, step, amplitude, domain.toDomain())

    /**
     * One `(x, y)` observation, with an optional weight — the currency of the scattered-data and sampling APIs here.
     *
     * [w] is only consulted by the RBF fitter, and there it scales the right-hand side of the fit rather than the row, so
     * a weight of zero asks the curve to pass through **zero** at that x rather than ignoring the point. Everything else
     * in this file ignores it.
     */
    data class Sample(val x: Double, val y: Double, val w: Double = 1.0)

    /** Builds a control-point list from `x to y` pairs, all with weight 1 — the terse way to hand-write input for [rbf] and friends. */
    fun points(vararg pairs: Pair<Double, Double>): List<Sample> = pairs.map { Sample(it.first, it.second) }

    /** Observes this function at the given coordinates, as `(x, value)` [Sample]s. Not domain-checked: each point is a raw evaluation, so coordinates outside the domain may be extrapolated. */
    fun Fn.samplesAt(xs: DoubleArray): List<Sample> = xs.map { Sample(it, eval(it)) }

    /**
     * Rescales the function so that its observed range lands on `targetMin .. targetMax` — normalization by
     * measurement.
     *
     * The measurement is the caveat. The range is **estimated once, at construction**, from [samples] pseudo-random
     * probes across [sampleDomain]; the affine map is then fixed. So a peak the probes happened to miss will overshoot
     * the target range, which is what [clamp] is there to contain. Raising [samples] tightens the estimate at a
     * proportional up-front cost. Edge cases are quiet rather than loud: if no finite sample can be taken the function
     * is returned unchanged, and if the observed range is zero the result is the constant [targetMin]. Deterministic in
     * [seed].
     */
    fun Fn.autoScale(
        sampleDomain: Domain = domain,
        samples: Int = 256,
        seed: Long = 0L,
        targetMin: Double = 0.0,
        targetMax: Double = 1.0,
        clamp: Boolean = false
    ): Fn {
        val stats = sampleStatsRandom(this, sampleDomain, samples, seed) ?: return this
        val range = stats.max - stats.min
        if (range == 0.0 || !range.isFinite()) return c(targetMin, sampleDomain)
        val scale = (targetMax - targetMin) / range
        val offset = targetMin - stats.min * scale
        val d = this.domain.intersect(sampleDomain)
        return graphFn(
            d,
            "autoscale",
            "autoScale",
            children = listOf(fnEdge("source", this)),
            numbers = mapOf(
                "samples" to samples.toDouble(),
                "targetMin" to targetMin,
                "targetMax" to targetMax
            ),
            strings = mapOf("sampleDomain" to sampleDomain.toString()),
            booleans = mapOf("clamp" to clamp),
            longs = mapOf("seed" to seed),
            rebuild = { children -> children[0].autoScale(sampleDomain, samples, seed, targetMin, targetMax, clamp) }
        ) { x ->
            val v = eval(x) * scale + offset
            if (clamp) v.coerceIn(min(targetMin, targetMax), max(targetMin, targetMax)) else v
        }
    }

    /**
     * Rescales the function so its observed range lands on `targetMin .. targetMax`, measuring over the given range. The
     * range is estimated once from [samples] probes, so unmeasured peaks can overshoot unless [clamp] is set.
     */
    fun Fn.autoScale(
        sampleDomain: ClosedRange<*>,
        samples: Int = 256,
        seed: Long = 0L,
        targetMin: Double = 0.0,
        targetMax: Double = 1.0,
        clamp: Boolean = false
    ): Fn = autoScale(sampleDomain.toDomain(), samples, seed, targetMin, targetMax, clamp)

    /**
     * Standardizes the function to zero mean and unit standard deviation — `(v - mean) / sd`, with both estimated from
     * [samples] pseudo-random probes at construction.
     *
     * Unlike [autoScale] this does not bound the output: a signal with outliers will still have them, now measured in
     * standard deviations. The estimate is fixed once and the probes may miss things, as there. A signal whose measured
     * deviation is below [epsilon] is treated as constant and becomes the constant **zero**; if no finite sample can be
     * taken at all the function is returned unchanged. Deterministic in [seed].
     */
    fun Fn.zNormalize(
        sampleDomain: Domain = domain,
        samples: Int = 256,
        seed: Long = 0L,
        epsilon: Double = 1e-9
    ): Fn {
        val stats = sampleStatsRandom(this, sampleDomain, samples, seed) ?: return this
        val std = kotlin.math.sqrt(stats.variance.coerceAtLeast(0.0))
        if (!std.isFinite() || std <= epsilon) return c(0.0, sampleDomain)
        val d = this.domain.intersect(sampleDomain)
        return graphFn(
            d,
            "znorm",
            "zNormalize",
            children = listOf(fnEdge("source", this)),
            numbers = mapOf("samples" to samples.toDouble(), "epsilon" to epsilon),
            strings = mapOf("sampleDomain" to sampleDomain.toString()),
            longs = mapOf("seed" to seed),
            rebuild = { children -> children[0].zNormalize(sampleDomain, samples, seed, epsilon) }
        ) { x -> (eval(x) - stats.mean) / std }
    }

    /**
     * Standardizes the function to zero mean and unit standard deviation, measuring over the given range. A signal whose
     * measured deviation is below [epsilon] becomes the constant zero.
     */
    fun Fn.zNormalize(
        sampleDomain: ClosedRange<*>,
        samples: Int = 256,
        seed: Long = 0L,
        epsilon: Double = 1e-9
    ): Fn = zNormalize(sampleDomain.toDomain(), samples, seed, epsilon)

    /**
     * Histogram equalization: pushes every value through the function's own cumulative distribution, so the output is
     * spread **uniformly** across `targetMin .. targetMax` however lopsidedly the input was distributed.
     *
     * The move to make when a signal is technically in range but spends all its time near one end. Where [autoScale] is
     * an affine stretch, this is monotone but non-linear, and it will happily flatten a peak in order to even out the
     * distribution. The CDF is estimated from [samples] probes into [bins] buckets — **lazily**, on the first evaluation,
     * then cached for the life of the function, so the cost is paid once but not at construction. If the estimate cannot
     * be built the original value is passed through untouched. Deterministic in [seed].
     */
    fun Fn.whitenCdf(
        sampleDomain: Domain = domain,
        samples: Int = 2048,
        bins: Int = 256,
        seed: Long = 0L,
        targetMin: Double = 0.0,
        targetMax: Double = 1.0,
        clamp: Boolean = true
    ): Fn {
        val d = this.domain.intersect(sampleDomain)
        val cached = lazy {
            val stats = sampleStatsRandom(this, sampleDomain, samples, seed) ?: return@lazy null
            val range = stats.max - stats.min
            if (!range.isFinite() || range == 0.0) return@lazy null
            val count = max(2, bins)
            val hist = DoubleArray(count)
            val sweep = sampleDomain.finiteSweep()
            val len = sweep.length
            var valid = 0
            for (i in 0 until samples) {
                val x = sweep.min + hashToUnit(seed + 1L, i.toLong()) * len
                val v = eval(x)
                if (!v.isFinite()) continue
                val u = ((v - stats.min) / range).coerceIn(0.0, 1.0)
                val idx = min(count - 1, floor(u * (count - 1)).toInt())
                hist[idx] += 1.0
                valid++
            }
            if (valid == 0) return@lazy null
            val cdf = DoubleArray(count)
            var acc = 0.0
            for (i in 0 until count) {
                acc += hist[i]
                cdf[i] = acc / valid.toDouble()
            }
            Triple(stats, cdf, range)
        }
        return graphFn(
            d,
            "whitenCdf",
            "whitenCdf",
            children = listOf(fnEdge("source", this)),
            numbers = mapOf(
                "samples" to samples.toDouble(),
                "bins" to bins.toDouble(),
                "targetMin" to targetMin,
                "targetMax" to targetMax
            ),
            strings = mapOf("sampleDomain" to sampleDomain.toString()),
            booleans = mapOf("clamp" to clamp),
            longs = mapOf("seed" to seed),
            rebuild = { children -> children[0].whitenCdf(sampleDomain, samples, bins, seed, targetMin, targetMax, clamp) }
        ) { x ->
            val data = cached.value
            if (data == null) return@graphFn eval(x)
            val (stats, cdf, range) = data
            val v = eval(x)
            if (!v.isFinite()) return@graphFn v
            val u = ((v - stats.min) / range)
            val t = if (clamp) u.coerceIn(0.0, 1.0) else u
            val idx = min(cdf.size - 1, max(0, floor(t * (cdf.size - 1)).toInt()))
            val next = min(cdf.size - 1, idx + 1)
            val frac = t * (cdf.size - 1) - idx
            val c = if (next == idx) cdf[idx] else blend(cdf[idx], cdf[next], frac)
            val out = targetMin + (targetMax - targetMin) * c
            if (clamp) out.coerceIn(min(targetMin, targetMax), max(targetMin, targetMax)) else out
        }
    }

    /**
     * Histogram equalization over the given range: values are pushed through the function's own estimated CDF so the
     * output is spread uniformly across `targetMin .. targetMax`. Monotone but non-linear, and estimated lazily on first
     * use.
     */
    fun Fn.whitenCdf(
        sampleDomain: ClosedRange<*>,
        samples: Int = 2048,
        bins: Int = 256,
        seed: Long = 0L,
        targetMin: Double = 0.0,
        targetMax: Double = 1.0,
        clamp: Boolean = true
    ): Fn = whitenCdf(sampleDomain.toDomain(), samples, bins, seed, targetMin, targetMax, clamp)

    /**
     * [count] evenly-spaced observations across [sampleDomain], as `(x, value)` [Sample]s.
     *
     * With `includeEnd = true` the first and last land exactly on the bounds; with false the last stops one step short,
     * which is what you want for anything cyclic. Returns an empty list for a count of zero or less, or an empty domain,
     * and previews an unbounded domain over a finite window. Values are raw evaluations, not domain-checked.
     */
    fun Fn.sampleUniform(count: Int, sampleDomain: Domain = domain, includeEnd: Boolean = true): List<Sample> {
        if (count <= 0 || sampleDomain._isEmpty) return emptyList()
        val sweep = sampleDomain.finiteSweep()
        val len = sweep.length
        val steps = if (includeEnd) max(1, count - 1) else count
        val step = if (steps == 0) 0.0 else len / steps.toDouble()
        return List(count) { i ->
            val x = sweep.min + step * i.toDouble()
            Sample(x, eval(x))
        }
    }

    /** [count] evenly-spaced observations across the given range, as [Sample]s. `includeEnd` decides whether the last one lands on the upper bound. */
    fun Fn.sampleUniform(count: Int, sampleDomain: ClosedRange<*>, includeEnd: Boolean = true): List<Sample> =
        sampleUniform(count, sampleDomain.toDomain(), includeEnd)

    /**
     * [count] observations, one per equal cell, each displaced from its cell centre by up to half [jitter] of a cell width
     * — stratified sampling, which covers the domain evenly while avoiding the aliasing a regular grid produces.
     *
     * [jitter] is a **fraction of the cell width**, not an x distance, so `1.0` lets a sample reach its cell's edges and
     * `0.0` reduces this to cell-centred uniform sampling. Positions are clamped inside the domain and deterministic in
     * [seed]. Returns an empty list for a count of zero or less, or an empty domain.
     */
    fun Fn.sampleJittered(count: Int, sampleDomain: Domain = domain, jitter: Double = 0.25, seed: Long = 0L): List<Sample> {
        if (count <= 0 || sampleDomain._isEmpty) return emptyList()
        val sweep = sampleDomain.finiteSweep()
        val len = sweep.length
        val step = if (count == 0) 0.0 else len / count.toDouble()
        return List(count) { i ->
            val base = sweep.min + step * (i + 0.5)
            val j = (hashToUnit(seed, i.toLong()) - 0.5) * jitter * step
            val x = (base + j).coerceIn(sweep.min, sweep.max)
            Sample(x, eval(x))
        }
    }

    /** [count] stratified observations across the given range, one per cell, displaced by up to half [jitter] of a cell width. Deterministic in [seed]. */
    fun Fn.sampleJittered(count: Int, sampleDomain: ClosedRange<*>, jitter: Double = 0.25, seed: Long = 0L): List<Sample> =
        sampleJittered(count, sampleDomain.toDomain(), jitter, seed)

    /**
     * [count] observations at pseudo-random positions across [sampleDomain] — unstratified, so expect clumps and gaps;
     * [sampleJittered] is usually the better-behaved choice.
     *
     * Deterministic in [seed], so the same seed gives the same positions. The samples come out in generation order, which
     * is **not sorted by x**. Returns an empty list for a count of zero or less, or an empty domain.
     */
    fun Fn.sampleRandom(count: Int, sampleDomain: Domain = domain, seed: Long = 0L): List<Sample> {
        if (count <= 0 || sampleDomain._isEmpty) return emptyList()
        val sweep = sampleDomain.finiteSweep()
        val len = sweep.length
        return List(count) { i ->
            val x = sweep.min + hashToUnit(seed, i.toLong()) * len
            Sample(x, eval(x))
        }
    }

    /** [count] observations at pseudo-random positions across the given range, unstratified and not sorted by x. Deterministic in [seed]. */
    fun Fn.sampleRandom(count: Int, sampleDomain: ClosedRange<*>, seed: Long = 0L): List<Sample> =
        sampleRandom(count, sampleDomain.toDomain(), seed)

    /**
     * Observations concentrated where [density] is high, by rejection sampling — the way to sample a signal more closely
     * in the regions you care about.
     *
     * [density] is read as a probability and **clamped to 0..1**, so it is a relative likelihood rather than a
     * distribution to be normalized; a density of zero over a region excludes it entirely. Because acceptance is random,
     * this **may return fewer than [count] samples**: it gives up after [maxRejects] attempts, which a sparse density
     * function will reach. Positions are not sorted by x. Deterministic in [seed].
     */
    fun Fn.sampleByDensity(
        count: Int,
        sampleDomain: Domain = domain,
        density: Fn,
        seed: Long = 0L,
        maxRejects: Int = 10000
    ): List<Sample> {
        if (count <= 0 || sampleDomain._isEmpty) return emptyList()
        val sweep = sampleDomain.finiteSweep()
        val len = sweep.length
        val out = ArrayList<Sample>(count)
        var tries = 0
        var i = 0L
        while (out.size < count && tries < maxRejects) {
            val x = sweep.min + hashToUnit(seed, i) * len
            val p = clamp01(density.eval(x))
            val r = hashToUnit(seed + 1L, i)
            if (r < p) out += Sample(x, eval(x))
            i++
            tries++
        }
        return out
    }

    /**
     * Observations concentrated where [density] is high, by rejection sampling over the given range. May return fewer than
     * [count] samples, since it gives up after [maxRejects] attempts.
     */
    fun Fn.sampleByDensity(
        count: Int,
        sampleDomain: ClosedRange<*>,
        density: Fn,
        seed: Long = 0L,
        maxRejects: Int = 10000
    ): List<Sample> = sampleByDensity(count, sampleDomain.toDomain(), density, seed, maxRejects)

    /**
     * Finds where the function crosses zero, walking [sampleDomain] in steps of [step] and linearly interpolating the
     * crossing between the two straddling samples. Every returned [Sample] has a y of exactly `0.0`.
     *
     * A **sign-change** detector, so its blind spots follow from that: a curve that touches zero without passing through
     * is missed, and two crossings inside one [step] cancel out and are missed as well. The interpolation assumes the
     * function is roughly straight across a step, so a coarse step misplaces the crossing. Default step is a 128th of the
     * domain.
     */
    fun Fn.sampleZeroCrossings(sampleDomain: Domain = domain, step: Double = sampleDomain.finiteSweep().length / 128.0): List<Sample> {
        if (sampleDomain._isEmpty || step == 0.0) return emptyList()
        val sweep = sampleDomain.finiteSweep()
        val out = ArrayList<Sample>()
        var x = sweep.min
        var prev = eval(x)
        x += step
        while (x <= sweep.max) {
            val y = eval(x)
            if (prev == 0.0) out += Sample(x - step, 0.0)
            if (prev * y < 0.0) {
                // `t` is the fraction of the step *from the left sample* at which the straight line
                // through (x - step, prev) and (x, y) hits zero, so the crossing is `step * (1 - t)`
                // back from x. Measuring it back from x directly — the original — is only right at
                // t = 0.5 and mirrors every other crossing to the wrong side of its step.
                val t = prev / (prev - y)
                val xc = x - step * (1.0 - t)
                out += Sample(xc, 0.0)
            }
            prev = y
            x += step
        }
        return out
    }

    /** Finds sign changes across the given range in steps of [step], interpolating each crossing. Tangential touches, and pairs of crossings closer together than one step, are missed. */
    fun Fn.sampleZeroCrossings(sampleDomain: ClosedRange<*>, step: Double = sampleDomain.toDomain().length / 128.0): List<Sample> =
        sampleZeroCrossings(sampleDomain.toDomain(), step)

    /**
     * Finds the [count] most prominent **local maxima**, by three-point comparison on a grid of [step].
     *
     * Two things to know. It looks only for maxima, so troughs are never returned — negate the function to find those. But
     * the candidates are then ranked by **absolute** value, so on a signal that dips well below zero a deep-but-still-local
     * maximum can outrank a shallow positive one. Resolution is limited by [step] (a 256th of the domain by default):
     * peaks narrower than that are missed, and a reported position is only accurate to one step. The result is ordered by
     * prominence, not by x.
     */
    fun Fn.samplePeaks(count: Int, sampleDomain: Domain = domain, step: Double = sampleDomain.finiteSweep().length / 256.0): List<Sample> {
        if (count <= 0 || sampleDomain._isEmpty || step == 0.0) return emptyList()
        val sweep = sampleDomain.finiteSweep()
        val candidates = ArrayList<Sample>()
        var x = sweep.min
        var prev = eval(x)
        x += step
        var current = eval(x)
        x += step
        while (x <= sweep.max) {
            val next = eval(x)
            if (current >= prev && current >= next) {
                candidates += Sample(x - step, current)
            }
            prev = current
            current = next
            x += step
        }
        return candidates.sortedByDescending { kotlin.math.abs(it.y) }.take(count)
    }

    /** Finds the [count] most prominent local maxima across the given range, on a grid of [step]. Maxima only — never troughs — and ranked by absolute value. */
    fun Fn.samplePeaks(count: Int, sampleDomain: ClosedRange<*>, step: Double = sampleDomain.toDomain().length / 256.0): List<Sample> =
        samplePeaks(count, sampleDomain.toDomain(), step)

    /** The [RbfKernels] catalogue, re-exported so `RbfKernels.thinPlate` resolves inside `with(Functions)` without an import. */
    val RbfKernels: afield.stdlib.RbfKernels
        get() = afield.stdlib.RbfKernels

    /** Builds an [afield.stdlib.RbfInterpolator] from inside a `Functions` scope, so `Sample` and `Fn` need no qualification. Solves its dense system eagerly — see that class for the sizing caveats. */
    fun RbfInterpolator(
        points: List<Sample>,
        kernel: RbfKernel,
        smoothing: Double = 0.0,
        base: Fn? = null
    ): afield.stdlib.RbfInterpolator =
        afield.stdlib.RbfInterpolator(points, kernel, smoothing, base)

    /**
     * Fits a smooth curve through scattered control points with radial basis functions — the tool for "make me a function
     * that passes through these", where the points need not be evenly spaced or even sorted.
     *
     * With the default `smoothing = 0.0` the curve passes exactly through every point; a positive [smoothing] relaxes that
     * in exchange for a calmer curve. If [base] is given the fit corrects *it* rather than starting from zero, so the base
     * supplies the trend. The domain spans the control points (unioned with the base's).
     *
     * Two practical warnings. The fit is **solved eagerly** and costs the cube of the point count, so this is for tens of
     * points, not thousands. And nothing constrains the curve outside the points' span — the default thin-plate kernel
     * diverges there — so treat extrapolation as undefined. An empty point list returns [base], or the constant zero.
     */
    fun rbf(points: List<Sample>, kernel: RbfKernel = RbfKernels.thinPlate, smoothing: Double = 0.0, base: Fn? = null): Fn {
        if (points.isEmpty()) return base ?: zero()
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val pointDomain = Domain.of(minX, maxX)
        val domain = (base?.domain ?: pointDomain).union(pointDomain)
        val interp = RbfInterpolator(points, kernel, smoothing, base)
        return graphFn(
            domain,
            "rbf",
            "rbf",
            children = base?.let { listOf(fnEdge("base", it)) } ?: emptyList(),
            numbers = mapOf("points" to points.size.toDouble(), "smoothing" to smoothing),
            strings = mapOf("kernel" to kernel.toString(), "pointDomain" to pointDomain.toString()),
            rebuild = { children -> rbf(points, kernel, smoothing, children.getOrNull(0)) }
        ) { x -> interp.eval(x) }
    }

    /** [rbf] with the `r²` kernel — stiffer than thin-plate, and more prone to overshoot away from the data. */
    fun rbfQuadratic(points: List<Sample>, smoothing: Double = 0.0, base: Fn? = null): Fn =
        rbf(points, RbfKernels.quadratic, smoothing, base)

    /** [rbf] with the `r³` kernel — the stiffest of the polynomial family, smooth through the points but wild beyond them. */
    fun rbfCubic(points: List<Sample>, smoothing: Double = 0.0, base: Fn? = null): Fn =
        rbf(points, RbfKernels.cubic, smoothing, base)

    /** [rbf] with the thin-plate kernel — the same as the default, spelled out. The one to reach for first, since it has no shape parameter to mis-scale. */
    fun rbfThinPlate(points: List<Sample>, smoothing: Double = 0.0, base: Fn? = null): Fn =
        rbf(points, RbfKernels.thinPlate, smoothing, base)

    /**
     * Adds an RBF correction to this function so the result passes through [points] while otherwise following this
     * function's shape — the "nudge this curve through my control points" operation.
     *
     * Only the *residual* against this function is fitted, so the further the points sit from it, the more the correction
     * has to work. Same cost and extrapolation caveats as [rbf].
     */
    fun Fn.rbf(points: List<Sample>, kernel: RbfKernel = RbfKernels.thinPlate, smoothing: Double = 0.0): Fn =
        rbf(points, kernel, smoothing, this)

    /** Nudges this function through [points] with an RBF correction using the `r²` kernel. See [Fn.rbf]. */
    fun Fn.rbfQuadratic(points: List<Sample>, smoothing: Double = 0.0): Fn =
        rbf(points, RbfKernels.quadratic, smoothing, this)

    /** Nudges this function through [points] with an RBF correction using the `r³` kernel. See [Fn.rbf]. */
    fun Fn.rbfCubic(points: List<Sample>, smoothing: Double = 0.0): Fn =
        rbf(points, RbfKernels.cubic, smoothing, this)

    /** Nudges this function through [points] with a thin-plate RBF correction — the default kernel, spelled out. See [Fn.rbf]. */
    fun Fn.rbfThinPlate(points: List<Sample>, smoothing: Double = 0.0): Fn =
        rbf(points, RbfKernels.thinPlate, smoothing, this)

    // Granular synthesis
    /**
     * Granular resynthesis: chops the function into windowed grains of [grainSize] laid down every [spacing], and sums them
     * back together.
     *
     * When spacing equals grain size and there is no jitter this is close to a no-op with a windowed ripple; the interesting
     * territory is where they disagree — spacing shorter than the grain overlaps material, longer than it leaves gaps.
     * [jitter] displaces each grain by a fraction of the spacing, hashed from [seed], so the texture is random-looking but
     * reproducible. [overlap] is how many neighbouring grains either side are considered at each x, so it caps how much
     * overlap can actually be heard: with the default of 1 only the immediate neighbours contribute, and heavy overlap needs
     * a larger value. Grains are read from the source *at their own position*, so this reorders nothing by itself. Either a
     * grain size or a spacing of zero passes the source through untouched.
     */
    fun Fn.granular(
        grainSize: Fn = param(1.0),
        spacing: Fn = grainSize,
        jitter: Fn = param(0.0),
        overlap: Int = 1,
        seed: Long = 0L,
        window: Fn = hannWindow(Domain.DEFAULT)
    ): Fn {
        val d = intersectDomains(domain, grainSize, spacing, jitter)
        val base = this
        return graphFn(
            d,
            "granular",
            "granular",
            children = listOf(
                fnEdge("source", base),
                fnEdge("grainSize", grainSize),
                fnEdge("spacing", spacing),
                fnEdge("jitter", jitter),
                fnEdge("window", window)
            ),
            numbers = mapOf("overlap" to overlap.toDouble()),
            longs = mapOf("seed" to seed),
            rebuild = { children ->
                childAt(children, 0, base).granular(
                    grainSize = childAt(children, 1, grainSize),
                    spacing = childAt(children, 2, spacing),
                    jitter = childAt(children, 3, jitter),
                    overlap = overlap,
                    seed = seed,
                    window = childAt(children, 4, window)
                )
            }
        ) { x ->
            val g = grainSize.eval(x)
            val sp = spacing.eval(x)
            if (g == 0.0 || sp == 0.0) return@graphFn base.eval(x)
            val index = floor(x / sp).toLong()
            var sum = 0.0
            val jit = jitter.eval(x)
            for (k in -overlap..overlap) {
                val i = index + k
                val jitterOffset = (hashToUnit(seed, i) - 0.5) * jit * sp
                val center = (i.toDouble() * sp) + jitterOffset
                val local = x - (center - g * 0.5)
                if (local >= 0.0 && local <= g) {
                    val u = if (g == 0.0) 0.0 else local / g
                    val win = window.eval(u)
                    sum += base.eval(center - g * 0.5 + local) * win
                }
            }
            sum
        }
    }

    /**
     * Granular resynthesis into windowed grains of [grainSize] every [spacing], taking those and [jitter] as numbers or
     * [Fn]s and throwing for anything else. [overlap] bounds how many neighbouring grains can be heard at once.
     */
    fun Fn.granular(
        grainSize: Any,
        spacing: Any = grainSize,
        jitter: Any = 0.0,
        overlap: Int = 1,
        seed: Long = 0L,
        window: Fn = hannWindow(Domain.DEFAULT)
    ): Fn = granular(
        grainSize = asParam(grainSize, "grainSize"),
        spacing = asParam(spacing, "spacing"),
        jitter = asParam(jitter, "jitter"),
        overlap = overlap,
        seed = seed,
        window = window
    )

    // Time-series style streaming filters
    /**
     * A **stateful** one-pole low-pass over the sampled function, as an infinite lazy sequence: `y += alpha * (x - y)`.
     *
     * This is the recursive filter the pure-function world cannot offer — which is why it returns a `Sequence` rather than an
     * [Fn]: the result at each point depends on all the points before it, so it cannot be evaluated out of order or randomly
     * seeked. [alpha] is clamped to 0..1, where 1 passes the signal straight through and small values smooth harder; it is a
     * coefficient, not a cutoff frequency, and its meaning depends on [step]. Never terminates, so bound it with `take`.
     */
    fun Fn.lowpassStream(alpha: Double, start: Double = domain.finiteSweep().min, step: Double = 1.0, initial: Double = 0.0): Sequence<Double> = sequence {
        var x = start
        var y = initial
        val a = alpha.coerceIn(0.0, 1.0)
        while (true) {
            val input = eval(x)
            y = y + a * (input - y)
            yield(y)
            x += step
        }
    }

    /**
     * A **stateful** one-pole high-pass over the sampled function, as an infinite lazy sequence:
     * `y = alpha * (y + x - xPrev)`.
     *
     * The recursive counterpart to [lowpassStream], and a `Sequence` for the same reason — each output depends on the whole
     * history, so it cannot be sampled out of order. [alpha] is clamped to 0..1 and is a coefficient rather than a cutoff.
     * Note that the previous input starts at zero regardless of [initial], so expect a transient in the first few values.
     * Never terminates; bound it with `take`.
     */
    fun Fn.highpassStream(alpha: Double, start: Double = domain.finiteSweep().min, step: Double = 1.0, initial: Double = 0.0): Sequence<Double> = sequence {
        var x = start
        var y = initial
        var lastInput = 0.0
        val a = alpha.coerceIn(0.0, 1.0)
        while (true) {
            val input = eval(x)
            val out = a * (y + input - lastInput)
            y = out
            lastInput = input
            yield(out)
            x += step
        }
    }

    // Convenience builders for piecewise functions
    /**
     * Assembles a function from explicitly-placed pieces: at each x the **first** piece whose range contains it wins, and
     * [default] covers everything no piece claims.
     *
     * First-match-wins means overlapping ranges are resolved by argument order, not by an error — handy for writing a
     * general case first and then overriding a window of it, as long as the override comes earlier in the list. Ranges are
     * inclusive at both ends, so at a shared boundary the earlier piece takes it. Nothing is faded between pieces; expect a
     * discontinuity wherever neighbouring pieces disagree. The domain is the hull of the default and every piece.
     */
    fun piecewise(default: Fn = zero(), vararg pieces: Pair<Domain, Fn>): Fn {
        val domain = pieces.fold(default.domain) { acc, (d, f) -> acc.union(d).union(f.domain) }
        return graphFn(
            domain,
            "piecewise",
            "piecewise",
            children = listOf(fnEdge("default", default)) + pieces.mapIndexed { index, (_, fn) -> fnEdge("piece$index", fn) },
            numbers = mapOf("pieces" to pieces.size.toDouble()),
            strings = pieces.mapIndexed { index, (pieceDomain, _) -> "piece$index.domain" to pieceDomain.toString() }.toMap(),
            rebuild = { children ->
                val rebuiltPieces = pieces.mapIndexed { index, (pieceDomain, fn) ->
                    pieceDomain to childAt(children, index + 1, fn)
                }.toTypedArray()
                piecewise(childAt(children, 0, default), *rebuiltPieces)
            }
        ) { x ->
            for ((d, f) in pieces) {
                if (d.containsInclusive(x)) return@graphFn f.eval(x)
            }
            default.eval(x)
        }
    }

    // ---------------------------------------------------------------------
    // Comprehensive demo (sampling + stats) for docs and quick validation
    // ---------------------------------------------------------------------

    data class DemoStats(
        val min: Double,
        val max: Double,
        val mean: Double,
        val rms: Double
    )

    data class DemoSeries(
        val name: String,
        val domain: Domain,
        val xs: DoubleArray,
        val ys: DoubleArray,
        val stats: DemoStats
    )

    data class DemoResult(
        val series: List<DemoSeries>,
        val notes: List<String>
    )

    // ══════════════════════════════════════════════════════════════════
    //  Keyframe-aware interpolation functions
    //
    //  Each takes a list of Keyframe (or Vec2) control points and returns
    //  a Fn defined over their time span. These are independent of the
    //  keyframe editor's built-in bezier evaluation — they provide
    //  alternative curve shapes that can be overlaid, compared, or used
    //  as stand-alone functions.
    // ══════════════════════════════════════════════════════════════════

    /**
     * Piecewise-linear interpolation through the keyframe positions.
     * Outside the keyframe range the first/last value is held.
     */
    fun piecewiseLinear(points: List<Keyframe>): Fn {
        if (points.isEmpty()) return zero()
        val sorted = points.sortedBy { it.time }
        val d = Domain.of(sorted.first().time, sorted.last().time)
        return graphFn(
            d,
            "piecewise-linear",
            "piecewiseLinear",
            numbers = mapOf("points" to sorted.size.toDouble()),
            rebuild = { piecewiseLinear(points) }
        ) { x ->
            when {
                x <= sorted.first().time -> sorted.first().value
                x >= sorted.last().time -> sorted.last().value
                else -> {
                    val i = sorted.indexOfLast { it.time <= x }
                    val a = sorted[i]
                    val b = sorted[i + 1]
                    val t = if (b.time == a.time) 0.0 else (x - a.time) / (b.time - a.time)
                    a.value + (b.value - a.value) * t
                }
            }
        }
    }

    /** Overload accepting Vec2 pairs instead of Keyframe objects. */
    @JvmName("piecewiseLinearVec2")
    fun piecewiseLinear(points: List<Vec2>): Fn {
        return piecewiseLinear(points.map { Keyframe(it.x.toDouble(), it.y.toDouble(), "linear") })
    }

    /**
     * Catmull-Rom spline through keyframe positions.
     *
     * The Catmull-Rom spline passes exactly through every control point and
     * produces smooth C¹-continuous curves. The [alpha] parameter controls
     * the spline variant:
     *  - 0.0 = uniform (classic)
     *  - 0.5 = centripetal (avoids cusps — recommended)
     *  - 1.0 = chordal
     *
     * @param points the keyframes to interpolate
     * @param alpha  parameterisation exponent (0 = uniform, 0.5 = centripetal, 1 = chordal)
     */
    fun catmullRomSpline(points: List<Keyframe>, alpha: Double = 0.5): Fn {
        if (points.isEmpty()) return zero()
        if (points.size == 1) return c(points[0].value)
        val sorted = points.sortedBy { it.time }
        val n = sorted.size
        val d = Domain.of(sorted.first().time, sorted.last().time)
        return graphFn(
            d,
            "catmull-rom",
            "catmullRom",
            numbers = mapOf("points" to sorted.size.toDouble(), "alpha" to alpha),
            rebuild = { catmullRomSpline(points, alpha) }
        ) { x ->
            when {
                x <= sorted.first().time -> sorted.first().value
                x >= sorted.last().time -> sorted.last().value
                else -> {
                    // Find segment
                    var seg = 0
                    for (i in 0 until n - 1) {
                        if (x >= sorted[i].time && x < sorted[i + 1].time) { seg = i; break }
                    }
                    val p0 = sorted[max(seg - 1, 0)]
                    val p1 = sorted[seg]
                    val p2 = sorted[min(seg + 1, n - 1)]
                    val p3 = sorted[min(seg + 2, n - 1)]
                    catmullRomSegment(
                        p0.time, p0.value,
                        p1.time, p1.value,
                        p2.time, p2.value,
                        p3.time, p3.value,
                        x, alpha
                    )
                }
            }
        }
    }

    /**
     * Evaluate a single Catmull-Rom segment.
     *
     * Uses cubic Hermite interpolation where the tangent at each interior knot
     * is the slope of the line connecting its neighbours, scaled to the segment
     * span. The [alpha] parameter biases knot spacing but the final evaluation
     * always uses the standard Hermite basis on the [t1, t2] interval.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun catmullRomSegment(
        t0: Double, v0: Double,
        t1: Double, v1: Double,
        t2: Double, v2: Double,
        t3: Double, v3: Double,
        x: Double, alpha: Double
    ): Double {
        val span = t2 - t1
        if (span < 1e-12) return v1
        val u = ((x - t1) / span).coerceIn(0.0, 1.0)

        // Tangents: slope of chord through neighbours, scaled to segment length
        val tangent1 = if (t2 != t0) (v2 - v0) / (t2 - t0) * span else 0.0
        val tangent2 = if (t3 != t1) (v3 - v1) / (t3 - t1) * span else 0.0

        val u2 = u * u
        val u3 = u2 * u
        val h00 = 2.0 * u3 - 3.0 * u2 + 1.0
        val h10 = u3 - 2.0 * u2 + u
        val h01 = -2.0 * u3 + 3.0 * u2
        val h11 = u3 - u2

        return h00 * v1 + h10 * tangent1 + h01 * v2 + h11 * tangent2
    }

    /**
     * Monotone cubic (Fritsch-Carlson) interpolation.
     *
     * Guarantees no overshoot — the curve never exceeds the range defined by
     * adjacent keyframe values. Excellent for animation curves where you want
     * smooth motion without unexpected wobble.
     */
    fun monotoneCubic(points: List<Keyframe>): Fn {
        if (points.isEmpty()) return zero()
        if (points.size == 1) return c(points[0].value)
        val sorted = points.sortedBy { it.time }
        val n = sorted.size

        // Compute slopes
        val dx = DoubleArray(n - 1) { sorted[it + 1].time - sorted[it].time }
        val dy = DoubleArray(n - 1) { sorted[it + 1].value - sorted[it].value }
        val slopes = DoubleArray(n - 1) { if (dx[it] == 0.0) 0.0 else dy[it] / dx[it] }

        // Tangents via Fritsch-Carlson
        val tangents = DoubleArray(n)
        tangents[0] = slopes[0]
        for (i in 1 until n - 1) {
            if (slopes[i - 1] * slopes[i] <= 0.0) {
                tangents[i] = 0.0
            } else {
                tangents[i] = (slopes[i - 1] + slopes[i]) * 0.5
            }
        }
        tangents[n - 1] = slopes[n - 2]

        // Enforce monotonicity
        for (i in 0 until n - 1) {
            if (abs(slopes[i]) < 1e-30) {
                tangents[i] = 0.0
                tangents[i + 1] = 0.0
            } else {
                val a = tangents[i] / slopes[i]
                val b = tangents[i + 1] / slopes[i]
                val s = a * a + b * b
                if (s > 9.0) {
                    val tau = 3.0 / sqrt(s)
                    tangents[i] = tau * a * slopes[i]
                    tangents[i + 1] = tau * b * slopes[i]
                }
            }
        }

        val d = Domain.of(sorted.first().time, sorted.last().time)
        return graphFn(
            d,
            "monotone-cubic",
            "monotoneCubic",
            numbers = mapOf("points" to sorted.size.toDouble()),
            rebuild = { monotoneCubic(points) }
        ) { x ->
            when {
                x <= sorted.first().time -> sorted.first().value
                x >= sorted.last().time -> sorted.last().value
                else -> {
                    var seg = 0
                    for (i in 0 until n - 1) {
                        if (x >= sorted[i].time && x < sorted[i + 1].time) { seg = i; break }
                    }
                    val h = dx[seg]
                    if (h < 1e-30) sorted[seg].value
                    else {
                        val t = (x - sorted[seg].time) / h
                        val t2 = t * t
                        val t3 = t2 * t
                        val h00 = 2.0 * t3 - 3.0 * t2 + 1.0
                        val h10 = (t3 - 2.0 * t2 + t) * h
                        val h01 = -2.0 * t3 + 3.0 * t2
                        val h11 = (t3 - t2) * h
                        h00 * sorted[seg].value + h10 * tangents[seg] +
                                h01 * sorted[seg + 1].value + h11 * tangents[seg + 1]
                    }
                }
            }
        }
    }

    /**
     * Cubic Hermite interpolation with user-specified tangent slopes.
     *
     * Each keyframe's tangent is derived from its [Keyframe.handles]:
     *  - If handles are present the outgoing slope is `handle.value / handle.time`.
     *  - If handles are missing a Catmull-Rom-style tangent is used.
     *
     * This lets you feed editor keyframes (with their tangent handles) directly
     * into an independent Hermite evaluator.
     */
    fun hermiteFromHandles(points: List<Keyframe>): Fn {
        if (points.isEmpty()) return zero()
        if (points.size == 1) return c(points[0].value)
        val sorted = points.sortedBy { it.time }
        val n = sorted.size

        // Derive tangent slopes — dValue/dTime at each keyframe
        val tangents = DoubleArray(n) { i ->
            val kf = sorted[i]
            // Try to use the outgoing handle slope
            val outH = kf.handles?.out
            if (outH != null && abs(outH.time) > 1e-12) {
                outH.value / outH.time
            } else {
                // Catmull-Rom fallback
                val prev = sorted[max(i - 1, 0)]
                val next = sorted[min(i + 1, n - 1)]
                val dt = next.time - prev.time
                if (abs(dt) < 1e-12) 0.0 else (next.value - prev.value) / dt
            }
        }

        val d = Domain.of(sorted.first().time, sorted.last().time)
        return graphFn(
            d,
            "hermite",
            "hermite",
            numbers = mapOf("points" to sorted.size.toDouble()),
            rebuild = { hermiteFromHandles(points) }
        ) { x ->
            when {
                x <= sorted.first().time -> sorted.first().value
                x >= sorted.last().time -> sorted.last().value
                else -> {
                    var seg = 0
                    for (i in 0 until n - 1) {
                        if (x >= sorted[i].time && x < sorted[i + 1].time) { seg = i; break }
                    }
                    val h = sorted[seg + 1].time - sorted[seg].time
                    if (h < 1e-30) sorted[seg].value
                    else {
                        val t = (x - sorted[seg].time) / h
                        val t2 = t * t
                        val t3 = t2 * t
                        val h00 = 2.0 * t3 - 3.0 * t2 + 1.0
                        val h10 = (t3 - 2.0 * t2 + t) * h
                        val h01 = -2.0 * t3 + 3.0 * t2
                        val h11 = (t3 - t2) * h
                        h00 * sorted[seg].value + h10 * tangents[seg] +
                                h01 * sorted[seg + 1].value + h11 * tangents[seg + 1]
                    }
                }
            }
        }
    }

    /**
     * Quadratic B-spline approximation of the keyframe curve.
     *
     * Unlike interpolating splines, B-splines do **not** pass through the
     * control points — they smoothly approximate the shape. This produces
     * ultra-smooth curves that are naturally bias-free.
     */
    fun bSplineQuadratic(points: List<Keyframe>): Fn {
        if (points.isEmpty()) return zero()
        if (points.size == 1) return c(points[0].value)
        if (points.size == 2) return piecewiseLinear(points)
        val sorted = points.sortedBy { it.time }
        val n = sorted.size

        // Uniform parameterisation: map keyframe index to parameter
        val d = Domain.of(sorted.first().time, sorted.last().time)
        return graphFn(
            d,
            "b-spline-quadratic",
            "bSplineQuadratic",
            numbers = mapOf("points" to sorted.size.toDouble()),
            rebuild = { bSplineQuadratic(points) }
        ) { x ->
            when {
                x <= sorted.first().time -> sorted.first().value
                x >= sorted.last().time -> sorted.last().value
                else -> {
                    // Map x to parameter space [0, n-1]
                    val span = sorted.last().time - sorted.first().time
                    val u = if (span < 1e-12) 0.0
                    else ((x - sorted.first().time) / span * (n - 1)).coerceIn(0.0, (n - 1).toDouble())

                    val seg = u.toInt().coerceIn(0, n - 2)
                    val t = u - seg

                    val p0val = sorted[max(seg - 1, 0)].value
                    val p1val = sorted[seg].value
                    val p2val = sorted[min(seg + 1, n - 1)].value

                    // Quadratic B-spline basis
                    val b0 = 0.5 * (1.0 - t) * (1.0 - t)
                    val b1 = 0.5 + t * (1.0 - t)
                    val b2 = 0.5 * t * t

                    b0 * p0val + b1 * p1val + b2 * p2val
                }
            }
        }
    }

    /**
     * Step / sample-and-hold through keyframes.
     * Holds each keyframe value until the next keyframe time.
     */
    fun stepHold(points: List<Keyframe>): Fn {
        if (points.isEmpty()) return zero()
        val sorted = points.sortedBy { it.time }
        val d = Domain.of(sorted.first().time, sorted.last().time)
        return graphFn(
            d,
            "step-hold",
            "stepHold",
            numbers = mapOf("points" to sorted.size.toDouble()),
            rebuild = { stepHold(points) }
        ) { x ->
            when {
                x <= sorted.first().time -> sorted.first().value
                x >= sorted.last().time -> sorted.last().value
                else -> {
                    var value = sorted.first().value
                    for (kf in sorted) {
                        if (kf.time > x) break
                        value = kf.value
                    }
                    value
                }
            }
        }
    }

    /**
     * Nearest-value interpolation — returns the value of the closest keyframe.
     */
    fun nearestValue(points: List<Keyframe>): Fn {
        if (points.isEmpty()) return zero()
        val sorted = points.sortedBy { it.time }
        val d = Domain.of(sorted.first().time, sorted.last().time)
        return graphFn(
            d,
            "nearest",
            "nearestValue",
            numbers = mapOf("points" to sorted.size.toDouble()),
            rebuild = { nearestValue(points) }
        ) { x ->
            sorted.minByOrNull { abs(it.time - x) }?.value ?: 0.0
        }
    }

    /**
     * Cosine interpolation through keyframes.
     *
     * Like piecewise-linear but each segment uses a cosine ease for softer
     * transitions — simple and cheap, no tangent computation required.
     */
    fun cosineInterp(points: List<Keyframe>): Fn {
        if (points.isEmpty()) return zero()
        val sorted = points.sortedBy { it.time }
        val d = Domain.of(sorted.first().time, sorted.last().time)
        return graphFn(
            d,
            "cosine",
            "cosineInterp",
            numbers = mapOf("points" to sorted.size.toDouble()),
            rebuild = { cosineInterp(points) }
        ) { x ->
            when {
                x <= sorted.first().time -> sorted.first().value
                x >= sorted.last().time -> sorted.last().value
                else -> {
                    val i = sorted.indexOfLast { it.time <= x }
                    val a = sorted[i]
                    val b = sorted[i + 1]
                    val span = b.time - a.time
                    if (span < 1e-12) a.value
                    else {
                        val t = (x - a.time) / span
                        val mu = (1.0 - kotlin.math.cos(t * PI)) * 0.5
                        a.value + (b.value - a.value) * mu
                    }
                }
            }
        }
    }

    /**
     * Smoothstep (Hermite) interpolation through keyframes.
     *
     * Each segment is eased with the classic smoothstep 3t² − 2t³, producing
     * a C¹-continuous curve with zero-velocity at every keyframe.
     */
    fun smoothStepInterp(points: List<Keyframe>): Fn {
        if (points.isEmpty()) return zero()
        val sorted = points.sortedBy { it.time }
        val d = Domain.of(sorted.first().time, sorted.last().time)
        return graphFn(
            d,
            "smoothstep",
            "smoothStepInterp",
            numbers = mapOf("points" to sorted.size.toDouble()),
            rebuild = { smoothStepInterp(points) }
        ) { x ->
            when {
                x <= sorted.first().time -> sorted.first().value
                x >= sorted.last().time -> sorted.last().value
                else -> {
                    val i = sorted.indexOfLast { it.time <= x }
                    val a = sorted[i]
                    val b = sorted[i + 1]
                    val span = b.time - a.time
                    if (span < 1e-12) a.value
                    else {
                        val t = ((x - a.time) / span).coerceIn(0.0, 1.0)
                        val s = smoothstep(t)
                        a.value + (b.value - a.value) * s
                    }
                }
            }
        }
    }

    /**
     * Smootherstep (Ken Perlin's 6t⁵ − 15t⁴ + 10t³) interpolation.
     *
     * C²-continuous — smoother than smoothstep, with zero first *and* second
     * derivative at keyframe positions.
     */
    fun smootherStepInterp(points: List<Keyframe>): Fn {
        if (points.isEmpty()) return zero()
        val sorted = points.sortedBy { it.time }
        val d = Domain.of(sorted.first().time, sorted.last().time)
        return graphFn(
            d,
            "smootherstep",
            "smootherStepInterp",
            numbers = mapOf("points" to sorted.size.toDouble()),
            rebuild = { smootherStepInterp(points) }
        ) { x ->
            when {
                x <= sorted.first().time -> sorted.first().value
                x >= sorted.last().time -> sorted.last().value
                else -> {
                    val i = sorted.indexOfLast { it.time <= x }
                    val a = sorted[i]
                    val b = sorted[i + 1]
                    val span = b.time - a.time
                    if (span < 1e-12) a.value
                    else {
                        val t = ((x - a.time) / span).coerceIn(0.0, 1.0)
                        val s = smootherstep(t)
                        a.value + (b.value - a.value) * s
                    }
                }
            }
        }
    }

    // ── Resampled / transformed variants ─────────────────────────────

    /**
     * Gaussian-smoothed version of a source [Fn].
     *
     * Convolves the function with a Gaussian kernel of the given [sigma],
     * keeping the same domain. Useful for "softening" a keyframe curve after
     * construction.
     *
     * @param source the function to smooth
     * @param sigma  standard deviation of the Gaussian kernel (in time units)
     * @param taps   number of kernel samples (higher = smoother but slower)
     */
    fun gaussianSmooth(source: Fn, sigma: Double = 0.1, taps: Int = 31): Fn {
        val halfTaps = taps / 2
        val weights = DoubleArray(taps) { i ->
            val offset = (i - halfTaps).toDouble()
            exp(-0.5 * (offset * offset) / (halfTaps * halfTaps).toDouble())
        }
        val wSum = weights.sum()
        for (i in weights.indices) weights[i] /= wSum

        return graphFn(
            source.domain,
            "gaussian-smooth(${source.name ?: "?"})",
            "gaussianSmooth",
            children = listOf(fnEdge("source", source)),
            numbers = mapOf("sigma" to sigma, "taps" to taps.toDouble()),
            rebuild = { children -> gaussianSmooth(children[0], sigma, taps) }
        ) { x ->
            var acc = 0.0
            val step = sigma * 3.0 / halfTaps
            for (i in 0 until taps) {
                val offset = (i - halfTaps) * step
                acc += weights[i] * source.evalSafe(x + offset, source.eval(x), EdgeMode.CLAMP)
            }
            acc
        }
    }

    /**
     * Numerically differentiate a source [Fn] (central differences).
     *
     * @param source the function to differentiate
     * @param h      finite-difference half-width
     */
    fun differentiate(source: Fn, h: Double = 1e-4): Fn {
        return graphFn(
            source.domain,
            "d/dt(${source.name ?: "?"})",
            "differentiate",
            children = listOf(fnEdge("source", source)),
            numbers = mapOf("h" to h),
            rebuild = { children -> differentiate(children[0], h) }
        ) { x ->
            (source.eval(x + h) - source.eval(x - h)) / (2.0 * h)
        }
    }

    /**
     * Produces a [Fn] that exactly matches the keyframe editor's native evaluation
     * (hold / linear / rational-bezier), by constructing a temporary
     * [KeyframeEvaluator] and sampling it.
     *
     * This is the function to use when you want a `Fn` that mirrors what the
     * user sees in the editor — the same bezier handles, the same hold segments,
     * the same tangent weights.
     *
     * @param keyframes     the keyframe list (e.g. from [KeyframeEvaluator.getKeyframes])
     * @param sampleCount   number of uniformly-spaced samples across the keyframe time range
     * @param interpolation how to reconstruct between samples in the resulting Fn
     */
    fun fromKeyframeEditor(
        keyframes: List<Keyframe>,
        sampleCount: Int = 512,
        interpolation: Interpolation = Interpolation.LINEAR
    ): Fn {
        if (keyframes.isEmpty()) return zero()
        val json = kotlinx.serialization.json.Json.encodeToString(keyframes)
        val evaluator = KeyframeEvaluator(json)
        val sorted = keyframes.sortedBy { it.time }
        val tMin = sorted.first().time
        val tMax = sorted.last().time
        val d = Domain.of(tMin, tMax)
        if (d._isEmpty || !d.isFinite || d.length < 1e-12) return c(sorted.first().value)

        val values = DoubleArray(sampleCount) { i ->
            val t = tMin + (tMax - tMin) * i.toDouble() / max(1, sampleCount - 1).toDouble()
            evaluator.evaluateValueAt(t)
        }
        return values.toFn(d, interpolation)
            .withName("keyframe-editor")
    }

    /**
     * Samples [source] at [sampleCount] uniform points across its own domain and returns a table-backed
     * [Fn] that reconstructs between them with [interpolation].
     *
     * The point is cost, not fidelity: a deep graph re-evaluates its whole stack on every `eval`, and
     * one pass through this collapses it to an array lookup. It is therefore lossy by construction —
     * anything finer than the sample spacing is gone, and a source whose domain is empty or unbounded
     * is returned untouched because there is nothing finite to walk. Endpoints are included.
     *
     * @param source the function to resample
     * @param sampleCount number of uniform samples
     * @param interpolation how to reconstruct between samples
     */
    fun resample(
        source: Fn,
        sampleCount: Int = 256,
        interpolation: Interpolation = Interpolation.CUBIC
    ): Fn {
        val d = source.domain
        if (d._isEmpty || !d.isFinite) return source
        val samples = source.toDoubleArray(sampleCount, d, includeEnd = true)
        return samples.toFn(d, interpolation)
            .withName("resampled(${source.name ?: "?"})")
    }

    fun demo(
        sampleCount: Int = 512,
        seed: Long = 1L,
        domain: Domain = Domain.DEFAULT,
        printSummary: Boolean = true
    ): DemoResult {
        val d = domain

        val base = sin(frequency = param(2.0), amplitude = param(1.0), domain = d)
        val saw = saw(period = param(1.0 / 3.0), amplitude = param(0.7), domain = d)
        val tri = triangle(period = param(1.0 / 4.0), amplitude = param(0.5), domain = d)
        val square = square(period = param(1.0), amplitude = param(0.6), duty = param(0.3), domain = d)
        val pulse = pulseTrain(period = param(0.2), duty = param(0.35), amplitude = param(1.0), offset = param(0.0), domain = d)
        val noise = valueNoise(seed = seed, frequency = param(8.0), amplitude = param(0.4), domain = d)
        val fbmFn = fbm(seed = seed + 1, frequency = param(2.0), amplitude = param(0.4), octaves = 4, domain = d)

        val mixed = (base + saw + tri + square + noise + fbmFn).softClip(param(1.2))
        val windowed = mixed * hannWindow(d)
        val blurred = mixed.blur(sigma = param(0.02), samples = 64)
        val averaged = mixed.movingAverage(window = param(0.1), samples = 64)
        val sampledHold = mixed.sampleHold(step = param(0.05))
        val granular = mixed.granular(grainSize = param(0.05), spacing = param(0.04), jitter = param(0.2), overlap = 1, seed = seed)

        val series = listOf(
            demoSeries("sin", base, d, sampleCount),
            demoSeries("saw", saw, d, sampleCount),
            demoSeries("triangle", tri, d, sampleCount),
            demoSeries("square", square, d, sampleCount),
            demoSeries("pulseTrain", pulse, d, sampleCount),
            demoSeries("valueNoise", noise, d, sampleCount),
            demoSeries("fbm", fbmFn, d, sampleCount),
            demoSeries("mixed", mixed, d, sampleCount),
            demoSeries("windowed", windowed, d, sampleCount),
            demoSeries("blurred", blurred, d, sampleCount),
            demoSeries("movingAverage", averaged, d, sampleCount),
            demoSeries("sampleHold", sampledHold, d, sampleCount),
            demoSeries("granular", granular, d, sampleCount)
        )

        val notes = listOf(
            "Domain: [${"%.3f".format(d.min)}, ${"%.3f".format(d.max)}]",
            "Samples per series: $sampleCount",
            "Seed: $seed"
        )

        if (printSummary) {
            println("Functions.demo: ${notes.joinToString(" | ")}")
            series.forEach { s ->
                println(
                    "  ${s.name.padEnd(14)} min=${"%.3f".format(s.stats.min)} " +
                        "max=${"%.3f".format(s.stats.max)} mean=${"%.3f".format(s.stats.mean)} " +
                        "rms=${"%.3f".format(s.stats.rms)}"
                )
            }
        }

        return DemoResult(series, notes)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        demo()
    }

    private fun demoSeries(name: String, fn: Fn, domain: Domain, count: Int): DemoSeries {
        val xs = sampleXs(domain, count)
        val ys = fn.toDoubleArray(count, domain)
        val stats = computeStats(ys)
        return DemoSeries(name, domain, xs, ys, stats)
    }

    private fun sampleXs(domain: Domain, count: Int): DoubleArray {
        if (count <= 0) return DoubleArray(0)
        if (domain._isEmpty) return DoubleArray(count) { Double.NaN }
        val len = domain.length
        val steps = max(1, count - 1)
        val step = if (steps == 0) 0.0 else len / steps.toDouble()
        return DoubleArray(count) { i -> domain.min + step * i.toDouble() }
    }

    private fun computeStats(values: DoubleArray): DemoStats {
        if (values.isEmpty()) return DemoStats(0.0, 0.0, 0.0, 0.0)
        var minV = Double.POSITIVE_INFINITY
        var maxV = Double.NEGATIVE_INFINITY
        var sum = 0.0
        var sumSq = 0.0
        for (v in values) {
            if (v < minV) minV = v
            if (v > maxV) maxV = v
            sum += v
            sumSq += v * v
        }
        val mean = sum / values.size.toDouble()
        val rms = kotlin.math.sqrt(sumSq / values.size.toDouble())
        return DemoStats(minV, maxV, mean, rms)
    }
}
