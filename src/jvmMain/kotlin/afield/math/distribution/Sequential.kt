package afield.math.distribution

import java.util.IdentityHashMap
import java.util.SplittableRandom
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

// ---------------------------------------------------------------------------------------------
// Sequential inference: a posterior updated as observations arrive (sequential Monte Carlo)
// ---------------------------------------------------------------------------------------------

/**
 * A posterior that learns as data arrives: a population of particles, each a joint value of the
 * [state] variables, weighted by how well it has explained what was observed so far.
 *
 * Name the unknowns as distributions (their prior), then alternate [evolve] (how the state moves
 * between observations) and [observe] (what was seen). Questions ([E], [Var], [quantile], [P]) are
 * weighted averages over the particles, and [dist] / [live] turn the posterior back into a [Dist]
 * that a pattern, a sound parameter or a shader can draw from.
 *
 * ```kotlin
 * // a performer's beat: its period, and when the last beat fell
 * val period = uniform(0.3, 1.0)                 // seconds per beat: 60 to 200 bpm
 * val beat = known(onsets[0])                   // the first onset was a beat
 * val post = Posterior(period, beat, particles = 2000)
 * for (t in onsets.drop(1)) {
 *     post.evolve(beat to beat + period, period to period * normal(0, 0.03).exp())
 *     post.observe(normal(beat, 0.015), t)       // the onset is the beat, give or take 15 ms
 * }
 * post.E(60 / period)                            // the tempo now, with its standard error
 * ```
 *
 * When the effective sample size falls below [resampleBelow] of the particles, the population is
 * resampled by weight (systematically), so particles that explain the data multiply and the rest
 * die. A state that never moves, such as a fixed parameter, collapses onto a few values after
 * resampling; [rejuvenate] spreads it again.
 *
 * Evaluating any graph of the state is per particle: the state variables take that particle's
 * values, and every other leaf (noise, in `evolve`) is drawn fresh. State variables are separate
 * slots; one should not be computed from another.
 */
