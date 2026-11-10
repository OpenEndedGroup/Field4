package afield.math.distribution

import java.util.IdentityHashMap
import java.util.SplittableRandom
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A random variable: one node in a lazy expression graph whose every value is a distribution.
 *
 * Nothing is sampled when a `Dist` is built. `die(6) + die(6)` builds three nodes and returns the
 * last; only a query ([Noise.P], [Noise.E], [Noise.Var], [Noise.quantile], [Dist.samples]) draws.
 *
 * **A name is a node, not a draw.** Each sample evaluates every node once, so a node that appears
 * twice in an expression has the same value both times: `x - x` is exactly 0 and `x + x` is `2x`.
 * Use [iid] for a second, independent variable with the same distribution, and [Noise.draws] for
 * a vector of them.
 *
 * A constant is a Dirac measure ([Noise.dirac], or any `Number` in an operator), so arithmetic
 * mixes numbers and random variables freely. Comparisons ([lt], [gt], [le], [ge], [eq], [ne]) are
 * random variables that are 1 or 0, and `P(c)` is the mean of one.
 *
 * Every built-in node can also be written as GLSL, so the same graph runs on the GPU
 * ([toGlsl], [GpuMonteCarlo]). [map] is the exception: arbitrary Kotlin stays on the CPU.
 */
