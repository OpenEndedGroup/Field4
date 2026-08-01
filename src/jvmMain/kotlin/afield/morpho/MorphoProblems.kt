package afield.morpho

/**
 * The three search problems this layer was built to ask, in increasing order of how much of the
 * answer is given away.
 *
 * Each one is a [MorphoSpec] (what the circuit must compute, at which widths) plus a
 * [MorphoProgramGenerator] (what a candidate is allowed to be). Keeping them here rather than
 * inside a test means a Field box, the measurement run and the regression tests are all asking
 * *the same question*, which is the only way the numbers in the documentation and the numbers in
 * the assertions can be the same numbers.
 */
class MorphoProblem(
    val name: String,
    val spec: MorphoSpec,
    val generator: MorphoProgramGenerator,
    /** One sentence on what is being searched for and what is being handed over. */
    val about: String,
) {
    /**
     * Score a design from morpho's own standard library against this problem's spec.
     *
     * The reference points on every Pareto figure come from here — run through the identical
     * evaluator, not quoted from a paper.
     */
    fun reference(design: String): MorphoFitness = MorphoSearch.reference(spec, design)

    override fun toString(): String = "$name: $about"
}

object MorphoProblems {

    /** Every candidate cell is called this, so [MorphoSpec.name] and the rendered root agree. */
    const val ROOT = "evolved"

    /** The base case a recursion falls into when [MorphoProblems.ROOT] is the recursion. */
    const val BASE = "evolved_base"

    /**
     * The default width family, and **not** a power-of-two one. This is a measured decision.
     *
     * Selected on 2/4/8, every one of 400 distinct correct `or-reduce` designs is also correct at
     * 3, 6, 12, 16, 32 and 48 — and 399 of the 400 are wrong at 5, 7 and 9. Selected on 2/3/5
     * instead, 400 of 400 are correct at every width tried.
     *
     * The mechanism is a hole in the language rather than a quirk of the search.
     * `SPLIT` halves a bus as `floor(w/2), w - floor(w/2)` (`primitives.js:42`), so the two halves
     * are equal exactly when the width is even, and 5, 7 and 9 are the small widths at which they
     * are unequal *and* both wider than one bit. A LUT applied to both halves — `Or(x0, x1)` — then
     * has operands of different widths, and `materializeLut` (`compiler.js:961-972`) takes the
     * maximum and broadcasts only operands of width 1. Unequal wider operands are neither an error
     * nor a broadcast; the extra pins read off the end of the shorter bus. So the design is
     * silently wrong rather than rejected, and a search selected on even widths never has to notice.
     *
     * Two bits (the base case), three (the smallest unequal split with a one-bit half) and five
     * (the smallest unequal split with both halves wider than one bit) cover the cases that
     * distinguish a parametric design from a coincidence. `MorphoSearchMeasurements.generalisation`
     * is where those counts come from.
     */
    val ODD_FAMILY: List<Int> = listOf(2, 3, 5)

    /**
     * **One bit, no recursion.** Find `sum, c_out = a + b + c_in` as straight-line logic over
     * morpho's standard cells.
     *
     * Exhaustive: eight input vectors is the entire truth table, so "correct" here means correct,
     * not "correct on the vectors we tried". This is the canary — a pipeline that cannot find a
     * full adder in a few hundred evaluations is broken rather than unlucky.
     */
    fun fullAdderCell(maxStatements: Int = 6): MorphoProblem {
        val spec = MorphoSpec.adder(widths = listOf(1), name = ROOT)
        val sketch = MorphoCellSketch(
            name = ROOT,
            inputs = listOf("a", "b", "c"),
            inputRoles = spec.inputRoles,
            outputRoles = spec.outputRoles,
            palette = MorphoPalette.standard,
            designWidth = 1,
            allowSplit = false,
            allowCat = false,
            allowSelf = false,
            minStatements = 2,
            maxStatements = maxStatements,
        )
        return MorphoProblem(
            "full-adder cell",
            spec,
            MorphoProgramGenerator(listOf(sketch), MorphoPalette.standard.declarations()),
            "straight-line logic at one bit, checked exhaustively; the recursion is not in play",
        )
    }

