package afield.ui.notation

import afield.geometry.path.FLine
import afield.graphics.render.filled
import afield.graphics.render.pointed
import afield.graphics.render.stroked
import afield.math.linalg.Vec3
import afield.math.linalg.Vec4
import kotlin.math.hypot

/**
 * The wire format of the stroke layer: a document's living strokes as the JSON one window draws.
 *
 * [StrokeSketch] calls `encode` once per published frame, giving it every stroke's lines and the
 * width its pen uses for a line that sets no `thickness`, and sends the result as
 * `stroke-layer-frame` to each window showing the document; `strokeLayer.kt` in the frontend draws
 * it. Colour, dot radius and the overshoot ticks are worked out here rather than on the page, so the
 * FLine properties a stroke sets are read in this file.
 *
 * `indexedPositions` is the other half, flattening a stroke's lines into the array of x, y pairs that
 * [Stroke.closestOutput] searches, with lines marked `ignore` left out.
 */

/**
 * One frame of the stroke layer, as the JSON the page draws (`strokeLayer.kt` in the frontend).
 *
 * ```
 * {"trail":0.18,"items":[{"c":"rgba(…)","w":2,"s":1,"f":0,"p":0,"d":[0,x,y,1,x,y,2,x1,y1,x2,y2,x,y,3],
 *                         "k":[…],"r":[…],"o":[x0,y0,x1,y1,…],"ok":[…]}]}
 * ```
 *
 * `d` is the line's path: 0 moveTo, 1 lineTo, 2 cubicTo, 3 close. `k` is present when any node has its
 * own colour (one CSS colour or 0 per node), and the page then strokes segment by segment in the colour
 * of the node that ends each. `r` is the dot radius per node for a pointed line whose nodes set
 * `pointSize` (radius is `pointSize * 3`, the FLine convention). `o` is the overshoot ticks, worked out
 * here, with `ok` their colours. Coordinates are world units, rounded to a tenth.
 */
object StrokeLayerFrames {

    /** The frame for [trail] and every stroke's lines with its default width. */
    fun encode(trail: Double, strokes: List<Pair<List<FLine>, Double>>): String {
        val out = StringBuilder(4096)
        out.append("{\"trail\":").append(num(trail)).append(",\"items\":[")
        var first = true
        for ((lines, width) in strokes) for (line in lines) {
            if (line.nodes.isEmpty()) continue
            if (!first) out.append(',')
            first = false
            item(out, line, width)
        }
        out.append("]}")
        return out.toString()
    }

    /** The positions [Stroke.closestOutput] searches: every node end point of [lines] not marked [FLine.ignore], as x, y pairs. */
    fun indexedPositions(lines: List<FLine>): FloatArray {
        var n = 0
        for (l in lines) if (l.properties["ignore"] != true) n += l.nodes.size
        val a = FloatArray(n * 2)
        var i = 0
        for (l in lines) {
            if (l.properties["ignore"] == true) continue
            for (node in l.nodes) {
                val to = node.toOrNull
                if (to == null) {
                    a[i++] = Float.NaN; a[i++] = Float.NaN
                } else {
                    a[i++] = to.x; a[i++] = to.y
                }
            }
        }
        return if (a.any { it.isNaN() }) a.filterNot { it.isNaN() }.toFloatArray() else a
    }

    private fun item(out: StringBuilder, line: FLine, defaultWidth: Double) {
        val color = line.properties["color"] as? Vec4
        val width = (line.properties["thickness"] as? Number)?.toDouble() ?: defaultWidth
        out.append("{\"c\":\"").append(css(color)).append("\",\"w\":").append(num(width))
        out.append(",\"s\":").append(if (line.stroked) 1 else 0)
        out.append(",\"f\":").append(if (line.filled) 1 else 0)
        val pointed = line.pointed
        out.append(",\"p\":").append(if (pointed) 1 else 0)
        if (pointed) {
            val size = (line.properties["pointSize"] as? Number)?.toDouble() ?: 1.0
            out.append(",\"pr\":").append(num(size * 3))
        }
        out.append(",\"d\":[")
        var firstOp = true
        fun op(code: Int, vararg v: Vec3) {
            if (!firstOp) out.append(',')
            firstOp = false
            out.append(code)
            for (p in v) out.append(',').append(num(p.x.toDouble())).append(',').append(num(p.y.toDouble()))
        }
        for (node in line.nodes) when (node) {
            is FLine.MoveTo -> op(0, node.to)
            is FLine.LineTo -> op(1, node.to)
            is FLine.CubicTo -> op(2, node.c1, node.c2, node.to)
            is FLine.Close -> op(3)
        }
        out.append(']')

        val nodeColors = line.nodes.map { it.properties["color"] as? Vec4 }
        val hasNodeColors = nodeColors.any { it != null }
        if (hasNodeColors) {
            out.append(",\"k\":[")
            nodeColors.forEachIndexed { i, c ->
                if (i > 0) out.append(',')
                if (c == null) out.append('0') else out.append('"').append(css(c)).append('"')
            }
            out.append(']')
        }
        if (pointed && line.nodes.any { it.properties["pointSize"] != null }) {
            out.append(",\"r\":[")
            val base = (line.properties["pointSize"] as? Number)?.toDouble() ?: 1.0
            line.nodes.forEachIndexed { i, node ->
                if (i > 0) out.append(',')
                out.append(num(((node.properties["pointSize"] as? Number)?.toDouble() ?: base) * 3))
            }
            out.append(']')
        }
        if (line.stroked) overshoots(out, line, nodeColors, hasNodeColors, color)
        out.append('}')
    }

