// Illuminate the rule space instead of optimising it.
//
// MAP-Elites keeps the best rule found in each cell of a grid over the BEHAVIOUR space -- here
// (density, activity), the same two numbers caEdgeOfChaosObjective already reports as its
// Evaluation.behaviour. What comes out is not one answer but a map of "the best rule that behaves
// like THIS", which is the contact sheet below.
//
// This is the figure worth looking at. Top left is sparse and nearly dead; bottom right is dense
// and noisy; and somewhere in the middle band are the rules that hold structure while still moving.

val ops = LifeRuleGenome(radius = 2)
val soups = listOf(randomSoup(48, 48, density = 0.35, seed = 303L))
val objective = caEdgeOfChaosObjective(soups, warmup = 20, scored = 25)
val bins = listOf(6, 6)
val axes = listOf(0.0..1.0, 0.0..0.6)
val budget = 600

val archive = PopulationSearch.mapElites(ops, objective, budget, axes, bins, seed = 31L, initial = 40)
println(archive)
println("off-grid (clamped to an edge cell): ${archive.offGridCandidates} of $budget")
println()

// The control arm for a quality-diversity claim is the same budget of independent draws, binned
// identically and reduced to the best genome per cell. Comparing against a single best random rule
// would be the wrong control, because MAP-Elites is not trying to find one good rule.
val controlCells = HashMap<List<Int>, Double>()
val rng = kotlin.random.Random(31L)
repeat(budget) {
    val e = objective.evaluate(ops.random(rng))
    val cell = axes.indices.map { d ->
        (((e.behaviour[d] - axes[d].start) / (axes[d].endInclusive - axes[d].start)) * bins[d])
            .toInt().coerceIn(0, bins[d] - 1)
    }
    val incumbent = controlCells[cell]
    if (incumbent == null || e.fitness > incumbent) controlCells[cell] = e.fitness
}
val controlQd = controlCells.values.sum()
println("random-sampled archive: ${controlCells.size}/${archive.cells} cells, QD %.3f".format(controlQd))
println("illuminated archive:    ${archive.elites.size}/${archive.cells} cells, QD %.3f".format(archive.qdScore))
println()
println("Quality, not coverage, is the claim. Random sampling spreads over a descriptor space for")
println("free -- that is what random means -- while MAP-Elites reaches a new cell only when a")
println("mutation moves a descriptor across a bin boundary. What illumination buys is the quality")
println("held in each cell it does occupy.")

val panels = archive.elites.entries
    .sortedWith(compareBy({ it.key[0] }, { it.key[1] }))
    .map { (cell, elite) ->
        "%d%d %.2f".format(cell[0], cell[1], elite.fitness) to
            caRun(soups[0], elite.genome, 20, 25).finalGrid.toImg(2).upscaleNearest(3)
    }
showImg(
    montage(panels, columns = 6, cellSide = 150),
    caption = "one panel per occupied cell; the label is (density bin)(activity bin) and the score"
)
