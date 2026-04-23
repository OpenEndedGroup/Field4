package afield.graphics

import afield.Box
import afield.examples.graphics.FLineGeometryExamples
import afield.registerFLineClickCallback
import afield.stdlib.*
import org.jetbrains.skia.*
import org.jetbrains.skia.svg.SVGCanvas
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID
import kotlin.math.min

/**
 * Render one or more [FLine]s to SVG and display them in an output channel.
 * If any FLine has [FLine.onClick] or [FLine.hoverColor] set, an interactive
 * SVG is generated instead of using the Skia canvas renderer.
 *
 * @param channel Output channel name (disclosure panel).
 * @param w Output SVG width in pixels.
 * @param h Output SVG height in pixels.
 * @param fitToSize If true, uniformly scales and offsets the lines to fit inside [w]x[h] with padding.
 */
fun Box.lines(
    vararg m: FLine,
    channel: String = "lines",
    w: Double = -1.0,
    h: Double = -1.0,
    fitToSize: Boolean = false,
    editable: Boolean = false,
    drawing: Boolean = false,
    liveDrawing: Boolean = false
): EditableFLineViewState? {
    val lines = m.asList().renderableLines()
    val fit = computeFitTransform(lines, w, h, fitToSize)
    val hasInteractive = lines.any { it.onClick != null || it.hoverColor != null }
    if (editable) {
        val pairs = lines.map { it to it.applyFit(fit) }
        val widget = buildEditableSVGWidget(pairs, fit, w, h, drawingEnabled = drawing, liveDrawingEnabled = liveDrawing)
        display(channel, widget.html)
        return widget.viewState
    } else if (hasInteractive) {
        val pairs = lines.map { it to it.applyFit(fit) }
        display(channel, wrapLinesOutput(buildInteractiveSVG(pairs, w, h, fit)))
    } else {
        val scaled = lines.map { it.applyFit(fit) }
        display(channel, wrapLinesOutput(drawToSVG(scaled, w, h)))
    }
    return null
}

/**
 * Render a list of [FLine]s to SVG and display them in an output channel.
 * If any FLine has [FLine.onClick] or [FLine.hoverColor] set, an interactive
 * SVG is generated instead of using the Skia canvas renderer.
 *
 * @param channel Output channel name (disclosure panel).
 * @param w Output SVG width in pixels.
 * @param h Output SVG height in pixels.
 * @param fitToSize If true, uniformly scales and offsets the lines to fit inside [w]x[h] with padding.
 */
fun Box.lines(
    m: List<FLine>,
    channel: String = "lines",
    w: Double = -1.0,
    h: Double = -1.0,
    fitToSize: Boolean = false,
    editable: Boolean = false,
    drawing: Boolean = false,
    liveDrawing: Boolean = false
): EditableFLineViewState? {
    val lines = m.renderableLines()
    val fit = computeFitTransform(lines, w, h, fitToSize)
    val hasInteractive = lines.any { it.onClick != null || it.hoverColor != null }
    if (editable) {
        val pairs = lines.map { it to it.applyFit(fit) }
        val widget = buildEditableSVGWidget(pairs, fit, w, h, drawingEnabled = drawing, liveDrawingEnabled = liveDrawing)
        display(channel, widget.html)
        return widget.viewState
    } else if (hasInteractive) {
        val pairs = lines.map { it to it.applyFit(fit) }
        display(channel, wrapLinesOutput(buildInteractiveSVG(pairs, w, h, fit)))
    } else {
        val scaled = lines.map { it.applyFit(fit) }
        display(channel, wrapLinesOutput(drawToSVG(scaled, w, h)))
    }
    return null
}

/**
 * Render [FLine]s from a map, optionally splitting by channel.
 * If any FLine has [FLine.onClick] or [FLine.hoverColor] set, an interactive
 * SVG is generated instead of using the Skia canvas renderer.
 *
 * @param w Output SVG width in pixels.
 * @param h Output SVG height in pixels.
 * @param fitToSize If true, uniformly scales and offsets the lines to fit inside [w]x[h] with padding.
 * @param splitByChannel If true, each map entry is displayed in its own channel key.
 * @param channel When [splitByChannel] is false, all lines are combined and displayed here.
 */
