package afield.stdlib

import kotlin.random.Random

/**
 * Seeded mutation operators and a bounded, deterministic search harness.
 *
 * This is the "rehearsal" the graph layer was built toward: [graphMetrics] gives a
 * cost, [graphMatches] locates occurrences, and the copy primitives
 * ([Functions.Fn.mapGraphNumbers], [Functions.Fn.pruneWhere],
 * [Functions.Fn.graftWhere], plus [OpRegistry] transmutation) mutate structure.
 * [evolve] ties them together into a reproducible optimizer over `Fn` graphs.
 *
 * Everything is deterministic given a seed: graph traversal is stable, and every
 * random draw goes through a single [Random]. The same seed and inputs produce the
 * same [FnEvolveRun], which is what makes evolved gestures reproducible in live art.
 */
fun interface FnMutation {
    fun mutate(fn: Functions.Fn, rng: Random): Functions.Fn
}

/** Library of seeded structural mutations, each expressed over existing graph primitives. */
object FnMutations {

    /** Perturb every numeric leaf by a bounded multiplicative jitter. */
    fun jitterNumbers(scale: Double = 0.2): FnMutation = FnMutation { fn, rng ->
        fn.mapGraphNumbers { value, _, _ -> value * (1.0 + (rng.nextDouble() * 2.0 - 1.0) * scale) }
    }

    /** Additively nudge every numeric leaf by a bounded amount. */
    fun nudgeNumbers(amount: Double = 0.1): FnMutation = FnMutation { fn, rng ->
        fn.mapGraphNumbers { value, _, _ -> value + (rng.nextDouble() * 2.0 - 1.0) * amount }
    }

    /** Transmute one random node to another op in the same family (oscillator, noise, …). */
    fun substituteOp(): FnMutation = FnMutation { fn, rng ->
        val candidates = fn.graphMatches { OpRegistry.isTransmutable(it.op) }
        if (candidates.isEmpty()) return@FnMutation fn
        val chosen = candidates[rng.nextInt(candidates.size)]
        val family = OpRegistry.family(chosen.context.op) ?: return@FnMutation fn
        val targets = OpRegistry.opsInFamily(family).filter { !it.equals(chosen.context.op, ignoreCase = true) }
        if (targets.isEmpty()) return@FnMutation fn
        val target = targets[rng.nextInt(targets.size)]
        fn.rewriteGraph { context, copy ->
            if (context.path == chosen.context.path) copy.rebuildAsOp(target) ?: copy else copy
        }
    }

    /** Replace one random non-root subtree with zero (parsimony pressure). */
    fun pruneSubtree(): FnMutation = FnMutation { fn, rng ->
        val candidates = fn.graphMatches { it.path.isNotEmpty() }
        if (candidates.isEmpty()) return@FnMutation fn
        val chosen = candidates[rng.nextInt(candidates.size)]
        fn.pruneWhere(predicate = { it.path == chosen.context.path })
    }

    /** Replace one random subtree with `subtree + subtree`, growing structure. */
    fun duplicateSubtree(): FnMutation = FnMutation { fn, rng ->
        val candidates = fn.graphMatches { it.path.isNotEmpty() }
        if (candidates.isEmpty()) return@FnMutation fn
        val chosen = candidates[rng.nextInt(candidates.size)]
        fn.rewriteGraph { context, copy ->
            if (context.path == chosen.context.path) with(Functions) { copy + copy.deepCopy() } else copy
        }
    }

    /** Graft a copy of [donor] onto one random non-root site (structural crossover). */
    fun graftSubtree(donor: Functions.Fn): FnMutation = FnMutation { fn, rng ->
        val candidates = fn.graphMatches { it.path.isNotEmpty() }
        if (candidates.isEmpty()) return@FnMutation fn
        val chosen = candidates[rng.nextInt(candidates.size)]
        fn.graftWhere(donor) { it.path == chosen.context.path }
    }

