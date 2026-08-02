package afield.morpho

import kotlin.test.Test

/**
 * Not an assertion — a measurement run, whose printed output is the source of every number on the
 * search half of `public/docs/morpho_examples.md`.
 *
 * ```
 * ./gradlew jvmMeasurements --tests "afield.morpho.MorphoSearchMeasurements" --rerun -i
 * ```
 *
 * Five seeds per driver per problem, because a single seed of a stochastic search is an anecdote
 * and the difference this table is about — evolution against random sampling at equal budget — is
 * exactly the kind of thing one lucky seed invents.
 *
 * Takes roughly ten minutes. It is a sibling of [MorphoMeasurements] and is kept out of the fast
 * path for the same reason.
 */
class MorphoSearchMeasurements {

    private data class Row(
        val problem: String,
        val driver: String,
        val budget: Int,
        val solved: Int,
        val seeds: Int,
        val firstCorrect: List<Int>,
        val bestError: Double,
        val cost: MorphoCost?,
        val failureRate: Double,
        val millis: Double,
    )

    private fun measure(problem: MorphoProblem, budget: Int, seeds: IntRange): List<Row> {
        val drivers: List<Pair<String, (Int) -> MorphoSearchRun>> = listOf(
            "random (control)" to { s: Int -> MorphoSearch.random(problem.spec, problem.generator, budget, s) },
            "hill climb" to { s: Int -> MorphoSearch.hillClimb(problem.spec, problem.generator, budget, s) },
            "evolution" to { s: Int -> MorphoSearch.evolve(problem.spec, problem.generator, budget, s) },
        )
        return drivers.map { (name, run) ->
            val runs = seeds.map(run)
            val best = runs.minWithOrNull(compareBy { it.best?.fitness?.error ?: 1.0 })
            Row(
                problem = problem.name,
                driver = name,
                budget = budget,
                solved = runs.count { it.solved },
                seeds = runs.size,
                firstCorrect = runs.filter { it.solved }.map { it.firstCorrectAt + 1 },
                bestError = runs.minOf { it.best?.fitness?.error ?: 1.0 },
                cost = best?.best?.fitness?.cost,
                failureRate = runs.map { it.failureRate }.average(),
                millis = runs.sumOf { it.millis },
            ).also { row ->
                println(
                    "SEARCH | %-22s | %-16s | %5d | %d/%d | %-12s | %.5f | %-28s | %.1f%% | %.0f ms".format(
                        row.problem, row.driver, row.budget, row.solved, row.seeds,
                        if (row.firstCorrect.isEmpty()) "-" else row.firstCorrect.sorted().joinToString(","),
                        row.bestError, row.cost?.toString() ?: "-", row.failureRate * 100, row.millis
                    )
                )
                // Print the winner, because a search result nobody can read is not a result.
                runs.firstOrNull { it.solved }?.best?.let { b ->
                    println("SEARCH-BEST ${row.problem} / ${row.driver}:\n" + b.genome.phenotype().cells.joinToString("\n") { it.render() })
                }
            }
        }
    }

    /**
     * Why the recursive adder is not found: the recursion is being *tried* and is not paying.
     *
     * Sampled separately from [measure] because it is a statement about the landscape rather than
     * about any driver, and because it is cheap enough to re-check on its own:
     *
     * ```
     * ./gradlew jvmMeasurements --tests "afield.morpho.MorphoSearchMeasurements.recursionDensity" --rerun -i
     * ```
     */
    @Test
    fun recursionDensity() {
        listOf(
            MorphoProblems.guidedRecursiveAdder(),
            MorphoProblems.recursiveAdder(),
            MorphoProblems.orReduce(),
        ).forEach { problem ->
            val rng = kotlin.random.Random(9)
            val evaluator = MorphoEvaluator(problem.spec)
            var recursive = 0
            var bestRecursive = 1.0
            var bestFlat = 1.0
            val n = 4000
            repeat(n) {
                val source = problem.generator.random(rng).render()
                val usesSelf = "= ${MorphoProblems.ROOT}(" in source
                val error = evaluator.evaluate(source, MorphoProblems.ROOT).error
                if (usesSelf) {
                    recursive++
                    if (error < bestRecursive) bestRecursive = error
                } else if (error < bestFlat) bestFlat = error
            }
            println(
                "SEARCH-DENSITY | %-24s | %d of %d random programs recurse (%.1f%%) | best with recursion %.4f | best without %.4f"
                    .format(problem.name, recursive, n, recursive * 100.0 / n, bestRecursive, bestFlat)
            )
        }
    }

