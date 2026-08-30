package drowning

import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** The fixed part of a maxnet `.mxg` stream header. */
data class MxgHeader(
    val format: String,
    val version: Int,
    val createdUnix: Double,
    val sourcePath: String,
    val width: Int,
    val height: Int,
    val fps: Double,
    val sourceFrameCount: Int,
    val quantizationLevels: Int,
    val medianRadius: Int,
    val minArea: Int,
    val minDynamics: Int,
    val contourEpsilon: Double,
    val stride: Int,
    val trackingEnabled: Boolean
)

/** Maximum decoded records needed by a fixed pair of streaming Vulkan buffers. */
data class MxgCapacity(val nodes: Int, val segments: Int) {
    val nodeBytes: Long get() = nodes.toLong() * MxgFrame.NODE_BYTES
    val segmentBytes: Long get() = segments.toLong() * MxgFrame.SEGMENT_BYTES
}

data class MxgProfile(
    val frames: Int,
    val capacity: MxgCapacity,
    val totalNodes: Long,
    val totalContours: Long,
    val totalSegments: Long
)

/**
 * One decoded maxnet frame, already in the two fixed-stride little-endian layouts a pulling shader
 * wants. There are no node, contour or point objects in the hot path.
 *
 * Node records are six `vec4`s (96 bytes):
 *
 * ```text
 * 0 centroid.xy, flatCentroid.xy
 * 1 area, flatArea, level, dynamics
 * 2 bbox.xyxy
 * 3 velocity.xy, areaDelta, levelDelta       (NaN means absent)
 * 4 parentId, sourceNodeId, depth, treeDepth (uvec4 through floatBitsToUint)
 * 5 trackId, matchedNodeId, maximumDynamics, flags
 * ```
 *
 * Segment records are two `vec4`s (32 bytes): `(x0,y0,x1,y1)`, followed by a `uvec4` containing
 * `(nodeId, contourId, parentContourId, flags)`. Every contour is closed here, once, so the shader
 * only expands segment `gl_VertexIndex / 6` into a quad.
 */
class MxgFrame internal constructor(
    val width: Int,
    val height: Int,
    val quantizationLevels: Int,
    val rawNodeCount: Int,
    val nodeCount: Int,
    val contourCount: Int,
    val segmentCount: Int,
    val frameIndex: Int,
    val streamIndex: Int,
    val timestampSeconds: Double,
    private val nodeRecords: ByteBuffer,
    private val segmentRecords: ByteBuffer
) {
    fun nodeBytes(): ByteBuffer = nodeRecords.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
    fun segmentBytes(): ByteBuffer = segmentRecords.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)

    fun nodeCentroidX(node: Int): Float = nodeRecords.getFloat(nodeOffset(node))
    fun nodeCentroidY(node: Int): Float = nodeRecords.getFloat(nodeOffset(node) + 4)
    fun nodeParent(node: Int): Int = nodeRecords.getInt(nodeOffset(node) + 64)
    fun nodeTrack(node: Int): Int = nodeRecords.getInt(nodeOffset(node) + 80)
    fun nodeLevel(node: Int): Float = nodeRecords.getFloat(nodeOffset(node) + 24)

    fun segmentX0(segment: Int): Float = segmentRecords.getFloat(segmentOffset(segment))
    fun segmentY0(segment: Int): Float = segmentRecords.getFloat(segmentOffset(segment) + 4)
    fun segmentX1(segment: Int): Float = segmentRecords.getFloat(segmentOffset(segment) + 8)
    fun segmentY1(segment: Int): Float = segmentRecords.getFloat(segmentOffset(segment) + 12)
    fun segmentNode(segment: Int): Int = segmentRecords.getInt(segmentOffset(segment) + 16)

    private fun nodeOffset(node: Int): Int {
        require(node in 0 until nodeCount) { "node $node outside 0 until $nodeCount" }
        return node * NODE_BYTES
    }

    private fun segmentOffset(segment: Int): Int {
        require(segment in 0 until segmentCount) { "segment $segment outside 0 until $segmentCount" }
        return segment * SEGMENT_BYTES
    }

    override fun toString(): String =
        "MxgFrame(frame=$frameIndex stream=$streamIndex t=${"%.3f".format(timestampSeconds)} " +
            "nodes=$nodeCount/$rawNodeCount contours=$contourCount segments=$segmentCount ${width}x$height)"

    companion object {
        const val NODE_BYTES = 96
        const val NODE_VEC4S = 6
        const val SEGMENT_BYTES = 32
        const val SEGMENT_VEC4S = 2
        const val INVALID = -1

        const val NODE_MAXIMUM = 1
        const val NODE_HAS_VELOCITY = 2
        const val NODE_HAS_AREA_DELTA = 4
        const val NODE_HAS_LEVEL_DELTA = 8

        const val CONTOUR_HOLE = 1
    }
}

