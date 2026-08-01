package afield.morpho

import kotlin.random.Random

/**
 * Searching the space of MorphoHDL programs for one that computes something.
 *
 * Three drivers, one evaluator, one generator. That is the arrangement the whole file exists for:
 * [MorphoSearch.random] is a **control arm** drawing from exactly the distribution
 * [MorphoSearch.hillClimb] and [MorphoSearch.evolve] mutate within, so a difference between their
 * results is a difference in *selection*. Reporting an evolutionary result without the control next
 * to it would say nothing at all — a generator good enough to make valid adders occasionally would
 * look like a successful search.
 *
 * Every candidate is scored by compiling it with upstream's compiler and running the netlist that
 * comes out. See [MorphoEvaluator].
 */

/** A program and what it scored. */
class MorphoIndividual(val genome: MorphoGenome, val fitness: MorphoFitness) {
    val gates: Int get() = fitness.cost?.gates ?: Int.MAX_VALUE
    val depth: Int get() = fitness.cost?.depth ?: Int.MAX_VALUE

    override fun toString(): String = "${genome.rootName}: $fitness"

    companion object {
        /**
         * Correctness first, then size, then depth, then brevity.
         *
         * Lexicographic rather than a weighted sum on purpose. A weighted sum has to answer "how
         * many wrong bits is a gate worth", and there is no answer: a circuit that is wrong is not
         * a cheap circuit, it is not a circuit. Weights would also silently set the crossover
         * point at which the search prefers to break a working design, which is precisely the
         * failure mode a Pareto front exists to avoid.
         */
        val BY_QUALITY: Comparator<MorphoIndividual> = Comparator { a, b ->
            when {
                a.fitness.valid != b.fitness.valid -> if (a.fitness.valid) -1 else 1
                a.fitness.correct != b.fitness.correct -> if (a.fitness.correct) -1 else 1
                a.fitness.error != b.fitness.error -> a.fitness.error.compareTo(b.fitness.error)
                a.gates != b.gates -> a.gates.compareTo(b.gates)
                a.depth != b.depth -> a.depth.compareTo(b.depth)
                else -> a.genome.statementCount.compareTo(b.genome.statementCount)
            }
        }
    }
}

/**
 * What one search run did.
 *
 * [trace] is the number that makes two runs comparable: best-so-far error after each *evaluation*,
 * not after each generation, because a population of 24 and a hill climb of 1 do very different
 * amounts of work per generation and comparing them per generation would be a comparison of budgets
 * rather than of methods.
 */
class MorphoSearchRun(
    val label: String,
    val spec: MorphoSpec,
    val evaluations: Int,
    val failures: Int,
    val millis: Double,
    val best: MorphoIndividual?,
    val trace: DoubleArray,
    /** Best-so-far gate count among correct candidates; `-1` at evaluations where none had been found. */
    val gateTrace: IntArray,
    /** Evaluation index at which the first fully correct design appeared, or -1. */
    val firstCorrectAt: Int,
    /** Non-dominated correct designs on (gates, depth). */
    val front: List<MorphoIndividual>,
    /** Every distinct correct program found, capped. The population a generalisation test is run over. */
    val solutions: List<MorphoIndividual> = emptyList(),
    /** The final population (or the climber's last point), for seeding the next stage of a curriculum. */
    val survivors: List<MorphoIndividual> = emptyList(),
    /** Programs proposed, including ones already evaluated. Well above [evaluations] means re-treading. */
    val proposals: Int = evaluations,
) {
    /** Fraction of candidates the compiler, the simulator or the cell budget rejected. */
    val failureRate: Double get() = if (evaluations == 0) 0.0 else failures.toDouble() / evaluations

    val solved: Boolean get() = firstCorrectAt >= 0

    fun summary(): String = buildString {
        append("%-22s %5d evals".format(label, evaluations))
        append("  %5.1f s".format(millis / 1000.0))
        append("  invalid %4.1f%%".format(failureRate * 100))
        append("  proposals %6d".format(proposals))
        append(if (solved) "  SOLVED at eval %d".format(firstCorrectAt + 1) else "  unsolved")
        append("  best err %.5f".format(best?.fitness?.error ?: 1.0))
        best?.fitness?.cost?.let { append("  (%s)".format(it)) }
        if (front.isNotEmpty()) append("  front %d".format(front.size))
    }
}

/**
 * How [MorphoSearch.evolve] picks a parent.
 *
 * Not a knob for its own sake — the two options select on genuinely different things, and on this
 * problem the difference is the whole result.
 */
