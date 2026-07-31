@file:afield.CompletionCategory("Function")

package afield.stdlib

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Gradient-based fitting of `Fn` graphs — the differentiable counterpart to the genetic
 * [evolve] in FunctionEvolve.kt.
 *
 * Where [evolve] searches over *structure* (seeded mutation of the graph), this file tunes
 * the *numbers*: the numeric leaves of a fixed-structure graph are the θ-vector, and Adam
 * descends the RMSE loss against a target `Fn`. The two are complementary — [fitHybrid]
 * runs [evolve] to discover a shape, then polishes its constants here.
 *
 * A lone [fitGradient] is *local* and will stall in a frequency local-minimum when the seed's
 * oscillator frequencies are far from the target's (RMSE-vs-frequency is periodic, not convex);
 * [fitBest] wraps it in deterministic jittered restarts and is the recommended entry when the
 * starting numbers may be wrong. See [fitBest]'s KDoc for the why.
 *
 * The θ extract/apply primitives ride on [Functions.Fn.mapGraphNumbers], whose post-order
 * DFS is stable and deterministic with `preserveSharing = false`: the *i*-th numeric leaf
 * seen while reading is the *i*-th leaf written while applying, so a plain index counter is
 * a sound θ↔graph bijection. Everything is deterministic given a seed (Adam itself has no
 * randomness; only an optional [FnFitConfig] init-jitter draws from a single [Random]),
 * which is what makes a fitted gesture reproducible in live art — the same discipline as
 * [FnEvolveRun].
 */

/**
 * Reads the numeric leaves of [this] into a θ-vector in stable post-order-DFS order.
 *
 * Every `const`/`param` leaf (anything carrying `numbers["value"]`) contributes one entry.
 * The read is non-destructive: [Functions.Fn.mapGraphNumbers] returns each value unchanged,
 * so the returned graph is discarded and only the ordered collection matters.
 */
fun Functions.Fn.extractParameters(): DoubleArray {
    val out = ArrayList<Double>()
    mapGraphNumbers(preserveSharing = false) { value, _, _ ->
        out.add(value)
        value
    }
    return out.toDoubleArray()
}

/** The number of tunable numeric leaves in [this] graph — the dimensionality of θ. */
fun Functions.Fn.parameterCount(): Int {
    var n = 0
    mapGraphNumbers(preserveSharing = false) { value, _, _ ->
        n++
        value
    }
    return n
}

/**
 * Rebuilds [this] graph with its numeric leaves replaced, left-to-right, by [theta].
 *
 * Uses the identical traversal as [extractParameters], so `theta[i]` always lands on the
 * leaf that produced `extractParameters()[i]`. If [theta] is shorter than the leaf count the
 * surplus leaves keep their value (defensive; callers should pass a full vector); extra
 * entries are ignored.
 */
fun Functions.Fn.applyParameters(theta: DoubleArray): Functions.Fn {
    var index = 0
    return mapGraphNumbers(preserveSharing = false) { value, _, _ ->
        val next = if (index < theta.size) theta[index] else value
        index++
        next
    }
}

/**
 * An RMSE loss (lower is better) of a candidate against [target] over an even grid across
 * [domain]. Kept as a named convenience; equivalent to `FnMetric.rmse.lossAgainst(...)`, which
 * is the general seam the fitters now use for any [FnMetric].
 *
 * Returns `+∞` when no grid point is jointly finite, so a degenerate candidate is maximally
 * penalised rather than silently scoring zero.
 */
fun rmseLossAgainst(
    target: Functions.Fn,
    domain: Functions.Domain = Functions.Domain.DEFAULT,
    samples: Int = 128
): (Functions.Fn) -> Double = FnMetric.rmse.lossAgainst(target, domain, samples)

/**
 * A [Functions.Domain] is a `ClosedRange<Double>`, so the fitters accept either a bare range
 * (`0.0 .. 1.0`) or a `Domain` and normalise here — no `Functions.Domain(...)` ceremony at the
 * call site. [fitGradient] and [fitHybrid] default to the bounded unit domain
 * [Functions.Domain.DEFAULT]. [fitBest] instead uses its target's domain when that domain is
 * finite, falling back to the unit domain for unbounded or empty targets.
 */
