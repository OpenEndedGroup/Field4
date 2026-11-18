@file:afield.runtime.completion.CompletionCategory("Networking")

package afield.io.drawingon

import afield.geometry.path.FLine
import afield.geometry.path.LowSegmentFLineFitConfig
import afield.geometry.path.fitLowSegmentFLineResult
import afield.graphics.render.circle
import afield.graphics.render.pointSize
import afield.graphics.render.pointed
import afield.math.linalg.Vec3
import afield.math.linalg.vec
import afield.media.image.simplifyPolyline
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Distances are in Drawing On canvas coordinates, independent of the viewport transform. */
data class DrawingOnNetworkConfig(
    val fitTolerance: Float = 1.25f,
    val retraceTolerance: Float = 2f,
    val joinTolerance: Float = 5f
) {
    init {
        require(fitTolerance.isFinite() && fitTolerance > 0)
        require(retraceTolerance.isFinite() && retraceTolerance >= 0)
        require(joinTolerance.isFinite() && joinTolerance >= 0)
    }
}

/** A source stroke in drawing order. The network uses only X and Y from [points]. */
data class DrawingOnNetworkStroke(
    val id: String,
    val layer: String,
    val tool: String,
    val points: List<Vec3>
)

/** Vertex keyed by source strokes. Coincident vertices on different layers remain separate. */
data class DrawingOnNetworkNode(val id: String, val layer: String, val x: Float, val y: Float)

/** Each edge owns an FLine and points to its two graph vertices. A closed loop can share a vertex. */
data class DrawingOnNetworkEdge(
    val id: String, val from: String, val to: String, val layer: String,
    val strokeIds: List<String>, val line: FLine
)

/** The grouped input to curve fitting, retained for inspection and comparison with StrokeStrip. */
data class DrawingOnStrokeCluster(val id: String, val strokes: List<DrawingOnNetworkStroke>)

/** A graph in the iPad drawing plane, plus the clustering evidence used to derive it. */
data class DrawingOnNetwork(
    val nodes: List<DrawingOnNetworkNode>,
    val edges: List<DrawingOnNetworkEdge>,
    val clusters: List<DrawingOnStrokeCluster>
) {
    /** Ordinary FLines, optionally including a point for each graph vertex. */
    fun toFLines(showNodes: Boolean = false): List<FLine> {
        val curves = edges.map { it.line.copy() }
        if (!showNodes) return curves
        return curves + nodes.map { node ->
            FLine().moveTo(node.x, node.y).apply {
                pointed = true
                pointSize = 0.8
                properties["drawingOn.networkNode"] = node.id
            }
        }
    }

    /** Export freehand clusters for the upstream StrokeStrip CLI. Coordinates share one origin shift. */
    fun strokeStripScap(): String {
        val freehand = clusters.map { group -> group.strokes.filter { it.tool == "freehand" } }.filter { it.isNotEmpty() }
        val points = freehand.asSequence().flatten().flatMap { it.points.asSequence() }
        val minX = points.minOfOrNull { it.x } ?: 0f
        val minY = points.minOfOrNull { it.y } ?: 0f
        val width = max(1.0, ceil((points.maxOfOrNull { it.x } ?: minX).toDouble() - minX))
        val height = max(1.0, ceil((points.maxOfOrNull { it.y } ?: minY).toDouble() - minY))
        return buildString {
            append("#").append(width.toLong()).append('\t').append(height.toLong()).append("\n@1.8\n")
            var strokeIndex = 0
            freehand.forEachIndexed { clusterIndex, group -> group.forEach { stroke ->
                append("{\n\t#").append(strokeIndex++).append('\t').append(clusterIndex).append('\n')
                stroke.points.forEach { p ->
                    append('\t').append(p.x.toDouble() - minX).append('\t').append(p.y.toDouble() - minY).append("\t0\n")
                }
                append("}\n")
            } }
        }
    }
}

