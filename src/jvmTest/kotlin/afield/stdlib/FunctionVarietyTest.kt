package afield.stdlib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FunctionVarietyTest {

    @Test
    fun behaviourDistanceIsZeroForIdenticalSignalsAndGrowsApart() = with(Functions) {
        val flat = c(0.5)
        assertEquals(0.0, flat.behaviour().distanceTo(flat.behaviour()), 0.0)

        val a = c(0.0).behaviour()
        val b = c(1.0).behaviour()
        // Constant 0 vs constant 1 sampled everywhere: RMS distance is exactly 1.
        assertEquals(1.0, a.distanceTo(b), 1e-12)

        // Finite fraction notices a mostly-divergent graph.
        assertEquals(1.0, flat.behaviour().finiteFraction, 0.0)
    }

    @Test
    fun evolveVarietyIsReproducible() = with(Functions) {
        val seed = sin(2.0) * 0.5
        val a = evolveVariety(seed, count = 5, iterations = 40, rngSeed = 7L)
        val b = evolveVariety(seed, count = 5, iterations = 40, rngSeed = 7L)

        assertEquals(a.results.size, b.results.size)
        assertEquals(
            a.results.map { it.graphSignature() },
            b.results.map { it.graphSignature() },
            "same seed must yield an identical set"
        )
        assertEquals(a.meanPairwiseDistance, b.meanPairwiseDistance, 0.0)
    }

    @Test
    fun evolveVarietyIsMoreDiverseThanRepeatedPlainEvolve() = with(Functions) {
        val seed = sin(2.0) * 0.5
        val count = 5

        // Baseline: what you get from plain evolve toward one objective, run `count`
        // times with different seeds. Every run is pulled toward the same target, so the
        // resulting set is behaviourally clustered.
        val target = c(0.5)
        val fitness = matchFitness(target, samples = 32)
        val baseline = (0 until count).map { slot ->
            evolve(seed, iterations = 40, lambda = 6, rngSeed = 100L + slot, fitness = fitness).best
        }
        val baselineBehaviours = baseline.map { it.behaviour() }
        val baselineSpread = meanPairwise(baselineBehaviours)

        // Variety search explicitly rewards behavioural distance from the archive.
        val variety = evolveVariety(seed, count = count, iterations = 40, rngSeed = 7L)

        assertEquals(count, variety.results.size)
        assertTrue(
            variety.meanPairwiseDistance > baselineSpread,
            "variety spread ${variety.meanPairwiseDistance} should exceed baseline $baselineSpread"
        )
    }

    @Test
    fun evolveVarietyGuardsItsArguments() = with(Functions) {
        assertFailsWith<IllegalArgumentException> { evolveVariety(c(0.0), count = 0) }
        assertFailsWith<IllegalArgumentException> { evolveVariety(c(0.0), neighbours = 0) }
        Unit
    }

    private fun meanPairwise(behaviours: List<FnBehaviour>): Double {
        if (behaviours.size < 2) return 0.0
        var sum = 0.0
        var pairs = 0
        for (i in behaviours.indices) {
            for (j in i + 1 until behaviours.size) {
                sum += behaviours[i].distanceTo(behaviours[j])
                pairs++
            }
        }
        return sum / pairs
    }
}
