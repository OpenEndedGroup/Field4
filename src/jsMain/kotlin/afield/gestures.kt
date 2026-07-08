import afield.*
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.DOMRect
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.svg.SVGElement
import org.w3c.dom.svg.SVGSVGElement
import kotlin.math.*


private enum class ConnectionTerminalStyle {
    None,
    Dot,
    PerpendicularTick
}

private data class ConnectionTerminalConfig(
    val style: ConnectionTerminalStyle = ConnectionTerminalStyle.None,
    val endpointInset: Double = 0.0
)

private data class ConnectionTerminals(
    val start: ConnectionTerminalConfig = ConnectionTerminalConfig(),
    val end: ConnectionTerminalConfig = ConnectionTerminalConfig(style = ConnectionTerminalStyle.Dot)
)

private data class ConnectionTerminalLabels(
    val start: String? = null,
    val end: String? = null
)

private data class NestedDisclosureSnapshot(
    val isOpen: Boolean,
    val viewportWorldRect: DOMRect?
)

private fun connectionLabelFor(box: HTMLElement): String? {
    val name = (box.asDynamic().name as? String)?.trim()
    if (!name.isNullOrEmpty()) return name
    val uid = (box.asDynamic().uid as? String)?.trim()
    return uid?.takeIf { it.isNotEmpty() }
}

private fun elementUidOrNull(box: HTMLElement): String? {
    val uid = box.unsafeCast<dynamic>().uid
    if (uid == undefined || uid == null) return null
    return uid.toString()
}

private var currentConnectionSelectedUids: Set<String> = emptySet()

private fun proxyAnchorSelected(box: HTMLElement, terminal: ConnectionTerminalConfig): Boolean {
    if (terminal.style != ConnectionTerminalStyle.PerpendicularTick) return false
    val nestedInUid = box.asDynamic().box_nestedIn as? String ?: return false
    return currentConnectionSelectedUids.contains(nestedInUid)
}

fun connect_center(a_box: HTMLElement, a_rect: DOMRect, b_box: HTMLElement, b_rect: DOMRect, svg: SVGElement, opacity: Double = 1.0) {
    connect_center_with_terminal(
        a_box,
        a_rect,
        b_box,
        b_rect,
        svg,
        opacity,
        ConnectionTerminals(),
        ConnectionTerminalLabels()
    )
}

private fun connect_center_nested_proxy_to(
    a_box: HTMLElement,
    a_rect: DOMRect,
    b_box: HTMLElement,
    b_rect: DOMRect,
    svg: SVGElement,
    opacity: Double = 1.0
) {
    connect_center_with_terminal(
        a_box,
        a_rect,
        b_box,
        b_rect,
        svg,
        opacity,
        ConnectionTerminals(
            end = ConnectionTerminalConfig(
                style = ConnectionTerminalStyle.PerpendicularTick,
                endpointInset = NESTED_PROXY_ENDPOINT_INSET
            )
        ),
        ConnectionTerminalLabels(end = connectionLabelFor(b_box))
    )
}

private fun connect_center_nested_proxy_from(
    a_box: HTMLElement,
    a_rect: DOMRect,
    b_box: HTMLElement,
    b_rect: DOMRect,
    svg: SVGElement,
    opacity: Double = 1.0
) {
    connect_center_with_terminal(
        a_box,
        a_rect,
        b_box,
        b_rect,
        svg,
        opacity,
        ConnectionTerminals(
            start = ConnectionTerminalConfig(
                style = ConnectionTerminalStyle.PerpendicularTick,
                endpointInset = NESTED_PROXY_ENDPOINT_INSET
            )
        ),
        ConnectionTerminalLabels(start = connectionLabelFor(a_box))
    )
}

private fun connect_center_nested_proxy_both(
    a_box: HTMLElement,
    a_rect: DOMRect,
    b_box: HTMLElement,
    b_rect: DOMRect,
    svg: SVGElement,
    opacity: Double = 1.0
) {
    connect_center_with_terminal(
        a_box,
        a_rect,
        b_box,
        b_rect,
        svg,
        opacity,
        ConnectionTerminals(
            start = ConnectionTerminalConfig(
                style = ConnectionTerminalStyle.PerpendicularTick,
                endpointInset = NESTED_PROXY_ENDPOINT_INSET
            ),
            end = ConnectionTerminalConfig(
                style = ConnectionTerminalStyle.PerpendicularTick,
                endpointInset = NESTED_PROXY_ENDPOINT_INSET
            )
        ),
        ConnectionTerminalLabels(
            start = connectionLabelFor(a_box),
            end = connectionLabelFor(b_box)
        )
    )
}

private fun connect_center_with_terminal(
    a_box: HTMLElement,
    a_rect: DOMRect,
    b_box: HTMLElement,
    b_rect: DOMRect,
    svg: SVGElement,
    opacity: Double = 1.0,
    terminals: ConnectionTerminals = ConnectionTerminals(),
    terminalLabels: ConnectionTerminalLabels = ConnectionTerminalLabels()
) {
    val (rawA, rawB) = connectCenters(a_rect, b_rect)

    val maxStartInset = max(0.0, rawA.point.distance(rawB.point) - MIN_CONNECTION_LENGTH)
    val clampedStartInset = min(terminals.start.endpointInset, maxStartInset)
    val a = if (clampedStartInset > 0.0) {
        ClippedResult(
            Point(
                rawA.point.x + rawA.normal.x * clampedStartInset,
                rawA.point.y + rawA.normal.y * clampedStartInset
            ),
            rawA.normal
        )
    } else {
        rawA
    }

    val maxEndInset = max(0.0, a.point.distance(rawB.point) - MIN_CONNECTION_LENGTH)
    val clampedEndInset = min(terminals.end.endpointInset, maxEndInset)
    val b = if (clampedEndInset > 0.0) {
        ClippedResult(
            Point(
                rawB.point.x + rawB.normal.x * clampedEndInset,
                rawB.point.y + rawB.normal.y * clampedEndInset
            ),
            rawB.normal
        )
    } else {
        rawB
    }
    if (!a.point.isFinite() || !b.point.isFinite()) return
    if (a.point.distance(b.point) < 1e-3) return

    val startHeading = atan2(a.normal.y, a.normal.x)
    val endHeading = atan2(-b.normal.y, -b.normal.x)
    val startScreen = DubinsPose(a.point.x, a.point.y, startHeading)
    val endScreen = DubinsPose(b.point.x, b.point.y, endHeading)

    val straight = a.point.distance(b.point)
    val maxAllowedRadius = max(1.0, straight / 3.1)
    val radius = min(dubinsMinRadius, maxAllowedRadius)

    val key = "${(a_box.asDynamic().uid ?: a_box.hashCode()).toString()}->${(b_box.asDynamic().uid ?: b_box.hashCode()).toString()}"
    currentFrameKeys += key

    val fromSelected = selected.contains(a_box) || proxyAnchorSelected(a_box, terminals.start)
    val toSelected = selected.contains(b_box) || proxyAnchorSelected(b_box, terminals.end)
    val isHighlighted = fromSelected || toSelected
    val color = when {
        fromSelected && !toSelected -> OUTGOING_CONNECTION_COLOR
        toSelected && !fromSelected -> INCOMING_CONNECTION_COLOR
        fromSelected && toSelected -> SELECTED_CONNECTION_COLOR
        else -> UNSELECTED_CONNECTION_COLOR
    }
    val visibilityScale = if (isHighlighted) HIGHLIGHTED_CONNECTION_VISIBILITY else DIMMED_CONNECTION_VISIBILITY
    val drawOpacity = (opacity * visibilityScale).coerceIn(0.0, 1.0)
    val markerLayer = currentFrontMarkerLayer ?: svg
    val startMarkerPoint = Point(a.point.x - a.normal.x * TERMINAL_MARKER_OFFSET, a.point.y - a.normal.y * TERMINAL_MARKER_OFFSET)
    val endMarkerPoint = Point(b.point.x + b.normal.x * TERMINAL_MARKER_OFFSET, b.point.y + b.normal.y * TERMINAL_MARKER_OFFSET)
    val startLabel = if (isHighlighted) terminalLabels.start else null
    val endLabel = if (isHighlighted) terminalLabels.end else null

    val dubins = dubinsShortestPath(screenToStandard(startScreen), screenToStandard(endScreen), radius)
    if (dubins == null) {
        drawFallbackBezier(a, b, svg, drawOpacity, color)
        addTerminalDecoration(markerLayer, "$key-start", startMarkerPoint, a, a_rect, drawOpacity, color, terminals.start.style, startLabel)
        addTerminalDecoration(markerLayer, "$key-end", endMarkerPoint, b, b_rect, drawOpacity, color, terminals.end.style, endLabel)
        return
    }

    val scratch = connectionGeometryScratch
    val step = max(3.0, radius / 3.0) / 2.0
    sampleDubinsPathPoints(dubins, screenToStandard(startScreen), step, scratch.sampled)
    val smoothedPoints = filterConnection(key, scratch.sampled, scratch)
    val drawPoints = if (smoothedPoints.size > MAX_SEGMENTS + 1) {
        resamplePolyline(smoothedPoints, MAX_SEGMENTS + 1, scratch.draw)
        scratch.draw
    } else {
        smoothedPoints
    }
    if (drawPoints.size == 0) return
    val arrowPose = midPose(drawPoints)

    path {
        moveTo(drawPoints.x(0), drawPoints.y(0))
        for (idx in 1 until drawPoints.size) {
            lineTo(drawPoints.x(idx), drawPoints.y(idx))
        }
        stroke = color
        strokeWidth = 1
        fill = "none"
        this.path.setAttribute("opacity", drawOpacity.toString())
        appendTo(svg)
    }

    val viewportRect = currentConnectionViewportRect
    val offscreenLayer = currentFrontMarkerLayer
    if (viewportRect != null && offscreenLayer != null && fromSelected != toSelected) {
        val hiddenBox: HTMLElement?
        val markerPoint: Point?
        val offscreenColor: String
        val directionKey: String
        when {
            fromSelected && !rectsOverlap(b_rect, viewportRect) -> {
                hiddenBox = b_box
                markerPoint = offscreenMarkerPointOnPolyline(drawPoints, viewportRect, forward = true)
                offscreenColor = OUTGOING_CONNECTION_COLOR
                directionKey = "out"
            }
            toSelected && !rectsOverlap(a_rect, viewportRect) -> {
                hiddenBox = a_box
                markerPoint = offscreenMarkerPointOnPolyline(drawPoints, viewportRect, forward = false)
                offscreenColor = INCOMING_CONNECTION_COLOR
                directionKey = "in"
            }
            else -> {
                hiddenBox = null
                markerPoint = null
                offscreenColor = color
                directionKey = ""
            }
        }
        val label = hiddenBox?.let { connectionLabelFor(it) }
        if (markerPoint != null && hiddenBox != null && !label.isNullOrBlank()) {
            val labelKey = "$key-$directionKey-${elementUidOrNull(hiddenBox) ?: hiddenBox.hashCode()}"
            val animate = seenOffscreenConnectionLabels.add(labelKey)
            addOffscreenConnectionLabel(
                offscreenLayer,
                labelKey,
                markerPoint,
                viewportRect,
                drawOpacity,
                offscreenColor,
                label,
                animate
            )
        }
    }

    addArrowHead(svg, Point(arrowPose.x, arrowPose.y), arrowPose.heading, drawOpacity, color)
    addTerminalDecoration(markerLayer, "$key-start", startMarkerPoint, a, a_rect, drawOpacity, color, terminals.start.style, startLabel)
    addTerminalDecoration(markerLayer, "$key-end", endMarkerPoint, b, b_rect, drawOpacity, color, terminals.end.style, endLabel)
}

