package afield.graphics.three

import afield.geometry.path.div
import afield.geometry.path.minus
import afield.geometry.path.plus
import afield.geometry.path.times
import afield.lang.remotejs.JsFun

import afield.geometry.mesh.TriMesh
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A translucent mesh must not write depth: three.js sorts translucent geometry per object, not per
 * triangle, so a see-through shell that wrote depth hid whatever drew after it (the house gallery's
 * conduits cut off inside x-ray walls). An opaque mesh keeps writing depth, which it needs to occlude
 * itself.
 */
class ThreeMeshDepthWriteTest {

    private val triangle = TriMesh(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), intArrayOf(0, 1, 2))

    private fun depthWrites(build: ThreeSceneBuilder.() -> Unit): List<Boolean> =
        ThreeSceneBuilder(48).apply(build).drawables.map { it.style.depthWrite }

    @Test
    fun translucentMeshesLeaveDepthAloneAndOpaqueOnesWriteIt() {
        assertEquals(listOf(true, false), depthWrites { mesh(triangle); mesh(triangle, opacity = .4) })
    }

    @Test
    fun anExplicitDepthWriteWins() {
        assertEquals(listOf(true, false), depthWrites { mesh(triangle, opacity = .4, depthWrite = true); mesh(triangle, depthWrite = false) })
    }
}
