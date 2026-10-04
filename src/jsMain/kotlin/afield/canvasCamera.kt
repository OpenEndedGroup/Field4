package afield

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.DOMRect
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.MutationObserver
import org.w3c.dom.MutationObserverInit
import org.w3c.dom.asList
import org.w3c.dom.events.MouseEvent
import kotlin.math.max

/**
 * The box canvas camera, held per pane so the canvas can be split spreadsheet-style
 * (`dev_docs/workspace/SPLIT_PANES_PLAN.md`): panes in one column share an X translation, panes in
 * one row share a Y translation, and every pane shares the zoom.
 *
 * The camera of a pane is `screen = world * zoom + (tx, ty)`, with the translation in window
 * (client) pixels rather than pixels from the pane's corner, so `getBoundingClientRect` arithmetic
 * is the same in every pane.
 *
 * A split is two world lines ([worldSplitX], [worldSplitY]) and two screen dividers ([splitX],
 * [splitY], fractions of the window). Each pane shows its own quadrant of the one world: the left
 * column `x < worldSplitX`, the right column `x >= worldSplitX`, and likewise for rows. A box
 * belongs to the pane whose quadrant holds its frame origin, and its DOM node lives in that pane's
 * canvas element. Each pane's translation is clamped so it never shows past its own boundary.
 * Unsplit, the DOM is exactly as it always was: [mainCanvas] directly inside [mainViewport].
 *
 * Everything that moves the camera goes through `applyViewportTranslation` in Main.kt.
 */
object CanvasCamera {
    internal var x = DoubleArray(1)
    internal var y = DoubleArray(1)

    var zoom = 1.0
        internal set

    /** Screen fraction of the vertical divider, or null when there is one column. */
    var splitX: Double? = null
        private set

    /** Screen fraction of the horizontal divider, or null when there is one row. */
    var splitY: Double? = null
        private set

    /** The world x the vertical divider separates; boxes at or right of it are in the right column. */
    var worldSplitX = 0.0
        private set

    /** The world y the horizontal divider separates; boxes at or below it are in the bottom row. */
    var worldSplitY = 0.0
        private set

    val columns: Int get() = if (splitX != null) 2 else 1
    val rows: Int get() = if (splitY != null) 2 else 1
    val isSplit: Boolean get() = splitX != null || splitY != null

    var panes: List<CanvasPane> = listOf(CanvasPane(0, 0))
        private set

    /** The scrolling body: the bottom-right pane, and the only one while the canvas is not split. */
    val main: CanvasPane get() = panes.last()

    /** The pane under a window point; points outside every pane belong to [main]. */
    fun paneAt(clientX: Double, clientY: Double): CanvasPane =
        panes.firstOrNull { it.contains(clientX, clientY) } ?: main

    /** The pane showing [element] (a box, or anything inside one). */
    fun paneOf(element: Element?): CanvasPane {
        if (!isSplit || element == null) return main
        val host = element.closest(".field-pane") ?: return main
        return panes.firstOrNull { it.element == host } ?: main
    }

    /** The pane a box with its frame origin at ([frameX], [frameY]) belongs to. */
    fun paneForFrame(frameX: Double, frameY: Double): CanvasPane {
        val col = if (splitX != null && frameX >= worldSplitX) 1 else 0
        val row = if (splitY != null && frameY >= worldSplitY) 1 else 0
        return panes.first { it.col == col && it.row == row }
    }

    /** True for the empty canvas background: the viewport, or a pane when split. */
    fun isBackground(target: Any?): Boolean =
        target == mainViewport || (target is HTMLElement && target.classList.contains("field-pane"))

    internal fun columnRect(col: Int): Pair<Double, Double> {
        val w = window.innerWidth.toDouble()
        val sx = splitX ?: return 0.0 to w
        val divider = w * sx
        return if (col == 0) 0.0 to divider else divider to w - divider
    }

    internal fun rowRect(row: Int): Pair<Double, Double> {
        val h = window.innerHeight.toDouble()
        val sy = splitY ?: return 0.0 to h
        val divider = h * sy
        return if (row == 0) 0.0 to divider else divider to h - divider
    }

    // ---- Constraining the camera ----

