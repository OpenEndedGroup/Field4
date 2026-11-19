package afield.graphics.three

import afield.geometry.mesh.TriMesh
import afield.geometry.path.FLine
import afield.graphics.render.FLineIntersection
import afield.graphics.render.color
import afield.graphics.render.filled
import afield.graphics.render.fline
import afield.graphics.render.onClick
import afield.math.linalg.vec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `three { lines(…, id = …) }`: a group of FLines as a named part, with a layer, a style and the HUD's counts. */
class ThreeLinePartsTest {

    private fun edge(x: Double, r: Double): FLine = fline {
        moveTo(x, 0.0, 0.0)
        lineTo(x, 1.0, 0.0)
        color = vec(r, 0.2, 0.2, 1.0)
    }

    @Test
    fun `a named group of lines becomes a lines drawable carrying its id, layer and colours`() {
        val builder = ThreeSceneBuilder(8)
        builder.lines([edge(0.0, 0.9), edge(1.0, 0.1)], id = "uprights", label = "Uprights", layer = ["Edges"])
        val payload = builder.payload("t")

        assertNull(payload.lines, "named lines leave the unnamed FLine batch empty")
        val part = payload.drawables.single()
        assertEquals("lines", part.style.kind)
        assertEquals("uprights", part.id)
        assertEquals("Uprights", part.label)
        assertEquals(["Edges"], part.layer)
        assertEquals(2 * 2 * 3, part.geometry.attributes.getValue("position").array.size, "two segments, two ends each")
        val colors = part.geometry.attributes.getValue("color").array
        assertEquals(0.9f, colors[0], 1e-6f)
        assertEquals(0.1f, colors[6], 1e-6f)
        assertEquals(2, payload.meta!!.lineSegments)
    }

    @Test
    fun `named and unnamed lines sit side by side and a style may name the group`() {
        val builder = ThreeSceneBuilder(8)
        builder.lines([edge(0.0, 0.5)])
        builder.lines([edge(1.0, 0.5)], id = "red", style = ThreePartStyle(color = [1.0, 0.0, 0.0]))
        val payload = builder.payload("t")

        assertEquals(2, payload.meta!!.lineSegments, "one from each half")
        assertTrue(payload.partStyles.containsKey("red"))
        assertEquals("red", payload.drawables.single().id)
    }

    @Test
    fun `a filled line in a named group draws its fill under the same id`() {
        val square = fline {
            moveTo(0.0, 0.0, 0.0); lineTo(1.0, 0.0, 0.0); lineTo(1.0, 1.0, 0.0); lineTo(0.0, 1.0, 0.0); close()
            filled = true
        }
        val builder = ThreeSceneBuilder(8)
        builder.lines([square], id = "face", layer = ["Polygons"])
        builder.mesh(TriMesh(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), intArrayOf(0, 1, 2)), id = "tri")
        val kinds = builder.payload("t").drawables.filter { it.id == "face" }.map { it.style.kind }
        assertEquals(["surface", "lines"], kinds)
    }

    @Test
    fun `a clickable line is refused in a named group`() {
        val clickable = edge(0.0, 0.5).also { it.onClick = { _: FLineIntersection -> } }
        val error = assertFailsWith<IllegalArgumentException> { ThreeSceneBuilder(8).lines([clickable], id = "x") }
        assertTrue("without an id" in error.message!!)
    }
}
