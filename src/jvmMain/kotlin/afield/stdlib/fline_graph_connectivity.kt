package afield.stdlib

import afield.graphics.FLine
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A sampled source point that contributed to a welded [FLineGraph] vertex.
 */
data class FLineGraphSource(
    val lineIndex: Int,
    val instruction: Float,
    val position: Vec3
)

/**
 * Graph view of one or more [FLine]s.
 *
 * [vertices] are the welded positions produced from sampled line geometry.
 * [graph] contains edges between consecutive sampled positions, using the same
 * [GraphEdge] / [PointGraph] types as the graph connectivity helpers.
 */
data class FLineGraph(
    val vertices: List<Vec3>,
    val graph: PointGraph,
    val vertexSources: List<List<FLineGraphSource>>
) {
    val edges: List<GraphEdge>
        get() = graph.edges

    val pointCount: Int
        get() = graph.pointCount
}

/**
 * Linearize [lines] into a graph by adaptively flattening curves and welding
 * vertices whose distance is <= [epsilon].
 *
 * [cubicFlatness] defaults to [epsilon]. Lower it when you want denser curve
 * sampling without changing vertex welding behavior.
 */
fun flineGraph(
    lines: Iterable<FLine>,
    epsilon: Number = 1e-4f,
    cubicFlatness: Number = epsilon,
    maxCubicSubdivisions: Int = 12
): FLineGraph {
    val weldEpsilon = epsilon.toFloat()
    val flatness = cubicFlatness.toFloat()
    require(weldEpsilon >= 0f && weldEpsilon.isFinite()) {
        "flineGraph: epsilon must be finite and >= 0, found $epsilon"
    }
    require(flatness >= 0f && flatness.isFinite()) {
        "flineGraph: cubicFlatness must be finite and >= 0, found $cubicFlatness"
    }
    require(maxCubicSubdivisions >= 0) {
        "flineGraph: maxCubicSubdivisions must be >= 0, found $maxCubicSubdivisions"
    }

    val raw = sampleFLineGraph(lines, flatness.coerceAtLeast(1e-7f), maxCubicSubdivisions)
    if (raw.points.isEmpty()) {
        return FLineGraph(emptyList(), PointGraph(emptyList(), 0), emptyList())
    }

    val rawToVertex = weldFLineGraphVertices(raw.points, weldEpsilon)
    val vertexCount = (rawToVertex.maxOrNull() ?: -1) + 1
    val vertexSums = Array(vertexCount) { Vec3() }
    val vertexCounts = IntArray(vertexCount)
    val sourceBuckets = Array(vertexCount) { mutableListOf<FLineGraphSource>() }

    raw.points.forEachIndexed { rawIndex, point ->
        val vertexIndex = rawToVertex[rawIndex]
        vertexSums[vertexIndex].add(point.position)
        vertexCounts[vertexIndex]++
        sourceBuckets[vertexIndex] += FLineGraphSource(
            lineIndex = point.lineIndex,
            instruction = point.instruction,
            position = Vec3(point.position)
        )
    }

    val vertices = List(vertexCount) { index ->
        Vec3(vertexSums[index]).div(vertexCounts[index].toFloat())
    }

    val edgeByKey = linkedMapOf<Long, GraphEdge>()
    for (edge in raw.edges) {
        val a = rawToVertex[edge.a]
        val b = rawToVertex[edge.b]
        if (a == b) continue
        val weight = distance3(vertices[a], vertices[b])
        if (!weight.isFinite()) continue

        val key = flineGraphEdgeKey(a, b)
        val existing = edgeByKey[key]
        if (existing == null || weight < existing.weight) {
            edgeByKey[key] = GraphEdge(a, b, weight)
        }
    }

    val graph = PointGraph(edgeByKey.values.toList(), vertices.size)
    return FLineGraph(
        vertices = vertices,
        graph = graph,
        vertexSources = sourceBuckets.map { it.toList() }
    )
}

/**
 * Convenience extension for [flineGraph].
 */
fun Iterable<FLine>.toFLineGraph(
    epsilon: Number = 1e-4f,
    cubicFlatness: Number = epsilon,
    maxCubicSubdivisions: Int = 12
): FLineGraph = flineGraph(this, epsilon, cubicFlatness, maxCubicSubdivisions)

/**
 * Convenience extension for a single [FLine].
 */
fun FLine.toFLineGraph(
    epsilon: Number = 1e-4f,
    cubicFlatness: Number = epsilon,
    maxCubicSubdivisions: Int = 12
): FLineGraph = listOf(this).toFLineGraph(epsilon, cubicFlatness, maxCubicSubdivisions)

private data class RawFLineGraphPoint(
    val position: Vec3,
    val lineIndex: Int,
    val instruction: Float
)

private data class RawFLineGraphEdge(
    val a: Int,
    val b: Int
)

