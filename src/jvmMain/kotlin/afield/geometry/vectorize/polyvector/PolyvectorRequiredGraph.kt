package afield.geometry.vectorize.polyvector

import afield.geometry.network.Channel
import afield.geometry.network.GeometryCurve
import afield.geometry.network.GeometryNetwork
import afield.geometry.network.GeometryNodes
import afield.media.image.Img
import kotlin.math.exp
import kotlin.math.hypot

/**
 * A [PolyvectorDrawing] traced under a required graph, and how far its connectivity keeps that
 * graph's. [network] is the traced curves with every end within the snap tolerance of a required
 * node moved onto that node and given its id; other shared ends become new nodes, numbered after the
 * required ones. A required curve between nodes `a` and `b` is kept when some chain of traced curves
 * joins `a` to `b` without passing through another required node.
 *
 * The constraint is soft: the required curves are added to the evidence and junctions are snapped
 * afterwards, so tracing can still lose a connection. [missingCurves] and [missingNodes] say where it
 * did; [satisfied] is true when nothing is missing.
 */
class RequiredGraphDrawing internal constructor(
    val drawing: PolyvectorDrawing,
    val required: GeometryNetwork,
    val network: GeometryNetwork,
    /** Ids of the required curves (between two nodes) that no chain of traced curves reproduces. */
    val missingCurves: List<Int>,
    /** Ids of required nodes (those a required curve ends at) that no traced curve end reached. */
    val missingNodes: List<Int>,
) {
    val satisfied: Boolean get() = missingCurves.isEmpty() && missingNodes.isEmpty()

    override fun toString() =
        "RequiredGraphDrawing(${network.curves.size} curves; ${missingCurves.size} of ${required.curves.count { it.startNode != null && it.endNode != null }} required curves missing, ${missingNodes.size} nodes missing)"
}

/**
 * [Polyvector.vectorize] for a drawing whose connectivity must agree with [required], a 2-D
 * [GeometryNetwork] in the drawing's pixel coordinates (a Morse–Smale complex's network, say).
 *
 * ```
 * val traced = Polyvector.vectorize(drawing, required = complex.network())
 * if (!traced.satisfied) println("lost ${traced.missingCurves}")
 * display("vectors", traced.network.toFLines())
 * ```
 *
 * The required curves are drawn into the evidence at [seed] (0..1, a soft line about three pixels
 * wide) so the field follows them, then traced ends within [snapTolerance] pixels of a required node
 * are joined there. Tracing pulls back from the end of a stroke, so a required node that no end
 * reached takes the traced junction (the one with most curve ends, then the nearest) lying on one of
 * its required curves within [reach] pixels, and the curves ending there are extended to it.
 */
fun Polyvector.vectorize(
    drawing: Img,
    required: GeometryNetwork,
    seed: Double = 0.6,
    snapTolerance: Double = 3.0,
    reach: Double = 12.0,
    field: FrameFieldParameters = FrameFieldParameters(),
    trace: TraceParameters = TraceParameters(),
): RequiredGraphDrawing {
    require(required.dimension == 2) { "the required graph must be 2-D (pixel coordinates); it has dimension ${required.dimension}" }
    val ink = inkOf(drawing)
    val w = ink.width; val h = ink.height
    val evidence = FloatArray(w * h) { ink.data[it] }
    if (seed > 0) required.curves.forEach { c ->
        for (k in 0 until c.vertexCount - 1) {
            val a = c.vertex(k); val b = c.vertex(k + 1)
            val n = maxOf(1, (hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) * 2).toInt())
            for (s in 0..n) {
                val cx = a.x + (b.x - a.x) * s / n; val cy = a.y + (b.y - a.y) * s / n
                for (y in (cy - 3).toInt()..(cy + 3).toInt()) for (x in (cx - 3).toInt()..(cx + 3).toInt()) {
                    if (x !in 0 until w || y !in 0 until h) continue
                    val d = hypot(x + 0.5 - cx, y + 0.5 - cy) / 1.5
                    evidence[y * w + x] = maxOf(evidence[y * w + x], (seed * exp(-d * d)).toFloat())
                }
            }
        }
    }
    val traced = fitField(evidence, w, h, field).trace(trace)
    val (network, missingCurves, missingNodes) = constrainToRequired(traced.curves, required, snapTolerance, reach, traced.network().metadata)
    return RequiredGraphDrawing(traced, required, network, missingCurves, missingNodes)
}

