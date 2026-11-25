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
        org.junit.Assume.assumeTrue("native library not available: ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
        val panel = Solid.cube(4.0, 0.1, 3.0)
        val inset = panel.insetFaces(d, except = [vec(0, 1, 0)])
        assertEquals((4.0 - 2 * d) * 0.1 * (3.0 - 2 * d), inset.volume, 1e-12)
        assertEquals(0.0, inset.bounds.min.y.toDouble(), 1e-7, "the bottom stays")
        assertEquals(0.1, inset.bounds.max.y.toDouble(), 1e-7, "and so does the top")
        assertEquals(1, inset.decompose().size)
    }

    @Test
    fun `with nothing kept every face moves`() {
        org.junit.Assume.assumeTrue("native library not available: ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
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
        org.junit.Assume.assumeTrue("native library not available: ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
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
        org.junit.Assume.assumeTrue("native library not available: ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
        val prism = Section.ofPolygon(outline).extruded(0.5)
        val flat = prism.insetFaces(d, except = [vec(0, 0, 1)])
        val inset = prism.rotated(9.09, 0.0, 9.09).insetFaces(d, except = [tiltedZ()])
        assertEquals(flat.volume, inset.volume, 1e-9 * flat.volume)
    }

    @Test
    fun `two boxes that touched end up twice the inset apart`() {
        org.junit.Assume.assumeTrue("native library not available: ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
        val up = [vec(0, 1, 0)]
        val a = Solid.cube(1.0, 0.1, 1.0).insetFaces(d, except = up)
        val b = Solid.cube(1.0, 0.1, 1.0).translated(1.0, 0.0, 0.0).insetFaces(d, except = up)
        assertEquals(2 * d, a.minGap(b, searchLength = 1.0), 1e-9)
    }

    @Test
    fun `a lap joint keeps bearing on its step and opens at its risers`() {
        org.junit.Assume.assumeTrue("native library not available: ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
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
        org.junit.Assume.assumeTrue("native library not available: ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
        // One solid, two cubes meeting along a single edge: any gap at all has to separate them,
        // and the cut must not leave a zero-thickness film where they met.
        val pair = Solid.union(Solid.cube(1.0, 1.0, 1.0), Solid.cube(1.0, 1.0, 1.0).translated(1.0, 0.0, 1.0))
        val inset = pair.insetFaces(d, except = [vec(0, 1, 0)])
        val pieces = inset.decompose()
        assertEquals(2, pieces.size, "two cubes, nothing else")
        for (piece in pieces) assertEquals((1 - 2 * d) * 1.0 * (1 - 2 * d), piece.volume, 1e-12)
    }

    @Test
    fun `kept planes keep only the faces in them, not every parallel face`() {
        org.junit.Assume.assumeTrue("native library not available: ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
        // An L in x-y run along z: bottom at y = 0, top at y = 0.1 over x < 1, a step at y = 0.05 over x > 1.
        val stepped = Solid.cube(2.0, 0.1, 1.0) - Solid.cube(1.0, 0.05, 1.0).translated(1.0, 0.05, 0.0)
        val parallel = stepped.insetFaces(d, except = [vec(0, 1, 0)])
        assertEquals(((2 - 2 * d) * 0.05 + (1 - 2 * d) * 0.05) * (1 - 2 * d), parallel.volume, 1e-8, "parallel keeps the step")
        val coplanar = stepped.insetFaces(d, exceptOn = [
            FacePlane(vec(0, 0, 0), vec(0, 1, 0)), FacePlane(vec(0.5, 0.1, 0.5), vec(0, 1, 0)),
        ])
        // The step is parallel to both planes and in neither, so it drops by d like a side face.
        assertEquals(((2 - 2 * d) * (0.05 - d) + (1 - 2 * d) * (0.05 + d)) * (1 - 2 * d), coplanar.volume, 1e-8)
        assertTrue(!coplanar.contains(1.5, 0.05 - 0.5 * d, 0.5) && coplanar.contains(1.5, 0.05 - 1.5 * d, 0.5), "the step moved by d")
        assertEquals(0.0, coplanar.bounds.min.y.toDouble(), 1e-7, "the bottom stays")
        assertEquals(0.1, coplanar.bounds.max.y.toDouble(), 1e-7, "and so does the top")
    }

    @Test
    fun `a kept plane holds its face whichever way its normal points`() {
        org.junit.Assume.assumeTrue("native library not available: ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
        val cube = Solid.cube(1.0, 1.0, 1.0)
        // The cube's top faces up; the plane is given facing down. Its bottom is parallel, and moves.
        val inset = cube.insetFaces(d, exceptOn = [FacePlane(vec(0, 1, 0), vec(0, -1, 0))])
        assertEquals((1 - 2 * d) * (1 - d) * (1 - 2 * d), inset.volume, 1e-12)
        assertEquals(1.0, inset.bounds.max.y.toDouble(), 1e-7)
        assertEquals(d, inset.bounds.min.y.toDouble(), 1e-7)
    }

    @Test
    fun `a crack inside a solid stays closed`() {
        org.junit.Assume.assumeTrue("native library not available: ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
        // Two halves of one panel that meet only up to rounding, as cells computed separately do: the
        // union keeps both faces at x = 1, back to back, with its own material either side. (A
        // compose of exactly touching halves merges them, so it would not test this.)
        val half = Solid.cube(1.0, 0.1, 1.0)
        val cracked = Solid.union(half, half.translated(1.0 + 1e-12, 0.0, 0.0))
        assertEquals(2, cracked.decompose().size, "the fixture holds a crack")
        val inset = cracked.insetFaces(d, except = [vec(0, 1, 0)])
        // As if it were the whole panel: only the outer edges move.
        assertEquals((2 - 2 * d) * 0.1 * (1 - 2 * d), inset.volume, 1e-12)
        for (x in [1.0 - 0.5 * d, 1.0 + 0.5 * d]) assertTrue(inset.contains(x, 0.05, 0.5), "no slot opened at the crack, x = $x")
        assertTrue(inset.contains(1.0, 0.05, 1.5 * d) && !inset.contains(1.0, 0.05, 0.5 * d), "the edge across the crack moved by d")
    }

    @Test
    fun `a face that is a crack over only part of it moves only where it is outside`() {
        org.junit.Assume.assumeTrue("native library not available: ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
        // An L of panel, from two cells that meet up to rounding at x = 1: the long cell's +x face is
        // a crack for z < 1, against the short cell, and the outside of the L's inner corner beyond.
        val long = Solid.cube(1.0, 0.1, 2.0)
        val short = Solid.cube(1.0, 0.1, 1.0).translated(1.0 + 1e-12, 0.0, 0.0)
        val l = Solid.union(long, short)
        assertEquals(2, l.decompose().size, "the fixture holds a crack")
        val inset = l.insetFaces(d, except = [vec(0, 1, 0)])
        // The L's outline offset inward by d, mitered: two overlapping rectangles.
        val area = (2 - 2 * d) * (1 - 2 * d) + (1 - 2 * d) * (2 - 2 * d) - (1 - 2 * d) * (1 - 2 * d)
        assertEquals(area * 0.1, inset.volume, 1e-9)
        for (x in [1.0 - 0.5 * d, 1.0 + 0.5 * d]) assertTrue(inset.contains(x, 0.05, 0.5), "no slot at the crack, x = $x")
        assertTrue(!inset.contains(1.0 - 0.5 * d, 0.05, 1.5) && inset.contains(1.0 - 1.5 * d, 0.05, 1.5), "the outside part moved by d")
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

    // A square pyramid, base +-5 on z = 0 and apex (0, 0, 10): four planes meet at the apex.
    private val k = 1 / kotlin.math.sqrt(5.0)
    private val sides = mapOf(
        "+x" to doubleArrayOf(2 * k, 0.0, k), "-x" to doubleArrayOf(-2 * k, 0.0, k),
        "+y" to doubleArrayOf(0.0, 2 * k, k), "-y" to doubleArrayOf(0.0, -2 * k, k),
    )
    private fun pyramid() = Solid.hullOfPointsD(doubleArrayOf(-5.0, -5.0, 0.0, 5.0, -5.0, 0.0, 5.0, 5.0, 0.0, -5.0, 5.0, 0.0, 0.0, 0.0, 10.0))

    /**
     * The solid where n . x <= c for every plane: what moving planes of a convex part has to give.
     * Cut from a cube only a little larger than the parts here: from a 200 cube, planes came out
     * about 1e-9 of its size off, a film 2.4e-7 thick over the walls a test compares.
     */
    private fun halfSpaces(planes: List<Pair<DoubleArray, Double>>): Solid {
        var solid = Solid.cube(30.0, center = true)
        for ((n, c) in planes) solid = solid.trimByPlane(-n[0], -n[1], -n[2], -c).also { solid.close() }
        return solid
    }

    private fun moving(normals: Collection<DoubleArray>): (DoubleArray) -> Boolean =
        { plane -> normals.any { n -> n[0] * plane[0] + n[1] * plane[1] + n[2] * plane[2] > 1 - 1e-9 } }

    @Test
    fun `a pyramid's apex opens into a ridge where its sides stop meeting at one point`() {
        org.junit.Assume.assumeTrue("native library not available: ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
        // One side, or two opposite ones, moved: the four planes no longer meet at one point, and the
        // apex becomes an edge. Two adjacent sides, or all four, still meet at one and the apex moves.
        // A least-squares apex left 296.07 and 258.80 where these give 277.66 and 223.39.
        for (moved in [setOf("+x"), setOf("+x", "-x"), setOf("+x", "+y"), sides.keys]) {
            val inset = pyramid().insetFaces(1.0, moving(moved.map { sides.getValue(it) }))
            val exact = halfSpaces(sides.map { (name, n) -> n to 10 * k - if (name in moved) 1.0 else 0.0 } + (doubleArrayOf(0.0, 0.0, -1.0) to 0.0))
            assertEquals(exact.volume, inset.volume, 1e-6 * exact.volume, "volume with $moved moved")
            val extra = inset - exact
            val missing = exact - inset
            // Not just the same volume: the same solid, up to the hair (1e-7 of the part's size) the
            // cut is pushed across kept faces so no skin is left on them. An inset a tenth of the
            // part slides a wide strip over the base, so that comes to 3.2e-5 with two sides moved.
            val hair = 1e-6 * exact.volume
            assertTrue(extra.volume < hair && missing.volume < hair, "same solid with $moved moved: ${extra.volume} extra in ${extra.bounds}, ${missing.volume} missing in ${missing.bounds}")
        }
    }

    @Test
    fun `a pyramid-shaped pit grows exactly when one of its walls moves`() {
        org.junit.Assume.assumeTrue("native library not available: ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
        // A block with a pit whose walls meet at a point (0, 0, 2) below its top: the material there
        // is the outside of a convex cone. Moving one wall back opens the pit's bottom into an edge.
        val block = Solid.cube(20.0, 20.0, 20.0, center = true)
        val wall = 1 / kotlin.math.sqrt(89.0)
        // The pit's own outward normals, and the plane of each through (0, 0, 2); its top is far above.
        val pit = [doubleArrayOf(8 * wall, 0.0, -5 * wall), doubleArrayOf(-8 * wall, 0.0, -5 * wall), doubleArrayOf(0.0, 8 * wall, -5 * wall), doubleArrayOf(0.0, -8 * wall, -5 * wall)]
        fun pitOf(grow: Double) = halfSpaces(pit.mapIndexed { i, n -> n to -10 * wall + if (i == 0) grow else 0.0 } + (doubleArrayOf(0.0, 0.0, 1.0) to 14.0))
        val part = block - pitOf(0.0)
        val inset = part.insetFaces(0.5, moving([pit[0].map { -it }.toDoubleArray()]))
        val exact = block - pitOf(0.5)
        assertEquals(exact.volume, inset.volume, 1e-6 * exact.volume)
        val extra = inset - exact
        val missing = exact - inset
        val hair = 1e-6 * exact.volume
        assertTrue(extra.volume < hair && missing.volume < hair, "same solid: ${extra.volume} extra in ${extra.bounds}, ${missing.volume} missing in ${missing.bounds}")
    }
}
