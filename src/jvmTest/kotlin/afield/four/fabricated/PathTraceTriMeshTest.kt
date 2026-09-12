package afield.four.fabricated

import afield.stdlib.TriMesh
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [importTriMesh], on a mesh small enough to check triangle by triangle.
 *
 * Every producer this bridge exists for wants a device or a native library: the polytope path is
 * cheap but the Manifold and cloth paths are not, and the tracer itself needs Vulkan. A hand-written
 * cube needs none of them, and it is enough to pin the four behaviours that separate this call from
 * [PathTraceScene.mesh] — the degenerate skip, the winding reversal under a mirroring transform, the
 * zero-normal fallback, and the per-triangle material selector.
 *
 * The counts are exact rather than floored. A cube is twelve triangles, so a test that accepted "at
 * least ten" would pass while the bridge silently dropped two of them.
 */
class PathTraceTriMeshTest {

    private companion object {
        /** A unit cube centred on the origin, outward-wound, no vertex normals. */
        fun cube(): TriMesh = TriMesh(
            positions = floatArrayOf(
                -1f, -1f, -1f, 1f, -1f, -1f, 1f, 1f, -1f, -1f, 1f, -1f,
                -1f, -1f, 1f, 1f, -1f, 1f, 1f, 1f, 1f, -1f, 1f, 1f,
            ),
            indices = intArrayOf(
                0, 3, 2, 0, 2, 1, // -z
                4, 5, 6, 4, 6, 7, // +z
                0, 1, 5, 0, 5, 4, // -y
                3, 7, 6, 3, 6, 2, // +y
                0, 4, 7, 0, 7, 3, // -x
                1, 2, 6, 1, 6, 5, // +x
            ),
        )

        fun sceneWithOneMaterial(): Pair<PathTraceScene, Int> {
            val scene = PathTraceScene()
            return scene to scene.material(PathTraceMaterial.diffuse(0.8f, 0.8f, 0.8f))
        }

        /** The geometric normal of a scene triangle, from its own three corners. */
        fun faceNormal(t: PathTraceScene.Triangle): Vector3f =
            Vector3f(t.p[1]).sub(t.p[0]).cross(Vector3f(t.p[2]).sub(t.p[0])).normalize()
    }

    @Test
    fun `a clean cube arrives whole, with its bounds`() {
        val (scene, material) = sceneWithOneMaterial()
        val added = scene.importTriMesh(cube(), material, warning = { })

        assertEquals(12, added.triangleCount, "a cube is twelve triangles")
        assertEquals(0, added.degenerateCount, "nothing about a cube is degenerate")
        assertEquals(12, scene.triangles.size, "the scene holds what the report claims")
        assertTrue(added.warnings.isEmpty(), "warnings on a clean mesh: ${added.warnings}")
        assertEquals(-1f, added.boundsMin.x)
        assertEquals(1f, added.boundsMax.z)
        assertEquals(Vector3f(2f, 2f, 2f), added.size)
    }

    @Test
    fun `every face of an untransformed cube keeps its outward winding`() {
        val (scene, material) = sceneWithOneMaterial()
        scene.importTriMesh(cube(), material, warning = { })
        // The cube is centred on the origin, so outward means the face normal agrees with the
        // direction from the centre to the triangle's own centroid.
        scene.triangles.forEachIndexed { i, t ->
            val centroid = Vector3f(t.p[0]).add(t.p[1]).add(t.p[2]).mul(1f / 3f)
            assertTrue(faceNormal(t).dot(centroid) > 0f, "triangle $i is wound inward")
        }
    }

    @Test
    fun `a mirroring transform reverses the winding rather than turning the cube inside out`() {
        val (scene, material) = sceneWithOneMaterial()
        val mirror = Matrix4f().scale(-1f, 1f, 1f)
        assertTrue(mirror.determinant() < 0f, "the premise: this transform has a negative determinant")

        val added = scene.importTriMesh(cube(), material, mirror, warning = { })
        assertEquals(12, added.triangleCount)
        scene.triangles.forEachIndexed { i, t ->
            val centroid = Vector3f(t.p[0]).add(t.p[1]).add(t.p[2]).mul(1f / 3f)
            assertTrue(faceNormal(t).dot(centroid) > 0f, "mirrored triangle $i is wound inward")
        }
    }

    @Test
    fun `a zero-area triangle is skipped, counted, and named once`() {
        val (scene, material) = sceneWithOneMaterial()
        val source = cube()
        // One extra triangle whose three corners are the same vertex. Nothing else changes, so the
        // twelve real ones are still expected to arrive.
        val withSliver = TriMesh(
            positions = source.positions,
            indices = source.indices + intArrayOf(0, 0, 0),
        )

        val added = scene.importTriMesh(withSliver, material, warning = { })
        assertEquals(12, added.triangleCount, "the twelve real triangles still arrive")
        assertEquals(1, added.degenerateCount)
        assertEquals(12, scene.triangles.size)
        assertEquals(
            listOf("Zero-area triangles are omitted."),
            added.warnings,
            "one line per reason, however many triangles share it",
        )
    }

