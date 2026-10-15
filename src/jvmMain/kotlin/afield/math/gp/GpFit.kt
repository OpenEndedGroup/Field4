@file:afield.runtime.completion.CompletionCategory("Math")
package afield.math.gp

import kotlin.math.*

/** Positive parameter and explicit bounds, searched in log space. Same units as the model builder. */
data class GpParameter(val name: String, val initial: Double, val lower: Double, val upper: Double) {
    init { require(name.isNotBlank() && lower.isFinite() && lower > 0 && upper.isFinite() && upper > lower && initial in lower..upper) }
}

/** A bounded local likelihood fit, not a posterior over hyperparameters or a global optimum. */
class GpFitResult internal constructor(
    val process: GaussianProcess, values: Map<String, Double>, val evaluations: Int,
    val iterations: Int, val converged: Boolean, val initialLogMarginalLikelihood: Double
) {
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
 * length scale. A broad posterior is the honest answer when the data cannot decide.
 */
class GpEvidenceScan internal constructor(candidates: DoubleArray, logEvidence: DoubleArray) {
    private val c = candidates.copyOf()
    private val e = logEvidence.copyOf()
    val candidates: DoubleArray get() = c.copyOf()
    val logEvidence: DoubleArray get() = e.copyOf()
    val posteriorWeights: DoubleArray get() {
        val top = e.max()
        val w = DoubleArray(e.size) { exp(e[it] - top) }
        val total = w.sum()
        return DoubleArray(w.size) { w[it] / total }
    }
    val best: Double get() = c[e.indices.maxBy { e[it] }]
}

fun scanGpEvidence(candidates: DoubleArray, build: (Double) -> GaussianProcess): GpEvidenceScan {
    require(candidates.isNotEmpty() && candidates.all { it.isFinite() })
    return GpEvidenceScan(candidates, DoubleArray(candidates.size) {
        val model = build(candidates[it])
        require(model.trainingSize > 0) { "Evidence needs observations" }
        model.logMarginalLikelihood
    })
}
