package afield.solidsl

import afield.stdlib.AD
import afield.stdlib.abs
import afield.stdlib.gradAt
import afield.stdlib.hypot
import afield.stdlib.max
import afield.stdlib.min
import afield.stdlib.sq
import afield.stdlib.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the box lowerings do at zero, where `SolidSLGradientTest`'s random census never lands.
 *
 * The census samples uniformly, so it visits the interior and the exterior of every primitive and
 * *never* an exact face, edge or corner. That is the right default — a measure-zero set is not
 * where a fit spends its time — but it means the boundary cases have to be written down by hand or
 * they go unchecked.
 *
 * It also means a claim about them can be wrong for a long time without anything noticing, and one
 * was. `adBox2`'s KDoc said that `hypot(0, 0)` made every [Profile.Rect] `NaN` throughout its
 * interior, the way `sqrt(0)` genuinely did make every cuboid `NaN` throughout its interior. It
 * never did, because `afield.stdlib.hypot` is zero-guarded and `sqrt` is not, and the guarding is
 * not an oversight in one of them: `hypot`'s partials at the origin are a choice of subgradient out
 * of the unit ball, where `0` is a legal member, and `sqrt(0)`'s `+∞` derivative is a real
 * singularity that it would be a lie to clamp.
 *
 * So the counterfactual below runs *both* pre-fix bodies and asserts the asymmetry between them,
 * rather than asserting the surviving half alone. A correction that only says "2D was fine" invites
 * the next reader to restore the symmetry on the same plausible-sounding grounds; one that measures
 * both halves does not.
 */
class SolidSLBoxGradientBoundaryTest {

    // ==========================================================================================
    // The pre-fix bodies, kept verbatim so the counterfactual is a measurement and not a memory
    // ==========================================================================================

    /** `adBox2` exactly as it stood before the interior branch was added. */
    private fun preBranchBox2(px: AD, py: AD, hx: AD, hy: AD): AD {
        val qx = abs(px) - hx; val qy = abs(py) - hy
        return hypot(max(qx, 0.0), max(qy, 0.0)) + min(max(qx, qy), 0.0)
    }

    /** `adBox3` exactly as it stood before the interior branch was added. */
    private fun preBranchBox3(px: AD, py: AD, pz: AD, hx: AD, hy: AD, hz: AD): AD {
        val qx = abs(px) - hx; val qy = abs(py) - hy; val qz = abs(pz) - hz
        val ox = max(qx, 0.0); val oy = max(qy, 0.0); val oz = max(qz, 0.0)
        return sqrt(ox.sq() + oy.sq() + oz.sq()) + min(max(qx, max(qy, qz)), 0.0)
    }

    // ==========================================================================================
    // `hypot` is guarded, `sqrt` is not, and that is the whole of the difference
    // ==========================================================================================

    /**
     * The one-line root of it. If this test ever fails, `adBox2`'s KDoc is wrong again — but so is
     * a good deal else, because `Profile.Circle` (`hypot(x, y) - r` at its own centre) and
     * `adCappedConeZ` (`hypot(px, py)` on the axis) lean on the same guard with no branch at all.
     */
    @Test
    fun `hypot is zero-guarded at the origin and sqrt is not`() {
        val h = gradAt(doubleArrayOf(0.0, 0.0)) { v -> hypot(v[0], v[1]) }
        assertTrue(
            h.all { it.isFinite() },
            "afield.stdlib.hypot's partials at (0,0) are guarded to zero; got ${h.toList()}",
        )
        assertEquals(0.0, h[0], 0.0)
        assertEquals(0.0, h[1], 0.0)

        val s = gradAt(doubleArrayOf(0.0)) { v -> sqrt(v[0]) }
        assertTrue(
            !s[0].isFinite(),
            "sqrt(0)'s derivative is +inf and must stay that way — it is a real singularity, not a " +
                "removable one, and guarding it would be a lie rather than a subgradient choice. " +
                "Got ${s[0]}",
        )
    }

    /**
     * The counterfactual, both halves in one test because the *asymmetry* is the claim.
     *
     * Strictly inside a unit box, on the pre-branch bodies: 3D is `NaN` in every component and 2D
     * is finite in every component. Delete either assertion and the false symmetry becomes
     * re-inventable.
     */
    @Test
    fun `counterfactual — the pre-branch interior NaN was 3D only`() {
        val g3 = gradAt(doubleArrayOf(0.3, -0.2, 0.1, 1.0, 1.0, 1.0)) { v ->
            preBranchBox3(v[0], v[1], v[2], v[3], v[4], v[5])
        }
        assertTrue(
            g3.all { it.isNaN() },
            "the 3D interior NaN is the bug that was actually there; without it this file's " +
                "reason to exist is gone. Got ${g3.toList()}",
        )

        val g2 = gradAt(doubleArrayOf(0.3, -0.2, 1.0, 1.0)) { v ->
            preBranchBox2(v[0], v[1], v[2], v[3])
        }
        assertTrue(
            g2.all { it.isFinite() },
            "2D never had the interior NaN it was once documented as having — hypot's zero guard " +
                "means there is no infinity to multiply by zero. Got ${g2.toList()}",
        )
        // And it was not merely finite, it was already exactly right: d/dpx = +1 moving away from
        // the centre, d/dhx = -1 as the rect grows.
        assertContentEqualsApprox(doubleArrayOf(1.0, 0.0, -1.0, 0.0), g2)
    }

    /** The shipping 2D lowering agrees with the pre-branch one it replaced, strictly inside. */
    @Test
    fun `the 2D interior branch is a semantic no-op`() {
        val rng = kotlin.random.Random(20260827)
        var inside = 0
        repeat(2000) {
            val p = doubleArrayOf(
                rng.nextDouble(-2.0, 2.0), rng.nextDouble(-2.0, 2.0),
                rng.nextDouble(0.2, 2.0), rng.nextDouble(0.2, 2.0),
            )
            if (kotlin.math.abs(p[0]) >= p[2] || kotlin.math.abs(p[1]) >= p[3]) return@repeat
            inside++
            val now = gradAt(p) { v -> adBox2(v[0], v[1], v[2], v[3]) }
            val before = gradAt(p) { v -> preBranchBox2(v[0], v[1], v[2], v[3]) }
            for (i in now.indices) {
                assertEquals(before[i], now[i], 0.0, "component $i moved at ${p.toList()}")
            }
        }
        assertTrue(inside > 200, "only $inside interior samples compared — the sweep stopped sweeping")
    }

    private fun assertContentEqualsApprox(expected: DoubleArray, actual: DoubleArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals(expected[i], actual[i], 1e-12, "component $i of ${actual.toList()}")
        }
    }
}
