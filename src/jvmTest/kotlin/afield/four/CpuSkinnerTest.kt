package afield.four

import afield.four.modules.CpuSkinner
import afield.four.modules.GltfSkinning
import afield.four.modules.GltfSupport
import afield.four.modules.RiggedFixture
import org.lwjgl.system.MemoryUtil.memFree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [CpuSkinner] writes deformed vertices back into the GPU vertex buffer the loader already owns, so
 * the bundled glTF shaders draw a deforming character with no shader change. This is the test for
 * the one thing that makes that hard.
 *
 * ### The bug
 *
 * Skinning *in place* is not idempotent if you read what you last wrote. A skinner that reads the
 * buffer it writes would, on frame two, skin an already-skinned mesh — and the character would drift
 * apart over a few seconds, slowly enough to look like a physics problem rather than a bookkeeping
 * one. [CpuSkinner] snapshots the bind pose at construction and every `pose` reads that snapshot;
 * [posingTwiceWithTheSamePaletteGivesTheSameAnswer] is the assertion that keeps it that way, and it
 * is checked **bit for bit**, because the wrong version's first frame of drift is small.
 */
class CpuSkinnerTest {

    private fun <T> withInstance(block: (GltfSupport.MeshInstance) -> T): T {
        val model = GltfSupport.loadGltfBasic(
            RiggedFixture.COMMITTED_PATH, GltfSupport.GltfLoadConfig(loadTextures = false),
        )
        try {
            return block(model.meshes.first { it.skin != null })
        } finally {
            // loadGltfBasic hands back memAlloc'd host memory; the TriMesh bridges free it and so
            // must this, or a test run leaks a buffer per load.
            for (m in model.meshes) {
                memFree(m.buffers.vertexData)
                memFree(m.buffers.indexData)
                m.buffers.tangentData?.let { memFree(it) }
            }
        }
    }

    private fun positions(instance: GltfSupport.MeshInstance): FloatArray {
        val f = instance.buffers.vertexData.asFloatBuffer()
        return FloatArray(instance.buffers.vertexCount * 3) { i ->
            f.get((i / 3) * 12 + i % 3)
        }
    }

    private fun row(instance: GltfSupport.MeshInstance, v: Int): FloatArray {
        val f = instance.buffers.vertexData.asFloatBuffer()
        return FloatArray(12) { f.get(v * 12 + it) }
    }

    @Test
    fun whatIsWrittenIntoTheVertexBufferIsExactlyWhatTheSkinnerComputed() {
        withInstance { instance ->
            val skinner = CpuSkinner(instance)
            assertEquals(listOf("bend", "curl", "twist"), skinner.clips)
            val out = skinner.pose("bend", 1f)
            val inBuffer = positions(instance)
            var worst = 0f
            for (i in inBuffer.indices) worst = maxOf(worst, kotlin.math.abs(inBuffer[i] - out.position[i]))
            println("[cpu-skinner] population: all ${instance.buffers.vertexCount} vertices; " +
                "worst buffer-vs-computed difference = $worst")
            assertEquals(0f, worst, "the buffer must hold exactly what skin() returned")
        }
    }

