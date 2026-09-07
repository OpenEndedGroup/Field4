@file:Suppress("MemberVisibilityCanBePrivate")
@file:afield.CompletionCategory("Math")

package afield.stdlib

import kotlin.math.absoluteValue

/**
 * Forward-mode automatic differentiation to **second order** — the other half of `autodiff.kt`.
 *
 * A [Dual2] is a truncated Taylor series `(v, v', v'')` carried along a single direction. Every
 * operation propagates all three components, so one evaluation of an expression in [Dual2]
 * arithmetic returns the value, the directional first derivative and the directional *curvature* at
 * once — no tape, no backward sweep, and no allocation beyond one three-`Double` object per node.
 *
 * ```kotlin
 * val t = taylor2(0.6) { x -> exp(-x * x) * sin(6.0 * x) }
 * t.value; t.first; t.second      // f, f', f'' at 0.6, all exact
 * ```
 *
 * ## Forward or reverse
 *
 * Reverse mode ([grad]) wins when there are many inputs and one output — a loss over a parameter
 * vector. Forward mode wins in the opposite shape (one input, many outputs: a parametric curve and
 * every quantity derived from it), and it is the only one of the two that reaches second order.
 * [hessian] in `autodiff.kt` is built on this file: `vᵀHv` is exactly [Dual2.second] after a pass
 * seeded along `v`.
 *
 * The op vocabulary mirrors `autodiff.kt` name for name — [sin], [exp], [tanh], [max],
 * [pow] and friends are overloaded on both [AD] and [Dual2], so the *same* expression body can be
 * written once against a shape and evaluated either way (see [ADFunction2]).
 *
 * ## Conventions and gaps
 *
 * - The kink convention matches `autodiff.kt`: [max] / [min] split evenly at an exact tie, so
 *   `relu''` is 0 everywhere and `relu'(0)` is 0.5.
 * - [pow] with a *tracked* exponent is evaluated as `exp(b · ln a)` and therefore requires a
 *   strictly positive base; the constant-exponent [Dual2.pow] has no such restriction.
 * - Third and higher derivatives are not represented. A `Dual3` would be a mechanical extension of
 *   the same chain rule; it is not here.
 */

/**
 * A second-order forward-mode number: a value together with its first and second derivative along
 * one direction.
 *
 * Construct the input you are differentiating with respect to via [Dual2.variable] (seed
 * `first = 1`) and everything else with [Dual2.constant]; for a multivariate directional derivative
 * seed each component's [first] with that component of the direction. Arithmetic then carries all
 * three orders through by the chain rule.
 */
