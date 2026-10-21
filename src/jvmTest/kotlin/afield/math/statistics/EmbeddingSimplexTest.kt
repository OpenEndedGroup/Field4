package afield.math.statistics

import afield.graphics.three.SimplexView
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The claim the tetra starting point teaches, held as numbers: four points of any Euclidean space sit
 * exactly in 3D, so the drawn edges are the distances; five or more in general do not.
 */
class EmbeddingSimplexTest {

    private fun drawn(s: EmbeddingSimplex, a: Int, b: Int): Double {
        val p = s.points[a]
        val q = s.points[b]
        val dx = (p.x - q.x).toDouble()
        val dy = (p.y - q.y).toDouble()
        val dz = (p.z - q.z).toDouble()
        return sqrt(dx * dx + dy * dy + dz * dz) / s.scale
    }

    private fun randomUnitVectors(n: Int, dims: Int, seed: Int): List<DoubleArray> {
        val r = Random(seed)
        return List(n) { DoubleArray(dims) { r.nextDouble(-1.0, 1.0) } }
    }

    @Test
    fun `four points in 512 dimensions embed exactly in 3D`() {
        for (seed in 1..5) {
            val s = embeddingSimplex(randomUnitVectors(4, 512, seed))
            for (e in s.edges) {
                // Vec3 is float32, so the drawn lengths carry float rounding at radius 3.
                assertEquals(e.distance, drawn(s, e.a, e.b), 2e-6, "seed $seed edge ${e.a}-${e.b}")
            }
            assertTrue(s.exact, "stress ${s.stress1} for four points")
            assertTrue(s.stress1 < 1e-9, "stress ${s.stress1}")
        }
    }

    @Test
    fun `a regular tetrahedron comes back regular, centred, at the radius asked for`() {
        val corners = listOf(
            doubleArrayOf(1.0, 1.0, 1.0, 0.0), doubleArrayOf(-1.0, -1.0, 1.0, 0.0),
            doubleArrayOf(-1.0, 1.0, -1.0, 0.0), doubleArrayOf(1.0, -1.0, -1.0, 0.0),
        )
        val s = embeddingSimplex(corners, normalize = false, radius = 3.0)
        val side = sqrt(8.0)
        for (e in s.edges) assertEquals(side, e.distance, 1e-12)
        for (e in s.edges) assertEquals(side, drawn(s, e.a, e.b), 1e-5)
        for (p in s.points) assertEquals(3.0, sqrt((p.x * p.x + p.y * p.y + p.z * p.z).toDouble()), 1e-5)
        val cx = s.points.sumOf { it.x.toDouble() }
        val cy = s.points.sumOf { it.y.toDouble() }
        val cz = s.points.sumOf { it.z.toDouble() }
        assertEquals(0.0, abs(cx) + abs(cy) + abs(cz), 1e-5)
    }

    @Test
    fun `chord distance is sqrt(2 - 2 cos) and cosine distance is half its square`() {
        val s = embeddingSimplex(randomUnitVectors(4, 64, 9))
        for (e in s.edges) {
            assertEquals(e.distance * e.distance / 2, e.cosineDistance, 1e-12)
        }
    }

    @Test
    fun `ranks order each point's edges, closest first`() {
        // Collinear, so the configuration is rank 1: MDS must still place it exactly.
        val xs = listOf(0.0, 1.0, 3.0, 7.0)
        val s = embeddingSimplex(xs.map { doubleArrayOf(it, 0.0, 0.0) }, normalize = false)
        assertEquals(listOf(1, 2, 3), s.edgesFrom(0).map { it.a + it.b - 0 })
        assertEquals(listOf(0, 2, 3), s.edgesFrom(1).map { it.a + it.b - 1 })
        assertEquals(listOf(1, 0, 3), s.edgesFrom(2).map { it.a + it.b - 2 })
        assertEquals(listOf(2, 1, 0), s.edgesFrom(3).map { it.a + it.b - 3 })
        assertEquals(0, s.edge(1, 2).rankFromA)
        assertEquals(1, s.edge(1, 2).rankFromB)
        for (e in s.edges) assertEquals(e.distance, drawn(s, e.a, e.b), 2e-6)
        assertTrue(s.exact)
    }

    @Test
    fun `three points make a flat triangle, two a segment, one a point`() {
        val tri = embeddingSimplex(randomUnitVectors(3, 32, 4))
        for (e in tri.edges) assertEquals(e.distance, drawn(tri, e.a, e.b), 2e-6)
        assertTrue(tri.points.all { it.z == 0f })

        val two = embeddingSimplex(randomUnitVectors(2, 32, 5))
        assertEquals(two.edges.single().distance, drawn(two, 0, 1), 2e-6)
        assertEquals(1, embeddingSimplex(randomUnitVectors(1, 32, 6)).points.size)
    }

    @Test
    fun `six points in 512 dimensions do not fit in 3D, and the stress says so`() {
        val s = embeddingSimplex(randomUnitVectors(6, 512, 11))
        assertFalse(s.exact, "six random points fitted with stress ${s.stress1}")
        assertTrue(s.stress1 > 1e-2, "stress ${s.stress1}")
        assertEquals(15, s.edges.size)
    }

    @Test
    fun `bad input is refused by name`() {
        assertFailsWith<IllegalArgumentException> { embeddingSimplex(emptyList<DoubleArray>()) }
        assertFailsWith<IllegalArgumentException> { embeddingSimplex(listOf(doubleArrayOf(1.0), doubleArrayOf(1.0, 2.0))) }
        assertFailsWith<IllegalArgumentException> { embeddingSimplex(listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 0.0))) }
        assertFailsWith<IllegalArgumentException> { embeddingSimplex(randomUnitVectors(2, 4, 1), labels = listOf("one")) }
    }

    @Test
    fun `the rank scale is tetra2's three colours for four points and spans them for more`() {
        assertEquals(SimplexView.RANK_COLORS, (0..2).map { SimplexView.rankColor(it, 3) })
        assertEquals(SimplexView.RANK_COLORS.first(), SimplexView.rankColor(0, 5))
        assertEquals(SimplexView.RANK_COLORS.last(), SimplexView.rankColor(4, 5))
    }
}
