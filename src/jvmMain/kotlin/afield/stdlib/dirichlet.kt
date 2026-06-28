@file:afield.CompletionCategory("Math")

package afield.stdlib

import kotlin.math.*

object CircularDirichlet {
    // Apollonius / multiplicatively weighted Voronoi:
    // score_i(x) = |x - p_i|^2 / w_i^2, with site = (x, y, w)

    private const val TAU: Float = (Math.PI * 2.0).toFloat()

    data class Aabb(val min: Vec2, val max: Vec2)

    sealed interface SupportingCurve {
        // For arcs this is the actual supporting circle.
        // For lines this is the radius->infinity case; center is null.
        val circleCenter: Vec2?
        val circleRadius: Float

        fun pointAt(parameter: Float): Vec2
        fun parameterOf(point: Vec2): Float
    }

    data class SupportCircle(
        val center: Vec2,
        val radius: Float,
    ) : SupportingCurve {
        override val circleCenter: Vec2 = Vec2(center.x, center.y)
        override val circleRadius: Float = radius

        override fun pointAt(parameter: Float): Vec2 {
            return vec(
                center.x + cos(parameter) * radius,
                center.y + sin(parameter) * radius,
            )
        }

        override fun parameterOf(point: Vec2): Float {
            return wrapAngle(atan2(point.y - center.y, point.x - center.x).f)
        }
    }

    data class SupportLine(
        val point: Vec2,      // one point on the infinite line
        val direction: Vec2,  // normalized
        val normal: Vec2,     // not normalized, line eq normal·x + c = 0
        val c: Float,
    ) : SupportingCurve {
        override val circleCenter: Vec2? = null
        override val circleRadius: Float = Float.POSITIVE_INFINITY

        override fun pointAt(parameter: Float): Vec2 {
            return Vec2(
                point.x + direction.x * parameter,
                point.y + direction.y * parameter,
            )
        }

        override fun parameterOf(point: Vec2): Float {
            return dot(sub(point, this.point), direction)
        }
    }

    sealed interface EdgePiece {
        val support: SupportingCurve
        val neighborSite: Int
        val startCorner: Vec2? // null when naturally unbounded / full circle
        val endCorner: Vec2?   // null when naturally unbounded / full circle

        fun samplePoint(): Vec2
        fun toPolyline(maxChordError: Float = 0.5f, clip: Aabb? = null): List<Vec2>
    }

    data class ArcPiece(
        override val support: SupportCircle,
        val startAngle: Float, // radians
        val sweepAngle: Float, // positive, CCW
        override val startCorner: Vec2?,
        override val endCorner: Vec2?,
        private val sample: Vec2,
        override val neighborSite: Int,
    ) : EdgePiece {

        override fun samplePoint(): Vec2 = Vec2(sample.x, sample.y)

        override fun toPolyline(maxChordError: Float, clip: Aabb?): List<Vec2> {
            val parts = toPolylines(maxChordError, clip)
            return when (parts.size) {
                0 -> emptyList()
                1 -> parts[0]
                else -> error(
                    "Clipping split this arc into ${parts.size} disjoint polylines. " +
                            "Call toPolylines(...) instead."
                )
            }
        }

        fun toPolylines(maxChordError: Float = 0.5f, clip: Aabb? = null): List<List<Vec2>> {
            require(maxChordError > 0f) { "maxChordError must be > 0" }
            if (support.radius <= 0f || sweepAngle <= 0f) return emptyList()

            val intervals = if (clip == null) {
                listOf(0f to sweepAngle)
            } else {
                clippedArcIntervals(
                    circle = support,
                    startAngle = startAngle,
                    sweepAngle = sweepAngle,
                    clip = clip
                )
            }

            if (intervals.isEmpty()) return emptyList()

            val out = ArrayList<List<Vec2>>(intervals.size)
            val fullCircle = clip == null && sweepAngle >= TAU - 1e-5f

            for ((u0, u1) in intervals) {
                val sweep = u1 - u0
                if (sweep <= 1e-6f) continue

                out += tessellateArc(
                    circle = support,
                    start = startAngle + u0,
                    sweep = sweep,
                    maxChordError = maxChordError,
                    closed = fullCircle && u0 <= 1e-6f && u1 >= sweepAngle - 1e-6f
                )
            }

            return out
        }
    }

