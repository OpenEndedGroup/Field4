/**
 * # Treemaps
 *
 * `treemap(tree)` draws a tree as nested rectangles, each with an area in proportion to its value, into a box's
 * output. It is an ordinary `lines(...)` drawing: every cell is a keyed [FLine], so the drawing can be
 * exported, inspected, restyled or drawn elsewhere ([Treemap.toFLines]), and everything the lines viewer does
 * applies to it.
 *
 * - **Zoom**: double-click a branch to zoom into it, Alt-click or a breadcrumb to zoom out. [Treemap.zoomTo] does it
 *   from code. The wheel and a drag pan and zoom the camera, and the treemap opens more detail where you look.
 * - **Level of detail**: cells are opened largest first up to [Treemap.maxCells], cells smaller than
 *   [Treemap.minCellSize] screen pixels are not opened, and labels appear only where they fit.
 * - **Selection**: click selects, Shift-click extends, Cmd/Ctrl-click toggles; [Treemap.onSelect] hears about it.
 * - **Animation and incremental update**: every change (zoom, selection, a new tree, an edited weight)
 *   re-lays out and morphs from the old picture to the new over [Treemap.transition] seconds. Keys
 *   ([TreemapNode.key]) carry identity, so a rebuilt tree animates as an edit of the old one. Focus and selection
 *   survive the box running again.
 *
 * ```kotlin
 * val tree = treemapTree("budget") {
 *     branch("housing") { leaf("rent", 1400.0); leaf("power", 90.0) }
 *     leaf("food", 600.0)
 * }
 * treemap(tree) { onSelect = { picked -> println(picked.map { it.name }) } }
 * ```
 */
package afield.ui.visualization.treemap

import afield.document.graph.Box
import afield.geometry.path.FLine
import afield.graphics.color.Colormaps
import afield.graphics.color.wcagContrast
import afield.graphics.color.okhsl
import afield.graphics.render.FLineIntersection
import afield.graphics.render.LineCaption
import afield.graphics.render.caption
import afield.graphics.render.color
import afield.graphics.render.filled
import afield.graphics.render.hoverColor
import afield.graphics.render.key
import afield.graphics.render.label
import afield.graphics.render.onClick
import afield.graphics.render.onHover
import afield.graphics.render.stroked
import afield.graphics.render.thickness
import afield.graphics.render.tooltip
import afield.graphics.render.pickDetail
import afield.graphics.text.textWidth
import afield.graphics.text.typeface
import afield.math.linalg.Vec4
import afield.math.linalg.vec
import afield.runtime.lifecycle.RunScope
import afield.ui.output.markdown
import afield.ui.visualization.LinesViewEvent
import afield.ui.visualization.ToHTML
import afield.ui.visualization.lines
import afield.ui.visualization.linesToHTML
import java.awt.Font
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * A treemap drawn into [channel] of [box]. Make one with [Box.treemap]; change any property and call [show] (or
 * use [update], which does both) to redraw with a morph.
 */
class Treemap internal constructor(private val owner: Box?, val channel: String, root: TreemapNode, private val memory: TreemapMemory) {

    /** The box this treemap draws into. */
    val box: Box get() = owner ?: error("This treemap draws only as a value; make an interactive one with treemap(...) in a box")

    /** The tree being drawn. Assigning a new tree redraws it as an edit of the old one: shared keys morph. */
    var root: TreemapNode = root
        set(v) { field = v; show() }

    /** Drawing size in pixels. */
    var w = 900.0
    var h = 560.0

    /** How rectangles are divided among children; [TreemapTiling.Resquarify] keeps cells in place as values animate. */
    var tiling: TreemapTiling = TreemapTiling.Squarify()

    /** Pixels between siblings, around children inside a branch, and at the top of a branch for its name. */
    var paddingInner = 1.0
    var paddingOuter = 2.0
    var headerHeight = 17.0

    /** Sort siblings largest first, which squarified layouts read best with; false keeps the tree's order. */
    var sort = true

    /** Levels opened below the focus at most. */
    var maxDepth = Int.MAX_VALUE

    /** A cell narrower or shorter than this many screen pixels is drawn but not opened. */
    var minCellSize = 4.0

    /** The most cells one display draws; the largest are opened first. */
    var maxCells = 2500

    /** Seconds each change morphs over. 0 redraws at once, which is what a `loop { }` that redraws every frame wants. */
    var transition = 0.45

