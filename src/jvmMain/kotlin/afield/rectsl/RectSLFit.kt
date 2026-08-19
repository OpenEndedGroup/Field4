package afield.rectsl

import afield.stdlib.Functions
import afield.stdlib.applyParameters
import afield.stdlib.extractParameters
import afield.stdlib.gradientAt
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Inverse animation: say where a rect should be and when, and solve for the numbers.
 *
 * This is what the differentiable backend is *for*. A RectSL program is normally read forwards —
 * you write the tracks and the sampler tells you where the rects went. Once a track lowers to an
 * `Fn` the arrow turns round: [FreeParamTrack] marks the numbers you are willing to have chosen for
 * you, [TrackFitTarget] says what the track should read at particular instants, and the exact
 * gradient of the lowered graph walks the parameters to the answer.
 *
 * ```
 * # a bar whose height is unknown
 * 1.0 free-param height
 * ```
 * ```kotlin
 * val fit = track.fitParameters(listOf(TrackFitTarget(0.5, 3.0), TrackFitTarget(1.5, 0.0)))
 * fit.initialResidual   // where the seed was
 * fit.finalResidual     // where the fit ended
 * fit.fitted            // the same track, with the parameters substituted
 * ```
 *
 * # Why this is not simply `fitGradient`
 *
 * `fitGradient` is the library's fitter and it does the right thing for a whole graph: it moves
 * **every** numeric leaf. A lowered track's leaves are mostly not parameters at all — they are the
 * key times, the clamp bounds, the `0.5` in an easing, the `-1.0` that mirrors a reversed clock.
 * Letting a descent move those would not fit a track, it would dismantle one. So the loss and the
 * Adam update here are the same shape as `fitGradient`'s, over a **mask**: only the θ slots that
 * [freeParameterIndices] identified as named parameters ever move.
 *
 * Everything under the mask is borrowed rather than reimplemented. The gradient is
 * [Functions.Fn.gradientAt] — one forward interpretation and one backward sweep through the same
 * `FnFormulas` table `Fn.eval` is pinned against — and θ is read and written by
 * `extractParameters` / `applyParameters`, so index *i* means the same leaf here as it does there.
 *
 * A track that does not lower cannot be fitted, and says so with the lowering's own reason rather
 * than a generic failure. A track that lowers but has no free parameters is refused too: descending
 * on an empty mask would report "converged" without having moved, which is the failure mode
 * `FnNonDifferentiable` exists to prevent.
 */

/** One thing the fit is being asked for: the value the track should read at [time]. */
data class TrackFitTarget(
    val time: Double,
    val value: Double,
    /** Relative importance. Targets with a larger weight are matched more closely. */
    val weight: Double = 1.0
)

/** One descent step: the residual after it was applied. */
data class TrackFitStep(val iteration: Int, val residual: Double)

/**
 * What a fit did, including where it started — because "the residual is 0.03" means nothing without
 * the number it came down from, and a fit that did not move should be visible as such.
 */
data class TrackFitResult(
    /** The solved value of each named parameter. */
    val parameters: Map<String, Double>,
    /** What each parameter started at. */
    val initialParameters: Map<String, Double>,
    /** Weighted RMS distance from the targets before any step. */
    val initialResidual: Double,
    /** Weighted RMS distance from the targets at the end. */
    val finalResidual: Double,
    /** The whole trajectory, `losses.first() == initialResidual`. */
    val residuals: List<Double>,
    val steps: List<TrackFitStep>,
    /** The input track with [parameters] substituted — a real track, samplable and renderable. */
    val fitted: Track<Double>,
    /** The lowered graph at the fitted parameters, for plotting or further composition. */
    val fittedFn: Functions.Fn,
    /**
     * Free parameters whose exact derivative was zero at *every* target when the fit started — the
     * ones sitting under a `floor`, a gate or a hold track, which have no differentiable path to the
     * value and therefore cannot have moved.
     *
     * A fit where **all** of them are stuck throws rather than returning; this set is for the partly
     * stuck case, which does descend, and legitimately so, in the subspace it can reach. Without it
     * that case is indistinguishable from a fit where everything moved, which is the one way this
     * fitter could mislead.
     */
    val stuckParameters: Set<String> = emptySet()
) {
    /** True when the fit ended strictly closer to the targets than it started. */
    val improved: Boolean get() = finalResidual < initialResidual

    /**
     * True when some — not all — free parameters could not move. The fit is real; it just solved for
     * fewer parameters than were named. Read [stuckParameters] for which.
     */
    val partlyStuck: Boolean get() = stuckParameters.isNotEmpty()

    /** How much of the starting residual was removed, in `0..1`. */
    val residualReduction: Double
        get() = if (initialResidual <= 0.0) 0.0 else 1.0 - finalResidual / initialResidual
}

