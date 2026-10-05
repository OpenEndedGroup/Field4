package afield

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/**
 * The command palette's preview for the split commands (`splitAction` in Main.kt, through each
 * action's `docJs`): a thumbnail of the window as it will be after the command, drawn from the
 * live split, the boxes where they are on screen, and the pointer, which is where the command acts.
 * New dividers are cyan, as is the pane an unlink or relink command acts on, with the stretches of
 * divider that will turn dashed (or solid again). A command that would be refused says why.
 */
object CanvasSplitPreview {
    private const val THUMB_WIDTH = 240.0
    private const val THUMB_MAX_HEIGHT = 170.0
    private const val CYAN = "#00e5ff"

    private class Segment(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val dashed: Boolean, val cyan: Boolean)

    fun html(commandId: String, label: String, detail: String): String {
        val w = window.innerWidth.toDouble()
        val h = window.innerHeight.toDouble()
        val px = CanvasCamera.lastPointerX.takeIf { !it.isNaN() } ?: (w * 0.3)
        val py = CanvasCamera.lastPointerY.takeIf { !it.isNaN() } ?: (h * 0.3)

        var fx = CanvasCamera.dividersX
        var fy = CanvasCamera.dividersY
        val cyanX = mutableListOf<Double>()
        val cyanY = mutableListOf<Double>()
        val notes = mutableListOf<String>()
        var target: CanvasPane? = null
        var unlinkX = false
        var unlinkY = false
        var relink = false

        fun add(vertical: Boolean) {
            val client = if (vertical) px else py
            val why = CanvasCamera.cannotAddDivider(vertical, client)
            if (why != null) { notes += why; return }
            val f = client / (if (vertical) w else h)
            if (vertical) { fx = (fx + f).sorted(); cyanX += f } else { fy = (fy + f).sorted(); cyanY += f }
        }
        when (commandId) {
            "split-canvas-here" -> { add(true); add(false) }
            "split-canvas-columns-here" -> add(true)
            "split-canvas-rows-here" -> add(false)
            "remove-canvas-split" -> {
                if (!CanvasCamera.isSplit) notes += "The canvas is not split" else { fx = emptyList(); fy = emptyList() }
            }
            "unlink-pane", "unlink-pane-horizontally", "unlink-pane-vertically", "relink-pane" -> {
                if (!CanvasCamera.isSplit) notes += "The canvas is not split" else {
                    target = CanvasCamera.paneAt(px, py)
                    unlinkX = commandId == "unlink-pane" || commandId == "unlink-pane-horizontally"
                    unlinkY = commandId == "unlink-pane" || commandId == "unlink-pane-vertically"
                    relink = commandId == "relink-pane"
                }
            }
        }

        val scale = minOf(THUMB_WIDTH / w, THUMB_MAX_HEIGHT / h)
        val tw = w * scale
        val th = h * scale
        fun bounds(fractions: List<Double>, total: Double) = listOf(0.0) + fractions.map { it * total } + listOf(total)
        val bx = bounds(fx, w)
        val by = bounds(fy, h)
        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="${tw}" height="${th}" viewBox="0 0 $w $h" style="display:block;margin:8px 0 10px;border-radius:4px">""")
        sb.append("""<rect x="0" y="0" width="$w" height="$h" fill="#000"/>""")
        val gap = 2.2 / scale
        val radius = 4.0 / scale
        for (r in 0 until by.size - 1) for (c in 0 until bx.size - 1) {
            val x0 = bx[c] + if (c > 0) gap / 2 else 0.0
            val x1 = bx[c + 1] - if (c < bx.size - 2) gap / 2 else 0.0
            val y0 = by[r] + if (r > 0) gap / 2 else 0.0
            val y1 = by[r + 1] - if (r < by.size - 2) gap / 2 else 0.0
            sb.append("""<rect x="$x0" y="$y0" width="${x1 - x0}" height="${y1 - y0}" rx="$radius" fill="#1b1b1b"/>""")
        }
        // The boxes where they are now; splitting moves nothing on screen.
        boxesOnScreen().forEach { (x, y, bw, bh) ->
            sb.append("""<rect x="$x" y="$y" width="$bw" height="$bh" rx="${1.5 / scale}" fill="#3a3a3a" stroke="#5a5a5a" stroke-width="${0.6 / scale}"/>""")
        }
        cyanX.forEach { f -> sb.append(line(f * w, 0.0, f * w, h, CYAN, 2.0 / scale, null)) }
        cyanY.forEach { f -> sb.append(line(0.0, f * h, w, f * h, CYAN, 2.0 / scale, null)) }
        segments(target, unlinkX, unlinkY, relink).forEach { s ->
            val dash = if (s.dashed) "${4.0 / scale} ${3.0 / scale}" else null
            sb.append(line(s.x1, s.y1, s.x2, s.y2, if (s.cyan) CYAN else "#777", 2.0 / scale, dash))
        }
        target?.let { pane ->
            val r = pane.rect
            sb.append("""<rect x="${r.x + gap}" y="${r.y + gap}" width="${r.w - 2 * gap}" height="${r.h - 2 * gap}" rx="$radius" fill="$CYAN" fill-opacity="0.08" stroke="$CYAN" stroke-opacity="0.5" stroke-width="${1.0 / scale}"/>""")
        }
        // Where the command acts: the pointer.
        sb.append("""<circle cx="$px" cy="$py" r="${3.0 / scale}" fill="none" stroke="$CYAN" stroke-width="${1.2 / scale}"/>""")
        sb.append("</svg>")

        val out = StringBuilder()
        out.append("""<div class="afield-palette-doc-title">${escape(label)}</div>""")
        out.append(sb)
        out.append("<p>${escape(detail)}</p>")
        notes.forEach { out.append("""<p class="afield-palette-doc-muted">${escape(it)}; the command will do nothing.</p>""") }
        if (notes.isEmpty()) out.append("""<p class="afield-palette-doc-muted">The circle is the pointer, where the command acts.</p>""")
        return out.toString()
    }

    /**
     * The stretches of divider to draw on top: those already dashed (another pane is unlinked), and
     * for the target pane the ones this command turns dashed, or solid again for a relink.
     */
    private fun segments(target: CanvasPane?, unlinkX: Boolean, unlinkY: Boolean, relink: Boolean): List<Segment> {
        val out = mutableListOf<Segment>()
        val panes = CanvasCamera.panes
        fun pane(c: Int, r: Int) = panes.firstOrNull { it.col == c && it.row == r }
        fun freeX(p: CanvasPane?) = p != null && (CanvasCamera.isFreeX(p) && !(relink && p === target) || (unlinkX && p === target))
        fun freeY(p: CanvasPane?) = p != null && (CanvasCamera.isFreeY(p) && !(relink && p === target) || (unlinkY && p === target))
        fun involves(a: CanvasPane?, b: CanvasPane?) = target != null && (a === target || b === target)
        for (i in CanvasCamera.dividersX.indices) for (r in 0 until CanvasCamera.rows) {
            val a = pane(i, r)
            val b = pane(i + 1, r)
            val (top, height) = CanvasCamera.rowRect(r)
            val x = CanvasCamera.columnRect(i + 1).first
            val dashed = freeY(a) || freeY(b)
            val changed = involves(a, b) && dashed != ((a != null && CanvasCamera.isFreeY(a)) || (b != null && CanvasCamera.isFreeY(b)))
            if (dashed || changed) out += Segment(x, top, x, top + height, dashed, changed)
        }
        for (i in CanvasCamera.dividersY.indices) for (c in 0 until CanvasCamera.columns) {
            val a = pane(c, i)
            val b = pane(c, i + 1)
            val (left, width) = CanvasCamera.columnRect(c)
            val y = CanvasCamera.rowRect(i + 1).first
            val dashed = freeX(a) || freeX(b)
            val changed = involves(a, b) && dashed != ((a != null && CanvasCamera.isFreeX(a)) || (b != null && CanvasCamera.isFreeX(b)))
            if (dashed || changed) out += Segment(left, y, left + width, y, dashed, changed)
        }
        return out
    }

    /** Each top-level box's on-screen rect, cut to its pane: what the window shows now. */
    private fun boxesOnScreen(): List<List<Double>> =
        document.querySelectorAll(".box").asList().mapNotNull { node ->
            val box = node as? HTMLElement ?: return@mapNotNull null
            if (box.asDynamic().uid == "root" || box.closest(".outputArea") != null) return@mapNotNull null
            val r = CanvasCamera.visibleRect(box, box.getBoundingClientRect()) ?: return@mapNotNull null
            if (r.width <= 0.0 || r.height <= 0.0) null else listOf(r.left, r.top, r.width, r.height)
        }

    private fun line(x1: Double, y1: Double, x2: Double, y2: Double, color: String, width: Double, dash: String?): String =
        """<line x1="$x1" y1="$y1" x2="$x2" y2="$y2" stroke="$color" stroke-width="$width"${if (dash != null) " stroke-dasharray=\"$dash\"" else ""}/>"""

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
