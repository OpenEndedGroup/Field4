package afield.examples.functions

import afield.stdlib.graphSignature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the CPU-only [EvolveWorkedExample]: no GPU, no window, no file output — just the
 * numbers behind the doc walkthrough. It must improve on the seed, grow a spread-out variety
 * set, reproduce bit-for-bit under the fixed seeds, AND still land on the exact scores the
 * doc prints — so the committed prose/trace table can never silently drift into a lie.
 */
class EvolveWorkedExampleTest {

    @Test
    fun producesDeterministicImprovingRun() {
        val a = EvolveWorkedExample.runExample()

        assertTrue(a.run.improved, "evolution should beat the seed: ${a.run.initialScore} -> ${a.run.bestScore}")
        assertTrue(a.run.bestScore > a.run.initialScore)
        assertTrue(a.run.steps.isNotEmpty(), "expected at least one accepted mutation")

        assertEquals(EvolveWorkedExample.VARIETY_COUNT, a.variety.results.size, "variety result count")
        assertTrue(a.variety.meanPairwiseDistance > 0.0, "variety set should be behaviourally spread out")

        // Pin the numbers the docs state verbatim. If the algorithm drifts these fail loudly,
        // forcing them to be updated in lockstep rather than quietly going stale: the score
        // endpoints, the ~81% reduction and the montage spread on
        // `public/docs/functions_examples.md`, and the endpoint rows of the accepted-mutation
        // trace in `dev_docs/FUNCTIONS_EVOLVE_MEASUREMENTS.md`. The eleven interior rows of that
        // trace are deliberately unguarded, which is why they live in dev_docs and not on a page.
        assertEquals(-0.8421, a.run.initialScore, 5e-5, "seed score must match the documented -0.8421")
        assertEquals(-0.1568, a.run.bestScore, 5e-5, "best score must match the documented -0.1568")
        assertEquals(1.513, a.variety.meanPairwiseDistance, 5e-4, "variety spread must match the documented ~1.51")

        // Determinism: a second run with the same fixed seeds reproduces the numbers and graph.
        val b = EvolveWorkedExample.runExample()
        assertEquals(a.run.bestScore, b.run.bestScore, 0.0, "bestScore must be reproducible")
        assertEquals(a.run.initialScore, b.run.initialScore, 0.0)
        assertEquals(a.run.best.graphSignature(), b.run.best.graphSignature(), "evolved graph must be reproducible")
        assertEquals(a.variety.meanPairwiseDistance, b.variety.meanPairwiseDistance, 0.0, "variety spread must be reproducible")
    }
}
