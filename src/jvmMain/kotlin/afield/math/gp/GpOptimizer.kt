@file:afield.runtime.completion.CompletionCategory("Math")
package afield.math.gp

import kotlin.math.*

/** One named, bounded parameter an optimiser or preference model searches over. */
data class GpRange(val name: String, val lower: Double, val upper: Double) {
    init {
        require(name.isNotBlank()) { "A parameter needs a name" }
        require(lower.isFinite() && upper.isFinite() && upper > lower) { "Range for '$name' must be finite with upper > lower" }
    }
    internal fun toUnit(v: Double) = ((v - lower) / (upper - lower)).coerceIn(0.0, 1.0)
    internal fun fromUnit(u: Double) = lower + u.coerceIn(0.0, 1.0) * (upper - lower)
}

/** How a [GpOptimizer] scores an untried point from the surrogate's mean and standard deviation. */
sealed class GpAcquisition {
    /** Expected improvement over the best value seen, less a margin [xi] (in standardised units). */
    data class ExpectedImprovement(val xi: Double = 0.01) : GpAcquisition()
    /** Optimism: mean + [beta] · std. Larger [beta] explores more. */
    data class UpperConfidenceBound(val beta: Double = 2.0) : GpAcquisition()
    /** Probability of improving on the best value seen by more than [xi]. Greedy; mostly for comparison. */
    data class ProbabilityOfImprovement(val xi: Double = 0.01) : GpAcquisition()
}

/** One evaluated point: parameter values by name, and the objective there. */
data class GpTrial(val parameters: Map<String, Double>, val value: Double) {
    operator fun get(name: String): Double = parameters[name] ?: error("No parameter '$name' in $parameters")
}

/**
 * Bayesian optimisation of an expensive or hand-judged objective over named, bounded parameters.
 *
 * ```kotlin
 * val opt = GpOptimizer("petals" to 2.0..9.0, "twist" to 0.0..1.0)
 * repeat(25) { opt.step { p -> score(p["petals"]!!, p["twist"]!!) } }
 * println(opt.best)
 * ```
 *
 * Each [suggest] fits a GP surrogate to everything [observe]d so far and returns the point that
 * maximises the [acquisition]. The first [initialPoints] suggestions are a seeded space-filling
 * design instead, since a surrogate on one point says nothing. Values are *maximised* unless
 * [minimize] is set.
 *
 * Inside, inputs are rescaled to the unit cube and outputs standardised, so the kernel is a
 * Matérn-5/2 with one length scale per parameter on [0, 1], fitted (with the observation noise) by
 * [fitGpHyperparameters] on every refit. The acquisition is maximised by scoring
 * [candidates] seeded random points plus jitters of the best few observations, then refining the
 * top [refineStarts] by projected gradient ascent. That gradient is a central difference: the
 * posterior *mean* gradient is analytic ([GaussianProcess.gradient]) but expected improvement
 * also needs the gradient of the posterior standard deviation, which the library does not expose.
 *
 * Deterministic for a given [seed] and sequence of observations. Not thread safe; a box's
 * callbacks run one at a time, which is all it needs.
 */