    @Test
    fun `a non-finite coordinate is skipped separately from a sliver`() {
        val (scene, material) = sceneWithOneMaterial()
        val source = cube()
        val broken = source.positions.copyOf().also { it[0] = Float.NaN }
        val added = scene.importTriMesh(
            TriMesh(positions = broken, indices = source.indices),
            material,
            warning = { },
        )
        // Vertex 0 is a corner of six of the twelve triangles.
        assertEquals(6, added.triangleCount)
        assertEquals(6, added.degenerateCount)
        assertEquals(
            listOf("Triangles with a non-finite coordinate are omitted."),
            added.warnings,
        )
    }

    @Test
    fun `a zero-length vertex normal falls back to the face normal`() {
        val (scene, material) = sceneWithOneMaterial()
        val source = cube()
        val added = scene.importTriMesh(
            TriMesh(positions = source.positions, indices = source.indices, normals = FloatArray(24)),
            material,
            warning = { },
        )
        assertEquals(12, added.triangleCount, "a zero normal is not a reason to drop a triangle")
        assertEquals(
            listOf("Zero-length vertex normals fall back to the face normal."),
            added.warnings,
        )
        scene.triangles.forEachIndexed { i, t ->
            val face = faceNormal(t)
            t.n.forEachIndexed { corner, n ->
                assertTrue(
                    n.dot(face) > 0.999f,
                    "triangle $i corner $corner kept a normal of $n against a face normal of $face",
                )
            }
        }
    }

    @Test
    fun `the per-triangle selector sees the source index, including the ones it goes on to skip`() {
        val scene = PathTraceScene()
        val even = scene.material(PathTraceMaterial.diffuse(1f, 0f, 0f))
        val odd = scene.material(PathTraceMaterial.diffuse(0f, 0f, 1f))
        val source = cube()
        // A sliver in the middle rather than at the end, so a selector indexed off the appended
        // count instead of the source count would disagree from triangle six onwards.
        val head = source.indices.copyOfRange(0, 18)
        val tail = source.indices.copyOfRange(18, source.indices.size)
        val withSliver = TriMesh(
            positions = source.positions,
            indices = head + intArrayOf(0, 0, 0) + tail,
        )

        val seen = mutableListOf<Int>()
        val added = scene.importTriMesh(
            withSliver,
            { t -> seen += t; if (t % 2 == 0) even else odd },
            warning = { },
        )
        // The skipped triangle is not asked about, and the numbering does not close up over it:
        // source triangle 7 is still called 7. A selector keyed off the appended count would see
        // 6 here and every material from this point on would be wrong.
        assertEquals(((0 until 6) + (7 until 13)).toList(), seen)
        assertEquals(12, added.triangleCount)
        assertEquals(1, added.degenerateCount)
        // Source triangle 6 is the sliver, so what reached the scene is 0..5 then 7..12.
        val expected = ((0 until 6) + (7 until 13)).map { if (it % 2 == 0) even else odd }
        assertEquals(expected, scene.triangles.map { it.material })
    }

    @Test
    fun `a material the scene does not have is refused rather than defaulted`() {
        val (scene, material) = sceneWithOneMaterial()
        assertFailsWith<IllegalArgumentException> {
            scene.importTriMesh(cube(), material + 1, warning = { })
        }
        assertFailsWith<IllegalArgumentException> {
            scene.importTriMesh(cube(), { -1 }, warning = { })
        }
        assertTrue(scene.triangles.isEmpty(), "a refused import leaves nothing behind")
    }

    @Test
    fun `a singular transform is refused before any triangle is read`() {
        val (scene, material) = sceneWithOneMaterial()
        assertFailsWith<IllegalArgumentException> {
            scene.importTriMesh(cube(), material, Matrix4f().scale(1f, 0f, 1f), warning = { })
        }
        assertTrue(scene.triangles.isEmpty())
    }

    @Test
    fun `vertexColors asked for on a mesh that has none says so and carries on`() {
        val (scene, material) = sceneWithOneMaterial()
        val added = scene.importTriMesh(cube(), material, vertexColors = true, warning = { })
        assertEquals(12, added.triangleCount)
        assertEquals(
            listOf("vertexColors was asked for on a mesh that carries none."),
            added.warnings,
        )
    }

    @Test
    fun `vertex colours reach the scene one per corner`() {
        val (scene, material) = sceneWithOneMaterial()
        val source = cube()
        val colors = FloatArray(24) { it / 24f }
        val added = scene.importTriMesh(
            TriMesh(positions = source.positions, indices = source.indices, colors = colors),
            material,
            vertexColors = true,
            warning = { },
        )
        assertEquals(12, added.triangleCount)
        assertTrue(added.warnings.isEmpty())
        scene.triangles.forEachIndexed { i, t ->
            val corner = t.color
            assertTrue(corner != null && corner.size == 3, "triangle $i has no three colours")
            corner.forEach { assertEquals(1f, it.w, "alpha comes from the bridge, not the mesh") }
        }
        // The first triangle is 0, 3, 2, so its first corner is vertex 0's colour.
        assertEquals(0f, scene.triangles[0].color!![0].x)
        assertEquals(colors[9], scene.triangles[0].color!![1].x, "the second corner is vertex 3")
    }
}