class Dist internal constructor(
    /** Short name of the operation, for [describe] and error messages. */
    val op: String,
    /** The nodes this one is computed from. Empty for a leaf. */
    val inputs: List<Dist>,
    internal val cpu: (ins: Array<DoubleArray>, out: DoubleArray, n: Int, rng: SplittableRandom) -> Unit,
    /** GLSL expression from the input variable names and the name of the `inout uint` RNG state. */
    internal val glsl: ((ins: List<String>, rng: String) -> String)?,
    /** Exact probability mass function, when this is a discrete leaf. */
    internal val pmf: Map<Double, Double>? = null,
    /** Exact density, when this is a continuous leaf (or an affine image of one). */
    internal val pdf: ((Double) -> Double)? = null,
    internal val kind: Kind = Kind.Op,
    /** For a Dirac: its value. */
    internal val constant: Double = Double.NaN,
    /** For [Kind.Linear] nodes: `out = a * in[0] + b * in[1] + c` (or with one input, `a * in + c`). */
    internal val linear: DoubleArray? = null,
) {
    internal enum class Kind { Dirac, Leaf, Op }

    /** For a normal leaf: (mean, sd), so sums of independent normals have an exact density. */
    internal var normalMs: Pair<Double, Double>? = null

    /** True if no sample of this variable depends on randomness. */
    val isConstant: Boolean get() = kind == Kind.Dirac || (kind == Kind.Op && inputs.all { it.isConstant })

    // ---- arithmetic -------------------------------------------------------------------------

    operator fun plus(o: Dist) = lin2(this, o, 1.0, 1.0, "+")
    operator fun minus(o: Dist) = lin2(this, o, 1.0, -1.0, "-")
    operator fun times(o: Dist) = bin("*", o, { a, b -> a * b }) { a, b -> "($a * $b)" }
    operator fun div(o: Dist) = bin("/", o, { a, b -> a / b }) { a, b -> "($a / $b)" }
    operator fun rem(o: Dist) = bin("%", o, { a, b -> a - b * floor(a / b) }) { a, b -> "mod($a, $b)" }
    operator fun plus(o: Number) = lin1(this, 1.0, o.toDouble())
    operator fun minus(o: Number) = lin1(this, 1.0, -o.toDouble())
    operator fun times(o: Number) = lin1(this, o.toDouble(), 0.0)
    operator fun div(o: Number) = lin1(this, 1.0 / o.toDouble(), 0.0)
    operator fun rem(o: Number) = rem(dirac(o))
    operator fun unaryMinus() = lin1(this, -1.0, 0.0)

    // ---- comparisons: 1 or 0 ----------------------------------------------------------------

    infix fun lt(o: Dist) = bin("<", o, { a, b -> if (a < b) 1.0 else 0.0 }) { a, b -> "float($a < $b)" }
    infix fun le(o: Dist) = bin("<=", o, { a, b -> if (a <= b) 1.0 else 0.0 }) { a, b -> "float($a <= $b)" }
    infix fun gt(o: Dist) = bin(">", o, { a, b -> if (a > b) 1.0 else 0.0 }) { a, b -> "float($a > $b)" }
    infix fun ge(o: Dist) = bin(">=", o, { a, b -> if (a >= b) 1.0 else 0.0 }) { a, b -> "float($a >= $b)" }
    infix fun eq(o: Dist) = bin("==", o, { a, b -> if (a == b) 1.0 else 0.0 }) { a, b -> "float($a == $b)" }
    infix fun ne(o: Dist) = bin("!=", o, { a, b -> if (a != b) 1.0 else 0.0 }) { a, b -> "float($a != $b)" }
    infix fun lt(o: Number) = lt(dirac(o))
    infix fun le(o: Number) = le(dirac(o))
    infix fun gt(o: Number) = gt(dirac(o))
    infix fun ge(o: Number) = ge(dirac(o))
    infix fun eq(o: Number) = eq(dirac(o))
    infix fun ne(o: Number) = ne(dirac(o))

    /** Logical and / or / not of 0-1 variables (anything non-zero is true). */
    infix fun and(o: Dist) = bin("and", o, { a, b -> if (a != 0.0 && b != 0.0) 1.0 else 0.0 }) { a, b -> "float($a != 0.0 && $b != 0.0)" }
    infix fun or(o: Dist) = bin("or", o, { a, b -> if (a != 0.0 || b != 0.0) 1.0 else 0.0 }) { a, b -> "float($a != 0.0 || $b != 0.0)" }
    operator fun not() = un("not", { if (it == 0.0) 1.0 else 0.0 }) { "float($it == 0.0)" }

    /** `this` where [cond] is true (non-zero), else [otherwise]. Both sides are evaluated. */
    fun where(cond: Dist, otherwise: Dist): Dist = Dist(
        "where", listOf(cond, this, otherwise),
        { ins, out, n, _ -> val c = ins[0]; val a = ins[1]; val b = ins[2]; for (i in 0 until n) out[i] = if (c[i] != 0.0) a[i] else b[i] },
        { ins, _ -> "(${ins[0]} != 0.0 ? ${ins[1]} : ${ins[2]})" },
    )

    // ---- functions --------------------------------------------------------------------------

    fun abs() = un("abs", ::abs) { "abs($it)" }
    fun sqrt() = un("sqrt", ::sqrt) { "sqrt($it)" }
    fun exp() = un("exp", ::exp) { "exp($it)" }
    fun ln() = un("log", ::ln) { "log($it)" }
    fun sin() = un("sin", { kotlin.math.sin(it) }) { "sin($it)" }
    fun cos() = un("cos", ::cos) { "cos($it)" }
    fun floor() = un("floor", ::floor) { "floor($it)" }
    fun squared() = this * this
    fun pow(e: Number) = un("pow", { it.pow(e.toDouble()) }) { "pow($it, ${glslFloat(e.toDouble())})" }
    fun min(o: Dist): Dist = bin("min", o, { a, b -> kotlin.math.min(a, b) }) { a, b -> "min($a, $b)" }
    fun max(o: Dist): Dist = bin("max", o, { a, b -> kotlin.math.max(a, b) }) { a, b -> "max($a, $b)" }
    fun min(o: Number): Dist = min(dirac(o))
    fun max(o: Number): Dist = max(dirac(o))

    /**
     * Arbitrary Kotlin applied per sample. CPU only: a graph containing a `map` will not lower to
     * GLSL, and [toGlsl] says which node stopped it.
     */
    fun map(name: String = "map", f: (Double) -> Double): Dist =
        Dist(name, listOf(this), { ins, out, n, _ -> val a = ins[0]; for (i in 0 until n) out[i] = f(a[i]) }, null)

    // ---- independence -----------------------------------------------------------------------

    /**
     * An independent copy: the same distribution, a different variable.
     *
     * Every node of this graph that depends on randomness is duplicated (sharing inside the graph
     * is preserved, so the copy of `x + x` is `x' + x'`); constants are shared.
     */
    fun iid(): Dist = copyGraph(this, IdentityHashMap())

    /** [n] independent copies. */
    fun iid(n: Int): List<Dist> = List(n) { iid() }

    // ---- sampling ---------------------------------------------------------------------------

    /** [n] draws, in one vectorised pass over the graph. */
    fun samples(n: Int, seed: Long = 1L): DoubleArray = Plan(listOf(this)).sample(n, SplittableRandom(seed))[0]

    /** A one-line structural description, e.g. `(die6 + die6)`. Two describes match iff the graphs match. */
    fun describe(): String = describeInto(this, IdentityHashMap(), StringBuilder()).toString()

    override fun toString(): String = "Dist(${describe()})"

    // ---- helpers ----------------------------------------------------------------------------

    private fun un(name: String, f: (Double) -> Double, g: (String) -> String) = Dist(
        name, listOf(this), { ins, out, n, _ -> val a = ins[0]; for (i in 0 until n) out[i] = f(a[i]) },
        { ins, _ -> g(ins[0]) },
    )

    private fun bin(name: String, o: Dist, f: (Double, Double) -> Double, g: (String, String) -> String) = Dist(
        name, listOf(this, o),
        { ins, out, n, _ -> val a = ins[0]; val b = ins[1]; for (i in 0 until n) out[i] = f(a[i], b[i]) },
        { ins, _ -> g(ins[0], ins[1]) },
    )

    companion object {
        /** The Dirac measure at [v]: a spike, all its weight on one point. */
        fun dirac(v: Number): Dist {
            val d = v.toDouble()
            return Dist(
                "dirac", emptyList(), { _, out, n, _ -> java.util.Arrays.fill(out, 0, n, d) },
                { _, _ -> glslFloat(d) }, pmf = mapOf(d to 1.0), kind = Kind.Dirac, constant = d,
            )
        }

        internal fun lin1(a: Dist, k: Double, c: Double): Dist = Dist(
            "lin", listOf(a),
            { ins, out, n, _ -> val x = ins[0]; for (i in 0 until n) out[i] = k * x[i] + c },
            { ins, _ -> "(${glslFloat(k)} * ${ins[0]} + ${glslFloat(c)})" },
            linear = doubleArrayOf(k, 0.0, c),
        )

        internal fun lin2(a: Dist, b: Dist, ka: Double, kb: Double, sym: String): Dist = Dist(
            sym, listOf(a, b),
            { ins, out, n, _ -> val x = ins[0]; val y = ins[1]; for (i in 0 until n) out[i] = ka * x[i] + kb * y[i] },
            { ins, _ -> "(${ins[0]} $sym ${ins[1]})" },
            linear = doubleArrayOf(ka, kb, 0.0),
        )

        private fun copyGraph(d: Dist, memo: IdentityHashMap<Dist, Dist>): Dist {
            if (d.isConstant) return d
            memo[d]?.let { return it }
            val c = Dist(d.op, d.inputs.map { copyGraph(it, memo) }, d.cpu, d.glsl, d.pmf, d.pdf, d.kind, d.constant, d.linear)
            c.normalMs = d.normalMs
            memo[d] = c
            return c
        }

        private fun describeInto(d: Dist, ids: IdentityHashMap<Dist, Int>, sb: StringBuilder): StringBuilder {
            when {
                d.kind == Kind.Dirac -> sb.append(fmtShort(d.constant))
                d.kind == Kind.Leaf -> { val id = ids.getOrPut(d) { ids.size }; sb.append(d.op).append('#').append(id) }
                d.inputs.size == 2 -> { sb.append('('); describeInto(d.inputs[0], ids, sb); sb.append(' ').append(d.op).append(' '); describeInto(d.inputs[1], ids, sb); sb.append(')') }
                d.op == "lin" -> { val l = d.linear!!; sb.append('('); sb.append(fmtShort(l[0])).append('*'); describeInto(d.inputs[0], ids, sb); sb.append(" + ").append(fmtShort(l[2])).append(')') }
                else -> { sb.append(d.op).append('('); d.inputs.forEachIndexed { i, x -> if (i > 0) sb.append(", "); describeInto(x, ids, sb) }; sb.append(')') }
            }
            return sb
        }
    }
}

