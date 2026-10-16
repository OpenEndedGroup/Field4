package afield.graphics.three

import afield.geometry.mesh.boxMesh
import kotlin.test.Test
import kotlin.test.assertEquals

/** A mesh that names no panel goes into the panel declared most recently. */
class ThreePanelDefaultTest {
    @Test
    fun `mesh follows the last declared panel`() {
        val b = ThreeSceneBuilder(8)
        b.mesh(boxMesh())                // before any panel: the first
        b.panel("A")
        b.mesh(boxMesh())
        b.panel("B")
        b.mesh(boxMesh())
        b.mesh(boxMesh(), panel = 0)     // an explicit index still wins
        assertEquals(listOf(0, 0, 1, 0), b.drawables.map { it.panel })
    }
}
