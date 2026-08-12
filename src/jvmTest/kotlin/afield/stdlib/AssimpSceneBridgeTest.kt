package afield.stdlib

import afield.four.modules.GltfSupport
import afield.four.modules.assimpToTriMesh
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What [assimpToTriMesh] does to a **scene** rather than to one flat mesh.
 *
 * A GLB is not a mesh: it is a node hierarchy where each node carries a transform, several nodes can
 * reference the same mesh, and a node transform is a full 4x4 — which means it can *mirror*. A bridge
 * that flattens all of that into one [TriMesh] has to answer four questions, and the answers cannot be
 * read off the code with any confidence, so this constructs the adversarial scene and measures them.
 *
 * The scene is authored here rather than committed, as glTF 2.0 text with its buffer inline as a data
 * URI: one asymmetric triangle, instanced by four nodes —
 *
 * | node | transform | tests |
 * |---|---|---|
 * | `identity` | none | the baseline |
 * | `translated` | `translation (3, 0, 0)` | is the transform applied at all |
 * | `nested` | child of `translated`, `translation (0, 2, 0)` | is the parent chain **composed** |
 * | `mirrored` | `scale (-1, 1, 1)`, `translation (-3, 0, 0)` | a **negative-determinant** transform |
 *
 * ### The bug this test was written to catch, and did
 *
 * `GltfSupport.MeshInstance.bindPose` is a translation/rotation/scale triple, and a mirror does not fit
 * in one: `Matrix4f.getScale` is `sqrt(...)` per basis column and therefore always positive, so
 * `decomposeToTRS` returns `s = (1, 1, 1)` for a mirrored node and the recomposed matrix comes back with
 * determinant `+1`. Measured: Assimp hands over row 1 `[-1 0 0 -3]` with 3x3 determinant `-1.0`;
 * `bindPose.toMatrix()` gives `[1 0 0 -3]` with determinant `+1.0`. A bridge reading `bindPose` therefore
 * placed the mirrored instance **un-mirrored** — vertex at `x = -2` instead of `x = -4` — which is the
 * worst kind of wrong, because it is a plausible mesh with no warning attached.
 *
 * The fix was to carry the un-decomposed world matrix (`MeshInstance.bindMatrix`) and use it here. The
 * GPU paths still read `bindPose`, so they still carry the flattening; that is stated, not fixed.
 */