    /** How cells are coloured; see [TreemapColors]. */
    var colors: TreemapColors = TreemapColors.byBranch()

    /** The text on a cell. */
    var label: (TreemapNode) -> String = { it.name }

    /** How a value is written on a cell and in its tooltip; null leaves values off cells. */
    var formatValue: ((Double) -> String)? = ::compactNumber

    /** Label size in screen pixels. */
    var labelSize = 11.0

    /** The colour behind the cells, showing through the padding. */
    var background: Vec4 = vec(0.075, 0.08, 0.095, 1.0)

    /** Show the path from the root to the focus as clickable breadcrumbs above the cells. */
    var breadcrumbs = true

    /** Whether clicks select. With [multiSelect], Shift-click extends and Cmd/Ctrl-click toggles. */
    var selectable = true
    var multiSelect = true

    /** Called with the whole selection whenever it changes. */
    var onSelect: ((List<TreemapNode>) -> Unit)? = null

    /** Called for every click on a cell, before selection or zoom, with the node and the click itself. */
    var onClick: ((TreemapNode, FLineIntersection) -> Unit)? = null

    /** Called when the pointer moves onto a cell, and with null when it leaves the treemap. */
    var onHover: ((TreemapNode?) -> Unit)? = null

    /** Called after the focus changes. */
    var onZoom: ((TreemapNode) -> Unit)? = null

    /**
     * A channel to describe the hovered (or else the selected) node in, as Markdown: its path, value, share of its
     * parent and of the focus. Null for none.
     */
    var infoChannel: String? = null

    /** What [infoChannel] says about a node; the default is its path, value and shares. */
    var describe: (TreemapNode) -> String = ::defaultDescription

    /** The node the treemap is zoomed into; the root unless zoomed. */
    val focus: TreemapNode
        get() = memory.focusKey?.let { root.find(it) } ?: root

    /** The selected nodes, in the order they were selected; nodes no longer in the tree drop out. */
    val selection: List<TreemapNode>
        get() = memory.selected.mapNotNull { root.find(it) }

    /** The node under the pointer, if any. */
    var hovered: TreemapNode? = null
        private set

    /** The cells of the latest display, parents before children, in layout coordinates. */
    var cells: List<TreemapCell> = emptyList()
        private set

    private val lock = Any()

    // ---- changes -------------------------------------------------------------------------------------------

    /** Zoom into [node] (a branch), morphing its rectangle out to fill the treemap. */
    fun zoomTo(node: TreemapNode) {
        val target = if (node.isLeaf) node.parent ?: node else node
        synchronized(lock) {
            memory.focusKey = if (target === root) null else target.key
            memory.view = null
        }
        show()
        onZoom?.invoke(focus)
    }

    /** Zoom into the node whose key is [key]. */
    fun zoomTo(key: String) { root.find(key)?.let { zoomTo(it) } }

    /** Zoom out one level; nothing at the root. */
    fun zoomOut() { focus.parent?.let { zoomTo(it) } }

    /** Zoom all the way out. */
    fun zoomToRoot() = zoomTo(root)

    /** Make [nodes] the selection; [extend] adds them to it instead, and [toggle] flips each one. */
    fun select(vararg nodes: TreemapNode, extend: Boolean = false, toggle: Boolean = false) {
        synchronized(lock) {
            if (!extend && !toggle) memory.selected.clear()
            for (n in nodes) {
                if (toggle && n.key in memory.selected) memory.selected.remove(n.key)
                else memory.selected.add(n.key)
            }
        }
        show()
        onSelect?.invoke(selection)
        describeInfo()
    }

    /** Select the nodes whose keys are [keys]. */
    fun select(keys: Collection<String>) = select(*keys.mapNotNull { root.find(it) }.toTypedArray())

    /** Empty the selection. */
    fun clearSelection() {
        if (memory.selected.isEmpty()) return
        synchronized(lock) { memory.selected.clear() }
        show()
        onSelect?.invoke(emptyList())
        describeInfo()
    }

    /**
     * Change the tree (or anything else) in [block] and redraw with a morph. Values are summed again, so editing
     * weights in place, or adding and removing nodes, all animate:
     *
     * ```kotlin
     * map.update { root.find("budget/food")?.weight = 750.0 }
     * ```
     */
    fun update(block: Treemap.() -> Unit = {}) {
        synchronized(lock) { block() }
        show()
    }

    /** Redraw now, morphing over [transition] seconds. */
    fun show() = draw(transition)

