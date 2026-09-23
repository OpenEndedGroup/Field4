package afield.four

import afield.four.modules.CappedTwistFixture
import afield.four.modules.DualQuaternionSkinning
import afield.four.modules.GltfSkinning.SkinningMethod
import afield.four.modules.GltfSkinning.SkinningOptions
import afield.four.modules.RiggedFixture
import afield.four.modules.skinnedGltfToTriMesh
import afield.manifold.ManifoldLibrary
import afield.manifold.toSolid
import afield.manifold.isValidSolid
import afield.stdlib.TriMesh
import afield.stdlib.boundaryLoops
import afield.stdlib.genus
import afield.stdlib.isWatertight
import afield.stdlib.signedVolume
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The candy wrapper measured as a **volume**: [CappedTwistFixture] through both skinning methods and
 * into Manifold, over a twist sweep.
 *
 * Every other skinning test measures the artefact as a length — the smallest ring radius
 * ([DualQuaternionSkinning.candyWrapperRadius]) or a mean edge ratio — and a length says nothing
 * about how much of the limb is gone. The volume is what `public/docs/chain_skinned_volume.md` is
 * about, and this file is where its structural claims are pinned.
 *
 * ### Oracles
 *
 * - **Rest volume, in closed form.** A straight prism over a regular 12-gon of circumradius 0.5 and
 *   height 4 encloses `0.75 × 4 = 3.0`. Manifold measures the solid in double precision from float
 *   vertices whose `cos`/`sin` are rounded, so the floor is float rounding, not a tuned number.
 * - **Two implementations of the volume.** Manifold's `volume` and `afield.stdlib.signedVolume` share
 *   no code; they must agree on every pose, which is what makes a Manifold volume on a *skinned*
 *   mesh evidence rather than a number.
 * - **The twist really is the twist.** The tip ring is pure `boneUpper`, so its angle about `+y` is
 *   the joint's; measuring it checks `secondsFor` against the sampler rather than trusting slerp.
 * - **LBS at 180° pinches the 50/50 ring to a point.** `½·I + ½·R_y(180°) = diag(0,1,0)`, so every
 *   vertex of the `y = 2` ring lands on `(0, 2, 0)`. That is arithmetic, and it is asserted exactly.
 */
class SkinnedVolumeTest {

    private val lbs = SkinningOptions(method = SkinningMethod.LINEAR_BLEND)
    private val dqs = SkinningOptions(method = SkinningMethod.DUAL_QUATERNION)

    private fun posed(degrees: Float, options: SkinningOptions): TriMesh =
        skinnedGltfToTriMesh(
            CappedTwistFixture.locate(),
            clip = "twist",
            seconds = CappedTwistFixture.secondsFor(degrees),
            options = options,
        )

    /** Manifold's volume and genus of [mesh], and Field's own signed volume of the same triangles. */
    private data class Measured(val manifold: Double, val genus: Int, val field: Double)

    private fun measure(mesh: TriMesh): Measured =
        mesh.toSolid().use { Measured(it.volume, it.genus, mesh.signedVolume()) }

    @Test
    fun `the bending cylinder is open, so it has no volume to lose`() {
        val tube = skinnedGltfToTriMesh(RiggedFixture.locate(), clip = "twist", seconds = 0f)
        assertEquals(RiggedFixture.VERTEX_COUNT, tube.vertexCount)
        assertEquals(false, tube.isWatertight(), "the bending cylinder has no caps")
        assertEquals(2, tube.boundaryLoops().size, "one open rim at each end")
        if (ManifoldLibrary.isAvailable) {
            assertEquals(false, tube.isValidSolid(), "and Manifold refuses it as a solid")
        }
    }

    @Test
    fun `the capped rest pose is a closed prism of volume three`() {
        val rest = posed(0f, lbs)
        assertEquals(CappedTwistFixture.VERTEX_COUNT, rest.vertexCount, "Assimp kept every vertex")
        assertEquals(CappedTwistFixture.TRIANGLE_COUNT, rest.triangleCount)
        assertTrue(rest.isWatertight(), "capped: no boundary edges")
        assertEquals(0, rest.genus(), "Field's mesh topology: a sphere")
        assertEquals(CappedTwistFixture.REST_VOLUME, rest.signedVolume(), 1e-5, "outward wound, and 0.75 × 4")
        if (!ManifoldLibrary.isAvailable) {
            println("SkinnedVolumeTest: Manifold half skipped — ${ManifoldLibrary.describe()}")
            return
        }
        val m = measure(rest)
        assertEquals(0, m.genus)
        assertEquals(CappedTwistFixture.REST_VOLUME, m.manifold, 1e-5)
        assertEquals(m.field, m.manifold, 1e-9, "Manifold and signedVolume agree at rest")
    }