fun Box.lines(
    linesByChannel: Map<String, List<FLine>>,
    w: Double = -1.0,
    h: Double = -1.0,
    fitToSize: Boolean = false,
    splitByChannel: Boolean = false,
    channel: String = "lines",
    editable: Boolean = false,
    drawing: Boolean = false,
    liveDrawing: Boolean = false
): Map<String, EditableFLineViewState> {
    val filteredLinesByChannel = linesByChannel.mapValues { (_, lines) -> lines.renderableLines() }
    val allLines = filteredLinesByChannel.values.flatten()
    val fit = computeFitTransform(allLines, w, h, fitToSize)
    val states = linkedMapOf<String, EditableFLineViewState>()
    if (splitByChannel) {
        filteredLinesByChannel.forEach { (channelName, lines) ->
            val hasInteractive = lines.any { it.onClick != null || it.hoverColor != null }
            if (editable) {
                val pairs = lines.map { it to it.applyFit(fit) }
                val widget = buildEditableSVGWidget(pairs, fit, w, h, drawingEnabled = drawing, liveDrawingEnabled = liveDrawing)
                display(channelName, widget.html)
                states[channelName] = widget.viewState
            } else if (hasInteractive) {
                val pairs = lines.map { it to it.applyFit(fit) }
                display(channelName, wrapLinesOutput(buildInteractiveSVG(pairs, w, h, fit)))
            } else {
                val scaled = lines.map { it.applyFit(fit) }
                display(channelName, wrapLinesOutput(drawToSVG(scaled, w, h)))
            }
        }
    } else {
        val hasInteractive = allLines.any { it.onClick != null || it.hoverColor != null }
        if (editable) {
            val pairs = allLines.map { it to it.applyFit(fit) }
            val widget = buildEditableSVGWidget(pairs, fit, w, h, drawingEnabled = drawing, liveDrawingEnabled = liveDrawing)
            display(channel, widget.html)
            states[channel] = widget.viewState
        } else if (hasInteractive) {
            val pairs = allLines.map { it to it.applyFit(fit) }
            display(channel, wrapLinesOutput(buildInteractiveSVG(pairs, w, h, fit)))
        } else {
            val scaled = allLines.map { it.applyFit(fit) }
            display(channel, wrapLinesOutput(drawToSVG(scaled, w, h)))
        }
    }
    return states
}


/**
 * Convenience wrapper for example maps.
 *
 * Uses smaller defaults and [fitToSize] true so small demos are easy to view.
 */
fun Box.l(
    m: Map<String, List<FLine>>,
    w: Double = 200.0,
    h: Double = 200.0,
    fitToSize: Boolean = true
) = lines(m, w = w, h = h, fitToSize = fitToSize)

private fun Map<String, List<FLine>>.namespaced(prefix: String): Map<String, List<FLine>> =
    this.mapKeys { (k, _) -> "$prefix/$k" }

private fun FLine.safeFast2DBoundsOrNull() = runCatching { fast2DBounds() }.getOrNull()

private fun Collection<FLine>.renderableLines(): List<FLine> =
    filter { it.safeFast2DBoundsOrNull() != null }

private fun Collection<FLine>.combinedRenderableBoundsOrNull() =
    mapNotNull { it.safeFast2DBoundsOrNull() }.reduceOrNull { a, b -> a.union(b) }

private fun emptySvg(w: Double, h: Double): String {
    val width = if (w > 0.0) w else 1.0
    val height = if (h > 0.0) h else 1.0
    return """<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" style="overflow:visible"/>"""
}

private fun wrapLinesOutput(svg: String): String {
    return """<div class="afield-lines-output" data-afield-lines-output="true">$svg</div>"""
}