private class NetworkCurve(val cluster: DrawingOnStrokeCluster, val points: List<Vec3>) {
    val seed = cluster.strokes.first()
    val id get() = cluster.id
    val explicit get() = seed.tool != "freehand"
    val circle get() = seed.tool == "circle"
    val cuts = mutableListOf<Pair<Float, DrawingOnNetworkNode>>()
    fun endpoint(side: Int) = if (side == 0) points.first() else points.last()
}

/**
 * Fit a conservative 2D network from strokes in capture order. Close, coextensive retraces are
 * grouped against the oldest stroke in a cluster. Endpoint joins and endpoint-to-interior joins
 * are inferred within a layer. Interior crossings stay independent. Circles remain closed loops.
 */
fun fitDrawingOnNetwork(
    strokes: List<DrawingOnNetworkStroke>,
    config: DrawingOnNetworkConfig = DrawingOnNetworkConfig()
): DrawingOnNetwork {
    require(strokes.map { it.id }.distinct().size == strokes.size) { "Stroke IDs must be unique" }
    val groups = mutableListOf<MutableList<DrawingOnNetworkStroke>>()
    val groupSamples = mutableListOf<List<Vec3>>()
    for (source in strokes) {
        val points = source.points.map {
            require(it.x.isFinite() && it.y.isFinite()) { "Stroke points must be finite" }
            vec(it.x, it.y, 0)
        }.dedupe()
        if (points.size < 2) continue
        val stroke = source.copy(points = points)
        val samples = if (stroke.tool == "freehand") resample(points, 32) else emptyList()
        val index = if (samples.isEmpty() || config.retraceTolerance == 0f) -1 else groups.indices.firstOrNull {
            groups[it].first().layer == stroke.layer && groupSamples[it].isNotEmpty() &&
                alignedRetrace(groupSamples[it], samples, config.retraceTolerance) != null
        } ?: -1
        if (index < 0) {
            groups += mutableListOf(stroke)
            groupSamples += samples
        } else groups[index] += stroke
    }

    val clusters = groups.map { DrawingOnStrokeCluster(it.first().id, it.toList()) }
    val curves = clusters.mapIndexedNotNull { index, cluster ->
        val seed = cluster.strokes.first()
        val points = when (seed.tool) {
            "line", "circle" -> listOf(seed.points.first(), seed.points.last()).dedupe()
            else -> {
                val fitInput = if (cluster.strokes.size == 1) seed.points else {
                    val aligned = listOf(groupSamples[index]) + cluster.strokes.drop(1).map {
                        checkNotNull(alignedRetrace(groupSamples[index], resample(it.points, 32), config.retraceTolerance))
                    }
                    groupSamples[index].indices.map { i ->
                        vec(aligned.map { it[i].x }.average(), aligned.map { it[i].y }.average(), 0)
                    }
                }
                simplifyPolyline(fitInput.map { vec(it.x, it.y) }, config.fitTolerance.toDouble() / 2)
                    .map { vec(it.x, it.y, 0) }.dedupe()
            }
        }
        if (points.size < 2) null else NetworkCurve(cluster, points)
    }
    return buildNetwork(curves, clusters, config)
}

