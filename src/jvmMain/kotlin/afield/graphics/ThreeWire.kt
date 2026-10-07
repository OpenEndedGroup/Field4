package afield.graphics

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.Locale

/*
 * What a Three.js view puts on the websocket, and what it refuses to.
 *
 * 2026-10-07 00:02, on a private house host: an AI tool styled 224 cut panels in one fabrication view.
 * `styleParts` ended in `update`, which encoded the whole payload again — every vertex of every panel as
 * decimal JSON text, on the runloop — in order to change some colours. With the old generation 99.9%
 * full, the encoder's buffer growth thrashed for twenty minutes and every document on the host froze
 * with it. Three changes, each held by `ThreeGeometryWireTest`:
 *
 *  * a style change is a `three/appearance` message, part id -> ThreePartAppearance, and the cached
 *    geometry encoding stays valid (`ThreeGeometryViewState.styleParts`);
 *  * drawable buffers travel as base64 little-endian Float32 / integer arrays rather than as decimal
 *    text: about half the bytes, and no float formatting at all ([packedForWire]). So does a solid's
 *    exact topology (`inspector.parts`), as Float64 — doubles are where decimal text is longest;
 *  * a message over the view's limit fails before it is encoded, naming its size and the drawables
 *    that make it ([ThreePayloadTooLargeException]).
 *
 * The FLine half of a payload (`lines`, `fills`, `points`, `interactive`, `scene`) keeps plain arrays.
 * The native visionOS viewer (`FLineWire.swift`) decodes those as `[Float]` and ignores `drawables`, so
 * packing only the drawables changes nothing for it. The viewer side is the `wire-payload` region of
 * `threejs_viewer.html`.
 */

/**
 * The default ceiling on one geometry message: `AFIELD_THREE_PAYLOAD_LIMIT_MB`, or 256 MB.
 *
 * V8 cannot hold a string much past 512M characters, so a bigger message could never reach the viewer
 * at all; 256 MB leaves room for `JSON.parse` and the typed arrays it becomes. A 3M-triangle mesh with
 * normals and colours packs to about 130 MB.
 */
internal val defaultThreePayloadLimitBytes: Long =
    (System.getenv("AFIELD_THREE_PAYLOAD_LIMIT_MB")?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: 256L) * 1024 * 1024

/**
 * A Three.js geometry message too large to send.
 *
 * Thrown by [ThreeGeometryViewState.update] — before encoding when the estimate is over, or after when
 * the encoded message is. Nothing is sent either way, and the view keeps what it was showing.
 */
class ThreePayloadTooLargeException internal constructor(
    val uid: String,
    /** The message's size in bytes, estimated before encoding or measured after. */
    val bytes: Long,
    val limitBytes: Long,
    /** The biggest contributors, largest first, as (what, estimated bytes). */
    val largest: List<Pair<String, Long>>,
    message: String,
) : IllegalStateException(message)

/** A part of a payload and about how many bytes of the message it accounts for. */
internal data class ThreeWireCost(val what: String, val bytes: Long)

/** `-12.345678,`: `Float.toString` of a typical coordinate, and its comma. */
private const val PLAIN_FLOAT_BYTES = 11L
private const val PLAIN_INT_BYTES = 7L

/** Bytes per element for the integer index types the viewer's `typedArrayCtor` knows. Others stay plain. */
private val PACKED_INDEX_WIDTH = mapOf(
    "Uint8Array" to 1, "Int8Array" to 1,
    "Uint16Array" to 2, "Int16Array" to 2,
    "Uint32Array" to 4, "Int32Array" to 4,
)

private val NO_FLOATS = FloatArray(0)
private val NO_INTS = IntArray(0)

private fun base64Length(rawBytes: Long): Long = (rawBytes + 2) / 3 * 4

