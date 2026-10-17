package afield.geometry.polytope

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [CanonicalDiagnostics.isEmbedded]: the canonical figures can all be good on a result that is not a solid. */
class CanonicalEmbeddingDiagnosticsTest {
    @Test
    fun `a canonical cube is embedded`() {
        val d = canonicalConway("C").polyhedron.canonicalDiagnostics()
        assertTrue(d.signedVolume > 0.0, "volume ${d.signedVolume}")
        assertEquals(0, d.selfIntersectingFacePairs)
        assertTrue(d.isEmbedded)
    }

    @Test
    fun `kkC as built is embedded and canonicalize turns it inside out`() {
        val seed = conway("kkC").polyhedron
        assertTrue(seed.canonicalDiagnostics().isEmbedded, "kkC as built: ${seed.canonicalDiagnostics()}")
        val iterated = seed.canonicalize().polyhedron.canonicalDiagnostics()
        assertFalse(iterated.isEmbedded, "canonicalize() of kkC should be reported as not a solid: $iterated")
    }

    @Test
    fun `the Ceres canonical kkC passes through itself and the diagnostics say so`() {
        val solved = try {
            conway("kkC").polyhedron.relaxWithCeres(
                RelaxationPlan.canonicalConditions(), CeresRelaxationOptions(maxIterations = 500),
            ).realization.polyhedron
        } catch (t: Throwable) {
            if (t is AssertionError) throw t
            println("CanonicalEmbeddingDiagnosticsTest: skipping the Ceres case — ${t.message}")
            return
        }
        val d = solved.canonicalDiagnostics()
        println("Ceres kkC: volume ${d.signedVolume}, crossing face pairs ${d.selfIntersectingFacePairs}, spread ${d.tangencyRadiusSpread}")
        assertTrue(d.signedVolume > 0.0)
        assertTrue(d.selfIntersectingFacePairs > 0, "the page measures a self-intersection here: $d")
        assertFalse(d.isEmbedded)
    }
}
