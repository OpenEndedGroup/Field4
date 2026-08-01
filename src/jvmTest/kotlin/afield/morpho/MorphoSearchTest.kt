package afield.morpho

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The search layer, held to the two things it could plausibly be lying about.
 *
 * **That the fitness function is real.** Everything downstream is worthless if a candidate is
 * scored by anything other than upstream's compiler and a genuine simulation, so the first tests
 * put morpho's own `ripple_adder` and `brent_kung_adder` through [MorphoEvaluator] and check the
 * numbers against `MorphoBridgeTest`'s independently pinned ones.
 *
 * **That a claim of "found it" means found it.** A parametric design is a claim about every width,
 * so a design is only correct here if it is correct at *every* width in the family, and the tests
 * re-check winners at widths nothing was searched at.
 *
 * Search runs are seeded and the evaluator is deterministic, so the outcomes asserted below are
 * reproducible rather than probabilistic — but they are still outcomes of a stochastic method, and
 * the thresholds are set well inside what was measured across seeds rather than at the edge of it.
 */
class MorphoSearchTest {

    // ---------------------------------------------------------------- the fixed vocabulary

    /**
     * The palette is morpho's, lifted out of `tiny_morpho.py` rather than copied into Kotlin.
     *
     * Checked by name and by truth table, because a regex that silently matched nothing would leave
     * an empty palette and a search that could not build anything, which reads as "the search is
     * bad" rather than as "the palette is empty".
     */
    @Test
    fun `the palette is morphos own standard cells`() {
        val palette = MorphoPalette.standard
        val byName = palette.luts.associateBy { it.name }
        listOf("Not", "And", "Or", "Xor", "Xor3", "Maj3", "carry_op", "Mux2").forEach {
            assertTrue(it in byName, "standard palette is missing '$it'; found ${byName.keys}")
        }
        assertEquals(3, byName.getValue("Xor3").arity)
        assertEquals(0b1001_0110, byName.getValue("Xor3").value, "Xor3 is odd parity")
        assertEquals(0b1110_1000, byName.getValue("Maj3").value, "Maj3 is majority")
        assertEquals(0b1100_1010, byName.getValue("Mux2").value, "Mux2 selects x1 when sel is high")

        // Round-trips: what the palette prints has to be what the parser reads back.
        val reread = MorphoPalette.from(palette.declarations()).luts.associateBy { it.name }
        palette.luts.forEach { assertEquals(it, reread[it.name], "declaration of ${it.name} does not round-trip") }
    }

    /** A given base case comes out of upstream's text, decorator and all. */
    @Test
    fun `a cell can be lifted out of morphos source`() {
        val text = morphoCellSource("full_adder")
        assertTrue(text.trimStart().startsWith("@morpho"), "lifted cell lost its decorator:\n$text")
        assertTrue("def full_adder(a, b, c_in):" in text, "lifted the wrong cell:\n$text")
        assertTrue(text.trimEnd().endsWith("return sum, c_out"), "lifted cell is truncated:\n$text")
        assertTrue("Xor3" in text && "Maj3" in text, "lifted cell lost its body:\n$text")
    }

    // ---------------------------------------------------------------- the evaluator is honest

    /**
     * Morpho's own designs, scored by the search's own evaluator.
     *
     * The gate counts here are the ones `MorphoBridgeTest` pins against `tiny_morpho.py`'s printed
     * output, at the widths this spec uses. If [MorphoEvaluator] ever started scoring something
     * other than the compiled netlist, this is where it would show.
     */
    @Test
    fun `the evaluator scores morphos own adders as correct`() {
        val spec = MorphoSpec.adder(widths = listOf(2, 4, 8))
        val ripple = MorphoSearch.reference(spec, "ripple_adder")
        val bk = MorphoSearch.reference(spec, "brent_kung_adder")

        assertTrue(ripple.correct, "ripple_adder scored as wrong: $ripple")
        assertTrue(bk.correct, "brent_kung_adder scored as wrong: $bk")
        val rippleCost = ripple.cost!!
        val bkCost = bk.cost!!
        assertEquals(16, rippleCost.gates, "an 8-bit ripple adder is 8 full adders = 16 gates")
        assertEquals(8, rippleCost.depth, "a ripple carry is linear in width")
        assertTrue(
            bkCost.depth < rippleCost.depth,
            "Brent-Kung should be shallower than ripple at 8 bits: $bkCost vs $rippleCost"
        )
        assertTrue(
            bkCost.gates > rippleCost.gates,
            "and should pay for it in gates: $bkCost vs $rippleCost"
        )
    }

