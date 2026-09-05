package afield.stdlib

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Exercises [PopulationSearch] on a genome whose optimum is known in closed form, so "the search
 * worked" means *it arrived at the right answer* rather than "the number went up".
 *
 * The genome is a fixed-length bit string and the objective is OneMax — count the set bits. That
 * choice is deliberate: the optimum is `LENGTH`, the space is `2^LENGTH`, the expected best of *n*
 * independent draws is computable, and every claim below can therefore be stated against a number
 * rather than against a previous run. A search whose only evidence is a rising fitness curve is
 * evidence of nothing; almost any mutation operator produces one.
 *
 * The ALife side of this engine is verified in `afield.examples.stdlib.AlifeEvolveGallery`, which
 * needs actual simulations and runs as a FIELD_TEST. This file is the fast, pure part: budget
 * accounting, determinism, selection, recombination and the guards.
 */
class PopulationSearchTest {

    /** Long enough that random search cannot solve it, short enough to run in milliseconds. */
    private val length = 48

    /**
     * A bit string as a genome. Uniform crossover, a signature, and a single-bit-flip mutation —
     * the smallest possible instance of everything [GenomeOps] can be asked for.
     */
    private inner class BitsOps(val bias: Double = 0.5) : GenomeOps<BooleanArray> {
        override fun random(rng: Random): BooleanArray = BooleanArray(length) { rng.nextDouble() < bias }

        override val mutations: List<Variation<BooleanArray>> = listOf(
            Variation("flip1") { g, rng -> g.copyOf().also { val i = rng.nextInt(length); it[i] = !it[i] } },
            Variation("flip2") { g, rng ->
                g.copyOf().also {
                    repeat(2) { _ -> val i = rng.nextInt(length); it[i] = !it[i] }
                }
            }
        )

        override fun crossover(a: BooleanArray, b: BooleanArray, rng: Random): BooleanArray =
            BooleanArray(length) { if (rng.nextBoolean()) a[it] else b[it] }

        override fun signature(g: BooleanArray): String = g.joinToString("") { if (it) "1" else "0" }
    }

    /** A genome that declares no recombination, by leaving [GenomeOps.crossover] at its default. */
    private inner class NoCrossoverOps : GenomeOps<BooleanArray> {
        override fun random(rng: Random): BooleanArray = BooleanArray(length) { rng.nextBoolean() }
        override val mutations: List<Variation<BooleanArray>> = listOf(
            Variation("flip") { g, rng -> g.copyOf().also { val i = rng.nextInt(length); it[i] = !it[i] } }
        )
    }

    private val oneMax = objectiveOf<BooleanArray> { g -> g.count { it }.toDouble() }

    // ------------------------------------------------------------------------------------------

    /**
     * The premise: OneMax at this length really is beyond random sampling.
     *
     * Asserted first, and asserted with the closed form rather than by observation. Each bit of an
     * unbiased draw is set with probability 1/2, so a single draw has mean `length/2` and standard
     * deviation `sqrt(length)/2`; the best of 600 draws is around three standard deviations up, i.e.
     * roughly `length/2 + 1.5*sqrt(length)` — about 34 of 48, nowhere near 48. If this ever stopped
     * holding, every "evolution beat random" assertion below would still pass while measuring
     * nothing, so it is checked rather than assumed.
     */
    @Test
    fun randomSamplingCannotSolveOneMaxAtThisLength() {
        val budget = 600
        val control = PopulationSearch.random(BitsOps(), oneMax, budget, seed = 1L)
        assertEquals(budget, control.evaluations, "the control must spend its whole budget")
        val mean = length / 2.0
        val sd = kotlin.math.sqrt(length.toDouble()) / 2.0
        assertTrue(
            control.bestFitness < mean + 4.0 * sd,
            "best of $budget unbiased draws was ${control.bestFitness}; the closed form says it should " +
                "sit near ${mean + 1.5 * sd} and cannot plausibly exceed ${mean + 4.0 * sd}"
        )
        assertTrue(
            control.bestFitness > mean,
            "best of $budget draws (${control.bestFitness}) must at least exceed the per-draw mean $mean, " +
                "or the generator is broken and the comparisons below are blank against blank"
        )
    }

