# Field Document: morpho_circuit_search

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. A recursion, found — reducing a bus to one bit, then drawn at three widths

<!-- field-box id="morpho_search_recursion" index="0" -->

```edn [field-box id="morpho_search_recursion" role="properties"]
{"id" "morpho_search_recursion" "index" 0 "name" "A recursion, found — reducing a bus to one bit, then drawn at three widths" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "A recursion, found — reducing a bus to one bit, then drawn at three widths" "_tombstone" false "frame" #afield/rect [60.0 520.0 590.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "morpho_search_recursion_code_.kt" "lines" 120 "characters" 4667 "sha256" "87c3d4297324a59069f153b1585ca8c4e7d6af518c1f48a58f766584bc507cb2"}]}
```

```kotlin [field-property box="morpho_search_recursion" name="code" path="morpho_search_recursion_code_.kt"]
import afield.lang.morpho.*

/*
y = (x != 0). The reason this is the interesting target and bitwise XOR is not: morpho
sizes a LUT cell to its widest operand and broadcasts the narrow ones, so there is no
gate and no combination of gates that turns eight wires into one. The only way down is
SPLIT, which means every correct answer is divide-and-conquer by construction.
*/
val widths = [2, 3, 5]
val maxStatements = 6
val designWidth = 8
val rootName = "evolved"
val palette = MorphoPalette.standard
val spec = MorphoSpec(
    name = rootName,
    inputRoles = [MorphoPortRole.BUS],
    outputRoles = [MorphoPortRole.BIT],
    widths = widths,
) { inputs, _ ->
    longArrayOf(if (inputs[0] != 0L) 1L else 0L)
}
val sketch = MorphoCellSketch(
    name = rootName,
    inputs = ["x"],
    inputRoles = spec.inputRoles,
    outputRoles = spec.outputRoles,
    palette = palette,
    fallback = "0",
    designWidth = designWidth,
    minStatements = 2,
    maxStatements = maxStatements,
)
val problem = MorphoProblem(
    "or-reduce",
    spec,
    MorphoProgramGenerator([sketch], palette.declarations()),
    "N wires to one bit, which broadcast LUTs cannot do: every correct answer recurses",
)
println(problem)
val budget = 6000

val runs = [
    MorphoSearch.random(problem.spec, problem.generator, budget, seed = 2),
    MorphoSearch.evolve(problem.spec, problem.generator, budget, seed = 2, mu = 24, lambda = 40),
]
runs.forEach { println(it.summary()) }

// Cheapest correct design across both runs; if a seed happens not to solve it, fall back
// to the closest thing found rather than throwing -- this is a demo, not an assertion.
val correct = runs.mapNotNull { it.best }.filter { it.fitness.correct }
val best = correct.minByOrNull { it.gates } ?: runs.mapNotNull { it.best }.first()
println()
println(if (correct.isEmpty()) "(nothing exactly correct this run; showing the best found)" else "")
println(best.genome.phenotype().cells[0].render())

/*
Selected at 2, 3 and 5 -- an ODD family on purpose. SPLIT gives equal halves exactly
when the width is even, so a family of powers of two never shows the search a cell
whose two halves differ, and it will happily return a design that is silently wrong at
5, 7 and 9. Re-compiled here at widths nothing was selected on: same source text, new
signature, nothing to translate and nothing to get wrong.
*/
[7, 9, 16, 32, 48].forEach { w ->
    val f = MorphoSearch.generalization(best.genome, problem.spec, [w])
    println("  at %2d bits: %s".format(w, f))
}

/*
The Pareto front over every correct design the two runs produced. It is small and it is
not empty: a reduction can be a shallow wide tree or a deep cheap one, and the search
finds both ends. The staircase is drawn as a step function on purpose -- there is no
design at a fractional gate count, and a smooth curve would claim there is.
*/
val found = runs.flatMap { it.costPoints() }
println()
println(
    "Pareto front (gates, depth) at %d bits: %s"
        .format(
            problem.spec.widths.max(),
            runs.flatMap { it.front }.joinToString(", ") { "(${it.gates}, ${it.depth})" },
        ),
)

/*
And the circuits themselves, through the same MorphoFLines the rest of the page uses.
One source, three signatures, three different circuits: that is the language's whole
conceit and it now applies to a design nobody wrote.
*/
val source = best.genome.render()
val panels =
    [4, 8, 16].map { w ->
        val c = morphoCircuit(MorphoProblems.ROOT, w, source = source)
        c.growFully(relaxSteps = 220)
        "evolved or-reduce at %d bits — %d gates, depth %d".format(w, c.netlist.gateCount, c.depth.toInt()) to
            c.flines(fit = true)
    }
showPlaneGrid(
    *panels.toTypedArray(),
    columns = 3,
    cellWidth = 300,
    cellHeight = 300,
    window = morphoUnitWindow(),
)

val cost = if (found.isEmpty()) emptyList<FLine>() else morphoParetoFLines(found)
if (found.isEmpty())
    markdown("search note", "No correct design at this budget; the convergence plot shows the closest attempts.")
showPlaneGrid(
    "or-reduce — best-so-far bit error vs compiler invocations (log x)\n" + morphoRunCaption(runs) to
        morphoConvergenceFLines(runs),
    "cost of an or-reduce — depth (x) against gates (y)\n" +
        "${found.size} correct designs, ${runs.sumOf { it.front.size }} of them non-dominated. " +
        "A staircase is only drawn when the front has more than one point: for a reduction " +
        "it usually does not, because cheaper and shallower go together here" to cost,
    channel = "cost",
    columns = 2,
    cellWidth = 430,
    cellHeight = 380,
    window = morphoPlotWindow(),
)
```