private const val SELECTED_CONNECTION_COLOR = "var(--field-cyan)"
private const val INCOMING_CONNECTION_COLOR = "var(--field-cyan)"
private const val OUTGOING_CONNECTION_COLOR = "rgba(235, 150, 70, 0.9)"
private const val UNSELECTED_CONNECTION_COLOR = "rgba(255,255,255,1.0)"
private const val HIGHLIGHTED_CONNECTION_VISIBILITY = 1.0
private const val DIMMED_CONNECTION_VISIBILITY = 0.12
private const val TERMINAL_MARKER_RADIUS = 3.25
private const val TERMINAL_MARKER_OFFSET = 0.0
private const val NESTED_PROXY_ENDPOINT_INSET = 20.0
private const val NESTED_PROXY_TICK_HALF_LENGTH = 5.5
private const val NESTED_PROXY_TICK_STROKE_WIDTH = 1.8
private const val NESTED_PROXY_LABEL_X_OFFSET = 7.0
private const val NESTED_PROXY_LABEL_Y_OFFSET = 8.0
private const val NESTED_PROXY_LABEL_FONT_SIZE = 10
private const val NESTED_PROXY_LABEL_BG_PAD_X = 3.5
private const val NESTED_PROXY_LABEL_BG_PAD_Y = 1.5
private const val NESTED_PROXY_LABEL_BG_RADIUS = 2.5
private const val NESTED_PROXY_LABEL_BG_FILL = "rgba(0,0,0,0.75)"
private const val OFFSCREEN_CONNECTION_LABEL_FONT_SIZE = 10
private const val OFFSCREEN_CONNECTION_LABEL_OFFSET = 8.0
private const val OFFSCREEN_CONNECTION_INSET = 10.0
private const val OFFSCREEN_CONNECTION_DOT_RADIUS = 2.75
private const val MIN_CONNECTION_LENGTH = 6.0
private const val NESTED_VIEWPORT_VISIBILITY_EPSILON = 1e-6
private const val FRONT_CONNECTION_LAYER_ID = "overlaysvgfrontlayer"
var knownConnections = mutableMapOf("parent->child" to ::connect_center)
var knownProxyConnectionsTo = mutableMapOf("parent->child" to ::connect_center_nested_proxy_to)
var knownProxyConnectionsFrom = mutableMapOf("parent->child" to ::connect_center_nested_proxy_from)
var knownProxyConnectionsBoth = mutableMapOf("parent->child" to ::connect_center_nested_proxy_both)
var dubinsMinRadius = 190.0 // ends up being length / 3

private const val FILTER_RESAMPLE_COUNT = 64
private const val FILTER_ALPHA = 0.01
private const val FILTER_EPS = 0.5
private const val MAX_SEGMENTS = 128

private data class PointBuffer(var coords: DoubleArray = DoubleArray(0), var size: Int = 0) {
    fun ensureCapacity(points: Int) {
        val required = points * 2
        if (coords.size >= required) return
        var capacity = max(16, coords.size)
        while (capacity < required) capacity *= 2
        coords = coords.copyOf(capacity)
    }

    fun clear() {
        size = 0
    }

    fun add(x: Double, y: Double) {
        ensureCapacity(size + 1)
        val idx = size * 2
        coords[idx] = x
        coords[idx + 1] = y
        size += 1
    }

    fun set(index: Int, x: Double, y: Double) {
        val idx = index * 2
        coords[idx] = x
        coords[idx + 1] = y
    }

    fun x(index: Int): Double = coords[index * 2]
    fun y(index: Int): Double = coords[index * 2 + 1]

    fun allFinite(): Boolean {
        for (i in 0 until size * 2) {
            if (!coords[i].isFinite()) return false
        }
        return true
    }
}

private data class ConnectionFilterState(val filtered: PointBuffer = PointBuffer())

private data class ConnectionGeometryScratch(
    val sampled: PointBuffer = PointBuffer(),
    val filtered: PointBuffer = PointBuffer(),
    val aligned: PointBuffer = PointBuffer(),
    val resampled: PointBuffer = PointBuffer(),
    val draw: PointBuffer = PointBuffer()
)

private val connectionFilterStates = mutableMapOf<String, ConnectionFilterState>()
private val connectionGeometryScratch = ConnectionGeometryScratch()
private var needsFilterFrame = false
private var filterFrameScheduled = false
private val currentFrameKeys = mutableSetOf<String>()
private var currentFrontMarkerLayer: SVGElement? = null
private var currentFrontMarkerDefs: SVGElement? = null
private var currentConnectionViewportRect: DOMRect? = null
private val seenOffscreenConnectionLabels = mutableSetOf<String>()

private fun DOMRect.copyRect(): DOMRect = DOMRect(x, y, width, height)

private fun ensureFrontConnectionLayer(): SVGSVGElement {
    val existing = document.getElementById(FRONT_CONNECTION_LAYER_ID) as? SVGSVGElement
    if (existing != null) return existing

    val front = document.createElementNS("http://www.w3.org/2000/svg", "svg") as SVGSVGElement
    front.id = FRONT_CONNECTION_LAYER_ID
    front.classList.add("front-connection-layer")
    front.setAttribute("height", "100%")
    front.setAttribute("width", "100%")
    document.body?.appendChild(front)
    return front
}

private fun sanitizeSvgId(value: String): String = value.map {
    if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_'
}.joinToString("")

private fun ensureMarkerMask(maskId: String, trimRect: DOMRect) {
    val defs = currentFrontMarkerDefs ?: return
    val ownerSvg = defs.ownerSVGElement ?: return

    val existing = document.getElementById(maskId)
    if (existing != null) return

    val mask = document.createElementNS("http://www.w3.org/2000/svg", "mask") as SVGElement
    mask.setAttribute("id", maskId)
    mask.setAttribute("maskUnits", "userSpaceOnUse")
    mask.setAttribute("x", "-1000000")
    mask.setAttribute("y", "-1000000")
    mask.setAttribute("width", "2000000")
    mask.setAttribute("height", "2000000")

    val full = document.createElementNS("http://www.w3.org/2000/svg", "rect") as SVGElement
    full.setAttribute("x", "-1000000")
    full.setAttribute("y", "-1000000")
    full.setAttribute("width", "2000000")
    full.setAttribute("height", "2000000")
    full.setAttribute("fill", "white")
    mask.appendChild(full)

    val cutout = document.createElementNS("http://www.w3.org/2000/svg", "rect") as SVGElement
    cutout.setAttribute("x", trimRect.x.toString())
    cutout.setAttribute("y", trimRect.y.toString())
    cutout.setAttribute("width", trimRect.width.toString())
    cutout.setAttribute("height", trimRect.height.toString())
    cutout.setAttribute("fill", "black")
    mask.appendChild(cutout)

    defs.appendChild(mask)
    ownerSvg.appendChild(defs)
}

private fun addTerminalMarker(layer: SVGElement, key: String, point: Point, trimRect: DOMRect, opacity: Double, color: String) {
    val marker = document.createElementNS("http://www.w3.org/2000/svg", "circle") as SVGElement
    marker.setAttribute("cx", point.x.toString())
    marker.setAttribute("cy", point.y.toString())
    marker.setAttribute("r", TERMINAL_MARKER_RADIUS.toString())
    marker.setAttribute("fill", color)
    marker.setAttribute("opacity", opacity.toString())

    val maskId = "conn-end-mask-${sanitizeSvgId(key)}"
    ensureMarkerMask(maskId, trimRect)
    marker.setAttribute("mask", "url(#$maskId)")

    layer.appendChild(marker)
}

private fun addPerpendicularTerminalTick(layer: SVGElement, point: Point, endNormal: Point, opacity: Double, color: String) {
    val dirLen = hypot(endNormal.x, endNormal.y)
    if (dirLen < 1e-6) return

    val nx = endNormal.x / dirLen
    val ny = endNormal.y / dirLen
    val tx = -ny
    val ty = nx
    val half = NESTED_PROXY_TICK_HALF_LENGTH

    path {
        moveTo(point.x - tx * half, point.y - ty * half)
        lineTo(point.x + tx * half, point.y + ty * half)
        stroke = color
        strokeWidth = NESTED_PROXY_TICK_STROKE_WIDTH
        fill = "none"
        this.path.setAttribute("opacity", opacity.toString())
        this.path.setAttribute("stroke-linecap", "round")
        appendTo(layer)
    }
}

private fun addTerminalDecoration(
    layer: SVGElement,
    key: String,
    point: Point,
    clippedEnd: ClippedResult,
    trimRect: DOMRect,
    opacity: Double,
    color: String,
    style: ConnectionTerminalStyle,
    labelText: String? = null
) {
    when (style) {
        ConnectionTerminalStyle.None -> {}
        ConnectionTerminalStyle.Dot -> addTerminalMarker(layer, key, point, trimRect, opacity, color)
        ConnectionTerminalStyle.PerpendicularTick -> {
            addPerpendicularTerminalTick(layer, point, clippedEnd.normal, opacity, color)
            if (!labelText.isNullOrBlank()) {
                addTerminalLabel(layer, point, clippedEnd, trimRect, opacity, color, labelText)
            }
        }
    }
}

private fun addTerminalLabel(
    layer: SVGElement,
    point: Point,
    clippedEnd: ClippedResult,
    trimRect: DOMRect,
    opacity: Double,
    color: String,
    text: String
) {
    val centerX = trimRect.x + trimRect.width * 0.5
    val rightSide = if (abs(clippedEnd.normal.x) > 0.2) clippedEnd.normal.x > 0 else point.x >= centerX
    val mostlyHorizontal = abs(clippedEnd.normal.x) >= abs(clippedEnd.normal.y)
    val placeBelow = if (mostlyHorizontal) {
        // For horizontal exits, keep left-side labels above and right-side labels below.
        rightSide
    } else {
        // For vertical exits, follow the line direction.
        clippedEnd.normal.y > 0
    }
    val textElement = document.createElementNS("http://www.w3.org/2000/svg", "text") as SVGElement
    val dx = if (rightSide) NESTED_PROXY_LABEL_X_OFFSET else -NESTED_PROXY_LABEL_X_OFFSET
    val dy = if (placeBelow) NESTED_PROXY_LABEL_Y_OFFSET else -NESTED_PROXY_LABEL_Y_OFFSET
    textElement.setAttribute("x", (point.x + dx).toString())
    textElement.setAttribute("y", (point.y + dy).toString())
    textElement.setAttribute("text-anchor", if (rightSide) "start" else "end")
    textElement.setAttribute("dominant-baseline", "middle")
    textElement.setAttribute("font-family", "\"Iosevka Web\", \"Iosevka\", monospace")
    textElement.setAttribute("font-size", NESTED_PROXY_LABEL_FONT_SIZE.toString())
    textElement.setAttribute("font-weight", "300")
    textElement.setAttribute("fill", color)
    textElement.setAttribute("opacity", opacity.toString())
    textElement.setAttribute("pointer-events", "none")
    textElement.textContent = text
    layer.appendChild(textElement)

    val bbox = try {
        textElement.asDynamic().getBBox()
    } catch (t: dynamic) {
        null
    } ?: return

    val bx = (bbox.x as Number).toDouble()
    val by = (bbox.y as Number).toDouble()
    val bw = (bbox.width as Number).toDouble()
    val bh = (bbox.height as Number).toDouble()
    if (!bx.isFinite() || !by.isFinite() || !bw.isFinite() || !bh.isFinite()) return

    val bg = document.createElementNS("http://www.w3.org/2000/svg", "rect") as SVGElement
    bg.setAttribute("x", (bx - NESTED_PROXY_LABEL_BG_PAD_X).toString())
    bg.setAttribute("y", (by - NESTED_PROXY_LABEL_BG_PAD_Y).toString())
    bg.setAttribute("width", (bw + NESTED_PROXY_LABEL_BG_PAD_X * 2.0).toString())
    bg.setAttribute("height", (bh + NESTED_PROXY_LABEL_BG_PAD_Y * 2.0).toString())
    bg.setAttribute("rx", NESTED_PROXY_LABEL_BG_RADIUS.toString())
    bg.setAttribute("ry", NESTED_PROXY_LABEL_BG_RADIUS.toString())
    bg.setAttribute("fill", NESTED_PROXY_LABEL_BG_FILL)
    bg.setAttribute("opacity", opacity.toString())
    bg.setAttribute("pointer-events", "none")
    layer.insertBefore(bg, textElement)
}

