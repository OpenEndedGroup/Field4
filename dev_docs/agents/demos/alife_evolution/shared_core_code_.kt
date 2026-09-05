// The engine is SHARED, and that is the point of the whole exercise.
//
// Field had two population searches before tonight and they shared no code: afield.morpho's
// (GraalJS, not stdlib) and FunctionEvolve's, over Fn graphs. Neither was parametric.
//
// FunctionEvolve.evolve no longer contains a search. Its body is
// PopulationSearch.evolve(mu = 1, lambda = lambda, crossoverRate = 0, immigrantRate = 0) over an
// FnGenomeOps -- the same engine the three ALife boxes run on. The random stream is IDENTICAL to
// the hand-rolled version's, because the core skips the parent draw when the population holds one
// individual and skips the crossover and immigrant draws when their rates are exactly zero. Each
// of those is right on its own terms; together they make mu=1 a plain (1+lambda) climb.
//
// The proof is the FIELD_TEST functions.evolve.worked-example, whose committed numbers were
// captured BEFORE the refactor and reproduce bit for bit after it:
//
//     initialScore=-0.8420503456220446   bestScore=-0.15676727177844857
//     acceptedMutations=12  evaluations=961
//
// Below is that same search, running here.

val target = with(Functions) { sin(frequency = 1.0, amplitude = 0.5) }
val seed = with(Functions) { cos(frequency = 1.0, amplitude = 1.0) }

val run = evolve(
    seed = seed,
    iterations = 120,
    lambda = 8,
    rngSeed = 3L,
    fitness = matchFitness(target, samples = 160, complexityWeight = 0.01)
)

println("initialScore=${run.initialScore}")
println("bestScore=${run.bestScore}")
println("acceptedMutations=${run.steps.size}  evaluations=${run.evaluations}")
println()
println("the accepted improvements, which are now read off the generic SearchRun.improvements:")
run.steps.forEach { println("  gen %3d  %-14s %.6f".format(it.iteration, it.mutation, it.score)) }
println()
println("evolved: ${run.best}")
println()
println("One core, four genomes: an Fn graph here, and a LeniaRule, a life-like rule table and an")
println("L-system grammar in alife_evolve.kt. A fifth costs a GenomeOps and an Objective.")

// The seed, the target and what the search found, on one time base.
val xs = (0..255).map { -1.0 + 2.0 * it / 255.0 }
showSignals(
    "target" to xs.map { target.evalSafe(it, outside = 0.0) },
    "seed" to xs.map { seed.evalSafe(it, outside = 0.0) },
    "evolved" to xs.map { run.best.evalSafe(it, outside = 0.0) }
)
