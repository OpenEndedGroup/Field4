package afield.graphics

import afield.stdlib.Vec3
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The other half of the `e4b309379` change to [FLine.tessellate]: the ring sanitiser, and what the
 * tessellator does with rings that are not the well-behaved planar convex ones.
 *
 * The sanitiser's duplicate test went from `same2D` (equal x and y) to `same3D`. That reads like a
 * detail; it is not. It is the change that makes the dominant-axis projection *reachable* at all,
 * and the first test here is the proof.
 */
class TessellationRingRobustnessTest {

    private class Tri(val a: Vec3, val b: Vec3, val c: Vec3)

    private fun triangulate(f: FLine): List<Tri> {
        val out = ArrayList<Tri>()
        f.tessellate(4) { verts, index ->
            var i = 0
            while (i + 2 < index.size) {
                fun v(k: Int) = Vec3(verts[3 * k], verts[3 * k + 1], verts[3 * k + 2])
                out.add(Tri(v(index[i]), v(index[i + 1]), v(index[i + 2])))
                i += 3
            }
        }
        return out
    }

    private fun area(tris: List<Tri>): Float = tris.sumOf { t ->
        val ux = t.b.x - t.a.x; val uy = t.b.y - t.a.y; val uz = t.b.z - t.a.z
        val vx = t.c.x - t.a.x; val vy = t.c.y - t.a.y; val vz = t.c.z - t.a.z
        val cx = uy * vz - uz * vy
        val cy = uz * vx - ux * vz
        val cz = ux * vy - uy * vx
        (0.5 * sqrt((cx * cx + cy * cy + cz * cz).toDouble()))
    }.toFloat()

    private fun ringLine(pts: List<Vec3>): FLine {
        val f = FLine()
        f.moveTo(pts[0])
        pts.drop(1).forEach { f.lineTo(it) }
        return f.close()
    }

    /** The old predicate, reproduced here so the counterfactual can be measured rather than argued. */
    private fun sanitizeWith(pts: List<Vec3>, same: (Vec3, Vec3) -> Boolean): List<Vec3> {
        val out = ArrayList<Vec3>()
        pts.forEach { p -> if (out.isEmpty() || !same(out.last(), p)) out.add(p) }
        if (out.size >= 2 && same(out[0], out.last())) out.removeAt(out.lastIndex)
        return out
    }

    private val eps = 1e-5f
    private val same2D = { a: Vec3, b: Vec3 -> abs(a.x - b.x) <= eps && abs(a.y - b.y) <= eps }
    private val same3D = { a: Vec3, b: Vec3 ->
        abs(a.x - b.x) <= eps && abs(a.y - b.y) <= eps && abs(a.z - b.z) <= eps
    }

    /**
     * `same2D` -> `same3D` is not merely defensible, it is load-bearing.
     *
     * A wall in the XZ plane has *only two distinct (x, y) pairs* along its whole boundary. Under
     * the old `same2D` predicate the sanitiser collapsed it to two points and [FLine.tessellate]
     * dropped the ring before the projection ever ran. So the dominant-axis projection and the
     * three-dimensional duplicate test are one change, not two: neither is any use alone.
     */
    @Test
    fun theThreeDimensionalDuplicateTestIsWhatMakesAnXZWallTessellableAtAll() {
        val wall = listOf(
            Vec3(0f, 0f, 0f), Vec3(100f, 0f, 0f), Vec3(100f, 0f, 100f), Vec3(0f, 0f, 100f)
        )

        // the counterfactual: the pre-e4b309379 predicate leaves too few points to be a ring
        val old = sanitizeWith(wall, same2D)
        assertTrue(old.size < 3, "same2D collapsed the XZ wall to ${old.size} points, which is the point")
        assertEquals(4, sanitizeWith(wall, same3D).size, "same3D keeps all four corners")

        // and what the tessellator actually does now
        val tris = triangulate(ringLine(wall))
        assertEquals(2, tris.size, "an XZ wall should be two triangles")
        assertTrue(abs(area(tris) - 10000f) < 1f, "an XZ wall should have area 10000, got ${area(tris)}")
    }

    /** `same3D` still does the job it was there for: genuinely repeated points are dropped. */
    @Test
    fun genuinelyRepeatedPointsAreStillCollapsed() {
        val withRepeats = listOf(
            Vec3(0f, 0f, 0f), Vec3(0f, 0f, 0f),
            Vec3(100f, 0f, 0f),
            Vec3(100f, 100f, 0f), Vec3(100f, 100f, 0f), Vec3(100f, 100f, 0f),
            Vec3(0f, 100f, 0f),
            Vec3(0f, 0f, 0f)
        )
        assertEquals(4, sanitizeWith(withRepeats, same3D).size)
        val tris = triangulate(ringLine(withRepeats))
        assertEquals(2, tris.size, "a square with repeated corners is still two triangles")
        assertTrue(abs(area(tris) - 10000f) < 1f, "area was ${area(tris)}")
    }