private fun addOffscreenConnectionLabel(
    layer: SVGElement,
    key: String,
    point: Point,
    viewportRect: DOMRect,
    opacity: Double,
    color: String,
    text: String,
    animate: Boolean
) {
    val group = document.createElementNS("http://www.w3.org/2000/svg", "g") as SVGElement
    group.classList.add("offscreen-connection-label")
    if (animate) group.classList.add("offscreen-connection-label-arrive")
    group.setAttribute("opacity", opacity.toString())
    group.setAttribute("pointer-events", "none")
    group.setAttribute("data-offscreen-label-key", key)
    layer.appendChild(group)

    val marker = document.createElementNS("http://www.w3.org/2000/svg", "circle") as SVGElement
    marker.setAttribute("cx", point.x.toString())
    marker.setAttribute("cy", point.y.toString())
    marker.setAttribute("r", OFFSCREEN_CONNECTION_DOT_RADIUS.toString())
    marker.setAttribute("fill", color)
    group.appendChild(marker)

    val leftDist = abs(point.x - viewportRect.left)
    val rightDist = abs(point.x - viewportRect.right)
    val topDist = abs(point.y - viewportRect.top)
    val bottomDist = abs(point.y - viewportRect.bottom)
    val nearest = min(min(leftDist, rightDist), min(topDist, bottomDist))

    var tx = point.x
    var ty = point.y
    var anchor = "middle"
    when (nearest) {
        leftDist -> {
            tx += OFFSCREEN_CONNECTION_LABEL_OFFSET
            anchor = "start"
        }
        rightDist -> {
            tx -= OFFSCREEN_CONNECTION_LABEL_OFFSET
            anchor = "end"
        }
        topDist -> {
            ty += OFFSCREEN_CONNECTION_LABEL_OFFSET
        }
        else -> {
            ty -= OFFSCREEN_CONNECTION_LABEL_OFFSET
        }
    }

    val probe = document.createElementNS("http://www.w3.org/2000/svg", "text") as SVGElement
    probe.setAttribute("x", "0")
    probe.setAttribute("y", "0")
    probe.setAttribute("text-anchor", "start")
    probe.setAttribute("dominant-baseline", "middle")
    probe.setAttribute("font-family", "\"Iosevka Web\", \"Iosevka\", monospace")
    probe.setAttribute("font-size", OFFSCREEN_CONNECTION_LABEL_FONT_SIZE.toString())
    probe.setAttribute("font-weight", "300")
    probe.setAttribute("visibility", "hidden")
    probe.textContent = text
    group.appendChild(probe)

    val bbox = try {
        probe.asDynamic().getBBox()
    } catch (t: dynamic) {
        null
    }
    group.removeChild(probe)

    val bw = ((bbox?.width as? Number)?.toDouble() ?: 0.0).coerceAtLeast(20.0)
    val bh = ((bbox?.height as? Number)?.toDouble() ?: 0.0).coerceAtLeast(12.0)
    val width = bw + 10.0
    val height = bh + 6.0
    val x = when (anchor) {
        "start" -> tx
        "end" -> tx - width
        else -> tx - width * 0.5
    }
    val y = ty - height * 0.5

    val foreign = document.createElementNS("http://www.w3.org/2000/svg", "foreignObject") as SVGElement
    foreign.setAttribute("x", x.toString())
    foreign.setAttribute("y", y.toString())
    foreign.setAttribute("width", width.toString())
    foreign.setAttribute("height", height.toString())

    val pill = document.createElement("div") as HTMLElement
    pill.classList.add("offscreen-connection-label-bg")
    pill.style.color = color
    pill.style.display = "inline-flex"
    pill.style.alignItems = "center"
    pill.style.justifyContent = "flex-start"
    pill.style.width = "100%"
    pill.style.height = "100%"
    pill.textContent = text
    foreign.appendChild(pill)
    group.appendChild(foreign)
}

private fun pointInsideRect(x: Double, y: Double, rect: DOMRect): Boolean {
    return x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom
}

private fun rectsOverlap(a: DOMRect, b: DOMRect): Boolean {
    return a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
}

private fun segmentViewportCrossing(
    x0: Double,
    y0: Double,
    x1: Double,
    y1: Double,
    viewportRect: DOMRect
): Point? {
    val dx = x1 - x0
    val dy = y1 - y0
    if (abs(dx) < 1e-9 && abs(dy) < 1e-9) return null

    var bestT = Double.POSITIVE_INFINITY
    var best: Point? = null

    fun addCandidate(t: Double, x: Double, y: Double) {
        if (t <= 1e-6 || t > 1.0) return
        if (x < viewportRect.left - 1e-6 || x > viewportRect.right + 1e-6) return
        if (y < viewportRect.top - 1e-6 || y > viewportRect.bottom + 1e-6) return
        if (t < bestT) {
            bestT = t
            best = Point(
                x.coerceIn(viewportRect.left, viewportRect.right),
                y.coerceIn(viewportRect.top, viewportRect.bottom)
            )
        }
    }

    if (abs(dx) > 1e-9) {
        val leftT = (viewportRect.left - x0) / dx
        addCandidate(leftT, viewportRect.left, y0 + leftT * dy)
        val rightT = (viewportRect.right - x0) / dx
        addCandidate(rightT, viewportRect.right, y0 + rightT * dy)
    }
    if (abs(dy) > 1e-9) {
        val topT = (viewportRect.top - y0) / dy
        addCandidate(topT, x0 + topT * dx, viewportRect.top)
        val bottomT = (viewportRect.bottom - y0) / dy
        addCandidate(bottomT, x0 + bottomT * dx, viewportRect.bottom)
    }

    return best
}

private fun offscreenMarkerPointOnPolyline(points: PointBuffer, viewportRect: DOMRect, forward: Boolean): Point? {
    if (points.size < 2) return null
    val range = if (forward) 1 until points.size else (points.size - 2 downTo 0)
    var prevIndex = if (forward) 0 else points.size - 1
    for (idx in range) {
        val x0 = points.x(prevIndex)
        val y0 = points.y(prevIndex)
        val x1 = points.x(idx)
        val y1 = points.y(idx)
        val prevInside = pointInsideRect(x0, y0, viewportRect)
        val nextInside = pointInsideRect(x1, y1, viewportRect)
        if (prevInside && !nextInside) {
            val crossing = segmentViewportCrossing(x0, y0, x1, y1, viewportRect) ?: return null
            val len = hypot(crossing.x - x0, crossing.y - y0)
            if (len < 1e-6) return crossing
            val inset = min(OFFSCREEN_CONNECTION_INSET, len)
            val f = (len - inset) / len
            return Point(
                x0 + (crossing.x - x0) * f,
                y0 + (crossing.y - y0) * f
            )
        }
        prevIndex = idx
    }
    return null
}

private fun drawFallbackBezier(a: ClippedResult, b: ClippedResult, svg: SVGElement, opacity: Double, color: String) {
    val sc = min(50.0, a.point.distance(b.point) / 3)
    path {
        d =
            "M${a.point.x} ${a.point.y} C${a.point.x + a.normal.x * sc} ${a.point.y + a.normal.y * sc} ${b.point.x + b.normal.x * sc} ${b.point.y + b.normal.y * sc} ${b.point.x} ${b.point.y}"
        stroke = color
        strokeWidth = 1
        fill = "none"
        this.path.setAttribute("opacity", opacity.toString())
        appendTo(svg)
    }

    addArrowHeadToCubicSpline(
        svg,
        a.point.x,
        a.point.y,
        a.point.x + a.normal.x * sc,
        a.point.y + a.normal.y * sc,
        b.point.x + b.normal.x * sc,
        b.point.y + b.normal.y * sc,
        b.point.x,
        b.point.y,
        opacity,
        color
    )
}

private fun filterConnection(key: String, target: PointBuffer, scratch: ConnectionGeometryScratch): PointBuffer {
    if (target.size == 0) return target
    if (!target.allFinite()) {
        connectionFilterStates.remove(key)
        return target
    }

    val state = connectionFilterStates.getOrPut(key) { ConnectionFilterState() }
    val resampleCount = max(FILTER_RESAMPLE_COUNT, target.size)
    resamplePolyline(target, resampleCount, scratch.resampled)
    val targetResampled = scratch.resampled
    if (!targetResampled.allFinite()) {
        connectionFilterStates.remove(key)
        return target
    }

    if (state.filtered.size == 0) {
        state.filtered.ensureCapacity(resampleCount)
        state.filtered.size = resampleCount
        val x0 = targetResampled.x(0)
        val y0 = targetResampled.y(0)
        val x1 = targetResampled.x(targetResampled.size - 1)
        val y1 = targetResampled.y(targetResampled.size - 1)
        for (i in 0 until resampleCount) {
            val f = if (resampleCount <= 1) 0.0 else i.toDouble() / (resampleCount - 1).toDouble()
            state.filtered.set(i, x0 + (x1 - x0) * f, y0 + (y1 - y0) * f)
        }
        needsFilterFrame = true
        return state.filtered
    }

    val prev = state.filtered
    alignPolyline(
        prev,
        prev.x(0),
        prev.y(0),
        prev.x(prev.size - 1),
        prev.y(prev.size - 1),
        target.x(0),
        target.y(0),
        target.x(target.size - 1),
        target.y(target.size - 1),
        scratch.aligned
    )
    resamplePolyline(scratch.aligned, resampleCount, scratch.filtered)
    val alignedResampled = scratch.filtered
    if (!alignedResampled.allFinite()) {
        connectionFilterStates.remove(key)
        return target
    }

    state.filtered.ensureCapacity(resampleCount)
    state.filtered.size = resampleCount
    for (idx in 0 until resampleCount) {
        if (idx == 0 || idx == resampleCount - 1) {
            state.filtered.set(idx, targetResampled.x(idx), targetResampled.y(idx))
        } else {
            val x = alignedResampled.x(idx) * (1 - FILTER_ALPHA) + targetResampled.x(idx) * FILTER_ALPHA
            val y = alignedResampled.y(idx) * (1 - FILTER_ALPHA) + targetResampled.y(idx) * FILTER_ALPHA
            state.filtered.set(idx, x, y)
        }
    }

    if (!state.filtered.allFinite()) {
        connectionFilterStates.remove(key)
        return target
    }

    var maxDelta = 0.0
    for (idx in 0 until resampleCount) {
        maxDelta = max(maxDelta, hypot(state.filtered.x(idx) - targetResampled.x(idx), state.filtered.y(idx) - targetResampled.y(idx)))
    }
    if (maxDelta > FILTER_EPS) {
        needsFilterFrame = true
    }

    return state.filtered
}

