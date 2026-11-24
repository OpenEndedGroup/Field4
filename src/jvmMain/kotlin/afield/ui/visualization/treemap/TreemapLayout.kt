/**
 * # Treemap layout
 *
 * Divides a rectangle among a [TreemapNode]'s children in proportion to their values, then recurses. The tilings
 * are the standard ones (the same algorithms as d3-hierarchy's, ported, so results can be checked against it):
 *
 * - [TreemapTiling.Squarify]: rows chosen greedily to keep cells near a target aspect ratio (Bruls, Huizing and
 *   van Wijk, 2000). The default, and the most readable.
 * - [TreemapTiling.Resquarify]: squarify once, then keep the same rows as values change, so an animated treemap
 *   grows and shrinks its cells in place instead of reshuffling them.
 * - [TreemapTiling.Binary]: recursive halving by value, balanced, alternating by the longer side.
 * - [TreemapTiling.SliceDice]: alternate vertical and horizontal strips by depth, which keeps order perfectly.
 * - [TreemapTiling.Slice] and [TreemapTiling.Dice]: every level in vertical, or horizontal, strips.
 *
 * Layout is also where level of detail is decided: [layoutTreemap] lays out only what is worth drawing, opening the
 * largest visible cells first until it reaches a cell budget, skipping cells smaller than a minimum on screen and
 * cells outside the visible rectangle.
 */
package afield.ui.visualization.treemap

import java.util.PriorityQueue
import kotlin.math.max
import kotlin.math.min

/** How a treemap divides a rectangle among children. */
sealed interface TreemapTiling {
    /** Rows chosen to keep cells near [ratio] (width to height or the reverse); the golden ratio by default. */
    data class Squarify(val ratio: Double = GOLDEN) : TreemapTiling

    /** [Squarify], but rows are kept from one layout to the next while the same children remain: stable for animation. */
    data class Resquarify(val ratio: Double = GOLDEN) : TreemapTiling

    /** Recursive halving by value. */
    data object Binary : TreemapTiling

    /** Vertical strips at even depths and horizontal ones at odd depths. */
    data object SliceDice : TreemapTiling

    /** Children stacked top to bottom. */
    data object Slice : TreemapTiling

    /** Children side by side, left to right. */
    data object Dice : TreemapTiling

    companion object {
        const val GOLDEN = 1.618033988749895
    }
}

/** A laid-out rectangle for [node], from ([x0], [y0]) to ([x1], [y1]). [open] is true when its children were laid out inside it. */
data class TreemapCell(
    val node: TreemapNode,
    val x0: Double,
    val y0: Double,
    val x1: Double,
    val y1: Double,
    /** Depth below the layout's root (the root's own cell is 0). */
    val depth: Int,
    val open: Boolean,
) {
    val w: Double get() = x1 - x0
    val h: Double get() = y1 - y0
    val area: Double get() = w * h

    fun contains(x: Double, y: Double) = x in x0..x1 && y in y0..y1
}

/**
 * Padding and level-of-detail settings for [layoutTreemap], in layout units (the display's pixels, unless zoomed).
 *
 * - [paddingOuter] insets each branch's children from its edge, and [paddingInner] separates siblings.
 * - [paddingTop] is extra room at the top of a branch for its header label, given only to branches at least
 *   [headerMinSize] in both directions, and to the root only when [rootHeader] (a treemap with breadcrumbs names
 *   its root there instead).
 * - [maxDepth] limits how many levels below the root are opened at all.
 * - [minCellSize]: a cell narrower or shorter than this many *screen* pixels is drawn but not opened.
 * - [maxCells] caps how many cells are produced. Cells are opened largest first, so the budget is spent where
 *   the eye is.
 */
data class TreemapLayoutOptions(
    val tiling: TreemapTiling = TreemapTiling.Squarify(),
    val paddingInner: Double = 1.0,
    val paddingOuter: Double = 2.0,
    val paddingTop: Double = 0.0,
    val headerMinSize: Double = 40.0,
    val rootHeader: Boolean = true,
    val sort: Boolean = true,
    val maxDepth: Int = Int.MAX_VALUE,
    val minCellSize: Double = 3.0,
    val maxCells: Int = 2500,
)

