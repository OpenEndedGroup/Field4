package afield.app.canvas

import afield.app.client.acceptSetStringSync
import afield.app.client.later
import afield.app.client.worldViewBox
import afield.app.protocol.handle

import afield.app.client.clear
import afield.app.client.client
import afield.app.client.copy
import afield.app.client.events
import afield.app.client.findBoxByUid
import afield.app.client.main
import afield.app.client.mainCanvas
import afield.app.client.mainViewport
import afield.app.protocol.Rect
import afield.app.protocol.payload
import afield.app.protocol.put
import afield.app.protocol.send
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
 * one row share a Y translation, and every pane shares the zoom. A pane can be unlinked on either
 * axis, and then scrolls on its own along it.
 *
 * The camera of a pane is `screen = world * zoom + (tx, ty)`, with the translation in window
 * (client) pixels rather than pixels from the pane's corner, so `getBoundingClientRect` arithmetic
 * is the same in every pane.
 *
 * A split is up to [MAX_DIVIDERS] dividers per axis, each a screen position (a fraction of the
 * window) and the world line it separates. Each column shows its own band of the one world, between
 * the world lines either side of it (unbounded at the window's edges), and likewise each row; a pane
 * shows the intersection. A box belongs to the pane whose band holds its frame origin, and its DOM
 * node lives in that pane's canvas element. Each translation is clamped so its pane never shows
 * world before its band; a middle band narrower than its pane shows from its near edge, and the
 * rest of the pane is flat grey (the background shader and the drawing layer both mask it).
 * Unsplit, the DOM is exactly as it always was: [mainCanvas] directly inside [mainViewport].
 *
 * Everything that moves the camera goes through `applyViewportTranslation` in Main.kt.
 */
object CanvasCamera {
    /** Each column's translation, then each row's. */
    internal var x = DoubleArray(1)
    internal var y = DoubleArray(1)

    /** Unlinked panes' own translations, by (col, row): such a pane ignores its column (row). */
    private var ownX = mutableMapOf<Pair<Int, Int>, Double>()
    private var ownY = mutableMapOf<Pair<Int, Int>, Double>()

    var zoom = 1.0
        internal set

    /** The vertical dividers' positions, as ascending fractions of the window width. */
    var dividersX: List<Double> = emptyList()
        private set

    /** The horizontal dividers' positions, as ascending fractions of the window height. */
    var dividersY: List<Double> = emptyList()
        private set

    /** The world x each vertical divider separates; a box at or right of line i is past column i. */
    var linesX: List<Double> = emptyList()
        private set

    /** The world y each horizontal divider separates. */
    var linesY: List<Double> = emptyList()
        private set

    /** The first divider's position, or null; kept for the single-divider readers (tests, docs). */
    val splitX: Double? get() = dividersX.firstOrNull()
    val splitY: Double? get() = dividersY.firstOrNull()
    val worldSplitX: Double get() = linesX.firstOrNull() ?: 0.0
    val worldSplitY: Double get() = linesY.firstOrNull() ?: 0.0

    val columns: Int get() = dividersX.size + 1
    val rows: Int get() = dividersY.size + 1
    val isSplit: Boolean get() = dividersX.isNotEmpty() || dividersY.isNotEmpty()

    const val MAX_DIVIDERS = 2

    var panes: List<CanvasPane> = listOf(CanvasPane(0, 0))
        private set

    /** The scrolling body: the bottom-right pane, and the only one while the canvas is not split. */
    val main: CanvasPane get() = panes.last()

    /** The pane under a window point; points outside every pane belong to [main]. */
    fun paneAt(clientX: Double, clientY: Double): CanvasPane =
        panes.firstOrNull { it.contains(clientX, clientY) } ?: main

    /** The pane showing [element] (a box, or anything inside one); for a box being dragged, its origin pane. */
    fun paneOf(element: Element?): CanvasPane {
        if (!isSplit || element == null) return main
        element.closest(".field-drag-canvas")?.let { canvas ->
            return dragCanvases.entries.firstOrNull { it.value == canvas }?.key ?: main
        }
        val host = element.closest(".field-pane") ?: return main
        return panes.firstOrNull { it.element == host } ?: main
    }

    /** The pane a box with its frame origin at ([frameX], [frameY]) belongs to. */
    fun paneForFrame(frameX: Double, frameY: Double): CanvasPane {
        val col = linesX.count { frameX >= it }
        val row = linesY.count { frameY >= it }
        return panes.first { it.col == col && it.row == row }
    }

    /**
     * [r] (the client rect of [box], or of something in it) cut to the pane showing it; null when
     * that pane hides it entirely. Unsplit, [r] itself.
     */
    fun visibleRect(box: Element, r: DOMRect): DOMRect? {
        if (!isSplit) return r
        // A box being dragged is drawn above the panes, unclipped.
        if (box.closest(".field-drag-canvas") != null) return r
        val p = paneOf(box).rect
        val left = max(r.left, p.x)
        val top = max(r.top, p.y)
        val right = minOf(r.right, p.x + p.w)
        val bottom = minOf(r.bottom, p.y + p.h)
        return if (right <= left || bottom <= top) null else DOMRect(left, top, right - left, bottom - top)
    }

    /** True for the empty canvas background: the viewport, or a pane when split. */
    fun isBackground(target: Any?): Boolean =
        target == mainViewport || (target is HTMLElement && target.classList.contains("field-pane"))

    private fun span(fractions: List<Double>, total: Double, index: Int): Pair<Double, Double> {
        val start = if (index == 0) 0.0 else total * fractions[index - 1]
        val end = if (index >= fractions.size) total else total * fractions[index]
        return start to end - start
    }

    internal fun columnRect(col: Int): Pair<Double, Double> = span(dividersX, window.innerWidth.toDouble(), col)
    internal fun rowRect(row: Int): Pair<Double, Double> = span(dividersY, window.innerHeight.toDouble(), row)

    /** The world band column [col] shows: from the line before it to the line after it. */
    internal fun bandX(col: Int): Pair<Double, Double> =
        (linesX.getOrNull(col - 1) ?: Double.NEGATIVE_INFINITY) to (linesX.getOrNull(col) ?: Double.POSITIVE_INFINITY)

    internal fun bandY(row: Int): Pair<Double, Double> =
        (linesY.getOrNull(row - 1) ?: Double.NEGATIVE_INFINITY) to (linesY.getOrNull(row) ?: Double.POSITIVE_INFINITY)

    // ---- Per-pane translation ----

    internal fun paneTx(pane: CanvasPane): Double =
        ownX[pane.col to pane.row] ?: x.let { it.getOrElse(pane.col) { _ -> it.last() } }

    internal fun paneTy(pane: CanvasPane): Double =
        ownY[pane.col to pane.row] ?: y.let { it.getOrElse(pane.row) { _ -> it.last() } }

    fun isFreeX(pane: CanvasPane) = (pane.col to pane.row) in ownX
    fun isFreeY(pane: CanvasPane) = (pane.col to pane.row) in ownY

    /** Writes [pane]'s translation: its own, on an axis it is unlinked on, else its column's (row's). */
    internal fun setTranslation(pane: CanvasPane, tx: Double, ty: Double) {
        val key = pane.col to pane.row
        if (key in ownX) ownX[key] = tx else if (pane.col < x.size) x[pane.col] = tx
        if (key in ownY) ownY[key] = ty else if (pane.row < y.size) y[pane.row] = ty
    }

    // ---- Constraining the camera ----

    /**
     * After [moved] was given a new translation and possibly a new zoom (from [previousZoom]):
     * keep the world point at every other translation's anchor edge where it was, so a zoom in one
     * pane leaves the others aligned to the dividers, then clamp every translation to its band.
     */
    internal fun constrain(moved: CanvasPane, previousZoom: Double) {
        if (!isSplit) return
        val movedKey = moved.col to moved.row
        val movedOwnX = movedKey in ownX
        val movedOwnY = movedKey in ownY
        if (previousZoom != zoom) {
            fun rezoom(value: Double, edge: Double) = edge - (edge - value) / previousZoom * zoom
            for (c in 0 until columns) if (movedOwnX || c != moved.col) x[c] = rezoom(x[c], anchorEdgeX(c))
            for (r in 0 until rows) if (movedOwnY || r != moved.row) y[r] = rezoom(y[r], anchorEdgeY(r))
            ownX.keys.toList().forEach { k -> if (!(movedOwnX && k == movedKey)) ownX[k] = rezoom(ownX[k]!!, anchorEdgeX(k.first)) }
            ownY.keys.toList().forEach { k -> if (!(movedOwnY && k == movedKey)) ownY[k] = rezoom(ownY[k]!!, anchorEdgeY(k.second)) }
        }
        for (c in 0 until columns) x[c] = clampX(x[c], c)
        for (r in 0 until rows) y[r] = clampY(y[r], r)
        ownX.keys.toList().forEach { k -> ownX[k] = clampX(ownX[k]!!, k.first) }
        ownY.keys.toList().forEach { k -> ownY[k] = clampY(ownY[k]!!, k.second) }
    }

    /**
     * Keeps column [col] inside its band: first so it shows nothing past the band's far line, then
     * (winning when the band is narrower than the pane) nothing before its near line.
     */
    private fun clampX(value: Double, col: Int): Double {
        val (left, width) = columnRect(col)
        val (lo, hi) = bandX(col)
        var v = value
        if (hi.isFinite()) v = max(v, left + width - hi * zoom)
        if (lo.isFinite()) v = minOf(v, left - lo * zoom)
        return v
    }

    private fun clampY(value: Double, row: Int): Double {
        val (top, height) = rowRect(row)
        val (lo, hi) = bandY(row)
        var v = value
        if (hi.isFinite()) v = max(v, top + height - hi * zoom)
        if (lo.isFinite()) v = minOf(v, top - lo * zoom)
        return v
    }

    /** The edge whose world point a column keeps under zooms and divider moves: its near divider. */
    private fun anchorEdgeX(col: Int): Double {
        val (left, width) = columnRect(col)
        return if (col == 0 && columns > 1) left + width else left
    }

    private fun anchorEdgeY(row: Int): Double {
        val (top, height) = rowRect(row)
        return if (row == 0 && rows > 1) top + height else top
    }

    // ---- Splitting ----

    /**
     * Splits the canvas afresh with one divider on each axis at window point ([clientX], [clientY]);
     * a null coordinate means no divider on that axis, and both null removes the split. The world
     * lines are the world point under the dividers and every pane starts at the current camera, so
     * nothing moves. The corner grip and the tests use this.
     */
    fun splitAt(clientX: Double?, clientY: Double?) {
        val w = window.innerWidth.toDouble()
        val h = window.innerHeight.toDouble()
        val tx = main.tx
        val ty = main.ty
        val sx = clientX?.let { (it / w).coerceIn(0.08, 0.92) }
        val sy = clientY?.let { (it / h).coerceIn(0.08, 0.92) }
        val state = SplitState(
            sx = listOfNotNull(sx),
            sy = listOfNotNull(sy),
            wx = listOfNotNull(sx?.let { (it * w - tx) / zoom }),
            wy = listOfNotNull(sy?.let { (it * h - ty) / zoom }),
        )
        setSplit(state, DoubleArray(state.columns) { tx }, DoubleArray(state.rows) { ty }, emptyMap(), emptyMap(), persist = true)
    }

    /**
     * Adds a divider ([vertical] for a column divider) at window coordinate [client], keeping the
     * rest of the split: the column (row) under it is cut in two at the world point there, seen
     * through that column's (row's) shared camera, and both halves start with that camera, so
     * nothing moves. Returns why it could not, or null.
     */
    fun addDivider(vertical: Boolean, client: Double): String? {
        cannotAddDivider(vertical, client)?.let { return it }
        val total = if (vertical) window.innerWidth.toDouble() else window.innerHeight.toDouble()
        val fractions = if (vertical) dividersX else dividersY
        val f = client / total
        val index = fractions.count { it < f }
        val translations = if (vertical) x else y
        val t = translations[index]
        val (lo, hi) = if (vertical) bandX(index) else bandY(index)
        // Over a narrow band's grey end the pointer is past the band; cut just inside it.
        val line = ((client - t) / zoom).coerceIn(if (lo.isFinite()) lo + 1.0 else Double.NEGATIVE_INFINITY, if (hi.isFinite()) hi - 1.0 else Double.POSITIVE_INFINITY)
        val state = currentState().let {
            if (vertical) it.copy(sx = (it.sx + f).sorted(), wx = (it.wx + line).sorted())
            else it.copy(sy = (it.sy + f).sorted(), wy = (it.wy + line).sorted())
        }
        val newTranslations = translations.toMutableList().also { it.add(index + 1, t) }.toDoubleArray()
        // Panes past the cut move one along; an unlinked pane that was cut leaves both halves unlinked.
        fun shift(map: Map<Pair<Int, Int>, Double>): Map<Pair<Int, Int>, Double> = buildMap {
            map.forEach { (k, v) ->
                val i = if (vertical) k.first else k.second
                fun at(n: Int) = if (vertical) n to k.second else k.first to n
                when {
                    i > index -> put(at(i + 1), v)
                    i == index -> { put(k, v); put(at(i + 1), v) }
                    else -> put(k, v)
                }
            }
        }
        setSplit(
            state.copy(free = emptyList()),
            if (vertical) newTranslations else y.copyOf(),
            if (vertical) x.copyOf() else newTranslations,
            shift(ownX), shift(ownY), persist = true
        )
        return null
    }

    /** Why [addDivider] would refuse a divider at [client], or null if it would add it. */
    fun cannotAddDivider(vertical: Boolean, client: Double): String? {
        val total = if (vertical) window.innerWidth.toDouble() else window.innerHeight.toDouble()
        val fractions = if (vertical) dividersX else dividersY
        if (fractions.size >= MAX_DIVIDERS) {
            return if (vertical) "The canvas already has $MAX_DIVIDERS column dividers" else "The canvas already has $MAX_DIVIDERS row dividers"
        }
        val f = client / total
        if (f < MIN_SPLIT || f > MAX_SPLIT || fractions.any { kotlin.math.abs(it - f) < MIN_GAP }) {
            return "Too close to the edge or to another divider"
        }
        val index = fractions.count { it < f }
        val (lo, hi) = if (vertical) bandX(index) else bandY(index)
        if (hi - lo < 4.0) return "That part of the canvas is too narrow to divide"
        return null
    }

    /**
     * Removes divider [index] ([vertical] for a column divider), keeping the others. The two
     * columns (rows) it separated become one, which keeps the later one's camera and unlinking.
     */
    fun removeDivider(vertical: Boolean, index: Int) {
        val state = currentState().let {
            if (vertical) it.copy(sx = it.sx.filterIndexed { i, _ -> i != index }, wx = it.wx.filterIndexed { i, _ -> i != index })
            else it.copy(sy = it.sy.filterIndexed { i, _ -> i != index }, wy = it.wy.filterIndexed { i, _ -> i != index })
        }
        val translations = if (vertical) x else y
        val merged = translations.filterIndexed { i, _ -> i != index }.toDoubleArray()
        fun drop(map: Map<Pair<Int, Int>, Double>): Map<Pair<Int, Int>, Double> = buildMap {
            map.forEach { (k, v) ->
                val i = if (vertical) k.first else k.second
                fun at(n: Int) = if (vertical) n to k.second else k.first to n
                when {
                    i == index -> {}
                    i > index -> put(at(i - 1), v)
                    else -> put(k, v)
                }
            }
        }
        setSplit(
            state.copy(free = emptyList()),
            if (vertical) merged else y.copyOf(),
            if (vertical) x.copyOf() else merged,
            drop(ownX), drop(ownY), persist = true
        )
    }

    /** Unlinks [pane] on the given axes: it keeps where it is and from now on scrolls on its own there. */
    fun unlinkPane(pane: CanvasPane, horizontally: Boolean, vertically: Boolean) {
        val key = pane.col to pane.row
        if (horizontally && key !in ownX) ownX[key] = paneTx(pane)
        if (vertically && key !in ownY) ownY[key] = paneTy(pane)
        layoutChanged()
    }

    /** Links [pane] back to its column and row: it jumps to where they are scrolled. */
    fun relinkPane(pane: CanvasPane) {
        val key = pane.col to pane.row
        ownX.remove(key)
        ownY.remove(key)
        layoutChanged()
    }

    private fun layoutChanged() {
        layoutPanes()
        constrain(main, zoom)
        persistSplit()
        onCameraLayoutChanged?.invoke()
    }

    /** Applies a split from the root box's `panes` property (another client, or a document load). */
    fun loadSplit(json: String?) {
        val state = SplitState.parse(json) ?: SplitState()
        if (state == currentState()) return
        val keepX = ownX.filterKeys { k -> state.free.any { it.c == k.first && it.r == k.second && it.x } }
        val keepY = ownY.filterKeys { k -> state.free.any { it.c == k.first && it.r == k.second && it.y } }
        setSplit(
            state,
            DoubleArray(state.columns) { x.getOrElse(it) { main.tx } }.takeIf { state.columns == columns } ?: DoubleArray(state.columns) { main.tx },
            DoubleArray(state.rows) { y.getOrElse(it) { main.ty } }.takeIf { state.rows == rows } ?: DoubleArray(state.rows) { main.ty },
            keepX, keepY, persist = false
        )
    }

    /**
     * Restores a bookmarked view: the split ([splitJson], empty for none), each column's and row's
     * translation when the bookmark recorded as many as the split has, and unlinked panes' own.
     */
    fun restoreView(
        splitJson: String,
        columnX: List<Double>?,
        rowY: List<Double>?,
        paneOwnX: Map<String, Double>? = null,
        paneOwnY: Map<String, Double>? = null
    ) {
        val state = SplitState.parse(splitJson) ?: SplitState()
        val xs = columnX?.takeIf { it.size == state.columns }?.toDoubleArray() ?: DoubleArray(state.columns) { main.tx }
        val ys = rowY?.takeIf { it.size == state.rows }?.toDoubleArray() ?: DoubleArray(state.rows) { main.ty }
        fun owns(saved: Map<String, Double>?, axisX: Boolean): Map<Pair<Int, Int>, Double> = buildMap {
            state.free.filter { if (axisX) it.x else it.y }.forEach { f ->
                put(f.c to f.r, saved?.get("${f.c},${f.r}") ?: if (axisX) xs.getOrElse(f.c) { 0.0 } else ys.getOrElse(f.r) { 0.0 })
            }
        }
        if (state == currentState()) {
            // Same split: only the cameras move; the panes and their boxes stay put.
            x = xs
            y = ys
            ownX = owns(paneOwnX, true).toMutableMap()
            ownY = owns(paneOwnY, false).toMutableMap()
            constrain(main, zoom)
            onCameraLayoutChanged?.invoke()
        } else {
            setSplit(state, xs, ys, owns(paneOwnX, true), owns(paneOwnY, false), persist = true)
        }
    }

    /** Each column's translation, then each row's, and the unlinked panes' own: for bookmarks. */
    fun columnTranslations(): List<Double> = x.toList()
    fun rowTranslations(): List<Double> = y.toList()
    fun ownTranslationsX(): Map<String, Double> = ownX.mapKeys { (k, _) -> "${k.first},${k.second}" }
    fun ownTranslationsY(): Map<String, Double> = ownY.mapKeys { (k, _) -> "${k.first},${k.second}" }

    /**
     * The world rectangle each pane of a saved split ([splitJson], with each column's and row's
     * translation, and unlinked panes' own) shows at [zoom], at the current window size; null when
     * there is no split or the translations do not match it. For the bookmark minimap.
     */
    fun viewsOf(
        splitJson: String,
        columnX: List<Double>,
        rowY: List<Double>,
        zoom: Double,
        paneOwnX: Map<String, Double>? = null,
        paneOwnY: Map<String, Double>? = null
    ): List<Rect>? {
        val state = SplitState.parse(splitJson) ?: return null
        if (columnX.size != state.columns || rowY.size != state.rows) return null
        val w = window.innerWidth.toDouble()
        val h = window.innerHeight.toDouble()
        return (0 until state.rows).flatMap { r ->
            (0 until state.columns).map { c ->
                val (left, width) = span(state.sx, w, c)
                val (top, height) = span(state.sy, h, r)
                val tx = paneOwnX?.get("$c,$r") ?: columnX[c]
                val ty = paneOwnY?.get("$c,$r") ?: rowY[r]
                Rect((left - tx) / zoom, (top - ty) / zoom, width / zoom, height / zoom)
            }
        }
    }

    /** The split as JSON for the root box and for bookmarks; empty when the canvas is not split. */
    fun splitJson(): String = if (isSplit) currentState().toJson() else ""

    private fun currentState() = SplitState(
        dividersX, dividersY, linesX, linesY,
        panes.mapNotNull { p ->
            val fx = isFreeX(p)
            val fy = isFreeY(p)
            if (fx || fy) FreePane(p.col, p.row, fx, fy) else null
        }
    )

    private fun setSplit(
        state: SplitState,
        xs: DoubleArray,
        ys: DoubleArray,
        freeX: Map<Pair<Int, Int>, Double>,
        freeY: Map<Pair<Int, Int>, Double>,
        persist: Boolean
    ) {
        dividersX = state.sx
        dividersY = state.sy
        linesX = state.wx
        linesY = state.wy
        x = xs
        y = ys
        // A pane named free with no own translation yet (another client unlinked it) starts where its column is.
        ownX = freeX.filterKeys { it.first < state.columns && it.second < state.rows }.toMutableMap()
        ownY = freeY.filterKeys { it.first < state.columns && it.second < state.rows }.toMutableMap()
        state.free.forEach { f ->
            val key = f.c to f.r
            if (f.x && key !in ownX) ownX[key] = xs.getOrElse(f.c) { 0.0 }
            if (f.y && key !in ownY) ownY[key] = ys.getOrElse(f.r) { 0.0 }
        }
        rebuildPanes()
        constrain(main, zoom)
        if (persist) persistSplit()
        onCameraLayoutChanged?.invoke()
    }

    private fun persistSplit() {
        val json = splitJson()
        findBoxByUid("root")?.asDynamic()?.panes = json
        // Through the marked send, so the server's echo of an older split (a divider drag sends
        // several) is recognised as ours and dropped by acceptSetStringSync instead of undoing this.
        val payload: dynamic = js("({})")
        payload.uid = "root"
        payload.property = "panes"
        payload.value = json
        send("set-string", payload)
    }

    /**
     * Moves divider [index] ([vertical] for a column divider) to window fraction [fraction], kept
     * clear of its neighbours. The world line stays with the divider: every translation keeps the
     * world point at its anchor edge, so the content next to a divider travels with it. Not
     * persisted until [commitDividers].
     */
    fun moveDivider(vertical: Boolean, index: Int, fraction: Double) {
        val edgeWorldX = (0 until columns).map { (anchorEdgeX(it) - x[it]) / zoom }
        val edgeWorldY = (0 until rows).map { (anchorEdgeY(it) - y[it]) / zoom }
        val ownWorldX = ownX.mapValues { (k, v) -> (anchorEdgeX(k.first) - v) / zoom }
        val ownWorldY = ownY.mapValues { (k, v) -> (anchorEdgeY(k.second) - v) / zoom }
        val fractions = (if (vertical) dividersX else dividersY).toMutableList()
        if (index !in fractions.indices) return
        val lo = (fractions.getOrNull(index - 1) ?: 0.0) + MIN_GAP
        val hi = (fractions.getOrNull(index + 1) ?: 1.0) - MIN_GAP
        fractions[index] = fraction.coerceIn(max(lo, MIN_SPLIT), minOf(hi, MAX_SPLIT))
        if (vertical) dividersX = fractions else dividersY = fractions
        for (c in 0 until columns) x[c] = anchorEdgeX(c) - edgeWorldX[c] * zoom
        for (r in 0 until rows) y[r] = anchorEdgeY(r) - edgeWorldY[r] * zoom
        ownWorldX.forEach { (k, w) -> ownX[k] = anchorEdgeX(k.first) - w * zoom }
        ownWorldY.forEach { (k, w) -> ownY[k] = anchorEdgeY(k.second) - w * zoom }
        layoutPanes()
        constrain(main, zoom)
        onCameraLayoutChanged?.invoke()
    }

    fun commitDividers() = persistSplit()

    private const val MIN_SPLIT = 0.04
    private const val MAX_SPLIT = 0.96

    /** The closest two dividers on one axis may come, as a fraction of the window. */
    private const val MIN_GAP = 0.04

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
        dividers.forEach { it.remove() }
        dividers.clear()
        dashes.forEach { it.remove() }
        dashes.clear()
        val next = mutableListOf<CanvasPane>()
        for (r in 0 until rows) for (c in 0 until columns) next += CanvasPane(c, r)
        panes = next

        if (isSplit) {
            next.forEach { pane ->
                val el = document.createElement("div") as HTMLElement
                el.className = "field-pane"
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
                // Above the boxes: its black spread paints this pane's share of the dividers.
                val frame = document.createElement("div") as HTMLElement
                frame.className = "field-pane-frame"
                el.appendChild(frame)
                pane.frame = frame
            }
            dividersX.indices.forEach { dividers += makeDivider(vertical = true, index = it) }
            dividersY.indices.forEach { dividers += makeDivider(vertical = false, index = it) }
            // One dash overlay per stretch of divider between two panes; shown when they stop sharing.
            for (i in dividersX.indices) for (r in 0 until rows) dashes += makeDash(vertical = true, index = i, along = r)
            for (i in dividersY.indices) for (c in 0 until columns) dashes += makeDash(vertical = false, index = i, along = c)
        } else {
            panes.single().canvas = mainCanvas
        }
        boxesToRehome.forEach { place(it) }
        installOnce()
        layoutPanes()
    }

    // ---- Divider handles, dashes and the corner grip ----

    private val dividers = mutableListOf<HTMLElement>()
    private val dashes = mutableListOf<HTMLElement>()
    private var grip: HTMLElement? = null

    /** Releasing a divider this close to the window edge (as a fraction) removes it. */
    private const val REMOVE_AT = 0.03

    /** Dropping the corner grip this close to the top (left) edge leaves out that divider. */
    private const val GRIP_EDGE_PX = 24.0

    /**
     * Divider [index]'s handle: a strip over the pane borders that drags the divider. Releasing it
     * at the window edge, or double-clicking it, removes that divider.
     */
    private fun makeDivider(vertical: Boolean, index: Int): HTMLElement {
        val el = document.createElement("div") as HTMLElement
        el.className = "field-pane-divider " + if (vertical) "field-pane-divider-v" else "field-pane-divider-h"
        el.asDynamic().dividerIndex = index
        el.title = "Drag to move the divider; drag to the edge or double-click to remove it"
        el.addEventListener("mousedown", { event ->
            val e = event as MouseEvent
            if (e.button.toInt() != 0) return@addEventListener
            e.preventDefault()
            e.stopPropagation()
            el.classList.add("dragging")
            document.body?.classList?.add(if (vertical) "field-pane-resizing-v" else "field-pane-resizing-h")
            fun fraction(ev: MouseEvent) =
                if (vertical) ev.clientX / window.innerWidth.toDouble() else ev.clientY / window.innerHeight.toDouble()
            trackPointer(
                move = { ev -> moveDivider(vertical, index, fraction(ev)) },
                up = { ev ->
                    el.classList.remove("dragging")
                    document.body?.classList?.remove("field-pane-resizing-v", "field-pane-resizing-h")
                    val f = fraction(ev)
                    if (f < REMOVE_AT || f > 1 - REMOVE_AT) removeDivider(vertical, index) else commitDividers()
                }
            )
        })
        el.addEventListener("dblclick", { event ->
            event.preventDefault()
            event.stopPropagation()
            removeDivider(vertical, index)
        })
        mainViewport.appendChild(el)
        return el
    }

    /**
     * The overlay for the stretch of divider [index] between the panes either side of it in row
     * (column) [along]: hidden while those two panes scroll together across it, a dashed bar when
     * one of them is unlinked on that axis.
     */
    private fun makeDash(vertical: Boolean, index: Int, along: Int): HTMLElement {
        val el = document.createElement("div") as HTMLElement
        el.className = "field-pane-dash " + if (vertical) "field-pane-dash-v" else "field-pane-dash-h"
        el.asDynamic().dashIndex = index
        el.asDynamic().dashAlong = along
        mainViewport.appendChild(el)
        return el
    }

    /**
     * The grip in the canvas's top-left corner, shown while the canvas is not split: drag it out
     * and release to split there. Released near the top edge it makes columns only, near the left
     * edge rows only.
     */
    private fun installGrip() {
        val el = document.createElement("div") as HTMLElement
        el.className = "field-split-grip"
        el.title = "Drag out to split the canvas into panes"
        val previewV = document.createElement("div") as HTMLElement
        previewV.className = "field-split-preview field-split-preview-v"
        val previewH = document.createElement("div") as HTMLElement
        previewH.className = "field-split-preview field-split-preview-h"
        el.addEventListener("mousedown", { event ->
            val e = event as MouseEvent
            if (e.button.toInt() != 0) return@addEventListener
            e.preventDefault()
            e.stopPropagation()
            mainViewport.appendChild(previewV)
            mainViewport.appendChild(previewH)
            fun show(ev: MouseEvent) {
                val px = ev.clientX.toDouble()
                val py = ev.clientY.toDouble()
                previewV.style.left = "${px}px"
                previewV.style.display = if (px >= GRIP_EDGE_PX) "block" else "none"
                previewH.style.top = "${py}px"
                previewH.style.display = if (py >= GRIP_EDGE_PX) "block" else "none"
            }
            show(e)
            trackPointer(
                move = ::show,
                up = { ev ->
                    previewV.remove()
                    previewH.remove()
                    val px = ev.clientX.toDouble().takeIf { it >= GRIP_EDGE_PX }
                    val py = ev.clientY.toDouble().takeIf { it >= GRIP_EDGE_PX }
                    if (px != null || py != null) splitAt(px, py)
                }
            )
        })
        mainViewport.appendChild(el)
        grip = el
    }

    /** Follows a drag through the window until the button is released, ahead of everything else. */
    private fun trackPointer(move: (MouseEvent) -> Unit, up: (MouseEvent) -> Unit) {
        val capture = js("({ capture: true })")
        lateinit var onMove: (org.w3c.dom.events.Event) -> Unit
        lateinit var onUp: (org.w3c.dom.events.Event) -> Unit
        onMove = { ev ->
            ev.preventDefault()
            ev.stopPropagation()
            move(ev as MouseEvent)
        }
        onUp = { ev ->
            ev.preventDefault()
            ev.stopPropagation()
            window.removeEventListener("mousemove", onMove, capture)
            window.removeEventListener("mouseup", onUp, capture)
            up(ev as MouseEvent)
        }
        window.addEventListener("mousemove", onMove, capture)
        window.addEventListener("mouseup", onUp, capture)
    }

    /** Boxes that sit directly on a pane canvas (not nested inside another box). */
    private fun allCanvasBoxes(): List<HTMLElement> =
        panes.mapNotNull { it.canvas }.ifEmpty { listOf(mainCanvas) }.flatMap { canvas ->
            canvas.children.asList().filterIsInstance<HTMLElement>().filter { it.classList.contains("box") }
        }

    /** Sizes the pane elements to their rects and puts the divider handles and dashes on the borders. */
    fun layoutPanes() {
        panes.forEach { pane ->
            val el = pane.element ?: return@forEach
            val r = pane.rect
            el.style.left = "${r.x}px"
            el.style.top = "${r.y}px"
            el.style.width = "${r.w}px"
            el.style.height = "${r.h}px"
            pane.frame?.let { frame ->
                val (top, right, bottom, left) = pane.dividerInsets()
                frame.style.top = "${top}px"
                frame.style.right = "${right}px"
                frame.style.bottom = "${bottom}px"
                frame.style.left = "${left}px"
                frame.style.borderRadius = pane.frameRadius()
            }
        }
        dividers.forEach { el ->
            val index = el.asDynamic().dividerIndex as Int
            if (el.classList.contains("field-pane-divider-v")) {
                el.style.left = "${columnRect(index + 1).first - DIVIDER_HALF_WIDTH}px"
            } else {
                el.style.top = "${rowRect(index + 1).first - DIVIDER_HALF_WIDTH}px"
            }
        }
        dashes.forEach { el ->
            val index = el.asDynamic().dashIndex as Int
            val along = el.asDynamic().dashAlong as Int
            val bar = CanvasPane.DIVIDER_THICKNESS
            val before = CanvasPane.DIVIDER_BEFORE
            if (el.classList.contains("field-pane-dash-v")) {
                // Between pane (index, along) and (index + 1, along): they share Y unless one is unlinked on it.
                val a = panes.first { it.col == index && it.row == along }
                val b = panes.first { it.col == index + 1 && it.row == along }
                val (top, height) = rowRect(along)
                el.style.left = "${columnRect(index + 1).first - before}px"
                el.style.width = "${bar}px"
                el.style.top = "${top}px"
                el.style.height = "${height}px"
                el.style.display = if (isFreeY(a) || isFreeY(b)) "block" else "none"
            } else {
                val a = panes.first { it.col == along && it.row == index }
                val b = panes.first { it.col == along && it.row == index + 1 }
                val (left, width) = columnRect(along)
                el.style.top = "${rowRect(index + 1).first - before}px"
                el.style.height = "${bar}px"
                el.style.left = "${left}px"
                el.style.width = "${width}px"
                el.style.display = if (isFreeX(a) || isFreeX(b)) "block" else "none"
            }
        }
        grip?.style?.display = if (isSplit) "none" else "block"
    }

    /** Half the divider handle's hit width (field.css `.field-pane-divider`). */
    private const val DIVIDER_HALF_WIDTH = 4.0

    /** Applies each pane's camera to its canvas element. */
    fun applyTransforms() {
        panes.forEach { pane -> pane.canvas?.style?.transform = pane.canvasTransform() }
        if (!isSplit) mainCanvas.style.transform = main.canvasTransform()
        // Drag canvases sit at the window's corner, so their translation is the pane's own.
        dragCanvases.forEach { (pane, canvas) -> canvas.style.transform = "translate(${pane.tx}px, ${pane.ty}px) scale($zoom)" }
        // For screen-constant sizes inside the scaled canvas (field.css `.box-resize-halo`). Only on a
        // zoom change: a custom property restyles everything under it, which a pan must not pay for.
        if (zoom != styledZoom) {
            styledZoom = zoom
            mainViewport.style.setProperty("--canvas-zoom", zoom.toString())
        }
    }

    private var styledZoom = 1.0

    // ---- Dragging boxes across dividers ----

    /** One unclipped canvas above the panes per pane a dragged box came from. */
    private val dragCanvases = mutableMapOf<CanvasPane, HTMLElement>()
    private val dragOrigins = mutableMapOf<HTMLElement, CanvasPane>()

    /**
     * A drag is starting: each dragged top-level box moves (with its state, see [moveNode]) onto
     * an unclipped canvas above the panes that carries its pane's camera, so it stays where it is
     * on screen and can be dragged across a divider without being cut off.
     */
    fun beginBoxDrag(boxes: Collection<HTMLElement>) {
        draggingBoxes = true
        if (!isSplit) return
        boxes.forEach { box ->
            val pane = panes.firstOrNull { it.canvas == box.parentElement } ?: return@forEach
            val canvas = dragCanvases.getOrPut(pane) {
                (document.createElement("div") as HTMLElement).also {
                    it.className = "field-main-canvas field-pane-canvas field-drag-canvas"
                    mainViewport.appendChild(it)
                }
            }
            dragOrigins[box] = pane
            moveNode(canvas, box, null)
        }
        applyTransforms()
    }

    /**
     * The drag ended. Each box goes back onto a pane canvas; one dropped over a different pane
     * gets the frame that keeps it where it was dropped, seen through that pane's camera (its
     * on-screen corner picks the pane, as its frame origin picks the band). Returns the boxes whose
     * frame changed, with their new frames, for the caller to apply and send; boxes are then placed
     * by their frames.
     */
    fun endBoxDrag(): List<Pair<HTMLElement, Rect>> {
        draggingBoxes = false
        val changed = mutableListOf<Pair<HTMLElement, Rect>>()
        dragOrigins.forEach { (box, origin) ->
            val r = box.getBoundingClientRect()
            val dest = paneAt(r.left, r.top)
            if (dest.col != origin.col || dest.row != origin.row) {
                // A corner dropped outside every pane (or over a band's grey end) is kept in the band.
                val (loX, hiX) = bandX(dest.col)
                val (loY, hiY) = bandY(dest.row)
                val x = dest.worldX(r.left).coerceIn(loX, if (hiX.isFinite()) hiX - 1.0 else hiX)
                val y = dest.worldY(r.top).coerceIn(loY, if (hiY.isFinite()) hiY - 1.0 else hiY)
                val d = box.asDynamic()
                val w = (d.data_w as? Number)?.toDouble() ?: (r.width / zoom)
                val h = (d.data_h as? Number)?.toDouble() ?: (r.height / zoom)
                changed += box to Rect(x, y, w, h)
            }
            origin.canvas?.let { if (box.isConnected) moveNode(it, box, null) }
        }
        dragOrigins.clear()
        dragCanvases.values.forEach { it.remove() }
        dragCanvases.clear()
        return changed
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

    /** Called once mainViewport and mainCanvas exist: the grip, the observers, the listeners. */
    fun installOnce() {
        if (installed) return
        installed = true
        installGrip()
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
        s.dividersX = dividersX.toTypedArray()
        s.dividersY = dividersY.toTypedArray()
        s.linesX = linesX.toTypedArray()
        s.linesY = linesY.toTypedArray()
        s.zoom = zoom
        s.panes = panes.map { pane ->
            val p: dynamic = js("({})")
            val r = pane.rect
            p.col = pane.col
            p.row = pane.row
            p.tx = pane.tx
            p.ty = pane.ty
            p.freeX = isFreeX(pane)
            p.freeY = isFreeY(pane)
            p.rect = arrayOf(r.x, r.y, r.w, r.h)
            p.empty = pane.emptyEnds().toTypedArray()
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

/** A pane unlinked on one or both axes, by column and row. */
private data class FreePane(val c: Int, val r: Int, val x: Boolean, val y: Boolean)

/**
 * The persisted split: the root box's `panes` property, as JSON. One divider on an axis is written
 * as a number (`sx`, `wx`), the form documents saved before multiple dividers use; two as a list.
 */
private data class SplitState(
    val sx: List<Double> = emptyList(),
    val sy: List<Double> = emptyList(),
    val wx: List<Double> = emptyList(),
    val wy: List<Double> = emptyList(),
    val free: List<FreePane> = emptyList()
) {
    val columns: Int get() = sx.size + 1
    val rows: Int get() = sy.size + 1

    fun toJson(): String {
        val o: dynamic = js("({})")
        fun put(key: String, values: List<Double>) {
            if (values.isEmpty()) return
            o[key] = if (values.size == 1) values[0] else values.toTypedArray()
        }
        put("sx", sx); put("wx", wx); put("sy", sy); put("wy", wy)
        if (free.isNotEmpty()) {
            o.free = free.map { f ->
                val e: dynamic = js("({})")
                e.c = f.c; e.r = f.r
                if (f.x) e.x = true
                if (f.y) e.y = true
                e
            }.toTypedArray()
        }
        return JSON.stringify(o)
    }

    companion object {
        private fun numbers(value: dynamic): List<Double> = when {
            value == null || value == undefined -> emptyList()
            js("Array").isArray(value) as Boolean -> (value as Array<dynamic>).mapNotNull { (it as? Number)?.toDouble() }
            else -> listOfNotNull((value as? Number)?.toDouble())
        }

        fun parse(json: String?): SplitState? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSON.parse<dynamic>(json)
                val sx = numbers(o.sx).take(CanvasCamera.MAX_DIVIDERS)
                val sy = numbers(o.sy).take(CanvasCamera.MAX_DIVIDERS)
                if (sx.isEmpty() && sy.isEmpty()) return null
                val wx = numbers(o.wx).take(sx.size)
                val wy = numbers(o.wy).take(sy.size)
                if (wx.size != sx.size || wy.size != sy.size) return null
                val free = (if (o.free == null || o.free == undefined) emptyArray<dynamic>() else o.free as Array<dynamic>).mapNotNull { e ->
                    val c = (e.c as? Number)?.toInt() ?: return@mapNotNull null
                    val r = (e.r as? Number)?.toInt() ?: return@mapNotNull null
                    if (c > sx.size || r > sy.size) return@mapNotNull null
                    FreePane(c, r, e.x == true, e.y == true).takeIf { it.x || it.y }
                }
                SplitState(sx, sy, wx, wy, free)
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

    /** The overlay whose rounded, black-spread outline draws this pane's side of the dividers. */
    internal var frame: HTMLElement? = null

    private val innerLeft get() = col > 0
    private val innerRight get() = col < CanvasCamera.columns - 1
    private val innerTop get() = row > 0
    private val innerBottom get() = row < CanvasCamera.rows - 1

    /**
     * How far the visible part of this pane stops short of each divider, as (top, right, bottom,
     * left) in pixels. A divider is [DIVIDER_THICKNESS] wide: the pane before it gives up
     * [DIVIDER_BEFORE], the pane after it the rest, so the bar sits under the handle's stripe.
     */
    fun dividerInsets(): List<Double> = listOf(
        if (innerTop) DIVIDER_THICKNESS - DIVIDER_BEFORE else 0.0,
        if (innerRight) DIVIDER_BEFORE else 0.0,
        if (innerBottom) DIVIDER_BEFORE else 0.0,
        if (innerLeft) DIVIDER_THICKNESS - DIVIDER_BEFORE else 0.0,
    )

    /** [rect] less the dividers: what is visible of the pane, and where its drawing layer sits. */
    val innerRect: Rect
        get() {
            val r = rect
            val (top, right, bottom, left) = dividerInsets()
            return Rect(r.x + left, r.y + top, r.w - left - right, r.h - top - bottom)
        }

    /** CSS border-radius for [innerRect]: every corner that touches a divider is rounded. */
    fun frameRadius(): String {
        fun corner(round: Boolean) = if (round) "${DIVIDER_RADIUS}px" else "0"
        return listOf(
            corner(innerTop || innerLeft), corner(innerTop || innerRight),
            corner(innerBottom || innerRight), corner(innerBottom || innerLeft)
        ).joinToString(" ")
    }

    /** The world band this pane shows, as (minX, minY, maxX, maxY); unbounded sides are infinite. */
    fun band(): List<Double> {
        val (loX, hiX) = CanvasCamera.bandX(col)
        val (loY, hiY) = CanvasCamera.bandY(row)
        return listOf(loX, loY, hiX, hiY)
    }

    /**
     * How much of the pane, from its right and bottom edges, lies past its band's far line: the
     * flat grey end of a band narrower than its pane. (right, bottom) in pixels; zero when none.
     */
    fun emptyEnds(): List<Double> {
        val inner = innerRect
        val (_, _, hiX, hiY) = band()
        val right = if (hiX.isFinite()) max(0.0, inner.x + inner.w - (hiX * zoom + tx)) else 0.0
        val bottom = if (hiY.isFinite()) max(0.0, inner.y + inner.h - (hiY * zoom + ty)) else 0.0
        return listOf(right, bottom)
    }

    companion object {
        const val DIVIDER_THICKNESS = 3.0
        const val DIVIDER_BEFORE = 1.0
        const val DIVIDER_RADIUS = 7.0
    }

    /** This pane's rectangle in window coordinates. */
    val rect: Rect
        get() {
            val (left, width) = CanvasCamera.columnRect(col)
            val (top, height) = CanvasCamera.rowRect(row)
            return Rect(left, top, width, height)
        }

    val tx: Double get() = CanvasCamera.paneTx(this)
    val ty: Double get() = CanvasCamera.paneTy(this)
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
