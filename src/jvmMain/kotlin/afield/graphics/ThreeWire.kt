package afield.graphics

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/*
 * What a Three.js view puts on the wire, and what it refuses to.
 *
 * A view's geometry goes out in two parts. The websocket carries a *description*: the payload with every
 * numeric array replaced by a reference into a buffer table, small however large the scene is. The
 * buffers are one binary response from `GET /three-buffers?uid=…&generation=…`, written straight from
 * the payload's own arrays by [writeThreeBuffers] when, and only if, a viewer asks for them. Nothing is
 * formatted as decimal text, nothing the size of the scene is allocated, and the bytes are written on a
 * Ktor IO thread, never on the runloop.
 *
 * Why: 2026-10-07 00:02, on a private house host, an AI tool styled 224 cut panels in one fabrication
 * view. `styleParts` ended in `update`, which encoded the whole payload as JSON text on the runloop, and
 * with the old generation 99.9% full the encoder's buffer growth thrashed for twenty minutes; every
 * document on the host froze with it. The first fix (`cdca61721`) made a style change a small
 * `three/appearance` message, packed drawable buffers as base64 inside the JSON and added the size guard.
 * Base64 in JSON is still one string the size of the scene at both ends, so this replaced it.
 *
 * Clients that do not ask for buffers in their hello — the native visionOS viewer (`FLineWire.swift`,
 * via `subscribe-all`) — get [forNativeClient]: the FLine half as JSON numbers, the format they decode,
 * without the drawables and solid topology they never read. It is encoded only when such a client is
 * connected. The viewer side is the `wire-payload` region of `threejs_viewer.html`.
 * `ThreeGeometryWireTest` holds both sides.
 */

/** `Content-Type` of a `/three-buffers` response, excluded from gzip: float data barely compresses. */
internal const val THREE_BUFFERS_CONTENT_TYPE = "application/x-field-three-buffers"

/**
 * The default ceiling on one view's geometry, buffers and description together:
 * `AFIELD_THREE_PAYLOAD_LIMIT_MB`, or 512 MB.
 *
 * The server streams the buffers from arrays it already holds, so the limit protects the browser,
 * which has to hold them and then copy them to the GPU. A 3M-triangle mesh with normals and colours is
 * about 100 MB.
 */
internal val defaultThreePayloadLimitBytes: Long =
    (System.getenv("AFIELD_THREE_PAYLOAD_LIMIT_MB")?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: 512L) * 1024 * 1024

/**
 * A Three.js view's geometry too large to send.
 *
 * Thrown by [ThreeGeometryViewState.update], before anything is encoded when the estimate is over, or
 * after encoding the description when the measured total is. Nothing is sent either way, and the view
 * keeps what it was showing.
 */
class ThreePayloadTooLargeException internal constructor(
    val uid: String,
    /** The geometry's size in bytes, estimated before encoding or measured after. */
    val bytes: Long,
    val limitBytes: Long,
    /** The biggest contributors, largest first, as (what, bytes). */
    val largest: List<Pair<String, Long>>,
    message: String,
) : IllegalStateException(message)

/** A part of a payload and about how many bytes it accounts for. */
internal data class ThreeWireCost(val what: String, val bytes: Long)

/** `-12.345678,`: `Float.toString` of a typical coordinate, and its comma. */
private const val PLAIN_FLOAT_BYTES = 11L
private const val PLAIN_INT_BYTES = 7L

/** Bytes per element of the integer index types the viewer's `typedArrayCtor` knows. Others stay JSON. */
private val INDEX_WIDTH = mapOf(
    "Uint8Array" to 1, "Int8Array" to 1,
    "Uint16Array" to 2, "Int16Array" to 2,
    "Uint32Array" to 4, "Int32Array" to 4,
)

private val NO_FLOATS = FloatArray(0)
private val NO_INTS = IntArray(0)
private val NO_DOUBLES = DoubleArray(0)

