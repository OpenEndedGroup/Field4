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

    /**
     * The documented limit of a *single* dominant axis: one FLine holding filled subpaths in two
     * different planes. Only one axis is chosen, from the strongest ring, and every subpath not
     * facing that way projects onto a line and vanishes.
     *
     * This is a characterisation, not an aspiration — it records where the current design stops so
     * that a future change to per-group projection has something to move. It is *not* a regression
     * from `e4b309379`: before it, the axis was permanently (x, y) and the XZ subpath vanished just
     * the same. What changed is only *which* of the two survives.
     */
    @Test
    fun oneFLineSpanningTwoPlanesFillsOnlyTheDominantOne() {
        val f = FLine()
        // XZ, area 40000 -- the stronger ring, so it wins the axis
        f.moveTo(Vec3(0f, 0f, 0f)).lineTo(Vec3(200f, 0f, 0f))
            .lineTo(Vec3(200f, 0f, 200f)).lineTo(Vec3(0f, 0f, 200f)).close()
        // XY, area 10000 -- projects onto a line under the XZ axis and contributes nothing
        f.moveTo(Vec3(500f, 0f, 0f)).lineTo(Vec3(600f, 0f, 0f))
            .lineTo(Vec3(600f, 100f, 0f)).lineTo(Vec3(500f, 100f, 0f)).close()

        val a = area(triangulate(f))
        assertTrue(
            abs(a - 40000f) < 1f,
            "expected only the dominant XZ subpath (40000) to fill; got $a. If this now reports " +
                    "50000, per-subpath projection has been implemented and this test should become " +
                    "an assertion of that instead."
        )
    }
}
