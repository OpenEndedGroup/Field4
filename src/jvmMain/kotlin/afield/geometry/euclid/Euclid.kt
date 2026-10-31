package afield.geometry.euclid

/**
 * Plane geometry in the classical sense: points, lines, rays, segments, circles, arcs and polygons,
 * with every pairwise intersection, closest point and distance, and the standard constructions
 * (perpendicular bisectors, angle bisectors, circumcircles, incircles, tangents).
 *
 * ```kotlin
 * val c = Circle2(point2(0, 0), 50.0)
 * val s = Segment2(point2(-80, 10), point2(80, 30))
 * val hits = s.intersect(c).points           // the two places the segment crosses the circle
 * val (onS, onC) = s.closestPoints(Circle2(point2(0, 200), 20.0))
 * lines(c.toFLine(), s.toFLine())
 * ```
 *
 * Everything is immutable and computed in `Double`. `Vec2` converts in and out with
 * `Vec2.toPoint2()` / `Point2.toVec2()`. Every shape implements [Shape2], so `intersect`,
 * `closestPoints` and `distanceTo` work for any pair, including a shape with itself.
 *
 * Robustness: comparisons use a distance tolerance of [Euclid.epsilon] times the size of the inputs
 * (one plus the largest coordinate involved, and any radius), so tangencies, touching endpoints and
 * collinear or co-circular overlaps are reported as such rather than lost to rounding.
 * Overlaps come back as shapes in [Intersection2.overlaps] (a shared piece of a line or of a
 * circle), not as a single point. Degenerate inputs are treated as what they are: a zero-length
 * segment or zero-radius circle behaves as a point.
 *
 * Arcs run from [Arc2.start] through a signed [Arc2.sweep] in radians: positive is
 * counter-clockwise in a y-up frame (clockwise on a y-down screen).
 */

import afield.geometry.path.FLine
import afield.math.linalg.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Tolerance settings shared by every Euclid computation. */
object Euclid {
    /** Relative distance tolerance; scaled by the magnitude of the inputs of each computation. */
    var epsilon = 1e-9
}

private const val TAU = 2 * PI

/** A point (or vector) in the plane. */
data class Point2(val x: Double, val y: Double) : Shape2 {
    operator fun plus(o: Point2) = Point2(x + o.x, y + o.y)
    operator fun minus(o: Point2) = Point2(x - o.x, y - o.y)
    operator fun times(s: Double) = Point2(x * s, y * s)
    operator fun div(s: Double) = Point2(x / s, y / s)
    operator fun unaryMinus() = Point2(-x, -y)

    infix fun dot(o: Point2) = x * o.x + y * o.y

    /** The z component of the 3D cross product: positive when [o] is counter-clockwise of this. */
    infix fun cross(o: Point2) = x * o.y - y * o.x

    val length get() = hypot(x, y)
    val lengthSquared get() = x * x + y * y

    /** Angle of this vector from the +x axis, in (-π, π]. */
    val angle get() = atan2(y, x)

    /** This vector rotated a quarter turn counter-clockwise. */
    val perp get() = Point2(-y, x)

    /** Unit vector in this direction; the zero vector stays zero. */
    fun normalized(): Point2 = length.let { if (it == 0.0) this else this / it }

    /** This point turned [radians] counter-clockwise (in a y-up frame) about [about]. */
    fun rotated(radians: Double, about: Point2 = ORIGIN): Point2 {
        val c = cos(radians); val s = sin(radians)
        val dx = x - about.x; val dy = y - about.y
        return Point2(about.x + c * dx - s * dy, about.y + s * dx + c * dy)
    }

    /** The point [t] of the way from this to [o]. */
    fun lerp(o: Point2, t: Double) = Point2(x + (o.x - x) * t, y + (o.y - y) * t)

    fun midpoint(o: Point2) = lerp(o, 0.5)

    /** This point as a [Vec2], in `Float`. */
    fun toVec2() = Vec2(x.toFloat(), y.toFloat())

    override fun toFLine(extent: Double): FLine =
        FLine().moveTo(x, y).lineTo(x, y)

