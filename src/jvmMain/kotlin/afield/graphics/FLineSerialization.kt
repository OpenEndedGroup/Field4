package afield.graphics

import afield.stdlib.Vec2
import afield.stdlib.Vec3
import afield.stdlib.Vec4
import afield.stdlib.vec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.awt.Stroke

object FLineSerializer : KSerializer<FLine> {
    override val descriptor: SerialDescriptor = FLineSurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: FLine) {
        encoder.encodeSerializableValue(FLineSurrogate.serializer(), value.toSurrogate())
    }

    override fun deserialize(decoder: Decoder): FLine {
        return decoder.decodeSerializableValue(FLineSurrogate.serializer()).toFLine()
    }
}

@Serializable
private data class FLineSurrogate(
    val version: Int = 1,
    val properties: Map<String, FLinePropertySurrogate> = emptyMap(),
    val nodes: List<FLineNodeSurrogate> = emptyList()
)

@Serializable
private data class FLineNodeSurrogate(
    val kind: String,
    val points: List<FloatArray> = emptyList(),
    val properties: Map<String, FLinePropertySurrogate> = emptyMap()
)

@Serializable
private data class FLinePropertySurrogate(
    val type: String,
    val value: JsonElement
)

private fun FLine.toSurrogate(): FLineSurrogate {
    return FLineSurrogate(
        properties = properties.toSerializablePropertyMap(owner = "line"),
        nodes = nodes.mapIndexed { index, node -> node.toSurrogate(index) }
    )
}

private fun FLine.Node.toSurrogate(index: Int): FLineNodeSurrogate {
    val kind = when (this) {
        is FLine.MoveTo -> "move"
        is FLine.LineTo -> "line"
        is FLine.CubicTo -> "cubic"
        is FLine.Close -> "close"
    }
    return FLineNodeSurrogate(
        kind = kind,
        points = storage.map { it.toJsonFloatArray() },
        properties = properties.toSerializablePropertyMap(owner = "node[$index]")
    )
}

private fun FLineSurrogate.toFLine(): FLine {
    if (version != 1) {
        throw SerializationException("Unsupported FLine JSON version $version")
    }

    val line = FLine()
    properties.forEach { (key, value) ->
        line.properties[key] = value.toProperty(path = "line.$key")
    }
    nodes.forEachIndexed { index, node ->
        line.nodes.add(node.toNode(index))
    }
    return line
}

private fun FLineNodeSurrogate.toNode(index: Int): FLine.Node {
    val node = when (kind) {
        "move" -> FLine.MoveTo().set(pointAt(index, 0))
        "line" -> FLine.LineTo().set(pointAt(index, 0))
        "cubic" -> FLine.CubicTo().set(
            pointAt(index, 0),
            pointAt(index, 1),
            pointAt(index, 2)
        )
        "close" -> {
            require(points.isEmpty()) { "FLine close node at index $index cannot contain points" }
            FLine.Close()
        }
        else -> throw SerializationException("Unknown FLine node kind '$kind' at index $index")
    }

    properties.forEach { (key, value) ->
        node.properties[key] = value.toProperty(path = "node[$index].$key")
    }
    return node
}

private fun Map<String, Any?>.toSerializablePropertyMap(owner: String): Map<String, FLinePropertySurrogate> {
    val out = linkedMapOf<String, FLinePropertySurrogate>()
    for ((key, value) in this) {
        if (shouldSkipProperty(key, value)) continue
        out[key] = value.toPropertySurrogate(path = "$owner.$key")
    }
    return out
}

private fun shouldSkipProperty(key: String, value: Any?): Boolean {
    if (key == FLINE_PLANE2D_KEY || key == "_tcb") return true
    if (value is Plane2D) return true
    if (key == "onClick" && value is Function<*>) return true
    if (key == "thicken" && value is Stroke) return true
    return false
}

private fun Any?.toPropertySurrogate(path: String): FLinePropertySurrogate {
    return when (this) {
        is Byte -> FLinePropertySurrogate("byte", JsonPrimitive(this))
        is Short -> FLinePropertySurrogate("short", JsonPrimitive(this))
        is Int -> FLinePropertySurrogate("int", JsonPrimitive(this))
        is Long -> FLinePropertySurrogate("long", JsonPrimitive(this))
        is Float -> FLinePropertySurrogate("float", JsonPrimitive(this))
        is Double -> FLinePropertySurrogate("double", JsonPrimitive(this))
        is Boolean -> FLinePropertySurrogate("boolean", JsonPrimitive(this))
        is String -> FLinePropertySurrogate("string", JsonPrimitive(this))
        is Vec2 -> FLinePropertySurrogate("vec2", JsonArray(toJsonPrimitives(this.x, this.y)))
        is Vec3 -> FLinePropertySurrogate("vec3", JsonArray(toJsonPrimitives(this.x, this.y, this.z)))
        is Vec4 -> FLinePropertySurrogate("vec4", JsonArray(toJsonPrimitives(this.x, this.y, this.z, this.w)))
        null -> throw SerializationException("Unsupported null FLine property at $path")
        else -> throw SerializationException(
            "Unsupported FLine property at $path: ${this::class.qualifiedName}"
        )
    }
}

private fun FLinePropertySurrogate.toProperty(path: String): Any {
    return when (type) {
        "byte" -> value.jsonPrimitive.int.toByte()
        "short" -> value.jsonPrimitive.int.toShort()
        "int" -> value.jsonPrimitive.int
        "long" -> value.jsonPrimitive.long
        "float" -> value.jsonPrimitive.float
        "double" -> value.jsonPrimitive.double
        "boolean" -> value.jsonPrimitive.boolean
        "string" -> value.jsonPrimitive.content
        "vec2" -> value.toFloatArray(path, expectedSize = 2).let { vec(it[0], it[1]) }
        "vec3" -> value.toFloatArray(path, expectedSize = 3).let { vec(it[0], it[1], it[2]) }
        "vec4" -> value.toFloatArray(path, expectedSize = 4).let { vec(it[0], it[1], it[2], it[3]) }
        else -> throw SerializationException("Unknown FLine property type '$type' at $path")
    }
}

private fun FLineNodeSurrogate.pointAt(nodeIndex: Int, pointIndex: Int): Vec3 {
    if (pointIndex !in points.indices) {
        throw SerializationException("FLine node $nodeIndex kind '$kind' is missing point $pointIndex")
    }
    val point = points[pointIndex]
    if (point.size != 3) {
        throw SerializationException("FLine node $nodeIndex point $pointIndex must have 3 coordinates, got ${point.size}")
    }
    return vec(point[0], point[1], point[2])
}

private fun Vec3.toJsonFloatArray(): FloatArray = floatArrayOf(x, y, z)

private fun toJsonPrimitives(vararg values: Float): List<JsonPrimitive> = values.map { JsonPrimitive(it) }

private fun JsonElement.toFloatArray(path: String, expectedSize: Int): FloatArray {
    val elements = jsonArray
    if (elements.size != expectedSize) {
        throw SerializationException("Expected $expectedSize values at $path, got ${elements.size}")
    }
    return FloatArray(expectedSize) { index -> elements[index].jsonPrimitive.float }
}