enum class MorphoSelection {
    /** Best of *k* by [MorphoIndividual.BY_QUALITY], i.e. by aggregate error. */
    TOURNAMENT,

    /**
     * Lexicase: shuffle the test cases, keep only the individuals that are best on the first, then
     * best on the second, until one is left.
     *
     * The point is that it never averages. An adder's cases are per-bit (`MorphoSpec.caseCount`),
     * and bit 7 of an eight-bit sum is only obtainable by propagating a carry seven times — so the
     * one individual in the population that has started to recurse is *uniquely* good on that case
     * and hopeless on aggregate. Tournament selection cannot see it; lexicase selects it whenever
     * that case comes up first, which is one shuffle in `caseCount`.
     */
    LEXICASE,
}

/** Search drivers. All three take the same budget and return the same shape of result. */
object MorphoSearch {

    /**
     * Draw [budget] independent programs and keep the best.
     *
     * The control. Anything the other two drivers claim has to be claimed *against this*, at the
     * same budget, from the same generator and with the same seed policy.
     */
    fun random(
        spec: MorphoSpec,
        generator: MorphoProgramGenerator,
        budget: Int,
        seed: Int = 1,
        optimize: Boolean = true,
        label: String = "random search",
    ): MorphoSearchRun {
        val session = Session(spec, generator, seed, optimize, label)
        while (session.running(budget)) session.consider(generator.random(session.rng))
        return session.finish()
    }

    /**
     * Accept any mutation that is not worse, restart when stuck.
     *
     * "Not worse" rather than "better" is not a detail. Program spaces are full of neutral changes
     * — rewiring a dead statement, swapping two commutative operands — and a climber that refuses
     * them sits on the first local optimum it finds. Neutral drift is how it walks along a plateau
     * to somewhere a single mutation can improve.
     *
     * @param patience non-improving steps before abandoning the current point for a fresh random one.
     */
    fun hillClimb(
        spec: MorphoSpec,
        generator: MorphoProgramGenerator,
        budget: Int,
        seed: Int = 1,
        patience: Int = 80,
        optimize: Boolean = true,
        seeds: List<MorphoGenome> = emptyList(),
        label: String = "hill climb",
    ): MorphoSearchRun {
        val session = Session(spec, generator, seed, optimize, label)
        var current = session.consider(seeds.firstOrNull() ?: generator.random(session.rng))
        var sinceImprovement = 0

        while (session.running(budget)) {
            val candidate = session.consider(generator.mutate(current.genome, session.rng))
            val order = MorphoIndividual.BY_QUALITY.compare(candidate, current)
            if (order < 0) sinceImprovement = 0 else sinceImprovement++
            if (order <= 0) current = candidate
            if (sinceImprovement >= patience && session.running(budget)) {
                current = session.consider(generator.random(session.rng))
                sinceImprovement = 0
            }
        }
        return session.finish(listOf(current))
    }

    /**
     * A (μ+λ) population with tournament selection and whole-cell crossover.
     *
     * The elitist form: parents survive, so the best design found can never be lost to a bad
     * generation. λ children are produced per generation and the whole μ+λ pool is truncated back
     * to μ.
     *
     * @param seeds programs to start from — how a curriculum stage inherits the previous stage's work.
     */
    fun evolve(
        spec: MorphoSpec,
        generator: MorphoProgramGenerator,
        budget: Int,
        seed: Int = 1,
        mu: Int = 12,
        lambda: Int = 24,
        tournament: Int = 2,
        selection: MorphoSelection = MorphoSelection.LEXICASE,
        crossoverRate: Double = 0.25,
        immigrantRate: Double = 0.12,
        optimize: Boolean = true,
        seeds: List<MorphoGenome> = emptyList(),
        label: String = "evolution",
    ): MorphoSearchRun {
        require(mu >= 2 && lambda >= 1) { "evolve needs mu >= 2 and lambda >= 1" }
        val session = Session(spec, generator, seed, optimize, label)
        val population = ArrayList<MorphoIndividual>(mu + lambda)
        seeds.take(mu).forEach { population += session.consider(it) }
        while (population.size < mu && session.running(budget)) {
            population += session.consider(generator.random(session.rng))
        }

        while (session.running(budget)) {
            val children = ArrayList<MorphoIndividual>(lambda)
            repeat(lambda) {
                if (!session.running(budget)) return@repeat
                val parent = select(population, tournament, selection, session.rng)
                val genome = when {
                    // Immigrants. Without them an elitist truncation on a deceptive landscape
                    // converges on `return a, ZERO` — 0 gates, 37.5% wrong, and unimprovable by one
                    // mutation — within about eighty evaluations, and then re-evaluates it forever.
                    session.rng.nextDouble() < immigrantRate -> generator.random(session.rng)
                    session.rng.nextDouble() < crossoverRate && population.size > 1 ->
                        generator.mutate(
                            generator.crossover(parent.genome, select(population, tournament, selection, session.rng).genome, session.rng),
                            session.rng
                        )
                    else -> generator.mutate(parent.genome, session.rng, 1 + session.rng.nextInt(2))
                }
                children += session.consider(genome)
            }
            population += children
            population.sortWith(MorphoIndividual.BY_QUALITY)
            // Truncate on *distinct phenotypes*. Twelve copies of the same program is a population
            // of one that costs twelve evaluations a generation, which is the shape premature
            // convergence takes when the genotype is a program and mutation is often neutral.
            val kept = LinkedHashMap<String, MorphoIndividual>()
            for (ind in population) {
                if (kept.size >= mu) break
                kept.putIfAbsent(ind.genome.render(), ind)
            }
            population.clear()
            population.addAll(kept.values)
        }
        return session.finish(population.toList())
    }

