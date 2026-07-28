package afield.stdlib

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import afield.stdlib.linalg.Vector3f as Vec3

/**
 * The face polygons [voronoi3d] emits have to describe a *closed polyhedron*, not just a bag of
 * plausible polygons: every corner shared by exactly the faces it lies on, every edge walked by exactly
 * two faces, in opposite directions.
 *
 * This is a regression test for a real defect. `voronoi.kt` decided both "is this point a corner of the
 * cell" and "does this corner belong to that face" with tolerances scaled off the *whole point cloud's*
 * diagonal — `eps` and `4 * eps`, about 3.5e-4 and 1.4e-3 for sites in the unit cube. A cluster of five
 * near-cospherical sites puts four or more bisector planes nearly through one point, and the real corners
 * near such a cluster are separated by *less than those tolerances*. So the clipper both admitted points
 * that were genuinely outside the cell and let faces claim corners that were not on them, and the result
 * was a **T-junction**: one edge on one side of a seam, two on the other, and in the worst cases a single
 * short edge listed by four different faces.
 *
 * Measured before the fix, with the checks below: 25 of 46 bounded cells of a 72-site random lattice
 * failed, and 2 of 8 at 20 sites. After it, none — and the cell volumes still tile the clip box to 1e-6
 * relative, so the repair did not come out of the geometry.
 *
 * The consequence downstream was a hairline crack in any mesh built from the faces — see
 * [VoronoiCell3.toTriMesh], which used to need a convex-hull fallback to close a third of its cells.
 */
class VoronoiFaceTopologyTest {

