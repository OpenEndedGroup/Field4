package afield.media.image

import afield.geometry.path.FLine
import afield.math.linalg.Vec3
import afield.math.linalg.Vec4
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Drawing [FLine]s into an [Img], in software and without AWT.
 *
 * [Img.drawFLines] is the whole surface. It resamples the image up by a scale factor and blends
 * each line over it, reading `color`, `thickness`, `pointed`, `filled` and the rest from the line's
 * own properties the way Field draws it on the canvas; round-capped segments and even-odd fills are
 * rasterised here, each with a pixel of antialiasing. What comes back is another [Img], so an
 * overlay costs no GPU, no window and no AWT font or toolkit, and `showImgGrid` can show it
 * wherever a box runs.
 */

/**
 * This image as RGB with [lines] drawn over it, antialiased and without AWT. Line coordinates are
 * in this image's pixels; the result is [scale] times larger, so thin curves over a small image stay
 * crisp.
 *
 * Each line is drawn in its `color` property (a [Vec4] with alpha) at its `thickness` (in output
 * pixels), or [color] and [thickness] when it has none. A line with `pointed = true` is drawn as
 * dots of `pointSize`, coloured per node by each node's own `color`, as Field draws it on the canvas. A
 * line with `filled = true` is filled in its `fill` colour (even-odd), and outlined too unless
 * `stroked = false`.
 *
 * ```
 * showImgGrid("separatrices" to photo.drawFLines(result.toFLines() + result.criticalPointsFLine(), scale = 2.0))
 * ```
 */
fun Img.drawFLines(
    lines: List<FLine>,
    scale: Double = 1.0,
    color: Vec4 = Vec4(1f, 0.3f, 0.1f, 1f),
    thickness: Double = 1.5,
): Img {
    require(scale > 0) { "scale must be positive, got $scale" }
    val w = ceil(width * scale).toInt()
    val h = ceil(height * scale).toInt()
    val rgb = FloatArray(w * h * 3)
    for (y in 0 until h) for (x in 0 until w) {
        val sx = ((x + 0.5) / scale - 0.5).coerceIn(0.0, width - 1.0)
        val sy = ((y + 0.5) / scale - 0.5).coerceIn(0.0, height - 1.0)
        for (c in 0 until 3) rgb[(y * w + x) * 3 + c] = sampleBilinear(sx, sy, if (channels >= 3) c else 0)
    }
    val out = Img(w, h, 3, rgb)
    lines.forEach { line ->
        if (line.properties["pointed"] == true) {
            val size = (line.properties["pointSize"] as? Number)?.toDouble() ?: 4.0
            var c = (line.properties["color"] as? Vec4) ?: color
            line.nodes.forEach { n ->
                c = (n.properties["color"] as? Vec4) ?: c
                val p = n.toOrNull ?: return@forEach
                out.stampCapsule(p.x * scale, p.y * scale, p.x * scale, p.y * scale, size / 2, c)
            }
        } else if (line.properties["filled"] == true) {
            val c = (line.properties["fill"] as? Vec4) ?: (line.properties["color"] as? Vec4) ?: color
            out.fillPolygons(line.polylines().map { poly -> poly.map { Vec3(it.x * scale.toFloat(), it.y * scale.toFloat(), 0f) } }, c)
            if (line.properties["stroked"] != false) {
                val half = ((line.properties["thickness"] as? Number)?.toDouble() ?: thickness) / 2
                line.polylines().forEach { poly -> for (i in 1 until poly.size) out.stampCapsule(poly[i - 1].x * scale, poly[i - 1].y * scale, poly[i].x * scale, poly[i].y * scale, half, c) }
            }
        } else {
            val c = (line.properties["color"] as? Vec4) ?: color
            val half = ((line.properties["thickness"] as? Number)?.toDouble() ?: thickness) / 2
            line.polylines().forEach { poly ->
                for (i in 1 until poly.size) {
                    val a = poly[i - 1]; val b = poly[i]
                    out.stampCapsule(a.x * scale, a.y * scale, b.x * scale, b.y * scale, half, c)
                }
            }
        }
    }
    return out
}