    @Test
    fun `the tip ring turns by the requested twist`() {
        // Vertex 0 of the top ring sits at angle 0 at rest; the tip is all boneUpper.
        val tipVertex = (CappedTwistFixture.RINGS - 1) * CappedTwistFixture.SEGMENTS
        for (deg in listOf(30f, 90f, 150f)) {
            val p = posed(deg, lbs).position(tipVertex)
            // A +y rotation by θ takes (r, y, 0) to (r cos θ, y, −r sin θ).
            val measured = Math.toDegrees(atan2(-p.z.toDouble(), p.x.toDouble()))
            assertEquals(deg.toDouble(), measured, 1e-3, "tip angle at a requested $deg°")
        }
    }

    @Test
    fun `under twist linear blending loses volume and dual quaternions keep it`() {
        val rest = CappedTwistFixture.REST_VOLUME
        val sweep = (0..12).map { it * 15f }
        val lbsVolumes = ArrayList<Double>()
        val dqsVolumes = ArrayList<Double>()
        for (deg in sweep) {
            val l = posed(deg, lbs)
            val d = posed(deg, dqs)
            // Field's own volume is measured on every pose, Manifold's when it is installed.
            lbsVolumes += l.signedVolume()
            dqsVolumes += d.signedVolume()
            if (ManifoldLibrary.isAvailable) {
                val ml = measure(l)
                val md = measure(d)
                assertEquals(0, ml.genus, "LBS at $deg° is still one sphere-like solid")
                assertEquals(0, md.genus, "DQS at $deg° is still one sphere-like solid")
                // At exactly 180° the LBS waist ring is 2.3e-7 across, under Manifold's own
                // tolerance (4.8e-7): Manifold keeps all 206 vertices and 408 triangles but swaps
                // edges in that collar, so it measures ITS triangulation, 7.0e-4 below the input's.
                // Measured 2026-09-23, Manifold via Homebrew; everywhere else the two agree to 1e-12.
                val lbsFloor = if (deg == 180f) 1e-3 else 1e-9
                assertEquals(ml.field, ml.manifold, lbsFloor, "LBS at $deg°: two volume implementations agree")
                assertEquals(md.field, md.manifold, 1e-9, "DQS at $deg°: two volume implementations agree")
                println("twist %5.1f°  LBS %.6f (genus %d)   DQS %.6f (genus %d)"
                    .format(deg, ml.manifold, ml.genus, md.manifold, md.genus))
            } else {
                println("twist %5.1f°  LBS %.6f   DQS %.6f".format(deg, lbsVolumes.last(), dqsVolumes.last()))
            }
        }
        for (i in 1 until sweep.size) {
            assertTrue(lbsVolumes[i] < lbsVolumes[i - 1],
                "LBS volume falls at every step: ${sweep[i]}° ${lbsVolumes[i]} vs ${sweep[i - 1]}° ${lbsVolumes[i - 1]}")
        }
        // LBS at 180° has a closed form. Each blended matrix is diag(1−2w, 1, 1−2w) — a pure
        // scale, no rotation — and 1−2w = 2−y is linear in y, so every band of the blended zone is
        // a frustum of similar 12-gons and the zone integrates exactly: A·∫₁³(2−y)²dy = A·2/3. The
        // two unblended units keep A each, so V = 0.75·(2 + 2/3) = 2.0: a third of the limb gone.
        assertEquals(2.0, lbsVolumes.last(), 1e-6, "LBS at 180°: the frustum closed form")
        // DQS does not lose volume; it GAINS a little, which is the bulge DualQuaternionSkinningTest
        // measures as a length (mean edge ratio 1.051). Asserted with its sign, measured +0.58% max.
        for ((i, v) in dqsVolumes.withIndex()) {
            if (i == 0) continue
            assertTrue(v > rest, "DQS at ${sweep[i]}° bulges rather than shrinks: $v")
        }
        val dqsWorst = dqsVolumes.maxOf { abs(it - rest) } / rest
        println("DQS worst |ΔV|/V over the sweep: ${"%.5f".format(dqsWorst)}")
        assertTrue(dqsWorst < 0.01, "DQS stays within 1% of rest over 0..180°, got $dqsWorst")
    }

    @Test
    fun `linear blending at a half turn collapses the fifty-fifty ring to a point`() {
        val mesh = posed(180f, lbs)
        val ring = (CappedTwistFixture.UPPER_JOINT_Y / CappedTwistFixture.RING_SPACING).toInt()
        for (k in 0 until CappedTwistFixture.SEGMENTS) {
            val p = mesh.position(ring * CappedTwistFixture.SEGMENTS + k)
            assertEquals(0f, abs(p.x) + abs(p.z), 1e-6f, "ring vertex $k is on the axis")
        }
        val dq = posed(180f, dqs).positions
        val r = DualQuaternionSkinning.candyWrapperRadius(dq, CappedTwistFixture.UPPER_JOINT_Y, tolerance = 0.01f)
        assertEquals(CappedTwistFixture.RADIUS, r, 1e-4f, "while dual quaternions keep that ring at full radius")
    }
}
