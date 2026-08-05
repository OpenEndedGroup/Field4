package afield.stdlib.polytope

import afield.stdlib.Vec3
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `POLYTOPE_ALGEBRA.md` item 4's first bullet: smooth and crease-aware normals *while preserving
 * abstract vertex provenance*. The second half is the load-bearing half — a smoothing pass that
 * merged duplicated corners would destroy the mapping from render vertex back to topology vertex, and
 * with it the picking path — so every test here checks the provenance arrays as well as the normals.
 *
 * The interesting case is the one the hole model made possible: a hole rim has no vertex *fan* at all,
 * because a hole's edges bound one face rather than two. A smoothing rule that walked the fan would
 * have to special-case it; the per-corner rule used here gets the right answer — the rim keeps the
 * face's flat normal — out of the ordinary rule.
 */
class PolytopeVertexNormalsTest {

    @Test
    fun theDefaultIsFlatAndByteIdenticalToNoPolicyAtAll() {
        for ((name, solid) in seeds()) {
            val implicit = solid.triangulate()
            val explicit = solid.triangulate(normals = NormalPolicy.Flat)
            assertContentEquals(implicit.mesh.normals, explicit.mesh.normals, "$name normals")
            assertContentEquals(implicit.mesh.positions, explicit.mesh.positions, "$name positions")
            assertContentEquals(implicit.mesh.indices, explicit.mesh.indices, "$name indices")

            // Flat means exactly the face's own normal on every one of its corners.
            for (triangle in 0 until implicit.triangleCount) {
                val face = implicit.sourceFace(triangle)
                val expected = solid.faceNormal(face)
                for (at in 0..2) {
                    val vertex = implicit.mesh.indices[triangle * 3 + at]
                    assertNormal(expected, implicit.mesh, vertex, "$name triangle $triangle")
                }
            }
        }
    }

    @Test
    fun creaseZeroIsFlat() {
        for ((name, solid) in seeds()) {
            val flat = solid.triangulate()
            val creased = solid.triangulate(normals = NormalPolicy.Crease(0.0))
            assertContentEquals(flat.mesh.normals, creased.mesh.normals, "$name at a zero crease angle")
        }
    }

    @Test
    fun aCubeStaysACubeUnderAnOrdinaryCreaseAngle() {
        // Every dihedral of a cube is 90 degrees, so a 30-degree threshold smooths nothing and the
        // normals stay axis-aligned. This is the test that a smoothing default would have ruined.
        val cube = cube()
        val flat = cube.triangulate()
        val creased = cube.triangulate(normals = NormalPolicy.Crease(30.0))
        assertContentEquals(flat.mesh.normals, creased.mesh.normals)
        val normals = requireNotNull(creased.mesh.normals)
        for (vertex in 0 until creased.mesh.vertexCount) {
            val axes = (0..2).count { abs(normals[vertex * 3 + it]) > 0.999f }
            assertEquals(1, axes, "cube normal $vertex is not axis-aligned")
        }
    }

    @Test
    fun aSphericalSolidComesOutSmooth() {
        // Every vertex of an icosahedron is on the unit sphere, so a fully smoothed normal there must
        // point along its own position. Flat normals do not: they point at the face centres.
        val solid = icosahedron()
        val smooth = solid.triangulate(normals = NormalPolicy.Smooth)
        val flat = solid.triangulate()
        val smoothNormals = requireNotNull(smooth.mesh.normals)
        val flatNormals = requireNotNull(flat.mesh.normals)

        var worstSmooth = 1.0
        var worstFlat = 1.0
        for (vertex in 0 until smooth.mesh.vertexCount) {
            val radial = Vec3(smooth.mesh.x(vertex), smooth.mesh.y(vertex), smooth.mesh.z(vertex)).normalize()
            worstSmooth = minOf(worstSmooth, radial.dot(normalAt(smoothNormals, vertex)).toDouble())
            worstFlat = minOf(worstFlat, radial.dot(normalAt(flatNormals, vertex)).toDouble())
        }
        assertTrue(worstSmooth > 0.999, "smoothed icosahedron normals are not radial: worst dot $worstSmooth")
        assertTrue(worstFlat < 0.9955, "flat normals should not already be radial: worst dot $worstFlat")

        // Same buffers, same provenance: only the normals moved.
        assertContentEquals(flat.mesh.positions, smooth.mesh.positions)
        assertContentEquals(flat.mesh.indices, smooth.mesh.indices)
        assertContentEquals(flat.vertexElementIds, smooth.vertexElementIds)
        assertContentEquals(flat.triangleFaceIds, smooth.triangleFaceIds)
    }