class AssimpSceneBridgeTest {

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("afield-assimp-scene").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * A glTF 2.0 scene: one right triangle with per-vertex colours, instanced by the four nodes above.
     *
     * Asymmetric on purpose — the right-angle corner is at the local origin and the long leg runs +x —
     * so a mirror is unmistakable in the coordinates rather than a matter of interpretation.
     */
    private fun adversarialGltf(): String {
        val positions = listOf(
            floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0.4f, 0f),
        )
        val colors = listOf(
            floatArrayOf(1f, 0.2f, 0.2f, 1f), floatArrayOf(0.2f, 1f, 0.2f, 1f), floatArrayOf(0.2f, 0.2f, 1f, 1f),
        )
        val positionBytes = 3 * 3 * 4
        val colorBytes = 3 * 4 * 4
        val indexBytes = 3 * 2
        val buffer = ByteBuffer.allocate(positionBytes + colorBytes + indexBytes + 2).order(ByteOrder.LITTLE_ENDIAN)
        for (p in positions) for (c in p) buffer.putFloat(c)
        for (c in colors) for (v in c) buffer.putFloat(v)
        for (i in 0..2) buffer.putShort(i.toShort())
        val base64 = Base64.getEncoder().encodeToString(buffer.array())
        return """
        {
         "asset": {"version": "2.0", "generator": "AssimpSceneBridgeTest"},
         "scene": 0,
         "scenes": [{"nodes": [0, 1, 3]}],
         "nodes": [
          {"name": "identity",   "mesh": 0},
          {"name": "translated", "mesh": 0, "translation": [3.0, 0.0, 0.0], "children": [2]},
          {"name": "nested",     "mesh": 0, "translation": [0.0, 2.0, 0.0]},
          {"name": "mirrored",   "mesh": 0, "scale": [-1.0, 1.0, 1.0], "translation": [-3.0, 0.0, 0.0]}
         ],
         "meshes": [{"name": "tri", "primitives": [
            {"attributes": {"POSITION": 0, "COLOR_0": 1}, "indices": 2, "mode": 4}]}],
         "accessors": [
          {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3",
           "min": [0.0, 0.0, 0.0], "max": [1.0, 0.4, 0.0]},
          {"bufferView": 1, "componentType": 5126, "count": 3, "type": "VEC4"},
          {"bufferView": 2, "componentType": 5123, "count": 3, "type": "SCALAR"}
         ],
         "bufferViews": [
          {"buffer": 0, "byteOffset": 0, "byteLength": $positionBytes},
          {"buffer": 0, "byteOffset": $positionBytes, "byteLength": $colorBytes},
          {"buffer": 0, "byteOffset": ${positionBytes + colorBytes}, "byteLength": $indexBytes}
         ],
         "buffers": [{"byteLength": ${buffer.capacity()},
                      "uri": "data:application/octet-stream;base64,$base64"}]
        }
        """.trimIndent()
    }

    private fun scene(dir: File): File =
        File(dir, "nodes.gltf").also { it.writeText(adversarialGltf()) }

    // ==========================================================================================
    // The four questions
    // ==========================================================================================

    @Test
    fun everyNodeInstanceIsMergedRatherThanTheFirstOneTaken() {
        withTempDir { dir ->
            val mesh = assimpToTriMesh(scene(dir).path)
            assertEquals(4, mesh.triangleCount, "all four node instances must arrive, not just the first")
            assertEquals(12, mesh.vertexCount, "3 vertices x 4 instances, unshared between instances")
            assertEquals(4, mesh.topology().componentCount, "and they are four separate pieces")
        }
    }

    @Test
    fun nodeTransformsAreAppliedAndTheParentChainIsComposed() {
        withTempDir { dir ->
            val mesh = assimpToTriMesh(scene(dir).path)
            val at = { x: Float, y: Float ->
                (0 until mesh.vertexCount).any { v ->
                    val p = mesh.position(v)
                    kotlin.math.abs(p.x - x) < 1e-4f && kotlin.math.abs(p.y - y) < 1e-4f
                }
            }
            assertTrue(at(0f, 0f) && at(1f, 0f), "the identity node sits at the origin")
            assertTrue(at(3f, 0f) && at(4f, 0f), "the translated node is moved by (3, 0, 0)")
            // The one that matters: `nested` is a child of `translated`, so its world transform is the
            // product. If the bridge used the local transform it would land at (0, 2); if it ignored
            // transforms it would land at (0, 0).
            assertTrue(at(3f, 2f) && at(4f, 2f), "the nested node is placed at parent x child = (3, 2, 0)")
        }
    }

    @Test
    fun aMirroredNodeIsReallyMirroredAndNotSilentlyFlattenedToATranslation() {
        withTempDir { dir ->
            val mesh = assimpToTriMesh(scene(dir).path)
            val xs = (0 until mesh.vertexCount).map { mesh.position(it).x }
            // The mirrored node is scale(-1,1,1) then translate(-3,0,0), so the long leg runs to x = -4.
            // Reading `bindPose` instead of `bindMatrix` put it at x = -2 -- a plausible, silently wrong
            // mesh. This is the assertion that catches a regression to that.
            assertTrue(
                xs.any { kotlin.math.abs(it - -4f) < 1e-4f },
                "the mirrored instance's far vertex must be at x = -4; got xs = ${xs.sorted()}",
            )
            assertTrue(
                xs.none { kotlin.math.abs(it - -2f) < 1e-4f },
                "x = -2 means the mirror was flattened to a pure translation; got xs = ${xs.sorted()}",
            )
            val b = mesh.bounds()
            assertEquals(-4f, b.min.x, 1e-4f)
            assertEquals(4f, b.max.x, 1e-4f)
            assertEquals(8f, b.max.x - b.min.x, 1e-4f, "the scene is 8 wide; 7 means the mirror was lost")
        }
    }

    @Test
    fun mirroringReversesWindingAndTheBridgeCompensatesSoNothingIsCulled() {
        withTempDir { dir ->
            val mesh = assimpToTriMesh(scene(dir).path)
            // Every triangle faces +z, including the mirrored one. A mirror reverses winding, so without
            // the compensating corner swap that triangle's face normal would be -z: it would be culled
            // and simply missing from the render, since these are open single-triangle pieces.
            for (t in 0 until mesh.triangleCount) {
                assertEquals(
                    1.0f, mesh.faceNormal(t).z, 1e-4f,
                    "triangle $t should face +z; ${mesh.faceNormal(t)}",
                )
            }
            for (v in 0 until mesh.vertexCount) {
                assertEquals(1.0f, mesh.normal(v)!!.z, 1e-4f, "vertex $v normal should face +z")
            }
            // And the proof that matters for a person: with culling on, all four are painted.
            val raster = mesh.renderPixels(
                MeshView(width = 240, height = 180, yawDegrees = 0.0, pitchDegrees = 0.0, cullBackFaces = true),
            )
            assertEquals(0, raster.culled, "nothing should be culled: ${raster.drawn} drawn, ${raster.culled} culled")
            assertEquals(4, raster.drawn)
        }
    }

    @Test
    fun perVertexColoursSurviveEveryInstance() {
        withTempDir { dir ->
            val mesh = assimpToTriMesh(scene(dir).path)
            assertTrue(mesh.hasColors, "COLOR_0 must come across")
            // Each instance carries the same red/green/blue corner triple.
            val reds = (0 until mesh.vertexCount).count { mesh.color(it)!!.x > 0.9f }
            assertEquals(4, reds, "one red corner per instance")
            assertTrue(!mesh.hasUvs, "this scene has no TEXCOORD_0, so no invented uvs")
        }
    }

    // ==========================================================================================
    // The upstream defect, stated as a test so the page's paragraph cannot go stale
    // ==========================================================================================

    @Test
    fun bindPoseStillCannotRepresentAMirrorWhichIsWhyBindMatrixExists() {
        withTempDir { dir ->
            val model = GltfSupport.loadGltfBasic(
                scene(dir).path, GltfSupport.GltfLoadConfig(loadTextures = false),
            )
            try {
                val mirrored = model.meshes.single { it.name == "mirrored" }
                // The lossy summary: a positive unit scale and an identity-ish rotation.
                assertEquals(1f, mirrored.bindPose.s.x, 1e-4f, "getScale is sqrt(...), so never negative")
                assertTrue(
                    mirrored.bindPose.toMatrix().determinant() > 0f,
                    "the recomposed TRS has lost the reflection",
                )
                // The truth, kept alongside it.
                val real = mirrored.bindMatrix
                assertTrue(real != null, "the loader must record the un-decomposed world matrix")
                assertTrue(
                    real.determinant() < 0f,
                    "bindMatrix must still be a reflection; det = ${real.determinant()}",
                )
                // Non-mirrored nodes agree between the two, so nothing else changed.
                for (m in model.meshes.filter { it.name != "mirrored" }) {
                    assertEquals(
                        m.bindPose.toMatrix().determinant(), m.bindMatrix!!.determinant(), 1e-4f,
                        "node '${m.name}' should be unaffected",
                    )
                }
            } finally {
                for (m in model.meshes) {
                    org.lwjgl.system.MemoryUtil.memFree(m.buffers.vertexData)
                    org.lwjgl.system.MemoryUtil.memFree(m.buffers.indexData)
                    m.buffers.tangentData?.let { org.lwjgl.system.MemoryUtil.memFree(it) }
                }
            }
        }
    }

    @Test
    fun toyCarsOwnNodesAreNotIdentityWhichIsWhyItsRenderIsEvidence() {
        val car = File("public/docs/assets/fabricate_demo/ToyCar.glb").takeIf { it.isFile } ?: return
        val model = GltfSupport.loadGltfBasic(car.path, GltfSupport.GltfLoadConfig(loadTextures = false))
        try {
            // Worth pinning, because it is what makes the page's ToyCar figure evidence that the
            // transform path works rather than a file that happens not to exercise it: all three of its
            // nodes carry a uniform 1e-4 scale. A bridge that dropped node transforms would render it
            // 10,000x too large, i.e. 730 m across instead of 0.073 m.
            for (m in model.meshes) {
                assertEquals(1e-4f, m.bindPose.s.x, 1e-6f, "node '${m.name}' should carry the 1e-4 scale")
            }
        } finally {
            for (m in model.meshes) {
                org.lwjgl.system.MemoryUtil.memFree(m.buffers.vertexData)
                org.lwjgl.system.MemoryUtil.memFree(m.buffers.indexData)
                m.buffers.tangentData?.let { org.lwjgl.system.MemoryUtil.memFree(it) }
            }
        }
        val mesh = assimpToTriMesh(car.path)
        val b = mesh.bounds()
        assertTrue(
            (b.max.x - b.min.x) < 0.1f,
            "ToyCar must arrive at metre scale (0.073 m), not 10,000x that; got ${b.max.x - b.min.x}",
        )
    }
}
