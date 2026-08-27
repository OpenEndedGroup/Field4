package afield.solidsl

import afield.stdlib.AD
import afield.stdlib.abs
import afield.stdlib.gradAt
import afield.stdlib.hypot
import afield.stdlib.max
import afield.stdlib.min
import afield.stdlib.sq
import afield.stdlib.sqrt
import afield.stdlib.valueAndGrad
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the box lowerings do at zero, where `SolidSLGradientTest`'s random census never lands.
 *
 * The census samples uniformly, so it visits the interior and the exterior of every primitive and
 * *never* an exact face, edge or corner. That is the right default — a measure-zero set is not
 * where a fit spends its time — but it means the boundary cases have to be written down by hand or
 * they go unchecked, and it means a claim about them can be wrong for a long time without anything
 * noticing. Both happened here, and this file holds the two results apart because they pull in
 * opposite directions.
 *
 * # The claim that was false
 *
 * `adBox2`'s KDoc said that `hypot(0, 0)` made every [Profile.Rect] `NaN` throughout its interior,
 * the way `sqrt(0)` genuinely did make every cuboid `NaN` throughout its interior. It never did,
 * because `afield.stdlib.hypot` is zero-guarded and `sqrt` is not — and the guarding is not an
 * oversight in one of them. `hypot`'s partials at the origin are a choice of subgradient out of the
 * unit ball, where `0` is a legal member; `sqrt(0)`'s `+∞` is a real singularity that it would be a
 * lie to clamp. So the counterfactual below runs *both* pre-fix bodies and asserts the asymmetry
 * between them rather than the surviving half alone. A correction that only says "2D was fine"
 * invites the next reader to restore the symmetry on the same plausible grounds; one that measures
 * `hypot` and `sqrt` at zero does not.
 *
 * # The bug that was real
 *
 * Exactly *on* a face, the 3D lowering returned `NaN` and the 2D lowering returned half the right
 * answer. Both because the strict `< 0` interior test sent a boundary sample down the general path,
 * where a clamp at an exact tie meets either `sqrt(0)`'s infinity or `min`'s even split. The face is
 * the interesting case because the box field is genuinely *smooth* across it — locally `|px| - hx`,
 * both one-sided limits agreeing — so a central difference is unambiguous and both answers were
 * simply wrong.
 *
 * The tests below pin the face, the edge and the interior separately and on purpose. An edge really
 * is kinked, `0.5` really is the honest answer there, and a "fix" that returned `1` near any
 * boundary would pass a face-only test and be wrong.
 */
class SolidSLBoxGradientBoundaryTest {

    // ==========================================================================================
    // Reference bodies, verbatim, so every counterfactual is a measurement and not a memory
    // ==========================================================================================

    /** `adBox2` as it stood before 41703e32c added the interior branch. */
    private fun preBranchBox2(px: AD, py: AD, hx: AD, hy: AD): AD {
        val qx = abs(px) - hx; val qy = abs(py) - hy
        return hypot(max(qx, 0.0), max(qy, 0.0)) + min(max(qx, qy), 0.0)
    }

    /** `adBox3` as it stood before 41703e32c added the interior branch. */
    private fun preBranchBox3(px: AD, py: AD, pz: AD, hx: AD, hy: AD, hz: AD): AD {
        val qx = abs(px) - hx; val qy = abs(py) - hy; val qz = abs(pz) - hz
        val ox = max(qx, 0.0); val oy = max(qy, 0.0); val oz = max(qz, 0.0)
        return sqrt(ox.sq() + oy.sq() + oz.sq()) + min(max(qx, max(qy, qz)), 0.0)
    }