class GpOptimizer(
    parameters: List<GpRange>,
    val minimize: Boolean = false,
    val acquisition: GpAcquisition = GpAcquisition.ExpectedImprovement(),
    val seed: Long = 1L,
    val initialPoints: Int = max(4, parameters.size + 2),
    val candidates: Int = 768,
    val refineStarts: Int = 4,
    /** Fit hyperparameters every this many observations; reuse the last fit in between. */
    val refitEvery: Int = 1,
) {
    constructor(
        vararg ranges: Pair<String, ClosedFloatingPointRange<Double>>,
        minimize: Boolean = false,
        acquisition: GpAcquisition = GpAcquisition.ExpectedImprovement(),
        seed: Long = 1L,
    ) : this(ranges.map { GpRange(it.first, it.second.start, it.second.endInclusive) }, minimize, acquisition, seed)

    val parameters: List<GpRange> = parameters.toList()
    val dimensions: Int get() = parameters.size
    init {
        require(parameters.isNotEmpty()) { "GpOptimizer needs at least one parameter" }
        require(parameters.map { it.name }.distinct().size == parameters.size) { "Parameter names must be distinct" }
        require(initialPoints >= 1 && candidates >= 1 && refineStarts >= 0 && refitEvery >= 1)
    }

    private val unitX = ArrayList<DoubleArray>()
    private val rawY = ArrayList<Double>()
    private val random = gpRandom(seed)
    private val design: List<DoubleArray> = gpSpaceFilling(initialPoints, dimensions, gpRandom(seed xor 0x5DEECE66DL))
    private var designIndex = 0
    private var hyper: DoubleArray? = null
    private var hyperAt = -1
    private var cached: Surrogate? = null

    /** Everything observed, in order. */
    val history: List<GpTrial> get() = unitX.indices.map { GpTrial(toNamed(unitX[it]), rawY[it]) }
    val observations: Int get() = rawY.size

    /** The best observed trial (largest value, or smallest when [minimize]); null before any. */
    val best: GpTrial?
        get() {
            if (rawY.isEmpty()) return null
            val i = rawY.indices.maxBy { sign * rawY[it] }
            return GpTrial(toNamed(unitX[i]), rawY[i])
        }

    /** Best value after each observation — the convergence trace. */
    val bestSoFar: List<Double>
        get() {
            var b = Double.NaN
            return rawY.map { y -> if (b.isNaN() || sign * y > sign * b) b = y; b }
        }

    /** Fitted kernel length scales (unit-cube units, by parameter name) and noise; empty before a fit. */
    val hyperparameters: Map<String, Double>
        get() = hyper?.let { h -> parameters.indices.associate { parameters[it].name to h[it] } + ("noise" to h[dimensions]) } ?: emptyMap()

    private val sign get() = if (minimize) -1.0 else 1.0

    /** Record that [parameters] scored [value]. Parameters missing from the map are an error. */
    fun observe(parameters: Map<String, Double>, value: Double) {
        require(value.isFinite()) { "Objective values must be finite (got $value)" }
        val u = DoubleArray(dimensions) { i ->
            val r = this.parameters[i]
            r.toUnit(parameters[r.name] ?: error("Missing parameter '${r.name}'"))
        }
        unitX += u
        rawY += value
        cached = null
    }

    fun observe(trial: GpTrial) = observe(trial.parameters, trial.value)

    /** The next point to evaluate. */
    fun suggest(): Map<String, Double> = toNamed(suggestUnit(surrogate()))

    /**
     * [count] points to evaluate together. After choosing each, it is provisionally "observed" at
     * the surrogate's own mean (the kriging believer), which shrinks the uncertainty there and
     * pushes the next choice elsewhere. Nothing provisional is kept.
     */
    fun suggest(count: Int): List<Map<String, Double>> {
        require(count >= 1)
        val chosen = ArrayList<DoubleArray>()
        var s = surrogate()
        repeat(count) {
            val u = suggestUnit(s, chosen)
            chosen += u
            if (s != null) s = s.believe(u)
        }
        return chosen.map { toNamed(it) }
    }

    /** Suggest, evaluate with [objective], observe; returns the trial. */
    fun step(objective: (Map<String, Double>) -> Double): GpTrial {
        val p = suggest()
        val v = objective(p)
        observe(p, v)
        return GpTrial(p, v)
    }

    /** [iterations] calls of [step]; returns [best]. */
    fun run(iterations: Int, objective: (Map<String, Double>) -> Double): GpTrial {
        repeat(iterations) { step(objective) }
        return best!!
    }

    /** Surrogate posterior of the objective at [parameters], in the objective's own units; null before data. */
    fun predict(parameters: Map<String, Double>): GpPrediction? {
        val s = surrogate() ?: return null
        val u = DoubleArray(dimensions) { this.parameters[it].toUnit(parameters[this.parameters[it].name]!!) }
        val p = s.model.predict(*u)
        return GpPrediction(s.offset + s.scale * p.mean, s.scale * s.scale * p.variance)
    }

    /** The acquisition value the next [suggest] would assign to [parameters]; null during the initial design. */
    fun acquisitionAt(parameters: Map<String, Double>): Double? {
        val s = surrogate() ?: return null
        return s.acquire(DoubleArray(dimensions) { this.parameters[it].toUnit(parameters[this.parameters[it].name]!!) })
    }

    private fun toNamed(u: DoubleArray): Map<String, Double> =
        LinkedHashMap<String, Double>().also { m -> parameters.forEachIndexed { i, r -> m[r.name] = r.fromUnit(u[i]) } }

    // --- surrogate ---------------------------------------------------------------------------

    private inner class Surrogate(
        val model: GaussianProcess, val offset: Double, val scale: Double, val bestStd: Double,
        val h: DoubleArray, val xs: List<DoubleArray>, val ys: List<Double>
    ) {
        fun acquire(u: DoubleArray): Double {
            val p = model.predict(*u)
            val mu = sign * p.mean
            val sd = max(p.std, 1e-12)
            return when (val a = acquisition) {
                is GpAcquisition.UpperConfidenceBound -> mu + a.beta * sd
                is GpAcquisition.ExpectedImprovement -> {
                    val imp = mu - bestStd - a.xi
                    val z = imp / sd
                    imp * gpNormalCdf(z) + sd * gpNormalPdf(z)
                }
                is GpAcquisition.ProbabilityOfImprovement -> gpNormalCdf((mu - bestStd - a.xi) / sd)
            }
        }
        /** The same hyperparameters with a fantasy observation at the posterior mean. */
        fun believe(u: DoubleArray): Surrogate {
            val m = model.mean(*u)
            val nx = xs + listOf(u); val ny = ys + listOf(m)
            return Surrogate(build(nx, ny, h), offset, scale, bestStd, h, nx, ny)
        }
    }

    private fun build(xs: List<DoubleArray>, ys: List<Double>, h: DoubleArray): GaussianProcess =
        GaussianProcess.fit(
            xs.toTypedArray(), ys.toDoubleArray(),
            kernel = GpKernels.matern(2.5, h.copyOfRange(0, dimensions)),
            noiseStd = DoubleArray(ys.size) { h[dimensions] },
            dimensions = dimensions,
        )

    private fun surrogate(): Surrogate? {
        cached?.let { return it }
        if (rawY.size < initialPoints || rawY.size < 2) return null
        val mean = rawY.average()
        val sd = sqrt(rawY.sumOf { (it - mean) * (it - mean) } / rawY.size).let { if (it > 1e-12) it else 1.0 }
        val ys = rawY.map { (it - mean) / sd }
        val xs = unitX.toList()
        val refit = hyper == null || rawY.size - hyperAt >= refitEvery
        val h = if (!refit) hyper!! else {
            val start = hyper ?: (DoubleArray(dimensions) { 0.3 } + 0.05)
            val ps = (0 until dimensions).map { GpParameter("l$it", start[it].coerceIn(0.03, 3.0), 0.03, 3.0) } +
                GpParameter("noise", start[dimensions].coerceIn(1e-3, 0.7), 1e-3, 0.7)
            val fit = fitGpHyperparameters(ps, maxIterations = 40, logTolerance = 0.05) { v -> build(xs, ys, v) }
            DoubleArray(dimensions + 1) { fit.parameters[ps[it].name]!! }.also { hyper = it; hyperAt = rawY.size }
        }
        val model = build(xs, ys, h)
        val bestStd = ys.maxOf { sign * it }
        return Surrogate(model, mean, sd, bestStd, h, xs, ys).also { cached = it }
    }

    private fun suggestUnit(s: Surrogate?, avoid: List<DoubleArray> = emptyList()): DoubleArray {
        if (s == null) {
            // Initial design, then (with one point or a batch beyond it) uniform random.
            return if (designIndex < design.size) design[designIndex++].copyOf() else DoubleArray(dimensions) { random.nextDouble() }
        }
        val pool = ArrayList<DoubleArray>(candidates + 32)
        repeat(candidates) { pool += DoubleArray(dimensions) { random.nextDouble() } }
        val elite = unitX.indices.sortedByDescending { sign * rawY[it] }.take(4)
        for (i in elite) repeat(8) {
            val base = unitX[i]
            pool += DoubleArray(dimensions) { (base[it] + 0.05 * random.nextGaussian()).coerceIn(0.0, 1.0) }
        }
        val scored = pool.map { it to s.acquire(it) }.sortedByDescending { it.second }
        var bestU = scored.first().first
        var bestA = scored.first().second
        for ((start, _) in scored.take(refineStarts)) {
            val (u, a) = ascend(s, start)
            if (a > bestA) { bestA = a; bestU = u }
        }
        // A suggestion on top of an observation (or another batch member) teaches nothing new.
        val taken = unitX + avoid
        if (taken.any { dist(it, bestU) < 1e-6 }) {
            bestU = scored.map { it.first }.firstOrNull { c -> taken.none { dist(it, c) < 1e-3 } } ?: bestU
        }
        return bestU
    }

    private fun ascend(s: Surrogate, start: DoubleArray): Pair<DoubleArray, Double> {
        var u = start.copyOf()
        var a = s.acquire(u)
        var step = 0.05
        val e = 1e-4
        repeat(40) {
            val g = DoubleArray(dimensions) { i ->
                val p = u.copyOf(); p[i] = (p[i] + e).coerceAtMost(1.0)
                val m = u.copyOf(); m[i] = (m[i] - e).coerceAtLeast(0.0)
                val d = p[i] - m[i]
                if (d <= 0) 0.0 else (s.acquire(p) - s.acquire(m)) / d
            }
            val norm = sqrt(g.sumOf { it * it })
            if (norm < 1e-12) return u to a
            val trial = DoubleArray(dimensions) { (u[it] + step * g[it] / norm).coerceIn(0.0, 1.0) }
            val at = s.acquire(trial)
            if (at > a) { u = trial; a = at; step *= 1.3 } else { step *= 0.5; if (step < 1e-5) return u to a }
        }
        return u to a
    }

    private fun dist(a: DoubleArray, b: DoubleArray): Double { var s = 0.0; for (i in a.indices) s += (a[i] - b[i]).pow(2); return sqrt(s) }
}

