package afield.geometry.mesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CPU wireframe leaves out edges that only triangulate a flat face, and keeps every other edge.
 *
 * Each case compares the same mesh rendered with and without the wireframe, so the pixels that differ
 * are exactly the pixels the wireframe drew.
 */
class WireframeFlatDiagonalsTest {

    private val view = MeshView(width = 200, height = 200, yawDegrees = 0.0, pitchDegrees = 0.0)

    private fun wirePixels(mesh: TriMesh): Set<Int> {
        val with = mesh.renderPixels(view).pixels
        val without = mesh.renderPixels(view.copy(wireframe = false)).pixels
        return with.indices.filter { with[it] != without[it] }.toSet()
    }

    // A unit square facing the camera, split along one diagonal, and the same square as a fan of four
    // triangles about its centre. Both are one flat face.
    private val diagonal = TriMesh(
        floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, 1f, 1f, 0f, -1f, 1f, 0f),
        intArrayOf(0, 1, 2, 0, 2, 3),
    )
    private val fan = TriMesh(
        floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, 1f, 1f, 0f, -1f, 1f, 0f, 0f, 0f, 0f),
        intArrayOf(0, 1, 4, 1, 2, 4, 2, 3, 4, 3, 0, 4),
    )

    @Test
    fun `a flat face draws only its outline, however it is triangulated`() {
        val a = wirePixels(diagonal)
        val b = wirePixels(fan)
        assertTrue(a.isNotEmpty(), "the outline is still drawn")
        assertEquals(a, b, "a diagonal and a fan of the same flat square draw the same wireframe")
    }

    @Test
    fun `an edge between faces that are not coplanar is still drawn`() {
        // The same square with its centre lifted towards the camera: four faces, four real creases.
        val raised = TriMesh(fan.positions.copyOf().also { it[14] = 0.3f }, fan.indices.copyOf())
        val flat = wirePixels(fan)
        val creased = wirePixels(raised)
        assertTrue(creased.size > flat.size + 100, "the four creases add lines: ${flat.size} vs ${creased.size} pixels")
    }
}
