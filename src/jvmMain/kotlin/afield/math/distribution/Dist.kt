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
    /** The parametric family this node draws from: a [Kind.Leaf] with [familyParams], or a [Kind.Param] node. */
    internal val family: Family? = null,
    /** For a leaf with a [family]: its constant parameters. */
    internal val familyParams: DoubleArray? = null,
    /** GLSL helper functions [glsl] calls, emitted once per shader. */
    internal val glslHelpers: List<String> = emptyList(),
    /** GLSL declared at global scope for this node, given the node's local name (for data arrays). */
    internal val glslPrelude: ((id: String) -> String)? = null,
) {
    /**
     * `Dirac` is a constant, `Leaf` draws from constant parameters, `Param` draws from a [family]
     * whose parameters are its inputs, `Given` passes its first input through and weighs the sample
     * by its second (a log-likelihood), and `Op` is a deterministic function of its inputs.
     */
    internal enum class Kind { Dirac, Leaf, Param, Given, Op }

    /** For a normal leaf: (mean, sd), so sums of independent normals have an exact density. */
    internal var normalMs: Pair<Double, Double>? = null

    /** True if no sample of this variable depends on randomness. */
    val isConstant: Boolean get() = kind == Kind.Dirac || ((kind == Kind.Op || kind == Kind.Given) && inputs.all { it.isConstant })

    /** True if this graph carries evidence ([given]) anywhere, so its samples are weighted. */
    val hasEvidence: Boolean get() = Plan(listOf(this)).hasEvidence

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

    // ---- conditioning -----------------------------------------------------------------------

    /**
     * This variable, conditioned on [evidence]: the posterior.
     *
     * Every sample is weighted by how likely the evidence is on that sample, and every query
     * ([P], [E], [Var], [quantile], [Progressive]) is then a weighted average. [observed] builds
     * evidence from data; `and` joins two pieces of it.
     *
     * Evidence conditions the whole sample, not only this node: in `(mu given e) + y`, `mu` is
     * conditioned and `y` is unaffected only because it shares nothing with `e`.
     *
     * ```kotlin
     * val mu = normal(0, 10)
     * val posterior = mu given observed(normal(mu, 1), 2.9, 3.3, 3.1)
     * E(posterior)     // about 3.1, the data's mean pulled very slightly towards 0
     * ```
     */
    infix fun given(evidence: Evidence): Dist = Dist(
        "given", listOf(this, evidence.logLikelihood),
        { ins, out, n, _ -> System.arraycopy(ins[0], 0, out, 0, n) },
        { ins, _ -> ins[0] },
        kind = Kind.Given,
    )

    /**
     * This variable, conditioned on [condition] being true.
     *
     * A comparison such as `x + y gt 8` is 1 or 0, and a sample where it is 0 is discarded: this is
     * rejection sampling, exact in the limit. Any non-negative variable works as a weight, so
     * `x given w` weighs each sample by `w`. A negative weight is an error when queried.
     *
     * ```kotlin
     * val x = die(6); val y = die(6)
     * P((x eq 6) given (x + y ge 10))    // exactly 1/2: of the six ways to make 10 or more, three have a 6 first
     * ```
     *
     * When nothing satisfies the condition, a query throws [EvidenceImpossibleException] rather than
     * answer NaN.
     */
    infix fun given(condition: Dist): Dist = given(Evidence(condition.asLogWeightNode()))

    /**
     * The same graph with every [given] removed: what this variable is before the evidence.
     * Leaves are shared with this graph, not copied, so `x.prior()` and `x` are the same variable.
     */
    fun prior(): Dist = stripEvidence(this, IdentityHashMap())

    internal fun asLogWeightNode(): Dist = Dist(
        "weight", listOf(this),
        { ins, out, n, _ -> val a = ins[0]; for (i in 0 until n) { val w = a[i]; out[i] = if (w > 0) ln(w) else if (w == 0.0) Double.NEGATIVE_INFINITY else Double.NaN } },
        { ins, _ -> "(${ins[0]} > 0.0 ? log(${ins[0]}) : $GLSL_NEG_INF)" },
    )

    // ---- sampling ---------------------------------------------------------------------------

    /**
     * [n] draws, in one vectorised pass over the graph.
     *
     * For a graph with evidence ([given]) the draws are weighted; these are then resampled by weight,
     * so they are draws from the posterior, with repeats. [weightedSamples] gives the raw draws and
     * their weights.
     */
    fun samples(n: Int, seed: Long = 1L): DoubleArray {
        val plan = Plan(listOf(this))
        val rng = SplittableRandom(seed)
        if (!plan.hasEvidence) return plan.sample(n, rng)[0]
        val w = plan.sampleWeighted(n, rng)
        return w.resampled(rng)[0]
    }

    /** [n] draws and their log-weights (null when the graph has no evidence). */
    fun weightedSamples(n: Int, seed: Long = 1L): WeightedSamples = Plan(listOf(this)).sampleWeighted(n, SplittableRandom(seed))

    /** A one-line structural description, e.g. `(die6 + die6)`. Two describes match iff the graphs match. */
    fun describe(): String = describeInto(this, IdentityHashMap(), StringBuilder()).toString()

    override fun toString(): String = "Dist(${describe()})"

    /** This node with other inputs: same operation, same exact-answer metadata. */
    internal fun rebuild(newInputs: List<Dist>): Dist {
        val c = Dist(op, newInputs, cpu, glsl, pmf, pdf, kind, constant, linear, family, familyParams, glslHelpers, glslPrelude)
        c.normalMs = normalMs
        return c
    }

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
            val c = d.rebuild(d.inputs.map { copyGraph(it, memo) })
            memo[d] = c
            return c
        }

        /** Every node above a [Kind.Given] rebuilt without it; nodes with no evidence below are shared. */
        internal fun stripEvidence(d: Dist, memo: IdentityHashMap<Dist, Dist>): Dist {
            memo[d]?.let { return it }
            val c = when {
                d.kind == Kind.Given -> stripEvidence(d.inputs[0], memo)
                d.inputs.isEmpty() -> d
                else -> {
                    val ins = d.inputs.map { stripEvidence(it, memo) }
                    if (ins.indices.all { ins[it] === d.inputs[it] }) d else d.rebuild(ins)
                }
            }
            memo[d] = c
            return c
        }

        private fun describeInto(d: Dist, ids: IdentityHashMap<Dist, Int>, sb: StringBuilder): StringBuilder {
            when {
                d.kind == Kind.Dirac -> sb.append(fmtShort(d.constant))
                d.kind == Kind.Leaf -> { val id = ids.getOrPut(d) { ids.size }; sb.append(d.op).append('#').append(id) }
                d.kind == Kind.Param -> {
                    val id = ids.getOrPut(d) { ids.size }
                    sb.append(d.op).append('#').append(id).append('(')
                    d.inputs.forEachIndexed { i, x -> if (i > 0) sb.append(", "); describeInto(x, ids, sb) }
                    sb.append(')')
                }
                d.kind == Kind.Given -> { sb.append('('); describeInto(d.inputs[0], ids, sb); sb.append(" | "); describeInto(d.inputs[1], ids, sb); sb.append(')') }
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

/** In a node's GLSL, replaced by that node's local name, so its expression can call its own [Dist.glslPrelude]. */
internal const val GLSL_ID = "@ID@"

/** The PCG-hash uniform the GLSL lowering declares; every leaf draws through it. */
internal const val GLSL_RND = "nlRnd"

internal fun leaf(
    name: String,
    pmf: Map<Double, Double>? = null,
    pdf: ((Double) -> Double)? = null,
    family: Family? = null,
    params: DoubleArray? = null,
    glsl: (rng: String) -> String,
    cpu: (rng: SplittableRandom) -> Double,
) = Dist(
    name, emptyList(), { _, out, n, rng -> for (i in 0 until n) out[i] = cpu(rng) },
    { _, r -> glsl(r) }, pmf = pmf, pdf = pdf, kind = Dist.Kind.Leaf,
    family = family, familyParams = params, glslHelpers = family?.glslHelpers.orEmpty(),
)

/** A leaf drawn through its family's own sampler, for families added with conditioning. */
private fun familyLeaf(name: String, f: Family, p: DoubleArray, pmf: Map<Double, Double>? = null, pdf: ((Double) -> Double)? = null): Dist {
    val lits = p.map { glslFloat(it) }
    return leaf(name, pmf = pmf, pdf = pdf, family = f, params = p, glsl = { r -> f.glslSample(lits, r) }) { f.sample(p, it) }
}

/**
 * A draw from [f] whose parameters are themselves random: one draw per sample, from the
 * parameters that sample has. If every parameter is a constant this is the ordinary leaf, [asLeaf].
 */
private fun familyNode(f: Family, params: List<Dist>, asLeaf: (DoubleArray) -> Dist): Dist {
    if (params.all { it.kind == Dist.Kind.Dirac }) return asLeaf(DoubleArray(params.size) { params[it].constant })
    return Dist(
        f.name, params,
        { ins, out, n, rng ->
            val p = DoubleArray(ins.size)
            for (i in 0 until n) {
                for (j in p.indices) p[j] = ins[j][i]
                out[i] = f.sample(p, rng)
            }
        },
        { ins, r -> f.glslSample(ins, r) },
        kind = Dist.Kind.Param, family = f, glslHelpers = f.glslHelpers,
    )
}

/** Uniform integers `lo..hi`, inclusive. */
fun uniformInt(lo: Int, hi: Int): Dist {
    require(hi >= lo) { "uniformInt($lo, $hi): empty range" }
    val k = hi - lo + 1
    val pmf = (lo..hi).associate { it.toDouble() to 1.0 / k }
    val fam = Family(
        "uniformInt", listOf("lo", "hi"),
        { x, _ -> if (x >= lo && x <= hi && x == floor(x)) -ln(k.toDouble()) else Double.NEGATIVE_INFINITY },
        { x, _ -> "($x >= ${glslFloat(lo.toDouble())} && $x <= ${glslFloat(hi.toDouble())} && $x == floor($x) ? ${glslFloat(-ln(k.toDouble()))} : $GLSL_NEG_INF)" },
        { _, rng -> (lo + rng.nextInt(k)).toDouble() },
        { _, r -> "(floor($GLSL_RND($r) * ${glslFloat(k.toDouble())}) + ${glslFloat(lo.toDouble())})" },
    )
    return leaf("unifInt$lo..$hi", pmf = pmf, family = fam, params = doubleArrayOf(lo.toDouble(), hi.toDouble()), glsl = { r -> "(floor($GLSL_RND($r) * ${glslFloat(k.toDouble())}) + ${glslFloat(lo.toDouble())})" }) {
        (lo + it.nextInt(k)).toDouble()
    }
}

/** A fair [sides]-sided die: uniform on `1..sides`. */
fun die(sides: Int = 6): Dist = uniformInt(1, sides).let {
    Dist("die$sides", it.inputs, it.cpu, it.glsl, it.pmf, it.pdf, it.kind, family = it.family, familyParams = it.familyParams)
}

/** Uniform on `[a, b)`. */
fun uniform(a: Number = 0.0, b: Number = 1.0): Dist {
    val lo = a.toDouble(); val hi = b.toDouble()
    return leaf("unif", pdf = { x -> if (x in lo..hi) 1.0 / (hi - lo) else 0.0 }, family = Families.uniform, params = doubleArrayOf(lo, hi),
        glsl = { r -> "(${glslFloat(lo)} + ${glslFloat(hi - lo)} * $GLSL_RND($r))" }) { lo + (hi - lo) * it.nextDouble() }
}

/** Normal with mean [mu] and standard deviation [sigma]. */
fun normal(mu: Number = 0.0, sigma: Number = 1.0): Dist {
    val m = mu.toDouble(); val s = sigma.toDouble()
    return leaf("normal", pdf = { x -> val z = (x - m) / s; exp(-0.5 * z * z) / (s * sqrt(2 * Math.PI)) }, family = Families.normal, params = doubleArrayOf(m, s),
        glsl = { r -> "(${glslFloat(m)} + ${glslFloat(s)} * sqrt(-2.0 * log(1.0 - $GLSL_RND($r))) * cos(6.2831853 * $GLSL_RND($r)))" }) {
        // Box-Muller, one of the pair, so CPU and GPU use the same construction.
        m + s * sqrt(-2.0 * ln(1.0 - it.nextDouble())) * cos(2 * Math.PI * it.nextDouble())
    }.also { it.normalMs = m to s }
}

/** 1 with probability [p], else 0. */
fun bernoulli(p: Number): Dist {
    val q = p.toDouble()
    return leaf("bern", pmf = mapOf(1.0 to q, 0.0 to 1 - q), family = Families.bernoulli, params = doubleArrayOf(q), glsl = { r -> "float($GLSL_RND($r) < ${glslFloat(q)})" }) {
        if (it.nextDouble() < q) 1.0 else 0.0
    }
}

/** Exponential with the given [rate]. */
fun exponential(rate: Number = 1.0): Dist {
    val l = rate.toDouble()
    return leaf("exp", pdf = { x -> if (x < 0) 0.0 else l * exp(-l * x) }, family = Families.exponential, params = doubleArrayOf(l),
        glsl = { r -> "(-log(1.0 - $GLSL_RND($r)) / ${glslFloat(l)})" }) { -ln(1.0 - it.nextDouble()) / l }
}

/** Poisson with the given [rate]: counts of events that happen independently at that rate. */
fun poisson(rate: Number): Dist {
    val l = rate.toDouble()
    require(l >= 0) { "poisson($l): the rate must be non-negative" }
    val p = doubleArrayOf(l)
    return familyLeaf("poisson", Families.poisson, p, pmf = truncatedPmf(Families.poisson, p, 0.0))
}

/** Gamma with [shape] `k` and [rate] `r` (mean `k / r`); with shape 1 it is [exponential]. */
fun gamma(shape: Number, rate: Number = 1.0): Dist {
    val p = doubleArrayOf(shape.toDouble(), rate.toDouble())
    require(p[0] > 0 && p[1] > 0) { "gamma(${p[0]}, ${p[1]}): shape and rate must be positive" }
    return familyLeaf("gamma", Families.gamma, p, pdf = { x -> exp(Families.gamma.logDensity(x, p)) })
}

/** Beta on `(0, 1)`, with mean `a / (a + b)`: the usual prior for an unknown probability. */
fun beta(a: Number, b: Number): Dist {
    val p = doubleArrayOf(a.toDouble(), b.toDouble())
    require(p[0] > 0 && p[1] > 0) { "beta(${p[0]}, ${p[1]}): both parameters must be positive" }
    return familyLeaf("beta", Families.beta, p, pdf = { x -> exp(Families.beta.logDensity(x, p)) })
}

/** The pmf of a discrete family on `start, start + 1, …`, cut where the remaining mass is below 1e-15; null if that needs over 5000 values. */
private fun truncatedPmf(f: Family, p: DoubleArray, start: Double): Map<Double, Double>? {
    val out = LinkedHashMap<Double, Double>()
    var total = 0.0
    var x = start
    while (total < 1 - 1e-15) {
        val m = exp(f.logDensity(x, p))
        if (m > 0) out[x] = m
        total += m
        x += 1
        if (out.size > 5000) return null
        if (m == 0.0 && x > start + 5000) return null // the mass is too far out to list
    }
    return out
}

// ---- families whose parameters are random -------------------------------------------------
//
// These are what make a model: `normal(mu, 1)` with `mu` itself a distribution draws a new mean
// for every sample, and `observed(normal(mu, 1), data)` weighs each sample by how well its `mu`
// explains the data. With constant parameters each is the ordinary leaf.

/** Normal with a random mean. */
fun normal(mu: Dist, sigma: Number = 1.0): Dist = familyNode(Families.normal, listOf(mu, Dist.dirac(sigma))) { normal(it[0], it[1]) }

/** Normal with a random standard deviation. */
fun normal(mu: Number, sigma: Dist): Dist = familyNode(Families.normal, listOf(Dist.dirac(mu), sigma)) { normal(it[0], it[1]) }

/** Normal with a random mean and standard deviation. */
fun normal(mu: Dist, sigma: Dist): Dist = familyNode(Families.normal, listOf(mu, sigma)) { normal(it[0], it[1]) }

/** Uniform on `[a, b)` with random ends. */
fun uniform(a: Dist, b: Dist): Dist = familyNode(Families.uniform, listOf(a, b)) { uniform(it[0], it[1]) }

/** Uniform on `[a, b)` with a random lower end. */
fun uniform(a: Dist, b: Number): Dist = uniform(a, Dist.dirac(b))

/** Uniform on `[a, b)` with a random upper end. */
fun uniform(a: Number, b: Dist): Dist = uniform(Dist.dirac(a), b)

/** 1 with a random probability [p], else 0. */
fun bernoulli(p: Dist): Dist = familyNode(Families.bernoulli, listOf(p)) { bernoulli(it[0]) }

/** Exponential with a random [rate]. */
fun exponential(rate: Dist): Dist = familyNode(Families.exponential, listOf(rate)) { exponential(it[0]) }

/** Poisson with a random [rate]. */
fun poisson(rate: Dist): Dist = familyNode(Families.poisson, listOf(rate)) { poisson(it[0]) }

/** Gamma with a random [rate]. */
fun gamma(shape: Number, rate: Dist): Dist = familyNode(Families.gamma, listOf(Dist.dirac(shape), rate)) { gamma(it[0], it[1]) }

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
    // The selector is a discrete leaf, the index of the component, so a mixture of discrete parts
    // has an exact pmf by enumeration. It draws one uniform per sample, as it always has.
    val selector = Dist(
        "select", emptyList(),
        { _, out, n, rng ->
            for (i in 0 until n) {
                val u = rng.nextDouble()
                var k = cum.indexOfFirst { u < it }
                if (k < 0) k = cum.size - 1
                out[i] = k.toDouble()
            }
        },
        { _, r -> "nlSelect$GLSL_ID($GLSL_RND($r))" },
        pmf = w.indices.associate { it.toDouble() to w[it] / total }, kind = Dist.Kind.Leaf,
        glslPrelude = { id ->
            var e = glslFloat((components.size - 1).toDouble())
            for (k in components.size - 2 downTo 0) e = "(u < ${glslFloat(cum[k])} ? ${glslFloat(k.toDouble())} : $e)"
            "float nlSelect$id(float u) { return $e; }\n"
        },
    )
    return Dist(
        "mix", listOf(selector) + components,
        { ins, out, n, _ ->
            val sel = ins[0]
            for (i in 0 until n) out[i] = ins[sel[i].toInt() + 1][i]
        },
        { ins, _ ->
            var e = ins.last()
            for (k in components.size - 2 downTo 0) e = "(${ins[0]} == ${glslFloat(k.toDouble())} ? ${ins[k + 1]} : $e)"
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

    /** Positions in [order] of the [Dist.given] nodes, whose second input is a log-weight. */
    private val givens: IntArray = order.indices.filter { order[it].kind == Dist.Kind.Given }.toIntArray()

    /**
     * The distinct evidence nodes, by identity: evidence applied to several variables
     * (`steps.given(e)`) is one observation, counted once, however many nodes it conditions.
     */
    internal val evidenceNodes: List<Dist> = run {
        val seen = java.util.Collections.newSetFromMap(IdentityHashMap<Dist, Boolean>())
        givens.map { order[it].inputs[1] }.filter { seen.add(it) }
    }

    /** True if any node weighs its samples, so the plan's draws are weighted. */
    val hasEvidence: Boolean get() = givens.isNotEmpty()

    /** [n] joint samples of every root, `result[root][sample]`, ignoring any weights. */
    fun sample(n: Int, rng: SplittableRandom, batch: Int = 8192): Array<DoubleArray> = run(n, rng, batch, null, null)

    /** [n] joint samples and, when the plan has evidence, each sample's log-weight. */
    fun sampleWeighted(n: Int, rng: SplittableRandom, batch: Int = 8192): WeightedSamples {
        val lw = if (hasEvidence) DoubleArray(n) else null
        return WeightedSamples(run(n, rng, batch, lw, null), lw)
    }

    /**
     * The evaluation loop. [logWeights], when given, receives the sum of every evidence node's
     * log-weight per sample. [fillLeaf], when given, supplies leaf values instead of drawing them
     * (`fillLeaf(position in order, buffer, first sample index, count)`): exact enumeration.
     */
    internal fun run(
        n: Int, rng: SplittableRandom, batch: Int, logWeights: DoubleArray?,
        fillLeaf: ((k: Int, buf: DoubleArray, offset: Int, m: Int) -> Unit)?,
    ): Array<DoubleArray> {
        val result = Array(roots.size) { DoubleArray(n) }
        val bufs = Array(order.size) { DoubleArray(min(batch, max(n, 1))) }
        val insOf = order.map { d -> Array(d.inputs.size) { bufs[indexOf(d.inputs[it])] } }
        val weightBufs = evidenceNodes.map { bufs[indexOf(it)] }
        var done = 0
        while (done < n) {
            val m = min(batch, n - done)
            for (k in order.indices) {
                if (fillLeaf != null && order[k].kind == Dist.Kind.Leaf) fillLeaf(k, bufs[k], done, m)
                else order[k].cpu(insOf[k], bufs[k], m, rng)
            }
            for (r in roots.indices) System.arraycopy(bufs[indexOf(roots[r])], 0, result[r], done, m)
            if (logWeights != null) for (w in weightBufs) for (i in 0 until m) logWeights[done + i] += w[i]
            done += m
        }
        return result
    }
}

/**
 * [n] joint draws of several variables at once: `result[variable][sample]`. Dependencies are kept.
 *
 * With evidence ([Dist.given]) the joint draws are resampled by weight, so each column is one draw
 * from the joint posterior.
 */
fun jointSamples(vararg ds: Dist, n: Int, seed: Long = 1L): Array<DoubleArray> {
    val plan = Plan(ds.toList())
    val rng = SplittableRandom(seed)
    if (!plan.hasEvidence) return plan.sample(n, rng)
    return plan.sampleWeighted(n, rng).resampled(rng)
}