    /**
     * The whole point of the class. Posing twice must give the same answer; if it does not, the
     * skinner is feeding its own output back in.
     */
    @Test
    fun posingTwiceWithTheSamePaletteGivesTheSameAnswer() {
        withInstance { instance ->
            val skinner = CpuSkinner(instance)
            skinner.pose("bend", 1f)
            val first = positions(instance)
            skinner.pose("bend", 1f)
            val second = positions(instance)
            var identical = 0
            var worst = 0f
            for (i in first.indices) {
                if (first[i] == second[i]) identical++
                worst = maxOf(worst, kotlin.math.abs(first[i] - second[i]))
            }
            println("[cpu-skinner] population: ${first.size} floats over " +
                "${instance.buffers.vertexCount} vertices; bit-identical across two poses = $identical; " +
                "worst difference = $worst")
            assertEquals(first.size, identical, "posing is not cumulative; worst difference was $worst")

            // And a third pose at a *different* time really does move -- so the above is not passing
            // because `pose` quietly does nothing. Stated per VERTEX and per region rather than as a
            // count of changed floats: `bend` animates only boneUpper, so the bottom two rings must
            // not move at all, and a z-rotation leaves z alone, so "most of the floats change" is
            // simply false (68 of 180 do) and would be a bad assertion even when it passed.
            skinner.pose("bend", 0.5f)
            val third = positions(instance)
            val movedVertices = (0 until instance.buffers.vertexCount).filter { v ->
                (0..2).any { first[v * 3 + it] != third[v * 3 + it] }
            }
            val lower = movedVertices.count { it < 24 }
            val upper = movedVertices.count { it >= 24 }
            println("[cpu-skinner] changing the time moved ${movedVertices.size} of " +
                "${instance.buffers.vertexCount} vertices: $lower of the 24 pure-boneLower ones, " +
                "$upper of the 36 the clip actually drives")
            assertEquals(0, lower, "`bend` does not animate boneLower, so the bottom two rings are fixed")
            // 34, not 36, and the two are identifiable. `bend` rotates about +z through the joint at
            // (0, 2, 0), and ring 2 has vertices at theta = 90 and 270 -- exactly (0, 2, +/-0.5), which
            // sit ON the axis of rotation and are therefore fixed points of it. (They are 50/50
            // blended, but the blend of the identity and a rotation still fixes that rotation's axis.)
            // The first version of this assertion said 36 and was wrong about the geometry.
            val stationary = (24 until 60).filter { it !in movedVertices }
            println("[cpu-skinner] the $stationary that did not move are the ring-2 vertices on the " +
                "rotation axis at x = 0")
            assertEquals(34, upper, "every driven vertex except the two on the rotation axis must move")
            assertEquals(listOf(27, 33), stationary, "theta = 90 and 270 of the middle ring")
        }
    }

    @Test
    fun resetRestoresTheBindPoseBitForBit() {
        withInstance { instance ->
            val before = positions(instance)
            val skinner = CpuSkinner(instance)
            skinner.pose("curl", 1f)
            skinner.reset()
            val after = positions(instance)
            val identical = before.indices.count { before[it] == after[it] }
            println("[cpu-skinner] reset: $identical of ${before.size} floats bit-identical to the load")
            assertEquals(before.size, identical)
        }
    }

    /**
     * Only positions and normals are overwritten. The packed layout is
     * `[pos.xyz][normal.xyz][uv.xy][color.rgba]`, and clobbering the last six floats would be a
     * quiet way to turn every skinned character white with no UVs.
     */
    @Test
    fun theUvAndColourFieldsAreLeftAlone() {
        withInstance { instance ->
            val before = (0 until instance.buffers.vertexCount).map { row(instance, it) }
            CpuSkinner(instance).pose("curl", 1f)
            val after = (0 until instance.buffers.vertexCount).map { row(instance, it) }
            var changedTail = 0
            var changedHead = 0
            for (v in before.indices) {
                for (k in 6..11) if (before[v][k] != after[v][k]) changedTail++
                for (k in 0..5) if (before[v][k] != after[v][k]) changedHead++
            }
            println("[cpu-skinner] population: all ${before.size} vertices; " +
                "uv+colour floats changed = $changedTail of ${before.size * 6}; " +
                "pos+normal floats changed = $changedHead of ${before.size * 6}")
            assertEquals(0, changedTail, "uv and colour must be untouched")
            assertTrue(changedHead > 0, "positions and normals must have changed")
        }
    }

    @Test
    fun aRigidMeshRefusesAsSkinnerRatherThanSilentlyDoingNothing() {
        val model = GltfSupport.loadGltfBasic(
            "dev_docs/agents/fixtures/path_tracing/transforms.gltf",
            GltfSupport.GltfLoadConfig(loadTextures = false),
        )
        try {
            val failure = runCatching { CpuSkinner(model.meshes.first()) }.exceptionOrNull()
            println("[cpu-skinner] rigid mesh -> ${failure?.message}")
            assertTrue(failure is IllegalArgumentException, "expected a refusal, got $failure")
            assertTrue("has no skin" in (failure.message ?: ""), "and it should say why")
        } finally {
            for (m in model.meshes) {
                memFree(m.buffers.vertexData)
                memFree(m.buffers.indexData)
                m.buffers.tangentData?.let { memFree(it) }
            }
        }
    }