private fun alignPolyline(
    points: PointBuffer,
    oldStartX: Double,
    oldStartY: Double,
    oldEndX: Double,
    oldEndY: Double,
    newStartX: Double,
    newStartY: Double,
    newEndX: Double,
    newEndY: Double,
    out: PointBuffer
) {
    out.clear()
    if (points.size == 0) return

    val oldDx = oldEndX - oldStartX
    val oldDy = oldEndY - oldStartY
    val newDx = newEndX - newStartX
    val newDy = newEndY - newStartY

    val oldLen = hypot(oldDx, oldDy)
    val newLen = hypot(newDx, newDy)
    out.ensureCapacity(points.size)
    out.size = points.size
    if (oldLen < 1e-6 || newLen < 1e-6) {
        for (idx in 0 until points.size) {
            when (idx) {
                0 -> out.set(idx, newStartX, newStartY)
                points.size - 1 -> out.set(idx, newEndX, newEndY)
                else -> out.set(idx, newStartX, newStartY)
            }
        }
        return
    }

    val scale = newLen / oldLen
    val angle = atan2(newDy, newDx) - atan2(oldDy, oldDx)
    val cosA = cos(angle)
    val sinA = sin(angle)

    for (idx in 0 until points.size) {
        val px = (points.x(idx) - oldStartX) * scale
        val py = (points.y(idx) - oldStartY) * scale
        val rx = px * cosA - py * sinA
        val ry = px * sinA + py * cosA
        out.set(idx, rx + newStartX, ry + newStartY)
    }

    out.set(0, newStartX, newStartY)
    out.set(out.size - 1, newEndX, newEndY)
}

private fun resamplePolyline(points: List<Point>, count: Int): List<Point> {
    if (points.isEmpty()) return points
    if (count <= 2) return listOf(points.first(), points.last())

    val lengths = DoubleArray(points.size)
    for (i in 1 until points.size) {
        lengths[i] = lengths[i - 1] + points[i - 1].distance(points[i])
    }
    val total = lengths.last()
    if (total < 1e-6) return List(count) { idx ->
        when (idx) {
            0 -> points.first()
            count - 1 -> points.last()
            else -> points.first()
        }
    }

    val step = total / (count - 1)
    val result = mutableListOf<Point>()
    var seg = 1
    for (i in 0 until count) {
        val targetDist = step * i
        while (seg < lengths.size && lengths[seg] < targetDist) seg++
        if (seg >= lengths.size) {
            result += points.last()
        } else {
            val prevDist = lengths[seg - 1]
            val nextDist = lengths[seg]
            val ratio = if (nextDist - prevDist < 1e-9) 0.0 else (targetDist - prevDist) / (nextDist - prevDist)
            val p0 = points[seg - 1]
            val p1 = points[seg]
            result += Point(
                p0.x + (p1.x - p0.x) * ratio,
                p0.y + (p1.y - p0.y) * ratio
            )
        }
    }
    result[0] = points.first()
    result[result.lastIndex] = points.last()
    return result
}

private fun resamplePolyline(points: PointBuffer, count: Int, out: PointBuffer) {
    out.clear()
    if (points.size == 0) return
    if (count <= 2) {
        out.add(points.x(0), points.y(0))
        out.add(points.x(points.size - 1), points.y(points.size - 1))
        return
    }

    val lengths = DoubleArray(points.size)
    for (i in 1 until points.size) {
        lengths[i] = lengths[i - 1] + hypot(points.x(i) - points.x(i - 1), points.y(i) - points.y(i - 1))
    }
    val total = lengths[points.size - 1]
    out.ensureCapacity(count)
    out.size = count
    if (total < 1e-6) {
        for (idx in 0 until count) {
            when (idx) {
                0 -> out.set(idx, points.x(0), points.y(0))
                count - 1 -> out.set(idx, points.x(points.size - 1), points.y(points.size - 1))
                else -> out.set(idx, points.x(0), points.y(0))
            }
        }
        return
    }

    val step = total / (count - 1)
    var seg = 1
    for (i in 0 until count) {
        val targetDist = step * i
        while (seg < lengths.size && lengths[seg] < targetDist) seg++
        if (seg >= lengths.size) {
            out.set(i, points.x(points.size - 1), points.y(points.size - 1))
        } else {
            val prevDist = lengths[seg - 1]
            val nextDist = lengths[seg]
            val ratio = if (nextDist - prevDist < 1e-9) 0.0 else (targetDist - prevDist) / (nextDist - prevDist)
            val x = points.x(seg - 1) + (points.x(seg) - points.x(seg - 1)) * ratio
            val y = points.y(seg - 1) + (points.y(seg) - points.y(seg - 1)) * ratio
            out.set(i, x, y)
        }
    }
    out.set(0, points.x(0), points.y(0))
    out.set(out.size - 1, points.x(points.size - 1), points.y(points.size - 1))
}

private fun Point.isFinite(): Boolean = x.isFinite() && y.isFinite()


data class DubinsPose(val x: Double, val y: Double, val heading: Double)

enum class DubinsSegmentType { L, S, R }

data class DubinsSegment(val type: DubinsSegmentType, val length: Double)

data class DubinsPath(val radius: Double, val segments: List<DubinsSegment>) {
    val totalLength: Double
        get() = radius * segments.sumOf { it.length }
}

private data class DubinsCandidate(val types: List<DubinsSegmentType>, val lengths: List<Double>)

private fun mod2pi(angle: Double): Double {
    var a = angle % (2 * PI)
    if (a < 0) a += 2 * PI
    return a
}

private fun safeSqrt(v: Double): Double? {
    if (v < -1e-9) return null
    return sqrt(max(0.0, v))
}

private fun computeDubinsCandidates(alpha: Double, beta: Double, d: Double): List<DubinsCandidate> {
    val sa = sin(alpha)
    val sb = sin(beta)
    val ca = cos(alpha)
    val cb = cos(beta)
    val cAB = cos(alpha - beta)

    val candidates = mutableListOf<DubinsCandidate>()

    run {
        val tmp0 = d + sa - sb
        val tmp2 = 2 + d * d - 2 * cAB + 2 * d * (sa - sb)
        val p = safeSqrt(tmp2)
        if (p != null) {
            val tmp1 = atan2(cb - ca, tmp0)
            val t = mod2pi(-alpha + tmp1)
            val q = mod2pi(beta - tmp1)
            candidates += DubinsCandidate(listOf(DubinsSegmentType.L, DubinsSegmentType.S, DubinsSegmentType.L), listOf(t, p, q))
        }
    }

    run {
        val tmp0 = d - sa + sb
        val tmp2 = 2 + d * d - 2 * cAB + 2 * d * (sb - sa)
        val p = safeSqrt(tmp2)
        if (p != null) {
            val tmp1 = atan2(ca - cb, tmp0)
            val t = mod2pi(alpha - tmp1)
            val q = mod2pi(-beta + tmp1)
            candidates += DubinsCandidate(listOf(DubinsSegmentType.R, DubinsSegmentType.S, DubinsSegmentType.R), listOf(t, p, q))
        }
    }

    run {
        val tmp0 = d + sa + sb
        val tmp2 = -2 + d * d + 2 * cAB + 2 * d * (sa + sb)
        val p = safeSqrt(tmp2)
        if (p != null) {
            val tmp1 = atan2(-ca - cb, tmp0) - atan2(-2.0, p)
            val t = mod2pi(-alpha + tmp1)
            val q = mod2pi(-beta + tmp1)
            candidates += DubinsCandidate(listOf(DubinsSegmentType.L, DubinsSegmentType.S, DubinsSegmentType.R), listOf(t, p, q))
        }
    }

    run {
        val tmp0 = d - sa - sb
        val tmp2 = d * d - 2 + 2 * cAB - 2 * d * (sa + sb)
        val p = safeSqrt(tmp2)
        if (p != null) {
            val tmp1 = atan2(ca + cb, tmp0) - atan2(2.0, p)
            val t = mod2pi(alpha - tmp1)
            val q = mod2pi(beta - tmp1)
            candidates += DubinsCandidate(listOf(DubinsSegmentType.R, DubinsSegmentType.S, DubinsSegmentType.L), listOf(t, p, q))
        }
    }

    run {
        val tmp0 = (6 - d * d + 2 * cAB + 2 * d * (sa - sb)) / 8.0
        if (abs(tmp0) <= 1.0 + 1e-9) {
            val p = mod2pi(2 * PI - acos(tmp0.coerceIn(-1.0, 1.0)))
            val t = mod2pi(alpha - atan2(ca - cb, d - sa + sb) + p / 2.0)
            val q = mod2pi(alpha - beta - t + p)
            candidates += DubinsCandidate(listOf(DubinsSegmentType.R, DubinsSegmentType.L, DubinsSegmentType.R), listOf(t, p, q))
        }
    }

    run {
        val tmp0 = (6 - d * d + 2 * cAB + 2 * d * (-sa + sb)) / 8.0
        if (abs(tmp0) <= 1.0 + 1e-9) {
            val p = mod2pi(2 * PI - acos(tmp0.coerceIn(-1.0, 1.0)))
            val t = mod2pi(-alpha - atan2(ca - cb, d + sa - sb) + p / 2.0)
            val q = mod2pi(beta - alpha - t + p)
            candidates += DubinsCandidate(listOf(DubinsSegmentType.L, DubinsSegmentType.R, DubinsSegmentType.L), listOf(t, p, q))
        }
    }

    return candidates
}

fun dubinsShortestPath(start: DubinsPose, end: DubinsPose, minRadius: Double): DubinsPath? {
    val radius = max(1e-3, minRadius)
    val dx = end.x - start.x
    val dy = end.y - start.y
    val d = hypot(dx, dy) / radius
    val theta = atan2(dy, dx)
    val alpha = mod2pi(start.heading - theta)
    val beta = mod2pi(end.heading - theta)

    val candidates = computeDubinsCandidates(alpha, beta, d)
    val best = candidates.minByOrNull { it.lengths.sum() } ?: return null
    val segments = best.types.zip(best.lengths) { type, len -> DubinsSegment(type, len) }
    return DubinsPath(radius, segments)
}

fun sampleDubinsPath(path: DubinsPath, start: DubinsPose, step: Double = 2.0): List<DubinsPose> {
    val poses = mutableListOf<DubinsPose>()
    var x = start.x
    var y = start.y
    var heading = start.heading
    poses += DubinsPose(x, y, heading)

    path.segments.forEach { seg ->
        when (seg.type) {
            DubinsSegmentType.S -> {
                val dist = seg.length * path.radius
                val count = max(1, ceil(dist / step).toInt())
                val delta = dist / count
                repeat(count) {
                    x += delta * cos(heading)
                    y += delta * sin(heading)
                    poses += DubinsPose(x, y, heading)
                }
            }

            DubinsSegmentType.L, DubinsSegmentType.R -> {
                val dir = if (seg.type == DubinsSegmentType.L) 1.0 else -1.0
                val angle = seg.length
                val arcLen = angle * path.radius
                val count = max(1, ceil(arcLen / step).toInt())
                val center = if (dir > 0) {
                    Point(x - path.radius * sin(heading), y + path.radius * cos(heading))
                } else {
                    Point(x + path.radius * sin(heading), y - path.radius * cos(heading))
                }
                (1..count).forEach { i ->
                    val newHeading = heading + dir * angle * (i.toDouble() / count.toDouble())
                    val pos = if (dir > 0) {
                        Point(center.x + path.radius * sin(newHeading), center.y - path.radius * cos(newHeading))
                    } else {
                        Point(center.x - path.radius * sin(newHeading), center.y + path.radius * cos(newHeading))
                    }
                    poses += DubinsPose(pos.x, pos.y, newHeading)
                }
                heading += dir * angle
                x = poses.last().x
                y = poses.last().y
            }
        }
    }

    return poses
}

