package afield

import afield.stdlib.Vec2
import afield.stdlib.vec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Cutting the drawing with a line: the geometry behind reading notation by intersection — a
 * vertical line is an instant, a horizontal one a pitch (or whatever a [NotationSpace] says the
 * axes mean).
 *
 * Every mark is flattened once per scene revision into world-space polylines with its rotation
 * applied ([MarkGeometry]); probes then only walk segments. The results are plain values, safe to
 * keep and to read from any thread.
 */

/** Which way a probe line runs. */
enum class ProbeAxis { VERTICAL, HORIZONTAL }

/**
 * Where a probe line meets a mark.
 *
 * For an open mark (a stroke, line or arrow) there is one hit per crossing, and [span] is null.
 * For a closed mark (a shape, a text, a frame) there is one hit per stretch of the probe that lies
 * inside it: [span] is that stretch and [value] its middle — a piano-roll rectangle cut at an
 * instant gives the pitches it covers.
 */
class Hit internal constructor(
    val mark: Mark,
    /** The crossing in world coordinates. */
    val point: Vec2,
    /** Position along the probe, in the probe's units (world y for a vertical world probe; pitch for a score's). */
    val value: Double,
    /** For closed marks: the stretch of the probe inside the mark, in the same units as [value]. */
    val span: ClosedFloatingPointRange<Double>?,
    /** How far along the mark's own path the crossing is, 0 at its first point and 1 at its last. */
    val along: Double,
    /** The mark's rate of change across the probe there, in the probe's units: d(value)/d(position). */
    val slope: Double,
    /** Pen pressure at the crossing, for a stroke that recorded it (0..1); otherwise null. */
    val pressure: Double?,
) {
    val isSpan: Boolean get() = span != null
    override fun toString(): String =
        if (span != null) "Hit(${mark.label ?: mark.id} ${fmt(span.start)}..${fmt(span.endInclusive)})"
        else "Hit(${mark.label ?: mark.id} ${fmt(value)} slope ${fmt(slope)})"
    private fun fmt(d: Double) = "%.3f".format(d)
}