    /**
     * Evolution solves it outright at the same budget, and beats the control by a floored margin.
     *
     * The margin is stated against the control's own result rather than as a bare number, and the
     * non-empty guard from the premise test above is repeated here so this test cannot pass on a
     * degenerate objective.
     */
    @Test
    fun evolutionBeatsTheRandomControlAtTheSameBudget() {
        val budget = 600
        val ops = BitsOps()
        val control = PopulationSearch.random(ops, oneMax, budget, seed = 1L)
        val evolved = PopulationSearch.evolve(
            ops, oneMax, budget, seed = 1L, mu = 8, lambda = 16, crossoverRate = 0.3
        )
        assertEquals(control.evaluations, evolved.evaluations, "both arms must spend the same budget")
        assertTrue(control.bestFitness > 0.0, "non-empty guard: the control found something")
        assertTrue(
            evolved.bestFitness >= control.bestFitness + 0.25 * (length - control.bestFitness),
            "evolution reached ${evolved.bestFitness} against the control's ${control.bestFitness}; " +
                "required at least a quarter of the control's remaining ${length - control.bestFitness} " +
                "distance to the optimum $length"
        )
        // Measured: 47 of 48 at 600 evaluations. Not 48, and the shortfall is structural rather
        // than a weak search — with a uniform single-bit flip the last wrong bit is picked with
        // probability 1/48 per mutation, so the final bit costs on the order of the whole budget
        // that got the other 47. Asserted at what it reaches, with the reason, rather than at the
        // optimum with the budget quietly inflated until it passes.
        assertTrue(
            evolved.bestFitness >= length - 1.0,
            "evolution reached ${evolved.bestFitness} of $length at $budget evaluations (measured 47)"
        )
    }

    /** Hill climbing also solves it, and also beats the control — one incumbent, no population. */
    @Test
    fun hillClimbAlsoBeatsTheRandomControl() {
        val budget = 600
        val ops = BitsOps()
        val control = PopulationSearch.random(ops, oneMax, budget, seed = 5L)
        val climbed = PopulationSearch.hillClimb(ops, oneMax, budget, seed = 5L, patience = 200)
        assertEquals(control.evaluations, climbed.evaluations, "same budget")
        assertTrue(
            climbed.bestFitness > control.bestFitness,
            "hill climb ${climbed.bestFitness} vs random ${control.bestFitness}"
        )
    }

    /**
     * The same seed reproduces the whole run, not only its endpoint.
     *
     * The trace and the genome are both compared. Two different genomes that happen to score the
     * same would slip past a scalar comparison, and the thing being claimed reproducible is the
     * search.
     */
    @Test
    fun sameSeedReproducesTheWholeRun() {
        val ops = BitsOps()
        val a = PopulationSearch.evolve(ops, oneMax, 300, seed = 42L, mu = 5, lambda = 10, crossoverRate = 0.4, immigrantRate = 0.1)
        val b = PopulationSearch.evolve(ops, oneMax, 300, seed = 42L, mu = 5, lambda = 10, crossoverRate = 0.4, immigrantRate = 0.1)
        assertTrue(a.trace.contentEquals(b.trace), "the whole ${a.trace.size}-point trace must repeat")
        assertEquals(ops.signature(a.best!!.genome), ops.signature(b.best!!.genome), "same genome")
        assertEquals(a.recombined, b.recombined, "same crossover count")
        assertEquals(a.immigrants, b.immigrants, "same immigrant count")
        assertEquals(a.generations, b.generations, "same generation count")

        val c = PopulationSearch.evolve(ops, oneMax, 300, seed = 43L, mu = 5, lambda = 10, crossoverRate = 0.4, immigrantRate = 0.1)
        assertTrue(
            !a.trace.contentEquals(c.trace),
            "a different seed must produce a different run, or the seed is being ignored"
        )
    }

    /**
     * Budget is counted in evaluations, and a cache hit costs none of it.
     *
     * Both halves matter. Without the first, two drivers are not comparable; without the second, the
     * memoisation `GenomeOps.signature` exists for would be a lie, because the run would still be
     * charged for a candidate the evaluator never saw.
     */
    @Test
    fun budgetIsEvaluationsAndCacheHitsAreFree() {
        var calls = 0
        val counting = Objective<BooleanArray> { g -> calls++; Evaluation(g.count { it }.toDouble()) }
        val ops = BitsOps()
        val run = PopulationSearch.evolve(ops, counting, 250, seed = 2L, mu = 4, lambda = 8)
        assertEquals(250, run.evaluations, "evaluations must equal the budget")
        assertEquals(250, calls, "the objective must be called exactly once per charged evaluation")
        assertTrue(
            run.proposals >= run.evaluations,
            "proposals ${run.proposals} cannot be below evaluations ${run.evaluations}"
        )

        // Force re-treading: a one-bit genome has two phenotypes, so a long run is nearly all cache
        // hits, and the objective must be called far fewer times than the budget.
        var tinyCalls = 0
        val tiny = object : GenomeOps<Boolean> {
            override fun random(rng: Random) = rng.nextBoolean()
            override val mutations = listOf(Variation<Boolean>("flip") { g, _ -> !g })
            override fun signature(g: Boolean) = g.toString()
        }
        val tinyObjective = Objective<Boolean> { g -> tinyCalls++; Evaluation(if (g) 1.0 else 0.0) }
        PopulationSearch.evolve(tiny, tinyObjective, 40, seed = 3L, mu = 1, lambda = 4)
        assertEquals(2, tinyCalls, "a two-phenotype genome must cost exactly two evaluator calls")
    }

