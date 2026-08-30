package drowning

import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector3fc
import afield.stdlib.linalg.Vector4f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** One independently scoreable world-space root for an MXG cell. */
class MxgTreeWarpCell internal constructor() {
    val root = Vector3f()
    var amount: Float = 0f

    fun set(position: Vector3fc, amount: Float = this.amount) = apply {
        root.set(position)
        this.amount = amount.coerceIn(0f, 1f)
    }

    fun set(x: Float, y: Float, z: Float, amount: Float = this.amount) = apply {
        root.set(x, y, z)
        this.amount = amount.coerceIn(0f, 1f)
    }

    /** `(root.xyz, amount)` is deliberately small enough to remain a live push constant. */
    fun shaderValue(): Vector4f = Vector4f(root.x, root.y, root.z, amount.coerceIn(0f, 1f))
}

/**
 * CPU-authored deformation of an MXG forest.
 *
 * Structural leaves have weight zero and remain exactly on the depth-unprojected image. A root has
 * weight one; intermediate nodes use their longest descendant distance, normalized by their root's
 * height. [distancePower] bends that interpolation without changing either endpoint.
 */
class MxgTreeWarp(cellCount: Int = 15) {
    val cells: List<MxgTreeWarpCell> = List(cellCount) { MxgTreeWarpCell() }
    var distancePower: Float = 1f
    var moveContours: Boolean = false
    var drawTethers: Boolean = true

    operator fun get(cell: Int): MxgTreeWarpCell = cells[cell]

    fun amount(value: Float) = apply { cells.forEach { it.amount = value.coerceIn(0f, 1f) } }

    fun clear() = amount(0f)

    /** Place the roots on a small horizontal ring above [centre]; leaves remain at their screens. */
    fun rootsAbove(
        centre: Vector3fc,
        height: Float,
        radius: Float = 0f,
        amount: Float = 1f,
        phaseTurns: Float = 0f
    ) = apply {
        cells.forEachIndexed { index, cell ->
            val angle = 2.0 * PI * (index.toDouble() / cells.size + phaseTurns)
            cell.set(
                centre.x() + (sin(angle) * radius).toFloat(),
                centre.y() + height,
                centre.z() + (cos(angle) * radius).toFloat(),
                amount
            )
        }
    }

    /**
     * Compute the annotation installed in the GPU copy of an MXG node frame. This is O(nodes), uses
     * no geometry or depth readback, and is expected to run once per newly presented source frame.
     */
    fun leafDistanceWeights(frame: MxgFrame): FloatArray {
        val count = frame.nodeCount
        if (count == 0) return FloatArray(0)
        val parent = IntArray(count) { node ->
            frame.nodeParent(node).takeIf { it in 0 until count && it != node } ?: MxgFrame.INVALID
        }
        val remainingChildren = IntArray(count)
        parent.forEach { if (it != MxgFrame.INVALID) remainingChildren[it]++ }
        val height = IntArray(count)
        val queue = IntArray(count)
        var read = 0
        var write = 0
        for (node in 0 until count) if (remainingChildren[node] == 0) queue[write++] = node
        while (read < write) {
            val node = queue[read++]
            val p = parent[node]
            if (p != MxgFrame.INVALID) {
                height[p] = maxOf(height[p], height[node] + 1)
                remainingChildren[p]--
                if (remainingChildren[p] == 0) queue[write++] = p
            }
        }
        require(write == count) { "MXG frame ${frame.frameIndex} contains a parent cycle" }

        val rootOf = IntArray(count)
        for (node in 0 until count) {
            var root = node
            while (parent[root] != MxgFrame.INVALID) root = parent[root]
            rootOf[node] = root
        }
        val power = distancePower.coerceAtLeast(0.01f).toDouble()
        return FloatArray(count) { node ->
            val rootHeight = height[rootOf[node]]
            if (rootHeight == 0) 0f
            else (height[node].toDouble() / rootHeight).pow(power).toFloat()
        }
    }
}