    /**
     * The case `same3D` newly *admits*: two points that differ only on the axis the projection is
     * about to drop, so they are distinct in 3D but coincident in 2D. Earcut sees a zero-length
     * edge. This is the one place the change could plausibly have made things worse, so it is
     * measured: it must not crash, must not produce NaN, and must not lose the shape's area.
     */
    @Test
    fun pointsThatCoincideOnlyAfterProjectionDoNotBreakTheFill() {
        // a square in XY, with an extra vertex stacked in z directly above a corner
        val folded = listOf(
            Vec3(0f, 0f, 0f), Vec3(100f, 0f, 0f), Vec3(100f, 0f, 3f),
            Vec3(100f, 100f, 0f), Vec3(0f, 100f, 0f)
        )
        val tris = triangulate(ringLine(folded))
        assertTrue(tris.isNotEmpty(), "the folded ring produced no triangles at all")
        tris.forEach { t ->
            listOf(t.a, t.b, t.c).forEach { v ->
                assertTrue(
                    v.x.isFinite() && v.y.isFinite() && v.z.isFinite(),
                    "a folded ring produced a non-finite vertex $v"
                )
            }
        }
        val a = area(tris)
        assertTrue(abs(a - 10000f) < 200f, "the folded square lost or gained area: $a")
    }

    // -------------------------------------------------------------------------------- degeneracy

    /** A ring with no area at all: three collinear points. Nothing to fill, and nothing to crash. */
    @Test
    fun aCollinearRingFillsNothingAndSurvives() {
        val collinear = listOf(Vec3(0f, 0f, 0f), Vec3(50f, 0f, 0f), Vec3(100f, 0f, 0f))
        val tris = triangulate(ringLine(collinear))
        assertTrue(abs(area(tris)) < 1e-3f, "a collinear ring filled ${area(tris)} of area")
    }

    /** All-identical points: the sanitiser eats the whole ring before anything else sees it. */
    @Test
    fun aRingOfIdenticalPointsProducesNothing() {
        val same = listOf(Vec3(7f, 7f, 7f), Vec3(7f, 7f, 7f), Vec3(7f, 7f, 7f), Vec3(7f, 7f, 7f))
        assertEquals(emptyList(), triangulate(ringLine(same)).map { it.a })
    }