    companion object {
        val ORIGIN = Point2(0.0, 0.0)

        /** The point at [radius] from [center] in direction [angle]. */
        fun polar(radius: Double, angle: Double, center: Point2 = ORIGIN) =
            Point2(center.x + radius * cos(angle), center.y + radius * sin(angle))
    }
}

/** A [Point2] from any numbers. */
fun point2(x: Number, y: Number) = Point2(x.toDouble(), y.toDouble())

/** This vector as a [Point2], in `Double`. */
fun Vec2.toPoint2() = Point2(x.toDouble(), y.toDouble())

/**
 * Anything Euclid can intersect and measure against anything else: [Point2], [Line2], [Ray2],
 * [Segment2], [Circle2], [Arc2] and [Polygon2] (which is measured by its boundary).
 */
sealed interface Shape2 {
    /** Where this meets [other]: crossing and touching points, plus any shared stretch. */
    fun intersect(other: Shape2): Intersection2 =
        if (this is Polygon2 || other is Polygon2) intersectPieces(this, other) else intersectPrims(prim(this), prim(other))

    /** True when this and [other] share at least one point. */
    fun intersects(other: Shape2): Boolean = !intersect(other).isEmpty

    /** The point of this shape nearest [p]. */
    fun closestPoint(p: Point2): Point2 =
        if (this is Polygon2) edges.map { it.closestPoint(p) }.minBy { (it - p).lengthSquared } else closestOn(prim(this), p)

    /** A nearest pair of points, the first on this shape and the second on [other]. */
    fun closestPoints(other: Shape2): Pair<Point2, Point2> =
        if (this is Polygon2 || other is Polygon2) {
            pieces(this).flatMap { a -> pieces(other).map { b -> a.closestPoints(b) } }.minBy { (a, b) -> (b - a).lengthSquared }
        } else closestPair(prim(this), prim(other))

    /** The shortest distance between this and [other]; zero when they meet. */
    fun distanceTo(other: Shape2): Double = closestPoints(other).let { (a, b) -> (b - a).length }

    /** An FLine drawing of this shape; unbounded lines and rays are drawn [extent] long each way. */
    fun toFLine(extent: Double = 1000.0): FLine
}

/** The infinite line through `point` in `direction` (any nonzero length). */
data class Line2(val point: Point2, val direction: Point2) : Shape2 {
    /** `point` plus [t] times [direction], so [t] counts whole direction vectors rather than distance. */
    fun at(t: Double) = point + direction * t

    /** Distance to [p], positive on the left of [direction] (counter-clockwise side). */
    fun signedDistance(p: Point2) = (direction cross (p - point)) / direction.length

    /** +1 left of the line, -1 right, 0 on it (within tolerance). */
    fun side(p: Point2): Int {
        val d = signedDistance(p)
        return if (abs(d) <= tol(point, p)) 0 else if (d > 0) 1 else -1
    }

    /** The foot of the perpendicular from [p]; same as [closestPoint]. */
    fun project(p: Point2) = at(((p - point) dot direction) / direction.lengthSquared)

    /** [p] mirrored across this line. */
    fun reflect(p: Point2) = project(p) * 2.0 - p

    fun perpendicularThrough(p: Point2) = Line2(p, direction.perp)
    fun parallelThrough(p: Point2) = Line2(p, direction)

    /** The line moved [distance] to its left (negative moves it right). */
    fun offset(distance: Double) = Line2(point + direction.normalized().perp * distance, direction)

    override fun toFLine(extent: Double): FLine {
        val u = direction.normalized() * extent
        val a = point - u; val b = point + u
        return FLine().moveTo(a.x, a.y).lineTo(b.x, b.y)
    }

    companion object {
        fun through(a: Point2, b: Point2) = Line2(a, b - a)

        /** The line of points equidistant from [a] and [b]. */
        fun perpendicularBisector(a: Point2, b: Point2) = Line2(a.midpoint(b), (b - a).perp)

        /**
         * The two bisectors of the angles between [l1] and [l2], or the midline when they are
         * parallel, or nothing when they coincide.
         */
        fun angleBisectors(l1: Line2, l2: Line2): List<Line2> {
            val u1 = l1.direction.normalized(); val u2 = l2.direction.normalized()
            val meet = intersectPrims(prim(l1), prim(l2))
            val p = meet.points.firstOrNull()
            if (p == null) {
                if (meet.overlaps.isNotEmpty()) return emptyList()
                return listOf(Line2(l1.point.midpoint(l2.project(l1.point)), u1))
            }
            return listOf(Line2(p, u1 + u2), Line2(p, u1 - u2))
        }
    }
}

