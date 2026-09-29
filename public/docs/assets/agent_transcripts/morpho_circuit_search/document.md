# Field Document: morpho_circuit_search

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. A recursion, found — reducing a bus to one bit, then drawn at three widths

<!-- field-box id="morpho_search_recursion" index="0" -->

```edn [field-box id="morpho_search_recursion" role="properties"]
{"id" "morpho_search_recursion" "index" 0 "name" "A recursion, found — reducing a bus to one bit, then drawn at three widths" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "A recursion, found — reducing a bus to one bit, then drawn at three widths" "_tombstone" false "frame" #afield/rect [60.0 520.0 590.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "morpho_search_recursion_code_.kt" "lines" 66 "characters" 3614 "sha256" "77bcb5b4c6dbb3e211b9c531d0073a7e501328ea785628246058ea66cf8e1e4f"}]}
```

```kotlin [field-property box="morpho_search_recursion" name="code" path="morpho_search_recursion_code_.kt"]
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
```

## 2. Does selection beat sampling? One-bit full adder, three drivers, one budget

<!-- field-box id="morpho_search_control" index="1" -->

```edn [field-box id="morpho_search_control" role="properties"]
{"id" "morpho_search_control" "index" 1 "name" "Does selection beat sampling? One-bit full adder, three drivers, one budget" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Does selection beat sampling? One-bit full adder, three drivers, one budget" "_tombstone" false "frame" #afield/rect [60.0 60.0 600.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "morpho_search_control_code_.kt" "lines" 38 "characters" 1933 "sha256" "096ba2aa11b4addbb8485bcfacdab9d2f1c3ae19059fd9eccdb76a0ec1d0cc61"}]}
```

```kotlin [field-property box="morpho_search_control" name="code" path="morpho_search_control_code_.kt"]
import afield.morpho.*

// The whole layer in four lines: a problem is an oracle plus a width family plus a
// grammar, and a driver turns a budget into a program. Nothing about addition is supplied
// except `sum, c_out = a + b + c_in`; the parts list is morpho's own eight standard cells,
// lifted out of tiny_morpho.py rather than copied into Kotlin.
val problem = MorphoProblems.fullAdderCell()
val budget = 4000
println(problem)
println("palette: " + MorphoPalette.standard.luts.joinToString { it.name })
println("input space at 1 bit: ${problem.spec.inputSpace(1)} vectors, " +
    "exhaustive = ${problem.spec.isExhaustiveAt(1)}")

// Same generator, same seed, same budget. One unit of budget is one invocation of
// morpho's compiler, which is the only resource all three spend at comparable rates.
val runs = listOf(
    MorphoSearch.random(problem.spec, problem.generator, budget, seed = 2),
    MorphoSearch.hillClimb(problem.spec, problem.generator, budget, seed = 2),
    MorphoSearch.evolve(problem.spec, problem.generator, budget, seed = 2),
)
runs.forEach { println(it.summary()) }

// What was actually found, in MorphoHDL. This is paste-into-a-morpho-box text.
runs.firstOrNull { it.solved }?.best?.let { winner ->
    println()
    println(winner.genome.phenotype().cells[0].render())
    println("cost: ${winner.fitness.cost}")
}

// The control arm IS the figure: cyan is random sampling, orange the hill climb, green
// the population. A dropped tick marks the invocation at which a run first became exactly
// correct, because at this scale a curve that reaches zero and one that reaches 0.004
// look identical and that is the entire question.
showPlaneGrid(
    "one-bit full adder — best-so-far bit error vs compiler invocations (log x, $budget budget)\n" +
        morphoRunCaption(runs) to morphoConvergenceFLines(runs),
    columns = 1, cellWidth = 700, cellHeight = 420, window = morphoPlotWindow()
)
```

## 3. The recursive adder is not found — and the target is provably in the space

<!-- field-box id="morpho_search_adder" index="2" -->

```edn [field-box id="morpho_search_adder" role="properties"]
{"id" "morpho_search_adder" "index" 2 "name" "The recursive adder is not found — and the target is provably in the space" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "The recursive adder is not found — and the target is provably in the space" "_tombstone" false "frame" #afield/rect [60.0 1020.0 590.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "morpho_search_adder_code_.kt" "lines" 60 "characters" 3024 "sha256" "b46a05a0e349c79b25d60c330096d3ab2dc464cb4d14aa0c8e13368a2b880381"}]}
```

```kotlin [field-property box="morpho_search_adder" name="code" path="morpho_search_adder_code_.kt"]
import afield.morpho.*

// The result this branch set out to get, and did not. Reported because the alternative is
// to quietly not mention it.
val free = MorphoProblems.recursiveAdder()
val guided = MorphoProblems.guidedRecursiveAdder()
val budget = 6000
val runs = listOf(
    MorphoSearch.random(free.spec, free.generator, budget, seed = 1),
    MorphoSearch.hillClimb(free.spec, free.generator, budget, seed = 1),
    MorphoSearch.evolve(free.spec, free.generator, budget, seed = 1),
    MorphoSearch.evolve(guided.spec, guided.generator, budget, seed = 1,
        label = "evolution, SPLITs given"),
)
runs.forEach { println(it.summary()) }

// A negative result is only worth printing if the thing not found was findable. Here is
// the ripple skeleton, written by hand in exactly the grammar the generator emits, scored
// by exactly the evaluator the search used.
val target = MorphoGenome(
    listOf(MorphoCellGenome(
        name = MorphoProblems.ROOT,
        inputs = listOf("a", "b", "c"),
        statements = listOf(
            MorphoStatement(listOf("a0", "a1"), "SPLIT", listOf("a")),
            MorphoStatement(listOf("b0", "b1"), "SPLIT", listOf("b")),
            MorphoStatement(listOf("s0", "cm"), MorphoProblems.ROOT, listOf("a0", "b0", "c")),
            MorphoStatement(listOf("s1", "co"), MorphoProblems.ROOT, listOf("a1", "b1", "cm")),
            MorphoStatement(listOf("sum"), "CAT", listOf("s0", "s1")),
        ),
        outputs = listOf("sum", "co"),
        fallback = "full_adder",
    )),
    free.generator.preamble,
)
println()
println(target.cells[0].render())
println("  scored at 2/4/8: " + MorphoEvaluator(free.spec).evaluate(target.render(), target.rootName))
println("  at 16 bits:      " + MorphoSearch.generalization(target, free.spec, listOf(16)))
println("  at 32 bits:      " + MorphoSearch.generalization(target, free.spec, listOf(32)))

// What the humans cost, through the same evaluator rather than quoted from a paper.
val references = listOf("ripple_adder", "brent_kung_adder").mapNotNull { design ->
    free.reference(design).cost?.let { MorphoCostPoint(design, it.gates, it.depth, reference = true) }
}
references.forEach { println("  reference ${it.label}: ${it.gates} gates, depth ${it.depth}") }

// Left: four flat tails, none of which reaches zero. Right: the cost plane for an 8-bit
// adder, occupied entirely by the two designs a person wrote. The empty half of that
// panel is the finding.
val found = runs.flatMap { it.costPoints() }
println("correct designs found by any driver: ${found.size}")
showPlaneGrid(
    "recursive 8-bit adder — best-so-far bit error vs compiler invocations (log x)\n" +
        morphoRunCaption(runs) to morphoConvergenceFLines(runs),
    "cost of an 8-bit adder — depth (x) against gates (y)\n" +
        "cyan: morpho's own designs.  orange: everything the search found (${found.size})" to
        morphoParetoFLines(found + references),
    columns = 2, cellWidth = 430, cellHeight = 400, window = morphoPlotWindow()
)
```

