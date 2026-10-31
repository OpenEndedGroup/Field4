package afield.media.video

import afield.media.image.Img
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Tracked particles from a video, turned into a velocity field that can be measured.
 *
 * [ParticleVideo] follows thousands of points through a movie and leaves a cloud of samples per
 * frame. This file splats those velocities onto a regular grid, one [FlowFrame] per frame, so every
 * cell carries a velocity whether or not a particle landed in it. [ParticleFlow.at] hands one frame
 * back as an [Img], u in channel 0 and v in channel 1, which is the form the vector-field topology
 * and streamline code reads.
 *
 * A tracked video's flow often describes the camera more than its subject, and on old film the
 * whole frame shifts and turns a little from one frame to the next. So each frame also carries the
 * camera motion measured from its own particles: a median shift, and an affine fit that picks up a
 * slight zoom or rotation as well. [CameraMotion] chooses how much of that to subtract when a frame
 * is read back. A flow is far smaller than the tracks it came from, and smaller again gzipped and
 * quantised, so [ParticleFlow.save] and [loadParticleFlow] let a page or a test begin from a saved
 * flow instead of running the tracker.
 */

/**
 * What to take out of a flow before using it: nothing ([KEEP]), the frame's median velocity
 * ([SHIFT]), or an affine motion fitted to every particle and refitted without the outliers
 * ([AFFINE]), which also removes a camera's slight zoom and rotation, or old film weaving in the gate.
 */
enum class CameraMotion { KEEP, SHIFT, AFFINE }

/**
 * One frame of a [ParticleFlow]: the particle velocities splatted onto the grid (weighted sums
 * [u], [v] and total weight [w] per cell), and the camera motion measured from the particles, as a
 * median [shift] (vx, vy) and an [affine] fit (vx = a0 + a1 x + a2 y, vy = b0 + b1 x + b2 y, in
 * processed-image pixels).
 */
class FlowFrame(val u: FloatArray, val v: FloatArray, val w: FloatArray, val shift: DoubleArray, val affine: DoubleArray)

/**
 * The tracked particles of a [ParticleVideo] turned into a dense velocity field: each frame's
 * particle velocities spread onto a grid of [cell]-pixel cells with a Gaussian of [sigma] cells.
 *
 * ```
 * val flow = loadParticleVideo("tracks.pvt").flow(cell = 4)
 * val topology = VectorFieldTopology.compute(flow.at(120, camera = CameraMotion.AFFINE))
 * ```
 *
 * [at] gives one frame's flow as an image (u in channel 0, v in channel 1, in pixels per frame),
 * averaged over a few neighbouring frames and with the camera's motion taken out or left in.
 * Positions on the grid are in cells: multiply by [cell] for processed-image pixels. A flow is much
 * smaller than its tracks, so [save] and [loadParticleFlow] keep it for later.
 */
class ParticleFlow(val width: Int, val height: Int, val cell: Int, val fps: Double, val frames: List<FlowFrame>) {
    val frameCount get() = frames.size

    /**
     * The flow at [frame], averaged over [window] frames either side, with [camera] motion
     * removed. Cells with no particles near them fade towards zero (the average is divided by the
     * weight plus [prior]).
     */
    fun at(frame: Int, window: Int = 1, camera: CameraMotion = CameraMotion.AFFINE, prior: Float = 0.5f): Img {
        require(frame in 0 until frameCount) { "frame $frame is outside 0 until $frameCount" }
        val n = width * height
        val su = FloatArray(n); val sv = FloatArray(n); val sw = FloatArray(n)
        for (f in (frame - window).coerceAtLeast(0)..(frame + window).coerceAtMost(frameCount - 1)) {
            val fr = frames[f]
            for (i in 0 until n) {
                val x = (i % width + 0.5) * cell; val y = (i / width + 0.5) * cell
                val (cu, cv) = when (camera) {
                    CameraMotion.KEEP -> 0.0 to 0.0
                    CameraMotion.SHIFT -> fr.shift[0] to fr.shift[1]
                    CameraMotion.AFFINE -> (fr.affine[0] + fr.affine[1] * x + fr.affine[2] * y) to (fr.affine[3] + fr.affine[4] * x + fr.affine[5] * y)
                }
                // the sums are weighted, so the camera's share of a cell is its weight times the camera velocity
                su[i] += fr.u[i] - (fr.w[i] * cu).toFloat(); sv[i] += fr.v[i] - (fr.w[i] * cv).toFloat(); sw[i] += fr.w[i]
            }
        }
        val out = FloatArray(n * 3)
        for (i in 0 until n) { val d = sw[i] + prior; out[3 * i] = su[i] / d; out[3 * i + 1] = sv[i] / d }
        return Img(width, height, 3, out)
    }