    private fun draw(seconds: Double) {
        val lines = synchronized(lock) { toFLines() }
        val box = this.box
        RunScope.withCurrent(RunScope.currentFor(box)) {
            box.lines(lines, channel = channel, w = w, h = h, transition = seconds, onView = ::viewChanged)
        }
    }

    // ---- drawing -------------------------------------------------------------------------------------------

    private val crumbHeight get() = if (breadcrumbs) max(20.0, labelSize + 9.0) else 0.0

    /**
     * The drawing as [FLine]s: a background, the breadcrumbs, one filled rectangle per cell with its caption, and
     * an outline per selected cell. Each line is keyed by its node, so successive drawings morph into each other.
     */
    fun toFLines(): List<FLine> {
        val view = memory.view
        val ppu = view?.pixelsPerUnit?.takeIf { it > 0.0 && it.isFinite() } ?: 1.0
        val px = 1.0 / ppu  // one screen pixel in layout units
        val focus = this.focus
        val top = crumbHeight
        val options = TreemapLayoutOptions(
            tiling = tiling,
            paddingInner = paddingInner * px,
            paddingOuter = paddingOuter * px,
            paddingTop = headerHeight * px,
            headerMinSize = (headerHeight * 2.4) * px,
            rootHeader = !breadcrumbs,
            sort = sort,
            maxDepth = maxDepth,
            minCellSize = minCellSize,
            maxCells = maxCells,
        )
        val visible = view?.takeIf { it.manual }?.let { TreemapCell(focus, it.x, it.y, it.x + it.w, it.y + it.h, 0, false) }
        root.sum()
        cells = layoutTreemap(focus, 0.0, top, w, h, options, ppu, visible, memory.rows)
        val selected = memory.selected.toHashSet()
        val face = labelTypeface(labelSize)
        val faceBold = labelTypeface(labelSize, bold = true)
        val out = ArrayList<FLine>(cells.size + 8)

        out.add(rectLine(0.0, 0.0, w, h, background).also {
            it.key = "treemap:background"
            it.pickDetail = false
            it.onClick = { hit -> if (hit.alt) zoomOut() else if (selectable && !hit.shift && !hit.meta) clearSelection() }
            if (onHover != null || infoChannel != null) it.onHover = { e -> if (e.entered) hoverChanged(null) }
        })
        if (breadcrumbs) addBreadcrumbs(out, focus, top, face, faceBold, px)

        val wantsHover = onHover != null || infoChannel != null
        for (cell in cells) {
            if (cell.w <= 0.0 || cell.h <= 0.0) continue
            val node = cell.node
            val fill = colors.colorOf(node, this)
            val line = rectLine(cell.x0, cell.y0, cell.w, cell.h, fill)
            line.key = node.key
            line.label = node.name
            line.pickDetail = false
            line.hoverColor = lighten(fill, if (cell.open) 0.05 else 0.12)
            line.tooltip = tooltipFor(node)
            line.onClick = { hit -> clicked(node, hit) }
            if (wantsHover) line.onHover = { e -> if (e.entered) hoverChanged(node) else if (hovered === node) hoverChanged(null) }
            line.caption = captionFor(cell, fill, face, faceBold, px)
            out.add(line)
        }

        // Selection outlines go over everything, keyed apart from their cells so they fade in and out on their own.
        for (cell in cells) {
            if (cell.node.key !in selected || cell.w <= 0.0 || cell.h <= 0.0) continue
            val inset = px
            out.add(rectLine(cell.x0 + inset, cell.y0 + inset, cell.w - 2 * inset, cell.h - 2 * inset, SELECTION).also {
                it.filled = false
                it.stroked = true
                it.thickness = 2.0
                it.key = "treemap:selected:${cell.node.key}"
            })
        }
        return out
    }

    private fun addBreadcrumbs(out: MutableList<FLine>, focus: TreemapNode, top: Double, face: afield.graphics.text.Typeface?, faceBold: afield.graphics.text.Typeface?, px: Double) {
        var x = 0.0
        val path = focus.path
        path.forEachIndexed { i, node ->
            val last = i == path.lastIndex
            val text = if (last) label(node) + (formatValue?.let { "  " + it(node.value) } ?: "") else label(node)
            val tw = measure(text, if (last) faceBold else face) * px
            val cw = tw + 14 * px
            val fill = if (last) vec(0.2, 0.22, 0.26, 1.0) else vec(0.14, 0.15, 0.18, 1.0)
            out.add(rectLine(x, 0.0, cw, top - 2 * px, fill).also {
                it.key = "treemap:crumb:$i"
                it.hoverColor = lighten(fill, 0.08)
                it.tooltip = if (last) "${node.key}" else "Zoom out to ${node.key}"
                it.pickDetail = false
                it.onClick = { _ -> zoomTo(node) }
                it.caption = LineCaption(text, x + 7 * px, (top - 2 * px) / 2, labelSize * px, TEXT_LIGHT, baseline = "middle", bold = last)
            })
            x += cw + 2 * px
        }
    }

