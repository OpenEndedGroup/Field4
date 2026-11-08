import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
A rule whose 40 particles pull themselves into a glider from a random square. The genome is five numbers
(muK, sigmaK, muG, sigmaG, cRep) with wK normalised. A rule scores speed times straightness over two windows
of 1500 steps after 3000 to settle, the worse of seeds 1 and 2, and zero if its creature splits or its spread
changes by more than 2%. Random sampling at the same budget and seed is the control.
*/
val genome = ParticleLeniaRuleGenome()
val objective = particleLeniaGlideObjective(genome, count = 40, seeds = [1L, 2L])

go {
    // three or four minutes of simulation, so it runs off the main loop
    val (runs, checks) = withContext(Dispatchers.Default) {
        val runs = [
            PopulationSearch.random(genome, objective, 300, seed = 5L, label = "random sampling"),
            PopulationSearch.evolve(
                genome,
                objective,
                300,
                seed = 5L,
                mu = 6,
                lambda = 12,
                crossoverRate = 0.3,
                immigrantRate = 0.2,
                label = "evolution",
            ),
        ]
        // each arm's best rule from five seeds the search never saw: two windows of 4000 after 2000 to settle
        val checks = runs.map { run ->
            (3L..7L)
                .toList()
                .parallelStream()
                .map { seed ->
                    val pl = ParticleLenia.random(
                        40,
                        side = 12.0 * Math.sqrt(0.2),
                        seed = seed,
                        params = genome.params(run.best!!.genome),
                    )
                    pl.stepTimes(2000)
                    seed to [pl.measure(4000), pl.measure(4000)]
                }
                .toList()
        }
        runs to checks
    }
    val table = StringBuilder(
        "| | best fitness | muK | sigmaK | muG | sigmaG | cRep |\n| --- | ---: | ---: | ---: | ---: | ---: | ---: |\n",
    )
    for (run in runs) {
        val p = genome.params(run.best!!.genome)
        table.append(
            "| %s | %.4f | %.3f | %.3f | %.3f | %.3f | %.3f |\n"
                .format(run.label, run.bestFitness, p.muK, p.sigmaK, p.muG, p.sigmaG, p.cRep),
        )
    }
    table.append("\n| best of | seed | steps 2000–6000 | steps 6000–10000 | heading drift | spread |\n")
    table.append("| --- | ---: | --- | --- | ---: | ---: |\n")
    for ((run, rows) in runs.zip(checks)) for ((seed, w) in rows) {
        table.append(
            "| %s | %d | %s %.4f | %s %.4f | %.3f | %.2f |\n"
                .format(
                    run.label,
                    seed,
                    w[0].motion.name.lowercase(),
                    w[0].speed,
                    w[1].motion.name.lowercase(),
                    w[1].speed,
                    w[1].headingDrift,
                    w[1].spreadEnd,
                ),
        )
    }
    markdown(table.toString())
    for (run in runs) {
        println(run.summary())
        println("  " + genome.params(run.best!!.genome))
    }
}