private fun ClosedRange<Double>.asFnDomain(): Functions.Domain =
    this as? Functions.Domain ?: Functions.Domain(start, endInclusive)

/** One descent step's record: the loss (the metric distance) after applying the step. */
data class FnFitStep(
    val iteration: Int,
    val loss: Double
)

/** Immutable configuration + seed for a [fitGradient] run, kept for provenance. */
data class FnFitConfig(
    val iterations: Int,
    val learningRate: Double,
    val beta1: Double,
    val beta2: Double,
    val epsilon: Double,
    val fdStep: Double,
    val samples: Int,
    val domain: Functions.Domain,
    val rngSeed: Long,
    val initJitter: Double,
    /** Which gradient the run was *asked* for; [FnFitRun.usedExactGradient] says what it got. */
    val gradient: FnGradientMode = FnGradientMode.FINITE_DIFFERENCE
)

/**
 * Result and provenance of a [fitGradient] run, mirroring the shape of [FnEvolveRun].
 *
 * [losses] is the full error trajectory including the starting point, so
 * `losses.first() == initialError` and `losses.last() == finalError`.
 *
 * [initialError] / [finalError] are the distance under the fit's [FnMetric] (RMSE by default)
 * at the start and end. The name is metric-neutral on purpose — a custom metric need not be
 * a root-mean-square, so "error" reads truthfully whatever objective the fit descended.
 */
data class FnFitRun(
    val fitted: Functions.Fn,
    val parameters: DoubleArray,
    val initialParameters: DoubleArray,
    val steps: List<FnFitStep>,
    val losses: List<Double>,
    val initialError: Double,
    val finalError: Double,
    val iterations: Int,
    val evaluations: Int,
    val config: FnFitConfig,
    /**
     * Whether the run really used the exact reverse-mode gradient from `autodiff_fn.kt`.
     *
     * Distinct from `config.gradient`, which records what was *asked* for:
     * [FnGradientMode.AUTO] falls back to finite differences without complaining, and this is how
     * you find out. See [exactFitRefusal] for why.
     */
    val usedExactGradient: Boolean = false
) {
    /** True when the fit ended strictly closer to the target than it started. */
    val improved: Boolean get() = finalError < initialError

    // DoubleArray needs value-based equality/hash to behave in collections and asserts.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FnFitRun) return false
        return parameters.contentEquals(other.parameters) &&
            initialParameters.contentEquals(other.initialParameters) &&
            steps == other.steps &&
            losses == other.losses &&
            initialError == other.initialError &&
            finalError == other.finalError &&
            iterations == other.iterations &&
            evaluations == other.evaluations &&
            config == other.config
    }

    override fun hashCode(): Int {
        var result = parameters.contentHashCode()
        result = 31 * result + initialParameters.contentHashCode()
        result = 31 * result + losses.hashCode()
        result = 31 * result + finalError.hashCode()
        return result
    }
}

/**
 * Central (symmetric) finite-difference gradient of [lossOfTheta] at [theta].
 *
 * The per-parameter step is *relative* — `fdStep * (1 + |θ_i|)` — so a small step probes a
 * near-zero constant finely while still moving a large one meaningfully. A parameter whose
 * ±perturbation makes the loss non-finite (a structural / degenerate leaf, e.g. a period
 * that collapses a domain) has its gradient frozen to `0.0` for this step rather than
 * poisoning the whole update with a NaN.
 */
private fun finiteDifferenceGradient(
    theta: DoubleArray,
    fdStep: Double,
    lossOfTheta: (DoubleArray) -> Double
): DoubleArray {
    val grad = DoubleArray(theta.size)
    for (i in theta.indices) {
        val h = fdStep * (1.0 + abs(theta[i]))
        if (h == 0.0 || !h.isFinite()) {
            grad[i] = 0.0
            continue
        }
        val original = theta[i]
        theta[i] = original + h
        val lossPlus = lossOfTheta(theta)
        theta[i] = original - h
        val lossMinus = lossOfTheta(theta)
        theta[i] = original
        grad[i] = if (lossPlus.isFinite() && lossMinus.isFinite()) {
            (lossPlus - lossMinus) / (2.0 * h)
        } else {
            0.0
        }
    }
    return grad
}

