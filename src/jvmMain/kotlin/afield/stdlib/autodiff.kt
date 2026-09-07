@file:Suppress("MemberVisibilityCanBePrivate")
@file:afield.CompletionCategory("Math")

package afield.stdlib

import kotlin.math.absoluteValue

/**
 * Reverse-mode automatic differentiation over `Double` scalars — exact gradients for ordinary
 * Kotlin arithmetic, in one pass, whatever the expression's size.
 *
 * The alternative is a numerical gradient, which is the default in [fitGradient] and
 * [leastSquares]: central differences over the loss or the residuals, `2·n` extra evaluations per
 * step, and a truncation-against-round-off compromise you tune with an `fdStep`. Reverse mode
 * costs one forward pass plus one backward sweep for *all* `n` derivatives, and the answer is
 * exact to the last few ulps.
 *
 * ## AD and the tape
 *
 * [AD] is a scalar that remembers how it was made. Arithmetic on [AD] values appends to an [ADTape]
 * — a flat array of (value, up-to-two parents, up-to-two local partials) — so
 * `(a * b + sin(c)) / 2.0` reads exactly like `Double` arithmetic and records a graph as it goes. The
 * backward sweep walks that array once in reverse accumulating adjoints. Because every node has at
 * most two parents, the tape is five parallel arrays of primitives — value, two parent indices,
 * two partials — and there is no object churn beyond the [AD] handles themselves.
 *
 * ```kotlin
 * val g = grad { p -> (p[0] - 3.0).sq() + 2.0 * (p[1] + 1.0).sq() }
 * g(doubleArrayOf(0.0, 0.0))          // [-6.0, 4.0], exact
 *
 * val (v, d) = valueAndDerivative(0.4) { x -> exp(-x * x) * sin(6.0 * x) }
 * ```
 *
 * [grad] returns a *function*, so it composes with the optimizers in `autodiff_optimize.kt`
 * ([minimize], [adam], [sgd], [lbfgs]) without a wrapper. [gradAt], [valueAndGrad], [jacobian] and
 * [hessian] are the immediate forms.
 *
 * ## Forward mode
 *
 * [Dual2] is a second-order forward-mode number (value, first derivative, second derivative along a
 * direction). It is the right tool when there is one input and many outputs — the reverse of
 * reverse mode — and it is what makes [hessian] *exact* rather than a finite difference of an exact
 * gradient: `vᵀHv` falls out of a single [Dual2] pass, and polarisation recovers the off-diagonal
 * entries. See [directionalDerivative] and [secondDerivative].
 *
 * ## The kink convention
 *
 * `abs`, `min`, `max` and `relu` are not differentiable at their kink, so a convention has to be
 * chosen. This library **splits the derivative evenly at an exact tie**: `min(a, b)` with `a == b`
 * sends 0.5 to each argument, and `relu` (which is `max(x, 0)`) has derivative 0.5 at zero. That is
 * what a central difference reports at a kink, so the analytic gradient and the finite-difference
 * oracle agree there too, and it makes `abs(x)` and `max(x, -x)` agree on derivative 0 at zero,
 * which is the minimum-norm subgradient. PyTorch instead defines `relu'(0) = 0`.
 *
 * ## What is not covered
 *
 * - **Mutation.** [AD] is immutable. `x += y` on a `var` rebinds it to a fresh handle and appends
 *   a node; the handle it replaced stays valid, and an accumulation loop therefore grows the tape
 *   by one node per iteration.
 * - **Control flow** is differentiated *as taken*: an `if (x > 0.0)` records only the branch that
 *   ran, which is the correct derivative of the piecewise function everywhere except at the
 *   switch-over. Comparisons on [AD] therefore return plain `Boolean` and carry no derivative.
 * - **Piecewise-constant ops** (`floor`, `round`, `sign`, `quantize`) have derivative 0 by
 *   definition, and no finite-difference oracle exists at their jumps.
 * - **Third and higher derivatives** are not available. [hessian] is exact; a third-order term
 *   would need a `Dual3` or a nestable tape and neither is here.
 * - **Tape size** grows with the number of *operations executed*, not with `n`. A loss over 100k
 *   samples builds a 100k-node tape per evaluation; that is the memory cost of reverse mode and it
 *   is not chunked or checkpointed.
 */

