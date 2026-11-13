package afield.graphics.three

import afield.geometry.kinematics.ClearanceBody
import afield.geometry.kinematics.RigidMotion
import afield.geometry.kinematics.sweptClearance
import afield.geometry.mesh.TriMesh
import afield.math.linalg.Vector3d
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Declared motions (F4-2) as the viewer receives them. */
class ThreeMotionTest {
    private val leaf = TriMesh(floatArrayOf(0f, 0f, 0f, 900f, 0f, 0f, 900f, 0f, 2000f), intArrayOf(0, 1, 2))
    private val swing = RigidMotion.rotation(Vector3d(0.0, 0.0, 0.0), Vector3d(0.0, 0.0, 1.0), 0.0, 90.0)
    private fun scene(block: ThreeSceneBuilder.() -> Unit) = ThreeSceneBuilder(12).apply(block).payload("u")

    @Test
    fun aMotionTravelsAsTheRigidMotionsOwnJson() {
        val payload = scene {
            mesh(leaf, id = "door"); mesh(leaf, id = "door#glass", opacity = 0.3)
            motion("door", "door#glass", motion = swing, at = 30.0, label = "door")
            timeline(periodSeconds = 5.0, playLabel = "Swing")
        }
        val m = payload.motions.single()
        assertEquals(listOf("door", "door#glass"), m.parts)
        assertEquals(swing, RigidMotion.fromJson(m.motion.toString()))
        assertEquals(30.0, m.at)
        assertEquals("Swing", payload.timeline!!.playLabel)
        val wire = Json.parseToJsonElement(Json.encodeToString(ThreeGeometryPayload.serializer(), payload)).jsonObject
        assertEquals("rotation", wire["motions"]!!.jsonArray[0].jsonObject["motion"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(threeReviewRevision(scene { mesh(leaf, id = "door"); mesh(leaf, id = "door#glass", opacity = 0.3) }),
            threeReviewRevision(payload), "a motion is not geometry")
    }

    @Test
    fun aSweptClearanceBecomesATrack() {
        val part = ClearanceBody.box(Vector3d(0.0, 0.0, 0.0), Vector3d(1000.0, 20.0, 100.0), "slab")
        val post = ClearanceBody.box(Vector3d(700.0, 700.0, 0.0), Vector3d(800.0, 800.0, 100.0), "post")
        val c = sweptClearance(part, swing, listOf(post), maxStep = 5.0)
        val track = ThreeMotionTrack.of(c, listOf(ThreeTintBand.under(150.0, "under 150")), unit = "mm")
        assertEquals(c.steps.map { it.value }, track.values)
        assertEquals(c.gaps().map { if (it.isFinite()) it else 1e9 }, track.samples)
        assertTrue(track.labels.all { it == "post" || it.isEmpty() })
    }

    @Test
    fun motionsAreCheckedWhereTheyAreWritten() {
        assertFailsWith<IllegalArgumentException> { scene { mesh(leaf, id = "a"); motion("ghost", motion = swing) } }
        assertFailsWith<IllegalArgumentException> { scene { mesh(leaf, id = "a"); motion("a", motion = swing); motion("a", motion = swing) } }
        assertFailsWith<IllegalArgumentException> { ThreeMotionTrack(listOf(0.0, 1.0), listOf(1.0)) }
        assertFailsWith<IllegalArgumentException> { ThreeMotionTrack(listOf(0.0), listOf(Double.POSITIVE_INFINITY)) }
        assertFailsWith<IllegalArgumentException> { ThreeTimeline(periodSeconds = 0.0) }
    }
}
