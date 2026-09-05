@file:afield.CompletionCategory("Search")

package afield.stdlib

import kotlin.random.Random

/**
 * Population search over *any* genome type — the search half that `alife*.kt` was missing and that
 * `FunctionEvolve.kt` and `afield.morpho.MorphoSearch` each had privately.
 *
 * A caller supplies two things and nothing else. [GenomeOps] says how to make a genome, how to vary
 * one, and (optionally) how to recombine two; [Objective] says what a genome scored. The four
 * drivers in [PopulationSearch] then spend a budget of *evaluations* against that pair:
 * [PopulationSearch.random] draws independently and keeps the best, [PopulationSearch.hillClimb]
 * walks one point, [PopulationSearch.evolve] runs a (µ+λ) generational loop with tournament or
 * lexicase selection, and [PopulationSearch.novelty] replaces the objective's own fitness with
 * distance from an archive of behaviours.
 *
 * [PopulationSearch.random] is a **control arm**, not a fourth option. It draws from exactly the
 * distribution the other three mutate within, so a difference between their results is a difference
 * in *selection* rather than in the generator. Reporting an evolutionary result without it says
 * nothing: a generator good enough to stumble onto a decent genome occasionally looks like a
 * successful search. This convention is taken directly from `MorphoSearch`, which is where it was
 * already right.
 *
 * **Budget is evaluations, not generations.** A population of 24 and a hill climb of 1 do very
 * different amounts of work per generation, so a per-generation budget would compare budgets rather
 * than methods. [SearchRun.trace] is likewise best-so-far *per evaluation*.
 *
 * **What determinism means here.** Given the same [GenomeOps], the same [Objective] and the same
 * `seed`, a driver returns the same [SearchRun]: the same best genome, the same trace, the same
 * evaluation count, every time. It is one seeded [Random] threaded through every draw, and
 * evaluation is deliberately **sequential** — parallel evaluation would reorder those draws and
 * throw the property away. It says nothing about thread safety, and it holds only if the caller's
 * `mutate` and `evaluate` are themselves deterministic functions of their arguments.
 */

/** A named variation operator, so a run can report *which* mutation produced each improvement. */
class Variation<G>(val name: String, val apply: (G, Random) -> G) {
    override fun toString(): String = name
}

/**
 * Everything a population search needs to know about a genome type.
 *
 * The three optional members are optional on purpose, and their defaults are the honest ones:
 * a genome with no recombination says so by leaving [crossover] alone rather than by returning one
 * parent unchanged and letting [evolve] believe it recombined.
 */
interface GenomeOps<G> {

    /** An independent draw from the genome distribution. The control arm samples exactly this. */
    fun random(rng: Random): G

    /** The variation operators, at least one. [evolve] picks uniformly among them and records the name. */
    val mutations: List<Variation<G>>

    /**
     * Recombine two parents, or `null` — the default — to declare that this genome has no
     * recombination.
     *
     * `null` rather than "return a parent" because [evolve] then never spends its `crossoverRate`
     * budget, and [SearchRun.recombined] reports honestly that no crossover happened. A default
     * that quietly returned one parent would make a mutation-only search look like a GA.
     */
    fun crossover(a: G, b: G, rng: Random): G? = null

    /**
     * A stable string identifying a genome's *phenotype*, or `null` — the default — for no
     * memoisation.
     *
     * When non-null a repeat evaluation is served from cache and **costs no budget**, which is the
     * right accounting when evaluation is the expensive resource and mutation is often neutral.
     * The default is `null` so a caller who has not thought about it gets the strict reading, where
     * every candidate costs one unit.
     */
    fun signature(g: G): String? = null
}

/**
 * What one candidate scored.
 *
 * @property fitness the scalar, **higher is better**. Drives tournament selection and the trace.
 * @property cases per-test-case scores, higher better, for [Selection.LEXICASE]. Empty means the
 *   objective has no case structure, and lexicase then degenerates to a uniform random draw — which
 *   is why [evolve] rejects that combination rather than quietly selecting at random.
 * @property behaviour a fixed-length behavioural descriptor for [PopulationSearch.novelty]. Empty
 *   means the objective offers none.
 */
class Evaluation(
    val fitness: Double,
    val cases: DoubleArray = DoubleArray(0),
    val behaviour: DoubleArray = DoubleArray(0)
) {
    override fun toString(): String = "Evaluation(%.5f, %d case(s), %d descriptor(s))"
        .format(fitness, cases.size, behaviour.size)
}