// ---------------------------------------------------------------------------------------------
// The tape
// ---------------------------------------------------------------------------------------------

/**
 * The append-only record of every operation performed on the [AD] values that belong to it.
 *
 * A tape is cheap and short-lived: one is created per gradient evaluation and thrown away. Each
 * entry stores the node's value plus at most two `(parent, ∂self/∂parent)` pairs, which is enough
 * for every operation in this file — n-ary reductions like [sum] decompose into a chain of
 * additions. Storage is five parallel primitive arrays that double when full, so building a tape
 * allocates nothing per node.
 *
 * You rarely name one. [grad], [gradAt], [valueAndGrad], [jacobian] and [hessian] make and discard
 * a tape for you; reach for [ADTape] directly only when you want several outputs differentiated
 * against one shared forward pass (see [backward]).
 */
class ADTape {

    private var count = 0
    private var values = DoubleArray(64)
    private var parent0 = IntArray(64)
    private var parent1 = IntArray(64)
    private var partial0 = DoubleArray(64)
    private var partial1 = DoubleArray(64)

    /** The number of operations recorded so far — the tape's length, useful for budgeting. */
    val size: Int get() = count

    private fun ensure(n: Int) {
        if (n <= values.size) return
        var next = values.size
        while (next < n) next *= 2
        values = values.copyOf(next)
        parent0 = parent0.copyOf(next)
        parent1 = parent1.copyOf(next)
        partial0 = partial0.copyOf(next)
        partial1 = partial1.copyOf(next)
    }

    internal fun valueAt(index: Int): Double = values[index]

    internal fun push(value: Double, p0: Int, d0: Double, p1: Int, d1: Double): Int {
        ensure(count + 1)
        values[count] = value
        parent0[count] = p0
        partial0[count] = d0
        parent1[count] = p1
        partial1[count] = d1
        return count++
    }

    /**
     * A tracked scalar holding [value] with no parents — a leaf of the tape.
     *
     * Use this for the quantities you want derivatives *with respect to*. A literal that appears in
     * an expression (`2.0 * x`) does not need to be a variable; the `Double` overloads fold it into
     * the operation's partial and cost nothing.
     */
    fun variable(value: Double): AD = AD(this, push(value, -1, 0.0, -1, 0.0))

    /**
     * A tracked scalar holding [value] that is treated as a leaf — identical in
     * mechanics to [variable], named differently to say "I do not intend to differentiate this".
     */
    fun constant(value: Double): AD = AD(this, push(value, -1, 0.0, -1, 0.0))

    /** [variable] for a whole vector, in order — the usual way to start a gradient. */
    fun variables(values: DoubleArray): List<AD> = values.map { variable(it) }

    /**
     * Sweeps backwards from [output], returning the adjoint (∂output/∂node) of **every** node on
     * the tape, indexed by [AD.index].
     *
     * One sweep answers for all inputs at once — that is the whole point of reverse mode. Call it
     * more than once (once per output) when you want a Jacobian from a shared forward pass; each
     * call allocates its own adjoint array and the tape is not consumed.
     */
    fun backward(output: AD): DoubleArray {
        require(output.tape === this) { "output belongs to a different tape" }
        val adjoint = DoubleArray(count)
        adjoint[output.index] = 1.0
        for (i in count - 1 downTo 0) {
            val a = adjoint[i]
            if (a == 0.0) continue
            val p0 = parent0[i]
            if (p0 >= 0) adjoint[p0] += a * partial0[i]
            val p1 = parent1[i]
            if (p1 >= 0) adjoint[p1] += a * partial1[i]
        }
        return adjoint
    }
}

