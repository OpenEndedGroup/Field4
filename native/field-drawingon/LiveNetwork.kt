import afield.geometry.path.FLine
import afield.geometry.path.fitLine2D
import afield.geometry.path.fitCircle
import afield.graphics.render.color
import afield.graphics.render.thickness
import afield.io.drawingon.DrawingOnCapture
import afield.io.drawingon.DrawingOnNetworkConfig
import afield.io.drawingon.DrawingOnNetwork
import afield.io.drawingon.DrawingOnNetworkEdge
import afield.io.drawingon.DrawingOnNetworkStroke
import afield.io.drawingon.DrawingOnSample
import afield.io.drawingon.drawingOn
import afield.io.drawingon.fitDrawingOnNetwork
import afield.math.linalg.Vec2
import afield.math.linalg.Vec3
import afield.media.image.simplifyPolyline
import afield.math.linalg.vec
import afield.math.optimization.ceres.AxisSet
import afield.math.optimization.ceres.PointRef
import afield.math.optimization.ceres.RobustLoss
import afield.math.optimization.ceres.geometryModel
import afield.runtime.lifecycle.inMain
import afield.ui.output.markdown
import afield.ui.visualization.lines
import afield.ui.visualization.LinesFraming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.max
import kotlin.math.min

// Paste into its own Field box and set the iPad's Field target to "network".
val pad = drawingOn("network")
val config = DrawingOnNetworkConfig(fitTolerance = 4f, retraceTolerance = 7f, joinTolerance = 6f)
val intersectionReach = 15f
var activeSession: String? = null
var activeTake: DrawingOnCapture? = null
var pendingSession: String? = null
var busy = false
var planJob: Job? = null
val drawingSessions = mutableSetOf<String>()
var liveRevision = 0L
var lastGraphKey: List<Any>? = null

// Compare the shared span of two fitted lines, even when their endpoints differ.
data class Trace(val stroke: DrawingOnNetworkStroke, val a: Vec2, val b: Vec2) {
    val axis = Vec2(b).sub(a).normalize()
    fun along(p: Vec2) = Vec2(p).sub(a).dot(axis)
    fun across(p: Vec2): Float {
        val offset = Vec2(p).sub(a)
        return axis.x * offset.y - axis.y * offset.x
    }
}

fun straightTrace(stroke: DrawingOnNetworkStroke): Trace? {
    if (stroke.tool != "freehand" || stroke.points.size < 2) return null
    val path = FLine().moveTo(stroke.points.first())
    stroke.points.drop(1).forEach { path.lineTo(it) }
    val (start, end, _) = path.fitLine2D(samples = 32)
    val length = start.distance(end)
    if (length < 24f) return null
    val trace = Trace(stroke, start, end)
    val errors = stroke.points.map { abs(trace.across(vec(it.x, it.y))) }.sorted()
    val tolerance = (length * 0.035f).coerceIn(3.5f, 8f)
    return trace.takeIf { errors[(errors.lastIndex * 0.9).toInt()] <= tolerance && errors.last() <= tolerance * 1.9f }
}

// Look for sustained, slow curvature in screen points, independent of the canvas zoom.
fun preserveTimedDetail(samples: List<DrawingOnSample>): Boolean {
    if (samples.size < 5 || samples.any { it.value("t") == null }) return false
    val screen = samples.map { sample ->
        vec(sample.value("preciseX") ?: sample.value("x") ?: sample.x,
            sample.value("preciseY") ?: sample.value("y") ?: sample.y)
    }
    val distance = FloatArray(samples.size)
    var first = 0
    for (last in 1 until samples.size) {
        distance[last] = distance[last - 1] + screen[last].distance(screen[last - 1])
        val gap = samples[last].t - samples[last - 1].t
        if (gap !in 0.0..0.12) first = last
        while (samples[last].t - samples[first].t > 0.42) first++
        val elapsed = samples[last].t - samples[first].t
        if (elapsed < 0.25 || last - first < 4) continue
        val traveled = distance[last] - distance[first]
        if (traveled < 8f || traveled / elapsed > 100.0) continue
        val start = screen[first]
        val chord = Vec2(screen[last]).sub(start)
        val length = chord.length()
        if (length < 1f) return true
        if ((first + 1 until last).any { index ->
            val offset = Vec2(screen[index]).sub(start)
            abs(chord.x * offset.y - chord.y * offset.x) >= length * 1.5f
        }) return true
    }
    return false
}

