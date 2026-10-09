@file:afield.CompletionCategory("Math")
package afield.stdlib

import smile.math.kernel.MercerKernel

/**
 * Use an existing Smile covariance unchanged in Field's exact inference/sampling machinery.
 * This calls k(a,b), not Smile's fitted regression or global random source. The supplied kernel
 * must be immutable, positive semidefinite, and accept the GP's input dimension.
 */
fun MercerKernel<DoubleArray>.asGpKernel(): GpKernel = GpKernel { a, b -> k(a, b) }

/** Smile L-BFGS-B result. A small projected gradient indicates local stationarity, not a global optimum. */
class GpSmileFitResult internal constructor(
    val process: GaussianProcess, val parameters: Map<String, Double>, val evaluations: Int,
    val projectedGradientMax: Double
)

/**
 * Smile's bounded L-BFGS optimizer, with bounded finite differences in log-parameter space.
 * Unlike Smile's regression fitter, the objective here includes Field's per-observation noise,
 * composed kernels and mean functions. Fixed positive bounds are required; zero-noise constraints
 * can be held fixed in [build]. A native optimizer failure is propagated, never hidden.
 */
fun fitGpHyperparametersWithSmile(parameters: List<GpParameter>, maxIterations: Int = 100, tolerance: Double = 1e-5,
                                 build: (DoubleArray) -> GaussianProcess): GpSmileFitResult {
    require(parameters.isNotEmpty() && parameters.map { it.name }.distinct().size == parameters.size)
    require(maxIterations > 0 && tolerance.isFinite() && tolerance > 0)
    val lo = parameters.map { kotlin.math.ln(it.lower) }.toDoubleArray()
    val hi = parameters.map { kotlin.math.ln(it.upper) }.toDoubleArray()
    val x = parameters.map { kotlin.math.ln(it.initial) }.toDoubleArray()
    var evaluations = 0
    fun model(p: DoubleArray): GaussianProcess {
        evaluations++
        return build(DoubleArray(p.size) { kotlin.math.exp(p[it]).coerceIn(parameters[it].lower, parameters[it].upper) })
    }
    var bestX = x.copyOf()
    var best = model(x)
    require(best.trainingSize > 0)
    val objective = object : smile.util.function.DifferentiableMultivariateFunction {
        override fun f(p: DoubleArray): Double {
            val candidate = model(p)
            if (candidate.logMarginalLikelihood > best.logMarginalLikelihood) { best = candidate; bestX = p.copyOf() }
            return -candidate.logMarginalLikelihood
        }
        override fun g(p: DoubleArray, gradient: DoubleArray): Double {
            for (i in p.indices) {
                val a = p.copyOf(); val b = p.copyOf()
                a[i] = (p[i] - 1e-4).coerceAtLeast(lo[i]); b[i] = (p[i] + 1e-4).coerceAtMost(hi[i])
                gradient[i] = (-model(b).logMarginalLikelihood + model(a).logMarginalLikelihood) / (b[i] - a[i])
            }
            return f(p)
        }
    }
    smile.math.BFGS.minimize(objective, minOf(5, parameters.size), x, lo, hi, tolerance, maxIterations)
    val gradient = DoubleArray(x.size)
    val finalX = bestX.copyOf()
    objective.g(finalX, gradient)
    val projected = gradient.indices.maxOf { i ->
        val g = gradient[i]
        if ((finalX[i] <= lo[i] + 1e-9 && g > 0) || (finalX[i] >= hi[i] - 1e-9 && g < 0)) 0.0 else kotlin.math.abs(g)
    }
    return GpSmileFitResult(best, parameters.indices.associate { parameters[it].name to kotlin.math.exp(finalX[it]).coerceIn(parameters[it].lower, parameters[it].upper) }, evaluations, projected)
}