/**
 * A scalar that remembers how it was computed: a value plus its position on an [ADTape].
 *
 * Arithmetic behaves exactly like `Double` — the operators, [kotlin.math]-shaped free functions
 * ([sin], [exp], [ln], …) and reductions ([sum], [dot]) in this file all take and return [AD] and
 * append to the tape as a side effect. Mixed arithmetic with `Double` works in both directions and
 * is *cheaper* than promoting the constant, since a literal folds into the local partial.
 *
 * Equality and comparison read the value only, so `if (x > 0.0)` and `list.sortedBy { it.value }`
 * do what you expect — but note the control-flow caveat in the file KDoc: a branch is
 * differentiated as taken.
 */
class AD internal constructor(
    /** The tape this value was recorded on. Two [AD]s can only be combined if they share one. */
    val tape: ADTape,
    internal val index: Int
) : Comparable<AD> {

    /** The plain numeric value, with no derivative information. */
    val value: Double get() = tape.valueAt(index)

    private fun unary(v: Double, d: Double): AD = AD(tape, tape.push(v, index, d, -1, 0.0))

    private fun binary(other: AD, v: Double, dSelf: Double, dOther: Double): AD {
        require(other.tape === tape) { "cannot combine AD values from different tapes" }
        return AD(tape, tape.push(v, index, dSelf, other.index, dOther))
    }

    // ---- arithmetic -----------------------------------------------------------------------

    /** Sum. */
    operator fun plus(other: AD): AD = binary(other, value + other.value, 1.0, 1.0)

    /** Sum with a constant — the constant contributes no derivative. */
    operator fun plus(other: Double): AD = unary(value + other, 1.0)

    /** Difference. */
    operator fun minus(other: AD): AD = binary(other, value - other.value, 1.0, -1.0)

    /** Difference with a constant. */
    operator fun minus(other: Double): AD = unary(value - other, 1.0)

    /** Product; `∂(ab)/∂a = b`. */
    operator fun times(other: AD): AD = binary(other, value * other.value, other.value, value)

    /** Product with a constant, whose value becomes the local partial. */
    operator fun times(other: Double): AD = unary(value * other, other)

    /** Quotient; `∂(a/b)/∂b = -a/b²`. */
    operator fun div(other: AD): AD {
        val inv = 1.0 / other.value
        return binary(other, value * inv, inv, -value * inv * inv)
    }

    /** Quotient by a constant. */
    operator fun div(other: Double): AD = unary(value / other, 1.0 / other)

    /** Negation. */
    operator fun unaryMinus(): AD = unary(-value, -1.0)

    /** Identity, so `+x` is legal and free of surprises. */
    operator fun unaryPlus(): AD = this

    // ---- comparison -----------------------------------------------------------------------

    /** Compares by [value] only — comparisons carry no derivative; see the control-flow caveat. */
    override fun compareTo(other: AD): Int = value.compareTo(other.value)

    /** Compares by [value] against a plain number. */
    operator fun compareTo(other: Double): Int = value.compareTo(other)

    override fun toString(): String = "AD($value)"
}

// ---- Double-on-the-left operators -----------------------------------------------------------

/** `2.0 + x`, with the literal folded in at no tape cost. */
operator fun Double.plus(other: AD): AD = other + this

/** `2.0 - x`. */
operator fun Double.minus(other: AD): AD = -other + this

/** `2.0 * x`. */
operator fun Double.times(other: AD): AD = other * this

/** `2.0 / x`; `∂/∂x = -k/x²`. */
operator fun Double.div(other: AD): AD = other.reciprocal() * this

/** `2 + x` for integer literals. */
operator fun Int.plus(other: AD): AD = other + this.toDouble()

/** `2 - x` for integer literals. */
operator fun Int.minus(other: AD): AD = -other + this.toDouble()

/** `2 * x` for integer literals. */
operator fun Int.times(other: AD): AD = other * this.toDouble()

/** `2 / x` for integer literals. */
operator fun Int.div(other: AD): AD = other.reciprocal() * this.toDouble()

/** Compares a plain number against a tracked one, by value. */
operator fun Double.compareTo(other: AD): Int = this.compareTo(other.value)