    private fun captionFor(cell: TreemapCell, fill: Vec4, face: afield.graphics.text.Typeface?, faceBold: afield.graphics.text.Typeface?, px: Double): LineCaption? {
        val sw = cell.w / px  // on-screen size
        val sh = cell.h / px
        val name = label(cell.node)
        val value = formatValue?.invoke(cell.node.value)
        val ink = if (wcagContrast(TEXT_LIGHT, fill) >= wcagContrast(TEXT_DARK, fill)) TEXT_LIGHT else TEXT_DARK
        val pad = 4.0
        if (sh < labelSize + 4 || sw < labelSize * 1.5) return null
        // An open branch is labelled only where the layout gave it a header to hold the label; breadcrumbs name the focus.
        if (cell.open && (sw < headerHeight * 2.4 || sh < headerHeight * 2.4)) return null
        if (cell.depth == 0 && breadcrumbs && !cell.node.isLeaf) return null
        val font = if (cell.open) faceBold else face
        val room = sw - 2 * pad
        val full = if (value != null) "$name  $value" else name
        val text = when {
            measure(full, font) <= room -> full
            measure(name, font) <= room -> name
            else -> ellipsize(name, font, room) ?: return null
        }
        // Fade a label in over the few pixels above where it first fits, so detail arriving with zoom is not a pop.
        val slack = min(room - measure(text, font), sh - labelSize - 4)
        val opacity = (0.35 + slack / 12.0).coerceIn(0.35, 1.0)
        return LineCaption(text, cell.x0 + pad * px, cell.y0 + 3 * px, labelSize * px, ink, bold = cell.open, opacity = opacity)
    }

    private fun tooltipFor(node: TreemapNode): String {
        val value = formatValue?.invoke(node.value) ?: node.value.toString()
        val share = node.parent?.takeIf { it.value > 0.0 }?.let { "  (${percent(node.value / it.value)} of ${label(it)})" } ?: ""
        return "${node.path.joinToString(" › ") { label(it) }}\n$value$share"
    }

    // ---- interaction ---------------------------------------------------------------------------------------

    private fun clicked(node: TreemapNode, hit: FLineIntersection) {
        onClick?.invoke(node, hit)
        when {
            hit.alt -> zoomOut()
            hit.clickCount >= 2 -> {
                // Zoom one level down, toward what was double-clicked.
                val focus = this.focus
                val step = node.path.getOrNull(focus.depth + 1)
                if (step != null && !step.isLeaf) zoomTo(step)
            }
            selectable -> select(node, extend = multiSelect && hit.shift, toggle = multiSelect && hit.meta)
        }
    }

    private fun hoverChanged(node: TreemapNode?) {
        if (hovered === node) return
        hovered = node
        onHover?.invoke(node)
        describeInfo()
    }

    private fun describeInfo() {
        val ch = infoChannel ?: return
        val subject = hovered ?: selection.lastOrNull()
        RunScope.withCurrent(RunScope.currentFor(box)) {
            box.markdown(ch, subject?.let(describe) ?: "*Point at a cell.*", open = true)
        }
    }

    private fun viewChanged(v: LinesViewEvent) {
        val previous = memory.view
        synchronized(lock) { memory.view = if (v.manual) v else null }
        // Only a change of scale or a pan past what is laid out changes the picture.
        if (previous == null && !v.manual) return
        draw(min(transition, 0.25))
    }

    private fun defaultDescription(node: TreemapNode): String {
        val value = formatValue?.invoke(node.value) ?: node.value.toString()
        val sb = StringBuilder()
        sb.append("**").append(node.path.joinToString(" › ") { label(it) }).append("**\n\n")
        sb.append("| | |\n|---|---|\n")
        sb.append("| value | ").append(value).append(" |\n")
        node.parent?.takeIf { it.value > 0 }?.let { sb.append("| of ").append(label(it)).append(" | ").append(percent(node.value / it.value)).append(" |\n") }
        if (focus !== node && focus !== node.parent && focus.contains(node) && focus.value > 0) sb.append("| of ").append(label(focus)).append(" | ").append(percent(node.value / focus.value)).append(" |\n")
        if (!node.isLeaf) sb.append("| children | ").append(node.children.size).append(" |\n| leaves | ").append(node.leaves().count()).append(" |\n")
        return sb.toString()
    }