data class Dual2(
    /** The plain value `f`. */
    val value: Double,
    /** The first derivative `df/dt` along the seeded direction. */
    val first: Double,
    /** The second derivative `d²f/dt²` along the seeded direction. */
    val second: Double
) {
    companion object {
        /** The differentiation variable: value [v], seeded with `f' = 1`. */
        fun variable(v: Double): Dual2 = Dual2(v, 1.0, 0.0)

        /** A constant: value [v], with both derivatives zero. */
        fun constant(v: Double): Dual2 = Dual2(v, 0.0, 0.0)

        /** A component of a multivariate input, seeded with the direction component [d]. */
        fun seeded(v: Double, d: Double): Dual2 = Dual2(v, d, 0.0)
    }

    /** Sum, componentwise. */
    operator fun plus(other: Dual2): Dual2 = Dual2(value + other.value, first + other.first, second + other.second)

    /** Sum with a constant, which shifts only the value. */
    operator fun plus(other: Double): Dual2 = Dual2(value + other, first, second)

    /** Difference, componentwise. */
    operator fun minus(other: Dual2): Dual2 = Dual2(value - other.value, first - other.first, second - other.second)

    /** Difference with a constant. */
    operator fun minus(other: Double): Dual2 = Dual2(value - other, first, second)

    /** Product; the second order picks up the `2·u'·w'` cross term. */
    operator fun times(other: Dual2): Dual2 = Dual2(
        value * other.value,
        first * other.value + value * other.first,
        second * other.value + 2.0 * first * other.first + value * other.second
    )

    /** Product with a constant, which scales all three orders. */
    operator fun times(other: Double): Dual2 = Dual2(value * other, first * other, second * other)

    /** Quotient, as `this * other.reciprocal()`. */
    operator fun div(other: Dual2): Dual2 = this * other.reciprocal()

    /** Quotient by a constant. */
    operator fun div(other: Double): Dual2 = Dual2(value / other, first / other, second / other)

    /** Negation. */
    operator fun unaryMinus(): Dual2 = Dual2(-value, -first, -second)

    /** Identity. */
    operator fun unaryPlus(): Dual2 = this

    /**
     * Applies an outer scalar function given its value and its first two derivatives at [value] —
     * the second-order chain rule `(f∘u)'' = f''·u'² + f'·u''`, and the single place every unary op
     * in this file is implemented.
     */
    fun chain(fv: Double, d1: Double, d2: Double): Dual2 =
        Dual2(fv, d1 * first, d2 * first * first + d1 * second)

    /** Reciprocal `1/x`. */
    fun reciprocal(): Dual2 {
        val inv = 1.0 / value
        return chain(inv, -inv * inv, 2.0 * inv * inv * inv)
    }

    /** The square `x²`. */
    fun sq(): Dual2 = chain(value * value, 2.0 * value, 2.0)

    /** `x` raised to a constant power, valid for negative [value] at integer exponents. */
    fun pow(exponent: Double): Dual2 = chain(
        Math.pow(value, exponent),
        exponent * Math.pow(value, exponent - 1.0),
        exponent * (exponent - 1.0) * Math.pow(value, exponent - 2.0)
    )

    /** `x` raised to a constant integer power. */
    fun pow(exponent: Int): Dual2 = pow(exponent.toDouble())

    /** Clamps into `[lo, hi]`, with the even-split tie convention on each boundary. */
    fun clamp(lo: Double, hi: Double): Dual2 {
        require(lo <= hi) { "clamp bounds are inverted: $lo > $hi" }
        return min(max(this, lo), hi)
    }

    override fun toString(): String = "Dual2($value, f'=$first, f''=$second)"
}

// ---- Double-on-the-left operators -------------------------------------------------------------

/** `2.0 + x`. */
operator fun Double.plus(other: Dual2): Dual2 = other + this

/** `2.0 - x`. */
operator fun Double.minus(other: Dual2): Dual2 = -other + this

/** `2.0 * x`. */
operator fun Double.times(other: Dual2): Dual2 = other * this

/** `2.0 / x`. */
operator fun Double.div(other: Dual2): Dual2 = other.reciprocal() * this

/** `2 + x` for integer literals. */
operator fun Int.plus(other: Dual2): Dual2 = other + this.toDouble()

/** `2 - x` for integer literals. */
operator fun Int.minus(other: Dual2): Dual2 = -other + this.toDouble()

/** `2 * x` for integer literals. */
operator fun Int.times(other: Dual2): Dual2 = other * this.toDouble()

/** `2 / x` for integer literals. */
operator fun Int.div(other: Dual2): Dual2 = other.reciprocal() * this.toDouble()

// ---- elementary functions ---------------------------------------------------------------------

/** Natural exponential. */
fun exp(x: Dual2): Dual2 {
    val v = kotlin.math.exp(x.value)
    return x.chain(v, v, v)
}

/** Natural logarithm. */
fun ln(x: Dual2): Dual2 {
    val inv = 1.0 / x.value
    return x.chain(kotlin.math.ln(x.value), inv, -inv * inv)
}

/** Base-10 logarithm. */
fun log10(x: Dual2): Dual2 = ln(x) / kotlin.math.ln(10.0)

/** Base-2 logarithm. */
fun log2(x: Dual2): Dual2 = ln(x) / kotlin.math.ln(2.0)

/** Square root. */
fun sqrt(x: Dual2): Dual2 {
    val v = kotlin.math.sqrt(x.value)
    return x.chain(v, 0.5 / v, -0.25 / (v * v * v))
}

/**
 * Cube root. Written out rather than delegated to [Dual2.pow], because `Math.pow` is undefined for
 * a negative base at a fractional exponent while the cube root is perfectly well defined there.
 */
fun cbrt(x: Dual2): Dual2 {
    val v = Math.cbrt(x.value)
    return x.chain(v, 1.0 / (3.0 * v * v), -2.0 / (9.0 * v * v * v * v * v))
}

/** Sine of an angle in radians. */
fun sin(x: Dual2): Dual2 {
    val s = kotlin.math.sin(x.value)
    return x.chain(s, kotlin.math.cos(x.value), -s)
}