/**
 * Showcase: render all [FLineGeometryExamples] with sensible defaults.
 *
 * Each example is displayed via [l] so you can quickly inspect the full set.
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
        "complexComposition" to FLineGeometryExamples.complexCompositionExample(),
        "smileSpline" to FLineGeometryExamples.smileSplineExample(),
        "smileGaussianProcess" to FLineGeometryExamples.smileGaussianProcessExample(),
        "akimaInterpolation" to FLineGeometryExamples.akimaInterpolationExample()
    )

    examples.forEach { (name, m) ->
        l(m.namespaced(name), w = w, h = h, fitToSize = fitToSize)
    }
}

/**
 * Render a list of [FLine]s into an SVG string.
 *
 * If [w] or [h] are -1, the SVG size is derived from the line bounds.
 */
fun drawToSVG(n: List<FLine>, w: Double = -1.0, h: Double = -1.0): String {
    val lines = n.renderableLines()
    if (lines.isEmpty()) return emptySvg(w, h)

    val ba = ByteArrayOutputStream()
    val o = OutputWStream(ba)

    var we = w;
    var he = h;
    if (we == -1.0) {
        val r = lines.combinedRenderableBoundsOrNull() ?: return emptySvg(w, h)
        we = r.w + r.x
        he = r.h + r.y
    }

    var s: Canvas? = null
    try {
        s = SVGCanvas.make(Rect.makeWH(we.toFloat(), he.toFloat()), o, true, true)

        lines.forEach {
            outputTo(it, s)
        }
    } finally {
        runCatching { s?.close() }
        runCatching { o.close() }
    }

    val hack = "xmlns=\"http://www.w3.org/2000/svg\""
    return ba.toString().replace(hack, " style='overflow:visible' " + hack)

}

internal data class FitTransform(val scale: Float, val offsetX: Float, val offsetY: Float)

private fun computeFitTransform(lines: Collection<FLine>, w: Double, h: Double, enabled: Boolean): FitTransform? {
    if (!enabled) return null
    if (lines.isEmpty()) return null
    if (w <= 0.0 || h <= 0.0) return null

    val bounds = lines.combinedRenderableBoundsOrNull() ?: return null
    if (bounds.w <= 0f || bounds.h <= 0f) return null

    val safety = 20f
    val availableW = (w - safety * 2).toFloat()
    val availableH = (h - safety * 2).toFloat()
    if (availableW <= 0f || availableH <= 0f) return null

    val scale = min(availableW / bounds.w, availableH / bounds.h)
    val offsetX = -bounds.x + safety / scale
    val offsetY = -bounds.y + safety / scale

    return FitTransform(scale.toFloat(), offsetX.toFloat(), offsetY.toFloat())
}

private fun FLine.applyFit(transform: FitTransform?): FLine {
    if (transform == null) return this
    val s = transform.scale
    val sx = transform.offsetX
    val sy = transform.offsetY
    val scaled = this.transform { p ->
        vec((p.x + sx) * s, (p.y + sy) * s, p.z)
    }
    val styleScale = s.toDouble().coerceIn(0.0, 1.0)
    this.thickness?.let { scaled.thickness = it * styleScale }
    scaled.pointSize = this.pointSize * styleScale
    scaled.nodes.forEach { node ->
        val pointSize = node.properties["pointSize"] as? Number ?: return@forEach
        node.properties["pointSize"] = pointSize.toDouble() * styleScale
    }
    return scaled
}

/**
 * Draw a single [FLine] to a Skia [Canvas], honoring stroke, fill, and point settings.
 */
fun outputTo(f: FLine, s: Canvas) {
    val p = Path()
    val paint = Paint()
    var circ: Path? = null
    try {
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

        fun radiusFor(f: FLine, it: FLine.Node) = (it.pointSize ?: f.pointSize) * 3.0

        if (f.pointed) {
            circ = Path()

            f.nodes.forEach {
                when (it) {
                    is FLine.MoveTo -> circ?.addCircle(it.to.x.toFloat(), it.to.y.toFloat(), radiusFor(f, it).toFloat())
                    is FLine.LineTo -> circ?.addCircle(it.to.x.toFloat(), it.to.y.toFloat(), radiusFor(f, it).toFloat())
                    is FLine.CubicTo -> circ?.addCircle(it.to.x.toFloat(), it.to.y.toFloat(), radiusFor(f, it).toFloat())
                    is FLine.Close -> {}
                }
            }

            paint.color4f = Color4f(c.x.toFloat(), c.y.toFloat(), c.z.toFloat(), c.w.toFloat())
            paint.mode = PaintMode.FILL
            circ?.let { s.drawPath(it, paint) }
        }
    } finally {
        runCatching { circ?.close() }
        runCatching { paint.close() }
        runCatching { p.close() }
    }

}

