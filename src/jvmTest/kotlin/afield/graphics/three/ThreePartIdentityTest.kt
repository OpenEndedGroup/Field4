package afield.graphics.three

import afield.geometry.mesh.TriMesh
import afield.math.linalg.Vec4
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Part identity (F3): a part's id, layer path and metadata reach the viewer, and its styles travel beside it. */
class ThreePartIdentityTest {
    private val triangle get() = TriMesh(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), intArrayOf(0, 1, 2))
    private val json = Json { encodeDefaults = true }

    private fun scene(block: ThreeSceneBuilder.() -> Unit) = ThreeSceneBuilder(12).apply(block).payload("u")

    @Test
    fun layerMetadataAndHiddenTravelWithEveryDrawableOfThePart() {
        val payload = scene {
            mesh(triangle, id = "door", edges = true, layer = listOf("Ground", "Doors"),
                metadata = mapOf("pivot" to listOf(1.0, 2.0, 0.0), "kind" to "swing", "leaves" to 2), hidden = true)
            metadata("datum", 3.5)
        }
        assertEquals(2, payload.drawables.size, "the mesh and its outline")
        for (d in payload.drawables) {
            assertEquals("door", d.id)
            assertEquals(listOf("Ground", "Doors"), d.layer)
            assertTrue(d.hidden)
            assertEquals("swing", d.metadata!!["kind"]!!.jsonPrimitive.content)
            assertEquals(3, d.metadata["pivot"]!!.jsonArray.size)
        }
        assertEquals(3.5, payload.metadata!!["datum"]!!.jsonPrimitive.content.toDouble())
        val wire = json.parseToJsonElement(json.encodeToString(ThreeGeometryPayload.serializer(), payload)).jsonObject
        assertEquals("Doors", wire["drawables"]!!.jsonArray[0].jsonObject["layer"]!!.jsonArray[1].jsonPrimitive.content)
    }

    @Test
    fun aPlainMeshEncodesAsItDidBefore() {
        val text = json.encodeToString(ThreeGeometryPayload.serializer(), scene { mesh(triangle, id = "t") })
        for (key in listOf("\"layer\"", "\"metadata\"", "\"hidden\"", "\"partStyles\"")) assertFalse(key in text, "$key in a plain payload")
    }

    @Test
    fun presentationLeavesTheReviewRevisionAlone() {
        val plain = scene { mesh(triangle, id = "a"); mesh(triangle.translated(2, 0, 0), id = "b") }
        val dressed = scene {
            mesh(triangle, id = "a", layer = listOf("L"), metadata = mapOf("x" to 1), hidden = true, style = ThreePartStyle.highlight())
            mesh(triangle.translated(2, 0, 0), id = "b", style = ThreePartStyle.muted())
            panel(styles = mapOf("a" to ThreePartStyle.muted()))
            metadata("frame", 7)
        }
        assertEquals(threeReviewRevision(plain), threeReviewRevision(dressed))
    }

    @Test
    fun panelsCarryTheirOwnStyles() {
        val payload = scene {
            mesh(triangle, id = "a", panel = -1); mesh(triangle, id = "b", panel = -1)
            style("b", ThreePartStyle.muted())
            columns(2)
            panel(title = "a", styles = mapOf("a" to ThreePartStyle.highlight()))
            panel(title = "b", styles = mapOf("b" to ThreePartStyle.highlight(Vec4(0f, 0f, 1f, 1f)), "a" to ThreePartStyle.muted()))
        }
        assertEquals(ThreeEmphasis.MUTED, payload.partStyles.getValue("b").emphasis)
        assertEquals(ThreeEmphasis.HIGHLIGHT, payload.panels[0].partStyles.getValue("a").emphasis)
        assertEquals(listOf(0.0, 0.0, 1.0), payload.panels[1].partStyles.getValue("b").color)
        assertNull(payload.panels[0].partStyles["b"], "a panel without its own style falls back to the scene's in the viewer")
    }

    @Test
    fun badIdentityIsRefusedWhereItIsWritten() {
        assertFailsWith<IllegalArgumentException> { scene { mesh(triangle, id = "a"); style("ghost", ThreePartStyle.muted()) } }
        assertFailsWith<IllegalArgumentException> { scene { mesh(triangle, id = "a"); panel(styles = mapOf("ghost" to ThreePartStyle.muted())) } }
        assertFailsWith<IllegalArgumentException> { scene { mesh(triangle, metadata = mapOf("x" to 1)) } }
        assertFailsWith<IllegalArgumentException> { scene { mesh(triangle, id = "a", metadata = mapOf("x" to Double.NaN)) } }
        assertFailsWith<IllegalArgumentException> { scene { mesh(triangle, id = "a", metadata = mapOf("x" to Any())) } }
        assertFailsWith<IllegalArgumentException> { ThreePartStyle(color = listOf(2.0, 0.0, 0.0)) }
    }
}
