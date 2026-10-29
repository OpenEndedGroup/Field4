package afield.geometry.curves.principal

import afield.geometry.network.Channel
import afield.geometry.network.GeometryCurve
import afield.geometry.network.GeometryNetwork
import afield.geometry.network.GeometryNodes
import afield.geometry.path.FLine
import afield.math.linalg.Vec3
import afield.math.linalg.Vec4
import afield.runtime.lifecycle.ReleasePolicy
import afield.runtime.lifecycle.RunScope
import java.lang.foreign.MemorySegment
import java.lang.ref.Cleaner
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Elastic principal graphs: smooth curves, trees and forests fitted through a 2-D or 3-D point
 * cloud, as a port of ElPiGraph.
 *
 * [PrincipalCurves] is the entry point, and what it hands back is not a finished answer but an
 * optimisation state. A [PrincipalForest] can be fitted again, grown with more nodes under one of
 * the graph grammars [Growth] offers, have nodes moved and pinned, and be read back at any point
 * along the way: as node positions and edges, as FLines, or as a [GeometryNetwork] whose channels
 * carry the per-node measurements. So pinning a node, replacing the points under a fitted graph, or
 * adding nodes to it continues from where the fit had reached rather than starting over.
 *
 * [ElasticParameters] holds the energy the fit minimises, [ElasticEnergy] its value split by term,
 * [PointAssignments] which node and edge each point ended up on, and [NodeStatistics] the spread
 * of the points around each node, which is what the ribbon widths are drawn from.
 *
 * The solver is the optional native library `field_principal_curves`
 * ([PrincipalCurvesLibrary]), so a forest holds native memory, is closed by the box's next run,
 * and `PrincipalCurves.isAvailable` reports whether the library loaded at all. The worked account
 * is `public/docs/principal_curves.md`.
 */

/**
 * The weights of the elastic energy and the solver's controls.
 *
 * [lambda] penalises stretching (the squared length of every edge) and so total length; [mu]
 * penalises bending (how far each node of degree two or more sits from the mean of its
 * neighbours). Larger values give shorter, smoother graphs that fit the points less closely.
 * Both are relative to the mean squared distance from the points to the graph, so they mean the
 * same thing at any scale only if the data's scale is similar; ElPiGraph's defaults (0.01, 0.1)
 * suit data whose spread is a few units to a few hundred.
 *
 * [trimmingRadius] makes the fit robust: points farther than this from every node stop pulling
 * (0 turns it off). [branchPenalty] is added per unit of excess degree at branch points when
 * choosing how to grow, so a branch must reduce the energy by more than it to be kept.
 */
data class ElasticParameters(
    val lambda: Double = 0.01,
    val mu: Double = 0.1,
    val trimmingRadius: Double = 0.0,
    val branchPenalty: Double = 0.0,
    val maxIterations: Int = 30,
    val eps: Double = 1e-3,
    val candidateSample: Int = 5000,
    val threads: Int = 0,
    val seed: Long = 1,
)

/** Which graph-grammar operations growth may use. */
enum class Growth(internal val flags: Int) {
    /** Split edges and extend leaves: every component stays a curve (or a loop). */
    CURVES(PrincipalCurvesNative.BISECT or PrincipalCurvesNative.EXTEND),
    /** Also add nodes to interior nodes, so components can branch into trees. */
    TREES(PrincipalCurvesNative.BISECT or PrincipalCurvesNative.EXTEND or PrincipalCurvesNative.BRANCH),
    /** Only split edges: the shape and topology stay, the curve gets more nodes. */
    REFINE(PrincipalCurvesNative.BISECT),
}

/** The elastic energy and its parts; [trimmedFraction] is the share of point weight beyond the trimming radius. */
data class ElasticEnergy(val total: Double, val approximation: Double, val stretching: Double, val bending: Double, val trimmedFraction: Double)

/**
 * Where each point went: its nearest [node], the nearest [edge] at that node (-1 if trimmed or if
 * the node has no edges), the parameter [t] along that edge from its first node, and its [distance]
 * to the graph.
 */
class PointAssignments(val node: IntArray, val edge: IntArray, val t: DoubleArray, val distance: DoubleArray)