/** Scores a genome. Higher is better, always — a cost function is negated by its caller, not here. */
fun interface Objective<G> {
    fun evaluate(genome: G): Evaluation
}

/** Build an [Objective] from a plain scalar score. */
fun <G> objectiveOf(score: (G) -> Double): Objective<G> = Objective { Evaluation(score(it)) }

/** A genome and what it scored. */
class Individual<G>(val genome: G, val evaluation: Evaluation, val origin: String) {
    val fitness: Double get() = evaluation.fitness
    override fun toString(): String = "%.5f via %s".format(fitness, origin)
}

/**
 * One generation in which the population's leader improved.
 *
 * @property generation 1-based generation index
 * @property fitness what the new leader scored
 * @property origin the variation operator that produced it, or `"seed"`/`"random"`/`"immigrant"`
 */
class Improvement(val generation: Int, val fitness: Double, val origin: String) {
    override fun toString(): String = "gen $generation: %.5f via %s".format(fitness, origin)
}

/** How [PopulationSearch.evolve] picks a parent. */
enum class Selection {
    /** Best of *k* by scalar fitness. */
    TOURNAMENT,

    /**
     * Lexicase: shuffle the cases, keep only the individuals best on the first, then best on the
     * second, until one is left.
     *
     * It never averages, which is the point. An individual that is uniquely good on one hard case
     * and hopeless on aggregate is invisible to a tournament and is selected by lexicase whenever
     * that case comes up first. Requires [Evaluation.cases] to be non-empty; [PopulationSearch.evolve]
     * rejects the combination otherwise rather than degenerating into a uniform random draw.
     */
    LEXICASE
}

/**
 * What one search run did.
 *
 * @property trace best-so-far fitness after each **evaluation**, so two drivers at the same budget
 *   are directly comparable however differently they are shaped internally.
 * @property proposals genomes offered to the evaluator, including cache hits. Well above
 *   [evaluations] means the driver is re-treading; see [GenomeOps.signature].
 * @property recombined how many children came from [GenomeOps.crossover]. Zero for a genome that
 *   declares no recombination, and the number to look at before calling a run "a GA".
 */
class SearchRun<G>(
    val label: String,
    val best: Individual<G>?,
    val evaluations: Int,
    val trace: DoubleArray,
    val proposals: Int,
    val recombined: Int,
    val immigrants: Int,
    val generations: Int,
    val survivors: List<Individual<G>>,
    val millis: Double,
    /** Every generation in which the leader improved, in order. Empty for a non-generational driver. */
    val improvements: List<Improvement> = emptyList()
) {
    /** The scalar the run reached, or negative infinity if it never evaluated anything. */
    val bestFitness: Double get() = best?.fitness ?: Double.NEGATIVE_INFINITY

    /** Best-so-far at the *first* evaluation — the one-shot baseline every later point improves on. */
    val initialFitness: Double get() = if (trace.isEmpty()) Double.NEGATIVE_INFINITY else trace[0]

    fun summary(): String = buildString {
        append("%-18s %5d evals".format(label, evaluations))
        append("  best %10.5f".format(bestFitness))
        append("  gens %3d".format(generations))
        append("  proposals %5d".format(proposals))
        if (recombined > 0) append("  crossover %d".format(recombined))
        if (immigrants > 0) append("  immigrants %d".format(immigrants))
        append("  %.1f s".format(millis / 1000.0))
    }

    override fun toString(): String = summary()
}

/** The drivers. All four take the same budget and return the same shape of result. */
object PopulationSearch {

    /** Proposals a driver may make per unit of budget before it is judged stuck and stopped. */
    const val PROPOSAL_LIMIT: Int = 200

    /**
     * Draw [budget] independent genomes and keep the best. **The control.**
     *
     * Anything [hillClimb] or [evolve] claims has to be claimed against this, at the same budget,
     * from the same [GenomeOps] and with the same seed policy.
     */
    fun <G> random(
        ops: GenomeOps<G>,
        objective: Objective<G>,
        budget: Int,
        seed: Long = 1L,
        label: String = "random"
    ): SearchRun<G> {
        val s = Session(ops, objective, seed, label)
        while (s.running(budget)) s.consider(ops.random(s.rng), "random")
        return s.finish()
    }

