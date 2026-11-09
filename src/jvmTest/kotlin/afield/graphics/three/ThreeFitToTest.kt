package afield.graphics.three

import afield.geometry.mesh.TriMesh
import afield.math.linalg.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

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

    @Test
    fun anInsideOutBoxIsRefused() {
        assertFailsWith<IllegalArgumentException> { ThreeSceneBuilder(48).fitTo(Vec3(1f, 0f, 0f), Vec3(0f, 1f, 1f)) }
    }
}
