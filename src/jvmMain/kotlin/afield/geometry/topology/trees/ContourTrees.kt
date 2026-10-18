package afield.geometry.topology.trees

import afield.geometry.network.Channel
import afield.geometry.network.GeometryCurve
import afield.geometry.network.GeometryNetwork
import afield.geometry.network.GeometryNodes
import afield.geometry.path.FLine
import afield.math.linalg.Vec4
import afield.media.image.Img

/**
 * The kind of a tree node: where a component of the level set begins or ends ([MINIMUM], [MAXIMUM]),
 * where two components join or one splits ([SADDLE]), or where the tree is rooted ([ROOT], the
 * global extremum a merge tree ends at).
 */
enum class TreeNodeType { MINIMUM, MAXIMUM, SADDLE, ROOT }

/** A node of a [ScalarTree]: the grid vertex it sits on, its value and position (pixels). */
data class TreeNode(val id: Int, val vertex: Int, val value: Double, val x: Double, val y: Double, val type: TreeNodeType)

/**
 * An arc of a [ScalarTree] from node [lower] to node [upper] (by value), with the grid [vertices] it
 * owns: the regular vertices whose level-set component it represents.
 */
data class TreeArc(val id: Int, val lower: Int, val upper: Int, val vertices: IntArray) {
    override fun equals(other: Any?) = other is TreeArc && other.id == id
    override fun hashCode() = id
}

/**
 * A merge tree or contour tree of a scalar field on a regular grid: critical nodes joined by arcs,
 * each arc owning the pixels whose level-set component it stands for.
 *
 * The field is the same piecewise-linear one Field's Morse–Smale complexes use: the grid cut into
 * triangles along the (x+1, y+1) diagonal, ties between equal samples broken by pixel index.
 */
