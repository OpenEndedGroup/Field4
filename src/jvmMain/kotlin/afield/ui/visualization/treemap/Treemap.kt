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
    var minCellSize = 2.0

    /**
     * The most cells one display draws; the largest are opened first. The viewer draws a hundred thousand cells
     * as easily as a hundred; the budget is there so that a tree of millions still lays out in a frame.
     */
    var maxCells = 150_000

    /** Seconds each change morphs over. 0 redraws at once, which is what a `loop { }` that redraws every frame wants. */
    var transition = 0.45

    /**
     * How cells are coloured; see [TreemapColors]. Left alone, a tree whose leaves carry a [TreemapNode.category] or
     * [TreemapNode.measure] gets a "Colour by" switch between them and [TreemapColors.byBranch].
     */
    var colors: TreemapColors = TreemapColors.byBranch()
        set(v) { field = v; colorsChosen = true }

    private var colorsChosen = false

    /** Offer the colourings the leaves carry data for, unless the caller chose colours of their own. */
    internal fun applyAutoColorings() {
        if (colorsChosen || colorings.isNotEmpty()) return
        val auto = autoColorings(root)
        if (auto.isNotEmpty()) colorings = auto
    }

    /**
     * Named colourings the viewer offers as a "Colour by" switch, in order. Choosing one makes it [colors] and
     * redraws: only the colours change, so only they are sent, and they morph.
     */
    var colorings: Map<String, TreemapColors> = emptyMap()
        set(v) { field = v; if (coloring == null || coloring !in v) v.entries.firstOrNull()?.let { coloring = it.key; colors = it.value } }

    /** The name of the [colorings] entry in use, if any. */
    var coloring: String? = null
        private set

    /** Use the colouring called [name] from [colorings], and redraw. */
    fun colorBy(name: String) {
        val chosen = colorings[name] ?: return
        synchronized(lock) { coloring = name; colors = chosen }
        show()
    }

    /** The text on a cell. */
    var label: (TreemapNode) -> String = { it.name }

    /** How a value is written on a cell and in its tooltip; null leaves values off cells. */
    var formatValue: ((Double) -> String)? = ::compactNumber

    /** Label size in screen pixels. */
    var labelSize = 11.0

    /**
     * How branches are named: [TreemapBranchLabels.HEADER] gives each a strip along its top, with room made for it
     * in the layout; [TreemapBranchLabels.BADGE] puts the name in a dark badge over the branch's own cells, which
     * keeps a dense map dense.
     */
    var branchLabels = TreemapBranchLabels.HEADER

    /** The line under the treemap saying what the mouse does; null keeps the viewer's own. */
    var hint: String? = null

    /** A leaf is labelled only when it is at least this many screen pixels wide; zoom in to read smaller ones. */
    var leafLabelMinWidth = 26.0

    /** The CSS font family the viewer writes labels, breadcrumbs and the legend in. */
    var labelFont = "system-ui, -apple-system, \"Segoe UI\", sans-serif"

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

    /** The viewer this treemap draws into; null for a treemap shown as a value. */
    internal var view: TreemapView? = null

    /** What the last frame cost to pack: cells, bytes and milliseconds. */
    val lastFrame: TreemapFrameStats? get() = view?.lastFrame

    private val lock = Any()

    // ---- changes -------------------------------------------------------------------------------------------

    /** Zoom into [node] (a branch), morphing its rectangle out to fill the treemap. */
    fun zoomTo(node: TreemapNode) {
        val target = if (node.isLeaf) node.parent ?: node else node
        synchronized(lock) {
            memory.focusKey = if (target === root) null else target.key
            memory.view = null
        }
        draw(transition, refit = true)
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

    private fun draw(seconds: Double, refit: Boolean = false) {
        val view = this.view ?: return
        val began = System.nanoTime()
        val frame = synchronized(lock) { packFrame(seconds, refit) }
        view.lastFrame = TreemapFrameStats(frame.cells.size, frame.description.size, frame.buffers.bytes, (System.nanoTime() - began) / 1e6)
        view.publish(frame)
    }

    /** Lay out the focus in the drawing below [top], with padding in screen pixels at the viewer's current zoom. */
    private fun layoutCells(top: Double, rootHeader: Boolean): List<TreemapCell> {
        val view = memory.view
        val ppu = view?.pixelsPerUnit?.takeIf { it > 0.0 && it.isFinite() } ?: 1.0
        val px = 1.0 / ppu
        val focus = this.focus
        val options = TreemapLayoutOptions(
            tiling = tiling,
            paddingInner = paddingInner * px,
            paddingOuter = paddingOuter * px,
            paddingTop = if (branchLabels == TreemapBranchLabels.HEADER) headerHeight * px else 0.0,
            headerMinSize = headerMinSize(px),
            rootHeader = rootHeader,
            sort = sort,
            maxDepth = maxDepth,
            minCellSize = minCellSize,
            maxCells = maxCells,
        )
        val visible = view?.takeIf { it.manual }?.let { TreemapCell(focus, it.x, it.y, it.x + it.w, it.y + it.h, 0, false) }
        root.sum()
        return layoutTreemap(focus, 0.0, top, w, h, options, ppu, visible, memory.rows).also { cells = it }
    }

    private fun headerMinSize(px: Double) = headerHeight * 2.4 * px

    /** The current layout packed for the viewer, morphing in over [seconds]. */
    private fun packFrame(seconds: Double, refit: Boolean): TreemapPublished {
        val laid = layoutCells(0.0, rootHeader = false)
        val ppu = memory.view?.pixelsPerUnit?.takeIf { it > 0.0 && it.isFinite() } ?: 1.0
        val headerMin = headerMinSize(1.0 / ppu)
        val selected = memory.selected.toHashSet()
        val focus = this.focus
        val legend = colors.legend(this)
        return packTreemapFrame(
            laid, newGeneration(),
            colorOf = { colors.colorOf(it.node, this) },
            idOf = { node -> memory.ids.getOrPut(node.key) { memory.ids.size + 1 } },
            flagsOf = { c ->
                var f = 0
                if (c.open) f = f or CELL_OPEN
                if (c.node.key in selected) f = f or CELL_SELECTED
                if (c.open && c.depth > 0 && c.w >= headerMin && c.h >= headerMin) f = f or CELL_HEADER
                if (c.node.isLeaf) f = f or CELL_LEAF
                f
            },
            nameOf = { node -> label(node) + (formatValue?.let { "\t" + it(node.value) } ?: "") },
        ) { table ->
            TreemapFrameMessage(
                uid = view!!.uid, generation = table.generation, count = laid.size, arrays = FRAME_ARRAYS, buffers = table,
                width = w, height = h, background = rgbaBytes(background), labelSize = labelSize, headerHeight = headerHeight, ppu = ppu,
                transition = seconds, refit = refit,
                crumbs = if (breadcrumbs) focus.path.map { TreemapCrumb(label(it), it.value, memory.ids.getOrPut(it.key) { memory.ids.size + 1 }) } else emptyList(),
                legend = legend?.entries.orEmpty().map { TreemapLegendRow(it.label, rgbaBytes(it.color), it.share) },
                legendTitle = legend?.title,
                colorings = colorings.keys.toList(), coloring = coloring,
                selectionRgba = rgbaBytes(SELECTION),
                valueFormat = if (formatValue != null) "tab" else "none",
                branchLabels = branchLabels.name.lowercase(),
                leafLabelMinWidth = leafLabelMinWidth,
                hint = hint,
                font = labelFont,
            )
        }
    }

    /** A message from the viewer: a click, a hover, a camera move, a breadcrumb or a colouring. */
    internal fun receiveFromViewer(view: TreemapView, type: String, message: kotlinx.serialization.json.JsonObject) {
        fun cellAt(): TreemapNode? {
            val cells = message.string("generation")?.let { view.cells(it) } ?: return null
            return message.int("index")?.let { cells.getOrNull(it)?.node }
        }
        when (type) {
            "click" -> {
                val hit = FLineIntersection(-1, vec(message.double("x") ?: 0.0, message.double("y") ?: 0.0, 0.0),
                    shift = message.bool("shift"), alt = message.bool("alt"), meta = message.bool("meta"),
                    clickCount = message.int("clickCount") ?: 1)
                val node = cellAt()
                if (node == null) {
                    if (hit.alt) zoomOut() else if (selectable && !hit.shift && !hit.meta) clearSelection()
                } else clicked(node, hit.copy(key = node.key, label = node.name))
            }
            "hover" -> hoverChanged(cellAt())
            "view" -> viewChanged(LinesViewEvent(
                message.double("x") ?: 0.0, message.double("y") ?: 0.0, message.double("w") ?: w, message.double("h") ?: h,
                message.double("scale") ?: 1.0, message.bool("manual")))
            "crumb" -> {
                val id = message.int("id") ?: return
                focus.path.firstOrNull { memory.ids[it.key] == id }?.let { zoomTo(it) }
            }
            "coloring" -> message.string("name")?.let { colorBy(it) }
            "zoom-out" -> zoomOut()
        }
    }

    // ---- drawing -------------------------------------------------------------------------------------------

    private val crumbHeight get() = if (breadcrumbs) max(20.0, labelSize + 9.0) else 0.0

    /**
     * The drawing as [FLine]s: a background, the breadcrumbs, one filled rectangle per cell with its caption, and
     * an outline per selected cell. Each line is keyed by its node, so successive drawings morph into each other.
     */
    fun toFLines(): List<FLine> {
        val ppu = memory.view?.pixelsPerUnit?.takeIf { it > 0.0 && it.isFinite() } ?: 1.0
        val px = 1.0 / ppu  // one screen pixel in layout units
        val focus = this.focus
        val top = crumbHeight
        val cells = layoutCells(top, rootHeader = !breadcrumbs)
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
        synchronized(lock) { memory.view = v }
        // Redraw for a pan or zoom by hand, or for a fitted scale the layout was not made for; not for a fit it already has.
        val rescaled = previous == null || abs(ln(previous.pixelsPerUnit / v.pixelsPerUnit)) > 0.05
        // The first fit only corrects the layout for the scale it is really shown at: no one should see that move.
        val seconds = if (previous == null && !v.manual) 0.0 else min(transition, 0.25)
        if (v.manual || previous?.manual == true || rescaled) draw(seconds)
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

/** How a [Treemap] names its branches. */
enum class TreemapBranchLabels { HEADER, BADGE }

/** What a treemap keeps across redraws and box runs: plain keys only, so it never pins a box's classes. */
internal class TreemapMemory {
    var focusKey: String? = null
    val selected = LinkedHashSet<String>()
    val rows = TreemapRows.create()
    var view: LinesViewEvent? = null
    /** Each node's stable id in the viewer, by key, so the same node morphs from frame to frame and run to run. */
    val ids = HashMap<String, Int>()
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
    map.applyAutoColorings()
    val view = treemapViewFor(treemapViewerUid(this, channel))
    view.treemap = map
    map.view = view
    // The viewer fits the drawing to its width; the strip above holds the breadcrumbs, the colouring switch and the legend.
    val frameW = map.w.toInt()
    val frameH = (map.h + TREEMAP_CHROME_PX).toInt()
    display(channel, "<iframe src='${treemapViewerSource(view.uid)}' data-afield-intrinsic-width='$frameW' data-afield-intrinsic-height='$frameH' " +
        "style='border:none;padding:0;margin:0;display:block;width:${frameW}px;max-width:100%;height:${frameH}px'></iframe>")
    map.show()
    return map
}

/** Room above the drawing for the viewer's breadcrumbs, colouring switch and legend. */
private const val TREEMAP_CHROME_PX = 72.0

internal fun treemapViewerUid(box: Box, channel: String): String =
    "${afield.graphics.three.threeDocumentScope(box)}_${box.id}_$channel"

/** Forget a treemap's focus, selection and rows, so its next draw starts afresh. */
fun Box.resetTreemap(channel: String = "treemap") {
    memories.remove("$id/$channel")
}

// ---- colour --------------------------------------------------------------------------------------------------

/** How a [Treemap] colours a cell. [TreemapNode.color], on a node or its ancestors, always wins. */
fun interface TreemapColors {
    fun color(node: TreemapNode, treemap: Treemap): Vec4

    /** What the colours mean, for the viewer to show above the treemap; null for no legend. */
    fun legend(treemap: Treemap): TreemapLegend? = null

    /** [color], unless the node or an ancestor sets its own. */
    fun colorOf(node: TreemapNode, treemap: Treemap): Vec4 =
        generateSequence(node) { it.parent }.firstNotNullOfOrNull { it.color } ?: color(node, treemap)

    companion object {
        /**
         * A hue per top-level branch, so a branch keeps its colour however far you zoom into it, lighter with depth,
         * with neighbouring leaves nudged apart in lightness so they read as separate cells.
         */
        fun byBranch(saturation: Double = 0.55, lightness: Double = 0.40, depthStep: Double = 0.07): TreemapColors {
            fun hue(i: Int) = (i * 0.381966) % 1.0  // golden-angle hues keep any two branches apart, however many
            return colors(
                legend = { map ->
                    val total = map.focus.value.takeIf { it > 0 } ?: 1.0
                    val top = map.root.children.withIndex().sortedByDescending { it.value.value }.take(12)
                    // Zoomed into a branch, the legend lists only the branches the focus shows.
                    val shown = top.map { (i, n) -> TreemapLegendEntry(map.label(n), okhsl(hue(i), saturation, lightness + depthStep), shareWithin(map.focus, n, total)) }
                    TreemapLegend(null, shown.filter { (it.share ?: 0.0) > 0.0 })
                },
            ) { node, map ->
                val path = node.path
                val top = path.getOrNull(1) ?: return@colors okhsl(0.6, 0.12, lightness)
                val i = map.root.children.indexOf(top).coerceAtLeast(0)
                val jitter = if (node.isLeaf) ((node.name.hashCode() * 31 + node.depth and 0xff) / 255.0 - 0.5) * 0.06 else 0.0
                val l = (lightness + depthStep * (path.size - 2) + jitter).coerceIn(0.2, 0.86)
                okhsl(hue(i), saturation, l)
            }
        }

        /**
         * Leaves by the category [classify] puts them in, one colour per category, with a legend of each category's
         * share of the focus. Categories are coloured in order of their share of the whole tree, so the biggest get
         * the most distinct hues and a category keeps its colour while you zoom. Branches, and leaves [classify]
         * returns null for, are grey and listed as "Not classified". By default a leaf's category is its
         * [TreemapNode.category].
         *
         * ```kotlin
         * colors = TreemapColors.byCategory("Extension") { node -> (node.data as? File)?.extension }
         * ```
         */
        fun byCategory(title: String? = null, classify: (TreemapNode) -> String? = { it.category }): TreemapColors {
            val unclassified = okhsl(0.6, 0.04, 0.36)
            var cachedFor: TreemapNode? = null
            var palette: Map<String, Vec4> = emptyMap()
            fun paletteFor(map: Treemap): Map<String, Vec4> {
                if (cachedFor === map.root) return palette
                val totals = HashMap<String, Double>()
                map.root.leaves().forEach { leaf -> classify(leaf)?.let { totals.merge(it, leaf.value, Double::plus) } }
                val order = totals.entries.sortedByDescending { it.value }.map { it.key }
                val colours = if (order.isEmpty()) emptyList() else afield.graphics.color.paletteForBackground(map.background, order.size, saturation = 0.62, lightness = 0.58)
                palette = order.zip(colours).toMap()
                cachedFor = map.root
                return palette
            }
            return colors(
                legend = { map ->
                    val p = paletteFor(map)
                    val totals = LinkedHashMap<String?, Double>()
                    map.focus.leaves().forEach { leaf -> totals.merge(classify(leaf)?.takeIf { it in p }, leaf.value, Double::plus) }
                    val whole = totals.values.sum().takeIf { it > 0 } ?: 1.0
                    val rows = p.keys.filter { it in totals }.map { TreemapLegendEntry(it, p.getValue(it), totals.getValue(it) / whole) }
                    TreemapLegend(title, rows + (totals[null]?.let { listOf(TreemapLegendEntry("Not classified", unclassified, it / whole)) } ?: emptyList()))
                },
            ) { node, map ->
                if (!node.isLeaf) okhsl(0.6, 0.05, 0.22) else classify(node)?.let { paletteFor(map)[it] } ?: unclassified
            }
        }

        /** Every cell by its depth below the root, through colormap [name] ([Colormaps]). */
        fun byDepth(name: String = "viridis"): TreemapColors {
            // The tree's height is measured once per drawing: [Treemap.cells] is a new list each time.
            var cachedFor: Any? = null
            var height = 1
            return TreemapColors { node, map ->
                if (cachedFor !== map.cells) {
                    height = max(1, map.root.walk().maxOf { it.depth })
                    cachedFor = map.cells
                }
                Colormaps.sample(name, node.depth.toDouble() / height).let { vec(it.r, it.g, it.b, 1.0) }
            }
        }

        /**
         * Leaves by [measure] (by default their [TreemapNode.measure], else their value) through colormap [name], scaled over all the leaves, on a log
         * scale when [log]; branches a neutral grey so the leaves carry the colour.
         */
        fun byValue(name: String = "viridis", log: Boolean = false, measure: (TreemapNode) -> Double = { it.measure ?: it.value }): TreemapColors {
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
            }.withLegend { map ->
                val format = map.formatValue ?: ::compactNumber
                fun back(v: Double) = if (log) kotlin.math.exp(v) else v
                TreemapLegend(null, (0..4).map { k ->
                    val t = k / 4.0
                    TreemapLegendEntry(format(back(lo + (hi - lo) * t)), Colormaps.sample(name, t).let { vec(it.r, it.g, it.b, 1.0) })
                })
            }
        }

        /** A [TreemapColors] from [color], with [legend]. */
        fun colors(legend: (Treemap) -> TreemapLegend?, color: (TreemapNode, Treemap) -> Vec4): TreemapColors = object : TreemapColors {
            override fun color(node: TreemapNode, treemap: Treemap) = color(node, treemap)
            override fun legend(treemap: Treemap) = legend(treemap)
        }

        /** Each cell by whatever [pick] returns for it. */
        fun by(pick: (TreemapNode) -> Vec4): TreemapColors = TreemapColors { node, _ -> pick(node) }
    }
}

/**
 * The colourings a tree's own leaves carry data for: "Category" when any leaf has a [TreemapNode.category],
 * "Measure" when any has a [TreemapNode.measure], then "Branch"; empty when they carry neither.
 */
internal fun autoColorings(root: TreemapNode): Map<String, TreemapColors> {
    var category = false
    var measure = false
    for (leaf in root.leaves()) {
        category = category || leaf.category != null
        measure = measure || leaf.measure != null
        if (category && measure) break
    }
    if (!category && !measure) return emptyMap()
    return buildMap {
        if (category) put("Category", TreemapColors.byCategory())
        if (measure) put("Measure", TreemapColors.byValue())
        put("Branch", TreemapColors.byBranch())
    }
}

/** [this] colouring with [legend] added. */
fun TreemapColors.withLegend(legend: (Treemap) -> TreemapLegend?): TreemapColors {
    val base = this
    return TreemapColors.colors(legend) { node, map -> base.color(node, map) }
}

/** What a treemap's colours mean: rows of colour and label, each with its share of the focus (0..1) when it has one. */
data class TreemapLegend(val title: String?, val entries: List<TreemapLegendEntry>)

/** One row of a [TreemapLegend]. */
data class TreemapLegendEntry(val label: String, val color: Vec4, val share: Double? = null)

/** [node]'s share of [focus], by value: 0 when it lies outside the focus. */
private fun shareWithin(focus: TreemapNode, node: TreemapNode, total: Double): Double = when {
    focus.contains(node) -> node.value / total
    node.contains(focus) -> focus.value / total
    else -> 0.0
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
    // Drawn as SVG, so held to the budget an SVG figure can carry.
    map.maxCells = 2500
    map.minCellSize = 4.0
    map.applyAutoColorings()
    // Hover colour and tooltips work in the browser alone; clicks need a box to call back to.
    val lines = map.toFLines().onEach { it.onClick = null; it.onHover = null }
    return linesToHTML(lines, w, h)
}