    private fun tessellateArc(
        circle: SupportCircle,
        start: Float,
        sweep: Float,
        maxChordError: Float,
        closed: Boolean,
    ): List<Vec2> {
        if (sweep <= 0f) return emptyList()

        val chordError = max(1e-5f, maxChordError)
        val cosArg = (1f - chordError / circle.radius).coerceIn(-1f, 1f)
        val maxStep = if (circle.radius <= chordError) {
            sweep
        } else {
            (2.0 * acos(cosArg.toDouble())).toFloat()
        }.coerceAtLeast(1e-3f)

        val segments = max(
            if (closed) 3 else 1,
            ceil(sweep / maxStep).toInt()
        )

        val out = ArrayList<Vec2>(segments + if (closed) 0 else 1)

        if (closed) {
            for (i in 0 until segments) {
                val a = start + sweep * (i.toFloat() / segments.toFloat())
                out += circle.pointAt(a)
            }
        } else {
            for (i in 0..segments) {
                val a = start + sweep * (i.toFloat() / segments.toFloat())
                out += circle.pointAt(a)
            }
        }

        return out
    }

    private fun clippedArcIntervals(
        circle: SupportCircle,
        startAngle: Float,
        sweepAngle: Float,
        clip: Aabb,
    ): List<Pair<Float, Float>> {
        val angleTol = max(1e-6f, 1e-5f / max(1f, circle.radius))
        val geomTol = 1e-5f

        val cuts = ArrayList<Float>()
        cuts += 0f
        cuts += sweepAngle
        cuts += circleAabbIntersectionParams(circle, startAngle, sweepAngle, clip, angleTol, geomTol)

        val params = uniqueScalars(
            cuts
                .map { it.coerceIn(0f, sweepAngle) }
                .sorted(),
            angleTol
        )

        if (params.isEmpty()) return emptyList()

        val out = ArrayList<Pair<Float, Float>>()

        for (i in 0 until params.lastIndex) {
            val u0 = params[i]
            val u1 = params[i + 1]
            if (u1 - u0 <= angleTol) continue

            val um = 0.5f * (u0 + u1)
            val p = circle.pointAt(startAngle + um)

            if (pointInAabb(p, clip, geomTol)) {
                out += (u0 to u1)
            }
        }

        return out
    }

    private fun circleAabbIntersectionParams(
        circle: SupportCircle,
        startAngle: Float,
        sweepAngle: Float,
        clip: Aabb,
        angleTol: Float,
        geomTol: Float,
    ): List<Float> {
        val out = ArrayList<Float>()
        val cx = circle.center.x
        val cy = circle.center.y
        val r2 = circle.radius * circle.radius

        fun addAngle(theta: Float) {
            val u = wrapAngle(theta - startAngle)
            val onArc = if (sweepAngle >= TAU - angleTol) {
                true
            } else {
                u > angleTol && u < sweepAngle - angleTol
            }
            if (onArc) out += u
        }

        fun addPoint(x: Float, y: Float) {
            addAngle(atan2(y - cy, x - cx).f)
        }

        fun maybeVertical(x: Float) {
            val dx = x - cx
            val rem = r2 - dx * dx
            if (rem < -geomTol) return
            val root = sqrt(max(0f, rem))

            val y0 = cy - root
            if (y0 >= clip.min.y - geomTol && y0 <= clip.max.y + geomTol) addPoint(x, y0)

            if (root > geomTol) {
                val y1 = cy + root
                if (y1 >= clip.min.y - geomTol && y1 <= clip.max.y + geomTol) addPoint(x, y1)
            }
        }

        fun maybeHorizontal(y: Float) {
            val dy = y - cy
            val rem = r2 - dy * dy
            if (rem < -geomTol) return
            val root = sqrt(max(0f, rem))

            val x0 = cx - root
            if (x0 >= clip.min.x - geomTol && x0 <= clip.max.x + geomTol) addPoint(x0, y)

            if (root > geomTol) {
                val x1 = cx + root
                if (x1 >= clip.min.x - geomTol && x1 <= clip.max.x + geomTol) addPoint(x1, y)
            }
        }

        maybeVertical(clip.min.x)
        maybeVertical(clip.max.x)
        maybeHorizontal(clip.min.y)
        maybeHorizontal(clip.max.y)

        return out
    }

