package afield.math.distribution

import java.util.IdentityHashMap
import java.util.SplittableRandom
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
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
data class Estimate(
    val value: Double,
    val se: Double,
    val n: Long,
    /**
     * Effective sample size: [n] for plain draws; for a posterior, how many unweighted draws the
     * weighted ones are worth (the number accepted, for a condition by rejection).
     */
    val ess: Double = n.toDouble(),
) {
    /** True if [exact] lies within [k] standard errors of [value]. */
    fun within(exact: Double, k: Double = 4.0): Boolean = abs(value - exact) <= k * se + 1e-12

    /** How many standard errors [value] is from [exact]. */
    fun zFrom(exact: Double): Double = if (se == 0.0) (if (value == exact) 0.0 else Double.POSITIVE_INFINITY) else (value - exact) / se

    operator fun times(k: Number) = Estimate(value * k.toDouble(), se * abs(k.toDouble()), n, ess)
    operator fun plus(k: Number) = Estimate(value + k.toDouble(), se, n, ess)

    /** True when the draws were weighted by evidence, so [ess] is below [n]. */
    val weighted: Boolean get() = ess < n.toDouble()

    override fun toString(): String =
        if (weighted) "${fmt(value)} ± ${fmt(se)} (n=$n, ess=${essText()})" else "${fmt(value)} ± ${fmt(se)} (n=$n)"

    private fun essText() = if (ess >= 100) ess.toLong().toString() else "%.3g".format(ess)

    private fun fmt(v: Double) = if (v == 0.0) "0" else "%.4g".format(v)
}

/** Default sample count for a one-shot query. */
const val DEFAULT_SAMPLES = 200_000

/** P(c): the probability that [c] is non-zero. */
fun P(c: Dist, n: Int = DEFAULT_SAMPLES, seed: Long = 1L): Estimate {
    val ws = c.weightedSamples(n, seed)
    if (ws.logWeights != null) return weightedMean(DoubleArray(n) { if (ws.values[0][it] != 0.0) 1.0 else 0.0 }, ws.normalizedWeights())
    val s = ws.values[0]
    var k = 0L
    for (v in s) if (v != 0.0) k++
    val p = k.toDouble() / n
    return Estimate(p, sqrt(p * (1 - p) / n), n.toLong())
}

/** E(x): the expectation of [x]. */
fun E(x: Dist, n: Int = DEFAULT_SAMPLES, seed: Long = 1L): Estimate {
    val ws = x.weightedSamples(n, seed)
    if (ws.logWeights != null) return weightedMean(ws.values[0], ws.normalizedWeights())
    return Moments().apply { addAll(ws.values[0]) }.mean()
}

/** Var(x): the variance of [x], with the large-sample standard error `sqrt((m4 - s^4) / n)`. */
fun Var(x: Dist, n: Int = DEFAULT_SAMPLES, seed: Long = 1L): Estimate {
    val ws = x.weightedSamples(n, seed)
    if (ws.logWeights != null) return weightedVariance(ws.values[0], ws.normalizedWeights())
    val s = ws.values[0]
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
    val ws = x.weightedSamples(n, seed)
    if (ws.logWeights != null) return weightedQuantile(ws.values[0], ws.normalizedWeights(), q)
    val s = ws.values[0].also { it.sort() }
    return quantileOfSorted(s, q)
}

// ---- weighted (posterior) estimates -------------------------------------------------------
//
// Self-normalised importance sampling: with weights w, the estimate of E(x) is Σ w x / Σ w, and
// its standard error (delta method) is sqrt(Σ w² (x - mean)²) / Σ w. With 0-1 weights, as from a
// condition, these are exactly the mean and standard error of the accepted samples.

internal fun weightedMean(x: DoubleArray, w: DoubleArray): Estimate {
    var sw = 0.0; var sw2 = 0.0; var swx = 0.0
    for (i in x.indices) { val wi = w[i]; if (wi == 0.0) continue; sw += wi; sw2 += wi * wi; swx += wi * x[i] }
    val mean = swx / sw
    var acc = 0.0
    for (i in x.indices) { val wi = w[i]; if (wi == 0.0) continue; val d = x[i] - mean; acc += wi * wi * d * d }
    return Estimate(mean, sqrt(acc) / sw, x.size.toLong(), sw * sw / sw2)
}

