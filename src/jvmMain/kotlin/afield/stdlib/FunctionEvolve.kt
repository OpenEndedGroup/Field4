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
    onIteration: ((iteration: Int, total: Int) -> Unit)? = null,
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

    // Politely offer a progress bar (with a tqdm-style ETA) unless the caller reports progress
    // itself. Inert outside a box, globally disableable, and only surfaces if the search runs
    // long enough to be worth mentioning — see afield.ProgressReporter.
    val reporter = if (onIteration == null) afield.progressReporter("evolve") else null
    val report: (Int, Int) -> Unit =
        onIteration ?: { iteration, total -> reporter?.update(iteration.toLong(), total.toLong()) }

    try {
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
            report(iteration, iterations)
        }
    } finally {
        reporter?.finish()
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

/**
 * A fixed-length behavioural descriptor: what an `Fn` *does*, not how it is built.
 *
 * [samples] holds [Fn.evalSafe] readings on an even grid across [domain] (non-finite
 * readings recorded as `0.0`), and [finiteFraction] is the share of grid points that
 * evaluated to a finite value. Two graphs with different structure but the same sampled
 * signal are behaviourally identical — this is the coordinate novelty search explores,
 * the counterpart to the structural view given by [graphMetrics].
 */
data class FnBehaviour(
    val samples: DoubleArray,
    val domain: Functions.Domain,
    val finiteFraction: Double
) {
    /** Root-mean-square difference between two equal-length descriptors. */
    fun distanceTo(other: FnBehaviour): Double {
        require(samples.size == other.samples.size) { "behaviours must share a sample count" }
        var sumSq = 0.0
        for (index in samples.indices) {
            val d = samples[index] - other.samples[index]
            sumSq += d * d
        }
        return kotlin.math.sqrt(sumSq / samples.size)
    }

    // DoubleArray needs value-based equality/hash for FnBehaviour to behave in collections.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FnBehaviour) return false
        return samples.contentEquals(other.samples) &&
            domain == other.domain &&
            finiteFraction == other.finiteFraction
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + domain.hashCode()
        result = 31 * result + finiteFraction.hashCode()
        return result
    }
}

/**
 * Characterizes what [this] *does* over [domain] into an [FnBehaviour] descriptor.
 *
 * Uses the same even grid convention as [matchFitness] so descriptors and match objectives
 * line up. Non-finite readings are recorded as `0.0` in [FnBehaviour.samples] but counted
 * against [FnBehaviour.finiteFraction], letting novelty search notice — and penalize —
 * candidates that mostly blow up.
 *
 * With [normalize] the sampled signal is reduced to a z-score (subtract the mean, divide by
 * the RMS) so the descriptor captures *shape* independent of amplitude and offset. This
 * matters for novelty search: the raw coordinate measures distance in value-space, so a
 * search rewarded for novelty can "win" trivially by scaling an amplitude — behaviourally
 * distant, but the same gesture louder. Normalizing forces novelty to explore genuinely
 * different shapes (sine vs saw vs pulse) instead of the same shape rescaled. A flat signal
 * (zero variance) normalizes to zeros rather than dividing by ~0.
 */
fun Functions.Fn.behaviour(
    domain: Functions.Domain = Functions.Domain.DEFAULT,
    samples: Int = 32,
    normalize: Boolean = false
): FnBehaviour {
    val count = samples.coerceIn(2, 8192)
    val readings = DoubleArray(count)
    var finite = 0
    for (index in 0 until count) {
        val x = domain.min + domain.length * index / (count - 1).toDouble()
        val y = evalSafe(x, outside = Double.NaN)
        if (y.isFinite()) {
            readings[index] = y
            finite++
        }
    }
    if (normalize) {
        var mean = 0.0
        for (v in readings) mean += v
        mean /= count
        var sumSq = 0.0
        for (index in readings.indices) {
            readings[index] -= mean
            sumSq += readings[index] * readings[index]
        }
        val rms = kotlin.math.sqrt(sumSq / count)
        if (rms > 1e-12) {
            for (index in readings.indices) readings[index] /= rms
        }
    }
    return FnBehaviour(readings, domain, finite / count.toDouble())
}

/**
 * A fitness that rewards *behavioural novelty* relative to an [archive] of already-seen
 * behaviours, plus a small [validityWeight] bonus for evaluating to finite values.
 *
 * A candidate's novelty is the mean behavioural distance to its [neighbours] nearest
 * archive members (k-nearest novelty, the canonical formulation). An empty archive gives
 * zero novelty, so the first pass is driven purely by validity — a deliberate,
 * deterministic cold start. Higher is better, so it drops straight into [evolve] in place
 * of [matchFitness].
 */
