import afield.morpho.*

// y = (x != 0). The reason this is the interesting target and bitwise XOR is not: morpho
// sizes a LUT cell to its widest operand and broadcasts the narrow ones, so there is no
// gate and no combination of gates that turns eight wires into one. The only way down is
// SPLIT, which means every correct answer is divide-and-conquer by construction.
val problem = MorphoProblems.orReduce()
println(problem)

val runs = listOf(
    MorphoSearch.random(problem.spec, problem.generator, 6000, seed = 2),
    MorphoSearch.evolve(problem.spec, problem.generator, 6000, seed = 2, mu = 24, lambda = 40),
)
runs.forEach { println(it.summary()) }

// Cheapest correct design across both runs; if a seed happens not to solve it, fall back
// to the closest thing found rather than throwing -- this is a demo, not an assertion.
val correct = runs.mapNotNull { it.best }.filter { it.fitness.correct }
val best = correct.minByOrNull { it.gates } ?: runs.mapNotNull { it.best }.first()
println()
println(if (correct.isEmpty()) "(nothing exactly correct this run; showing the best found)" else "")
println(best.genome.phenotype().cells[0].render())

// Selected at 2, 3 and 5 -- an ODD family on purpose. SPLIT gives equal halves exactly
// when the width is even, so a family of powers of two never shows the search a cell
// whose two halves differ, and it will happily return a design that is silently wrong at
// 5, 7 and 9. Re-compiled here at widths nothing was selected on: same source text, new
// signature, nothing to translate and nothing to get wrong.
listOf(7, 9, 16, 32, 48).forEach { w ->
    val f = MorphoSearch.generalization(best.genome, problem.spec, listOf(w))
    println("  at %2d bits: %s".format(w, f))
}

// The Pareto front over every correct design the two runs produced. It is small and it is
// not empty: a reduction can be a shallow wide tree or a deep cheap one, and the search
// finds both ends. The staircase is drawn as a step function on purpose -- there is no
// design at a fractional gate count, and a smooth curve would claim there is.
val found = runs.flatMap { it.costPoints() }
println()
println("Pareto front (gates, depth) at %d bits: %s".format(
    problem.spec.widths.max(),
    runs.flatMap { it.front }.joinToString(", ") { "(${it.gates}, ${it.depth})" }))

// And the circuits themselves, through the same MorphoFLines the rest of the page uses.
// One source, three signatures, three different circuits: that is the language's whole
// conceit and it now applies to a design nobody wrote.
val source = best.genome.render()
val panels = listOf(4, 8, 16).map { w ->
    val c = morphoCircuit(MorphoProblems.ROOT, w, source = source)
    c.growFully(relaxSteps = 220)
    "evolved or-reduce at %d bits — %d gates, depth %d".format(w, c.netlist.gateCount, c.depth.toInt()) to
        c.flines(fit = true)
}
showPlaneGrid(*panels.toTypedArray(), columns = 3, cellWidth = 300, cellHeight = 300,
    window = morphoUnitWindow())

showPlaneGrid(
    "or-reduce — best-so-far bit error vs compiler invocations (log x)\n" +
        morphoRunCaption(runs) to morphoConvergenceFLines(runs),
    "cost of an or-reduce — depth (x) against gates (y)\n" +
        "${found.size} correct designs, ${runs.sumOf { it.front.size }} of them non-dominated. " +
        "A staircase is only drawn when the front has more than one point: for a reduction " +
        "it usually does not, because cheaper and shallower go together here" to
        morphoParetoFLines(found),
    channel = "cost", columns = 2, cellWidth = 430, cellHeight = 380, window = morphoPlotWindow()
)