private fun drawableName(drawable: ThreeDrawable, ordinal: Int): String =
    (if (drawable.id.isNotBlank()) "drawable '${drawable.id}'" else "drawable #$ordinal") +
        (drawable.label?.takeIf { it.isNotBlank() && it != drawable.id }?.let { " ($it)" } ?: "")

private fun plainBytes(data: ThreeGeometryData?): Long = if (data == null) 0L else
    data.attributes.values.sumOf { it.array.size * PLAIN_FLOAT_BYTES } + (data.index?.array?.size ?: 0) * PLAIN_INT_BYTES

private fun packedBytes(data: ThreeGeometryData): Long =
    data.attributes.values.sumOf { base64Length(it.array.size * 4L) } + (data.index?.let { index ->
        PACKED_INDEX_WIDTH[index.type]?.let { base64Length(index.array.size.toLong() * it) }
            ?: (index.array.size * PLAIN_INT_BYTES)
    } ?: 0L)

/**
 * Where a payload's bytes go, from array lengths alone — nothing is formatted or encoded.
 *
 * Exact for packed drawable buffers, an estimate for the plain FLine arrays (decimal text has no fixed
 * width). Small fields — bounds, styles, camera — are left out; they are not what makes a message large.
 */
internal fun threeWireCosts(payload: ThreeGeometryPayload): List<ThreeWireCost> = buildList {
    listOf("FLine lines" to payload.lines, "FLine fills" to payload.fills, "FLine points" to payload.points)
        .forEach { (what, data) -> plainBytes(data).takeIf { it > 0 }?.let { add(ThreeWireCost(what, it)) } }
    payload.interactive.forEach { obj ->
        add(ThreeWireCost(
            "interactive FLine ${obj.lineIndex}" + (obj.label?.let { " ($it)" } ?: ""),
            plainBytes(obj.lines) + plainBytes(obj.fills) + plainBytes(obj.points) + obj.vertices.size * 48L + obj.topology.length,
        ))
    }
    payload.scene?.let { scene ->
        add(ThreeWireCost("editable scene", scene.lines.sumOf { it.nodes.size * 96L + it.topology.length }))
    }
    payload.drawables.forEachIndexed { ordinal, drawable ->
        add(ThreeWireCost(drawableName(drawable, ordinal), packedBytes(drawable.geometry) + drawable.topology.length))
    }
    // A solid's exact boundary, beside its display mesh: doubles, so per vertex as large again.
    payload.inspector?.parts?.forEach { (id, part) ->
        add(ThreeWireCost("solid topology '$id'", base64Length(part.positions.size * 8L) +
            base64Length((part.indices.size + part.faces.size) * 4L) + base64Length(part.edges.size * 8L)))
    }
}

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
    val message = "Three.js view '$uid': the geometry message is ${megabytes(bytes)} " +
        (if (measured) "(measured after encoding)" else "(estimated before encoding)") +
        ", over the ${megabytes(limitBytes)} limit. Nothing was sent and the view keeps its previous geometry. " +
        "Largest: $listing" + (if (drawables > 0) " ($drawables drawables in all)" else "") + ". " +
        "Send coarser meshes, split the parts across views, or raise AFIELD_THREE_PAYLOAD_LIMIT_MB."
    return ThreePayloadTooLargeException(uid, bytes, limitBytes, largest.map { it.what to it.bytes }, message)
}

/**
 * The payload as the viewer receives it: every drawable buffer packed, everything else as declared.
 *
 * A non-finite value fails here, naming the drawable and attribute. It always failed — kotlinx refuses
 * to write NaN as JSON — but as a serializer exception that said nothing about where it was.
 */
internal fun ThreeGeometryPayload.packedForWire(): ThreeGeometryPayload {
    val parts = inspector?.parts.orEmpty()
    if (drawables.isEmpty() && parts.isEmpty()) return this
    return copy(
        drawables = drawables.mapIndexed { ordinal, drawable ->
            val owner = drawableName(drawable, ordinal)
            drawable.copy(geometry = ThreeGeometryData(
                attributes = drawable.geometry.attributes.mapValues { (name, attribute) -> attribute.packed(owner, name) },
                index = drawable.geometry.index?.packed(),
            ))
        },
        inspector = if (parts.isEmpty()) inspector else inspector!!.copy(parts = parts.mapValues { (id, part) -> part.packed(id) }),
    )
}