    /**
     * Accept any variation that is not worse, restart when stuck.
     *
     * "Not worse" rather than "better" is not a detail. Genome spaces are full of neutral changes,
     * and a climber that refuses them sits on the first local optimum it meets. Neutral drift is how
     * it walks along a plateau to somewhere a single mutation can improve.
     *
     * @param patience non-improving steps before abandoning the current point for a fresh random one
     */
    fun <G> hillClimb(
        ops: GenomeOps<G>,
        objective: Objective<G>,
        budget: Int,
        seed: Long = 1L,
        patience: Int = 40,
        start: G? = null,
        label: String = "hill climb"
    ): SearchRun<G> {
        require(ops.mutations.isNotEmpty()) { "hillClimb needs at least one mutation" }
        val s = Session(ops, objective, seed, label)
        if (!s.running(budget)) return s.finish()
        var current = s.consider(start ?: ops.random(s.rng), "seed")
        var sinceImprovement = 0
        while (s.running(budget)) {
            val v = ops.mutations[s.rng.nextInt(ops.mutations.size)]
            val candidate = s.consider(s.vary(v, current.genome), v.name)
            if (candidate.fitness > current.fitness) sinceImprovement = 0 else sinceImprovement++
            if (candidate.fitness >= current.fitness) current = candidate
            if (sinceImprovement >= patience && s.running(budget)) {
                current = s.consider(ops.random(s.rng), "restart")
                sinceImprovement = 0
            }
        }
        return s.finish(listOf(current))
    }

    /**
     * A (µ+λ) generational search: elitist truncation, tournament or lexicase selection, optional
     * recombination and optional random immigrants.
     *
     * Elitist because parents survive into the truncation, so the best genome found can never be
     * lost to a bad generation. λ children are produced per generation and the whole µ+λ pool is cut
     * back to µ.
     *
     * **µ = 1 is a legal and meaningful configuration**: a single incumbent, λ children, keep the
     * best. That is the shape `FunctionEvolve.evolve` has always had, and it is why the parent draw
     * is skipped outright when the pool holds one individual — a tournament over a population of one
     * has no decision to make, so drawing for it would only burn entropy. For the same reason a
     * `crossoverRate` or `immigrantRate` of exactly `0.0` skips its draw rather than making one whose
     * comparison can never succeed.
     *
     * @param mu parents kept after truncation, >= 1
     * @param lambda children per generation, >= 1
     * @param tournament tournament size k, ignored under [Selection.LEXICASE]
     * @param crossoverRate probability a child comes from [GenomeOps.crossover] before mutation;
     *   silently inert for a genome that declares no recombination
     * @param immigrantRate probability a child is a fresh random draw instead. Without immigrants an
     *   elitist truncation on a deceptive landscape converges early and then re-evaluates its
     *   converged point forever.
     * @param elitistMutation when `true` (the default) a crossover child is *also* mutated
     * @param seeds genomes to start the population from, e.g. the survivors of a previous stage
     */
    fun <G> evolve(
        ops: GenomeOps<G>,
        objective: Objective<G>,
        budget: Int,
        seed: Long = 1L,
        mu: Int = 8,
        lambda: Int = 16,
        tournament: Int = 2,
        selection: Selection = Selection.TOURNAMENT,
        crossoverRate: Double = 0.0,
        immigrantRate: Double = 0.0,
        elitistMutation: Boolean = true,
        seeds: List<G> = emptyList(),
        label: String = "evolve",
        onGeneration: ((generation: Int, evaluations: Int) -> Unit)? = null
    ): SearchRun<G> = evolveWithHook(
        ops, objective, budget, seed, mu, lambda, tournament, selection,
        crossoverRate, immigrantRate, elitistMutation, seeds, label, null, onGeneration
    )