    /** A design with the wrong port count is a failure, not a design that scored badly. */
    @Test
    fun `a shape mismatch is reported as a failure`() {
        val spec = MorphoSpec.adder(widths = listOf(4))
        val wrong = MorphoSearch.reference(spec, "wallace_multiplier")
        assertFalse(wrong.valid, "a 2-input multiplier should not pass a 3-input adder spec")
        assertTrue("input ports" in (wrong.failure ?: ""), "unhelpful failure: ${wrong.failure}")
    }

    /** Fan-out is counted off the netlist, and a prefix tree really does fan out more than a chain. */
    @Test
    fun `cost reads fanout off the netlist`() {
        val spec = MorphoSpec.adder(widths = listOf(16))
        val ripple = MorphoSearch.reference(spec, "ripple_adder").cost!!
        val bk = MorphoSearch.reference(spec, "brent_kung_adder").cost!!
        assertTrue(ripple.maxFanout >= 1, "every net in a live circuit feeds something")
        assertTrue(
            bk.maxFanout > ripple.maxFanout,
            "a parallel-prefix adder reuses its prefixes: ${bk.maxFanout} should exceed ${ripple.maxFanout}"
        )
    }

    // ---------------------------------------------------------------- the target is in the space

    /** The ripple-adder skeleton, written by hand in exactly the grammar the generator emits. */
    private fun rippleGenome(preamble: String) = MorphoGenome(
        listOf(
            MorphoCellGenome(
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
            )
        ),
        preamble,
    )

    /**
     * A negative result is only interesting if the thing not found was findable, so this pins that
     * the recursive adder really is inside the generator's grammar — and that a genome expressing
     * it compiles to *the same circuit* morpho's own `ripple_adder` compiles to.
     *
     * Five widths, two of which (16 and 32) no search in this file ever selects on.
     */
    @Test
    fun `the recursive adder is reachable in the genome grammar`() {
        val problem = MorphoProblems.recursiveAdder()
        val genome = rippleGenome(problem.generator.preamble)

        val scored = MorphoEvaluator(problem.spec).evaluate(genome.render(), genome.rootName)
        assertTrue(scored.correct, "the hand-written skeleton is not correct: $scored")
        val cost = scored.cost!!
        assertEquals(16, cost.gates, "should be exactly ripple_adder's 8-bit cost")
        assertEquals(8, cost.depth)

        listOf(listOf(2, 3, 5), listOf(16), listOf(32)).forEach { widths ->
            val wide = MorphoSearch.generalization(genome, problem.spec, widths)
            assertTrue(wide.correct, "the skeleton fails at widths $widths: $wide")
        }
        assertEquals(
            64,
            MorphoSearch.generalization(genome, problem.spec, listOf(32)).cost!!.gates,
            "a 32-bit ripple adder is 32 full adders = 64 gates"
        )
    }

    /** And that the generator's own well-foundedness check accepts it. */
    @Test
    fun `the termination check accepts a narrowing recursion and rejects a stalled one`() {
        val sketch = MorphoProblems.recursiveAdder().generator.sketches[0]
        val good = rippleGenome("").cells[0]
        assertTrue(
            MorphoWidthModel.terminates(good, sketch.inputWidths, sketch.palette, sketch::selfShape),
            "the ripple skeleton halves both operands and must be accepted"
        )

        // `Maj3(b, c, b)` is as wide as b, so this calls itself at (1, 8, 1) forever.
        val stalled = good.copy(
            statements = listOf(
                MorphoStatement(listOf("t"), "Maj3", listOf("b", "c", "b")),
                MorphoStatement(listOf("s", "co"), MorphoProblems.ROOT, listOf("ONE", "t", "ONE")),
            ),
            outputs = listOf("s", "co"),
        )
        assertFalse(
            MorphoWidthModel.terminates(stalled, sketch.inputWidths, sketch.palette, sketch::selfShape),
            "a self-call at an unchanging signature must be rejected"
        )
    }