private data class RawFLineGraph(
    val points: List<RawFLineGraphPoint>,
    val edges: List<RawFLineGraphEdge>
)

private fun sampleFLineGraph(
    lines: Iterable<FLine>,
    cubicFlatness: Float,
    maxCubicSubdivisions: Int
): RawFLineGraph {
    val points = mutableListOf<RawFLineGraphPoint>()
    val edges = mutableListOf<RawFLineGraphEdge>()

    fun addPoint(position: Vec3, lineIndex: Int, instruction: Float): Int {
        val index = points.size
        points += RawFLineGraphPoint(Vec3(position), lineIndex, instruction)
        return index
    }

    fun addEdge(a: Int, b: Int) {
        if (a != b) edges += RawFLineGraphEdge(a, b)
    }

    lines.forEachIndexed { lineIndex, line ->
        var current = Vec3()
        var currentIndex = -1
        var hasCurrent = false
        var open = Vec3()
        var openIndex = -1
        var hasOpen = false

        line.nodes.forEachIndexed { instructionIndex, node ->
            val instruction = instructionIndex.toFloat()
            when (node) {
                is FLine.MoveTo -> {
                    current = Vec3(node.to)
                    currentIndex = addPoint(current, lineIndex, instruction)
                    hasCurrent = true
                    open = Vec3(current)
                    openIndex = currentIndex
                    hasOpen = true
                }

                is FLine.LineTo -> {
                    val next = Vec3(node.to)
                    val nextIndex = addPoint(next, lineIndex, instruction)
                    if (hasCurrent) {
                        addEdge(currentIndex, nextIndex)
                    } else {
                        open = Vec3(next)
                        openIndex = nextIndex
                        hasOpen = true
                    }
                    current = next
                    currentIndex = nextIndex
                    hasCurrent = true
                }

                is FLine.CubicTo -> {
                    val end = Vec3(node.to)
                    if (!hasCurrent) {
                        current = end
                        currentIndex = addPoint(current, lineIndex, instruction)
                        open = Vec3(current)
                        openIndex = currentIndex
                        hasOpen = true
                        hasCurrent = true
                    } else {
                        val samples = flattenCubic(
                            a = current,
                            c1 = node.c1,
                            c2 = node.c2,
                            b = end,
                            fromInstruction = max(0f, instruction - 1f),
                            toInstruction = instruction,
                            flatness = cubicFlatness,
                            maxSubdivisions = maxCubicSubdivisions
                        )
                        for (sample in samples) {
                            val nextIndex = addPoint(sample.position, lineIndex, sample.instruction)
                            addEdge(currentIndex, nextIndex)
                            current = Vec3(sample.position)
                            currentIndex = nextIndex
                        }
                    }
                }

                is FLine.Close -> {
                    if (hasCurrent && hasOpen) {
                        addEdge(currentIndex, openIndex)
                        current = Vec3(open)
                        currentIndex = openIndex
                    }
                }
            }
        }
    }

    return RawFLineGraph(points, edges)
}

private data class FlattenedCubicPoint(
    val position: Vec3,
    val instruction: Float
)

private fun flattenCubic(
    a: Vec3,
    c1: Vec3,
    c2: Vec3,
    b: Vec3,
    fromInstruction: Float,
    toInstruction: Float,
    flatness: Float,
    maxSubdivisions: Int
): List<FlattenedCubicPoint> {
    val out = mutableListOf<FlattenedCubicPoint>()

    fun recurse(
        p0: Vec3,
        p1: Vec3,
        p2: Vec3,
        p3: Vec3,
        t0: Float,
        t1: Float,
        depth: Int
    ) {
        if (depth >= maxSubdivisions || cubicFlatness(p0, p1, p2, p3) <= flatness) {
            out += FlattenedCubicPoint(Vec3(p3), t1)
            return
        }

        val p01 = midpoint(p0, p1)
        val p12 = midpoint(p1, p2)
        val p23 = midpoint(p2, p3)
        val p012 = midpoint(p01, p12)
        val p123 = midpoint(p12, p23)
        val p0123 = midpoint(p012, p123)
        val tm = (t0 + t1) * 0.5f

        recurse(p0, p01, p012, p0123, t0, tm, depth + 1)
        recurse(p0123, p123, p23, p3, tm, t1, depth + 1)
    }

    recurse(Vec3(a), Vec3(c1), Vec3(c2), Vec3(b), fromInstruction, toInstruction, 0)
    return out
}

