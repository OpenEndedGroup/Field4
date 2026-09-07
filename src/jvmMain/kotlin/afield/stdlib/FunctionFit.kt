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
 * [fitBest] wraps it in deterministic jittered restarts and is the entry to reach for when the
 * starting numbers may be wrong; its own KDoc has the shape of that landscape.
 *
 * The θ extract/apply primitives ride on [Functions.Fn.mapGraphNumbers], whose post-order
 * DFS is stable and deterministic with `preserveSharing = false`: the *i*-th numeric leaf
 * seen while reading is the *i*-th leaf written while applying, so a plain index counter is
 * a sound θ↔graph bijection. Everything is deterministic given a seed: Adam itself has no
 * randomness, and only an optional [FnFitConfig] init-jitter draws from a single [Random], so a
 * fit re-run with the same seed comes back identical. [FnEvolveRun] holds to the same rule.
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
 * [domain]. A named convenience, equivalent to `FnMetric.rmse.lossAgainst(...)` — which is the
 * general form the fitters take for any [FnMetric].
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
 * at the start and end. The name is metric-neutral — a custom metric need not be
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
 *        [FnGradientMode.AUTO] takes the exact path where it applies and falls back silently;
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

/**
 * Rewrites **every** numeric leaf of this graph as a `const`, whether or not its value changed — so a
 * fitted graph and its seed have the same leaf *representation* and can be compared structurally.
 *
 * This exists because of a real trap in [applyParameters]. It rides on
 * [Functions.Fn.mapGraphNumbers], which replaces a leaf only when the transform returns a *different*
 * value, and the replacement is always a `const`. So after a fit, a leaf whose θ moved has become
 * `const` while a leaf whose θ happened to land on its original value is still `param` — and
 * [graphSignature] records `op` and `tags`, so two fits of the same seed can produce graphs whose
 * signatures differ *structurally* for a reason that has nothing to do with structure. That is the
 * "gradient-fitted graphs re-entering evolution" landmine: a structural-novelty or dedup pass over
 * fitted graphs sees phantom variety.
 *
 * Normalising to `const` everywhere removes that inconsistency. It does **not** make the signature
 * value-blind — [graphSignature] includes `numbers`, so a graph whose constants moved is
 * still a different signature, which is correct. What it fixes is the part that was an artefact.
 *
 * Values, domains and names are preserved exactly, so this changes no evaluation:
 * `normalizeNumericLeaves().eval(x) == eval(x)` for every x.
 */
fun Functions.Fn.normalizeNumericLeaves(): Functions.Fn = rewriteGraph(preserveSharing = false) { context, copy ->
    val value = context.node.numbers["value"] ?: return@rewriteGraph copy
    if (context.node.op == "const") copy else Functions.c(value, copy.domain).withName(copy.name)
}

/** One round of [fitEvolveRounds]: what the structural search found, and what the fit did to it. */
data class FnRefineRound(
    /** 1-based round index. */
    val round: Int,
    /** The structural search for this round. */
    val evolveRun: FnEvolveRun,
    /** The numeric refinement of [evolveRun]'s best. */
    val fitRun: FnFitRun,
    /** Whether this round's refinement really got the exact gradient, or fell back. */
    val usedExactGradient: Boolean,
    /** Why the exact path was refused this round, when it was. Null when it was used. */
    val refusal: String?
) {
    /** The error after this round's refinement. */
    val error: Double get() = fitRun.finalError
}

/** Result and provenance of a [fitEvolveRounds] run. */
data class FnRefineRun(
    /** The best graph found across all rounds, with its numeric leaves normalised. */
    val best: Functions.Fn,
    /** The error of [best]. */
    val bestError: Double,
    /** The error of the original seed, for the improvement ratio. */
    val seedError: Double,
    /** Every round, in order. */
    val rounds: List<FnRefineRound>
) {
    /** How many rounds got the exact gradient rather than falling back to finite differences. */
    val exactRounds: Int get() = rounds.count { it.usedExactGradient }

    /** The error after each round, so the alternation is plottable. */
    val errors: List<Double> get() = rounds.map { it.error }
}