private fun sampleDubinsPathPoints(path: DubinsPath, start: DubinsPose, step: Double = 2.0, out: PointBuffer) {
    out.clear()
    var x = start.x
    var y = start.y
    var heading = start.heading
    out.add(x, -y)

    path.segments.forEach { seg ->
        when (seg.type) {
            DubinsSegmentType.S -> {
                val dist = seg.length * path.radius
                val count = max(1, ceil(dist / step).toInt())
                val delta = dist / count
                repeat(count) {
                    x += delta * cos(heading)
                    y += delta * sin(heading)
                    out.add(x, -y)
                }
            }

            DubinsSegmentType.L, DubinsSegmentType.R -> {
                val dir = if (seg.type == DubinsSegmentType.L) 1.0 else -1.0
                val angle = seg.length
                val arcLen = angle * path.radius
                val count = max(1, ceil(arcLen / step).toInt())
                val centerX: Double
                val centerY: Double
                if (dir > 0) {
                    centerX = x - path.radius * sin(heading)
                    centerY = y + path.radius * cos(heading)
                } else {
                    centerX = x + path.radius * sin(heading)
                    centerY = y - path.radius * cos(heading)
                }
                for (i in 1..count) {
                    val newHeading = heading + dir * angle * (i.toDouble() / count.toDouble())
                    if (dir > 0) {
                        x = centerX + path.radius * sin(newHeading)
                        y = centerY - path.radius * cos(newHeading)
                    } else {
                        x = centerX - path.radius * sin(newHeading)
                        y = centerY + path.radius * cos(newHeading)
                    }
                    out.add(x, -y)
                }
                heading += dir * angle
            }
        }
    }
}

fun screenToStandard(pose: DubinsPose) = DubinsPose(pose.x, -pose.y, -pose.heading)
fun standardToScreen(pose: DubinsPose) = DubinsPose(pose.x, -pose.y, -pose.heading)

fun posesFromPolyline(points: List<Point>): List<DubinsPose> {
    if (points.isEmpty()) return emptyList()
    if (points.size == 1) return listOf(DubinsPose(points.first().x, points.first().y, 0.0))

    return points.mapIndexed { idx, p ->
        val (dx, dy) = when (idx) {
            0 -> {
                val next = points[idx + 1]
                next.x - p.x to next.y - p.y
            }

            points.lastIndex -> {
                val prev = points[idx - 1]
                p.x - prev.x to p.y - prev.y
            }

            else -> {
                val prev = points[idx - 1]
                val next = points[idx + 1]
                next.x - prev.x to next.y - prev.y
            }
        }
        val heading = atan2(dy, dx)
        DubinsPose(p.x, p.y, heading)
    }
}

fun interpolateHeading(a: Double, b: Double, t: Double): Double {
    var delta = mod2pi(b - a)
    if (delta > PI) delta -= 2 * PI
    return a + delta * t
}

fun midPose(sampled: List<DubinsPose>): DubinsPose {
    if (sampled.isEmpty()) error("Cannot compute mid pose from empty path")
    if (sampled.size == 1) return sampled.first()

    var total = 0.0
    for (i in 1 until sampled.size) {
        val p0 = sampled[i - 1]
        val p1 = sampled[i]
        total += hypot(p1.x - p0.x, p1.y - p0.y)
    }
    if (total < 1e-6) return sampled.first()

    val target = total / 2.0
    var acc = 0.0
    for (i in 1 until sampled.size) {
        val p0 = sampled[i - 1]
        val p1 = sampled[i]
        val seg = hypot(p1.x - p0.x, p1.y - p0.y)
        if (acc + seg >= target) {
            val f = (target - acc) / seg
            val x = p0.x + (p1.x - p0.x) * f
            val y = p0.y + (p1.y - p0.y) * f
            val heading = if (seg < 1e-9) p0.heading else atan2(p1.y - p0.y, p1.x - p0.x)
            return DubinsPose(x, y, heading)
        }
        acc += seg
    }
    return sampled.last()
}

private fun midPose(sampled: PointBuffer): DubinsPose {
    if (sampled.size == 0) error("Cannot compute mid pose from empty path")
    if (sampled.size == 1) return DubinsPose(sampled.x(0), sampled.y(0), 0.0)

    var total = 0.0
    for (i in 1 until sampled.size) {
        total += hypot(sampled.x(i) - sampled.x(i - 1), sampled.y(i) - sampled.y(i - 1))
    }
    if (total < 1e-6) return DubinsPose(sampled.x(0), sampled.y(0), 0.0)

    val target = total / 2.0
    var acc = 0.0
    for (i in 1 until sampled.size) {
        val x0 = sampled.x(i - 1)
        val y0 = sampled.y(i - 1)
        val x1 = sampled.x(i)
        val y1 = sampled.y(i)
        val seg = hypot(x1 - x0, y1 - y0)
        if (acc + seg >= target) {
            val f = (target - acc) / seg
            val x = x0 + (x1 - x0) * f
            val y = y0 + (y1 - y0) * f
            val heading = if (seg < 1e-9) 0.0 else atan2(y1 - y0, x1 - x0)
            return DubinsPose(x, y, heading)
        }
        acc += seg
    }

    val last = sampled.size - 1
    val prev = max(0, last - 1)
    return DubinsPose(sampled.x(last), sampled.y(last), atan2(sampled.y(last) - sampled.y(prev), sampled.x(last) - sampled.x(prev)))
}