/** Squarified rows remembered between layouts for [TreemapTiling.Resquarify], keyed by branch key. */
class TreemapRows internal constructor() {
    internal class Row(val keys: List<String>, val dice: Boolean)
    internal class Entry(val ratio: Double, val childKeys: Set<String>, val rows: List<Row>)
    internal val byKey = HashMap<String, Entry>()

    /** Forget every remembered row, so the next layout squarifies afresh. */
    fun clear() = byKey.clear()

    companion object {
        fun create() = TreemapRows()
    }
}

/**
 * Lay out [root] in the rectangle ([x0], [y0])–([x1], [y1]) and return the cells worth drawing, parents before
 * children.
 *
 * [pixelsPerUnit] converts layout units to screen pixels for [TreemapLayoutOptions.minCellSize], and [visible],
 * when given, is the part of the layout on screen. Cells entirely outside it are not opened. [rows] holds the
 * remembered rows for [TreemapTiling.Resquarify]; pass the same object to each layout of one treemap.
 */
fun layoutTreemap(
    root: TreemapNode,
    x0: Double, y0: Double, x1: Double, y1: Double,
    options: TreemapLayoutOptions = TreemapLayoutOptions(),
    pixelsPerUnit: Double = 1.0,
    visible: TreemapCell? = null,
    rows: TreemapRows = TreemapRows(),
): List<TreemapCell> {
    root.sum()
    val out = ArrayList<TreemapCell>()
    // Largest open-able cell first: a budget runs out on the smallest detail, never on a big empty region.
    val frontier = PriorityQueue<TreemapCell>(compareByDescending { it.area })
    val minPx = options.minCellSize / max(pixelsPerUnit, 1e-9)
    var budget = max(1, options.maxCells) - 1

    fun openable(c: TreemapCell): Boolean {
        if (c.node.isLeaf || c.depth >= options.maxDepth) return false
        if (c.w < minPx * 2 || c.h < minPx * 2) return false
        if (visible != null && (c.x1 < visible.x0 || c.x0 > visible.x1 || c.y1 < visible.y0 || c.y0 > visible.y1)) return false
        return true
    }

    val rootCell = TreemapCell(root, x0, y0, x1, y1, 0, open = false)
    val placed = ArrayList<TreemapCell>()
    placed.add(rootCell)
    if (openable(rootCell)) frontier.add(rootCell)
    val opened = HashSet<TreemapCell>()
    // Cells by node, so the drawing order (parents first, siblings in order) can be rebuilt after the opening order.
    val childrenOf = HashMap<TreemapCell, List<TreemapCell>>()

    while (frontier.isNotEmpty()) {
        val cell = frontier.poll()
        val kids = cell.node.children.filter { it.value > 0.0 }
        if (kids.isEmpty() || kids.size > budget) continue
        val laid = tileChildren(cell, kids, options, rows)
        budget -= laid.size
        opened.add(cell)
        childrenOf[cell] = laid
        for (k in laid) if (openable(k)) frontier.add(k)
    }

    fun emit(c: TreemapCell) {
        val isOpen = c in opened
        out.add(if (isOpen) c.copy(open = true) else c)
        childrenOf[c]?.forEach { emit(it) }
    }
    // Iterative would need an explicit stack of iterators; depth is bounded by maxDepth and the budget, so recursion is fine.
    emit(rootCell)
    return out
}

