/**
 * String art: one thread wound around a ring of pins until its straight chords trace a shape.
 *
 * [stringArt] takes any [FLine] as the target, its outline (or, with `filled = true`, its inside),
 * and greedily picks the next pin from the current one, choosing the chord that covers the most
 * target not yet covered. Each chord drawn is subtracted from the target, so later chords go where
 * earlier ones have not. This is Petros Vrellis's "A new way to knit" algorithm, which Erik and
 * Martin Demaine used for their 1,500-string font: 200 pins on a circle, 1,500 wraps of a single
 * string, an outlined letter as the target. The pins sit on a circle around the shape by default,
 * or along any other line you pass as `frame`, such as a square, a blob, or the shape itself.
 *
 * ```kotlin
 * val m = layoutText("m", typeface("Helvetica", 400.0)).toFLine()
 * val art = m.stringArt(strings = 1500, pins = 200)
 * lines(art.chordLines() + art.pinDots(), w = 500.0, h = 500.0, fitToSize = true)
 * ```
 *
 * Tests: `StringArtTest`.
 */

package afield.geometry.path

import afield.graphics.render.color
import afield.graphics.render.pointSize
import afield.graphics.render.pointed
import afield.graphics.render.stroked
import afield.graphics.render.thickness
import afield.math.linalg.Vec3
import afield.math.linalg.Vec4
import afield.math.linalg.vec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The result of [stringArt]: where the pins are and the order the string visits them.
 *
 * @property pins the pin positions, in the target's coordinates.
 * @property path pin indices in the order the string visits them; chord `i` runs from
 *   `pins[path[i]]` to `pins[path[i + 1]]`.
 * @property target the line the string was fitted to.
 * @property remaining how much of the target the string left uncovered, from 0 (all of it covered)
 *   to 1 (none of it). It is measured on the raster the solver worked on.
 */
class StringArt internal constructor(
    val pins: List<Vec3>,
    val path: IntArray,
    val target: FLine,
    val remaining: Double,
) {
    /** How many chords the string has: one fewer than the pins it visits. */
    val strings: Int get() = max(0, path.size - 1)

    /**
     * The string as one [FLine], a single unbroken path from pin to pin.
     *
     * @param upTo draw only the first `upTo` chords, for watching it being wound.
     * @param color the string's colour; the default is translucent so crossings build up tone.
     * @param thickness the string's stroke width.
     */
    fun string(
        upTo: Int = strings,
        color: Vec4 = vec(1.0, 1.0, 1.0, 0.35),
        thickness: Double = 0.6,
    ): FLine {
        val f = FLine()
        val n = upTo.coerceIn(0, strings)
        if (path.isNotEmpty()) {
            f.moveTo(pins[path[0]])
            for (i in 1..n) f.lineTo(pins[path[i]])
        }
        f.color = Vec4(color)
        f.thickness = thickness
        return f
    }

    /**
     * The string as one [FLine] per chord, so that where chords cross they build up brightness.
     *
     * Draw this rather than [string] to see the picture. A single line is painted as one stroke with
     * one opacity, so its self-crossings never add up; separate lines composite over each other,
     * and `n` chords through a point give `1 - (1 - alpha)^n` of [color], nearly additive while
     * alpha is small.
     *
     * @param upTo draw only the first `upTo` chords.
     * @param color each chord's colour; keep alpha low, since density is the picture.
     * @param thickness each chord's stroke width.
     */
    fun chordLines(
        upTo: Int = strings,
        color: Vec4 = vec(1.0, 1.0, 1.0, 0.12),
        thickness: Double = 0.6,
    ): List<FLine> = (0 until upTo.coerceIn(0, strings)).map { i ->
        FLine().moveTo(pins[path[i]]).lineTo(pins[path[i + 1]]).also {
            it.color = Vec4(color)
            it.thickness = thickness
        }
    }

    /**
     * The pins as dots.
     *
     * @param size the dot size.
     * @param color the dot colour.
     */
    fun pinDots(size: Double = 0.6, color: Vec4 = vec(1.0, 0.55, 0.25, 0.9)): FLine {
        val f = FLine()
        pins.forEach { f.moveTo(it) }
        f.stroked = false
        f.pointed = true
        f.pointSize = size
        f.color = Vec4(color)
        return f
    }

    /** The chords as pairs of pin indices, in winding order. */
    fun chords(): List<Pair<Int, Int>> = (0 until strings).map { path[it] to path[it + 1] }

    override fun toString(): String =
        "StringArt(${pins.size} pins, $strings strings, ${"%.1f".format(remaining * 100)}% uncovered)"
}

