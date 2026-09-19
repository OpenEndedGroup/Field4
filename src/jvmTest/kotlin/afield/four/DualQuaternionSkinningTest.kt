package afield.four

import afield.four.modules.DualQuaternionSkinning
import afield.four.modules.GltfSkinning
import afield.four.modules.GltfSupport
import afield.four.modules.RiggedFixture
import afield.four.modules.StressFixture
import afield.stdlib.linalg.Matrix4f
import org.lwjgl.system.MemoryUtil.memFree
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [DualQuaternionSkinning] against [GltfSkinning], on inputs where they must agree exactly and on
 * the one input where they must not.
 *
 * ### Why the interesting assertion is a disagreement
 *
 * Most of this file checks the two methods produce the *same* answer, because on a rigid single
 * influence they are two spellings of one transform and any difference is a bug. The test that
 * justifies the file is the opposite: [underTwistLinearBlendingCollapsesTheWaistAndDualQuaternionsDoNot]
 * pins the candy wrapper as a **number with a sign**, measured on both methods from one palette in
 * one run. A test that only asserted "dual quaternions work" would pass on a stub that called
 * straight through to linear blending.
 *
 * ### Two oracles, both hand-derived rather than captured
 *
 * - **A rigid motion preserves distance — but only between vertices that share a transform.** This
 *   was written first in the false, stronger form ("blending rigid motions gives a rigid motion, so
 *   every edge keeps its rest length") and the test caught it: dual quaternion blending came back at
 *   **1.051** over all 288 edges, not 1.000. The error is worth keeping rather than quietly fixing.
 *   Each *vertex* gets a rigid transform, so it cannot be squashed; but two vertices with different
 *   weights get *different* rigid transforms, and the edge between them is under no obligation at
 *   all. The correct oracle is therefore restricted to a set of vertices whose weights are equal —
 *   one ring — where the shared transform makes distance preservation exact.
 *   What the whole-mesh number actually measures is the **known tradeoff**: linear blending
 *   *shrinks* (0.950) and dual quaternions *bulge* (1.051). Both are artefacts; they have opposite
 *   signs; neither is 1. That is a more useful sentence than the one that was wrong.
 * - **At 180° the collapse is total, and that is arithmetic, not observation.** At the joint ring
 *   the weights are 0.5/0.5 (the fixture prints `[[1,0,0,0], [0.5,0.5,0,0]]`, so this is exact, not
 *   approximate). Linear blending there averages the identity with a 180° rotation about `y`:
 *   `0.5·diag(1,1,1) + 0.5·diag(-1,1,-1) = diag(0,1,0)`. Every `x` and `z` is multiplied by **zero**.
 *   So the predicted linear-blend radius at that ring is not "small", it is **0**, and the test
 *   asserts against 0 rather than against a threshold someone tuned until it went green.
 */
class DualQuaternionSkinningTest {

    /** Where the ring at the joint sits, and the radius it has at rest. */
    private val jointY = RiggedFixture.UPPER_JOINT_Y
    private val restRadius = RiggedFixture.RADIUS

    private class Rig(
        val skin: GltfSkinning.MeshSkin,
        val positions: FloatArray,
        val normals: FloatArray?,
    )

    private fun <T> withRig(path: String, block: (Rig) -> T): T {
        val model = GltfSupport.loadGltfBasic(path, GltfSupport.GltfLoadConfig(loadTextures = false))
        try {
            val instance = model.meshes.first { it.skin != null }
            val skin = instance.skin!!
            val n = skin.influences.vertexCount
            val stride = instance.buffers.vertexData.capacity() / 4 / n
            val src = instance.buffers.vertexData.asFloatBuffer()
            val p = FloatArray(n * 3)
            val nr = FloatArray(n * 3)
            for (v in 0 until n) {
                p[v * 3] = src.get(v * stride); p[v * 3 + 1] = src.get(v * stride + 1); p[v * 3 + 2] = src.get(v * stride + 2)
                nr[v * 3] = src.get(v * stride + 3); nr[v * 3 + 1] = src.get(v * stride + 4); nr[v * 3 + 2] = src.get(v * stride + 5)
            }
            return block(Rig(skin, p, nr))
        } finally {
            for (m in model.meshes) {
                memFree(m.buffers.vertexData)
                memFree(m.buffers.indexData)
                m.buffers.tangentData?.let { memFree(it) }
            }
        }
    }

    private fun maxDelta(a: FloatArray, b: FloatArray): Float {
        var worst = 0f
        for (i in a.indices) worst = maxOf(worst, abs(a[i] - b[i]))
        return worst
    }

    /** Mean |deformed edge| / |rest edge| over every edge of the triangle list. A rigid motion gives 1. */
    private fun meanEdgeRatio(rest: FloatArray, moved: FloatArray, indices: IntArray): Pair<Double, Int> {
        var acc = 0.0
        var count = 0
        fun len(a: FloatArray, i: Int, j: Int): Float {
            val dx = a[i * 3] - a[j * 3]; val dy = a[i * 3 + 1] - a[j * 3 + 1]; val dz = a[i * 3 + 2] - a[j * 3 + 2]
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
        var t = 0
        while (t + 2 < indices.size) {
            for ((i, j) in listOf(indices[t] to indices[t + 1], indices[t + 1] to indices[t + 2], indices[t + 2] to indices[t])) {
                val r = len(rest, i, j)
                if (r > 1e-6f) { acc += len(moved, i, j) / r; count++ }
            }
            t += 3
        }
        return (acc / count) to count
    }

    // ==============================================================================================
    // Where the two methods MUST agree
    // ==============================================================================================

    @Test
    fun theBindPoseIsTheIdentityUnderDualQuaternionsToo() = withRig(RiggedFixture.COMMITTED_PATH) { rig ->
        val palette = rig.skin.skeleton.palette(null, 0f)
        val dq = DualQuaternionSkinning.skin(rig.positions, rig.normals, rig.skin.influences, palette)

        assertEquals(0, dq.nonRigidJoints, "every joint in the bending cylinder is a pure rotation")
        val worst = maxDelta(rig.positions, dq.position)
        assertTrue(
            worst <= 1e-6f,
            "bind pose must come back unchanged over all ${dq.vertexCount} vertices, worst |delta| = $worst",
        )
    }

    @Test
    fun aSingleRigidInfluenceAgreesWithLinearBlending() = withRig(RiggedFixture.COMMITTED_PATH) { rig ->
        // The lower half of the cylinder has exactly one influence at weight 1 (the fixture's own
        // evidence run prints the two distinct weight tuples: [1,0,0,0] and [0.5,0.5,0,0]). For those
        // vertices both methods reduce to "apply this joint's matrix", so they must agree.
        //
        // To a tolerance and not bit-for-bit, and the reason is worth stating rather than hiding in
        // an epsilon: linear blending multiplies a 4x4, dual quaternion blending rotates by a unit
        // quaternion and adds a translation. Same transform, different float operation order. 1e-6
        // is about 1e-7 of the mesh's 4-unit extent.
        val palette = rig.skin.skeleton.palette("bend", 1f)
        val lbs = GltfSkinning.skin(rig.positions, rig.normals, rig.skin.influences, palette)
        val dq = DualQuaternionSkinning.skin(rig.positions, rig.normals, rig.skin.influences, palette)

        var single = 0
        var worst = 0f
        val k = GltfSkinning.MAX_INFLUENCES
        for (v in 0 until rig.skin.influences.vertexCount) {
            var nonZero = 0
            for (i in 0 until k) if (rig.skin.influences.weight[v * k + i] > 0f) nonZero++
            if (nonZero != 1) continue
            single++
            for (c in 0 until 3) worst = maxOf(worst, abs(lbs.position[v * 3 + c] - dq.position[v * 3 + c]))
        }
        assertTrue(single > 0, "the fixture must have single-influence vertices for this test to mean anything")
        assertTrue(
            worst <= 1e-6f,
            "over $single single-influence vertices of ${rig.skin.influences.vertexCount}, " +
                "worst |LBS - DQS| = $worst, must be <= 1e-6",
        )
    }

    @Test
    fun scaleAndShearSurviveTheDualQuaternionPath() = withRig(StressFixture.COMMITTED_PATH) { rig ->
        // The whole hazard of dual quaternions: they cannot represent scale, so a naive
        // implementation drops it silently and the mesh snaps back to unit size looking fine. This
        // fixture exists to be non-rigid. Assert BOTH that the residual path engaged and that it
        // reproduces linear blending where linear blending is exactly right (single influence).
        val palette = rig.skin.skeleton.palette("shear", 1f)
        val lbs = GltfSkinning.skin(rig.positions, rig.normals, rig.skin.influences, palette)
        val dq = DualQuaternionSkinning.skin(rig.positions, rig.normals, rig.skin.influences, palette)

        assertTrue(
            dq.nonRigidJoints > 0,
            "the shear fixture must present at least one non-rigid joint, or this test proves nothing",
        )

        var single = 0
        var worst = 0f
        val k = GltfSkinning.MAX_INFLUENCES
        for (v in 0 until rig.skin.influences.vertexCount) {
            var nonZero = 0
            for (i in 0 until k) if (rig.skin.influences.weight[v * k + i] > 0f) nonZero++
            if (nonZero != 1) continue
            single++
            for (c in 0 until 3) worst = maxOf(worst, abs(lbs.position[v * 3 + c] - dq.position[v * 3 + c]))
        }
        assertTrue(single > 0, "need single-influence vertices on the shear fixture")
        assertTrue(
            worst <= 1e-4f,
            "a scaled/sheared single influence must still land where linear blending puts it: " +
                "over $single vertices worst |LBS - DQS| = $worst",
        )
    }

    // ==============================================================================================
    // Where they MUST NOT agree — the reason this exists
    // ==============================================================================================

    @Test
    fun underTwistLinearBlendingCollapsesTheWaistAndDualQuaternionsDoNot() =
        withRig(RiggedFixture.COMMITTED_PATH) { rig ->
            val palette = rig.skin.skeleton.palette("twist", 1f)
            val lbs = GltfSkinning.skin(rig.positions, rig.normals, rig.skin.influences, palette)
            val dq = DualQuaternionSkinning.skin(rig.positions, rig.normals, rig.skin.influences, palette)

            val rRest = DualQuaternionSkinning.candyWrapperRadius(rig.positions, jointY)
            val rLbs = DualQuaternionSkinning.candyWrapperRadius(lbs.position, jointY)
            val rDq = DualQuaternionSkinning.candyWrapperRadius(dq.position, jointY)

            println(
                "candy wrapper at the y = $jointY ring, twist t = 1 (180 degrees about the bone axis):\n" +
                    "  rest                 min radius %.6f\n".format(rRest) +
                    "  linear blend         min radius %.6f  (%.1f%% of rest)\n".format(rLbs, 100f * rLbs / rRest) +
                    "  dual quaternion      min radius %.6f  (%.1f%% of rest)".format(rDq, 100f * rDq / rRest)
            )

            assertEquals(restRadius, rRest, 1e-5f, "the rest ring is the fixture's own radius")

            // Predicted, not observed: at the joint ring the weights are exactly 0.5/0.5 and the blend
            // is 0.5*I + 0.5*Ry(180) = diag(0, 1, 0). x and z are multiplied by zero.
            assertTrue(
                rLbs <= 1e-5f,
                "linear blending must collapse this ring to a line: predicted 0, measured $rLbs",
            )
            assertTrue(
                rDq >= 0.99f * restRadius,
                "dual quaternion blending must hold the ring open: rest $restRadius, measured $rDq",
            )
        }

    @Test
    fun aSharedTransformPreservesDistanceExactlyAndTheWholeMeshBulgesInstead() =
        withRig(RiggedFixture.COMMITTED_PATH) { rig ->
            val model = GltfSupport.loadGltfBasic(
                RiggedFixture.COMMITTED_PATH, GltfSupport.GltfLoadConfig(loadTextures = false),
            )
            val indices: IntArray
            try {
                val ib = model.meshes.first { it.skin != null }.buffers.indexData
                indices = IntArray(ib.capacity()) { ib.get(it) }
            } finally {
                for (m in model.meshes) {
                    memFree(m.buffers.vertexData); memFree(m.buffers.indexData)
                    m.buffers.tangentData?.let { memFree(it) }
                }
            }

            val palette = rig.skin.skeleton.palette("twist", 1f)
            val lbs = GltfSkinning.skin(rig.positions, rig.normals, rig.skin.influences, palette)
            val dq = DualQuaternionSkinning.skin(rig.positions, rig.normals, rig.skin.influences, palette)

            // (a) The exact half. The joint ring's vertices all carry weights 0.5/0.5, so they share
            // ONE blended transform. Under dual quaternions that transform is rigid, so the distances
            // *within* the ring must survive to float precision. Under linear blending the shared
            // transform is diag(0, 1, 0) and the ring collapses onto a line.
            val ring = (0 until rig.skin.influences.vertexCount).filter {
                abs(rig.positions[it * 3 + 1] - jointY) <= 1e-4f
            }
            assertTrue(ring.size >= 4, "need a ring to measure; found ${ring.size} vertices at y = $jointY")

            fun worstPairwiseRatio(moved: FloatArray): Pair<Double, Double> {
                var lo = Double.MAX_VALUE; var hi = 0.0
                for (a in ring.indices) for (b in a + 1 until ring.size) {
                    val i = ring[a]; val j = ring[b]
                    fun d(arr: FloatArray): Double {
                        val dx = (arr[i * 3] - arr[j * 3]).toDouble()
                        val dy = (arr[i * 3 + 1] - arr[j * 3 + 1]).toDouble()
                        val dz = (arr[i * 3 + 2] - arr[j * 3 + 2]).toDouble()
                        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                    }
                    val rest = d(rig.positions)
                    if (rest < 1e-6) continue
                    val r = d(moved) / rest
                    if (r < lo) lo = r
                    if (r > hi) hi = r
                }
                return lo to hi
            }

            val (dqLo, dqHi) = worstPairwiseRatio(dq.position)
            val (lbsLo, _) = worstPairwiseRatio(lbs.position)
            val pairs = ring.size * (ring.size - 1) / 2
            println(
                "within the y = $jointY ring (${ring.size} vertices, $pairs pairs, all weights 0.5/0.5):\n" +
                    "  dual quaternion  distance ratio in [%.7f, %.7f]\n".format(dqLo, dqHi) +
                    "  linear blend     distance ratio min %.7f".format(lbsLo)
            )
            assertTrue(
                dqLo >= 1.0 - 1e-5 && dqHi <= 1.0 + 1e-5,
                "vertices sharing one rigid transform must keep every mutual distance: " +
                    "got [$dqLo, $dqHi] over $pairs pairs",
            )
            assertTrue(
                lbsLo <= 0.5,
                "linear blending flattens this ring onto a line, so some pair must close up: got $lbsLo",
            )

            // (b) The whole-mesh half, which is a MEASUREMENT of the tradeoff, not a rigidity check.
            // Across a weight gradient the two methods fail in opposite directions.
            val (lbsRatio, edges) = meanEdgeRatio(rig.positions, lbs.position, indices)
            val (dqRatio, _) = meanEdgeRatio(rig.positions, dq.position, indices)
            println(
                "mean |deformed edge| / |rest edge| over $edges edges, twist t = 1:\n" +
                    "  linear blend     %.6f  (shrinks)\n".format(lbsRatio) +
                    "  dual quaternion  %.6f  (bulges)".format(dqRatio)
            )
            assertTrue(lbsRatio < 1.0, "linear blending shrinks under twist: got $lbsRatio over $edges edges")
            assertTrue(dqRatio > 1.0, "dual quaternion blending bulges under twist: got $dqRatio over $edges edges")
            assertTrue(
                abs(dqRatio - 1.0) < abs(lbsRatio - 1.0) * 1.5,
                "the bulge must at least be comparable to the shrink, not worse: " +
                    "lbs $lbsRatio, dq $dqRatio over $edges edges",
            )
        }

    @Test
    fun antipodalInfluencesBlendTheShortWayRatherThanCancelling() = withRig(RiggedFixture.COMMITTED_PATH) { rig ->
        // Past 180 degrees the quaternion a rotation matrix decomposes to has a NEGATIVE dot with the
        // identity, and an implementation that does not flip its sign averages toward zero instead of
        // toward the midpoint. The clips only reach 180, where the dot is exactly 0 and the bug is
        // invisible -- so build the palette by hand at 190 and check the mechanism directly.
        val identity = Matrix4f().identity()
        val past = Matrix4f().identity().rotate(Math.toRadians(190.0).toFloat(), 0f, 1f, 0f)
        val palette = listOf(identity, past)

        val dq = DualQuaternionSkinning.skin(rig.positions, rig.normals, rig.skin.influences, palette)

        assertTrue(
            dq.antipodalFlips > 0,
            "a 190 degree influence beside the identity must trip the antipodality flip; got ${dq.antipodalFlips}",
        )
        // The blend must still be rigid. Without the flip the real part cancels toward zero, and
        // normalize() then amplifies float noise into a wild rotation -- so this catches it.
        val rRest = DualQuaternionSkinning.candyWrapperRadius(rig.positions, jointY)
        val rDq = DualQuaternionSkinning.candyWrapperRadius(dq.position, jointY)
        assertTrue(
            rDq >= 0.99f * rRest,
            "a correctly flipped blend stays rigid and keeps the ring open: rest $rRest, got $rDq",
        )
    }
}
