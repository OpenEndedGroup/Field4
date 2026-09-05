// Field could always MEASURE artificial life. It could not search it.
//
// alife.kt is 816 lines of morphology statistics -- polarization, blobShapes,
// correlationLength, massRadiusDimension, stateEntropyBits, centroidDrift -- and until now not one
// of them was ever used as a fitness. This box uses one.
//
// The question: is there a Lenia rule whose seed stays alive, stays in ONE piece, and TRAVELS?
// That is what a soliton is, it is the one thing continuous Lenia is famous for, and every term of
// the score below is read straight off leniaRun, which already classifies a run into
// DEATH / BOUNDED / SATURATION and already reports a wrapped centroid drift.

val ops = LeniaGenome(radius = 6, dt = 0.1, shells = 3)
val objective = leniaSolitonObjective(width = 48, height = 48, steps = 90, initialSeed = 5L, driftScale = 8.0)
val budget = 220

// PopulationSearch.random is a CONTROL ARM, not a fourth driver. It draws from exactly the
// distribution `evolve` mutates within, at the same budget, under the same seed. Without it a
// result here would say nothing at all: a generator good enough to stumble onto a decent rule
// occasionally looks exactly like a successful search.
val control = PopulationSearch.random(ops, objective, budget, seed = 13L, label = "random")
val evolved = PopulationSearch.evolve(
    ops, objective, budget, seed = 13L,
    mu = 6, lambda = 12, crossoverRate = 0.3, immigrantRate = 0.08, label = "evolve"
)

println(control.summary())
println(evolved.summary())
println()
println("margin %.4f  (%.1f%% above the control)".format(
    evolved.bestFitness - control.bestFitness,
    100.0 * (evolved.bestFitness / control.bestFitness - 1.0)
))
println()

val best = evolved.best!!.genome
val run = leniaRun(leniaSeed(48, 48, best, seed = 5L), best, steps = 90, sampleEvery = 8)
println("evolved rule: mu=%.4f sigma=%.4f".format(best.mu, best.sigma))
println("  $run")
println("  phase is MEASURED, not asserted: leniaRun classifies it from the mean field value.")
println()
println("`improvements` carries the genome of every generation whose leader improved. That is a")
println("chain of RECORD HOLDERS, not a parent-child lineage -- with mu=6 and crossover on, the")
println("odds that these six are literally descended from one another are near zero.")

// The lineage, left to right. The control arm's best is the first panel: that is the thing
// evolution has to beat, in the same picture, at the same budget.
val panels = mutableListOf<Pair<String, Img>>()
val cRule = control.best!!.genome
panels += "rnd %.2f".format(control.bestFitness) to
    leniaRun(leniaSeed(48, 48, cRule, seed = 5L), cRule, steps = 90, sampleEvery = 8).field.upscaleNearest(3)
val picks = evolved.improvements.indices.toList().let { idx ->
    if (idx.size <= 5) idx else (1..5).map { (it * idx.size / 5) - 1 }
}
for (i in picks) {
    val imp = evolved.improvements[i]
    panels += "g%d %.2f".format(imp.generation, imp.fitness) to
        leniaRun(leniaSeed(48, 48, imp.genome, seed = 5L), imp.genome, steps = 90, sampleEvery = 8)
            .field.upscaleNearest(3)
}

// A FIXED 0..1 display range. A saturated Lenia field is constant at 1, and an auto-ranged colour
// map draws a constant field black -- i.e. identical to death. This is the one family where the
// range policy changes the answer rather than tidying it.
showImg(
    montage(panels, columns = 3, cellSide = 200, displayRange = 0f..1f),
    caption = "left to right: the random control's best, then each successive record holder"
)