private fun drawableName(drawable: ThreeDrawable, ordinal: Int): String =
    (if (drawable.id.isNotBlank()) "drawable '${drawable.id}'" else "drawable #$ordinal") +
        (drawable.label?.takeIf { it.isNotBlank() && it != drawable.id }?.let { " ($it)" } ?: "")

private fun bufferBytes(data: ThreeGeometryData?): Long = if (data == null) 0L else
    data.attributes.values.sumOf { it.array.size * 4L } +
        (data.index?.let { index -> index.array.size * (INDEX_WIDTH[index.type]?.toLong() ?: PLAIN_INT_BYTES) } ?: 0L)

private fun plainBytes(data: ThreeGeometryData?): Long = if (data == null) 0L else
    data.attributes.values.sumOf { it.array.size * PLAIN_FLOAT_BYTES } + (data.index?.array?.size ?: 0) * PLAIN_INT_BYTES

private fun interactiveName(obj: ThreeInteractiveObject) = "interactive FLine ${obj.lineIndex}" + (obj.label?.let { " ($it)" } ?: "")

/**
 * Where a payload's bytes go, from array lengths alone — nothing is formatted or encoded.
 *
 * Exact for the buffers; an estimate for what stays in the description as JSON (interactive vertices,
 * an editable scene's nodes, topology strings). Small fields — bounds, styles, camera — are left out.
 */
internal fun threeWireCosts(payload: ThreeGeometryPayload): List<ThreeWireCost> = buildList {
    listOf("FLine lines" to payload.lines, "FLine fills" to payload.fills, "FLine points" to payload.points)
        .forEach { (what, data) -> bufferBytes(data).takeIf { it > 0 }?.let { add(ThreeWireCost(what, it)) } }
    payload.interactive.forEach { obj ->
        add(ThreeWireCost(interactiveName(obj), bufferBytes(obj.lines) + bufferBytes(obj.fills) + bufferBytes(obj.points) +
            obj.vertices.size * 48L + obj.topology.length))
    }
    payload.scene?.let { scene ->
        add(ThreeWireCost("editable scene", scene.lines.sumOf { it.nodes.size * 96L + it.topology.length }))
    }
    payload.drawables.forEachIndexed { ordinal, drawable ->
        add(ThreeWireCost(drawableName(drawable, ordinal), bufferBytes(drawable.geometry) + drawable.topology.length))
    }
    // A solid's exact boundary, beside its display mesh: doubles, so per vertex as large again.
    payload.inspector?.parts?.forEach { (id, part) ->
        add(ThreeWireCost("solid topology '$id'",
            part.positions.size * 8L + (part.indices.size + part.faces.size) * 4L + part.edges.size * 8L))
    }
}

/** The same, for the JSON a native client is sent instead: FLine arrays as decimal text, nothing else large. */
private fun nativeCost(payload: ThreeGeometryPayload): Long =
    plainBytes(payload.lines) + plainBytes(payload.fills) + plainBytes(payload.points) +
        payload.interactive.sumOf { plainBytes(it.lines) + plainBytes(it.fills) + plainBytes(it.points) + it.vertices.size * 48L + it.topology.length } +
        (payload.scene?.lines?.sumOf { it.nodes.size * 96L + it.topology.length } ?: 0L)

private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)

internal fun threePayloadTooLarge(
    uid: String,
    bytes: Long,
    limitBytes: Long,
    costs: List<ThreeWireCost>,
    measured: Boolean,
): ThreePayloadTooLargeException {
    val largest = costs.sortedByDescending { it.bytes }.take(8)
    val drawables = costs.count { it.what.startsWith("drawable ") }
    val listing = largest.joinToString(", ") { "${it.what} ${megabytes(it.bytes)}" } +
        (if (costs.size > largest.size) ", and ${costs.size - largest.size} more" else "")
    val message = "Three.js view '$uid': the geometry is ${megabytes(bytes)} " +
        (if (measured) "(measured after encoding)" else "(estimated before encoding)") +
        ", over the ${megabytes(limitBytes)} limit. Nothing was sent and the view keeps its previous geometry. " +
        "Largest: $listing" + (if (drawables > 0) " ($drawables drawables in all)" else "") + ". " +
        "Send coarser meshes, split the parts across views, or raise AFIELD_THREE_PAYLOAD_LIMIT_MB."
    return ThreePayloadTooLargeException(uid, bytes, limitBytes, largest.map { it.what to it.bytes }, message)
}

