package afield.graphics

import afield.Box
import afield.graphics.examples.FLineGeometryExamples
import afield.stdlib.*
import org.jetbrains.skia.*
import org.jetbrains.skia.svg.SVGCanvas
import java.io.ByteArrayOutputStream
import kotlin.math.min

fun Box.lines(
    vararg m: FLine,
    channel: String = "lines",
    w: Double = 1000.0,
    h: Double = 1000.0,
    fitToSize: Boolean = false
) {
    val fit = computeFitTransform(m.asList(), w, h, fitToSize)
    val scaled = m.map { it.applyFit(fit) }
    display(channel, drawToSVG(scaled, w, h))
}

/**
 * Render multiple channels of FLines at once.
 * Each map entry key is used as the display channel, and the value is the list of lines to draw.
 */
fun Box.lines(
    linesByChannel: Map<String, List<FLine>>,
    w: Double = 1000.0,
    h: Double = 1000.0,
    fitToSize: Boolean = false
) {
    val allLines = linesByChannel.values.flatten()
    val fit = computeFitTransform(allLines, w, h, fitToSize)
    linesByChannel.forEach { (channel, lines) ->
        val scaled = lines.map { it.applyFit(fit) }
        display(channel, drawToSVG(scaled, w, h))
    }
}


// Convenience display helpers for example maps
fun Box.l(
    m: Map<String, List<FLine>>,
    w: Double = 200.0,
    h: Double = 200.0,
    fitToSize: Boolean = true
) = lines(m, w = w, h = h, fitToSize = fitToSize)

private fun Map<String, List<FLine>>.namespaced(prefix: String): Map<String, List<FLine>> =
    this.mapKeys { (k, _) -> "$prefix/$k" }

/**
 * Showcase: render all FLineGeometryExamples with sensible defaults.
 */
fun Box.showFLineGeometryExamples(w: Double = 200.0, h: Double = 200.0, fitToSize: Boolean = true) {
    val examples = listOf(
        "spiralCurvature" to FLineGeometryExamples.spiralCurvatureExample(),
        "offsetShape" to FLineGeometryExamples.offsetShapeExample(),
        "booleanOps" to FLineGeometryExamples.booleanOperationsExample(),
        "smoothSimplify" to FLineGeometryExamples.smoothAndSimplifyExample(),
        "tangentNormal" to FLineGeometryExamples.tangentNormalExample(),
        "morphing" to FLineGeometryExamples.morphingExample(),
        "pattern" to FLineGeometryExamples.patternExample(),
        "transformations3D" to FLineGeometryExamples.transformations3DExample(),
        "convexHull" to FLineGeometryExamples.convexHullExample(),
        "adaptiveSampling" to FLineGeometryExamples.adaptiveSamplingExample(),
        "comprehensiveDemo" to FLineGeometryExamples.comprehensiveDemo(),
        "arcs" to FLineGeometryExamples.arcExample(),
        "noise" to FLineGeometryExamples.noiseComparisonExample(),
        "curveCombination" to FLineGeometryExamples.curveCombinationExample(),
        "parameterization" to FLineGeometryExamples.parameterizationExample(),
        "complexComposition" to FLineGeometryExamples.complexCompositionExample()
    )

    examples.forEach { (name, m) ->
        l(m.namespaced(name), w = w, h = h, fitToSize = fitToSize)
    }
}

fun drawToSVG(n: List<FLine>, w: Double = -1.0, h: Double = -1.0): String {
    val ba = ByteArrayOutputStream()
    val o = OutputWStream(ba)

    var we = w;
    var he = h;
    if (we == -1.0) {
        val r = n.map { it.fast2DBounds() }.reduce { a, b -> a.union(b) }
        we = r.w+r.x
        he = r.h+r.y
    }

    val s = SVGCanvas.make(Rect.makeWH(we.toFloat(), he.toFloat()), o, true, true)

    n.forEach {
        outputTo(it, s)
    }
    s.close()
    o.close()

    val hack = "xmlns=\"http://www.w3.org/2000/svg\""
    return ba.toString().replace(hack, " style='overflow:visible' " + hack)

}