    /**
     * **The recursion.** The 1-bit base case is handed over — it is morpho's own `full_adder`,
     * lifted verbatim out of `tiny_morpho.py` — and the divide-and-conquer body is searched for.
     *
     * This is the problem that is specific to MorphoHDL. The candidate is a *width-parametric*
     * program, so it is compiled and simulated once per width in [widths] and scored on the worst
     * of them: an individual that adds at two bits and not at eight has found a coincidence.
     */
    fun recursiveAdder(
        widths: List<Int> = listOf(2, 4, 8),
        maxStatements: Int = 10,
        designWidth: Int = 8,
    ): MorphoProblem {
        val spec = MorphoSpec.adder(widths = widths, name = ROOT)
        val preamble = MorphoPalette.standard.declarations() + "\n\n" + morphoCellSource("full_adder")
        val sketch = MorphoCellSketch(
            name = ROOT,
            inputs = listOf("a", "b", "c"),
            inputRoles = spec.inputRoles,
            outputRoles = spec.outputRoles,
            palette = MorphoPalette.standard,
            fallback = "full_adder",
            designWidth = designWidth,
            minStatements = 3,
            maxStatements = maxStatements,
        )
        return MorphoProblem(
            "recursive adder",
            spec,
            MorphoProgramGenerator(listOf(sketch), preamble),
            "the divide-and-conquer body, over SPLIT / CAT / self-call / standard cells, " +
                "with morpho's own full_adder as the given base case",
        )
    }

    /**
     * **The conquer, with the divide given.** Same as [recursiveAdder], except that
     * `a0, a1 = SPLIT(a)` and `b0, b1 = SPLIT(b)` are already in the body and cannot be removed.
     *
     * This exists because [recursiveAdder] does not get solved, and the interesting question is
     * *which part* of the leap is too far. Halving both operands is two statements that pay nothing
     * on their own — a candidate that has split its inputs and not yet used the halves scores
     * exactly as well as one that has not bothered — so they are the classic case of a fitness
     * plateau that has to be crossed blind. Handing them over turns "find a recursive adder" into
     * "given that you have the halves, work out what to do with them", which is a much smaller
     * question and, unlike the larger one, an answerable one.
     */
    fun guidedRecursiveAdder(
        widths: List<Int> = listOf(2, 4, 8),
        maxStatements: Int = 10,
        designWidth: Int = 8,
    ): MorphoProblem {
        val spec = MorphoSpec.adder(widths = widths, name = ROOT)
        val preamble = MorphoPalette.standard.declarations() + "\n\n" + morphoCellSource("full_adder")
        val sketch = MorphoCellSketch(
            name = ROOT,
            inputs = listOf("a", "b", "c"),
            inputRoles = spec.inputRoles,
            outputRoles = spec.outputRoles,
            palette = MorphoPalette.standard,
            fallback = "full_adder",
            designWidth = designWidth,
            minStatements = 3,
            maxStatements = maxStatements,
            prologue = listOf(
                MorphoStatement(listOf("a0", "a1"), "SPLIT", listOf("a")),
                MorphoStatement(listOf("b0", "b1"), "SPLIT", listOf("b")),
            ),
        )
        return MorphoProblem(
            "guided recursive adder",
            spec,
            MorphoProgramGenerator(listOf(sketch), preamble),
            "the two SPLITs are given and unremovable; the recursion and the recombination are searched",
        )
    }

    /**
     * **Both at once.** The recursion *and* the base case it falls into, evolved together.
     *
     * Nothing about addition is supplied except the oracle. The two cells are separate genes, so
     * crossover has something to recombine across — a good base case found in one lineage can move
     * into another lineage's recursion.
     */
    fun adderFromScratch(
        widths: List<Int> = listOf(2, 4, 8),
        maxStatements: Int = 10,
        designWidth: Int = 8,
    ): MorphoProblem {
        val spec = MorphoSpec.adder(widths = widths, name = ROOT)
        val recursion = MorphoCellSketch(
            name = ROOT,
            inputs = listOf("a", "b", "c"),
            inputRoles = spec.inputRoles,
            outputRoles = spec.outputRoles,
            palette = MorphoPalette.standard,
            fallback = BASE,
            designWidth = designWidth,
            minStatements = 3,
            maxStatements = maxStatements,
        )
        val base = MorphoCellSketch(
            name = BASE,
            inputs = listOf("a", "b", "c"),
            inputRoles = spec.inputRoles,
            outputRoles = spec.outputRoles,
            palette = MorphoPalette.standard,
            designWidth = 1,
            allowSplit = false,
            allowCat = false,
            allowSelf = false,
            minStatements = 2,
            maxStatements = 6,
        )
        return MorphoProblem(
            "adder from scratch",
            spec,
            MorphoProgramGenerator(listOf(recursion, base), MorphoPalette.standard.declarations()),
            "recursion and base case as two genes, with nothing about addition supplied but the oracle",
        )
    }