/**
 * Where a description's buffers are: fetch [generation] from `/three-buffers`, [bytes] long, and read
 * each entry as [ThreeWireBuffer.count] little-endian elements of its type at its offset.
 *
 * Public only because [ThreeGeometryPayload] is; it is a wire type, and nothing outside this file makes one.
 */
@Serializable
@ConsistentCopyVisibility
data class ThreeWireBuffers internal constructor(val generation: String, val bytes: Long, val entries: List<ThreeWireBuffer>)

/** One typed array in a buffer response. Offsets are multiples of eight, so a Float64 view needs no copy. */
@Serializable
@ConsistentCopyVisibility
data class ThreeWireBuffer internal constructor(val type: String, val offset: Long, val count: Int)

/** One numeric array, as the buffer response carries it. Holds the payload's own array, never a copy. */
internal sealed class ThreeWireSource(val type: String, val count: Int, val width: Int) {
    val byteLength: Long get() = count.toLong() * width
    class Floats(val values: FloatArray) : ThreeWireSource("Float32Array", values.size, 4)
    class Doubles(val values: DoubleArray) : ThreeWireSource("Float64Array", values.size, 8)
    class Ints(type: String, width: Int, val values: IntArray) : ThreeWireSource(type, values.size, width)
    /** `[[a, b], …]` edge lists, flattened. */
    class Pairs(val values: List<List<Int>>) : ThreeWireSource("Int32Array", values.size * 2, 4)
}

/** A view's buffers for one generation: what `/three-buffers` writes. */
internal class ThreeGeometryBuffers(val bytes: Long, private val sources: List<ThreeWireSource>) {
    fun writeTo(out: OutputStream) = writeThreeBuffers(sources, out)
    fun toByteArray(): ByteArray = ByteArrayOutputStream(bytes.toInt()).also(::writeTo).toByteArray()
}

/** A payload split for the wire: the [description] to encode, and the arrays its references point into. */
internal class ThreeWireSplit(val description: ThreeGeometryPayload, val buffers: ThreeGeometryBuffers?)

private class Splitter {
    val sources = ArrayList<ThreeWireSource>()
    val entries = ArrayList<ThreeWireBuffer>()
    var bytes = 0L

    fun add(source: ThreeWireSource): Int {
        val offset = (bytes + 7) / 8 * 8
        entries += ThreeWireBuffer(source.type, offset, source.count)
        sources += source
        bytes = offset + source.byteLength
        return sources.size - 1
    }

    fun geometry(data: ThreeGeometryData?, owner: String): ThreeGeometryData? = data?.let { geometry ->
        ThreeGeometryData(
            attributes = geometry.attributes.mapValues { (name, attribute) -> attribute(attribute, owner, name) },
            index = geometry.index?.let(::index),
        )
    }

    private fun attribute(attribute: ThreeAttribute, owner: String, name: String): ThreeAttribute {
        val values = attribute.array
        // It always failed — kotlinx refuses to write NaN — but as a serializer error that said nothing about where.
        for (i in values.indices) require(values[i].isFinite()) {
            "$owner: attribute '$name' holds ${values[i]} at index $i, which the viewer cannot draw"
        }
        return attribute.copy(array = NO_FLOATS, buffer = add(ThreeWireSource.Floats(values)))
    }

    private fun index(index: ThreeIndex): ThreeIndex {
        val width = INDEX_WIDTH[index.type] ?: return index
        return index.copy(array = NO_INTS, buffer = add(ThreeWireSource.Ints(index.type, width, index.array)))
    }