/**
 * Wind one string around a ring of pins so that its chords trace this line.
 *
 * Each step looks at every chord from the current pin and takes the one that runs over the most
 * target the string has not yet covered (the Vrellis greedy step). It never takes the same chord
 * twice, and it skips pins closer than [minSkip] along the frame, whose chords would be too short
 * to show. When every chord from the current pin would only cross covered target or empty space it
 * still takes the least bad one, because a wound string has to go somewhere; the over-covered pixels
 * it leaves push later chords elsewhere. [stopWhenNoGain] stops there instead.
 *
 * The target is drawn into a [resolution]-pixel raster: this line's outline at [lineWidth], or,
 * with [filled], the area inside it (non-zero winding, so a glyph's counters stay empty). Each chord
 * then darkens the pixels it crosses by [stringWeight]; where the target is 1, about
 * `1 / stringWeight` chords are wanted.
 *
 * @param strings how many chords to wind.
 * @param pins how many pins.
 * @param frame where the pins go: they are spaced evenly by arc length along it. `null` puts them on
 *   a circle around this line's bounds, starting at the top.
 * @param resolution the raster's longer side, in pixels.
 * @param lineWidth the outline's width in this line's units; `null` is 1.2% of the pin ring's size.
 * @param filled fit the inside of this line rather than its outline.
 * @param stringWeight how much one chord covers, from 0 to 1, of a target pixel it passes.
 * @param background how much a chord is penalised for crossing empty space, per pixel, relative to
 *   the reward for crossing target. 0 lets chords cross empty space freely until covered target
 *   pushes them elsewhere.
 * @param lengthBias how a chord's score is divided by its length in pixels, as `sum / length^lengthBias`.
 *   1 (the default) scores the mean coverage along the chord, so a long chord tangent to an edge and
 *   a short one that crosses it compete fairly, and once the target is covered the string does not
 *   retreat into short chords along the rim. 0 scores the plain sum, which favours long chords.
 * @param stopWhenNoGain stop before [strings] once no chord from the current pin would add coverage.
 * @param minSkip the closest two pins can be, counted along the frame, and still be joined; `null`
 *   is a twentieth of [pins].
 */