/** Cosine of an angle in radians. */
fun cos(x: Dual2): Dual2 {
    val c = kotlin.math.cos(x.value)
    return x.chain(c, -kotlin.math.sin(x.value), -c)
}

/** Tangent. */
fun tan(x: Dual2): Dual2 {
    val t = kotlin.math.tan(x.value)
    val d = 1.0 + t * t
    return x.chain(t, d, 2.0 * t * d)
}

/** Arc sine. */
fun asin(x: Dual2): Dual2 {
    val a = x.value
    val r = kotlin.math.sqrt(1.0 - a * a)
    return x.chain(kotlin.math.asin(a), 1.0 / r, a / (r * r * r))
}

/** Arc cosine. */
fun acos(x: Dual2): Dual2 {
    val a = x.value
    val r = kotlin.math.sqrt(1.0 - a * a)
    return x.chain(kotlin.math.acos(a), -1.0 / r, -a / (r * r * r))
}

/** Arc tangent. */
fun atan(x: Dual2): Dual2 {
    val a = x.value
    val d = 1.0 / (1.0 + a * a)
    return x.chain(kotlin.math.atan(a), d, -2.0 * a * d * d)
}

/** Hyperbolic sine. */
fun sinh(x: Dual2): Dual2 {
    val s = kotlin.math.sinh(x.value)
    return x.chain(s, kotlin.math.cosh(x.value), s)
}

/** Hyperbolic cosine. */
fun cosh(x: Dual2): Dual2 {
    val c = kotlin.math.cosh(x.value)
    return x.chain(c, kotlin.math.sinh(x.value), c)
}

/** Hyperbolic tangent. */
fun tanh(x: Dual2): Dual2 {
    val t = kotlin.math.tanh(x.value)
    val d = 1.0 - t * t
    return x.chain(t, d, -2.0 * t * d)
}

/** Absolute value; the derivative is 0 at exactly zero, and the curvature is 0 everywhere. */
fun abs(x: Dual2): Dual2 {
    val s = if (x.value > 0.0) 1.0 else if (x.value < 0.0) -1.0 else 0.0
    return x.chain(x.value.absoluteValue, s, 0.0)
}

/** The rectifier `max(x, 0)`; derivative 0.5 at exactly zero, curvature 0 everywhere. */
fun relu(x: Dual2): Dual2 = max(x, 0.0)

/** The logistic sigmoid `1/(1+e⁻ˣ)`; `σ'' = σ(1-σ)(1-2σ)`. */
fun sigmoid(x: Dual2): Dual2 {
    val a = x.value
    val v = if (a >= 0.0) 1.0 / (1.0 + kotlin.math.exp(-a)) else kotlin.math.exp(a) / (1.0 + kotlin.math.exp(a))
    val d = v * (1.0 - v)
    return x.chain(v, d, d * (1.0 - 2.0 * v))
}

/** The smooth rectifier `ln(1+eˣ)`; its derivative is the sigmoid and its curvature `σ(1-σ)`. */
fun softplus(x: Dual2): Dual2 {
    val a = x.value
    val v = if (a > 30.0) a else if (a < -30.0) kotlin.math.exp(a) else kotlin.math.ln(1.0 + kotlin.math.exp(a))
    val s = if (a >= 0.0) 1.0 / (1.0 + kotlin.math.exp(-a)) else kotlin.math.exp(a) / (1.0 + kotlin.math.exp(a))
    return x.chain(v, s, s * (1.0 - s))
}

/** Floor — piecewise constant, so both derivatives are 0. */
fun floor(x: Dual2): Dual2 = Dual2(kotlin.math.floor(x.value), 0.0, 0.0)

/** Ceiling — piecewise constant, so both derivatives are 0. */
fun ceil(x: Dual2): Dual2 = Dual2(kotlin.math.ceil(x.value), 0.0, 0.0)

/** Nearest integer — piecewise constant, so both derivatives are 0. */
fun round(x: Dual2): Dual2 = Dual2(kotlin.math.round(x.value), 0.0, 0.0)

/** `a` raised to the power `b`, both tracked, as `exp(b · ln a)`; needs a strictly positive base. */
fun pow(base: Dual2, exponent: Dual2): Dual2 {
    require(base.value > 0.0) { "Dual2 pow with a tracked exponent needs a positive base, got ${base.value}" }
    return exp(exponent * ln(base))
}

