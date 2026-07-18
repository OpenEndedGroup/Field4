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
 * [domain], reusing [matchFitness]'s NaN-safe grid semantics verbatim by negating it.
 *
 * Returns `+∞` when no grid point is jointly finite (matching [matchFitness]'s
 * `NEGATIVE_INFINITY`), so a degenerate candidate is maximally penalised rather than
 * silently scoring zero.
 */
fun rmseLossAgainst(
    target: Functions.Fn,
    domain: Functions.Domain = Functions.Domain.DEFAULT,
    samples: Int = 128
): (Functions.Fn) -> Double {
    val fitness = matchFitness(target, domain, samples, complexityWeight = 0.0)
    return { candidate -> -fitness(candidate) }
}

/** One descent step's record: the loss (RMSE) after applying the step. */
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
    val initJitter: Double
)

/**
 * Result and provenance of a [fitGradient] run, mirroring the shape of [FnEvolveRun].
 *
 * [losses] is the full RMSE trajectory including the starting point, so
 * `losses.first() == initialRmse` and `losses.last() == finalRmse`.
 */
data class FnFitRun(
    val fitted: Functions.Fn,
    val parameters: DoubleArray,
    val initialParameters: DoubleArray,
    val steps: List<FnFitStep>,
    val losses: List<Double>,
    val initialRmse: Double,
    val finalRmse: Double,
    val iterations: Int,
    val evaluations: Int,
    val config: FnFitConfig
) {
    /** True when the fit ended strictly closer to the target than it started. */
    val improved: Boolean get() = finalRmse < initialRmse

    // DoubleArray needs value-based equality/hash to behave in collections and asserts.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FnFitRun) return false
        return parameters.contentEquals(other.parameters) &&
            initialParameters.contentEquals(other.initialParameters) &&
            steps == other.steps &&
            losses == other.losses &&
            initialRmse == other.initialRmse &&
            finalRmse == other.finalRmse &&
            iterations == other.iterations &&
            evaluations == other.evaluations &&
            config == other.config
    }

    override fun hashCode(): Int {
        var result = parameters.contentHashCode()
        result = 31 * result + initialParameters.contentHashCode()
        result = 31 * result + losses.hashCode()
        result = 31 * result + finalRmse.hashCode()
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
 * @param initJitter if > 0, θ is perturbed by `±initJitter*(1+|θ|)` before descent using
 *        [rngSeed] — a deterministic warm-shake for escaping a flat start.
 * @return an [FnFitRun] with the fitted graph and the full loss trajectory.
 */
fun fitGradient(
    target: Functions.Fn,
    seed: Functions.Fn,
    domain: Functions.Domain = Functions.Domain.DEFAULT,
    iterations: Int = 200,
    lr: Double = 0.05,
    beta1: Double = 0.9,
    beta2: Double = 0.999,
    epsilon: Double = 1e-8,
    fdStep: Double = 1e-4,
    samples: Int = 128,
    rngSeed: Long = 0L,
    initJitter: Double = 0.0,
    onIteration: ((iteration: Int, total: Int) -> Unit)? = null
): FnFitRun {
    require(iterations >= 0) { "iterations must be non-negative" }
    require(lr > 0.0) { "lr must be positive" }
    require(samples >= 2) { "samples must be >= 2" }

    val config = FnFitConfig(
        iterations, lr, beta1, beta2, epsilon, fdStep, samples, domain, rngSeed, initJitter
    )
    val loss = rmseLossAgainst(target, domain, samples)
    val lossOfTheta: (DoubleArray) -> Double = { theta -> loss(seed.applyParameters(theta)) }

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
            val grad = finiteDifferenceGradient(theta, fdStep) { evalLoss(it) }
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
        initialRmse = startLoss,
        finalRmse = finalLoss,
        iterations = iterations,
        evaluations = evaluations,
        config = config
    )
}

/** Result and provenance of a [fitHybrid] run: the structural search then the polish. */
data class FnHybridRun(
    val evolveRun: FnEvolveRun,
    val fitRun: FnFitRun
) {
    /** The final polished graph. */
    val best: Functions.Fn get() = fitRun.fitted

    /** RMSE at the very start (seed), after evolve, and after the gradient polish. */
    val seedRmse: Double get() = -evolveRun.initialScore
    val evolvedRmse: Double get() = fitRun.initialRmse
    val finalRmse: Double get() = fitRun.finalRmse
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
    domain: Functions.Domain = Functions.Domain.DEFAULT,
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
    rngSeed: Long = 0L,
    mutations: List<Pair<String, FnMutation>> = FnMutations.defaults()
): FnHybridRun {
    val fitness = matchFitness(target, domain, samples, complexityWeight)
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
        domain = domain,
        iterations = fitIterations,
        lr = lr,
        beta1 = beta1,
        beta2 = beta2,
        epsilon = epsilon,
        fdStep = fdStep,
        samples = samples,
        rngSeed = rngSeed + 1
    )
    return FnHybridRun(evolveRun, fitRun)
}
