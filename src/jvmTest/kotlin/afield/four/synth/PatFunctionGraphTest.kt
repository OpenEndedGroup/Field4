package afield.four.synth

import afield.stdlib.FnNonDifferentiable
import afield.stdlib.Functions
import afield.stdlib.OpRegistry
import afield.stdlib.applyParameters
import afield.stdlib.deepCopy
import afield.stdlib.extractParameters
import afield.stdlib.fitGradient
import afield.stdlib.graphNode
import afield.stdlib.graphText
import afield.stdlib.parameterCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `pat` graph op: a rhythm as a first-class node of an `Fn` graph rather than an opaque lambda
 * that happens to produce numbers.
 *
 * Three things are being pinned here, and they are worth naming separately because only the first
 * is new surface. **That the node exists and is honest**: its numbers are the pattern's numbers, its
 * source text is the pattern's text, and a rebuild from it reproduces the same curve. **That the
 * hoisting changed nothing** — the graph path resolves every atom parameter through a `param` leaf
 * instead of through `toDouble()`, and the resulting curve has to be bit-identical to the literal
 * path it replaced, at every sample. And **that the evaluator is a function of x alone**, which is
 * the prerequisite for any of it: `fitGradient` samples non-monotonically, so an evaluator that
 * answered differently depending on the order it was asked would be fitting a moving target.
 */
class PatFunctionGraphTest {

    private fun samples(fn: Functions.Fn, from: Double = 0.0, to: Double = 2.0, count: Int = 201): DoubleArray =
        DoubleArray(count) { i -> fn.evalSafe(from + (to - from) * i / (count - 1)) }

    /** The same pattern with the graph path deliberately disabled, for a literal-path comparison. */
    private fun literalPath(source: String, cps: Double, range: Double): Functions.Fn =
        pat(source).asFn(cps = cps, range = range, atomDecoder = { event, lookAhead ->
            decodePatFunctionAtom(event, lookAhead)
        })

    // ── the node ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a string-atom pattern carries a pat node whose leaves are the pattern's numbers`() {
        val fn = patFn("g:(0.3,0.12) adsr:(0.8,0.01,0.08,0.4,0.2)", cps = 2.0, range = 0.9)
        val node = fn.graphNode()

        assertEquals("pat", node.op)
        assertTrue("pat" in node.tags)
        assertEquals("g:(0.3,0.12) adsr:(0.8,0.01,0.08,0.4,0.2)", node.strings["source"])