// ---------------------------------------------------------------------------------------------
// Elementary functions
// ---------------------------------------------------------------------------------------------

private fun AD.node(v: Double, d: Double): AD = AD(tape, tape.push(v, index, d, -1, 0.0))

/** Reciprocal `1/x`; `∂ = -1/x²`. */
fun AD.reciprocal(): AD {
    val inv = 1.0 / value
    return node(inv, -inv * inv)
}

/** The square `x²`, cheaper and more readable than `x * x`; `∂ = 2x`. */
fun AD.sq(): AD = node(value * value, 2.0 * value)

/** Natural exponential; `∂ = eˣ`. */
fun exp(x: AD): AD {
    val v = kotlin.math.exp(x.value)
    return x.node(v, v)
}

/** Natural logarithm; `∂ = 1/x`. Non-positive input gives NaN/-∞ as `kotlin.math` does. */
fun ln(x: AD): AD = x.node(kotlin.math.ln(x.value), 1.0 / x.value)

/** Base-10 logarithm; `∂ = 1/(x·ln 10)`. */
fun log10(x: AD): AD = x.node(kotlin.math.log10(x.value), 1.0 / (x.value * kotlin.math.ln(10.0)))

/** Base-2 logarithm; `∂ = 1/(x·ln 2)`. */
fun log2(x: AD): AD = x.node(kotlin.math.log2(x.value), 1.0 / (x.value * kotlin.math.ln(2.0)))

/** Square root; `∂ = 1/(2√x)`, which is +∞ at zero. */
fun sqrt(x: AD): AD {
    val v = kotlin.math.sqrt(x.value)
    return x.node(v, 0.5 / v)
}

/** Cube root, defined for negative input too; `∂ = 1/(3·x^(2/3))`. */
fun cbrt(x: AD): AD {
    val v = Math.cbrt(x.value)
    return x.node(v, 1.0 / (3.0 * v * v))
}

/** Sine of an angle in radians; `∂ = cos x`. */
fun sin(x: AD): AD = x.node(kotlin.math.sin(x.value), kotlin.math.cos(x.value))

/** Cosine of an angle in radians; `∂ = -sin x`. */
fun cos(x: AD): AD = x.node(kotlin.math.cos(x.value), -kotlin.math.sin(x.value))

/** Tangent; `∂ = 1 + tan²x`. */
fun tan(x: AD): AD {
    val t = kotlin.math.tan(x.value)
    return x.node(t, 1.0 + t * t)
}

/** Arc sine; `∂ = 1/√(1-x²)`. */
fun asin(x: AD): AD = x.node(kotlin.math.asin(x.value), 1.0 / kotlin.math.sqrt(1.0 - x.value * x.value))

/** Arc cosine; `∂ = -1/√(1-x²)`. */
fun acos(x: AD): AD = x.node(kotlin.math.acos(x.value), -1.0 / kotlin.math.sqrt(1.0 - x.value * x.value))

/** Arc tangent; `∂ = 1/(1+x²)`. */
fun atan(x: AD): AD = x.node(kotlin.math.atan(x.value), 1.0 / (1.0 + x.value * x.value))

/** Hyperbolic sine; `∂ = cosh x`. */
fun sinh(x: AD): AD = x.node(kotlin.math.sinh(x.value), kotlin.math.cosh(x.value))

/** Hyperbolic cosine; `∂ = sinh x`. */
fun cosh(x: AD): AD = x.node(kotlin.math.cosh(x.value), kotlin.math.sinh(x.value))

/** Hyperbolic tangent — the classic squashing nonlinearity; `∂ = 1 - tanh²x`. */
fun tanh(x: AD): AD {
    val t = kotlin.math.tanh(x.value)
    return x.node(t, 1.0 - t * t)
}

/**
 * Absolute value; `∂ = sign x`, and **0 at exactly zero** — see the kink convention in the file
 * KDoc, which is also what a central difference reports there.
 */
fun abs(x: AD): AD = x.node(x.value.absoluteValue, if (x.value > 0.0) 1.0 else if (x.value < 0.0) -1.0 else 0.0)