class Posterior(
    vararg state: Dist,
    /** How many particles carry the posterior. */
    val particles: Int = 2000,
    seed: Long = 1L,
) {
    /** The variables the particles hold, in the order given. */
    val state: List<Dist> = state.toList()

    init {
        require(this.state.isNotEmpty()) { "a posterior needs at least one state variable" }
        require(particles >= 2) { "at least two particles" }
        require(this.state.distinct().size == this.state.size) { "each state variable once" }
        for (v in this.state) require(!v.isConstant) {
            "the state variable ${v.describe()} is a constant, so a model built on it, such as normal(it, 1), is fixed when it is written " +
                "and cannot learn: use known(value) for a state that starts at a known value"
        }
    }

    /**
     * After every resample, [rejuvenate] with this shrink (null: never). Use it for a state that
     * does not move between observations, such as a fixed parameter, so resampling does not
     * collapse it onto a few repeated values.
     */
    var rejuvenateAfterResample: Double? = null

    private val rng = SplittableRandom(seed)
    private val slot = IdentityHashMap<Dist, Int>().apply { this@Posterior.state.forEachIndexed { i, d -> put(d, i) } }

    /** `values[variable][particle]`. Replaced wholesale on every change, so a reader's copy stays whole. */
    @Volatile private var values: Array<DoubleArray> = jointSamples(*state, n = particles, seed = rng.nextLong())
    @Volatile private var logW: DoubleArray = DoubleArray(particles)

    /** Fraction of [particles] the effective sample size may fall to before [observe] resamples. */
    var resampleBelow: Double = 0.5

    /** How many observations have been made. */
    var observations: Int = 0
        private set

    /** How many times the population has been resampled. */
    var resamples: Int = 0
        private set

    /**
     * The log of the evidence so far, `log p(all observations)`: the sum, over observations, of
     * the log of the weighted mean likelihood. Two models of the same data compare by it, the
     * larger the better (a difference of 3 is odds of about 20 to 1).
     */
    var logEvidence: Double = 0.0
        private set

    /** Per particle, the value of [d] with the state fixed to that particle's values and every other leaf drawn fresh. */
    fun valuesOf(d: Dist): DoubleArray = evaluate(listOf(d), values)[0]

    private fun evaluate(ds: List<Dist>, vals: Array<DoubleArray>): Array<DoubleArray> {
        val plan = Plan(ds)
        val fixedAt = HashMap<Int, DoubleArray>()
        for ((k, node) in plan.order.withIndex()) slot[node]?.let { fixedAt[k] = vals[it] }
        return plan.run(particles, rng, 8192, null) { k, buf, offset, m ->
            val src = fixedAt[k] ?: return@run false
            System.arraycopy(src, offset, buf, 0, m)
            true
        }
    }

    private fun normalized(): DoubleArray = normalizeLogWeights(logW)

    /** The effective sample size: how many equally weighted particles the population is worth. */
    val ess: Double get() {
        val w = normalized()
        var s = 0.0; var s2 = 0.0
        for (v in w) { s += v; s2 += v * v }
        return s * s / s2
    }

    /**
     * Move every particle: each `variable to expression` sets that variable to the expression,
     * evaluated with the particle's current values (all updates see the old values, together).
     * Noise in an expression is drawn fresh per particle, which is what spreads the population.
     */
    fun evolve(vararg updates: Pair<Dist, Dist>): Posterior {
        for ((v, _) in updates) require(v in slot) { "${v.describe()} is not one of this posterior's state variables" }
        val next = evaluate(updates.map { it.second }, values)
        val fresh = Array(state.size) { values[it] }
        updates.forEachIndexed { i, (v, _) -> fresh[slot[v]!!] = next[i] }
        values = fresh
        return this
    }

    /** Observe that [data] were drawn from [model], whose parameters may depend on the state. */
    fun observe(model: Dist, vararg data: Number): Posterior = observe(observed(model, DoubleArray(data.size) { data[it].toDouble() }))

    /** Observe [evidence]: weigh every particle by its likelihood, then resample if the weights have collapsed. */
    fun observe(evidence: Evidence): Posterior {
        val ll = valuesOf(evidence.logLikelihood)
        val before = logW
        // the evidence increment: log Σ w_old·L / Σ w_old, computed in log space
        var mOld = Double.NEGATIVE_INFINITY
        for (v in before) if (v > mOld) mOld = v
        var num = 0.0; var den = 0.0
        val after = DoubleArray(particles)
        for (i in 0 until particles) {
            val l = ll[i]
            if (l.isNaN()) throw IllegalArgumentException("a weight in the evidence is negative or NaN")
            after[i] = before[i] + l
        }
        var mNew = Double.NEGATIVE_INFINITY
        for (v in after) if (v > mNew) mNew = v
        if (mNew == Double.NEGATIVE_INFINITY) throw EvidenceImpossibleException(
            "observation ${observations + 1}: none of the $particles particles is consistent with it, so the posterior is empty. " +
                "The model gives it no chance, or more particles (or wider transition noise) are needed."
        )
        for (i in 0 until particles) { den += exp(before[i] - mOld); num += exp(after[i] - mNew) }
        logEvidence += (ln(num) + mNew) - (ln(den) + mOld)
        logW = after
        observations++
        if (ess < resampleBelow * particles) {
            resample()
            rejuvenateAfterResample?.let { rejuvenate(it) }
        }
        return this
    }

    /** Observe that [condition] holds (or weigh by it, if it is a non-negative variable). */
    fun observe(condition: Dist): Posterior = observe(Evidence(condition.asLogWeightNode()))

    /** Resample the population by weight now (systematic resampling); afterwards every weight is equal. */
    fun resample(): Posterior {
        val w = normalized()
        var total = 0.0
        for (v in w) total += v
        val step = total / particles
        var u = rng.nextDouble() * step
        var cum = w[0]
        var j = 0
        val idx = IntArray(particles)
        for (i in 0 until particles) {
            while (cum < u && j < particles - 1) { j++; cum += w[j] }
            idx[i] = j
            u += step
        }
        values = Array(state.size) { v -> DoubleArray(particles) { values[v][idx[it]] } }
        logW = DoubleArray(particles)
        resamples++
        return this
    }

    /**
     * Spread a population that resampling has collapsed (Liu and West's kernel): each value moves
     * a fraction `1 - shrink` towards the weighted mean and gains matching Gaussian noise, so the
     * mean and variance of each variable are kept while repeated values separate again.
     */
    fun rejuvenate(shrink: Double = 0.95, vararg only: Dist): Posterior {
        require(shrink in 0.0..1.0) { "shrink must be in [0, 1]" }
        val w = normalized()
        var total = 0.0
        for (v in w) total += v
        val which = if (only.isEmpty()) state.indices.toList() else only.map { slot[it] ?: error("${it.describe()} is not a state variable") }
        val fresh = values.copyOf()
        for (v in which) {
            val x = values[v]
            var m = 0.0
            for (i in 0 until particles) m += w[i] * x[i]
            m /= total
            var s2 = 0.0
            for (i in 0 until particles) s2 += w[i] * (x[i] - m) * (x[i] - m)
            s2 /= total
            val h = sqrt((1 - shrink * shrink) * s2)
            fresh[v] = DoubleArray(particles) { shrink * x[it] + (1 - shrink) * m + h * stdNormal(rng) }
        }
        values = fresh
        return this
    }

    /** The posterior mean of [d], a graph of the state, with its standard error. */
    fun E(d: Dist): Estimate = weightedMean(valuesOf(d), normalized())

    /** The posterior variance of [d]. */
    fun Var(d: Dist): Estimate = weightedVariance(valuesOf(d), normalized())

    /** The posterior [q]-quantile of [d]. */
    fun quantile(d: Dist, q: Double): Estimate = weightedQuantile(valuesOf(d), normalized(), q)

    /** The posterior probability that [c] is true. */
    fun P(c: Dist): Estimate = weightedMean(valuesOf(c).map { if (it != 0.0) 1.0 else 0.0 }.toDoubleArray(), normalized())

    /**
     * [d] under the posterior as it is now, as a [Dist] of its own: a draw picks a particle by
     * weight and returns its value of [d]. The snapshot does not change when this posterior
     * learns more; [live] does. It lowers to GLSL (the particles become a constant table).
     */
    fun dist(d: Dist): Dist = empiricalDist(valuesOf(d), normalized(), "posterior")

    /**
     * Several graphs of the state under the posterior as it is now, drawn *together*: one draw
     * picks one particle by weight, and every returned distribution reads that particle. So
     * `val (px, py) = post.dists(x, y)` is a point from the posterior, not an x from one particle
     * and a y from another. Like [dist], a snapshot, and it lowers to GLSL.
     */
    fun dists(vararg ds: Dist): List<Dist> {
        require(ds.isNotEmpty()) { "dists of nothing" }
        val tables = evaluate(ds.toList(), values)
        val index = empiricalDist(DoubleArray(particles) { it.toDouble() }, normalized(), "particle")
        return tables.map { table -> lookup(index, table) }
    }

    /** [n] joint draws from the posterior of [ds]: `result[graph][draw]`. */
    fun samples(vararg ds: Dist, n: Int, seed: Long = 1L): Array<DoubleArray> = jointSamples(*dists(*ds).toTypedArray(), n = n, seed = seed)

    /**
     * [d] under the posterior as it is whenever it is drawn: a pattern or a sound parameter built
     * from it follows the posterior as observations arrive. CPU only.
     */
    fun live(d: Dist): Dist {
        val plan = Plan(listOf(d))
        return Dist(
            "live", emptyList(),
            { _, out, n, r ->
                val vals = values; val lw = logW
                val w = normalizeLogWeights(lw)
                val cum = DoubleArray(particles)
                var c = 0.0
                for (i in 0 until particles) { c += w[i]; cum[i] = c }
                val pick = IntArray(n) { java.util.Arrays.binarySearch(cum, r.nextDouble() * c).let { k -> if (k >= 0) k else minOf(-k - 1, particles - 1) } }
                val fixedAt = HashMap<Int, DoubleArray>()
                for ((k, node) in plan.order.withIndex()) slot[node]?.let { s -> fixedAt[k] = DoubleArray(n) { vals[s][pick[it]] } }
                val res = plan.run(n, r, 8192, null) { k, buf, offset, m ->
                    val src = fixedAt[k] ?: return@run false
                    System.arraycopy(src, offset, buf, 0, m)
                    true
                }[0]
                System.arraycopy(res, 0, out, 0, n)
            },
            null, kind = Dist.Kind.Leaf,
        )
    }

    override fun toString(): String = "Posterior(${state.size} variables, $particles particles, $observations observations, ess=${"%.0f".format(ess)})"
}

