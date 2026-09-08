package afield.four.targets

import afield.stdlib.Vec3
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Quaternionf
import kotlin.math.sqrt
import kotlin.test.*

class BinocularGazeIntersectionTest {
    private val left = Vec3(-0.032f, 0f, 0f)
    private val right = Vec3(0.032f, 0f, 0f)
    private fun toward(origin: Vec3, target: Vec3) = Vec3(target).sub(origin)
    private fun assertPoint(expected: Vec3, actual: Vec3, tolerance: Float = 1e-4f) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
        assertEquals(expected.z, actual.z, tolerance)
    }

    @Test fun convergentRaysRecoverOffAxisTargetsAndIgnoreDirectionMagnitude() {
        val solver = BinocularGazeIntersection()
        for (target in listOf(Vec3(0f, 0f, -0.5f), Vec3(0.2f, -0.1f, -2f), Vec3(-0.3f, 0.2f, -4f))) {
            assertTrue(solver.solve(left, toward(left, target).mul(7f), right, toward(right, target).mul(0.3f)))
            assertPoint(target, solver.position)
            assertTrue(solver.leftDistance > 0)
            assertTrue(solver.separation < 1e-5)
        }
    }

    @Test fun skewRaysUseClosestPointsAndRejectLargeGap() {
        val solver = BinocularGazeIntersection()
        val raisedRight = Vec3(right.x, 0.02f, 0f)
        assertTrue(solver.solve(left, Vec3(0.032f, 0f, -2f), raisedRight, Vec3(-0.032f, 0f, -2f)))
        assertPoint(Vec3(0f, 0.01f, -2f), solver.position)
        assertEquals(0.02, solver.separation, 1e-5)
        assertFalse(solver.solve(left, Vec3(0.032f, 0f, -2f), Vec3(right.x, 0.1f, 0f), Vec3(-0.032f, 0f, -2f)))
        assertEquals(BinocularGazeIntersection.Status.RAYS_MISS, solver.status)
    }

    @Test fun parallelDivergentFarAndInvalidRaysNeverPlaceMarker() {
        val solver = BinocularGazeIntersection()
        assertFalse(solver.solve(left, Vec3(0f, 0f, -1f), right, Vec3(0f, 0f, -1f)))
        assertEquals(BinocularGazeIntersection.Status.PARALLEL, solver.status)
        assertFalse(solver.solve(left, Vec3(-0.032f, 0f, -1f), right, Vec3(0.032f, 0f, -1f)))
        assertEquals(BinocularGazeIntersection.Status.BEHIND_EYES, solver.status)
        val far = Vec3(0f, 0f, -12f)
        assertFalse(solver.solve(left, toward(left, far), right, toward(right, far)))
        assertEquals(BinocularGazeIntersection.Status.OUT_OF_RANGE, solver.status)
        assertFalse(solver.solve(left, Vec3(Float.NaN, 0f, -1f), right, Vec3(0f, 0f, -1f)))
        assertFalse(solver.raysValid)
        assertFalse(solver.solve(left, Vec3(), right, Vec3(0f, 0f, -1f)))
        assertFalse(solver.solve(left, Vec3(0f, 0f, -1f), left, Vec3(1f, 0f, -1f)))
    }

    @Test fun htcPosesAlreadyInLocalSpaceNeedNoHeadTransformAndInvalidateOnLoss() {
        val state = HtcEyeTrackingState()
        val target = Vec3(0.5f, 0.1f, -2f)
        for ((eye, origin) in listOf(state.left to Vec3(0.368f, 0.2f, 0.3f), state.right to Vec3(0.432f, 0.2f, 0.3f))) {
            eye.position.set(origin)
            eye.gazeValid = true
            val direction = toward(origin, target).normalize()
            // Quaternion rotating -Z to the desired local-space direction.
            val w = 1f - direction.z
            val n = sqrt(direction.x * direction.x + direction.y * direction.y + w * w)
            eye.orientation.set(direction.y / n, -direction.x / n, 0f, w / n)
        }
        val solver = BinocularGazeIntersection()
        assertTrue(solver.update(state))
        assertPoint(target, solver.position)
        state.right.gazeValid = false
        assertFalse(solver.update(state))
        assertFalse(solver.raysValid)
        assertEquals(BinocularGazeIntersection.Status.NO_TRACKING, solver.status)
        assertTrue(solver.separation.isNaN())
    }

    @Test fun worldTransformCommutesWithTriangulationAndCancelsInEyeView() {
        val target = Vec3(0.2f, -0.1f, -2f)
        val q = Quaternionf(0f, 0.25881904f, 0f, 0.9659258f)
        val worldFromLocal = Matrix4f().translate(3f, 1f, -4f).rotate(q)
        val localFromWorld = Matrix4f(worldFromLocal).invert()
        val worldLeft = worldFromLocal.transformPosition(Vec3(left))
        val worldRight = worldFromLocal.transformPosition(Vec3(right))
        val worldTarget = worldFromLocal.transformPosition(Vec3(target))
        val solver = BinocularGazeIntersection()
        assertTrue(solver.solve(worldLeft, toward(worldLeft, worldTarget), worldRight, toward(worldRight, worldTarget)))
        assertPoint(worldTarget, solver.position)
        for (eye in listOf(left, right)) {
            val eyeViewLocal = Matrix4f().translate(eye).invert()
            val expectedEyePoint = eyeViewLocal.transformPosition(Vec3(target))
            val eyeViewWorld = Matrix4f(eyeViewLocal).mul(localFromWorld)
            assertPoint(expectedEyePoint, eyeViewWorld.transformPosition(Vec3(solver.position)))
        }
    }
}