fun FLine.stringArt(
    strings: Int = 1500,
    pins: Int = 200,
    frame: FLine? = null,
    resolution: Int = 400,
    lineWidth: Double? = null,
    filled: Boolean = false,
    stringWeight: Double = 0.2,
    background: Double = 0.5,
    minSkip: Int? = null,
    stopWhenNoGain: Boolean = false,
    lengthBias: Double = 1.0,
): StringArt {
    require(pins >= 3) { "stringArt needs at least 3 pins, got $pins" }
    require(strings >= 0) { "stringArt needs strings >= 0, got $strings" }
    require(resolution >= 16) { "stringArt needs resolution >= 16, got $resolution" }
    require(stringWeight > 0.0) { "stringArt needs stringWeight > 0, got $stringWeight" }

    val targetPolys = flattenForStringArt(this, segmentsPerCubic = 48)
    require(targetPolys.any { it.size >= 4 }) { "stringArt needs a target line with at least one segment" }

    // Pins.
    val tb = boundsOf(targetPolys)
    val closedFrame: Boolean
    val pinXY: DoubleArray
    if (frame == null) {
        val cx = (tb[0] + tb[2]) / 2
        val cy = (tb[1] + tb[3]) / 2
        val r = 0.5 * hypot(tb[2] - tb[0], tb[3] - tb[1]) * 1.06
        pinXY = DoubleArray(pins * 2) { k ->
            val a = -PI / 2 + 2 * PI * (k / 2) / pins
            if (k % 2 == 0) cx + r * cos(a) else cy + r * sin(a)
        }
        closedFrame = true
    } else {
        val framePolys = flattenForStringArt(frame, segmentsPerCubic = 64)
        val chain = framePolys.filter { it.size >= 4 }.flatMap { it.asIterable() }.toDoubleArray()
        require(chain.size >= 4) { "stringArt needs a frame with at least one segment" }
        closedFrame = hypot(chain[0] - chain[chain.size - 2], chain[1] - chain[chain.size - 1]) <
            1e-6 * max(1.0, hypot(tb[2] - tb[0], tb[3] - tb[1])) || frame.nodes.any { it is FLine.Close }
        pinXY = spacedAlong(chain, pins, closedFrame)
    }
    val skip = (minSkip ?: max(1, pins / 20)).coerceIn(1, pins / 2)

    // Raster covering pins and target.
    var minX = tb[0]; var minY = tb[1]; var maxX = tb[2]; var maxY = tb[3]
    for (i in 0 until pins) {
        minX = min(minX, pinXY[2 * i]); maxX = max(maxX, pinXY[2 * i])
        minY = min(minY, pinXY[2 * i + 1]); maxY = max(maxY, pinXY[2 * i + 1])
    }
    val span = max(maxX - minX, maxY - minY).coerceAtLeast(1e-9)
    val scale = (resolution - 1) / span
    val w = ((maxX - minX) * scale).toInt() + 1
    val h = ((maxY - minY) * scale).toInt() + 1
    val toPx = { x: Double -> (x - minX) * scale }
    val toPy = { y: Double -> (y - minY) * scale }

    val pxPolys = targetPolys.map { p -> DoubleArray(p.size) { i -> if (i % 2 == 0) toPx(p[i]) else toPy(p[i]) } }
    val target = FloatArray(w * h)
    if (filled) fillNonZero(pxPolys, target, w, h)
    else strokeInto(pxPolys, target, w, h, halfWidth = 0.5 * (lineWidth ?: (0.012 * span)) * scale)

    val bg = background.toFloat()
    val residual = FloatArray(w * h) { target[it] - bg * (1f - target[it]) }
    val targetTotal = target.sum().toDouble().coerceAtLeast(1e-9)

    val ppx = DoubleArray(pins) { toPx(pinXY[2 * it]) }
    val ppy = DoubleArray(pins) { toPy(pinXY[2 * it + 1]) }

    val bias = lengthBias.toFloat()
    fun score(a: Int, b: Int): Float {
        val dx = ppx[b] - ppx[a]
        val dy = ppy[b] - ppy[a]
        val n = max(1, ceil(max(abs(dx), abs(dy))).toInt())
        var s = 0f
        for (k in 0..n) {
            val x = (ppx[a] + dx * k / n).roundToInt().coerceIn(0, w - 1)
            val y = (ppy[a] + dy * k / n).roundToInt().coerceIn(0, h - 1)
            s += residual[y * w + x]
        }
        return if (bias == 0f) s else s / Math.pow((n + 1).toDouble(), bias.toDouble()).toFloat()
    }

    fun draw(a: Int, b: Int) {
        val dx = ppx[b] - ppx[a]
        val dy = ppy[b] - ppy[a]
        val n = max(1, ceil(max(abs(dx), abs(dy))).toInt())
        val wt = stringWeight.toFloat()
        for (k in 0..n) {
            val x = (ppx[a] + dx * k / n).roundToInt().coerceIn(0, w - 1)
            val y = (ppy[a] + dy * k / n).roundToInt().coerceIn(0, h - 1)
            residual[y * w + x] -= wt
        }
    }

    fun apart(a: Int, b: Int): Boolean {
        val d = abs(a - b)
        return (if (closedFrame) min(d, pins - d) else d) >= skip
    }

    val used = HashSet<Long>()
    fun key(a: Int, b: Int): Long = min(a, b).toLong() * pins + max(a, b)

    val path = ArrayList<Int>(strings + 1)
    if (strings > 0) {
        // Start on the best chord anywhere.
        var bestA = 0; var bestB = -1; var best = Float.NEGATIVE_INFINITY
        for (a in 0 until pins) for (b in a + 1 until pins) {
            if (!apart(a, b)) continue
            val s = score(a, b)
            if (s > best) { best = s; bestA = a; bestB = b }
        }
        if (bestB >= 0 && (best > 0f || !stopWhenNoGain)) {
            path.add(bestA); path.add(bestB)
            used.add(key(bestA, bestB)); draw(bestA, bestB)
            var cur = bestB
            while (path.size <= strings) {
                var next = -1; var nb = Float.NEGATIVE_INFINITY
                for (b in 0 until pins) {
                    if (b == cur || !apart(cur, b) || key(cur, b) in used) continue
                    val s = score(cur, b)
                    if (s > nb) { nb = s; next = b }
                }
                if (next < 0 || (stopWhenNoGain && nb <= 0f)) break
                path.add(next); used.add(key(cur, next)); draw(cur, next)
                cur = next
            }
        }
    }

    var left = 0.0
    for (i in target.indices) {
        if (target[i] <= 0f) continue
        val covered = (target[i] - bg * (1f - target[i]) - residual[i]).coerceIn(0f, target[i])
        left += target[i] - covered
    }

    val z = this.nodes.firstOrNull()?.storage?.firstOrNull()?.z ?: 0f
    val pinList = (0 until pins).map { Vec3(pinXY[2 * it].toFloat(), pinXY[2 * it + 1].toFloat(), z) }
    return StringArt(pinList, path.toIntArray(), this, left / targetTotal)
}

