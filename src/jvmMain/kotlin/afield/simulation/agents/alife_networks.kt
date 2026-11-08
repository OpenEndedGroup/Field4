@file:afield.runtime.completion.CompletionCategory("ALife")

package afield.simulation.agents

import afield.data.graph.PointGraph
import afield.data.graph.vec2DelaunayGraph
import afield.data.graph.vec2MinimumSpanningTree
import afield.graphics.color.Color4
import afield.math.linalg.Vec2
import afield.math.linalg.vec
import afield.media.image.Img
import afield.media.image.withLabel
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Physarum as a transport-network designer, after Tero et al., "Rules for biologically inspired
 * adaptive network design", *Science* 327 (2010).
 *
 * Tero's slime mould, grown over oat flakes placed like the cities around Tokyo, built a network
 * comparable to the rail system on three numbers: cost (total length), transport efficiency (how
 * much longer than the straight line a trip between two cities is) and fault tolerance (how likely
 * one broken link is to cut a city off). This file gives Jones' agent model the same food sources
 * and measures the network it grows the same way:
 *
 * - [PhysarumFood] feeds the trail at fixed points every step, for the CPU [PhysarumSwarm.step]
 *   (the GPU [ShaderPhysarumSystem] has the same as [ShaderPhysarumSettings.food]).
 * - [TransportNetwork.extract] thresholds a trail, thins it to a one-pixel skeleton and reads that
 *   as a graph whose nodes are the food sources and the junctions between them.
 * - [TransportNetwork.score] gives Tero's three numbers, [NetworkScore].
 * - [TransportNetwork.mst], [TransportNetwork.delaunay] and [TransportNetwork.complete] are the
 *   controls: the cheapest network that connects every source, and two meshes.
 * - [costTolerancePlot] draws any number of scored networks on the cost against fault-tolerance
 *   plane, where Tero compared mould and railway.
 */

/**
 * Food sources for a Physarum population: points that add [deposit] to the trail every step, over a
 * square of side `2 * radius + 1` cells, so the network has somewhere it must reach.
 *
 * With [recycle] (the default) the field also gets an edge. An agent that would step within a cell
 * of it re-enters at a random food source instead. That turns the agent count into a budget of
 * material: agents that wander off are not lost to the far side of a torus but come back into the
 * network, and once every agent is on a road the total road length is set by how many there are.
 * Few agents give a tree, more give loops.
 */
data class PhysarumFood(
    /** Where the food is, in cells. */
    val points: List<Vec2>,
    /** Trail added per cell of each source per step. Ten times an agent's deposit is a good start. */
    val deposit: Double = 50.0,
    /** Half-width of each source's square, in cells. */
    val radius: Int = 1,
    /** Give the field an edge and send agents that reach it back in at a random source. */
    val recycle: Boolean = true
) {
    init {
        require(deposit >= 0.0) { "deposit must be >= 0, got $deposit" }
        require(radius >= 0) { "radius must be >= 0, got $radius" }
    }

    /** Add one step of food to a one-channel [trail]. */
    fun depositInto(trail: Img) {
        val w = trail.width
        val h = trail.height
        val d = deposit.toFloat()
        for (p in points) {
            val cx = p.x.roundToInt()
            val cy = p.y.roundToInt()
            for (dy in -radius..radius) for (dx in -radius..radius) {
                val x = Math.floorMod(cx + dx, w)
                val y = Math.floorMod(cy + dy, h)
                trail.data[y * w + x] += d
            }
        }
    }

    companion object {
        /**
         * [count] sources scattered in the middle of a `width x height` field, at least [spacing]
         * cells apart and [margin] cells from the edge. Deterministic for a given [seed].
         */
        fun scattered(width: Int, height: Int, count: Int, margin: Double = 0.15 * min(width, height),
                      spacing: Double = 0.12 * min(width, height), seed: Long = 1L,
                      deposit: Double = 50.0): PhysarumFood {
            require(count >= 1) { "count must be >= 1, got $count" }
            val rng = kotlin.random.Random(seed)
            val out = ArrayList<Vec2>()
            var tries = 0
            while (out.size < count) {
                val gap = if (tries++ < 20_000) spacing else spacing * 0.5
                val x = margin + rng.nextDouble() * (width - 2 * margin)
                val y = margin + rng.nextDouble() * (height - 2 * margin)
                if (out.all { hypot(it.x - x, it.y - y) >= gap }) out += vec(x, y)
            }
            return PhysarumFood(out, deposit)
        }
    }
}

