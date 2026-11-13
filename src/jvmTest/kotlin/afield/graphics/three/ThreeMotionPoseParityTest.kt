package afield.graphics.three

import afield.geometry.kinematics.RigidMotion
import afield.geometry.kinematics.transformPoint
import afield.math.linalg.Vector3d
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The viewer plays a declared motion with its own pose maths (`motion-pose` in `threejs_viewer.html`). This holds
 * it to [RigidMotion.poseAt], the definition it follows, on GraalJS, and checks the timeline and track lookups.
 */
class ThreeMotionPoseParityTest {
    private fun session() = ViewerJavaScript.Session.open("motion-pose", ViewerJavaScript.KNOWN_REGIONS.getValue("motion-pose"))
    private fun org.graalvm.polyglot.Value.doubles() = DoubleArray(arraySize.toInt()) { getArrayElement(it.toLong()).asDouble() }

    private val motions = listOf(
        RigidMotion.rotation(Vector3d(1200.0, -300.0, 0.0), Vector3d(0.0, 0.0, -1.0), 0.0, 95.0),
        RigidMotion.rotation(Vector3d(0.5, 2.0, 3.0), Vector3d(1.0, 2.0, -0.5), -20.0, 40.0),
        RigidMotion.slide(Vector3d(1.0, 1.0, 0.0), 0.0, 600.0),
    )

    @Test
    fun posesMatchTheKotlinDefinition() = session().use { js ->
        for (m in motions) for (value in listOf(m.from, m.to, (m.from + m.to) / 3, 17.5)) {
            val expected = m.poseAt(value).get(DoubleArray(16))
            val actual = js.call("motionPoseAt", js.fromJson(m.toJson()), value).doubles()
            for (i in 0 until 16) assertEquals(expected[i], actual[i], 1e-9 * (1 + abs(expected[i])), "$m at $value, element $i")
        }
    }

    @Test
    fun aPartDrawnPartWayIsMovedFromWhereItIsDrawn() = session().use { js ->
        val m = motions[1]; val at = 25.0; val value = -5.0
        val rest = Vector3d(3.0, -1.0, 2.0)
        // The part's geometry is at pose(at) · rest; the viewer shows it at pose(value) · rest.
        val drawn = m.poseAt(at).transformPoint(rest)
        val want = m.poseAt(value).transformPoint(rest)
        val d = js.call("motionDelta", js.fromJson(m.toJson()), at, value).doubles()
        val x = d[0] * drawn.x + d[4] * drawn.y + d[8] * drawn.z + d[12]
        val y = d[1] * drawn.x + d[5] * drawn.y + d[9] * drawn.z + d[13]
        val z = d[2] * drawn.x + d[6] * drawn.y + d[10] * drawn.z + d[14]
        assertEquals(want.x, x, 1e-9); assertEquals(want.y, y, 1e-9); assertEquals(want.z, z, 1e-9)
    }

    @Test
    fun theTimelineAndTheTrackLookups() = session().use { js ->
        assertEquals(0.0, js.call("timelineAt", "SWING", 0.0).asDouble())
        assertEquals(1.0, js.call("timelineAt", "SWING", 0.45).asDouble())
        assertEquals(0.0, js.call("timelineAt", "SWING", 0.95).asDouble())
        val half = js.call("timelinePhaseOf", "SWING", 0.5).asDouble()
        assertTrue(abs(js.call("timelineAt", "SWING", half).asDouble() - 0.5) < 1e-6, "play resumes where it was scrubbed to")
        assertEquals(0.25, js.call("timelineAt", "LOOP", 0.25).asDouble())
        val track = js.fromJson("""{"values":[0,10,20,30],"samples":[9,4,0.3,-1],"bands":[{"below":0.5,"color":[1,0,0]},{"below":5,"color":[1,0.6,0]}]}""")
        assertEquals(2, js.call("motionStepAt", track, 22.0).asInt())
        assertEquals(-1, js.call("motionBandOf", track, 0).asInt())
        assertEquals(1, js.call("motionBandOf", track, 1).asInt())
        assertEquals(0, js.call("motionBandOf", track, 2).asInt())
        assertEquals(0, js.call("motionBandOf", track, 3).asInt())
    }
}
