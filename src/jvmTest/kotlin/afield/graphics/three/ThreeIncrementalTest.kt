package afield.graphics.three

import afield.app.server.SessionInformation
import afield.document.graph.Box
import afield.geometry.mesh.TriMesh
import afield.geometry.mesh.icosphereMesh
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Incremental updates (F2): what a redraw of a view sends when only some parts changed. */
class ThreeIncrementalTest {
    private fun document(): Box {
        val box = Box("incremental")
        val info = SessionInformation(Files.createTempDirectory("three-incremental-").toString(), documentName = "incremental", root = box, ephemeral = true)
        box.inside = info; info.boxes[box.id] = box
        return box
    }

    // Without normals, as a mesh from a solid arrives: the viewer path shades it, the same for every pose.
    private val ball = icosphereMesh(2, 0.5).let { TriMesh(it.positions, it.indices) }
    private fun turned(m: TriMesh, angle: Double, dx: Float, dz: Float): TriMesh {
        val p = m.positions.copyOf(); val c = cos(angle).toFloat(); val s = sin(angle).toFloat()
        for (k in p.indices step 3) { val x = p[k]; val y = p[k + 1]; p[k] = c * x - s * y + dx; p[k + 1] = s * x + c * y; p[k + 2] += dz }
        return TriMesh(p, m.indices)
    }
    private fun stretched(m: TriMesh, s: Float) = TriMesh(m.positions.copyOf().also { for (k in 2 until it.size step 3) it[k] *= s }, m.indices)

    private fun parse(message: ByteArray): JsonObject = Json.parseToJsonElement(message.decodeToString()).jsonObject
    private fun drawables(d: JsonObject) = d["drawables"]!!.jsonArray.map { it.jsonObject }
    private fun hashes(d: JsonObject) = d["buffers"]!!.jsonObject["entries"]!!.jsonArray.map { it.jsonObject["hash"]!!.jsonPrimitive.content }
    private fun hashesOf(d: JsonObject, drawable: JsonObject): List<String> {
        val table = hashes(d)
        val g = drawable["geometry"]!!.jsonObject
        return g["attributes"]!!.jsonObject.values.map { table[it.jsonObject["buffer"]!!.jsonPrimitive.content.toInt()] } +
            listOfNotNull(g["index"]?.jsonObject?.get("buffer")?.jsonPrimitive?.content?.toInt()?.let { table[it] })
    }

    @Test
    fun aRigidMoveIsFoundAndAReshapeIsNot() {
        val a = ball.toThreeDrawable(); val moved = turned(ball, 0.7, 3f, 1f).toThreeDrawable()
        val m = assertNotNull(ThreeRigidParts.rigidMove(a.geometry, moved.geometry))
        val p = a.geometry.attributes.getValue("position").array; val q = moved.geometry.attributes.getValue("position").array
        for (i in p.indices step 3) {
            val x = m[0] * p[i] + m[4] * p[i + 1] + m[8] * p[i + 2] + m[12]
            val y = m[1] * p[i] + m[5] * p[i + 1] + m[9] * p[i + 2] + m[13]
            val z = m[2] * p[i] + m[6] * p[i + 1] + m[10] * p[i + 2] + m[14]
            assertTrue(kotlin.math.abs(x - q[i]) + kotlin.math.abs(y - q[i + 1]) + kotlin.math.abs(z - q[i + 2]) < 1e-5, "vertex ${i / 3}")
        }
        assertNull(ThreeRigidParts.rigidMove(a.geometry, stretched(ball, 1.2f).toThreeDrawable().geometry), "a stretch is not rigid")
        assertSame(ThreeRigidParts.IDENTITY, ThreeRigidParts.rigidMove(a.geometry, ball.toThreeDrawable().geometry))
    }

    @Test
    fun aRedrawSendsMovedPartsAsTransformsOnTheGeometryTheViewerHas() {
        val box = document()
        fun draw(t: Double) = box.three(name = "drag") {
            mesh(turned(ball, t, t.toFloat() * 2, 0f), id = "moving", wireframe = false)
            mesh(stretched(ball, 1f + t.toFloat()).translated(3, 0, 0), id = "stretching", wireframe = false)
            mesh(ball.translated(-3, 0, 0), id = "still", wireframe = false)
        }
        val view = draw(0.0)
        val first = parse(view.replayMessages().last())
        draw(0.4)
        val second = parse(view.replayMessages().last())
        assertEquals(1, view.lastUpdate!!.rigidMoves)
        val (m1, s1, k1) = drawables(first); val (m2, s2, k2) = drawables(second)
        assertTrue(m2["transform"] is JsonArray, "the moved part carries a transform")
        assertEquals(hashesOf(first, m1), hashesOf(second, m2), "…and the arrays the viewer already holds")
        assertEquals(hashesOf(first, k1), hashesOf(second, k2), "an unchanged part sends the same arrays")
        assertNotEquals(hashesOf(first, s1), hashesOf(second, s2), "a reshaped part sends new ones")
        assertTrue(k2["transform"] == null || k2["transform"] is kotlinx.serialization.json.JsonNull)
        // The bounds still follow the moved part, and the revision changes, since the geometry did.
        assertNotEquals(first["revision"], second["revision"])
    }

    @Test
    fun aStyleOnlyRedrawSendsNoNewArrays() {
        val box = document()
        fun draw(red: Boolean) = box.three(name = "styles") {
            mesh(ball, id = "a", wireframe = false)
            mesh(ball.translated(2, 0, 0), id = "b", wireframe = false)
            style("a", if (red) ThreePartStyle.highlight() else ThreePartStyle.muted())
        }
        val view = draw(false)
        val first = parse(view.replayMessages().last())
        draw(true)
        val second = parse(view.replayMessages().last())
        assertEquals(hashes(first), hashes(second), "every array is one the viewer has")
        assertEquals(first["revision"], second["revision"], "a style is not geometry")
        assertEquals("HIGHLIGHT", second["partStyles"]!!.jsonObject["a"]!!.jsonObject["emphasis"]!!.jsonPrimitive.content)
    }

    @Test
    fun aNoteFiledUnderTheOldRevisionStaysCurrentOnTheSameGeometry() {
        val box = document()
        val payload = ThreeSceneBuilder(12).apply { mesh(ball, id = "a") }.payload(threeViewerUid(box, "legacy"))
        val legacy = threeLegacyReviewRevision(payload)
        val uid = threeViewerUid(box, "legacy")
        box.properties["geometryReviews"] = JsonArray(listOf(buildJsonObject {
            put("id", "n1"); put("viewId", uid); put("artifactDigest", legacy); put("text", "check this")
        })).toString()
        val view = box.three(name = "legacy") { mesh(ball, id = "a") }
        assertEquals(legacy, view.reviews()["revision"]!!.jsonPrimitive.content)
        assertEquals("false", view.reviews()["notes"]!!.jsonArray.single().jsonObject["stale"]!!.jsonPrimitive.content)
        // Without such a note a view files under the new revision.
        val fresh = document().three(name = "legacy") { mesh(ball, id = "a") }
        assertTrue(fresh.reviews()["revision"]!!.jsonPrimitive.content.startsWith("r2-"))
    }
}
