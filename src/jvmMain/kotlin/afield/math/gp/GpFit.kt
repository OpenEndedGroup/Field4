@file:afield.runtime.completion.CompletionCategory("Math")
package afield.math.gp

import kotlin.math.*

/**
 * Choosing GP hyperparameters from data by maximising the log marginal likelihood.
 *
 * `fitGpHyperparameters` runs a bounded pattern search in log space over [GpParameter]s and returns a
 * [GpFitResult] with the fitted process and whether the search step converged; it finds a local
 * optimum, so compare several starts on multimodal problems. `scanGpEvidence` evaluates the evidence
 * over a fixed list of candidate values and returns a [GpEvidenceScan] with normalised posterior
 * weights.
 */

/** Positive parameter and explicit bounds, searched in log space. Same units as the model builder. */
data class GpParameter(val name: String, val initial: Double, val lower: Double, val upper: Double) {
    init { require(name.isNotBlank() && lower.isFinite() && lower > 0 && upper.isFinite() && upper > lower && initial in lower..upper) }
}

/** A bounded local likelihood fit, not a posterior over hyperparameters or a global optimum. */
class GpFitResult internal constructor(
    /** The process built at the best parameters found, ready to predict from. */
    val process: GaussianProcess,
    values: Map<String, Double>,
    /** How many models the search built, including the one at the initial setting. */
    val evaluations: Int,
    /** How many pattern-search passes ran, at most the `maxIterations` asked for. */
    val iterations: Int,
    /** True when the search step fell below the tolerance, false when the iterations ran out. */
    val converged: Boolean,
    /** Log marginal likelihood at the initial setting, to compare against [process]'s. */
    val initialLogMarginalLikelihood: Double
) {
    /** The fitted value of each parameter, by name, clamped to the bounds it was given. */
    val parameters: Map<String, Double> = values.toMap()
}

/**
 * Deterministic bounded coordinate pattern search over log parameters. Works with any composed
 * kernel, mean or noise model supplied by [build]. No hidden normalization, derivatives or native
 * optimizer. Run from several initial settings and compare likelihoods for multimodal models.
 * Each iteration tries both directions on every axis, halving the log step only when none improve.
 * [converged][GpFitResult.converged] means the search step fell below [logTolerance], not global optimality.
 */
fun fitGpHyperparameters(
    parameters: List<GpParameter>, maxIterations: Int = 100, logTolerance: Double = 1e-3,
    build: (DoubleArray) -> GaussianProcess
): GpFitResult {
    require(parameters.isNotEmpty() && parameters.map { it.name }.distinct().size == parameters.size)
    require(maxIterations > 0 && logTolerance.isFinite() && logTolerance > 0)
    val lower = parameters.map { ln(it.lower) }.toDoubleArray()
    val upper = parameters.map { ln(it.upper) }.toDoubleArray()
    var pos = parameters.map { ln(it.initial) }.toDoubleArray()
    var best = build(DoubleArray(pos.size) { exp(pos[it]) })
    require(best.trainingSize > 0) { "Hyperparameter fitting requires observations" }
    val initial = best.logMarginalLikelihood
    var evaluations = 1
    val step = DoubleArray(pos.size) { min(1.0, (upper[it] - lower[it]) / 4) }
    var iterations = 0
    while (iterations < maxIterations && step.max() > logTolerance) {
        var improved = false
        for (axis in pos.indices) {
            val origin = pos.copyOf()
            for (direction in intArrayOf(-1, 1)) {
                val candidate = origin.copyOf()
                candidate[axis] = (origin[axis] + direction * step[axis]).coerceIn(lower[axis], upper[axis])
                if (candidate[axis] == origin[axis]) continue
                val model = build(DoubleArray(pos.size) { exp(candidate[it]).coerceIn(parameters[it].lower, parameters[it].upper) })
                evaluations++
                if (model.logMarginalLikelihood > best.logMarginalLikelihood) { best = model; pos = candidate; improved = true }
            }
        }
        if (!improved) for (i in step.indices) step[i] *= 0.5
        iterations++
    }
    return GpFitResult(best, parameters.indices.associate { parameters[it].name to exp(pos[it]).coerceIn(parameters[it].lower, parameters[it].upper) },
        evaluations, iterations, step.max() <= logTolerance, initial)
}

/**
 * Evidence for each candidate value of one parameter, with every other choice fixed by [build].
 * [posteriorWeights] normalise exp(log evidence): the posterior over the candidates under a
 * uniform prior on the grid. Spread candidates in log space for a scale such as a period or
 * length scale. A broad posterior means the data does not choose between the candidates.
 */
class GpEvidenceScan internal constructor(candidates: DoubleArray, logEvidence: DoubleArray) {
    private val c = candidates.copyOf()
    private val e = logEvidence.copyOf()
    /** The candidate values that were scanned, in the order they were given. A copy. */
    val candidates: DoubleArray get() = c.copyOf()
    /** The log marginal likelihood at each candidate, aligned with [candidates]. A copy. */
    val logEvidence: DoubleArray get() = e.copyOf()
    /**
     * The posterior over [candidates] under a uniform prior on the grid: [logEvidence]
     * exponentiated against its maximum and normalised, so the weights sum to one.
     */
    val posteriorWeights: DoubleArray get() {
        val top = e.max()
        val w = DoubleArray(e.size) { exp(e[it] - top) }
        val total = w.sum()
        return DoubleArray(w.size) { w[it] / total }
    }
    /** The candidate with the largest evidence. The first of them if several tie. */
    val best: Double get() = c[e.indices.maxBy { e[it] }]
}

/**
 * The log marginal likelihood of the model [build] makes at each of [candidates], as a
 * [GpEvidenceScan]. Every model must have observations. One parameter at a time: whatever
 * else [build] varies with the candidate is folded into the evidence without being reported.
 */
fun scanGpEvidence(candidates: DoubleArray, build: (Double) -> GaussianProcess): GpEvidenceScan {
    require(candidates.isNotEmpty() && candidates.all { it.isFinite() })
    return GpEvidenceScan(candidates, DoubleArray(candidates.size) {
        val model = build(candidates[it])
        require(model.trainingSize > 0) { "Evidence needs observations" }
        model.logMarginalLikelihood
    })
}