    /**
     * `adBox3` as 41703e32c left it — strict `< 0`, and the `min` still applied on the branch.
     * Kept so the boundary repair can be shown to have disturbed *nothing* in the interior that the
     * interior repair had already got right.
     */
    private fun strictBranchBox3(px: AD, py: AD, pz: AD, hx: AD, hy: AD, hz: AD): AD {
        val qx = abs(px) - hx; val qy = abs(py) - hy; val qz = abs(pz) - hz
        val inner = min(max(qx, max(qy, qz)), 0.0)
        if (qx.value < 0.0 && qy.value < 0.0 && qz.value < 0.0) return inner
        val ox = max(qx, 0.0); val oy = max(qy, 0.0); val oz = max(qz, 0.0)
        return sqrt(ox.sq() + oy.sq() + oz.sq()) + inner
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

    // ==========================================================================================
    // The boundary: a sample landing exactly on a face
    // ==========================================================================================

    /**
     * Central differences on the plain-`Double` lowering, which shares no line of code with the AD
     * one — so the oracle is independent of the thing under test, as it is in the census.
     */
    private fun cdBox3(p: DoubleArray, i: Int, h: Double): Double {
        fun f(step: Double): Double {
            val q = p.copyOf(); q[i] += step
            return sdBox3(q[0], q[1], q[2], q[3], q[4], q[5])
        }
        return (f(h) - f(-h)) / (2 * h)
    }

    private fun cdBox2(p: DoubleArray, i: Int, h: Double): Double {
        fun f(step: Double): Double {
            val q = p.copyOf(); q[i] += step
            return sdBox2(q[0], q[1], q[2], q[3])
        }
        return (f(h) - f(-h)) / (2 * h)
    }

    /**
     * A sample sitting exactly on the `+x` face of a unit cube.
     *
     * Before the closed-region branch this was `NaN` in all six components: `max(qx, 0)` splits its
     * tie 0.5/0.5 and hands a zero-*valued* node to `sqrt`, whose derivative there is `+∞`, and
     * `∞ × 0` is `NaN`. The original mechanism, reached through the boundary rather than the
     * interior — and the census never lands here, so nothing caught it.
     *
     * The oracle is unambiguous: across a face the box field is locally just `|px| - hx`, so the
     * derivative is exactly `1`, both one-sided limits agree, and `h` and `h/2` agree too. Note
     * that widening the test to `≤` *without* dropping the redundant `min` would return `0.5` here
     * and still fail, which is the point of asserting the value and not merely finiteness.
     */
    @Test
    fun `exactly on a 3D face the gradient is finite and matches central differences`() {
        val p = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 1.0, 1.0)
        val g = gradAt(p) { v -> adBox3(v[0], v[1], v[2], v[3], v[4], v[5]) }
        assertTrue(
            g.all { it.isFinite() },
            "a sample exactly on a box face must not produce a non-finite gradient; got ${g.toList()}",
        )
        for (i in p.indices) {
            val oracle = cdBox3(p, i, 1e-5)
            assertEquals(
                oracle, g[i], 1e-6,
                "component $i on the +x face: analytic ${g[i]} vs central difference $oracle",
            )
        }
        // Named explicitly, because "matches the oracle" is only as good as the oracle: the face
        // normal is +1 in px and -1 in hx, and nothing else moves.
        assertContentEqualsApprox(doubleArrayOf(1.0, 0.0, 0.0, -1.0, 0.0, 0.0), g)
    }

    /**
     * The same point in 2D, where the failure was quieter and therefore worse: not `NaN` but `0.5`,
     * exactly half the right answer, from `min(max(q), 0)` splitting its tie against a constant
     * zero that the closed region makes redundant.
     */
    @Test
    fun `exactly on a 2D face the gradient matches central differences`() {
        val p = doubleArrayOf(1.0, 0.0, 1.0, 1.0)
        val g = gradAt(p) { v -> adBox2(v[0], v[1], v[2], v[3]) }
        assertTrue(g.all { it.isFinite() }, "got ${g.toList()}")
        for (i in p.indices) {
            val oracle = cdBox2(p, i, 1e-5)
            assertEquals(
                oracle, g[i], 1e-6,
                "component $i on the +x face: analytic ${g[i]} vs central difference $oracle",
            )
        }
        assertContentEqualsApprox(doubleArrayOf(1.0, 0.0, -1.0, 0.0), g)
    }