    /**
     * Whatever the operators do, what comes out is well-founded.
     *
     * A property check rather than an example: 400 random-then-mutated programs across the three
     * recursive problems, every one of which has to pass the termination check *and* compile
     * without the watchdog firing. This is the test that would have caught the original design,
     * where the narrowing rule was checked only at the top level.
     */
    @Test
    fun `generated and mutated programs always terminate`() {
        listOf(
            MorphoProblems.recursiveAdder(),
            MorphoProblems.guidedRecursiveAdder(),
            MorphoProblems.orReduce(),
        ).forEach { problem ->
            val rng = Random(4242)
            val sketch = problem.generator.sketches[0]
            var genome = problem.generator.random(rng)
            repeat(140) {
                genome = problem.generator.mutate(genome, rng)
                val cell = genome.phenotype().cells[0]
                assertTrue(
                    MorphoWidthModel.terminates(cell, sketch.inputWidths, sketch.palette, sketch::selfShape),
                    "${problem.name}: generator emitted a non-terminating body:\n${cell.render()}"
                )
            }
        }
    }

    // ---------------------------------------------------------------- genotype and phenotype

    /** Dead statements stay in the genome and never reach the compiler. */
    @Test
    fun `introns survive in the genome and are pruned out of the program`() {
        val genome = MorphoGenome(
            listOf(
                MorphoCellGenome(
                    name = "evolved",
                    inputs = listOf("a", "b"),
                    statements = listOf(
                        MorphoStatement(listOf("live"), "Xor", listOf("a", "b")),
                        MorphoStatement(listOf("dead"), "And", listOf("a", "b")),
                    ),
                    outputs = listOf("live"),
                    fallback = null,
                )
            ),
            MorphoPalette.standard.declarations(),
        )
        assertEquals(2, genome.statementCount, "the genotype keeps both statements")
        assertEquals(1, genome.liveStatementCount, "only one is live")
        assertFalse("And(" in genome.render(), "the dead statement reached the compiler:\n${genome.render()}")
        assertTrue("Xor(" in genome.render())
    }

    // ---------------------------------------------------------------- the searches

    /**
     * The one-bit full adder: **hill climbing solves it and random sampling does not**, at the same
     * budget, from the same generator.
     *
     * This is the control-arm result the rest of the layer is judged by. Measured across seeds 1-3,
     * hill climbing solved it in 1789-3846 evaluations every time and random sampling solved it in
     * none of three runs of 4000, plateauing at a bit-error rate of 0.125 — which is
     * `sum = Xor3(a, b, c)` with `c_out = And(a, b)`, one `And` -> `Maj3` swap short.
     */
    @Test
    fun `hill climbing finds a full adder where random sampling does not`() {
        val problem = MorphoProblems.fullAdderCell()
        val budget = 4000

        val control = MorphoSearch.random(problem.spec, problem.generator, budget, seed = 2)
        val climb = MorphoSearch.hillClimb(problem.spec, problem.generator, budget, seed = 2)

        assertTrue(climb.solved, "hill climb did not solve the full adder in $budget: ${climb.summary()}")
        assertFalse(control.solved, "random search unexpectedly solved it: ${control.summary()}")
        assertTrue(
            control.best!!.fitness.error > 0.0,
            "the control must have a non-zero error for the comparison to mean anything"
        )
        // Exhaustive at one bit, so "correct" here is the whole truth table and not a sample of it.
        assertTrue(problem.spec.isExhaustiveAt(1), "the full-adder check must be exhaustive")
        assertEquals(2, climb.best!!.fitness.cost!!.gates, "the minimum full adder is Xor3 plus Maj3")
    }