    private fun <G> evolveWithHook(
        ops: GenomeOps<G>,
        objective: Objective<G>,
        budget: Int,
        seed: Long,
        mu: Int,
        lambda: Int,
        tournament: Int,
        selection: Selection,
        crossoverRate: Double,
        immigrantRate: Double,
        elitistMutation: Boolean,
        seeds: List<G>,
        label: String,
        hook: ((Individual<G>) -> Unit)?,
        onGeneration: ((generation: Int, evaluations: Int) -> Unit)? = null
    ): SearchRun<G> {
        require(mu >= 1) { "evolve needs mu >= 1, got $mu" }
        require(lambda >= 1) { "evolve needs lambda >= 1, got $lambda" }
        require(tournament >= 1) { "tournament size must be >= 1, got $tournament" }
        require(crossoverRate in 0.0..1.0) { "crossoverRate must be in 0..1, got $crossoverRate" }
        require(immigrantRate in 0.0..1.0) { "immigrantRate must be in 0..1, got $immigrantRate" }
        require(ops.mutations.isNotEmpty()) { "evolve needs at least one mutation" }

        val s = Session(ops, objective, seed, label, hook)
        val population = ArrayList<Individual<G>>(mu + lambda)
        for (g in seeds.take(mu)) {
            if (!s.running(budget)) break
            population += s.consider(g, "seed")
        }
        while (population.size < mu && s.running(budget)) {
            population += s.consider(ops.random(s.rng), "random")
        }
        // Lexicase over a population that carries no per-case scores is a uniform random draw
        // wearing a selection operator's name. Refuse rather than silently become the control arm.
        if (selection == Selection.LEXICASE) {
            require(population.any { it.evaluation.cases.isNotEmpty() }) {
                "Selection.LEXICASE needs Evaluation.cases; this objective supplies none, and " +
                    "lexicase over empty case vectors is a uniform random draw"
            }
        }
        var leaderFitness = population.maxOfOrNull { it.fitness } ?: Double.NEGATIVE_INFINITY

        while (s.running(budget)) {
            s.generations++
            val children = ArrayList<Individual<G>>(lambda)
            repeat(lambda) {
                if (!s.running(budget)) return@repeat
                val takeImmigrant = immigrantRate > 0.0 && s.rng.nextDouble() < immigrantRate
                if (takeImmigrant) {
                    s.immigrants++
                    children += s.consider(ops.random(s.rng), "immigrant")
                    return@repeat
                }
                val parent = select(population, tournament, selection, s.rng)
                val takeCrossover = crossoverRate > 0.0 && population.size > 1 &&
                    s.rng.nextDouble() < crossoverRate
                val recombinant: G? = if (!takeCrossover) null else {
                    val other = select(population, tournament, selection, s.rng)
                    ops.crossover(parent.genome, other.genome, s.rng)?.also { s.recombined++ }
                }
                val base = recombinant ?: parent.genome
                val prefix = if (recombinant == null) "" else "crossover+"
                if (recombinant != null && !elitistMutation) {
                    children += s.consider(base, "crossover")
                } else {
                    val v = ops.mutations[s.rng.nextInt(ops.mutations.size)]
                    children += s.consider(s.vary(v, base), prefix + v.name)
                }
            }
            population += children
            // Sort descending by fitness. `sortWith` on a list is TimSort and therefore stable, so
            // a child that merely *ties* the incumbent does not displace it — the strict-improvement
            // rule (1+λ) hill climbing has always had, generalised to any µ.
            population.sortWith(compareByDescending { it.fitness })
            if (population.size > mu) {
                val kept = truncate(ops, population, mu)
                population.clear()
                population.addAll(kept)
            }
            val leader = population.firstOrNull()
            if (leader != null && leader.fitness > leaderFitness) {
                leaderFitness = leader.fitness
                s.improvements += Improvement(s.generations, leader.fitness, leader.origin)
            }
            onGeneration?.invoke(s.generations, s.evaluations)
        }
        return s.finish(population.toList())
    }

