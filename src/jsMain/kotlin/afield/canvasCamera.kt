package afield

import kotlinx.browser.window
import org.w3c.dom.DOMRect
import org.w3c.dom.Element

/**
 * The box canvas camera, held per pane so the canvas can later be split spreadsheet-style
 * (`dev_docs/workspace/SPLIT_PANES_PLAN.md`): panes in one column share an X translation, panes in
 * one row share a Y translation, and every pane shares the zoom.
 *
 * The camera of a pane is `screen = world * zoom + (tx, ty)`, with the translation in window
 * (client) pixels rather than pixels from the pane's corner, so `getBoundingClientRect` arithmetic
 * is the same in every pane. There is one pane today, the whole window.
 *
 * Everything that moves the camera goes through `applyViewportTranslation` in Main.kt; this file
 * only holds the state and the conversions.
 */
object CanvasCamera {
    internal val x = DoubleArray(1)
    internal val y = DoubleArray(1)

    var zoom = 1.0
        internal set

    val panes: List<CanvasPane> = listOf(CanvasPane(0, 0))

    /** The scrolling body: the bottom-right pane, and the only one while the canvas is not split. */
    val main: CanvasPane get() = panes.last()

    /** The pane under a window point; points outside every pane belong to [main]. */
    fun paneAt(clientX: Double, clientY: Double): CanvasPane =
        panes.firstOrNull { it.contains(clientX, clientY) } ?: main

    /** The pane showing [element] (a box, or anything inside one). */
    @Suppress("UNUSED_PARAMETER")
    fun paneOf(element: Element?): CanvasPane = main
}

/** One rectangle of the window showing the box canvas through [CanvasCamera]. */
class CanvasPane(val col: Int, val row: Int) {
    /** This pane's rectangle in window coordinates. */
    val rect: Rect get() = Rect(0.0, 0.0, window.innerWidth.toDouble(), window.innerHeight.toDouble())

    val tx: Double get() = CanvasCamera.x[col]
    val ty: Double get() = CanvasCamera.y[row]
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

    /** The CSS transform for this pane's canvas element, which sits at the pane's corner. */
    fun canvasTransform(): String {
        val r = rect
        return "translate(${tx - r.x}px, ${ty - r.y}px) scale($zoom)"
    }
}
