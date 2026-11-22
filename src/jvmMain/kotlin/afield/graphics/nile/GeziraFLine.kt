package afield.graphics.nile

import afield.document.properties.Delegation
import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.graphics.render.filled
import afield.graphics.render.stroked
import afield.graphics.render.thickness

/**
 * The fill rule Gezira's rasteriser uses when it fills this line ([filled]). Non-zero by default,
 * as every other Field renderer fills. FLine has no fill rule of its own; [GeziraSvg] sets this
 * from SVG's `fill-rule`, and only [GeziraFLine] reads it.
 */
var FLine.fillRule by Delegation<FillRule> { FillRule.NON_ZERO }

/**
 * Field's FLines drawn by Gezira's kernels, painted by their own attributes: [color] (alpha is the
 * opacity), [filled] under [fillRule], [stroked] at [thickness] (1 when unset).
 *
 * ```
 * val r = GeziraFLine.render(listOf(circle), 200, 200)
 * display("gezira", GeziraSvg.png(r.canvas.rgba, 200, 200))
 * ```
 *
 * Each line is filled (every subpath closed, [Gezira.rasterizer] under its rule) and then stroked
 * ([GeziraStroke.strokeBezierPath] per subpath, bevel joins and butt caps, rasterised non-zero),
 * each composited source-over in list order with [GeziraComposite.compositeOver]. Per-node colours,
 * `thicken`, and `pointed` are not drawn.
 */
object GeziraFLine {

    /** A [render]: the canvas, and what went through the kernels to make it. */
    class Render(
        val canvas: Canvas,
        /** Lines filled and lines stroked. */
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

    /** [line]'s colour as a premultiplied [Rgba]. */
    fun paint(line: FLine): Rgba {
        val c = line.color
        return Rgba.of(c.x.toDouble(), c.y.toDouble(), c.z.toDouble(), c.w.toDouble())
    }

    /**
     * Draw [lines] into a `w × h` canvas (y down, one unit a pixel after [transform]), in order.
     * [transform] maps the lines' coordinates to pixels and scales their thickness by its
     * [SvgTransform.scale].
     */
    fun render(
        lines: List<FLine>,
        w: Int,
        h: Int,
        transform: SvgTransform = SvgTransform.IDENTITY,
        tolerance: Double = Gezira.FLATNESS,
    ): Render {
        val canvas = Canvas(w, h)
        val composite = GeziraComposite.compositeOver(canvas)
        var fills = 0
        var strokes = 0
        var beziers = 0
        var flat = 0
        var spans = 0
        var tStroke = 0L
        var tRaster = 0L
        var tComposite = 0L
        fun draw(input: List<Bezier>, rule: FillRule, c: Rgba) {
            if (input.isEmpty() || c.a <= 0f) return
            beziers += input.size
            val t0 = System.nanoTime()
            val trace = Gezira.rasterizer(rule, tolerance).trace(input)
            val s = trace.output.filterIsInstance<Span>()
            flat += trace.streams["FlattenBeziers"]?.size ?: 0
            spans += s.size
            val t1 = System.nanoTime()
            composite(GeziraComposite.color(c)(s))
            tRaster += t1 - t0
            tComposite += System.nanoTime() - t1
        }
        for (line in lines) {
            val c = paint(line)
            if (line.filled) {
                fills++
                draw(Gezira.beziers(line).map(transform::apply), line.fillRule, c)
            }
            val width = (line.thickness ?: 1.0) * transform.scale
            if (line.stroked && width > 0) {
                strokes++
                val t0 = System.nanoTime()
                val outline = Gezira.subpaths(line).flatMap { sp -> GeziraStroke.strokeBezierPath(width)(sp.map(transform::apply)) }
                tStroke += System.nanoTime() - t0
                draw(outline, FillRule.NON_ZERO, c)
            }
        }
        return Render(canvas, fills, strokes, beziers, flat, spans, tStroke / 1e6, tRaster / 1e6, tComposite / 1e6)
    }
}
