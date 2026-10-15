package afield.app.canvas

import afield.app.client.allActions
import afield.app.client.clear
import afield.app.client.main
import afield.app.client.mainZoom
import afield.app.client.updateActions
import afield.app.client.worldViewBox
import afield.app.protocol.DependencyGraph
import afield.app.protocol.DependencyGraphRequest
import afield.app.protocol.Handled
import afield.app.protocol.Message
import afield.app.protocol.payload
import afield.app.protocol.sendWithReply
import connectCenters
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.w3c.dom.DOMRect
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import org.w3c.dom.svg.SVGElement
import org.w3c.dom.svg.SVGSVGElement
import kotlin.math.sqrt

/**
 * The dependency layer: an optional overlay drawing which boxes read values other boxes supply.
 *
 * The server records every inherited read (`prop`, `rootProp`, `@exported`, `fromContext`) as box
 * code runs and keeps the record beside `metadata.edn` (`afield.document.properties.PropertyDependencies`). With the
 * layer on (palette: **Toggle Dependency Layer**), this page polls `dependency-graph` and draws one
 * arrow per producer → consumer pair, labelled with the names that flowed:
 *
 * - bright, while the read happened in the last couple of seconds;
 * - solid, for a read seen since the server started;
 * - faint and dashed, for one only remembered from an earlier session;
 * - a red `? name` tag on a box whose read found nothing, with a dashed red arrow from each box that
 *   supplied that name to it before — the box an upstream run would start.
 *
 * Drawn in world coordinates on its own SVG, redrawn with the connection layer
 * ([redrawDependencyLayer] is called from `rebuildConnectionGroup`), so it follows pan, zoom and
 * box drags. **Clear Dependency Record** forgets the document's record.
 */

private const val LAYER_ID = "dependency-layer"
private const val TOGGLE_COMMAND = "toggle-dependency-layer"
private const val CLEAR_COMMAND = "clear-dependency-record"
private const val STORAGE_KEY = "afield.dependencyLayer"
private const val POLL_MS = 750
private const val HOT_MS = 2_000L
private const val SVG_NS = "http://www.w3.org/2000/svg"

private var dependencyLayerActive = false
private var pollTimer: Int? = null
private var lastGraph: DependencyGraph? = null
private var requestInFlight = false

@OptIn(ExperimentalJsExport::class)
@JsExport
fun toggleDependencyLayer() = setDependencyLayerActive(!dependencyLayerActive)

fun setDependencyLayerActive(on: Boolean) {
    dependencyLayerActive = on
    runCatching { localStorage.setItem(STORAGE_KEY, if (on) "1" else "0") }
    pollTimer?.let { window.clearInterval(it) }
    pollTimer = null
    if (on) {
        requestDependencyGraph()
        pollTimer = window.setInterval({ requestDependencyGraph() }, POLL_MS)
    } else {
        lastGraph = null
        document.getElementById(LAYER_ID)?.remove()
    }
}

private fun requestDependencyGraph(action: String = "get") {
    if (requestInFlight && action == "get") return
    requestInFlight = true
    sendWithReply(
        Message("dependency-graph").payload(DependencyGraphRequest(action)),
        "dependency-graph-reply"
    ) { message, _ ->
        requestInFlight = false
        val graph = runCatching { Json.decodeFromJsonElement<DependencyGraph>(message.payload!!) }.getOrNull()
        if (graph != null && graph.ok && dependencyLayerActive) {
            lastGraph = graph
            redrawDependencyLayer()
        }
        Handled.yes
    }
}

private fun ensureLayer(): SVGSVGElement {
    (document.getElementById(LAYER_ID) as? SVGSVGElement)?.let { return it }
    val layer = document.createElementNS(SVG_NS, "svg") as SVGSVGElement
    layer.id = LAYER_ID
    layer.classList.add("dependency-layer")
    layer.setAttribute("height", "100%")
    layer.setAttribute("width", "100%")
    document.body?.appendChild(layer)
    return layer
}