class CanvasGestures(
    val launcher: (List<HTMLElement>) -> Unit,
    val executionExpansionProvider: (HTMLElement, Boolean) -> List<HTMLElement> = { box, _ -> listOf(box) }
) {

    companion object {
        val svgNamespace = "http://www.w3.org/2000/svg"

        fun select(selector: String): List<HTMLElement> {
            val s = document.querySelectorAll(selector)
            return (0 until s.length).mapNotNull {
                s.item(it) as? HTMLElement
            }
        }

        fun find(u: String) = select(".box").find { it.unsafeCast<dynamic>().uid === u }

        fun boxes() = select(".box").filter { it.unsafeCast<dynamic>().uid != undefined }
    }


    private var overlayContext: CanvasRenderingContext2D
    private var overlayCanvas: HTMLCanvasElement
    private var cachedViewportWidth = 0.0
    private var cachedViewportHeight = 0.0
    private var cachedBoxes: List<HTMLElement> = emptyList()
    private var cachedBoxByUid: Map<String, HTMLElement> = emptyMap()
    private var cachedWorldRects: Map<HTMLElement, DOMRect> = emptyMap()
    private var cachedDomBoxCount: Int = -1
    private var connectionRebuildScheduled = false
    private var pendingTranslationOnlyRebuild = true

    init {
        println("finding ${document.querySelector("#overlaycanvas")}")
        overlayCanvas = document.querySelector("#overlaycanvas")!! as HTMLCanvasElement
        overlayContext = overlayCanvas.getContext("2d") as CanvasRenderingContext2D
        window.addEventListener("keydown", { event ->
            handleExecutionModifierKey(event as? KeyboardEvent ?: return@addEventListener)
        })
        window.addEventListener("keyup", { event ->
            handleExecutionModifierKey(event as? KeyboardEvent ?: return@addEventListener)
        })
    }

    private fun uidOrNull(box: HTMLElement): String? {
        val uid = box.unsafeCast<dynamic>().uid
        if (uid == undefined || uid == null) return null
        return uid.toString()
    }

    private fun captureLayoutSnapshot(parent: SVGSVGElement, ox: Double, oy: Double) {
        val parentRect = parent.getBoundingClientRect()
        val boxElements = boxes()
        val screenRects = boxElements.associateWith { it.getBoundingClientRect() }
        val worldRects = screenRects.mapValues { (_, rect) ->
            DOMRect(rect.x - ox, rect.y - oy, rect.width, rect.height)
        }
        val boxByUid = boxElements.mapNotNull { box ->
            uidOrNull(box)?.let { it to box }
        }.toMap()

        cachedViewportWidth = parentRect.width
        cachedViewportHeight = parentRect.height
        cachedBoxes = boxElements
        cachedBoxByUid = boxByUid
        cachedWorldRects = worldRects
        cachedDomBoxCount = document.querySelectorAll(".box").length
    }

    private fun hasValidCachedLayoutForTranslation(): Boolean {
        if (cachedBoxes.isEmpty()) return false
        if (document.querySelectorAll(".box").length != cachedDomBoxCount) return false
        if (abs(window.innerWidth.toDouble() - cachedViewportWidth) > 0.5) return false
        if (abs(window.innerHeight.toDouble() - cachedViewportHeight) > 0.5) return false
        if (cachedBoxes.any { it.asDynamic().isConnected != true }) return false
        return true
    }

    private fun nestedDisclosureElement(parentBox: HTMLElement): HTMLElement? {
        val outputAreas = parentBox.getElementsByClassName("outputArea")
        val outputArea = if (outputAreas.length > 0) outputAreas.item(0) as? HTMLElement else null
        if (outputArea == null) return null
        val contain = outputArea.querySelector(".disclosure-group") ?: return null
        val disclosures = contain.querySelectorAll(".disclosure")
        for (i in 0 until disclosures.length) {
            val disclosure = disclosures.item(i) as? HTMLElement ?: continue
            if ((disclosure.asDynamic().__disclosureId as? String) == "nested") {
                return disclosure
            }
        }
        return null
    }

    private fun rectsIntersect(a: DOMRect, b: DOMRect): Boolean {
        return a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
    }

    private fun pointInRect(p: Point, r: DOMRect): Boolean {
        return p.x >= r.left && p.x <= r.right && p.y >= r.top && p.y <= r.bottom
    }

    fun getNamedSVGGroup(name: String) =
        (document.getElementById("overlaysvglayer") as SVGSVGElement).getGroupOrMake(name)

    fun invoke(name: String, builder: SVGElement.() -> Unit) {
        val circleLayer = getNamedSVGGroup(name)
        circleLayer.builder()
    }

    fun requestConnectionGroupRebuild(translationOnly: Boolean = false) {
        pendingTranslationOnlyRebuild = pendingTranslationOnlyRebuild && translationOnly
        if (connectionRebuildScheduled) return
        connectionRebuildScheduled = true
        window.requestAnimationFrame {
            val scheduledTranslationOnly = pendingTranslationOnlyRebuild
            connectionRebuildScheduled = false
            pendingTranslationOnlyRebuild = true
            rebuildConnectionGroup(translationOnly = scheduledTranslationOnly)
        }
    }


    fun rebuildConnectionGroup(translationOnly: Boolean = false) {
        currentFrameKeys.clear()
        needsFilterFrame = false

        val parent = document.getElementById("overlaysvglayer") as SVGSVGElement
        val frontParent = ensureFrontConnectionLayer()
        val ox = mainTranslation[0] * 1.0
        val oy = mainTranslation[1] * 1.0

        val shouldCapture = !translationOnly || !hasValidCachedLayoutForTranslation()
        if (shouldCapture) {
            captureLayoutSnapshot(parent, ox, oy)
        }

        val vbw = cachedViewportWidth
        val vbh = cachedViewportHeight

        parent.setAttribute("viewBox", "${-mainTranslation[0]} ${-mainTranslation[1]} $vbw $vbh")
        frontParent.setAttribute("viewBox", "${-mainTranslation[0]} ${-mainTranslation[1]} $vbw $vbh")

        val svg = getNamedSVGGroup("connections")
        svg.innerHTML = ""
        getNamedSVGGroup("offscreen-hints").innerHTML = ""
        val hints = frontParent.getGroupOrMake("offscreen-hints-front")
        hints.innerHTML = ""
        val markers = frontParent.getGroupOrMake("connection-end-markers")
        markers.innerHTML = ""
        val markerDefs = (document.getElementById("connection-end-marker-defs") as? SVGElement)
            ?: (document.createElementNS(svgNamespace, "defs") as SVGElement).also {
                it.setAttribute("id", "connection-end-marker-defs")
                frontParent.appendChild(it)
            }
        markerDefs.innerHTML = ""
        currentFrontMarkerLayer = markers
        currentFrontMarkerDefs = markerDefs
        currentConnectionSelectedUids = selected.mapNotNull { it.asDynamic().uid as? String }.toSet()

        // example of new box accessable drawing
//        val circleLayer = getNamedSVGGroup("circleLayer")
//
//        run {
//            circle(circleLayer, "circley") {
//                isNew {
//                    cx = 50.0
//                    cy = 50.0
//                    r = 10.0
//                    fill = "white"
//                }
//                onMouseDown {
//                    fill = "red"
//                    animate("cx", 100.0 * Random.nextDouble(), 3.0)
//                    r = 20.0
//                }
//            }
//        }

        fun clamp(v: Double, lo: Double, hi: Double) = max(lo, min(hi, v))

        fun addHintLine(x1: Double, y1: Double, x2: Double, y2: Double) {
            val line = document.createElementNS(svgNamespace, "line") as SVGElement
            line.setAttribute("x1", x1.toString())
            line.setAttribute("y1", y1.toString())
            line.setAttribute("x2", x2.toString())
            line.setAttribute("y2", y2.toString())
            line.setAttribute("stroke", "rgba(255,255,255,0.35)")
            line.setAttribute("stroke-width", "4")
            line.setAttribute("stroke-linecap", "round")
            hints.appendChild(line)
        }

        fun addOffscreenHintForViewport(targetRect: DOMRect, viewportRect: DOMRect) {
            val viewportLeft = viewportRect.x
            val viewportTop = viewportRect.y
            val viewportRight = viewportRect.x + viewportRect.width
            val viewportBottom = viewportRect.y + viewportRect.height

            val offLeft = targetRect.x + targetRect.width < viewportLeft
            val offRight = targetRect.x > viewportRight
            val offTop = targetRect.y + targetRect.height < viewportTop
            val offBottom = targetRect.y > viewportBottom
            if (!(offLeft || offRight || offTop || offBottom)) return

            val margin = 4.0
            val edgeX = if (offLeft) viewportLeft + margin else viewportRight - margin
            val edgeY = if (offTop) viewportTop + margin else viewportBottom - margin
            when {
                (offLeft || offRight) && (offTop || offBottom) -> {
                    addHintLine(edgeX, edgeY, edgeX, edgeY)
                }
                offLeft || offRight -> {
                    val y1 = clamp(targetRect.y, viewportTop, viewportBottom)
                    val y2 = clamp(targetRect.y + targetRect.height, viewportTop, viewportBottom)
                    addHintLine(edgeX, y1, edgeX, y2)
                }
                offTop || offBottom -> {
                    val x1 = clamp(targetRect.x, viewportLeft, viewportRight)
                    val x2 = clamp(targetRect.x + targetRect.width, viewportLeft, viewportRight)
                    addHintLine(x1, edgeY, x2, edgeY)
                }
            }
	        }

	        val mainViewportRect = DOMRect(-ox, -oy, vbw, vbh)
	        currentConnectionViewportRect = mainViewportRect

	        val nestedDisclosureSnapshotCache = mutableMapOf<String, NestedDisclosureSnapshot>()
        fun nestedDisclosureSnapshot(parentUid: String): NestedDisclosureSnapshot = nestedDisclosureSnapshotCache.getOrPut(parentUid) {
            val parentBox = cachedBoxByUid[parentUid] ?: return@getOrPut NestedDisclosureSnapshot(
                isOpen = false,
                viewportWorldRect = null
            )
            val disclosure = nestedDisclosureElement(parentBox)
            val isOpen = disclosure?.classList?.contains("open") == true
            if (!isOpen) {
                return@getOrPut NestedDisclosureSnapshot(
                    isOpen = false,
                    viewportWorldRect = null
                )
            }
            val viewport = disclosure.querySelector(".disclosure-content") as? HTMLElement
            val r = viewport?.getBoundingClientRect()
            val viewportWorldRect = if (
                r != null &&
                r.width > NESTED_VIEWPORT_VISIBILITY_EPSILON &&
                r.height > NESTED_VIEWPORT_VISIBILITY_EPSILON
            ) {
                DOMRect(r.x - ox, r.y - oy, r.width, r.height)
            } else {
                null
            }
            NestedDisclosureSnapshot(isOpen = true, viewportWorldRect = viewportWorldRect)
        }

        fun traverse(connectionName: String, find: (HTMLElement) -> List<String>?) {
            val proxyCache = mutableMapOf<HTMLElement, HTMLElement?>()
            fun proxyFor(box: HTMLElement): HTMLElement? = proxyCache.getOrPut(box) {
                val nestedInUid = box.unsafeCast<dynamic>().box_nestedIn as? String ?: return@getOrPut null
                val nestedIn = cachedBoxByUid[nestedInUid] ?: return@getOrPut null
                val snapshot = nestedDisclosureSnapshot(nestedInUid)
                if (!snapshot.isOpen) return@getOrPut nestedIn
                val childRect = cachedWorldRects[box] ?: return@getOrPut nestedIn
                val viewportRect = snapshot.viewportWorldRect ?: return@getOrPut nestedIn
                if (!rectsIntersect(childRect, viewportRect)) nestedIn else null
            }

            cachedBoxes.forEach { from ->
                val c = find(from)
                val fromProxyTarget = proxyFor(from)
                val renderedFrom = fromProxyTarget ?: from
                val worldRect = cachedWorldRects[renderedFrom] ?: return@forEach

                if (renderedFrom.style.visibility === "hidden" || !hasRenderableFrame(renderedFrom)) return@forEach

                var o0 = 1.0
                if (from.asDynamic().offline ) o0 = 0.25

                val r1 = worldRect.copyRect()

                addOffscreenHintForViewport(worldRect, mainViewportRect)

                c?.let {
                    it.forEach { id ->
                        cachedBoxByUid[id]?.let { to ->
                            var effectiveFromProxy = fromProxyTarget
                            var effectiveToProxy = proxyFor(to)
                            var effectiveRenderedFrom = effectiveFromProxy ?: from
                            var effectiveRenderedTo = effectiveToProxy ?: to
                            if (effectiveRenderedTo.style.visibility === "hidden" || !hasRenderableFrame(effectiveRenderedTo)) return@forEach
                            if (effectiveRenderedFrom == effectiveRenderedTo) return@forEach
                            var effectiveFromRect = cachedWorldRects[effectiveRenderedFrom]?.copyRect() ?: return@forEach
                            var effectiveToRect = cachedWorldRects[effectiveRenderedTo]?.copyRect() ?: return@forEach

                            // Per-connection endpoint visibility: if the box is partially visible
                            // inside the nest viewport but the connection endpoint falls outside
                            // the viewport, override to proxy so the connection doesn't float.
                            fun endpointVisibilityOverride() {
                                val fromNestedUid = from.unsafeCast<dynamic>().box_nestedIn as? String
                                val toNestedUid = to.unsafeCast<dynamic>().box_nestedIn as? String
                                if (fromNestedUid == null && toNestedUid == null) return

                                val endpoints = connectCenters(effectiveFromRect, effectiveToRect)

                                // Check "from" endpoint: if from is nested, not proxied, and endpoint is outside viewport
                                if (fromNestedUid != null && effectiveFromProxy == null) {
                                    val viewportRect = nestedDisclosureSnapshot(fromNestedUid).viewportWorldRect
                                    if (viewportRect != null && !pointInRect(endpoints.first.point, viewportRect)) {
                                        val parentEl = cachedBoxByUid[fromNestedUid]
                                        if (parentEl != null) {
                                            effectiveFromProxy = parentEl
                                            effectiveRenderedFrom = parentEl
                                            effectiveFromRect = cachedWorldRects[parentEl]?.copyRect() ?: return
                                        }
                                    }
                                }

                                // Check "to" endpoint: if to is nested, not proxied, and endpoint is outside viewport
                                if (toNestedUid != null && effectiveToProxy == null) {
                                    // Recompute endpoints since fromRect may have changed
                                    val endpoints2 = connectCenters(effectiveFromRect, effectiveToRect)
                                    val viewportRect = nestedDisclosureSnapshot(toNestedUid).viewportWorldRect
                                    if (viewportRect != null && !pointInRect(endpoints2.second.point, viewportRect)) {
                                        val parentEl = cachedBoxByUid[toNestedUid]
                                        if (parentEl != null) {
                                            effectiveToProxy = parentEl
                                            effectiveRenderedTo = parentEl
                                            effectiveToRect = cachedWorldRects[parentEl]?.copyRect() ?: return
                                        }
                                    }
                                }

                                // If from was overridden, re-verify its endpoint against the new geometry
                                if (fromNestedUid != null && effectiveFromProxy != null && effectiveFromProxy != fromProxyTarget) {
                                    // Already proxied to parent — nothing more to do
                                } else if (fromNestedUid != null && effectiveFromProxy == null) {
                                    // Recheck: overriding "to" may have shifted from's endpoint
                                    val endpoints3 = connectCenters(effectiveFromRect, effectiveToRect)
                                    val viewportRect = nestedDisclosureSnapshot(fromNestedUid).viewportWorldRect
                                    if (viewportRect != null && !pointInRect(endpoints3.first.point, viewportRect)) {
                                        val parentEl = cachedBoxByUid[fromNestedUid]
                                        if (parentEl != null) {
                                            effectiveFromProxy = parentEl
                                            effectiveRenderedFrom = parentEl
                                            effectiveFromRect = cachedWorldRects[parentEl]?.copyRect() ?: return
                                        }
                                    }
                                }
                            }
                            endpointVisibilityOverride()

                            // Re-check identity after potential proxy overrides
                            if (effectiveRenderedFrom == effectiveRenderedTo) return@forEach

	                            var o1 = 1.0
	                            if (to.asDynamic().offline ) o1 = 0.25

	                            when {
                                effectiveFromProxy != null && effectiveToProxy != null -> {
                                    (knownProxyConnectionsBoth[connectionName] ?: knownConnections[connectionName])?.invoke(
                                        from,
                                        effectiveFromRect,
                                        to,
                                        effectiveToRect,
                                        svg,
                                        o0 * o1
                                    )
                                }

                                effectiveFromProxy != null -> {
                                    (knownProxyConnectionsFrom[connectionName] ?: knownConnections[connectionName])?.invoke(
                                        from,
                                        effectiveFromRect,
                                        to,
                                        effectiveToRect,
                                        svg,
                                        o0 * o1
                                    )
                                }

                                effectiveToProxy != null -> {
                                    (knownProxyConnectionsTo[connectionName] ?: knownConnections[connectionName])?.invoke(
                                        from,
                                        effectiveFromRect,
                                        to,
                                        effectiveToRect,
                                        svg,
                                        o0 * o1
                                    )
                                }

                                else -> knownConnections[connectionName]?.invoke(
                                    from,
                                    effectiveFromRect,
                                    to,
                                    effectiveToRect,
                                    svg,
                                    o0 * o1
                                )
                            }
                        }
                    }
                }
            }
        }

        traverse("parent->child") { from ->
            (from.unsafeCast<dynamic>().box_children as Array<String>?)?.toList()
        }

        cachedBoxes.forEach { box ->
            if (box.style.visibility === "hidden" || !hasRenderableFrame(box)) return@forEach
            val parentUid = box.unsafeCast<dynamic>().box_nestedIn as? String ?: return@forEach
            val viewportRect = nestedDisclosureSnapshot(parentUid).viewportWorldRect ?: return@forEach
            val boxRect = cachedWorldRects[box] ?: return@forEach
            if (rectsIntersect(boxRect, viewportRect)) return@forEach
            addOffscreenHintForViewport(boxRect, viewportRect)
        }

        val obsolete = connectionFilterStates.keys - currentFrameKeys
        obsolete.forEach { connectionFilterStates.remove(it) }
	        currentConnectionSelectedUids = emptySet()
	        currentFrontMarkerLayer = null
	        currentFrontMarkerDefs = null
	        currentConnectionViewportRect = null

        if (needsFilterFrame && !filterFrameScheduled) {
            filterFrameScheduled = true
            window.requestAnimationFrame {
                filterFrameScheduled = false
                requestConnectionGroupRebuild(translationOnly = translationOnly)
            }
        }
    }


    fun drawDot(x: Double, y: Double, r: Double) {
        overlayContext.beginPath()
        overlayContext.arc(x, y, r, 0.0, 2 * PI)
        overlayContext.fill()
    }

    private fun clearOverlayCanvas() {
        // Clear in raw canvas pixel space so browser zoom / DPR transforms cannot leave stale regions.
        overlayContext.save()
        overlayContext.setTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        overlayContext.clearRect(0.0, 0.0, overlayCanvas.width.toDouble(), overlayCanvas.height.toDouble())
        overlayContext.restore()
    }

    fun intersects(x: Double, y: Double): dynamic {
        val element = document.elementFromPoint(x, y).unsafeCast<dynamic>()
        println("intersects $x $y = $element")
        return element
    }

    var isDown = false
    var isExecutionLine = false
    var isMarquee = false
    var start: Pair<Double, Double>? = null
    var rects: List<Pair<HTMLElement, DOMRect>>? = null
    private var executionBreadthFirst = false
    private var lastExecutionPointer: Pair<Double, Double>? = null

    private data class ExecutionHit(
        val element: HTMLElement,
        val rect: DOMRect,
        val t: Double,
        val point: Point
    )

    private data class ExecutionLabel(
        val number: Int,
        val point: Point
    )


    fun down(e: org.w3c.dom.events.MouseEvent) {
        isDown = true
        if (e.altKey) {

            var boxList = document.querySelectorAll(".box")
            rects = (0 until boxList.length).map { boxList.item(it) as HTMLElement }
                .map { it to it.getBoundingClientRect() }

            isExecutionLine = true
            start = e.x to e.y
            lastExecutionPointer = e.x to e.y
            executionBreadthFirst = e.shiftKey
        } else {

            var boxList = document.querySelectorAll(".box")
            rects = (0 until boxList.length).map { boxList.item(it) as HTMLElement }
                .map { it to it.getBoundingClientRect() }

            isMarquee = true
            start = e.x to e.y
        }
    }

    fun up(e: org.w3c.dom.events.MouseEvent) {
        executionBreadthFirst = e.shiftKey
        isDown = false
        if (isExecutionLine) {
            finalizeExecutionLine(start!!, e.x, e.y)
        } else if (isMarquee) {
            finalizeSelectionLine(start!!, e)
        }
        isExecutionLine = false
        isMarquee = false
        lastExecutionPointer = null
        rebuildConnectionGroup()
    }

    private fun handleExecutionModifierKey(e: KeyboardEvent) {
        if (!isExecutionLine) return
        if (e.key != "Shift") return
        executionBreadthFirst = e.type == "keydown"
        val s = start ?: return
        val pointer = lastExecutionPointer ?: return
        updateExecutionLine(s, pointer.first, pointer.second)
    }

    fun updateExecutionLine(start: Pair<Double, Double>, e: MouseEvent) {
        executionBreadthFirst = e.shiftKey
        lastExecutionPointer = e.x to e.y
        updateExecutionLine(start, e.x, e.y)
    }

    private fun updateExecutionLine(start: Pair<Double, Double>, x: Double, y: Double) {
        overlayContext.fillStyle = "rgba(0,0,0,0)"
        clearOverlayCanvas()
        overlayContext.strokeStyle = "rgba(128,255,164,0.5)"
        overlayContext.lineWidth = 1.75
        overlayContext.beginPath()
        overlayContext.moveTo(x, y)
        overlayContext.lineTo(start.first, start.second)
        overlayContext.stroke()
        overlayContext.fillStyle = "rgba(128,255,164,0.5)"
        drawDot(x, y, 2.0)
        drawDot(start.first, start.second, 2.0)
        val hits = executionHits(start, x, y)
        hits.forEach { (_, rect) ->
            overlayContext.strokeRect(rect.x, rect.y, rect.width, rect.height)
        }
        drawExecutionOrderLabels(labelsForHits(hits, executionBreadthFirst, start, x, y))
    }

    fun updateMarquee(start: Pair<Double, Double>, e: MouseEvent) {
        overlayContext.fillStyle = "rgba(0,0,0,0)"
        clearOverlayCanvas()
        overlayContext.strokeStyle = "rgba(128,255,164,0.5)"
        overlayContext.lineWidth = 1.75
        overlayContext.beginPath()
        overlayContext.moveTo(e.x, e.y)
        overlayContext.lineTo(start.first, e.y)
        overlayContext.lineTo(start.first, start.second)
        overlayContext.lineTo(e.x, start.second)
        overlayContext.lineTo(e.x, e.y)
        overlayContext.stroke()
        overlayContext.fillStyle = "rgba(128,255,164,0.5)"
        drawDot(e.x, e.y, 2.0)
        drawDot(start.first, start.second, 2.0)
        rects!!.filter { (element, rect) ->
            rect.intersectsRect(e.x, e.y, start.first, start.second)
        }.forEach { (element, rect) ->
            overlayContext.strokeRect(rect.x, rect.y, rect.width, rect.height)
        }
    }

    fun finalizeExecutionLine(start: Pair<Double, Double>, x: Double, y: Double) {
        overlayContext.fillStyle = "rgba(0,0,0,0)"
        clearOverlayCanvas()

        launcher(expandedExecutionOrder(executionHits(start, x, y), executionBreadthFirst))
    }

    fun finalizeSelectionLine(start: Pair<Double, Double>, e: MouseEvent) {
        overlayContext.fillStyle = "rgba(0,0,0,0)"
        clearOverlayCanvas()

        if (!e.shiftKey) selected.copy().forEach {
            deselectBox(it)
        }

        rects!!.filter { (element, rect) ->
            rect.intersectsRect(e.x, e.y, start.first, start.second)
        }.let {
            it.forEach { selectBox(it.first) }
        }
    }


    fun move(e: org.w3c.dom.events.MouseEvent) {
        if (isDown) {
            intersects(e.x, e.y)
        }

        if (isExecutionLine) {
            updateExecutionLine(start!!, e)
        } else if (isMarquee) {
            updateMarquee(start!!, e)
        }
    }

    fun doLineSegmentsIntersect(
        x0: Double, y0: Double, x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double
    ): Boolean {
        val s1x = x1 - x0
        val s1y = y1 - y0
        val s2x = x3 - x2
        val s2y = y3 - y2
        val denom = (-s2x * s1y + s1x * s2y)
        if (abs(denom) < 1e-10) return false
        val s = (-s1y * (x0 - x2) + s1x * (y0 - y2)) / denom
        val t = (s2x * (y0 - y2) - s2y * (x0 - x2)) / denom
        return (s in 0.0..1.0) && (t in 0.0..1.0)
    }

    private fun lineRectIntersectionPoint(
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        rect: DOMRect
    ): Pair<Point, Double>? {
        val dx = endX - startX
        val dy = endY - startY
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 1e-9) return null

        fun inside(x: Double, y: Double): Boolean {
            return x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom
        }

        if (inside(startX, startY)) {
            return Point(startX, startY) to 0.0
        }

        val candidates = mutableListOf<Pair<Point, Double>>()

        fun addCandidate(t: Double, x: Double, y: Double) {
            if (t !in 0.0..1.0) return
            if (!inside(x, y)) return
            candidates.add(Point(x, y) to t)
        }

        if (abs(dx) > 1e-9) {
            val leftT = (rect.left - startX) / dx
            addCandidate(leftT, rect.left, startY + leftT * dy)
            val rightT = (rect.right - startX) / dx
            addCandidate(rightT, rect.right, startY + rightT * dy)
        }
        if (abs(dy) > 1e-9) {
            val topT = (rect.top - startY) / dy
            addCandidate(topT, startX + topT * dx, rect.top)
            val bottomT = (rect.bottom - startY) / dy
            addCandidate(bottomT, startX + bottomT * dx, rect.bottom)
        }

        return candidates.minByOrNull { it.second }
    }

    private fun executionHits(start: Pair<Double, Double>, pointerX: Double, pointerY: Double): List<ExecutionHit> {
        val sx = start.first
        val sy = start.second
        val ex = pointerX
        val ey = pointerY
        val dx = ex - sx
        val dy = ey - sy
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 1e-9) return emptyList()

        return rects.orEmpty().mapNotNull { (element, rect) ->
            if (!lineIntersectsDOMRect(ex, ey, sx, sy, rect)) return@mapNotNull null
            val hit = lineRectIntersectionPoint(sx, sy, ex, ey, rect)
            val t = hit?.second ?: run {
                val centerX = rect.x + rect.width / 2.0
                val centerY = rect.y + rect.height / 2.0
                ((centerX - sx) * dx + (centerY - sy) * dy) / lengthSquared
            }
            val point = hit?.first ?: Point(
                (rect.x + rect.width / 2.0).coerceIn(rect.left, rect.right),
                (rect.y + rect.height / 2.0).coerceIn(rect.top, rect.bottom)
            )
            ExecutionHit(element, rect, t, point)
        }.sortedBy { it.t }
    }

    private fun expandedExecutionOrder(hits: List<ExecutionHit>, breadthFirst: Boolean): List<HTMLElement> {
        val seen = mutableSetOf<String>()
        val ordered = mutableListOf<HTMLElement>()

        fun keyFor(box: HTMLElement): String {
            val uid = box.unsafeCast<dynamic>().uid
            if (uid != undefined && uid != null) return uid.toString()
            return "element:${box.hashCode()}"
        }

        hits.forEach { hit ->
            if (keyFor(hit.element) in seen) return@forEach
            executionExpansionProvider(hit.element, breadthFirst).forEach { box ->
                val key = keyFor(box)
                if (seen.add(key)) ordered.add(box)
            }
        }
        return ordered
    }

    private fun rectForBox(box: HTMLElement): DOMRect? {
        return rects.orEmpty().firstOrNull { it.first == box }?.second
    }

    private fun closestPointOnSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Point {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 1e-9) return Point(ax, ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return Point(ax + t * dx, ay + t * dy)
    }

    private fun closestPointOnRectToSegment(rect: DOMRect, sx: Double, sy: Double, ex: Double, ey: Double): Point {
        lineRectIntersectionPoint(sx, sy, ex, ey, rect)?.let { return it.first }

        fun dist2(a: Point, bx: Double, by: Double): Double {
            val dx = a.x - bx
            val dy = a.y - by
            return dx * dx + dy * dy
        }

        val edges = listOf(
            doubleArrayOf(rect.left, rect.top, rect.right, rect.top),
            doubleArrayOf(rect.right, rect.top, rect.right, rect.bottom),
            doubleArrayOf(rect.right, rect.bottom, rect.left, rect.bottom),
            doubleArrayOf(rect.left, rect.bottom, rect.left, rect.top)
        )

        val candidates = mutableListOf<Pair<Point, Double>>()
        edges.forEach { edge ->
            val ax = edge[0]
            val ay = edge[1]
            val bx = edge[2]
            val by = edge[3]

            val nearStart = closestPointOnSegment(sx, sy, ax, ay, bx, by)
            candidates.add(nearStart to dist2(nearStart, sx, sy))

            val nearEnd = closestPointOnSegment(ex, ey, ax, ay, bx, by)
            candidates.add(nearEnd to dist2(nearEnd, ex, ey))

            val edgeStartToChord = closestPointOnSegment(ax, ay, sx, sy, ex, ey)
            candidates.add(Point(ax, ay) to dist2(edgeStartToChord, ax, ay))

            val edgeEndToChord = closestPointOnSegment(bx, by, sx, sy, ex, ey)
            candidates.add(Point(bx, by) to dist2(edgeEndToChord, bx, by))
        }

        return candidates.minByOrNull { it.second }?.first ?: Point(rect.x + rect.width / 2.0, rect.y + rect.height / 2.0)
    }

    private fun labelsForHits(
        hits: List<ExecutionHit>,
        breadthFirst: Boolean,
        start: Pair<Double, Double>,
        pointerX: Double,
        pointerY: Double
    ): List<ExecutionLabel> {
        val seen = mutableSetOf<String>()
        val labels = mutableListOf<ExecutionLabel>()
        var nextNumber = 1

        fun keyFor(box: HTMLElement): String {
            val uid = box.unsafeCast<dynamic>().uid
            if (uid != undefined && uid != null) return uid.toString()
            return "element:${box.hashCode()}"
        }

        hits.forEach { hit ->
            if (keyFor(hit.element) in seen) return@forEach
            executionExpansionProvider(hit.element, breadthFirst).forEach { box ->
                val key = keyFor(box)
                if (seen.add(key)) {
                    val labelPoint = rectForBox(box)?.let { rect ->
                        closestPointOnRectToSegment(rect, start.first, start.second, pointerX, pointerY)
                    } ?: hit.point
                    labels.add(ExecutionLabel(nextNumber++, labelPoint))
                }
            }
        }
        return labels
    }

    private fun drawExecutionOrderLabels(labels: List<ExecutionLabel>) {
        if (labels.isEmpty()) return

        overlayContext.save()
        overlayContext.font = "12px \"Iosevka Web\", \"Iosevka\", monospace"
        overlayContext.asDynamic().textAlign = "center"
        overlayContext.asDynamic().textBaseline = "middle"

        labels.forEach { label ->
            val text = label.number.toString()
            val radius = max(8.0, 5.0 + text.length * 3.2)
            val x = label.point.x
            val y = label.point.y

            overlayContext.beginPath()
            overlayContext.arc(x, y, radius, 0.0, 2 * PI)
            overlayContext.fillStyle = "rgba(128,255,164,0.94)"
            overlayContext.fill()
            overlayContext.lineWidth = 1.25
            overlayContext.strokeStyle = "rgba(0,0,0,0.82)"
            overlayContext.stroke()
            overlayContext.fillStyle = "rgba(0,0,0,0.92)"
            overlayContext.fillText(text, x, y + 0.5)
        }

        overlayContext.restore()
    }

    fun lineIntersectsDOMRect(x0: Double, y0: Double, x1: Double, y1: Double, rect: DOMRect): Boolean {
        if (x0 >= rect.x && x0 <= rect.x + rect.width && y0 >= rect.y && y0 <= rect.y + rect.height) return true
        if (x1 >= rect.x && x1 <= rect.x + rect.width && y1 >= rect.y && y1 <= rect.y + rect.height) return true

        val left = rect.x
        val right = rect.x + rect.width
        val top = rect.y
        val bottom = rect.y + rect.height

        if (doLineSegmentsIntersect(x0, y0, x1, y1, left, top, right, top)) return true
        if (doLineSegmentsIntersect(x0, y0, x1, y1, left, bottom, right, bottom)) return true
        if (doLineSegmentsIntersect(x0, y0, x1, y1, left, top, left, bottom)) return true
        if (doLineSegmentsIntersect(x0, y0, x1, y1, right, top, right, bottom)) return true

        return false
    }

    fun DOMRect.intersectsRect(x0: Double, y0: Double, x1: Double, y1: Double): Boolean {
        return !(x + width < min(x0, x1) || x > max(x0, x1) || y + height < min(y0, y1) || y > max(y0, y1))
    }
}