    private fun pointInAabb(p: Vec2, box: Aabb, eps: Float = 1e-6f): Boolean {
        return p.x >= box.min.x - eps &&
                p.x <= box.max.x + eps &&
                p.y >= box.min.y - eps &&
                p.y <= box.max.y + eps
    }

    data class LinePiece(
        override val support: SupportLine,
        val t0: Float, // can be -INF
        val t1: Float, // can be +INF
        override val startCorner: Vec2?,
        override val endCorner: Vec2?,
        private val sample: Vec2,
        override val neighborSite: Int,
    ) : EdgePiece {

        override fun samplePoint(): Vec2 = Vec2(sample.x, sample.y)

        override fun toPolyline(maxChordError: Float, clip: Aabb?): List<Vec2> {
            var lo = t0
            var hi = t1

            if (clip != null) {
                val clipped = clipLineParameters(support, clip, lo, hi) ?: return emptyList()
                lo = clipped.first
                hi = clipped.second
            }

            require(lo.isFinite() && hi.isFinite()) {
                "Unbounded line piece needs a clip Aabb to become a finite polyline."
            }

            if (hi < lo) return emptyList()
            if (abs(hi - lo) <= 1e-6f) return listOf(support.pointAt(lo))
            return listOf(support.pointAt(lo), support.pointAt(hi))
        }
    }

    data class CellBoundary(
        val siteIndex: Int,
        val pieces: List<EdgePiece>, // angularly sorted around the site
    )

    data class Diagram(val cells: List<CellBoundary>) {
        operator fun get(siteIndex: Int): CellBoundary = cells[siteIndex]
    }

    fun build(sites: List<Vec3>, tolerance: Float = 1e-4f): Diagram {
        require(tolerance > 0f) { "tolerance must be > 0" }
        sites.forEachIndexed { i, s ->
            require(s.z > 0f) { "All weights must be > 0, but site $i had weight=${s.z}" }
        }
        if (sites.isEmpty()) return Diagram(emptyList())

        val geomScale = geometryScale(sites)
        val minWeight = sites.minOf { it.z }
        val geoTol = tolerance * max(1f, geomScale)
        val scoreTol = tolerance * (1f + sq(geomScale / minWeight))
        val sampleSpan = max(1f, geomScale * 2f)

        val bisectors = Array(sites.size) { arrayOfNulls<SupportingCurve>(sites.size) }
        for (i in sites.indices) {
            for (j in i + 1 until sites.size) {
                val curve = bisectorOf(sites[i], sites[j], tolerance, geoTol)
                bisectors[i][j] = curve
                bisectors[j][i] = curve
            }
        }

        val cells = ArrayList<CellBoundary>(sites.size)

        for (i in sites.indices) {
            val pieces = ArrayList<EdgePiece>()

            for (j in sites.indices) {
                if (i == j) continue
                when (val support = bisectors[i][j]) {
                    is SupportCircle -> pieces += buildArcPieces(
                        siteIndex = i,
                        neighborSite = j,
                        support = support,
                        bisectors = bisectors,
                        sites = sites,
                        geoTol = geoTol,
                        scoreTol = scoreTol,
                    )
                    is SupportLine -> pieces += buildLinePieces(
                        siteIndex = i,
                        neighborSite = j,
                        support = support,
                        bisectors = bisectors,
                        sites = sites,
                        geoTol = geoTol,
                        scoreTol = scoreTol,
                        sampleSpan = sampleSpan,
                    )
                    null -> Unit
                }
            }

            pieces.sortBy {
                val p = it.samplePoint()
                atan2((p.y - sites[i].y).toDouble(), (p.x - sites[i].x).toDouble())
            }

            cells += CellBoundary(i, pieces)
        }

        return Diagram(cells)
    }

