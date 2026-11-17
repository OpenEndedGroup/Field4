package afield.geometry.solid.manifold

import afield.math.linalg.vec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [closed] and [ballMilled] against shapes whose milled form is known without computing it.
 *
 * The ball is a polyhedron, so volumes are compared with a tolerance sized to its faceting; the
 * reachability checks (is this point still solid?) are exact questions with exact answers.
 */
class MillingTest {

    @Test
    fun `a convex part comes back unchanged`() {
        if (!ManifoldLibrary.isAvailable) return
        val box = Solid.cube(2.0, 1.0, 3.0)
        val milled = box.ballMilled(0.25)
        assertEquals(box.volume, milled.volume, 1e-9, "a ball reaches every point outside a convex part")
    }

    @Test
    fun `a sloping convex part gains no slivers`() {
        if (!ManifoldLibrary.isAvailable) return
        // Re-derived sloping faces land a few epsilon outside the originals; none of that may survive.
        val slab = Solid.cube(3.0, 0.1, 2.0).rotated(23.0, 0.0, 11.5)
        for (milled in [slab.ballMilled(0.0125, segments = 12), slab.ballMilled(0.0125, from = [vec(0, 1, 0), vec(0, -1, 0)], segments = 12)]) {
            val extra = milled - slab
            assertEquals(0, extra.decompose().size, "nothing outside a convex part, not even a zero-thickness patch")
        }
    }

    @Test
    fun `point reflection keeps the tool a positive volume`() {
        if (!ManifoldLibrary.isAvailable) return
        val offCentre = Solid.cube(1.0, 2.0, 3.0)
        val reflected = offCentre.scaled(-1.0, -1.0, -1.0)
        assertEquals(6.0, reflected.volume, 1e-12)
        assertEquals(-3.0, reflected.bounds.min.z.toDouble(), 1e-12)
    }

    @Test
    fun `an inside corner gains exactly a fillet's worth of material`() {
        if (!ManifoldLibrary.isAvailable) return
        // An L extruded 1 deep: one concave right-angle edge of length 1.
        val l = Solid.union(Solid.cube(2.0, 1.0, 1.0), Solid.cube(1.0, 2.0, 1.0))
        val r = 0.25
        val milled = l.ballMilled(r, segments = 32)
        val added = milled.volume - l.volume
        val exact = (1 - PI / 4) * r * r * 1.0
        assertTrue(abs(added - exact) / exact < 0.02, "fillet volume $added, a true quarter-round would add $exact")
        assertTrue(milled.contains(1.02, 1.02, 0.5), "the corner itself is filled")
        assertFalse(milled.contains(1.2, 1.2, 0.5), "but not beyond the fillet")
    }

    @Test
    fun `a slot narrower than the ball fills, and a wide one only rounds its floor`() {
        if (!ManifoldLibrary.isAvailable) return
        val block = Solid.cube(3.0, 1.0, 1.0)
        val narrow = Solid.cube(0.3, 0.6, 2.0).translated(0.6, 0.4, -0.5)
        val wide = Solid.cube(1.0, 0.6, 2.0).translated(1.6, 0.4, -0.5)
        val part = block - narrow - wide
        val milled = part.ballMilled(0.2)
        assertTrue(milled.contains(0.75, 0.45, 0.5), "the bottom of a 0.3 slot is out of a 0.4 ball's reach")
        assertTrue(milled.contains(1.62, 0.42, 0.5), "a wide slot's floor corner is filleted")
        assertFalse(milled.contains(2.1, 0.45, 0.5), "and the middle of its floor is cut")
    }

    @Test
    fun `a 3-axis setup cannot reach under an overhang that a free ball can`() {
        if (!ManifoldLibrary.isAvailable) return
        // A base, a post, and a roof over a cavity that opens to the side.
        val base = Solid.cube(3.0, 0.4, 2.0)
        val post = Solid.cube(0.5, 1.0, 2.0).translated(0.0, 0.4, 0.0)
        val roof = Solid.cube(3.0, 0.3, 2.0).translated(0.0, 1.4, 0.0)
        val part = Solid.union(base, post, roof)
        val cavity = doubleArrayOf(2.0, 0.9, 1.0)

        val free = part.ballMilled(0.15)
        assertFalse(free.contains(cavity[0], cavity[1], cavity[2]), "a free ball reaches the cavity from the side")

        val fromTop = part.ballMilled(0.15, from = listOf(vec(0, 1, 0)))
        assertTrue(fromTop.contains(cavity[0], cavity[1], cavity[2]), "a tool from +y is blocked by the roof")
        assertTrue(fromTop.volume <= Solid.cube(3.0, 1.7, 2.0).volume + 1e-9, "and the result stays inside the stock")
    }

    @Test
    fun `turning the part over opens a pocket on its underside`() {
        if (!ManifoldLibrary.isAvailable) return
        val plate = Solid.cube(3.0, 1.0, 2.0)
        val top = Solid.cube(1.0, 0.5, 1.0).translated(0.5, 0.5, 0.5)
        val under = Solid.cube(1.0, 0.5, 1.0).translated(1.5, 0.0, 0.5)
        val part = plate - top - under
        val up = vec(0, 1, 0)
        val down = vec(0, -1, 0)

        val oneSide = part.ballMilled(0.2, from = listOf(up))
        assertFalse(oneSide.contains(1.0, 0.8, 1.0), "the top pocket is cut")
        assertTrue(oneSide.contains(2.0, 0.2, 1.0), "the underside pocket is not reachable from above")

        val bothSides = part.ballMilled(0.2, from = listOf(up, down))
        assertFalse(bothSides.contains(2.0, 0.2, 1.0), "turned over, it is")
        assertTrue(bothSides.contains(1.52, 0.48, 0.52), "with its own inside corner filleted")
        assertTrue(bothSides.volume > part.volume, "both sides leave fillets behind")
    }
}