/** The half-line from [origin] in [direction]. */
data class Ray2(val origin: Point2, val direction: Point2) : Shape2 {
    /** [origin] plus [t] times [direction]; the ray itself is [t] of zero and above. */
    fun at(t: Double) = origin + direction * t
    val line get() = Line2(origin, direction)

    override fun toFLine(extent: Double): FLine {
        val b = origin + direction.normalized() * extent
        return FLine().moveTo(origin.x, origin.y).lineTo(b.x, b.y)
    }
}

/** The finite segment from [a] to [b]. */
data class Segment2(val a: Point2, val b: Point2) : Shape2 {
    /** The point [t] of the way from [a] to [b]; the segment itself is [t] in 0..1. */
    fun at(t: Double) = a.lerp(b, t)
    val length get() = (b - a).length
    val midpoint get() = a.midpoint(b)
    val direction get() = b - a
    val line get() = Line2(a, b - a)
    val perpendicularBisector get() = Line2.perpendicularBisector(a, b)
    fun reversed() = Segment2(b, a)

    override fun toFLine(extent: Double): FLine = FLine().moveTo(a.x, a.y).lineTo(b.x, b.y)
}

/** The circle of [radius] about [center]. */
data class Circle2(val center: Point2, val radius: Double) : Shape2 {
    fun at(angle: Double) = Point2.polar(radius, angle, center)
    val area get() = PI * radius * radius
    val circumference get() = TAU * radius

    /** True when [p] is inside or on the circle. */
    operator fun contains(p: Point2) = (p - center).length <= radius + tol(center, p, radius)

    /** The points where the two tangent lines from [p] touch this circle (one if [p] is on it). */
    fun tangentPointsFrom(p: Point2): List<Point2> {
        val v = p - center
        val d = v.length
        val e = tol(center, p, radius)
        if (d < radius - e) return emptyList()
        if (d <= radius + e) return listOf(p)
        val alpha = acos(radius / d)
        return listOf(at(v.angle + alpha), at(v.angle - alpha))
    }

    /** The tangent lines from [p]; the single tangent at [p] when it lies on the circle. */
    fun tangentLinesFrom(p: Point2): List<Line2> = tangentPointsFrom(p).map { t ->
        if ((t - p).length <= tol(center, p, radius)) Line2(t, (t - center).perp) else Line2.through(p, t)
    }

    /** The tangent line at [angle]. */
    fun tangentAt(angle: Double) = Line2(at(angle), Point2(-sin(angle), cos(angle)))

    /**
     * Every line tangent to both circles (up to four: two outer, two inner), each as the segment
     * from its touching point on this circle to its touching point on [other]. Where the circles
     * touch, the shared tangent there comes back as a zero-length segment.
     */
    fun commonTangents(other: Circle2): List<Segment2> {
        val d = other.center - center
        val z = d.lengthSquared
        val e = tol(center, other.center, radius + other.radius)
        if (z <= e * e) return emptyList()
        val out = mutableListOf<Segment2>()
        for (s1 in doubleArrayOf(1.0, -1.0)) for (s2 in doubleArrayOf(1.0, -1.0)) for (root in doubleArrayOf(1.0, -1.0)) {
            val r1 = radius * s1; val r2 = other.radius * s2
            val r = r2 - r1
            var h = z - r * r
            if (h < -2 * e * sqrt(z)) continue
            h = sqrt(max(0.0, h)) * root
            val n = Point2((d.x * r - d.y * h) / z, (d.y * r + d.x * h) / z)
            val seg = Segment2(center - n * r1, other.center - n * r2)
            if (out.none { near(it.a, seg.a, e) && near(it.b, seg.b, e) }) out += seg
        }
        return out
    }

