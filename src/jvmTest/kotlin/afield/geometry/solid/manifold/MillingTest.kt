package afield.geometry.solid.manifold

import afield.math.linalg.vec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [closedBy], [ballMilled] and [beyond] against shapes whose milled form is known without computing it.
 *
 * The ball is a polyhedron, so volumes are compared with a tolerance sized to its faceting; the
 * reachability checks (is this point still solid?) are exact questions with exact answers.
 */
class MillingTest {

    @Test
    fun `a convex part comes back unchanged`() {
        org.junit.Assume.assumeTrue("native library not available: ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
        val box = Solid.cube(2.0, 1.0, 3.0)
        val milled = box.ballMilled(0.25)
        assertEquals(box.volume, milled.volume, 1e-9, "a ball reaches every point outside a convex part")
    }

    @Test
    fun `beyond shows nothing on a sloping convex part`() {
        org.junit.Assume.assumeTrue("native library not available: ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
        // Re-derived sloping faces land a few epsilon off the originals. A plain difference keeps
        // those zero-thickness sheets; beyond must not.
        val slab = Solid.cube(3.0, 0.1, 2.0).rotated(23.0, 0.0, 11.5)
        for (milled in [slab.ballMilled(0.0125, segments = 12), slab.ballMilled(0.0125, from = [vec(0, 1, 0), vec(0, -1, 0)], segments = 12)]) {
            assertEquals(0, milled.beyond(slab).decompose().size, "nothing outside a convex part, not even a zero-thickness patch")
        }
    }

    @Test
    fun `faceted holes stay open`() {
        org.junit.Assume.assumeTrue("native library not available: ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
        // The shallow creases between a hole's facets are concave too. Whatever the closing does
        // there, the hole itself must stay open from end to end.
        val plate = Solid.cube(60.0, 40.0, 8.0, center = true)
        val holes = (0..3).map { Solid.cylinder(30.0, 3.0, segments = 48, center = true).translated(-21.0 + 14.0 * it, 0.0, 0.0) }
        val pocket = Solid.cube(30.0, 14.0, 6.0, center = true).translated(0.0, 12.0, 4.0)
        val part = plate - Solid.union(holes) - pocket
        val milled = part.ballMilled(1.5)
        for (h in 0..3) for (z in [-3.9, 0.0, 3.9]) {
            assertFalse(milled.contains(-21.0 + 14.0 * h, 0.0, z), "hole $h is open at z = $z")
        }
        assertTrue(milled.beyond(part).volume > 40.0, "and the pocket's inside edges are filleted")
    }

    @Test
    fun `a sloping stepped part keeps its fillets`() {
        org.junit.Assume.assumeTrue("native library not available: ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
        // Like a roof panel: a thin slab with a two-step rebate, tilted, so every face is re-derived
        // off-axis. Its two inside edges must still come back filleted, by a quarter-round each.
        val r = 0.0125
        val slab = (Solid.cube(3.0, 0.1, 2.0) - Solid.cube(1.0, 0.05, 2.0).translated(0.0, 0.05, 0.0) -
            Solid.cube(0.5, 0.025, 2.0).translated(1.0, 0.075, 0.0)).rotated(9.09, 0.0, 9.09)
        val expected = 2 * (1 - PI / 4) * r * r * 2.0
        val milled = slab.ballMilled(r)
        for (added in [milled.volume - slab.volume, milled.beyond(slab).volume]) {
            assertTrue(abs(added - expected) / expected < 0.06, "fillets add $added, two quarter-rounds $expected")
        }
    }

    @Test
    fun `point reflection keeps the tool a positive volume`() {
        org.junit.Assume.assumeTrue("native library not available: ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
        val offCentre = Solid.cube(1.0, 2.0, 3.0)
        val reflected = offCentre.scaled(-1.0, -1.0, -1.0)
        assertEquals(6.0, reflected.volume, 1e-12)
        assertEquals(-3.0, reflected.bounds.min.z.toDouble(), 1e-12)
    }

    @Test
    fun `an inside corner gains exactly a fillet's worth of material`() {
        org.junit.Assume.assumeTrue("native library not available: ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
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
        org.junit.Assume.assumeTrue("native library not available: ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
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
        org.junit.Assume.assumeTrue("native library not available: ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
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
        org.junit.Assume.assumeTrue("native library not available: ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
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
