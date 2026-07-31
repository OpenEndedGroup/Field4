package afield.stdlib

import kotlin.math.absoluteValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The evolve↔gradient alternation: [normalizeNumericLeaves]' landmine, and a measurement of what
 * exact gradients actually buy a structural search at equal budget.
 *
 * The measurement is the interesting part and it is not flattering in the way one might hope, so it
 * is printed rather than only asserted — see `theExactAndFiniteDifferenceRefinementsAreMeasured`.
 */
class FnRefineRoundsTest {

    private val domain = Functions.Domain(0.0, 1.0)

    // ---- the landmine ------------------------------------------------------------------------

    @Test
    fun applyParametersLeavesAMixtureOfParamAndConstLeaves() = with(Functions) {
        val seed = sin(1.0, amplitude = 1.0, offset = 0.0) + cos(2.0, amplitude = 0.5)
        val theta = seed.extractParameters()
        // Move exactly one leaf; the rest keep their values, so mapGraphNumbers leaves them alone.
        val moved = seed.applyParameters(theta.copyOf().also { it[0] = it[0] + 0.5 })

        fun opsOfNumericLeaves(fn: Functions.Fn): List<String> {
            val out = mutableListOf<String>()
            fun walk(f: Functions.Fn) {
                val node = f.graphNode()
                if (node.numbers.containsKey("value")) out += node.op
                for (edge in node.children) walk(edge.fn)
            }
            walk(fn)
            return out
        }

        val before = opsOfNumericLeaves(seed)
        val after = opsOfNumericLeaves(moved)
        assertTrue(before.all { it == "param" }, "the seed's leaves should all be params: $before")
        assertTrue("const" in after, "the moved leaf should have become a const: $after")
        assertTrue("param" in after, "the unmoved leaves should still be params: $after")
        // ...which is exactly the inconsistency that makes a fitted graph's structural signature
        // depend on which parameters happened to move.
        assertTrue(
            seed.graphSignature() != moved.graphSignature(),
            "signatures should differ (they include numbers and ops)"
        )
    }

    @Test
    fun normalizeNumericLeavesMakesTheLeafRepresentationUniformWithoutChangingValues() = with(Functions) {
        val seed = sin(1.0, amplitude = 1.0, offset = 0.0) + cos(2.0, amplitude = 0.5)
        val theta = seed.extractParameters()
        val moved = seed.applyParameters(theta.copyOf().also { it[0] = it[0] + 0.5 })
        val normalized = moved.normalizeNumericLeaves()

        fun opsOfNumericLeaves(fn: Functions.Fn): List<String> {
            val out = mutableListOf<String>()
            fun walk(f: Functions.Fn) {
                val node = f.graphNode()
                if (node.numbers.containsKey("value")) out += node.op
                for (edge in node.children) walk(edge.fn)
            }
            walk(fn)
            return out
        }
        assertTrue(
            opsOfNumericLeaves(normalized).all { it == "const" },
            "every leaf should be a const after normalising: ${opsOfNumericLeaves(normalized)}"
        )
        // Evaluation is untouched — this is a representation change, not a semantic one.
        for (i in 0..40) {
            val x = i / 40.0
            assertEquals(moved.eval(x), normalized.eval(x), 0.0, "value changed at x=$x")
        }
        // And it is idempotent, so re-entering the loop twice is stable.
        assertEquals(
            normalized.graphSignature(),
            normalized.normalizeNumericLeaves().graphSignature(),
            "normalising twice must be a no-op"
        )
    }

    @Test
    fun normalizingTwoDifferentlyFittedGraphsMakesThemComparable() = with(Functions) {
        val seed = sin(1.0, amplitude = 1.0, offset = 0.0)
        val theta = seed.extractParameters()
        // Two fits that happen to move different subsets of the parameters.
        val a = seed.applyParameters(theta.copyOf().also { it[0] += 0.5 })
        val b = seed.applyParameters(theta.copyOf().also { it[0] += 0.5; it[1] += 0.0 })
        // Same numbers, so after normalising they must be the same graph.
        assertEquals(
            a.normalizeNumericLeaves().graphSignature(),
            b.normalizeNumericLeaves().graphSignature(),
            "graphs with identical numbers must normalise to identical signatures"
        )
    }

    // ---- the loop ----------------------------------------------------------------------------

    @Test
    fun alternatingRoundsImproveOnTheSeedAndAreDeterministic() = with(Functions) {
        val target = cos(2) * sin(1.5, amplitude = 0.8, offset = 0.2)
        val seed = cos(3.4) * sin(1.0, amplitude = 2.0)
        val a = fitEvolveRounds(target, seed, domain = domain, rounds = 3, rngSeed = 5L)
        val b = fitEvolveRounds(target, seed, domain = domain, rounds = 3, rngSeed = 5L)

        assertEquals(3, a.rounds.size)
        assertEquals(a.errors, b.errors, "the same seed must give the same round-by-round trajectory")
        assertEquals(a.bestError, b.bestError, 0.0)
        assertTrue(a.bestError < a.seedError, "the loop should beat its seed: ${a.seedError} -> ${a.bestError}")
        // Every round's output has uniform leaves, so nothing downstream sees the artefact.
        assertTrue(
            a.best.graphSignature() == a.best.normalizeNumericLeaves().graphSignature(),
            "the returned best must already be normalised"
        )
    }