// ---------------------------------------------------------------------------
// Interactive SVG renderer (click callbacks + hover colors)
// ---------------------------------------------------------------------------

/**
 * Build an SVG string from [pairs] of (original, scaled) [FLine]s.
 *
 * For each original FLine that has [FLine.onClick] set, a unique callback ID is
 * registered via [registerFLineClickCallback] and the corresponding `<g>` element
 * gets an `onclick` attribute that calls `window.sendFLineClick(id, hit)`.
 *
 * For each original FLine that has [FLine.hoverColor] set, the `<g>` element gets
 * `onmouseover` / `onmouseout` attributes that call `window.__flineHoverIn` /
 * `window.__flineHoverOut` to swap the geometry colour on pointer entry/exit.
 */
internal fun buildInteractiveSVG(pairs: List<Pair<FLine, FLine>>, w: Double, h: Double, fit: FitTransform? = null): String {
    val renderablePairs = pairs.filter { (_, scaled) -> scaled.safeFast2DBoundsOrNull() != null }
    if (renderablePairs.isEmpty()) return emptySvg(w, h)

    val we: Double
    val he: Double
    if (w <= 0.0 || h <= 0.0) {
        val scaledLines = renderablePairs.map { it.second }
        val bounds = scaledLines.combinedRenderableBoundsOrNull() ?: return emptySvg(w, h)
        we = (bounds.w + bounds.x).toDouble()
        he = (bounds.h + bounds.y).toDouble()
    } else {
        we = w
        he = h
    }

    val sb = StringBuilder()
    sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$we" height="$he" style="overflow:visible">""")

    renderablePairs.forEach { (original, scaled) ->
        val onClick = original.onClick
        val hoverColor = original.hoverColor
        val hasInteraction = onClick != null || hoverColor != null

        val callbackId: String? = if (onClick != null) {
            val id = "fline-${UUID.randomUUID()}"
            registerFLineClickCallback(id, onClick)
            id
        } else null

        if (hasInteraction) {
            sb.append("<g")
            if (callbackId != null) {
                sb.append(""" data-fline-callback-id="$callbackId"""")
                sb.append(""" data-fline-vertices='${flineVertexData(original)}'""")
                if (fit != null) {
                    sb.append(""" data-fit-scale="${jsonN(fit.scale)}" data-fit-offset-x="${jsonN(fit.offsetX)}" data-fit-offset-y="${jsonN(fit.offsetY)}"""")
                }
                sb.append(""" onclick="window.sendFLineClick&&window.sendFLineClick('$callbackId',window.__flineIntersectionFromSvg&&window.__flineIntersectionFromSvg(event,this))" style="cursor:pointer"""")
            }
            if (hoverColor != null) {
                val hc = vec4ToSvgColor(hoverColor)
                sb.append(""" data-hover-color="$hc"""")
                sb.append(""" onmouseover="window.__flineHoverIn&&window.__flineHoverIn(this)"""")
                sb.append(""" onmouseout="window.__flineHoverOut&&window.__flineHoverOut(this)"""")
            }
            sb.append(">")
        }

        renderFLineToSVG(scaled, sb)

        if (hasInteraction) sb.append("</g>")
    }

    sb.append("</svg>")
    return sb.toString()
}

/** Build compact endpoint metadata for SVG click picking: [x, y, z, originalNodeIndex]. */
private fun flineVertexData(f: FLine): String {
    val vertices = f.nodes.mapIndexedNotNull { nodeIndex, node ->
        val p = when (node) {
            is FLine.MoveTo -> node.to
            is FLine.LineTo -> node.to
            is FLine.CubicTo -> node.to
            is FLine.Close -> null
        } ?: return@mapIndexedNotNull null
        "[${jsonN(p.x)},${jsonN(p.y)},${jsonN(p.z)},$nodeIndex]"
    }
    return vertices.joinToString(prefix = "[", postfix = "]")
}

private fun jsonN(v: Number): String {
    val d = v.toDouble()
    if (!d.isFinite()) return "0"
    return String.format(Locale.US, "%.6f", d).trimEnd('0').trimEnd('.').ifEmpty { "0" }
}

/** Convert a [Vec4] RGBA colour (components in [0,1]) to an SVG `rgba(...)` string. */
internal fun vec4ToSvgColor(c: Vec4): String {
    val r = (c.x * 255).toInt().coerceIn(0, 255)
    val g = (c.y * 255).toInt().coerceIn(0, 255)
    val b = (c.z * 255).toInt().coerceIn(0, 255)
    return "rgba($r,$g,$b,${c.w})"
}

/** Format a floating-point coordinate to a compact SVG number string. */
private fun svgN(v: Float): String {
    if (!v.isFinite()) return "0"
    return "%.3f".format(v).trimEnd('0').trimEnd('.')
}

/** Build the SVG path `d` attribute from the nodes of [f]. */
private fun flinePathData(f: FLine): String {
    val sb = StringBuilder()
    var first = true
    f.nodes.forEach { node ->
        when (node) {
            is FLine.MoveTo -> {
                sb.append("M${svgN(node.to.x)},${svgN(node.to.y)} ")
                first = false
            }
            is FLine.LineTo -> {
                if (first) sb.append("M${svgN(node.to.x)},${svgN(node.to.y)} ")
                else sb.append("L${svgN(node.to.x)},${svgN(node.to.y)} ")
                first = false
            }
            is FLine.CubicTo -> {
                if (first) sb.append("M${svgN(node.to.x)},${svgN(node.to.y)} ")
                else sb.append(
                    "C${svgN(node.c1.x)},${svgN(node.c1.y)} " +
                    "${svgN(node.c2.x)},${svgN(node.c2.y)} " +
                    "${svgN(node.to.x)},${svgN(node.to.y)} "
                )
                first = false
            }
            is FLine.Close -> sb.append("Z ")
        }
    }
    return sb.toString().trimEnd()
}

/**
 * Append SVG `<path>` and/or `<circle>` elements for [f] to [sb].
 * Uses presentation attributes so that CSS hover rules (applied by the JS helpers in
 * `sliders.js`) can override stroke/fill without fighting inline `style` specificity.
 */
private fun renderFLineToSVG(f: FLine, sb: StringBuilder) {
    val color = f.color
    val colorStr = vec4ToSvgColor(color)
    val strokeWidth = svgN((f.thickness ?: 1.0).toFloat())
    val pathData = flinePathData(f)

    if (pathData.isNotEmpty()) {
        when {
            f.stroked && f.filled -> {
                sb.append("""<path d="$pathData" stroke="$colorStr" stroke-width="$strokeWidth" fill="$colorStr"/>""")
            }
            f.stroked -> {
                sb.append("""<path d="$pathData" stroke="$colorStr" stroke-width="$strokeWidth" fill="none"/>""")
            }
            f.filled -> {
                sb.append("""<path d="$pathData" stroke="none" fill="$colorStr"/>""")
            }
        }
    }

    if (f.pointed) {
        f.nodes.forEach { node ->
            val to = when (node) {
                is FLine.MoveTo -> node.to
                is FLine.LineTo -> node.to
                is FLine.CubicTo -> node.to
                is FLine.Close -> null
            } ?: return@forEach
            val r = svgN(((node.pointSize ?: f.pointSize) * 3.0).toFloat())
            sb.append("""<circle cx="${svgN(to.x)}" cy="${svgN(to.y)}" r="$r" fill="$colorStr"/>""")
        }
    }
}
