package afield.graphics.scene.body

import afield.graphics.vulkan.targets.OpenXRHmdTarget.*
import afield.math.linalg.Vector3f
import kotlin.test.*

class BodyGeometryTest {
    private fun body(): BodyTrackingState = BodyTrackingState().apply {
        this[BodyJoint.HIPS].apply { positionValid = true; position.set(0f, 0f, 0f) }
        this[BodyJoint.CHEST].apply { positionValid = true; position.set(0f, 2f, 0f) }
    }
    private fun close(expected: Vector3f, actual: Vector3f) = assertTrue(expected.distance(actual) < 1e-5f, "$expected != $actual")

    @Test fun emptyOrInvalidObservationsHaveNoBoundsOrHit() {
        val state = BodyTrackingState()
        state[BodyJoint.HIPS].position.set(20f, 30f, 40f)
        assertNull(BodyGeometry(state).bounds())
        assertNull(BodyGeometry(state).closestPoint(Vector3f()))
        state[BodyJoint.HIPS].positionValid = true
        state[BodyJoint.HIPS].position.x = Float.NaN
        assertNull(BodyGeometry(state).closestJoint(Vector3f()))
    }
    @Test fun snapshotAndQueriesDoNotMutateOrFollowTheirInputs() {
        val state = body()
        val geometry = BodyGeometry(state)
        state[BodyJoint.HIPS].position.set(100f, 100f, 100f)
        val query = Vector3f(1f, .5f, 0f)
        close(Vector3f(0f, .5f, 0f), geometry.closestPoint(query)!!.point)
        close(Vector3f(1f, .5f, 0f), query)
        assertEquals(BodyJoint.HIPS, geometry.closestJoint(query)!!.joint)
    }
    @Test fun finiteCylinderHandlesSidesCapsAndCorners() {
        val geometry = BodyGeometry(body())
        close(Vector3f(.2f, 1f, 0f), geometry.closestPoint(Vector3f(1f, 1f, 0f), limbRadius = .2f)!!.point)
        close(Vector3f(.1f, 2f, 0f), geometry.closestPoint(Vector3f(.1f, 3f, 0f), limbRadius = .2f)!!.point)
        close(Vector3f(.2f, 2f, 0f), geometry.closestPoint(Vector3f(1f, 3f, 0f), limbRadius = .2f)!!.point)
    }
    @Test fun interiorQueriesReportSignedDistanceAndStableNormalsOnAxis() {
        val geometry = BodyGeometry(body())
        val side = geometry.closestPoint(Vector3f(0f, 1f, 0f), limbRadius = .2f)!!
        assertEquals(-.2f, side.signedDistance, 1e-5f)
        assertTrue(side.normal.isFinite())
        val cap = geometry.closestPoint(Vector3f(0f, .01f, 0f), limbRadius = .2f)!!
        close(Vector3f(), cap.point)
        close(Vector3f(0f, -1f, 0f), cap.normal)
    }
    @Test fun headIsOptionalAndBoundsIncludeItsRadius() {
        val head = JointPose(position = Vector3f(0f, 3f, 0f), positionValid = true)
        val geometry = BodyGeometry(body(), head)
        val hit = geometry.closestPoint(Vector3f(0f, 3.2f, 0f), headRadius = .1f)!!
        assertTrue(hit.head)
        close(Vector3f(0f, 3.1f, 0f), hit.point)
        assertFalse(geometry.closestPoint(Vector3f(0f, 3.2f, 0f), includeHead = false)!!.head)
        close(Vector3f(.2f, 3.1f, .2f), geometry.bounds(.2f, .1f)!!.max)
        close(Vector3f(-.2f, 0f, -.2f), geometry.bounds(.2f, .1f)!!.min)
        val headOnly = BodyGeometry(null, head)
        assertTrue(headOnly.closestPoint(Vector3f(0f, 4f, 0f), headRadius = .1f)!!.head)
        assertNull(headOnly.bounds(includeHead = false))
    }
    @Test fun tiltedCylinderBoundsUseRadialExtentsRatherThanFullRadiusAlongItsAxis() {
        val state = body()
        state[BodyJoint.CHEST].position.set(1f, 1f, 0f)
        val bounds = BodyGeometry(state).bounds(limbRadius = .2f)!!
        val radial = .2f / kotlin.math.sqrt(2f)
        close(Vector3f(1f + radial, 1f + radial, .2f), bounds.max)
        close(Vector3f(-radial, -radial, -.2f), bounds.min)
    }

    @Test fun collapsedSegmentAndIsolatedJointHaveFiniteSphereFallbacks() {
        val state = body()
        state[BodyJoint.CHEST].position.set(0f, 0f, 0f)
        val geometry = BodyGeometry(state)
        close(Vector3f(.2f, 0f, 0f), geometry.closestPoint(Vector3f(1f, 0f, 0f), .2f)!!.point)
        close(Vector3f(-.2f), geometry.bounds(.2f)!!.min)
        state[BodyJoint.CHEST].positionValid = false
        assertEquals(BodyJoint.HIPS, BodyGeometry(state).closestPoint(Vector3f(1f, 0f, 0f))!!.joint)
    }
    @Test fun rejectsNonfiniteQueriesAndNegativeRadii() {
        val geometry = BodyGeometry(body())
        assertFailsWith<IllegalArgumentException> { geometry.closestPoint(Vector3f(Float.NaN)) }
        assertFailsWith<IllegalArgumentException> { geometry.bounds(limbRadius = -1f) }
        assertFailsWith<IllegalArgumentException> { geometry.bounds(padding = Float.POSITIVE_INFINITY) }
    }
}