    /**
     * After [moved] was given a new translation and possibly a new zoom (from [previousZoom]):
     * keep the world point at each other column's and row's divider edge where it was, so a zoom
     * in one pane leaves the others aligned to the dividers, then clamp every column and row to
     * its quadrant.
     */
    internal fun constrain(moved: CanvasPane, previousZoom: Double) {
        if (!isSplit) return
        if (previousZoom != zoom) {
            for (c in 0 until columns) {
                if (c == moved.col) continue
                val edge = dividerEdgeX(c)
                x[c] = edge - (edge - x[c]) / previousZoom * zoom
            }
            for (r in 0 until rows) {
                if (r == moved.row) continue
                val edge = dividerEdgeY(r)
                y[r] = edge - (edge - y[r]) / previousZoom * zoom
            }
        }
        if (splitX != null) {
            val (l0, w0) = columnRect(0)
            val (l1, _) = columnRect(1)
            // The left column never shows world x past the boundary; the right never shows before it.
            x[0] = max(x[0], l0 + w0 - worldSplitX * zoom)
            x[1] = minOf(x[1], l1 - worldSplitX * zoom)
        }
        if (splitY != null) {
            val (t0, h0) = rowRect(0)
            val (t1, _) = rowRect(1)
            y[0] = max(y[0], t0 + h0 - worldSplitY * zoom)
            y[1] = minOf(y[1], t1 - worldSplitY * zoom)
        }
    }

    private fun dividerEdgeX(col: Int): Double {
        val (left, width) = columnRect(col)
        return if (col == 0) left + width else left
    }

    private fun dividerEdgeY(row: Int): Double {
        val (top, height) = rowRect(row)
        return if (row == 0) top + height else top
    }

    // ---- Splitting ----

    /**
     * Splits the canvas with dividers at window point ([clientX], [clientY]); a null coordinate
     * means no divider on that axis, and both null removes the split. The world lines are the world
     * point under the dividers and every pane starts at the current camera, so nothing moves.
     */
    fun splitAt(clientX: Double?, clientY: Double?) {
        val w = window.innerWidth.toDouble()
        val h = window.innerHeight.toDouble()
        val from = main
        val tx = from.tx
        val ty = from.ty
        val sx = clientX?.let { (it / w).coerceIn(0.08, 0.92) }
        val sy = clientY?.let { (it / h).coerceIn(0.08, 0.92) }
        setSplit(
            SplitState(
                sx = sx,
                sy = sy,
                wx = sx?.let { (it * w - tx) / zoom } ?: 0.0,
                wy = sy?.let { (it * h - ty) / zoom } ?: 0.0,
            ),
            tx, ty, persist = true
        )
    }

    /** Applies a split from the root box's `panes` property (another client, or a document load). */
    fun loadSplit(json: String?) {
        val state = SplitState.parse(json)
        if (state == SplitState(splitX, splitY, worldSplitX, worldSplitY)) return
        setSplit(state ?: SplitState(null, null, 0.0, 0.0), main.tx, main.ty, persist = false)
    }

    private fun setSplit(state: SplitState, tx: Double, ty: Double, persist: Boolean) {
        splitX = state.sx
        splitY = state.sy
        worldSplitX = state.wx
        worldSplitY = state.wy
        x = DoubleArray(columns) { tx }
        y = DoubleArray(rows) { ty }
        rebuildPanes()
        constrain(main, zoom)
        if (persist) {
            val json = if (isSplit) state.toJson() else ""
            findBoxByUid("root")?.asDynamic()?.panes = json
            send(Message("set-string").payload(Set_string("root", "panes", json)))
        }
        onCameraLayoutChanged?.invoke()
    }

    /** Set by Main.kt: re-applies transforms and every layer that copies the camera. */
    internal var onCameraLayoutChanged: (() -> Unit)? = null

    // ---- Pane DOM ----

    private var mutationObserver: MutationObserver? = null
    private var installed = false
    internal var draggingBoxes = false