    override fun toFLine(extent: Double): FLine = Arc2(center, radius, 0.0, TAU).toFLine(extent)

    companion object {
        /** The circle through three points, or null when they are collinear. */
        fun through(a: Point2, b: Point2, c: Point2): Circle2? {
            val ab = b - a; val ac = c - a
            val den = 2 * (ab cross ac)
            if (abs(den) <= tol(a, b, c) * max(ab.length, ac.length)) return null
            val o = Point2(
                (ac.y * ab.lengthSquared - ab.y * ac.lengthSquared) / den,
                (ab.x * ac.lengthSquared - ac.x * ab.lengthSquared) / den,
            )
            return Circle2(a + o, o.length)
        }

        /** The incircle of triangle [a] [b] [c], or null when it is degenerate. */
        fun inscribed(a: Point2, b: Point2, c: Point2): Circle2? {
            val la = (c - b).length; val lb = (a - c).length; val lc = (b - a).length
            val perimeter = la + lb + lc
            val twiceArea = abs((b - a) cross (c - a))
            if (twiceArea <= tol(a, b, c) * max(la, max(lb, lc))) return null
            return Circle2((a * la + b * lb + c * lc) / perimeter, twiceArea / perimeter)
        }

        /** The circles of [radius] through [a] and [b] (none, one or two). */
        fun throughWithRadius(a: Point2, b: Point2, radius: Double): List<Circle2> {
            val half = (b - a).length / 2
            val e = tol(a, b, radius)
            if (half == 0.0 || half > radius + e) return emptyList()
            val m = a.midpoint(b)
            if (half >= radius - e) return listOf(Circle2(m, radius))
            val off = (b - a).perp.normalized() * sqrt(radius * radius - half * half)
            return listOf(Circle2(m + off, radius), Circle2(m - off, radius))
        }

        /** The circles of [radius] tangent to both lines (the fillets of their four corners). */
        fun tangentToLines(l1: Line2, l2: Line2, radius: Double): List<Circle2> {
            val out = mutableListOf<Circle2>()
            for (s1 in doubleArrayOf(1.0, -1.0)) for (s2 in doubleArrayOf(1.0, -1.0)) {
                val m = intersectPrims(prim(l1.offset(s1 * radius)), prim(l2.offset(s2 * radius)))
                m.points.firstOrNull()?.let { out += Circle2(it, radius) }
            }
            return out
        }
    }
}

/**
 * The part of the circle about [center] of [radius] from angle [start] through signed [sweep]
 * (radians, positive counter-clockwise). A sweep of 2π or more is the whole circle.
 */
data class Arc2(val center: Point2, val radius: Double, val start: Double, val sweep: Double) : Shape2 {
    val end get() = start + sweep
    val startPoint get() = Point2.polar(radius, start, center)
    val endPoint get() = Point2.polar(radius, end, center)
    val midPoint get() = Point2.polar(radius, start + sweep / 2, center)
    val length get() = abs(sweep) * radius
    val circle get() = Circle2(center, radius)
    fun at(t: Double) = Point2.polar(radius, start + sweep * t, center)
    fun reversed() = Arc2(center, radius, end, -sweep)

    /** True when the direction [angle] (from [center]) falls within the arc. */
    fun containsAngle(angle: Double) = prim(this).let { it is Circ && it.hasAngle(angle, 0.0) }

    override fun toFLine(extent: Double): FLine {
        val f = FLine()
        val pieces = max(1, ceil(abs(sweep) / (PI / 2) - 1e-9).toInt())
        val step = sweep / pieces
        val k = 4.0 / 3.0 * tan(step / 4) * radius
        var a = start
        val p0 = Point2.polar(radius, a, center)
        f.moveTo(p0.x, p0.y)
        repeat(pieces) {
            val b = a + step
            val q0 = Point2.polar(radius, a, center); val q3 = Point2.polar(radius, b, center)
            val c1 = q0 + Point2(-sin(a), cos(a)) * k
            val c2 = q3 - Point2(-sin(b), cos(b)) * k
            f.cubicTo(c1.x, c1.y, 0, c2.x, c2.y, 0, q3.x, q3.y, 0)
            a = b
        }
        return f
    }