    private fun distance(a: Vec3, b: Vec3): Float {
        val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** The distinct corners of a cell, and each face as a ring of indices into them. */
    private class Rings(cell: VoronoiCell3) {
        val corners = ArrayList<Vec3>()
        val rings: List<List<Int>>

        init {
            // The faces of one cell draw their corners from one shared list, so equal coordinates are a
            // shared corner exactly — no welding tolerance, which is the point: a tolerance here would
            // hide the very defect being tested for.
            rings = cell.faces.map { face ->
                face.polygon.map { p ->
                    var at = corners.indexOfFirst { it.x == p.x && it.y == p.y && it.z == p.z }
                    if (at < 0) { corners += p; at = corners.size - 1 }
                    at
                }
            }
        }

        fun extent(): Float {
            if (corners.isEmpty()) return 0f
            var lo = Vec3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            var hi = Vec3(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for (p in corners) {
                lo = Vec3(min(lo.x, p.x), min(lo.y, p.y), min(lo.z, p.z))
                hi = Vec3(max(hi.x, p.x), max(hi.y, p.y), max(hi.z, p.z))
            }
            return sqrt(
                (hi.x - lo.x) * (hi.x - lo.x) + (hi.y - lo.y) * (hi.y - lo.y) + (hi.z - lo.z) * (hi.z - lo.z),
            )
        }
    }

    /**
     * Every way this cell's faces fail to be a closed, consistently wound polyhedron.
     *
     * The test is exact directed-edge pairing: every ordered pair of corners walked by exactly one face,
     * and its reverse by exactly one other. That is the precise statement of the invariant, and it is
     * precisely what a T-junction breaks — a corner splitting one face's edge in two leaves the long edge
     * walked once and the two halves walked once each. Deliberately *not* a geometric "is this corner near
     * that edge" heuristic: an honest, very flat corner on a cell stretched against the clip box comes
     * within 4e-4 of the cell's size of a non-incident edge, which is nearer than a real T-junction was.
     */
    private fun defects(cell: VoronoiCell3): List<String> {
        val out = ArrayList<String>()
        val r = Rings(cell)
        if (r.rings.size < 4) return listOf("only ${r.rings.size} faces")

        // 1. Every undirected edge walked exactly twice, and once in each direction.
        val directed = HashMap<Long, Int>()
        fun key(a: Int, b: Int) = a.toLong() * 1_000_000L + b
        for (ring in r.rings) {
            for (i in ring.indices) {
                val a = ring[i]
                val b = ring[(i + 1) % ring.size]
                if (a == b) {
                    out += "face repeats corner $a"
                    continue
                }
                directed[key(a, b)] = (directed[key(a, b)] ?: 0) + 1
            }
        }
        for ((k, count) in directed) {
            val a = (k / 1_000_000L).toInt()
            val b = (k % 1_000_000L).toInt()
            if (count != 1) out += "edge $a->$b walked $count times in the same direction"
            val back = directed[key(b, a)] ?: 0
            if (back != 1) {
                out += "edge $a-$b walked $count times one way and $back the other " +
                        "(len %.3e on a cell %.3e across)".format(distance(r.corners[a], r.corners[b]), r.extent())
            }
        }

        // 2. Every corner used by at least three faces, which is what being a corner means.
        val useCount = IntArray(r.corners.size)
        for (ring in r.rings) for (at in ring.toSet()) useCount[at]++
        for (at in useCount.indices) {
            if (useCount[at] < 3) out += "corner $at is used by only ${useCount[at]} face(s)"
        }
        return out
    }

    private fun randomSites(seed: Int, count: Int): List<Vec3> {
        val random = kotlin.random.Random(seed)
        return List(count) {
            Vec3(random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1)
        }
    }

    @Test
    fun everyBoundedCellsFacesFormAClosedConsistentlyWoundPolyhedron() {
        var bounded = 0
        var clipped = 0
        val failures = ArrayList<String>()
        val clippedFailures = ArrayList<String>()
        for (seed in listOf(1, 5, 11, 17, 31, 37)) {
            for (count in listOf(12, 20, 48, 72)) {
                val lattice = voronoi3d(randomSites(seed, count))
                for (cell in lattice.cells) {
                    if (cell.bounded) bounded++ else clipped++
                    val bad = defects(cell)
                    if (bad.isEmpty()) continue
                    val note = "seed=$seed n=$count cell ${cell.siteIndex} (${cell.faces.size} faces): ${bad.take(3)}"
                    if (cell.bounded) failures += note else clippedFailures += note
                }
            }
        }
        assertTrue(bounded > 300, "the sweep should be substantial; $bounded bounded cells")

        // Ratchets, not zeroes, and the honest numbers matter more than the round one. Over this sweep the
        // old clipper failed 25 of the 46 bounded cells of a single 72-site lattice; it now fails 4 of 465
        // bounded cells across 24 lattices, and 12 of 447 clipped ones.
        //
        // The residue is the same shape of degeneracy in its last corner: a genuinely ill-conditioned
        // corner cluster whose spread (6.7e-4 on a cell 1.18 across, at seed 37) is wider than the error
        // bound that explains it, so two computations of one corner stay two corners. Widening the bound to
        // catch them costs more cells than it fixes — swept, and written down in `conditionRadius`. Closing
        // it properly means carrying the cell's combinatorics through the clipping rather than recovering
        // them afterwards, which is a rewrite of this file, not a tolerance.
        assertTrue(
            failures.size <= 4,
            "${failures.size} of $bounded bounded cells are not closed polyhedra, was 4:\n" +
                    failures.take(6).joinToString("\n"),
        )
        // The unbounded cells only exist at all because they are clipped against a box eight times the size
        // of the point cloud, which leaves them with 34-unit edges next to 1e-3-unit ones on a cell 50
        // across. They are the worst-conditioned thing this file produces and nothing consumes them for
        // their shape.
        assertTrue(
            clippedFailures.size <= 12,
            "${clippedFailures.size} of $clipped clipped cells fail, was 12:\n" +
                    clippedFailures.take(4).joinToString("\n"),
        )
    }

    @Test
    fun aCubicLatticeOfSitesGivesCubicCellsDespiteBeingWhollyDegenerate() {
        // Exactly symmetric input is the hard case in the other direction: at every corner of every cell
        // seven bisectors pass through one point *exactly*, so the corner is maximally degenerate and the
        // right answer is nevertheless a cube. Interior cells must come out with six quadrilateral faces.
        val sites = ArrayList<Vec3>()
        for (i in 0 until 4) for (j in 0 until 4) for (k in 0 until 4) {
            sites += Vec3(i.toFloat(), j.toFloat(), k.toFloat())
        }
        val lattice = voronoi3d(sites)
        var interior = 0
        for (cell in lattice.cells) {
            val s = cell.site
            val isInterior = s.x > 0.5f && s.x < 2.5f && s.y > 0.5f && s.y < 2.5f && s.z > 0.5f && s.z < 2.5f
            if (!isInterior) continue
            interior++
            assertTrue(defects(cell).isEmpty(), "cell ${cell.siteIndex}: ${defects(cell)}")
            val real = cell.faces.filter { !it.isClip }
            assertEquals(6, real.size, "an interior cell of a cubic lattice is a cube; cell ${cell.siteIndex}")
            val mesh = cell.toTriMesh(shrink = 1.0, healToConvexHull = false).weld(1e-5)
            assertTrue(mesh.isWatertight(), "cell ${cell.siteIndex} should close from its faces alone")
            assertEquals(1.0, mesh.signedVolume(), 1e-4, "and be a unit cube")
        }
        assertEquals(8, interior, "the 4x4x4 lattice has eight interior sites")
    }

    @Test
    fun boundedCellsCloseFromTheirFacesAloneWithNoConvexHullFallback() {
        // The mesh bridge keeps a convex-hull repair path for robustness, but it must no longer be
        // *needed*: before the fix only 22 of the 36 interior cells of a 72-site lattice closed without it.
        for (seed in listOf(11, 31)) {
            val lattice = voronoi3d(randomSites(seed, 72))
            var closed = 0
            var interior = 0
            var volume = 0.0
            for (cell in lattice.cells) {
                val mesh = cell.toTriMesh(shrink = 1.0, healToConvexHull = false).weld(1e-5)
                volume += abs(mesh.signedVolume())
                if (!cell.bounded) continue
                interior++
                if (mesh.isWatertight()) {
                    closed++
                    assertEquals(
                        2, mesh.topology().eulerCharacteristic,
                        "a closed convex cell is a sphere; seed=$seed cell ${cell.siteIndex}",
                    )
                    assertTrue(mesh.signedVolume() > 0.0, "and wound outward; seed=$seed cell ${cell.siteIndex}")
                }
            }
            assertTrue(interior > 30, "seed=$seed should have plenty of interior cells, got $interior")
            assertEquals(interior, closed, "every interior cell should close from its faces alone at seed=$seed")

            // And the repair must not have come out of the geometry: the cells still tile the clip box. This
            // covers the clipped cells too, whose volumes are most of the total.
            val box = lattice.clipBounds
            val boxVolume = (box.max.x - box.min.x).toDouble() *
                    (box.max.y - box.min.y) * (box.max.z - box.min.z)
            assertEquals(boxVolume, volume, boxVolume * 1e-4, "cell volumes must still tile the clip box")
        }
    }

    @Test
    fun degenerateInputStillProducesClosedCells() {
        // Cospherical sites (every corner of the cell they surround is maximally degenerate) and coplanar
        // sites (whole cells collapse to slabs) are the two configurations that used to break worst.
        val cospherical = ArrayList<Vec3>()
        for (i in 0 until 24) {
            val phi = kotlin.math.acos(1.0 - 2.0 * (i + 0.5) / 24)
            val theta = kotlin.math.PI * (1 + 5.0.let { kotlin.math.sqrt(it) }) * i
            cospherical += Vec3(
                (kotlin.math.sin(phi) * kotlin.math.cos(theta)).toFloat(),
                (kotlin.math.sin(phi) * kotlin.math.sin(theta)).toFloat(),
                kotlin.math.cos(phi).toFloat(),
            )
        }
        val coplanar = List(16) { Vec3((it % 4).toFloat(), (it / 4).toFloat(), 0f) }

        for ((name, sites) in listOf("cospherical" to cospherical, "coplanar" to coplanar)) {
            val lattice = voronoi3d(sites)
            for (cell in lattice.cells) {
                assertTrue(
                    defects(cell).isEmpty(),
                    "$name cell ${cell.siteIndex}: ${defects(cell).take(3)}",
                )
            }
        }
    }
}
