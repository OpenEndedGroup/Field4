package afield.graphics.three

import afield.app.server.SessionInformation
import afield.document.graph.Box
import afield.geometry.mesh.TriMesh
import afield.geometry.mesh.icosphereMesh
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A mesh placed by a transform (F2-3): the same mesh moved each redraw costs a transform and nothing else. */
class ThreePlacedMeshTest {
    private fun document(): Box {
        val box = Box("placed")
        val info = SessionInformation(Files.createTempDirectory("three-placed-").toString(), documentName = "placed", root = box, ephemeral = true)
        box.inside = info; info.boxes[box.id] = box
        return box
    }

    private val ball = icosphereMesh(2, 0.5)
    private fun parse(message: ByteArray): JsonObject = Json.parseToJsonElement(message.decodeToString()).jsonObject
    private fun drawables(d: JsonObject) = d["drawables"]!!.jsonArray.map { it.jsonObject }
    private fun hashes(d: JsonObject) = d["buffers"]!!.jsonObject["entries"]!!.jsonArray.map { it.jsonObject["hash"]!!.jsonPrimitive.content }

    @Test
    fun theSameMeshMovedByATransformSendsTheSameArraysAndTheTransform() {
        val box = document()
        fun draw(x: Double) = box.three(name = "placed") {
            mesh(ball, id = "carried", wireframe = false, transform = translation(x, 0.0, 0.0))
            mesh(ball, id = "still", wireframe = false)
        }
        val view = draw(0.0)
        val first = parse(view.replayMessages().last())
        draw(5.0)
        val second = parse(view.replayMessages().last())
        assertEquals(hashes(first), hashes(second), "no new arrays: the viewer already holds the mesh")
        assertEquals(0, view.lastUpdate!!.rigidMoves, "a placed part is not compared vertex by vertex")
        val t = drawables(second)[0]["transform"]!!.jsonArray.map { it.jsonPrimitive.float }
        assertEquals(5f, t[12])
        assertEquals(listOf(1f, 0f, 0f, 0f), t.take(4))
        // The scene's bounds follow the part where it is placed.
        val bounds = second["bounds"]!!.jsonObject
        assertTrue(bounds["max"]!!.jsonArray[0].jsonPrimitive.float > 5.4f, "bounds: $bounds")
    }

    @Test
    fun aMeshHandedOverAgainIsPreparedOnceAndAnEqualOneIsFoundByContent() {
        val a = ball.toThreeDrawable().geometry
        assertSame(a, ball.toThreeDrawable().geometry, "the same mesh, the same prepared arrays")
        val copy = TriMesh(ball.positions.copyOf(), ball.indices.copyOf(), ball.normals?.copyOf())
        assertSame(a, copy.toThreeDrawable().geometry, "an equal mesh built afresh is still found by its content")
        val moved = TriMesh(ball.positions.copyOf().also { it[0] += 1f }, ball.indices, ball.normals)
        assertTrue(moved.toThreeDrawable().geometry !== a, "a different mesh is prepared anew")
    }

    @Test
    fun aTransformMustBeSixteenFiniteFloats() {
        assertFailsWith<IllegalArgumentException> { document().three(name = "bad") { mesh(ball, transform = FloatArray(12)) } }
        assertFailsWith<IllegalArgumentException> {
            document().three(name = "nan") { mesh(ball, transform = translation(Double.NaN, 0.0)) }
        }
    }
}