    @Test
    fun theSkinnerAndTheTriMeshBridgeAgreeVertexForVertex() {
        withInstance { instance ->
            val skinner = CpuSkinner(instance)
            skinner.pose("curl", 1f)
            val viaBuffer = positions(instance)
            val viaBridge = afield.four.modules.skinnedGltfToTriMesh(
                RiggedFixture.COMMITTED_PATH, clip = "curl", seconds = 1f,
            )
            var worst = 0f
            for (v in 0 until viaBridge.vertexCount) {
                val p = viaBridge.position(v)
                worst = maxOf(
                    worst,
                    maxOf(
                        kotlin.math.abs(p.x - viaBuffer[v * 3]),
                        kotlin.math.abs(p.y - viaBuffer[v * 3 + 1]),
                        kotlin.math.abs(p.z - viaBuffer[v * 3 + 2]),
                    ),
                )
            }
            println("[cpu-skinner] in-place buffer vs TriMesh bridge, over all " +
                "${viaBridge.vertexCount} vertices: worst difference = $worst")
            assertEquals(0f, worst, "the two write paths must agree exactly")
        }
    }

    @Test
    fun theApproximateNormalOptionReachesTheBuffer() {
        withInstance { instance ->
            // BOTH skinners are constructed before either poses. A skinner snapshots the bind pose
            // at construction, so building the second one after the first had already written the
            // buffer would snapshot a deformed mesh -- the very mistake this class exists to make
            // impossible, reintroduced in the test that checks it.
            val exactSkinner = CpuSkinner(instance)
            val approxSkinner = CpuSkinner(instance, GltfSkinning.SkinningOptions(exactNormalMatrix = false))

            exactSkinner.pose("curl", 1f)
            val exact = (0 until instance.buffers.vertexCount).map { row(instance, it) }
            approxSkinner.pose("curl", 1f)
            val approx = (0 until instance.buffers.vertexCount).map { row(instance, it) }

            // Counted over the whole mesh rather than asserted on one chosen vertex: only the
            // 50/50 ring can differ at all (a single-influence vertex of a rigid skeleton is exact
            // either way), and not every vertex of that ring does, so naming one is a coin flip.
            val ring = 24 until 36   // y = 2, the blended ring
            fun gap(v: Int) = (3..5).maxOf { kotlin.math.abs(exact[v][it] - approx[v][it]) }
            // A THRESHOLD, not `!=`. Measured: 35 of the 60 vertices differ in their last bits even
            // though the shortcut is algebraically exact for every single-influence vertex of a rigid
            // skeleton -- because `Matrix3f.invert().transpose()` computes a general cofactor inverse,
            // which for a rotation returns the rotation only to within rounding. The exact path
            // therefore costs a few ULP where it buys nothing. Worth knowing; not worth asserting on.
            val ulp = exact.indices.filter { gap(it) > 0f && gap(it) <= 1e-5f }
            val real = exact.indices.filter { gap(it) > 1e-5f }
            println("[cpu-skinner] population: all ${exact.size} vertices; " +
                "normals differing by more than 1e-5 = ${real.size} (all in ring $ring); " +
                "differing only in the last bits = ${ulp.size}; worst gap = ${exact.indices.maxOf { gap(it) }}")
            assertTrue(real.isNotEmpty(), "the option must reach the buffer")
            assertTrue(real.all { it in ring }, "only the blended ring may differ meaningfully; got $real")
            assertTrue(ulp.all { gap(it) < 1e-6f }, "the rigid vertices' disagreement must be rounding-sized")
            // And the positions are identical either way -- the option is about normals only.
            for (v in exact.indices) for (k in 0..2) {
                assertEquals(exact[v][k], approx[v][k], "vertex $v position component $k must not depend on it")
            }
        }
    }
}
