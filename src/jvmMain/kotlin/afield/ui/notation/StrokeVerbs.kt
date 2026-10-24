package afield.ui.notation

import afield.document.properties.Delegation
import afield.geometry.path.ClassicPerlin2D
import afield.geometry.path.FLine
import afield.geometry.path.byAtomizing
import afield.geometry.path.byTransformingTo
import afield.geometry.path.byWiggling
import afield.geometry.path.catmullRomCentripetal
import afield.graphics.render.pointSize
import afield.graphics.render.pointed
import afield.graphics.render.stroked
import afield.math.linalg.Vec2
import afield.math.linalg.Vec4
import afield.math.linalg.vec
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The curve and drawing vocabulary a living stroke is written in: noise, resampling, curve
 * arithmetic, and the verbs that turn a list of points into a line.
 *
 * The verbs are methods of [StrokeVerbs], which [Stroke] extends, so inside an [eachStroke] block
 * they are plain calls. A curve is a `List<Vec2>`; `polyline`, `smooth`, `arcs` and `dots` turn one
 * into an [FLine], and the list of FLines a stroke's `draw { t -> }` returns is what it draws that
 * frame.
 *
 * The three properties this file adds to [FLine] and its nodes are read by the stroke layer rather
 * than by the verbs. `overshoot`, set on a line or on the node that ends a subpath, draws each open
 * end a short way past where it stops; `ignore` keeps a line out of what [Stroke.closestOutput]
 * searches.
 */

/**
 * How far past each end the stroke layer draws every subpath of this line, along its tangent, in the
 * line's own units: the sketchy overrun of a pen that does not stop exactly where it means to. `null`
 * (the default) draws none. Only the stroke layer ([eachStroke]) draws it.
 */
var FLine.overshoot by Delegation<Double?>()

/**
 * Overshoot for the subpath this node ends, overriding the line's [FLine.overshoot] at both of that
 * subpath's ends. A line of separate segments ([StrokeVerbs.atomize]) can so overrun each segment by
 * its own amount.
 */
var FLine.Node.overshoot by Delegation<Double?>()

/**
 * Leave this line out of what other strokes find with [Stroke.closestOutput]: guides, stitches and
 * other decoration a neighbouring stroke should not be drawn towards. `false` by default.
 */
var FLine.ignore by Delegation<Boolean> { false }

/**
 * The vocabulary a living stroke is written in ([eachStroke]): the madp line demo's noise, curve maths
 * and drawing verbs, under the demo's names.
 *
 * A **curve** is a `List<Vec2>`: the stroke's points, a resampled copy of them, the result of curve
 * maths. Drawing verbs turn curves into [FLine]s (`polyline`, `smooth`, `arcs`, `dots`) or work on
 * FLines directly (`atomize`, `wiggle`), and a stroke draws whatever list of FLines it returns. A line's
 * colour is its `color` (a node's `color` overrides it from that node on, segment by segment, which is
 * how a single line carries a gradient); `filled`, [FLine.overshoot] and [FLine.ignore] finish the
 * demo's grammar.
 *
 * Inherited by [Stroke], so inside `eachStroke { }` these are plain calls: `resample(points, 72)`.
 */
open class StrokeVerbs {

    // ── noise and waves ─────────────────────────────────────────────────────────────────────────

    /** Classic 2D Perlin noise, about -1..1, with the madp demos' permutation table. */
    fun perlin2D(x: Double, y: Double): Double = ClassicPerlin2D.noise(x, y)

    /** [v] clamped to 0..1. */
    fun clamp01(v: Double): Double = v.coerceIn(0.0, 1.0)

    /** A triangle wave of period 1 in -1..1: -1 at whole numbers, 1 half way between. */
    fun triWave(x: Double): Double = 1 - 4 * abs(x - floor(x) - 0.5)

    /** [triWave] in 0..1. */
    fun tri01(x: Double): Double = 0.5 + 0.5 * triWave(x)

    /** Noise that holds still and then jumps, [rate] times a second: broken, stepped motion. */
    fun heldNoise(seed: Double, t: Double, rate: Double): Double =
        perlin2D(seed * 0.173 + 11.7, floor(t * rate) * 0.193 + seed * 0.071)

