package afield.geometry.network

import afield.geometry.path.FLine
import afield.geometry.path.catmullRomToBezier
import afield.math.linalg.Vec3

/**
 * The answer Field's geometry-extraction modules share: named points, the curves that join them,
 * and typed channels holding whatever each module measured along the way.
 *
 * [MorseSmale], [Polyvector], [PrincipalCurves], [Mapper], [ContourTrees], [VectorFieldTopology]
 * and the feature tracker all return a [GeometryNetwork], so code that filters, counts or draws
 * one does not need to know which of them produced it, and `metadata` says which did.
 *
 * The three parts divide the work. [GeometryNodes] holds the points a module considers meaningful,
 * each under an identity it keeps stable across recomputation where it can, so a downstream object
 * stays attached to the same feature. A [GeometryCurve] is a polyline that names the nodes at its
 * ends, which is why two curves crossing at a position are still unconnected: connectivity is
 * stated, never inferred from proximity. A [Channel] packs a scalar, a direction or a covariance
 * for every node or every vertex into one array, so a large network costs no per-element objects.
 *
 * [GeometryNetwork.toFLines] turns a network into drawable lines and leaves the network itself on
 * each of them, so whatever the lines cannot carry is still reachable from them.
 */

/**
 * A per-element attribute channel: [width] doubles per element, packed into [data].
 *
 * A scalar such as persistence has width 1, a 2D tangent has width 2, and a 3D normal-plane
 * covariance has width 3 (xx, xy, yy). Packing keeps large networks free of per-vertex objects.
 */
class Channel(
    /** Doubles per element: 1 for a scalar, 2 for a 2D tangent, 3 for the xx, xy and yy of a covariance. */
    val width: Int,
    /** Every element's values in storage order; element `i` begins at `i * width`. */
    val data: DoubleArray,
) {
    init {
        require(width > 0) { "channel width must be positive, got $width" }
        require(data.size % width == 0) { "channel data size ${data.size} is not a multiple of width $width" }
    }

    /** The number of elements this channel describes. */
    val count: Int get() = data.size / width

    /** The value at [element], or component [component] of it for a wider channel. */
    operator fun get(element: Int, component: Int = 0): Double = data[element * width + component]

    companion object {
        fun scalar(values: DoubleArray) = Channel(1, values)
    }
}

/**
 * The vertices of a [GeometryNetwork]: critical points, junctions, endpoints.
 *
 * Positions are packed `dimension` doubles per node. Node `i` has identity [ids]`[i]`, which a
 * module keeps stable across recomputation where it can, so downstream objects stay attached to
 * the same feature.
 */
class GeometryNodes(
    /** Coordinates stored per node, which a network requires its curves to agree with. */
    val dimension: Int,
    /** Node identities in storage order; [indexOf] maps one back to its index. */
    val ids: IntArray,
    /** Every node's coordinates in storage order, [dimension] doubles each. */
    val positions: DoubleArray,
    /** Per-node channels by name. Each one describes every node. */
    val channels: Map<String, Channel> = emptyMap(),
) {
    init {
        require(positions.size == ids.size * dimension) {
            "node positions hold ${positions.size} doubles, expected ${ids.size} × $dimension"
        }
        channels.forEach { (name, c) ->
            require(c.count == ids.size) { "node channel '$name' describes ${c.count} nodes, there are ${ids.size}" }
        }
    }

    /** How many nodes this holds. */
    val size: Int get() = ids.size

    private val indexById: Map<Int, Int> by lazy { ids.withIndex().associate { (i, id) -> id to i } }

    /** The storage index of the node with identity `id`, or null. */
    fun indexOf(id: Int): Int? = indexById[id]

    /** The position of the node at storage index [index], padded to 3D. */
    fun position(index: Int): Vec3 = Vec3(
        positions[index * dimension].toFloat(),
        positions[index * dimension + 1].toFloat(),
        if (dimension > 2) positions[index * dimension + 2].toFloat() else 0f,
    )

    companion object {
        fun empty(dimension: Int) = GeometryNodes(dimension, IntArray(0), DoubleArray(0))
    }
}

/**
 * One curve of a [GeometryNetwork]: a polyline in `dimension` coordinates, its endpoint nodes, and
 * per-vertex [samples].
 *
 * [startNode] and [endNode] are node identities, not storage indices, and are null when the curve
 * ends without a node. Two curves that pass through the same position share a vertex only if they
 * name the same node: a crossing is not a junction.
 */
