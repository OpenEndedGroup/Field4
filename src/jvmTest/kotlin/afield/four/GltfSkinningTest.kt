package afield.four

import afield.four.modules.GltfSkinning
import afield.four.modules.GltfSupport
import afield.four.modules.RiggedFixture
import afield.four.modules.assimpToTriMesh
import afield.four.modules.skinnedGltfToTriMesh
import afield.stdlib.TriMesh
import afield.stdlib.Vec3
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Does the skinning actually skin, and does it leave the bind pose alone?
 *
 * Measured against [RiggedFixture]'s generated two-bone bending cylinder — the repository contains
 * no rigged model, so the fixture is written rather than downloaded, and being written is what lets
 * every oracle here be a **closed form** rather than a golden number.
 *
 * ### The four oracles, and why each is the strong version
 *
 *  - **Bind pose is bit-identical, not close.** At `t = 0` every skinned position must equal the
 *    unskinned position *exactly*, all 32 bits. An inverse-bind-matrix mistake — transposed, or
 *    composed on the wrong side, or missing the parent chain — produces a mesh that is a fraction of
 *    a percent off, which `assertEquals(a, b, 1e-4f)` waves straight through. The fixture's constants
 *    exist to make the exact statement true: bind translations of `0` and `2`, an identity first
 *    rotation key, weights from `{0, ¼, ½, ¾, 1}`. See [RiggedFixture].
 *  - **A floored deformation with a predicted direction.** Not "something changed": the mean of the
 *    tip ring at `t = 1` must land within `1e-4` of `(-2 sin 60°, 2 cos 60° + 2)`, a number derived
 *    from the rig on paper and not from this code. Averaging over the whole ring is exact, not
 *    approximate — the ring's own `x` and `z` sum to zero and the palette is affine, so the mean of
 *    the transformed ring is the transform of the mean.
 *  - **Parent-chain composition, two-sided.** The `curl` clip rotates *both* joints. A palette that
 *    forgets to compose `boneUpper` through `boneLower` still passes `bend` — `boneLower` does not
 *    move there — and misses `curl`'s tip by 1.07 units. And the converse: in `bend`, the ring at
 *    `y = 1` (pure `boneLower`) must be *exactly* where it started, which catches a palette that
 *    leaks one joint's motion into another.
 *  - **Weights sum to one, over a stated population.**
 *
 * Every number printed below carries its population, because a `max` over an unstated set of
 * vertices is not a measurement.
 */
class GltfSkinningTest {

    private val fixture = RiggedFixture.COMMITTED_PATH

    // Hand-computed from the rig, in this comment and not from the code under test.
    //   sin 55 = 0.81915204  cos 55 = 0.57357644   sin 25 = 0.42261826  cos 25 = 0.90630779
    // bend @ t=1: tip (0,4,0) -> T(0,-2,0) -> Rz(60) -> T(0,2,0)
    //   = (-2 sin60, 2 cos60 + 2, 0) = (-1.7320508, 3.0, 0)
    // curl @ t=1: tip (0,4,0) -> T(0,-2,0) -> Rz(55) -> T(0,2,0) -> Rz(25)
    //   Rz(55)·(0,2,0)     = (-1.63830409, 1.14715287, 0)
    //   + (0,2,0)          = (-1.63830409, 3.14715287, 0)
    //   Rz(25)·that:  x' = -1.63830409·0.90630779 - 3.14715287·0.42261826
    //                    = -1.48480610 - 1.33004428 = -2.81485038
    //                 y' = -1.63830409·0.42261826 + 3.14715287·0.90630779
    //                    = -0.69237715 + 2.85228897 =  2.15991182
    // (This is worth a note: the first version of that arithmetic, done to five figures, said
    //  x' = -2.80486, and the test failed by 0.0100 against a measured -2.8148518. The *oracle* was
    //  wrong, not the code — which is the point of writing the prediction out by hand rather than
    //  capturing whatever the code produced. Redone to eight figures it agrees to 1.5e-6.)
    // curl @ t=1, the y = 1 ring (pure boneLower): (0,1,0) -> Rz(25) = (-0.4226183, 0.9063078, 0)
    private val bendTipAtOne = Vec3(-1.7320508f, 3.0f, 0f)
    private val curlTipAtOne = Vec3(-2.8148504f, 2.1599118f, 0f)
    private val curlLowerRingAtOne = Vec3(-0.4226183f, 0.9063078f, 0f)