/**
 * Per-node statistics of the points projected onto the graph. Each point is shared between the two
 * ends of its edge by its parameter.
 *
 * [spread] is the variance of the points across the curve: one value per node in 2D, and in 3D a
 * 2 × 2 covariance (uu, uv, vv) per node in the normal-plane basis [basis] (u then v, 6 values per
 * node). [width] is its square root (in 3D, of uu + vv). [standardError] is how well the node's
 * position is determined, sqrt(spread / nEff). Spread describes the data and standard error the
 * fit: a broad, densely sampled ribbon has a large spread and a small error.
 */
class NodeStatistics(
    val dimension: Int,
    val support: DoubleArray,
    val nEff: DoubleArray,
    val residual: DoubleArray,
    val spread: DoubleArray,
    val standardError: DoubleArray,
    val tangent: DoubleArray,
    val basis: DoubleArray,
) {
    val width: DoubleArray = DoubleArray(support.size) { i ->
        if (dimension == 2) sqrt(spread[i]) else sqrt(spread[3 * i] + spread[3 * i + 2])
    }
}

/**
 * An elastic principal graph fitted to a point cloud, kept as an optimisation state: refine it,
 * grow it, move or pin nodes, split edges, and read it back at any point.
 *
 * ```
 * val forest = PrincipalCurves.fit(points, dimension = 2, curves = 2, nodes = 40)
 * display("fit", forest.toFLines())
 * forest.moveNode(3, 120.0, 80.0)          // pin a node where you want it
 * forest.fit()                             // and let the rest settle around it
 * ```
 *
 * The forest holds native memory. One made while a box runs is closed when the box runs again;
 * outside a box run, [close] it yourself.
 */