/** A seeded Latin hypercube in [0,1]^d with each stratum jittered. */
internal fun gpSpaceFilling(n: Int, d: Int, random: java.util.Random): List<DoubleArray> {
    val columns = Array(d) { (0 until n).shuffled(kotlin.random.Random(random.nextLong())) }
    return List(n) { i -> DoubleArray(d) { j -> (columns[j][i] + random.nextDouble()) / n } }
}

internal fun gpNormalPdf(z: Double): Double = exp(-0.5 * z * z) / sqrt(2 * PI)

/** Φ(z) via the Numerical Recipes `erfcc` Chebyshev fit (fractional error below 1.2e-7 everywhere). */
internal fun gpNormalCdf(z: Double): Double = 0.5 * gpErfc(-z / sqrt(2.0))

private fun gpErfc(x: Double): Double {
    val t = 1.0 / (1.0 + 0.5 * abs(x))
    val y = t * exp(-x * x - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 +
        t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 +
        t * (-0.82215223 + t * 0.17087277)))))))))
    return if (x >= 0) y else 2.0 - y
}

/** φ(z)/Φ(z), stable for very negative z where both underflow. */
internal fun gpInverseMills(z: Double): Double {
    if (z > -8.0) return gpNormalPdf(z) / gpNormalCdf(z)
    val z2 = z * z
    return -z / (1.0 - 1.0 / z2 + 3.0 / (z2 * z2))
}