## 2. Does selection beat sampling? One-bit full adder, three drivers, one budget

<!-- field-box id="morpho_search_control" index="1" -->

```edn [field-box id="morpho_search_control" role="properties"]
{"id" "morpho_search_control" "index" 1 "name" "Does selection beat sampling? One-bit full adder, three drivers, one budget" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Does selection beat sampling? One-bit full adder, three drivers, one budget" "_tombstone" false "frame" #afield/rect [60.0 60.0 600.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "morpho_search_control_code_.kt" "lines" 80 "characters" 2876 "sha256" "24459131d91fe5e49abb4ae11ccf8e7d4685b9f3cc24b2bcdbb72792633c0562"}]}
```

```kotlin [field-property box="morpho_search_control" name="code" path="morpho_search_control_code_.kt"]
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
```

## 3. The recursive adder is not found — and the target is provably in the space

<!-- field-box id="morpho_search_adder" index="2" -->

```edn [field-box id="morpho_search_adder" role="properties"]
{"id" "morpho_search_adder" "index" 2 "name" "The recursive adder is not found — and the target is provably in the space" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "The recursive adder is not found — and the target is provably in the space" "_tombstone" false "frame" #afield/rect [60.0 1020.0 590.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "morpho_search_adder_code_.kt" "lines" 119 "characters" 4683 "sha256" "140ea49c6334233c2d164fa3519a6f494d1a6ec1643922dacffc4c56da3a5ea4"}]}
```

```kotlin [field-property box="morpho_search_adder" name="code" path="morpho_search_adder_code_.kt"]
import afield.lang.morpho.*

// The result this branch set out to get, and did not. Reported because the alternative is
// to quietly not mention it.
val widths = [2, 4, 8]
val maxStatements = 10
val designWidth = 8
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
val declarations = palette.declarations() + "\n\n" + morphoCellSource("full_adder")
fun adderProblem(given: List<MorphoStatement>): MorphoProblem {
    val sketch = MorphoCellSketch(
        name = rootName,
        inputs = ["a", "b", "c"],
        inputRoles = spec.inputRoles,
        outputRoles = spec.outputRoles,
        palette = palette,
        fallback = "full_adder",
        designWidth = designWidth,
        minStatements = 3,
        maxStatements = maxStatements,
        prologue = given,
    )
    val guided = given.isNotEmpty()
    return MorphoProblem(
        if (guided) "guided recursive adder" else "recursive adder",
        spec,
        MorphoProgramGenerator([sketch], declarations),
        if (guided) "the two SPLITs are given and unremovable; the recursion and the recombination are searched"
        else
            "the divide-and-conquer body, over SPLIT / CAT / self-call / standard cells, with morpho's own full_adder as the given base case",
    )
}
val free = adderProblem([])
// The guided search receives exactly these two fixed statements; everything else is shared.
val guided = adderProblem(
    [
        MorphoStatement(["a0", "a1"], "SPLIT", ["a"]),
        MorphoStatement(["b0", "b1"], "SPLIT", ["b"]),
    ],
)
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
```

