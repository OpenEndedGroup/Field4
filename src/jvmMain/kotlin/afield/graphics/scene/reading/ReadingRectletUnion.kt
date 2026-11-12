package afield.graphics.scene.reading

import java.awt.geom.Area
import java.awt.geom.PathIterator
import java.awt.geom.Rectangle2D
import kotlin.math.*

/** One measured line in text-local metres, before adding the frame margin. Row retains blank lines. */
internal data class ReadingRectlet(
    val left: Double,
    val bottom: Double,
    val right: Double,
    val top: Double,
    val row: Int,
) {
    val width get() = right - left
}

internal data class ReadingFramePoint(val x: Double, val y: Double)
internal enum class ReadingFrameSide { LEFT, RIGHT, BOTTOM, TOP }
internal data class ReadingFrameEdge(val a: ReadingFramePoint, val b: ReadingFramePoint, val side: ReadingFrameSide)

/** Exact union boundary: shared/internal edges are absent, including at concave steps and holes. */
internal class ReadingFrameGeometry(
    val rectlets: List<ReadingRectlet>,
    private val area: Area,
    val contours: List<List<ReadingFramePoint>>,
) {
    val left = rectlets.minOfOrNull { it.left } ?: 0.0
    val right = rectlets.maxOfOrNull { it.right } ?: 0.0
    val bottom = rectlets.minOfOrNull { it.bottom } ?: 0.0
    val top = rectlets.maxOfOrNull { it.top } ?: 0.0
    val center get() = ReadingFramePoint((left + right) * .5, (bottom + top) * .5)
    val edges = contours.flatMap { contour ->
        contour.indices.map { i ->
            val a = contour[i]
            val b = contour[(i + 1) % contour.size]
            val mx = (a.x + b.x) * .5
            val my = (a.y + b.y) * .5
            // Probe across the edge, not along its winding: holes have opposite winding.
            val epsilon = max(max(right - left, top - bottom) * 1e-9, 1e-12)
            val side = if (a.x == b.x) {
                if (area.contains(mx + epsilon, my)) ReadingFrameSide.LEFT else ReadingFrameSide.RIGHT
            } else {
                if (area.contains(mx, my + epsilon)) ReadingFrameSide.BOTTOM else ReadingFrameSide.TOP
            }
            ReadingFrameEdge(a, b, side)
        }
    }

    fun contains(p: ReadingFramePoint) = area.contains(p.x, p.y)

    /** Filled CLOSEST can remain inside. All other sliding queries use only exposed segments. */
    fun project(toward: ReadingFramePoint, site: DiagramTextSite): ReadingFramePoint {
        if (site == DiagramTextSite.CENTER) return center
        if (site == DiagramTextSite.CLOSEST && contains(toward)) return toward
        val selected = when (site) {
            DiagramTextSite.LEFT, DiagramTextSite.LEFT_MARGIN -> edges.filter { it.side == ReadingFrameSide.LEFT }
            DiagramTextSite.RIGHT, DiagramTextSite.RIGHT_MARGIN -> edges.filter { it.side == ReadingFrameSide.RIGHT }
            DiagramTextSite.UNDERLINE -> edges.filter { it.side == ReadingFrameSide.BOTTOM }
            else -> edges
        }
        val query = when (site) {
            DiagramTextSite.LEFT -> ReadingFramePoint(left, center.y)
            DiagramTextSite.RIGHT -> ReadingFramePoint(right, center.y)
            else -> toward
        }
        var closest = center
        var distance = Double.POSITIVE_INFINITY
        for (edge in selected.ifEmpty { edges }) {
            val dx = edge.b.x - edge.a.x
            val dy = edge.b.y - edge.a.y
            val length2 = dx * dx + dy * dy
            val t = if (length2 > 0.0)
                (((query.x - edge.a.x) * dx + (query.y - edge.a.y) * dy) / length2).coerceIn(0.0, 1.0)
            else 0.0
            val p = ReadingFramePoint(edge.a.x + t * dx, edge.a.y + t * dy)
            val d = (p.x - query.x).pow(2) + (p.y - query.y).pow(2)
            if (d < distance) { distance = d; closest = p }
        }
        return closest
    }
}

/** Only adjacent, vertically touching padded rows participate; all snapping expands outwards. */
internal fun readingRectletUnion(
    input: List<ReadingRectlet>,
    padding: Double,
    mergeSmallSteps: Boolean,
): ReadingFrameGeometry {
    require(padding.isFinite() && padding >= 0.0)
    val rows = input.toMutableList()
    if (mergeSmallSteps && padding > 0.0) {
        // Edges can only move to an existing neighbour's edge, so this monotone pass converges.
        // Use a snapshot per pass: the result is independent of whether rows run up or down.
        repeat(rows.size * 2) {
            val before = rows.toList()
            for (i in rows.indices) {
                val row = before[i]
                var left = row.left
                var right = row.right
                for (j in listOf(i - 1, i + 1)) if (j in rows.indices) {
                    val neighbour = before[j]
                    if (abs(neighbour.row - row.row) != 1 || neighbour.width <= row.width) continue
                    val verticalGap = max(row.bottom, neighbour.bottom) - min(row.top, neighbour.top)
                    if (verticalGap > padding * 2.0) continue
                    if (neighbour.left < left && left - neighbour.left <= padding * 2.0) left = neighbour.left
                    if (neighbour.right > right && neighbour.right - right <= padding * 2.0) right = neighbour.right
                }
                rows[i] = row.copy(left = left, right = right)
            }
            if (rows == before) return makeReadingFrameGeometry(rows, padding)
        }
    }
    return makeReadingFrameGeometry(rows, padding)
}

private fun makeReadingFrameGeometry(rows: List<ReadingRectlet>, padding: Double): ReadingFrameGeometry {
    val padded = rows.map { it.copy(left = it.left - padding, right = it.right + padding,
        bottom = it.bottom - padding, top = it.top + padding) }
    val area = Area()
    for (r in padded) if (r.right > r.left && r.top > r.bottom)
        area.add(Area(Rectangle2D.Double(r.left, r.bottom, r.right - r.left, r.top - r.bottom)))
    val contours = mutableListOf<List<ReadingFramePoint>>()
    var points = mutableListOf<ReadingFramePoint>()
    fun finish() {
        if (points.size > 1 && points.first() == points.last()) points.removeAt(points.lastIndex)
        var changed = true
        while (changed && points.size > 3) {
            changed = false
            for (i in points.indices) {
                val a = points[(i + points.size - 1) % points.size]
                val b = points[i]
                val c = points[(i + 1) % points.size]
                if ((a.x == b.x && b.x == c.x) || (a.y == b.y && b.y == c.y)) {
                    points.removeAt(i); changed = true; break
                }
            }
        }
        if (points.size >= 3) contours.add(points.toList())
        points = mutableListOf()
    }
    val iterator = area.getPathIterator(null)
    val coordinates = DoubleArray(6)
    while (!iterator.isDone) {
        when (iterator.currentSegment(coordinates)) {
            PathIterator.SEG_MOVETO -> { finish(); points.add(ReadingFramePoint(coordinates[0], coordinates[1])) }
            PathIterator.SEG_LINETO -> points.add(ReadingFramePoint(coordinates[0], coordinates[1]))
            PathIterator.SEG_CLOSE -> finish()
            else -> error("A union of rectangles produced a curved edge")
        }
        iterator.next()
    }
    finish()
    return ReadingFrameGeometry(padded, area, contours)
}