/** One link of a [TransportNetwork]: two node indices, its length, and the polyline it follows. */
data class NetworkEdge(val a: Int, val b: Int, val length: Double, val path: List<Vec2>)

/**
 * Tero's three numbers for one network, plus what is needed to read them.
 *
 * The cost and the stretch are measured against the straight-line distances between the sources,
 * so they compare across layouts; [relativeCost] divides by the minimum spanning tree's length, as
 * Tero did.
 */
data class NetworkScore(
    /** A name for the plot and the table. */
    val label: String,
    /** Total link length, in cells. */
    val length: Double,
    /** [length] divided by the minimum spanning tree of the same sources: 1 is the cheapest possible tree. */
    val relativeCost: Double,
    /**
     * Mean over connected source pairs of (route through the network) / (straight-line distance).
     * 1 is a straight road between every pair, as in the complete graph.
     */
    val stretch: Double,
    /**
     * The chance that a single fault, striking a random point of the network, leaves every source
     * pair that was connected still connected. Faults land in proportion to length, so a long road
     * is likelier to break than a short one. 0 for any tree, 1 for a network with no bridge.
     */
    val faultTolerance: Double,
    /** The same with every link equally likely to fail, whatever its length. */
    val linkFaultTolerance: Double,
    /** Fraction of source pairs joined at all. Below 1 the stretch is over the joined pairs only. */
    val connectedPairs: Double,
    /** Independent loops, `links - nodes + components`: 0 for a tree. */
    val loops: Int,
    val nodes: Int,
    val links: Int
) {
    /** One row of [table]. */
    fun row(): String = "%-22s cost %6.0f (%.2f x MST)  stretch %.3f  fault tolerance %.2f  loops %3d  connected %.2f"
        .format(label, length, relativeCost, stretch, faultTolerance, loops, connectedPairs)

    override fun toString(): String = row()

    companion object {
        /** [row] for each score, one per line. */
        fun table(scores: List<NetworkScore>): String = scores.joinToString("\n") { it.row() }
    }
}

/**
 * A network between food sources: nodes at the sources and at junctions, links with lengths and
 * paths. Build one from a trail with [extract], or from a graph on the sources themselves with
 * [mst], [delaunay], [complete] or [of]; measure it with [score].
 */
