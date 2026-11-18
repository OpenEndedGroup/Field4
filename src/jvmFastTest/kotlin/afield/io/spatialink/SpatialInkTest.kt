package afield.io.spatialink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SpatialInkTest {
    @Test
    fun snapsInThreeDimensionsAndKeepsSettledEdgesAcrossUndo() {
        val fitter = SpatialInkFitter()
        val wall = SpatialInkStroke("wall", listOf(p(0f, 0f, 0f), p(0.3f, 0f, 0f)))
        val joined = SpatialInkStroke("joined", listOf(p(0.302f, 0.002f, 0.003f), p(0.4f, 0.2f, 0.08f)))
        val behind = SpatialInkStroke("behind", listOf(p(0.3f, 0f, 0.09f), p(0.3f, -0.2f, 0.1f)))
        val first = fitter.update(SpatialInkRequest(1, listOf(wall, joined, behind)))
        assertEquals(first.edges[0].to, first.edges[1].from)
        assertEquals(first.edges[0].points.last(), first.edges[1].points.first())
        assertNotEquals(first.edges[0].to, first.edges[2].from)
        assertTrue(first.edges[1].toFLine().nodes.any { it.to.z > 0.05f })
        assertEquals(first.edges.take(1), fitter.update(SpatialInkRequest(2, listOf(wall))).edges)
        assertEquals(emptyList(), fitter.update(SpatialInkRequest(3, emptyList())).edges)
    }

    @Test
    fun aRejectedSnapshotDoesNotInvalidateThePreviousFit() {
        val fitter = SpatialInkFitter()
        val stroke = SpatialInkStroke("ink", listOf(p(0f, 0f, 0f), p(0.3f, 0f, 0f)))
        val accepted = fitter.update(SpatialInkRequest(1, listOf(stroke)))
        val invalid = stroke.copy(points = listOf(p(0f, 0f, 0f), p(Float.NaN, 0f, 0f)))
        assertFailsWith<IllegalArgumentException> { fitter.update(SpatialInkRequest(2, listOf(invalid))) }
        assertEquals(accepted.edges, fitter.update(SpatialInkRequest(3, listOf(stroke))).edges)
    }

    private fun p(x: Float, y: Float, z: Float) = SpatialInkPoint(x, y, z)
}