// Define the constant threshold for corner adjustment.
const val EDGE_CHEAT = 20.0


// A data class to hold the clipped point and the normal of the edge.
data class ClippedResult(val point: Point, val normal: Point)

/**
 * Returns the center point of a DOMRect.
 */
fun getCenter(rect: DOMRect): Point = Point((rect.left + rect.right) / 2.0, (rect.top + rect.bottom) / 2.0)

/**
 * Given a rectangle and a ray direction (dx, dy) from its center,
 * returns a ClippedResult containing:
 *  - the intersection point on the rectangle's boundary (possibly adjusted so that
 *    it is at least EDGE_CHEAT pixels away from the corner along that edge), and
 *  - the normal to the edge that was hit.
 *
 * The rectangle is assumed to be axis-aligned.
 */
fun clipRay(rect: DOMRect, dx: Double, dy: Double): ClippedResult {
    val center = getCenter(rect)

    // Compute the scale factors (t) needed to reach each vertical or horizontal edge.
    val tX = when {
        dx > 0 -> (rect.right - center.x) / dx
        dx < 0 -> (rect.left - center.x) / dx
        else -> Double.POSITIVE_INFINITY
    }
    val tY = when {
        dy > 0 -> (rect.bottom - center.y) / dy
        dy < 0 -> (rect.top - center.y) / dy
        else -> Double.POSITIVE_INFINITY
    }

    val eps = 1e-9
    val t: Double
    val normal: Point

    if (tX < tY - eps) {
        // Hits a vertical edge.
        t = tX
        normal = if (dx > 0) Point(1.0, 0.0) else Point(-1.0, 0.0)
    } else if (tY < tX - eps) {
        // Hits a horizontal edge.
        t = tY
        normal = if (dy > 0) Point(0.0, 1.0) else Point(0.0, -1.0)
    } else {
        // Corner hit (tX and tY nearly equal).
        t = tX  // (or tY—they're effectively equal)
        val nX = when {
            dx > 0 -> 1.0
            dx < 0 -> -1.0
            else -> 0.0
        }
        val nY = when {
            dy > 0 -> 1.0
            dy < 0 -> -1.0
            else -> 0.0
        }
        val len = sqrt(nX * nX + nY * nY)
        normal = if (len > 0) Point(nX / len, nY / len) else Point(0.0, 0.0)
    }

    // Compute the raw intersection point.
    val intersection = Point(center.x + t * dx, center.y + t * dy)

    // --- Corner Adjustment ---
    // If the intersection is within EDGE_CHEAT pixels of a corner along the edge,
    // slide it along the edge so that it's at least EDGE_CHEAT pixels from the corner.
    var adjusted = intersection

    // When the normal is strictly horizontal, the edge is vertical.
    if (abs(normal.x) > eps && abs(normal.y) < eps) {
        // p.x is fixed (either rect.left or rect.right).
        if (adjusted.y - rect.top < EDGE_CHEAT) {
            adjusted = Point(adjusted.x, rect.top + EDGE_CHEAT)
        } else if (rect.bottom - adjusted.y < EDGE_CHEAT) {
            adjusted = Point(adjusted.x, rect.bottom - EDGE_CHEAT)
        }
    }
    // When the normal is strictly vertical, the edge is horizontal.
    else if (abs(normal.y) > eps && abs(normal.x) < eps) {
        // p.y is fixed (either rect.top or rect.bottom).
        if (adjusted.x - rect.left < EDGE_CHEAT) {
            adjusted = Point(rect.left + EDGE_CHEAT, adjusted.y)
        } else if (rect.right - adjusted.x < EDGE_CHEAT) {
            adjusted = Point(rect.right - EDGE_CHEAT, adjusted.y)
        }
    }
    // For a corner hit (or blended normal), decide which edge to treat as active.
    else {
        if (abs(dx) >= abs(dy)) {
            // Treat as if on a vertical edge.
            if (adjusted.y - rect.top < EDGE_CHEAT) {
                adjusted = Point(adjusted.x, rect.top + EDGE_CHEAT)
            } else if (rect.bottom - adjusted.y < EDGE_CHEAT) {
                adjusted = Point(adjusted.x, rect.bottom - EDGE_CHEAT)
            }
        } else {
            // Treat as if on a horizontal edge.
            if (adjusted.x - rect.left < EDGE_CHEAT) {
                adjusted = Point(rect.left + EDGE_CHEAT, adjusted.y)
            } else if (rect.right - adjusted.x < EDGE_CHEAT) {
                adjusted = Point(rect.right - EDGE_CHEAT, adjusted.y)
            }
        }
    }
    // --- End Corner Adjustment ---

    return ClippedResult(adjusted, normal)
}