fun sameTrace(anchor: Trace, other: Trace): Boolean {
    if (anchor.stroke.layer != other.stroke.layer || abs(anchor.axis.dot(other.axis)) < 0.99f) return false
    val a = anchor.along(other.a)
    val b = anchor.along(other.b)
    val length = anchor.a.distance(anchor.b)
    val overlapStart = max(0f, min(a, b))
    val overlapEnd = min(length, max(a, b))
    if (overlapEnd - overlapStart < 0.55f * min(length, abs(b - a))) return false
    val crossA = anchor.across(other.a)
    val crossB = anchor.across(other.b)
    fun offset(at: Float) = crossA + (crossB - crossA) * (at - a) / (b - a)
    val left = offset(overlapStart)
    val right = offset(overlapEnd)
    // Close parallel wall faces stay separate when the offset is sustained.
    return abs(left) <= 11f && abs(right) <= 11f && abs((left + right) / 2f) <= 6f
}

// Extend fitted axes to a common corner or tee. A crossing with no nearby tip stays open.
fun snapIntersections(strokes: List<DrawingOnNetworkStroke>): Map<String, DrawingOnNetworkStroke> {
    val snaps = mutableMapOf<Pair<String, Int>, Pair<Float, Vec2>>()
    fun propose(id: String, side: Int, distance: Float, point: Vec2) {
        val key = id to side
        if (distance < (snaps[key]?.first ?: Float.POSITIVE_INFINITY)) snaps[key] = distance to point
    }
    val axes = strokes.map { stroke ->
        Trace(stroke, vec(stroke.points.first().x, stroke.points.first().y),
            vec(stroke.points.last().x, stroke.points.last().y))
    }
    for (i in axes.indices) for (j in i + 1 until axes.size) {
        val a = axes[i]
        val b = axes[j]
        if (a.stroke.layer != b.stroke.layer) continue
        val determinant = a.axis.x * b.axis.y - a.axis.y * b.axis.x
        if (abs(determinant) < 0.45f) continue
        val delta = Vec2(b.a).sub(a.a)
        val alongA = (delta.x * b.axis.y - delta.y * b.axis.x) / determinant
        val alongB = (delta.x * a.axis.y - delta.y * a.axis.x) / determinant
        val lengthA = a.a.distance(a.b)
        val lengthB = b.a.distance(b.b)
        val distanceA = min(abs(alongA), abs(alongA - lengthA))
        val distanceB = min(abs(alongB), abs(alongB - lengthB))
        val nearA = distanceA <= min(intersectionReach, lengthA * 0.18f)
        val nearB = distanceB <= min(intersectionReach, lengthB * 0.18f)
        val insideA = alongA in 0f..lengthA
        val insideB = alongB in 0f..lengthB
        val point = Vec2(a.a).add(Vec2(a.axis).mul(alongA))
        if (nearA && (nearB || insideB)) propose(a.stroke.id, if (alongA < lengthA / 2) 0 else 1, distanceA, point)
        if (nearB && (nearA || insideA)) propose(b.stroke.id, if (alongB < lengthB / 2) 0 else 1, distanceB, point)
    }
    return strokes.associate { stroke ->
        stroke.id to stroke.copy(points = stroke.points.mapIndexed { i, point ->
            snaps[stroke.id to i]?.second?.let { vec(it.x, it.y, 0) } ?: point
        })
    }
}