private fun boxRectsByUid(): Map<String, DOMRect> {
    val out = HashMap<String, DOMRect>()
    val boxes = document.body?.getElementsByClassName("box")?.asList() ?: return out
    for (el in boxes) {
        val uid = (el.asDynamic().uid as? String) ?: el.getAttribute("uid") ?: el.getAttribute("data-uid") ?: continue
        if ((el as? HTMLElement)?.style?.display == "none") continue
        val r = el.getBoundingClientRect()
        if (r.width <= 0.0 && r.height <= 0.0) continue
        // Through the main pane's camera, as the actor-arc layer does, so a box in any pane is reached.
        out[uid] = CanvasCamera.main.worldRect(CanvasCamera.visibleRect(el, r) ?: r)
    }
    return out
}

/** Redraws from the last graph the server sent. Cheap no-op while the layer is off. */
fun redrawDependencyLayer() {
    if (!dependencyLayerActive) return
    val graph = lastGraph ?: return
    val layer = ensureLayer()
    layer.setAttribute("viewBox", CanvasCamera.main.worldViewBox())
    layer.innerHTML = ""
    val rects = boxRectsByUid()
    val px = 1.0 / mainZoom

    val defs = svg("defs")
    for ((id, color) in listOf("dep-arrow-hot" to HOT, "dep-arrow" to LIVE, "dep-arrow-old" to OLD, "dep-arrow-miss" to MISS)) {
        val marker = svg("marker")
        marker.setAttribute("id", id)
        marker.setAttribute("viewBox", "0 0 10 10")
        marker.setAttribute("refX", "9")
        marker.setAttribute("refY", "5")
        marker.setAttribute("markerWidth", "7")
        marker.setAttribute("markerHeight", "7")
        marker.setAttribute("orient", "auto-start-reverse")
        val path = svg("path")
        path.setAttribute("d", "M 0 1 L 10 5 L 0 9 z")
        path.setAttribute("fill", color)
        marker.appendChild(path)
        defs.appendChild(marker)
    }
    layer.appendChild(defs)

    // Pairs drawn in both directions bow apart rather than overlapping.
    val pairs = graph.flows.map { it.from to it.to }.toSet()
    for (flow in graph.flows) {
        val a = rects[flow.from] ?: continue
        val b = rects[flow.to] ?: continue
        val age = graph.now - flow.lastSeen
        val (color, marker) = when {
            flow.sessionCount > 0 && age < HOT_MS -> HOT to "dep-arrow-hot"
            flow.sessionCount > 0 -> LIVE to "dep-arrow"
            else -> OLD to "dep-arrow-old"
        }
        val bow = if ((flow.to to flow.from) in pairs) 0.18 else 0.08
        val mid = drawCurve(layer, a, b, color, marker, width = if (color == HOT) 2.2 * px else 1.4 * px,
            dashed = flow.sessionCount == 0L, bow = bow, px = px)
        if (mid != null) {
            val names = if (flow.names.size > 3) flow.names.take(3).joinToString(", ") + " +${flow.names.size - 3}" else flow.names.joinToString(", ")
            label(layer, mid.x, mid.y, names, color, px)
        }
    }

    // Unanswered reads: a tag on the box, and a dashed arrow from whoever answered before.
    val missesByBox = graph.misses.groupBy { it.box }
    for ((uid, misses) in missesByBox) {
        val r = rects[uid] ?: continue
        misses.forEachIndexed { i, miss ->
            val text = "? " + miss.name
            val t = svg("text")
            t.setAttribute("x", (r.right + 4 * px).toString())
            t.setAttribute("y", (r.top + (12 + i * 13) * px).toString())
            t.setAttribute("font-size", (11 * px).toString())
            t.setAttribute("font-family", "Iosevka, monospace")
            t.setAttribute("fill", MISS)
            t.textContent = text
            layer.appendChild(t)
            for (supplier in miss.rememberedSuppliers) {
                val s = rects[supplier] ?: continue
                drawCurve(layer, s, r, MISS, "dep-arrow-miss", width = 1.2 * px, dashed = true, bow = 0.12, px = px)
            }
        }
    }
}

