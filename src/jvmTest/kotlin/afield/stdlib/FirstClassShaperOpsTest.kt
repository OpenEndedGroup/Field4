package afield.stdlib

import kotlin.math.absoluteValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `abs`, `flip`, `exp` and `sign` became first-class graph ops on 2026-07-31, having previously been
 * built with `mapY { … }` — an opaque `(Double) -> Double` closure in a node labelled with the
 * *child's* name, so the graph did not record that an abs had happened at all.
 *
 * That is what this file pins, in four separable parts:
 *
 * 1. **Nothing evaluates differently.** The op change is structural; the numbers are bit-identical to
 *    what the `mapY` closures computed, checked against an independent re-implementation of the old
 *    lambda across a grid.
 * 2. **The graph now records the op**, is rebuildable, and round-trips through `OpRegistry`.
 * 3. **The derivatives are right**, checked against a central-difference oracle at many inputs
 *    *including the kink at zero*, and one at a time so a wrong sign in one op cannot be masked.
 * 4. **`sign` is still refused, and `mapY` still is too** — deliberately, for reasons stated in
 *    `FnNonDifferentiable`.
 *
 * The gradient-vs-oracle sweep over whole graphs lives in `AutoDiffFnBridgeTest`, which now carries
 * `exp`/`flip`/`abs` graphs (and the user-reported `cos(1.9).abs() * sin(1.3)` shape) through its
 * three-theta oracle comparison. What is added here is the *pointwise* check at chosen inputs — the
 * kink in particular, which a graph-level sweep only hits by luck.
 */
class FirstClassShaperOpsTest {

    // -----------------------------------------------------------------------------------------
    // 1. Backward compatibility: the same numbers, verified rather than assumed
    // -----------------------------------------------------------------------------------------

    /**
     * The pre-change definitions, re-implemented here from `Functions.kt` as it stood before the op
     * pass, so "evaluation is unchanged" is a comparison and not an assertion about a diff.
     *
     * Old bodies: `flip = mapY(name) { -it }`, `abs = mapY(name) { kotlin.math.abs(it) }`,
     * `sign = mapY(name) { kotlin.math.sign(it) }`, `exp = mapY(name) { kotlin.math.exp(it) }`.
     */
    private fun legacyShapers(source: Functions.Fn): List<Triple<String, Functions.Fn, (Double) -> Double>> =
        with(Functions) {
            listOf(
                Triple("flip", source.flip(), { v: Double -> -v }),
                Triple("abs", source.abs(), { v: Double -> kotlin.math.abs(v) }),
                Triple("sign", source.sign(), { v: Double -> kotlin.math.sign(v) }),
                Triple("exp", source.exp(), { v: Double -> kotlin.math.exp(v) })
            )
        }

    @Test
    fun evaluationIsBitIdenticalToTheOldMapYClosures() = with(Functions) {
        // Two sources: one that crosses zero (so abs folds and sign takes all three of its values)
        // and one held strictly positive.
        for (source in listOf(sin(1.3, amplitude = 0.9), sin(1.1, amplitude = 0.4, offset = 1.7))) {
            for ((name, built, oldLambda) in legacyShapers(source)) {
                for (i in 0..512) {
                    val x = i / 512.0
                    val expected = oldLambda(source.eval(x))
                    assertEquals(
                        expected.toRawBits(), built.eval(x).toRawBits(),
                        "$name at x=$x: old mapY closure gave $expected, the op gives ${built.eval(x)}"
                    )
                }
            }
        }
    }

    @Test
    fun theDomainAndNameSurviveExactlyAsMapYLeftThem() = with(Functions) {
        val source = sin(1.2, amplitude = 0.8).withName("gesture")
        for ((name, built, _) in legacyShapers(source)) {
            assertEquals(source.domain, built.domain, "$name: domain moved")
            // `mapY(name)` passed the *child's* name through, and so does the op.
            assertEquals(source.name, built.name, "$name: name moved")
        }
    }