class GeometryCurve(
    /** This curve's identity within its network, which [GeometryNetwork.toFLines] copies out as `curveId`. */
    val id: Int,
    /** Coordinates per vertex: 2 or 3. */
    val dimension: Int,
    /** The polyline, [dimension] doubles per vertex, in order along the curve. */
    val coordinates: DoubleArray,
    /** The identity of the node this curve starts at, or null when it starts at no node. */
    val startNode: Int? = null,
    /** The identity of the node this curve ends at, or null when it ends at no node. */
    val endNode: Int? = null,
    /** Whether the last vertex joins back to the first. */
    val closed: Boolean = false,
    /** Whole-curve values by name, such as `kind` or `born`, each copied onto the line this becomes. */
    val attributes: Map<String, Double> = emptyMap(),
    /** Per-vertex channels by name. Each one holds a value for every vertex of this curve. */
    val samples: Map<String, Channel> = emptyMap(),
) {
    init {
        require(dimension == 2 || dimension == 3) { "dimension must be 2 or 3, got $dimension" }
        require(coordinates.size % dimension == 0) { "curve $id coordinates are not a multiple of $dimension" }
        samples.forEach { (name, c) ->
            require(c.count == vertexCount) { "curve $id sample '$name' has ${c.count} values for $vertexCount vertices" }
        }
    }

    /** How many vertices the polyline has. */
    val vertexCount: Int get() = coordinates.size / dimension

    /** Vertex `i`, padded to 3D. */
    fun vertex(i: Int): Vec3 = Vec3(
        coordinates[i * dimension].toFloat(),
        coordinates[i * dimension + 1].toFloat(),
        if (dimension > 2) coordinates[i * dimension + 2].toFloat() else 0f,
    )

    /** Polyline length. */
    val length: Double
        get() {
            var total = 0.0
            for (i in 1 until vertexCount) total += vertex(i).distance(vertex(i - 1))
            if (closed && vertexCount > 1) total += vertex(0).distance(vertex(vertexCount - 1))
            return total
        }
}

/**
 * The common result type of Field's geometry-extraction modules (Morse–Smale, polyvector,
 * principal curves): explicit nodes, curves that name their endpoint nodes, typed attribute
 * channels, and string [metadata] recording provenance (which module, which parameters).
 *
 * Converting to [FLine] with [toFLines] keeps this network reachable from every line it produces,
 * under the `"geometryNetwork"` property, so nothing the conversion cannot express is lost.
 */
