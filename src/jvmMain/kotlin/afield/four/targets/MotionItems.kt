package afield.four.targets

import kotlin.math.cos
import kotlin.math.sin

/** Mutable 2D affine pose. Angles are radians; scale is positive and uniform. */
class MotionPose(
    var x: Float = 0f, var y: Float = 0f, var angle: Float = 0f,
    var scale: Float = 1f, var energy: Float = 1f, var softness: Float = 0f
) {
    fun set(other: MotionPose) {
        x = other.x
        y = other.y
        angle = other.angle
        scale = other.scale
        energy = other.energy
        softness = other.softness
    }
}

/**
 * Correspondence by stable item identity, not draw-list index. Call [begin] once
 * per rendered frame (duplicate calls are harmless). Topology or material changes
 * use a different revision; camera cuts and world resets call [clear].
 * Retains only this frame and its immediate predecessor. Owner-thread only.
 */
class MotionItems {
    private class Item {
        val current = MotionPose()
        val previous = MotionPose()
        var frame = Long.MIN_VALUE
        var revision: Any? = null
        var previousRevision: Any? = null
        var valid = false
    }
    private val items = HashMap<Any, Item>()
    private var frame: Long? = null
    val retained: Int get() = items.size

    fun begin(serial: Long) {
        val last = frame
        if (last == serial) return
        if (last == null || last == Long.MAX_VALUE || serial != last + 1) items.clear()
        else items.entries.removeIf { it.value.frame != last }
        frame = serial
    }

    /**
     * Writes two vec4s: previous centre xy, cosine/sine scaled by previous/current
     * scale; then valid, previous energy, previous softness, reserved.
     * Use [FrameHistory.available] as [allow] when camera history is required.
     */
    fun write(key: Any, pose: MotionPose, out: FloatArray, at: Int = 0, allow: Boolean = true, revision: Any? = null) {
        val serial = checkNotNull(frame) { "Call MotionItems.begin before writing" }
        require(at >= 0 && at.toLong() + 8 <= out.size)
        require(pose.x.isFinite() && pose.y.isFinite() && pose.angle.isFinite())
        require(pose.scale.isFinite() && pose.scale > 0 && pose.energy.isFinite() && pose.softness.isFinite())
        val item = items.getOrPut(key) { Item() }
        if (item.frame != serial) {
            item.valid = serial != Long.MIN_VALUE && item.frame == serial - 1
            item.previous.set(item.current)
            item.previousRevision = item.revision
            item.frame = serial
        }
        val valid = allow && item.valid && item.previousRevision == revision && item.previous.energy > 1e-7f
        val before = if (valid) item.previous else pose
        val delta = before.angle - pose.angle
        val ratio = before.scale / pose.scale
        out[at] = before.x
        out[at + 1] = before.y
        out[at + 2] = cos(delta) * ratio
        out[at + 3] = sin(delta) * ratio
        out[at + 4] = if (valid) 1f else 0f
        out[at + 5] = before.energy
        out[at + 6] = before.softness
        out[at + 7] = 0f
        item.current.set(pose)
        item.revision = revision
    }

    fun clear() {
        items.clear()
        frame = null
    }
}
