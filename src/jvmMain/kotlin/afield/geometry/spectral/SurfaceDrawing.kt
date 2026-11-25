@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.spectral

import afield.geometry.mesh.TriMesh
import afield.geometry.mesh.topology
import afield.math.linalg.Vec3
import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.random.Random

data class SurfaceDrawing(val targets: List<Int>, val machine: EpicycleGeometry)

/** Fit a periodic drawing to a closed edge walk through farthest-point targets on a connected mesh. */
fun TriMesh.fitSurfaceDrawing(targetCount: Int, seed: Int, maxDegree: Double, reusePenalty: Double = 0.8): SurfaceDrawing {
    require(targetCount in 2..vertexCount)
    require(reusePenalty.isFinite() && reusePenalty >= 0.0)
    val points = List(vertexCount, ::position)
    // Spread targets over the scan before putting them in a seeded order.
    val anchors = mutableListOf<Int>()
    val nearest = FloatArray(vertexCount) { Float.POSITIVE_INFINITY }
    var next = (0 until vertexCount).maxBy(::y)
    repeat(targetCount) {
        anchors += next
        val point = points[next]
        for (i in nearest.indices) nearest[i] = minOf(nearest[i], point.distanceSquared(points[i]))
        next = nearest.indices.maxBy { nearest[it] }
    }

    val topology = topology()
    val neighbors = List(vertexCount) { mutableListOf<Pair<Int, Int>>() }
    for ((index, edge) in topology.edges.withIndex()) {
        val (a, b) = edge
        neighbors[a].add(b to index)
        neighbors[b].add(a to index)
    }
    val visits = DoubleArray(topology.edgeCount)

    fun route(from: Int, to: Int): List<Int> {
        val costs = DoubleArray(vertexCount) { Double.POSITIVE_INFINITY }
        val previous = IntArray(vertexCount) { -1 }
        val queue = PriorityQueue(compareBy<Pair<Int, Double>> { it.second })
        costs[from] = 0.0
        queue.add(from to 0.0)
        while (queue.isNotEmpty()) {
            val (at, cost) = queue.remove()
            if (cost > costs[at]) continue
            if (at == to) break
            for ((neighbor, edge) in neighbors[at]) {
                val distance = points[at].distance(points[neighbor])
                val candidate = cost + distance * (1.0 + reusePenalty * visits[edge])
                if (candidate < costs[neighbor]) {
                    costs[neighbor] = candidate
                    previous[neighbor] = at
                    queue.add(neighbor to candidate)
                }
            }
        }
        check(costs[to].isFinite()) { "Drawing targets must be on the same connected component" }
        val reverse = mutableListOf(to)
        while (reverse.last() != from) reverse += previous[reverse.last()]
        val path = reverse.asReversed()
        for ((a, b) in path.zipWithNext()) visits[topology.edgeOf(a, b)] += 1.0
        return path
    }

    val tour = anchors.shuffled(Random(seed))
    val walk = (tour + tour.first()).zipWithNext().flatMap { (a, b) -> route(a, b).dropLast(1) }
    val loop = (walk + walk.first()).map(points::get)
    val length = DoubleArray(loop.size)
    for (i in 1 until loop.size) length[i] = length[i - 1] + loop[i - 1].distance(loop[i])

    // Fit a circle of time to the whole closed walk, sampled by arc length.
    val machine = EpicycleGeometry.fit(intArrayOf(2048), maxDegree = maxDegree) { phase ->
        val distance = phase[0] / (2 * PI) * length.last()
        val search = length.binarySearch(distance)
        val i = if (search >= 0) search.coerceAtMost(loop.lastIndex - 1) else -search - 2
        val t = (distance - length[i]) / (length[i + 1] - length[i])
        Vec3(loop[i]).lerp(loop[i + 1], t.toFloat())
    }
    return SurfaceDrawing(anchors, machine)
}