class GeometryNetwork(
    /** Coordinates per point. Every node and every curve must agree with it. */
    val dimension: Int,
    /** The points curves may name as their ends. */
    val nodes: GeometryNodes,
    /** The curves, which may name only nodes that are in [nodes]. */
    val curves: List<GeometryCurve>,
    /** Provenance by name: which module made this network, and under which parameters. */
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(nodes.dimension == dimension) { "node dimension ${nodes.dimension} differs from network dimension $dimension" }
        curves.forEach { c ->
            require(c.dimension == dimension) { "curve ${c.id} has dimension ${c.dimension}, network has $dimension" }
            listOfNotNull(c.startNode, c.endNode).forEach { n ->
                require(nodes.indexOf(n) != null) { "curve ${c.id} names node $n, which is not in the network" }
            }
        }
    }

    /** Curves whose start or end is the node with identity [nodeId]. */
    fun curvesAt(nodeId: Int): List<GeometryCurve> = curves.filter { it.startNode == nodeId || it.endNode == nodeId }

    /** The number of curve ends meeting at each node identity. */
    fun degrees(): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        nodes.ids.forEach { out[it] = 0 }
        curves.forEach { c ->
            c.startNode?.let { out[it] = out.getValue(it) + 1 }
            c.endNode?.let { out[it] = out.getValue(it) + 1 }
        }
        return out
    }

    /**
     * Connected components, as lists of curve ids. Curves are connected only through shared nodes,
     * never by proximity.
     */
    fun components(): List<List<Int>> {
        val parent = IntArray(curves.size) { it }
        fun find(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var j = i
            while (parent[j] != r) { val n = parent[j]; parent[j] = r; j = n }
            return r
        }
        val firstAtNode = HashMap<Int, Int>()
        curves.forEachIndexed { i, c ->
            listOfNotNull(c.startNode, c.endNode).forEach { n ->
                val other = firstAtNode.putIfAbsent(n, i)
                if (other != null) parent[find(i)] = find(other)
            }
        }
        return curves.indices.groupBy { find(it) }.values.map { group -> group.map { curves[it].id } }
    }

    /** A network holding only the curves that satisfy [keep], and the nodes they still name. */
    fun filterCurves(keep: (GeometryCurve) -> Boolean): GeometryNetwork {
        val kept = curves.filter(keep)
        val used = kept.flatMap { listOfNotNull(it.startNode, it.endNode) }.toSet()
        val idx = nodes.ids.indices.filter { nodes.ids[it] in used }
        val d = dimension
        val newNodes = GeometryNodes(
            d,
            IntArray(idx.size) { nodes.ids[idx[it]] },
            DoubleArray(idx.size * d) { nodes.positions[idx[it / d] * d + it % d] },
            nodes.channels.mapValues { (_, c) ->
                Channel(c.width, DoubleArray(idx.size * c.width) { c.data[idx[it / c.width] * c.width + it % c.width] })
            },
        )
        return GeometryNetwork(d, newNodes, kept, metadata)
    }

    /**
     * One [FLine] per curve.
     *
     * With [smooth], each curve becomes a Catmull–Rom spline through its own vertices, so it still
     * passes through its endpoints and junctions; only the embedding between vertices changes.
     * Without it, vertices are joined by straight segments, and with [withSamples] each vertex node
     * also carries that vertex's [GeometryCurve.samples] in its properties. That costs a property map
     * per vertex, so it is off by default: the samples stay reachable through `geometryNetwork`.
     *
     * Every line carries `curveId`, `startNode`, `endNode`, each curve attribute, and
     * `geometryNetwork` (this network) in its properties.
     */
    fun toFLines(smooth: Boolean = false, tension: Float = 1f, withSamples: Boolean = false): List<FLine> = curves.filter { it.vertexCount >= 2 }.map { c ->
        val line = if (smooth) {
            catmullRomToBezier(List(c.vertexCount) { c.vertex(it) }, c.closed, tension)
        } else {
            FLine().also { f ->
                for (i in 0 until c.vertexCount) {
                    if (i == 0) f.moveTo(c.vertex(i)) else f.lineTo(c.vertex(i))
                    if (!withSamples) continue
                    val node = f.nodes.last()
                    c.samples.forEach { (name, ch) ->
                        node.properties[name] = if (ch.width == 1) ch[i] else DoubleArray(ch.width) { k -> ch[i, k] }
                    }
                }
                if (c.closed) f.close()
            }
        }
        line.properties["curveId"] = c.id
        line.properties["startNode"] = c.startNode
        line.properties["endNode"] = c.endNode
        c.attributes.forEach { (k, v) -> line.properties[k] = v }
        line.properties["geometryNetwork"] = this
        line
    }

    /** Every node as a single FLine of points, for display; each point carries `nodeId` and node channels. */
    fun nodesFLine(): FLine = FLine().also { f ->
        for (i in 0 until nodes.size) {
            f.moveTo(nodes.position(i))
            val node = f.nodes.last()
            node.properties["nodeId"] = nodes.ids[i]
            nodes.channels.forEach { (name, ch) -> node.properties[name] = if (ch.width == 1) ch[i] else DoubleArray(ch.width) { k -> ch[i, k] } }
        }
    }

    /** Dimension, node and curve counts, and the source recorded in [metadata], on one line. */
    override fun toString() =
        "GeometryNetwork(${dimension}D, ${nodes.size} nodes, ${curves.size} curves${metadata["source"]?.let { ", from $it" } ?: ""})"

    companion object {
        /**
         * A network from [lines], one curve per subpath, sampled with [samplesPerSegment] points per
         * curved segment (straight segments keep their two endpoints).
         *
         * The result has no nodes: subpaths that touch are not joined, because touching is not
         * connectivity. Name nodes yourself, or let a fitting module infer them, when you want topology.
         */
        fun fromFLines(lines: List<FLine>, dimension: Int = 2, samplesPerSegment: Int = 8): GeometryNetwork {
            val curves = mutableListOf<GeometryCurve>()
            lines.forEach { line ->
                var current = mutableListOf<Vec3>()
                var closed = false
                fun flush() {
                    if (current.size >= 2) {
                        val coords = DoubleArray(current.size * dimension)
                        current.forEachIndexed { i, v ->
                            coords[i * dimension] = v.x.toDouble()
                            coords[i * dimension + 1] = v.y.toDouble()
                            if (dimension == 3) coords[i * dimension + 2] = v.z.toDouble()
                        }
                        curves.add(GeometryCurve(curves.size, dimension, coords, closed = closed))
                    }
                    current = mutableListOf()
                    closed = false
                }
                line.nodes.forEach { n ->
                    when (n) {
                        is FLine.MoveTo -> { flush(); current.add(n.to) }
                        is FLine.LineTo -> current.add(n.to)
                        is FLine.CubicTo -> {
                            val p0 = current.lastOrNull() ?: n.to
                            val (c1, c2, p3) = Triple(n.storage[1], n.storage[2], n.storage[0])
                            for (k in 1..samplesPerSegment) {
                                val t = k.toFloat() / samplesPerSegment
                                val u = 1 - t
                                val (b0, b1, b2, b3) = listOf(u * u * u, 3 * u * u * t, 3 * u * t * t, t * t * t)
                                current.add(Vec3(
                                    b0 * p0.x + b1 * c1.x + b2 * c2.x + b3 * p3.x,
                                    b0 * p0.y + b1 * c1.y + b2 * c2.y + b3 * p3.y,
                                    b0 * p0.z + b1 * c1.z + b2 * c2.z + b3 * p3.z,
                                ))
                            }
                        }
                        is FLine.Close -> closed = true
                    }
                }
                flush()
            }
            return GeometryNetwork(dimension, GeometryNodes.empty(dimension), curves, mapOf("source" to "FLine"))
        }
    }
}