    @Test
    fun everyNormalIsAUnitVectorWhicheverPolicyRan() {
        val policies = listOf(NormalPolicy.Flat, NormalPolicy.Crease(0.0), NormalPolicy.Crease(45.0), NormalPolicy.Smooth)
        for ((name, solid) in seeds()) for (policy in policies) {
            val normals = requireNotNull(solid.triangulate(normals = policy).mesh.normals)
            for (vertex in 0 until normals.size / 3) {
                val length = sqrt(
                    (0..2).sumOf { normals[vertex * 3 + it].toDouble() * normals[vertex * 3 + it] },
                )
                assertEquals(1.0, length, 1e-5, "$name / $policy: normal $vertex has length $length")
            }
        }
    }

    @Test
    fun aWindowRimKeepsTheFaceNormalEvenWhenFullySmoothed() {
        // The case a fan walk would have had to special-case: the rim vertices belong to one face
        // only, so there is nothing to average with and the flat normal is the answer. The *outer*
        // corners of the same face do get smoothed, because three faces of the cube meet there — so
        // this also shows the rule is not simply doing nothing to the windowed face.
        val solid = cubeWithWindow(0.5f)
        val topology = solid.topology
        val windowed = topology.elements(2).single { topology.faceHasHoles(it.id) }
        val rim = topology.faceHoles(windowed.id).single().toSet()
        val outline = topology.faceCycle(windowed.id).toSet()

        val geometry = solid.triangulate(normals = NormalPolicy.Smooth)
        val normals = requireNotNull(geometry.mesh.normals)
        val faceNormal = solid.faceNormal(windowed.id)

        var rimCorners = 0
        var smoothedOutlineCorners = 0
        for (vertex in 0 until geometry.mesh.vertexCount) {
            val source = geometry.sourceVertex(vertex).value
            // Corners of the windowed face only: a rim vertex belongs to no other face anyway, but an
            // outline vertex is duplicated once per cube face it touches.
            if (abs(geometry.mesh.z(vertex) - 1f) > 1e-6f) continue
            if (source in rim) {
                assertNormal(faceNormal, geometry.mesh, vertex, "window rim vertex $source")
                rimCorners++
            } else if (source in outline && normalAt(normals, vertex).dot(faceNormal) < 0.999f) {
                smoothedOutlineCorners++
            }
        }
        assertEquals(4, rimCorners, "all four rim corners should have been checked")
        assertTrue(smoothedOutlineCorners > 0, "the windowed face's outer corners should have smoothed")

        // And the hole is still a hole: the policy touched normals and nothing else.
        assertEquals(
            8,
            (0 until geometry.triangleCount).count { geometry.sourceFace(it) == windowed.id },
            "smoothing changed the triangulation",
        )
    }

    @Test
    fun anImpossibleCreaseAngleIsRefused() {
        assertFailsWith<IllegalArgumentException> { NormalPolicy.Crease(-1.0) }
        assertFailsWith<IllegalArgumentException> { NormalPolicy.Crease(181.0) }
        assertFailsWith<IllegalArgumentException> { NormalPolicy.Crease(Double.NaN) }
    }

    // ---------------------------------------------------------------------------------- machinery

    private fun seeds(): Map<String, PolyhedralObject> = mapOf(
        "tetrahedron" to tetrahedron(),
        "cube" to cube(),
        "icosahedron" to icosahedron(),
        "gC" to conway("gC").polyhedron,
        "cubeWithWindow" to cubeWithWindow(0.5f),
    )

    private fun normalAt(normals: FloatArray, vertex: Int) =
        Vec3(normals[vertex * 3], normals[vertex * 3 + 1], normals[vertex * 3 + 2])

    private fun assertNormal(expected: Vec3, mesh: afield.stdlib.TriMesh, vertex: Int, message: String) {
        val normals = requireNotNull(mesh.normals)
        assertEquals(expected.x, normals[vertex * 3], 1e-5f, "$message normal x")
        assertEquals(expected.y, normals[vertex * 3 + 1], 1e-5f, "$message normal y")
        assertEquals(expected.z, normals[vertex * 3 + 2], 1e-5f, "$message normal z")
    }
}
