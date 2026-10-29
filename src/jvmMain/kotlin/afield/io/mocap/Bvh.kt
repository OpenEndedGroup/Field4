@file:afield.runtime.completion.CompletionCategory("Data")

package afield.io.mocap

import afield.geometry.mesh.TriMesh
import afield.geometry.mesh.tubeMesh
import afield.geometry.path.FLine
import afield.math.linalg.Vec3
import afield.math.oracle.Trajectory
import java.io.File
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Motion capture in BVH format: a joint hierarchy and one line of channel values per frame.
 *
 * [Bvh.read] parses a file; [Bvh.positions] runs forward kinematics to give every joint's position
 * (and every end site's: fingertips, toes, the top of the head) at a frame. [Bvh.toTrajectory]
 * turns a take into a [Trajectory] of joint positions for analysis or sequence memory, optionally
 * in the performer's own frame (travel and turning removed), and [Bvh.skeleton] draws a pose as
 * 3D FLines for `three(...)`. Tests: `BvhTest`.
 */

/** One joint of a [Bvh] hierarchy, or an end site (no channels, [isEndSite]). */
class BvhJoint internal constructor(
    val name: String,
    /** Index of the parent in [Bvh.joints], or -1 for the root. */
    val parent: Int,
    /** Offset from the parent, in the file's units. */
    val offset: DoubleArray,
    /** Channel names in file order, e.g. `Zrotation Yrotation Xrotation`. */
    val channels: List<String>,
    val isEndSite: Boolean,
    /** Index of this joint's first channel in a frame line. */
    internal val channelStart: Int,
)

/**
 * A parsed BVH take: [joints] in file order (end sites included, named `<parent>_end`), one
 * [frames] row of channel values per frame, [frameTime] seconds apart.
 */
class Bvh internal constructor(
    val joints: List<BvhJoint>,
    val frameTime: Double,
    val frames: Array<DoubleArray>,
    val name: String,
) {
    /** Number of frames. */
    val frameCount: Int get() = frames.size

    /** Frames per second. */
    val sampleRate: Double get() = 1.0 / frameTime

    /** Duration in seconds. */
    val duration: Double get() = frameCount * frameTime

    /** Index of the joint called [name]. */
    fun joint(name: String): Int = joints.indexOfFirst { it.name == name }.also { require(it >= 0) { "no joint '$name' in ${joints.filter { !it.isEndSite }.map { it.name }}" } }

    /** Parent–child pairs, one per bone. */
    val bones: List<Pair<Int, Int>> get() = joints.indices.filter { joints[it].parent >= 0 }.map { joints[it].parent to it }

    /**
     * World position of every joint at [frame], `[joint][x, y, z]`, y up as in the file. With
     * [bodyFrame] the root's horizontal position and heading are removed: positions are relative
     * to the point on the floor under the root, facing +z, so the same movement gives the same
     * numbers wherever the performer is and whichever way they face. Height is kept.
     */
    fun positions(frame: Int, bodyFrame: Boolean = false): Array<DoubleArray> {
        val row = frames[frame]
        val world = arrayOfNulls<DoubleArray>(joints.size) // 4x4 row-major transforms
        val out = Array(joints.size) { DoubleArray(3) }
        for ((i, j) in joints.withIndex()) {
            var m = translation(j.offset[0], j.offset[1], j.offset[2])
            for ((k, ch) in j.channels.withIndex()) {
                val v = row[j.channelStart + k]
                m = when (ch) {
                    "Xposition" -> mul(m, translation(v, 0.0, 0.0))
                    "Yposition" -> mul(m, translation(0.0, v, 0.0))
                    "Zposition" -> mul(m, translation(0.0, 0.0, v))
                    "Xrotation" -> mul(m, rotation(0, v))
                    "Yrotation" -> mul(m, rotation(1, v))
                    "Zrotation" -> mul(m, rotation(2, v))
                    else -> m
                }
            }
            val w = if (j.parent < 0) m else mul(world[j.parent]!!, m)
            world[i] = w
            out[i][0] = w[3]; out[i][1] = w[7]; out[i][2] = w[11]
        }
        if (!bodyFrame) return out
        val root = world[0]!!
        // heading: where the root's local +z points, projected on the floor
        val heading = atan2(root[2], root[10])
        val c = cos(-heading); val s = sin(-heading)
        val ox = root[3]; val oz = root[11]
        for (p in out) {
            val x = p[0] - ox; val z = p[2] - oz
            p[0] = c * x + s * z; p[2] = -s * x + c * z
        }
        return out
    }

    /**
     * Frames at the start that are a rest pose rather than motion: 1 when frame 0 jumps to frame 1
     * by far more (over 20×) than the take's typical frame-to-frame motion, as in the CMU BVH
     * conversion, whose first frame is a T-pose; otherwise 0.
     */
    val restPoseFrames: Int by lazy {
        if (frameCount < 10) return@lazy 0
        fun step(f: Int): Double { val a = positions(f); val b = positions(f + 1); return a.indices.sumOf { j -> (0 until 3).sumOf { (a[j][it] - b[j][it]).let { d -> d * d } } } }
        val typical = (1 until min(frameCount - 1, 200) step 10).map { step(it) }.sorted().let { it[it.size / 2] }
        if (step(0) > 400 * typical.coerceAtLeast(1e-12)) 1 else 0
    }

    /**
     * The take as a [Trajectory] of joint positions resampled to [rate] Hz: channels named
     * `Joint.x`, `Joint.y`, `Joint.z` for each of [jointNames] (all joints and end sites by
     * default). [bodyFrame] as in [positions]. [from] and [to] trim the take, in seconds; [from]
     * starts after any [restPoseFrames].
     */
    fun toTrajectory(
        rate: Double = 30.0,
        jointNames: List<String> = joints.map { it.name },
        bodyFrame: Boolean = true,
        from: Double = restPoseFrames * frameTime,
        to: Double = duration,
    ): Trajectory {
        val ids = jointNames.map { joint(it) }
        val n = max(1, ((min(to, duration) - from) * rate).roundToInt())
        val values = Array(ids.size * 3) { DoubleArray(n) }
        for (t in 0 until n) {
            val f = ((from + t / rate) * sampleRate).roundToInt().coerceIn(0, frameCount - 1)
            val p = positions(f, bodyFrame)
            for ((k, id) in ids.withIndex()) for (a in 0 until 3) values[k * 3 + a][t] = p[id][a]
        }
        val names = jointNames.flatMap { listOf("$it.x", "$it.y", "$it.z") }
        return Trajectory(rate, names, values, name = name)
    }

    /**
     * A pose drawn as 3D FLines, one per bone, for `three(...)`. [frame] is one sample of a
     * [toTrajectory] trajectory (all joints, in order), or of any trajectory with the same
     * channels; joints missing from [channels] are skipped. [at] shifts the figure, e.g. to lay
     * out poses side by side.
     */
    fun skeleton(frame: DoubleArray, channels: List<String>, at: DoubleArray = DoubleArray(3)): List<FLine> {
        val index = HashMap<String, Int>()
        for ((i, c) in channels.withIndex()) if (c.endsWith(".x")) index[c.removeSuffix(".x")] = i
        fun p(j: Int): DoubleArray? = index[joints[j].name]?.let { doubleArrayOf(frame[it] + at[0], frame[it + 1] + at[1], frame[it + 2] + at[2]) }
        return bones.mapNotNull { (a, b) ->
            val pa = p(a) ?: return@mapNotNull null
            val pb = p(b) ?: return@mapNotNull null
            FLine().moveTo(pa[0], pa[1], pa[2]).lineTo(pb[0], pb[1], pb[2])
        }
    }

    /**
     * A pose as one solid mesh: a round tube of [radius] along every bone, painted [color] (linear
     * rgb), for `three { mesh(...) }`. Unlike [skeleton]'s lines, which the viewer draws one pixel wide,
     * tubes keep their thickness when a figure is scaled down, as in a recorded film. [frame],
     * [channels] and [at] as for [skeleton].
     */
    fun skeletonMesh(frame: DoubleArray, channels: List<String>, radius: Double = 0.5, color: DoubleArray = doubleArrayOf(0.5, 0.85, 1.0), at: DoubleArray = DoubleArray(3)): TriMesh {
        val index = HashMap<String, Int>()
        for ((i, c) in channels.withIndex()) if (c.endsWith(".x")) index[c.removeSuffix(".x")] = i
        fun p(j: Int): Vec3? = index[joints[j].name]?.let { Vec3((frame[it] + at[0]).toFloat(), (frame[it + 1] + at[1]).toFloat(), (frame[it + 2] + at[2]).toFloat()) }
        val tubes = bones.mapNotNull { (a, b) ->
            val pa = p(a) ?: return@mapNotNull null
            val pb = p(b) ?: return@mapNotNull null
            if (pa.distance(pb) < 1e-6f) null else tubeMesh(listOf(pa, pb), radius, sides = 8)
        }
        require(tubes.isNotEmpty()) { "no bones found in the given channels" }
        val c = Vec3(color[0].toFloat(), color[1].toFloat(), color[2].toFloat())
        return tubes.reduce { m, t -> m + t }.coloredBy { c }
    }

    override fun toString() = "Bvh($name: ${joints.count { !it.isEndSite }} joints, $frameCount frames @ ${"%.0f".format(sampleRate)} Hz, ${"%.1f".format(duration)} s)"

    companion object {
        /** Reads a BVH file, gzipped or not (`.bvh` or `.bvh.gz`). */
        fun read(file: File): Bvh {
            val text = if (file.name.endsWith(".gz")) java.util.zip.GZIPInputStream(file.inputStream()).bufferedReader().readText() else file.readText()
            return parse(text, file.name.removeSuffix(".gz").removeSuffix(".bvh"))
        }

        /** Reads a BVH file by path. */
        fun read(path: String): Bvh = read(File(path))

        /** Parses BVH text. */
        fun parse(text: String, name: String = ""): Bvh {
            val tok = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            var i = 0
            fun next() = tok[i++]
            fun expect(s: String) { val t = next(); require(t.equals(s, ignoreCase = true)) { "BVH: expected $s, found $t" } }
            val joints = ArrayList<BvhJoint>()
            var channelCount = 0
            fun joint(parent: Int, endSite: Boolean, jname: String) {
                expect("{")
                expect("OFFSET")
                val offset = DoubleArray(3) { next().toDouble() }
                val channels = ArrayList<String>()
                if (!endSite && tok[i].equals("CHANNELS", true)) {
                    next()
                    val n = next().toInt()
                    repeat(n) { channels.add(next()) }
                }
                val me = joints.size
                joints.add(BvhJoint(jname, parent, offset, channels, endSite, channelCount))
                channelCount += channels.size
                while (true) {
                    when (val t = next().uppercase()) {
                        "JOINT" -> joint(me, false, next())
                        "END" -> { next(); joint(me, true, "${jname}_end") }
                        "}" -> return
                        else -> error("BVH: unexpected '$t' in joint $jname")
                    }
                }
            }
            expect("HIERARCHY")
            expect("ROOT")
            joint(-1, false, next())
            expect("MOTION")
            expect("Frames:")
            val count = next().toInt()
            expect("Frame")
            expect("Time:")
            val frameTime = next().toDouble()
            val frames = Array(count) { DoubleArray(channelCount) { next().toDouble() } }
            return Bvh(joints, frameTime, frames, name)
        }

        private fun translation(x: Double, y: Double, z: Double) = doubleArrayOf(1.0, 0.0, 0.0, x, 0.0, 1.0, 0.0, y, 0.0, 0.0, 1.0, z, 0.0, 0.0, 0.0, 1.0)

        private fun rotation(axis: Int, degrees: Double): DoubleArray {
            val a = degrees * PI / 180; val c = cos(a); val s = sin(a)
            return when (axis) {
                0 -> doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, c, -s, 0.0, 0.0, s, c, 0.0, 0.0, 0.0, 0.0, 1.0)
                1 -> doubleArrayOf(c, 0.0, s, 0.0, 0.0, 1.0, 0.0, 0.0, -s, 0.0, c, 0.0, 0.0, 0.0, 0.0, 1.0)
                else -> doubleArrayOf(c, -s, 0.0, 0.0, s, c, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
            }
        }

        private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
            val r = DoubleArray(16)
            for (row in 0 until 4) for (col in 0 until 4) {
                var s = 0.0
                for (k in 0 until 4) s += a[row * 4 + k] * b[k * 4 + col]
                r[row * 4 + col] = s
            }
            return r
        }
    }
}