    /** `sign` really does take all three of its values on the crossing source, so the test above is
     *  exercising the whole comparator rather than one branch of it. */
    @Test
    fun theCrossingSourceActuallyExercisesEveryBranch() = with(Functions) {
        val source = sin(1.3, amplitude = 0.9)
        val values = (0..512).map { source.eval(it / 512.0) }
        assertTrue(values.any { it > 0.0 }, "source never goes positive")
        assertTrue(values.any { it < 0.0 }, "source never goes negative")
        assertEquals(0.0, source.eval(0.0), 0.0, "x=0 should sit exactly on abs's kink")
    }

    // -----------------------------------------------------------------------------------------
    // 2. The graph records the op, and it round-trips
    // -----------------------------------------------------------------------------------------

    @Test
    fun eachShaperIsItsOwnOpRatherThanAnOpaqueLambda() = with(Functions) {
        val source = sin(1.2, amplitude = 0.8)
        val expected = mapOf(
            "flip" to source.flip(), "abs" to source.abs(),
            "sign" to source.sign(), "exp" to source.exp()
        )
        for ((op, fn) in expected) {
            val node = fn.graphNode()
            assertEquals(op, node.op, "the node should name the op it performed")
            // The old `mapY` node was labelled with the child's name and tagged "lambda", which is
            // exactly what made the function unrecoverable by inspection.
            assertEquals(op, node.label, "$op: the label should say what happened")
            assertFalse("lambda" in node.tags, "$op: should no longer be tagged as a user lambda")
            assertEquals(1, node.children.size, "$op: one child")
            assertEquals("source", node.children[0].role, "$op: addressed as `source`")
            assertTrue(OpRegistry.knows(op), "$op: OpRegistry should know it")
        }
    }

    @Test
    fun eachShaperRebuildsThroughOpRegistryAndSurvivesTheRoundTrip() = with(Functions) {
        // A value transform with a `source` child, which is what OpRegistry.rebuild needs.
        val start = sin(1.0).clip(0.9)
        for (op in listOf("exp", "abs", "flip", "sign")) {
            val rebuilt = OpRegistry.rebuild(start, op)
            assertNotNull(rebuilt, "rebuild(clip-node, $op) must be non-null")
            assertEquals(op, rebuilt.graphNode().op)
            for (i in 0..20) assertTrue(rebuilt.eval(i / 20.0).isFinite(), "$op finite at x=${i / 20.0}")
        }
        // Aliases resolve to the same canonical op.
        assertEquals("abs", OpRegistry.canonical("absoluteValue"))
        assertEquals("flip", OpRegistry.canonical("negate"))
        assertEquals("sign", OpRegistry.canonical("signum"))
        // And the rewrite DSL can reach them by name, canonical and aliased.
        assertEquals("abs", sin(1.0).clip(0.9).rewrite("clip => op=abs").graphNode().op)
        assertEquals("exp", sin(1.0).clip(0.9).rewrite("clip => op=exp").graphNode().op)
        assertEquals("flip", sin(1.0).clip(0.9).rewrite("clip => op=negate").graphNode().op)
    }

    /** The node's own `rebuild` hook — what the rewrite layer uses to replace a child in place —
     *  reconstructs the same op over the new child rather than losing it. */
    @Test
    fun theRebuildHookPreservesTheOpWhenAChildIsReplaced() = with(Functions) {
        val replacement = sin(2.0, amplitude = 0.5)
        for (op in listOf("exp", "abs", "flip", "sign")) {
            val original = OpRegistry.rebuild(sin(1.0).clip(0.9), op)!!
            val node = original.graphNode()
            val rebuilt = node.rebuild!!.invoke(listOf(replacement))
            assertEquals(op, rebuilt.graphNode().op, "$op: rebuild lost the op")
            for (i in 0..20) {
                val x = i / 20.0
                // The rebuilt node computes the same shaper over the new child.
                val direct = OpRegistry.rebuild(replacement.withName(replacement.name), op)!!
                assertEquals(direct.eval(x), rebuilt.eval(x), 1e-12, "$op at $x")
            }
        }
    }