    /**
     * The overshoot ticks of every open subpath: from each end, straight on along the end's tangent,
     * for the subpath's last node's [FLine.Node.overshoot], else the line's [FLine.overshoot].
     */
    private fun overshoots(out: StringBuilder, line: FLine, nodeColors: List<Vec4?>, perNode: Boolean, lineColor: Vec4?) {
        val lineOvershoot = (line.properties["overshoot"] as? Number)?.toDouble()
        val nodes = line.nodes
        val ticks = ArrayList<Double>()
        val tickColors = ArrayList<Vec4?>()
        var start = 0
        while (start < nodes.size) {
            var end = start + 1
            while (end < nodes.size && nodes[end] !is FLine.MoveTo) end++
            // nodes[start until end] is one subpath
            val last = end - 1
            val closed = nodes[last] is FLine.Close
            if (!closed && last > start) {
                val len = (nodes[last].properties["overshoot"] as? Number)?.toDouble() ?: lineOvershoot ?: 0.0
                if (len > 0) {
                    val p0 = nodes[start].to
                    val n1 = nodes[start + 1]
                    val q0 = if (n1 is FLine.CubicTo && n1.c1.distance(p0) > 1e-4f) n1.c1 else n1.to
                    val pn = nodes[last].to
                    val nl = nodes[last]
                    val qn = if (nl is FLine.CubicTo && nl.c2.distance(pn) > 1e-4f) nl.c2 else line.endPointOf(last - 1)
                    val c = if (perNode) nodeColors[last] ?: lineColor else lineColor
                    if (tick(ticks, p0, q0, len)) tickColors += c
                    if (tick(ticks, pn, qn, len)) tickColors += c
                }
            }
            start = end
        }
        if (ticks.isEmpty()) return
        out.append(",\"o\":[")
        ticks.forEachIndexed { i, v ->
            if (i > 0) out.append(',')
            out.append(num(v))
        }
        out.append(']')
        if (perNode) {
            out.append(",\"ok\":[")
            tickColors.forEachIndexed { i, c ->
                if (i > 0) out.append(',')
                out.append('"').append(css(c)).append('"')
            }
            out.append(']')
        }
    }

    /** A tick from [p] away from [q], [len] long. */
    private fun tick(into: MutableList<Double>, p: Vec3, q: Vec3, len: Double): Boolean {
        val dx = (p.x - q.x).toDouble()
        val dy = (p.y - q.y).toDouble()
        val d = hypot(dx, dy)
        if (d < 0.01) return false
        into += p.x.toDouble(); into += p.y.toDouble()
        into += p.x + dx / d * len; into += p.y + dy / d * len
        return true
    }

    /** [c] as a CSS colour; white when null (an FLine's default). */
    fun css(c: Vec4?): String {
        if (c == null) return "rgba(255,255,255,1)"
        fun ch(v: Float) = Math.round(v.coerceIn(0f, 1f) * 255)
        return "rgba(${ch(c.x)},${ch(c.y)},${ch(c.z)},${num(c.w.coerceIn(0f, 1f).toDouble(), 3)})"
    }

    private fun num(v: Double, places: Int = 1): String {
        if (!v.isFinite()) return "0"
        val scale = if (places == 1) 10.0 else 1000.0
        val r = Math.round(v * scale) / scale
        val l = r.toLong()
        return if (r == l.toDouble()) l.toString() else r.toString()
    }
}