    /**
     * **The recursion, on a target where a half-built one already pays.** Reduce an N-bit bus to
     * one bit: `y = (x != 0)`.
     *
     * Nothing is given away — no prologue, no base cell, `fallback=0` (a one-bit bus is its own
     * reduction, which `compiler.js:1068` wires as a pass-through). What is different from
     * [recursiveAdder] is the *landscape*, not the amount of help: see [MorphoSpec.orReduce]. This
     * is the problem that answers whether the search can find divide-and-conquer at all.
     */
    fun orReduce(widths: List<Int> = ODD_FAMILY, maxStatements: Int = 6, designWidth: Int = 8): MorphoProblem {
        val spec = MorphoSpec.orReduce(widths = widths, name = ROOT)
        val sketch = MorphoCellSketch(
            name = ROOT,
            inputs = listOf("x"),
            inputRoles = spec.inputRoles,
            outputRoles = spec.outputRoles,
            palette = MorphoPalette.standard,
            fallback = "0",
            designWidth = designWidth,
            minStatements = 2,
            maxStatements = maxStatements,
        )
        return MorphoProblem(
            "or-reduce",
            spec,
            MorphoProgramGenerator(listOf(sketch), MorphoPalette.standard.declarations()),
            "N wires to one bit, which broadcast LUTs cannot do: every correct answer recurses",
        )
    }

    /** [orReduce]'s dual: `y = (x == all ones)`. A second point on the same claim. */
    fun andReduce(widths: List<Int> = ODD_FAMILY, maxStatements: Int = 6, designWidth: Int = 8): MorphoProblem {
        val spec = MorphoSpec.andReduce(widths = widths, name = ROOT)
        val sketch = MorphoCellSketch(
            name = ROOT,
            inputs = listOf("x"),
            inputRoles = spec.inputRoles,
            outputRoles = spec.outputRoles,
            palette = MorphoPalette.standard,
            fallback = "0",
            designWidth = designWidth,
            minStatements = 2,
            maxStatements = maxStatements,
        )
        return MorphoProblem(
            "and-reduce",
            spec,
            MorphoProgramGenerator(listOf(sketch), MorphoPalette.standard.declarations()),
            "the dual of or-reduce, so a result on one is not a result about one lucky truth table",
        )
    }

    /**
     * **The easy parametric one.** Bitwise XOR of two buses — one statement is enough, and no
     * recursion is required, because morpho broadcasts a LUT across a bus for free.
     *
     * Worth having precisely because it is trivial: it separates "the search cannot find this" from
     * "the search cannot find anything", and it is fast enough to assert on in a unit test.
     */
    fun bitwiseXor(widths: List<Int> = listOf(2, 4, 8)): MorphoProblem {
        val spec = MorphoSpec.bitwiseXor(widths = widths, name = ROOT)
        val sketch = MorphoCellSketch(
            name = ROOT,
            inputs = listOf("a", "b"),
            inputRoles = spec.inputRoles,
            outputRoles = spec.outputRoles,
            palette = MorphoPalette.standard,
            designWidth = widths.max(),
            allowSelf = false,
            minStatements = 1,
            maxStatements = 4,
        )
        return MorphoProblem(
            "bitwise xor",
            spec,
            MorphoProgramGenerator(listOf(sketch), MorphoPalette.standard.declarations()),
            "one broadcast LUT would do it; here to tell a failing search from a failing harness",
        )
    }
}

/**
 * One cell's source, lifted out of a MorphoHDL module by name.
 *
 * Used to hand a search a *given* base case without copying morpho's code into Kotlin, where it
 * would drift. The slice runs from the cell's `@morpho` decorator to its `return`, which is exactly
 * what `parser.js:134-177` treats as one cell.
 */
fun morphoCellSource(name: String, source: String = MorphoRuntime.standardLibrary): String {
    val lines = source.lines()
    val def = lines.indexOfFirst { Regex("""^\s*def\s+$name\s*\(""").containsMatchIn(it) }
    require(def >= 0) { "no cell named '$name' in this MorphoHDL source" }
    var start = def
    while (start > 0 && lines[start - 1].trimStart().startsWith("@morpho")) start--
    var end = def
    while (end < lines.size && !lines[end].trimStart().startsWith("return ")) end++
    require(end < lines.size) { "cell '$name' has no return statement" }
    return lines.subList(start, end + 1).joinToString("\n").trimEnd()
}