/** The subpaths of this line as point lists, with cubics flattened into 12 segments each. */
private fun FLine.polylines(): List<List<Vec3>> {
    val out = mutableListOf<MutableList<Vec3>>()
    nodes.forEach { n ->
        when (n) {
            is FLine.MoveTo -> out.add(mutableListOf(n.to))
            is FLine.CubicTo -> {
                val cur = out.lastOrNull() ?: mutableListOf<Vec3>().also { out.add(it) }
                val p0 = cur.lastOrNull() ?: n.to
                val c1 = n.storage[1]; val c2 = n.storage[2]; val p3 = n.storage[0]
                for (k in 1..12) {
                    val t = k / 12f; val u = 1 - t
                    val b0 = u * u * u; val b1 = 3 * u * u * t; val b2 = 3 * u * t * t; val b3 = t * t * t
                    cur.add(Vec3(b0 * p0.x + b1 * c1.x + b2 * c2.x + b3 * p3.x, b0 * p0.y + b1 * c1.y + b2 * c2.y + b3 * p3.y, 0f))
                }
            }
            is FLine.LineTo -> (out.lastOrNull() ?: mutableListOf<Vec3>().also { out.add(it) }).add(n.to)
            // back to the subpath's first point; an interior Close keeps the same list, as drawing does
            is FLine.Close -> out.lastOrNull()?.let { cur -> cur.firstOrNull()?.let { cur.add(it) } }
        }
    }
    return out
}

/** Blends a round-capped segment of radius [r] into this RGB image, with one-pixel antialiasing. */
private fun Img.stampCapsule(ax: Double, ay: Double, bx: Double, by: Double, r: Double, c: Vec4) {
    val x0 = max(0, floor(min(ax, bx) - r - 1).toInt()); val x1 = min(width - 1, ceil(max(ax, bx) + r + 1).toInt())
    val y0 = max(0, floor(min(ay, by) - r - 1).toInt()); val y1 = min(height - 1, ceil(max(ay, by) + r + 1).toInt())
    val dx = bx - ax; val dy = by - ay
    val len2 = dx * dx + dy * dy
    for (y in y0..y1) for (x in x0..x1) {
        val px = x + 0.5 - ax; val py = y + 0.5 - ay
        val t = if (len2 == 0.0) 0.0 else ((px * dx + py * dy) / len2).coerceIn(0.0, 1.0)
        val ex = px - t * dx; val ey = py - t * dy
        val coverage = (r + 0.5 - sqrt(ex * ex + ey * ey)).coerceIn(0.0, 1.0) * c.w
        if (coverage <= 0) continue
        val i = (y * width + x) * 3
        data[i] += ((c.x - data[i]) * coverage).toFloat()
        data[i + 1] += ((c.y - data[i + 1]) * coverage).toFloat()
        data[i + 2] += ((c.z - data[i + 2]) * coverage).toFloat()
    }
}

/** Blends the even-odd interior of [polys] into this RGB image, sampling each pixel at its centre. */
private fun Img.fillPolygons(polys: List<List<Vec3>>, c: Vec4) {
    val all = polys.flatten()
    if (all.isEmpty()) return
    val y0 = max(0, floor(all.minOf { it.y }.toDouble()).toInt())
    val y1 = min(height - 1, ceil(all.maxOf { it.y }.toDouble()).toInt())
    val xs = ArrayList<Double>()
    for (y in y0..y1) {
        val sy = y + 0.5
        xs.clear()
        polys.forEach { poly ->
            for (i in poly.indices) {
                val a = poly[i]; val b = poly[(i + 1) % poly.size]
                if ((a.y <= sy) != (b.y <= sy)) xs.add(a.x + (sy - a.y) / (b.y - a.y) * (b.x - a.x))
            }
        }
        xs.sort()
        for (k in 0 until xs.size - 1 step 2) {
            for (x in max(0, ceil(xs[k] - 0.5).toInt())..min(width - 1, floor(xs[k + 1] - 0.5).toInt())) {
                val i = (y * width + x) * 3
                data[i] += ((c.x - data[i]) * c.w)
                data[i + 1] += ((c.y - data[i + 1]) * c.w)
                data[i + 2] += ((c.z - data[i + 2]) * c.w)
            }
        }
    }
}