    /**
     * An edge, where `0.5` is the *right* answer and the fix must not overshoot into `1`.
     *
     * On the `+x/+y` edge the field really is kinked in `px`: stepping out grows the exterior term
     * at rate 1, stepping in leaves the value pinned at 0. The even-split convention reports the
     * average and so does a central difference. This is the assertion that stops the face repair
     * from degenerating into "return 1 near any boundary".
     */
    @Test
    fun `exactly on a 3D edge the gradient is the averaged subgradient, not one`() {
        val p = doubleArrayOf(1.0, 1.0, 0.0, 1.0, 1.0, 1.0)
        val g = gradAt(p) { v -> adBox3(v[0], v[1], v[2], v[3], v[4], v[5]) }
        assertTrue(g.all { it.isFinite() }, "got ${g.toList()}")
        for (i in intArrayOf(0, 1, 2)) {
            val oracle = cdBox3(p, i, 1e-5)
            assertEquals(
                oracle, g[i], 1e-6,
                "component $i on the +x/+y edge: analytic ${g[i]} vs central difference $oracle",
            )
        }
        assertEquals(0.5, g[0], 1e-12, "the edge is a genuine kink and 0.5 is the honest answer")
        assertEquals(0.5, g[1], 1e-12)
    }

    // ==========================================================================================
    // And the repair has to be invisible everywhere else
    // ==========================================================================================

    /**
     * Off the boundary the closed-region branch must move nothing: not the value, which is checked
     * against the independent scalar lowering, and not the gradient, which is checked against the
     * interior fix as 41703e32c left it and against the pre-branch body out in the exterior where
     * that body is itself trustworthy.
     *
     * The census would catch a disturbed interior, but only statistically and only where it looks.
     * This is the direct statement.
     */
    @Test
    fun `the closed-region branch changes nothing away from the boundary`() {
        val rng = kotlin.random.Random(20260827)
        var interior = 0
        var exterior = 0
        // 8000, not 4000: a point uniform on [-3,3]^3 with half-extents uniform on [0.2,2] lands
        // strictly inside on all three axes about 4.9% of the time, so 4000 draws yields ~195 and
        // sits right on the floor below. The floor is there to catch the branch becoming
        // unreachable, so it is the sample count that gives way, not the floor.
        repeat(8000) {
            val p = doubleArrayOf(
                rng.nextDouble(-3.0, 3.0), rng.nextDouble(-3.0, 3.0), rng.nextDouble(-3.0, 3.0),
                rng.nextDouble(0.2, 2.0), rng.nextDouble(0.2, 2.0), rng.nextDouble(0.2, 2.0),
            )
            val now = valueAndGrad(p) { v -> adBox3(v[0], v[1], v[2], v[3], v[4], v[5]) }
            assertTrue(
                now.gradient.all { it.isFinite() },
                "non-finite gradient at ${p.toList()}: ${now.gradient.toList()}",
            )
            // The value must be the scalar lowering's, exactly — nothing here is allowed to be an
            // approximation of the field, only of its derivative.
            assertEquals(
                sdBox3(p[0], p[1], p[2], p[3], p[4], p[5]), now.value, 1e-15,
                "the AD value drifted from sdBox3 at ${p.toList()}",
            )

            val qmax = maxOf(
                kotlin.math.abs(p[0]) - p[3],
                maxOf(kotlin.math.abs(p[1]) - p[4], kotlin.math.abs(p[2]) - p[5]),
            )
            if (qmax < -1e-6) {
                interior++
                val strict = gradAt(p) { v -> strictBranchBox3(v[0], v[1], v[2], v[3], v[4], v[5]) }
                for (i in strict.indices) {
                    assertEquals(strict[i], now.gradient[i], 0.0, "interior component $i moved at ${p.toList()}")
                }
            } else if (qmax > 1e-6) {
                exterior++
                val old = gradAt(p) { v -> preBranchBox3(v[0], v[1], v[2], v[3], v[4], v[5]) }
                for (i in old.indices) {
                    assertEquals(old[i], now.gradient[i], 1e-12, "exterior component $i moved at ${p.toList()}")
                }
            }
        }
        assertTrue(interior > 200, "only $interior interior samples compared — the sweep stopped sweeping")
        assertTrue(exterior > 500, "only $exterior exterior samples compared — the sweep stopped sweeping")
    }

    private fun assertContentEqualsApprox(expected: DoubleArray, actual: DoubleArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals(expected[i], actual[i], 1e-12, "component $i of ${actual.toList()}")
        }
    }
}