    companion object {
        private val SELECTION = vec(1.0, 0.86, 0.35, 1.0)
        private val TEXT_LIGHT = vec(0.96, 0.97, 0.98, 1.0)
        private val TEXT_DARK = vec(0.06, 0.07, 0.08, 1.0)
    }
}

/** What a treemap keeps across redraws and box runs: plain keys only, so it never pins a box's classes. */
internal class TreemapMemory {
    var focusKey: String? = null
    val selected = LinkedHashSet<String>()
    val rows = TreemapRows.create()
    var view: LinesViewEvent? = null
}

private val memories = ConcurrentHashMap<String, TreemapMemory>()

/**
 * Draw [root] as a treemap in [channel] and return it, configured by [configure] before the first draw.
 *
 * Running the box again keeps the treemap's focus, selection and stable rows, and morphs from the previous drawing
 * to the new one. Call methods on the returned [Treemap] (or set its properties and call [Treemap.show]) to change
 * it later, from callbacks, sliders or a `loop { }`.
 */
fun Box.treemap(
    root: TreemapNode,
    channel: String = "treemap",
    w: Double = 900.0,
    h: Double = 560.0,
    configure: Treemap.() -> Unit = {},
): Treemap {
    val memory = memories.computeIfAbsent("$id/$channel") { TreemapMemory() }
    val map = Treemap(this, channel, root, memory)
    map.w = w
    map.h = h
    map.configure()
    map.show()
    return map
}

/** Forget a treemap's focus, selection and rows, so its next draw starts afresh. */
fun Box.resetTreemap(channel: String = "treemap") {
    memories.remove("$id/$channel")
}

// ---- colour --------------------------------------------------------------------------------------------------

/** How a [Treemap] colours a cell. [TreemapNode.color], on a node or its ancestors, always wins. */
fun interface TreemapColors {
    fun color(node: TreemapNode, treemap: Treemap): Vec4

    /** [color], unless the node or an ancestor sets its own. */
    fun colorOf(node: TreemapNode, treemap: Treemap): Vec4 =
        generateSequence(node) { it.parent }.firstNotNullOfOrNull { it.color } ?: color(node, treemap)

    companion object {
        /**
         * A hue per top-level branch, so a branch keeps its colour however far you zoom into it, lighter with depth,
         * with neighbouring leaves nudged apart in lightness so they read as separate cells.
         */
        fun byBranch(saturation: Double = 0.55, lightness: Double = 0.40, depthStep: Double = 0.07): TreemapColors = TreemapColors { node, map ->
            val path = node.path
            val top = path.getOrNull(1) ?: return@TreemapColors okhsl(0.6, 0.12, lightness)
            val siblings = map.root.children
            val i = siblings.indexOf(top).coerceAtLeast(0)
            // Golden-angle hues keep any two branches apart, however many there are.
            val hue = (i * 0.381966) % 1.0
            val jitter = if (node.isLeaf) ((node.key.hashCode() and 0xff) / 255.0 - 0.5) * 0.06 else 0.0
            val l = (lightness + depthStep * (path.size - 2) + jitter).coerceIn(0.2, 0.86)
            okhsl(hue, saturation, l)
        }

        /** Every cell by its depth below the root, through colormap [name] ([Colormaps]). */
        fun byDepth(name: String = "viridis"): TreemapColors = TreemapColors { node, map ->
            val height = max(1, map.root.walk().maxOf { it.depth })
            Colormaps.sample(name, node.depth.toDouble() / height).let { vec(it.r, it.g, it.b, 1.0) }
        }

        /**
         * Leaves by [measure] (by default their value) through colormap [name], scaled over all the leaves, on a log
         * scale when [log]; branches a neutral grey so the leaves carry the colour.
         */
        fun byValue(name: String = "viridis", log: Boolean = false, measure: (TreemapNode) -> Double = { it.value }): TreemapColors {
            // The range is measured once per drawing: [Treemap.cells] is a new list each time.
            var cachedFor: Any? = null
            var lo = 0.0
            var hi = 1.0
            fun f(v: Double) = if (log) ln(max(v, 1e-12)) else v
            return TreemapColors { node, map ->
                if (!node.isLeaf) return@TreemapColors okhsl(0.6, 0.05, 0.22)
                if (cachedFor !== map.cells) {
                    val values = map.root.leaves().map { f(measure(it)) }.filter { it.isFinite() }.toList()
                    lo = values.minOrNull() ?: 0.0
                    hi = values.maxOrNull() ?: 1.0
                    cachedFor = map.cells
                }
                val t = if (hi > lo) (f(measure(node)) - lo) / (hi - lo) else 0.5
                Colormaps.sample(name, t).let { vec(it.r, it.g, it.b, 1.0) }
            }
        }

        /** Each cell by whatever [pick] returns for it. */
        fun by(pick: (TreemapNode) -> Vec4): TreemapColors = TreemapColors { node, _ -> pick(node) }
    }
}

