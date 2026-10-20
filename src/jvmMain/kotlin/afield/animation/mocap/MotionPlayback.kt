@file:afield.runtime.completion.CompletionCategory("Animation")

package afield.animation.mocap

import java.util.ArrayDeque

data class MotionSnapshot(val frame: MotionFrame, val lagSeconds: Double, val held: Boolean)

/**
 * Bounded live display buffer. Missing data holds the last pose and reports lag; no extrapolation.
 * Reset/episode edges hold the earlier pose until the new frame's time. Reader and producer may differ.
 */
class MotionBuffer(val capacity: Int = 8, val maxSpanSeconds: Double = .4) {
    private val frames = ArrayDeque<MotionFrame>()
    var dropped: Long = 0; private set
    init { require(capacity in 2..1024 && maxSpanSeconds.isFinite() && maxSpanSeconds > 0) }
    @Synchronized fun clear() { frames.clear() }
    @Synchronized fun add(frame: MotionFrame) {
        val previous = frames.peekLast()
        if (previous != null) {
            require(frame.time >= previous.time && frame.index > previous.index) { "Out-of-order motion frame" }
            require(frame.episode >= previous.episode) { "Old episode" }
        }
        frames.addLast(frame)
        while (frames.size > capacity || frames.size > 1 && frame.time - frames.first.time > maxSpanSeconds) {
            frames.removeFirst(); dropped++
        }
    }
    @Synchronized fun sample(time: Double): MotionSnapshot? = sampleFrames(frames.toList(), time)
    @Synchronized fun size(): Int = frames.size
}

/** Immutable recorded poses, independent of model installations and seeds. */
class MotionClip(val skeleton: MotionSkeleton, frames: List<MotionFrame>) {
    val frames = frames.toList()
    init {
        require(frames.isNotEmpty())
        frames.forEach { require(it.executed.size == skeleton.size) }
        frames.zipWithNext().forEach { (a, b) ->
            require(b.time >= a.time && b.index > a.index && b.episode >= a.episode)
        }
    }
    val startTime get() = frames.first().time
    val endTime get() = frames.last().time
    fun sample(time: Double): MotionSnapshot = sampleFrames(frames, time)!!
}

private fun sampleFrames(frames: List<MotionFrame>, time: Double): MotionSnapshot? {
    require(time.isFinite())
    if (frames.isEmpty()) return null
    val insertion = frames.binarySearchBy(time) { it.time }
    if (insertion >= 0) return MotionSnapshot(frames[insertion], 0.0, false)
    val next = -insertion - 1
    if (next == 0) return MotionSnapshot(frames.first(), frames.first().time - time, true)
    val a = frames[next - 1]
    if (next == frames.size) return MotionSnapshot(a, time - a.time, true)
    val b = frames[next]
    if (a.episode != b.episode) return MotionSnapshot(a, time - a.time, true)
    val fraction = ((time - a.time) / (b.time - a.time)).toFloat()
    fun pose(a: MotionPose, b: MotionPose): MotionPose {
        fun linear(x: List<Double>, y: List<Double>) = x.indices.map { x[it] + (y[it] - x[it]) * fraction }
        val rotations = ArrayList<Double>(a.size * 4)
        if (a.hasRotations && b.hasRotations) repeat(a.size) { i ->
            // Field's Quaternionf.slerp uses the shortest arc, including q/-q equivalence.
            val q = a.rotation(i).slerp(b.rotation(i), fraction).normalize()
            rotations.addAll(listOf(q.x.toDouble(), q.y.toDouble(), q.z.toDouble(), q.w.toDouble()))
        }
        return MotionPose(linear(a.root, b.root), rotations, linear(a.worldPositions, b.worldPositions))
    }
    val intended = if (a.intended != null && b.intended != null) pose(a.intended, b.intended) else a.intended
    return MotionSnapshot(MotionFrame(time, a.index, a.episode, a.revision, pose(a.executed, b.executed),
        intended, a.contacts, a.contactProvenance, a.diagnostics), 0.0, false)
}