class ScalarTree internal constructor(
    val kind: String,
    val width: Int,
    val height: Int,
    val nodes: List<TreeNode>,
    val arcs: List<TreeArc>,
) {
    private val byId = nodes.associateBy { it.id }

    fun node(id: Int): TreeNode = byId.getValue(id)

    /** Per pixel, the id of the arc that owns it (nodes' own pixels belong to an arc ending there), or -1. */
    val arcOfPixel: IntArray by lazy {
        val out = IntArray(width * height) { -1 }
        arcs.forEach { a -> a.vertices.forEach { out[it] = a.id } }
        arcs.forEach { a -> listOf(a.lower, a.upper).forEach { n -> val v = node(n).vertex; if (out[v] < 0) out[v] = a.id } }
        out
    }

    /** The arcs at node [id]. */
    fun arcsAt(id: Int): List<TreeArc> = arcs.filter { it.lower == id || it.upper == id }

    /**
     * The tree with every leaf arc shorter than [persistence] (its value span) removed, repeatedly,
     * and nodes left with one arc above and one below merged away. The leaf's pixels go to the arc it
     * joined. This is the tree's persistence simplification: small bumps and dips vanish first.
     */
    fun simplify(persistence: Double): ScalarTree {
        val arcs = this.arcs.associateBy { it.id }.toMutableMap()
        val pixels = arcs.mapValues { (_, a) -> a.vertices.toMutableList() }.toMutableMap()
        var nextArc = (arcs.keys.maxOrNull() ?: -1) + 1
        fun degree(n: Int) = arcs.values.count { it.lower == n || it.upper == n }
        fun isLeaf(n: Int) = degree(n) == 1 && node(n).type != TreeNodeType.ROOT
        while (true) {
            val leafArc = arcs.values.filter { a -> (isLeaf(a.lower) || isLeaf(a.upper)) && arcs.size > 1 }
                .minByOrNull { node(it.upper).value - node(it.lower).value } ?: break
            if (node(leafArc.upper).value - node(leafArc.lower).value >= persistence) break
            val leaf = if (isLeaf(leafArc.lower)) leafArc.lower else leafArc.upper
            val keep = if (leaf == leafArc.lower) leafArc.upper else leafArc.lower
            arcs.remove(leafArc.id)
            val orphaned = pixels.remove(leafArc.id)!! + node(leaf).vertex
            // merge away `keep` if it is now regular: one arc below, one above
            val around = arcs.values.filter { it.lower == keep || it.upper == keep }
            val below = around.filter { it.upper == keep }; val above = around.filter { it.lower == keep }
            if (below.size == 1 && above.size == 1 && node(keep).type != TreeNodeType.ROOT) {
                val a = below[0]; val b = above[0]
                arcs.remove(a.id); arcs.remove(b.id)
                val merged = pixels.remove(a.id)!! + node(keep).vertex + pixels.remove(b.id)!! + orphaned
                val id = nextArc++
                arcs[id] = TreeArc(id, a.lower, b.upper, IntArray(0))
                pixels[id] = merged.toMutableList()
            } else {
                around.firstOrNull()?.let { pixels.getValue(it.id).addAll(orphaned) }
            }
        }
        val used = arcs.values.flatMap { listOf(it.lower, it.upper) }.toSet()
        val finalArcs = arcs.values.map { TreeArc(it.id, it.lower, it.upper, pixels.getValue(it.id).toIntArray()) }.sortedBy { it.id }
        return ScalarTree(kind, width, height, nodes.filter { it.id in used }, finalArcs)
    }

    /**
     * The tree drawn the classic way, as a plot: each node at height = its value (scaled into
     * [height] pixels, higher values up), leaves spread evenly along x in depth-first order and
     * every other node above the mean of the leaves below it; arcs drawn as right-angled elbows.
     */
    fun plot(width: Double = 400.0, height: Double = 240.0, color: Vec4 = Vec4(0.9f, 0.85f, 0.75f, 1f)): List<FLine> {
        if (nodes.isEmpty()) return emptyList()
        val lo = nodes.minOf { it.value }; val hi = nodes.maxOf { it.value }
        fun y(v: Double) = height - (v - lo) / (hi - lo).coerceAtLeast(1e-12) * height
        val adj = nodes.associate { n -> n.id to arcsAt(n.id).map { if (it.lower == n.id) it.upper else it.lower } }
        val rootId = nodes.firstOrNull { it.type == TreeNodeType.ROOT }?.id ?: nodes.maxBy { adj.getValue(it.id).size }.id
        val xOf = HashMap<Int, Double>()
        var leafIndex = 0
        val leaves = nodes.count { adj.getValue(it.id).size == 1 && it.id != rootId }.coerceAtLeast(1)
        fun place(n: Int, parent: Int): Double {
            val kids = adj.getValue(n).filter { it != parent }
            val x = if (kids.isEmpty()) (leafIndex++ + 0.5) / leaves * width else kids.map { place(it, n) }.average()
            xOf[n] = x
            return x
        }
        place(rootId, -1)
        return arcs.map { a ->
            val l = node(a.lower); val u = node(a.upper)
            FLine().moveTo(xOf.getValue(l.id), y(l.value)).lineTo(xOf.getValue(l.id), y(u.value)).lineTo(xOf.getValue(u.id), y(u.value))
                .also { it.properties["color"] = colourOf(a.id); it.properties["thickness"] = 2.0; it.properties["arcId"] = a.id }
        } + FLine().also { f ->
            nodes.forEach { n -> f.moveTo(xOf.getValue(n.id), y(n.value)); f.nodes.last().properties["color"] = color }
            f.properties["pointed"] = true; f.properties["stroked"] = false; f.properties["pointSize"] = 6.0
        }
    }

    /** Each arc's pixels in its own colour: the field divided into the regions the tree's arcs stand for. */
    fun segmentationImage(): Img {
        val data = FloatArray(width * height * 3)
        arcOfPixel.forEachIndexed { i, a ->
            if (a < 0) return@forEachIndexed
            val c = colourOf(a)
            data[3 * i] = c.x; data[3 * i + 1] = c.y; data[3 * i + 2] = c.z
        }
        return Img(width, height, 3, data)
    }

    /**
     * The tree as a [GeometryNetwork] in image space: nodes at their pixels (channels `type`, `value`),
     * each arc a straight curve between its nodes (attributes `lowerValue`, `upperValue`, `pixels`).
     */
    fun network(): GeometryNetwork = GeometryNetwork(
        2,
        GeometryNodes(2, IntArray(nodes.size) { nodes[it].id }, DoubleArray(nodes.size * 2) { i -> nodes[i / 2].let { if (i % 2 == 0) it.x else it.y } },
            mapOf("type" to Channel.scalar(DoubleArray(nodes.size) { nodes[it].type.ordinal.toDouble() }),
                "value" to Channel.scalar(DoubleArray(nodes.size) { nodes[it].value }))),
        arcs.map { a ->
            val l = node(a.lower); val u = node(a.upper)
            GeometryCurve(a.id, 2, doubleArrayOf(l.x, l.y, u.x, u.y), l.id, u.id,
                attributes = mapOf("lowerValue" to l.value, "upperValue" to u.value, "pixels" to a.vertices.size.toDouble()))
        },
        mapOf("source" to kind),
    )

    override fun toString() = "ScalarTree($kind, ${nodes.size} nodes, ${arcs.size} arcs)"

    companion object {
        fun colourOf(id: Int): Vec4 {
            val h = (id * -0x61c88647) ushr 8
            return Vec4(0.35f + 0.6f * ((h and 0xff) / 255f), 0.35f + 0.6f * (((h shr 8) and 0xff) / 255f), 0.35f + 0.6f * (((h shr 16) and 0xff) / 255f), 1f)
        }
    }
}