    private fun buildArcPieces(
        siteIndex: Int,
        neighborSite: Int,
        support: SupportCircle,
        bisectors: Array<Array<SupportingCurve?>>,
        sites: List<Vec3>,
        geoTol: Float,
        scoreTol: Float,
    ): List<ArcPiece> {
        val angleTol = max(1e-6f, geoTol / max(1f, support.radius))
        val breakAngles = ArrayList<Float>()

        for (k in sites.indices) {
            if (k == siteIndex || k == neighborSite) continue
            val other = bisectors[siteIndex][k] ?: continue
            for (point in intersections(support, other, geoTol)) {
                breakAngles += support.parameterOf(point)
            }
        }

        val uniqueAngles = uniqueAngles(breakAngles, angleTol)
        if (uniqueAngles.isEmpty()) {
            val sample = support.pointAt(0f)
            return if (isBoundarySample(sample, siteIndex, sites, scoreTol)) {
                listOf(
                    ArcPiece(
                        support = support,
                        startAngle = 0f,
                        sweepAngle = TAU,
                        startCorner = null,
                        endCorner = null,
                        sample = sample,
                        neighborSite = neighborSite,
                    )
                )
            } else {
                emptyList()
            }
        }

        val pieces = ArrayList<ArcPiece>()

        for (i in uniqueAngles.indices) {
            val a0 = uniqueAngles[i]
            val a1 = if (i + 1 < uniqueAngles.size) uniqueAngles[i + 1] else uniqueAngles[0] + TAU
            val sweep = a1 - a0
            if (sweep <= angleTol) continue

            val sample = support.pointAt(a0 + 0.5f * sweep)
            if (!isBoundarySample(sample, siteIndex, sites, scoreTol)) continue

            val full = sweep >= TAU - 8f * angleTol
            val startAngle = wrapAngle(a0)
            val endAngle = wrapAngle(a0 + sweep)

            pieces += ArcPiece(
                support = support,
                startAngle = startAngle,
                sweepAngle = if (full) TAU else sweep,
                startCorner = if (full) null else support.pointAt(startAngle),
                endCorner = if (full) null else support.pointAt(endAngle),
                sample = sample,
                neighborSite = neighborSite,
            )
        }

        return pieces
    }

    private fun buildLinePieces(
        siteIndex: Int,
        neighborSite: Int,
        support: SupportLine,
        bisectors: Array<Array<SupportingCurve?>>,
        sites: List<Vec3>,
        geoTol: Float,
        scoreTol: Float,
        sampleSpan: Float,
    ): List<LinePiece> {
        val breakTs = ArrayList<Float>()

        for (k in sites.indices) {
            if (k == siteIndex || k == neighborSite) continue
            val other = bisectors[siteIndex][k] ?: continue
            for (point in intersections(support, other, geoTol)) {
                breakTs += support.parameterOf(point)
            }
        }

        val ts = uniqueScalars(breakTs, geoTol)
        val pieces = ArrayList<LinePiece>()

        fun maybeAdd(t0: Float, t1: Float) {
            if (t0.isFinite() && t1.isFinite() && abs(t1 - t0) <= geoTol) return

            val sampleT = sampleParameter(t0, t1, sampleSpan)
            val sample = support.pointAt(sampleT)
            if (!isBoundarySample(sample, siteIndex, sites, scoreTol)) return

            pieces += LinePiece(
                support = support,
                t0 = t0,
                t1 = t1,
                startCorner = if (t0.isFinite()) support.pointAt(t0) else null,
                endCorner = if (t1.isFinite()) support.pointAt(t1) else null,
                sample = sample,
                neighborSite = neighborSite,
            )
        }

        if (ts.isEmpty()) {
            maybeAdd(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)
            return pieces
        }

        maybeAdd(Float.NEGATIVE_INFINITY, ts.first())
        for (i in 0 until ts.lastIndex) {
            maybeAdd(ts[i], ts[i + 1])
        }
        maybeAdd(ts.last(), Float.POSITIVE_INFINITY)

        return pieces
    }

