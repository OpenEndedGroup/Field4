package afield.graphics.three

import afield.geometry.mesh.flatShaded
import afield.geometry.mesh.icosphereMesh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThreeGridFactsTest {
    @Test
    fun aFlatShadedClosedMeshIsReportedWatertight() {
        // flatShaded() gives every triangle its own three vertices; read by index that is all open
        // edges, but the fact line measures the surface, so the mesh is still closed.
        val facts = icosphereMesh(2, 1.0).flatShaded().threeFacts()
        assertTrue("watertight" in facts, facts)
        assertTrue("open" !in facts, facts)
    }

    @Test
    fun sharedScaleGrowsEveryPanelToTheLargestExtentAboutItsOwnCentre() {
        val small = ThreeBounds(floatArrayOf(4f, 4f, 4f), floatArrayOf(6f, 6f, 6f))
        val large = ThreeBounds(floatArrayOf(-5f, -1f, 0f), floatArrayOf(5f, 1f, 2f))
        val (s, l) = sharedScaleBounds(listOf(small, large))
        assertEquals(listOf(0f, 4f, 4f), s.min.toList(), "small, centred on (5,5,5), half-extent (5,1,1)")
        assertEquals(listOf(10f, 6f, 6f), s.max.toList())
        assertEquals(large.min.toList(), l.min.toList(), "the largest panel is unchanged")
        assertEquals(large.max.toList(), l.max.toList())
    }
}