    /**
     * A ring lying along the z axis only — zero Newell normal, so no ring is ever "strongest" and
     * `projectionAxis` keeps its initial value. Pinned because that default is otherwise invisible:
     * it must be a no-op, not an exception.
     */
    @Test
    fun aDegenerateNormalFallsBackToTheXYProjectionWithoutThrowing() {
        val onTheZAxis = listOf(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 50f), Vec3(0f, 0f, 100f))
        val tris = triangulate(ringLine(onTheZAxis))
        assertTrue(abs(area(tris)) < 1e-3f, "a zero-normal ring filled ${area(tris)} of area")
    }

    /** A bowtie: self-intersecting, so "the area" is not well defined. It must still terminate. */
    @Test
    fun aSelfIntersectingRingTerminatesWithFiniteTriangles() {
        val bowtie = listOf(
            Vec3(0f, 0f, 0f), Vec3(100f, 100f, 0f), Vec3(100f, 0f, 0f), Vec3(0f, 100f, 0f)
        )
        val tris = triangulate(ringLine(bowtie))
        tris.forEach { t ->
            listOf(t.a, t.b, t.c).forEach { v ->
                assertTrue(v.x.isFinite() && v.y.isFinite() && v.z.isFinite(), "non-finite vertex $v")
            }
        }
        assertTrue(area(tris) <= 10001f, "a bowtie cannot fill more than its bounding box: ${area(tris)}")
    }

    /** A ring that touches itself at a single vertex without crossing. */
    @Test
    fun aRingThatTouchesItselfAtAVertexStillFills() {
        val pinched = listOf(
            Vec3(0f, 0f, 0f), Vec3(100f, 0f, 0f), Vec3(50f, 50f, 0f),
            Vec3(100f, 100f, 0f), Vec3(0f, 100f, 0f), Vec3(50f, 50f, 0f)
        )
        val tris = triangulate(ringLine(pinched))
        assertTrue(tris.isNotEmpty(), "a pinched ring produced nothing")
        assertTrue(area(tris) <= 10001f, "a pinched ring over-filled: ${area(tris)}")
    }

    // ------------------------------------------------------------------------------ mixed planes

    /** Build one FLine out of several closed rings, in the order given. */
    private fun lineOf(vararg rings: List<Vec3>): FLine {
        val f = FLine()
        rings.forEach { ring ->
            f.moveTo(ring[0])
            ring.drop(1).forEach { f.lineTo(it) }
            f.close()
        }
        return f
    }

    private fun triangulate(f: FLine, coplanarity: Coplanarity): List<Tri> {
        val out = ArrayList<Tri>()
        f.tessellate(4, coplanarity) { verts, index ->
            var i = 0
            while (i + 2 < index.size) {
                fun v(k: Int) = Vec3(verts[3 * k], verts[3 * k + 1], verts[3 * k + 2])
                out.add(Tri(v(index[i]), v(index[i + 1]), v(index[i + 2])))
                i += 3
            }
        }
        return out
    }

    /** Signed area of [tris] along [normal]: positive when they are wound with it. */
    private fun signedArea(tris: List<Tri>, normal: Vec3): Float {
        val l = sqrt(normal.x * normal.x + normal.y * normal.y + normal.z * normal.z)
        if (l == 0f) return 0f
        return tris.sumOf { t ->
            val ux = t.b.x - t.a.x; val uy = t.b.y - t.a.y; val uz = t.b.z - t.a.z
            val vx = t.c.x - t.a.x; val vy = t.c.y - t.a.y; val vz = t.c.z - t.a.z
            val cx = uy * vz - uz * vy
            val cy = uz * vx - ux * vz
            val cz = ux * vy - uy * vx
            (0.5 * (cx * normal.x + cy * normal.y + cz * normal.z) / l)
        }.toFloat()
    }

    /**
     * What used to be the documented limit of a *single* dominant axis, now the assertion that it is
     * gone: one FLine holding filled subpaths in two different planes fills **both**.
     *
     * This test was written the other way round on 2026-08-02 — it characterised the limit rather
     * than asserting an ideal, "so a future move to per-group projection has something to move". This
     * is that move. Per-plane grouping gives each coplanar group of subpaths its own projected frame,
     * so an XZ wall and an XY floor in one line no longer have to agree about an axis.
     *
     * The old behaviour is still measurable rather than merely described: [Coplanarity.SINGLE_PLANE]
     * puts every ring in one plane whatever its geometry says, which is exactly what the single-axis
     * code did, and under it the XY subpath still vanishes.
     */
    @Test
    fun oneFLineSpanningTwoPlanesNowFillsBoth() {
        val xzWall = listOf(
            Vec3(0f, 0f, 0f), Vec3(200f, 0f, 0f), Vec3(200f, 0f, 200f), Vec3(0f, 0f, 200f)
        )
        val xyFloor = listOf(
            Vec3(500f, 0f, 0f), Vec3(600f, 0f, 0f), Vec3(600f, 100f, 0f), Vec3(500f, 100f, 0f)
        )
        val f = lineOf(xzWall, xyFloor)

        val both = area(triangulate(f))
        assertTrue(abs(both - 50000f) < 1f, "both subpaths should fill: 40000 + 10000, got $both")

        // the counterfactual, measured: one plane for everything is the pre-2026-08-03 behaviour
        val forcedToOnePlane = area(triangulate(lineOf(xzWall, xyFloor), Coplanarity.SINGLE_PLANE))
        assertTrue(
            abs(forcedToOnePlane - 40000f) < 1f,
            "forced into one plane, only the dominant XZ subpath fills; got $forcedToOnePlane"
        )
    }

    /**
     * And each of those planes keeps its holes, and each faces the way its own ring is wound. A hole
     * is a per-plane decision now, so this is the case that would break if the grouping and the
     * nesting ever came apart.
     */
    @Test
    fun eachPlaneOfAMultiPlaneFLineKeepsItsOwnHolesAndItsOwnFacing() {
        val wall = listOf(
            Vec3(0f, 0f, 0f), Vec3(200f, 0f, 0f), Vec3(200f, 0f, 200f), Vec3(0f, 0f, 200f)
        )
        val wallHole = listOf(
            Vec3(50f, 0f, 50f), Vec3(50f, 0f, 150f), Vec3(150f, 0f, 150f), Vec3(150f, 0f, 50f)
        )
        val floor = listOf(
            Vec3(0f, 0f, 400f), Vec3(200f, 0f, 400f), Vec3(200f, 200f, 400f), Vec3(0f, 200f, 400f)
        )
        val floorHole = listOf(
            Vec3(50f, 50f, 400f), Vec3(50f, 150f, 400f), Vec3(150f, 150f, 400f), Vec3(150f, 50f, 400f)
        )

        val tris = triangulate(lineOf(wall, wallHole, floor, floorHole))
        val total = area(tris)
        assertTrue(
            abs(total - 60000f) < 1f,
            "two 200x200 faces less a 100x100 hole each is 60000, got $total"
        )

        // and the facing, split back out by which plane each triangle landed in
        val onTheWall = tris.filter { abs(it.a.y) < 1e-3f && abs(it.b.y) < 1e-3f && abs(it.c.y) < 1e-3f }
        val onTheFloor = tris.filter { abs(it.a.z - 400f) < 1e-3f }
        assertTrue(onTheWall.isNotEmpty() && onTheFloor.isNotEmpty(), "one of the two planes vanished")
        assertTrue(
            signedArea(onTheWall, newell(wall)) > 29000f,
            "the wall faces its own normal: ${signedArea(onTheWall, newell(wall))}"
        )
        assertTrue(
            signedArea(onTheFloor, newell(floor)) > 29000f,
            "the floor faces its own normal: ${signedArea(onTheFloor, newell(floor))}"
        )
    }

    /**
     * The decision worth defending. A ring nested inside another *in projection* but lying in a
     * different plane is not a hole — it is a coincidence of the axis that happened to be chosen.
     *
     * Here a tilted parallelogram sits inside an XY square when both are flattened onto `(x, y)`.
     * Under one shared frame it was subtracted, and the square came back with a hole in it that
     * nothing in the geometry justifies. Scoped to a plane, the square fills whole and the
     * parallelogram fills as the separate surface it is.
     *
     * The counterfactual is worth measuring for a second reason. Forced into one plane, the result is
     * not merely the square-with-a-hole one might picture: the hole *boundary* is at z 0 and z 40, so
     * the triangles that bridge out to it climb out of the square's plane, and the "area" comes back
     * as 12276 for a figure whose flat interpretation is 8400. The old shared frame did not just
     * misclassify the ring, it emitted a surface that lies in no plane at all.
     */
    @Test
    fun aRingInADifferentPlaneIsNotAHoleEvenWhenItProjectsInsideOne() {
        val square = listOf(
            Vec3(0f, 0f, 0f), Vec3(100f, 0f, 0f), Vec3(100f, 100f, 0f), Vec3(0f, 100f, 0f)
        )
        // planar, tilted about x, and its (x, y) shadow is the 40x40 square at (30, 30)
        val tilted = listOf(
            Vec3(30f, 30f, 0f), Vec3(70f, 30f, 0f), Vec3(70f, 70f, 40f), Vec3(30f, 70f, 40f)
        )
        val tiltedArea = 40f * sqrt(40f * 40f + 40f * 40f)
        val rings = listOf(square, tilted)

        assertEquals(
            listOf(0 to emptyList<Int>(), 1 to emptyList()),
            PlanarTessellation.tessellate(rings).map { it.outer to it.holes },
            "the tilted ring is a separate surface, not a hole"
        )
        val separate = area(triangulate(lineOf(square, tilted)))
        assertTrue(
            abs(separate - (10000f + tiltedArea)) < 2f,
            "the square should fill whole (10000) and the tilted ring separately ($tiltedArea); got $separate"
        )

        // the counterfactual: one frame for everything, which is what the code did before
        assertEquals(
            listOf(0 to listOf(1)),
            PlanarTessellation.tessellate(rings, Coplanarity.SINGLE_PLANE).map { it.outer to it.holes },
            "forced into one plane the tilted ring should be subtracted as a hole"
        )
        val forcedToOnePlane = area(triangulate(lineOf(square, tilted), Coplanarity.SINGLE_PLANE))
        assertTrue(
            forcedToOnePlane > 12000f,
            "the forced surface is not planar and over-measures its own area; got $forcedToOnePlane"
        )
    }

    /**
     * A ring with no normal is not allowed to decide anybody's plane. Listed first — where a naive
     * greedy clustering would let it seed one — it must still leave the real geometry that follows
     * with its own frame.
     */
    @Test
    fun aRingWithNoNormalDoesNotSeedAPlane() {
        val collinear = listOf(Vec3(300f, 0f, 0f), Vec3(350f, 0f, 0f), Vec3(400f, 0f, 0f))
        val wall = listOf(
            Vec3(0f, 0f, 0f), Vec3(200f, 0f, 0f), Vec3(200f, 0f, 200f), Vec3(0f, 0f, 200f)
        )
        val tris = triangulate(lineOf(collinear, wall))
        assertEquals(2, tris.size, "the XZ wall is two triangles, and the collinear ring is none")
        assertTrue(abs(area(tris) - 40000f) < 1f, "area was ${area(tris)}")
    }

    private fun newell(ring: List<Vec3>): Vec3 = PlanarTessellation.newellNormal(ring)
}