// Detect corners over a spatial window so coalesced sample density does not set the angle.
fun cornerRuns(stroke: DrawingOnNetworkStroke): List<DrawingOnNetworkStroke> {
    if (stroke.tool != "freehand" || stroke.points.size < 3) return listOf(stroke)
    val input = stroke.points
    val extent = max(input.maxOf { it.x } - input.minOf { it.x }, input.maxOf { it.y } - input.minOf { it.y })
    if (extent < 24f) return listOf(stroke)
    val closed = input.first().distance(input.last()) <= min(18f, extent * 0.16f)
    val points = if (closed) input.dropLast(1) + input.first() else input
    val planar = points.map { vec(it.x, it.y) }
    val candidates = simplifyPolyline(planar, max(3.0, extent * 0.012))
    val window = (extent * 0.12f).coerceIn(10f, 28f)
    val cuts = mutableListOf(0)
    var searchFrom = 1
    for (candidate in candidates.drop(1).dropLast(1)) {
        val index = (searchFrom until points.lastIndex).firstOrNull { planar[it] == candidate } ?: continue
        searchFrom = index + 1
        val left = (index - 1 downTo cuts.last()).firstOrNull { points[it].distance(points[index]) >= window } ?: continue
        val right = (index + 1..points.lastIndex).firstOrNull { points[it].distance(points[index]) >= window } ?: continue
        val incoming = Vec3(points[index]).sub(points[left]).normalize()
        val outgoing = Vec3(points[right]).sub(points[index]).normalize()
        if (incoming.dot(outgoing) < 0.55f) cuts += index
    }
    cuts += points.lastIndex
    if (cuts.size == 2) return listOf(stroke.copy(points = points))
    val runs = cuts.zipWithNext { a, b -> points.subList(a, b + 1) }
    val atSeam = if (closed && runs.size >= 3) {
        val before = runs.last().asReversed().firstOrNull { it.distance(points.first()) >= window }
        val after = runs.first().firstOrNull { it.distance(points.first()) >= window }
        if (before != null && after != null) {
            Vec3(points.first()).sub(before).normalize().dot(Vec3(after).sub(points.first()).normalize())
        } else -1f
    } else -1f
    val joined = if (atSeam > 0.85f) {
        runs.drop(1).dropLast(1) + listOf(runs.last() + runs.first().drop(1))
    } else runs
    return joined.mapIndexed { index, run -> stroke.copy(id = "${stroke.id}/run/$index", points = run) }
}

fun circularFit(points: List<Vec3>): FLine? {
    if (points.size < 4) return null
    val path = FLine().moveTo(points.first())
    points.drop(1).forEach { path.lineTo(it) }
    val samples = path.sampleByNumber(32)
    val a = points.first()
    val b = points.last()
    val closed = a.distanceSquared(b) < 1e-6f
    val center = if (closed) {
        // Fit near the origin to avoid cancellation when the canvas has been panned far away.
        val local = FLine().moveTo(0, 0)
        points.drop(1).forEach { local.lineTo(Vec3(it).sub(a)) }
        val (initial, _, _) = local.fitCircle(samples = 32)
        vec(a.x + initial.x, a.y + initial.y, 0)
    } else {
        // Fit directly on the perpendicular bisector, so the arc meets both graph nodes.
        val normal = vec(a.y - b.y, b.x - a.x, 0).normalize()
        val midpoint = Vec3(a).lerp(b, 0.5f)
        val halfChordSquared = a.distanceSquared(midpoint).toDouble()
        var numerator = 0.0
        var denominator = 0.0
        for (p in samples) {
            val offset = Vec3(p).sub(midpoint)
            val y = offset.dot(normal).toDouble()
            numerator += y * (offset.lengthSquared() - halfChordSquared)
            denominator += 2 * y * y
        }
        if (denominator < 1e-6) return null
        midpoint.add(normal.mul((numerator / denominator).toFloat()))
    }
    val r = center.distance(a)
    if (!r.isFinite() || r < 8f) return null
    val angles = samples.map { atan2((it.y - center.y).toDouble(), (it.x - center.x).toDouble()) }
    val turns = angles.zipWithNext { x, y -> atan2(sin(y - x), cos(y - x)) }
    val sweep = turns.sum()
    if (abs(sweep) !in 0.33..(2 * PI + 0.05) ||
        turns.sumOf { abs(it) } - abs(sweep) > 0.55) return null
    val bulge = r * (1 - cos(abs(sweep) / 2))
    if (bulge < 2.3) return null
    val tolerance = min((r * 0.07f).coerceIn(3f, 9f).toDouble(), bulge * 0.45)
    if (samples.count { abs(it.distance(center) - r) > tolerance } > samples.size / 6 ||
        points.any { abs(it.distance(center) - r) > tolerance * 2.2 }) return null
    val parts = ceil(abs(sweep) / (PI / 2)).toInt()
    val step = sweep / parts
    val result = FLine().moveTo(a)
    fun position(angle: Double) = vec(center.x + r * cos(angle), center.y + r * sin(angle), 0)
    fun tangent(angle: Double) = vec(-sin(angle), cos(angle), 0)
    val handle = (4.0 / 3.0 * r * tan(step / 4)).toFloat()
    for (i in 0 until parts) {
        val begin = angles.first() + i * step
        val end = begin + step
        val from = if (i == 0) a else position(begin)
        val to = if (i == parts - 1) b else position(end)
        result.cubicTo(Vec3(from).add(tangent(begin).mul(handle)), Vec3(to).sub(tangent(end).mul(handle)), to)
    }
    return result
}