    /** Noise that drifts smoothly through time at [rate]: breathing motion. */
    fun driftNoise(seed: Double, t: Double, rate: Double): Double =
        perlin2D(seed * 0.173 + t * rate, seed * 0.271 - t * rate * 0.7)

    // ── curves ──────────────────────────────────────────────────────────────────────────────────

    /** The length of [curve], along it. */
    fun length(curve: List<Vec2>): Double {
        var total = 0.0
        for (i in 1 until curve.size) total += dist(curve[i - 1], curve[i])
        return total
    }

    /** [curve] resampled to [n] points evenly spaced along it, ends kept. A curve too short to resample comes back as it is. */
    fun resample(curve: List<Vec2>, n: Int): List<Vec2> {
        if (curve.size < 2 || n < 2) return curve
        val d = DoubleArray(curve.size)
        for (i in 1 until curve.size) d[i] = d[i - 1] + dist(curve[i - 1], curve[i])
        val total = d.last()
        if (total < 1e-6) return curve
        val out = ArrayList<Vec2>(n)
        var seg = 0
        for (i in 0 until n) {
            val target = i.toDouble() / (n - 1) * total
            while (seg < curve.size - 2 && d[seg + 1] < target) seg++
            val span = d[seg + 1] - d[seg]
            val f = if (span > 0) ((target - d[seg]) / span).coerceIn(0.0, 1.0) else 0.0
            out += lerp(curve[seg], curve[seg + 1], f)
        }
        return out
    }

    /** A stroke's points, at their current positions, resampled to [n] points. */
    @JvmName("resamplePoints")
    fun resample(points: List<StrokePoint>, n: Int): List<Vec2> = resample(points.map { it.at }, n)

    /** Every point of [curve] moved by [fn]. */
    fun warp(curve: List<Vec2>, fn: (Vec2) -> Vec2): List<Vec2> = curve.map(fn)

    /** [curve] pushed about by Perlin noise: [amplitude] units, [frequency] cycles per unit, [phase] slides the field. */
    fun wiggle(curve: List<Vec2>, amplitude: Double = 10.0, frequency: Double = 0.05, phase: Double = 0.0): List<Vec2> =
        curve.map { p ->
            val x = p.x + perlin2D(p.x * frequency + phase, p.y * frequency + phase + 100) * amplitude
            val y = p.y + perlin2D(x * frequency + phase + 200, p.y * frequency + phase + 300) * amplitude
            vec(x, y)
        }

    /** Every node of [line] pushed about by Perlin noise, as [wiggle] does to a curve. */
    fun wiggle(line: FLine, amplitude: Double = 10.0, frequency: Double = 0.05, phase: Double = 0.0): FLine =
        line.byWiggling(amplitude, frequency, phase)

