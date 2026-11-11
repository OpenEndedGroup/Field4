package afield.simulation.agents


import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the self-organizing tree model in `alife_tree_growth.kt`: it stays a tree through growth and
 * shedding, Borchert–Honda allocation conserves the resource it splits, light never rises when shadow is
 * added, and apical control `λ` moves the form in the direction the method says it should.
 */
class TreeGrowthTest {
    @Test fun staysATreeEverySeason() {
        val t = TreeGrowth(seed = 2L)
        repeat(30) {
            t.step()
            assertTrue(t.isTree(), "season ${t.season}: $t")
        }
        println("tree growth, 30 seasons: $t")
        assertTrue(t.nodeCount > 1000, "$t")
        assertTrue(t.shedBranchesTotal > 0, "shading should shed something by season 30: $t")
    }

    @Test fun resourceIsConservedEverySeason() {
        for (lambda in listOf(0.0, 0.3, 0.5, 0.52, 0.7, 1.0)) {
            val t = TreeGrowth(params = TreeGrowthParams(lambda = lambda), seed = 5L)
            repeat(25) { t.step() }
            for (r in t.history) {
                assertTrue(r.baseResource > 0.0, "λ=$lambda $r")
                assertTrue(r.conservationError < 1e-9, "λ=$lambda season ${r.season}: $r")
            }
        }
    }

    @Test fun lightIsMonotoneUnderShading() {
        val t = TreeGrowth(seed = 3L).stepTimes(15)
        t.castShadows()
        val g = t.shadow
        val pts = (0 until t.nodeCount).map { t.x(it) to t.y(it) }
        val before = pts.map { (x, y) -> g.lightAt(x, y) }
        val rng = kotlin.random.Random(9)
        repeat(200) { g.cast(rng.nextDouble() * t.width, rng.nextDouble() * t.height) }
        val after = pts.map { (x, y) -> g.lightAt(x, y) }
        var strictlyDarker = 0
        for (i in pts.indices) {
            assertTrue(after[i] <= before[i] + 1e-12, "node $i got lighter: ${before[i]} -> ${after[i]}")
            if (after[i] < before[i] - 1e-9) strictlyDarker++
        }
        // positive control: the extra casters really did shade something
        assertTrue(strictlyDarker > 0)
    }

    @Test fun shadowWedgeShape() {
        val g = ShadowGrid(11, 11, a = 1.0, b = 2.0, depth = 3)
        g.cast(5.5, 2.5)
        assertEquals(1.0, g.at(5, 2))
        assertEquals(0.0, g.lightAt(5.5, 3.5, ownCaster = false) - 0.5)
        assertEquals(0.5, g.at(4, 3)); assertEquals(0.5, g.at(6, 3)); assertEquals(0.0, g.at(3, 3))
        assertEquals(0.125, g.at(2, 5)); assertEquals(0.0, g.at(5, 6)); assertEquals(0.0, g.at(5, 1))
        assertEquals(1.0, g.lightAt(5.5, 2.5))
        assertEquals(0.5, g.lightAt(6.5, 3.5, ownCaster = false))
    }

    @Test fun apicalControlMovesTheForm() {
        fun crown(lambda: Double): Pair<Double, Double> {
            val t = TreeGrowth(params = TreeGrowthParams(lambda = lambda), seed = 4L).stepTimes(30)
            val xs = (0 until t.nodeCount).map { t.x(it) }
            val ys = (0 until t.nodeCount).map { t.y(it) }
            return (xs.max() - xs.min()) to (ys.max() - ys.min())
        }
        val (wLow, hLow) = crown(0.46)
        val (wHigh, hHigh) = crown(0.60)
        assertTrue(wLow > wHigh && hHigh > hLow, "λ 0.46 ${wLow}x$hLow vs λ 0.60 ${wHigh}x$hHigh")
        println("λ 0.46: ${wLow}x$hLow, λ 0.60: ${wHigh}x$hHigh")
        // the leader-favouring tree is taller and narrower than the lateral-favouring one
        assertTrue(hHigh / wHigh > hLow / wLow, "λ 0.46 ${wLow}x$hLow vs λ 0.60 ${wHigh}x$hHigh")
    }

    @Test fun pipeModelRadii() {
        val t = TreeGrowth(seed = 2L).stepTimes(12)
        val r = t.radii()
        val sizes = t.subtreeSizes()
        assertEquals(t.nodeCount, sizes[0])
        // the root is the thickest internode
        assertTrue((1 until t.nodeCount).all { r[it] <= r[0] + 1e-12 })
    }
}