// A small sideways tick or turnback at pen-down/up can mark an endpoint.
fun endpointMarker(points: List<Vec3>, budget: Float): Int {
    var traveled = 0f
    for (i in 1 until points.size - 2) {
        val tip = points[i]
        traveled += tip.distance(points[i - 1])
        if (traveled > budget) break
        if (traveled < 2.5f) continue
        val ahead = (i + 1..points.lastIndex).firstOrNull { tip.distance(points[it]) >= 10f } ?: break
        val approach = Vec3(tip).sub(points.first())
        if (approach.length() < traveled * 0.35f ||
            approach.normalize().dot(Vec3(points[ahead]).sub(tip).normalize()) < 0.23f) return i
    }
    return 0
}

fun withoutEndpointMarkers(stroke: DrawingOnNetworkStroke): DrawingOnNetworkStroke {
    if (stroke.tool != "freehand" || stroke.preserveDetail || stroke.points.size < 6) return stroke
    val points = stroke.points
    val budget = min(11f, points.zipWithNext { a, b -> a.distance(b) }.sum() * 0.12f)
    val start = endpointMarker(points, budget)
    val end = endpointMarker(points.asReversed(), budget)
    if (start == 0 && end == 0) return stroke
    for ((left, right) in listOf(start to end, start to 0, 0 to end).distinct()) {
        if (left == 0 && right == 0) continue
        val remaining = points.subList(left, points.size - right)
        val candidate = stroke.copy(points = remaining)
        if (remaining.size >= 5 && remaining.first().distance(remaining.last()) <= min(5f, budget)) {
            val loop = remaining.dropLast(1) + remaining.first()
            if (circularFit(loop) != null) return stroke.copy(points = loop)
        }
        if (circularFit(remaining) != null || straightTrace(candidate) != null) return candidate
    }
    return stroke
}

fun smoothFallback(points: List<Vec3>): FLine {
    val result = FLine().moveTo(points.first())
    for (i in 0 until points.lastIndex) {
        val a = points[i]
        val b = points[i + 1]
        val before = points[max(0, i - 1)]
        val after = points[min(points.lastIndex, i + 2)]
        val reach = a.distance(b) / 3f
        fun handle(from: Vec3, to: Vec3): Vec3 {
            val delta = Vec3(to).sub(from)
            val length = delta.length()
            return if (length > 0f) delta.mul(min(reach / length, 1f / 6f)) else delta
        }
        result.cubicTo(Vec3(a).add(handle(before, b)), Vec3(b).sub(handle(a, after)), b)
    }
    return result
}

data class Recognition(val graph: DrawingOnNetwork, val measured: Int, val straight: Int, val merged: Int, val arcs: Int, val smooth: Int, val annotationLayers: Set<String> = emptySet(), val detail: Int = 0)

