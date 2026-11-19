package afield.geometry.spatial

import afield.data.graph.GraphConnectivityOptions
import afield.data.graph.vec3OrthantGraph
import afield.math.linalg.Vector3f
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParticleSpatialTest {
    @Test
    fun orthantSearchMatchesBruteForceIncludingPairsAcrossGridCells() {
        val rng = Random(4919)
        val points = FloatArray(241 * 3) { rng.nextInt(-50, 51).toFloat() }
        // Two equally distant +X neighbours and a coincident point.
        for (i in 0..11) points[i] = 0f
        points[3] = 2f
        points[6] = 2f
        val answer = OrthantNeighbors3D.build(points, cellsPerAxisHint = 7, maxRings = Int.MAX_VALUE)
        val serial = OrthantNeighbors3D.build(points, cellsPerAxisHint = 7, maxRings = Int.MAX_VALUE, parallel = false)
        assertContentEquals(serial.neighbors, answer.neighbors)
        assertContentEquals(serial.distances, answer.distances)
        assertEquals(0, answer.ringLimited)
        for (i in 0 until answer.count) {
            val nearest = IntArray(8) { -1 }
            val squared = DoubleArray(8) { Double.POSITIVE_INFINITY }
            for (j in 0 until answer.count) {
                if (i == j) continue
                val delta = DoubleArray(3) { points[j * 3 + it].toDouble() - points[i * 3 + it] }
                val octant = OrthantNeighbors3D.octant(delta[0], delta[1], delta[2])
                val cost = delta.sumOf { it * it }
                if (cost < squared[octant]) {
                    squared[octant] = cost
                    nearest[octant] = j
                }
            }
            for (o in nearest.indices) {
                assertEquals(nearest[o], answer.neighbors[i * 8 + o])
                assertEquals(if (nearest[o] >= 0) sqrt(squared[o]) else 0.0, answer.distances[i * 8 + o])
            }
        }
    }

    @Test
    fun orthantCapReportsQueriesWithUnsearchedCells() {
        val points = floatArrayOf(0f, 0f, 0f, 2f, 0f, 0f, 2f, 0f, 0f)
        val capped = OrthantNeighbors3D.build(points, maxRings = 0)
        assertEquals(3, capped.ringLimited)
        assertEquals(-1, capped.neighbors[0])
        val exact = OrthantNeighbors3D.build(points, maxRings = 8)
        assertEquals(1, exact.neighbors[0])
        assertEquals(2.0, exact.distances[0])
    }

    @Test
    fun exactGraphRetainsOpenOctantsAndEdgeOrder() {
        val points = listOf(
            Vector3f(0f, 0f, 0f), Vector3f(1f, 0f, 1f), Vector3f(2f, 2f, 2f),
            Vector3f(3f, -2f, 1f), Vector3f(-3f, 2f, 1f), Vector3f(2f, 2f, 2f)
        )
        val exhaustive = vec3OrthantGraph(points, GraphConnectivityOptions(customDistanceMetric = { i, j, cloud ->
            val a = cloud[i] as Vector3f
            val b = cloud[j] as Vector3f
            val dx = a.x.toDouble() - b.x
            val dy = a.y.toDouble() - b.y
            val dz = a.z.toDouble() - b.z
            sqrt(dx * dx + dy * dy + dz * dz)
        }))
        val accelerated = vec3OrthantGraph(points)
        assertEquals(exhaustive.edges.map { Triple(it.i, it.j, it.weight) }, accelerated.edges.map { Triple(it.i, it.j, it.weight) })
    }

    @Test
    fun projectedMatchingOptimizesActualTravelAndRespectsWarmStart() {
        val from = floatArrayOf(0f, 0f, 0f, 0f, 0f, 4f, 0f, 0f, 9f, 0f, 0f, 30f)
        val to = floatArrayOf(0f, 0f, 30f, 0f, 0f, 9f, 0f, 0f, 4f, 0f, 0f, 0f)
        val options = BspOt3D.Options(projections = 3, treesPerProjection = 2)
        val result = BspOt3D.match(from, to, options)
        assertContentEquals(intArrayOf(3, 2, 1, 0), result.targetOfSource)
        assertEquals(0.0, result.squaredCost)
        assertTrue((1..<result.costHistory.size).all { result.costHistory[it] <= result.costHistory[it - 1] })
        val warm = BspOt3D.match(from, to, options, initial = result.targetOfSource)
        assertEquals(0.0, warm.costHistory.first())
        assertContentEquals(result.targetOfSource, warm.targetOfSource)
    }
}
