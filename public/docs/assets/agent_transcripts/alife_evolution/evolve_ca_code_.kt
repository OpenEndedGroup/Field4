// The same engine, a completely different genome.
//
// A life-like rule is a TABLE: which neighbour counts cause a birth, which cause survival. At a
// radius-2 neighbourhood that is 24 birth bits and 25 survival bits, a fixed-length bit string, and
// therefore a genome two parents can be recombined across. Field had exactly one other two-parent
// crossover before this (afield.morpho.MorphoGenerator); LifeRuleGenome.crossover is uniform
// crossover over the rule table and it is real recombination, not a graft.
//
// Radius 2 rather than the classic Moore 8 is a deliberate choice and it is the most useful thing
// in this box. At radius 1 the space is 2^17 rules, and a few hundred INDEPENDENT draws land close
// to the optimum on their own -- the first version of this demo measured evolution winning by 3%
// for exactly that reason. A control arm that nearly wins is telling you the problem is easy, not
// that the search is good. At radius 2 the space is 2^49 and the margin is 27%.

val ops = LifeRuleGenome(radius = 2)
val soups = listOf(
    randomSoup(48, 48, density = 0.35, seed = 101L),
    randomSoup(48, 48, density = 0.12, seed = 202L)
)

// "Alive but not saturated, and still changing" -- activity times a Gaussian band on density.
// Each factor alone is trivially gamed. A rule that fills the grid and stays filled scores 1 on
// density and 0 on activity; a rule that flickers every cell scores ~1 on activity and ~0 on the
// band; an extinct rule scores the floor. Only the product is large for a rule that keeps a
// moderate population AND keeps rearranging it, which is what everybody means by an interesting
// rule and what nobody can state by looking.
val objective = caEdgeOfChaosObjective(soups, warmup = 20, scored = 30)
val budget = 400

val control = PopulationSearch.random(ops, objective, budget, seed = 7L, label = "random")

// LEXICASE, because this objective genuinely has a case structure: one case per soup. A rule that
// holds together on one initial condition and collapses on the other is exactly what a
// tournament's average hides, and lexicase never averages.
val evolved = PopulationSearch.evolve(
    ops, objective, budget, seed = 7L,
    mu = 8, lambda = 16, selection = Selection.LEXICASE,
    crossoverRate = 0.3, immigrantRate = 0.1, label = "evolve"
)

println(control.summary())
println(evolved.summary())
println()
println("margin %.4f  (%.1f%% above the control)".format(
    evolved.bestFitness - control.bestFitness,
    100.0 * (evolved.bestFitness / control.bestFitness - 1.0)
))
println("recombined ${evolved.recombined} children by uniform crossover over the rule table")
println()
println("evolved rule ${lifeRuleSignature(evolved.best!!.genome)}")
println("control rule ${lifeRuleSignature(control.best!!.genome)}")
println()
println("Try Selection.TOURNAMENT above and compare. Try LifeRuleGenome() with no radius argument")
println("and watch the margin collapse -- that is the easy problem, and it is worth seeing.")

val panels = mutableListOf<Pair<String, Img>>()
panels += "rnd %.3f".format(control.bestFitness) to
    caRun(soups[0], control.best!!.genome, 20, 30).finalGrid.toImg(2).upscaleNearest(3)
val picks = evolved.improvements.indices.toList().let { idx ->
    if (idx.size <= 5) idx else (1..5).map { (it * idx.size / 5) - 1 }
}
for (i in picks) {
    val imp = evolved.improvements[i]
    panels += "g%d %.3f".format(imp.generation, imp.fitness) to
        caRun(soups[0], imp.genome, 20, 30).finalGrid.toImg(2).upscaleNearest(3)
}
showImg(
    montage(panels, columns = 3, cellSide = 200),
    caption = "the random control's best, then each successive record holder, all on the same soup"
)