    /**
     * The declared consequence of this change, pinned so it cannot drift unnoticed.
     *
     * `FnMutations.substituteOp` draws its replacement with
     * `opsInFamily(family)[rng.nextInt(targets.size)]` (`FunctionEvolve.kt:63-65`), so the *size* of a
     * family is part of every seeded `evolve` run's random stream. Adding four ops to `transform` took
     * it from 15 to 19 and therefore changes which op a given seed picks — see
     * `dev_docs/stdlib_behaviour_changes.md`. Asserting the size here makes the next addition a
     * conscious decision rather than a silent reproducibility break.
     */
    @Test
    fun theTransformFamilySizeIsPinnedBecauseSeededEvolveDrawsFromIt() {
        val transform = OpRegistry.opsInFamily("transform")
        assertEquals(
            19, transform.size,
            "the transform family changed size, which reseeds every evolve run that mutates a " +
                "transform node — declare it in dev_docs/stdlib_behaviour_changes.md: $transform"
        )
        for (op in listOf("exp", "abs", "flip", "sign")) {
            assertTrue(op in transform, "$op should be in the transform family")
            assertTrue(OpRegistry.isTransmutable(op), "$op should be a transmutation target")
        }
    }

    // -----------------------------------------------------------------------------------------
    // 3. The derivatives, against a central-difference oracle, one op at a time
    // -----------------------------------------------------------------------------------------

    /**
     * `d/dθ` of each shaper applied to a single tunable leaf, compared against a central difference
     * of the *same* graph at a spread of θ — including θ = 0, which for `abs` is exactly the kink.
     *
     * Structured as `shaper(scaleY(identity, θ))` so there is exactly one parameter and the
     * derivative is analytic by hand as well as by oracle: at x = 1 the inner value *is* θ, so
     * `d|θ|/dθ` is `sign θ`, `d(-θ)/dθ` is `-1` and `d(exp θ)/dθ` is `exp θ`.
     */
    private fun shaperGradientAt(op: String, theta: Double, x: Double): Double = with(Functions) {
        val inner = identity(Functions.Domain(0.0, 1.0)).scaleY(theta)
        val fn = OpRegistry.rebuild(inner.clip(9.9), op)
            ?: error("no rebuild for $op")
        // clip(9.9) is a no-op on this range but gives rebuild the `source` child it requires; the
        // graph therefore carries two leaves (the scale and the threshold). Index 0 is the scale.
        val leaves = fn.extractParameters()
        val scaleIndex = leaves.indexOfFirst { it == theta }
        require(scaleIndex >= 0) { "$op: could not find the scale leaf in ${leaves.toList()}" }
        fn.gradientAt(x).gradient[scaleIndex]
    }

    private fun shaperValueAt(op: String, theta: Double, x: Double): Double = with(Functions) {
        val inner = identity(Functions.Domain(0.0, 1.0)).scaleY(theta)
        (OpRegistry.rebuild(inner.clip(9.9), op) ?: error("no rebuild for $op")).eval(x)
    }

    /** Central difference of the graph's own value with respect to θ — the oracle. */
    private fun oracleAt(op: String, theta: Double, x: Double, h: Double = 1e-6): Double =
        (shaperValueAt(op, theta + h, x) - shaperValueAt(op, theta - h, x)) / (2.0 * h)

    /**
     * Nine θ values per op, spanning both signs and **including exactly zero** — abs's kink. A
     * universally-quantified claim checked at one input is how two wrong claims shipped on
     * 2026-07-30, so the sweep is wide and the kink is not a special case bolted on the side.
     */
    private val thetas = listOf(-2.3, -1.0, -0.4, -1e-3, 0.0, 1e-3, 0.4, 1.0, 2.3)

    @Test
    fun absMatchesACentralDifferenceOracleEitherSideOfTheKinkAndAtIt() {
        for (theta in thetas) {
            val exact = shaperGradientAt("abs", theta, 1.0)
            val oracle = oracleAt("abs", theta, 1.0)
            assertEquals(
                oracle, exact, 1e-6 * (1.0 + oracle.absoluteValue),
                "abs at theta=$theta: exact $exact, central difference $oracle"
            )
        }
    }