/**
 * The rectifier `max(x, 0)`; `∂ = 1` for positive input, `0` for negative, and **0.5 at exactly
 * zero** under this library's even-split tie convention.
 */
fun relu(x: AD): AD =
    x.node(if (x.value > 0.0) x.value else 0.0, if (x.value > 0.0) 1.0 else if (x.value < 0.0) 0.0 else 0.5)

/** The logistic sigmoid `1/(1+e⁻ˣ)`; `∂ = σ(1-σ)`. Computed in the numerically stable branch. */
fun sigmoid(x: AD): AD {
    val v = if (x.value >= 0.0) {
        1.0 / (1.0 + kotlin.math.exp(-x.value))
    } else {
        val e = kotlin.math.exp(x.value)
        e / (1.0 + e)
    }
    return x.node(v, v * (1.0 - v))
}

/** The smooth rectifier `ln(1+eˣ)`; `∂ = σ(x)`. Stable for large `|x|`. */
fun softplus(x: AD): AD {
    val a = x.value
    val v = if (a > 30.0) a else if (a < -30.0) kotlin.math.exp(a) else kotlin.math.ln(1.0 + kotlin.math.exp(a))
    val d = if (a >= 0.0) 1.0 / (1.0 + kotlin.math.exp(-a)) else kotlin.math.exp(a) / (1.0 + kotlin.math.exp(a))
    return x.node(v, d)
}

/** Floor. Piecewise constant, so the derivative is 0 everywhere it exists. */
fun floor(x: AD): AD = x.node(kotlin.math.floor(x.value), 0.0)

/** Ceiling. Piecewise constant, so the derivative is 0 everywhere it exists. */
fun ceil(x: AD): AD = x.node(kotlin.math.ceil(x.value), 0.0)

/** Nearest integer. Piecewise constant, so the derivative is 0 everywhere it exists. */
fun round(x: AD): AD = x.node(kotlin.math.round(x.value), 0.0)

/** The sign of a tracked value as a plain `Double` — *not* an [AD], since its
 *  derivative is 0 away from the origin and undefined at it. */
fun sign(x: AD): Double = kotlin.math.sign(x.value)

/**
 * `x` raised to a constant power; `∂ = k·x^(k-1)`.
 *
 * The constant-exponent form is the common case and is exact for negative `x` at integer `k`,
 * which the two-argument [pow] cannot be.
 */
fun AD.pow(exponent: Double): AD =
    node(Math.pow(value, exponent), exponent * Math.pow(value, exponent - 1.0))

/** `x` raised to a constant integer power. */
fun AD.pow(exponent: Int): AD = pow(exponent.toDouble())

/**
 * `a` raised to the power `b`, both tracked.
 *
 * `∂/∂a = b·a^(b-1)` and `∂/∂b = a^b·ln a`. The second is undefined for `a ≤ 0`, where this
 * returns **0** for the exponent's partial rather than a NaN that would poison the whole sweep —
 * differentiate through [pow]`(Double)` instead if the base can go non-positive.
 */
fun pow(base: AD, exponent: AD): AD {
    require(base.tape === exponent.tape) { "cannot combine AD values from different tapes" }
    val a = base.value
    val b = exponent.value
    val v = Math.pow(a, b)
    val dBase = b * Math.pow(a, b - 1.0)
    val dExp = if (a > 0.0) v * kotlin.math.ln(a) else 0.0
    return AD(base.tape, base.tape.push(v, base.index, dBase, exponent.index, dExp))
}

/**
 * The larger of two tracked values. At an exact tie the derivative splits 0.5/0.5 — see the kink
 * convention in the file KDoc.
 */
fun max(a: AD, b: AD): AD {
    require(a.tape === b.tape) { "cannot combine AD values from different tapes" }
    val da: Double
    val db: Double
    when {
        a.value > b.value -> { da = 1.0; db = 0.0 }
        a.value < b.value -> { da = 0.0; db = 1.0 }
        else -> { da = 0.5; db = 0.5 }
    }
    return AD(a.tape, a.tape.push(kotlin.math.max(a.value, b.value), a.index, da, b.index, db))
}

