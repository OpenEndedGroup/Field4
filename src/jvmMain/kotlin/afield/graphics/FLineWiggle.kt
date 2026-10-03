package afield.graphics

import afield.stdlib.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Wiggle this line with another: [a]'s coordinates are read in a frame that travels along this line.
 *
 * A's x picks where along this line a point lands (A's x-extent maps onto the whole length, or onto
 * one share of it per repeat); A's y becomes an offset along the normal and A's z along the
 * binormal. A that doubles back in x doubles back along this line, so loops come through.
 *
 * ```kotlin
 * val wave = FLine().moveTo(0, 0).lineTo(1, 1).lineTo(2, -1).lineTo(3, 0)
 * val path = FLine().moveTo(0, 0).cubicTo(100, 0, 200, 200, 300, 200)
 * lines(path.wiggledBy(wave, repeats = 8, amplitude = 4))
 * ```
 *
 * @param a the wiggle.
 * @param repeats how many times A is played back along this line, each copy over an equal share of
 *   its length.
 * @param subtractChord read A relative to the straight line from its start to its end: x along that
 *   line, y perpendicular to it (in A's plane, if A is drawn flat in xy), z the rest. A then starts
 *   and ends at zero offset, so repeats join up and the result ends where this line ends. With
 *   `false`, A is read in world axes from its start — x along, y and z as offsets — so whatever y
 *   or z A gains between its ends is carried into the offset, and stacks up over repeats only if
 *   you draw it to.
 * @param amplitude scales the offset (A's y and z, not its x).
 * @param resolution samples taken of A; this line is sampled at least as finely per repeat.
 * @param up 3D only: the direction the starting normal leans toward when this line's own shape
 *   cannot choose one (see [sweptFrames]). `null` means no preference.
 */
fun FLine.wiggledBy(
    a: FLine,
    repeats: Int = 1,
    subtractChord: Boolean = true,
    amplitude: Number = 1.0,
    resolution: Int = 200,
    up: Vec3? = Vec3(0f, 0f, 1f),
): FLine {
    require(repeats >= 1) { "wiggledBy needs repeats >= 1, got $repeats" }
    require(resolution >= 2) { "wiggledBy needs resolution >= 2, got $resolution" }
    val amp = amplitude.toDouble()
    val out = FLine()
    val frames = sweptFrames(samples = maxOf(256, resolution * repeats), up = up) ?: return out
    val pa = sampleDense(a, resolution) ?: return out
    val start = pa[0]
    val end = pa[pa.size - 1]
    val chord = sub(end, start)
    val chordLen = len(chord)

    // A's axes: the chord's frame, or the world's. `along` is how far x runs from start to end.
    val ax: D3; val ay: D3; val az: D3
    if (subtractChord && chordLen > 1e-12) {
        ax = scale(chord, 1 / chordLen)
        val z = if (abs(ax[2]) < 0.9) doubleArrayOf(0.0, 0.0, 1.0) else doubleArrayOf(0.0, 1.0, 0.0)
        ay = normalize(cross(z, ax))
        az = cross(ax, ay)
    } else {
        ax = doubleArrayOf(1.0, 0.0, 0.0); ay = doubleArrayOf(0.0, 1.0, 0.0); az = doubleArrayOf(0.0, 0.0, 1.0)
    }
    val along = dot(chord, ax)
    // A with no x-extent (closed, or drawn straight up) has nothing to place it by; use arc length.
    val byArc = if (abs(along) > 1e-12) null else arcFractions(pa)

    var first = true
    for (k in 0 until repeats) {
        for (j in pa.indices) {
            if (k > 0 && j == 0) continue // the previous copy's last point
            val d = sub(pa[j], start)
            val u = byArc?.get(j) ?: (dot(d, ax) / along)
            val oy = dot(d, ay) * amp; val oz = dot(d, az) * amp
            val f = frames.frameAt((k + u) / repeats)
            val x = f.position[0] + oy * f.normal[0] + oz * f.binormal[0]
            val y = f.position[1] + oy * f.normal[1] + oz * f.binormal[1]
            val z = f.position[2] + oy * f.normal[2] + oz * f.binormal[2]
            if (first) out.moveTo(x, y, z) else out.lineTo(x, y, z)
            first = false
        }
    }
    return out
}

/** One frame along a line: where it is, and the tangent / normal / binormal there. */
class SweptFrame(val position: DoubleArray, val tangent: DoubleArray, val normal: DoubleArray, val binormal: DoubleArray)

/**
 * Frames swept along a line, sampled evenly by arc length. Query any point with [frameAt].
 *
 * In 2D (every point at z = 0) the normal is the tangent turned 90° anticlockwise and the binormal
 * is +z.
 *
 * In 3D the frames are rotation-minimising: the normal turns no more than the tangent forces it to.
 * That leaves one free choice, the angle of the first normal, and it is chosen from the line's own
 * shape — the angle that best lines the normals up with the line's curvature, weighting the start
 * more than the end. Because it comes from the shape, rotating the line rotates its frames with it,
 * smoothly, through every orientation. No rule that looks only at the starting direction can do that
 * (it would be a continuous field of normals on the sphere, which the hairy ball theorem forbids).
 *
 * The one case the shape cannot settle is a line with no preferred side — straight, or so nearly so
 * that the curvature cancels out. [strength] (0..1) says how decisive the shape was; as it falls
 * toward 0 the choice leans toward [up], and for an exactly straight line it is [up] alone.
 *
 * A closed line (start equals end) has its leftover twist spread evenly along it, so the last frame
 * meets the first.
 */
class SweptFrames internal constructor(
    val positions: Array<DoubleArray>,
    val tangents: Array<DoubleArray>,
    val normals: Array<DoubleArray>,
    val binormals: Array<DoubleArray>,
    /** Arc-length fraction (0..1) of each sample. */
    val fractions: DoubleArray,
    val planar: Boolean,
    val strength: Double,
    /** Arc length of the line. */
    val length: Double,
) {
    /**
     * The frame at arc-length fraction [s], interpolated between samples. Past either end the line
     * is continued straight along its end tangent, with the end frame.
     */
    fun frameAt(s: Double): SweptFrame {
        val n = positions.size
        val c = s.coerceIn(0.0, 1.0)
        var lo = 0; var hi = n - 1
        while (hi - lo > 1) { val m = (lo + hi) ushr 1; if (fractions[m] <= c) lo = m else hi = m }
        val span = fractions[hi] - fractions[lo]
        val t = if (span > 0) (c - fractions[lo]) / span else 0.0
        val p = lerp(positions[lo], positions[hi], t)
        val tan = normalize(lerp(tangents[lo], tangents[hi], t))
        var nor = lerp(normals[lo], normals[hi], t)
        nor = normalize(sub(nor, scale(tan, dot(nor, tan))))
        val p2 = if (s == c) p else add(p, scale(tan, (s - c) * length))
        return SweptFrame(p2, tan, nor, cross(tan, nor))
    }
}

/**
 * Frames along this line; see [SweptFrames]. `null` if the line has no length.
 *
 * @param samples how finely to sample the line.
 * @param up the lean for a line too straight to choose its own starting normal; `null` for none.
 */
fun FLine.sweptFrames(samples: Int = 400, up: Vec3? = Vec3(0f, 0f, 1f)): SweptFrames? {
    val p = sampleDense(this, maxOf(samples, 3)) ?: return null
    val n = p.size
    val s = arcFractions(p)
    val closed = n > 3 && len(sub(p[0], p[n - 1])) < 1e-6 * maxOf(1.0, totalLength(p))
    val planar = p.all { abs(it[2]) < 1e-9 }

    val tangents = Array(n) { i ->
        val prev = p[if (i == 0) (if (closed) n - 2 else 0) else i - 1]
        val next = p[if (i == n - 1) (if (closed) 1 else n - 1) else i + 1]
        val t = sub(next, prev)
        if (len(t) < 1e-15) doubleArrayOf(1.0, 0.0, 0.0) else normalize(t)
    }

    if (planar) {
        val normals = Array(n) { doubleArrayOf(-tangents[it][1], tangents[it][0], 0.0) }
        val binormals = Array(n) { doubleArrayOf(0.0, 0.0, 1.0) }
        return SweptFrames(p.toTypedArray(), tangents, normals, binormals, s, true, 1.0, totalLength(p))
    }

    // Rotation-minimising frames by double reflection (Wang et al. 2008) from an arbitrary seed. The
    // seed's angle is then replaced by one read off the shape, so its arbitrariness never shows.
    val u = arrayOfNulls<DoubleArray>(n)
    u[0] = anyPerpendicular(tangents[0])
    for (i in 1 until n) {
        val v1 = sub(p[i], p[i - 1])
        val c1 = dot(v1, v1)
        if (c1 < 1e-30) { u[i] = u[i - 1]; continue }
        val rL = sub(u[i - 1]!!, scale(v1, 2 * dot(v1, u[i - 1]!!) / c1))
        val tL = sub(tangents[i - 1], scale(v1, 2 * dot(v1, tangents[i - 1]) / c1))
        val v2 = sub(tangents[i], tL)
        val c2 = dot(v2, v2)
        var r = if (c2 < 1e-30) rL else sub(rL, scale(v2, 2 * dot(v2, rL) / c2))
        r = sub(r, scale(tangents[i], dot(r, tangents[i])))
        u[i] = if (len(r) < 1e-12) anyPerpendicular(tangents[i]) else normalize(r)
    }
    val w = Array(n) { cross(tangents[it], u[it]!!) }

    // Closed: spread the holonomy so the last frame meets the first.
    val twist = DoubleArray(n)
    if (closed) {
        val total = atan2(dot(u[n - 1]!!, w[0]), dot(u[n - 1]!!, u[0]!!))
        for (i in 0 until n) twist[i] = -total * s[i]
    }
    fun rotated(i: Int, extra: Double): DoubleArray {
        val a = twist[i] + extra
        return add(scale(u[i]!!, cos(a)), scale(w[i]!!, sin(a)))
    }

    // Starting angle from the shape: the start-weighted curvature vector, measured in the frame.
    var ca = 0.0; var cb = 0.0; var mag = 0.0
    for (i in 1 until n - 1) {
        val ds = 0.5 * (len(sub(p[i + 1], p[i])) + len(sub(p[i], p[i - 1])))
        if (ds < 1e-30) continue
        val k = scale(sub(tangents[i + 1], tangents[i - 1]), 1 / (2 * ds)) // curvature vector
        val weight = ds * (1.0 - 0.5 * s[i])
        val ui = rotated(i, 0.0)
        val wi = cross(tangents[i], ui)
        ca += weight * dot(k, ui); cb += weight * dot(k, wi); mag += weight * len(k)
    }
    val strength = if (mag > 1e-12) sqrt(ca * ca + cb * cb) / mag else 0.0
    // Blend toward `up` as the shape's vote weakens. Both are unit-scale, so the blend is continuous.
    var bx = if (mag > 1e-12) ca / mag else 0.0
    var by = if (mag > 1e-12) cb / mag else 0.0
    if (up != null) {
        val upd = doubleArrayOf(up.x.toDouble(), up.y.toDouble(), up.z.toDouble())
        val upPerp = sub(upd, scale(tangents[0], dot(upd, tangents[0])))
        if (len(upPerp) > 1e-9) {
            val lean = UP_LEAN * (1 - strength)
            val u0 = rotated(0, 0.0)
            val w0 = cross(tangents[0], u0)
            val l = len(upPerp)
            bx += lean * dot(upPerp, u0) / l; by += lean * dot(upPerp, w0) / l
        }
    }
    val theta = if (bx * bx + by * by > 1e-24) atan2(by, bx) else 0.0

    val normals = Array(n) { rotated(it, theta) }
    val binormals = Array(n) { cross(tangents[it], normals[it]) }
    return SweptFrames(p.toTypedArray(), tangents, normals, binormals, s, false, strength, totalLength(p))
}

/** How hard the `up` hint pulls on a line whose curvature gives no direction at all. */
private const val UP_LEAN = 0.05

private typealias D3 = DoubleArray

private fun sampleDense(line: FLine, count: Int): List<D3>? {
    if (line.nodes.size < 2) return null
    val c = line.cursor()
    val length = c.lengthD()
    if (!(length > 0f)) return null
    val out = ArrayList<D3>(count)
    for (i in 0 until count) {
        c.setD(length * i / (count - 1f))
        val v = c.position()
        val d = doubleArrayOf(v.x.toDouble(), v.y.toDouble(), v.z.toDouble())
        if (out.isEmpty() || len(sub(d, out[out.size - 1])) > 0.0) out.add(d)
    }
    return if (out.size >= 2) out else null
}

private fun totalLength(p: List<D3>): Double { var t = 0.0; for (i in 1 until p.size) t += len(sub(p[i], p[i - 1])); return t }

private fun arcFractions(p: List<D3>): DoubleArray {
    val s = DoubleArray(p.size)
    for (i in 1 until p.size) s[i] = s[i - 1] + len(sub(p[i], p[i - 1]))
    val total = s[p.size - 1]
    if (total > 0) for (i in s.indices) s[i] /= total
    s[p.size - 1] = 1.0
    return s
}

private fun anyPerpendicular(t: D3): D3 {
    val h = if (abs(t[0]) < 0.9) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(0.0, 1.0, 0.0)
    return normalize(cross(h, t))
}

private fun add(a: D3, b: D3) = doubleArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
private fun sub(a: D3, b: D3) = doubleArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
private fun scale(a: D3, k: Double) = doubleArrayOf(a[0] * k, a[1] * k, a[2] * k)
private fun dot(a: D3, b: D3) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
private fun cross(a: D3, b: D3) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
private fun len(a: D3) = sqrt(dot(a, a))
private fun normalize(a: D3): D3 { val l = len(a); return if (l > 0) scale(a, 1 / l) else a }
private fun lerp(a: D3, b: D3, t: Double) = doubleArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)