/** The larger of two, selecting a branch; an exact tie averages both, matching the AD convention. */
fun max(a: Dual2, b: Dual2): Dual2 = when {
    a.value > b.value -> a
    a.value < b.value -> b
    else -> Dual2(a.value, 0.5 * (a.first + b.first), 0.5 * (a.second + b.second))
}

/** The smaller of two, selecting a branch; an exact tie averages both. */
fun min(a: Dual2, b: Dual2): Dual2 = when {
    a.value < b.value -> a
    a.value > b.value -> b
    else -> Dual2(a.value, 0.5 * (a.first + b.first), 0.5 * (a.second + b.second))
}

/** The larger of a tracked value and a constant. */
fun max(a: Dual2, b: Double): Dual2 = max(a, Dual2.constant(b))

/** The smaller of a tracked value and a constant. */
fun min(a: Dual2, b: Double): Dual2 = min(a, Dual2.constant(b))

/** `√(x²+y²)`. */
fun hypot(x: Dual2, y: Dual2): Dual2 = sqrt(x.sq() + y.sq())

/**
 * Two-argument arc tangent, correct in all four quadrants.
 *
 * The derivatives are those of `atan(y/x)`, which agree with `atan2`'s wherever `x ≠ 0`; only the
 * value carries the quadrant. Undefined derivatives on the `x = 0` axis are left to `1/0`.
 */
fun atan2(y: Dual2, x: Dual2): Dual2 {
    val inner = atan(y / x)
    return Dual2(kotlin.math.atan2(y.value, x.value), inner.first, inner.second)
}

// ---- reductions ---------------------------------------------------------------------------------

/** The sum of a list. */
fun sumDual(values: List<Dual2>): Dual2 {
    require(values.isNotEmpty()) { "sum of an empty list is ambiguous here; pass at least one term" }
    var acc = values[0]
    for (i in 1 until values.size) acc = acc + values[i]
    return acc
}

/** The arithmetic mean of a list. */
fun meanDual(values: List<Dual2>): Dual2 = sumDual(values) / values.size.toDouble()

/** The sum of squares of a list. */
fun sumOfSquaresDual(values: List<Dual2>): Dual2 = sumDual(values.map { it.sq() })

/** The dot product of two equal-length lists. */
fun dotDual(a: List<Dual2>, b: List<Dual2>): Dual2 {
    require(a.size == b.size) { "dot needs equal lengths, got ${a.size} and ${b.size}" }
    return sumDual(a.indices.map { a[it] * b[it] })
}

/** The Euclidean norm of a list. */
fun normDual(values: List<Dual2>): Dual2 = sqrt(sumOfSquaresDual(values))

// ---- entry points --------------------------------------------------------------------------------

/**
 * A scalar function of a vector written once against [Dual2], so it can be handed to both
 * [hessian] and [directionalDerivative] without being restated.
 */
typealias ADFunction2 = (List<Dual2>) -> Dual2

/** The value, first and second derivative of a single-variable function at [x], in one pass. */
fun taylor2(x: Double, f: (Dual2) -> Dual2): Dual2 = f(Dual2.variable(x))

/** The exact second derivative of a single-variable function at [x]. */
fun secondDerivative(x: Double, f: (Dual2) -> Dual2): Double = taylor2(x, f).second

/**
 * The exact directional derivative `∇f(x)·v` — one forward pass, whatever the dimension.
 *
 * Cheaper than a whole gradient when you only need the slope along one direction (a line search,
 * a curvature probe); [Dual2.second] of the same result is `vᵀHv`, the curvature along `v`.
 */
fun directionalDerivative(x: DoubleArray, direction: DoubleArray, f: ADFunction2): Dual2 {
    require(x.size == direction.size) { "point and direction must have the same length" }
    return f(List(x.size) { Dual2.seeded(x[it], direction[it]) })
}

/**
 * The gradient by forward mode — `n` passes, one per axis.
 *
 * Present mostly as an independent check on [gradAt]: two entirely separate mechanisms
 * (tape-and-sweep versus Taylor propagation) that must agree. Prefer [gradAt] in real use, which
 * needs one pass rather than `n`.
 */
fun gradForward(x: DoubleArray, f: ADFunction2): DoubleArray = DoubleArray(x.size) { i ->
    f(List(x.size) { k -> Dual2.seeded(x[k], if (k == i) 1.0 else 0.0) }).first
}
