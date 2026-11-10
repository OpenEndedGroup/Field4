package afield.graphics.three

import afield.geometry.mesh.TriMesh
import afield.math.linalg.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `fitTo` frames the camera on a declared box rather than on the content, so a figure whose lamp
 * grows with a slider keeps its view. It must widen the scene's bounds to the box and draw nothing.
 */
class ThreeFitToTest {

    private val triangle = TriMesh(floatArrayOf(0f, 0f, 0f, 0.1f, 0f, 0f, 0f, 0.1f, 0f), intArrayOf(0, 1, 2))

    @Test
    fun theBoundsBecomeTheDeclaredBoxAndTheBoxIsInvisible() {
        val builder = ThreeSceneBuilder(48).apply {
            mesh(triangle)
            fitTo(Vec3(-2f, 0f, -1f), Vec3(2f, 3f, 1f))
        }
        val bounds = builder.payload("fit-to-test").bounds!!
        assertEquals(listOf(-2f, 0f, -1f), bounds.min.toList())
        assertEquals(listOf(2f, 3f, 1f), bounds.max.toList())
        val box = builder.drawables.last()
        assertEquals(0f, box.style.opacity, "the framing box draws no pixels")
        assertFalse(box.style.depthWrite, "and hides nothing behind it")
        assertFalse(box.pickable, "and cannot be clicked")
    }

    /**
     * Cameras' pinhole figure: a small camera box on a large floor. fitTo must make the small box the
     * subject; the floor reaching far beyond it must not widen what the camera frames.
     */
    @Test
    fun geometryBeyondTheDeclaredBoxDoesNotWidenTheFrame() {
        val floor = TriMesh(floatArrayOf(-50f, 0f, -50f, 50f, 0f, -50f, 50f, 0f, 50f, -50f, 0f, 50f), intArrayOf(0, 1, 2, 0, 2, 3))
        val payload = ThreeSceneBuilder(48).apply {
            mesh(floor)
            mesh(triangle)
            fitTo(Vec3(-0.2f, 0f, -0.2f), Vec3(0.2f, 0.3f, 0.2f))
            camera(yaw = 30.0, pitch = 20.0)
        }.payload("fit-to-floor")
        assertEquals(listOf(-0.2f, 0f, -0.2f), payload.bounds!!.min.toList())
        assertEquals(listOf(0.2f, 0.3f, 0.2f), payload.bounds!!.max.toList())
        assertTrue(payload.fitBounds, "the viewer frames the box's corners, not the floor's points")
        val unfitted = ThreeSceneBuilder(48).apply { mesh(floor); mesh(triangle) }.payload("no-fit")
        assertFalse(unfitted.fitBounds)
        assertEquals(listOf(-50f, 0f, -50f), unfitted.bounds!!.min.toList(), "without fitTo the content frames itself")
    }

    @Test
    fun anInsideOutBoxIsRefused() {
        assertFailsWith<IllegalArgumentException> { ThreeSceneBuilder(48).fitTo(Vec3(1f, 0f, 0f), Vec3(0f, 1f, 1f)) }
    }
}