/** A mark flattened to world-space polylines, rotation applied. */
internal class MarkGeometry(
    val paths: List<List<Vec2>>,
    val closed: Boolean,
    /** Cumulative arc length at each point of the first path (for [Hit.along]). */
    val arc: DoubleArray,
    val pressures: DoubleArray?,
    val minX: Double, val maxX: Double, val minY: Double, val maxY: Double,
) {
    val length: Double get() = if (arc.isEmpty()) 0.0 else arc.last()

    companion object {
        private val cache = ConcurrentHashMap<JsonObject, MarkGeometry>()

        /** Geometry for [e]; cached by the element's content, so it is computed once per revision. */
        fun of(e: JsonObject): MarkGeometry {
            cache[e]?.let { return it }
            if (cache.size > 20_000) cache.clear()
            return build(e).also { cache[e] = it }
        }

        private fun build(e: JsonObject): MarkGeometry {
            val type = MarkType.of(e.string("type"))
            val raw: List<Vec2>
            val closed: Boolean
            when {
                type.isLinear -> { raw = e.worldPoints(); closed = false }
                else -> {
                    val b = e.worldBounds()
                    raw = when (type) {
                        MarkType.ELLIPSE -> (0..64).map { i ->
                            val t = 2 * Math.PI * i / 64
                            vec(b.x + b.w / 2 + b.w / 2 * cos(t), b.y + b.h / 2 + b.h / 2 * sin(t))
                        }
                        MarkType.DIAMOND -> listOf(
                            vec(b.x + b.w / 2, b.y), vec(b.x + b.w, b.y + b.h / 2),
                            vec(b.x + b.w / 2, b.y + b.h), vec(b.x, b.y + b.h / 2), vec(b.x + b.w / 2, b.y),
                        )
                        else -> listOf(vec(b.x, b.y), vec(b.x + b.w, b.y), vec(b.x + b.w, b.y + b.h), vec(b.x, b.y + b.h), vec(b.x, b.y))
                    }
                    closed = true
                }
            }
            // Excalidraw rotates an element about the centre of its bounds.
            val angle = e.double("angle") ?: 0.0
            val pts = if (abs(angle) < 1e-9 || raw.isEmpty()) raw else {
                val b = e.worldBounds()
                val cx = b.x + b.w / 2; val cy = b.y + b.h / 2
                val c = cos(angle); val s = sin(angle)
                raw.map { p -> val dx = p.x - cx; val dy = p.y - cy; vec(cx + dx * c - dy * s, cy + dx * s + dy * c) }
            }
            val arc = DoubleArray(pts.size)
            for (i in 1 until pts.size) arc[i] = arc[i - 1] + hypot((pts[i].x - pts[i - 1].x).toDouble(), (pts[i].y - pts[i - 1].y).toDouble())
            val pressures = (e["pressures"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.doubleOrNull }
                ?.takeIf { it.size == pts.size && it.isNotEmpty() && type == MarkType.STROKE }?.toDoubleArray()
            var minX = Double.POSITIVE_INFINITY; var maxX = Double.NEGATIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
            for (p in pts) { minX = min(minX, p.x.toDouble()); maxX = max(maxX, p.x.toDouble()); minY = min(minY, p.y.toDouble()); maxY = max(maxY, p.y.toDouble()) }
            return MarkGeometry(listOf(pts), closed, arc, pressures, minX, maxX, minY, maxY)
        }
    }
}

/** One raw crossing of a probe line with a polyline segment, before mapping into a space. */
internal class Crossing(val x: Double, val y: Double, val along: Double, val dAcrossDProbe: Double, val pressure: Double?)

internal object Probe {
    /**
     * Crossings of the line `axis = at` with [g]'s path. Segments are half-open, so a probe
     * passing exactly through a vertex counts it once.
     */
    fun crossings(g: MarkGeometry, axis: ProbeAxis, at: Double): List<Crossing> {
        val out = ArrayList<Crossing>()
        val pts = g.paths.firstOrNull() ?: return out
        if (axis == ProbeAxis.VERTICAL && (at < g.minX || at > g.maxX)) return out
        if (axis == ProbeAxis.HORIZONTAL && (at < g.minY || at > g.maxY)) return out
        for (i in 0 until pts.size - 1) {
            val p = pts[i]; val q = pts[i + 1]
            val a0 = if (axis == ProbeAxis.VERTICAL) p.x.toDouble() else p.y.toDouble()
            val a1 = if (axis == ProbeAxis.VERTICAL) q.x.toDouble() else q.y.toDouble()
            val last = i == pts.size - 2 && !g.closed
            val inside = (a0 <= at && at < a1) || (a1 <= at && at < a0) || (last && at == a1 && a0 != a1)
            if (!inside) continue
            val f = if (a1 == a0) 0.0 else (at - a0) / (a1 - a0)
            val x = p.x + (q.x - p.x) * f
            val y = p.y + (q.y - p.y) * f
            val da = a1 - a0
            val db = if (axis == ProbeAxis.VERTICAL) (q.y - p.y).toDouble() else (q.x - p.x).toDouble()
            val along = if (g.length <= 0) 0.0 else (g.arc[i] + (g.arc[i + 1] - g.arc[i]) * f) / g.length
            val pressure = g.pressures?.let { it[i] + (it[i + 1] - it[i]) * f }
            out += Crossing(x, y, along, if (da == 0.0) Double.POSITIVE_INFINITY else db / da, pressure)
        }
        return out
    }

    /** Crossings of the segment [a]–[b] with [g]'s path, ordered along the segment. */
    fun crossings(g: MarkGeometry, a: Vec2, b: Vec2): List<Pair<Double, Crossing>> {
        val out = ArrayList<Pair<Double, Crossing>>()
        val pts = g.paths.firstOrNull() ?: return out
        val rx = (b.x - a.x).toDouble(); val ry = (b.y - a.y).toDouble()
        for (i in 0 until pts.size - 1) {
            val p = pts[i]; val q = pts[i + 1]
            val sx = (q.x - p.x).toDouble(); val sy = (q.y - p.y).toDouble()
            val den = rx * sy - ry * sx
            if (abs(den) < 1e-12) continue
            val qpx = (p.x - a.x).toDouble(); val qpy = (p.y - a.y).toDouble()
            val t = (qpx * sy - qpy * sx) / den
            val u = (qpx * ry - qpy * rx) / den
            if (t < 0 || t > 1 || u < 0 || u >= 1) continue
            val along = if (g.length <= 0) 0.0 else (g.arc[i] + (g.arc[i + 1] - g.arc[i]) * u) / g.length
            val pressure = g.pressures?.let { it[i] + (it[i + 1] - it[i]) * u }
            out += t to Crossing(a.x + rx * t, a.y + ry * t, along, 0.0, pressure)
        }
        return out.sortedBy { it.first }
    }
}

/**
 * A region of the drawing with its own axes — a score, a graph, a map. Horizontal position in the
 * region becomes the [x] range and vertical position the [y] range, so
 * `notation.space("score", x = 0.0..8.0, y = 84.0..48.0)` reads the frame named "score" as eight
 * beats across and a MIDI pitch range down (high at the top, because Field's y grows downward).
 *
 * The region is looked up again on every question, so a score that is a box's frame, or a frame
 * someone drags, is read where it is now.
 */
class NotationSpace internal constructor(
    private val notation: Notation,
    private val region: () -> Rect?,
    val x: ClosedFloatingPointRange<Double>,
    val y: ClosedFloatingPointRange<Double>,
) {
    /** The region in world coordinates, as it is now (null if it has gone). */
    val bounds: Rect? get() = region()

    fun toSpace(world: Vec2): Vec2 = region()!!.let { r ->
        vec(x.start + (world.x - r.x) / r.w * (x.endInclusive - x.start), y.start + (world.y - r.y) / r.h * (y.endInclusive - y.start))
    }

    fun toWorld(sx: Double, sy: Double): Vec2 = region()!!.let { r ->
        vec(r.x + (sx - x.start) / (x.endInclusive - x.start) * r.w, r.y + (sy - y.start) / (y.endInclusive - y.start) * r.h)
    }

    private fun worldX(sx: Double, r: Rect) = r.x + (sx - x.start) / (x.endInclusive - x.start) * r.w
    private fun worldY(sy: Double, r: Rect) = r.y + (sy - y.start) / (y.endInclusive - y.start) * r.h
    private fun spaceX(wx: Double, r: Rect) = x.start + (wx - r.x) / r.w * (x.endInclusive - x.start)
    private fun spaceY(wy: Double, r: Rect) = y.start + (wy - r.y) / r.h * (y.endInclusive - y.start)

    /**
     * Marks that overlap the region, excluding the frame (or shape) that defines it. A stroke that
     * runs out of the region still counts; what lies outside is clipped away by the cuts and [events].
     */
    val marks: List<Mark> get() {
        val r = region() ?: return emptyList()
        val defining = notation.marks.firstOrNull { !it.kind.isLinear && it.bounds == r }?.id
        return notation.marks.filter { m ->
            m.id != defining && m.bounds.let { b -> b.x <= r.x + r.w && b.x + b.w >= r.x && b.y <= r.y + r.h && b.y + b.h >= r.y }
        }
    }

    /** Cut the region at [at] across: what is sounding at that instant, and at what [y] values. */
    fun vertical(at: Double): List<Hit> {
        val r = region() ?: return emptyList()
        if (at < min(x.start, x.endInclusive) || at > max(x.start, x.endInclusive)) return emptyList()
        return clip(notation.cut(marks, ProbeAxis.VERTICAL, worldX(at, r)) { wy -> spaceY(wy, r) }, ProbeAxis.VERTICAL, r)
    }

    /** Cut the region at [at] along: where marks pass that [y] value, as [x] values. */
    fun horizontal(at: Double): List<Hit> {
        val r = region() ?: return emptyList()
        if (at < min(y.start, y.endInclusive) || at > max(y.start, y.endInclusive)) return emptyList()
        return clip(notation.cut(marks, ProbeAxis.HORIZONTAL, worldY(at, r)) { wx -> spaceX(wx, r) }, ProbeAxis.HORIZONTAL, r)
    }

    /** Drops hits outside the region, clips spans to it, and rescales slopes into the space's units. */
    private fun clip(hits: List<Hit>, axis: ProbeAxis, r: Rect): List<Hit> {
        val range = if (axis == ProbeAxis.VERTICAL) y else x
        val lo = min(range.start, range.endInclusive); val hi = max(range.start, range.endInclusive)
        val sx = (x.endInclusive - x.start) / r.w
        val sy = (y.endInclusive - y.start) / r.h
        val k = if (axis == ProbeAxis.VERTICAL) sy / sx else sx / sy
        return hits.mapNotNull { h ->
            val span = h.span
            if (span != null) {
                val a = max(span.start, lo); val b = min(span.endInclusive, hi)
                if (a > b) null else Hit(h.mark, h.point, (a + b) / 2, a..b, h.along, 0.0, h.pressure)
            } else if (h.value < lo || h.value > hi) null
            else Hit(h.mark, h.point, h.value, null, h.along, h.slope * k, h.pressure)
        }
    }

    /**
     * Every mark in the region as an event: where it starts and ends along [x], and its [y] value
     * at its start and end — a note's onset, offset and pitch; a gesture's first and last pitch.
     * Sorted by start.
     */
    val events: List<NotationEvent> get() {
        val r = region() ?: return emptyList()
        return marks.mapNotNull { m ->
            val g = MarkGeometry.of(m.json)
            if (g.paths.firstOrNull().isNullOrEmpty()) return@mapNotNull null
            val xl = min(x.start, x.endInclusive); val xh = max(x.start, x.endInclusive)
            val start = spaceX(g.minX, r).coerceIn(xl, xh)
            val end = spaceX(g.maxX, r).coerceIn(xl, xh)
            val (y0, y1) = if (g.closed) {
                val c = spaceY((g.minY + g.maxY) / 2, r); c to c
            } else {
                val pts = g.paths.first()
                val first = pts.minBy { it.x }; val last = pts.maxBy { it.x }
                spaceY(first.y.toDouble(), r) to spaceY(last.y.toDouble(), r)
            }
            NotationEvent(m, min(start, end), max(start, end), y0, y1,
                spaceY(g.minY, r).let { a -> spaceY(g.maxY, r).let { b -> min(a, b)..max(a, b) } })
        }.sortedBy { it.start }
    }

    /** Events starting in [from, until) — what a playhead moving from [from] to [until] has just reached. */
    fun between(from: Double, until: Double): List<NotationEvent> = events.filter { it.start >= from && it.start < until }

    /** Events sounding at [at]: started at or before it and not yet ended. */
    fun sounding(at: Double): List<NotationEvent> = events.filter { it.start <= at && it.end >= at }
}

/**
 * A mark read as an event in a [NotationSpace]: it [start]s and [end]s along the space's x axis,
 * with [value] at its start and [endValue] at its end on the y axis, and covers [range] on y.
 */
class NotationEvent internal constructor(
    val mark: Mark,
    val start: Double,
    val end: Double,
    val value: Double,
    val endValue: Double,
    val range: ClosedFloatingPointRange<Double>,
) {
    val duration: Double get() = end - start
    override fun toString(): String = "Event(${mark.label ?: mark.kind.name.lowercase()} %.3f..%.3f at %.2f)".format(start, end, value)
}