/**
 * The smaller of two tracked values. At an exact tie the derivative splits 0.5/0.5 — see the kink
 * convention in the file KDoc.
 */
fun min(a: AD, b: AD): AD {
    require(a.tape === b.tape) { "cannot combine AD values from different tapes" }
    val da: Double
    val db: Double
    when {
        a.value < b.value -> { da = 1.0; db = 0.0 }
        a.value > b.value -> { da = 0.0; db = 1.0 }
        else -> { da = 0.5; db = 0.5 }
    }
    return AD(a.tape, a.tape.push(kotlin.math.min(a.value, b.value), a.index, da, b.index, db))
}

/** The larger of a tracked value and a constant; ties split 0.5/0.5 as elsewhere. */
fun max(a: AD, b: Double): AD =
    a.node(kotlin.math.max(a.value, b), if (a.value > b) 1.0 else if (a.value < b) 0.0 else 0.5)

/** The smaller of a tracked value and a constant; ties split 0.5/0.5 as elsewhere. */
fun min(a: AD, b: Double): AD =
    a.node(kotlin.math.min(a.value, b), if (a.value < b) 1.0 else if (a.value > b) 0.0 else 0.5)

/** Clamps into `[lo, hi]`; the derivative is 1 strictly inside and 0 strictly outside, with the
 *  usual even split exactly on a boundary. */
fun AD.clamp(lo: Double, hi: Double): AD {
    require(lo <= hi) { "clamp bounds are inverted: $lo > $hi" }
    return min(max(this, lo), hi)
}

/**
 * Two-argument arc tangent, `atan2(y, x)`, correct in all four quadrants.
 *
 * `∂/∂y = x/(x²+y²)` and `∂/∂x = -y/(x²+y²)` — the reason to prefer this over `atan(y/x)` in a
 * differentiated angle is that it stays finite and correctly signed as `x` crosses zero.
 */
fun atan2(y: AD, x: AD): AD {
    require(y.tape === x.tape) { "cannot combine AD values from different tapes" }
    val denom = y.value * y.value + x.value * x.value
    return AD(
        y.tape,
        y.tape.push(kotlin.math.atan2(y.value, x.value), y.index, x.value / denom, x.index, -y.value / denom)
    )
}

/**
 * `√(x²+y²)` without intermediate overflow; the partials are the components of the unit vector.
 *
 * The value is `kotlin.math.hypot` and not `sqrt(x*x + y*y)`, which is what the doc comment said all
 * along and the body did not: the naive form squares before it adds, so it overflows for components
 * above ~1.3e154 and underflows below ~1.5e-162, and it is up to an ulp away from the correctly
 * rounded answer everywhere else. That last ulp is not cosmetic — `FnExpr.Hypot` exists so a
 * *threshold test* on a length can be transcribed exactly, and an interpreter computing a different
 * length would be exactly the silent divergence `FnAutoDiff.agrees` is there to catch.
 */
fun hypot(x: AD, y: AD): AD {
    require(x.tape === y.tape) { "cannot combine AD values from different tapes" }
    val v = kotlin.math.hypot(x.value, y.value)
    val dx = if (v == 0.0) 0.0 else x.value / v
    val dy = if (v == 0.0) 0.0 else y.value / v
    return AD(x.tape, x.tape.push(v, x.index, dx, y.index, dy))
}

// ---------------------------------------------------------------------------------------------
// Reductions
// ---------------------------------------------------------------------------------------------

/** The sum of a list of tracked values, as a left fold — the empty list is an error, since there
 *  is no tape to hang a zero on. */
fun sum(values: List<AD>): AD {
    require(values.isNotEmpty()) { "sum of an empty list has no tape to record on" }
    var acc = values[0]
    for (i in 1 until values.size) acc = acc + values[i]
    return acc
}

/** The arithmetic mean of a list of tracked values. */
fun mean(values: List<AD>): AD = sum(values) / values.size.toDouble()