fun recognize(take: DrawingOnCapture): Recognition {
    val measured = take.networkStrokesAt(preserveDetail = ::preserveTimedDetail)
        .filter { stroke -> stroke.points.firstOrNull()?.let { point -> stroke.points.any { it != point } } == true }
    val groups = mutableListOf<MutableList<Trace>>()
    val curves = mutableListOf<DrawingOnNetworkStroke>()
    val arcRuns = mutableSetOf<String>()
    val runs = measured.flatMap { input ->
        val stroke = withoutEndpointMarkers(input)
        val wholeArc = stroke.tool == "freehand" && !stroke.preserveDetail && circularFit(stroke.points) != null
        if (wholeArc) arcRuns += stroke.id
        val pieces = if (stroke.preserveDetail || wholeArc || straightTrace(stroke) != null) listOf(stroke) else cornerRuns(stroke)
        pieces.map { it to stroke.id }
    }
    val originalByRun = runs.associate { (run, original) -> run.id to original }
    for ((stroke, _) in runs) {
        if (stroke.id !in arcRuns && stroke.tool == "freehand" && !stroke.preserveDetail &&
            circularFit(stroke.points) != null) arcRuns += stroke.id
        val trace = if (stroke.preserveDetail || stroke.id in arcRuns) null else straightTrace(stroke)
        if (trace == null) {
            curves += stroke
        } else {
            val group = groups.firstOrNull { sameTrace(it.first(), trace) }
            if (group == null) groups += mutableListOf(trace) else group += trace
        }
    }
    val sources = mutableMapOf<String, List<String>>()
    for (group in groups) {
        val anchor = group.first()
        val extent = group.flatMap { listOf(anchor.along(it.a), anchor.along(it.b)) }
        fun point(t: Float) = vec(anchor.a.x + anchor.axis.x * t, anchor.a.y + anchor.axis.y * t, 0)
        curves += anchor.stroke.copy(points = listOf(point(extent.min()), point(extent.max())))
        sources[anchor.stroke.id] = group.map { it.stroke.id }
    }
    val snapped = snapIntersections(curves.filter { it.id in sources })
    val graph = fitDrawingOnNetwork(curves.map { snapped[it.id] ?: it }, config)
    val rawById = runs.associate { (run, _) -> run.id to run }
    val edgeCounts = graph.edges.flatMap { it.strokeIds }.groupingBy { it }.eachCount()
    var arcs = 0
    var smooth = 0
    val edges = graph.edges.map { edge ->
        val originals = edge.strokeIds.flatMap { sources[it] ?: listOf(it) }.map { originalByRun[it] ?: it }.distinct()
        val rawId = edge.strokeIds.singleOrNull()
        val raw = rawById[rawId]
        var line = edge.line
        if (raw?.preserveDetail == true) {
            line = smoothFallback(line.nodes.map { it.to })
            line.properties["drawingOn.preserveDetail"] = true
        } else if (raw?.tool == "freehand" && rawId !in sources && (line.nodes.size > 2 || rawId in arcRuns)) {
            val points = if (edgeCounts[rawId] == 1 && raw.points.size >= 4) {
                listOf(line.nodes.first().to) + raw.points.drop(1).dropLast(1) + line.nodes.last().to
            } else line.sampleByNumber((line.cursor().lengthD() / 4).toInt().coerceIn(4, 160))
            val arc = circularFit(points)
            if (arc != null) {
                line = arc
                line.properties["drawingOn.arc"] = true
                arcs++
            } else if (line.nodes.none { it is FLine.CubicTo } || edgeCounts[rawId] == 1) {
                val dense = FLine().moveTo(points.first())
                points.drop(1).forEach { dense.lineTo(it) }
                val samples = dense.sampleByNumber((dense.cursor().lengthD() / 4).toInt().coerceIn(4, 240)).toMutableList()
                samples[0] = points.first()
                samples[samples.lastIndex] = points.last()
                line = smoothFallback(samples)
                smooth++
            }
        }
        if (line !== edge.line) line.properties.putAll(edge.line.properties)
        line.properties["drawingOn.sourceStrokes"] = originals
        edge.copy(strokeIds = originals, line = line)
    }
    val annotationLayers = take.layers.filter { layer ->
        layer.name.trim().lowercase() in setOf("annotation", "annotations", "markup", "hatch", "hatching")
    }.map { it.id }.toSet()
    return Recognition(graph.copy(edges = edges), measured.size, groups.sumOf { it.size },
        groups.count { it.size > 1 }, arcs, smooth, annotationLayers, measured.count { it.preserveDetail })
}

fun showRecognition(result: Recognition, publish: Boolean) {
    val graph = result.graph
    val paths = graph.toFLines(showNodes = true).onEach {
        it.color = vec(0.3, 0.85, 1.0, 0.9)
        it.thickness = 2.0
    }
    val degree = (graph.edges.map { it.from } + graph.edges.map { it.to }).groupingBy { it }.eachCount()
    val junctions = degree.count { it.value > 1 }
    markdown("Recognition", "${result.measured} strokes · ${result.straight} straight runs · ${result.arcs} arcs · ${result.smooth} smooth curves · ${result.detail} detail strokes preserved · ${result.merged} retrace groups · ${graph.edges.size} edges · $junctions shared nodes")
    lines(paths, w = 560.0, h = 360.0, fitToSize = true, framing = LinesFraming.Fit)
    if (publish) pad.publish(paths, title = "Clean drawing · ${result.measured} strokes / ${graph.edges.size} edges", samplesPerCubic = 6)
}