/** Each subpath of [line] as interleaved x,y doubles, curves cut into [segmentsPerCubic] pieces. */
internal fun flattenForStringArt(line: FLine, segmentsPerCubic: Int): List<DoubleArray> {
    val out = ArrayList<DoubleArray>()
    var cur = ArrayList<Double>()
    var startX = 0.0; var startY = 0.0
    var lastX = 0.0; var lastY = 0.0
    fun flush() {
        if (cur.size >= 4) out.add(cur.toDoubleArray())
        cur = ArrayList()
    }
    fun add(x: Double, y: Double) { cur.add(x); cur.add(y); lastX = x; lastY = y }
    for (node in line.nodes) {
        when (node) {
            is FLine.MoveTo -> {
                flush()
                startX = node.storage[0].x.toDouble(); startY = node.storage[0].y.toDouble()
                add(startX, startY)
            }
            is FLine.CubicTo -> {
                if (cur.isEmpty()) add(lastX, lastY)
                val x0 = lastX; val y0 = lastY
                val e = node.storage[0]; val c1 = node.storage[1]; val c2 = node.storage[2]
                for (i in 1..segmentsPerCubic) {
                    val t = i.toDouble() / segmentsPerCubic
                    val u = 1 - t
                    val b0 = u * u * u; val b1 = 3 * u * u * t; val b2 = 3 * u * t * t; val b3 = t * t * t
                    add(
                        b0 * x0 + b1 * c1.x + b2 * c2.x + b3 * e.x,
                        b0 * y0 + b1 * c1.y + b2 * c2.y + b3 * e.y,
                    )
                }
            }
            is FLine.Close -> if (cur.size >= 2) add(startX, startY)
            else -> if (node.storage.isNotEmpty()) {
                if (cur.isEmpty()) { startX = node.storage[0].x.toDouble(); startY = node.storage[0].y.toDouble() }
                add(node.storage[0].x.toDouble(), node.storage[0].y.toDouble())
            }
        }
    }
    flush()
    return out
}