/**
 * Fits the numeric leaves of [seed] to [target] by Adam descent on the RMSE loss.
 *
 * The graph *structure* of [seed] is fixed; only its numbers move. This is the
 * differentiable counterpart to [evolve]: pass the shape you want (e.g.
 * `sin(freq) * amp + offset` with rough starting constants) and it tunes the constants.
 *
 * @param target the function to match.
 * @param seed the fixed-structure graph whose numeric leaves are optimised.
 * @param domain even sample grid for the loss (shared with [matchFitness]).
 * @param iterations number of Adam steps (>= 0).
 * @param lr Adam learning rate.
 * @param fdStep base relative step for the finite-difference gradient.
 * @param metric the distance the descent minimises (lower is better). Defaults to [FnMetric.rmse],
 *        which reproduces the historic root-mean-square objective exactly; pass e.g.
 *        [FnMetric.huber] or [FnMetric.lp] to reshape the loss landscape.
 * @param initJitter if > 0, θ is perturbed by `±initJitter*(1+|θ|)` before descent using
 *        [rngSeed] — a deterministic warm-shake for escaping a flat start.
 * @param gradient how the descent direction is obtained. The default
 *        [FnGradientMode.FINITE_DIFFERENCE] is the historic path and is bit-for-bit unchanged.
 *        [FnGradientMode.EXACT] differentiates the graph itself (`autodiff_fn.kt`) — one loss
 *        evaluation per step instead of `2n+1`, and no `fdStep` to tune — and throws with a
 *        diagnostic if the graph or metric is outside the supported set.
 *        [FnGradientMode.AUTO] takes the exact path where it applies and quietly falls back;
 *        [FnFitRun.usedExactGradient] reports which happened.
 * @param adMetric the differentiable form of [metric], for the exact path. When omitted it is
 *        derived from [metric] via [FnAdMetric.of], which recognises the singleton
 *        [FnMetric.rmse] / [FnMetric.mae] / [FnMetric.logCosh]; pass it explicitly for a bespoke
 *        objective.
 * @return an [FnFitRun] with the fitted graph and the full loss trajectory.
 */