    /**
     * Novelty search: score a genome by how *unlike* everything already found it is, not by how good.
     *
     * Each candidate's novelty is the mean behavioural distance to its [neighbours] nearest archive
     * members — the canonical k-nearest formulation. An empty archive gives zero novelty, so the
     * first generation is driven purely by [validityWeight], a deliberate and deterministic cold
     * start. The archive grows with every candidate whose novelty clears [archiveThreshold], so the
     * landscape this search climbs is one it is itself flattening.
     *
     * The objective's own [Evaluation.fitness] is **not** used for selection. It is still evaluated
     * and still recorded in [SearchRun.trace], so a novelty run can be read against a fitness run on
     * the same axes; [NoveltyRun.archive] is what the run is actually for.
     *
     * @param validityWeight weight on the objective's fitness, squashed into `0..1` by
     *   [validity], added to novelty. Zero is pure novelty.
     * @param validity maps the objective's fitness onto `0..1`; the default treats any finite
     *   fitness as fully valid and a non-finite one as worthless, which is the minimum a novelty
     *   search needs to avoid filling its archive with genomes that crashed.
     */
    fun <G> novelty(
        ops: GenomeOps<G>,
        objective: Objective<G>,
        budget: Int,
        seed: Long = 1L,
        mu: Int = 8,
        lambda: Int = 16,
        neighbours: Int = 3,
        validityWeight: Double = 0.0,
        archiveThreshold: Double = 0.0,
        validity: (Double) -> Double = { if (it.isFinite()) 1.0 else 0.0 },
        label: String = "novelty"
    ): NoveltyRun<G> {
        require(neighbours >= 1) { "neighbours must be >= 1, got $neighbours" }
        val archive = ArrayList<DoubleArray>()
        val archived = ArrayList<Individual<G>>()
        var descriptorLength = -1
        val wrapped = Objective<G> { genome ->
            val e = objective.evaluate(genome)
            require(e.behaviour.isNotEmpty()) {
                "PopulationSearch.novelty needs Evaluation.behaviour; this objective supplies none"
            }
            if (descriptorLength < 0) descriptorLength = e.behaviour.size
            require(e.behaviour.size == descriptorLength) {
                "behaviour descriptors must all be the same length, got ${e.behaviour.size} " +
                    "after $descriptorLength"
            }
            val n = meanDistanceToNearest(e.behaviour, archive, neighbours)
            Evaluation(n + validityWeight * validity(e.fitness), e.cases, e.behaviour)
        }
        // Novelty search IS [evolve] under a moving objective. It gets the archive-growing hook and
        // nothing else — no second population loop, because a second loop would be a second engine
        // and the two would drift.
        val run = evolveWithHook(
            ops, wrapped, budget, seed, mu, lambda,
            tournament = 2, selection = Selection.TOURNAMENT,
            crossoverRate = 0.0, immigrantRate = 0.0, elitistMutation = true,
            seeds = emptyList(), label = label,
            hook = { individual ->
                val b = individual.evaluation.behaviour
                if (b.isNotEmpty() && meanDistanceToNearest(b, archive, neighbours) >= archiveThreshold) {
                    archive += b
                    archived += individual
                }
            }
        )
        return NoveltyRun(run, archived.toList())
    }

    /** [novelty]'s result: the underlying run plus the behavioural archive it grew. */
    class NoveltyRun<G>(val run: SearchRun<G>, val archive: List<Individual<G>>) {
        /** Mean pairwise behavioural distance across the archive — the spread of the set. */
        val meanPairwiseDistance: Double
            get() {
                if (archive.size < 2) return 0.0
                var sum = 0.0
                var pairs = 0
                for (i in archive.indices) for (j in i + 1 until archive.size) {
                    sum += euclidean(archive[i].evaluation.behaviour, archive[j].evaluation.behaviour)
                    pairs++
                }
                return sum / pairs
            }

        override fun toString(): String =
            "NoveltyRun(${archive.size} archived, spread %.4f)".format(meanPairwiseDistance)
    }

    // ------------------------------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------------------------------

    /**
     * Truncate to µ on **distinct phenotypes** where [GenomeOps.signature] can tell them apart.
     *
     * µ copies of one genome is a population of one that costs µ evaluations a generation, and that
     * is the shape premature convergence takes when mutation is often neutral. Without a signature
     * there is nothing to deduplicate on and this is a plain truncation.
     */
    private fun <G> truncate(ops: GenomeOps<G>, sorted: List<Individual<G>>, mu: Int): List<Individual<G>> {
        val first = sorted.firstOrNull() ?: return emptyList()
        if (ops.signature(first.genome) == null) return sorted.take(mu)
        val kept = LinkedHashMap<String, Individual<G>>()
        val unkeyed = ArrayList<Individual<G>>()
        for (ind in sorted) {
            if (kept.size + unkeyed.size >= mu) break
            val key = ops.signature(ind.genome)
            if (key == null) unkeyed += ind else kept.putIfAbsent(key, ind)
        }
        return (kept.values + unkeyed).take(mu)
    }

