package afield.graphics.nile

import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.graphics.render.filled
import afield.graphics.render.stroked
import afield.graphics.render.thickness
import afield.math.linalg.vec
import afield.media.image.util.PngEncoder
import org.jetbrains.skia.Data
import org.jetbrains.skia.svg.SVGDOM
import org.w3c.dom.Element
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * An affine transform as SVG writes it, `matrix(a b c d e f)`: `x' = a x + c y + e`,
 * `y' = b x + d y + f`.
 */
data class SvgTransform(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {

    /** This transform after [o]: `(this * o)(p) = this(o(p))`, the order an SVG transform list composes in. */
    operator fun times(o: SvgTransform) = SvgTransform(
        a * o.a + c * o.b, b * o.a + d * o.b,
        a * o.c + c * o.d, b * o.c + d * o.d,
        a * o.e + c * o.f + e, b * o.e + d * o.f + f,
    )

    fun x(x: Double, y: Double) = a * x + c * y + e
    fun y(x: Double, y: Double) = b * x + d * y + f

    /** The factor lengths scale by on average, `sqrt|det|`, which is what a stroke width becomes. */
    val scale get() = sqrt(abs(a * d - b * c))

    /** [bz] with every control point transformed; exact, since Béziers are affine invariant. */
    fun apply(bz: Bezier) = Bezier(DoubleArray(bz.p.size) { i ->
        val x = bz.p[i - i % 2]
        val y = bz.p[i - i % 2 + 1]
        if (i % 2 == 0) x(x, y) else y(x, y)
    })

    companion object {
        val IDENTITY = SvgTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        fun translate(x: Double, y: Double) = SvgTransform(1.0, 0.0, 0.0, 1.0, x, y)
        fun scale(x: Double, y: Double = x) = SvgTransform(x, 0.0, 0.0, y, 0.0, 0.0)
        fun rotate(degrees: Double): SvgTransform {
            val r = degrees * PI / 180
            return SvgTransform(cos(r), sin(r), -sin(r), cos(r), 0.0, 0.0)
        }

        /**
         * An SVG `transform` attribute: a list of `matrix`, `translate`, `scale`, `rotate` (with an
         * optional centre), `skewX` and `skewY`, composed left to right.
         */
        fun parse(s: String?): SvgTransform {
            if (s.isNullOrBlank()) return IDENTITY
            var t = IDENTITY
            for (m in Regex("""([a-zA-Z]+)\s*\(([^)]*)\)""").findAll(s)) {
                val n = numbers(m.groupValues[2])
                fun at(i: Int, default: Double = 0.0) = n.getOrElse(i) { default }
                t *= when (m.groupValues[1]) {
                    "matrix" -> SvgTransform(at(0), at(1), at(2), at(3), at(4), at(5))
                    "translate" -> translate(at(0), at(1))
                    "scale" -> scale(at(0), at(1, at(0)))
                    "rotate" -> if (n.size >= 3) translate(at(1), at(2)) * rotate(at(0)) * translate(-at(1), -at(2)) else rotate(at(0))
                    "skewX" -> SvgTransform(1.0, 0.0, tan(at(0) * PI / 180), 1.0, 0.0, 0.0)
                    "skewY" -> SvgTransform(1.0, tan(at(0) * PI / 180), 0.0, 1.0, 0.0, 0.0)
                    else -> IDENTITY
                }
            }
            return t
        }

        internal fun numbers(s: String): List<Double> =
            Regex("""[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""").findAll(s).map { it.value.toDouble() }.toList()
    }
}

/** One subpath of an SVG path: its segments in order, and whether it ended with `z`. */
class SvgSubpath(val beziers: List<Bezier>, val closed: Boolean) {
    /** The segments with the closing line added where the subpath does not end where it began, as a fill sees it. */
    fun closedBeziers(): List<Bezier> {
        if (beziers.isEmpty()) return beziers
        val s = beziers.first().p
        val e = beziers.last().p
        val ex = e[e.size - 2]
        val ey = e[e.size - 1]
        return if (ex == s[0] && ey == s[1]) beziers else beziers + Bezier(doubleArrayOf(ex, ey, s[0], s[1]))
    }
}

/**
 * One painted SVG element after its own and every ancestor's `transform` and inherited style:
 * geometry in the document's user (viewBox) units, colours straight from the cascade with
 * `opacity` and `fill-opacity` / `stroke-opacity` folded into their alpha.
 */
class SvgShape(
    val element: String,
    val id: String?,
    val subpaths: List<SvgSubpath>,
    val fill: Rgba?,
    val fillRule: FillRule,
    val stroke: Rgba?,
    /** In user units, already scaled by the element's transform. */
    val strokeWidth: Double,
) {
    /** Every subpath closed, as one list: what the fill rasterises. */
    fun fillBeziers(): List<Bezier> = subpaths.flatMap { it.closedBeziers() }

    /** [t] applied to the geometry and the stroke width. */
    fun transformed(t: SvgTransform) = SvgShape(
        element, id, subpaths.map { s -> SvgSubpath(s.beziers.map(t::apply), s.closed) },
        fill, fillRule, stroke, strokeWidth * t.scale,
    )
}

/** An SVG document read by [GeziraSvg.parse]: its view box, its painted shapes in paint order, and what was skipped. */
class SvgDocument(val viewBox: DoubleArray, val width: Double, val height: Double, val shapes: List<SvgShape>, val skipped: Map<String, Int>) {

    /** The `preserveAspectRatio="xMidYMid meet"` map from the view box into a `w × h` pixel viewport. */
    fun fit(w: Int, h: Int): SvgTransform {
        val s = min(w / viewBox[2], h / viewBox[3])
        return SvgTransform.translate((w - s * viewBox[2]) / 2, (h - s * viewBox[3]) / 2) * SvgTransform.scale(s) *
            SvgTransform.translate(-viewBox[0], -viewBox[1])
    }

    /** The shapes in pixels of a `w × h` viewport. */
    fun shapesIn(w: Int, h: Int): List<SvgShape> = fit(w, h).let { t -> shapes.map { it.transformed(t) } }

    /** Number of path segments over all shapes. */
    val segments get() = shapes.sumOf { s -> s.subpaths.sumOf { it.beziers.size } }

    override fun toString() =
        "SvgDocument ${viewBox.joinToString(" ") { "%.6g".format(it) }}: ${shapes.size} shapes, $segments segments" +
            if (skipped.isEmpty()) "" else ", skipped $skipped"
}

/**
 * SVG into Gezira: a reader for SVG documents (path data with every command including arcs, the
 * basic shapes, nested groups with `transform`s, inherited `fill`, `stroke`, `stroke-width`,
 * `fill-rule` and opacities, from attributes or `style`), a renderer that draws them through the
 * existing kernels — [Gezira.rasterizer] under each shape's fill rule, [GeziraStroke.strokeBezierPath],
 * [GeziraComposite] — and Skia's own SVG renderer as the reference.
 *
 * ```
 * val doc = GeziraSvg.parse(GeziraSvg.tiger())
 * val r = GeziraSvg.render(doc, 300, 300)
 * val skia = GeziraSvg.skia(GeziraSvg.tiger(), 300, 300)
 * ```
 *
 * Gradients, patterns, `use`, clipping, masks, text and images are not read; [SvgDocument.skipped]
 * counts the elements passed over. Group `opacity` multiplies into each descendant's alpha rather
 * than compositing the group as a layer.
 */
object GeziraSvg {

    /** The Ghostscript tiger (AGPL, see `ghostscript_tiger.svg.SOURCE.txt` beside it) as SVG text. */
    fun tiger(): String = GeziraSvg::class.java.getResourceAsStream("/afield/graphics/nile/ghostscript_tiger.svg")
        ?.use { it.readBytes().decodeToString() } ?: error("ghostscript_tiger.svg is missing from the classpath")

    // ---------------------------------------------------------------- path data

    /**
     * SVG path data (`d`) as subpaths of [Bezier]s: `M L H V C S Q T A Z` in both cases, implicit
     * repeats, packed numbers (`1.5.5`, `-1-2`) and packed arc flags. Smooth curves reflect the
     * previous control point; arcs become one cubic per quarter turn or less.
     */
    fun pathData(d: String): List<SvgSubpath> {
        val out = ArrayList<SvgSubpath>()
        var cur = ArrayList<Bezier>()
        var i = 0
        var cx = 0.0
        var cy = 0.0
        var sx = 0.0
        var sy = 0.0
        var lastCtrlX = 0.0
        var lastCtrlY = 0.0
        var lastCmd = ' '
        var cmd = ' '

        fun skipSeparators() {
            while (i < d.length && (d[i].isWhitespace() || d[i] == ',')) i++
        }

        fun number(): Double {
            skipSeparators()
            val start = i
            if (i < d.length && (d[i] == '-' || d[i] == '+')) i++
            var dot = false
            while (i < d.length && (d[i].isDigit() || (d[i] == '.' && !dot))) {
                if (d[i] == '.') dot = true
                i++
            }
            if (i < d.length && (d[i] == 'e' || d[i] == 'E') && i + 1 < d.length && (d[i + 1].isDigit() || d[i + 1] == '-' || d[i + 1] == '+')) {
                i += 2
                while (i < d.length && d[i].isDigit()) i++
            }
            require(i > start) { "expected a number at $start in path data: '${d.substring(start, min(d.length, start + 20))}'" }
            return d.substring(start, i).toDouble()
        }

        fun flag(): Boolean {
            skipSeparators()
            require(i < d.length && (d[i] == '0' || d[i] == '1')) { "expected an arc flag at $i in path data" }
            return d[i++] == '1'
        }

        fun finish(closed: Boolean) {
            if (cur.isNotEmpty()) out.add(SvgSubpath(cur, closed))
            cur = ArrayList()
        }

        fun seg(vararg p: Double) {
            cur.add(Bezier(doubleArrayOf(cx, cy, *p)))
            cx = p[p.size - 2]
            cy = p[p.size - 1]
        }

        while (true) {
            skipSeparators()
            if (i >= d.length) break
            val ch = d[i]
            if (ch.isLetter() && ch != 'e' && ch != 'E') {
                cmd = ch
                i++
            } else {
                require(cmd != ' ') { "path data must start with a command" }
                // A number after a command repeats it; after M it means L.
                if (cmd == 'M') cmd = 'L' else if (cmd == 'm') cmd = 'l'
                require(cmd != 'z' && cmd != 'Z') { "number after z at $i in path data" }
            }
            val rel = cmd.isLowerCase()
            val ox = if (rel) cx else 0.0
            val oy = if (rel) cy else 0.0
            when (cmd.uppercaseChar()) {
                'M' -> {
                    finish(false)
                    cx = ox + number()
                    cy = oy + number()
                    sx = cx
                    sy = cy
                }

                'L' -> seg(ox + number(), oy + number())
                'H' -> seg(ox + number(), cy)
                'V' -> {
                    val y = oy + number()
                    seg(cx, y)
                }

                'C' -> {
                    val x1 = ox + number()
                    val y1 = oy + number()
                    val x2 = ox + number()
                    val y2 = oy + number()
                    seg(x1, y1, x2, y2, ox + number(), oy + number())
                    lastCtrlX = x2
                    lastCtrlY = y2
                }

                'S' -> {
                    val smooth = lastCmd.uppercaseChar() in "CS"
                    val x1 = if (smooth) 2 * cx - lastCtrlX else cx
                    val y1 = if (smooth) 2 * cy - lastCtrlY else cy
                    val x2 = ox + number()
                    val y2 = oy + number()
                    seg(x1, y1, x2, y2, ox + number(), oy + number())
                    lastCtrlX = x2
                    lastCtrlY = y2
                }

                'Q' -> {
                    val x1 = ox + number()
                    val y1 = oy + number()
                    seg(x1, y1, ox + number(), oy + number())
                    lastCtrlX = x1
                    lastCtrlY = y1
                }

                'T' -> {
                    val smooth = lastCmd.uppercaseChar() in "QT"
                    val x1 = if (smooth) 2 * cx - lastCtrlX else cx
                    val y1 = if (smooth) 2 * cy - lastCtrlY else cy
                    seg(x1, y1, ox + number(), oy + number())
                    lastCtrlX = x1
                    lastCtrlY = y1
                }

                'A' -> {
                    val rx = number()
                    val ry = number()
                    val rot = number()
                    val large = flag()
                    val sweep = flag()
                    val x = ox + number()
                    val y = oy + number()
                    for (c in arc(cx, cy, rx, ry, rot, large, sweep, x, y)) cur.add(c)
                    cx = x
                    cy = y
                }

                'Z' -> {
                    if (cur.isNotEmpty() && (cx != sx || cy != sy)) seg(sx, sy)
                    cx = sx
                    cy = sy
                    finish(true)
                }

                else -> error("unknown path command '$cmd'")
            }
            lastCmd = cmd
        }
        finish(false)
        return out
    }

    /**
     * An elliptical arc in SVG's endpoint form as cubics, one per quarter turn or less (SVG 1.1
     * appendix F.6.5 for the centre, the usual `4/3 tan(θ/4)` handles). Radii too small to reach
     * are scaled up; a zero radius is a straight line.
     */
    fun arc(
        x1: Double, y1: Double, rxIn: Double, ryIn: Double, rotation: Double,
        large: Boolean, sweep: Boolean, x2: Double, y2: Double,
    ): List<Bezier> {
        if (x1 == x2 && y1 == y2) return emptyList()
        var rx = abs(rxIn)
        var ry = abs(ryIn)
        if (rx == 0.0 || ry == 0.0) return listOf(Bezier(doubleArrayOf(x1, y1, x2, y2)))
        val phi = rotation * PI / 180
        val cp = cos(phi)
        val sp = sin(phi)
        val dx = (x1 - x2) / 2
        val dy = (y1 - y2) / 2
        val x1p = cp * dx + sp * dy
        val y1p = -sp * dx + cp * dy
        val lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
        if (lambda > 1) {
            rx *= sqrt(lambda)
            ry *= sqrt(lambda)
        }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var k = sqrt(max(0.0, num / den))
        if (large == sweep) k = -k
        val cxp = k * rx * y1p / ry
        val cyp = -k * ry * x1p / rx
        val ccx = cp * cxp - sp * cyp + (x1 + x2) / 2
        val ccy = sp * cxp + cp * cyp + (y1 + y2) / 2
        fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val a = acos(((ux * vx + uy * vy) / (sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy))).coerceIn(-1.0, 1.0))
            return if (ux * vy - uy * vx < 0) -a else a
        }
        val theta1 = angle(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var dTheta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && dTheta > 0) dTheta -= 2 * PI else if (sweep && dTheta < 0) dTheta += 2 * PI
        val n = max(1, ceil(abs(dTheta) / (PI / 2) - 1e-9).toInt())
        val step = dTheta / n
        val h = 4.0 / 3 * tan(step / 4)
        fun px(ex: Double, ey: Double) = ccx + cp * rx * ex - sp * ry * ey
        fun py(ex: Double, ey: Double) = ccy + sp * rx * ex + cp * ry * ey
        val out = ArrayList<Bezier>(n)
        for (j in 0 until n) {
            val a0 = theta1 + j * step
            val a1 = a0 + step
            val c0 = cos(a0)
            val s0 = sin(a0)
            val c1 = cos(a1)
            val s1 = sin(a1)
            val ex = if (j == n - 1) x2 else px(c1, s1)
            val ey = if (j == n - 1) y2 else py(c1, s1)
            val sx = if (j == 0) x1 else px(c0, s0)
            val sy = if (j == 0) y1 else py(c0, s0)
            out.add(
                Bezier(
                    doubleArrayOf(
                        sx, sy,
                        px(c0 - h * s0, s0 + h * c0), py(c0 - h * s0, s0 + h * c0),
                        px(c1 + h * s1, s1 - h * c1), py(c1 + h * s1, s1 - h * c1),
                        ex, ey,
                    ),
                ),
            )
        }
        return out
    }

    // ---------------------------------------------------------------- style

    private val NAMED = mapOf(
        "black" to 0x000000, "white" to 0xffffff, "red" to 0xff0000, "lime" to 0x00ff00, "green" to 0x008000,
        "blue" to 0x0000ff, "yellow" to 0xffff00, "cyan" to 0x00ffff, "aqua" to 0x00ffff, "magenta" to 0xff00ff,
        "fuchsia" to 0xff00ff, "gray" to 0x808080, "grey" to 0x808080, "silver" to 0xc0c0c0, "maroon" to 0x800000,
        "olive" to 0x808000, "navy" to 0x000080, "purple" to 0x800080, "teal" to 0x008080, "orange" to 0xffa500,
    )

    /** An SVG colour as straight `0xRRGGBB`, or null for `none`, a `url(...)` paint or anything unread. */
    fun color(s: String?): Int? {
        val v = s?.trim()?.lowercase() ?: return null
        if (v.startsWith("#")) {
            val h = v.substring(1)
            return when (h.length) {
                3 -> h.map { "$it$it" }.joinToString("").toIntOrNull(16)
                6 -> h.toIntOrNull(16)
                else -> null
            }
        }
        if (v.startsWith("rgb(")) {
            val parts = v.removePrefix("rgb(").removeSuffix(")").split(',').map { it.trim() }
            if (parts.size != 3) return null
            val c = parts.map { p ->
                if (p.endsWith("%")) (p.removeSuffix("%").toDouble() * 2.55).roundToInt() else p.toDouble().roundToInt()
            }.map { it.coerceIn(0, 255) }
            return (c[0] shl 16) or (c[1] shl 8) or c[2]
        }
        return NAMED[v]
    }

    private val INHERITED = setOf("fill", "stroke", "stroke-width", "fill-rule", "fill-opacity", "stroke-opacity")

    /** An element's presentation attributes with its `style` declarations over them. */
    private fun ownStyle(e: Element): Map<String, String> {
        val m = HashMap<String, String>()
        for (k in INHERITED + "opacity") if (e.hasAttribute(k)) m[k] = e.getAttribute(k)
        e.getAttribute("style").split(';').forEach { decl ->
            val c = decl.indexOf(':')
            if (c > 0) m[decl.substring(0, c).trim()] = decl.substring(c + 1).trim()
        }
        return m
    }

    private class Context(val transform: SvgTransform, val style: Map<String, String>, val opacity: Double)

    // ---------------------------------------------------------------- document

    /** Read an SVG document's view box and every painted shape, in paint order. */
    fun parse(svg: String): SvgDocument {
        val f = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val root = f.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(svg))).documentElement
        fun length(s: String) = SvgTransform.numbers(s).firstOrNull()
        val w = length(root.getAttribute("width"))
        val h = length(root.getAttribute("height"))
        val vb = SvgTransform.numbers(root.getAttribute("viewBox")).takeIf { it.size == 4 }?.toDoubleArray()
            ?: doubleArrayOf(0.0, 0.0, w ?: 300.0, h ?: 150.0)
        val shapes = ArrayList<SvgShape>()
        val skipped = LinkedHashMap<String, Int>()
        fun visit(e: Element, parent: Context) {
            val own = ownStyle(e)
            val style = parent.style + own.filterKeys { it != "opacity" }
            val ctx = Context(
                parent.transform * SvgTransform.parse(e.getAttribute("transform")),
                style,
                parent.opacity * (own["opacity"]?.toDoubleOrNull() ?: 1.0),
            )
            when (val tag = e.tagName.substringAfter(':')) {
                "svg", "g", "a" -> {
                    val kids = e.childNodes
                    for (k in 0 until kids.length) (kids.item(k) as? Element)?.let { visit(it, ctx) }
                }

                "path", "rect", "circle", "ellipse", "line", "polyline", "polygon" -> {
                    val geometry = geometry(tag, e)
                    if (geometry.isNotEmpty()) shapes.add(shape(tag, e.getAttribute("id").ifEmpty { null }, geometry, ctx))
                }

                "title", "desc", "metadata", "defs" -> {}
                else -> skipped[tag] = (skipped[tag] ?: 0) + 1
            }
        }
        visit(root, Context(SvgTransform.IDENTITY, emptyMap(), 1.0))
        return SvgDocument(vb, w ?: vb[2], h ?: vb[3], shapes, skipped)
    }

    private fun shape(tag: String, id: String?, geometry: List<SvgSubpath>, ctx: Context): SvgShape {
        val s = ctx.style
        fun alpha(key: String) = (s[key]?.toDoubleOrNull() ?: 1.0) * ctx.opacity
        fun paint(key: String, default: String?): Rgba? {
            val c = color(s[key] ?: default) ?: return null
            return Rgba.of((c shr 16 and 255) / 255.0, (c shr 8 and 255) / 255.0, (c and 255) / 255.0, alpha("$key-opacity"))
        }
        val t = ctx.transform
        return SvgShape(
            tag, id,
            geometry.map { g -> SvgSubpath(g.beziers.map(t::apply), g.closed) },
            paint("fill", "black"),
            if (s["fill-rule"] == "evenodd") FillRule.EVEN_ODD else FillRule.NON_ZERO,
            paint("stroke", null),
            (s["stroke-width"]?.let { SvgTransform.numbers(it).firstOrNull() } ?: 1.0) * t.scale,
        )
    }

    private fun geometry(tag: String, e: Element): List<SvgSubpath> {
        fun n(k: String) = SvgTransform.numbers(e.getAttribute(k)).firstOrNull() ?: 0.0
        fun line(vararg p: Double) = Bezier(p)
        return when (tag) {
            "path" -> pathData(e.getAttribute("d"))
            "rect" -> {
                val x = n("x")
                val y = n("y")
                val w = n("width")
                val h = n("height")
                if (w <= 0 || h <= 0) return emptyList()
                var rx = if (e.hasAttribute("rx")) n("rx") else n("ry")
                var ry = if (e.hasAttribute("ry")) n("ry") else rx
                rx = min(rx, w / 2)
                ry = min(ry, h / 2)
                if (rx <= 0 || ry <= 0) {
                    listOf(SvgSubpath(listOf(line(x, y, x + w, y), line(x + w, y, x + w, y + h), line(x + w, y + h, x, y + h), line(x, y + h, x, y)), true))
                } else {
                    pathData(
                        "M${x + rx},$y H${x + w - rx} A$rx,$ry 0 0 1 ${x + w},${y + ry} V${y + h - ry} " +
                            "A$rx,$ry 0 0 1 ${x + w - rx},${y + h} H${x + rx} A$rx,$ry 0 0 1 $x,${y + h - ry} " +
                            "V${y + ry} A$rx,$ry 0 0 1 ${x + rx},$y Z",
                    )
                }
            }

            "circle", "ellipse" -> {
                val cx = n("cx")
                val cy = n("cy")
                val rx = if (tag == "circle") n("r") else n("rx")
                val ry = if (tag == "circle") rx else n("ry")
                if (rx <= 0 || ry <= 0) return emptyList()
                pathData("M${cx + rx},$cy A$rx,$ry 0 0 1 $cx,${cy + ry} A$rx,$ry 0 0 1 ${cx - rx},$cy A$rx,$ry 0 0 1 $cx,${cy - ry} A$rx,$ry 0 0 1 ${cx + rx},$cy Z")
            }

            "line" -> listOf(SvgSubpath(listOf(line(n("x1"), n("y1"), n("x2"), n("y2"))), false))
            "polyline", "polygon" -> {
                val p = SvgTransform.numbers(e.getAttribute("points"))
                if (p.size < 4) return emptyList()
                val segs = (0 until p.size / 2 - 1).map { line(p[2 * it], p[2 * it + 1], p[2 * it + 2], p[2 * it + 3]) }
                val sub = SvgSubpath(segs, tag == "polygon")
                listOf(if (tag == "polygon") SvgSubpath(sub.closedBeziers(), true) else sub)
            }

            else -> emptyList()
        }
    }

    // ---------------------------------------------------------------- rendering

    /** A [render]: the canvas, and what went through the kernels to make it. */
    class Render(
        val canvas: Canvas,
        /** Shapes filled and shapes stroked. */
        val fills: Int,
        val strokes: Int,
        /** Béziers into the rasteriser (fill outlines plus stroke outlines), the flattened lines, and the spans out. */
        val beziers: Int,
        val lines: Int,
        val spans: Int,
        /** Milliseconds spent stroking, rasterising and compositing. */
        val strokeMs: Double,
        val rasterMs: Double,
        val compositeMs: Double,
    ) {
        override fun toString() =
            "$fills fills, $strokes strokes; $beziers béziers -> $lines lines -> $spans spans; " +
                "stroke %.0f ms, rasterise %.0f ms, composite %.0f ms".format(strokeMs, rasterMs, compositeMs)
    }

    /**
     * Draw [doc] into a `w × h` canvas with Gezira's kernels: each shape's fill under its fill rule,
     * then its stroke (the stroke kernel per subpath, rasterised non-zero), source-over, in paint order.
     */
    fun render(doc: SvgDocument, w: Int, h: Int, tolerance: Double = Gezira.FLATNESS): Render {
        val canvas = Canvas(w, h)
        val composite = GeziraComposite.compositeOver(canvas)
        var fills = 0
        var strokes = 0
        var beziers = 0
        var lines = 0
        var spans = 0
        var tStroke = 0L
        var tRaster = 0L
        var tComposite = 0L
        fun paint(input: List<Bezier>, rule: FillRule, c: Rgba) {
            if (input.isEmpty() || c.a <= 0f) return
            beziers += input.size
            val t0 = System.nanoTime()
            val trace = Gezira.rasterizer(rule, tolerance).trace(input)
            val s = trace.output.filterIsInstance<Span>()
            lines += trace.streams["FlattenBeziers"]?.size ?: 0
            spans += s.size
            val t1 = System.nanoTime()
            composite(GeziraComposite.color(c)(s))
            tRaster += t1 - t0
            tComposite += System.nanoTime() - t1
        }
        for (shape in doc.shapesIn(w, h)) {
            shape.fill?.let {
                fills++
                paint(shape.fillBeziers(), shape.fillRule, it)
            }
            val stroke = shape.stroke
            if (stroke != null && shape.strokeWidth > 0) {
                strokes++
                val t0 = System.nanoTime()
                val outline = shape.subpaths.flatMap { sp ->
                    GeziraStroke.strokeBezierPath(shape.strokeWidth)(if (sp.closed) sp.closedBeziers() else sp.beziers)
                }
                tStroke += System.nanoTime() - t0
                paint(outline, FillRule.NON_ZERO, stroke)
            }
        }
        return Render(canvas, fills, strokes, beziers, lines, spans, tStroke / 1e6, tRaster / 1e6, tComposite / 1e6)
    }

    /** Skia's own SVG renderer (`SVGDOM`) over the same text into a `w × h` premultiplied RGBA raster. */
    fun skia(svg: String, w: Int, h: Int): FloatArray {
        val data = Data.makeFromBytes(svg.encodeToByteArray())
        val dom = SVGDOM(data)
        try {
            dom.setContainerSize(w.toFloat(), h.toFloat())
            return Gezira.skiaRaster(w, h) { dom.render(it) }
        } finally {
            runCatching { dom.close() }
            runCatching { data.close() }
        }
    }

    /** `|a - b|` per channel times [gain], clamped, as an opaque premultiplied image: white where they agree. */
    fun difference(a: FloatArray, b: FloatArray, gain: Double = 10.0): FloatArray {
        val out = FloatArray(a.size)
        for (i in 0 until a.size / 4) {
            for (c in 0..2) out[4 * i + c] = (1 - min(1.0, gain * abs(a[4 * i + c] - b[4 * i + c]))).toFloat()
            out[4 * i + 3] = 1f
        }
        return out
    }

    /** Premultiplied RGBA over [background] (straight `0xRRGGBB`) as PNG bytes, which `display` shows as an image. */
    fun png(rgba: FloatArray, w: Int, h: Int, background: Int = 0xffffff): ByteArray {
        val br = (background shr 16 and 255) / 255f
        val bg = (background shr 8 and 255) / 255f
        val bb = (background and 255) / 255f
        val px = IntArray(w * h) { i ->
            val a = min(1f, rgba[4 * i + 3])
            fun ch(v: Float, back: Float) = ((v + back * (1 - a)).coerceIn(0f, 1f) * 255).roundToInt()
            (0xff shl 24) or (ch(rgba[4 * i], br) shl 16) or (ch(rgba[4 * i + 1], bg) shl 8) or ch(rgba[4 * i + 2], bb)
        }
        return PngEncoder.encode(px, w, h)
    }

    /**
     * [doc] as FLines for the rest of Field, in its user units (y down, as SVG has it): one filled
     * FLine per filled shape in its fill colour and one stroked FLine per stroked shape with its
     * width as `thickness`.
     */
    fun flines(doc: SvgDocument, t: SvgTransform = SvgTransform.IDENTITY): List<FLine> {
        fun outline(subpaths: List<SvgSubpath>, close: Boolean) = FLine().also { f ->
            for (sp in subpaths) {
                val bs = if (close) sp.closedBeziers() else sp.beziers
                if (bs.isEmpty()) continue
                val first = t.apply(bs[0]).p
                f.moveTo(first[0], first[1])
                for (b in bs) {
                    val p = t.apply(b).p
                    when (b.degree) {
                        1 -> f.lineTo(p[2], p[3])
                        2 -> f.cubicTo(
                            p[0] + 2.0 / 3 * (p[2] - p[0]), p[1] + 2.0 / 3 * (p[3] - p[1]), 0.0,
                            p[4] + 2.0 / 3 * (p[2] - p[4]), p[5] + 2.0 / 3 * (p[3] - p[5]), 0.0, p[4], p[5], 0.0,
                        )
                        else -> f.cubicTo(p[2], p[3], 0.0, p[4], p[5], 0.0, p[6], p[7], 0.0)
                    }
                }
            }
        }
        fun vec4(c: Rgba) = if (c.a > 0f) vec(c.r / c.a, c.g / c.a, c.b / c.a, c.a) else vec(0.0, 0.0, 0.0, 0.0)
        val out = ArrayList<FLine>()
        for (s in doc.shapes) {
            s.fill?.let { c ->
                out.add(outline(s.subpaths, true).also { it.filled = true; it.stroked = false; it.color = vec4(c) })
            }
            s.stroke?.let { c ->
                out.add(outline(s.subpaths, false).also { it.stroked = true; it.thickness = s.strokeWidth * t.scale; it.color = vec4(c) })
            }
        }
        return out
    }
}