fun fitGradient(
    target: Functions.Fn,
    seed: Functions.Fn,
    domain: ClosedRange<Double> = Functions.Domain.DEFAULT,
    iterations: Int = 200,
    lr: Double = 0.05,
    beta1: Double = 0.9,
    beta2: Double = 0.999,
    epsilon: Double = 1e-8,
    fdStep: Double = 1e-4,
    samples: Int = 128,
    metric: FnMetric = FnMetric.rmse,
    rngSeed: Long = 0L,
    initJitter: Double = 0.0,
    gradient: FnGradientMode = FnGradientMode.FINITE_DIFFERENCE,
    adMetric: FnAdMetric? = null,
    onIteration: ((iteration: Int, total: Int) -> Unit)? = null
): FnFitRun {
    require(iterations >= 0) { "iterations must be non-negative" }
    require(lr > 0.0) { "lr must be positive" }
    require(samples >= 2) { "samples must be >= 2" }

    val dom = domain.asFnDomain()
    val config = FnFitConfig(
        iterations, lr, beta1, beta2, epsilon, fdStep, samples, dom, rngSeed, initJitter, gradient
    )
    val loss = metric.lossAgainst(target, dom, samples)
    val lossOfTheta: (DoubleArray) -> Double = { theta -> loss(seed.applyParameters(theta)) }

    // The exact gradient, or null when this run stays on finite differences. Resolved once, before
    // the loop, so the descent itself has no per-step branch beyond a null check.
    val exactGradient: ((DoubleArray) -> ADGradient)? =
        if (gradient == FnGradientMode.FINITE_DIFFERENCE) {
            null
        } else {
            val differentiable = adMetric ?: FnAdMetric.of(metric)
            val built = differentiable?.let { exactFitObjective(target, seed, dom, samples, it) }
            if (gradient == FnGradientMode.EXACT && built == null) {
                val why = if (differentiable == null) {
                    "the metric has no differentiable form — pass adMetric = ... explicitly " +
                        "(FnAdMetric.of only recognises the singleton FnMetric.rmse / mae / logCosh)"
                } else {
                    exactFitRefusal(target, seed, dom, samples) ?: "unknown"
                }
                error("fitGradient(gradient = EXACT) cannot differentiate this fit: $why")
            }
            built
        }

    val rng = Random(rngSeed)
    val initialTheta = seed.extractParameters()
    val theta = initialTheta.copyOf()
    if (initJitter > 0.0) {
        for (i in theta.indices) {
            theta[i] += (rng.nextDouble() * 2.0 - 1.0) * initJitter * (1.0 + abs(theta[i]))
        }
    }

    var evaluations = 0
    fun evalLoss(t: DoubleArray): Double {
        evaluations++
        return lossOfTheta(t)
    }

    val startLoss = evalLoss(theta)
    val losses = mutableListOf(startLoss)
    val steps = mutableListOf<FnFitStep>()

    // Adam moment estimates.
    val m = DoubleArray(theta.size)
    val v = DoubleArray(theta.size)

    // Bounded like evolve/rewriteUntilStable — a fit can never disappear into an
    // animation loop. Politely offers a progress bar unless the caller reports itself.
    val reporter = if (onIteration == null) afield.progressReporter("fit") else null
    val report: (Int, Int) -> Unit =
        onIteration ?: { iteration, total -> reporter?.update(iteration.toLong(), total.toLong()) }

    try {
        for (iteration in 1..iterations) {
            val grad = if (exactGradient != null) {
                evaluations++
                exactGradient(theta).gradient
            } else {
                finiteDifferenceGradient(theta, fdStep) { evalLoss(it) }
            }
            val t = iteration
            for (i in theta.indices) {
                val g = grad[i]
                if (!g.isFinite()) continue
                m[i] = beta1 * m[i] + (1.0 - beta1) * g
                v[i] = beta2 * v[i] + (1.0 - beta2) * g * g
                val mHat = m[i] / (1.0 - Math.pow(beta1, t.toDouble()))
                val vHat = v[i] / (1.0 - Math.pow(beta2, t.toDouble()))
                val update = lr * mHat / (sqrt(vHat) + epsilon)
                if (update.isFinite()) theta[i] -= update
            }
            val stepLoss = evalLoss(theta)
            losses += stepLoss
            steps += FnFitStep(iteration, stepLoss)
            report(iteration, iterations)
        }
    } finally {
        reporter?.finish()
    }

    val finalLoss = losses.last()
    return FnFitRun(
        fitted = seed.applyParameters(theta),
        parameters = theta,
        initialParameters = initialTheta,
        steps = steps,
        losses = losses,
        initialError = startLoss,
        finalError = finalLoss,
        iterations = iterations,
        evaluations = evaluations,
        config = config,
        usedExactGradient = exactGradient != null
    )
}

/**
 * Robust fit by multiple jittered restarts — the recommended entry point when the seed's
 * numbers might be far from the target's.
 *
 * A single [fitGradient] is *local*: Adam follows the RMSE gradient into the nearest basin.
 * For parameters that enter the loss linearly (amplitude, offset) that basin is the global
 * one and one shot suffices. But **oscillator frequency/phase enter the loss periodically**,
 * so RMSE-vs-frequency is riddled with local minima: a lone descent from the wrong frequency
 * can drift into a spurious basin and stall. This runs [restarts] independent fits — restart 0 from the
 * clean seed, the rest from `±restartJitter*(1+|θ|)` shakes of it (each seeded from
 * `rngSeed + k`) — and keeps the lowest-RMSE result. One of the shakes usually lands in the
 * global basin. Fully deterministic given [rngSeed].
 *
 * For a *structurally* wrong seed (missing operators, wrong topology) reach for [fitHybrid],
 * which searches shape with [evolve] first; [fitBest] only searches numbers.
 *
 * @param domain sampling range for the loss. When omitted, use [target]'s domain if it is finite;
 *        otherwise use the bounded unit domain [Functions.Domain.DEFAULT].
 * @param metric the distance minimised and compared across restarts (lower is better).
 *        Defaults to [FnMetric.rmse]; the restart story above holds for any metric whose
 *        landscape is multimodal in frequency/phase.
 */