/** `table[index]` per sample: a value read from a fixed table by a random index. CPU and GPU. */
internal fun lookup(index: Dist, table: DoubleArray): Dist {
    val t = table.copyOf()
    return Dist(
        "lookup", listOf(index),
        { ins, out, n, _ -> val k = ins[0]; for (i in 0 until n) out[i] = t[k[i].toInt().coerceIn(0, t.size - 1)] },
        { ins, _ -> "nlLookup$GLSL_ID(${ins[0]})" },
        glslPrelude = { id ->
            "const float nlTable$id[${t.size}] = float[](" + t.joinToString(", ") { glslFloat(it) } + ");\n" +
                "float nlLookup$id(float k) { return nlTable$id[clamp(int(k), 0, ${t.size - 1})]; }\n"
        },
    )
}

/**
 * A value known exactly now, as a state variable a [Posterior] can change: `known(onsets[0])` is
 * "the last beat fell at the first onset". Unlike [Dist.dirac] it is not a constant, so a model
 * built on it, such as `normal(beat, 0.015)`, keeps reading it rather than folding it in.
 */
fun known(value: Number): Dist {
    val v = value.toDouble()
    return Dist("known", emptyList(), { _, out, n, _ -> java.util.Arrays.fill(out, 0, n, v) }, { _, _ -> glslFloat(v) },
        pmf = mapOf(v to 1.0), kind = Dist.Kind.Leaf)
}