/**
 * Indexed reader for appendable maxnet `.mxg` streams.
 *
 * [refresh] scans only 16-byte record headers and never touches compressed payloads. A partial final
 * record is ignored until the next refresh, exactly like maxnet's Python reader; completed records can
 * then be read randomly. [readFrame] independently inflates, length-checks and CRC-checks one record,
 * and parses MessagePack directly into [MxgFrame]'s GPU records.
 */
class MxgFile private constructor(
    val path: Path,
    private val channel: FileChannel,
    val header: MxgHeader,
    private var scanAt: Long
) : AutoCloseable {
    private data class Record(val payloadAt: Long, val compressedBytes: Int, val rawBytes: Int, val checksum: Long)

    private val records = ArrayList<Record>()

    @Volatile
    var ended: Boolean = false
        private set

    val frameCount: Int get() = synchronized(records) { records.size }

    init {
        refresh()
    }

    /** Add every newly-complete record. Returns the number added. */
    @Synchronized
    fun refresh(): Int {
        if (ended) return 0
        val started = records.size
        while (true) {
            val fileBytes = channel.size()
            if (scanAt + RECORD_HEADER_BYTES > fileBytes) break
            val h = readAt(scanAt, RECORD_HEADER_BYTES) ?: break
            val tag = ByteArray(4).also { h.get(it) }.decodeToString()
            val compressed = h.int
            val raw = h.int
            val crc = h.int.toLong() and 0xffffffffL
            when (tag) {
                END_TAG -> {
                    require(compressed == 0 && raw == 0) { "$path: malformed END1 record at $scanAt" }
                    ended = true
                    scanAt += RECORD_HEADER_BYTES
                    break
                }
                FRAME_TAG -> {
                    require(compressed >= 0 && raw >= 0) { "$path: negative frame length at $scanAt" }
                    require(raw <= MAX_RAW_FRAME_BYTES) {
                        "$path: frame at $scanAt claims $raw uncompressed bytes (limit $MAX_RAW_FRAME_BYTES)"
                    }
                    val payloadAt = scanAt + RECORD_HEADER_BYTES
                    val next = payloadAt + compressed
                    if (next > fileBytes) break
                    records += Record(payloadAt, compressed, raw, crc)
                    scanAt = next
                }
                else -> error("$path: unknown record tag '$tag' at $scanAt")
            }
        }
        return records.size - started
    }

    fun readFrame(index: Int): MxgFrame {
        val record = synchronized(records) {
            require(index in records.indices) { "frame record $index outside 0 until ${records.size}" }
            records[index]
        }
        val compressed = readAt(record.payloadAt, record.compressedBytes)
            ?: throw EOFException("$path: frame $index was truncated after it was indexed")
        val packed = ByteArray(record.compressedBytes)
        compressed.get(packed)
        val raw = inflate(packed, record.rawBytes, index)
        val crc = CRC32().also { it.update(raw) }.value
        require(crc == record.checksum) {
            "$path: frame $index CRC mismatch (0x${crc.toString(16)} != 0x${record.checksum.toString(16)})"
        }
        return parseFrame(raw, path.toString(), index)
    }

    /** Decode every current frame once to obtain exact fixed-buffer capacities. */
    fun profile(): MxgProfile {
        var maxNodes = 0
        var maxSegments = 0
        var nodes = 0L
        var contours = 0L
        var segments = 0L
        val count = frameCount
        for (i in 0 until count) {
            val frame = readFrame(i)
            maxNodes = maxOf(maxNodes, frame.nodeCount)
            maxSegments = maxOf(maxSegments, frame.segmentCount)
            nodes += frame.nodeCount
            contours += frame.contourCount
            segments += frame.segmentCount
        }
        return MxgProfile(count, MxgCapacity(maxNodes, maxSegments), nodes, contours, segments)
    }

    private fun readAt(at: Long, bytes: Int): ByteBuffer? {
        if (at + bytes > channel.size()) return null
        val out = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var position = at
        while (out.hasRemaining()) {
            val n = channel.read(out, position)
            if (n < 0) return null
            if (n == 0) continue
            position += n
        }
        out.flip()
        return out
    }

    private fun inflate(compressed: ByteArray, expected: Int, record: Int): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(compressed)
            val out = ByteArray(expected)
            var at = 0
            while (!inflater.finished() && at < out.size) {
                val n = inflater.inflate(out, at, out.size - at)
                if (n == 0 && inflater.needsInput()) break
                if (n == 0 && inflater.needsDictionary()) error("$path: frame $record needs a zlib dictionary")
                at += n
            }
            require(inflater.finished() && at == expected) {
                "$path: frame $record inflated to $at bytes, expected $expected"
            }
            out
        } catch (e: DataFormatException) {
            throw IllegalArgumentException("$path: frame $record has invalid zlib data", e)
        } finally {
            inflater.end()
        }
    }

    override fun close() = channel.close()

    override fun toString(): String =
        "MxgFile($path, ${header.width}x${header.height} @ ${header.fps}fps, $frameCount complete frames" +
            if (ended) ", closed)" else ", growing)"

    companion object {
        private const val FILE_MAGIC = "MXGRAPH\u0001"
        private const val FRAME_TAG = "FRM1"
        private const val END_TAG = "END1"
        private const val RECORD_HEADER_BYTES = 16
        private const val MAX_HEADER_BYTES = 16 * 1024 * 1024
        private const val MAX_RAW_FRAME_BYTES = 512 * 1024 * 1024

        fun open(path: String): MxgFile = open(Path.of(path))

        fun open(path: Path): MxgFile {
            val channel = FileChannel.open(path, StandardOpenOption.READ)
            try {
                val prefix = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                var at = 0L
                while (prefix.hasRemaining()) {
                    val n = channel.read(prefix, at)
                    if (n < 0) throw EOFException("$path: incomplete maxnet header")
                    at += n
                }
                prefix.flip()
                val magic = ByteArray(8).also { prefix.get(it) }.decodeToString()
                require(magic == FILE_MAGIC) { "$path: not a maxnet graph stream" }
                val headerBytes = prefix.int
                require(headerBytes in 1..MAX_HEADER_BYTES) { "$path: invalid header length $headerBytes" }
                val packedHeader = ByteBuffer.allocate(headerBytes)
                at = 12L
                while (packedHeader.hasRemaining()) {
                    val n = channel.read(packedHeader, at)
                    if (n < 0) throw EOFException("$path: incomplete MessagePack header")
                    at += n
                }
                val header = parseHeader(packedHeader.array(), path.toString())
                require(header.format == "maxnet-frame-stream") { "$path: unknown format '${header.format}'" }
                require(header.version == 1) { "$path: unsupported maxnet version ${header.version}" }
                return MxgFile(path, channel, header, 12L + headerBytes)
            } catch (t: Throwable) {
                channel.close()
                throw t
            }
        }
    }
}