    private fun bisectorOf(a: Vec3, b: Vec3, tol: Float, geoTol: Float): SupportingCurve? {
        // score(x) = |x-p|^2 / w^2
        // Equality gives A|x|^2 + B·x + C = 0.
        // If A == 0 -> line, else circle.

        val invWa2 = 1f / (a.z * a.z)
        val invWb2 = 1f / (b.z * b.z)

        val sameWeight = abs(invWa2 - invWb2) <= tol * max(1f, max(abs(invWa2), abs(invWb2)))

        val A = invWa2 - invWb2
        val Bx = -2f * (a.x * invWa2 - b.x * invWb2)
        val By = -2f * (a.y * invWa2 - b.y * invWb2)
        val C = (a.x * a.x + a.y * a.y) * invWa2 - (b.x * b.x + b.y * b.y) * invWb2

        if (sameWeight) {
            val normal = Vec2(Bx, By)
            val n2 = dot(normal, normal)
            if (n2 <= geoTol * geoTol) return null // coincident / duplicate site
            val point = mul(normal, -C / n2)
            val direction = normalize(perp(normal))
            return SupportLine(
                point = point,
                direction = direction,
                normal = normal,
                c = C,
            )
        }

        if (abs(A) <= 1e-12f) return null

        val center = Vec2(-Bx / (2f * A), -By / (2f * A))
        val r2 = dot(center, center) - C / A

        // Tiny / negative due numerical noise -> ignore.
        if (r2 <= geoTol * geoTol) return null
        if (r2 < 0f && abs(r2) > 16f * geoTol * geoTol) return null

        return SupportCircle(
            center = center,
            radius = sqrt(max(0f, r2)),
        )
    }

    private fun intersections(a: SupportingCurve, b: SupportingCurve, geoTol: Float): List<Vec2> {
        return when {
            a is SupportCircle && b is SupportCircle -> circleCircleIntersections(a, b, geoTol)
            a is SupportCircle && b is SupportLine -> lineCircleIntersections(b, a, geoTol)
            a is SupportLine && b is SupportCircle -> lineCircleIntersections(a, b, geoTol)
            a is SupportLine && b is SupportLine -> lineLineIntersection(a, b, geoTol)
            else -> emptyList()
        }
    }

    private fun circleCircleIntersections(a: SupportCircle, b: SupportCircle, geoTol: Float): List<Vec2> {
        val delta = sub(b.center, a.center)
        val d = length(delta)
        if (d <= geoTol) return emptyList()

        if (d > a.radius + b.radius + geoTol) return emptyList()
        if (d < abs(a.radius - b.radius) - geoTol) return emptyList()

        val x = (sq(a.radius) - sq(b.radius) + sq(d)) / (2f * d)
        val y2 = sq(a.radius) - sq(x)
        if (y2 < -geoTol) return emptyList()

        val dir = mul(delta, 1f / d)
        val base = add(a.center, mul(dir, x))
        val y = sqrt(max(0f, y2))
        val offset = mul(perp(dir), y)

        return if (y <= geoTol) {
            listOf(base)
        } else {
            listOf(add(base, offset), sub(base, offset))
        }
    }

    private fun lineCircleIntersections(line: SupportLine, circle: SupportCircle, geoTol: Float): List<Vec2> {
        // line = point + t * direction, direction normalized
        val m = sub(line.point, circle.center)
        val b = dot(m, line.direction)
        val c = dot(m, m) - sq(circle.radius)
        val disc = sq(b) - c

        if (disc < -geoTol) return emptyList()
        if (disc <= geoTol) {
            val t = -b
            return listOf(line.pointAt(t))
        }

        val root = sqrt(disc)
        val t0 = -b - root
        val t1 = -b + root
        return listOf(line.pointAt(t0), line.pointAt(t1))
    }

    private fun lineLineIntersection(a: SupportLine, b: SupportLine, geoTol: Float): List<Vec2> {
        val denom = cross(a.direction, b.direction)
        if (abs(denom) <= geoTol) return emptyList() // parallel / coincident
        val t = cross(sub(b.point, a.point), b.direction) / denom
        return listOf(a.pointAt(t))
    }

    private fun isBoundarySample(point: Vec2, siteIndex: Int, sites: List<Vec3>, scoreTol: Float): Boolean {
        val s0 = weightedScore(point, sites[siteIndex])
        var tied = false

        for (k in sites.indices) {
            val s = weightedScore(point, sites[k])
            if (s < s0 - scoreTol) return false
            if (k != siteIndex && abs(s - s0) <= scoreTol) tied = true
        }

        return tied
    }