internal fun weightedVariance(x: DoubleArray, w: DoubleArray): Estimate {
    var sw = 0.0; var sw2 = 0.0; var swx = 0.0
    for (i in x.indices) { val wi = w[i]; if (wi == 0.0) continue; sw += wi; sw2 += wi * wi; swx += wi * x[i] }
    val mean = swx / sw
    var m2 = 0.0
    for (i in x.indices) { val wi = w[i]; if (wi == 0.0) continue; val d = x[i] - mean; m2 += wi * d * d }
    m2 /= sw
    // the reliability-weights correction, n/(n-1) when the weights are equal
    val correction = 1.0 / max(1e-300, 1 - sw2 / (sw * sw))
    var acc = 0.0
    for (i in x.indices) { val wi = w[i]; if (wi == 0.0) continue; val d = x[i] - mean; val e = d * d - m2; acc += wi * wi * e * e }
    return Estimate(m2 * correction, sqrt(acc) / sw, x.size.toLong(), sw * sw / sw2)
}

internal fun weightedQuantile(x: DoubleArray, w: DoubleArray, q: Double): Estimate {
    val idx = x.indices.filter { w[it] > 0 }.sortedBy { x[it] }
    val total = idx.sumOf { w[it] }
    var sw2 = 0.0
    for (i in idx) sw2 += w[i] * w[i]
    val ess = total * total / sw2
    val cum = DoubleArray(idx.size)
    var c = 0.0
    for (k in idx.indices) { c += w[idx[k]] / total; cum[k] = c }
    fun at(p: Double): Double {
        val pp = p.coerceIn(0.0, 1.0)
        var lo = 0; var hi = cum.size - 1
        while (lo < hi) { val mid = (lo + hi) / 2; if (cum[mid] < pp) lo = mid + 1 else hi = mid }
        return x[idx[lo]]
    }
    val d = sqrt(q * (1 - q) / ess)
    return Estimate(at(q), (at(q + d) - at(q - d)) / 2.0, x.size.toLong(), ess)
}

/**
 * Running weighted sums for a posterior, mergeable, for progressive estimates. Weights arrive as
 * logs and are kept relative to the largest seen, so a long run of tiny likelihoods neither
 * underflows nor loses the samples that matter.
 */
class WeightedMoments {
    /** Draws seen, including rejected ones. */
    var n = 0L; private set
    private var logShift = Double.NEGATIVE_INFINITY
    private var shift = Double.NaN // value shift, against cancellation
    private var sw = 0.0; private var swd = 0.0; private var swdd = 0.0
    private var sw2 = 0.0; private var sw2d = 0.0; private var sw2dd = 0.0

