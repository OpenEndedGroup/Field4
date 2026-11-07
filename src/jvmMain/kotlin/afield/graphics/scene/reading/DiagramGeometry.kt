package afield.graphics.scene.reading

import afield.geometry.path.FLine
import afield.graphics.render.*
import afield.math.linalg.Vector3f
import afield.math.linalg.Vector4f
import java.nio.ByteBuffer
import kotlin.math.*

/** Physical widths/distances are metres in the diagram's local frame; colours are linear RGBA. */
data class DiagramInk(
    val width: Float = .0015f,
    val color: Vector4f = Vector4f(.35f, .55f, .75f, 1f),
    val dashPeriod: Float = 0f,
    val dashDuty: Float = .55f,
    val dashPhase: Float = 0f,
    val drawFrom: Float = 0f,
    val drawTo: Float = 1f,
    val arrow: Float = 0f,
)

enum class DiagramPointShape {
    DISC,
    RING,
    DIAMOND,
    LEAF,
}

/** CPU centreline samples only. The GPU expands a connected, antialiased triangle ribbon. */
class DiagramGeometry(val tolerance: Float = .0003f) {
    data class Vertex(val p: Vector3f, val color: Vector4f, val width: Float)

    data class Segment(
        val previous: Vector3f,
        val a: Vertex,
        val b: Vertex,
        val next: Vector3f,
        val s0: Float,
        val s1: Float,
        val ink: DiagramInk,
        val first: Boolean,
        val last: Boolean,
        val length: Float,
    )

    data class Point(
        val p: Vector3f,
        val radius: Float,
        val color: Vector4f,
        val shape: DiagramPointShape,
        val ringWidth: Float = .25f,
        val rotation: Float = 0f,
        val aspect: Float = 1f,
    )

    val segments = ArrayList<Segment>()
    val points = ArrayList<Point>()

    fun clear() {
        segments.clear()
        points.clear()
    }

    /**
     * MoveTo starts a new strip; Close is resolved per contour. Node colour/width override line
     * ink.
     */
    fun stroke(line: FLine, ink: DiagramInk = DiagramInk(), widthScale: Float = 1f) {
        require(widthScale.isFinite() && widthScale >= 0)
        require(tolerance.isFinite() && tolerance > 0)
        if (ink.drawTo <= ink.drawFrom) return
        val baseColor = (line.properties["color"] as? Vector4f) ?: ink.color
        val baseWidth = (line.properties["thickness"] as? Number)?.toFloat() ?: ink.width
        var contour = ArrayList<Vertex>()
        fun vertex(node: FLine.Node, p: Vector3f) =
            Vertex(
                Vector3f(p),
                Vector4f((node.properties["color"] as? Vector4f) ?: baseColor),
                (widthScale * ((node.properties["thickness"] as? Number)?.toFloat() ?: baseWidth))
                    .coerceAtLeast(
                        0f,
                    ),
            )
        fun finish(closed: Boolean = false) {
            emit(contour, ink, closed)
            contour = ArrayList()
        }
        for (node in line.nodes) when (node) {
            is FLine.MoveTo -> {
                finish()
                contour.add(vertex(node, node.to))
            }
            is FLine.LineTo -> contour.add(vertex(node, node.to))
            is FLine.CubicTo -> {
                if (contour.isEmpty()) continue
                val start = contour.last()
                val end = vertex(node, node.to)
                fun split(
                    a: Vector3f,
                    b: Vector3f,
                    c: Vector3f,
                    d: Vector3f,
                    t0: Float,
                    t1: Float,
                    depth: Int,
                ) {
                    val chord = Vector3f(d).sub(a)
                    val length = chord.length()
                    fun deviation(p: Vector3f) =
                        if (length < 1e-8f) p.distance(a)
                        else Vector3f(p).sub(a).cross(chord).length() / length
                    if (depth >= 12 || max(deviation(b), deviation(c)) <= tolerance) {
                        contour.add(
                            Vertex(
                                Vector3f(d),
                                Vector4f(start.color).lerp(end.color, t1),
                                start.width + (end.width - start.width) * t1,
                            ),
                        )
                        return
                    }
                    val ab = Vector3f(a).lerp(b, .5f)
                    val bc = Vector3f(b).lerp(c, .5f)
                    val cd = Vector3f(c).lerp(d, .5f)
                    val abc = Vector3f(ab).lerp(bc, .5f)
                    val bcd = Vector3f(bc).lerp(cd, .5f)
                    val mid = Vector3f(abc).lerp(bcd, .5f)
                    val tm = (t0 + t1) * .5f
                    split(a, ab, abc, mid, t0, tm, depth + 1)
                    split(mid, bcd, cd, d, tm, t1, depth + 1)
                }
                split(start.p, node.c1, node.c2, end.p, 0f, 1f, 0)
            }
            is FLine.Close -> {
                if (
                    contour.size > 1 && contour.last().p.distanceSquared(contour.first().p) > 1e-16f
                )
                    contour.add(contour.first())
                finish(true)
            }
        }
        finish()
    }