private fun boundsOf(polys: List<DoubleArray>): DoubleArray {
    val b = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
    for (p in polys) for (i in p.indices step 2) {
        b[0] = min(b[0], p[i]); b[1] = min(b[1], p[i + 1])
        b[2] = max(b[2], p[i]); b[3] = max(b[3], p[i + 1])
    }
    return b
}

/** [count] points evenly spaced by arc length along the polyline [xy]; a closed one wraps around. */
private fun spacedAlong(xy: DoubleArray, count: Int, closed: Boolean): DoubleArray {
    val n = xy.size / 2
    val cum = DoubleArray(n)
    for (i in 1 until n) cum[i] = cum[i - 1] + hypot(xy[2 * i] - xy[2 * i - 2], xy[2 * i + 1] - xy[2 * i - 1])
    val total = cum[n - 1]
    val out = DoubleArray(count * 2)
    var seg = 1
    for (k in 0 until count) {
        val s = if (closed) total * k / count else total * k / max(1, count - 1)
        while (seg < n - 1 && cum[seg] < s) seg++
        val l = cum[seg] - cum[seg - 1]
        val t = if (l > 0) ((s - cum[seg - 1]) / l).coerceIn(0.0, 1.0) else 0.0
        out[2 * k] = xy[2 * seg - 2] + t * (xy[2 * seg] - xy[2 * seg - 2])
        out[2 * k + 1] = xy[2 * seg - 1] + t * (xy[2 * seg + 1] - xy[2 * seg - 1])
    }
    return out
}

/** Max-composite an antialiased stroke of every segment, [halfWidth] pixels either side, into [img]. */
private fun strokeInto(polys: List<DoubleArray>, img: FloatArray, w: Int, h: Int, halfWidth: Double) {
    val reach = halfWidth + 1.0
    for (p in polys) for (i in 0 until p.size / 2 - 1) {
        val ax = p[2 * i]; val ay = p[2 * i + 1]; val bx = p[2 * i + 2]; val by = p[2 * i + 3]
        val x0 = max(0, (min(ax, bx) - reach).toInt()); val x1 = min(w - 1, ceil(max(ax, bx) + reach).toInt())
        val y0 = max(0, (min(ay, by) - reach).toInt()); val y1 = min(h - 1, ceil(max(ay, by) + reach).toInt())
        val dx = bx - ax; val dy = by - ay
        val ll = dx * dx + dy * dy
        for (y in y0..y1) for (x in x0..x1) {
            val t = if (ll > 0) (((x - ax) * dx + (y - ay) * dy) / ll).coerceIn(0.0, 1.0) else 0.0
            val d = hypot(x - (ax + t * dx), y - (ay + t * dy))
            val v = (halfWidth + 0.5 - d).coerceIn(0.0, 1.0).toFloat()
            val idx = y * w + x
            if (v > img[idx]) img[idx] = v
        }
    }
}

/** Fill the inside of [polys] (each treated as closed, non-zero winding) into [img] with 1. */
private fun fillNonZero(polys: List<DoubleArray>, img: FloatArray, w: Int, h: Int) {
    val xs = ArrayList<Pair<Double, Int>>()
    for (y in 0 until h) {
        xs.clear()
        val sy = y.toDouble()
        for (p in polys) {
            val n = p.size / 2
            for (i in 0 until n) {
                val j = (i + 1) % n
                val ay = p[2 * i + 1]; val by = p[2 * j + 1]
                if (ay == by) continue
                val up = ay < by
                if ((up && sy >= ay && sy < by) || (!up && sy >= by && sy < ay)) {
                    val ax = p[2 * i]; val bx = p[2 * j]
                    xs.add((ax + (sy - ay) / (by - ay) * (bx - ax)) to (if (up) 1 else -1))
                }
            }
        }
        if (xs.isEmpty()) continue
        xs.sortBy { it.first }
        var wind = 0
        for (k in 0 until xs.size - 1) {
            wind += xs[k].second
            if (wind == 0) continue
            val from = max(0, ceil(xs[k].first).toInt())
            val to = min(w - 1, xs[k + 1].first.toInt())
            for (x in from..to) img[y * w + x] = 1f
        }
    }
}