private class GrowingLittleEndian(initialBytes: Int = 4096) {
    private var bytes = ByteBuffer.allocate(maxOf(64, initialBytes)).order(ByteOrder.LITTLE_ENDIAN)
    val size: Int get() = bytes.position()

    private fun need(extra: Int) {
        if (bytes.remaining() >= extra) return
        var capacity = bytes.capacity()
        while (capacity - bytes.position() < extra) capacity = Math.multiplyExact(capacity, 2)
        val next = ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN)
        bytes.flip()
        next.put(bytes)
        bytes = next
    }

    fun segment(x0: Int, y0: Int, x1: Int, y1: Int, node: Int, contour: Int, parent: Int, flags: Int) {
        need(MxgFrame.SEGMENT_BYTES)
        bytes.putFloat(x0.toFloat()).putFloat(y0.toFloat()).putFloat(x1.toFloat()).putFloat(y1.toFloat())
        bytes.putInt(node).putInt(contour).putInt(parent).putInt(flags)
    }

    fun finish(): ByteBuffer {
        bytes.flip()
        return bytes.slice().order(ByteOrder.LITTLE_ENDIAN).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
    }
}

private fun parseHeader(raw: ByteArray, source: String): MxgHeader {
    var format = ""
    var version = -1
    var created = Double.NaN
    var sourcePath = ""
    var width = 0
    var height = 0
    var fps = 0.0
    var sourceFrames = 0
    var levels = 0
    var median = 0
    var minArea = 0
    var minDynamics = 0
    var epsilon = 0.0
    var stride = 1
    var tracking = false

    MessagePack.newDefaultUnpacker(raw).use { u ->
        repeat(u.unpackMapHeader()) {
            when (val key = u.unpackString()) {
                "format" -> format = u.unpackString()
                "version" -> version = u.unpackInt()
                "created_unix" -> created = u.unpackDouble()
                "stride" -> stride = u.unpackInt()
                "source" -> repeat(u.unpackMapHeader()) {
                    when (u.unpackString()) {
                        "path" -> sourcePath = u.unpackString()
                        "width" -> width = u.unpackInt()
                        "height" -> height = u.unpackInt()
                        "fps" -> fps = u.unpackDouble()
                        "frame_count" -> sourceFrames = u.unpackInt()
                        else -> u.skipValue()
                    }
                }
                "extraction" -> repeat(u.unpackMapHeader()) {
                    when (u.unpackString()) {
                        "quantization_levels" -> levels = u.unpackInt()
                        "median_radius" -> median = u.unpackInt()
                        "min_area" -> minArea = u.unpackInt()
                        "min_dynamics" -> minDynamics = u.unpackInt()
                        "contour_epsilon" -> epsilon = u.unpackDouble()
                        else -> u.skipValue()
                    }
                }
                "tracking" -> repeat(u.unpackMapHeader()) {
                    when (u.unpackString()) {
                        "enabled" -> tracking = u.unpackBoolean()
                        else -> u.skipValue()
                    }
                }
                else -> u.skipValue()
            }
        }
        require(!u.hasNext()) { "$source: extra MessagePack values after header" }
    }
    return MxgHeader(
        format, version, created, sourcePath, width, height, fps, sourceFrames, levels, median,
        minArea, minDynamics, epsilon, stride, tracking
    )
}