// ---- helpers -------------------------------------------------------------------------------------------------

private fun rectLine(x: Double, y: Double, w: Double, h: Double, c: Vec4): FLine = FLine().apply {
    moveTo(x, y); lineTo(x + w, y); lineTo(x + w, y + h); lineTo(x, y + h); close()
    filled = true
    stroked = false
    color = c
}

private fun lighten(c: Vec4, amount: Double): Vec4 =
    vec(c.x + (1 - c.x) * amount, c.y + (1 - c.y) * amount, c.z + (1 - c.z) * amount, c.w.toDouble())

private val typefaces = ConcurrentHashMap<Pair<Double, Boolean>, Any>()
private object NoFace

private fun labelTypeface(size: Double, bold: Boolean = false): afield.graphics.text.Typeface? {
    val face = typefaces.computeIfAbsent(size to bold) { runCatching { typeface(Font.SANS_SERIF, size, bold = bold) }.getOrNull() ?: NoFace }
    return face as? afield.graphics.text.Typeface
}

/** Width of [text] in pixels in [face], or an estimate when no font is available (a headless box). */
private fun measure(text: String, face: afield.graphics.text.Typeface?): Double =
    face?.let { f -> runCatching { textWidth(text, f) }.getOrNull() } ?: (text.length * 6.2)

private fun ellipsize(text: String, face: afield.graphics.text.Typeface?, room: Double): String? {
    if (text.length < 2) return null
    var lo = 1
    var hi = text.length - 1
    var best: String? = null
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        val candidate = text.take(mid).trimEnd() + "…"
        if (measure(candidate, face) <= room) { best = candidate; lo = mid + 1 } else hi = mid - 1
    }
    return best?.takeIf { it.length >= 3 }
}

private fun percent(f: Double) = if (f >= 0.1) "${(f * 100).toInt()}%" else String.format(java.util.Locale.US, "%.1f%%", f * 100)

/** 1234 → `1.2k`, 5_600_000 → `5.6M`: short enough to sit on a small cell. */
fun compactNumber(v: Double): String {
    val a = abs(v)
    fun f(x: Double, suffix: String): String {
        val text = (if (abs(x) >= 100) "%.0f" else if (abs(x) >= 10) "%.1f" else "%.2f").format(java.util.Locale.US, x)
        // Only a fraction loses its trailing zeros: 520 stays 520.
        return (if ('.' in text) text.trimEnd('0').trimEnd('.') else text) + suffix
    }
    return when {
        a >= 1e12 -> f(v / 1e12, "T")
        a >= 1e9 -> f(v / 1e9, "G")
        a >= 1e6 -> f(v / 1e6, "M")
        a >= 1e3 -> f(v / 1e3, "k")
        a == 0.0 -> "0"
        a < 0.01 -> String.format(java.util.Locale.US, "%.2g", v)
        else -> f(v, "")
    }
}

/**
 * A treemap shown as a value: `tree.h`, `display(channel, tree)`, or a box whose last expression is a tree draws
 * it, static, at 720×420. Use [Box.treemap] for the interactive one.
 */
fun TreemapNode.toTreemapHTML(w: Double = 720.0, h: Double = 420.0): String {
    val map = Treemap(null, "__value", this, TreemapMemory())
    map.w = w
    map.h = h
    map.breadcrumbs = false
    // Hover colour and tooltips work in the browser alone; clicks need a box to call back to.
    val lines = map.toFLines().onEach { it.onClick = null; it.onHover = null }
    return linesToHTML(lines, w, h)
}