internal fun fmtShort(v: Double): String = if (v == floor(v) && abs(v) < 1e15) v.toLong().toString() else "%.4g".format(v)

/** A GLSL float literal for [v] (always with a decimal point or exponent). */
internal fun glslFloat(v: Double): String {
    if (v.isNaN()) return "(0.0/0.0)"
    if (v.isInfinite()) return if (v > 0) "(1.0/0.0)" else "(-1.0/0.0)"
    val f = v.toFloat()
    val s = f.toString()
    return if (s.contains('.') || s.contains('E') || s.contains('e')) s.replace("E", "e") else "$s.0"
}

private fun dirac(v: Number) = Dist.dirac(v)

operator fun Number.plus(d: Dist) = d + this
operator fun Number.minus(d: Dist) = Dist.lin1(d, -1.0, this.toDouble())
operator fun Number.times(d: Dist) = d * this
operator fun Number.div(d: Dist) = Dist.dirac(this) / d

// ---------------------------------------------------------------------------------------------
// Leaves
// ---------------------------------------------------------------------------------------------

/** The PCG-hash uniform the GLSL lowering declares; every leaf draws through it. */
internal const val GLSL_RND = "nlRnd"

internal fun leaf(
    name: String,
    pmf: Map<Double, Double>? = null,
    pdf: ((Double) -> Double)? = null,
    glsl: (rng: String) -> String,
    cpu: (rng: SplittableRandom) -> Double,
) = Dist(
    name, emptyList(), { _, out, n, rng -> for (i in 0 until n) out[i] = cpu(rng) },
    { _, r -> glsl(r) }, pmf = pmf, pdf = pdf, kind = Dist.Kind.Leaf,
)

