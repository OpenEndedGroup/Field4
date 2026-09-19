package afield.four

import afield.four.modules.GltfSkinning
import afield.four.modules.GltfSupport
import afield.four.modules.StressFixture
import afield.four.modules.assimpToTriMesh
import afield.four.modules.posedGltfToTriMesh
import afield.four.modules.skinnedGltfToTriMesh
import afield.stdlib.Vec3
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three things the well-behaved cylinder cannot test, on the rig built to test them.
 *
 * [GltfSkinningTest] measures against [afield.four.modules.RiggedFixture] — two rigid joints, two
 * influences per vertex, weights exact in binary — because that is what makes its bind-pose oracle
 * bit-exact. The price is that three real behaviours are invisible to it, and each is a place where
 * one skinning implementation quietly differs from the next:
 *
 *  1. **A non-rigid joint.** Every joint in the cylinder is a rotation, and for a rotation the
 *     inverse transpose *is* the matrix. So the cylinder cannot distinguish the exact normal
 *     transform from the shortcut everyone reaches for. [StressFixture]'s `shearBone` animates a
 *     non-uniform **scale**, and the difference then has a closed form: `1.0` against `0.8`.
 *  2. **More than four influences.** The cylinder has two per vertex, so the cap never fires and its
 *     discard counters are untested. Here three vertices carry six, spread across `JOINTS_0` and
 *     `JOINTS_1`, and the cap costs a measurable `0.318` units of displacement.
 *  3. **A skinned mesh node with its own transform**, which glTF 2.0 §3.7.3.2 says to ignore.
 *
 * None of the numbers below were captured from the code: they are worked out in [StressFixture]'s
 * documentation from the rig's geometry, and the fixture was then built to have them.
 */
class GltfSkinningStressTest {

    private val fixture = StressFixture.COMMITTED_PATH

    private fun dot(a: Vec3, b: Vec3) = a.x * b.x + a.y * b.y + a.z * b.z

    @Test
    fun theCommittedStressFixtureStillMatchesItsGenerator() {
        val committed = File(fixture)
        assertTrue(committed.exists(), "${committed.absolutePath} is missing; run ./gradlew runSkinningEvidence")
        assertEquals(
            StressFixture.shearAndCapGltf(), committed.readText(),
            "the committed stress fixture has drifted; regenerate with ./gradlew runSkinningEvidence",
        )
    }

    /**
     * Assimp really does read `JOINTS_1`, and the cap really does keep the largest four.
     *
     * Worth an explicit assertion rather than an assumption: had Assimp dropped the second joint set,
     * the discard count would have been `0` and *every* other assertion in this class would still
     * have passed — the mesh would simply have been a four-influence mesh that the cap never touched.
     */
    @Test
    fun sixInfluencesArriveAndTheCapKeepsTheLargestFourAndSaysWhatItDropped() {
        val model = GltfSupport.loadGltfBasic(fixture, GltfSupport.GltfLoadConfig(loadTextures = false))
        val infl = model.meshes.firstNotNullOf { it.skin }.influences
        println("[skinning-stress] population: all ${infl.vertexCount} vertices; " +
            "discarded ${infl.discardedInfluences}, largest discarded ${infl.largestDiscardedWeight}, " +
            "worst |sum(w) - 1| after renormalisation = ${infl.worstWeightSumError()}")
        assertEquals(7, infl.vertexCount, "4 plate corners + 3 triangle corners")
        // Two influences dropped on each of the three six-influence vertices. A 0 here means Assimp
        // ignored JOINTS_1 and this whole fixture is measuring something easier than it claims.
        assertEquals(6, infl.discardedInfluences)
        assertEquals(0.06f, infl.largestDiscardedWeight, 1e-7f, "the fifth-largest weight, 0.06")
        assertEquals(0f, infl.worstWeightSumError(), 1e-6f, "renormalised back to 1 over all 7 vertices")

        // The kept four are 0.30/0.25/0.20/0.15 renormalised by their sum, 0.90.
        val kept = (0..3).map { infl.weight[4 * 4 + it] }
        println("[skinning-stress] vertex 4 kept weights $kept")
        val expected = StressFixture.SIX_WEIGHTS.take(4).map { it / 0.90f }
        for (k in 0..3) assertEquals(expected[k], kept[k], 1e-6f, "influence $k")
        assertEquals(listOf(1, 2, 3, 4), (0..3).map { infl.jointIndex[4 * 4 + it] }, "the six cap bones are 1..6")
    }