    /**
     * Writes the flow, gzipped and quantised: each cell's mean velocity to 1/64 pixel per frame and
     * its weight to 1/256, far finer than the tracking itself, which keeps a few hundred frames to a
     * few megabytes.
     */
    fun save(path: String) {
        DataOutputStream(GZIPOutputStream(File(path).outputStream().buffered())).use { o ->
            o.writeInt(MAGIC); o.writeInt(width); o.writeInt(height); o.writeInt(cell); o.writeDouble(fps); o.writeInt(frames.size)
            fun q(x: Float, scale: Float) = (x * scale).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            for (f in frames) {
                for (i in f.w.indices) {
                    val wt = f.w[i]; val mu = if (wt > 0) f.u[i] / wt else 0f; val mv = if (wt > 0) f.v[i] / wt else 0f
                    o.writeShort(q(mu, 64f)); o.writeShort(q(mv, 64f)); o.writeShort(q(wt, 256f).coerceAtLeast(0))
                }
                f.shift.forEach(o::writeDouble); f.affine.forEach(o::writeDouble)
            }
        }
    }

    internal companion object {
        const val MAGIC = 0x464c4f57 // "FLOW"
    }
}

/** Reads a flow written by [ParticleFlow.save]. */
fun loadParticleFlow(path: String): ParticleFlow =
    DataInputStream(GZIPInputStream(File(path).inputStream().buffered())).use { i ->
        require(i.readInt() == ParticleFlow.MAGIC) { "$path is not a particle flow" }
        val w = i.readInt(); val h = i.readInt(); val cell = i.readInt(); val fps = i.readDouble(); val count = i.readInt()
        val n = w * h
        ParticleFlow(w, h, cell, fps, List(count) {
            val u = FloatArray(n); val v = FloatArray(n); val wt = FloatArray(n)
            for (k in 0 until n) {
                val mu = i.readShort() / 64f; val mv = i.readShort() / 64f; wt[k] = i.readShort() / 256f
                u[k] = mu * wt[k]; v[k] = mv * wt[k]
            }
            FlowFrame(u, v, wt, DoubleArray(2) { i.readDouble() }, DoubleArray(6) { i.readDouble() })
        })
    }

/**
 * Every frame's particle velocities spread onto a grid of [cell]-pixel cells, with a Gaussian of
 * [sigma] cells, and the camera motion of each frame measured: see [ParticleFlow].
 */
fun ParticleVideo.flow(cell: Int = 4, sigma: Double = 1.5): ParticleFlow {
    require(cell >= 1 && sigma > 0) { "cell must be at least 1 and sigma positive" }
    val gw = width / cell; val gh = height / cell
    val r = kotlin.math.ceil(3 * sigma).toInt()
    return ParticleFlow(gw, gh, cell, fps, (0 until frameCount).map { f ->
        val samples = frame(f).samples
        val u = FloatArray(gw * gh); val v = FloatArray(gw * gh); val w = FloatArray(gw * gh)
        for (s in samples) {
            val gx = s.x / cell - 0.5; val gy = s.y / cell - 0.5
            for (yy in (gy.toInt() - r)..(gy.toInt() + r + 1)) for (xx in (gx.toInt() - r)..(gx.toInt() + r + 1)) {
                if (xx !in 0 until gw || yy !in 0 until gh) continue
                val k = exp(-((xx - gx) * (xx - gx) + (yy - gy) * (yy - gy)) / (2 * sigma * sigma)).toFloat()
                val i = yy * gw + xx
                u[i] += k * s.vx; v[i] += k * s.vy; w[i] += k
            }
        }
        FlowFrame(u, v, w, medianShift(samples), affineCamera(samples))
    })
}

private fun medianShift(s: List<ParticleSample>): DoubleArray {
    if (s.isEmpty()) return DoubleArray(2)
    return doubleArrayOf(s.map { it.vx }.sorted()[s.size / 2].toDouble(), s.map { it.vy }.sorted()[s.size / 2].toDouble())
}

/** Least-squares affine flow over [s], refitted once without samples more than 3 median residuals off. */
private fun affineCamera(s: List<ParticleSample>): DoubleArray {
    if (s.size < 10) return DoubleArray(6)
    fun fit(p: List<ParticleSample>): DoubleArray {
        val m = Array(3) { DoubleArray(3) }; val bu = DoubleArray(3); val bv = DoubleArray(3)
        for (q in p) {
            val b = doubleArrayOf(1.0, q.x.toDouble(), q.y.toDouble())
            for (a in 0..2) { for (c in 0..2) m[a][c] += b[a] * b[c]; bu[a] += b[a] * q.vx; bv[a] += b[a] * q.vy }
        }
        fun solve(rhs: DoubleArray): DoubleArray {
            val a = Array(3) { m[it].copyOf() + rhs[it] }
            for (c in 0..2) {
                val p = (c..2).maxBy { abs(a[it][c]) }; val t = a[c]; a[c] = a[p]; a[p] = t
                if (abs(a[c][c]) < 1e-12) return DoubleArray(3)
                for (r in 0..2) if (r != c) { val k = a[r][c] / a[c][c]; for (cc in c..3) a[r][cc] -= k * a[c][cc] }
            }
            return DoubleArray(3) { a[it][3] / a[it][it] }
        }
        return solve(bu) + solve(bv)
    }
    fun residual(q: ParticleSample, c: DoubleArray) =
        hypot(q.vx - (c[0] + c[1] * q.x + c[2] * q.y), q.vy - (c[3] + c[4] * q.x + c[5] * q.y))
    val first = fit(s)
    val rs = s.map { residual(it, first) }.sorted()
    val cut = 3 * rs[rs.size / 2] + 0.05
    return fit(s.filter { residual(it, first) < cut })
}