    companion object {
        /** The arc from [a] through [b] to [c], or null when they are collinear. */
        fun through(a: Point2, b: Point2, c: Point2): Arc2? {
            val circle = Circle2.through(a, b, c) ?: return null
            val a0 = (a - circle.center).angle
            val ccw = wrap(( c - circle.center).angle - a0)
            val mid = wrap((b - circle.center).angle - a0)
            val sweep = if (mid <= ccw) ccw else ccw - TAU
            return Arc2(circle.center, circle.radius, a0, sweep)
        }

        /** The arc that leaves [from] heading along [tangent] and ends at [to]; null if they are in line. */
        fun tangentTo(from: Point2, tangent: Point2, to: Point2): Arc2? {
            val chord = to - from
            val den = 2 * (tangent.normalized() cross chord)
            if (abs(den) <= tol(from, to) * chord.length) return null
            val n = tangent.normalized().perp
            val signedR = chord.lengthSquared / den
            val center = from + n * signedR
            val a0 = (from - center).angle
            val delta = wrap((to - center).angle - a0)
            val sweep = if (signedR > 0) delta else delta - TAU
            return Arc2(center, abs(signedR), a0, sweep)
        }
    }
}

/**
 * The closed polygon through [points] in order. Intersections and distances are with its boundary,
 * its edges; [Polygon2.contains] asks about its inside.
 */
data class Polygon2(val points: List<Point2>) : Shape2 {
    constructor(vararg points: Point2) : this(points.toList())

    val edges get() = points.indices.map { Segment2(points[it], points[(it + 1) % points.size]) }

    /** Signed area: positive when the points run counter-clockwise (in a y-up frame). */
    val signedArea get() = points.indices.sumOf { points[it] cross points[(it + 1) % points.size] } / 2

    val area get() = abs(signedArea)
    val perimeter get() = edges.sumOf { it.length }

    /** True when [p] is inside the polygon or on its boundary (even-odd rule). */
    operator fun contains(p: Point2): Boolean {
        if (edges.any { (it.closestPoint(p) - p).length <= tol(it.a, it.b) }) return true
        var inside = false
        for (e in edges) {
            val (a, b) = e
            if ((a.y > p.y) != (b.y > p.y) && p.x < a.x + (p.y - a.y) / (b.y - a.y) * (b.x - a.x)) inside = !inside
        }
        return inside
    }

    override fun toFLine(extent: Double): FLine {
        val f = FLine()
        if (points.isEmpty()) return f
        f.moveTo(points[0].x, points[0].y)
        for (p in points.drop(1)) f.lineTo(p.x, p.y)
        return f.close()
    }
}

/**
 * The result of intersecting two shapes: isolated crossing or touching [points], and any
 * stretch the shapes share in [overlaps] (a [Segment2], [Ray2], [Line2], [Arc2] or
 * [Circle2]).
 */
data class Intersection2(val points: List<Point2> = emptyList(), val overlaps: List<Shape2> = emptyList()) {
    val isEmpty get() = points.isEmpty() && overlaps.isEmpty()
    companion object {
        val NONE = Intersection2()
    }
}

// ---------------------------------------------------------------------------------------------
// Internals: every shape reduces to a point, a parametrized line piece, or a circle piece.

private sealed interface Prim

/** `p + d t` for `t` in [lo, hi] (either may be infinite). */
private data class Lin(val p: Point2, val d: Point2, val lo: Double, val hi: Double) : Prim {
    fun at(t: Double) = p + d * t
    fun param(q: Point2) = ((q - p) dot d) / d.lengthSquared
    fun clampT(t: Double) = t.coerceIn(lo, hi)
    val finiteEnds get() = listOfNotNull(lo.takeIf { it.isFinite() }?.let(::at), hi.takeIf { it.isFinite() }?.let(::at))
    val sizeHint get() = max(abs(p.x), abs(p.y)) + (if (lo.isFinite()) abs(lo) * d.length else 0.0) +
        (if (hi.isFinite()) abs(hi) * d.length else 0.0)
}