/** The product of a list of tracked values. */
fun product(values: List<AD>): AD {
    require(values.isNotEmpty()) { "product of an empty list has no tape to record on" }
    var acc = values[0]
    for (i in 1 until values.size) acc = acc * values[i]
    return acc
}

/** The dot product of two equal-length lists of tracked values. */
fun dot(a: List<AD>, b: List<AD>): AD {
    require(a.size == b.size) { "dot needs equal lengths, got ${a.size} and ${b.size}" }
    require(a.isNotEmpty()) { "dot of empty vectors has no tape to record on" }
    var acc = a[0] * b[0]
    for (i in 1 until a.size) acc = acc + a[i] * b[i]
    return acc
}

/** The sum of squares — the squared Euclidean norm. */
fun sumOfSquares(values: List<AD>): AD = sum(values.map { it.sq() })

/** The Euclidean norm `√Σxᵢ²`. Its derivative is undefined at the origin, where `√` reports ∞. */
fun norm(values: List<AD>): AD = sqrt(sumOfSquares(values))

/** The largest element, folded pairwise with [max] (so ties split evenly at each comparison). */
fun maxOf(values: List<AD>): AD {
    require(values.isNotEmpty()) { "maxOf an empty list has no tape to record on" }
    var acc = values[0]
    for (i in 1 until values.size) acc = max(acc, values[i])
    return acc
}

/** The smallest element, folded pairwise with [min]. */
fun minOf(values: List<AD>): AD {
    require(values.isNotEmpty()) { "minOf an empty list has no tape to record on" }
    var acc = values[0]
    for (i in 1 until values.size) acc = min(acc, values[i])
    return acc
}

/**
 * `ln Σ eˣⁱ`, shifted by the maximum so it never overflows — the smooth maximum, and the numerically
 * safe way to write a soft-min/soft-max penalty inside a differentiated loss.
 */
fun logSumExp(values: List<AD>): AD {
    require(values.isNotEmpty()) { "logSumExp of an empty list has no tape to record on" }
    val shift = values.maxOf { it.value }
    var acc = exp(values[0] - shift)
    for (i in 1 until values.size) acc = acc + exp(values[i] - shift)
    return ln(acc) + shift
}

/** The softmax of a list — each element's share of `Σeˣ`, summing to one. */
fun softmax(values: List<AD>): List<AD> {
    val lse = logSumExp(values)
    return values.map { exp(it - lse) }
}

// ---------------------------------------------------------------------------------------------
// Entry points
// ---------------------------------------------------------------------------------------------

/** A value together with its exact gradient — the result of [valueAndGrad]. */
data class ADGradient(
    /** The objective's value at the point that was differentiated. */
    val value: Double,
    /** `∂value/∂xᵢ`, in the order the inputs were given. */
    val gradient: DoubleArray
) {
    /** The largest absolute component of the gradient — the usual convergence test. */
    val gradientInfNorm: Double get() = if (gradient.isEmpty()) 0.0 else gradient.maxOf { it.absoluteValue }

    override fun equals(other: Any?): Boolean =
        other is ADGradient && value == other.value && gradient.contentEquals(other.gradient)

    override fun hashCode(): Int = 31 * value.hashCode() + gradient.contentHashCode()
}

/**
 * The exact value and gradient of [f] at [x], in one forward pass and one backward sweep.
 *
 * ```kotlin
 * val r = valueAndGrad(doubleArrayOf(1.0, 2.0)) { p -> p[0].sq() * p[1] + sin(p[1]) }
 * r.value      // 2.0 + sin 2
 * r.gradient   // [2·x·y, x² + cos y] = [4.0, 1 + cos 2]
 * ```
 */
fun valueAndGrad(x: DoubleArray, f: (List<AD>) -> AD): ADGradient {
    val tape = ADTape()
    val inputs = tape.variables(x)
    val output = f(inputs)
    require(output.tape === tape) {
        "the objective returned a value from a different tape — do not close over AD values from an outer gradient"
    }
    val adjoint = tape.backward(output)
    return ADGradient(output.value, DoubleArray(x.size) { adjoint[inputs[it].index] })
}