data class PlanResult(val graph: DrawingOnNetwork, val marks: List<FLine>, val aligned: Int, val joined: Int, val status: String)

fun planStraight(edge: DrawingOnNetworkEdge): Boolean =
    edge.from != edge.to && edge.line.nodes.size == 2 && edge.line.nodes.last() is FLine.LineTo

fun simplifyPlan(result: Recognition): PlanResult {
    val graph = result.graph
    val fixedStrokeIds = graph.clusters.flatMap { it.strokes }.filter { it.tool != "freehand" }.map { it.id }.toSet()
    val sourceLength = graph.edges.associate { it.id to Vec3(it.line.nodes.first().to).distance(it.line.nodes.last().to) }
    val straight = graph.edges.filter(::planStraight)
    val incidence = (graph.edges.map { it.from } + graph.edges.map { it.to }).groupingBy { it }.eachCount()
    fun direction(edge: DrawingOnNetworkEdge) = Vec3(edge.line.nodes.last().to).sub(edge.line.nodes.first().to).normalize()
    // Hatch candidates must be isolated short strokes, repeated with similar spacing and direction.
    val hatch = straight.filter { edge ->
        val length = sourceLength.getValue(edge.id)
        length in 8f..65f && incidence[edge.from] == 1 && incidence[edge.to] == 1 &&
            edge.strokeIds.none { it in fixedStrokeIds }
    }
    val inferredHatch = hatch.filter { edge ->
        val a = edge.line.nodes.first().to
        val b = edge.line.nodes.last().to
        val u = direction(edge)
        val middle = Vec3(a).lerp(b, 0.5f)
        val size = sourceLength.getValue(edge.id)
        val offsets = hatch.filter { it != edge && it.layer == edge.layer }.mapNotNull { other ->
            val length = sourceLength.getValue(other.id)
            if (length !in size * 0.65f..size * 1.5f || abs(u.dot(direction(other))) < 0.98f) return@mapNotNull null
            val offset = Vec3(other.line.nodes.first().to).lerp(other.line.nodes.last().to, 0.5f).sub(middle)
            val across = u.x * offset.y - u.y * offset.x
            across.takeIf { abs(it) in 4f..23f && abs(u.dot(offset)) <= size * 0.26f }
        }
        offsets.indices.any { i -> offsets.drop(i + 1).any { j ->
            abs(offsets[i] - j) in 4f..26f &&
                min(abs(offsets[i]), abs(j)) >= 0.42f * max(abs(offsets[i]), abs(j))
        } }
    }
    val hatchIds = inferredHatch.map { it.id }.toSet()
    val marks = graph.edges.filter { it.layer in result.annotationLayers || it.id in hatchIds }
    val markIds = marks.map { it.id }.toSet()
    val kept = graph.edges.filter { it.id !in markIds }
    val nodes = graph.nodes.associateBy { it.id }
    val used = kept.flatMap { listOf(it.from, it.to) }.toSet()
    val solvedPositions = mutableMapOf<String, Vec3>()
    val pinned = kept.filter { !planStraight(it) || it.strokeIds.any { id -> id in fixedStrokeIds } }
        .flatMap { listOf(it.from, it.to) }.toSet()
    var aligned = 0
    var axisLayers = 0
    var solverIssue = false
    for ((layer, edges) in kept.filter(::planStraight).groupBy { it.layer }) {
        val candidates = edges.filter { sourceLength.getValue(it.id) >= 35f }
        val angles = candidates.map { edge ->
            val u = direction(edge)
            atan2(u.y.toDouble(), u.x.toDouble())
        }
        if (angles.size < 3) continue
        val weights = candidates.map { sourceLength.getValue(it.id).toDouble().coerceAtMost(250.0) }
        fun angularError(angle: Double, frame: Double): Double =
            abs(atan2(sin(4 * (angle - frame)), cos(4 * (angle - frame))) / 4)
        val strongest = angles.maxBy { hypothesis ->
            angles.indices.filter { angularError(angles[it], hypothesis) < 0.18 }.sumOf { weights[it] }
        }
        val support = angles.indices.filter { angularError(angles[it], strongest) < 0.18 }
        val theta = atan2(support.sumOf { sin(4 * angles[it]) * weights[it] },
            support.sumOf { cos(4 * angles[it]) * weights[it] }) / 4
        fun error(angle: Double) = angularError(angle, theta)
        val evidence = angles.indices.filter { error(angles[it]) < 0.18 }
        if (evidence.size < 3 || evidence.sumOf { weights[it] } < weights.sum() * 0.65) continue
        fun horizontal(angle: Double) = abs(cos(angle - theta)) > abs(sin(angle - theta))
        if (evidence.map { horizontal(angles[it]) }.distinct().size < 2) continue
        val eligible = evidence.map { candidates[it] }.filter { it.strokeIds.none { id -> id in fixedStrokeIds } }
        if (eligible.isEmpty()) continue
        val eligibleIds = eligible.map { it.id }.toSet()
        val fixed = pinned + edges.filter { it.id !in eligibleIds }.flatMap { listOf(it.from, it.to) }
        val touched = eligible.flatMap { listOf(it.from, it.to) }.distinct()
        val origin = nodes.getValue(touched.first())
        val c = cos(theta)
        val s = sin(theta)
        val refs = mutableMapOf<String, PointRef>()
        val model = geometryModel {
            robustLoss(RobustLoss.NONE)
            for (id in touched) {
                val n = nodes.getValue(id)
                val dx = (n.x - origin.x).toDouble()
                val dy = (n.y - origin.y).toDouble()
                refs[id] = point(dx * c + dy * s, -dx * s + dy * c, 0.0)
            }
            constraints {
                for (edge in eligible) {
                    val u = direction(edge)
                    val axis = if (horizontal(atan2(u.y.toDouble(), u.x.toDouble()))) AxisSet.Y else AxisSet.X
                    same(listOf(refs.getValue(edge.from), refs.getValue(edge.to)), axis, weight = 32.0)
                }
            }
        }
        val proposed = try {
            model.openSession(baselinePrior = 1.0).use { session ->
                for (id in touched.filter { it in fixed }) session.pin(refs.getValue(id), weight = 1e7)
                if (!session.solve(maxIterations = 20)) emptyMap()
                else touched.filterNot { it in fixed }.associateWith { id ->
                    val p = session.get(refs.getValue(id))
                    vec(origin.x + p.x * c - p.y * s, origin.y + p.x * s + p.y * c, 0)
                }
            }
        } catch (error: Exception) {
            solverIssue = true
            emptyMap()
        } catch (error: LinkageError) {
            solverIssue = true
            emptyMap()
        }
        fun original(id: String): Vec3 = nodes.getValue(id).let { vec(it.x, it.y, 0) }
        fun position(id: String) = proposed[id] ?: original(id)
        val safe = proposed.isNotEmpty() && proposed.all { (id, p) ->
            p.x.isFinite() && p.y.isFinite() && p.distance(original(id)) <= 9f
        } && eligible.all { edge ->
            Vec3(position(edge.to)).sub(position(edge.from)).dot(direction(edge)) >=
                sourceLength.getValue(edge.id) * 0.8f
        }
        // Leave near, disconnected collinear tips alone if a displacement would close their gap.
        val freeTips = edges.flatMap { listOf(it.from, it.to) }.distinct().filter { incidence[it] == 1 }
        val closesGap = safe && freeTips.indices.any { i ->
            val a = freeTips[i]
            freeTips.drop(i + 1).any { b ->
                val old = original(a).distance(original(b))
                (a in proposed || b in proposed) && old in 1f..45f &&
                    position(a).distance(position(b)) < old * 0.75f
            }
        }
        if (safe && !closesGap) {
            solvedPositions.putAll(proposed)
            axisLayers++
            aligned += eligible.count { edge ->
                val u = Vec3(position(edge.to)).sub(position(edge.from))
                error(atan2(u.y.toDouble(), u.x.toDouble())) < 0.035
            }
        }
    }
    val fittedNodes = graph.nodes.filter { it.id in used }.map { node ->
        solvedPositions[node.id]?.let { node.copy(x = it.x, y = it.y) } ?: node
    }
    val at = fittedNodes.associateBy { it.id }
    fun pos(id: String): Vec3 = at.getValue(id).let { vec(it.x, it.y, 0) }
    val simplified = kept.map { edge ->
        if (edge.from !in solvedPositions && edge.to !in solvedPositions) edge
        else edge.copy(line = FLine().moveTo(pos(edge.from)).lineTo(pos(edge.to)).apply { properties.putAll(edge.line.properties) })
    }.associateByTo(linkedMapOf()) { it.id }
    // Collapse only straight degree-two continuations. T-junctions and corners remain graph nodes.
    var joined = 0
    for (node in fittedNodes) {
        val pair = simplified.values.filter { it.from == node.id || it.to == node.id }
        if (pair.size != 2 || pair.any { !planStraight(it) || it.strokeIds.any { id -> id in fixedStrokeIds } }) continue
        val (first, second) = pair
        if (first.layer != second.layer) continue
        val a = if (first.from == node.id) first.to else first.from
        val b = if (second.from == node.id) second.to else second.from
        if (a == b) continue
        val left = Vec3(pos(a)).sub(pos(node.id)).normalize()
        val right = Vec3(pos(b)).sub(pos(node.id)).normalize()
        if (left.dot(right) > -0.998f) continue
        val sources = (first.strokeIds + second.strokeIds).distinct()
        val line = FLine().moveTo(pos(a)).lineTo(pos(b)).apply {
            properties.putAll(first.line.properties)
            properties["drawingOn.sourceStrokes"] = sources
        }
        simplified[first.id] = first.copy(from = a, to = b, line = line, strokeIds = sources)
        simplified.remove(second.id)
        joined++
    }
    val finalIds = simplified.values.flatMap { listOf(it.from, it.to) }.toSet()
    val status = if (solverIssue) "Ceres unavailable for this fit" else "$axisLayers layers with fitted axes"
    return PlanResult(graph.copy(nodes = fittedNodes.filter { it.id in finalIds }, edges = simplified.values.toList()),
        marks.map { it.line.copy() }, aligned, joined, status)
}