/**
 * Returns a pair of ClippedResults that represent the endpoints of the line segment connecting
 * the two rectangles. The segment is drawn between the centers of the rectangles, but is "clipped"
 * so that it starts and ends at the rectangle boundaries.
 *
 * For rect1, the ray is cast from its center toward rect2's center.
 * For rect2, the ray is cast from its center toward rect1's center.
 */
fun connectCenters(rect1: DOMRect, rect2: DOMRect): Pair<ClippedResult, ClippedResult> {
    var center1 = getCenter(rect1)
    var center2 = getCenter(rect2)

    // Direction vector from rect1's center to rect2's center.
    val dx = center2.x - center1.x
    val dy = center2.y - center1.y

    // For rect1, clip along (dx, dy).
    val clipped1 = clipRay(rect1, dx, dy)
    // For rect2, clip along (-dx, -dy).
    val clipped2 = clipRay(rect2, -dx, -dy)

    return clipped1 to clipped2
}


fun addArrowHeadToCubicSpline(
    svg: SVGElement, x0: Double, y0: Double, cx0: Double, cy0: Double, cx1: Double, cy1: Double, x1: Double, y1: Double, opacity: Double = 1.0, color: String? = null
) {
    // Parameter at which to place the arrow head.
//    val t = 40/(sqrt((x0-x1)*(x0-x1)+(y0-y1)*(y0-y1)) + sqrt((cx0-cx1)*(cx0-cx1)+(cy0-cy1)*(cy0-cy1)))
    val t = 0.5
    // Get the point on the curve.
    val (px, py) = cubicBezierPoint(t, x0, y0, cx0, cy0, cx1, cy1, x1, y1)

    // Get the tangent vector at t.
    val (dx, dy) = cubicBezierTangent(t, x0, y0, cx0, cy0, cx1, cy1, x1, y1)
    val len = sqrt(dx * dx + dy * dy)
    val ux = dx / len
    val uy = dy / len

    // Arrow head parameters.
    val ARROW_HEAD_LENGTH = 12.0   // length of each arrow head line in pixels
    val ARROW_HEAD_ANGLE = PI / 6  // 30° in radians

    // Compute the angle of the tangent.
    val tangentAngle = atan2(uy, ux)
    // The arrow head lines will point backward, so subtract PI.
    // Then offset by ±ARROW_HEAD_ANGLE.
    val angle1 = tangentAngle + PI - ARROW_HEAD_ANGLE
    val angle2 = tangentAngle + PI + ARROW_HEAD_ANGLE

    // Compute the endpoints of the arrow head lines.
    val ax1 = px + ARROW_HEAD_LENGTH * cos(angle1)
    val ay1 = py + ARROW_HEAD_LENGTH * sin(angle1)
    val ax2 = px + ARROW_HEAD_LENGTH * cos(angle2)
    val ay2 = py + ARROW_HEAD_LENGTH * sin(angle2)

    path {
//        d = "M $ax1,$ay1 L$px,$py L$ax2,$ay2 Z"
        moveTo(ax1, ay1)
        lineTo(px, py)
        lineTo(ax2, ay2)
        close()
        stroke = color ?: SELECTED_CONNECTION_COLOR
        strokeWidth = 2.0+(1-opacity)*2.0
        fill = color ?: "rgba(255,255,255,$opacity)"
        this.path.setAttribute("opacity", opacity.toString())
        appendTo(svg)
    }
}

fun addArrowHead(svg: SVGElement, tip: Point, heading: Double, opacity: Double = 1.0, color: String? = null) {
    val arrowLength = 12.0
    val arrowAngle = PI / 6

    val angle1 = heading + PI - arrowAngle
    val angle2 = heading + PI + arrowAngle

    val ax1 = tip.x + arrowLength * cos(angle1)
    val ay1 = tip.y + arrowLength * sin(angle1)
    val ax2 = tip.x + arrowLength * cos(angle2)
    val ay2 = tip.y + arrowLength * sin(angle2)

    path {
        moveTo(ax1, ay1)
        lineTo(tip.x, tip.y)
        lineTo(ax2, ay2)
        close()
        stroke = color ?: SELECTED_CONNECTION_COLOR
        strokeWidth = 2.0 + (1 - opacity) * 2.0
        fill = color ?: "rgba(255,255,255,$opacity)"
        this.path.setAttribute("opacity", opacity.toString())
        appendTo(svg)
    }
}