private fun weldFLineGraphVertices(
    points: List<RawFLineGraphPoint>,
    epsilon: Float
): IntArray {
    if (points.isEmpty()) return IntArray(0)

    val unionFind = GraphWeldUnionFind(points.size)

    if (epsilon == 0f) {
        val exact = mutableMapOf<ExactVec3Key, Int>()
        points.forEachIndexed { index, point ->
            val previous = exact.putIfAbsent(ExactVec3Key(point.position), index)
            if (previous != null) unionFind.union(previous, index)
        }
    } else {
        val cells = mutableMapOf<GridCell3, MutableList<Int>>()
        val neighborOffsets = (-1..1).flatMap { dx ->
            (-1..1).flatMap { dy ->
                (-1..1).map { dz -> GridCell3(dx, dy, dz) }
            }
        }

        points.forEachIndexed { index, point ->
            val cell = GridCell3.of(point.position, epsilon)
            for (offset in neighborOffsets) {
                val bucket = cells[GridCell3(cell.x + offset.x, cell.y + offset.y, cell.z + offset.z)] ?: continue
                for (candidate in bucket) {
                    if (distance3(point.position, points[candidate].position) <= epsilon.toDouble()) {
                        unionFind.union(candidate, index)
                    }
                }
            }
            cells.getOrPut(cell) { mutableListOf() } += index
        }
    }

    val rootToVertex = linkedMapOf<Int, Int>()
    val rawToVertex = IntArray(points.size)
    points.indices.forEach { index ->
        val root = unionFind.find(index)
        rawToVertex[index] = rootToVertex.getOrPut(root) { rootToVertex.size }
    }
    return rawToVertex
}

private data class GridCell3(val x: Int, val y: Int, val z: Int) {
    companion object {
        fun of(point: Vec3, cellSize: Float): GridCell3 = GridCell3(
            floor(point.x / cellSize).toInt(),
            floor(point.y / cellSize).toInt(),
            floor(point.z / cellSize).toInt()
        )
    }
}

private data class ExactVec3Key(val x: Int, val y: Int, val z: Int) {
    constructor(point: Vec3) : this(
        normalizedFloatBits(point.x),
        normalizedFloatBits(point.y),
        normalizedFloatBits(point.z)
    )
}

private fun normalizedFloatBits(value: Float): Int =
    if (value == 0f) 0 else value.toRawBits()

private class GraphWeldUnionFind(size: Int) {
    private val parent = IntArray(size) { it }
    private val rank = IntArray(size)

    fun find(value: Int): Int {
        if (parent[value] != value) parent[value] = find(parent[value])
        return parent[value]
    }

    fun union(a: Int, b: Int) {
        val rootA = find(a)
        val rootB = find(b)
        if (rootA == rootB) return
        when {
            rank[rootA] < rank[rootB] -> parent[rootA] = rootB
            rank[rootA] > rank[rootB] -> parent[rootB] = rootA
            else -> {
                parent[rootB] = rootA
                rank[rootA]++
            }
        }
    }
}

private fun midpoint(a: Vec3, b: Vec3): Vec3 =
    Vec3(
        (a.x + b.x) * 0.5f,
        (a.y + b.y) * 0.5f,
        (a.z + b.z) * 0.5f
    )

private fun cubicFlatness(a: Vec3, c1: Vec3, c2: Vec3, b: Vec3): Float =
    sqrt(max(pointSegmentDistanceSquared(c1, a, b), pointSegmentDistanceSquared(c2, a, b))).toFloat()

private fun pointSegmentDistanceSquared(p: Vec3, a: Vec3, b: Vec3): Double {
    val abx = b.x.toDouble() - a.x.toDouble()
    val aby = b.y.toDouble() - a.y.toDouble()
    val abz = b.z.toDouble() - a.z.toDouble()
    val apx = p.x.toDouble() - a.x.toDouble()
    val apy = p.y.toDouble() - a.y.toDouble()
    val apz = p.z.toDouble() - a.z.toDouble()
    val ab2 = abx * abx + aby * aby + abz * abz
    if (ab2 <= 0.0) return apx * apx + apy * apy + apz * apz

    val t = ((apx * abx + apy * aby + apz * abz) / ab2).coerceIn(0.0, 1.0)
    val qx = a.x.toDouble() + t * abx
    val qy = a.y.toDouble() + t * aby
    val qz = a.z.toDouble() + t * abz
    val dx = p.x.toDouble() - qx
    val dy = p.y.toDouble() - qy
    val dz = p.z.toDouble() - qz
    return dx * dx + dy * dy + dz * dz
}

private fun distance3(a: Vec3, b: Vec3): Double {
    val dx = a.x.toDouble() - b.x.toDouble()
    val dy = a.y.toDouble() - b.y.toDouble()
    val dz = a.z.toDouble() - b.z.toDouble()
    return sqrt(dx * dx + dy * dy + dz * dz)
}

private fun flineGraphEdgeKey(a: Int, b: Int): Long {
    val lo = minOf(a, b).toLong() and 0xffffffffL
    val hi = maxOf(a, b).toLong() and 0xffffffffL
    return (lo shl 32) or hi
}