class PrincipalForest internal constructor(
    val dimension: Int,
    points: DoubleArray,
    handle: MemorySegment,
    parameters: ElasticParameters,
) : AutoCloseable {
    private class Owner(var handle: MemorySegment?) : Runnable {
        override fun run() { synchronized(this) { handle?.let(PrincipalCurvesNative::free); handle = null } }
    }

    private val owner = Owner(handle)
    private val cleanable = CLEANER.register(this, owner)

    private fun <T> native(body: (MemorySegment) -> T): T = synchronized(owner) {
        body(owner.handle ?: throw IllegalStateException("this PrincipalForest has been closed"))
    }

    private var points: DoubleArray = points

    val pointCount: Int get() = points.size / dimension

    /**
     * Replaces the points (and weights), keeping the graph, so that [refine] follows data that moves:
     * a particle system, a stipple that changes, a camera feed's bright pixels.
     */
    fun setPoints(points: DoubleArray, weights: DoubleArray? = null) = apply {
        require(points.size % dimension == 0) { "points must hold $dimension values per point" }
        weights?.let { require(it.size == points.size / dimension) { "one weight per point" } }
        native { PrincipalCurvesNative.setPoints(it, dimension, points, weights) }
        this.points = points.copyOf()
    }

    var parameters: ElasticParameters = parameters
        set(value) { native { PrincipalCurvesNative.setParams(it, value) }; field = value }

    /**
     * Replaces the graph with [components] separate chains of [nodesEach] nodes. The points are
     * clustered by single linkage (k-means centres joined by their minimum spanning tree, cut at its
     * longest edges), and each chain starts along the longest path through its cluster, so curved
     * strands are followed rather than cut across.
     */
    fun initComponents(components: Int = 1, nodesEach: Int = 3) = apply { native { PrincipalCurvesNative.initComponents(it, components, nodesEach) } }

    /** Replaces the graph with the minimum spanning tree of [k] k-means centres. */
    fun initTree(k: Int) = apply { native { PrincipalCurvesNative.initTree(it, k) } }

    /** Replaces the graph with one given as node positions ([dimension] per node) and edges (pairs of node indices). */
    fun initGraph(nodePositions: DoubleArray, edges: IntArray) = apply {
        require(nodePositions.size % dimension == 0) { "node positions must be a multiple of $dimension" }
        native { PrincipalCurvesNative.setGraph(it, nodePositions, nodePositions.size / dimension, edges) }
    }

    /**
     * Replaces the graph with [lines]: each subpath becomes a chain of [nodesPerCurve] nodes evenly
     * spaced along it, closed subpaths become loops, and separate subpaths stay separate. Draw a few
     * strokes, then [fit] or [grow] them onto the points.
     */
    fun initFromFLines(lines: List<FLine>, nodesPerCurve: Int = 8) = apply {
        val net = GeometryNetwork.fromFLines(lines, dimension, samplesPerSegment = 16)
        val pos = ArrayList<Double>()
        val edges = ArrayList<Int>()
        net.curves.forEach { c ->
            val samples = resample(c, if (c.closed) nodesPerCurve else nodesPerCurve.coerceAtLeast(2))
            val base = pos.size / dimension
            samples.forEach { pos.add(it) }
            val count = samples.size / dimension
            for (i in 1 until count) { edges.add(base + i - 1); edges.add(base + i) }
            if (c.closed && count > 2) { edges.add(base + count - 1); edges.add(base) }
        }
        require(pos.isNotEmpty()) { "no subpaths with two or more points in these lines" }
        initGraph(pos.toDoubleArray(), edges.toIntArray())
    }

    private fun resample(c: GeometryCurve, count: Int): List<Double> {
        val n = c.vertexCount
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + c.vertex(i).distance(c.vertex(i - 1)).toDouble()
        val total = cum.last() + if (c.closed) c.vertex(0).distance(c.vertex(n - 1)).toDouble() else 0.0
        val out = ArrayList<Double>()
        val steps = if (c.closed) count else count - 1
        for (s in 0 until count) {
            val target = total * s / steps
            var i = cum.indexOfLast { it <= target }.coerceIn(0, n - 1)
            val a = c.vertex(i)
            val b = if (i + 1 < n) c.vertex(i + 1) else c.vertex(0)
            val segment = (if (i + 1 < n) cum[i + 1] else total) - cum[i]
            val u = if (segment > 0) ((target - cum[i]) / segment).toFloat() else 0f
            out.add((a.x + (b.x - a.x) * u).toDouble()); out.add((a.y + (b.y - a.y) * u).toDouble())
            if (dimension == 3) out.add((a.z + (b.z - a.z) * u).toDouble())
        }
        return out
    }

    /** Fits node positions to the points without changing the topology. Returns the iterations used. */
    fun fit(iterations: Int = 0): Int = native { PrincipalCurvesNative.fit(it, iterations) }

    /** [fit] with a small iteration budget, for refining a little at a time (from a frame loop, say). */
    fun refine(iterations: Int = 5): Int = fit(iterations)

    /**
     * Grows the graph by grammar operations until it has [nodes] nodes, choosing at each step the
     * operation whose fitted configuration has the lowest energy. Returns the steps applied.
     */
    fun grow(nodes: Int, growth: Growth = Growth.CURVES): Int = native { PrincipalCurvesNative.grow(it, nodes, growth.flags) }

    /** Moves [node] to ([x], [y], [z]) and pins it there (unless [fixed] is false). Call [fit] to settle the rest. */
    fun moveNode(node: Int, x: Double, y: Double, z: Double = 0.0, fixed: Boolean = true) = apply {
        val p = if (dimension == 2) doubleArrayOf(x, y) else doubleArrayOf(x, y, z)
        native { PrincipalCurvesNative.setNode(it, node, p, fixed) }
    }

    /** Pins or frees [node] where it is. */
    fun fixNode(node: Int, fixed: Boolean = true) = apply { native { PrincipalCurvesNative.setNode(it, node, null, fixed) } }

    /** Inserts a node on [edge] at parameter [t] (0..1 from its first node). Returns the new node's index. */
    fun splitEdge(edge: Int, t: Double = 0.5): Int = native { PrincipalCurvesNative.splitEdge(it, edge, t) }

    private fun state() = native { PrincipalCurvesNative.graph(it, dimension) }

    /** Node positions, [dimension] doubles per node, in node-index order. */
    val nodePositions: DoubleArray get() = state().positions

    /** Edges as pairs of node indices. */
    val edges: IntArray get() = state().edges

    val nodeCount: Int get() = state().fixed.size

    fun energy(): ElasticEnergy = native { PrincipalCurvesNative.energy(it) }.let { ElasticEnergy(it[0], it[1], it[2], it[3], it[4]) }

    fun assignments(): PointAssignments = native { PrincipalCurvesNative.assignments(it) }.let { PointAssignments(it.node, it.edge, it.t, it.distance) }

    fun nodeStatistics(): NodeStatistics = native { PrincipalCurvesNative.nodeStats(it, dimension) }.let {
        NodeStatistics(dimension, it.support, it.nEff, it.residual, it.spread, it.standardError, it.tangent, it.basis)
    }

    /**
     * The graph as a [GeometryNetwork]. Nodes are the graph's branch points and endpoints (node index
     * as id); curves are the chains between them, so a curve's interior vertices are graph nodes of
     * degree two. A component with no endpoints is one closed curve. Per-vertex samples: `width`,
     * `spread`, `support`, `nEff`, `residual`, `standardError`, `graphNode`, and in 3D `covariance`
     * (uu, uv, vv). Node channels: `degree`, `fixed`.
     */
    val network: GeometryNetwork get() = buildNetwork(state(), nodeStatistics())

    private fun buildNetwork(s: PrincipalCurvesNative.GraphState, stats: NodeStatistics): GeometryNetwork {
        val k = s.fixed.size
        val adj = Array(k) { ArrayList<Pair<Int, Int>>() }  // (neighbour, edge)
        for (e in 0 until s.edges.size / 2) {
            adj[s.edges[2 * e]].add(s.edges[2 * e + 1] to e)
            adj[s.edges[2 * e + 1]].add(s.edges[2 * e] to e)
        }
        val ends = (0 until k).filter { adj[it].size != 2 }
        val used = BooleanArray(s.edges.size / 2)
        val chains = ArrayList<Triple<List<Int>, Int, Boolean>>()  // nodes, first edge, closed
        fun walk(start: Int, firstNeighbour: Int, firstEdge: Int): List<Int> {
            val path = arrayListOf(start)
            var prev = start; var cur = firstNeighbour; var edge = firstEdge
            used[edge] = true
            while (true) {
                path.add(cur)
                if (adj[cur].size != 2 || cur == start) break
                val next = adj[cur].first { it.second != edge }
                if (used[next.second]) break
                used[next.second] = true
                prev = cur; cur = next.first; edge = next.second
            }
            return path
        }
        ends.forEach { a -> adj[a].forEach { (b, e) -> if (!used[e]) chains.add(Triple(walk(a, b, e), e, false)) } }
        for (e in used.indices) if (!used[e]) {  // remaining edges belong to loops
            val a = s.edges[2 * e]; val b = s.edges[2 * e + 1]
            chains.add(Triple(walk(a, b, e), e, true))
        }
        val d = dimension
        val curves = chains.map { (path, firstEdge, closed) ->
            val verts = if (closed) path.dropLast(1) else path
            fun col(f: (Int) -> Double) = Channel.scalar(DoubleArray(verts.size) { f(verts[it]) })
            val samples = linkedMapOf(
                "width" to col { stats.width[it] },
                "support" to col { stats.support[it] },
                "nEff" to col { stats.nEff[it] },
                "residual" to col { stats.residual[it] },
                "standardError" to col { stats.standardError[it] },
                "graphNode" to col { it.toDouble() },
            )
            if (d == 2) samples["spread"] = col { stats.spread[it] }
            else samples["covariance"] = Channel(3, DoubleArray(verts.size * 3) { stats.spread[3 * verts[it / 3] + it % 3] })
            GeometryCurve(
                id = firstEdge, dimension = d,
                coordinates = DoubleArray(verts.size * d) { s.positions[verts[it / d] * d + it % d] },
                startNode = if (closed) null else path.first(), endNode = if (closed) null else path.last(),
                closed = closed, samples = samples,
                attributes = mapOf("meanWidth" to verts.map { stats.width[it] }.average()),
            )
        }
        val nodes = GeometryNodes(
            d, ends.toIntArray(), DoubleArray(ends.size * d) { s.positions[ends[it / d] * d + it % d] },
            mapOf(
                "degree" to Channel.scalar(DoubleArray(ends.size) { adj[ends[it]].size.toDouble() }),
                "fixed" to Channel.scalar(DoubleArray(ends.size) { s.fixed[ends[it]].toDouble() }),
            ),
        )
        val e = energy()
        return GeometryNetwork(d, nodes, curves, mapOf(
            "source" to "principalCurves", "energy" to e.total.toString(), "lambda" to parameters.lambda.toString(),
            "mu" to parameters.mu.toString(), "library" to PrincipalCurvesNative.version(),
        ))
    }

    /** The fitted curves as FLines (splines through the nodes with [smooth]), each carrying `geometryNetwork`. */
    fun toFLines(smooth: Boolean = true, color: Vec4 = Vec4(0.9f, 0.3f, 0.1f, 1f), thickness: Double = 2.0): List<FLine> {
        val net = network
        return net.toFLines(smooth).onEach { it.properties["color"] = color; it.properties["thickness"] = thickness; it.properties["geometryNetwork"] = net }
    }

    /**
     * 2D only: a filled ribbon around each curve, [scale] standard deviations of the transverse
     * spread to each side, so its width shows how spread out the points are along the curve.
     */
    fun widthFLines(scale: Double = 2.0, color: Vec4 = Vec4(0.95f, 0.55f, 0.2f, 0.3f)): List<FLine> {
        require(dimension == 2) { "width ribbons are drawn in 2D; in 3D read nodeStatistics().spread" }
        return network.curves.filter { it.vertexCount >= 2 }.map { c ->
            val w = c.samples.getValue("width")
            val n = c.vertexCount
            fun normal(i: Int): Pair<Double, Double> {
                val a = c.vertex(maxOf(0, i - 1)); val b = c.vertex(minOf(n - 1, i + 1))
                val dx = (b.x - a.x).toDouble(); val dy = (b.y - a.y).toDouble()
                val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-12)
                return -dy / len to dx / len
            }
            val left = (0 until n).map { i -> val (nx, ny) = normal(i); Vec3(c.vertex(i).x + (nx * w[i] * scale).toFloat(), c.vertex(i).y + (ny * w[i] * scale).toFloat(), 0f) }
            val right = (0 until n).map { i -> val (nx, ny) = normal(i); Vec3(c.vertex(i).x - (nx * w[i] * scale).toFloat(), c.vertex(i).y - (ny * w[i] * scale).toFloat(), 0f) }
            FLine().also { f ->
                f.moveTo(left[0]); left.drop(1).forEach { f.lineTo(it) }; right.reversed().forEach { f.lineTo(it) }; f.close()
                f.properties["filled"] = true; f.properties["stroked"] = false; f.properties["fill"] = color; f.properties["color"] = color
            }
        }
    }

    /** Short lines from every [every]th point to where it projects onto the graph, coloured by edge. */
    fun assignmentFLines(every: Int = 1, color: Vec4 = Vec4(0.3f, 0.3f, 0.3f, 0.35f)): FLine {
        val a = assignments()
        val s = state()
        val d = dimension
        val f = FLine()
        for (i in 0 until pointCount step every.coerceAtLeast(1)) {
            val e = a.edge[i]
            if (e < 0) continue
            val p = s.edges[2 * e]; val q = s.edges[2 * e + 1]; val t = a.t[i]
            fun at(j: Int) = (s.positions[p * d + j] * (1 - t) + s.positions[q * d + j] * t).toFloat()
            f.moveTo(points[i * d], points[i * d + 1], if (d == 3) points[i * d + 2] else 0.0)
            f.lineTo(at(0), at(1), if (d == 3) at(2) else 0f)
        }
        f.properties["color"] = color
        f.properties["thickness"] = 0.6
        return f
    }

    /** The input points as one FLine of dots, for display. */
    fun pointsFLine(pointSize: Double = 2.0, color: Vec4 = Vec4(0.2f, 0.4f, 0.8f, 0.6f)): FLine = FLine().also { f ->
        for (i in 0 until pointCount) f.moveTo(points[i * dimension], points[i * dimension + 1], if (dimension == 3) points[i * dimension + 2] else 0.0)
        f.properties["pointed"] = true; f.properties["stroked"] = false; f.properties["pointSize"] = pointSize; f.properties["color"] = color
    }

    val isClosed: Boolean get() = synchronized(owner) { owner.handle == null }

    override fun close() = cleanable.clean()

    override fun toString() = "PrincipalForest(${dimension}D, $pointCount points, $nodeCount nodes, ${edges.size / 2} edges)"

    companion object {
        private val CLEANER: Cleaner = Cleaner.create()
    }
}

