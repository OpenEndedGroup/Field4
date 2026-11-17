package afield.geometry.solid.manifold

import afield.math.linalg.Vec2
import afield.math.linalg.vec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [insetFaces] against answers that do not come from it: closed-form boxes, and Clipper's own mitered
 * 2D offset extruded, which is what an inset of a prism's side faces has to equal.
 */
class FaceInsetTest {

    private val d = 0.01

    @Test
    fun `a panel keeps its thickness and loses its edges`() {
        if (!ManifoldLibrary.isAvailable) return
        val panel = Solid.cube(4.0, 0.1, 3.0)
        val inset = panel.insetFaces(d, except = [vec(0, 1, 0)])
        assertEquals((4.0 - 2 * d) * 0.1 * (3.0 - 2 * d), inset.volume, 1e-12)
        assertEquals(0.0, inset.bounds.min.y.toDouble(), 1e-7, "the bottom stays")
        assertEquals(0.1, inset.bounds.max.y.toDouble(), 1e-7, "and so does the top")
        assertEquals(1, inset.decompose().size)
    }

    @Test
    fun `with nothing kept every face moves`() {
        if (!ManifoldLibrary.isAvailable) return
        val box = Solid.cube(2.0, 1.0, 3.0)
        assertEquals((2 - 2 * d) * (1 - 2 * d) * (3 - 2 * d), box.insetFaces(d, except = []).volume, 1e-12)
    }

    // A non-convex outline with concave and convex corners, and edges that are not axis-aligned.
    private val outline = [
        Vec2(0f, 0f), Vec2(3f, 0f), Vec2(3f, 1f), Vec2(2f, 1.4f), Vec2(1.6f, 2.5f), Vec2(1f, 2.5f),
        Vec2(1f, 1.2f), Vec2(0f, 1.5f),
    ]

    @Test
    fun `a prism's side faces move exactly as a mitered 2D offset of its outline`() {
        if (!ManifoldLibrary.isAvailable) return
        val section = Section.ofPolygon(outline)
        val prism = section.extruded(0.5)
        val inset = prism.insetFaces(d, except = [vec(0, 0, 1)])
        // Clipper's mitered offset is the same plane-shift construction in 2D, computed independently.
        val expected = section.offset(-d, JoinType.MITER, miterLimit = 100.0).extruded(0.5)
        assertEquals(expected.volume, inset.volume, 1e-9 * expected.volume)
        // Clipper rounds its coordinates to 1e-8, so the two may differ by a layer that thin over the
        // 5 square units of side wall: measured 3.0e-9 and 1.3e-9.
        val extra = inset - expected
        val missing = expected - inset
        assertTrue(extra.volume < 5e-8 && missing.volume < 5e-8,
            "same solid, not just same volume: ${extra.volume} extra in ${extra.bounds}, ${missing.volume} missing in ${missing.bounds}")
    }

    @Test
    fun `a tilted prism gives the same answer when its axis is kept`() {
        if (!ManifoldLibrary.isAvailable) return
        val prism = Section.ofPolygon(outline).extruded(0.5)
        val flat = prism.insetFaces(d, except = [vec(0, 0, 1)])
        val inset = prism.rotated(9.09, 0.0, 9.09).insetFaces(d, except = [tiltedZ()])
        assertEquals(flat.volume, inset.volume, 1e-9 * flat.volume)
    }

    @Test
    fun `two boxes that touched end up twice the inset apart`() {
        if (!ManifoldLibrary.isAvailable) return
        val up = [vec(0, 1, 0)]
        val a = Solid.cube(1.0, 0.1, 1.0).insetFaces(d, except = up)
        val b = Solid.cube(1.0, 0.1, 1.0).translated(1.0, 0.0, 0.0).insetFaces(d, except = up)
        assertEquals(2 * d, a.minGap(b, searchLength = 1.0), 1e-9)
    }

    @Test
    fun `a lap joint keeps bearing on its step and opens at its risers`() {
        if (!ManifoldLibrary.isAvailable) return
        // a's lower tongue runs under b's upper tongue, between x = 1.5 and x = 2.
        val a = Solid.cube(2.0, 0.1, 1.0) - Solid.cube(0.5, 0.05, 1.0).translated(1.5, 0.05, 0.0)
        val b = Solid.cube(2.0, 0.1, 1.0).translated(1.5, 0.0, 0.0) - Solid.cube(0.5, 0.05, 1.0).translated(1.5, 0.0, 0.0)
        assertTrue((a and b).volume < 1e-12, "the fixture fits together")
        val up = [vec(0, 1, 0)]
        val ai = a.insetFaces(d, except = up)
        val bi = b.insetFaces(d, except = up)
        assertEquals(0.0, ai.minGap(bi, searchLength = 1.0), 1e-9, "the step is kept, so the tongues still bear on it")
        // Every riser and end face moved back by d, so the material just behind each is gone and the
        // material a little further in remains.
        for ((part, x, y, inward) in [
            Probe(ai, 1.5, 0.075, -1.0), Probe(ai, 2.0, 0.025, -1.0),
            Probe(bi, 1.5, 0.075, 1.0), Probe(bi, 2.0, 0.025, 1.0),
        ]) {
            assertTrue(!part.contains(x + inward * 0.5 * d, y, 0.5), "face at x = $x has moved back")
            assertTrue(part.contains(x + inward * 1.5 * d, y, 0.5), "by d and no more, at x = $x")
        }
    }

    @Test
    fun `pieces that only touched along an edge come apart cleanly`() {
        if (!ManifoldLibrary.isAvailable) return
        // One solid, two cubes meeting along a single edge: any gap at all has to separate them,
        // and the cut must not leave a zero-thickness film where they met.
        val pair = Solid.union(Solid.cube(1.0, 1.0, 1.0), Solid.cube(1.0, 1.0, 1.0).translated(1.0, 0.0, 1.0))
        val inset = pair.insetFaces(d, except = [vec(0, 1, 0)])
        val pieces = inset.decompose()
        assertEquals(2, pieces.size, "two cubes, nothing else")
        for (piece in pieces) assertEquals((1 - 2 * d) * 1.0 * (1 - 2 * d), piece.volume, 1e-12)
    }

    private data class Probe(val part: Solid, val x: Double, val y: Double, val inward: Double)

    /** Where the z axis points after `rotated(9.09, 0.0, 9.09)`, read off a rotated needle. */
    private fun tiltedZ(): afield.math.linalg.Vec3 {
        val p = Solid.cube(1e-6, 1e-6, 2.0, center = true).rotated(9.09, 0.0, 9.09).toPlanarMesh().positions
        var far = 0; var best = -1.0
        for (v in 0 until p.size / 3) {
            val dx = p[v * 3] - p[0]; val dy = p[v * 3 + 1] - p[1]; val dz = p[v * 3 + 2] - p[2]
            val dist = dx * dx + dy * dy + dz * dz
            if (dist > best) { best = dist; far = v }
        }
        return vec(p[far * 3] - p[0], p[far * 3 + 1] - p[1], p[far * 3 + 2] - p[2])
    }
}