private fun parseFrame(raw: ByteArray, source: String, recordIndex: Int): MxgFrame {
    var width = 0
    var height = 0
    var levels = 0
    var rawNodes = 0
    var declaredNodes = -1
    var parsedNodes = 0
    var contours = 0
    var frameIndex = -1
    var streamIndex = -1
    var timestamp = Double.NaN
    var nodeRecords: ByteBuffer? = null
    val segments = GrowingLittleEndian(maxOf(4096, minOf(raw.size * 2, 4 * 1024 * 1024)))

    MessagePack.newDefaultUnpacker(raw).use { u ->
        repeat(u.unpackMapHeader()) {
            when (u.unpackString()) {
                "width" -> width = u.unpackInt()
                "height" -> height = u.unpackInt()
                "quantization_levels" -> levels = u.unpackInt()
                "raw_node_count" -> rawNodes = u.unpackInt()
                "node_count" -> {
                    declaredNodes = u.unpackInt()
                    nodeRecords = ByteBuffer.allocate(Math.multiplyExact(declaredNodes, MxgFrame.NODE_BYTES))
                        .order(ByteOrder.LITTLE_ENDIAN)
                }
                "nodes" -> {
                    val count = u.unpackArrayHeader()
                    require(declaredNodes >= 0 && count == declaredNodes) {
                        "$source: record $recordIndex nodes array has $count entries, node_count is $declaredNodes"
                    }
                    val nodes = requireNotNull(nodeRecords)
                    repeat(count) { nodeIndex ->
                        contours += unpackNode(u, nodes, segments, nodeIndex, contours, source, recordIndex)
                        parsedNodes++
                    }
                }
                "frame_index" -> frameIndex = u.unpackInt()
                "stream_index" -> streamIndex = u.unpackInt()
                "timestamp_seconds" -> timestamp = u.unpackDouble()
                else -> u.skipValue()
            }
        }
        require(!u.hasNext()) { "$source: extra MessagePack values after record $recordIndex" }
    }

    require(width > 0 && height > 0 && levels > 0) { "$source: record $recordIndex has invalid dimensions/levels" }
    require(parsedNodes == declaredNodes) { "$source: record $recordIndex parsed $parsedNodes/$declaredNodes nodes" }
    val nodes = requireNotNull(nodeRecords).also { it.flip() }.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
    val segmentRecords = segments.finish()
    require(segmentRecords.remaining() % MxgFrame.SEGMENT_BYTES == 0)
    return MxgFrame(
        width, height, levels, rawNodes, declaredNodes, contours,
        segmentRecords.remaining() / MxgFrame.SEGMENT_BYTES,
        frameIndex, streamIndex, timestamp, nodes, segmentRecords
    )
}

