import afield.lang.morpho.*

/*
A problem is an oracle plus a width family plus a
grammar, and a driver turns a budget into a program. Nothing about addition is supplied
except `sum, c_out = a + b + c_in`; the parts list is morpho's own eight standard cells,
lifted out of tiny_morpho.py rather than copied into Kotlin.
*/
val widths = [1]
val maxStatements = 6
val rootName = "evolved"
val palette = MorphoPalette.standard
val spec = MorphoSpec(
    name = rootName,
    inputRoles = [MorphoPortRole.BUS, MorphoPortRole.BUS, MorphoPortRole.BIT],
    outputRoles = [MorphoPortRole.BUS, MorphoPortRole.BIT],
    widths = widths,
) { inputs, width ->
    val total = inputs[0] + inputs[1] + inputs[2]
    longArrayOf(total and ((1L shl width) - 1), total ushr width)
}
val sketch = MorphoCellSketch(
    name = rootName,
    inputs = ["a", "b", "c"],
    inputRoles = spec.inputRoles,
    outputRoles = spec.outputRoles,
    palette = palette,
    designWidth = 1,
    allowSplit = false,
    allowCat = false,
    allowSelf = false,
    minStatements = 2,
    maxStatements = maxStatements,
)
val problem = MorphoProblem(
    "full-adder cell",
    spec,
    MorphoProgramGenerator([sketch], palette.declarations()),
    "straight-line logic at one bit, checked exhaustively; the recursion is not in play",
)
val budget = 4000
println(problem)
println("palette: " + MorphoPalette.standard.luts.joinToString { it.name })
println(
    "input space at 1 bit: ${problem.spec.inputSpace(1)} vectors, " + "exhaustive = ${problem.spec.isExhaustiveAt(1)}",
)

// Same generator, same seed, same budget. One unit of budget is one invocation of
// morpho's compiler, which is the only resource all three spend at comparable rates.
val runs = [
    MorphoSearch.random(problem.spec, problem.generator, budget, seed = 2),
    MorphoSearch.hillClimb(problem.spec, problem.generator, budget, seed = 2),
    MorphoSearch.evolve(problem.spec, problem.generator, budget, seed = 2),
]
runs.forEach { println(it.summary()) }

// What was actually found, in MorphoHDL. This is paste-into-a-morpho-box text.
runs
    .firstOrNull { it.solved }
    ?.best
    ?.let { winner ->
        println()
        println(winner.genome.phenotype().cells[0].render())
        println("cost: ${winner.fitness.cost}")
    }

/*
The control arm IS the figure: cyan is random sampling, orange the hill climb, green
the population. A dropped tick marks the invocation at which a run first became exactly
correct, because at this scale a curve that reaches zero and one that reaches 0.004
look identical and that is the entire question.
*/
showPlaneGrid(
    "one-bit full adder — best-so-far bit error vs compiler invocations (log x, $budget budget)\n" +
        morphoRunCaption(runs) to morphoConvergenceFLines(runs),
    columns = 1,
    cellWidth = 700,
    cellHeight = 420,
    window = morphoPlotWindow(),
)