private fun buildNetwork(
    curves: List<NetworkCurve>,
    clusters: List<DrawingOnStrokeCluster>,
    config: DrawingOnNetworkConfig
): DrawingOnNetwork {
    val nodes = linkedMapOf<String, DrawingOnNetworkNode>()
    val edges = mutableListOf<DrawingOnNetworkEdge>()
    val endpoints = mutableMapOf<Pair<String, Int>, DrawingOnNetworkNode>()
    val incident = mutableMapOf<String, MutableList<Vec3>>()
    val byLayer = curves.filterNot { it.circle }.groupBy { it.seed.layer }
    val tolerance = config.joinTolerance
    val toleranceSquared = tolerance * tolerance
    for ((layer, paths) in byLayer) {
        // Exact tool geometry gets first choice of vertex position.
        for (curve in paths.sortedByDescending { it.explicit }) for (side in 0..1) {
            val point = curve.endpoint(side)
            val id = "${curve.id}/$side"
            val otherEnd = endpoints[curve.id to (1 - side)]
            val tangent = if (side == 0) Vec3(curve.points[1]).sub(point).normalize()
                else Vec3(curve.points[curve.points.lastIndex - 1]).sub(point).normalize()
            val nearby = nodes.values.asSequence()
                .filter { it.layer == layer && (it != otherEnd || point.distanceSquared(curve.endpoint(1-side)) <= 1e-8f) }
                .map { it to point.distanceSquared(vec(it.x, it.y, 0)) }
                .filter { it.second <= if (curve.explicit) 1e-8f else toleranceSquared }
                .filter { (node, distance) ->
                    distance <= 1e-8f || incident[node.id].orEmpty().none { it.dot(tangent) > 0.94f }
                }
                .minByOrNull { it.second }?.first
            if (nearby != null) {
                incident.getOrPut(nearby.id) { mutableListOf() }.add(tangent)
                endpoints[curve.id to side] = nearby
                continue
            }
            val teeTolerance = if (curve.explicit) 0f else tolerance
            val tee = paths.asSequence().filter { it !== curve }
                .mapNotNull { host -> projectInterior(point, tangent, host, teeTolerance) }
                .minByOrNull { it.distanceSquared }
            val cut = tee?.host?.cuts?.minByOrNull { abs(it.first - tee.parameter) }
                ?.second?.takeIf {
                    val position = vec(it.x, it.y, 0)
                    point.distanceSquared(position) <= teeTolerance * teeTolerance &&
                        tee.point.distanceSquared(position) <= teeTolerance * teeTolerance
                }
            val position = tee?.point ?: point
            val node = cut ?: DrawingOnNetworkNode(id, layer, position.x, position.y).also {
                nodes[id] = it
                tee?.host?.cuts?.add(tee.parameter to it)
            }
            incident.getOrPut(node.id) { mutableListOf() }.add(tangent)
            endpoints[curve.id to side] = node
        }
    }

    fun addEdge(curve: NetworkCurve, a: DrawingOnNetworkNode, b: DrawingOnNetworkNode, line: FLine, id: String) {
        line.properties["drawingOn.networkEdge"] = id
        line.properties["drawingOn.layer"] = curve.seed.layer
        val sources = curve.cluster.strokes.map { it.id }
        line.properties["drawingOn.sourceStrokes"] = sources
        edges += DrawingOnNetworkEdge(id, a.id, b.id, curve.seed.layer, sources, line)
    }

    for (curve in curves) {
        if (curve.circle) {
            val center = curve.points.first()
            val radius = center.distance(curve.points.last())
            val node = DrawingOnNetworkNode("${curve.id}/0", curve.seed.layer, center.x + radius, center.y)
            nodes[node.id] = node
            addEdge(curve, node, node, FLine().circle(center.x, center.y, radius), curve.id)
            continue
        }
        val start = endpoints.getValue(curve.id to 0)
        val end = endpoints.getValue(curve.id to 1)
        val splits = listOf(0f to start) + curve.cuts.sortedBy { it.first } + listOf(curve.points.lastIndex.toFloat() to end)
        for ((left, right) in splits.zipWithNext()) {
            val (from, a) = left
            val (until, b) = right
            val points = buildList {
                add(vec(a.x, a.y, 0))
                for (i in (from.toInt() + 1)..min(ceil(until).toInt() - 1, curve.points.lastIndex)) add(curve.points[i])
                add(vec(b.x, b.y, 0))
            }.dedupe()
            if (points.size < 2) continue
            val id = if (a == start) curve.id else "${curve.id}/after/${a.id}"
            addEdge(curve, a, b, fitCurve(points, config.fitTolerance), id)
        }
    }
    return DrawingOnNetwork(nodes.values.toList(), edges, clusters)
}

private data class InteriorHit(val host: NetworkCurve, val parameter: Float, val point: Vec3, val distanceSquared: Float)