/**
 * Principal curves, trees and forests: smooth curves fitted through weighted 2D or 3D point clouds,
 * as elastic principal graphs (a port of ElPiGraph). Needs the optional native library
 * `field_principal_curves` (see [PrincipalCurvesLibrary]).
 *
 * ```
 * val forest = PrincipalCurves.fit(points, dimension = 2, curves = 3, nodes = 60)
 * display("fit", forest.pointsFLine() + forest.widthFLines() + forest.toFLines())
 * ```
 */
object PrincipalCurves {
    private val workers = Executors.newFixedThreadPool(2) { r -> Thread(r, "Field-principal-curves").apply { isDaemon = true } }

    val isAvailable: Boolean get() = PrincipalCurvesLibrary.isAvailable

    /** A forest over [points] ([dimension] doubles per point) with no graph yet; initialise it, then fit or grow. */
    fun create(points: DoubleArray, dimension: Int = 2, weights: DoubleArray? = null, parameters: ElasticParameters = ElasticParameters()): PrincipalForest {
        require(dimension == 2 || dimension == 3) { "dimension must be 2 or 3" }
        require(points.size % dimension == 0) { "points must hold $dimension values per point" }
        weights?.let { require(it.size == points.size / dimension) { "one weight per point" } }
        val forest = PrincipalForest(dimension, points.copyOf(), PrincipalCurvesNative.create(dimension, points, weights, parameters), parameters)
        RunScope.current()?.own(forest, ReleasePolicy.ACCUMULATING)
        return forest
    }