/**
 * Alternates **structural** search and **numeric** refinement for [rounds] rounds — the marriage the
 * two halves of this package were always heading for, and the thing [fitHybrid] only does once.
 *
 * Each round runs [evolve] to look for a better *shape*, then hands that shape's numeric leaves to
 * [fitGradient] to nail its *constants*, then normalises the result with [normalizeNumericLeaves] and
 * feeds it back in as the next round's seed. Genetic search is good at finding operators and topology
 * and bad at exact constants; gradient descent is the reverse.
 *
 * ## Measured, and it is not the free win it sounds like
 *
 * The motivating hypothesis was that a *polished* graph is a better starting point for the next
 * mutation than an unpolished one, because a mutation's fitness is then judged against a shape that
 * has been given its best shot. `FnRefineRoundsTest` tested that at equal budget on
 * `cos(2)·sin(1.5, …)` from `cos(3.4)·sin(1.0, …)` — one 160-iteration evolve plus one 480-iteration
 * fit ([fitHybrid]) against four interleaved rounds of 40 + 120 — and **the single pass won**: RMSE
 * 0.1935 against 0.2644, from a seed at 1.1857. On that landscape, spending the evolve budget in four
 * short bursts costs more than the polishing between them buys, because a 40-iteration `evolve` is a
 * much weaker structural search than a 160-iteration one and the polish does not make up for it.
 *
 * So this is offered as a *tool with a shape*, not an improvement: it is the right thing when the
 * structural search is cheap relative to the fit, when you want to *watch* the alternation (the
 * per-round record is plottable), or when a round's polish genuinely unlocks a mutation the unpolished
 * shape would have had rejected. It is the wrong thing when one long structural search would do. Try
 * both; [FnRefineRun.errors] and [FnHybridRun] both report it.
 *
 * The normalisation between rounds is not cosmetic — see [normalizeNumericLeaves] for the trap it
 * closes. Without it, whether a leaf comes back as `param` or `const` depends on whether its value
 * happened to move, and a fitted graph re-entering evolution carries that inconsistency into every
 * structural comparison downstream.
 *
 * [gradient] defaults to [FnGradientMode.AUTO] rather than `EXACT` because `evolve`'s op-substitution
 * mutations freely produce graphs the exact path cannot differentiate (a `sin` becomes a `saw`, and
 * `saw` is a piecewise oscillator with no formula — see [FnNonDifferentiable]). `EXACT` would abort
 * such a round; `AUTO` refines it with finite differences instead, and
 * [FnRefineRound.usedExactGradient] plus [FnRefineRound.refusal] record what happened, so
 * [FnRefineRun.exactRounds] tells you how much of the run got exact derivatives.
 *
 * What exact gradients buy *here* is also measured, and it is precision rather than speed: over four
 * rounds of the case above, finite differences reached RMSE 0.182600 in 585 ms using 14884 loss
 * evaluations, and the exact path 0.182557 in 592 ms using 964. A **15× reduction in evaluations that
 * is not a wall-clock win at all** — building the tape costs about what those extra graph evaluations
 * did. The exact path's real advantages are that it is exact, has no `fdStep` to tune, and scales far
 * better as the parameter count grows (finite differences is `2n` evaluations per step; reverse mode is
 * one, whatever `n` is). Do not reach for it expecting it to be faster on a four-parameter graph.
 *
 * Deterministic given [rngSeed]: round *k* evolves from `rngSeed + 2k` and fits from `rngSeed + 2k+1`.
 */
fun fitEvolveRounds(
    target: Functions.Fn,
    seed: Functions.Fn,
    domain: ClosedRange<Double> = Functions.Domain.DEFAULT,
    rounds: Int = 4,
    evolveIterations: Int = 40,
    evolveLambda: Int = 6,
    fitIterations: Int = 120,
    lr: Double = 0.05,
    fdStep: Double = 1e-4,
    samples: Int = 128,
    complexityWeight: Double = 0.0,
    metric: FnMetric = FnMetric.rmse,
    gradient: FnGradientMode = FnGradientMode.AUTO,
    adMetric: FnAdMetric? = null,
    rngSeed: Long = 0L,
    mutations: List<Pair<String, FnMutation>> = FnMutations.defaults()
): FnRefineRun {
    require(rounds >= 1) { "rounds must be >= 1" }
    val dom = domain.asFnDomain()
    val loss = metric.lossAgainst(target, dom, samples)
    val fitness = matchFitness(target, dom, samples, complexityWeight, metric)

    var current = seed.normalizeNumericLeaves()
    val seedError = loss(current)
    var best = current
    var bestError = seedError
    val records = mutableListOf<FnRefineRound>()

    val reporter = afield.progressReporter("refine")
    try {
        for (round in 1..rounds) {
            val evolveRun = evolve(
                seed = current,
                iterations = evolveIterations,
                lambda = evolveLambda,
                rngSeed = rngSeed + 2L * round,
                mutations = mutations,
                fitness = fitness
            )
            val refusal = exactFitRefusal(target, evolveRun.best, dom, samples)
            val fitRun = fitGradient(
                target = target,
                seed = evolveRun.best,
                domain = dom,
                iterations = fitIterations,
                lr = lr,
                fdStep = fdStep,
                samples = samples,
                metric = metric,
                rngSeed = rngSeed + 2L * round + 1L,
                gradient = gradient,
                adMetric = adMetric,
                onIteration = { _, _ -> }
            )
            records += FnRefineRound(
                round = round,
                evolveRun = evolveRun,
                fitRun = fitRun,
                usedExactGradient = fitRun.usedExactGradient,
                refusal = if (fitRun.usedExactGradient) null else refusal
            )
            // Normalise before re-entry: the next round's evolve, and anything comparing signatures
            // downstream, must not see a leaf-representation artefact.
            current = fitRun.fitted.normalizeNumericLeaves()
            val error = loss(current)
            if (error < bestError) {
                bestError = error
                best = current
            }
            reporter.update(round.toLong(), rounds.toLong())
        }
    } finally {
        reporter.finish()
    }

    return FnRefineRun(best, bestError, seedError, records)
}