private fun projectInterior(point: Vec3, tangent: Vec3, host: NetworkCurve, tolerance: Float): InteriorHit? {
    var best: InteriorHit? = null
    val limit = tolerance * tolerance
    if (point.distanceSquared(host.points.first()) <= limit || point.distanceSquared(host.points.last()) <= limit) return null
    for (i in 0 until host.points.lastIndex) {
        val a = host.points[i]
        val b = host.points[i+1]
        val delta = Vec3(b).sub(a)
        if (abs(Vec3(delta).normalize().dot(tangent)) > 0.94f) continue
        val fraction = (Vec3(point).sub(a).dot(delta) / delta.lengthSquared()).coerceIn(0f, 1f)
        val onHost = Vec3(a).lerp(b, fraction)
        val distance = point.distanceSquared(onHost)
        if (distance <= limit && distance < (best?.distanceSquared ?: Float.POSITIVE_INFINITY) &&
            i + fraction > 0 && i + fraction < host.points.lastIndex) {
            best = InteriorHit(host, i + fraction, onHost, distance)
        }
    }
    return best
}

private fun fitCurve(points: List<Vec3>, tolerance: Float): FLine {
    if (points.size == 2) return FLine().moveTo(points.first()).lineTo(points.last())
    val samples = if (points.size > 32) resample(points, 32) else points
    val fit = samples.fitLowSegmentFLineResult(LowSegmentFLineFitConfig(maxSegments = 3, arcCandidateCount = 9))
    // Bound the fit in both directions, including corners discarded by uniform resampling.
    val fitted = fit.line.sampleByNumber(32)
    if (fit.line.nodes.first().to == points.first() && fit.line.nodes.last().to == points.last() &&
        fit.maxError <= tolerance && points.all { distanceToPolyline(it, fitted) <= tolerance } &&
        fitted.all { distanceToPolyline(it, points) <= tolerance }) return fit.line
    return FLine().moveTo(points.first()).apply { points.drop(1).forEach { lineTo(it) } }
}

private fun distanceToPolyline(point: Vec3, path: List<Vec3>): Float =
    sqrt(path.zipWithNext().minOf { (a, b) ->
        val delta = Vec3(b).sub(a)
        val length = delta.lengthSquared()
        val f = if (length == 0f) 0f else (Vec3(point).sub(a).dot(delta) / length).coerceIn(0f, 1f)
        point.distanceSquared(Vec3(a).lerp(b, f))
    })

private fun alignedRetrace(anchor: List<Vec3>, candidate: List<Vec3>, tolerance: Float): List<Vec3>? {
    val forward = anchor.first().distanceSquared(candidate.first()) + anchor.last().distanceSquared(candidate.last())
    val reverse = anchor.first().distanceSquared(candidate.last()) + anchor.last().distanceSquared(candidate.first())
    val aligned = if (reverse < forward) candidate.asReversed() else candidate
    val maxDistance = tolerance * tolerance
    // Repeated strokes must agree along their entire length. Small lateral drift is allowed;
    // sustained offset is held to a tighter limit to preserve close parallel edges.
    var signed = 0f
    for (i in anchor.indices) {
        if (anchor[i].distanceSquared(aligned[i]) > maxDistance) return null
        val tangent = Vec3(anchor[min(i+1, anchor.lastIndex)]).sub(anchor[max(i-1, 0)])
        if (tangent.lengthSquared() > 0) {
            tangent.normalize()
            val offset = Vec3(aligned[i]).sub(anchor[i])
            signed += tangent.x * offset.y - tangent.y * offset.x
        }
    }
    return aligned.takeIf { abs(signed / anchor.size) <= tolerance * 0.5f }
}

private fun resample(points: List<Vec3>, count: Int): List<Vec3> {
    val lengths = FloatArray(points.size)
    for (i in 1..points.lastIndex) lengths[i] = lengths[i-1] + points[i].distance(points[i-1])
    var segment = 1
    return List(count) { i ->
        val distance = lengths.last() * i / (count - 1)
        while (segment < points.lastIndex && lengths[segment] < distance) segment++
        Vec3(points[segment-1]).lerp(points[segment],
            ((distance - lengths[segment-1]) / (lengths[segment] - lengths[segment-1])).coerceIn(0f, 1f))
    }
}

private fun List<Vec3>.dedupe(): List<Vec3> = filterIndexed { i, p -> i == 0 || p != this[i-1] }
