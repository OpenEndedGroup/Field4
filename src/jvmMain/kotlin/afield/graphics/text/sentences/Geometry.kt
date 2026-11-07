package afield.graphics.text.sentences

import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Layout works in doubles and in its own point type rather than [afield.math.linalg.Vec2],
 * so the geometry here stays independent of the drawing stack (and testable without
 * it).  [Flines] converts to Vec2/FLine at the boundary.
 *
 * y increases *downward* throughout, as in SVG: the constituency tree is at small y,
 * the words below it, the dependency arcs below that.  If your drawing surface is
 * y-up, flip once at the end -- see [Flines.toFLine].
 */
data class P(val x: Double, val y: Double) {
    operator fun plus(o: P) = P(x + o.x, y + o.y)
    operator fun minus(o: P) = P(x - o.x, y - o.y)
    operator fun times(s: Double) = P(x * s, y * s)

    fun lerp(o: P, t: Double) = P(x + (o.x - x) * t, y + (o.y - y) * t)

    val length: Double get() = sqrt(x * x + y * y)

    /** Angle in degrees, measuring clockwise from +x because y points down. */
    val angle: Double get() = Math.toDegrees(atan2(y, x))
}

data class Rect(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    val width: Double get() = x1 - x0
    val height: Double get() = y1 - y0
    val centre: P get() = P((x0 + x1) / 2, (y0 + y1) / 2)

    fun expand(m: Double) = Rect(x0 - m, y0 - m, x1 + m, y1 + m)

    /** Expand downward only -- clearance below a piece of text, not around it. */
    fun expandDown(m: Double) = Rect(x0, y0, x1, y1 + m)

    fun union(o: Rect) = Rect(min(x0, o.x0), min(y0, o.y0), max(x1, o.x1), max(y1, o.y1))

    operator fun contains(p: P) = p.x >= x0 && p.x <= x1 && p.y >= y0 && p.y <= y1

    fun intersects(o: Rect) = x0 <= o.x1 && x1 >= o.x0 && y0 <= o.y1 && y1 >= o.y0

    companion object {
        val EMPTY = Rect(0.0, 0.0, 0.0, 0.0)

        /**
         * Bounds of a rotated line of text that *starts* at [at] and runs [width] along
         * [angleDegrees] -- the way a word set along a slant does.
         *
         * The perpendicular extent is the point of it: a 16pt line on a 45 degree slant reaches
         * about 11pt either side of the line it sits on, and reserving only the line's own length
         * is what lets two nearly-parallel slants collide.
         */
        fun textFromStart(at: P, width: Double, height: Double, angleDegrees: Double): Rect {
            val r = Math.toRadians(angleDegrees)
            val cs = Math.cos(r)
            val sn = Math.sin(r)
            val top = -height * 0.78
            val bottom = height * 0.22
            val corners = listOf(
                P(0.0, top), P(width, top), P(width, bottom), P(0.0, bottom),
            ).map { P(at.x + it.x * cs - it.y * sn, at.y + it.x * sn + it.y * cs) }
            return around(corners)
        }

        /**
         * Axis-aligned bounds of a rotated line of text.
         *
         * [centre] is the text's baseline midpoint, so the box sits mostly above it, the way
         * set type does.
         */
        fun rotatedText(centre: P, width: Double, height: Double, angleDegrees: Double): Rect {
            val r = Math.toRadians(angleDegrees)
            val cs = Math.cos(r)
            val sn = Math.sin(r)
            val hw = width / 2
            val top = -height * 0.78
            val bottom = height * 0.22
            val corners = listOf(
                P(-hw, top), P(hw, top), P(hw, bottom), P(-hw, bottom),
            ).map { P(centre.x + it.x * cs - it.y * sn, centre.y + it.x * sn + it.y * cs) }
            return around(corners)
        }

        fun around(points: Iterable<P>): Rect {
            val it = points.iterator()
            if (!it.hasNext()) return EMPTY
            var first = it.next()
            var r = Rect(first.x, first.y, first.x, first.y)
            while (it.hasNext()) {
                val p = it.next()
                r = Rect(min(r.x0, p.x), min(r.y0, p.y), max(r.x1, p.x), max(r.y1, p.y))
            }
            return r
        }

        fun union(rects: Iterable<Rect>): Rect =
            rects.fold(null as Rect?) { acc, r -> acc?.union(r) ?: r } ?: EMPTY
    }
}

/** A cubic Bezier -- every curve this package emits is one of these. */
data class Cubic(val a: P, val c1: P, val c2: P, val b: P) {

    fun pointAt(t: Double): P {
        val u = 1 - t
        val w0 = u * u * u
        val w1 = 3 * u * u * t
        val w2 = 3 * u * t * t
        val w3 = t * t * t
        return P(
            w0 * a.x + w1 * c1.x + w2 * c2.x + w3 * b.x,
            w0 * a.y + w1 * c1.y + w2 * c2.y + w3 * b.y,
        )
    }

    fun tangentAt(t: Double): P {
        val u = 1 - t
        val d = P(
            3 * u * u * (c1.x - a.x) + 6 * u * t * (c2.x - c1.x) + 3 * t * t * (b.x - c2.x),
            3 * u * u * (c1.y - a.y) + 6 * u * t * (c2.y - c1.y) + 3 * t * t * (b.y - c2.y),
        )
        // Degenerate control points can flatten the derivative to nothing; fall back to
        // the chord so callers always get a usable direction to rotate text along.
        return if (d.length < 1e-9) (b - a) else d
    }

    /** Tangent direction at *t* in degrees, for rotating a label along the curve. */
    fun angleAt(t: Double): Double = tangentAt(t).angle

    /** The point furthest down the page -- where an arc label naturally wants to sit. */
    fun lowestPoint(samples: Int = 64): P {
        var best = a
        for (i in 0..samples) {
            val p = pointAt(i.toDouble() / samples)
            if (p.y > best.y) best = p
        }
        return best
    }

    fun bounds(samples: Int = 32): Rect =
        Rect.around((0..samples).map { pointAt(it.toDouble() / samples) })
}