/**
 * A distribution that is a weighted set of values: a draw picks one by weight. Lowers to GLSL as a
 * constant table and a binary search.
 */
fun empiricalDist(values: DoubleArray, weights: DoubleArray, name: String = "empirical"): Dist {
    require(values.size == weights.size && values.isNotEmpty()) { "one weight per value" }
    var total = 0.0
    for (w in weights) { require(w >= 0 && w.isFinite()) { "weights must be finite and non-negative" }; total += w }
    require(total > 0) { "the weights sum to 0" }
    val vs = values.copyOf()
    val cum = DoubleArray(vs.size)
    var c = 0.0
    for (i in vs.indices) { c += weights[i] / total; cum[i] = c }
    cum[cum.size - 1] = 1.0
    val pmf = HashMap<Double, Double>()
    for (i in vs.indices) pmf.merge(vs[i] + 0.0, weights[i] / total, Double::plus)
    return Dist(
        name, emptyList(),
        { _, out, n, r ->
            for (i in 0 until n) {
                val k = java.util.Arrays.binarySearch(cum, r.nextDouble())
                out[i] = vs[if (k >= 0) k else minOf(-k - 1, vs.size - 1)]
            }
        },
        { _, r -> "nlEmpirical$GLSL_ID($GLSL_RND($r))" },
        pmf = if (pmf.size <= 20_000) pmf.toSortedMap() else null,
        kind = Dist.Kind.Leaf,
        glslPrelude = { id ->
            buildString {
                append("const float nlEmpV$id[${vs.size}] = float[](").append(vs.joinToString(", ") { glslFloat(it) }).append(");\n")
                append("const float nlEmpC$id[${vs.size}] = float[](").append(cum.joinToString(", ") { glslFloat(it) }).append(");\n")
                append("float nlEmpirical$id(float u) {\n")
                append("    int lo = 0, hi = ${vs.size - 1};\n")
                append("    while (lo < hi) { int mid = (lo + hi) / 2; if (nlEmpC$id[mid] < u) lo = mid + 1; else hi = mid; }\n")
                append("    return nlEmpV$id[lo];\n}\n")
            }
        },
    )
}
