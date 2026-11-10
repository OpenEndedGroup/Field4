package afield.math.distribution

import java.util.IdentityHashMap
import java.util.SplittableRandom
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A Monte Carlo answer: the estimate, its standard error, and how many samples it rests on.
 *
 * Read `0.507 ± 0.002` as "the true value is within about two of those of 0.507, nineteen times in
 * twenty". [within] is that test, for checking an estimate against a value you know.
 */
data class Estimate(val value: Double, val se: Double, val n: Long) {
    /** True if [exact] lies within [k] standard errors of [value]. */
    fun within(exact: Double, k: Double = 4.0): Boolean = abs(value - exact) <= k * se + 1e-12

    /** How many standard errors [value] is from [exact]. */
    fun zFrom(exact: Double): Double = if (se == 0.0) (if (value == exact) 0.0 else Double.POSITIVE_INFINITY) else (value - exact) / se

    operator fun times(k: Number) = Estimate(value * k.toDouble(), se * abs(k.toDouble()), n)
    operator fun plus(k: Number) = Estimate(value + k.toDouble(), se, n)

    override fun toString(): String = "${fmt(value)} ± ${fmt(se)} (n=$n)"

    private fun fmt(v: Double) = if (v == 0.0) "0" else "%.4g".format(v)
}

/** Default sample count for a one-shot query. */
const val DEFAULT_SAMPLES = 200_000

/** P(c): the probability that [c] is non-zero. */
fun P(c: Dist, n: Int = DEFAULT_SAMPLES, seed: Long = 1L): Estimate {
    val s = c.samples(n, seed)
    var k = 0L
    for (v in s) if (v != 0.0) k++
    val p = k.toDouble() / n
    return Estimate(p, sqrt(p * (1 - p) / n), n.toLong())
}

/** E(x): the expectation of [x]. */
fun E(x: Dist, n: Int = DEFAULT_SAMPLES, seed: Long = 1L): Estimate = Moments().apply { addAll(x.samples(n, seed)) }.mean()

/** Var(x): the variance of [x], with the large-sample standard error `sqrt((m4 - s^4) / n)`. */
fun Var(x: Dist, n: Int = DEFAULT_SAMPLES, seed: Long = 1L): Estimate {
    val s = x.samples(n, seed)
    var mean = 0.0
    for (v in s) mean += v
    mean /= n
    var m2 = 0.0; var m4 = 0.0
    for (v in s) { val d = v - mean; val d2 = d * d; m2 += d2; m4 += d2 * d2 }
    m2 /= n; m4 /= n
    val variance = m2 * n / (n - 1).coerceAtLeast(1)
    return Estimate(variance, sqrt(max(0.0, m4 - m2 * m2) / n), n.toLong())
}

/**
 * The [q]-quantile of [x]. Its standard error comes from the order statistics: the sample
 * quantile's rank is binomial, so the values `sqrt(n q (1-q))` ranks either side bracket one SE.
 */
fun quantile(x: Dist, q: Double, n: Int = DEFAULT_SAMPLES, seed: Long = 1L): Estimate {
    require(q in 0.0..1.0) { "quantile $q is not in [0, 1]" }
    val s = x.samples(n, seed).also { it.sort() }
    return quantileOfSorted(s, q)
}

internal fun quantileOfSorted(s: DoubleArray, q: Double): Estimate {
    val n = s.size
    fun at(r: Double) = s[r.toInt().coerceIn(0, n - 1)]
    val r = q * (n - 1)
    val lo = floor(r).toInt(); val hi = ceil(r).toInt()
    val v = s[lo] + (s[hi] - s[lo]) * (r - lo)
    val d = sqrt(n * q * (1 - q))
    val se = (at(r + d) - at(r - d)) / 2.0
    return Estimate(v, se, n.toLong())
}

/** Running mean and second moment (Welford), mergeable, for progressive estimates. */
class Moments {
    var n = 0L; private set
    private var mean = 0.0
    private var m2 = 0.0

    fun add(v: Double) { n++; val d = v - mean; mean += d / n; m2 += d * (v - mean) }
    fun addAll(vs: DoubleArray, count: Int = vs.size) { for (i in 0 until count) add(vs[i]) }

    /** Merge a block summarised as `count`, `sum`, `sum of squares` (what the GPU returns). */
    fun addBlock(count: Long, sum: Double, sumSq: Double) {
        if (count == 0L) return
        val bm = sum / count
        val bm2 = max(0.0, sumSq - sum * bm)
        val tot = n + count
        val d = bm - mean
        m2 += bm2 + d * d * n * count / tot
        mean += d * count / tot
        n = tot
    }

    fun variance(): Double = if (n > 1) m2 / (n - 1) else 0.0
    fun mean(): Estimate = Estimate(mean, if (n > 1) sqrt(variance() / n) else Double.NaN, n)
}

/**
 * Progressive sampling: draw in batches and watch the estimate settle.
 *
 * Every [draw] adds a batch, updates the running mean, records a point of the [trace] (n, mean,
 * SE) and keeps up to [keep] raw samples for histograms. The [Dist] is evaluated with one RNG
 * stream for the life of this object, so successive batches are independent.
 */
class Progressive(val dist: Dist, seed: Long = 1L, val keep: Int = 200_000) {
    private val plan = Plan(listOf(dist))
    private val rng = SplittableRandom(seed)
    val moments = Moments()
    private val kept = DoubleArray(keep)
    private var kn = 0
    private val traceN = ArrayList<Long>()
    private val traceMean = ArrayList<Double>()
    private val traceSe = ArrayList<Double>()

