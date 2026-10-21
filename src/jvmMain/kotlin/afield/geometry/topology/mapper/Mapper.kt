package afield.geometry.topology.mapper

import afield.geometry.network.Channel
import afield.geometry.network.GeometryCurve
import afield.geometry.network.GeometryNetwork
import afield.geometry.network.GeometryNodes
import afield.geometry.path.FLine
import afield.graphics.color.Colormaps
import afield.math.linalg.Vec2
import afield.math.linalg.Vec4
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A node of a [MapperGraph]: one cluster of points within one bin of the lens. [members] are point
 * indices; [centroid] their mean position (all [MapperGraph.dimension] coordinates) and [lens] their
 * mean lens value.
 */
class MapperNode(val id: Int, val bin: Int, val members: IntArray, val centroid: DoubleArray, val lens: Double) {
    val size get() = members.size
    override fun toString() = "MapperNode($id, bin $bin, $size points)"
}

/** An edge of a [MapperGraph]: nodes [a] and [b] share [shared] points. */
data class MapperEdge(val a: Int, val b: Int, val shared: Int)

/**
 * The Mapper graph of a point cloud: a cluster per node, an edge wherever two clusters share points.
 * It is a coarse picture of the cloud's shape: branches, loops and separate pieces show up as
 * branches, loops and separate pieces of the graph.
 */
class MapperGraph internal constructor(
    val dimension: Int,
    val points: DoubleArray,
    val lensValues: DoubleArray,
    val nodes: List<MapperNode>,
    val edges: List<MapperEdge>,
) {
    /** For each point, the nodes it belongs to (one, or two where bins overlap; none if it was noise). */
    val nodesOfPoint: List<IntArray> by lazy {
        val lists = Array(points.size / dimension) { ArrayList<Int>(2) }
        nodes.forEach { n -> n.members.forEach { lists[it].add(n.id) } }
        lists.map { it.toIntArray() }
    }

    /** The graph's connected components, as lists of node ids. */
    fun components(): List<List<Int>> {
        val parent = IntArray(nodes.size) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
        edges.forEach { parent[find(it.a)] = find(it.b) }
        return nodes.indices.groupBy { find(it) }.values.toList()
    }

    /**
     * The number of independent loops in the graph (edges − nodes + components): 0 for a tree, 1
     * for a ring.
     */
    val loops get() = edges.size - nodes.size + components().size

    /**
     * The graph drawn over the cloud's first two coordinates: each edge a line between its nodes'
     * centroids, each node a dot sized by how many points it has ([minSize] to [maxSize] pixels) and
     * coloured by its mean lens value through [colormap] (low to high).
     */
    fun toFLines(minSize: Double = 4.0, maxSize: Double = 14.0, colormap: (Double) -> Vec4 = ::lensColour): List<FLine> {
        val lo = nodes.minOfOrNull { it.lens } ?: 0.0; val hi = nodes.maxOfOrNull { it.lens } ?: 1.0
        val biggest = nodes.maxOfOrNull { it.size } ?: 1
        val lines = edges.map { e ->
            val a = nodes[e.a].centroid; val b = nodes[e.b].centroid
            FLine().moveTo(a[0], a[1]).lineTo(b[0], b[1]).also { it.properties["color"] = Vec4(0.85f, 0.85f, 0.85f, 0.8f); it.properties["thickness"] = 1.5 }
        }
        val dots = nodes.map { n ->
            val r = minSize + (maxSize - minSize) * sqrt(n.size.toDouble() / biggest)
            FLine().moveTo(n.centroid[0], n.centroid[1]).also {
                it.properties["pointed"] = true; it.properties["stroked"] = false; it.properties["pointSize"] = r
                it.properties["color"] = colormap(if (hi > lo) (n.lens - lo) / (hi - lo) else 0.5)
            }
        }
        return lines + dots
    }

    /**
     * The points as one pointed FLine (first two coordinates), each coloured by its lens value, so
     * you can see what the lens measured.
     */
    fun pointsFLine(pointSize: Double = 2.0, colormap: (Double) -> Vec4 = ::lensColour): FLine {
        val lo = lensValues.min(); val hi = lensValues.max()
        return FLine().also { f ->
            for (i in lensValues.indices) {
                f.moveTo(points[i * dimension], points[i * dimension + 1])
                f.nodes.last().properties["color"] = colormap(if (hi > lo) (lensValues[i] - lo) / (hi - lo) else 0.5)
            }
            f.properties["pointed"] = true; f.properties["stroked"] = false; f.properties["pointSize"] = pointSize
        }
    }

    /**
     * The graph as a [GeometryNetwork]: nodes at their centroids (channels `size`, `lens`), each
     * edge a straight curve (attribute `shared`).
     */
    fun network(): GeometryNetwork = GeometryNetwork(
        dimension,
        GeometryNodes(dimension, IntArray(nodes.size) { it }, DoubleArray(nodes.size * dimension) { nodes[it / dimension].centroid[it % dimension] },
            mapOf("size" to Channel.scalar(DoubleArray(nodes.size) { nodes[it].size.toDouble() }),
                "lens" to Channel.scalar(DoubleArray(nodes.size) { nodes[it].lens }))),
        edges.mapIndexed { i, e -> GeometryCurve(i, dimension, nodes[e.a].centroid + nodes[e.b].centroid, e.a, e.b, attributes = mapOf("shared" to e.shared.toDouble())) },
        mapOf("source" to "mapper"),
    )

    override fun toString() = "MapperGraph(${nodes.size} nodes, ${edges.size} edges, ${components().size} component(s), $loops loop(s))"

    companion object {
        /** Field's viridis ramp ([Colormaps]) for lens values in 0..1. */
        fun lensColour(t: Double): Vec4 = Colormaps.sample("viridis", t.coerceIn(0.0, 1.0)).let { Vec4(it.r.toFloat(), it.g.toFloat(), it.b.toFloat(), 1f) }
    }
}