/** The snapping and connectivity check of [vectorize] with a required graph, on bare x, y curves. */
internal fun constrainToRequired(
    tracedCurves: List<FloatArray>,
    required: GeometryNetwork,
    snapTolerance: Double,
    reach: Double = 0.0,
    metadata: Map<String, String> = emptyMap(),
): Triple<GeometryNetwork, List<Int>, List<Int>> {
    val rn = required.nodes
    // Only nodes a required curve ends at constrain anything; a network can carry others (minima, say).
    val requiredIds = required.curves.flatMap { listOfNotNull(it.startNode, it.endNode) }.toSet()
    val candidates = (0 until rn.size).filter { rn.ids[it] in requiredIds }

    // A stroke traced straight through a required node is cut there, so the node can join it.
    val tracedCurves = tracedCurves.flatMap { c ->
        val n = c.size / 2
        val cuts = ArrayList<Pair<Double, Int>>() // position along the polyline (segment + t), node index
        for (i in candidates) {
            val p = rn.position(i)
            var best = Double.MAX_VALUE; var at = -1.0
            for (k in 0 until n - 1) {
                val ax = c[2 * k].toDouble(); val ay = c[2 * k + 1].toDouble()
                val dx = c[2 * k + 2] - ax; val dy = c[2 * k + 3] - ay; val l2 = dx * dx + dy * dy
                val t = if (l2 > 0) (((p.x - ax) * dx + (p.y - ay) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
                val d = hypot(p.x - ax - t * dx, p.y - ay - t * dy)
                if (d < best) { best = d; at = k + t }
            }
            val fromStart = hypot(p.x - c[0], p.y - c[1]); val fromEnd = hypot(p.x - c[2 * n - 2], p.y - c[2 * n - 1])
            if (best <= snapTolerance && fromStart > snapTolerance && fromEnd > snapTolerance) cuts.add(at to i)
        }
        if (cuts.isEmpty()) return@flatMap listOf(c)
        cuts.sortBy { it.first }
        val pieces = ArrayList<FloatArray>()
        var piece = arrayListOf(c[0], c[1]); var k = 0
        for ((at, i) in cuts) {
            while (k + 1 <= at.toInt() && k + 1 < n) { k++; piece.add(c[2 * k]); piece.add(c[2 * k + 1]) }
            val p = rn.position(i)
            piece.add(p.x.toFloat()); piece.add(p.y.toFloat())
            pieces.add(piece.toFloatArray())
            piece = arrayListOf(p.x.toFloat(), p.y.toFloat())
        }
        while (k + 1 < n) { k++; piece.add(c[2 * k]); piece.add(c[2 * k + 1]) }
        pieces.add(piece.toFloatArray())
        pieces.filter { it.size >= 4 }
    }
    var nextId = (rn.ids.maxOrNull() ?: -1) + 1
    val free = ArrayList<Triple<Double, Double, Int>>() // x, y, id of ends not on a required node
    val freeCount = HashMap<Int, Int>()

    fun nodeFor(x: Double, y: Double): Int {
        var best = -1; var bestD = snapTolerance
        for (i in candidates) {
            val p = rn.position(i)
            val d = hypot(p.x - x, p.y - y)
            if (d <= bestD) { best = i; bestD = d }
        }
        if (best >= 0) return rn.ids[best]
        free.firstOrNull { hypot(it.first - x, it.second - y) <= 0.5 }?.let { freeCount[it.third] = freeCount[it.third]!! + 1; return it.third }
        val id = nextId++
        free.add(Triple(x, y, id)); freeCount[id] = 1
        return id
    }

    var ends = tracedCurves.map { c -> nodeFor(c[0].toDouble(), c[1].toDouble()) to nodeFor(c[c.size - 2].toDouble(), c[c.size - 1].toDouble()) }
    val snapped = ends.flatMap { listOf(it.first, it.second) }.filter { it in requiredIds }.toSet()

    // A required node nothing snapped to takes the best traced junction on one of its curves within reach.
    fun distanceToCurve(c: GeometryCurve, x: Double, y: Double): Double = (0 until c.vertexCount - 1).minOfOrNull { k ->
        val a = c.vertex(k); val b = c.vertex(k + 1)
        val dx = (b.x - a.x).toDouble(); val dy = (b.y - a.y).toDouble(); val l2 = dx * dx + dy * dy
        val t = if (l2 > 0) (((x - a.x) * dx + (y - a.y) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
        hypot(x - a.x - t * dx, y - a.y - t * dy)
    } ?: Double.MAX_VALUE
    val pulledIn = HashMap<Int, Int>()
    if (reach > 0) for (i in candidates) {
        val id = rn.ids[i]
        if (id in snapped) continue
        val p = rn.position(i)
        val along = required.curvesAt(id)
        free.filter { (x, y, f) -> f !in pulledIn && hypot(p.x - x, p.y - y) <= reach && along.any { distanceToCurve(it, x, y) <= snapTolerance } }
            .minWithOrNull(compareBy({ -(freeCount[it.third] ?: 0) }, { hypot(p.x - it.first, p.y - it.second) }))
            ?.let { pulledIn[it.third] = id }
    }
    if (pulledIn.isNotEmpty()) ends = ends.map { (s, e) -> (pulledIn[s] ?: s) to (pulledIn[e] ?: e) }
    val reached = ends.flatMap { listOf(it.first, it.second) }.filter { it in requiredIds }.toSet()
    // Unshared free ends carry no node, as in PolyvectorDrawing.network.
    fun keep(id: Int) = id in requiredIds || (freeCount[id] ?: 0) >= 2
    val usedRequired = (0 until rn.size).filter { rn.ids[it] in reached }
    val usedFree = free.filter { it.third !in pulledIn && keep(it.third) }
    val nodes = GeometryNodes(
        2,
        IntArray(usedRequired.size + usedFree.size) { if (it < usedRequired.size) rn.ids[usedRequired[it]] else usedFree[it - usedRequired.size].third },
        DoubleArray((usedRequired.size + usedFree.size) * 2) { i ->
            val k = i / 2
            if (k < usedRequired.size) rn.position(usedRequired[k]).let { if (i % 2 == 0) it.x.toDouble() else it.y.toDouble() }
            else usedFree[k - usedRequired.size].let { if (i % 2 == 0) it.first else it.second }
        },
        mapOf("required" to Channel.scalar(DoubleArray(usedRequired.size + usedFree.size) { if (it < usedRequired.size) 1.0 else 0.0 })),
    )
    val curves = tracedCurves.mapIndexed { i, c ->
        val (s, e) = ends[i]
        var xy = DoubleArray(c.size) { c[it].toDouble() }
        // Snapped ends move onto their node; pulled-in ends are extended to it.
        fun onto(id: Int, atStart: Boolean) {
            val p = rn.position(rn.indexOf(id)!!)
            val k = if (atStart) 0 else xy.size - 2
            if (hypot(xy[k] - p.x, xy[k + 1] - p.y) <= snapTolerance) { xy[k] = p.x.toDouble(); xy[k + 1] = p.y.toDouble() }
            else xy = if (atStart) doubleArrayOf(p.x.toDouble(), p.y.toDouble()) + xy else xy + doubleArrayOf(p.x.toDouble(), p.y.toDouble())
        }
        if (s in requiredIds) onto(s, true)
        if (e in requiredIds) onto(e, false)
        GeometryCurve(i, 2, xy, s.takeIf(::keep), e.takeIf(::keep))
    }
    val network = GeometryNetwork(2, nodes, curves, metadata + mapOf("requiredGraph" to (required.metadata["source"] ?: "given"), "snapTolerance" to snapTolerance.toString(), "reach" to reach.toString()))

    // Required a–b is kept when a walk over traced curves reaches b from a through free nodes only.
    val adjacency = HashMap<Int, MutableSet<Int>>()
    curves.forEach { c -> val s = c.startNode; val e = c.endNode; if (s != null && e != null) { adjacency.getOrPut(s) { HashSet() }.add(e); adjacency.getOrPut(e) { HashSet() }.add(s) } }
    fun joined(a: Int, b: Int): Boolean {
        val seen = hashSetOf(a); val queue = ArrayDeque(listOf(a))
        while (queue.isNotEmpty()) for (n in adjacency[queue.removeFirst()].orEmpty()) {
            if (n == b) return true
            if (n !in requiredIds && seen.add(n)) queue.add(n)
        }
        return false
    }
    val missingCurves = required.curves.filter { c -> val s = c.startNode; val e = c.endNode; s != null && e != null && !joined(s, e) }.map { it.id }
    val missingNodes = candidates.map { rn.ids[it] }.filter { it !in reached }
    return Triple(network, missingCurves, missingNodes)
}
