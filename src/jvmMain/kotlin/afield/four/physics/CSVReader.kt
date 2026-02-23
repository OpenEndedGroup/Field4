package afield.four.physics


import afield.stdlib.Vec3
import org.joml.Vector3f
import java.io.File
import java.util.TreeMap
import kotlin.math.max
import kotlin.math.min

data class Frame(
    val time: Float,
    val joints: Map<String, Vector3f>
)

class MotionClip(frames: List<Frame>) {

    private val frames: List<Frame> = frames.sortedBy { it.time }
    private val times: List<Float> = this.frames.map { it.time }

    val start : Float
        get() = times.first()

    val end : Float
        get() = times.last()

    val duration : Float
        get() = end-start

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
     * Interpolates all joints at arbitrary time.
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
         * Loads CSV file of format:
         * time,Joint.x,Joint.y,Joint.z,...
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

fun main() {
    val clip = MotionClip.load("motion.csv")

    val t = 0.05f
    val pose = clip.sample(t)

    println("Pose at $t:")
    pose.forEach { (joint, position) ->
        println("$joint -> $position")
    }
}