/** Uniform integers `lo..hi`, inclusive. */
fun uniformInt(lo: Int, hi: Int): Dist {
    require(hi >= lo) { "uniformInt($lo, $hi): empty range" }
    val k = hi - lo + 1
    val pmf = (lo..hi).associate { it.toDouble() to 1.0 / k }
    return leaf("unifInt$lo..$hi", pmf = pmf, glsl = { r -> "(floor($GLSL_RND($r) * ${glslFloat(k.toDouble())}) + ${glslFloat(lo.toDouble())})" }) {
        (lo + it.nextInt(k)).toDouble()
    }
}

/** A fair [sides]-sided die: uniform on `1..sides`. */
fun die(sides: Int = 6): Dist = uniformInt(1, sides).let {
    Dist("die$sides", it.inputs, it.cpu, it.glsl, it.pmf, it.pdf, it.kind)
}

/** Uniform on `[a, b)`. */
fun uniform(a: Number = 0.0, b: Number = 1.0): Dist {
    val lo = a.toDouble(); val hi = b.toDouble()
    return leaf("unif", pdf = { x -> if (x in lo..hi) 1.0 / (hi - lo) else 0.0 },
        glsl = { r -> "(${glslFloat(lo)} + ${glslFloat(hi - lo)} * $GLSL_RND($r))" }) { lo + (hi - lo) * it.nextDouble() }
}

/** Normal with mean [mu] and standard deviation [sigma]. */
fun normal(mu: Number = 0.0, sigma: Number = 1.0): Dist {
    val m = mu.toDouble(); val s = sigma.toDouble()
    return leaf("normal", pdf = { x -> val z = (x - m) / s; exp(-0.5 * z * z) / (s * sqrt(2 * Math.PI)) },
        glsl = { r -> "(${glslFloat(m)} + ${glslFloat(s)} * sqrt(-2.0 * log(1.0 - $GLSL_RND($r))) * cos(6.2831853 * $GLSL_RND($r)))" }) {
        // Box-Muller, one of the pair, so CPU and GPU use the same construction.
        m + s * sqrt(-2.0 * ln(1.0 - it.nextDouble())) * cos(2 * Math.PI * it.nextDouble())
    }.also { it.normalMs = m to s }
}

/** 1 with probability [p], else 0. */
fun bernoulli(p: Number): Dist {
    val q = p.toDouble()
    return leaf("bern", pmf = mapOf(1.0 to q, 0.0 to 1 - q), glsl = { r -> "float($GLSL_RND($r) < ${glslFloat(q)})" }) {
        if (it.nextDouble() < q) 1.0 else 0.0
    }
}

/** Exponential with the given [rate]. */
fun exponential(rate: Number = 1.0): Dist {
    val l = rate.toDouble()
    return leaf("exp", pdf = { x -> if (x < 0) 0.0 else l * exp(-l * x) },
        glsl = { r -> "(-log(1.0 - $GLSL_RND($r)) / ${glslFloat(l)})" }) { -ln(1.0 - it.nextDouble()) / l }
}

/**
 * A mixture: with probability `weights[i] / sum`, the value of `components[i]`.
 *
 * Every component is evaluated on every sample (that is what makes it lower to straight-line
 * GLSL); the selector is independent of them, so the result is the mixture.
 */
fun mixture(components: List<Dist>, weights: List<Number> = List(components.size) { 1.0 }): Dist {
    require(components.isNotEmpty() && components.size == weights.size) { "mixture needs one weight per component" }
    val w = weights.map { it.toDouble() }
    val total = w.sum()
    val cum = w.runningReduce { a, b -> a + b }.map { it / total }
    val selector = uniform(0.0, 1.0)
    return Dist(
        "mix", listOf(selector) + components,
        { ins, out, n, _ ->
            val u = ins[0]
            for (i in 0 until n) {
                var k = cum.indexOfFirst { u[i] < it }
                if (k < 0) k = cum.size - 1
                out[i] = ins[k + 1][i]
            }
        },
        { ins, _ ->
            // nested ternary from the last component back
            var e = ins.last()
            for (k in components.size - 2 downTo 0) e = "(${ins[0]} < ${glslFloat(cum[k])} ? ${ins[k + 1]} : $e)"
            e
        },
    )
}