/**
 * Merge trees and contour trees of 2D scalar fields.
 *
 * ```
 * val field = imgFromFile("terrain.png").luma()
 * val tree = ContourTrees.contourTree(field).simplify(persistence = 0.05)
 * display("tree", tree.plot())
 * ```
 *
 * A **join tree** follows the sublevel sets `{f ≤ t}` as t rises: a component is born at each
 * minimum, components join at saddles, and everything has joined by the global maximum. A **split
 * tree** is the same from the top down. The **contour tree** combines them: it follows every
 * connected component of every level set `{f = t}`, so it records joins *and* splits.
 */
object ContourTrees {
    private class Grid(val w: Int, val h: Int, val values: FloatArray) {
        val n = w * h
        val order: IntArray = (0 until n).sortedWith(compareBy({ values[it] }, { it })).toIntArray()
        val rank = IntArray(n).also { r -> order.forEachIndexed { i, v -> r[v] = i } }
        private val dx = intArrayOf(1, 1, 0, -1, -1, 0)
        private val dy = intArrayOf(0, 1, 1, 0, -1, -1)
        inline fun forNeighbours(v: Int, f: (Int) -> Unit) {
            val x = v % w; val y = v / w
            for (k in 0 until 6) {
                val nx = x + dx[k]; val ny = y + dy[k]
                if (nx in 0 until w && ny in 0 until h) f(ny * w + nx)
            }
        }
    }

    private fun gridOf(img: Img, channel: Int): Grid {
        require(channel in 0 until img.channels) { "no channel $channel" }
        val v = FloatArray(img.width * img.height) { img.data[it * img.channels + channel] }
        require(v.none { it.isNaN() }) { "the field has NaN values" }
        return Grid(img.width, img.height, v)
    }

    private class UnionFind(n: Int) {
        val parent = IntArray(n) { it }
        fun find(v: Int): Int { var r = v; while (parent[r] != r) r = parent[r]; var x = v; while (parent[x] != r) { val nx = parent[x]; parent[x] = r; x = nx }; return r }
    }

    /**
     * The augmented join tree (ascending) or split tree (descending): for every vertex, the vertices
     * it is joined to below (join) or above (split). Each vertex links to the most recently added
     * vertex of every component it touches.
     */
    private fun augmented(g: Grid, ascending: Boolean): Array<IntArray> {
        val uf = UnionFind(g.n)
        val head = IntArray(g.n) { -1 }
        val seen = BooleanArray(g.n)
        val down = Array(g.n) { IntArray(0) }
        val seq = if (ascending) g.order else g.order.reversedArray()
        for (v in seq) {
            val roots = ArrayList<Int>(6)
            g.forNeighbours(v) { u -> if (seen[u]) { val r = uf.find(u); if (r !in roots) roots.add(r) } }
            down[v] = IntArray(roots.size) { head[roots[it]] }
            seen[v] = true
            roots.forEach { uf.parent[it] = v }
            head[v] = v
        }
        return down
    }

    private fun build(kind: String, g: Grid, edges: List<Pair<Int, Int>>, typeOf: (Int, Int, Int) -> TreeNodeType, root: Int?): ScalarTree {
        // edges are (lower vertex, upper vertex) of the augmented tree; contract degree-2 vertices
        val up = HashMap<Int, MutableList<Int>>(); val dn = HashMap<Int, MutableList<Int>>()
        edges.forEach { (a, b) -> up.getOrPut(a) { mutableListOf() }.add(b); dn.getOrPut(b) { mutableListOf() }.add(a) }
        fun ups(v: Int) = up[v] ?: emptyList<Int>()
        fun dns(v: Int) = dn[v] ?: emptyList<Int>()
        val critical = (0 until g.n).filter { v -> !(ups(v).size == 1 && dns(v).size == 1) || v == root }.toSet()
        val nodeOf = HashMap<Int, Int>()
        val nodes = critical.sortedBy { g.rank[it] }.mapIndexed { i, v ->
            nodeOf[v] = i
            TreeNode(i, v, g.values[v].toDouble(), v % g.w.toDouble(), (v / g.w).toDouble(),
                if (v == root) TreeNodeType.ROOT else typeOf(v, dns(v).size, ups(v).size))
        }
        val arcs = ArrayList<TreeArc>()
        for (v in critical) for (start in ups(v)) {
            val path = ArrayList<Int>()
            var cur = start
            while (cur !in critical) { path.add(cur); cur = ups(cur).single() }
            arcs.add(TreeArc(arcs.size, nodeOf.getValue(v), nodeOf.getValue(cur), path.toIntArray()))
        }
        return ScalarTree(kind, g.w, g.h, nodes, arcs)
    }

