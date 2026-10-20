package afield.geometry.topology.mapper

import afield.math.linalg.Vec2
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapperTest {
    private fun cloud(n: Int, seed: Long, f: (Random) -> Pair<Double, Double>): DoubleArray {
        val r = Random(seed); val out = DoubleArray(2 * n)
        for (i in 0 until n) { val (x, y) = f(r); out[2 * i] = x + 0.03 * r.nextGaussian(); out[2 * i + 1] = y + 0.03 * r.nextGaussian() }
        return out
    }

    private fun ring(seed: Long) = cloud(600, seed) { r -> val t = r.nextDouble() * 2 * PI; cos(t) to sin(t) }

    private fun letterY(seed: Long) = cloud(900, seed) { r ->
        val arm = r.nextInt(3); val s = r.nextDouble()
        val a = PI / 2 + arm * 2 * PI / 3
        s * cos(a) to s * sin(a)
    }

    @Test fun ringHasOneLoop() {
        for (seed in 0L until 4L) {
            val p = ring(seed)
            val g = Mapper.compute(p, lens = Mapper.coordinate(p, axis = 0), intervals = 8, overlap = 0.3)
            assertEquals(1, g.components().size, "seed $seed $g")
            assertEquals(1, g.loops, "seed $seed $g")
        }
    }

    @Test fun letterYIsATreeWithThreeEnds() {
        for (seed in 0L until 4L) {
            val p = letterY(seed)
            val g = Mapper.compute(p, lens = Mapper.eccentricity(p), intervals = 8, overlap = 0.3)
            assertEquals(0, g.loops, "seed $seed $g")
            val degree = IntArray(g.nodes.size); g.edges.forEach { degree[it.a]++; degree[it.b]++ }
            assertEquals(3, degree.count { it == 1 }, "seed $seed $g")
        }
    }

    @Test fun separateBlobsAreSeparateComponents() {
        val p = cloud(400, 1) { r -> if (r.nextBoolean()) 0.0 to 0.0 else 3.0 to 0.0 }
        val g = Mapper.compute(p, lens = Mapper.coordinate(p, axis = 1), intervals = 4)
        assertEquals(2, g.components().size, g.toString())
    }

    @Test fun outputs() {
        val p = ring(7)
        val pts = (0 until p.size / 2).map { Vec2(p[2 * it].toFloat(), p[2 * it + 1].toFloat()) }
        val g = Mapper.compute(pts, lens = Mapper.eccentricity(pts))
        val g2 = Mapper.compute(pts, lens = Mapper.density(pts, k = 6), eps = 0.2, minClusterSize = 5)
        assertTrue(g2.nodes.all { it.size >= 5 })
        assertEquals(g.nodes.size + g.edges.size, g.toFLines().size)
        assertEquals(p.size / 2, g.pointsFLine().nodes.size)
        assertEquals(g.edges.size, g.network().curves.size)
        assertTrue(g.nodesOfPoint.count { it.size == 2 } > 0, "overlapping bins put points in two nodes")
        assertTrue(g.nodes.all { n -> n.centroid.size == 2 && n.lens.isFinite() && n.bin >= 0 })
    }
}