    /**
     * The kink convention, stated rather than inferred: `d|x|/dx` at exactly zero is **0** here, the
     * even split — which is also what the central difference reports, hence the agreement above.
     */
    @Test
    fun absReportsTheEvenSplitSubgradientAtTheKink() {
        assertEquals(0.0, shaperGradientAt("abs", 0.0, 1.0), 0.0, "abs'(0) should be the even split, 0")
        assertEquals(0.0, oracleAt("abs", 0.0, 1.0), 1e-12, "a central difference agrees at the kink")
        // Either side it is an honest +/-1, so the 0 is genuinely the tie and not a flat spot.
        assertEquals(1.0, shaperGradientAt("abs", 0.5, 1.0), 1e-12)
        assertEquals(-1.0, shaperGradientAt("abs", -0.5, 1.0), 1e-12)
    }

    @Test
    fun flipMatchesACentralDifferenceOracleEverywhere() {
        for (theta in thetas) {
            val exact = shaperGradientAt("flip", theta, 1.0)
            val oracle = oracleAt("flip", theta, 1.0)
            assertEquals(oracle, exact, 1e-6, "flip at theta=$theta: exact $exact, oracle $oracle")
            assertEquals(-1.0, exact, 1e-12, "flip's derivative is -1 everywhere")
        }
    }

    @Test
    fun expMatchesACentralDifferenceOracleAndIsItsOwnDerivative() {
        for (theta in thetas) {
            val exact = shaperGradientAt("exp", theta, 1.0)
            val oracle = oracleAt("exp", theta, 1.0)
            assertEquals(
                oracle, exact, 1e-6 * (1.0 + oracle.absoluteValue),
                "exp at theta=$theta: exact $exact, oracle $oracle"
            )
            // d(exp t)/dt == exp t == the node's own value.
            assertEquals(
                shaperValueAt("exp", theta, 1.0), exact, 1e-9 * (1.0 + exact.absoluteValue),
                "exp's derivative should equal its value at theta=$theta"
            )
        }
    }