    /** The mean of the vertices whose *bind-pose* height is [bindY], in [mesh]. */
    private fun ringMean(rest: TriMesh, mesh: TriMesh, bindY: Float): Pair<Vec3, Int> {
        var x = 0f; var y = 0f; var z = 0f; var n = 0
        for (v in 0 until mesh.vertexCount) {
            if (kotlin.math.abs(rest.position(v).y - bindY) > 1e-4f) continue
            val p = mesh.position(v); x += p.x; y += p.y; z += p.z; n++
        }
        require(n > 0) { "no vertices at bind height $bindY" }
        return Vec3(x / n, y / n, z / n) to n
    }

    private fun assertNear(expected: Vec3, actual: Vec3, tolerance: Float, what: String) {
        val d = kotlin.math.sqrt(
            (expected.x - actual.x) * (expected.x - actual.x) +
                (expected.y - actual.y) * (expected.y - actual.y) +
                (expected.z - actual.z) * (expected.z - actual.z)
        )
        assertTrue(d <= tolerance, "$what: expected $expected, got $actual, |error| = $d > $tolerance")
    }

    // ==========================================================================================

    @Test
    fun theCommittedFixtureStillMatchesItsGenerator() {
        val committed = File(fixture)
        assertTrue(committed.exists(), "${committed.absolutePath} is missing; run ./gradlew runSkinningEvidence")
        assertEquals(
            RiggedFixture.bendingCylinderGltf(), committed.readText(),
            "the committed rigged fixture has drifted from RiggedFixture; regenerate it with " +
                "./gradlew runSkinningEvidence rather than editing the .gltf by hand",
        )
    }

    @Test
    fun theLoaderFindsTheBonesTheNodesAndBothClips() {
        val model = GltfSupport.loadGltfBasic(fixture, GltfSupport.GltfLoadConfig(loadTextures = false))
        val skin = model.meshes.firstNotNullOfOrNull { it.skin }
        requireNotNull(skin) { "the fixture's mesh came back with no skin at all" }
        val s = skin.skeleton
        println("[skinning] nodes ${s.nodes.map { it.name }}, parents ${s.nodes.map { it.parent }}")
        println("[skinning] bones ${s.bones.map { "${it.name}@node${it.node}" }}, clips ${s.clips.map { it.name }}")
        assertEquals(2, s.bones.size, "two bones")
        assertEquals(listOf("boneLower", "boneUpper"), s.bones.map { it.name })
        // Assimp adds its own ROOT above the file's two scene roots, so five nodes, not four. The
        // skeleton root (armature) is NOT the mesh's node (cylinder) -- the case the model exists for.
        assertEquals(listOf("ROOT", "cylinder", "armature", "boneLower", "boneUpper"), s.nodes.map { it.name })
        assertEquals(listOf(-1, 0, 0, 2, 3), s.nodes.map { it.parent }, "cylinder and armature are siblings")
        assertEquals(listOf("bend", "curl"), s.clips.map { it.name }, "both clips, not just mAnimations()[0]")
        assertEquals(1, s.clip("bend")!!.channels.size)
        assertEquals(2, s.clip("curl")!!.channels.size, "curl animates both joints")
    }

    @Test
    fun weightsSumToOneOnEveryVertexAndTheFourWideCapNeverBit() {
        val model = GltfSupport.loadGltfBasic(fixture, GltfSupport.GltfLoadConfig(loadTextures = false))
        val infl = model.meshes.firstNotNullOf { it.skin }.influences
        val population = infl.vertexCount
        val worst = infl.worstWeightSumError()
        println("[skinning] weights: population = all $population vertices; worst |sum(w) - 1| = $worst; " +
            "unweighted = ${infl.unweightedVertices()}; discarded influences = ${infl.discardedInfluences}")
        assertEquals(RiggedFixture.VERTEX_COUNT, population, "the whole fixture mesh")
        // Exactly zero, not an epsilon: the fixture's weights are quarters, which are exact in binary.
        assertEquals(0f, worst, "weights must sum to exactly 1 over all $population vertices")
        assertEquals(0, infl.unweightedVertices())
        assertEquals(0, infl.discardedInfluences, "this fixture has two influences per vertex, so the cap is idle")
    }