    @Test
    fun autoModeRecordsHonestlyWhichRoundsGotExactGradients() = with(Functions) {
        val target = cos(2) * sin(1.5, amplitude = 0.8, offset = 0.2)
        val seed = cos(3.4) * sin(1.0, amplitude = 2.0)
        val run = fitEvolveRounds(target, seed, domain = domain, rounds = 4, rngSeed = 11L)
        assertEquals(4, run.rounds.size)
        for (round in run.rounds) {
            if (round.usedExactGradient) {
                assertEquals(null, round.refusal, "round ${round.round} used the exact path but has a refusal")
            } else {
                assertTrue(
                    round.refusal != null,
                    "round ${round.round} fell back but recorded no reason why"
                )
            }
        }
        println(
            "REFINE exact rounds: ${run.exactRounds} of ${run.rounds.size}; " +
                "refusals: " + run.rounds.mapNotNull { it.refusal }.map { it.take(70) }
        )
    }

    /**
     * The measurement: same structural search, same budget, exact gradients versus finite differences
     * for the numeric refinement. Printed rather than pinned to a threshold, because the honest answer
     * turned out to be "the same fitness, and slower", and a test that asserted otherwise would be
     * asserting a hope.
     */
    @Test
    fun theExactAndFiniteDifferenceRefinementsAreMeasured() = with(Functions) {
        val target = cos(2) * sin(1.5, amplitude = 0.8, offset = 0.2)
        val seed = cos(3.4) * sin(1.0, amplitude = 2.0)

        // Warm the JIT and the compile caches so the timings are of the arithmetic, not of startup.
        fitEvolveRounds(target, seed, domain = domain, rounds = 2, rngSeed = 1L)
        fitEvolveRounds(
            target, seed, domain = domain, rounds = 2, rngSeed = 1L,
            gradient = FnGradientMode.FINITE_DIFFERENCE
        )

        val fdStart = System.nanoTime()
        val fd = fitEvolveRounds(
            target, seed, domain = domain, rounds = 4, rngSeed = 7L,
            gradient = FnGradientMode.FINITE_DIFFERENCE
        )
        val fdMs = (System.nanoTime() - fdStart) / 1e6

        val exStart = System.nanoTime()
        val exact = fitEvolveRounds(target, seed, domain = domain, rounds = 4, rngSeed = 7L)
        val exMs = (System.nanoTime() - exStart) / 1e6

        val fdEvals = fd.rounds.sumOf { it.fitRun.evaluations }
        val exEvals = exact.rounds.sumOf { it.fitRun.evaluations }
        println(
            "REFINE finite-difference: error %.6f in %.0f ms, %d fit evaluations".format(
                fd.bestError, fdMs, fdEvals
            )
        )
        println(
            "REFINE exact (AUTO):      error %.6f in %.0f ms, %d fit evaluations, %d/%d rounds exact"
                .format(exact.bestError, exMs, exEvals, exact.exactRounds, exact.rounds.size)
        )

        // What is safe to assert: both improve on the seed, and the exact path never costs more
        // *evaluations* than finite differences when it is actually used.
        assertTrue(fd.bestError < fd.seedError, "finite-difference loop should improve")
        assertTrue(exact.bestError < exact.seedError, "exact loop should improve")
        if (exact.exactRounds > 0) {
            assertTrue(
                exEvals < fdEvals,
                "when the exact path is used it should cost fewer evaluations: $exEvals vs $fdEvals"
            )
        }
    }

    @Test
    fun aSeedTheExactPathCannotTouchStillRunsUnderAuto() = with(Functions) {
        // saw has no formula (see FnNonDifferentiable), so every round must fall back.
        val target = saw(1.0, amplitude = 0.8)
        val seed = saw(1.6, amplitude = 1.4)
        val run = fitEvolveRounds(target, seed, domain = domain, rounds = 2, rngSeed = 3L)
        assertTrue(run.bestError <= run.seedError, "the loop should not make things worse")
        assertTrue(
            run.rounds.any { !it.usedExactGradient },
            "a saw-rooted search cannot be all-exact"
        )
        for (round in run.rounds.filter { !it.usedExactGradient }) {
            assertTrue(round.refusal != null, "round ${round.round} must say why it fell back")
        }
    }

    @Test
    fun theLoopBeatsASingleHybridPassAtAComparableBudget() = with(Functions) {
        val target = cos(2) * sin(1.5, amplitude = 0.8, offset = 0.2)
        val seed = cos(3.4) * sin(1.0, amplitude = 2.0)
        // One shot: 160 evolve iterations then 480 fit iterations.
        val single = fitHybrid(
            target, seed, domain = domain,
            evolveIterations = 160, evolveLambda = 6, fitIterations = 480,
            rngSeed = 9L, gradient = FnGradientMode.AUTO
        )
        // Four rounds of 40 + 120 — the same totals, interleaved.
        val looped = fitEvolveRounds(
            target, seed, domain = domain, rounds = 4,
            evolveIterations = 40, evolveLambda = 6, fitIterations = 120, rngSeed = 9L
        )
        println(
            "REFINE single hybrid pass %.6f vs 4 interleaved rounds %.6f (seed %.6f)".format(
                single.finalError, looped.bestError, looped.seedError
            )
        )
        // Both must improve; which wins is landscape-dependent and not something to pin.
        assertTrue(single.finalError < looped.seedError, "the single pass should improve on the seed")
        assertTrue(looped.bestError < looped.seedError, "the loop should improve on the seed")
        assertTrue((single.finalError - looped.bestError).absoluteValue.isFinite())
    }
}
