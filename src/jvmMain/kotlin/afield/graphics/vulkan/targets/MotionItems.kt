package afield.graphics.vulkan.targets

import kotlin.math.cos
import kotlin.math.sin

/**
 * Per-item motion for 2D items drawn by stable identity, for passes that need each item's previous
 * pose.
 *
 * [MotionItems] keeps this frame's and last frame's [MotionPose] for each key and writes the
 * difference, falling back to no motion when the item is new, changed revision or was cleared.
 * `MotionItemsTest` covers it.
 */

/** Mutable 2D affine pose. Angles are radians; scale is positive and uniform. */
class MotionPose(
    /** Centre of the item on the x axis, in whatever units the pass draws in. */
    var x: Float = 0f,
    /** Centre of the item on the y axis. */
    var y: Float = 0f,
    /** Rotation about that centre, in radians. Only the difference between two frames is written out. */
    var angle: Float = 0f,
    /** Uniform scale about that centre. Must be finite and above zero; [MotionItems.write] requires it. */
    var scale: Float = 1f,
    /**
     * How much this item should count for. [MotionItems.write] treats a previous energy at or below
     * `1e-7` as no usable history, so a fading item stops contributing motion before it disappears.
     */
    var energy: Float = 1f,
    /** How soft the item's edge is. Like [energy], what reaches the pass is the previous frame's value. */
    var softness: Float = 0f
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
    /**
     * How many keys are being held, which is this frame's items plus any from the frame before that
     * have not been written again yet. [begin] drops the rest, and [clear] takes it to zero.
     */
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