/**
 * Solves this track's named free parameters so it passes as near [targets] as it can.
 *
 * @param over the time window the lowering must be valid on. Defaults to the span the targets
 *        themselves cover, widened to include 0, which is what a scene's clock looks like.
 * @param iterations Adam steps. Bounded, like every other fitter here.
 * @param lr Adam learning rate. The default is `fitGradient`'s.
 * @throws RectLangException when the track does not lower, has nothing to solve for, or has only
 *         parameters whose exact derivative is zero at every target — see the refusal in the body,
 *         which is the one a `floor`, a gate or a hold track will trip.
 */
fun Track<Double>.fitParameters(
    targets: List<TrackFitTarget>,
    over: Functions.Domain = defaultFitWindow(targets),
    iterations: Int = 300,
    lr: Double = 0.05,
    beta1: Double = 0.9,
    beta2: Double = 0.999,
    epsilon: Double = 1e-8
): TrackFitResult {
    require(iterations >= 0) { "iterations must be non-negative" }
    require(lr > 0.0) { "lr must be positive" }
    require(targets.isNotEmpty()) { "a fit needs at least one target" }

    val seed = toFn(over).orThrow()
    // Per *name*, not per θ index. A lowering may use one sub-`Fn` more than once — a heading reads
    // each of its position tracks twelve times — and `FnAutoDiff.leafIndices` keys by path, so one
    // named parameter can occupy several θ slots. They are occurrences of a single variable: the
    // gradient with respect to it is the sum over its slots (the chain rule for a repeated
    // occurrence), and a step has to move all of them or the graph stops corresponding to any
    // `substituteParameters` of the IR — `fitted` and `fittedFn` would then sample differently,
    // which is the one way this fitter could return a confidently wrong answer.
    val slots = freeParameterSlots(seed)
    if (slots.isEmpty()) {
        throw RectLangException(
            "this track has no free parameters to solve for — mark one with `free-param` " +
                "(RectSL) or FreeParamTrack (Kotlin). Descending on an empty mask would report " +
                "convergence without having moved."
        )
    }

    val theta = seed.extractParameters()
    val names = slots.keys.toList()
    val slotsOf: List<IntArray> = names.map { slots.getValue(it).toIntArray() }

    /** Weighted RMS distance from the targets, which is what `improved` and the plots report. */
    fun residual(current: Functions.Fn): Double {
        var sum = 0.0
        var weight = 0.0
        for (target in targets) {
            val delta = current.eval(target.time) - target.value
            sum += target.weight * delta * delta
            weight += target.weight
        }
        return if (weight <= 0.0) 0.0 else sqrt(sum / weight)
    }

    /**
     * d(mean squared weighted error)/d(parameter), one entry per name in [names] and one backward
     * sweep per target — each name's entry summed over every θ slot the name occupies.
     */
    fun gradient(at: DoubleArray): DoubleArray {
        val current = seed.applyParameters(at)
        val grad = DoubleArray(names.size)
        var weight = 0.0
        for (target in targets) {
            val g = current.gradientAt(target.time)
            val delta = g.value - target.value
            weight += target.weight
            for (n in names.indices) {
                var partial = 0.0
                for (i in slotsOf[n]) partial += g.gradient[i]
                grad[n] += 2.0 * target.weight * delta * partial
            }
        }
        if (weight > 0.0) for (n in grad.indices) grad[n] /= weight
        return grad
    }

    // A parameter can be *present* in the graph and still have no differentiable path to the value:
    // put one under a `floor`, a gate or a hold and its exact derivative is zero, which is the truth
    // about a staircase rather than a bug. Descending on *only* such parameters would report
    // convergence without ever having moved — the precise failure `FnNonDifferentiable` refuses
    // `sign` to avoid — so an all-stuck mask is refused here too, loudly, and the message names the
    // stuck parameters.
    //
    // The threshold is deliberately "every free parameter", not "any". A partly-stuck fit is a real
    // fit: it descends in the subspace it can reach, which is why the gallery example that solves
    // *beside* a gate reaches residual 6.2e-15 while the one *under* a floor is refused. Refusing on
    // `any` would block the first case, and there is no principled way to solve for the second.
    // What is *not* acceptable is doing that silently, so [TrackFitResult.stuckParameters] carries
    // the set out either way and `TrackFitResult.partlyStuck` says so in one boolean.
    val startGradient = gradient(theta)
    val stuck = names.filterIndexed { n, _ -> startGradient[n] == 0.0 }.toSet()
    if (stuck.size == names.size) {
        throw RectLangException(
            "every free parameter of this track has an exactly zero derivative at every target " +
                "($stuck), so a descent would report convergence without moving. The usual cause is " +
                "a parameter sitting under a step: a floor, a gate, or a hold track. Those lower " +
                "faithfully — their derivative really is zero almost everywhere — but nothing " +
                "downstream of one can be solved for."
        )
    }

    val startResidual = residual(seed.applyParameters(theta))
    val residuals = mutableListOf(startResidual)
    val steps = mutableListOf<TrackFitStep>()

    // Adam state is per parameter, not per θ slot: the slots of one name move together, so they
    // share one moment estimate as well as one gradient.
    val m = DoubleArray(names.size)
    val v = DoubleArray(names.size)

    for (iteration in 1..iterations) {
        // The *squared* weighted mean is what is descended; the sqrt in `residual` is a positive
        // monotone wrapper, so the two have the same minimiser and this avoids a division by zero at
        // a perfect fit.
        val grad = if (iteration == 1) startGradient else gradient(theta)

        for (n in names.indices) {
            val g = grad[n]
            if (!g.isFinite()) continue
            m[n] = beta1 * m[n] + (1.0 - beta1) * g
            v[n] = beta2 * v[n] + (1.0 - beta2) * g * g
            val mHat = m[n] / (1.0 - Math.pow(beta1, iteration.toDouble()))
            val vHat = v[n] / (1.0 - Math.pow(beta2, iteration.toDouble()))
            val update = lr * mHat / (sqrt(vHat) + epsilon)
            if (update.isFinite()) for (i in slotsOf[n]) theta[i] -= update
        }

        val stepResidual = residual(seed.applyParameters(theta))
        residuals += stepResidual
        steps += TrackFitStep(iteration, stepResidual)
    }

    val fittedFn = seed.applyParameters(theta)
    val solved = names.withIndex().associate { (n, name) -> name to theta[slotsOf[n][0]] }
    return TrackFitResult(
        parameters = solved,
        initialParameters = freeParameters(),
        initialResidual = startResidual,
        finalResidual = residuals.last(),
        residuals = residuals,
        steps = steps,
        fitted = substituteParameters(solved),
        fittedFn = fittedFn,
        stuckParameters = stuck
    )
}