    /** Exact pmf of [dist] when it can be computed by convolution; null otherwise. */
    val exactPmf: Map<Double, Double>? by lazy { exactPmf(dist) }

    /** Exact density of [dist] when known in closed form; null otherwise. */
    val exactPdf: ((Double) -> Double)? by lazy { exactPdf(dist) }

    /** Add [n] samples. Returns this, for chaining. */
    fun draw(n: Int): Progressive {
        val s = plan.sample(n, rng)[0]
        moments.addAll(s)
        // reservoir-ish: keep the first [keep], then replace uniformly so the kept set stays a fair sample
        for (v in s) {
            if (kn < keep) kept[kn++] = v
            else { val j = rng.nextLong(moments.n); if (j < keep) kept[j.toInt()] = v }
        }
        val e = moments.mean()
        traceN += e.n; traceMean += e.value; traceSe += e.se
        return this
    }

    /** E(dist) so far. */
    val estimate: Estimate get() = moments.mean()

    /** The samples kept so far (a uniform subsample once more than [keep] have been drawn). */
    fun kept(): DoubleArray = kept.copyOf(kn)

    /** (n, mean, SE) after every [draw]. */
    val trace: List<Triple<Long, Double, Double>> get() = traceN.indices.map { Triple(traceN[it], traceMean[it], traceSe[it]) }
}

// ---------------------------------------------------------------------------------------------
// Exact answers, where the graph allows them
// ---------------------------------------------------------------------------------------------

private fun randomLeaves(d: Dist, into: MutableSet<Dist> = java.util.Collections.newSetFromMap(IdentityHashMap())): MutableSet<Dist> {
    if (d.kind == Dist.Kind.Leaf) into += d
    for (i in d.inputs) randomLeaves(i, into)
    return into
}

private fun independent(a: Dist, b: Dist): Boolean {
    val la = randomLeaves(a)
    return randomLeaves(b).none { it in la }
}

/**
 * The exact pmf of a discrete graph, by convolution, or null.
 *
 * Works for discrete leaves, constants, affine maps, and `+ - * < <= > >= == !=` of two
 * *independent* operands (no shared random leaf); anything else, or a support beyond
 * [maxSupport], gives null. That is the honest limit: `x + x` has shared leaves and is refused
 * rather than wrongly convolved with itself.
 */
fun exactPmf(d: Dist, maxSupport: Int = 20_000): Map<Double, Double>? {
    d.pmf?.let { return it }
    if (d.op == "lin" && d.inputs.size == 1) {
        val a = exactPmf(d.inputs[0], maxSupport) ?: return null
        val l = d.linear!!
        return a.entries.groupingBy { l[0] * it.key + l[2] }.fold(0.0) { acc, e -> acc + e.value }
    }
    if (d.inputs.size != 2) return null
    val (x, y) = d.inputs
    if (!independent(x, y)) return null
    val f: (Double, Double) -> Double = when {
        d.linear != null -> { a, b -> d.linear[0] * a + d.linear[1] * b }
        d.op == "*" -> { a, b -> a * b }
        d.op == "<" -> { a, b -> if (a < b) 1.0 else 0.0 }
        d.op == "<=" -> { a, b -> if (a <= b) 1.0 else 0.0 }
        d.op == ">" -> { a, b -> if (a > b) 1.0 else 0.0 }
        d.op == ">=" -> { a, b -> if (a >= b) 1.0 else 0.0 }
        d.op == "==" -> { a, b -> if (a == b) 1.0 else 0.0 }
        d.op == "!=" -> { a, b -> if (a != b) 1.0 else 0.0 }
        d.op == "min" -> ::min
        d.op == "max" -> ::max
        else -> return null
    }
    val px = exactPmf(x, maxSupport) ?: return null
    val py = exactPmf(y, maxSupport) ?: return null
    if (px.size.toLong() * py.size > 50_000_000L) return null
    val out = HashMap<Double, Double>()
    for ((a, pa) in px) for ((b, pb) in py) out.merge(f(a, b), pa * pb, Double::plus)
    return if (out.size > maxSupport) null else out.toSortedMap()
}

/**
 * The exact density, when known in closed form: a continuous leaf, an affine image of one, or a
 * sum of independent normals. Null otherwise (then a plot falls back to a kernel estimate).
 */
fun exactPdf(d: Dist): ((Double) -> Double)? {
    d.pdf?.let { return it }
    if (d.op == "lin" && d.inputs.size == 1) {
        val inner = exactPdf(d.inputs[0]) ?: return null
        val (k, _, c) = d.linear!!.toList()
        if (k == 0.0) return null
        return { x -> inner((x - c) / k) / abs(k) }
    }
    normalParams(d)?.let { (m, s) -> if (s > 0) return normal(m, s).pdf }
    return null
}

/** (mean, sd) if [d] is an affine combination of independent normal leaves. */
private fun normalParams(d: Dist): Pair<Double, Double>? {
    if (d.kind == Dist.Kind.Leaf && d.op == "normal") {
        return d.normalMs
    }
    if (d.kind == Dist.Kind.Dirac) return d.constant to 0.0
    val l = d.linear ?: return null
    if (d.inputs.size == 1) {
        val (m, s) = normalParams(d.inputs[0]) ?: return null
        return l[0] * m + l[2] to abs(l[0]) * s
    }
    if (!independent(d.inputs[0], d.inputs[1])) return null
    val (ma, sa) = normalParams(d.inputs[0]) ?: return null
    val (mb, sb) = normalParams(d.inputs[1]) ?: return null
    return l[0] * ma + l[1] * mb to sqrt(l[0] * l[0] * sa * sa + l[1] * l[1] * sb * sb)
}