    /** [curve] moved, rotated and scaled as one shape so its first point lands on [start] and its last on [end]. */
    fun transformTo(curve: List<Vec2>, start: Vec2, end: Vec2): List<Vec2> {
        if (curve.size < 2) return curve
        val a = curve.first()
        val b = curve.last()
        val sLen = dist(a, b)
        if (sLen < 1e-6) return curve
        val scale = dist(start, end) / sLen
        val rot = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble()) - atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
        val c = cos(rot)
        val s = sin(rot)
        return curve.map { p ->
            val dx = (p.x - a.x).toDouble()
            val dy = (p.y - a.y).toDouble()
            vec(start.x + (dx * c - dy * s) * scale, start.y + (dx * s + dy * c) * scale)
        }
    }

    /** [line] moved, rotated and scaled so its first node lands on [start] and its last on [end]. */
    fun transformTo(line: FLine, start: Vec2, end: Vec2): FLine = line.byTransformingTo(start, end)

    /**
     * [a] displaced by [b]'s shape: both are resampled to their average point count, and each point of
     * [a] moves by how far the matching point of [b] is from [b]'s centroid.
     */
    fun add(a: List<Vec2>, b: List<Vec2>): List<Vec2> {
        val (ra, rb) = pair(a, b)
        val cb = centroid(rb)
        return ra.indices.map { vec(ra[it].x + rb[it].x - cb.x, ra[it].y + rb[it].y - cb.y) }
    }

    /** [a] displaced against [b]'s shape: [add] with [b]'s displacements reversed. */
    fun subtract(a: List<Vec2>, b: List<Vec2>): List<Vec2> {
        val (ra, rb) = pair(a, b)
        val cb = centroid(rb)
        return ra.indices.map { vec(ra[it].x - (rb[it].x - cb.x), ra[it].y - (rb[it].y - cb.y)) }
    }

    /**
     * Each point of [a], as a displacement from [a]'s centroid, projected onto the direction of the
     * matching point of [b] from [b]'s centroid: [a] squeezed into [b]'s directions.
     */
    fun project(a: List<Vec2>, b: List<Vec2>): List<Vec2> {
        val (ra, rb) = pair(a, b)
        val ca = centroid(ra)
        val cb = centroid(rb)
        return ra.indices.map {
            val dax = (ra[it].x - ca.x).toDouble()
            val day = (ra[it].y - ca.y).toDouble()
            val dbx = (rb[it].x - cb.x).toDouble()
            val dby = (rb[it].y - cb.y).toDouble()
            val lenSq = dbx * dbx + dby * dby
            val s = if (lenSq > 1e-10) (dax * dbx + day * dby) / lenSq else 0.0
            vec(ca.x + dbx * s, ca.y + dby * s)
        }
    }

    /** [curve] scaled by [factor] about its centroid. */
    fun scale(curve: List<Vec2>, factor: Double): List<Vec2> {
        if (curve.size < 2) return curve
        val c = centroid(curve)
        return curve.map { vec(c.x + (it.x - c.x) * factor, c.y + (it.y - c.y) * factor) }
    }

    /** [curve] scaled point by point by how far [by]'s matching point is from [by]'s centroid, relative to its mean radius. */
    fun scale(curve: List<Vec2>, by: List<Vec2>): List<Vec2> {
        if (curve.size < 2) return curve
        val (ra, rb) = pair(curve, by)
        val ca = centroid(ra)
        val cb = centroid(rb)
        val mean = rb.sumOf { dist(it, cb) } / rb.size
        val avg = if (mean > 0) mean else 1.0
        return ra.indices.map {
            val s = dist(rb[it], cb) / avg
            vec(ca.x + (ra[it].x - ca.x) * s, ca.y + (ra[it].y - ca.y) * s)
        }
    }

    /** The unit normal of [curve] at point [i], from its neighbours: the tangent turned a quarter turn. */
    fun normalAt(curve: List<Vec2>, i: Int): Vec2 {
        val t = tangentAt(curve, i)
        return vec(-t.y, t.x)
    }

    /** The unit tangent of [curve] at point [i], from its neighbours. */
    fun tangentAt(curve: List<Vec2>, i: Int): Vec2 {
        val prev = curve[max(0, i - 1)]
        val next = curve[min(curve.size - 1, i + 1)]
        val dx = (next.x - prev.x).toDouble()
        val dy = (next.y - prev.y).toDouble()
        val len = hypot(dx, dy).let { if (it > 0) it else 1.0 }
        return vec(dx / len, dy / len)
    }

    /** 0 at both ends of a curve of [n] points, 1 in the middle: `1 - |2i/(n-1) - 1|`. */
    fun envelope(i: Int, n: Int): Double = if (n < 2) 1.0 else 1 - abs(i.toDouble() / (n - 1) * 2 - 1)

    // ── drawing ─────────────────────────────────────────────────────────────────────────────────

    /** [curve] as straight segments. */
    fun polyline(curve: List<Vec2>): FLine = FLine().also { f ->
        curve.forEachIndexed { i, p -> if (i == 0) f.moveTo(p.x, p.y) else f.lineTo(p.x, p.y) }
    }

    /** [curve] as a smooth line through every point: a centripetal Catmull-Rom spline ([catmullRomCentripetal]). */
    fun smooth(curve: List<Vec2>): FLine = catmullRomCentripetal(curve)

    /**
     * [curve] as a chain of circular arcs: from its third point on, each point is reached along the
     * circle through it and the two points before it (a straight line where they are in a line).
     */
    fun arcs(curve: List<Vec2>, segmentsPerArc: Int = 10): FLine {
        val f = FLine()
        if (curve.isEmpty()) return f
        f.moveTo(curve[0].x, curve[0].y)
        if (curve.size > 1) f.lineTo(curve[1].x, curve[1].y)
        for (i in 2 until curve.size) {
            val a = curve[i - 2]
            val b = curve[i - 1]
            val c = curve[i]
            val circle = circumcircle(a, b, c)
            if (circle == null) {
                f.lineTo(c.x, c.y)
                continue
            }
            val (cx, cy, r) = circle
            val angB = atan2(b.y - cy, b.x - cx)
            var angC = atan2(c.y - cy, c.x - cx)
            val ccw = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x) > 0
            // The canvas sense: ccw draws decreasing angles in y-down coordinates.
            if (ccw) {
                while (angC > angB) angC -= 2 * Math.PI
            } else {
                while (angC < angB) angC += 2 * Math.PI
            }
            for (k in 1..segmentsPerArc) {
                val ang = angB + (angC - angB) * k / segmentsPerArc
                f.lineTo(cx + cos(ang) * r, cy + sin(ang) * r)
            }
        }
        return f
    }

    /** A dot of [radius] at every point of [curve]: an unstroked, [pointed] line. */
    fun dots(curve: List<Vec2>, radius: Double = 2.0): FLine = FLine().also { f ->
        f.pointed = true
        f.stroked = false
        f.pointSize = radius / 3
        curve.forEach { f.moveTo(it.x, it.y) }
    }

    /** A dot of [radius] at every node of [line], each keeping its node's colour. */
    fun dots(line: FLine, radius: Double = 2.0): FLine = FLine().also { f ->
        f.properties.putAll(line.properties)
        f.pointed = true
        f.stroked = false
        f.pointSize = radius / 3
        for (node in line.nodes) {
            val to = node.toOrNull ?: continue
            f.moveTo(to.x, to.y)
            (node.properties["color"] as? Vec4)?.let { f.here.properties["color"] = it }
        }
    }

    /** Adds a dot of [radius] at ([x], [y]) to this line, coloured [color] (the line's colour when null). Make the line [pointed] and unstroked to draw dots alone. */
    fun FLine.dot(x: Number, y: Number, radius: Double, color: Vec4? = null): FLine {
        moveTo(x, y)
        here.properties["pointSize"] = radius / 3
        if (color != null) here.properties["color"] = color
        return this
    }

    /** Adds the separate segment ([x0], [y0]) to ([x1], [y1]) to this line, coloured [color] when given. */
    fun FLine.segment(x0: Number, y0: Number, x1: Number, y1: Number, color: Vec4? = null): FLine {
        moveTo(x0, y0)
        lineTo(x1, y1)
        if (color != null) here.properties["color"] = color
        return this
    }

    /** [curve] as separate segments, one subpath per step between neighbouring points. */
    fun atomize(curve: List<Vec2>): FLine = polyline(curve).byAtomizing()

    /** [line] as separate segments ([byAtomizing]). */
    fun atomize(line: FLine): FLine = line.byAtomizing()

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private fun dist(a: Vec2, b: Vec2): Double = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())

    private fun lerp(a: Vec2, b: Vec2, f: Double): Vec2 = vec(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)

    private fun centroid(curve: List<Vec2>): Vec2 {
        var x = 0.0
        var y = 0.0
        for (p in curve) {
            x += p.x
            y += p.y
        }
        return vec(x / curve.size, y / curve.size)
    }

    private fun pair(a: List<Vec2>, b: List<Vec2>): Pair<List<Vec2>, List<Vec2>> {
        val n = max(2, Math.round((a.size + b.size) / 2.0).toInt())
        return resample(a, n) to resample(b, n)
    }

    private fun circumcircle(p1: Vec2, p2: Vec2, p3: Vec2): Triple<Double, Double, Double>? {
        val ax = p1.x.toDouble(); val ay = p1.y.toDouble()
        val bx = p2.x.toDouble(); val by = p2.y.toDouble()
        val cx = p3.x.toDouble(); val cy = p3.y.toDouble()
        val d = 2 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
        if (abs(d) < 1e-6) return null
        val ux = ((ax * ax + ay * ay) * (by - cy) + (bx * bx + by * by) * (cy - ay) + (cx * cx + cy * cy) * (ay - by)) / d
        val uy = ((ax * ax + ay * ay) * (cx - bx) + (bx * bx + by * by) * (ax - cx) + (cx * cx + cy * cy) * (bx - ax)) / d
        return Triple(ux, uy, sqrt((bx - ux) * (bx - ux) + (by - uy) * (by - uy)))
    }
}
