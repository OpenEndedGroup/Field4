package afield.four.physics


import afield.stdlib.Vec3
import afield.stdlib.linalg.Vector3f
import java.io.File
import java.util.TreeMap
import kotlin.math.max
import kotlin.math.min

/**
 * A reader for motion-capture CSV, and a clip that samples it at an arbitrary time.
 *
 * The format is one row per captured frame: a time in the first column, then three columns per
 * joint. Joint names come out of the header, which is expected to read
 * `time,Hip.x,Hip.y,Hip.z,Knee.x,…`: every third cell, cut at its first `.`. `MotionClip.load` does
 * that parsing and is the only way to build a clip from a file. It sits on the companion object, so
 * it has no row of its own on this page.
 *
 * [MotionClip] sorts its frames by time on construction, and `sample(t)` clamps `t` into the clip's
 * range, binary-searches for the bracketing pair of frames and interpolates every joint linearly
 * between them. The vectors that come back are fresh copies, so a caller can move them around
 * without disturbing the clip.
 *
 * A clip holds no reference to a solver and nothing else in `afield.four.physics` reads one, so
 * which particle a joint drives is a decision for the calling code. Two kinds of malformed input
 * load without an exception. A data row with fewer fields than the header promises is skipped, so a
 * truncated file becomes a shorter clip; and a file whose every data row is short becomes a clip of
 * no frames, which answers `sample` with an empty map and throws from `start`, `end` and
 * `duration`.
 */

/**
 * One captured frame: a timestamp, and a position per joint name.
 *
 * The names are whatever the CSV header spelled, and `MotionClip.load` gives every frame it builds
 * the same set of them. A hand-assembled list whose names vary between frames will fail in
 * `MotionClip.sample`, which looks the earlier frame's names up in the later one.
 */
data class Frame(
    val time: Float,
    val joints: Map<String, Vector3f>
)

/**
 * A sequence of [Frame]s, sampled by time.
 *
 * Frames are sorted by time on construction, so a list in any order is accepted. `start`, `end` and
 * `duration` read off the first and last of the sorted frames, and so throw on a clip of none.
 */
class MotionClip(frames: List<Frame>) {

    private val frames: List<Frame> = frames.sortedBy { it.time }
    private val times: List<Float> = this.frames.map { it.time }

    val start : Float
        get() = times.first()

    val end : Float
        get() = times.last()

    val duration : Float
        get() = end-start

    /**
     * The axis-aligned extent of every joint of every frame, as `(minimum, maximum)`.
     *
     * Recomputed on each read, walking the whole clip. A clip of no frames answers with the
     * inverted box it starts from, positive infinity paired with negative infinity, rather than
     * throwing.
     */
    val bounds : Pair<Vec3, Vec3>
        get() {
            val m = Vec3(Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY)
            val x = Vec3(Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY)
            frames.forEach {
                it.joints.forEach { string, at ->
                    m.min(at)
                    x.max(at)
                }
            }
            return m to x
        }

    /**
     * Every joint's position at [time], interpolated linearly between the two frames bracketing it.
     *
     * [time] is clamped into the clip's range, so a value before `start` or after `end` gives the
     * pose of the nearer end frame rather than an error, and a clip of no frames gives an empty
     * map. The vectors returned are new on every call and are never the ones the clip holds.
     */
    fun sample(time: Float): Map<String, Vector3f> {
        if (frames.isEmpty()) return emptyMap()

        // Clamp time to valid range
        val clampedTime = time.coerceIn(times.first(), times.last())

        // Binary search
        val index = times.binarySearch(clampedTime)
        if (index >= 0) {
            // Exact frame hit
            return frames[index].joints.mapValues { Vector3f(it.value) }
        }

        val insertIndex = -index - 1
        val i0 = max(0, insertIndex - 1)
        val i1 = min(frames.lastIndex, insertIndex)

        if (i0 == i1) {
            return frames[i0].joints.mapValues { Vector3f(it.value) }
        }

        val f0 = frames[i0]
        val f1 = frames[i1]

        val t = (clampedTime - f0.time) / (f1.time - f0.time)

        val result = mutableMapOf<String, Vector3f>()
        for (joint in f0.joints.keys) {
            val v0 = f0.joints[joint]!!
            val v1 = f1.joints[joint]!!
            result[joint] = Vector3f(v0).lerp(v1, t)
        }

        return result
    }

    companion object {

        /**
         * Loads a CSV file of the format `time,Joint.x,Joint.y,Joint.z,…`.
         *
         * The joint names, and how many of them there are, come from the header alone: every third
         * cell after the first, cut at its first `.`. A blank line anywhere is dropped; a data row
         * carrying fewer than `1 + 3 * joints` fields is skipped rather than reported. An empty
         * file throws.
         */
        fun load(file: String): MotionClip {
            val lines = File(file).readLines().filter { it.isNotBlank() }
            require(lines.isNotEmpty()) { "Empty file" }

            val header = lines.first().split(",").map { it.trim() }

            // Extract joint names from header
            val jointOrder = mutableListOf<String>()
            for (i in 1 until header.size step 3) {
                val name = header[i].substringBefore(".")
                jointOrder.add(name)
            }

            val frames = mutableListOf<Frame>()

            for (line in lines.drop(1)) {
                val tokens = line.split(",").map { it.trim() }
                if (tokens.size < 1 + jointOrder.size * 3) continue

                val time = tokens[0].toFloat()
                val joints = mutableMapOf<String, Vector3f>()

                var idx = 1
                for (joint in jointOrder) {
                    val x = tokens[idx++].toFloat()
                    val y = tokens[idx++].toFloat()
                    val z = tokens[idx++].toFloat()
                    joints[joint] = Vector3f(x, y, z)
                }

                frames.add(Frame(time, joints))
            }

            return MotionClip(frames)
        }
    }
}

//////////////////////
// Example Usage
//////////////////////

/** Example usage: load `motion.csv`, sample it at 0.05 and print the pose a joint at a time. */
fun main() {
    val clip = MotionClip.load("motion.csv")

    val t = 0.05f
    val pose = clip.sample(t)

    println("Pose at $t:")
    pose.forEach { (joint, position) ->
        println("$joint -> $position")
    }
}