/** Circle piece from angle [a0] counter-clockwise through [s] >= 0; s >= 2π is full. */
private data class Circ(val c: Point2, val r: Double, val a0: Double, val s: Double) : Prim {
    val full get() = s >= TAU
    fun at(a: Double) = Point2.polar(r, a, c)
    fun hasAngle(a: Double, distTol: Double): Boolean {
        if (full) return true
        val slack = if (r > 0) distTol / r else 0.0
        val off = wrap(a - a0)
        return off <= s + slack || off >= TAU - slack
    }
    val ends get() = if (full) emptyList() else listOf(at(a0), at(a0 + s))
}

private data class Pnt(val p: Point2) : Prim

private fun pieces(s: Shape2): List<Shape2> = if (s is Polygon2) s.edges else listOf(s)

/** Every edge against every piece of the other shape, with shared corners counted once. */
private fun intersectPieces(a: Shape2, b: Shape2): Intersection2 {
    val points = mutableListOf<Point2>(); val overlaps = mutableListOf<Shape2>()
    for (p in pieces(a)) for (q in pieces(b)) {
        val m = intersectPrims(prim(p), prim(q))
        val e = tolFor(prim(p), prim(q))
        m.points.forEach { pt -> if (points.none { near(it, pt, e) }) points += pt }
        overlaps += m.overlaps
    }
    return Intersection2(points.filter { pt -> overlaps.none { o -> near(o.closestPoint(pt), pt, Euclid.epsilon * (1 + abs(pt.x) + abs(pt.y))) } }, overlaps)
}