private const val HOT = "rgba(255, 214, 120, 0.95)"
private const val LIVE = "rgba(120, 190, 255, 0.75)"
private const val OLD = "rgba(160, 170, 190, 0.35)"
private const val MISS = "rgba(255, 110, 100, 0.9)"

private fun svg(tag: String) = document.createElementNS(SVG_NS, tag) as SVGElement

/** A gently bowed curve between the facing edges of [a] and [b]; returns its midpoint. */
private fun drawCurve(
    layer: SVGElement,
    a: DOMRect,
    b: DOMRect,
    color: String,
    marker: String,
    width: Double,
    dashed: Boolean,
    bow: Double,
    px: Double,
): Point? {
    val (ca, cb) = connectCenters(a, b)
    val p0 = ca.point
    val p1 = cb.point
    val dx = p1.x - p0.x
    val dy = p1.y - p0.y
    val len = sqrt(dx * dx + dy * dy)
    if (len < 4 * px) return null
    // Control point off the chord's midpoint, to its left.
    val cx = (p0.x + p1.x) / 2 - dy * bow
    val cy = (p0.y + p1.y) / 2 + dx * bow
    val path = svg("path")
    path.setAttribute("d", "M ${p0.x} ${p0.y} Q $cx $cy ${p1.x} ${p1.y}")
    path.setAttribute("stroke", color)
    path.setAttribute("stroke-width", width.toString())
    path.setAttribute("fill", "none")
    path.setAttribute("marker-end", "url(#$marker)")
    if (dashed) path.setAttribute("stroke-dasharray", "${4 * px} ${3 * px}")
    layer.appendChild(path)
    // The quadratic's midpoint.
    return Point(0.25 * p0.x + 0.5 * cx + 0.25 * p1.x, 0.25 * p0.y + 0.5 * cy + 0.25 * p1.y)
}

private fun label(layer: SVGElement, x: Double, y: Double, text: String, color: String, px: Double) {
    val t = svg("text")
    t.setAttribute("x", x.toString())
    t.setAttribute("y", (y - 3 * px).toString())
    t.setAttribute("text-anchor", "middle")
    t.setAttribute("font-size", (11 * px).toString())
    t.setAttribute("font-family", "Iosevka, monospace")
    t.setAttribute("fill", color)
    t.setAttribute("stroke", "rgba(20, 20, 20, 0.85)")
    t.setAttribute("stroke-width", (3 * px).toString())
    t.setAttribute("paint-order", "stroke")
    t.textContent = text
    layer.appendChild(t)
}

/** For tests and the backdoor: `window.dependencyLayerState()`. */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun dependencyLayerState(): dynamic {
    val g = lastGraph
    val state: dynamic = js("({})")
    state.active = dependencyLayerActive
    state.received = g != null
    state.flows = g?.flows?.size ?: 0
    state.misses = g?.misses?.size ?: 0
    state.edges = g?.edgeCount ?: 0
    state.drawnPaths = document.getElementById(LAYER_ID)?.querySelectorAll("path[marker-end]")?.length ?: 0
    return state
}

fun initializeDependencyLayer() {
    val toggle: dynamic = js("({})")
    toggle.id = TOGGLE_COMMAND
    toggle.label = "Toggle Dependency Layer"
    toggle.detail = "Draw which boxes read values from which: prop, rootProp, @exported and fromContext reads, as they run"
    toggle.run = { toggleDependencyLayer() }
    val clear: dynamic = js("({})")
    clear.id = CLEAR_COMMAND
    clear.label = "Clear Dependency Record"
    clear.detail = "Forget the box-to-box reads this document has recorded (dependencies.edn)"
    clear.run = {
        lastGraph = null
        requestInFlight = false
        requestDependencyGraph("clear")
    }

    val builtin = allActions.builtin ?: js("[]")
    builtin.push(toggle)
    builtin.push(clear)
    allActions.builtin = builtin
    updateActions()

    window.asDynamic().toggleDependencyLayer = ::toggleDependencyLayer
    window.asDynamic().dependencyLayerState = ::dependencyLayerState

    if (runCatching { localStorage.getItem(STORAGE_KEY) }.getOrNull() == "1") setDependencyLayerActive(true)
}
