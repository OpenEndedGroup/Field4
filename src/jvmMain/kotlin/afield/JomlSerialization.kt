package afield

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.FloatArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.joml.Quaternionf
import org.joml.Vector2f
import org.joml.Vector3f
import org.joml.Vector4f

object Vector2fSerializer : KSerializer<Vector2f> {
    override val descriptor: SerialDescriptor = FloatArraySerializer().descriptor

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Vector2f) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("Vector2fSerializer only supports JSON")
        jsonEncoder.encodeJsonElement(JsonArray(listOf(JsonPrimitive(value.x), JsonPrimitive(value.y))))
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Vector2f {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("Vector2fSerializer only supports JSON")
        val values = decodeFloatArray(jsonDecoder, descriptor.serialName, expectedSize = 2)
        return Vector2f(values[0], values[1])
    }
}

object Vector3fSerializer : KSerializer<Vector3f> {
    override val descriptor: SerialDescriptor = FloatArraySerializer().descriptor

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Vector3f) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("Vector3fSerializer only supports JSON")
        jsonEncoder.encodeJsonElement(JsonArray(listOf(JsonPrimitive(value.x), JsonPrimitive(value.y), JsonPrimitive(value.z))))
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Vector3f {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("Vector3fSerializer only supports JSON")
        val values = decodeFloatArray(jsonDecoder, descriptor.serialName, expectedSize = 3)
        return Vector3f(values[0], values[1], values[2])
    }
}

object Vector4fSerializer : KSerializer<Vector4f> {
    override val descriptor: SerialDescriptor = FloatArraySerializer().descriptor

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Vector4f) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("Vector4fSerializer only supports JSON")
        jsonEncoder.encodeJsonElement(
            JsonArray(listOf(JsonPrimitive(value.x), JsonPrimitive(value.y), JsonPrimitive(value.z), JsonPrimitive(value.w)))
        )
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Vector4f {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("Vector4fSerializer only supports JSON")
        val values = decodeFloatArray(jsonDecoder, descriptor.serialName, expectedSize = 4)
        return Vector4f(values[0], values[1], values[2], values[3])
    }
}

object QuaternionfSerializer : KSerializer<Quaternionf> {
    override val descriptor: SerialDescriptor = FloatArraySerializer().descriptor

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Quaternionf) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("QuaternionfSerializer only supports JSON")
        jsonEncoder.encodeJsonElement(
            JsonArray(listOf(JsonPrimitive(value.x), JsonPrimitive(value.y), JsonPrimitive(value.z), JsonPrimitive(value.w)))
        )
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Quaternionf {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("QuaternionfSerializer only supports JSON")
        val values = decodeFloatArray(jsonDecoder, descriptor.serialName, expectedSize = 4)
        return Quaternionf(values[0], values[1], values[2], values[3])
    }
}

val FieldSerializersModule = SerializersModule {
    contextual(Vector2f::class, Vector2fSerializer)
    contextual(Vector3f::class, Vector3fSerializer)
    contextual(Vector4f::class, Vector4fSerializer)
    contextual(Quaternionf::class, QuaternionfSerializer)
}

val FieldJson = Json {
    serializersModule = FieldSerializersModule
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private fun decodeFloatArray(decoder: JsonDecoder, typeName: String, expectedSize: Int): FloatArray {
    val values = decoder.decodeJsonElement().jsonArray
    if (values.size != expectedSize) {
        throw SerializationException("Expected $expectedSize values for $typeName, got ${values.size}")
    }
    return FloatArray(expectedSize) { index -> values[index].jsonPrimitive.float }
}
