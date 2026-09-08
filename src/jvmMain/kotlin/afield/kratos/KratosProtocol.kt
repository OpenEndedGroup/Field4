package afield.kratos

import org.msgpack.core.MessagePack
import org.msgpack.core.MessagePacker
import org.msgpack.value.Value
import org.msgpack.value.ValueType
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val KRATOS_SCHEMA = 1
internal const val KRATOS_UNITS = "SI:m,kg,s,K"
internal const val MAX_KRATOS_FRAME = 64 * 1024 * 1024

internal fun MessagePacker.packField(value: Any?) {
    when (value) {
        null -> packNil()
        is String -> packString(value)
        is Boolean -> packBoolean(value)
        is Int -> packInt(value)
        is Long -> packLong(value)
        is Double -> packDouble(value)
        is ByteArray -> { packBinaryHeader(value.size); writePayload(value) }
        is List<*> -> { packArrayHeader(value.size); value.forEach { packField(it) } }
        is Map<*, *> -> { packMapHeader(value.size); value.forEach { (k, v) -> packString(k as String); packField(v) } }
        else -> error("Unsupported control-plane type ${value.javaClass.name}; numerical arrays must be binary")
    }
}

private fun Value.fieldValue(depth: Int = 0): Any? {
    require(depth < 64) { "MessagePack nesting exceeds limit" }
    return when (valueType) {
        ValueType.NIL -> null
        ValueType.BOOLEAN -> asBooleanValue().boolean
        ValueType.INTEGER -> asIntegerValue().toLong()
        ValueType.FLOAT -> asFloatValue().toDouble()
        ValueType.STRING -> asStringValue().asString()
        ValueType.BINARY -> asBinaryValue().asByteArray()
        ValueType.ARRAY -> asArrayValue().list().map { it.fieldValue(depth + 1) }
        ValueType.MAP -> asMapValue().map().entries.associate { it.key.asStringValue().asString() to it.value.fieldValue(depth + 1) }
        else -> error("MessagePack extension is not part of Kratos schema 1")
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.fieldMap(): Map<String, Any?> = this as? Map<String, Any?> ?: error("Expected protocol map")
internal fun Any?.fieldInteger(): Long = when (this) {
    is Int -> toLong()
    is Long -> this
    else -> error("Expected a protocol integer, got $this")
}
internal fun Map<String, Any?>.number(key: String): Long = get(key).fieldInteger()

internal fun writeKratosFrame(output: DataOutputStream, value: Map<String, Any?>) {
    val bytes = MessagePack.newDefaultBufferPacker().use { it.packField(value); it.toByteArray() }
    require(bytes.size in 1..MAX_KRATOS_FRAME) { "Kratos frame exceeds 64 MiB" }
    output.writeInt(bytes.size)
    output.write(bytes)
    output.flush()
}

internal fun readKratosFrame(input: DataInputStream, transferred: (Double) -> Unit = {}): Map<String, Any?> {
    val length = input.readInt()
    require(length in 1..MAX_KRATOS_FRAME) { "Invalid Kratos frame length $length" }
    val started = System.nanoTime()
    val bytes = ByteArray(length)
    input.readFully(bytes)
    transferred((System.nanoTime() - started) / 1e6)
    return MessagePack.newDefaultUnpacker(bytes).use {
        val result = it.unpackValue().fieldValue().fieldMap()
        require(!it.hasNext()) { "Trailing MessagePack data" }
        result
    }
}

/** Validated binary array view; no boxed numerical values and no shared files. */
internal class KratosArrays(payload: Map<String, Any?>) {
    private val binary = payload["binary"] as? ByteArray ?: error("Missing binary block")
    private val descriptors = (payload["arrays"] as? List<*>)?.map { it.fieldMap() } ?: error("Missing array descriptors")

    init {
        require(descriptors.map { it["name"] }.distinct().size == descriptors.size) { "Duplicate array names" }
    }

    fun view(name: String, dtype: String, shape: List<Int>): ByteBuffer {
        val d = descriptors.singleOrNull { it["name"] == name } ?: error("Missing array $name")
        require(d["dtype"] == dtype && d["byteOrder"] == "little" && d["storage"] == "inline") { "Invalid type/storage for $name" }
        val actual = (d["shape"] as? List<*>)?.map { it.fieldInteger() }
        require(actual == shape.map { it.toLong() }) { "Invalid shape for $name: $actual, expected $shape" }
        val itemBytes = when (dtype) { "float64" -> 8L; "int32" -> 4L; else -> error("Unsupported dtype $dtype") }
        val expected = shape.fold(itemBytes) { size, n -> require(n >= 0); Math.multiplyExact(size, n.toLong()) }
        val offset = d.number("offset")
        val length = d.number("length")
        require(length == expected && offset >= 0 && length >= 0 && offset <= binary.size.toLong() - length) { "Invalid byte range for $name" }
        return ByteBuffer.wrap(binary, offset.toInt(), length.toInt()).slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    fun doubles(name: String, vararg shape: Int): DoubleArray {
        val view = view(name, "float64", shape.toList()).asDoubleBuffer()
        return DoubleArray(view.remaining()).also { view.get(it); require(it.all(Double::isFinite)) { "$name contains nonfinite values" } }
    }

    fun ints(name: String, vararg shape: Int): IntArray {
        val view = view(name, "int32", shape.toList()).asIntBuffer()
        return IntArray(view.remaining()).also { view.get(it) }
    }
}