private fun base64Of(bytes: ByteBuffer): String = Base64.getEncoder().encodeToString(bytes.array())

private fun littleEndian(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

/** Exact: Float64 bits, which the viewer turns back into the same doubles `JSON.parse` would give it. */
private fun ThreePartTopology.packed(id: String): ThreePartTopology {
    if (base64 != null) return this
    for (i in positions.indices) require(positions[i].isFinite()) {
        "solid topology '$id' holds ${positions[i]} at index $i, which the viewer cannot use"
    }
    val packed = linkedMapOf(
        "positions" to base64Of(littleEndian(positions.size * 8).also { it.asDoubleBuffer().put(positions) }),
        "indices" to base64Of(littleEndian(indices.size * 4).also { it.asIntBuffer().put(indices) }),
        "faces" to base64Of(littleEndian(faces.size * 4).also { it.asIntBuffer().put(faces) }),
    )
    // Pairs are what `solidPartTopology` writes; anything else keeps its own shape.
    val pairs = edges.all { it.size == 2 }
    if (pairs) packed["edges"] = base64Of(littleEndian(edges.size * 8).also { buffer ->
        val ints = buffer.asIntBuffer()
        for (edge in edges) { ints.put(edge[0]); ints.put(edge[1]) }
    })
    return copy(positions = DoubleArray(0), indices = NO_INTS, faces = NO_INTS,
        edges = if (pairs) emptyList() else edges, base64 = packed)
}

private fun ThreeAttribute.packed(owner: String, name: String): ThreeAttribute {
    if (base64 != null) return this
    val values = array
    for (i in values.indices) require(values[i].isFinite()) {
        "$owner: attribute '$name' holds ${values[i]} at index $i, which the viewer cannot draw"
    }
    val bytes = littleEndian(values.size * 4).also { it.asFloatBuffer().put(values) }
    return copy(array = NO_FLOATS, base64 = base64Of(bytes))
}

private fun ThreeIndex.packed(): ThreeIndex {
    if (base64 != null) return this
    val width = PACKED_INDEX_WIDTH[type] ?: return this
    val bytes = littleEndian(array.size * width)
    // Narrowing keeps the low bits, which is what `new Uint16Array([...])` does to the same numbers.
    when (width) {
        1 -> for (value in array) bytes.put(value.toByte())
        2 -> bytes.asShortBuffer().let { shorts -> for (value in array) shorts.put(value.toShort()) }
        else -> bytes.asIntBuffer().put(array)
    }
    return copy(array = NO_INTS, base64 = base64Of(bytes))
}

/**
 * Encode straight to UTF-8 bytes, in a buffer sized from the estimate.
 *
 * Bytes rather than a String: a String goes through a UTF-16 `CharArray` that doubles as it grows —
 * the `ensureTotalCapacity` frame in the 2026-10-07 thread dump — and Ktor's `Frame.Text(String)` then
 * makes a UTF-8 copy for every session. These bytes are the frame, shared by every session they go to.
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun encodeThreeWire(json: Json, payload: ThreeGeometryPayload, sizeHint: Long): ByteArray {
    // The estimate leaves out keys and small fields, so allow a little over it.
    val out = WireBytes((sizeHint + sizeHint / 32 + 65_536).coerceIn(4096L, Int.MAX_VALUE - 64L).toInt())
    json.encodeToStream(ThreeGeometryPayload.serializer(), payload, out)
    return out.bytes()
}

/** Grows by a quarter, not by doubling: an underestimate of a 200 MB message must not allocate 400. */
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