fun noveltyFitness(
    archive: List<FnBehaviour>,
    domain: Functions.Domain = Functions.Domain.DEFAULT,
    samples: Int = 32,
    neighbours: Int = 3,
    validityWeight: Double = 0.25,
    normalize: Boolean = false
): (Functions.Fn) -> Double {
    require(neighbours > 0) { "neighbours must be positive" }
    val frozen = archive.toList()
    return { candidate ->
        val behaviour = candidate.behaviour(domain, samples, normalize)
        val novelty = if (frozen.isEmpty()) {
            0.0
        } else {
            val distances = frozen.map { behaviour.distanceTo(it) }.sorted()
            val k = minOf(neighbours, distances.size)
            distances.take(k).sum() / k
        }
        novelty + validityWeight * behaviour.finiteFraction
    }
}

/** Result and provenance of an [evolveVariety] run. */
data class FnVarietyRun(
    val results: List<Functions.Fn>,
    val behaviours: List<FnBehaviour>,
    val runs: List<FnEvolveRun>
) {
    /** Mean pairwise behavioural distance across [behaviours] — the spread of the set. */
    val meanPairwiseDistance: Double
        get() {
            if (behaviours.size < 2) return 0.0
            var sum = 0.0
            var pairs = 0
            for (i in behaviours.indices) {
                for (j in i + 1 until behaviours.size) {
                    sum += behaviours[i].distanceTo(behaviours[j])
                    pairs++
                }
            }
            return sum / pairs
        }
}

/**
 * Evolves a *diverse set* of `Fn` graphs instead of a single best match.
 *
 * This is the variety-generation counterpart to [evolve]: rather than converging every
 * candidate onto one target, it grows [count] behaviourally distinct results by novelty
 * search. Each slot runs a full [evolve] against [noveltyFitness] over a growing
 * [archive], adopts the winner, records its [behaviour], then drifts the seed onto that
 * winner so the next slot has rich material to mutate *away* from.
 *
 * Deterministic in the same disciplined way as [evolve]: each slot derives its own seed
 * (`rngSeed * 31 + slot`), so the same [rngSeed] and inputs yield an identical set — the
 * property that lets an evolved *family* of gestures be reproduced in live art.
 *
 * Set [normalize] to explore *shape* rather than amplitude: without it, novelty distance is
 * measured in raw value-space and the search tends to drift toward ever-louder versions of
 * the same gesture; with it, descriptors are z-scored so the set spreads across genuinely
 * different waveforms. See [Functions.Fn.behaviour].
 */
fun evolveVariety(
    seed: Functions.Fn,
    count: Int = 6,
    iterations: Int = 48,
    lambda: Int = 6,
    rngSeed: Long = 0L,
    domain: Functions.Domain = Functions.Domain.DEFAULT,
    samples: Int = 32,
    neighbours: Int = 3,
    validityWeight: Double = 0.25,
    normalize: Boolean = false,
    mutations: List<Pair<String, FnMutation>> = FnMutations.defaults()
): FnVarietyRun {
    require(count > 0) { "count must be positive" }
    require(neighbours > 0) { "neighbours must be positive" }

    val archive = mutableListOf<FnBehaviour>()
    val results = mutableListOf<Functions.Fn>()
    val runs = mutableListOf<FnEvolveRun>()
    var current = seed

    // Offer a transient progress bar to the box we're running in. Inert (a complete no-op)
    // when there's no active box or when progress bars are globally disabled; removes itself
    // from the output-area stack on completion. Total work = count slots x iterations each.
    val progress = afield.progressReporter("evolve variety")
    val totalIterations = count.toLong() * iterations.toLong()
    var completedIterations = 0L
    try {
        for (slot in 0 until count) {
            val fitness = noveltyFitness(archive, domain, samples, neighbours, validityWeight, normalize)
            val slotBase = completedIterations
            val run = evolve(
                seed = current,
                iterations = iterations,
                lambda = lambda,
                rngSeed = rngSeed * 31 + slot,
                mutations = mutations,
                onIteration = { iteration, total ->
                    progress.update(
                        done = slotBase + iteration,
                        total = totalIterations,
                        details = "slot ${slot + 1}/$count · iter $iteration/$total"
                    )
                },
                fitness = fitness
            )
            completedIterations += iterations.toLong()
            runs += run
            results += run.best
            archive += run.best.behaviour(domain, samples, normalize)
            current = run.best
        }
    } finally {
        progress.finish()
    }

    return FnVarietyRun(results, archive.toList(), runs)
}