/** The exact gradient of [f] at [x]; [valueAndGrad] when you also want the value. */
fun gradAt(x: DoubleArray, f: (List<AD>) -> AD): DoubleArray = valueAndGrad(x, f).gradient

/**
 * Turns an objective into its gradient *function* — the curried form, and the one the optimizers
 * in `autodiff_optimize.kt` take.
 *
 * ```kotlin
 * val g = grad { p -> (p[0] - 3.0).sq() + 2.0 * (p[1] + 1.0).sq() }
 * g(doubleArrayOf(0.0, 0.0))   // [-6.0, 4.0]
 * ```
 */
fun grad(f: (List<AD>) -> AD): (DoubleArray) -> DoubleArray = { x -> gradAt(x, f) }

/** Turns an objective into a function returning both value and gradient — what [minimize] wants. */
fun valueAndGradOf(f: (List<AD>) -> AD): (DoubleArray) -> ADGradient = { x -> valueAndGrad(x, f) }

/** The exact derivative of a single-variable function at [x]. */
fun derivative(x: Double, f: (AD) -> AD): Double = gradAt(doubleArrayOf(x)) { p -> f(p[0]) }[0]

/** The value and the exact derivative of a single-variable function at [x]. */
fun valueAndDerivative(x: Double, f: (AD) -> AD): Pair<Double, Double> {
    val r = valueAndGrad(doubleArrayOf(x)) { p -> f(p[0]) }
    return r.value to r.gradient[0]
}

/**
 * The exact Jacobian `∂fᵢ/∂xⱼ` of a vector-valued function, as `rows × columns`.
 *
 * One forward pass builds the tape; one backward sweep per *output* reads a row off it. That is the
 * right way round when there are few outputs and many inputs (a residual vector against many
 * parameters is the opposite case, and still fine — the shared forward pass is not repeated).
 */
fun jacobian(x: DoubleArray, f: (List<AD>) -> List<AD>): Array<DoubleArray> {
    val tape = ADTape()
    val inputs = tape.variables(x)
    val outputs = f(inputs)
    val indices = IntArray(x.size) { inputs[it].index }
    return Array(outputs.size) { row ->
        val adjoint = tape.backward(outputs[row])
        DoubleArray(x.size) { col -> adjoint[indices[col]] }
    }
}

/**
 * The exact Hessian `∂²f/∂xᵢ∂xⱼ`, symmetric, via second-order forward mode.
 *
 * A single [Dual2] pass along a direction `v` yields `vᵀHv` exactly. Running it along each axis
 * gives the diagonal, and along each `eᵢ+eⱼ` gives the off-diagonals by polarisation:
 * `H(i,j) = ½·((eᵢ+eⱼ)ᵀH(eᵢ+eⱼ) - Hᵢᵢ - Hⱼⱼ)`. Cost is `n(n+1)/2` passes, which is the price of
 * not having a nestable tape; it is exact, not a finite difference of a gradient.
 */
fun hessian(x: DoubleArray, f: (List<Dual2>) -> Dual2): Array<DoubleArray> {
    val n = x.size
    val h = Array(n) { DoubleArray(n) }
    val diagonal = DoubleArray(n)
    for (i in 0 until n) {
        diagonal[i] = curvatureAlong(x, i, -1, f)
        h[i][i] = diagonal[i]
    }
    for (i in 0 until n) {
        for (j in i + 1 until n) {
            val both = curvatureAlong(x, i, j, f)
            val v = 0.5 * (both - diagonal[i] - diagonal[j])
            h[i][j] = v
            h[j][i] = v
        }
    }
    return h
}

private fun curvatureAlong(x: DoubleArray, i: Int, j: Int, f: (List<Dual2>) -> Dual2): Double {
    val inputs = List(x.size) { k ->
        val d = (if (k == i) 1.0 else 0.0) + (if (k == j) 1.0 else 0.0)
        Dual2(x[k], d, 0.0)
    }
    return f(inputs).second
}