    @Test
    fun theBindPoseIsBitIdenticalToTheUnskinnedMesh() {
        val rest = assimpToTriMesh(fixture)
        val skinned = skinnedGltfToTriMesh(fixture, clip = "bend", seconds = 0f)
        assertEquals(rest.vertexCount, skinned.vertexCount)
        val population = rest.vertexCount
        var exact = 0
        var worst = 0f
        for (v in 0 until population) {
            val a = rest.position(v)
            val b = skinned.position(v)
            if (a.x == b.x && a.y == b.y && a.z == b.z) exact++
            worst = maxOf(
                worst,
                maxOf(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y), kotlin.math.abs(a.z - b.z)),
            )
        }
        println("[skinning] bind pose: population = all $population vertices; bit-identical = $exact; " +
            "worst component difference = $worst")
        assertEquals(
            population, exact,
            "every vertex must be bit-identical at the bind pose; worst component difference was $worst",
        )
    }

    @Test
    fun theBendClipMovesTheTipByThePredictedAmountInThePredictedDirection() {
        val rest = assimpToTriMesh(fixture)
        val at = { t: Float -> skinnedGltfToTriMesh(fixture, clip = "bend", seconds = t) }

        var maxDisplacement = 0f
        val one = at(1f)
        for (v in 0 until one.vertexCount) {
            val a = rest.position(v)
            val b = one.position(v)
            val d = kotlin.math.sqrt(
                (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z)
            )
            if (d > maxDisplacement) maxDisplacement = d
        }
        val (tip, tipCount) = ringMean(rest, one, 4f)
        val (base, baseCount) = ringMean(rest, one, 0f)
        val (lower, lowerCount) = ringMean(rest, one, 1f)
        println("[skinning] bend@1: population = all ${one.vertexCount} vertices; " +
            "max |displacement| = $maxDisplacement")
        println("[skinning] bend@1: tip ring mean over $tipCount vertices = $tip (predicted $bendTipAtOne)")
        println("[skinning] bend@1: base ring mean over $baseCount = $base; y=1 ring over $lowerCount = $lower")

        // A named floor, over the whole mesh. The closed form for the tip ring's centre is 2.0
        // exactly; the ring's outer vertices go slightly further, and 2.0616 is what is measured.
        assertTrue(
            maxDisplacement > 1.8f,
            "max vertex displacement over all ${one.vertexCount} vertices was $maxDisplacement, " +
                "which is not the >1.8 the rig predicts",
        )
        assertNear(bendTipAtOne, tip, 1e-4f, "bend@1 tip ring mean over $tipCount vertices")
        // boneLower is not animated in `bend`, so everything weighted only to it must be EXACTLY put.
        assertNear(Vec3(0f, 0f, 0f), base, 1e-6f, "bend@1 base ring ($baseCount vertices) must not move")
        assertNear(Vec3(0f, 1f, 0f), lower, 1e-6f, "bend@1 y=1 ring ($lowerCount vertices) must not move")

        // And the bend is monotone in t, so a palette that samples the wrong key is visible.
        val displacements = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { t ->
            val m = at(t)
            val (r, _) = ringMean(rest, m, 4f)
            r.x
        }
        println("[skinning] bend tip-ring mean x at t = 0, .25, .5, .75, 1: $displacements")
        assertEquals(0f, displacements.first(), 1e-6f, "t = 0 is the bind pose")
        for (i in 1 until displacements.size) {
            assertTrue(
                displacements[i] < displacements[i - 1],
                "the tip must keep moving in -x as t rises; got $displacements",
            )
        }
    }

    @Test
    fun theCurlClipComposesDownTheParentChain() {
        val rest = assimpToTriMesh(fixture)
        val curl = skinnedGltfToTriMesh(fixture, clip = "curl", seconds = 1f)
        val (tip, tipCount) = ringMean(rest, curl, 4f)
        val (lower, lowerCount) = ringMean(rest, curl, 1f)
        val (base, baseCount) = ringMean(rest, curl, 0f)
        println("[skinning] curl@1: tip ring mean over $tipCount vertices = $tip (predicted $curlTipAtOne)")
        println("[skinning] curl@1: y=1 ring mean over $lowerCount = $lower (predicted $curlLowerRingAtOne)")
        println("[skinning] curl@1: base ring mean over $baseCount = $base (predicted the origin)")

        // The decisive one: boneUpper's global must include boneLower's rotation. Dropping the
        // composition leaves the tip at boneUpper-only, which is 1.07 units away from this.
        assertNear(curlTipAtOne, tip, 1e-4f, "curl@1 tip ring mean over $tipCount vertices")
        assertNear(curlLowerRingAtOne, lower, 1e-4f, "curl@1 y=1 ring mean over $lowerCount vertices")
        // boneLower rotates about its own origin, so the base ring's centre is a fixed point.
        assertNear(Vec3(0f, 0f, 0f), base, 1e-5f, "curl@1 base ring ($baseCount vertices) is the pivot")

        val bend = skinnedGltfToTriMesh(fixture, clip = "bend", seconds = 1f)
        val (bendTip, _) = ringMean(rest, bend, 4f)
        val gap = kotlin.math.sqrt(
            (bendTip.x - tip.x) * (bendTip.x - tip.x) + (bendTip.y - tip.y) * (bendTip.y - tip.y)
        )
        println("[skinning] curl@1 tip is $gap from bend@1 tip — two clips, not one read twice")
        assertTrue(gap > 1f, "the two clips must give genuinely different poses; got $gap")
    }

    @Test
    fun blendingTwoClipsLandsBetweenThem() {
        val rest = assimpToTriMesh(fixture)
        val pose = { mix: Float ->
            skinnedGltfToTriMesh(fixture) { s ->
                s.blendedPalette(s.clip("bend"), 1f, s.clip("curl"), 1f, mix)
            }
        }
        val (a, n) = ringMean(rest, pose(0f), 4f)
        val (mid, _) = ringMean(rest, pose(0.5f), 4f)
        val (b, _) = ringMean(rest, pose(1f), 4f)
        println("[skinning] blend tip ring means over $n vertices: mix=0 $a, mix=0.5 $mid, mix=1 $b")
        assertNear(bendTipAtOne, a, 1e-4f, "mix = 0 must be exactly the bend pose")
        assertNear(curlTipAtOne, b, 1e-4f, "mix = 1 must be exactly the curl pose")
        // The palette blend is componentwise -- the same linear blend LBS itself commits to -- so the
        // midpoint of the tip is the midpoint of the two tips, not a slerp. Asserted as such.
        assertNear(Vec3((a.x + b.x) / 2f, (a.y + b.y) / 2f, 0f), mid, 1e-4f, "mix = 0.5 tip")
    }

    @Test
    fun skinningIsOptOutAndTheRigidPathIsUnchangedByIt() {
        val model = GltfSupport.loadGltfBasic(
            fixture, GltfSupport.GltfLoadConfig(loadTextures = false, loadSkins = false),
        )
        assertTrue(model.meshes.all { it.skin == null }, "loadSkins = false must leave skin null")
        // And a rigid file is still rigid with skins on: the four-node scene the bridge test uses.
        val rigid = GltfSupport.loadGltfBasic(
            "dev_docs/agents/fixtures/path_tracing/transforms.gltf",
            GltfSupport.GltfLoadConfig(loadTextures = false),
        )
        println("[skinning] transforms.gltf: ${rigid.meshes.size} meshes, " +
            "${rigid.meshes.count { it.skin != null }} skinned")
        assertEquals(0, rigid.meshes.count { it.skin != null }, "a rigid file must gain no skin")
    }

    @Test
    fun theNormalTransformIsTheInverseTransposeAndTheSurfaceStaysUnitLength() {
        val mesh = skinnedGltfToTriMesh(fixture, clip = "curl", seconds = 1f)
        var worst = 0f
        var counted = 0
        for (v in 0 until mesh.vertexCount) {
            val n = mesh.normal(v) ?: continue
            counted++
            worst = maxOf(worst, kotlin.math.abs(kotlin.math.sqrt(n.x * n.x + n.y * n.y + n.z * n.z) - 1f))
        }
        println("[skinning] normals: population = $counted of ${mesh.vertexCount} vertices; " +
            "worst |length - 1| = $worst")
        assertEquals(mesh.vertexCount, counted, "every vertex carries a normal")
        assertTrue(worst < 1e-5f, "skinned normals must stay unit length; worst was $worst")

        // The inverse transpose is only distinguishable from the plain 3x3 where the blend is
        // non-rigid -- the 50/50 ring. Turning it off must therefore change *those* normals and
        // leave the single-influence rings alone, which is the two-sided version of the claim.
        val approx = skinnedGltfToTriMesh(
            fixture, clip = "curl", seconds = 1f,
            options = GltfSkinning.SkinningOptions(exactNormalMatrix = false),
        )
        val rest = assimpToTriMesh(fixture)
        var midChanged = 0
        var endsChanged = 0
        for (v in 0 until mesh.vertexCount) {
            val a = mesh.normal(v) ?: continue
            val b = approx.normal(v) ?: continue
            val d = kotlin.math.abs(a.x - b.x) + kotlin.math.abs(a.y - b.y) + kotlin.math.abs(a.z - b.z)
            val y = rest.position(v).y
            if (kotlin.math.abs(y - 2f) < 1e-4f) { if (d > 1e-5f) midChanged++ } else if (d > 1e-5f) endsChanged++
        }
        println("[skinning] exact vs approximate normal matrix: $midChanged of the 50/50 ring's " +
            "${RiggedFixture.SEGMENTS} vertices differ; $endsChanged of the other " +
            "${RiggedFixture.VERTEX_COUNT - RiggedFixture.SEGMENTS} differ")
        assertEquals(0, endsChanged, "a single-influence vertex of a rigid skeleton must be unaffected")
        assertTrue(midChanged > 0, "the blended ring must be where the two differ")
    }
}
