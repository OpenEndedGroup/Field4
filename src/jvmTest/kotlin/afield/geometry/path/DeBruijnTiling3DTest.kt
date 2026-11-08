package afield.geometry.path

import afield.math.linalg.Vector3d
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks that [deBruijnTiling3D] really fills space: face to face, no overlaps, closed cells, and
 * for the icosahedral star Ammann's two rhombohedra in the golden ratio.
 */
class DeBruijnTiling3DTest {

    private val phi = (1 + sqrt(5.0)) / 2

    private fun length(v: Vector3d) = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)

    /** Face sharing, closed cells and no overlaps; [interior] is the crossing radius to hold faces shared. */
    private fun checkTiling(t: DeBruijnTiling3D, interior: Double) {
        val cells = t.cells
        assertTrue(cells.size > 40, "expected many cells, got ${cells.size}")

        for (c in cells) {
            // Every cell is a closed parallelepiped: its outward faces enclose its volume.
            var signed = 0.0
            for (f in c.faces()) {
                val p = f.map { c.corners[it] }
                // Divergence theorem over the two triangles of each quad.
                for ((i, j) in listOf(1 to 2, 2 to 3)) {
                    val a = p[0]; val b = p[i]; val d = p[j]
                    signed += (a.x * (b.y * d.z - b.z * d.y) - a.y * (b.x * d.z - b.z * d.x) + a.z * (b.x * d.y - b.y * d.x)) / 6
                }
            }
            assertEquals(c.volume, signed, 1e-6 * c.volume, "cell faces do not enclose its volume, or are wound inward")
            assertTrue(c.volume > 1e-6)
        }

        // No face belongs to more than two cells; every face of an interior cell belongs to two.
        val byFace = HashMap<List<List<Int>>, Int>()
        for (c in cells) for (f in c.faces()) byFace.merge(c.faceKey(f), 1, Int::plus)
        assertTrue(byFace.values.all { it <= 2 }, "a face is shared by more than two cells")
        val inner = cells.filter { length(it.crossing) < interior }
        assertTrue(inner.size > 10, "only ${inner.size} interior cells")
        for (c in inner) for (f in c.faces()) assertEquals(2, byFace[c.faceKey(f)], "an interior face has no neighbour")

        // Shared vertices are bit-identical: one lift, one position.
        val at = HashMap<List<Int>, Vector3d>()
        for (c in cells) for (k in 0 until 8) {
            val p = c.corners[k]
            val seen = at.getOrPut(c.lift(k).toList()) { p }
            assertTrue(seen.x == p.x && seen.y == p.y && seen.z == p.z, "a shared vertex was computed twice differently")
        }

        // No cell's centre, or any point 90% of the way to a corner, lies inside another cell.
        val edge = t.cells.maxOf { c -> listOf(1, 2, 4).maxOf { length(Vector3d(c.corners[it]).sub(c.corners[0])) } }
        val cell = 2 * edge
        fun key(p: Vector3d) = Triple(floor(p.x / cell).toInt(), floor(p.y / cell).toInt(), floor(p.z / cell).toInt())
        val grid = HashMap<Triple<Int, Int, Int>, MutableList<Int>>()
        cells.forEachIndexed { i, c -> grid.getOrPut(key(c.center)) { mutableListOf() }.add(i) }
        cells.forEachIndexed { i, c ->
            val probes = listOf(c.center) + c.corners.map { k ->
                Vector3d(c.center.x + 0.9 * (k.x - c.center.x), c.center.y + 0.9 * (k.y - c.center.y), c.center.z + 0.9 * (k.z - c.center.z))
            }
            val (gx, gy, gz) = key(c.center)
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) for (j in grid[Triple(gx + dx, gy + dy, gz + dz)].orEmpty()) {
                if (j == i) continue
                for (p in probes) assertTrue(!cells[j].contains(p, 1e-7), "cell $i overlaps cell $j")
            }
        }
    }

    @Test
    fun icosahedralIsFaceToFaceWithAmmannRhombohedraInTheGoldenRatio() {
        val t = deBruijnTiling3D(radius = 4.0, scale = 1.0)
        checkTiling(t, 2.5)
        val counts = t.shapeCounts()
        assertEquals(setOf(1, 2), counts.keys, "only the prolate and oblate rhombohedra")
        // Both have unit edges and golden-rhombus faces; their volumes are in the golden ratio.
        assertEquals(phi, t.shapeVolumes[0] / t.shapeVolumes[1], 1e-12)
        for (c in t.cells) for (b in listOf(1, 2, 4)) assertEquals(1.0, length(Vector3d(c.corners[b]).sub(c.corners[0])), 1e-12)
        val ratio = counts.getValue(1).toDouble() / counts.getValue(2)
        assertEquals(phi, ratio, 0.05, "prolate/oblate = $ratio")
    }

    @Test
    fun goldenRatioImprovesWithRadius() {
        fun err(r: Double) = deBruijnTiling3D(radius = r).shapeCounts().let { abs(it.getValue(1).toDouble() / it.getValue(2) - phi) }
        assertTrue(err(7.0) < 0.01, "error at radius 7 is ${err(7.0)}")
    }

    /**
     * The volume the cells fill, per unit volume of grid space, is det(sum e eT) by Cauchy-Binet:
     * 2^3 = 8 for six icosahedral axes, whose sum e eT is 2 I. A gap or an overlap would show here.
     */
    @Test
    fun cellsFillEightTimesTheCrossingBall() {
        val r = 6.0
        val t = deBruijnTiling3D(radius = r, scale = 1.0)
        val ball = 4.0 / 3.0 * PI * r * r * r
        assertEquals(8.0, t.volume() / ball, 0.08, "filled volume / crossing-ball volume")
    }

    @Test
    fun onePlanePerFamilyFillsARhombicTriacontahedron() {
        val t = deBruijnTiling3D(planes = 0..0, scale = 1.0)
        checkTiling(t, 0.0)
        assertEquals(mapOf(1 to 10, 2 to 10), t.shapeCounts())
        // Unit-edge rhombic triacontahedron: 4 sqrt(5 + 2 sqrt 5).
        assertEquals(4 * sqrt(5 + 2 * sqrt(5.0)), t.volume(), 1e-9)
        // Its boundary: thirty faces with one cell each, and sixty interior faces with two.
        val byFace = HashMap<List<List<Int>>, Int>()
        for (c in t.cells) for (f in c.faces()) byFace.merge(c.faceKey(f), 1, Int::plus)
        assertEquals(30, byFace.values.count { it == 1 })
        assertEquals(45, byFace.values.count { it == 2 })
        // Two planes per family: the same solid twice the size, 160 cells.
        val twice = deBruijnTiling3D(planes = 0..1, scale = 1.0)
        assertEquals(160, twice.cells.size)
        assertEquals(8 * t.volume(), twice.volume(), 1e-9)
    }

    @Test
    fun everyStarTiles() {
        val cubic = deBruijnTiling3D(DeBruijnStar.CUBIC, radius = 3.0, scale = 1.0)
        checkTiling(cubic, 1.5)
        assertEquals(listOf(1.0), cubic.shapeVolumes.map { Math.round(it * 1e9) / 1e9 })
        checkTiling(deBruijnTiling3D(DeBruijnStar.CUBIC_THREEFOLD, radius = 3.0), 1.5)
        assertEquals(1, deBruijnTiling3D(DeBruijnStar.CUBIC_THREEFOLD, radius = 3.0).shapeCounts().size)
        checkTiling(deBruijnTiling3D(DeBruijnStar.CUBIC_TWOFOLD, radius = 3.0), 1.5)
        checkTiling(deBruijnTiling3D(DeBruijnStar.ICOSAHEDRAL_THREEFOLD, radius = 2.0), 0.8)
        // Offsets moved off the defaults still tile.
        val moved = defaultDeBruijnOffsets(6).mapIndexed { k, g -> g + 0.37 * k }
        checkTiling(deBruijnTiling3D(offsets = moved, radius = 3.5), 2.0)
    }

    @Test
    fun aSliceIsAPolygonTilingOfThePlane() {
        val t = deBruijnTiling3D(radius = 5.0, scale = 1.0)
        val s = t.slice(t.directions[0], offset = 0.3)
        assertTrue(s.pieces.size > 50)
        assertTrue(s.sideCounts().keys.all { it in 3..6 }, "sides ${s.sideCounts()}")
        // Areas: the pieces near the middle cover a disk with no gaps or overlaps.
        val r = 3.0
        for (p in s.pieces) assertTrue(p.area() > 0, "a slice polygon is wound clockwise or empty")
        // Sample points of the disk; each lies in exactly one polygon.
        var hits = 0
        for (i in -20..20) for (j in -20..20) {
            val x = r * i / 20.0 + 1e-4
            val y = r * j / 20.0 + 2e-4
            if (x * x + y * y > r * r) continue
            val n = s.pieces.count { inside(it.polygon, x, y) }
            assertEquals(1, n, "($x, $y) is in $n slice polygons")
            hits++
        }
        assertTrue(hits > 1000)
    }

    private fun inside(p: List<afield.math.linalg.Vector2d>, x: Double, y: Double): Boolean {
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            if ((b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x) < 0) return false
        }
        return true
    }

    @Test
    fun fourConcurrentPlanesAreNudgedOrReported() {
        // Zero offsets put every family's plane 0 through the origin.
        val zero = List(6) { 0.0 }
        val error = runCatching { deBruijnTiling3D(offsets = zero, radius = 2.0, onDegenerate = DeBruijnDegeneracy.ERROR) }.exceptionOrNull()
        assertTrue(error is DeBruijnDegenerateException, "expected DeBruijnDegenerateException, got $error")
        checkTiling(deBruijnTiling3D(offsets = zero, radius = 3.0), 1.5)
    }

    @Test
    fun orientedTurnsWithoutChangingTheCells() {
        val t = deBruijnTiling3D(radius = 2.5)
        val up = t.oriented(t.directions[0])
        assertEquals(1.0, up.directions[0].z, 1e-12)
        assertEquals(t.volume(), up.volume(), 1e-9)
        checkTiling(up, 1.0)
        val mesh = up.toTriMesh(shrink = 0.8)
        assertEquals(up.cells.size * 12, mesh.triangleCount)
    }
}