/**
 * The window a fit lowers over when the caller does not say: the targets' own span, widened to
 * include time 0 and padded by 10% so a target sitting exactly on the end of a gate or a loop is
 * not on the boundary of validity.
 */
fun defaultFitWindow(targets: List<TrackFitTarget>): Functions.Domain {
    if (targets.isEmpty()) return Functions.Domain.DEFAULT
    val lo = minOf(0.0, targets.minOf { it.time })
    val hi = maxOf(0.0, targets.maxOf { it.time })
    val pad = 0.1 * maxOf(1e-9, hi - lo)
    return Functions.Domain(lo, hi + pad)
}

/**
 * The exact gradient of this track's value at [time] with respect to its named free parameters,
 * against a central finite difference of the same thing.
 *
 * Not a convenience: it is the check that keeps the exact path honest, and it is exposed rather than
 * hidden in a test because a caller with a bespoke track has the same right to ask. Returns the
 * worst relative disagreement across [times] and every named parameter, or `+inf` if the track does
 * not lower.
 *
 * [step] is relative, `step * (1 + |value|)`, the same scaling `fitGradient`'s finite-difference
 * path uses, so a near-zero parameter is probed finely and a large one meaningfully.
 *
 * A parameter occupying several θ slots (see [freeParameterSlots]) is treated as the one variable it
 * is: the exact side sums its slots' partials and the difference side perturbs them together. Doing
 * either half of that alone would make the two disagree for a reason that has nothing to do with the
 * derivative being wrong.
 */
fun Track<Double>.gradientAgreement(
    times: List<Double>,
    over: Functions.Domain = Functions.Domain(
        minOf(0.0, times.minOrNull() ?: 0.0),
        maxOf(1.0, times.maxOrNull() ?: 1.0)
    ),
    step: Double = 1e-6
): Double {
    val fn = toFn(over).valueOrNull() ?: return Double.POSITIVE_INFINITY
    val slots = freeParameterSlots(fn)
    if (slots.isEmpty()) return 0.0
    val theta = fn.extractParameters()
    var worst = 0.0
    for (time in times) {
        val exact = fn.applyParameters(theta).gradientAt(time).gradient
        for ((_, indices) in slots) {
            val h = step * (1.0 + abs(theta[indices.first()]))
            val plus = theta.copyOf().also { for (i in indices) it[i] += h }
            val minus = theta.copyOf().also { for (i in indices) it[i] -= h }
            val fd = (fn.applyParameters(plus).eval(time) - fn.applyParameters(minus).eval(time)) /
                (2.0 * h)
            val analytic = indices.sumOf { exact[it] }
            val delta = abs(analytic - fd) / (1.0 + abs(fd))
            if (delta > worst || delta.isNaN()) worst = delta
        }
    }
    return worst
}