private fun prim(s: Shape2): Prim = when (s) {
    is Polygon2 -> error("a polygon is handled edge by edge")
    is Point2 -> Pnt(s)
    is Line2 -> if (s.direction.lengthSquared == 0.0) Pnt(s.point) else Lin(s.point, s.direction, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
    is Ray2 -> if (s.direction.lengthSquared == 0.0) Pnt(s.origin) else Lin(s.origin, s.direction, 0.0, Double.POSITIVE_INFINITY)
    is Segment2 -> if (s.a == s.b) Pnt(s.a) else Lin(s.a, s.b - s.a, 0.0, 1.0)
    is Circle2 -> if (s.radius == 0.0) Pnt(s.center) else Circ(s.center, abs(s.radius), 0.0, TAU)
    is Arc2 -> when {
        s.radius == 0.0 || s.sweep == 0.0 -> Pnt(s.startPoint)
        s.sweep > 0 -> Circ(s.center, abs(s.radius), s.start, min(s.sweep, TAU))
        else -> Circ(s.center, abs(s.radius), s.start + s.sweep, min(-s.sweep, TAU))
    }
}

/** Angle wrapped into [0, 2π). */
private fun wrap(a: Double): Double {
    val w = a % TAU
    return if (w < 0) w + TAU else w
}

private fun tol(vararg ps: Point2) = Euclid.epsilon * (1 + ps.maxOf { max(abs(it.x), abs(it.y)) })
private fun tol(a: Point2, b: Point2, extra: Double) = Euclid.epsilon * (1 + max(max(abs(a.x), abs(a.y)), max(abs(b.x), abs(b.y))) + abs(extra))
private fun near(a: Point2, b: Point2, e: Double) = (a - b).length <= e

private fun scaleOf(p: Prim): Double = when (p) {
    is Pnt -> max(abs(p.p.x), abs(p.p.y))
    is Lin -> p.sizeHint
    is Circ -> max(abs(p.c.x), abs(p.c.y)) + p.r
}

private fun tolFor(a: Prim, b: Prim) = Euclid.epsilon * (1 + max(scaleOf(a), scaleOf(b)))

private fun closestOn(p: Prim, q: Point2): Point2 = when (p) {
    is Pnt -> p.p
    is Lin -> p.at(p.clampT(p.param(q)))
    is Circ -> {
        val v = q - p.c
        if (v.lengthSquared == 0.0) p.at(p.a0)
        else if (p.hasAngle(v.angle, 0.0)) p.c + v.normalized() * p.r
        else p.ends.minBy { (it - q).lengthSquared }
    }
}

private fun intersectPrims(a: Prim, b: Prim): Intersection2 {
    val e = tolFor(a, b)
    return when {
        a is Pnt -> if (near(closestOn(b, a.p), a.p, e)) Intersection2(listOf(a.p)) else Intersection2.NONE
        b is Pnt -> if (near(closestOn(a, b.p), b.p, e)) Intersection2(listOf(b.p)) else Intersection2.NONE
        a is Lin && b is Lin -> linLin(a, b, e)
        a is Lin && b is Circ -> Intersection2(linCirc(a, b, e))
        a is Circ && b is Lin -> Intersection2(linCirc(b, a, e))
        else -> circCirc(a as Circ, b as Circ, e)
    }
}

private fun linLin(a: Lin, b: Lin, e: Double): Intersection2 {
    val den = a.d cross b.d
    val w = b.p - a.p
    val la = a.d.length; val lb = b.d.length
    val ta = e / la; val tb = e / lb
    if (abs(den) > Euclid.epsilon * la * lb) {
        val t = (w cross b.d) / den
        val u = (w cross a.d) / den
        // a near-parallel pair can meet far out; check in distance, not only in parameter
        if (t < a.lo - ta || t > a.hi + ta || u < b.lo - tb || u > b.hi + tb) {
            val pa = a.at(a.clampT(t)); val pb = b.at(b.clampT(u))
            return if (near(pa, pb, e)) Intersection2(listOf(pa)) else Intersection2.NONE
        }
        return Intersection2(listOf(a.at(a.clampT(t))))
    }
    if (abs(w cross a.d) / la > e) return Intersection2.NONE
    // collinear: b's range expressed in a's parameter
    val t0 = (w dot a.d) / (la * la)
    val k = (b.d dot a.d) / (la * la)
    val e1 = t0 + k * b.lo; val e2 = t0 + k * b.hi
    val lo = max(a.lo, min(e1, e2)); val hi = min(a.hi, max(e1, e2))
    if (lo > hi + ta) return Intersection2.NONE
    if (hi - lo <= ta) return Intersection2(listOf(a.at(if (lo.isFinite()) lo else hi)))
    val overlap: Shape2 = when {
        lo.isFinite() && hi.isFinite() -> Segment2(a.at(lo), a.at(hi))
        lo.isFinite() -> Ray2(a.at(lo), a.d)
        hi.isFinite() -> Ray2(a.at(hi), -a.d)
        else -> Line2(a.p, a.d)
    }
    return Intersection2(overlaps = listOf(overlap))
}

private fun linCirc(a: Lin, c: Circ, e: Double): List<Point2> {
    val len = a.d.length
    val f = a.p - c.c
    val t0 = -(f dot a.d) / (len * len)
    val foot = a.at(t0)
    val h = (foot - c.c).length
    if (h > c.r + e) return emptyList()
    val ts = if (h >= c.r - e) listOf(t0) else {
        val half = sqrt(c.r * c.r - h * h) / len
        listOf(t0 - half, t0 + half)
    }
    val ta = e / len
    return ts.filter { it >= a.lo - ta && it <= a.hi + ta }
        .map { a.at(a.clampT(it)) }
        .filter { c.hasAngle((it - c.c).angle, e) }
}

private fun circCirc(a: Circ, b: Circ, e: Double): Intersection2 {
    val dv = b.c - a.c
    val d = dv.length
    if (d <= e) {
        if (abs(a.r - b.r) > e) return Intersection2.NONE
        return sameCircle(a, b, e)
    }
    if (d > a.r + b.r + e || d < abs(a.r - b.r) - e) return Intersection2.NONE
    val u = dv / d
    val x = (d * d + a.r * a.r - b.r * b.r) / (2 * d)
    val tangent = abs(d - (a.r + b.r)) <= e || abs(d - abs(a.r - b.r)) <= e
    val pts = if (tangent || a.r * a.r - x * x <= 0) listOf(a.c + u * x.coerceIn(-a.r, a.r)) else {
        val h = sqrt(a.r * a.r - x * x)
        val m = a.c + u * x
        listOf(m + u.perp * h, m - u.perp * h)
    }
    return Intersection2(pts.filter { a.hasAngle((it - a.c).angle, e) && b.hasAngle((it - b.c).angle, e) })
}

/** Two pieces of one circle: their shared arcs, or a whole circle, or touching end points. */
private fun sameCircle(a: Circ, b: Circ, e: Double): Intersection2 {
    if (a.full && b.full) return Intersection2(overlaps = listOf(Circle2(a.c, a.r)))
    val (p, q) = if (a.full) b to a else a to b
    if (q.full) return Intersection2(overlaps = listOf(Arc2(p.c, p.r, p.a0, p.s)))
    val slack = e / p.r
    val b0 = p.a0 + wrap(q.a0 - p.a0)
    val points = mutableListOf<Point2>(); val overlaps = mutableListOf<Shape2>()
    for (start in doubleArrayOf(b0, b0 - TAU)) {
        val lo = max(p.a0, start); val hi = min(p.a0 + p.s, start + q.s)
        if (lo > hi + slack) continue
        if (hi - lo <= slack) points += p.at(lo) else overlaps += Arc2(p.c, p.r, lo, hi - lo)
    }
    return Intersection2(points.filter { pt -> overlaps.none { o -> near(closestOn(prim(o), pt), pt, e) } }
        .fold(mutableListOf<Point2>()) { acc, pt -> if (acc.none { near(it, pt, e) }) acc += pt; acc }, overlaps)
}

private fun closestPair(a: Prim, b: Prim): Pair<Point2, Point2> {
    if (a is Pnt) return a.p to closestOn(b, a.p)
    if (b is Pnt) return closestOn(a, b.p) to b.p
    val meet = intersectPrims(a, b)
    meet.points.firstOrNull()?.let { return it to it }
    meet.overlaps.firstOrNull()?.let { val p = anyPointOf(it); return p to p }
    val candidates = mutableListOf<Pair<Point2, Point2>>()
    fun fromA(q: Point2) { candidates += q to closestOn(b, q) }
    fun fromB(q: Point2) { candidates += closestOn(a, q) to q }
    when {
        a is Lin && b is Lin -> {
            a.finiteEnds.forEach(::fromA); b.finiteEnds.forEach(::fromB)
            if (candidates.isEmpty()) fromA(a.p) // parallel infinite lines
        }
        a is Lin && b is Circ -> linCircCandidates(a, b).forEach(::fromA).also { b.ends.forEach(::fromB) }
        a is Circ && b is Lin -> linCircCandidates(b, a).forEach(::fromB).also { a.ends.forEach(::fromA) }
        a is Circ && b is Circ -> {
            a.ends.forEach(::fromA); b.ends.forEach(::fromB)
            val dv = b.c - a.c
            if (dv.lengthSquared > 0) {
                val ang = dv.angle
                for (sa in doubleArrayOf(0.0, PI)) for (sb in doubleArrayOf(0.0, PI))
                    if (a.hasAngle(ang + sa, 0.0) && b.hasAngle(ang + sb, 0.0)) candidates += a.at(ang + sa) to b.at(ang + sb)
            } else if (candidates.isEmpty()) candidates += a.at(0.0) to b.at(0.0)
        }
    }
    return candidates.minBy { (p, q) -> (q - p).lengthSquared }
}

/** Points along [a] where its distance to [c] can be smallest. */
private fun linCircCandidates(a: Lin, c: Circ): List<Point2> {
    val out = a.finiteEnds.toMutableList()
    out += a.at(a.clampT(a.param(c.c)))
    if (!c.full) for (ang in doubleArrayOf(c.a0, c.a0 + c.s)) {
        val ray = Lin(c.c, Point2(cos(ang), sin(ang)), 0.0, Double.POSITIVE_INFINITY)
        out += linLin(a, ray, tolFor(a, c)).points
    }
    return out
}

private fun anyPointOf(s: Shape2): Point2 = when (s) {
    is Point2 -> s
    is Line2 -> s.point
    is Ray2 -> s.origin
    is Segment2 -> s.a
    is Circle2 -> s.at(0.0)
    is Arc2 -> s.startPoint
    is Polygon2 -> s.points.first()
}