    /**
     * The most useful number this file produces: **how many designs that are correct at the widths
     * they were selected on are correct anywhere else.**
     *
     * A MorphoHDL design is a claim about all N, and the whole reason [MorphoSpec] carries a list of
     * widths rather than one is that a search will otherwise satisfy the widths it is shown and no
     * others. This measures how badly. Broken down per width, because "overfits to powers of two"
     * and "overfits to small buses" are different diagnoses with different fixes.
     *
     * ```
     * ./gradlew jvmMeasurements --tests "afield.morpho.MorphoSearchMeasurements.generalisation" --rerun -i
     * ```
     */
    @Test
    fun generalisation() {
        listOf(MorphoProblems.orReduce(), MorphoProblems.andReduce()).forEach { problem ->
            val run = MorphoSearch.evolve(problem.spec, problem.generator, 8000, seed = 2, mu = 24, lambda = 40)
            val designs = run.solutions
            val widths = listOf(2, 3, 4, 5, 6, 7, 8, 9, 12, 16, 32, 48)
            val holds = IntArray(widths.size)
            var everywhere = 0
            designs.forEach { d ->
                var all = true
                widths.forEachIndexed { i, w ->
                    if (MorphoSearch.generalization(d.genome, problem.spec, listOf(w)).correct) holds[i]++
                    else all = false
                }
                if (all) everywhere++
            }
            println(
                "SEARCH-GEN | %-12s | %d distinct designs correct at %s | %d of them correct at every width tried"
                    .format(problem.name, designs.size, problem.spec.widths.joinToString("/"), everywhere)
            )
            widths.forEachIndexed { i, w ->
                println(
                    "SEARCH-GEN |   width %2d: %4d of %4d hold (%.0f%%)%s"
                        .format(w, holds[i], designs.size, holds[i] * 100.0 / designs.size.coerceAtLeast(1),
                            if (w in problem.spec.widths) "   <- selected on" else "")
                )
            }
            // A power-of-two-only family is the obvious suspect, so re-search on one that is not.
            val odd = problem.spec.at(listOf(2, 3, 5))
            val oddRun = MorphoSearch.evolve(odd, problem.generator, 8000, seed = 2, mu = 24, lambda = 40)
            var oddEverywhere = 0
            oddRun.solutions.forEach { d ->
                if (widths.all { w -> MorphoSearch.generalization(d.genome, problem.spec, listOf(w)).correct }) oddEverywhere++
            }
            println(
                "SEARCH-GEN | %-12s | selected on 2/3/5 instead: %d designs, %d correct at every width tried"
                    .format(problem.name, oddRun.solutions.size, oddEverywhere)
            )
        }
    }

    @Test
    fun measure() {
        println("SEARCH | problem | driver | budget | solved | first correct | best err | best cost | invalid | wall")

        measure(MorphoProblems.bitwiseXor(), 2000, 1..5)
        measure(MorphoProblems.fullAdderCell(), 4000, 1..5)
        measure(MorphoProblems.orReduce(), 6000, 1..5)
        measure(MorphoProblems.andReduce(), 6000, 1..5)
        measure(MorphoProblems.recursiveAdder(), 20000, 1..3)
        measure(MorphoProblems.guidedRecursiveAdder(), 20000, 1..3)

        // ------------------------------------------------ what the hand-written designs cost
        val adder = MorphoSpec.adder(widths = listOf(2, 4, 8), name = "adder")
        listOf("ripple_adder", "brent_kung_adder").forEach { design ->
            val f = MorphoSearch.reference(adder, design)
            println("SEARCH-REF | %-18s | %s".format(design, f))
        }

        // ------------------------------------------------ generalisation of what was found
        val or = MorphoProblems.orReduce()
        val run = MorphoSearch.evolve(or.spec, or.generator, 8000, seed = 2, mu = 24, lambda = 40)
        val wider = listOf(3, 5, 16, 32, 48)
        var generalising = 0
        run.solutions.forEach { s ->
            if (wider.all { w -> MorphoSearch.generalization(s.genome, or.spec, listOf(w)).correct }) generalising++
        }
        println(
            "SEARCH-GEN | or-reduce: %d of %d designs correct at %s are also correct at %s"
                .format(generalising, run.solutions.size, or.spec.widths.joinToString("/"), wider.joinToString("/"))
        )
        println("SEARCH-FRONT | or-reduce (gates, depth): " +
            run.front.joinToString(", ") { "(${it.gates}, ${it.depth})" })

        // ------------------------------------------------ throughput
        val t0 = System.nanoTime()
        val warm = MorphoSearch.random(or.spec, or.generator, 3000, seed = 99)
        val ms = (System.nanoTime() - t0) / 1e6
        println("SEARCH-RATE | %.2f ms per compiler invocation (%d invocations, %d proposals)"
            .format(ms / warm.evaluations, warm.evaluations, warm.proposals))
    }
}