private data class FitTransform(val scale: Float, val offsetX: Float, val offsetY: Float)

private fun computeFitTransform(lines: Collection<FLine>, w: Double, h: Double, enabled: Boolean): FitTransform? {
    if (!enabled) return null
    if (lines.isEmpty()) return null
    if (w <= 0.0 || h <= 0.0) return null

    val bounds = lines.map { it.fast2DBounds() }.reduce { a, b -> a.union(b) }
    if (bounds.w <= 0f || bounds.h <= 0f) return null

    val scale = min((w / bounds.w).toFloat(), (h / bounds.h).toFloat())
    val offsetX = -bounds.x
    val offsetY = -bounds.y

    return FitTransform(scale, offsetX.toFloat(), offsetY.toFloat())
}

private fun FLine.applyFit(transform: FitTransform?): FLine {
    if (transform == null) return this
    val s = transform.scale
    val sx = transform.offsetX
    val sy = transform.offsetY
    val scaled = this.transform { p ->
        vec((p.x + sx) * s, (p.y + sy) * s, p.z)
    }
    return scaled.apply {
        thickness = thickness?.times(s)
        pointSize = pointSize?.times(s)
    }
}

fun outputTo(f: FLine, s: Canvas) {
    var p = Path()

    var first = true
    f.nodes.forEach {
        when (it) {
            is FLine.MoveTo -> p.moveTo(it.to.x.toFloat(), it.to.y.toFloat())
            is FLine.LineTo -> {
                if (first) {
                    p.moveTo(it.to.x.toFloat(), it.to.y.toFloat())
                } else
                    p.lineTo(it.to.x.toFloat(), it.to.y.toFloat())
            }

            is FLine.CubicTo -> {
                if (first) {
                    p.moveTo(it.to.x.toFloat(), it.to.y.toFloat())
                } else

                    p.cubicTo(
                        it.c1.x.toFloat(),
                        it.c1.y.toFloat(),
                        it.c2.x.toFloat(),
                        it.c2.y.toFloat(),
                        it.to.x.toFloat(),
                        it.to.y.toFloat()
                    )
            }

            is FLine.Close -> p.closePath()
        }
        first = false
    }

    val paint = Paint()
    paint.strokeWidth = f.thickness?.toFloat() ?: 1.0f
    val c = f.color
    paint.color4f = Color4f(c.x.toFloat(), c.y.toFloat(), c.z.toFloat(), c.w.toFloat())
    val mode =
        if (f.stroked && f.filled)
            PaintMode.STROKE_AND_FILL
        else
            if (f.stroked)
                PaintMode.STROKE
            else if (f.filled) PaintMode.FILL
            else null

    if (mode != null) {
        paint.mode = mode
        s.drawPath(p, paint)
    }

    fun radiusFor(f: FLine, it: FLine.Node) = it.pointSize ?: f.pointSize

    if (f.pointed) {
        var circ = Path()

        f.nodes.forEach {
            when (it) {
                is FLine.MoveTo -> circ.addCircle(it.to.x.toFloat(), it.to.y.toFloat(), radiusFor(f, it).toFloat())
                is FLine.LineTo -> circ.addCircle(it.to.x.toFloat(), it.to.y.toFloat(), radiusFor(f, it).toFloat())
                is FLine.CubicTo -> circ.addCircle(it.to.x.toFloat(), it.to.y.toFloat(), radiusFor(f, it).toFloat())
                is FLine.Close -> {}
            }
        }

        paint.color4f = Color4f(c.x.toFloat(), c.y.toFloat(), c.z.toFloat(), c.w.toFloat())
        paint.mode = PaintMode.FILL
        s.drawPath(circ, paint)
    }

    p.close()

}