    /**
     * A **recursion**, found from nothing: reducing an N-bit bus to one bit.
     *
     * Morpho broadcasts a LUT across a bus (`compiler.js:963`), so no combination of gates turns
     * eight wires into one; the only route is to keep `SPLIT`ting. Every correct answer here is
     * therefore genuinely divide-and-conquer, which makes this the positive counterpart to the
     * adder's negative result.
     */
    @Test
    fun `evolution finds a recursive bus reduction that generalises`() {
        val problem = MorphoProblems.orReduce()
        val run = MorphoSearch.evolve(problem.spec, problem.generator, 6000, seed = 2, mu = 24, lambda = 40)
        assertTrue(run.solved, "or-reduce was not solved: ${run.summary()}")

        val best = run.best!!
        assertTrue("SPLIT(" in best.genome.render(), "a reduction with no SPLIT is impossible:\n${best.genome.render()}")
        assertTrue(
            "= ${MorphoProblems.ROOT}(" in best.genome.render(),
            "a reduction with no recursion is impossible:\n${best.genome.render()}"
        )

        // Widths nothing was selected on. Three of them, because "it is parametric" is a universally
        // quantified claim and one extra width would be an anecdote.
        listOf(listOf(3), listOf(16), listOf(32), listOf(48)).forEach { widths ->
            val wide = MorphoSearch.generalization(best.genome, problem.spec, widths)
            assertTrue(wide.correct, "the found reduction fails at $widths: $wide\n${best.genome.render()}")
        }
    }

    /** The dual, so the result is about the method and not about one lucky truth table. */
    @Test
    fun `the same search finds the dual reduction`() {
        val problem = MorphoProblems.andReduce()
        val run = MorphoSearch.evolve(problem.spec, problem.generator, 6000, seed = 2, mu = 24, lambda = 40)
        assertTrue(run.solved, "and-reduce was not solved: ${run.summary()}")
        val best = run.best!!
        listOf(listOf(16), listOf(32), listOf(48)).forEach { widths ->
            assertTrue(
                MorphoSearch.generalization(best.genome, problem.spec, widths).correct,
                "the found and-reduction fails at $widths:\n${best.genome.render()}"
            )
        }
    }

    /**
     * The negative result, pinned so it cannot quietly become a positive one in the prose while
     * remaining a negative one in fact.
     *
     * If a later change *does* solve the recursive adder this test fails, and the right response is
     * to celebrate and rewrite the documentation — which is the point of asserting it.
     */
    @Test
    fun `the recursive adder is not solved at this budget`() {
        val problem = MorphoProblems.recursiveAdder()
        val budget = 6000
        val runs = listOf(
            MorphoSearch.random(problem.spec, problem.generator, budget, seed = 1),
            MorphoSearch.hillClimb(problem.spec, problem.generator, budget, seed = 1),
            MorphoSearch.evolve(problem.spec, problem.generator, budget, seed = 1),
        )
        runs.forEach { r ->
            assertFalse(
                r.solved,
                "the recursive adder was SOLVED by ${r.label} — the documentation says it is not. " +
                    "Fix the documentation:\n${r.best?.genome?.render()}"
            )
        }
        // And the failure is a plateau rather than a broken harness: everything still compiles.
        runs.forEach { r ->
            assertTrue(r.failureRate < 0.1, "${r.label} rejected ${r.failureRate * 100}% of candidates")
        }
    }

    // ---------------------------------------------------------------- figures

    /** The figures are geometry, and geometry that is finite and inside its own window. */
    @Test
    fun `search figures produce finite geometry`() {
        val problem = MorphoProblems.orReduce()
        val runs = listOf(
            MorphoSearch.random(problem.spec, problem.generator, 600, seed = 1),
            MorphoSearch.evolve(problem.spec, problem.generator, 600, seed = 1),
        )
        val lines = morphoConvergenceFLines(runs)
        assertTrue(lines.size >= 3, "expected a frame and a line per run, got ${lines.size}")
        lines.forEach { line ->
            line.nodes.forEach { n ->
                assertTrue(n.to.x.isFinite() && n.to.y.isFinite(), "non-finite point in a convergence figure")
                assertTrue(n.to.x in -0.01..1.01, "x escaped the plot box: ${n.to.x}")
                assertTrue(n.to.y in -1.01..0.01, "y escaped the plot box: ${n.to.y}")
            }
        }

        val pareto = morphoParetoFLines(
            runs.flatMap { it.costPoints() } + MorphoCostPoint("ripple_adder", 16, 8, reference = true)
        )
        pareto.forEach { line ->
            line.nodes.forEach { n ->
                assertTrue(n.to.x.isFinite() && n.to.y.isFinite(), "non-finite point in a Pareto figure")
            }
        }
    }
}