    /** Add one draw [v] with log-weight [lw]. */
    fun add(v: Double, lw: Double) = addBlock(1, lw, v, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

    /**
     * Merge a block of [count] draws summarised relative to log-weight shift [m] and value shift
     * [f]: `Σw, Σw(x-f), Σw(x-f)²` and the same with `w²`, where `w = exp(lw - m)`. This is what
     * the GPU returns per invocation.
     */
    fun addBlock(count: Long, m: Double, f: Double, bw: Double, bwd: Double, bwdd: Double, bw2: Double, bw2d: Double, bw2dd: Double) {
        if (m.isNaN()) throw IllegalArgumentException("a weight in the evidence is negative or NaN; `x given w` needs w >= 0")
        if (m == Double.POSITIVE_INFINITY) throw IllegalArgumentException("a weight in the evidence is infinite")
        n += count
        if (m == Double.NEGATIVE_INFINITY || bw == 0.0) return
        if (shift.isNaN()) shift = f
        if (m > logShift) {
            val k = exp(logShift - m)
            sw *= k; swd *= k; swdd *= k
            sw2 *= k * k; sw2d *= k * k; sw2dd *= k * k
            logShift = m
        }
        val k = exp(m - logShift)
        val k2 = k * k
        val o = f - shift // re-centre the block on our shift
        sw += k * bw; swd += k * (bwd + o * bw); swdd += k * (bwdd + 2 * o * bwd + o * o * bw)
        sw2 += k2 * bw2; sw2d += k2 * (bw2d + o * bw2); sw2dd += k2 * (bw2dd + 2 * o * bw2d + o * o * bw2)
    }

    /** True once any draw has had a non-zero weight. */
    val anyAccepted: Boolean get() = sw > 0

    /** Effective sample size so far. */
    val ess: Double get() = if (sw > 0) sw * sw / sw2 else 0.0

    /** The weighted mean with its standard error; throws [EvidenceImpossibleException] if no draw has been accepted. */
    fun mean(): Estimate {
        if (!(sw > 0)) throw noSampleSatisfied(n)
        val md = swd / sw
        // Σw²(x - mean)² = Σw²d² - 2 md Σw²d + md² Σw²
        val acc = max(0.0, sw2dd - 2 * md * sw2d + md * md * sw2)
        return Estimate(shift + md, sqrt(acc) / sw, n, ess)
    }
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
class Progressive(
    val dist: Dist,
    seed: Long = 1L,
    val keep: Int = 200_000,
    /**
     * True when the draws come from outside through [addDraws], already drawn from [dist] (a
     * posterior's draws already resampled), so they count equally and [draw] is not used.
     */
    val suppliedDraws: Boolean = false,
) {
    private val plan = Plan(listOf(dist))
    private val rng = SplittableRandom(seed)
    val moments = Moments()

    /** True if [dist] carries evidence and this draws for itself: then [weightedMoments] holds the estimate and [kept] carries weights. */
    val weighted: Boolean = plan.hasEvidence && !suppliedDraws

    /** The posterior sums, when [weighted]. */
    val weightedMoments = WeightedMoments()
    private val kept = DoubleArray(keep)
    private val keptLw = if (weighted) DoubleArray(keep) else null
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
        check(!suppliedDraws) { "this Progressive takes its draws from addDraws" }
        if (weighted) return drawWeighted(n)
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

    /**
     * Add draws made elsewhere, such as the values a [DistParam] played, as if [draw] had made
     * them. Needs [suppliedDraws].
     */
    fun addDraws(values: DoubleArray): Progressive {
        check(suppliedDraws) { "construct with suppliedDraws = true to add outside draws" }
        if (values.isEmpty()) return this
        moments.addAll(values)
        for (v in values) {
            if (kn < keep) kept[kn++] = v
            else { val j = rng.nextLong(moments.n); if (j < keep) kept[j.toInt()] = v }
        }
        val e = moments.mean()
        traceN += e.n; traceMean += e.value; traceSe += e.se
        return this
    }

    private fun drawWeighted(n: Int): Progressive {
        val ws = plan.sampleWeighted(n, rng)
        val s = ws.values[0]
        val lw = ws.logWeights!!
        for (i in s.indices) {
            if (lw[i].isNaN()) throw IllegalArgumentException("a weight in the evidence is negative or NaN; `x given w` needs w >= 0")
            weightedMoments.add(s[i], lw[i])
            if (kn < keep) { kept[kn] = s[i]; keptLw!![kn] = lw[i]; kn++ }
            else { val j = rng.nextLong(weightedMoments.n); if (j < keep) { kept[j.toInt()] = s[i]; keptLw!![j.toInt()] = lw[i] } }
        }
        if (weightedMoments.anyAccepted) {
            val e = weightedMoments.mean()
            traceN += e.n; traceMean += e.value; traceSe += e.se
        }
        return this
    }

    /** E(dist) so far; for a posterior, throws [EvidenceImpossibleException] until a draw has been accepted. */
    val estimate: Estimate get() = if (weighted) weightedMoments.mean() else moments.mean()

    /** How many draws so far, accepted or not. */
    val drawn: Long get() = if (weighted) weightedMoments.n else moments.n

    /** The samples kept so far (a uniform subsample once more than [keep] have been drawn). */
    fun kept(): DoubleArray = kept.copyOf(kn)

    /** The log-weights of [kept], one each; null unless [weighted]. */
    fun keptLogWeights(): DoubleArray? = keptLw?.copyOf(kn)

    /** (n, mean, SE) after every [draw]. */
    val trace: List<Triple<Long, Double, Double>> get() = traceN.indices.map { Triple(traceN[it], traceMean[it], traceSe[it]) }
}

// ---------------------------------------------------------------------------------------------
// Exact answers, where the graph allows them
// ---------------------------------------------------------------------------------------------

private fun randomLeaves(d: Dist, into: MutableSet<Dist> = java.util.Collections.newSetFromMap(IdentityHashMap())): MutableSet<Dist> {
    if (d.kind == Dist.Kind.Leaf || d.kind == Dist.Kind.Param) into += d
    for (i in d.inputs) randomLeaves(i, into)
    return into
}

private fun independent(a: Dist, b: Dist): Boolean {
    val la = randomLeaves(a)
    return randomLeaves(b).none { it in la }
}

/**
 * The exact pmf of a discrete graph, or null.
 *
 * Two methods, the fast one first. Convolution handles discrete leaves, constants, affine maps,
 * and `+ - * < <= > >= == != min max` of two *independent* operands. Enumeration handles anything
 * else built only from discrete leaves, including shared leaves (`x + x`), [Dist.map], and
 * evidence ([Dist.given]): it visits every joint value of the leaves, up to
 * [maxAssignments] of them, and for a posterior renormalises by the probability of the evidence.
 *
 * Null means "not known exactly": a continuous leaf, a family with random parameters, too many
 * joint values, or a support beyond [maxSupport]. Evidence with probability exactly 0 throws
 * [EvidenceImpossibleException].
 */
fun exactPmf(d: Dist, maxSupport: Int = 20_000, maxAssignments: Long = 1_000_000): Map<Double, Double>? =
    convolvedPmf(d, maxSupport) ?: enumeratedPmf(d, maxSupport, maxAssignments)

private fun convolvedPmf(d: Dist, maxSupport: Int): Map<Double, Double>? {
    d.pmf?.let { return it }
    if (d.op == "lin" && d.inputs.size == 1) {
        val a = convolvedPmf(d.inputs[0], maxSupport) ?: return null
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
    val px = convolvedPmf(x, maxSupport) ?: return null
    val py = convolvedPmf(y, maxSupport) ?: return null
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

/**
 * The pmf by visiting every joint value of the graph's discrete leaves, each weighted by its
 * probability and by the evidence. Null when a random node is not a discrete leaf, or there are
 * more than [maxAssignments] joint values.
 */
private fun enumeratedPmf(d: Dist, maxSupport: Int, maxAssignments: Long): Map<Double, Double>? {
    val plan = Plan(listOf(d))
    val leaves = ArrayList<Int>()
    for (k in plan.order.indices) {
        val node = plan.order[k]
        when (node.kind) {
            Dist.Kind.Param -> return null
            Dist.Kind.Leaf -> { if (node.pmf == null) return null; leaves += k }
            else -> {}
        }
    }
    val supports = leaves.map { k -> plan.order[k].pmf!!.entries.filter { it.value > 0 }.map { it.key to it.value } }
    var total = 1L
    for (s in supports) { total *= s.size; if (total > maxAssignments) return null }
    val n = total.toInt()
    // mixed-radix strides: assignment a has digit (a / stride[j]) % size[j] for leaf j
    val stride = LongArray(leaves.size)
    var acc = 1L
    for (j in leaves.indices) { stride[j] = acc; acc *= supports[j].size }
    val slot = HashMap<Int, Int>().apply { leaves.forEachIndexed { j, k -> put(k, j) } }
    val logW = DoubleArray(n)
    val values = plan.run(n, SplittableRandom(0), 8192, logW) { k, buf, offset, m ->
        val j = slot[k] ?: return@run false
        val sup = supports[j]
        for (i in 0 until m) buf[i] = sup[(((offset + i) / stride[j]) % sup.size).toInt()].first
        true
    }[0]
    // log-probability of each assignment, plus its evidence
    for (a in 0 until n) {
        var lp = logW[a]
        for (j in leaves.indices) lp += kotlin.math.ln(supports[j][((a / stride[j]) % supports[j].size).toInt()].second)
        logW[a] = lp
    }
    var m = Double.NEGATIVE_INFINITY
    for (v in logW) { if (v.isNaN()) throw IllegalArgumentException("a weight in the evidence is negative or NaN; `x given w` needs w >= 0"); if (v > m) m = v }
    if (m == Double.NEGATIVE_INFINITY) throw EvidenceImpossibleException("the evidence has probability 0: no joint value of the leaves is consistent with it")
    val out = HashMap<Double, Double>()
    var z = 0.0
    for (a in 0 until n) {
        val w = kotlin.math.exp(logW[a] - m)
        if (w == 0.0) continue
        z += w
        out.merge(values[a] + 0.0, w, Double::plus) // + 0.0 folds -0.0 into 0.0
        if (out.size > maxSupport) return null
    }
    return out.mapValues { it.value / z }.toSortedMap()
}