class TransportNetwork(
    /** Node positions, in cells. */
    val nodes: List<Vec2>,
    /** Links; parallel links and loops are allowed and count. */
    val edges: List<NetworkEdge>,
    /** The food sources, in cells. */
    val sources: List<Vec2>,
    /** For each source, the node it attaches to, or -1 if no road reaches it. */
    val sourceNodes: IntArray
) {
    init {
        require(sourceNodes.size == sources.size) { "one node per source" }
    }

    /** Total link length. */
    val length: Double get() = edges.sumOf { it.length }

    /** Independent loops: `links - nodes + components`, over the nodes that have links. */
    val loops: Int get() {
        val used = HashSet<Int>()
        edges.forEach { used += it.a; used += it.b }
        val uf = UnionFind(nodes.size)
        edges.forEach { uf.union(it.a, it.b) }
        val components = used.map { uf.find(it) }.toSet().size
        return edges.size - used.size + components
    }

    /**
     * Tero's numbers for this network: cost against the minimum spanning tree of the [sources],
     * stretch between every pair of sources, and fault tolerance. See [NetworkScore].
     */
    fun score(label: String = "network"): NetworkScore {
        val n = sources.size
        val mstLength = mst(sources).length
        val dist = Array(n) { s -> if (sourceNodes[s] < 0) null else shortestPaths(sourceNodes[s], edges) }
        var pairs = 0
        var joined = 0
        var stretchSum = 0.0
        for (i in 0 until n) for (j in i + 1 until n) {
            pairs++
            val d = dist[i] ?: continue
            val nj = sourceNodes[j]
            if (nj < 0 || d[nj].isInfinite()) continue
            val straight = hypot(sources[i].x - sources[j].x.toDouble(), sources[i].y - sources[j].y.toDouble())
            if (straight <= 1e-9) continue
            val route = d[nj] + offset(i) + offset(j)
            joined++
            stretchSum += route / straight
        }
        val base = connectedPairCount(edges, -1)
        var cut = 0.0
        var cutLinks = 0
        edges.forEachIndexed { k, e ->
            if (connectedPairCount(edges, k) < base) { cut += e.length; cutLinks++ }
        }
        val total = length
        return NetworkScore(
            label = label,
            length = total,
            relativeCost = if (mstLength > 0) total / mstLength else Double.NaN,
            stretch = if (joined > 0) stretchSum / joined else Double.NaN,
            faultTolerance = if (total > 0) 1.0 - cut / total else 0.0,
            linkFaultTolerance = if (edges.isNotEmpty()) 1.0 - cutLinks.toDouble() / edges.size else 0.0,
            connectedPairs = if (pairs > 0) joined.toDouble() / pairs else 1.0,
            loops = loops,
            nodes = nodes.size,
            links = edges.size
        )
    }

    private fun offset(source: Int): Double {
        val node = nodes[sourceNodes[source]]
        return hypot(node.x - sources[source].x.toDouble(), node.y - sources[source].y.toDouble())
    }

    /** How many source pairs are joined with link [skip] removed (-1 removes nothing). */
    private fun connectedPairCount(edges: List<NetworkEdge>, skip: Int): Int {
        val uf = UnionFind(nodes.size)
        edges.forEachIndexed { k, e -> if (k != skip) uf.union(e.a, e.b) }
        val roots = sourceNodes.filter { it >= 0 }.groupingBy { uf.find(it) }.eachCount()
        return roots.values.sumOf { it * (it - 1) / 2 }
    }

    private fun shortestPaths(from: Int, edges: List<NetworkEdge>): DoubleArray {
        val adj = Array(nodes.size) { ArrayList<NetworkEdge>() }
        edges.forEach { adj[it.a] += it; if (it.b != it.a) adj[it.b] += it }
        val d = DoubleArray(nodes.size) { Double.POSITIVE_INFINITY }
        d[from] = 0.0
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        queue += 0.0 to from
        while (queue.isNotEmpty()) {
            val (du, u) = queue.poll()
            if (du > d[u]) continue
            for (e in adj[u]) {
                val v = if (e.a == u) e.b else e.a
                val nd = du + e.length
                if (nd < d[v]) { d[v] = nd; queue += nd to v }
            }
        }
        return d
    }

    /**
     * Draw the network over [background] (any [Img] of the same field size; one channel is shown
     * as grey): links in [linkColor], nodes as small squares, food sources in [sourceColor] (hollow
     * where no road reaches them). Returns a new three-channel image.
     */
    fun drawOver(background: Img, linkColor: FloatArray = floatArrayOf(0.35f, 0.95f, 1f),
                 sourceColor: FloatArray = floatArrayOf(1f, 1f, 1f), thickness: Int = 1): Img {
        val w = background.width
        val h = background.height
        val out = Img(w, h, 3, FloatArray(w * h * 3) { i ->
            val p = i / 3
            if (background.channels >= 3) background.data[p * background.channels + i % 3]
            else background.data[p * background.channels] * 0.6f
        })
        fun put(x: Int, y: Int, c: FloatArray) {
            if (x !in 0 until w || y !in 0 until h) return
            val o = (y * w + x) * 3
            out.data[o] = c[0]; out.data[o + 1] = c[1]; out.data[o + 2] = c[2]
        }
        fun dot(x: Int, y: Int, r: Int, c: FloatArray) {
            for (dy in -r..r) for (dx in -r..r) put(x + dx, y + dy, c)
        }
        for (e in edges) e.path.zipWithNext().forEach { (a, b) ->
            val steps = max(1, max(abs(b.x - a.x), abs(b.y - a.y)).roundToInt())
            for (s in 0..steps) {
                val t = s.toFloat() / steps
                dot((a.x + (b.x - a.x) * t).toInt(), (a.y + (b.y - a.y) * t).toInt(), thickness / 2, linkColor)
            }
        }
        val attached = sourceNodes.filter { it >= 0 }.toSet()
        nodes.forEachIndexed { i, p -> if (i !in attached) dot(p.x.toInt(), p.y.toInt(), 1, linkColor) }
        sources.forEachIndexed { s, p ->
            val x = p.x.roundToInt()
            val y = p.y.roundToInt()
            if (sourceNodes[s] >= 0) dot(x, y, 2, sourceColor)
            else for (d in -3..3) { put(x + d, y - 3, sourceColor); put(x + d, y + 3, sourceColor); put(x - 3, y + d, sourceColor); put(x + 3, y + d, sourceColor) }
        }
        return out
    }

    override fun toString(): String =
        "TransportNetwork(${nodes.size} nodes, ${edges.size} links, length %.0f, %d of %d sources reached)"
            .format(length, sourceNodes.count { it >= 0 }, sources.size)

    companion object {
        /** A network on the [sources] themselves, one straight link per edge of [graph]. */
        fun of(sources: List<Vec2>, graph: PointGraph): TransportNetwork = TransportNetwork(
            sources, graph.edges.map { e ->
                NetworkEdge(e.i, e.j, hypot(sources[e.i].x - sources[e.j].x.toDouble(), sources[e.i].y - sources[e.j].y.toDouble()),
                    listOf(sources[e.i], sources[e.j]))
            }, sources, IntArray(sources.size) { it }
        )

        /** The minimum spanning tree of the [sources]: the cheapest network that joins them all, and the least tolerant. */
        fun mst(sources: List<Vec2>): TransportNetwork =
            if (sources.size < 2) of(sources, PointGraph(emptyList(), sources.size)) else of(sources, vec2MinimumSpanningTree(sources))

        /** The Delaunay triangulation of the [sources]: a planar mesh with no bridge. */
        fun delaunay(sources: List<Vec2>): TransportNetwork =
            if (sources.size < 3) mst(sources) else of(sources, vec2DelaunayGraph(sources))

        /** A straight link between every pair of [sources]: stretch 1, at the greatest cost. */
        fun complete(sources: List<Vec2>): TransportNetwork {
            val edges = ArrayList<afield.data.graph.GraphEdge>()
            for (i in sources.indices) for (j in i + 1 until sources.size) edges += afield.data.graph.GraphEdge(i, j, 0.0)
            return of(sources, PointGraph(edges, sources.size))
        }

        /**
         * Read the network a trail has grown between [sources].
         *
         * 1. Cells at or above [threshold] are road. For a Jones trail, a quarter of the level a cell
         *    reaches when an agent stands on it every step, `deposit / (1 - decay)`, picks out the
         *    roads and leaves the blind control's diffuse trail below it.
         * 2. Holes in the road smaller than [minHoleArea] cells are filled, so a wide road does not
         *    read as two roads around a pinhole.
         * 3. Zhang–Suen thinning reduces the road to a one-pixel skeleton.
         * 4. Skeleton pixels with other than two neighbours become nodes, as does the skeleton pixel
         *    nearest each source, if it is within [attachRadius]. Runs of pixels between nodes become
         *    links, their length measured along the path simplified to within [simplifyTolerance].
         * 5. Parts with no source, spurs that end anywhere but a source, and nodes that only join two
         *    links are removed; junctions closer than [mergeLength] merge.
         *
         * The outermost cell all round is treated as empty, so the field is read as bounded, not
         * toroidal.
         */
        fun extract(trail: Img, sources: List<Vec2>, threshold: Double, minHoleArea: Int = 40,
                    attachRadius: Double = 8.0, simplifyTolerance: Double = 1.0, mergeLength: Double = 4.0): TransportNetwork {
            require(trail.channels == 1) { "extract reads a one-channel trail; pass trail.channel(c)" }
            val w = trail.width
            val h = trail.height
            val road = BooleanArray(w * h) { i ->
                val x = i % w
                val y = i / w
                x > 0 && y > 0 && x < w - 1 && y < h - 1 && trail.data[i] >= threshold
            }
            fillSmallHoles(road, w, h, minHoleArea)
            thin(road, w, h)
            return graphFromSkeleton(road, w, h, sources, attachRadius, simplifyTolerance, mergeLength)
        }

        private val DX = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
        private val DY = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)

        private fun fillSmallHoles(road: BooleanArray, w: Int, h: Int, maxArea: Int) {
            val seen = BooleanArray(w * h)
            val stack = IntArray(w * h)
            val members = ArrayList<Int>()
            for (start in 0 until w * h) {
                if (road[start] || seen[start]) continue
                var sp = 0
                stack[sp++] = start
                seen[start] = true
                members.clear()
                var touchesEdge = false
                while (sp > 0) {
                    val i = stack[--sp]
                    members += i
                    val x = i % w
                    val y = i / w
                    if (x == 0 || y == 0 || x == w - 1 || y == h - 1) touchesEdge = true
                    for (k in 0 until 8 step 2) {
                        val nx = x + DX[k]
                        val ny = y + DY[k]
                        if (nx !in 0 until w || ny !in 0 until h) continue
                        val j = ny * w + nx
                        if (!road[j] && !seen[j]) { seen[j] = true; stack[sp++] = j }
                    }
                }
                if (!touchesEdge && members.size < maxArea) members.forEach { road[it] = true }
            }
        }

        /** Zhang–Suen thinning, in place; the border is assumed empty. */
        private fun thin(img: BooleanArray, w: Int, h: Int) {
            val remove = ArrayList<Int>()
            val nb = BooleanArray(8)
            var changed = true
            while (changed) {
                changed = false
                for (pass in 0..1) {
                    remove.clear()
                    for (y in 1 until h - 1) for (x in 1 until w - 1) {
                        val i = y * w + x
                        if (!img[i]) continue
                        var count = 0
                        for (k in 0 until 8) { nb[k] = img[(y + DY[k]) * w + x + DX[k]]; if (nb[k]) count++ }
                        if (count < 2 || count > 6) continue
                        var transitions = 0
                        for (k in 0 until 8) if (!nb[k] && nb[(k + 1) % 8]) transitions++
                        if (transitions != 1) continue
                        // nb: 0 N, 1 NE, 2 E, 3 SE, 4 S, 5 SW, 6 W, 7 NW
                        val ok = if (pass == 0) !(nb[0] && nb[2] && nb[4]) && !(nb[2] && nb[4] && nb[6])
                                 else !(nb[0] && nb[2] && nb[6]) && !(nb[0] && nb[4] && nb[6])
                        if (ok) remove += i
                    }
                    if (remove.isNotEmpty()) { changed = true; remove.forEach { img[it] = false } }
                }
            }
        }

        private fun graphFromSkeleton(skel: BooleanArray, w: Int, h: Int, sources: List<Vec2>, attachRadius: Double,
                                      simplifyTolerance: Double, mergeLength: Double): TransportNetwork {
            fun neighbours(i: Int): List<Int> {
                val x = i % w
                val y = i / w
                val out = ArrayList<Int>(8)
                for (k in 0 until 8) {
                    val nx = x + DX[k]
                    val ny = y + DY[k]
                    if (nx in 0 until w && ny in 0 until h && skel[ny * w + nx]) out += ny * w + nx
                }
                return out
            }
            val isNode = BooleanArray(w * h)
            for (i in 0 until w * h) if (skel[i] && neighbours(i).size != 2) isNode[i] = true
            // the skeleton pixel nearest each source, if close enough, is a node too
            val r = attachRadius.toInt() + 1
            val sourcePixel = IntArray(sources.size) { s ->
                val sx = sources[s].x.toDouble()
                val sy = sources[s].y.toDouble()
                var best = -1
                var bestD = attachRadius
                for (y in max(0, sy.toInt() - r)..min(h - 1, sy.toInt() + r)) for (x in max(0, sx.toInt() - r)..min(w - 1, sx.toInt() + r)) {
                    val i = y * w + x
                    if (!skel[i]) continue
                    val d = hypot(x - sx, y - sy)
                    if (d <= bestD) { bestD = d; best = i }
                }
                if (best >= 0) isNode[best] = true
                best
            }
            // node pixels that touch are one node
            val nodeOf = IntArray(w * h) { -1 }
            val clusters = ArrayList<MutableList<Int>>()
            for (i in 0 until w * h) {
                if (!isNode[i] || nodeOf[i] >= 0) continue
                val id = clusters.size
                val members = ArrayList<Int>()
                val stack = ArrayDeque<Int>()
                stack += i
                nodeOf[i] = id
                while (stack.isNotEmpty()) {
                    val p = stack.removeLast()
                    members += p
                    for (q in neighbours(p)) if (isNode[q] && nodeOf[q] < 0) { nodeOf[q] = id; stack += q }
                }
                clusters += members
            }
            val positions = clusters.map { m -> vec(m.sumOf { it % w + 0.5 } / m.size, m.sumOf { it / w + 0.5 } / m.size) }
            fun pt(i: Int) = vec(i % w + 0.5, i / w + 0.5)
            // trace runs of ordinary pixels from node to node
            val visited = BooleanArray(w * h)
            val edges = ArrayList<NetworkEdge>()
            for (i in 0 until w * h) {
                if (!isNode[i]) continue
                for (q0 in neighbours(i)) {
                    if (isNode[q0] || visited[q0]) continue
                    val path = arrayListOf(positions[nodeOf[i]], pt(q0))
                    visited[q0] = true
                    var prev = i
                    var cur = q0
                    var end = -1
                    while (true) {
                        val next = neighbours(cur).filter { it != prev }
                        // a pixel of the node we started from only ends the walk once it has gone somewhere
                        val node = next.firstOrNull { isNode[it] && !(nodeOf[it] == nodeOf[i] && path.size < 6) }
                        if (node != null) { end = node; break }
                        val step = next.firstOrNull { !visited[it] } ?: break
                        visited[step] = true
                        path += pt(step)
                        prev = cur
                        cur = step
                    }
                    if (end < 0) continue
                    path += positions[nodeOf[end]]
                    val simple = simplify(path, simplifyTolerance)
                    edges += NetworkEdge(nodeOf[i], nodeOf[end], polylineLength(simple), simple)
                }
            }
            val sourceNodes = IntArray(sources.size) { s -> if (sourcePixel[s] >= 0) nodeOf[sourcePixel[s]] else -1 }
            return tidy(positions, edges, sources, sourceNodes, mergeLength)
        }

        /** Drop sourceless parts and spurs, merge close junctions, contract pass-through nodes, renumber. */
        private fun tidy(positions: List<Vec2>, edgesIn: List<NetworkEdge>, sources: List<Vec2>, sourceNodesIn: IntArray,
                         mergeLength: Double): TransportNetwork {
            var edges = edgesIn.toMutableList()
            val sourceNodes = sourceNodesIn.copyOf()
            val pos = positions.toMutableList()
            val isSource = BooleanArray(pos.size).also { a -> sourceNodes.forEach { if (it >= 0) a[it] = true } }
            // merge junctions joined by a short link (never two sources)
            run {
                val uf = UnionFind(pos.size)
                val hasSource = isSource.copyOf()
                for (e in edges.sortedBy { it.length }) {
                    if (e.length >= mergeLength || e.a == e.b) continue
                    val ra = uf.find(e.a)
                    val rb = uf.find(e.b)
                    if (ra == rb || (hasSource[ra] && hasSource[rb])) continue
                    uf.union(ra, rb)
                    val root = uf.find(ra)
                    hasSource[root] = hasSource[ra] || hasSource[rb]
                }
                // a merged group sits at its source if it has one, else at its mean
                val groups = pos.indices.groupBy { uf.find(it) }
                for ((root, members) in groups) {
                    val src = members.firstOrNull { isSource[it] }
                    pos[root] = if (src != null) pos[src] else vec(members.sumOf { pos[it].x.toDouble() } / members.size, members.sumOf { pos[it].y.toDouble() } / members.size)
                    if (src != null) isSource[root] = true
                }
                edges = edges.mapNotNull { e ->
                    val a = uf.find(e.a)
                    val b = uf.find(e.b)
                    if (a == b && e.length < mergeLength) null else e.copy(a = a, b = b)
                }.toMutableList()
                for (s in sourceNodes.indices) if (sourceNodes[s] >= 0) sourceNodes[s] = uf.find(sourceNodes[s])
            }
            // keep only parts that hold a source
            run {
                val uf = UnionFind(pos.size)
                edges.forEach { uf.union(it.a, it.b) }
                val keep = sourceNodes.filter { it >= 0 }.map { uf.find(it) }.toSet()
                edges = edges.filter { uf.find(it.a) in keep }.toMutableList()
            }
            // prune spurs: nodes of degree one that are not sources, repeatedly
            while (true) {
                val degree = IntArray(pos.size)
                edges.forEach { degree[it.a]++; degree[it.b]++ }
                val spur = edges.indexOfFirst { (degree[it.a] == 1 && !isSource[it.a]) || (degree[it.b] == 1 && !isSource[it.b]) }
                if (spur < 0) break
                edges.removeAt(spur)
            }
            // contract nodes that only pass a road through
            while (true) {
                val incident = HashMap<Int, MutableList<Int>>()
                edges.forEachIndexed { k, e -> incident.getOrPut(e.a) { ArrayList() } += k; if (e.b != e.a) incident.getOrPut(e.b) { ArrayList() } += k }
                val node = incident.entries.firstOrNull { (n, ks) -> !isSource[n] && ks.size == 2 && ks.all { edges[it].a != edges[it].b } }?.key ?: break
                val (k1, k2) = incident[node]!!
                val e1 = edges[k1]
                val e2 = edges[k2]
                val p1 = if (e1.b == node) e1.path else e1.path.reversed()
                val p2 = if (e2.a == node) e2.path else e2.path.reversed()
                val other1 = if (e1.a == node) e1.b else e1.a
                val other2 = if (e2.a == node) e2.b else e2.a
                val merged = NetworkEdge(other1, other2, e1.length + e2.length, p1 + p2.drop(1))
                edges = edges.filterIndexed { k, _ -> k != k1 && k != k2 }.toMutableList()
                edges += merged
            }
            // renumber the nodes still in use (sources always)
            val used = sortedSetOf<Int>()
            edges.forEach { used += it.a; used += it.b }
            sourceNodes.forEach { if (it >= 0) used += it }
            val index = used.withIndex().associate { (k, v) -> v to k }
            return TransportNetwork(
                used.map { pos[it] },
                edges.map { it.copy(a = index[it.a]!!, b = index[it.b]!!) },
                sources,
                IntArray(sources.size) { s -> if (sourceNodes[s] >= 0) index[sourceNodes[s]]!! else -1 }
            )
        }

        private fun polylineLength(p: List<Vec2>): Double =
            p.zipWithNext().sumOf { (a, b) -> hypot(b.x - a.x.toDouble(), b.y - a.y.toDouble()) }

        /** Douglas–Peucker. */
        private fun simplify(p: List<Vec2>, tolerance: Double): List<Vec2> {
            if (p.size < 3 || tolerance <= 0) return p
            val keep = BooleanArray(p.size)
            keep[0] = true
            keep[p.size - 1] = true
            fun rec(i: Int, j: Int) {
                if (j <= i + 1) return
                val ax = p[i].x.toDouble(); val ay = p[i].y.toDouble()
                val bx = p[j].x.toDouble(); val by = p[j].y.toDouble()
                val len = hypot(bx - ax, by - ay)
                var worst = -1.0
                var at = -1
                for (k in i + 1 until j) {
                    val d = if (len < 1e-9) hypot(p[k].x - ax, p[k].y - ay)
                            else abs((bx - ax) * (ay - p[k].y) - (ax - p[k].x) * (by - ay)) / len
                    if (d > worst) { worst = d; at = k }
                }
                if (worst > tolerance) { keep[at] = true; rec(i, at); rec(at, j) }
            }
            rec(0, p.size - 1)
            return p.filterIndexed { k, _ -> keep[k] }
        }
    }
}