/**
 * Mapper (Singh, Mémoli and Carlsson, 2007): a graph summarising the shape of a point cloud.
 *
 * ```
 * val graph = Mapper.compute(points, lens = Mapper.eccentricity(points))
 * display("mapper", lines(graph.toFLines()))
 * ```
 *
 * Every point gets a **lens** value, a number that says something about where it is: a
 * coordinate, how far it is from the middle of the cloud ([eccentricity]), how crowded its
 * neighbourhood is ([density]). The range of the lens is cut into [intervals] overlapping bins
 * ([overlap] is the fraction each bin shares with the next). The points in each bin are clustered
 * (single linkage: two points are in the same cluster if a chain of points joins them with no gap
 * longer than [eps], by default three times the median distance from a point to its 8th nearest
 * neighbour), each cluster is a node, and clusters in neighbouring bins that share points
 * are joined. Clusters smaller than [minClusterSize] are dropped as noise.
 */
object Mapper {
    /** The Mapper graph of [points] (`dimension` coordinates per point) with one lens value per point. */
    fun compute(
        points: DoubleArray,
        dimension: Int = 2,
        lens: DoubleArray,
        intervals: Int = 10,
        overlap: Double = 0.3,
        eps: Double? = null,
        minClusterSize: Int = 3,
    ): MapperGraph {
        require(dimension >= 1 && points.size % dimension == 0) { "points.size ${points.size} is not a multiple of dimension $dimension" }
        val n = points.size / dimension
        require(lens.size == n) { "lens has ${lens.size} values for $n points" }
        require(intervals >= 1) { "intervals must be at least 1" }
        require(overlap in 0.0..0.95) { "overlap must be in 0..0.95, got $overlap" }
        if (n == 0) return MapperGraph(dimension, points, lens, emptyList(), emptyList())
        val linkage = eps ?: defaultEps(points, dimension)
        val lo = lens.min(); val hi = lens.max()
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        // bins of length L overlapping by overlap * L, the first starting at lo, the last ending at hi
        val length = span / (intervals - (intervals - 1) * overlap)
        val stride = length * (1 - overlap)
        val nodes = ArrayList<MapperNode>()
        for (b in 0 until intervals) {
            val a0 = lo + b * stride; val a1 = if (b == intervals - 1) hi else a0 + length
            val inBin = (0 until n).filter { lens[it] >= a0 - 1e-12 && lens[it] <= a1 + 1e-12 }.toIntArray()
            for (cluster in singleLinkage(points, dimension, inBin, linkage)) {
                if (cluster.size < minClusterSize) continue
                val c = DoubleArray(dimension)
                cluster.forEach { p -> for (d in 0 until dimension) c[d] += points[p * dimension + d] }
                for (d in 0 until dimension) c[d] /= cluster.size
                nodes.add(MapperNode(nodes.size, b, cluster, c, cluster.sumOf { lens[it] } / cluster.size))
            }
        }
        val edges = ArrayList<MapperEdge>()
        val byBin = nodes.groupBy { it.bin }
        for ((b, here) in byBin) for (next in byBin[b + 1] ?: emptyList()) for (h in here) {
            val shared = h.members.toSet().intersect(next.members.toSet()).size
            if (shared > 0) edges.add(MapperEdge(h.id, next.id, shared))
        }
        return MapperGraph(dimension, points, lens, nodes, edges)
    }