    fun topology(part: ThreePartTopology, id: String): ThreePartTopology {
        for (i in part.positions.indices) require(part.positions[i].isFinite()) {
            "solid topology '$id' holds ${part.positions[i]} at index $i, which the viewer cannot use"
        }
        val refs = linkedMapOf(
            "positions" to add(ThreeWireSource.Doubles(part.positions)),
            "indices" to add(ThreeWireSource.Ints("Int32Array", 4, part.indices)),
            "faces" to add(ThreeWireSource.Ints("Int32Array", 4, part.faces)),
        )
        // Pairs are what `solidPartTopology` writes; anything else keeps its own shape.
        val pairs = part.edges.all { it.size == 2 }
        if (pairs) refs["edges"] = add(ThreeWireSource.Pairs(part.edges))
        return part.copy(positions = NO_DOUBLES, indices = NO_INTS, faces = NO_INTS,
            edges = if (pairs) emptyList() else part.edges, buffers = refs)
    }
}

/**
 * Split [this] into a description and its buffers. Fails, naming the part and attribute, on a value
 * the viewer cannot draw.
 *
 * Every FLine and drawable array goes to the buffers, and so does a solid's exact topology. What stays
 * JSON is structure: an editable scene's nodes, interactive vertices, topology strings, styles.
 */
internal fun ThreeGeometryPayload.splitForWire(generation: String): ThreeWireSplit {
    val splitter = Splitter()
    val description = copy(
        lines = splitter.geometry(lines, "FLine lines"),
        fills = splitter.geometry(fills, "FLine fills"),
        points = splitter.geometry(points, "FLine points"),
        interactive = interactive.map { obj ->
            val owner = interactiveName(obj)
            obj.copy(lines = splitter.geometry(obj.lines, "$owner lines"), fills = splitter.geometry(obj.fills, "$owner fills"),
                points = splitter.geometry(obj.points, "$owner points"))
        },
        drawables = drawables.mapIndexed { ordinal, drawable ->
            drawable.copy(geometry = splitter.geometry(drawable.geometry, drawableName(drawable, ordinal))!!)
        },
        inspector = inspector?.let { spec ->
            if (spec.parts.isEmpty()) spec else spec.copy(parts = spec.parts.mapValues { (id, part) -> splitter.topology(part, id) })
        },
    )
    if (splitter.sources.isEmpty()) return ThreeWireSplit(description, null)
    return ThreeWireSplit(
        description.copy(buffers = ThreeWireBuffers(generation, splitter.bytes, splitter.entries)),
        ThreeGeometryBuffers(splitter.bytes, splitter.sources),
    )
}

/**
 * The buffers, little-endian, each padded to a multiple of eight, through one 64 KB chunk.
 *
 * Reads the payload's arrays as they are now: a box must not change an array after handing it to
 * [ThreeGeometryViewState.update]. Every builder in Field copies, so none does.
 */
