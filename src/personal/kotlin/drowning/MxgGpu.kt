package drowning

import afield.four.buffers.BufferWriteable
import afield.four.device.CommandBuffer

/** `defaultBuffer { size = frame.nodeVec4s.vec4s }`. */
val MxgFrame.nodeVec4s: Int get() = nodeCount * MxgFrame.NODE_VEC4S

/** `defaultBuffer { size = frame.segmentVec4s.vec4s }`. */
val MxgFrame.segmentVec4s: Int get() = segmentCount * MxgFrame.SEGMENT_VEC4S

val MxgFrame.gpuBytes: Long get() = nodeCount.toLong() * MxgFrame.NODE_BYTES + segmentCount.toLong() * MxgFrame.SEGMENT_BYTES

val MxgCapacity.nodeVec4s: Int get() = Math.multiplyExact(nodes, MxgFrame.NODE_VEC4S)
val MxgCapacity.segmentVec4s: Int get() = Math.multiplyExact(segments, MxgFrame.SEGMENT_VEC4S)
val MxgCapacity.gpuBytes: Long get() = nodeBytes + segmentBytes

/**
 * Bulk-copy the already packed records into the two buffers owned by a Field box.
 *
 * Both targets are [BufferWriteable], so a `FrameBufferGroup`, raw `VulkanBuffer`, staging buffer,
 * or a test target all work. For playback, allocate with `perFrame = true; streamWrites()`; the
 * background [MxgSource] never touches Vulkan and the render loop performs these two bounded copies.
 */
fun MxgFrame.writeInto(nodes: BufferWriteable? = null, segments: BufferWriteable? = null, command: CommandBuffer? = null) {
    nodes?.let {
        it.mapAndWrite_bytes { put(nodeBytes()) }
        it.clean(false, command)
    }
    segments?.let {
        it.mapAndWrite_bytes { put(segmentBytes()) }
        it.clean(false, command)
    }
}

fun MxgFrame.describeGpu(): String =
    "$this -> GPU ${"%.2f".format(gpuBytes / 1e6)} MB; " +
        "$nodeCount node records, $segmentCount closed contour segments; " +
        "draw ${nodeCount * 6} node, ${(nodeCount - 1).coerceAtLeast(0) * 6} edge, ${segmentCount * 6} contour vertices"

/** Live shader knobs. Buffer content does not change when these do. */
data class MxgLook(
    var contourWidthPixels: Float = 1.15f,
    var edgeWidthPixels: Float = 0.75f,
    var nodeRadiusPixels: Float = 2.2f,
    var maximumRadiusPixels: Float = 3.4f,
    var contourAlpha: Float = 0.68f,
    var edgeAlpha: Float = 0.16f,
    var nodeAlpha: Float = 0.82f,
    var holesAlpha: Float = 0.42f,
    var dynamicsGain: Float = 0.055f,
    var levelFloor: Float = 0.08f,
    var levelCeiling: Float = 0.94f,
    var drawContours: Boolean = true,
    var drawEdges: Boolean = true,
    var drawNodes: Boolean = true
)