    /** A genome that declares no recombination never reports any, whatever `crossoverRate` says. */
    @Test
    fun aGenomeWithNoCrossoverNeverReportsRecombination() {
        val run = PopulationSearch.evolve(
            NoCrossoverOps(), oneMax, 200, seed = 9L, mu = 6, lambda = 12, crossoverRate = 1.0
        )
        assertEquals(0, run.recombined, "the default GenomeOps.crossover returns null and must not be counted")
        assertTrue(run.bestFitness > length / 2.0, "the run still made progress by mutation alone")

        val withCrossover = PopulationSearch.evolve(
            BitsOps(), oneMax, 200, seed = 9L, mu = 6, lambda = 12, crossoverRate = 1.0
        )
        assertTrue(
            withCrossover.recombined > 0,
            "the counterfactual: a genome that DOES recombine reports ${withCrossover.recombined}, so " +
                "the zero above is a property of the genome and not of the counter"
        )
    }

    /**
     * Lexicase over an objective with no case structure is refused rather than silently becoming a
     * uniform random draw.
     */
    @Test
    fun lexicaseWithoutCasesIsRefused() {
        val failure = assertFailsWith<IllegalArgumentException> {
            PopulationSearch.evolve(BitsOps(), oneMax, 100, selection = Selection.LEXICASE)
        }
        assertTrue(
            failure.message!!.contains("cases"),
            "the message should name what is missing, got: ${failure.message}"
        )
        // And it works the moment cases exist.
        val withCases = Objective<BooleanArray> { g ->
            val cases = DoubleArray(4) { block -> g.slice(block * 12 until (block + 1) * 12).count { it }.toDouble() }
            Evaluation(cases.sum(), cases = cases)
        }
        val run = PopulationSearch.evolve(
            BitsOps(), withCases, 400, seed = 4L, mu = 6, lambda = 12, selection = Selection.LEXICASE
        )
        assertTrue(run.bestFitness > length * 0.8, "lexicase run reached ${run.bestFitness} of $length")
    }

    /** Novelty search spreads over the behaviour space instead of converging on the fitness peak. */
    @Test
    fun noveltySearchSpreadsRatherThanConverging() {
        // Behaviour = (fraction of set bits in the first half, in the second half). Fitness is
        // OneMax, so a fitness search runs to one corner and a novelty search should not.
        val describing = Objective<BooleanArray> { g ->
            val first = g.take(length / 2).count { it } / (length / 2.0)
            val second = g.drop(length / 2).count { it } / (length / 2.0)
            Evaluation(g.count { it }.toDouble(), behaviour = doubleArrayOf(first, second))
        }
        val novel = PopulationSearch.novelty(BitsOps(), describing, 400, seed = 6L, mu = 6, lambda = 12)
        assertTrue(novel.archive.size > 10, "the archive grew to ${novel.archive.size} entries")
        assertTrue(
            novel.meanPairwiseDistance > 0.15,
            "novelty spread ${novel.meanPairwiseDistance} over a unit-square behaviour space"
        )

        val converged = PopulationSearch.evolve(BitsOps(), describing, 400, seed = 6L, mu = 6, lambda = 12)
        val best = converged.best!!.evaluation.behaviour
        assertTrue(
            abs(best[0] - 1.0) < 0.2 && abs(best[1] - 1.0) < 0.2,
            "the counterfactual: a *fitness* search ends in the (1, 1) corner at ${best.toList()}, " +
                "which is what novelty search is being asked not to do"
        )
    }