internal fun writeThreeBuffers(sources: List<ThreeWireSource>, out: OutputStream) {
    val chunk = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
    fun flush() {
        out.write(chunk.array(), 0, chunk.position())
        chunk.clear()
    }
    fun room(bytes: Int) { if (chunk.remaining() < bytes) flush() }
    var position = 0L
    for (source in sources) {
        val pad = ((8 - position % 8) % 8).toInt()
        room(pad)
        repeat(pad) { chunk.put(0) }
        position += pad
        when (source) {
            is ThreeWireSource.Floats -> {
                val values = source.values
                var i = 0
                while (i < values.size) {
                    room(4)
                    val n = minOf(values.size - i, chunk.remaining() / 4)
                    chunk.asFloatBuffer().put(values, i, n)
                    chunk.position(chunk.position() + n * 4)
                    i += n
                }
            }
            is ThreeWireSource.Doubles -> {
                val values = source.values
                var i = 0
                while (i < values.size) {
                    room(8)
                    val n = minOf(values.size - i, chunk.remaining() / 8)
                    chunk.asDoubleBuffer().put(values, i, n)
                    chunk.position(chunk.position() + n * 8)
                    i += n
                }
            }
            is ThreeWireSource.Ints -> {
                val values = source.values
                // Narrowing keeps the low bits, which is what `new Uint16Array([...])` does to the same numbers.
                when (source.width) {
                    1 -> for (value in values) { room(1); chunk.put(value.toByte()) }
                    2 -> for (value in values) { room(2); chunk.putShort(value.toShort()) }
                    else -> {
                        var i = 0
                        while (i < values.size) {
                            room(4)
                            val n = minOf(values.size - i, chunk.remaining() / 4)
                            chunk.asIntBuffer().put(values, i, n)
                            chunk.position(chunk.position() + n * 4)
                            i += n
                        }
                    }
                }
            }
            is ThreeWireSource.Pairs -> for (edge in source.values) {
                room(8)
                chunk.putInt(edge[0])
                chunk.putInt(edge[1])
            }
        }
        position += source.byteLength
    }
    flush()
    out.flush()
}

/** What a client that does not fetch buffers is sent: the FLine half, without what it does not read. */
internal fun ThreeGeometryPayload.forNativeClient(): ThreeGeometryPayload =
    copy(drawables = emptyList(), inspector = inspector?.copy(parts = emptyMap()))

/** [forNativeClient], encoded, or `null` when even its estimate is over [limitBytes]. */
internal fun encodeForNativeClient(json: Json, stamped: ThreeGeometryPayload, limitBytes: Long): ByteArray? {
    val native = stamped.forNativeClient()
    val estimate = nativeCost(native)
    if (estimate > limitBytes) return null
    return encodeThreeWire(json, native, estimate).takeIf { it.size <= limitBytes }
}

/** Encode straight to UTF-8 bytes, in a buffer sized from the estimate: these bytes are the frame. */
@OptIn(ExperimentalSerializationApi::class)
internal fun encodeThreeWire(json: Json, payload: ThreeGeometryPayload, sizeHint: Long): ByteArray {
    // The estimate leaves out keys and small fields, so allow a little over it.
    val out = WireBytes((sizeHint + sizeHint / 32 + 65_536).coerceIn(4096L, Int.MAX_VALUE - 64L).toInt())
    json.encodeToStream(ThreeGeometryPayload.serializer(), payload, out)
    return out.bytes()
}

/** Grows by a quarter, not by doubling: an underestimate of a large message must not allocate twice it. */
private class WireBytes(size: Int) : ByteArrayOutputStream(size) {
    private fun room(extra: Int) {
        val needed = count.toLong() + extra
        if (needed <= buf.size) return
        check(needed < Int.MAX_VALUE - 64) { "a Three.js message cannot exceed 2 GB" }
        buf = buf.copyOf(maxOf(needed, minOf(Int.MAX_VALUE - 64L, buf.size + buf.size / 4L + 65_536)).toInt())
    }
    override fun write(b: Int) { room(1); super.write(b) }
    override fun write(b: ByteArray, off: Int, len: Int) { room(len); super.write(b, off, len) }
    fun bytes(): ByteArray = if (count == buf.size) buf else buf.copyOf(count)
}

/**
 * A change to a view's part styles, sent instead of the geometry.
 *
 * `replace` false merges [parts] over the styles the viewer has; true makes them all of them.
 * [beforeGeometry] marks the replay to a newly attached viewer: these are the styles for the geometry
 * message that follows, which was encoded before they changed. Sending them first lets the viewer draw
 * the styled scene once, so a capture never sees the stale one in between.
 */
@Serializable
internal data class ThreeAppearanceMessage(
    val uid: String,
    val revision: String?,
    val replace: Boolean,
    val parts: Map<String, ThreePartAppearance>,
    val beforeGeometry: Boolean = false,
    val type: String = "three/appearance",
)