private fun unpackNode(
    u: MessageUnpacker,
    out: ByteBuffer,
    segments: GrowingLittleEndian,
    nodeIndex: Int,
    contourBase: Int,
    source: String,
    recordIndex: Int
): Int {
    var id = -1
    var parent = MxgFrame.INVALID
    var sourceId = MxgFrame.INVALID
    var level = 0
    var depth = 0
    var treeDepth = 0
    var area = 0
    var flatArea = 0
    var cx = 0f; var cy = 0f
    var fcx = 0f; var fcy = 0f
    var x0 = 0; var y0 = 0; var x1 = 0; var y1 = 0
    var dynamics = 0
    var maximum = false
    var maximumDynamics = MxgFrame.INVALID
    var track = MxgFrame.INVALID
    var matched = MxgFrame.INVALID
    var vx = Float.NaN; var vy = Float.NaN
    var areaDelta = Float.NaN
    var levelDelta = Float.NaN
    var contourCount = 0

    repeat(u.unpackMapHeader()) {
        when (u.unpackString()) {
            "id" -> id = u.unpackInt()
            "parent_id" -> parent = u.unpackInt()
            "source_node_id" -> sourceId = u.unpackInt()
            "level" -> level = u.unpackInt()
            "depth" -> depth = u.unpackInt()
            "tree_depth" -> treeDepth = u.unpackInt()
            "area" -> area = u.unpackInt()
            "flat_area" -> flatArea = u.unpackInt()
            "centroid" -> { val v = unpackPair(u); cx = v[0]; cy = v[1] }
            "flat_centroid" -> { val v = unpackPair(u); fcx = v[0]; fcy = v[1] }
            "bbox" -> {
                require(u.unpackArrayHeader() == 4)
                x0 = u.unpackInt(); y0 = u.unpackInt(); x1 = u.unpackInt(); y1 = u.unpackInt()
            }
            "dynamics" -> dynamics = u.unpackInt()
            "is_maximum" -> maximum = u.unpackBoolean()
            "maximum_dynamics" -> maximumDynamics = unpackNullableInt(u)
            "track_id" -> track = unpackNullableInt(u)
            "matched_from_node_id" -> matched = unpackNullableInt(u)
            "velocity" -> {
                if (!u.tryUnpackNil()) {
                    val v = unpackPair(u); vx = v[0]; vy = v[1]
                }
            }
            "area_delta" -> areaDelta = unpackNullableFloat(u)
            "level_delta" -> levelDelta = unpackNullableFloat(u)
            "contours" -> {
                contourCount = u.unpackArrayHeader()
                repeat(contourCount) { localContour ->
                    unpackContour(
                        u, segments, nodeIndex, contourBase + localContour, contourBase,
                        source, recordIndex
                    )
                }
            }
            else -> u.skipValue()
        }
    }

    require(id == nodeIndex) {
        "$source: record $recordIndex node array index $nodeIndex has id $id; GPU topology assumes dense ids"
    }
    require(parent == MxgFrame.INVALID || parent in 0 until nodeIndex) {
        "$source: record $recordIndex node $id has invalid parent $parent"
    }
    var flags = 0
    if (maximum) flags = flags or MxgFrame.NODE_MAXIMUM
    if (!vx.isNaN()) flags = flags or MxgFrame.NODE_HAS_VELOCITY
    if (!areaDelta.isNaN()) flags = flags or MxgFrame.NODE_HAS_AREA_DELTA
    if (!levelDelta.isNaN()) flags = flags or MxgFrame.NODE_HAS_LEVEL_DELTA

    out.putFloat(cx).putFloat(cy).putFloat(fcx).putFloat(fcy)
    out.putFloat(area.toFloat()).putFloat(flatArea.toFloat()).putFloat(level.toFloat()).putFloat(dynamics.toFloat())
    out.putFloat(x0.toFloat()).putFloat(y0.toFloat()).putFloat(x1.toFloat()).putFloat(y1.toFloat())
    out.putFloat(vx).putFloat(vy).putFloat(areaDelta).putFloat(levelDelta)
    out.putInt(parent).putInt(sourceId).putInt(depth).putInt(treeDepth)
    out.putInt(track).putInt(matched).putInt(maximumDynamics).putInt(flags)
    return contourCount
}

