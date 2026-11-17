package afield.geometry.solid.manifold

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [fused] on cracks a union leaves, against volumes and piece counts known in advance. */
class FusingTest {

    private val half = Solid.cube(1.0, 0.1, 1.0)

    @Test
    fun `two halves that only touch become one piece`() {
        if (!ManifoldLibrary.isAvailable) return
        val cracked = Solid.union(half, half.translated(1.0 + 1e-12, 0.0, 0.0))
        assertEquals(2, cracked.decompose().size, "the fixture holds a crack")
        val fused = cracked.fused()
        assertEquals(1, fused.decompose().size)
        assertEquals(0.2, fused.volume, 1e-12)
        assertEquals(0, fused.genus)
        assertEquals(2.0, fused.bounds.size.x.toDouble(), 1e-6, "nothing moved")
    }

    @Test
    fun `a tilted crack fuses too`() {
        if (!ManifoldLibrary.isAvailable) return
        val cracked = Solid.union(half, half.translated(1.0 + 1e-12, 0.0, 0.0)).rotated(17.0, 31.0, 7.0)
        assertEquals(2, cracked.decompose().size, "the fixture holds a crack")
        val fused = cracked.fused()
        assertEquals(1, fused.decompose().size)
        assertEquals(0.2, fused.volume, 1e-12)
    }

    @Test
    fun `a crack over part of a face fuses only there`() {
        if (!ManifoldLibrary.isAvailable) return
        // An L: the long cell's +x face is a crack for z < 1 and outside beyond.
        val l = Solid.union(Solid.cube(1.0, 0.1, 2.0), half.translated(1.0 + 1e-12, 0.0, 0.0))
        assertEquals(2, l.decompose().size, "the fixture holds a crack")
        val fused = l.fused()
        assertEquals(1, fused.decompose().size)
        assertEquals(0.3, fused.volume, 1e-12)
        // The L's inside corner is still open: nothing was filled outside the overlap.
        assertTrue(!fused.contains(1.0 + 1e-6, 0.05, 1.0 + 1e-6) && fused.contains(1.0 - 1e-6, 0.05, 1.0 + 1e-6))
    }

    @Test
    fun `pieces that touch only along an edge stay apart`() {
        if (!ManifoldLibrary.isAvailable) return
        val pair = Solid.union(Solid.cube(1.0, 1.0, 1.0), Solid.cube(1.0, 1.0, 1.0).translated(1.0, 0.0, 1.0))
        val fused = pair.fused()
        assertEquals(pair.decompose().size, fused.decompose().size)
        assertEquals(2.0, fused.volume, 1e-12)
    }

    @Test
    fun `a solid with no crack comes back as it was`() {
        if (!ManifoldLibrary.isAvailable) return
        val l = Solid.cube(2.0, 0.1, 1.0) + Solid.cube(1.0, 0.1, 2.0)
        val fused = l.fused()
        assertEquals(l.volume, fused.volume, 1e-15)
        assertEquals(l.toPlanarMesh().triangleCount, fused.toPlanarMesh().triangleCount)
    }
}
