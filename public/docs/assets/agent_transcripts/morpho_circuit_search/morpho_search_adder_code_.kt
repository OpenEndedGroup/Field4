import afield.lang.morpho.*

// The result this branch set out to get, and did not. Reported because the alternative is
// to quietly not mention it.
val free = MorphoProblems.recursiveAdder()
val guided = MorphoProblems.guidedRecursiveAdder()
val budget = 6000
val runs = [
    MorphoSearch.random(free.spec, free.generator, budget, seed = 1),
    MorphoSearch.hillClimb(free.spec, free.generator, budget, seed = 1),
    MorphoSearch.evolve(free.spec, free.generator, budget, seed = 1),
    MorphoSearch.evolve(
        guided.spec,
        guided.generator,
        budget,
        seed = 1,
        label = "evolution, SPLITs given",
    ),
]
runs.forEach { println(it.summary()) }

/*
A negative result is only worth printing if the thing not found was findable. Here is
the ripple skeleton, written by hand in exactly the grammar the generator emits, scored
by exactly the evaluator the search used.
*/
val target = MorphoGenome(
    [
        MorphoCellGenome(
            name = MorphoProblems.ROOT,
            inputs = ["a", "b", "c"],
            statements = [
                MorphoStatement(["a0", "a1"], "SPLIT", ["a"]),
                MorphoStatement(["b0", "b1"], "SPLIT", ["b"]),
                MorphoStatement(["s0", "cm"], MorphoProblems.ROOT, ["a0", "b0", "c"]),
                MorphoStatement(["s1", "co"], MorphoProblems.ROOT, ["a1", "b1", "cm"]),
                MorphoStatement(["sum"], "CAT", ["s0", "s1"]),
            ],
            outputs = ["sum", "co"],
            fallback = "full_adder",
        ),
    ],
    free.generator.preamble,
)
println()
println(target.cells[0].render())
println("  scored at 2/4/8: " + MorphoEvaluator(free.spec).evaluate(target.render(), target.rootName))
println("  at 16 bits:      " + MorphoSearch.generalization(target, free.spec, [16]))
println("  at 32 bits:      " + MorphoSearch.generalization(target, free.spec, [32]))

// What the humans cost, through the same evaluator rather than quoted from a paper.
val references =
    ["ripple_adder", "brent_kung_adder"].mapNotNull { design ->
        free.reference(design).cost?.let { MorphoCostPoint(design, it.gates, it.depth, reference = true) }
    }
references.forEach { println("  reference ${it.label}: ${it.gates} gates, depth ${it.depth}") }

/*
Left: four flat tails, none of which reaches zero. Right: the cost plane for an 8-bit
adder, occupied entirely by the two designs a person wrote. The empty half of that
panel is the finding.
*/
val found = runs.flatMap { it.costPoints() }
println("correct designs found by any driver: ${found.size}")
showPlaneGrid(
    "recursive 8-bit adder — best-so-far bit error vs compiler invocations (log x)\n" + morphoRunCaption(runs) to
        morphoConvergenceFLines(runs),
    "cost of an 8-bit adder — depth (x) against gates (y)\n" +
        "cyan: morpho's own designs.  orange: everything the search found (${found.size})" to
        morphoParetoFLines(found + references),
    columns = 2,
    cellWidth = 430,
    cellHeight = 400,
    window = morphoPlotWindow(),
)
