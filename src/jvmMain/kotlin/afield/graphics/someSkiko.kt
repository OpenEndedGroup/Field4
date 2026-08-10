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
import kotlin.math.max
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
        display(channel, wrapLinesOutput(buildInteractiveSVG(pairs, w, h, fit), pairs.map { it.second }.combinedRenderableBoundsOrNull()))
    } else {
        val scaled = lines.map { it.applyFit(fit) }
        display(channel, wrapLinesOutput(drawToSVG(scaled, w, h), renderedSvgBoundsFor(scaled, w, h)))
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
        display(channel, wrapLinesOutput(buildInteractiveSVG(pairs, w, h, fit), pairs.map { it.second }.combinedRenderableBoundsOrNull()))
    } else {
        val scaled = lines.map { it.applyFit(fit) }
        display(channel, wrapLinesOutput(drawToSVG(scaled, w, h), renderedSvgBoundsFor(scaled, w, h)))
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
                display(channelName, wrapLinesOutput(buildInteractiveSVG(pairs, w, h, fit), pairs.map { it.second }.combinedRenderableBoundsOrNull()))
            } else {
                val scaled = lines.map { it.applyFit(fit) }
                display(channelName, wrapLinesOutput(drawToSVG(scaled, w, h), renderedSvgBoundsFor(scaled, w, h)))
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
            display(channel, wrapLinesOutput(buildInteractiveSVG(pairs, w, h, fit), pairs.map { it.second }.combinedRenderableBoundsOrNull()))
        } else {
            val scaled = allLines.map { it.applyFit(fit) }
            display(channel, wrapLinesOutput(drawToSVG(scaled, w, h), renderedSvgBoundsFor(scaled, w, h)))
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

private fun FLine.safeFast2DBoundsOrNull() = runCatching { fast2DBounds() }.getOrNull()?.takeIf {
    it.x.isFinite() && it.y.isFinite() && it.w.isFinite() && it.h.isFinite() && it.w >= 0.0 && it.h >= 0.0
}

private fun Collection<FLine>.renderableLines(): List<FLine> =
    filter { it.safeFast2DBoundsOrNull() != null }

private fun Collection<FLine>.combinedRenderableBoundsOrNull() =
    mapNotNull { it.safeFast2DBoundsOrNull() }.reduceOrNull { a, b -> a.union(b) }

private fun emptySvg(w: Double, h: Double): String {
    val width = if (w > 0.0) w else 1.0
    val height = if (h > 0.0) h else 1.0
    return """<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" style="overflow:visible"/>"""
}

private fun wrapLinesOutput(svg: String, bounds: afield.Rect? = null): String {
    val boundsAttrs = bounds?.let {
        """ data-afield-bounds-x="${jsonN(it.x)}" data-afield-bounds-y="${jsonN(it.y)}" data-afield-bounds-w="${jsonN(it.w)}" data-afield-bounds-h="${jsonN(it.h)}""""
    } ?: ""
    return """<div class="afield-lines-output" data-afield-lines-output="true"$boundsAttrs>$svg</div>"""
}

private fun renderedSvgBoundsFor(lines: Collection<FLine>, w: Double, h: Double): afield.Rect? {
    val bounds = lines.combinedRenderableBoundsOrNull() ?: return null
    return afield.Rect(
        if (w <= 0.0) 0.0 else bounds.x,
        if (h <= 0.0) 0.0 else bounds.y,
        bounds.w,
        bounds.h
    )
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

    val bounds = lines.combinedRenderableBoundsOrNull() ?: return emptySvg(w, h)
    val autoWidth = w <= 0.0
    val autoHeight = h <= 0.0
    var we = w
    var he = h
    if (autoWidth) we = max(1.0, bounds.w)
    if (autoHeight) he = max(1.0, bounds.h)

    val renderedLines = if (autoWidth || autoHeight) {
        val tx = if (autoWidth) -bounds.x else 0.0
        val ty = if (autoHeight) -bounds.y else 0.0
        lines.map { line ->
            line.transform { p -> vec(p.x + tx, p.y + ty, p.z) }
        }
    } else {
        lines
    }

    var s: Canvas? = null
    try {
        s = SVGCanvas.make(Rect.makeWH(we.toFloat(), he.toFloat()), o, true, true)

        renderedLines.forEach {
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
 *
 * @param antiAlias smooth the path's edges. Off by default, which is Skia's own default and what the
 * SVG outputs have always used — an SVG is rasterised by the viewer, so asking Skia to antialias one
 * only fattens the emitted geometry. Turn it on when the canvas is a **raster** surface and the
 * result is the final image, as `afield.rect`'s PNG figures are; without it a diagonal roof edge
 * comes out as a staircase.
 */
fun outputTo(f: FLine, s: Canvas, antiAlias: Boolean = false) {
    val p = Path()
    val paint = Paint()
    paint.isAntiAlias = antiAlias
    var circ: Path? = null
    try {
        var first = true
        f.nodes.forEach {
            when (it) {
                is FLine.MoveTo -> p.moveTo(it.to.x, it.to.y)
                is FLine.LineTo -> {
                    if (first) {
                        p.moveTo(it.to.x, it.to.y)
                    } else
                        p.lineTo(it.to.x, it.to.y)
                }

                is FLine.CubicTo -> {
                    if (first) {
                        p.moveTo(it.to.x, it.to.y)
                    } else

                        p.cubicTo(
                            it.c1.x,
                            it.c1.y,
                            it.c2.x,
                            it.c2.y,
                            it.to.x,
                            it.to.y
                        )
                }

                is FLine.Close -> p.closePath()
            }
            first = false
        }

        paint.strokeWidth = f.thickness?.toFloat() ?: 1.0f
        val c = f.color
        paint.color4f = Color4f(c.x, c.y, c.z, c.w)
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
                    is FLine.MoveTo -> circ.addCircle(it.to.x, it.to.y, radiusFor(f, it).toFloat())
                    is FLine.LineTo -> circ.addCircle(it.to.x, it.to.y, radiusFor(f, it).toFloat())
                    is FLine.CubicTo -> circ.addCircle(it.to.x, it.to.y, radiusFor(f, it).toFloat())
                    is FLine.Close -> {}
                }
            }

            paint.color4f = Color4f(c.x, c.y, c.z, c.w)
            paint.mode = PaintMode.FILL
            s.drawPath(circ, paint)
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
 *
 * A clickable `<g>` also carries what the browser needs to say **what** was hit rather than only
 * "this line was": its index in [pairs] (`data-fline-index`), its [FLine.label], its topology
 * (`data-fline-elements` — segments and closed subpaths, with node indices) and the picking
 * tolerances (`data-fline-pick`). All of it in the *original*, unfitted coordinate space, like the
 * older `data-fline-vertices`, because that is the space the user's own numbers are in; the browser
 * inverts [fit] before it picks. `__flineIntersectionFromSvg` in `sliders.js` is the consumer, and
 * `FLineHitRecordProbeTest` executes that very function against the SVG this builds.
 */
internal fun buildInteractiveSVG(pairs: List<Pair<FLine, FLine>>, w: Double, h: Double, fit: FitTransform? = null): String {
    val renderablePairs = pairs.withIndex()
        .filter { (_, pair) -> pair.second.safeFast2DBoundsOrNull() != null }
        .map { (index, pair) -> Triple(index, pair.first, pair.second) }
    if (renderablePairs.isEmpty()) return emptySvg(w, h)

    val we: Double
    val he: Double
    if (w <= 0.0 || h <= 0.0) {
        val scaledLines = renderablePairs.map { it.third }
        val bounds = scaledLines.combinedRenderableBoundsOrNull() ?: return emptySvg(w, h)
        we = if (w > 0.0) w else max(1.0, max(bounds.w, bounds.x + bounds.w))
        he = if (h > 0.0) h else max(1.0, max(bounds.h, bounds.y + bounds.h))
    } else {
        we = w
        he = h
    }

    val sb = StringBuilder()
    sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$we" height="$he" style="overflow:visible"><g vector-effect="non-scaling-stroke">""")
    sb.append(CALIBRATION_RECT)

    renderablePairs.forEach { (lineIndex, original, scaled) ->
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
                sb.append(""" data-fline-index="$lineIndex"""")
                original.label?.let { sb.append(""" data-fline-label="${svgAttr(it)}"""") }
                sb.append(""" data-fline-elements='${flineElementData(original)}'""")
                sb.append(""" data-fline-pick='${flinePickData(original, fit)}'""")
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

    sb.append("</g></svg>")
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

private fun jsonPoint(p: Vec3): String = "[${jsonN(p.x)},${jsonN(p.y)},${jsonN(p.z)}]"

/** Escape a string for use inside a double-quoted SVG/HTML attribute. */
private fun svgAttr(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

/**
 * The line's topology, for a browser that has to name the element a click landed on.
 *
 * Two lists, both in the line's own coordinates:
 *
 *  - `segments`, one per drawn segment, in draw order. `k` is `l` (straight), `c` (cubic) or `z` (the
 *    segment a `close` draws back to the subpath's first anchor); `n` is the index in `nodes` of the
 *    node that *draws* the segment; `p` is the subpath (`moveTo`-delimited piece) it belongs to; `e`
 *    is its ordinal among the line's segments; `a`/`b` are its ends, `na`/`nb` those ends' own node
 *    indices (which for a `close` are the last and the *first* anchor, not `n`), and `c1`/`c2` a
 *    cubic's controls.
 *  - `faces`, one per **closed** subpath, as the polygon through that subpath's anchors — cubics
 *    contribute their endpoint, not their curve, which is what makes the four-corner test below mean
 *    "a quad panel" rather than "a shape with four straight sides". `n` is the `close` node's index.
 *
 * A leading `lineTo`/`cubicTo` with no `moveTo` before it starts a subpath and draws nothing, exactly
 * as [flinePathData] and [outputTo] treat it — otherwise the browser would name segments the renderer
 * never drew.
 */
private fun flineElementData(f: FLine): String {
    val segments = StringBuilder()
    val faces = StringBuilder()
    var segmentCount = 0
    var faceCount = 0
    var subPath = -1
    var current: Vec3? = null
    var currentNode = -1
    var subPathStart: Vec3? = null
    var subPathStartNode = -1
    val anchors = mutableListOf<Vec3>()
    var closeNode = -1

    fun segment(
        kind: String,
        nodeIndex: Int,
        a: Vec3,
        b: Vec3,
        nodeA: Int,
        nodeB: Int,
        c1: Vec3? = null,
        c2: Vec3? = null
    ) {
        if (segmentCount > 0) segments.append(',')
        segments.append("""{"k":"$kind","n":$nodeIndex,"p":$subPath,"e":$segmentCount""")
        segments.append(""","na":$nodeA,"nb":$nodeB""")
        segments.append(""","a":${jsonPoint(a)},"b":${jsonPoint(b)}""")
        if (c1 != null && c2 != null) segments.append(""","c1":${jsonPoint(c1)},"c2":${jsonPoint(c2)}""")
        segments.append('}')
        segmentCount++
    }

    fun face() {
        if (anchors.size < 3) return
        if (faceCount > 0) faces.append(',')
        faces.append("""{"n":$closeNode,"p":$subPath,"e":$faceCount,"pts":[""")
        faces.append(anchors.joinToString(",") { jsonPoint(it) })
        faces.append("]}")
        faceCount++
    }

    fun begin(at: Vec3, nodeIndex: Int) {
        subPath++
        current = at
        currentNode = nodeIndex
        subPathStart = at
        subPathStartNode = nodeIndex
        anchors.clear()
        anchors.add(at)
    }

    f.nodes.forEachIndexed { nodeIndex, node ->
        when (node) {
            is FLine.MoveTo -> begin(node.to, nodeIndex)
            is FLine.LineTo -> {
                val from = current
                if (from == null) begin(node.to, nodeIndex)
                else {
                    segment("l", nodeIndex, from, node.to, currentNode, nodeIndex)
                    current = node.to
                    currentNode = nodeIndex
                    anchors.add(node.to)
                }
            }
            is FLine.CubicTo -> {
                val from = current
                if (from == null) begin(node.to, nodeIndex)
                else {
                    segment("c", nodeIndex, from, node.to, currentNode, nodeIndex, node.c1, node.c2)
                    current = node.to
                    currentNode = nodeIndex
                    anchors.add(node.to)
                }
            }
            is FLine.Close -> {
                val from = current
                val start = subPathStart
                if (from != null && start != null) {
                    closeNode = nodeIndex
                    // A subpath whose last anchor already coincides with its first draws no closing
                    // segment, but it is still a face.
                    if (from.x != start.x || from.y != start.y || from.z != start.z) {
                        segment("z", nodeIndex, from, start, currentNode, subPathStartNode)
                    } else if (anchors.size > 1) {
                        anchors.removeAt(anchors.size - 1)
                    }
                    face()
                }
                current = null
                currentNode = -1
                subPathStart = null
                subPathStartNode = -1
                anchors.clear()
            }
        }
    }

    return """{"segments":[$segments],"faces":[$faces]}"""
}

/**
 * How close, **in the line's own units**, a click has to be to count as hitting a node or a segment.
 *
 * Chosen in screen pixels and divided by the fit scale, because that is where the user's finger is:
 * a figure scaled down to a thumbnail should not need sub-unit precision to click. A node wins over a
 * segment and a segment over a face, so these two numbers are the whole picking policy — [PICK_POINT_PX]
 * deliberately small (4px) so that clicking the rim of a filled shape still reads as its edge rather
 * than as whichever tessellation vertex happened to be nearest.
 */
private fun flinePickData(f: FLine, fit: FitTransform?): String {
    val scale = fit?.scale?.toDouble()?.takeIf { it > 0.0 } ?: 1.0
    val pointPx = if (f.pointed) max(f.pointSize * 3.0, PICK_POINT_PX) else PICK_POINT_PX
    val edgePx = max((f.thickness ?: 1.0) / 2.0, PICK_EDGE_PX)
    return """{"point":${jsonN(pointPx / scale)},"edge":${jsonN(edgePx / scale)}}"""
}

private const val PICK_POINT_PX = 4.0
private const val PICK_EDGE_PX = 4.0

/**
 * A painted-nothing square of known size, so the browser can measure how the page is scaling this SVG.
 *
 * Measured on a live canvas, 2026-08-10: an interactive figure's clicks were arriving in the wrong
 * place — a click in the middle of one panel reconstructed to a point three times too far up the
 * figure, and therefore named the wrong element. The cause is not in the picking: Field's output area
 * gives the emitted `<svg>` `width: 100%` and the canvas scales the box with CSS, and
 * `SVGSVGElement.getScreenCTM()` — which `__flineIntersectionFromSvg` used on its own — reports the
 * matrix *inside* the SVG only, so the canvas's own scale was missing from the inverse. Nobody had
 * noticed because until this commit nothing read the position: a click's meaning came entirely from
 * *which* callback fired, which is right whatever the coordinates say.
 *
 * `getBoundingClientRect()` of this rectangle, against its known 100×100 user units, measures the
 * whole chain at once — viewBox, CSS transform, CSS zoom, device pixel ratio — with two divisions and
 * no assumptions. It is `pointer-events="none"` so it can never take a click, and paints nothing.
 */
private const val CALIBRATION_RECT =
    """<rect data-fline-calibration="100" x="0" y="0" width="100" height="100" fill="none" stroke="none" pointer-events="none"/>"""

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
                sb.append("""<path d="$pathData" stroke="$colorStr" stroke-width="$strokeWidth" fill="$colorStr" vector-effect="non-scaling-stroke"/>""")
            }
            f.stroked -> {
                sb.append("""<path d="$pathData" stroke="$colorStr" stroke-width="$strokeWidth" fill="none" vector-effect="non-scaling-stroke"/>""")
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
            sb.append("""<circle cx="${svgN(to.x)}" cy="${svgN(to.y)}" r="$r" fill="$colorStr" vector-effect="non-scaling-stroke"/>""")
        }
    }
}
