package afield.geometry.path

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Checks that [deBruijnTiling] really is a tiling: edge to edge, no overlaps, the right rhombi. */
class DeBruijnTilingTest {

    private fun inside(p: afield.math.linalg.Vector2d, t: DeBruijnTile): Boolean {
        // Strictly inside a convex quad, either winding.
        var sign = 0
        for (i in 0 until 4) {
            val a = t.corners[i]
            val b = t.corners[(i + 1) % 4]
            val c = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
            if (abs(c) < 1e-6) return false
            val s = if (c > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    private fun checkTiling(tiling: DeBruijnTiling, radius: Double, maxGap: Double) {
        val tiles = tiling.tiles
        assertTrue(tiles.size > 50, "expected many tiles, got ${tiles.size}")

        // Every tile is a non-degenerate rhombus with unit (scaled) edges.
        for (t in tiles) {
            assertTrue(abs(t.signedArea()) > 1e-6, "degenerate tile")
            for (i in 0 until 4) {
                val a = t.corners[i]
                val b = t.corners[(i + 1) % 4]
                assertEquals(20.0, hypot(b.x - a.x, b.y - a.y), 1e-6)
            }
        }

        // No edge belongs to more than two tiles; interior tiles have all four edges shared.
        val byEdge = HashMap<Pair<Long, Long>, Int>()
        for (t in tiles) for (i in 0 until 4)
            byEdge.merge(DeBruijnTiling.edgeKey(t.corners[i], t.corners[(i + 1) % 4]), 1, Int::plus)
        assertTrue(byEdge.values.all { it <= 2 }, "an edge is shared by more than two tiles")
        val interior = tiles.filter { hypot(it.crossing.x, it.crossing.y) < radius - maxGap }
        assertTrue(interior.size > 20)
        for (t in interior) for (i in 0 until 4)
            assertEquals(2, byEdge[DeBruijnTiling.edgeKey(t.corners[i], t.corners[(i + 1) % 4])], "interior edge not shared by two tiles")

        // No tile's centre or corner-nudged point lies inside another tile.
        val cell = 40.0
        val grid = HashMap<Pair<Int, Int>, MutableList<Int>>()
        tiles.forEachIndexed { i, t -> grid.getOrPut(Math.floorDiv(t.center.x.toInt(), cell.toInt()) to Math.floorDiv(t.center.y.toInt(), cell.toInt())) { mutableListOf() }.add(i) }
        tiles.forEachIndexed { i, t ->
            val cx = Math.floorDiv(t.center.x.toInt(), cell.toInt())
            val cy = Math.floorDiv(t.center.y.toInt(), cell.toInt())
            for (dx in -1..1) for (dy in -1..1) for (j in grid[(cx + dx) to (cy + dy)].orEmpty()) {
                if (j != i) assertTrue(!inside(t.center, tiles[j]), "tile $i overlaps tile $j")
            }
        }

        // Each tile's type agrees with its acute angle.
        for (t in tiles) {
            val a = t.corners[0]
            val ux = t.corners[1].x - a.x
            val uy = t.corners[1].y - a.y
            val vx = t.corners[3].x - a.x
            val vy = t.corners[3].y - a.y
            val raw = abs(Math.toDegrees(Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)))
            val deg = if (raw > 90) 180 - raw else raw
            assertEquals(t.acuteDegrees(tiling.symmetry), deg, 1e-6)
        }
    }

    @Test
    fun penroseIsEdgeToEdgeWithTwoRhombsInTheGoldenRatio() {
        val radius = 14.0
        val tiling = deBruijnTiling(symmetry = 5, radius = radius)
        assertEquals(0.0, tiling.offsets.sum(), 1e-12)
        checkTiling(tiling, radius, 2.0)
        val counts = tiling.typeCounts()
        assertEquals(setOf(1, 2), counts.keys, "Penrose has only thin (36) and thick (72) rhombi")
        val ratio = counts.getValue(2).toDouble() / counts.getValue(1)
        val phi = (1 + Math.sqrt(5.0)) / 2
        assertEquals(phi, ratio, 0.02, "thick/thin = $ratio")
    }

    @Test
    fun goldenRatioImprovesWithRadius() {
        val phi = (1 + Math.sqrt(5.0)) / 2
        fun err(r: Double) = deBruijnTiling(radius = r).typeCounts().let { abs(it.getValue(2).toDouble() / it.getValue(1) - phi) }
        assertTrue(err(24.0) < 0.01, "error at radius 24 is ${err(24.0)}")
    }

    @Test
    fun ammannBeenkerHasSquaresAndFortyFiveDegreeRhombi() {
        val tiling = deBruijnTiling(symmetry = 8, radius = 10.0)
        checkTiling(tiling, 10.0, 3.0)
        // Directions are PI k / 8 for k = 0..7, so 4-fold is squares, 2-fold 45 degree rhombi; but
        // with 8 families every pair-difference class 1..4 appears.
        assertEquals(setOf(1, 2, 3, 4), tiling.typeCounts().keys)
    }

    @Test
    fun sevenFoldAndDisorderedSixFoldStillTile() {
        for (n in listOf(3, 4, 7, 9, 12)) checkTiling(deBruijnTiling(symmetry = n, radius = 10.0), 10.0, 4.0)
        checkTiling(deBruijnTiling(symmetry = 6, radius = 10.0, disorder = 0.6, seed = 3), 10.0, 3.5)
    }

    @Test
    fun ribbonIsAChainOfAdjacentTiles() {
        val tiling = deBruijnTiling(radius = 10.0)
        val ribbon = tiling.ribbon(0, 0)
        assertTrue(ribbon.size > 10)
        val adjacency = tiling.adjacency()
        val indices = ribbon.map { tiling.tiles.indexOf(it) }.toSet()
        // Every ribbon tile touches another ribbon tile across a shared edge.
        for (i in indices) assertTrue(adjacency[i].any { it in indices }, "ribbon tile $i is isolated")
    }
}