    /** The Mapper graph of 2D [points]. */
    fun compute(points: List<Vec2>, lens: DoubleArray, intervals: Int = 10, overlap: Double = 0.3, eps: Double? = null, minClusterSize: Int = 3): MapperGraph =
        compute(flatten(points), 2, lens, intervals, overlap, eps, minClusterSize)

    /** Each point's first coordinate ([axis] 0), second (1), and so on: a lens that slices the cloud. */
    fun coordinate(points: DoubleArray, dimension: Int = 2, axis: Int = 0): DoubleArray =
        DoubleArray(points.size / dimension) { points[it * dimension + axis] }

    /**
     * Each point's mean distance to every other point: small in the middle of the cloud, large at
     * its extremities. Picks out the ends of branches whatever their direction. `O(n²)`.
     */
    fun eccentricity(points: DoubleArray, dimension: Int = 2): DoubleArray {
        val n = points.size / dimension
        val out = DoubleArray(n)
        for (i in 0 until n) for (j in i + 1 until n) {
            val d = sqrt(dist2(points, dimension, i, j)); out[i] += d; out[j] += d
        }
        for (i in 0 until n) out[i] /= max(1, n - 1)
        return out
    }

    /**
     * Each point's distance to its [k]th nearest neighbour, negated, so larger is more crowded: a
     * lens that separates dense cores from sparse fringes. `O(n²)`.
     */
    fun density(points: DoubleArray, dimension: Int = 2, k: Int = 8): DoubleArray {
        val n = points.size / dimension
        return DoubleArray(n) { i -> -sqrt(kthNearest2(points, dimension, i, min(k, n - 1))) }
    }

    fun eccentricity(points: List<Vec2>) = eccentricity(flatten(points))
    fun density(points: List<Vec2>, k: Int = 8) = density(flatten(points), 2, k)

    private fun flatten(points: List<Vec2>) = DoubleArray(points.size * 2) { if (it % 2 == 0) points[it / 2].x.toDouble() else points[it / 2].y.toDouble() }

    private fun dist2(p: DoubleArray, dim: Int, i: Int, j: Int): Double {
        var s = 0.0
        for (d in 0 until dim) { val x = p[i * dim + d] - p[j * dim + d]; s += x * x }
        return s
    }

    private fun kthNearest2(p: DoubleArray, dim: Int, i: Int, k: Int): Double {
        if (k <= 0) return 0.0
        val n = p.size / dim
        val best = DoubleArray(k) { Double.MAX_VALUE }
        for (j in 0 until n) if (j != i) {
            val d = dist2(p, dim, i, j)
            if (d < best[k - 1]) {
                var m = k - 1
                while (m > 0 && best[m - 1] > d) { best[m] = best[m - 1]; m-- }
                best[m] = d
            }
        }
        return best[k - 1]
    }

    /**
     * Three times the median distance to the 8th nearest neighbour, over up to 500 sampled points:
     * wide enough to bridge the gaps random sampling leaves along a thin strand.
     */
    internal fun defaultEps(p: DoubleArray, dim: Int): Double {
        val n = p.size / dim
        if (n < 2) return 1.0
        val stride = max(1, n / 500)
        val ds = (0 until n step stride).map { sqrt(kthNearest2(p, dim, it, min(8, n - 1))) }.sorted()
        return 3 * ds[ds.size / 2]
    }

    /** Connected components of the [eps]-neighbourhood graph on [subset]. */
    private fun singleLinkage(p: DoubleArray, dim: Int, subset: IntArray, eps: Double): List<IntArray> {
        val m = subset.size
        val parent = IntArray(m) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; var y = x; while (parent[y] != r) { val z = parent[y]; parent[y] = r; y = z }; return r }
        val e2 = eps * eps
        // sort along the first coordinate so each point only looks ahead within eps
        val order = (0 until m).sortedBy { p[subset[it] * dim] }
        for (a in order.indices) {
            val i = order[a]; val xi = p[subset[i] * dim]
            var b = a + 1
            while (b < order.size && p[subset[order[b]] * dim] - xi <= eps) {
                val j = order[b]
                if (dist2(p, dim, subset[i], subset[j]) <= e2) parent[find(i)] = find(j)
                b++
            }
        }
        return (0 until m).groupBy { find(it) }.values.map { g -> IntArray(g.size) { subset[g[it]] } }
    }
}
