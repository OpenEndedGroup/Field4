@file:afield.runtime.completion.CompletionCategory("Networking")

package afield.io.spatialink

import afield.geometry.path.FLine
import afield.geometry.path.LowSegmentFLineFitConfig
import afield.geometry.path.fitLowSegmentFLineResult
import afield.math.linalg.Vec3
import afield.math.linalg.vec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.ceil

/** Position in the headset's immersive tracking origin, in metres. */
@Serializable
data class SpatialInkPoint(val x: Float, val y: Float, val z: Float) {
    internal fun vector() = vec(x, y, z)
}

@Serializable
internal data class SpatialInkRequest(
    val revision: Int,
    val strokes: List<SpatialInkStroke>
)

@Serializable
internal data class SpatialInkStroke(val id: String, val points: List<SpatialInkPoint>)

/** An edge in the spatial sketch, with shared endpoint IDs and a sampled curve in metres. */
@Serializable
data class SpatialInkEdge(
    val id: String, val from: String, val to: String, val points: List<SpatialInkPoint>
) {
    /** Convert to a Field path, retaining the source stroke and graph endpoints as properties. */
    fun toFLine(): FLine = FLine().apply {
        moveTo(points.first().vector())
        points.drop(1).forEach { lineTo(it.vector()) }
        properties["spatialInk.stroke"] = id
        properties["spatialInk.from"] = from
        properties["spatialInk.to"] = to
    }
}

@Serializable
internal data class SpatialInkReply(val revision: Int, val edges: List<SpatialInkEdge>)

internal val spatialInkJson = Json

/** Read the last fitted snapshot from a Field spatial ink session's JSON file. */
fun readSpatialInk(file: File): List<FLine> =
    spatialInkJson.decodeFromString<SpatialInkReply>(file.readText()).edges.map { it.toFLine() }

internal class SpatialInkFitter {
    private data class Vertex(val id: String, val point: Vec3)
    private var source = emptyList<SpatialInkStroke>()
    private val edges = mutableListOf<SpatialInkEdge>()
    private val vertices = mutableListOf<Vertex>()
    private val vertexCounts = mutableListOf<Int>()

    fun update(request: SpatialInkRequest): SpatialInkReply {
        val incoming = request.strokes
        require(incoming.size <= 96 && incoming.map { it.id }.toSet().size == incoming.size)
        require(incoming.all { stroke ->
            stroke.id.length in 1..64 && stroke.points.size in 2..384 &&
                stroke.points.all { point -> listOf(point.x, point.y, point.z).all { it.isFinite() && it in -50f..50f } }
        })
        val prefix = source.zip(incoming).takeWhile { (old, new) -> old == new }.size
        while (edges.size > prefix) {
            edges.removeLast()
            val vertexCount = vertexCounts.removeLast()
            vertices.subList(vertexCount, vertices.size).clear()
        }
        for (stroke in incoming.drop(prefix)) {
            vertexCounts.add(vertices.size)
            edges.add(fit(stroke))
        }
        source = incoming
        return SpatialInkReply(request.revision, edges.toList())
    }

    private fun fit(stroke: SpatialInkStroke): SpatialInkEdge {
        val raw = stroke.points.map { it.vector() }
        val from = vertex(stroke.id + "/start", raw.first())
        val to = vertex(stroke.id + "/end", raw.last(), from.takeUnless {
            raw.first().distance(raw.last()) <= 0.012f && raw.any { point -> point.distance(raw.first()) > 0.035f }
        })
        val distances = FloatArray(raw.size)
        for (i in 1..raw.lastIndex) distances[i] = distances[i - 1] + raw[i - 1].distance(raw[i])
        val length = distances.last()
        val samples = if (length > 0) {
            val count = (ceil(length / 0.008f).toInt() + 1).coerceIn(2, 24)
            var segment = 1
            List(count) { i ->
                val distance = length * i / (count - 1)
                while (segment < raw.lastIndex && distances[segment] <= distance) segment++
                val width = distances[segment] - distances[segment - 1]
                val fraction = if (width == 0f) 0f else (distance - distances[segment - 1]) / width
                Vec3(raw[segment - 1]).lerp(raw[segment], fraction).also { point ->
                    // Fade endpoint displacement into the nearby samples instead of producing a kink.
                    val startWeight = (1f - distance / 0.045f).coerceAtLeast(0f)
                    val endWeight = (1f - (length - distance) / 0.045f).coerceAtLeast(0f)
                    point.add(Vec3(from.point).sub(raw.first()).mul(startWeight * (1f - endWeight)))
                    point.add(Vec3(to.point).sub(raw.last()).mul(endWeight * (1f - startWeight)))
                }
            }.toMutableList().apply { this[0] = from.point; this[lastIndex] = to.point }
        } else listOf(from.point, to.point)
        val result = samples.fitLowSegmentFLineResult(LowSegmentFLineFitConfig(maxSegments = 3, arcCandidateCount = 9))
        val curve = if (result.maxError <= 0.004 && result.line.nodes.size > 1) {
            result.line.sampleByNumber((samples.size * 2).coerceAtLeast(2))
        } else samples
        val points = curve.map { SpatialInkPoint(it.x, it.y, it.z) }.toMutableList()
        points[0] = SpatialInkPoint(from.point.x, from.point.y, from.point.z)
        points[points.lastIndex] = SpatialInkPoint(to.point.x, to.point.y, to.point.z)
        return SpatialInkEdge(stroke.id, from.id, to.id, points)
    }

    private fun vertex(id: String, point: Vec3, exclude: Vertex? = null): Vertex {
        return vertices.asSequence()
            .filter { it !== exclude && it.point.distanceSquared(point) <= 0.018f * 0.018f }
            .minByOrNull { it.point.distanceSquared(point) }
            ?: Vertex(id, point).also(vertices::add)
    }
}