private fun unpackContour(
    u: MessageUnpacker,
    out: GrowingLittleEndian,
    node: Int,
    contour: Int,
    contourBase: Int,
    source: String,
    recordIndex: Int
) {
    var parentLocal = MxgFrame.INVALID
    var hole = false
    var pointCount = -1
    var encoded: ByteArray? = null
    repeat(u.unpackMapHeader()) {
        when (u.unpackString()) {
            "parent_contour" -> parentLocal = u.unpackInt()
            "is_hole" -> hole = u.unpackBoolean()
            "point_count" -> pointCount = u.unpackInt()
            "points_delta" -> encoded = ByteArray(u.unpackBinaryHeader()).also(u::readPayload)
            else -> u.skipValue()
        }
    }
    require(pointCount >= 0 && encoded != null) { "$source: record $recordIndex contour $contour is incomplete" }
    val data = requireNotNull(encoded)
    require(parentLocal == MxgFrame.INVALID || parentLocal in 0 until contour - contourBase) {
        "$source: record $recordIndex contour $contour has invalid local parent $parentLocal"
    }
    if (pointCount < 2) {
        decodeContour(data, pointCount, source, recordIndex, contour) { _, _, _, _ -> }
        return
    }
    val parent = if (parentLocal < 0) MxgFrame.INVALID else contourBase + parentLocal
    val flags = if (hole) MxgFrame.CONTOUR_HOLE else 0
    decodeContour(data, pointCount, source, recordIndex, contour) { ax, ay, bx, by ->
        out.segment(ax, ay, bx, by, node, contour, parent, flags)
    }
}

private inline fun decodeContour(
    encoded: ByteArray,
    count: Int,
    source: String,
    recordIndex: Int,
    contour: Int,
    emit: (Int, Int, Int, Int) -> Unit
) {
    var at = 0
    var x = 0
    var y = 0
    var firstX = 0
    var firstY = 0
    var previousX = 0
    var previousY = 0
    repeat(count) { point ->
        val dx = readVarUInt(encoded, at, source, recordIndex, contour).also { at = it.second }.first
        val dy = readVarUInt(encoded, at, source, recordIndex, contour).also { at = it.second }.first
        x += ((dx ushr 1) xor -(dx and 1L)).toInt()
        y += ((dy ushr 1) xor -(dy and 1L)).toInt()
        if (point == 0) {
            firstX = x; firstY = y
        } else {
            emit(previousX, previousY, x, y)
        }
        previousX = x; previousY = y
    }
    if (count >= 2) emit(previousX, previousY, firstX, firstY)
    require(at == encoded.size) {
        "$source: record $recordIndex contour $contour has ${encoded.size - at} extra delta bytes"
    }
}

private fun readVarUInt(data: ByteArray, start: Int, source: String, record: Int, contour: Int): Pair<Long, Int> {
    var at = start
    var shift = 0
    var value = 0L
    while (true) {
        require(at < data.size) { "$source: record $record contour $contour has a truncated varint" }
        val byte = data[at++].toInt() and 0xff
        value = value or ((byte and 0x7f).toLong() shl shift)
        if ((byte and 0x80) == 0) return value to at
        shift += 7
        require(shift <= 63) { "$source: record $record contour $contour has an invalid varint" }
    }
}

private fun unpackPair(u: MessageUnpacker): FloatArray {
    require(u.unpackArrayHeader() == 2)
    return floatArrayOf(u.unpackDouble().toFloat(), u.unpackDouble().toFloat())
}

private fun unpackNullableInt(u: MessageUnpacker): Int = if (u.tryUnpackNil()) MxgFrame.INVALID else u.unpackInt()
private fun unpackNullableFloat(u: MessageUnpacker): Float =
    if (u.tryUnpackNil()) Float.NaN
    else when (u.nextFormat.valueType) {
        org.msgpack.value.ValueType.INTEGER -> u.unpackLong().toFloat()
        org.msgpack.value.ValueType.FLOAT -> u.unpackDouble().toFloat()
        else -> error("expected a numeric MessagePack value, got ${u.nextFormat.valueType}")
    }