private fun tileChildren(parent: TreemapCell, kids: List<TreemapNode>, o: TreemapLayoutOptions, rows: TreemapRows): List<TreemapCell> {
    val header = if (o.paddingTop > 0.0 && parent.w >= o.headerMinSize && parent.h >= o.headerMinSize && (o.rootHeader || parent.depth > 0)) o.paddingTop else 0.0
    val half = o.paddingInner / 2
    // Children are inset by the outer padding, then expanded by half the inner padding so that, after each child is
    // shrunk by half the inner padding on every side, siblings are exactly paddingInner apart (d3's arrangement).
    var x0 = parent.x0 + o.paddingOuter - half
    var y0 = parent.y0 + o.paddingOuter + header - half
    var x1 = parent.x1 - o.paddingOuter + half
    var y1 = parent.y1 - o.paddingOuter + half
    if (x1 < x0) { x0 = (x0 + x1) / 2; x1 = x0 }
    if (y1 < y0) { y0 = (y0 + y1) / 2; y1 = y0 }

    val ordered = if (o.sort) kids.sortedByDescending { it.value } else kids
    val boxes = ordered.map { Slot(it) }
    val total = ordered.sumOf { it.value }
    when (val t = o.tiling) {
        is TreemapTiling.Squarify -> squarify(t.ratio, boxes, total, x0, y0, x1, y1)
        is TreemapTiling.Resquarify -> resquarify(t.ratio, parent.node.key, boxes, total, x0, y0, x1, y1, rows)
        TreemapTiling.Binary -> binary(boxes, total, x0, y0, x1, y1)
        TreemapTiling.SliceDice -> if (parent.depth % 2 == 0) dice(boxes, total, x0, y0, x1, y1) else slice(boxes, total, x0, y0, x1, y1)
        TreemapTiling.Slice -> slice(boxes, total, x0, y0, x1, y1)
        TreemapTiling.Dice -> dice(boxes, total, x0, y0, x1, y1)
    }
    return boxes.map { b ->
        var a0 = b.x0 + half
        var b0 = b.y0 + half
        var a1 = b.x1 - half
        var b1 = b.y1 - half
        if (a1 < a0) { a0 = (a0 + a1) / 2; a1 = a0 }
        if (b1 < b0) { b0 = (b0 + b1) / 2; b1 = b0 }
        TreemapCell(b.node, a0, b0, a1, b1, parent.depth + 1, open = false)
    }
}

/** A child being placed: its node and the rectangle the tiling gives it. */
internal class Slot(val node: TreemapNode) {
    var x0 = 0.0; var y0 = 0.0; var x1 = 0.0; var y1 = 0.0
    val value get() = node.value
}

/** Side by side along x, each as wide as its share of [total]. */
internal fun dice(slots: List<Slot>, total: Double, x0: Double, y0: Double, x1: Double, y1: Double) {
    val k = if (total > 0.0) (x1 - x0) / total else 0.0
    var x = x0
    for (s in slots) {
        s.y0 = y0; s.y1 = y1; s.x0 = x
        x += s.value * k
        s.x1 = x
    }
}

/** Stacked along y, each as tall as its share of [total]. */
internal fun slice(slots: List<Slot>, total: Double, x0: Double, y0: Double, x1: Double, y1: Double) {
    val k = if (total > 0.0) (y1 - y0) / total else 0.0
    var y = y0
    for (s in slots) {
        s.x0 = x0; s.x1 = x1; s.y0 = y
        y += s.value * k
        s.y1 = y
    }
}

private class SquarifyRow(val slots: List<Slot>, val value: Double, val dice: Boolean)

private fun squarifyRows(ratio: Double, slots: List<Slot>, total: Double, x0in: Double, y0in: Double, x1: Double, y1: Double): List<SquarifyRow> {
    val rows = ArrayList<SquarifyRow>()
    var x0 = x0in
    var y0 = y0in
    var value = total
    var i0 = 0
    var i1 = 0
    val n = slots.size
    while (i0 < n) {
        val dx = x1 - x0
        val dy = y1 - y0
        // The next non-empty child starts the row.
        var sumValue: Double
        do sumValue = slots[i1++].value while (sumValue == 0.0 && i1 < n)
        var minValue = sumValue
        var maxValue = sumValue
        val alpha = max(dy / dx, dx / dy) / (value * ratio)
        var beta = sumValue * sumValue * alpha
        var minRatio = max(maxValue / beta, beta / minValue)
        // Keep adding children while the worst aspect ratio in the row holds or improves.
        while (i1 < n) {
            val nodeValue = slots[i1].value
            sumValue += nodeValue
            if (nodeValue < minValue) minValue = nodeValue
            if (nodeValue > maxValue) maxValue = nodeValue
            beta = sumValue * sumValue * alpha
            val newRatio = max(maxValue / beta, beta / minValue)
            if (newRatio > minRatio) { sumValue -= nodeValue; break }
            minRatio = newRatio
            i1++
        }
        val row = SquarifyRow(slots.subList(i0, i1), sumValue, dx < dy)
        rows.add(row)
        if (row.dice) {
            val yEnd = if (value > 0.0) y0 + dy * sumValue / value else y1
            dice(row.slots, row.value, x0, y0, x1, yEnd)
            y0 = yEnd
        } else {
            val xEnd = if (value > 0.0) x0 + dx * sumValue / value else x1
            slice(row.slots, row.value, x0, y0, xEnd, y1)
            x0 = xEnd
        }
        value -= sumValue
        i0 = i1
    }
    return rows
}

