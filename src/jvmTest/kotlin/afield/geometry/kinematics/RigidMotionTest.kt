package afield.geometry.kinematics

import afield.math.linalg.Vector3d
import afield.math.linalg.Vector3dc
import afield.math.linalg.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RigidMotionTest {
    private fun assertNear(expected: Vector3dc, actual: Vector3dc, eps: Double = 1e-9) {
        val d = sqrt((expected.x() - actual.x()).let { it * it } + (expected.y() - actual.y()).let { it * it } + (expected.z() - actual.z()).let { it * it })
        assertTrue(d < eps, "expected $expected, got $actual (off by $d)")
    }

    @Test fun rotationAboutZThroughAPointTurnsByTheRightHandRule() {
        val m = RigidMotion.rotation(Vector3d(1.0, 2.0, 0.0), Vector3d(0.0, 0.0, 5.0), 0.0, 90.0)
        // (2, 2, 3) is 1 along +x from the axis; a quarter turn takes it 1 along +y.
        assertNear(Vector3d(1.0, 3.0, 3.0), m.pointAt(1.0, Vector3d(2.0, 2.0, 3.0)))
        assertNear(Vector3d(2.0, 2.0, 3.0), m.pointAt(0.0, Vector3d(2.0, 2.0, 3.0)))
        val h = m.pointAt(0.5, Vector3d(2.0, 2.0, 3.0))
        assertNear(Vector3d(1.0 + cos(PI / 4), 2.0 + sin(PI / 4), 3.0), h)
        // Points on the axis stay put.
        assertNear(Vector3d(1.0, 2.0, -7.0), m.pointAt(0.73, Vector3d(1.0, 2.0, -7.0)))
    }

    @Test fun rotationAboutATiltedAxisKeepsDistancesAndAxisPoints() {
        val point = Vector3d(0.3, -1.2, 2.0)
        val axis = Vector3d(1.0, 1.0, 1.0)
        val m = RigidMotion.rotation(point, axis, -30.0, 120.0)
        val p = Vector3d(2.0, 0.5, -1.0); val q = Vector3d(-1.0, 4.0, 0.25)
        for (t in listOf(0.0, 0.2, 0.5, 1.0)) {
            val a = m.pointAt(t, p); val b = m.pointAt(t, q)
            assertEquals(Vector3d(p).sub(q).length(), Vector3d(a).sub(b).length(), 1e-9)
            assertNear(Vector3d(point).add(Vector3d(2.0, 2.0, 2.0)), m.pointAt(t, Vector3d(point).add(Vector3d(2.0, 2.0, 2.0))))
        }
        // A full turn about the (1,1,1) axis by 120° cycles x -> y -> z.
        val cyc = RigidMotion.rotation(Vector3d(), axis, 0.0, 120.0)
        assertNear(Vector3d(0.0, 1.0, 0.0), cyc.pointAt(1.0, Vector3d(1.0, 0.0, 0.0)))
        assertNear(Vector3d(0.0, 0.0, 1.0), cyc.pointAt(1.0, Vector3d(0.0, 1.0, 0.0)))
        // The rotation is proper (determinant 1).
        assertEquals(1.0, m.pose(0.37).determinant(), 1e-12)
    }

    @Test fun slideMovesByTheDistanceAlongTheUnitDirection() {
        val m = RigidMotion.slide(Vector3d(3.0, 4.0, 0.0), 10.0, 60.0)
        assertNear(Vector3d(6.0, 8.0, 1.0), m.pointAt(0.0, Vector3d(0.0, 0.0, 1.0)))
        assertNear(Vector3d(36.0, 48.0, 1.0), m.pointAt(1.0, Vector3d(0.0, 0.0, 1.0)))
        assertEquals(35.0, m.valueAt(0.5), 1e-12)
        assertEquals(0.5, m.parameterOf(35.0), 1e-12)
        assertEquals(1.0, m.pose(0.5).determinant(), 1e-12)
    }

    @Test fun valueZeroIsTheRestPose() {
        for (m in listOf(RigidMotion.rotation(Vector3d(5.0, 1.0, 2.0), Vector3d(0.2, 0.3, 0.9), 15.0, 80.0),
                         RigidMotion.slide(Vector3d(0.0, 1.0, 0.0), 4.0, 9.0))) {
            val id = m.poseAt(0.0).get(DoubleArray(16))
            val expected = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
            for (i in 0 until 16) assertEquals(expected[i], id[i], 1e-12, "component $i of $m")
        }
    }

    @Test fun stepsCoverTheRangeInclusively() {
        val m = RigidMotion.rotation(Vector3d(), Vector3d(0.0, 0.0, 1.0), 0.0, 95.0)
        val steps = m.stepsBy(5.0)
        assertEquals(20, steps.size)
        assertEquals(0.0, steps.first().value); assertEquals(95.0, steps.last().value)
        assertEquals(1.0, steps.last().t)
        assertEquals(96, m.stepsBy(1.0).size)
        assertEquals(2, m.steps(1).size)
        val back = m.reversed().steps(4)
        assertEquals(95.0, back.first().value); assertEquals(0.0, back.last().value)
        assertFailsWith<IllegalArgumentException> { m.steps(0) }
        assertFailsWith<IllegalArgumentException> { m.stepsBy(0.0) }
    }

    @Test fun jsonRoundTripsBothKinds() {
        val r = RigidMotion.rotation(Vector3f(1f, 2f, 3f), Vector3f(0f, 0f, 2f), 0.0, 90.0)
        val s = RigidMotion.slide(Vector3d(1.0, 0.0, 0.0), -5.0, 25.0)
        assertEquals("""{"type":"rotation","point":[1.0,2.0,3.0],"axis":[0.0,0.0,1.0],"from":0.0,"to":90.0}""", r.toJson())
        assertEquals("""{"type":"slide","direction":[1.0,0.0,0.0],"from":-5.0,"to":25.0}""", s.toJson())
        assertEquals(r, RigidMotion.fromJson(r.toJson()))
        assertEquals(s, RigidMotion.fromJson(s.toJson()))
        // A hand-written motion with an unnormalised axis still poses correctly.
        val hand = RigidMotion.fromJson("""{"type":"rotation","point":[0,0,0],"axis":[0,0,10],"from":0,"to":90}""")
        assertNear(Vector3d(0.0, 1.0, 0.0), hand.pointAt(1.0, Vector3d(1.0, 0.0, 0.0)))
    }

    @Test fun poseFormsAgree() {
        val m = RigidMotion.rotation(Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0, 40.0)
        val d = m.poseArray(0.3)
        val f = m.poseF(0.3).get(FloatArray(16))
        for (i in 0 until 16) assertEquals(d[i], f[i].toDouble(), 1e-6)
        assertEquals(0.0, d[3]); assertEquals(1.0, d[15])
    }

    @Test fun maxTravelBoundsTheMotionOfAnyPointInTheBall() {
        val m = RigidMotion.rotation(Vector3d(), Vector3d(0.0, 0.0, 1.0), 0.0, 90.0)
        val bound = m.maxTravel(Vector3d(3.0, 0.0, 0.0), 1.0, 10.0, 20.0)
        assertEquals(4.0 * Math.toRadians(10.0), bound, 1e-12)
        val p = Vector3d(4.0, 0.0, 0.0)
        val moved = Vector3d(m.poseAt(20.0).transformPoint(p)).sub(m.poseAt(10.0).transformPoint(p)).length()
        assertTrue(moved <= bound)
        val pts = doubleArrayOf(4.0, 0.0, 9.0, 0.0, -2.0, 1.0)
        assertEquals(4.0 * Math.toRadians(10.0), m.maxTravel(pts, 10.0, 20.0), 1e-12)
        assertEquals(7.0, RigidMotion.slide(Vector3d(0.0, 0.0, 1.0), 0.0, 10.0).maxTravel(Vector3d(), 100.0, 2.0, 9.0))
    }

    @Test fun degenerateInputsAreRefused() {
        assertFailsWith<IllegalArgumentException> { RigidMotion.rotation(Vector3d(), Vector3d(), 0.0, 1.0) }
        assertFailsWith<IllegalArgumentException> { RigidMotion.slide(Vector3d(1.0, 0.0, 0.0), 0.0, Double.NaN) }
    }
}