    private fun rebuildPanes() {
        // Gather every top-level box onto mainCanvas while the old panes are still attached (a
        // detached node cannot be moved with its state), then take the old panes down.
        val boxesToRehome = allCanvasBoxes()
        boxesToRehome.forEach { if (it.parentElement != mainCanvas) moveNode(mainCanvas, it, null) }
        panes.forEach { pane ->
            if (pane.element != null) {
                if (pane.canvas == mainCanvas) moveNode(mainViewport, mainCanvas, null)
                pane.element?.remove()
            }
        }
        val next = mutableListOf<CanvasPane>()
        for (r in 0 until rows) for (c in 0 until columns) next += CanvasPane(c, r)
        panes = next

        if (isSplit) {
            next.forEach { pane ->
                val el = document.createElement("div") as HTMLElement
                el.className = "field-pane"
                el.classList.add(if (pane.col == 0 && columns == 2) "field-pane-left" else "field-pane-right")
                el.classList.add(if (pane.row == 0 && rows == 2) "field-pane-top" else "field-pane-bottom")
                mainViewport.appendChild(el)
                pane.element = el
                if (pane === next.last()) {
                    moveNode(el, mainCanvas, null)
                    pane.canvas = mainCanvas
                } else {
                    val canvas = document.createElement("div") as HTMLElement
                    canvas.className = "field-main-canvas field-pane-canvas"
                    el.appendChild(canvas)
                    pane.canvas = canvas
                }
            }
        } else {
            panes.single().canvas = mainCanvas
        }
        boxesToRehome.forEach { place(it) }
        layoutPanes()
        installOnce()
    }

    /** Boxes that sit directly on a pane canvas (not nested inside another box). */
    private fun allCanvasBoxes(): List<HTMLElement> =
        panes.mapNotNull { it.canvas }.ifEmpty { listOf(mainCanvas) }.flatMap { canvas ->
            canvas.children.asList().filterIsInstance<HTMLElement>().filter { it.classList.contains("box") }
        }

    /** Sizes the pane elements to their rects. */
    fun layoutPanes() {
        panes.forEach { pane ->
            val el = pane.element ?: return@forEach
            val r = pane.rect
            el.style.left = "${r.x}px"
            el.style.top = "${r.y}px"
            el.style.width = "${r.w}px"
            el.style.height = "${r.h}px"
        }
    }

    /** Applies each pane's camera to its canvas element. */
    fun applyTransforms() {
        panes.forEach { pane -> pane.canvas?.style?.transform = pane.canvasTransform() }
        if (!isSplit) mainCanvas.style.transform = main.canvasTransform()
    }

    /**
     * Moves a top-level box into the canvas of the pane its frame belongs to. Boxes nested in
     * another box, and boxes being dragged, stay where they are.
     */
    fun place(box: HTMLElement) {
        if (!isSplit || draggingBoxes) return
        val parent = box.parentElement as? HTMLElement ?: return
        if (panes.none { it.canvas == parent }) return
        val frameX = (box.asDynamic().data_x as? Number)?.toDouble() ?: return
        val frameY = (box.asDynamic().data_y as? Number)?.toDouble() ?: return
        val target = paneForFrame(frameX, frameY).canvas ?: return
        if (target != parent) moveNode(target, box, null)
    }

    /**
     * Moves [node] under [parent] keeping its state: `moveBefore` keeps iframes loaded and focus
     * where it was, where `appendChild` would reload every iframe output in the box.
     */
    private fun moveNode(parent: HTMLElement, node: HTMLElement, before: HTMLElement?) {
        val p = parent.asDynamic()
        if (p.moveBefore != null && p.moveBefore != undefined && node.isConnected) {
            try {
                p.moveBefore(node, before)
                return
            } catch (e: dynamic) {
                // moveBefore refuses some moves (across documents); fall back.
            }
        }
        parent.insertBefore(node, before)
    }

    private fun installOnce() {
        if (installed) return
        installed = true
        window.addEventListener("resize", {
            if (isSplit) {
                layoutPanes()
                constrain(main, zoom)
                onCameraLayoutChanged?.invoke()
            }
        })
        // Boxes are created inside mainCanvas; while split, send each to the pane it belongs to.
        mutationObserver = MutationObserver { records, _ ->
            if (!isSplit) return@MutationObserver
            records.forEach { record ->
                record.addedNodes.asList().forEach { node ->
                    val box = node as? HTMLElement ?: return@forEach
                    if (box.classList.contains("box")) place(box)
                }
            }
        }.also { it.observe(mainCanvas, js("({ childList: true })").unsafeCast<MutationObserverInit>()) }
        document.addEventListener("mousemove", { event ->
            val e = event as MouseEvent
            lastPointerX = e.clientX.toDouble()
            lastPointerY = e.clientY.toDouble()
        })
    }