fun showPlan(plan: PlanResult, result: Recognition, publish: Boolean) {
    val paths = plan.graph.toFLines().onEach { it.color = vec(1, 1, 1, 0.95); it.thickness = 2.0 }
    val marks = plan.marks.map { it.copy().apply { color = vec(0.55, 0.55, 0.55, 0.35) } }
    markdown("Plan cleanup", "${result.graph.edges.size} fitted edges → ${plan.graph.edges.size} plan edges · ${plan.aligned} aligned · ${plan.joined} continuations merged · ${marks.size} annotation or hatch edges · ${plan.status}")
    lines(paths + marks, channel = "Plan geometry", w = 560.0, h = 360.0, fitToSize = true, framing = LinesFraming.Fit)
    if (publish) pad.publish(paths, title = "Plan cleanup · ${plan.graph.edges.size} edges", samplesPerCubic = 6)
}

markdown("Recognition", "Listening for live pen lifts on target **network**.")

pad.onEvent { event ->
    if (!event.upload && event.type == "stroke.begin") {
        drawingSessions += event.session
        liveRevision++
        planJob?.cancel()
    }
    if (!event.upload && event.type == "stroke.end") drawingSessions -= event.session
    if (!event.upload && event.type in setOf("stroke.end", "stroke.correct", "stroke.visibility", "layers.state")) {
        liveRevision++
        planJob?.cancel()
        pendingSession = event.session
        if (!busy) {
            busy = true
            inMain {
                try {
                    while (pendingSession != null) {
                        delay(90)
                        val session = checkNotNull(pendingSession)
                        pendingSession = null
                        val previous = activeTake.takeIf { activeSession == session }
                        val (take, result) = withContext(Dispatchers.Default) {
                            val recording = previous ?: pad.capture(session)
                            recording to recognize(recording.refresh())
                        }
                        activeSession = session
                        activeTake = take
                        val key = listOf(session, result.measured, result.straight, result.merged, result.arcs, result.smooth, result.detail, result.annotationLayers) + result.graph.edges.map { edge ->
                            listOf(edge.id, edge.from, edge.to, edge.strokeIds, edge.line.nodes.map { it.storage.toList() })
                        }
                        if (pendingSession == null && session !in drawingSessions) {
                            val revision = liveRevision
                            planJob?.cancel()
                            planJob = inMain {
                                delay(650)
                                val plan = withContext(Dispatchers.Default) { simplifyPlan(result) }
                                if (revision == liveRevision) showPlan(plan, result, publish = true)
                            }
                        }
                        if (key != lastGraphKey) {
                            lastGraphKey = key
                            showRecognition(result, publish = true)
                        }
                    }
                } finally {
                    busy = false
                }
            }
        }
    }
}