    /**
     * MAP-Elites fills more of the behaviour space, at higher quality per cell, than the same budget
     * of independent draws binned the same way.
     */
    @Test
    fun mapElitesBeatsRandomSamplingOnQualityPerCell() {
        val describing = Objective<BooleanArray> { g ->
            val first = g.take(length / 2).count { it } / (length / 2.0)
            val second = g.drop(length / 2).count { it } / (length / 2.0)
            // Reward evenness within each half, so different cells have genuinely different optima.
            Evaluation(1.0 - abs(first - second), behaviour = doubleArrayOf(first, second))
        }
        val axes = listOf(0.0..1.0, 0.0..1.0)
        val bins = listOf(8, 8)
        val budget = 1200
        val ops = BitsOps()
        val archive = PopulationSearch.mapElites(ops, describing, budget, axes, bins, seed = 8L, initial = 50)

        val controlCells = HashMap<List<Int>, Double>()
        val rng = Random(8L)
        repeat(budget) {
            val e = describing.evaluate(ops.random(rng))
            val cell = axes.indices.map { d ->
                ((e.behaviour[d] - axes[d].start) / (axes[d].endInclusive - axes[d].start) * bins[d])
                    .toInt().coerceIn(0, bins[d] - 1)
            }
            val incumbent = controlCells[cell]
            if (incumbent == null || e.fitness > incumbent) controlCells[cell] = e.fitness
        }

        assertTrue(controlCells.isNotEmpty(), "non-empty guard: the control archive is not blank")
        // Quality per cell, not coverage and not total QD. Measured here: MAP-Elites holds 20 cells
        // against random sampling's 29, and therefore loses on both raw coverage and summed QD.
        // Random sampling covers a descriptor space for free — that is what random means — while
        // MAP-Elites reaches a new cell only when a mutation moves a descriptor across a bin
        // boundary, and a single-bit flip moves this one by 1/24, a twelfth of a bin. See the note on
        // mutation step size in PopulationSearch.mapElites. The claim that survives is the one
        // illumination actually makes: each cell it holds contains a better genome than the luckiest
        // draw that landed in the same cell.
        val controlMean = controlCells.values.sum() / controlCells.size
        val archiveMean = archive.qdScore / archive.elites.size
        assertTrue(
            archiveMean > controlMean,
            "mean elite quality ${archiveMean} over ${archive.elites.size} cells against " +
                "${controlMean} over the random archive's ${controlCells.size}"
        )
        // And head to head on the cells both archives occupy, which removes the coverage difference
        // from the comparison entirely.
        val shared = archive.elites.keys.filter { it in controlCells.keys }
        assertTrue(shared.size >= 8, "the two archives share ${shared.size} cells to compare on")
        val wins = shared.count { archive.elites[it]!!.fitness > controlCells[it]!! }
        val losses = shared.count { archive.elites[it]!!.fitness < controlCells[it]!! }
        assertTrue(
            wins > losses,
            "head to head on ${shared.size} shared cells: MAP-Elites better in $wins, worse in $losses"
        )
        assertEquals(
            0,
            archive.elites.count { (cell, _) -> cell.size != bins.size || cell.indices.any { cell[it] !in 0 until bins[it] } },
            "every cell key must be inside the ${bins} grid"
        )
    }

    /** The guards on the drivers' own arguments, so a nonsense configuration fails loudly. */
    @Test
    fun driversRejectNonsenseConfigurations() {
        val ops = BitsOps()
        assertFailsWith<IllegalArgumentException> { PopulationSearch.evolve(ops, oneMax, 10, mu = 0) }
        assertFailsWith<IllegalArgumentException> { PopulationSearch.evolve(ops, oneMax, 10, lambda = 0) }
        assertFailsWith<IllegalArgumentException> { PopulationSearch.evolve(ops, oneMax, 10, crossoverRate = 1.5) }
        assertFailsWith<IllegalArgumentException> {
            PopulationSearch.mapElites(ops, oneMax, 10, listOf(0.0..1.0), listOf(4, 4))
        }
        assertFailsWith<IllegalArgumentException> {
            PopulationSearch.mapElites(ops, oneMax, 10, listOf(1.0..1.0), listOf(4))
        }
    }

    /**
     * A variation operator that throws is treated as having declined, not as a run failure.
     *
     * Structural mutations that cannot apply to a particular genome are ordinary; the alternative is
     * a search that dies on its first awkward candidate.
     */
    @Test
    fun aThrowingVariationDoesNotKillTheRun() {
        val flaky = object : GenomeOps<BooleanArray> {
            override fun random(rng: Random) = BooleanArray(length) { rng.nextBoolean() }
            override val mutations = listOf(
                Variation<BooleanArray>("throws") { _, _ -> error("this operator never applies") },
                Variation<BooleanArray>("flip") { g, rng ->
                    g.copyOf().also { val i = rng.nextInt(length); it[i] = !it[i] }
                }
            )
        }
        val run = PopulationSearch.evolve(flaky, oneMax, 400, seed = 11L, mu = 4, lambda = 8)
        assertEquals(400, run.evaluations, "the run completed its budget despite half its operators throwing")
        assertTrue(run.bestFitness > length * 0.7, "and still made progress: ${run.bestFitness} of $length")
    }
}