    internal var lastPointerX = Double.NaN
    internal var lastPointerY = Double.NaN

    /** For tests and the backdoor: `window.canvasPanesState()`. */
    fun state(): dynamic {
        val s: dynamic = js("({})")
        s.split = isSplit
        s.columns = columns
        s.rows = rows
        s.splitX = splitX
        s.splitY = splitY
        s.worldSplitX = worldSplitX
        s.worldSplitY = worldSplitY
        s.zoom = zoom
        s.panes = panes.map { pane ->
            val p: dynamic = js("({})")
            val r = pane.rect
            p.col = pane.col
            p.row = pane.row
            p.tx = pane.tx
            p.ty = pane.ty
            p.rect = arrayOf(r.x, r.y, r.w, r.h)
            p.boxes = (pane.canvas?.children?.asList() ?: emptyList())
                .filterIsInstance<HTMLElement>()
                .filter { it.classList.contains("box") }
                .mapNotNull { it.asDynamic().uid as? String }
                .toTypedArray()
            p
        }.toTypedArray()
        return s
    }
}

/** The persisted split: the root box's `panes` property, as JSON. */
private data class SplitState(val sx: Double?, val sy: Double?, val wx: Double, val wy: Double) {
    fun toJson(): String {
        val o: dynamic = js("({})")
        if (sx != null) { o.sx = sx; o.wx = wx }
        if (sy != null) { o.sy = sy; o.wy = wy }
        return JSON.stringify(o)
    }

    companion object {
        fun parse(json: String?): SplitState? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSON.parse<dynamic>(json)
                val sx = (o.sx as? Number)?.toDouble()
                val sy = (o.sy as? Number)?.toDouble()
                if (sx == null && sy == null) null
                else SplitState(
                    sx, sy,
                    (o.wx as? Number)?.toDouble() ?: 0.0,
                    (o.wy as? Number)?.toDouble() ?: 0.0
                )
            } catch (e: Throwable) {
                console.warn("[panes] unreadable split, ignoring", json)
                null
            }
        }
    }
}

/** One rectangle of the window showing the box canvas through [CanvasCamera]. */
class CanvasPane(val col: Int, val row: Int) {
    /** The `.field-pane` element; null while the canvas is not split. */
    internal var element: HTMLElement? = null

    /** The element holding this pane's boxes, transformed by its camera. */
    internal var canvas: HTMLElement? = null

    /** This pane's rectangle in window coordinates. */
    val rect: Rect
        get() {
            val (left, width) = CanvasCamera.columnRect(col)
            val (top, height) = CanvasCamera.rowRect(row)
            return Rect(left, top, width, height)
        }

    val tx: Double get() = CanvasCamera.x.let { it.getOrElse(col) { _ -> it.last() } }
    val ty: Double get() = CanvasCamera.y.let { it.getOrElse(row) { _ -> it.last() } }
    val zoom: Double get() = CanvasCamera.zoom

    fun contains(clientX: Double, clientY: Double): Boolean {
        val r = rect
        return clientX >= r.x && clientX < r.x + r.w && clientY >= r.y && clientY < r.y + r.h
    }

    fun worldX(clientX: Double) = (clientX - tx) / zoom
    fun worldY(clientY: Double) = (clientY - ty) / zoom

    /** A window-space rect (a `getBoundingClientRect`) in world coordinates. */
    fun worldRect(r: DOMRect) = DOMRect(worldX(r.x), worldY(r.y), r.width / zoom, r.height / zoom)

    /** The part of the world this pane shows. */
    fun visibleWorldRect(): Rect {
        val r = rect
        return Rect(worldX(r.x), worldY(r.y), r.w / zoom, r.h / zoom)
    }

    /** The viewBox for an SVG layer covering this pane, drawn in world coordinates. */
    fun worldViewBox(): String {
        val v = visibleWorldRect()
        return "${v.x} ${v.y} ${v.w} ${v.h}"
    }

    /**
     * The CSS transform for this pane's canvas element. Split, the canvas sits at the pane's
     * corner; unsplit, at the window's.
     */
    fun canvasTransform(): String {
        val r = if (element != null) rect else Rect(0.0, 0.0, 0.0, 0.0)
        return "translate(${tx - r.x}px, ${ty - r.y}px) scale($zoom)"
    }
}