private class UnionFind(n: Int) {
    private val parent = IntArray(n) { it }
    fun find(x: Int): Int {
        var r = x
        while (parent[r] != r) r = parent[r]
        var c = x
        while (parent[c] != r) { val nx = parent[c]; parent[c] = r; c = nx }
        return r
    }
    fun union(a: Int, b: Int) { parent[find(a)] = find(b) }
}

/**
 * The cost against fault-tolerance plane Tero et al. drew mould and railway on: [NetworkScore.relativeCost]
 * across (1 is the minimum spanning tree), [NetworkScore.faultTolerance] up. Each score is a dot in
 * its colour (by default grey for labels starting "MST", "Delaunay" or "complete" and a ramp for the
 * rest), labelled when [labels] is on. Returns a three-channel [Img].
 */
fun costTolerancePlot(scores: List<NetworkScore>, width: Int = 420, height: Int = 300,
                      costRange: ClosedFloatingPointRange<Double>? = null, labels: Boolean = true,
                      colors: List<FloatArray>? = null): Img {
    var out = Img.filled(width, height, 3, 0.06f)
    val finite = scores.filter { it.relativeCost.isFinite() }
    val lo = costRange?.start ?: 0.8
    val hi = costRange?.endInclusive ?: max(2.0, (finite.maxOfOrNull { it.relativeCost } ?: 2.0) * 1.05)
    val left = 34
    val right = width - 10
    val top = 10
    val bottom = height - 22
    fun px(c: Double) = (left + (c - lo) / (hi - lo) * (right - left)).roundToInt()
    fun py(f: Double) = (bottom - f.coerceIn(0.0, 1.0) * (bottom - top)).roundToInt()
    fun put(x: Int, y: Int, c: FloatArray, a: Float = 1f) {
        if (x !in 0 until width || y !in 0 until height) return
        val o = (y * width + x) * 3
        for (k in 0..2) out.data[o + k] = out.data[o + k] * (1 - a) + c[k] * a
    }
    val axis = floatArrayOf(0.45f, 0.46f, 0.5f)
    for (y in top..bottom) put(left - 1, y, axis)
    for (x in left..right) put(x, bottom + 1, axis)
    val text = ArrayList<Triple<String, Int, Int>>()
    for (k in 0..4) {
        val f = k / 4.0
        val y = py(f)
        for (x in left..right step 4) put(x, y, axis, 0.25f)
        text += Triple("%.2f".format(f), 1, y - 3)
    }
    var tick = kotlin.math.ceil(lo * 2) / 2
    while (tick <= hi) {
        val x = px(tick)
        for (y in top..bottom step 4) put(x, y, axis, 0.25f)
        text += Triple("%.1f".format(tick), x - 8, bottom + 6)
        tick += if (hi - lo > 6) 1.0 else 0.5
    }
    val controls = setOf("MST", "Delaunay", "complete")
    val grey = floatArrayOf(0.62f, 0.64f, 0.7f)
    val agents = scores.filter { s -> controls.none { s.label.startsWith(it) } }
    scores.forEachIndexed { i, s ->
        if (!s.relativeCost.isFinite()) return@forEachIndexed
        val c = colors?.getOrNull(i) ?: if (controls.any { s.label.startsWith(it) }) grey else {
            val t = agents.indexOf(s).toFloat() / max(1, agents.size - 1)
            floatArrayOf(1f, 0.78f - 0.45f * t, 0.35f + 0.4f * t)
        }
        val x = px(min(s.relativeCost, hi))
        val y = py(s.faultTolerance)
        for (dy in -2..2) for (dx in -2..2) if (dx * dx + dy * dy <= 5) put(x + dx, y + dy, c)
        if (labels) text += Triple(s.label, min(x + 5, width - 6 * s.label.length), y - 9)
    }
    for (l in text) out = out.withLabel(l.first, l.second, l.third, scale = 1, color = Color4(0.7, 0.72, 0.78))
    return out
}