    /**
     * Score a hand-written design under the same spec, so a search result has something to be
     * measured against.
     *
     * This is how `ripple_adder` and `brent_kung_adder` get onto the same Pareto axes as the
     * evolved candidates: not by quoting their published gate counts but by running them through
     * the identical evaluator.
     */
    fun reference(
        spec: MorphoSpec,
        design: String,
        source: String = MorphoRuntime.standardLibrary,
        optimize: Boolean = true,
    ): MorphoFitness = MorphoEvaluator(spec, optimize).evaluate(source, design)

    /**
     * A **width curriculum**: search at two bits, then carry the survivors up to four, then eight.
     *
     * The motivation is structural rather than a training trick. At two bits a MorphoHDL recursion
     * unrolls exactly one level — `SPLIT` yields single wires, so the self-calls immediately hit
     * their base case — which makes the two-bit problem small enough to solve and yet, if the design
     * really is recursive, *the same program* as the sixteen-bit one. Whether that "if" holds is the
     * question [generalization] exists to ask, and the answer is not automatically yes.
     *
     * @return one run per stage, in order. Each stage's population seeds the next.
     */
    fun staged(
        spec: MorphoSpec,
        generator: MorphoProgramGenerator,
        budgetPerStage: Int,
        seed: Int = 1,
        mu: Int = 12,
        lambda: Int = 24,
        optimize: Boolean = true,
    ): List<MorphoSearchRun> {
        val runs = ArrayList<MorphoSearchRun>(spec.widths.size)
        var seeds = emptyList<MorphoGenome>()
        for (stage in spec.widths.indices) {
            val widths = spec.widths.take(stage + 1)
            val run = evolve(
                spec.at(widths), generator, budgetPerStage, seed + stage * 977, mu, lambda,
                optimize = optimize, seeds = seeds,
                label = "stage ${stage + 1} @ ${widths.joinToString("/")}",
            )
            runs += run
            seeds = run.survivors.map { it.genome }
        }
        return runs
    }

    /**
     * Re-score a program at widths it was never selected on.
     *
     * The honest test of a parametric design, and the reason [MorphoSpec.widths] is a list. A cell
     * that adds at two bits may be a divide-and-conquer adder or may be two bits of hand-unrolled
     * logic that happens to fit; only a wider signature can tell them apart, and it is the *same
     * source text* compiled at a new signature, so there is nothing to translate and nothing to get
     * wrong.
     */
    fun generalization(
        genome: MorphoGenome,
        spec: MorphoSpec,
        widths: List<Int>,
        optimize: Boolean = true,
    ): MorphoFitness = MorphoEvaluator(spec.at(widths), optimize).evaluate(genome.render(), genome.rootName)

    /** How many distinct correct programs one run will remember. */
    const val SOLUTION_CAP = 400

    /** Proposals a driver may make per unit of budget before it is judged to be stuck. */
    const val PROPOSAL_LIMIT = 200