    /** A forest over 2D [points]. */
    fun create(points: List<afield.math.linalg.Vec2>, weights: DoubleArray? = null, parameters: ElasticParameters = ElasticParameters()): PrincipalForest =
        create(DoubleArray(points.size * 2) { if (it % 2 == 0) points[it / 2].x.toDouble() else points[it / 2].y.toDouble() }, 2, weights, parameters)

    /**
     * Fits [curves] curves (separate components, one per k-means cluster to start with) with
     * [nodes] nodes in all. With [allowBranching] the components may grow into trees. With [from],
     * starts from those strokes instead, keeping their components and closed loops.
     */
    fun fit(
        points: DoubleArray,
        dimension: Int = 2,
        weights: DoubleArray? = null,
        curves: Int = 1,
        nodes: Int = 30,
        allowBranching: Boolean = false,
        parameters: ElasticParameters = ElasticParameters(),
        from: List<FLine>? = null,
    ): PrincipalForest {
        val forest = create(points, dimension, weights, parameters)
        try {
            // Start each chain with half its share of the nodes: a chain of three can have its end
            // captured by a neighbouring strand during the first fit, and growth does not undo that.
            val each = maxOf(3, nodes / curves / 2)
            if (from != null) forest.initFromFLines(from, nodesPerCurve = maxOf(4, each)) else forest.initComponents(curves, each)
            forest.grow(maxOf(nodes, forest.nodeCount), if (allowBranching) Growth.TREES else Growth.CURVES)
        } catch (t: Throwable) {
            forest.close(); throw t
        }
        return forest
    }

    /** [fit] on a worker thread, for `runAsync`. A forest made off the box's thread is yours to close. */
    fun fitAsync(points: DoubleArray, dimension: Int = 2, weights: DoubleArray? = null, curves: Int = 1, nodes: Int = 30,
                 allowBranching: Boolean = false, parameters: ElasticParameters = ElasticParameters()): CompletableFuture<PrincipalForest> =
        CompletableFuture.supplyAsync({ fit(points, dimension, weights, curves, nodes, allowBranching, parameters) }, workers)
}
