package drowning

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Small persistent selection from already-presented MXG records. Never reads/decodes another file.
 * Slots retain track identity, and change generation on reuse so particles cannot attach to a
 * different track by accident. Missing tracks reserve their slots briefly; the shader fades them.
 * CPU output is 3 vec4/slot: pixel.xy, area fraction, valid; generation, track, dynamics, cell;
 * tree-root world.xyz, effective distance-from-leaf warp. The third record makes the sculpted MXG
 * position authoritative for particle attachment as well as drawing.
 */
class DrowningAnchors(val cells: Int = 15, val slotsPerCell: Int = 32, val graceFrames: Int = 8) {
    init { require(cells > 0 && slotsPerCell > 0 && graceFrames >= 0) }
    val count = cells * slotsPerCell
    val vec4s = count * 3
    private val tracks = IntArray(count) { -1 }
    private val generations = IntArray(count)
    private val missing = IntArray(count)
    private val previousFrames = IntArray(cells) { -1 }
    private val values = FloatArray(count * FLOATS_PER_SLOT)
    private val leafWeights = FloatArray(count)
    var activeCount = 0
        private set

    fun generation(slot: Int) = generations[slot]
    fun track(slot: Int) = tracks[slot]
    fun valid(slot: Int) = values[slot * FLOATS_PER_SLOT + 3] > 0f

    fun update(frames: List<MxgFrame>) = update(IntArray(cells) { it }, frames)

    fun update(cellIndices: IntArray, frames: List<MxgFrame>) = update(cellIndices, frames, null)

    fun update(cellIndices: IntArray, frames: List<MxgFrame>, treeWarp: MxgTreeWarp?) {
        require(cellIndices.size == frames.size)
        require(cellIndices.distinct().size == cellIndices.size && cellIndices.all { it in 0 until cells })
        activeCount = 0
        val supplied = cellIndices.toSet()
        for (cell in 0 until cells) if (cell !in supplied) {
            previousFrames[cell] = -1
            for (slot in cell * slotsPerCell until (cell + 1) * slotsPerCell) {
                values[slot * FLOATS_PER_SLOT + 3] = 0f
                leafWeights[slot] = 0f
            }
        }
        frames.forEachIndexed { frameIndex, frame ->
            val cell = cellIndices[frameIndex]
            val first = cell * slotsPerCell
            val last = first + slotsPerCell
            val previous = previousFrames[cell]
            // Seeking/reversing/looping is a new identity epoch, not a gigantic track velocity.
            if (previous >= 0 && frame.frameIndex < previous) {
                for (slot in first until last) {
                    tracks[slot] = -1
                    values[slot * FLOATS_PER_SLOT + 3] = 0f
                    leafWeights[slot] = 0f
                }
            }
            val step = (frame.frameIndex - previous).coerceAtLeast(1)
            previousFrames[cell] = frame.frameIndex
            val bytes = frame.nodeBytes()
            val nodes = HashMap<Int, Int>()
            val scores = HashMap<Int, Float>()
            val imageArea = frame.width.toFloat() * frame.height
            val frameLeafWeights = treeWarp?.leafDistanceWeights(frame)
            for (node in 0 until frame.nodeCount) {
                val at = node * MxgFrame.NODE_BYTES
                val track = frame.nodeTrack(node)
                val area = bytes.getFloat(at + 16)
                val dynamics = bytes.getFloat(at + 28)
                if (track < 0 || frame.nodeParent(node) < 0 || !area.isFinite() || area < 16f || area > imageArea*.85f) continue
                if (!frame.nodeCentroidX(node).isFinite() || !frame.nodeCentroidY(node).isFinite()) continue
                val score = sqrt(area) * (1f + .03f * dynamics.takeIf { it.isFinite() }?.coerceIn(0f, 100f).orZero())
                if (score > (scores[track] ?: -1f)) { scores[track] = score; nodes[track] = node }
            }
            for (slot in first until last) {
                val node = nodes.remove(tracks[slot])
                if (node != null) {
                    missing[slot] = 0
                    writeNode(slot, cell, frame, bytes, node, imageArea, frameLeafWeights)
                } else {
                    values[slot * FLOATS_PER_SLOT + 3] = 0f
                    missing[slot] += step
                    if (missing[slot] > graceFrames) tracks[slot] = -1
                }
            }
            val candidates = nodes.keys.sortedWith(compareByDescending<Int> { scores[it] ?: 0f }.thenBy { it }).iterator()
            for (slot in first until last) {
                if (tracks[slot] >= 0 || !candidates.hasNext()) continue
                val track = candidates.next()
                tracks[slot] = track
                // Float-exact IDs; wrapping clears any ancient attachment rather than overflowing.
                generations[slot] = generations[slot] % 16_000_000 + 1
                missing[slot] = 0
                writeNode(slot, cell, frame, bytes, nodes.getValue(track), imageArea, frameLeafWeights)
            }
            activeCount += (first until last).count(::valid)
        }
        if (treeWarp == null) clearTreeWarp() else applyTreeWarp(treeWarp)
    }

    private fun writeNode(
        slot: Int,
        cell: Int,
        frame: MxgFrame,
        bytes: ByteBuffer,
        node: Int,
        area: Float,
        frameLeafWeights: FloatArray?
    ) {
        val out = slot * FLOATS_PER_SLOT
        values[out] = frame.nodeCentroidX(node)
        values[out+1] = frame.nodeCentroidY(node)
        values[out+2] = (bytes.getFloat(node*MxgFrame.NODE_BYTES+16)/area).coerceIn(0f, 1f)
        values[out+3] = 1f
        values[out+4] = generations[slot].toFloat()
        values[out+5] = tracks[slot].toFloat() // debug only; generation is the GPU identity token
        values[out+6] = bytes.getFloat(node*MxgFrame.NODE_BYTES+28).takeIf { it.isFinite() } ?: 0f
        values[out+7] = cell.toFloat()
        leafWeights[slot] = frameLeafWeights?.get(node)?.coerceIn(0f, 1f) ?: 0f
    }

    /** Refresh scoreable root positions without reselecting tracks or decoding another MXG frame. */
    fun applyTreeWarp(treeWarp: MxgTreeWarp) {
        require(treeWarp.cells.size == cells) { "tree warp has ${treeWarp.cells.size} cells, expected $cells" }
        for (slot in 0 until count) {
            val out = slot * FLOATS_PER_SLOT + 8
            val warp = treeWarp[slot / slotsPerCell]
            values[out] = warp.root.x
            values[out + 1] = warp.root.y
            values[out + 2] = warp.root.z
            values[out + 3] = leafWeights[slot] * warp.amount.coerceIn(0f, 1f)
        }
    }

    private fun clearTreeWarp() {
        for (slot in 0 until count) {
            val out = slot * FLOATS_PER_SLOT + 8
            values[out] = 0f
            values[out + 1] = 0f
            values[out + 2] = 0f
            values[out + 3] = 0f
        }
    }

    fun write(into: ByteBuffer) {
        into.order(ByteOrder.LITTLE_ENDIAN)
        values.forEach(into::putFloat)
    }

    private companion object {
        const val FLOATS_PER_SLOT = 12
    }
}

private fun Float?.orZero(): Float = this ?: 0f