internal fun squarify(ratio: Double, slots: List<Slot>, total: Double, x0: Double, y0: Double, x1: Double, y1: Double) {
    if (slots.isEmpty()) return
    squarifyRows(ratio, slots, total, x0, y0, x1, y1)
}

internal fun resquarify(
    ratio: Double, parentKey: String, slots: List<Slot>, total: Double,
    x0in: Double, y0in: Double, x1: Double, y1: Double, rows: TreemapRows,
) {
    if (slots.isEmpty()) return
    val byKey = slots.associateBy { it.node.key }
    val remembered = rows.byKey[parentKey]
    if (remembered != null && remembered.ratio == ratio && remembered.childKeys == byKey.keys) {
        var x0 = x0in
        var y0 = y0in
        var value = total
        for (r in remembered.rows) {
            val rowSlots = r.keys.mapNotNull { byKey[it] }
            val rowValue = rowSlots.sumOf { it.value }
            if (r.dice) {
                val yEnd = if (value > 0.0) y0 + (y1 - y0) * rowValue / value else y1
                dice(rowSlots, rowValue, x0, y0, x1, yEnd)
                y0 = yEnd
            } else {
                val xEnd = if (value > 0.0) x0 + (x1 - x0) * rowValue / value else x1
                slice(rowSlots, rowValue, x0, y0, xEnd, y1)
                x0 = xEnd
            }
            value -= rowValue
        }
        return
    }
    val computed = squarifyRows(ratio, slots, total, x0in, y0in, x1, y1)
    rows.byKey[parentKey] = TreemapRows.Entry(ratio, byKey.keys.toSet(), computed.map { r -> TreemapRows.Row(r.slots.map { it.node.key }, r.dice) })
}

internal fun binary(slots: List<Slot>, total: Double, x0: Double, y0: Double, x1: Double, y1: Double) {
    val n = slots.size
    if (n == 0) return
    val sums = DoubleArray(n + 1)
    for (i in 0 until n) sums[i + 1] = sums[i] + slots[i].value

    fun partition(i: Int, j: Int, value: Double, x0: Double, y0: Double, x1: Double, y1: Double) {
        if (i >= j - 1) {
            val s = slots[i]
            s.x0 = x0; s.y0 = y0; s.x1 = x1; s.y1 = y1
            return
        }
        val valueOffset = sums[i]
        val valueTarget = value / 2 + valueOffset
        var k = i + 1
        var hi = j - 1
        while (k < hi) {
            val mid = (k + hi) ushr 1
            if (sums[mid] < valueTarget) k = mid + 1 else hi = mid
        }
        if (valueTarget - sums[k - 1] < sums[k] - valueTarget && i + 1 < k) --k
        val valueLeft = sums[k] - valueOffset
        val valueRight = value - valueLeft
        if (x1 - x0 > y1 - y0) {
            val xk = if (value > 0.0) (x0 * valueRight + x1 * valueLeft) / value else x1
            partition(i, k, valueLeft, x0, y0, xk, y1)
            partition(k, j, valueRight, xk, y0, x1, y1)
        } else {
            val yk = if (value > 0.0) (y0 * valueRight + y1 * valueLeft) / value else y1
            partition(i, k, valueLeft, x0, y0, x1, yk)
            partition(k, j, valueRight, x0, yk, x1, y1)
        }
    }
    partition(0, n, total, x0, y0, x1, y1)
}

/** The worst (largest) aspect ratio among [cells], long side over short; 1 is all squares. */
fun worstAspectRatio(cells: List<TreemapCell>): Double =
    cells.filter { it.w > 0 && it.h > 0 }.maxOfOrNull { max(it.w, it.h) / min(it.w, it.h) } ?: 1.0