    /** The join tree: sublevel components born at minima, joining at saddles, rooted at the global maximum. */
    fun joinTree(field: Img, channel: Int = 0): ScalarTree {
        val g = gridOf(field, channel)
        val down = augmented(g, ascending = true)
        val edges = ArrayList<Pair<Int, Int>>()
        for (v in 0 until g.n) down[v].forEach { edges.add(it to v) }
        return build("joinTree", g, edges, { _, d, _ -> if (d == 0) TreeNodeType.MINIMUM else TreeNodeType.SADDLE }, g.order.last())
    }

    /** The split tree: superlevel components born at maxima, joining at saddles, rooted at the global minimum. */
    fun splitTree(field: Img, channel: Int = 0): ScalarTree {
        val g = gridOf(field, channel)
        val above = augmented(g, ascending = false)
        val edges = ArrayList<Pair<Int, Int>>()
        for (v in 0 until g.n) above[v].forEach { edges.add(v to it) }
        return build("splitTree", g, edges, { _, _, u -> if (u == 0) TreeNodeType.MAXIMUM else TreeNodeType.SADDLE }, g.order.first())
    }

    /**
     * The contour tree, by Carr, Snoeyink and Axen's merge of the augmented join and split trees:
     * leaves are peeled off one at a time, each giving one contour-tree arc, until one vertex is left.
     * `O(n log n)` for the sort and close to linear after it.
     */
    fun contourTree(field: Img, channel: Int = 0): ScalarTree {
        val g = gridOf(field, channel)
        val jDown = augmented(g, ascending = true)   // join tree: v's neighbours below
        val sUp = augmented(g, ascending = false)    // split tree: v's neighbours above
        // adjacency as mutable sets: join tree (up, down), split tree (up, down)
        val jUp = Array(g.n) { HashSet<Int>(2) }; val jDn = Array(g.n) { HashSet<Int>(2) }
        val sUpS = Array(g.n) { HashSet<Int>(2) }; val sDn = Array(g.n) { HashSet<Int>(2) }
        for (v in 0 until g.n) {
            jDown[v].forEach { u -> jDn[v].add(u); jUp[u].add(v) }
            sUp[v].forEach { u -> sUpS[v].add(u); sDn[u].add(v) }
        }
        // a lower leaf starts a join-tree branch and continues one split-tree arc; an upper leaf the reverse
        fun isLeaf(v: Int) = (jDn[v].isEmpty() && sUpS[v].size == 1) || (sUpS[v].isEmpty() && jDn[v].size == 1)
        val queue = ArrayDeque<Int>()
        val removed = BooleanArray(g.n)
        val queued = BooleanArray(g.n)
        for (v in 0 until g.n) if (isLeaf(v)) { queue.add(v); queued[v] = true }
        val edges = ArrayList<Pair<Int, Int>>(g.n)
        var left = g.n
        fun detach(v: Int, up: Array<HashSet<Int>>, dn: Array<HashSet<Int>>) {
            // splice v out of one tree: its single neighbour on one side inherits the other side
            val ups = up[v].toList(); val dns = dn[v].toList()
            ups.forEach { dn[it].remove(v) }; dns.forEach { up[it].remove(v) }
            if (ups.size == 1) dns.forEach { d -> up[d].add(ups[0]); dn[ups[0]].add(d) }
            else if (dns.size == 1) ups.forEach { u -> dn[u].add(dns[0]); up[dns[0]].add(u) }
            up[v].clear(); dn[v].clear()
        }
        while (left > 1 && queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (removed[v]) continue
            queued[v] = false
            if (!isLeaf(v)) continue
            val u: Int
            if (jDn[v].isEmpty() && sUpS[v].size == 1) {
                // a lower leaf (a join-tree leaf): its arc goes up to its join-tree neighbour
                u = jUp[v].first()
                edges.add(v to u)
            } else {
                // an upper leaf (a split-tree leaf): its arc goes down to its split-tree neighbour
                u = sDn[v].first()
                edges.add(u to v)
            }
            val touched = (jUp[v] + jDn[v] + sUpS[v] + sDn[v]).toList()
            detach(v, jUp, jDn); detach(v, sUpS, sDn)
            removed[v] = true; left--
            touched.forEach { w -> if (!removed[w] && !queued[w] && isLeaf(w)) { queue.add(w); queued[w] = true } }
        }
        // orient each edge from lower to higher value
        val oriented = edges.map { (a, b) -> if (g.rank[a] < g.rank[b]) a to b else b to a }
        return build("contourTree", g, oriented, { _, d, u ->
            when {
                d == 0 -> TreeNodeType.MINIMUM
                u == 0 -> TreeNodeType.MAXIMUM
                else -> TreeNodeType.SADDLE
            }
        }, null)
    }
}