    /**
     * Dots are authored FLine nodes, not the adaptive stroke samples. Close carries no position.
     */
    fun markers(
        line: FLine,
        radius: Float = .003f,
        color: Vector4f = Vector4f(1f),
        shape: DiagramPointShape = DiagramPointShape.DISC,
    ) {
        for (node in line.nodes) if (node !is FLine.Close) {
            val p = node.to
            val size =
                (node.properties["pointSize"] as? Number)?.toFloat()
                    ?: (line.properties["pointSize"] as? Number)?.toFloat()
                    ?: radius * 2
            val tint =
                (node.properties["color"] as? Vector4f)
                    ?: (line.properties["color"] as? Vector4f)
                    ?: color
            val form = (node.properties["sdfShape"] as? DiagramPointShape) ?: shape
            if (size > 0 && p.x.isFinite() && p.y.isFinite() && p.z.isFinite())
                points.add(Point(Vector3f(p), size * .5f, Vector4f(tint), form))
        }
    }

    private fun emit(input: List<Vertex>, ink: DiagramInk, closed: Boolean) {
        val v = ArrayList<Vertex>()
        for (p in input) {
            require(p.p.x.isFinite() && p.p.y.isFinite() && p.p.z.isFinite()) {
                "Nonfinite FLine point"
            }
            if (v.isEmpty() || v.last().p.distanceSquared(p.p) > 1e-16f) v.add(p)
        }
        if (v.size < 2) return
        val lengths = FloatArray(v.size)
        for (i in 1 until v.size) lengths[i] = lengths[i - 1] + v[i].p.distance(v[i - 1].p)
        val total = lengths.last()
        for (i in 0 until v.lastIndex) segments.add(
            Segment(
                if (i > 0) v[i - 1].p else if (closed) v[v.lastIndex - 1].p else v[i].p,
                v[i],
                v[i + 1],
                if (i + 2 < v.size) v[i + 2].p else if (closed) v[1].p else v[i + 1].p,
                lengths[i],
                lengths[i + 1],
                ink,
                !closed && i == 0,
                !closed && i + 1 == v.lastIndex,
                total,
            ),
        )
        if (ink.arrow > 0 && !closed && ink.drawTo > 0) {
            val tipAt = ink.drawTo.coerceIn(0f, 1f) * total
            val i = (1 until lengths.size).firstOrNull { lengths[it] >= tipAt } ?: v.lastIndex
            val t = ((tipAt - lengths[i - 1]) / (lengths[i] - lengths[i - 1])).coerceIn(0f, 1f)
            val tip = Vector3f(v[i - 1].p).lerp(v[i].p, t)
            val axis = Vector3f(v[i].p).sub(v[i - 1].p).normalize()
            var side = Vector3f(-axis.y, axis.x, 0f)
            if (side.lengthSquared() < 1e-8f) side = Vector3f(1f, 0f, 0f) else side.normalize()
            val arrowInk = ink.copy(drawFrom = 0f, drawTo = 1f, dashPeriod = 0f, arrow = 0f)
            for (sign in listOf(-1f, 1f)) {
                val tail = Vector3f(tip).fma(-ink.arrow, axis).fma(sign * ink.arrow * .45f, side)
                emit(
                    listOf(
                        Vertex(tail, Vector4f(v[i].color), v[i].width),
                        Vertex(tip, Vector4f(v[i].color), v[i].width),
                    ),
                    arrowInk,
                    false,
                )
            }
        }
    }

    /** Eight vec4 records per segment; no CPU-generated triangles. */
    fun writeSegments(b: ByteBuffer) {
        fun vector(v: Vector3f, w: Float) {
            b.putFloat(v.x).putFloat(v.y).putFloat(v.z).putFloat(w)
        }
        fun color(v: Vector4f) {
            b.putFloat(v.x).putFloat(v.y).putFloat(v.z).putFloat(v.w)
        }
        for (s in segments) {
            vector(s.previous, s.a.width * .5f)
            vector(s.a.p, s.s0)
            vector(s.b.p, s.s1)
            vector(s.next, s.b.width * .5f)
            color(s.a.color)
            color(s.b.color)
            b.putFloat(s.ink.dashPeriod)
                .putFloat(s.ink.dashDuty)
                .putFloat(s.ink.dashPhase)
                .putFloat((if (s.first) 1 else 0).toFloat() + (if (s.last) 2 else 0))
            b.putFloat(s.ink.drawFrom.coerceIn(0f, 1f) * s.length)
                .putFloat(s.ink.drawTo.coerceIn(0f, 1f) * s.length)
                .putFloat(0f)
                .putFloat(0f)
        }
    }

    /** Three vec4 records per marker: position/radius, colour, shape parameters. */
    fun writePoints(b: ByteBuffer) {
        for (p in points) {
            b.putFloat(p.p.x).putFloat(p.p.y).putFloat(p.p.z).putFloat(p.radius)
            b.putFloat(p.color.x).putFloat(p.color.y).putFloat(p.color.z).putFloat(p.color.w)
            b.putFloat(p.shape.ordinal.toFloat())
                .putFloat(p.ringWidth)
                .putFloat(p.rotation)
                .putFloat(p.aspect)
        }
    }
}