        // cps, then every numeric position of every atom, in text order, addressed by role.
        assertEquals(
            listOf(
                "cps",
                "atom0.value", "atom0.sigma",
                "atom1.value", "atom1.attack", "atom1.decay", "atom1.sustain", "atom1.release"
            ),
            node.children.map { it.role }
        )
        // Every child is a numeric leaf, so every child is a tunable parameter.
        assertEquals(8, fn.parameterCount())
        assertEquals(
            listOf(2.0, 0.3, 0.12, 0.8, 0.01, 0.08, 0.4, 0.2),
            fn.extractParameters().toList()
        )
    }

    @Test
    fun `the evaluator's own settings ride as metadata, deliberately outside theta`() {
        val fn = patFn("g:(0.3,0.12)", cps = 2.0, range = 0.9, outside = 0.25, strict = false)
        val node = fn.graphNode()

        assertEquals(0.9, node.numbers["range"])
        assertEquals(0.25, node.numbers["outside"])
        assertEquals(false, node.booleans["strict"])
        // A look-ahead bound and an error policy are not part of the gesture: three leaves, not six.
        assertEquals(3, fn.parameterCount())
    }

    @Test
    fun `the node is known to the registry, is not a transmutation target, and says why it has no derivative`() {
        assertTrue(OpRegistry.knows("pat"))
        // Its identity lives in pattern text, which no foreign node can supply — the same contract
        // `timeline`, `rbf` and the sequencing ops have.
        assertTrue(!OpRegistry.isTransmutable("pat"))
        val status = FnNonDifferentiable.statusOf("pat")
        assertTrue(status != null, "pat must declare its differentiability status")
        assertTrue("cps" in status.reason)
    }

    // ── the hoisting is semantically invisible ─────────────────────────────────────────────

    @Test
    fun `resolving atom numbers through leaves gives bit-identical values to reading them from the text`() {
        val source = "g:(0.3,0.12) tent:(0.8,0.2,0.7) wendland:(-0.4,0.15)"
        val graphed = patFn(source, cps = 1.5, range = 0.6)
        val literal = literalPath(source, cps = 1.5, range = 0.6)

        assertNull(literal.graph, "the custom-decoder path must stay opaque")
        assertEquals("pat", graphed.graphNode().op)
        // Not `assertEquals(expected, actual, tolerance)`: the two paths must agree exactly, because
        // they are meant to be the same arithmetic reached two ways.
        assertEquals(samples(literal).toList(), samples(graphed).toList())
    }

    @Test
    fun `env and adsr atoms hoist their envelope segments too`() {
        val source = "env:(0.7,0.02,0.15) adsr:(0.4,0.01,0.05,0.5,0.1,0.8)"
        val graphed = patFn(source, cps = 1.0, range = 0.5)

        assertEquals(
            listOf(
                "cps",
                "atom0.value", "atom0.attack", "atom0.release",
                "atom1.value", "atom1.attack", "atom1.decay", "atom1.sustain", "atom1.release",
                "atom1.weight"
            ),
            graphed.graphNode().children.map { it.role }
        )
        assertEquals(samples(literalPath(source, 1.0, 0.5)).toList(), samples(graphed).toList())
    }

    // ── rebuilding ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a deep copy of the node reproduces the curve`() {
        val fn = patFn("g:(0.3,0.12) tent:(0.9,0.18)", cps = 2.0, range = 0.7)
        val copy = fn.deepCopy()

        assertEquals("pat", copy.graphNode().op)
        assertEquals(samples(fn).toList(), samples(copy).toList())
    }

    @Test
    fun `applying the parameters back is the identity, on the curve and on the source text`() {
        val fn = patFn("g:(0.3,0.12) tent:(0.9,0.18)", cps = 2.0, range = 0.7)
        val round = fn.applyParameters(fn.extractParameters())

        assertEquals(fn.graphNode().strings["source"], round.graphNode().strings["source"])
        assertEquals(samples(fn).toList(), samples(round).toList())
    }

    @Test
    fun `moving one leaf moves the curve and comes back out as pasteable pattern text`() {
        val fn = patFn("g:(0.3,0.12) tent:(0.9,0.18)", cps = 2.0, range = 0.7)
        val theta = fn.extractParameters()
        theta[1] = 0.75 // atom0.value
        val moved = fn.applyParameters(theta)

        assertEquals("g:(0.75,0.12) tent:(0.9,0.18)", moved.graphNode().strings["source"])
        assertNotEquals(samples(fn).toList(), samples(moved).toList())
        // And the text really is the function: re-parsing it agrees with the rewritten graph.
        assertEquals(
            samples(patFn("g:(0.75,0.12) tent:(0.9,0.18)", cps = 2.0, range = 0.7)).toList(),
            samples(moved).toList()
        )
    }

    @Test
    fun `a rebuild keeps the pattern, not just the text, so a transformed pattern survives it`() {
        // `fast(2)` is not expressible in the atom text. Rebuilding from the text alone would
        // silently drop it, so the recipe holds the pattern object instead.
        val fn = pat("g:(0.3,0.12) tent:(0.9,0.18)").fast(2).asFn(cps = 1.0, range = 0.7)
        assertEquals("pat", fn.graphNode().op)
        assertEquals(samples(fn).toList(), samples(fn.deepCopy()).toList())
        assertNotEquals(
            samples(pat("g:(0.3,0.12) tent:(0.9,0.18)").asFn(cps = 1.0, range = 0.7)).toList(),
            samples(fn).toList()
        )
    }

    // ── a function of x alone ──────────────────────────────────────────────────────────────

    @Test
    fun `sampling order does not change a single value`() {
        val fn = patFn("g:(0.3,0.12) tent:(0.9,0.18) adsr:(0.5,0.01,0.05,0.4,0.1)", cps = 2.0, range = 0.6)
        val grid = DoubleArray(201) { i -> 2.0 * i / 200.0 }

        val ascending = grid.map { fn.evalSafe(it) }
        val descending = grid.reversed().map { fn.evalSafe(it) }.reversed()
        val shuffled = grid.indices.sortedBy { (it * 7919) % 201 }.map { i -> i to fn.evalSafe(grid[i]) }
            .sortedBy { it.first }.map { it.second }

        assertEquals(ascending, descending)
        assertEquals(ascending, shuffled)
    }

    @Test
    fun `a neighbourhood sweep decodes each atom once instead of restarting the pattern`() {
        // The shape `blur`, `derivative` and `convolve` all have: walk forwards, but read a little
        // way back at every step. Before the retained window this reset the stream on every
        // backwards probe and re-unrolled the pattern from cycle zero — one full re-decode per
        // output sample rather than per atom.
        var decodes = 0
        val fn = pat("g:(0.3,0.12) tent:(0.9,0.18)").asFn(cps = 1.0, range = 0.4, atomDecoder = { event, lookAhead ->
            decodes++
            decodePatFunctionAtom(event, lookAhead)
        })

        for (step in 0..100) {
            val x = step / 100.0
            fn.evalSafe(x)
            fn.evalSafe(x - 0.005)
            fn.evalSafe(x + 0.005)
        }

        // Two atoms per cycle, ~1.5 cycles unrolled: single figures. Measured rather than asserted
        // from theory — reinstating the old reset rule takes this exact sweep to 233 decodes.
        assertTrue(decodes <= 8, "expected each atom decoded about once, got $decodes decodes")
    }

    @Test
    fun `a blur over the pattern agrees with a blur over a freshly built one`() = with(Functions) {
        val source = "g:(0.3,0.12) tent:(0.9,0.18)"
        val blurred = patFn(source, cps = 1.0, range = 0.5).blur(0.02, samples = 17)
        // A second, untouched instance evaluated on the same grid: if the first had been perturbed
        // by its own neighbourhood reads, the two would part company.
        val reference = patFn(source, cps = 1.0, range = 0.5).blur(0.02, samples = 17)

        assertEquals(samples(reference, 0.1, 1.9, 91).toList(), samples(blurred, 0.1, 1.9, 91).toList())
    }

    // ── what cannot carry the node ─────────────────────────────────────────────────────────

    @Test
    fun `a source that really uses a live binding stays opaque`() {
        var knob = 0.4
        val bound = patFn("g:(%k,0.12) tent:(0.9,0.18)", cps = 1.0, range = 0.6) { name ->
            if (name == "k") knob else 0.0
        }
        assertNull(bound.graph, "a %binding is not a function of x, so it must not claim a fittable node")

        // It still works, and still tracks the knob — that behaviour is unchanged.
        val before = bound.evalSafe(0.0)
        knob = 0.9
        assertNotEquals(before, patFn("g:(%k,0.12)", cps = 1.0, range = 0.6) { _ -> knob }.evalSafe(0.0))
    }

    @Test
    fun `a resolver the text never calls on does not cost the node`() {
        val fn = patFn("g:(0.3,0.12)", cps = 1.0, range = 0.6) { _ -> 0.0 }
        assertEquals("pat", fn.graphNode().op, "no % in the text means the resolver is dead code")
    }

    @Test
    fun `typed atoms and custom decoders stay opaque`() {
        val typed = pat("a b") { atom ->
            if (atom == "a") gaussianAtom(sigma = 0.08) { x -> kotlin.math.sin(local(x) * 12.0) }
            else tentAtom(radius = 0.1) { _ -> 0.5 }
        }.asFn(cps = 2.0, maxLookAhead = 0.5)

        assertNull(typed.graph, "a user lambda cannot describe how to rebuild itself")
    }

    @Test
    fun `a stack of two source strings cannot have one string's offsets read against the other`() {
        // Both texts have an atom at offset 0. Keying overrides by offset alone would let the first
        // pattern's numbers be applied to the second pattern's atoms.
        val stacked = stack(pat("g:(0.2,0.1)"), pat("tent:(0.9,0.3)")).asFn(cps = 1.0, range = 0.5)
        val literal = stack(pat("g:(0.2,0.1)"), pat("tent:(0.9,0.3)"))
            .asFn(cps = 1.0, range = 0.5, atomDecoder = { event, lookAhead ->
                decodePatFunctionAtom(event, lookAhead)
            })

        assertEquals(samples(literal).toList(), samples(stacked).toList())
    }

    // ── the point of all of it ─────────────────────────────────────────────────────────────

    @Test
    fun `fitGradient recovers a rhythm's numbers and hands back the fitted pattern text`() {
        val target = patFn("g:(0.9,0.18) g:(0.1,0.18)", cps = 1.0, range = 0.9)
        val seed = patFn("g:(0.7,0.25) g:(0.3,0.12)", cps = 1.0, range = 0.9)

        val run = fitGradient(
            target = target,
            seed = seed,
            domain = 0.0..2.0,
            iterations = 200,
            lr = 0.02,
            samples = 96
        )

        println("PAT FIT: ${run.initialError} -> ${run.finalError}")
        println("PAT FIT SOURCE: ${seed.graphNode().strings["source"]} -> ${run.fitted.graphNode().strings["source"]}")

        assertTrue(
            run.finalError < 0.1 * run.initialError,
            "expected a real convergence, got ${run.initialError} -> ${run.finalError}"
        )
        // Stronger than a loss ratio, and the actual claim: Adam recovered the gesture's own
        // numbers — both atom values and both kernel widths — not merely something that scores well.
        val theta = run.parameters
        assertEquals(0.9, theta[1], 0.03, "atom0.value")
        assertEquals(0.18, theta[2], 0.02, "atom0.sigma")
        assertEquals(0.1, theta[3], 0.03, "atom1.value")
        assertEquals(0.18, theta[4], 0.02, "atom1.sigma")

        // The fitted graph is still a pattern, and still says so in text.
        val fittedSource = run.fitted.graphNode().strings["source"]!!
        assertNotEquals(seed.graphNode().strings["source"], fittedSource)
        // Full precision, not `patFormatNumber`'s nine places, so the text is the function:
        // re-parsing the fitted source reproduces the fitted curve exactly.
        val reparsed = patFn(fittedSource, cps = theta[0], range = 0.9)
        assertEquals(samples(run.fitted).toList(), samples(reparsed).toList())
    }

    /**
     * Characterization, not aspiration: the fit tunes numbers and cannot re-assign them between
     * atoms.
     *
     * Seeded with the target's values *swapped*, descent does not cross over — reaching the answer
     * needs a permutation, and there is no downhill path through it. What it does instead is
     * escape into wide kernels, where a normalised partition-of-unity blend flattens toward the mean
     * of its atom values, which halves the RMSE and stalls there. `fitBest`'s jittered restarts do
     * not rescue it either (16 restarts still land ~5× worse than the aligned seed above). Choosing
     * *which* atom carries which value is a structure question, so it belongs to `evolve`, not here.
     *
     * If this ever fails because the fitter got better at it, that is good news — read this comment,
     * check the improvement is real, and delete the test.
     */
    @Test
    fun `a mirrored seed stalls, because re-assigning values between atoms is a permutation`() {
        val target = patFn("g:(0.9,0.18) g:(0.1,0.18)", cps = 1.0, range = 0.9)
        val mirrored = patFn("g:(0.2,0.18) g:(0.8,0.18)", cps = 1.0, range = 0.9)

        val run = fitGradient(target, mirrored, 0.0..2.0, iterations = 200, lr = 0.02, samples = 96)

        println("PAT FIT (mirrored): ${run.initialError} -> ${run.finalError}")
        assertTrue(run.improved, "it still descends, just not to the answer")
        assertTrue(
            kotlin.math.abs(run.parameters[1] - 0.9) > 0.1,
            "the swapped assignment was not expected to be recovered, but atom0.value reached ${run.parameters[1]}"
        )
    }

    @Test
    fun `the graph renders as a readable recipe`() {
        val text = patFn("g:(0.3,0.12) tent:(0.9,0.18)", cps = 2.0).graphText()
        assertTrue("pat" in text, text)
        assertTrue("atom0.sigma" in text, text)
    }
}