    /**
     * The same oracle at several **x**, not only at x = 1, so the check is not accidentally about one
     * position. `abs` folds where the inner ramp is negative, which for a negative θ is the whole
     * interval, and the derivative has to follow the fold.
     */
    @Test
    fun theOraclesAgreeAcrossXAsWellAsAcrossTheta() {
        for (op in listOf("abs", "flip", "exp")) {
            for (theta in listOf(-1.4, -0.25, 0.0, 0.25, 1.4)) {
                for (x in listOf(0.0, 0.17, 0.5, 0.83, 1.0)) {
                    val exact = shaperGradientAt(op, theta, x)
                    val oracle = oracleAt(op, theta, x)
                    assertEquals(
                        oracle, exact, 1e-6 * (1.0 + oracle.absoluteValue),
                        "$op at theta=$theta, x=$x: exact $exact, oracle $oracle"
                    )
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // 4. What stays refused, and why
    // -----------------------------------------------------------------------------------------

    @Test
    fun theNewShapersAreClassifiedExactlyAsIntended() {
        for (op in listOf("abs", "flip", "exp")) {
            assertTrue(op in FnAutoDiff.differentiableOps, "$op should be differentiable now")
            assertNull(FnNonDifferentiable.statusOf(op), "$op should not also be declared undifferentiable")
        }
        // `sign` gets the op but not a formula: its derivative is 0 wherever it exists, so a gradient
        // through it is always exactly zero and a fit would report convergence without moving.
        assertFalse("sign" in FnAutoDiff.differentiableOps, "sign must NOT be differentiable")
        val status = FnNonDifferentiable.statusOf("sign")
        assertNotNull(status, "sign needs a declared reason, not a silent gap")
        assertFalse(status.closable, "sign is not a backlog item — there is no honest derivative")
        assertTrue("zero" in status.reason, "the reason should say why: '${status.reason}'")
    }

    /** A genuine user lambda stays refused. That is the feature, not the gap this change closed. */
    @Test
    fun mapYIsStillRefusedAndStillForTheRightReason() = with(Functions) {
        val status = FnNonDifferentiable.statusOf("mapY")
        assertNotNull(status)
        assertFalse(status.closable, "a user lambda has no formula to read — it must stay unclosable")
        assertTrue("lambda" in status.reason, "reason: '${status.reason}'")

        val seed = sin(1.3, amplitude = 1.0).mapY { kotlin.math.tanh(it * 3.0) }
        assertFalse(FnAutoDiff.supports(seed), "a mapY seed must not be supported")
        val refusal = exactFitRefusal(sin(1.5, amplitude = 0.8), seed, Functions.Domain.DEFAULT)
        assertNotNull(refusal, "a mapY seed must be refused")
        assertTrue("mapY" in refusal, "the refusal should name mapY: $refusal")
    }

    @Test
    fun aSignSeedIsRefusedByNameRatherThanSilentlyGivenAZeroGradient() = with(Functions) {
        val seed = cos(1.9).sign() * sin(1.3, amplitude = 1.0)
        assertFalse(FnAutoDiff.supports(seed))
        val refusal = exactFitRefusal(cos(2) * sin(1.5, amplitude = 0.8, offset = 0.2), seed)
        assertNotNull(refusal, "a sign seed must be refused")
        assertTrue("sign" in refusal, "the refusal should name sign: $refusal")
    }

    // -----------------------------------------------------------------------------------------
    // The acceptance test: the expression a human typed and had refused
    // -----------------------------------------------------------------------------------------

    /**
     * The reported case. `cos(1.9).abs() * sin(1.3, amplitude = 1.0)` used to be refused with
     * "non-differentiable ops mapY"; it must now run exactly, and converge.
     */
    @Test
    fun theReportedAbsSeedNowFitsExactlyAndConverges() = with(Functions) {
        val target = cos(2) * sin(1.5, amplitude = 0.8, offset = 0.2)
        val seed = cos(1.9).abs() * sin(1.3, amplitude = 1.0)

        assertTrue(FnAutoDiff.supports(seed), "unsupported: ${FnAutoDiff.unsupportedOps(seed)}")
        assertNull(exactFitRefusal(target, seed), "the exact path must no longer refuse this seed")

        val exact = fitGradient(target, seed, iterations = 400, lr = 0.05, gradient = FnGradientMode.EXACT)
        assertTrue(exact.usedExactGradient, "the run must actually have used the exact gradient")
        assertTrue(exact.improved, "RMSE ${exact.initialError} -> ${exact.finalError} is not an improvement")
        // Genuine convergence, not a token step: the residual has to come down by more than half.
        assertTrue(
            exact.finalError < exact.initialError * 0.5,
            "RMSE only fell from ${exact.initialError} to ${exact.finalError}"
        )

        // And the same seed on the historic path, for the comparison the docs box reports.
        val fd = fitGradient(target, seed, iterations = 400, lr = 0.05)
        assertFalse(fd.usedExactGradient)
        println(
            "ABS ACCEPTANCE: finite differences RMSE ${fd.initialError} -> ${fd.finalError} " +
                "in ${fd.evaluations} loss evaluations; exact RMSE ${exact.initialError} -> " +
                "${exact.finalError} in ${exact.evaluations} loss evaluations"
        )
        // The exact path is a different arithmetic route to the same landscape, so it must start from
        // the same loss; only the descent may differ.
        assertEquals(fd.initialError, exact.initialError, 1e-9, "the two arms must share a landscape")
    }

    /** The smooth case the human tried first, kept beside it so the comparison is like for like. */
    @Test
    fun theSmoothSeedStillFitsExactlyToo() = with(Functions) {
        val target = cos(2) * sin(1.5, amplitude = 0.8, offset = 0.2)
        val seed = cos(1.9) * sin(1.3, amplitude = 1.0)
        val exact = fitGradient(target, seed, iterations = 400, lr = 0.05, gradient = FnGradientMode.EXACT)
        val fd = fitGradient(target, seed, iterations = 400, lr = 0.05)
        assertTrue(exact.usedExactGradient)
        assertTrue(exact.finalError < exact.initialError * 0.5)
        println(
            "SMOOTH ACCEPTANCE: finite differences RMSE ${fd.initialError} -> ${fd.finalError} " +
                "in ${fd.evaluations} loss evaluations; exact RMSE ${exact.initialError} -> " +
                "${exact.finalError} in ${exact.evaluations} loss evaluations"
        )
    }
}