    /**
     * What the cap costs, as a number.
     *
     * Each cap bone `i` translates `(i, 0, 0)` at `t = 1`, so the triangle's displacement is
     * `Σ wᵢ · i`. With all six influences that is `2.54`; with the largest four renormalised it is
     * `2.0 / 0.9 = 2.2222`. Asserting the *capped* value and explicitly refusing the uncapped one is
     * the two-sided version — it pins the discard rather than merely tolerating it.
     */
    @Test
    fun theFourWideCapChangesTheAnswerByAKnownAmount() {
        val rest = posedGltfToTriMesh(fixture) { it.palette(null, 0f) }
        val moved = skinnedGltfToTriMesh(fixture, clip = "shear", seconds = 1f)
        val dx = moved.position(4).x - rest.position(4).x
        println("[skinning-stress] six-influence triangle x displacement = $dx " +
            "(capped ${StressFixture.CAPPED_DISPLACEMENT}, uncapped ${StressFixture.UNCAPPED_DISPLACEMENT})")
        assertEquals(StressFixture.CAPPED_DISPLACEMENT, dx, 1e-5f)
        assertTrue(
            kotlin.math.abs(dx - StressFixture.UNCAPPED_DISPLACEMENT) > 0.3f,
            "this must NOT equal the uncapped 2.54; got $dx",
        )
        // All three corners move together, because they carry identical weights.
        for (v in 4..6) assertEquals(StressFixture.CAPPED_DISPLACEMENT, moved.position(v).x - rest.position(v).x, 1e-5f)
    }

    /**
     * The exact inverse transpose against the shortcut, on the one rig where they differ by a
     * number you can write down.
     *
     * The plate is flat, so its face normal *is* the truth. Under `shearBone`'s `(1, 2, 1)` scale the
     * exact vertex normal must agree with it to within rounding; the plain blended 3×3 must score
     * exactly `0.8`, which is `2 × 0.4472136 × 0.8944272`.
     */
    @Test
    fun theExactInverseTransposeIsRightWhereTheShortcutIsMeasurablyWrong() {
        val exact = skinnedGltfToTriMesh(fixture, clip = "shear", seconds = 1f)
        val approx = skinnedGltfToTriMesh(
            fixture, clip = "shear", seconds = 1f,
            options = GltfSkinning.SkinningOptions(exactNormalMatrix = false),
        )
        val face = exact.faceNormal(0)
        val predicted = StressFixture.shearedNormal
        println("[skinning-stress] sheared face normal $face, predicted ${predicted.toList()}")
        assertEquals(predicted[0], face.x, 1e-5f)
        assertEquals(predicted[1], face.y, 1e-5f)
        assertEquals(predicted[2], face.z, 1e-5f)

        val exactDot = dot(exact.normal(0)!!, face)
        val approxDot = dot(approx.normal(0)!!, face)
        println("[skinning-stress] population: the 4 plate corners; " +
            "exact normal · face = $exactDot, plain blended 3x3 · face = $approxDot")
        for (v in 0..3) {
            assertEquals(1f, dot(exact.normal(v)!!, face), 1e-5f, "corner $v: the exact normal is the true one")
        }
        assertEquals(0.8f, approxDot, 1e-5f, "the shortcut's error is exactly 0.8, not approximately wrong")
    }

    /**
     * glTF 2.0 §3.7.3.2: a skinned mesh node's own transform is not applied.
     *
     * The `plate` node is translated `(0, 10, 0)`. The skinned path must ignore it and the rigid path
     * must apply it — the two disagreeing by exactly ten is the assertion, and it is the reason
     * [skinnedGltfToTriMesh] exists as its own function rather than as a flag on [assimpToTriMesh].
     */
    @Test
    fun aSkinnedMeshNodesOwnTransformIsIgnoredAndTheRigidPathStillAppliesIt() {
        val skinned = posedGltfToTriMesh(fixture) { it.palette(null, 0f) }
        val rigid = assimpToTriMesh(fixture)
        // The plate's four corners specifically, on both sides. Taking the min over the *whole* mesh
        // would compare the plate against the separate triangle and measure 8.707, which is a true
        // number about nothing.
        val skinnedY = (0..3).map { skinned.position(it).y }
        val rigidY = (0..3).map { rigid.position(it).y }
        println("[skinning-stress] plate corners, skinned y in [${skinnedY.min()}, ${skinnedY.max()}]; " +
            "rigid path y in [${rigidY.min()}, ${rigidY.max()}]")
        // The plate binds centred at y = 2, spanning +/- sqrt(2)/2.
        assertEquals(2f - 0.70710678f, skinnedY.min(), 1e-5f)
        assertEquals(2f + 0.70710678f, skinnedY.max(), 1e-5f)
        assertEquals(10f, rigidY.min() - skinnedY.min(), 1e-4f, "exactly the node's ten units apart")
        assertEquals(10f, rigidY.max() - skinnedY.max(), 1e-4f)
    }

    @Test
    fun theSkeletonRunsThroughANonBoneWrapperNodeAndFindsAllSevenBones() {
        val model = GltfSupport.loadGltfBasic(fixture, GltfSupport.GltfLoadConfig(loadTextures = false))
        val s = model.meshes.firstNotNullOf { it.skin }.skeleton
        println("[skinning-stress] nodes ${s.nodes.map { it.name }}, bones ${s.bones.map { it.name }}")
        assertEquals(
            listOf("ROOT", "plate", "rig", "shearBone", "capBone1", "capBone2", "capBone3", "capBone4", "capBone5", "capBone6"),
            s.nodes.map { it.name },
        )
        // `rig` is a node and not a bone; every bone's parent chain passes through it.
        assertTrue("rig" !in s.bones.map { it.name })
        assertEquals(7, s.bones.size)
        assertTrue(s.bones.all { s.nodes[it.node].parent == 2 }, "every bone hangs off the `rig` wrapper")
    }
}