/** A mixture of components with weights, as pairs. */
fun mixture(vararg weighted: Pair<Number, Dist>): Dist = mixture(weighted.map { it.second }, weighted.map { it.first })

/** A discrete distribution over [values] with [weights]. */
fun categorical(values: List<Number>, weights: List<Number> = List(values.size) { 1.0 }): Dist =
    mixture(values.map { Dist.dirac(it) }, weights)

/** The sum of these variables. */
fun List<Dist>.sum(): Dist = reduce { a, b -> a + b }

/** [n] independent draws from [d], as a vector: `draws(23, uniformInt(1, 365))`. */
fun draws(n: Int, d: Dist): List<Dist> = d.iid(n)

/** 1 if any two of [ds] take the same value on this sample, else 0 (the birthday question). */
fun hasDuplicates(ds: List<Dist>): Dist = Dist(
    "hasDuplicates", ds,
    { ins, out, n, _ ->
        val k = ins.size
        val seen = HashSet<Double>(k * 2)
        for (i in 0 until n) {
            seen.clear()
            var dup = 0.0
            for (j in 0 until k) if (!seen.add(ins[j][i])) { dup = 1.0; break }
            out[i] = dup
        }
    },
    { ins, _ ->
        val terms = ArrayList<String>()
        for (a in ins.indices) for (b in a + 1 until ins.size) terms += "${ins[a]} == ${ins[b]}"
        if (terms.isEmpty()) "0.0" else "float(${terms.joinToString(" || ")})"
    },
)

/** The number of distinct values among [ds] on each sample. CPU and GPU. */
fun distinctCount(ds: List<Dist>): Dist = Dist(
    "distinct", ds,
    { ins, out, n, _ ->
        val seen = HashSet<Double>()
        for (i in 0 until n) { seen.clear(); for (x in ins) seen.add(x[i]); out[i] = seen.size.toDouble() }
    },
    { ins, _ ->
        // value j counts if it equals none of the values before it
        ins.indices.joinToString(" + ", "(", ")") { j ->
            if (j == 0) "1.0" else "float(!(" + (0 until j).joinToString(" || ") { "${ins[it]} == ${ins[j]}" } + "))"
        }
    },
)

// ---------------------------------------------------------------------------------------------
// Evaluation
// ---------------------------------------------------------------------------------------------

/**
 * A graph flattened into evaluation order. Each node is evaluated once per sample, which is what
 * makes a name a fixed node: every consumer of `x` reads the same array.
 */
class Plan(val roots: List<Dist>) {
    val order: List<Dist>
    private val index = IdentityHashMap<Dist, Int>()

    init {
        val out = ArrayList<Dist>()
        val state = IdentityHashMap<Dist, Int>() // 1 visiting, 2 done
        // iterative DFS so a 10 000-node sum does not blow the stack
        val stack = ArrayDeque<Pair<Dist, Int>>()
        for (r in roots) {
            if (state[r] == 2) continue
            stack.addLast(r to 0)
            while (stack.isNotEmpty()) {
                val (d, i) = stack.removeLast()
                if (i == 0) { if (state[d] == 2) continue; state[d] = 1 }
                if (i < d.inputs.size) {
                    stack.addLast(d to i + 1)
                    val c = d.inputs[i]
                    if (state[c] != 2) stack.addLast(c to 0)
                } else if (state[d] != 2) {
                    state[d] = 2; index[d] = out.size; out += d
                }
            }
        }
        order = out
    }

    fun indexOf(d: Dist): Int = index[d] ?: error("node not in plan")

    /** [n] joint samples of every root, `result[root][sample]`. */
    fun sample(n: Int, rng: SplittableRandom, batch: Int = 8192): Array<DoubleArray> {
        val result = Array(roots.size) { DoubleArray(n) }
        val bufs = Array(order.size) { DoubleArray(min(batch, max(n, 1))) }
        val insOf = order.map { d -> Array(d.inputs.size) { bufs[indexOf(d.inputs[it])] } }
        var done = 0
        while (done < n) {
            val m = min(batch, n - done)
            for (k in order.indices) order[k].cpu(insOf[k], bufs[k], m, rng)
            for (r in roots.indices) System.arraycopy(bufs[indexOf(roots[r])], 0, result[r], done, m)
            done += m
        }
        return result
    }
}

/** [n] joint draws of several variables at once: `result[variable][sample]`. Dependencies are kept. */
fun jointSamples(vararg ds: Dist, n: Int, seed: Long = 1L): Array<DoubleArray> =
    Plan(ds.toList()).sample(n, SplittableRandom(seed))