fun fitBest(
    target: Functions.Fn,
    seed: Functions.Fn,
    domain: ClosedRange<Double> = target.domain.takeIf { it.isFinite } ?: Functions.Domain.DEFAULT,
    restarts: Int = 12,
    iterations: Int = 300,
    lr: Double = 0.05,
    beta1: Double = 0.9,
    beta2: Double = 0.999,
    epsilon: Double = 1e-8,
    fdStep: Double = 1e-4,
    samples: Int = 128,
    metric: FnMetric = FnMetric.rmse,
    restartJitter: Double = 0.6,
    rngSeed: Long = 0L,
    gradient: FnGradientMode = FnGradientMode.FINITE_DIFFERENCE,
    adMetric: FnAdMetric? = null
): FnFitRun {
    require(restarts >= 1) { "restarts must be >= 1" }
    val reporter = afield.progressReporter("fit best")
    var best: FnFitRun? = null
    try {
        for (k in 0 until restarts) {
            // Restart 0 is the un-jittered anchor: fitBest is never worse than a plain fit.
            val jitter = if (k == 0) 0.0 else restartJitter
            val run = fitGradient(
                target = target,
                seed = seed,
                domain = domain,
                iterations = iterations,
                lr = lr,
                beta1 = beta1,
                beta2 = beta2,
                epsilon = epsilon,
                fdStep = fdStep,
                samples = samples,
                metric = metric,
                rngSeed = rngSeed + k,
                initJitter = jitter,
                gradient = gradient,
                adMetric = adMetric,
                onIteration = { _, _ -> } // suppress inner bars; the outer one tracks restarts
            )
            if (best == null || run.finalError < best.finalError) best = run
            reporter.update((k + 1).toLong(), restarts.toLong())
        }
    } finally {
        reporter.finish()
    }
    return best!!
}

/** Result and provenance of a [fitHybrid] run: the structural search then the polish. */
data class FnHybridRun(
    val evolveRun: FnEvolveRun,
    val fitRun: FnFitRun
) {
    /** The final polished graph. */
    val best: Functions.Fn get() = fitRun.fitted

    /** RMSE at the very start (seed), after evolve, and after the gradient polish. */
    val seedError: Double get() = -evolveRun.initialScore
    val evolvedError: Double get() = fitRun.initialError
    val finalError: Double get() = fitRun.finalError
}

/**
 * Structure-then-numbers fitting: run [evolve] to discover a graph *shape* that roughly
 * matches [target], then hand its numeric leaves to [fitGradient] for a precise polish.
 *
 * This is the natural marriage of the two optimisers in this package — genetic search is
 * good at finding the right operators and topology but converges slowly on exact constants;
 * Adam nails the constants once the shape is right. Deterministic given [rngSeed]: the
 * evolve phase seeds from `rngSeed`, the fit phase from `rngSeed + 1`.
 */
fun fitHybrid(
    target: Functions.Fn,
    seed: Functions.Fn,
    domain: ClosedRange<Double> = Functions.Domain.DEFAULT,
    evolveIterations: Int = 64,
    evolveLambda: Int = 6,
    fitIterations: Int = 200,
    lr: Double = 0.05,
    beta1: Double = 0.9,
    beta2: Double = 0.999,
    epsilon: Double = 1e-8,
    fdStep: Double = 1e-4,
    samples: Int = 128,
    complexityWeight: Double = 0.0,
    metric: FnMetric = FnMetric.rmse,
    rngSeed: Long = 0L,
    mutations: List<Pair<String, FnMutation>> = FnMutations.defaults(),
    gradient: FnGradientMode = FnGradientMode.FINITE_DIFFERENCE,
    adMetric: FnAdMetric? = null
): FnHybridRun {
    val dom = domain.asFnDomain()
    val fitness = matchFitness(target, dom, samples, complexityWeight, metric)
    val evolveRun = evolve(
        seed = seed,
        iterations = evolveIterations,
        lambda = evolveLambda,
        rngSeed = rngSeed,
        mutations = mutations,
        fitness = fitness
    )
    val fitRun = fitGradient(
        target = target,
        seed = evolveRun.best,
        domain = dom,
        iterations = fitIterations,
        lr = lr,
        beta1 = beta1,
        beta2 = beta2,
        epsilon = epsilon,
        fdStep = fdStep,
        samples = samples,
        metric = metric,
        rngSeed = rngSeed + 1,
        gradient = gradient,
        adMetric = adMetric
    )
    return FnHybridRun(evolveRun, fitRun)
}
