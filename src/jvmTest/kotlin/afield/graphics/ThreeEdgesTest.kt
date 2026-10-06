package afield.graphics

import afield.stdlib.TriMesh
import afield.stdlib.Vec4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [outlineEdges] and `mesh(edges = true)`: a flat-shaded solid's outline, without the diagonals that
 * triangulate its faces, moving with its explode offsets.
 */
class ThreeEdgesTest {
    private val cubePositions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f, 1f, 1f, 0f, 1f, 1f)
    private val cubeIndices = intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 2, 3, 7, 2, 7, 6, 1, 2, 6, 1, 6, 5, 0, 4, 7, 0, 7, 3)
    private fun cube(dx: Float = 0f) = TriMesh(FloatArray(24) { cubePositions[it] + if (it % 3 == 0) dx else 0f }, cubeIndices)

    /** The same cube with every triangle's corners its own vertices, as an STL or a merged soup arrives. */
    private fun soup(): TriMesh {
        val p = FloatArray(cubeIndices.size * 3) { cubePositions[cubeIndices[it / 3] * 3 + it % 3] }
        return TriMesh(p, IntArray(cubeIndices.size) { it })
    }

    private fun length(m: TriMesh, e: Pair<Int, Int>) = (0..2).sumOf { val d = (m.positions[e.first * 3 + it] - m.positions[e.second * 3 + it]).toDouble(); d * d }

    @Test
    fun `a cube's outline is its twelve edges and none of its face diagonals`() {
        val m = cube()
        val edges = m.outlineEdges()
        assertEquals(12, edges.size)
        edges.forEach { assertEquals(1.0, length(m, it), 1e-12, "edge $it is a cube edge, not a diagonal") }
    }

    @Test
    fun `a triangle soup gets the indexed mesh's outline`() {
        val m = soup()
        val edges = m.outlineEdges()
        assertEquals(12, edges.size)
        edges.forEach { assertEquals(1.0, length(m, it), 1e-12) }
    }

    @Test
    fun `a flat quad keeps its boundary and drops its diagonal`() {
        val quad = TriMesh(floatArrayOf(0f, 0f, 0f, 2f, 0f, 0f, 2f, 1f, 0f, 0f, 1f, 0f), intArrayOf(0, 1, 2, 0, 2, 3))
        assertEquals(4, quad.outlineEdges().size)
    }

    @Test
    fun `two touching parts with their own explode offsets keep their own outlines`() {
        // Two cubes side by side in one mesh, the second shifted +1 in x so they share the face at x = 1.
        val a = cube(); val b = cube(1f)
        val m = TriMesh(a.positions + b.positions, a.indices + b.indices.map { it + 8 }.toIntArray())
        val explode = FloatArray(48) { if (it >= 24 && it % 3 == 0) 1f else 0f }
        assertEquals(24, m.outlineEdges(explode = explode).size)
    }

    @Test
    fun `mesh with edges adds an outline that carries the explode offsets of its ends`() {
        val m = cube()
        val explode = FloatArray(24) { if (it % 3 == 1) 2f else 0f }
        val builder = ThreeSceneBuilder(48)
        builder.mesh(m, wireframe = false, panel = 3, explode = explode, edges = true, edgeColor = Vec4(.5f, .25f, 0f, 1f))
        val payload = builder.payload("t")
        assertEquals(2, payload.drawables.size)
        val lines = payload.drawables[1]
        assertEquals("lines", lines.style.kind)
        assertEquals(3, lines.panel)
        val position = lines.geometry.attributes.getValue("position").array
        assertEquals(12 * 2 * 3, position.size, "two ends for each of the twelve edges")
        val offsets = assertNotNull(lines.geometry.attributes["explode"]).array
        assertEquals(position.size, offsets.size)
        for (k in offsets.indices) assertEquals(if (k % 3 == 1) 2f else 0f, offsets[k])
        val colour = lines.geometry.attributes.getValue("color").array
        assertTrue(colour.toList().chunked(3).all { it == listOf(.5f, .25f, 0f) })
    }

    @Test
    fun `a mesh without edges draws no outline`() {
        val builder = ThreeSceneBuilder(48)
        builder.mesh(cube())
        assertEquals(1, builder.payload("t").drawables.size)
        assertNull(builder.payload("t").drawables.single().geometry.attributes["explode"])
    }
}