    /** A reasonable default palette for [evolve] when the caller has no donor material. */
    fun defaults(): List<Pair<String, FnMutation>> = listOf(
        "jitter" to jitterNumbers(),
        "nudge" to nudgeNumbers(),
        "substituteOp" to substituteOp(),
        "prune" to pruneSubtree(),
        "duplicate" to duplicateSubtree()
    )
}

/** One accepted improvement during a run. */
data class FnEvolveStep(
    val iteration: Int,
    val mutation: String,
    val score: Double
)

/** Result and provenance of an [evolve] run, mirroring [FnGraphRewriteRun]'s shape. */
data class FnEvolveRun(
    val best: Functions.Fn,
    val bestScore: Double,
    val initialScore: Double,
    val steps: List<FnEvolveStep>,
    val iterations: Int,
    val evaluations: Int
) {
    val improved: Boolean get() = bestScore > initialScore
}

/**
 * A bounded (µ+λ) hill-climb over `Fn` graphs.
 *
 * Each iteration draws [lambda] mutated candidates from the current best (mutation
 * chosen by the seeded [Random]), scores them with [fitness] (higher is better),
 * and adopts the best candidate that beats the incumbent. Bounded by [iterations]
 * so it can never disappear into a live animation loop — the same discipline as
 * [Functions.Fn.rewriteUntilStable].
 */
fun evolve(
    seed: Functions.Fn,
    iterations: Int = 64,
    lambda: Int = 6,
    rngSeed: Long = 0L,
    mutations: List<Pair<String, FnMutation>> = FnMutations.defaults(),
    fitness: (Functions.Fn) -> Double
): FnEvolveRun {
    require(iterations >= 0) { "iterations must be non-negative" }
    require(lambda > 0) { "lambda must be positive" }
    require(mutations.isNotEmpty()) { "at least one mutation is required" }

    val rng = Random(rngSeed)
    var best = seed
    val initialScore = fitness(seed)
    var bestScore = initialScore
    var evaluations = 1
    val steps = mutableListOf<FnEvolveStep>()

    for (iteration in 1..iterations) {
        var roundBest: Functions.Fn? = null
        var roundScore = bestScore
        var roundMutation = ""
        repeat(lambda) {
            val (name, mutation) = mutations[rng.nextInt(mutations.size)]
            val candidate = runCatching { mutation.mutate(best, rng) }.getOrDefault(best)
            val score = runCatching { fitness(candidate) }.getOrDefault(Double.NEGATIVE_INFINITY)
            evaluations++
            if (score > roundScore) {
                roundScore = score
                roundBest = candidate
                roundMutation = name
            }
        }
        val winner = roundBest
        if (winner != null && roundScore > bestScore) {
            best = winner
            bestScore = roundScore
            steps += FnEvolveStep(iteration, roundMutation, roundScore)
        }
    }

    return FnEvolveRun(best, bestScore, initialScore, steps, iterations, evaluations)
}

/**
 * A ready-made fitness: how closely a candidate matches [target] on a shared time-base,
 * minus an optional complexity penalty from [graphMetrics]. Higher is better.
 */
fun matchFitness(
    target: Functions.Fn,
    domain: Functions.Domain = Functions.Domain.DEFAULT,
    samples: Int = 128,
    complexityWeight: Double = 0.0
): (Functions.Fn) -> Double {
    val count = samples.coerceIn(2, 8192)
    val xs = DoubleArray(count) { index -> domain.min + domain.length * index / (count - 1).toDouble() }
    val targetY = DoubleArray(count) { index -> target.evalSafe(xs[index], outside = Double.NaN) }
    return { candidate ->
        var sumSq = 0.0
        var valid = 0
        for (index in xs.indices) {
            val a = candidate.evalSafe(xs[index], outside = Double.NaN)
            val b = targetY[index]
            if (a.isFinite() && b.isFinite()) {
                val d = a - b
                sumSq += d * d
                valid++
            }
        }
        if (valid == 0) {
            Double.NEGATIVE_INFINITY
        } else {
            val rmse = kotlin.math.sqrt(sumSq / valid)
            -rmse - complexityWeight * candidate.graphMetrics().cost
        }
    }
}