    private fun select(
        pool: List<MorphoIndividual>,
        k: Int,
        how: MorphoSelection,
        rng: Random,
    ): MorphoIndividual = when (how) {
        MorphoSelection.TOURNAMENT -> {
            var best = pool[rng.nextInt(pool.size)]
            repeat(k - 1) {
                val other = pool[rng.nextInt(pool.size)]
                if (MorphoIndividual.BY_QUALITY.compare(other, best) < 0) best = other
            }
            best
        }

        MorphoSelection.LEXICASE -> {
            var survivors = pool
            val cases = pool.maxOf { it.fitness.cases.size }
            val order = (0 until cases).shuffled(rng)
            for (c in order) {
                if (survivors.size <= 1) break
                val floor = survivors.minOf { it.fitness.cases.getOrElse(c) { 1.0 } }
                survivors = survivors.filter { it.fitness.cases.getOrElse(c) { 1.0 } <= floor + 1e-12 }
            }
            survivors[rng.nextInt(survivors.size)]
        }
    }

    /**
     * The bookkeeping every driver shares: evaluate, trace, archive.
     *
     * Keeping it in one place is what guarantees the three runs are actually comparable — there is
     * exactly one code path that decides what counts as an evaluation.
     */
    private class Session(
        val spec: MorphoSpec,
        val generator: MorphoProgramGenerator,
        seed: Int,
        optimize: Boolean,
        val label: String,
    ) {
        val rng = Random(seed)
        val evaluator = MorphoEvaluator(spec, optimize)
        val trace = ArrayList<Double>()
        val gates = ArrayList<Int>()
        val front = ArrayList<MorphoIndividual>()
        /** Distinct correct programs, capped: a curriculum's whole point is to produce several. */
        val solutions = LinkedHashMap<String, MorphoIndividual>()
        var best: MorphoIndividual? = null
        var firstCorrectAt = -1
        val started = System.nanoTime()
        val evaluations: Int get() = trace.size

        /**
         * Memoised: mutation and repair produce the same normalised program often enough to matter.
         *
         * A cache hit costs no budget — see [consider], which returns before touching [trace], and
         * the budget rationale on it.
         */
        private val seen = HashMap<String, MorphoFitness>()
        var invalid = 0
            private set
        var considered = 0
            private set

        /**
         * Budget is spent on *distinct* programs, and a re-proposal is free.
         *
         * The unit has to be the compiler invocation, because that is the resource and it is the
         * only thing the three drivers spend at comparable rates. Charging for re-proposals instead
         * would have measured how repetitive each driver is: hill climbing re-treads constantly (it
         * mutates one point) and random sampling almost never does, so a budget of "proposals" hands
         * the control arm three times the real work and calls it a fair fight.
         *
         * [considered] is kept so a driver can bail out of a search that has stopped producing
         * anything new rather than spinning.
         */
        fun consider(genome: MorphoGenome): MorphoIndividual {
            considered++
            val source = genome.render()
            val cached = seen[source]
            val fitness = cached ?: evaluator.evaluate(source, genome.rootName).also { seen[source] = it }
            val individual = MorphoIndividual(genome, fitness)
            if (best == null || MorphoIndividual.BY_QUALITY.compare(individual, best!!) < 0) best = individual
            if (cached != null) return individual

            if (!fitness.valid) invalid++
            if (fitness.correct) {
                if (firstCorrectAt < 0) firstCorrectAt = trace.size
                offer(individual)
                if (solutions.size < SOLUTION_CAP) solutions.putIfAbsent(source, individual)
            }
            trace += best!!.fitness.error
            gates += front.minOfOrNull { it.gates } ?: -1
            return individual
        }

        /**
         * True while there is budget left and the search is still producing new programs.
         *
         * The second clause is a stop, not a budget: a mutation operator that keeps landing on the
         * same phenotype would otherwise spin forever. [MorphoSearchRun.proposals] reports when it
         * bound, and it usually does for hill climbing, which re-treads about twenty-five times per
         * new program once it has settled onto a plateau.
         */
        fun running(budget: Int): Boolean = evaluations < budget && considered < budget * PROPOSAL_LIMIT

        /** Keep [front] non-dominated on (gates, depth). */
        private fun offer(candidate: MorphoIndividual) {
            if (front.any { it.gates <= candidate.gates && it.depth <= candidate.depth }) return
            front.removeAll { candidate.gates <= it.gates && candidate.depth <= it.depth }
            front += candidate
            front.sortBy { it.depth }
        }

        fun finish(survivors: List<MorphoIndividual> = emptyList()): MorphoSearchRun = MorphoSearchRun(
            label = label,
            spec = spec,
            evaluations = evaluations,
            failures = invalid,
            millis = (System.nanoTime() - started) / 1e6,
            best = best,
            trace = trace.toDoubleArray(),
            gateTrace = gates.toIntArray(),
            firstCorrectAt = firstCorrectAt,
            front = front.sortedBy { it.depth },
            solutions = solutions.values.toList(),
            survivors = survivors,
            proposals = considered,
        )
    }
}