    private fun weightedScore(point: Vec2, site: Vec3): Float {
        val dx = point.x - site.x
        val dy = point.y - site.y
        return (dx * dx + dy * dy) / (site.z * site.z)
    }

    private fun uniqueAngles(values: List<Float>, tol: Float): List<Float> {
        if (values.isEmpty()) return emptyList()
        val sorted = values.map(::wrapAngle).sorted()
        val out = ArrayList<Float>(sorted.size)

        for (a in sorted) {
            if (out.isEmpty() || circularDistance(a, out.last()) > tol) {
                out += a
            }
        }

        if (out.size > 1 && circularDistance(out.first(), out.last()) <= tol) {
            out.removeAt(out.lastIndex)
        }

        return out
    }

    private fun uniqueScalars(values: List<Float>, tol: Float): List<Float> {
        if (values.isEmpty()) return emptyList()
        val sorted = values.sorted()
        val out = ArrayList<Float>(sorted.size)

        for (v in sorted) {
            if (out.isEmpty() || abs(v - out.last()) > tol) {
                out += v
            }
        }

        return out
    }

    private fun sampleParameter(t0: Float, t1: Float, span: Float): Float {
        return when {
            t0.isInfinite() && t1.isInfinite() -> 0f
            t0.isInfinite() -> t1 - span
            t1.isInfinite() -> t0 + span
            else -> 0.5f * (t0 + t1)
        }
    }

    private fun clipLineParameters(
        line: SupportLine,
        box: Aabb,
        initialLo: Float,
        initialHi: Float,
    ): Pair<Float, Float>? {
        var lo = initialLo
        var hi = initialHi
        val eps = 1e-6f

        fun slab(origin: Float, dir: Float, min: Float, max: Float): Boolean {
            if (abs(dir) <= eps) {
                return origin >= min - eps && origin <= max + eps
            }
            var a = (min - origin) / dir
            var b = (max - origin) / dir
            if (a > b) {
                val tmp = a
                a = b
                b = tmp
            }
            if (a > lo) lo = a
            if (b < hi) hi = b
            return lo <= hi
        }

        if (!slab(line.point.x, line.direction.x, box.min.x, box.max.x)) return null
        if (!slab(line.point.y, line.direction.y, box.min.y, box.max.y)) return null

        return lo to hi
    }

    private fun geometryScale(sites: List<Vec3>): Float {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        for (s in sites) {
            if (s.x < minX) minX = s.x
            if (s.y < minY) minY = s.y
            if (s.x > maxX) maxX = s.x
            if (s.y > maxY) maxY = s.y
        }

        val dx = maxX - minX
        val dy = maxY - minY
        return max(1f, sqrt(dx * dx + dy * dy))
    }

    private fun wrapAngle(angle: Float): Float {
        var a = angle % TAU
        if (a < 0f) a += TAU
        return a
    }

    private fun circularDistance(a: Float, b: Float): Float {
        val d = abs(wrapAngle(a) - wrapAngle(b))
        return min(d, TAU - d)
    }

    private fun sq(v: Float): Float = v * v

    private fun dot(a: Vec2, b: Vec2): Float = a.x * b.x + a.y * b.y
    private fun cross(a: Vec2, b: Vec2): Float = a.x * b.y - a.y * b.x
    private fun length(v: Vec2): Float = sqrt(dot(v, v))

    private fun add(a: Vec2, b: Vec2): Vec2 = Vec2(a.x + b.x, a.y + b.y)
    private fun sub(a: Vec2, b: Vec2): Vec2 = Vec2(a.x - b.x, a.y - b.y)
    private fun mul(v: Vec2, s: Float): Vec2 = Vec2(v.x * s, v.y * s)
    private fun perp(v: Vec2): Vec2 = Vec2(-v.y, v.x)

    private fun normalize(v: Vec2): Vec2 {
        val len = length(v)
        return if (len <= 1e-20f) Vec2(1f, 0f) else mul(v, 1f / len)
    }
}
