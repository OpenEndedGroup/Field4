package afield.geometry.polytope

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [canonicalize] starts from the seed gauged to unit mean tangency radius, so it is scale-invariant: an
 * exactly representable rescale (a power of two) gives a bit-identical answer, and any rescale of an input
 * the iteration converges on gives the same canonical form to tolerance.
 *
 * What it cannot be is insensitive to rounding on an input it does not converge on. `kkC` runs out its
 * iteration budget, and there a rescale by `1.0000001` (a change in the last float bits of the stored seed)
 * moves the result as far as any other rescale does. That is the "scale dependence" the side-2 vs
 * unit-circumradius cube showed; the last test pins it so the explanation stays true.
 */
class CanonicalizeScaleInvarianceTest {
    private fun scaled(p: PolyhedralObject, k: Double) =
        p.withCoordinateVector(p.coordinateVector().map { it * k }.toDoubleArray())

    @Test
    fun powerOfTwoRescaleIsBitIdentical() {
        val seed = conway("kkC").polyhedron
        val one = seed.canonicalize().finalDiagnostics
        for (k in listOf(0.5, 2.0, 8.0)) {
            val d = scaled(seed, k).canonicalize().finalDiagnostics
            assertEquals(one.tangencyRadiusSpread, d.tangencyRadiusSpread, "k=$k")
            assertEquals(one.signedVolume, d.signedVolume, "k=$k")
        }
    }

    @Test
    fun convergingInputsAgreeUnderAnyRescale() {
        for (expression in listOf("gC", "aC", "tC", "kC", "dC")) {
            val seed = conway(expression).polyhedron
            val base = seed.canonicalize()
            assertTrue(base.converged, "$expression did not converge: ${base.reason}")
            for (k in listOf(1.0 / sqrt(3.0), 0.01, 37.0)) {
                val r = scaled(seed, k).canonicalize()
                assertTrue(r.converged, "$expression at k=$k did not converge: ${r.reason}")
                val a = base.finalDiagnostics
                val b = r.finalDiagnostics
                assertTrue(abs(a.dihedralAngleMean - b.dihedralAngleMean) < 1e-5, "$expression k=$k dihedral ${a.dihedralAngleMean} vs ${b.dihedralAngleMean}")
                assertTrue(abs(a.tangencyRadiusSpread - b.tangencyRadiusSpread) < 1e-5, "$expression k=$k spread")
            }
        }
    }

    @Test
    fun kkcDoesNotConvergeSoRoundingDecidesItsOutcome() {
        val seed = conway("kkC").polyhedron
        val base = seed.canonicalize()
        val nudged = scaled(seed, 1.0000001).canonicalize()
        assertTrue(!base.converged && !nudged.converged, "kkC converged; the explanation above is out of date")
        assertTrue(
            abs(base.finalDiagnostics.tangencyRadiusSpread - nudged.finalDiagnostics.tangencyRadiusSpread) > 0.05,
            "a last-bit rescale no longer moves kkC's outcome; the explanation above is out of date",
        )
    }
}