    private fun <G> select(
        pool: List<Individual<G>>,
        k: Int,
        how: Selection,
        rng: Random
    ): Individual<G> {
        // A tournament over a population of one has no decision to make. Skipping the draw keeps
        // the mu=1 configuration's random stream identical to a plain (1+lambda) hill climb's.
        if (pool.size == 1) return pool[0]
        return when (how) {
            Selection.TOURNAMENT -> {
                var best = pool[rng.nextInt(pool.size)]
                repeat(k - 1) {
                    val other = pool[rng.nextInt(pool.size)]
                    if (other.fitness > best.fitness) best = other
                }
                best
            }

            Selection.LEXICASE -> {
                var survivors = pool
                val cases = pool.maxOf { it.evaluation.cases.size }
                if (cases == 0) return pool[rng.nextInt(pool.size)]
                for (c in (0 until cases).shuffled(rng)) {
                    if (survivors.size <= 1) break
                    val ceiling = survivors.maxOf { it.evaluation.cases.getOrElse(c) { Double.NEGATIVE_INFINITY } }
                    survivors = survivors.filter {
                        it.evaluation.cases.getOrElse(c) { Double.NEGATIVE_INFINITY } >= ceiling - 1e-12
                    }
                }
                survivors[rng.nextInt(survivors.size)]
            }
        }
    }

    /** Mean distance from [point] to its [k] nearest members of [archive]; 0 for an empty archive. */
    internal fun meanDistanceToNearest(point: DoubleArray, archive: List<DoubleArray>, k: Int): Double {
        if (archive.isEmpty()) return 0.0
        val distances = archive.map { euclidean(point, it) }.sorted()
        val n = minOf(k, distances.size)
        var sum = 0.0
        for (i in 0 until n) sum += distances[i]
        return sum / n
    }

    /** Euclidean distance between two equal-length descriptors. */
    internal fun euclidean(a: DoubleArray, b: DoubleArray): Double {
        require(a.size == b.size) { "descriptors must share a length, got ${a.size} and ${b.size}" }
        var sumSq = 0.0
        for (i in a.indices) {
            val d = a[i] - b[i]
            sumSq += d * d
        }
        return kotlin.math.sqrt(sumSq)
    }

    /**
     * The bookkeeping every driver shares: evaluate, trace, remember the best.
     *
     * Keeping it in one place is what guarantees the four runs are comparable — there is exactly
     * one code path that decides what counts as an evaluation.
     */
    private class Session<G>(
        val ops: GenomeOps<G>,
        val objective: Objective<G>,
        seed: Long,
        val label: String,
        val hook: ((Individual<G>) -> Unit)? = null
    ) {
        val rng = Random(seed)
        val trace = ArrayList<Double>()
        var best: Individual<G>? = null
        var proposals = 0
        var recombined = 0
        var immigrants = 0
        var generations = 0
        val improvements = ArrayList<Improvement>()
        val started = System.nanoTime()
        private val seen = HashMap<String, Evaluation>()

        val evaluations: Int get() = trace.size

        /**
         * Apply a variation, treating a throw as "the variation declined" rather than as a run
         * failure. A structural mutation that cannot apply to a particular genome is ordinary, and
         * the alternative is a search that dies on its first awkward candidate.
         */
        fun vary(v: Variation<G>, g: G): G = runCatching { v.apply(g, rng) }.getOrDefault(g)

        fun consider(genome: G, origin: String): Individual<G> {
            proposals++
            val key = ops.signature(genome)
            val cached = if (key == null) null else seen[key]
            val evaluation = cached
                ?: runCatching { objective.evaluate(genome) }
                    .getOrElse { Evaluation(Double.NEGATIVE_INFINITY) }
                    .also { if (key != null) seen[key] = it }
            val individual = Individual(genome, evaluation, origin)
            val incumbent = best
            if (incumbent == null || individual.fitness > incumbent.fitness) best = individual
            // A cache hit costs no budget: the resource is the evaluator, and it was not called.
            if (cached != null) return individual
            trace += best!!.fitness
            hook?.invoke(individual)
            return individual
        }

        /** Budget left, and the search is still producing genomes the evaluator has not seen. */
        fun running(budget: Int): Boolean =
            evaluations < budget && proposals < budget.toLong() * PROPOSAL_LIMIT

        fun finish(survivors: List<Individual<G>> = emptyList()): SearchRun<G> = SearchRun(
            label = label,
            best = best,
            evaluations = evaluations,
            trace = trace.toDoubleArray(),
            proposals = proposals,
            recombined = recombined,
            immigrants = immigrants,
            generations = generations,
            survivors = survivors,
            millis = (System.nanoTime() - started) / 1e6,
            improvements = improvements.toList()
        )
    }
}
