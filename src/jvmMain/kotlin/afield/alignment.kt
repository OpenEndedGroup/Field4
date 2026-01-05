package afield

import afield.stdlib.Action
import afield.stdlib.actions
import kotlin.math.*

// =====================================================================================
// Existing API (yours) — kept as-is (plus: intraDistribution honored + tiny-shift guard)
// =====================================================================================

enum class Orientation { VERTICAL, HORIZONTAL }
enum class IntraDistribution { EQUAL_GAPS, PACK_MIN_MOVE }

data class LayoutParams(
    val intraGap: Double = 0.0,
    val crossGap: Double = 16.0,
    val verticalPreferenceRatio: Double = 0.95,
    val singleBandOverfillTolerance: Double = 0.08,
    val intraDistribution: IntraDistribution = IntraDistribution.EQUAL_GAPS
)

private fun distributeEqualGapsWithinSpan(
    spanMin: Double, spanMax: Double,
    sizes: DoubleArray,
    minGap: Double
): DoubleArray? {
    val n = sizes.size
    val span = spanMax - spanMin
    val total = sizes.sum()
    val base = total + (n - 1) * minGap
    if (span + 1e-9 < base) return null
    val extra = span - base
    val gap = minGap + extra / (n + 1)
    val starts = DoubleArray(n)
    var cursor = spanMin + gap
    for (i in 0 until n) {
        starts[i] = cursor
        cursor += sizes[i] + gap
    }
    return starts
}

private fun <T> partitionSequential(
    items: List<T>,
    size: (T) -> Double,
    available: Double,
    minGap: Double
): List<List<T>> {
    if (items.isEmpty()) return emptyList()
    if (available <= 0.0) return items.map { listOf(it) }

    val bands = mutableListOf<MutableList<T>>()
    var current = mutableListOf<T>()
    var usedSize = 0.0

    fun pushBand() {
        if (current.isNotEmpty()) bands += current
        current = mutableListOf()
        usedSize = 0.0
    }

    for (it in items) {
        val s = size(it)
        val newUsed = if (current.isEmpty()) s else usedSize + minGap + s
        if (newUsed <= available + 1e-9 || current.isEmpty()) {
            current += it
            usedSize = newUsed
        } else {
            pushBand()
            current += it
            usedSize = s
        }
    }
    pushBand()
    return bands
}

fun alignAndDistribute(
    original: List<Rect>,
    params: LayoutParams = LayoutParams()
): List<Rect> {
    if (original.isEmpty()) return emptyList()
    if (original.size == 1) return listOf(original[0].copy())

    val orientation = inferOrientation(original, params.verticalPreferenceRatio)
    return when (orientation) {
        Orientation.VERTICAL -> layoutVertical(original, params)
        Orientation.HORIZONTAL -> layoutHorizontal(original, params)
    }
}

private fun inferOrientation(rects: List<Rect>, verticalPreferenceRatio: Double): Orientation {
    val xs = rects.map { it.cx }
    val ys = rects.map { it.cy }
    val sx = stdev(xs)
    val sy = stdev(ys)
    return if (sx < sy * verticalPreferenceRatio) Orientation.VERTICAL else Orientation.HORIZONTAL
}

private fun stdev(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val mean = values.sum() / values.size
    val varSum = values.sumOf { (it - mean) * (it - mean) }
    return sqrt(varSum / values.size)
}

private fun median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val s = values.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
}

private data class PackResult(val starts: DoubleArray, val fitsInBounds: Boolean)

private fun pack1D(
    desiredStarts: DoubleArray,
    sizes: DoubleArray,
    minGap: Double,
    minBound: Double? = null,
    maxBound: Double? = null
): PackResult {
    val n = desiredStarts.size
    if (n == 0) return PackResult(DoubleArray(0), true)

    val starts = DoubleArray(n)
    for (i in 0 until n) {
        starts[i] = if (i == 0) desiredStarts[0]
        else max(desiredStarts[i], starts[i - 1] + sizes[i - 1] + minGap)
    }

    var deltaOpt = 0.0
    for (i in 0 until n) deltaOpt += (desiredStarts[i] - starts[i])
    deltaOpt /= n.toDouble()

    var deltaMin = Double.NEGATIVE_INFINITY
    var deltaMax = Double.POSITIVE_INFINITY
    if (minBound != null) deltaMin = minBound - starts[0]
    if (maxBound != null) deltaMax = maxBound - (starts[n - 1] + sizes[n - 1])

    val eps = 1e-9
    val feasible = deltaMin <= deltaMax + eps

    val deltaRaw = when {
        feasible -> deltaOpt.coerceIn(deltaMin, deltaMax)
        deltaOpt < deltaMin -> deltaMin
        deltaOpt > deltaMax -> deltaMax
        else -> deltaMin
    }

    val delta = if (abs(deltaRaw) < 1e-6) 0.0 else deltaRaw
    for (i in 0 until n) starts[i] += delta

    return PackResult(starts, feasible)
}

private data class IdxRect(val idx: Int, val r: Rect)

private fun layoutVertical(original: List<Rect>, params: LayoutParams): List<Rect> {
    val n = original.size
    val src = original.mapIndexed { i, r -> IdxRect(i, r.copy()) }

    val minTop = src.minOf { it.r.top }
    val maxBottom = src.maxOf { it.r.bottom }
    val spanY = (maxBottom - minTop).coerceAtLeast(0.0)

    val byY = src.sortedWith(compareBy<IdxRect> { it.r.top }.thenBy { it.idx })

    val sumH = byY.sumOf { it.r.h }
    val reqOne = sumH + (byY.size - 1) * params.intraGap
    val keepSingle = spanY > 0.0 && reqOne <= spanY * (1.0 + params.singleBandOverfillTolerance)

    val columns: List<List<IdxRect>> =
        if (keepSingle) listOf(byY)
        else partitionSequential(byY, { it.r.h }, spanY, params.intraGap)

    val colWidths = columns.map { col -> col.maxOf { it.r.w } }.toDoubleArray()
    val baselineLeft = median(src.map { it.r.left })

    val colDesired = DoubleArray(colWidths.size)
    run {
        var cursor = baselineLeft
        for (i in colDesired.indices) {
            colDesired[i] = cursor
            cursor += colWidths[i] + params.crossGap
        }
    }
    val colLefts = pack1D(colDesired.copyOf(), colWidths, params.crossGap, baselineLeft, null).starts

    val out = Array<Rect?>(n) { null }
    for ((ci, col) in columns.withIndex()) {
        val sizes = col.map { it.r.h }.toDoubleArray()
        val tops = when (params.intraDistribution) {
            IntraDistribution.EQUAL_GAPS -> {
                distributeEqualGapsWithinSpan(minTop, maxBottom, sizes, params.intraGap)
                    ?: run {
                        val desired = col.map { it.r.y }.toDoubleArray()
                        pack1D(desired, sizes, params.intraGap, null, null).starts
                    }
            }
            IntraDistribution.PACK_MIN_MOVE -> {
                val desired = col.map { it.r.y }.toDoubleArray()
                pack1D(desired, sizes, params.intraGap, minTop, maxBottom).starts
            }
        }

        val x = colLefts[ci]
        for (i in col.indices) {
            val ir = col[i]
            out[ir.idx] = Rect(x = x, y = tops[i], w = ir.r.w, h = ir.r.h)
        }
    }
    return out.map { it!! }
}

private fun layoutHorizontal(original: List<Rect>, params: LayoutParams): List<Rect> {
    val n = original.size
    val src = original.mapIndexed { i, r -> IdxRect(i, r.copy()) }

    val minLeft = src.minOf { it.r.left }
    val maxRight = src.maxOf { it.r.right }
    val spanX = (maxRight - minLeft).coerceAtLeast(0.0)

    val byX = src.sortedWith(compareBy<IdxRect> { it.r.left }.thenBy { it.idx })

    val sumW = byX.sumOf { it.r.w }
    val reqOne = sumW + (byX.size - 1) * params.intraGap
    val keepSingle = spanX > 0.0 && reqOne <= spanX * (1.0 + params.singleBandOverfillTolerance)

    val rows: List<List<IdxRect>> =
        if (keepSingle) listOf(byX)
        else partitionSequential(byX, { it.r.w }, spanX, params.intraGap)

    val rowHeights = rows.map { row -> row.maxOf { it.r.h } }.toDoubleArray()
    val baselineTop = median(src.map { it.r.top })

    val rowDesired = DoubleArray(rowHeights.size)
    run {
        var cursor = baselineTop
        for (i in rowDesired.indices) {
            rowDesired[i] = cursor
            cursor += rowHeights[i] + params.crossGap
        }
    }
    val rowTops = pack1D(rowDesired.copyOf(), rowHeights, params.crossGap, baselineTop, null).starts

    val out = Array<Rect?>(n) { null }
    for ((ri, row) in rows.withIndex()) {
        val sizes = row.map { it.r.w }.toDoubleArray()
        val lefts = when (params.intraDistribution) {
            IntraDistribution.EQUAL_GAPS -> {
                distributeEqualGapsWithinSpan(minLeft, maxRight, sizes, params.intraGap)
                    ?: run {
                        val desired = row.map { it.r.x }.toDoubleArray()
                        pack1D(desired, sizes, params.intraGap, null, null).starts
                    }
            }
            IntraDistribution.PACK_MIN_MOVE -> {
                val desired = row.map { it.r.x }.toDoubleArray()
                pack1D(desired, sizes, params.intraGap, minLeft, maxRight).starts
            }
        }

        val top = rowTops[ri]
        for (i in row.indices) {
            val ir = row[i]
            out[ir.idx] = Rect(x = lefts[i], y = top, w = ir.r.w, h = ir.r.h)
        }
    }
    return out.map { it!! }
}

// =====================================================================================
// Snap/Tidy + Route (with aggressive final "columnize" + NEW preserve-centroid offset)
// =====================================================================================

data class Vec2(val x: Double, val y: Double)
enum class RouteAxis { HORIZONTAL, VERTICAL }

data class RouteParams(
    val layerGap: Double = 240.0,
    val nodeGap: Double = 36.0,

    val inertia: Double = 0.15,
    val maxMove: Double = 10000.0,

    val origin: Vec2 = Vec2(0.0, 0.0),
    val preferNearOrigin: Double = 1.5,
    val preferInDegree0: Double = 3.0,
    val preferOutDegree: Double = 0.25,

    val axis: RouteAxis = RouteAxis.HORIZONTAL,
    val directionFromPositions: Boolean = true,

    val crossingIterations: Int = 12,
    val orthoRelaxIterations: Int = 10,
    val orthoBaryWeight: Double = 0.9,

    val dummyW: Double = 12.0,
    val dummyH: Double = 12.0,

    val finalColumnize: Boolean = true,
    val finalColumnizePasses: Int = 1,

    // NEW: keep overall placement close by restoring centroid at the end
    val preserveCentroid: Boolean = true,
    val centroidMode: CentroidMode = CentroidMode.AREA_WEIGHTED
)

enum class GuideMode1D { MEAN, MEDIAN }
enum class EdgeAnchor { LEFT, RIGHT, TOP, BOTTOM }

data class SizeSnapParams(
    val widthSnapTol: Double = 14.0,
    val heightSnapTol: Double = 14.0,
    val mode: GuideMode1D = GuideMode1D.MEDIAN,
    val minW: Double = 8.0,
    val minH: Double = 8.0,
    val maxDeltaPerRect: Double = 1000.0,
    val preserveCenter: Boolean = false,
    val onlyIfClusterAtLeast: Int = 2
)

data class EdgeSnapParams(
    val tol: Double = 14.0,
    val anchors: Set<EdgeAnchor> = setOf(EdgeAnchor.RIGHT, EdgeAnchor.BOTTOM),
    val maxDeltaPerRect: Double = 1000.0,
    val onlyIfClusterAtLeast: Int = 2
)

data class LocalTidyParams(
    val posSnapTol: Double = 10.0,
    val minGap: Double = 24.0,
    val overlapPasses: Int = 4
)

data class EdgeId(val fromId: String, val toId: String, val weight: Double = 1.0)

private data class G(
    val nodes: Map<String, Rect>,
    val out: Map<String, List<EdgeId>>,
    val `in`: Map<String, List<EdgeId>>
)

// ---------- NEW: centroid preservation helpers ----------

enum class CentroidMode { UNWEIGHTED, AREA_WEIGHTED }

private fun centroid(rects: List<Rect>, mode: CentroidMode): Vec2 {
    if (rects.isEmpty()) return Vec2(0.0, 0.0)
    var sx = 0.0
    var sy = 0.0
    var sw = 0.0
    for (r in rects) {
        val w = when (mode) {
            CentroidMode.UNWEIGHTED -> 1.0
            CentroidMode.AREA_WEIGHTED -> max(1e-9, r.w * r.h)
        }
        sx += r.cx * w
        sy += r.cy * w
        sw += w
    }
    if (sw <= 1e-12) return Vec2(0.0, 0.0)
    return Vec2(sx / sw, sy / sw)
}

private fun translate(rects: List<Rect>, dx: Double, dy: Double): List<Rect> {
    if (abs(dx) < 1e-12 && abs(dy) < 1e-12) return rects
    return rects.map { r -> Rect(x = r.x + dx, y = r.y + dy, w = r.w, h = r.h) }
}

private fun restoreCentroid(newRects: List<Rect>, oldRects: List<Rect>, mode: CentroidMode): List<Rect> {
    if (newRects.isEmpty() || oldRects.isEmpty()) return newRects
    val cOld = centroid(oldRects, mode)
    val cNew = centroid(newRects, mode)
    return translate(newRects, cOld.x - cNew.x, cOld.y - cNew.y)
}

// ---------- Public APIs ----------

fun snapAndTidyLocal(
    rects: List<Rect>,
    sizeSnap: SizeSnapParams = SizeSnapParams(),
    edgeSnap: EdgeSnapParams = EdgeSnapParams(),
    tidy: LocalTidyParams = LocalTidyParams(),
    preserve: Boolean = true,
    centroidMode: CentroidMode = CentroidMode.AREA_WEIGHTED
): List<Rect> {
    if (rects.isEmpty()) return emptyList()
    if (rects.size == 1) return listOf(rects[0].copy())

    val before = rects.map { it.copy() }

    var r = snapRectSizes(before, sizeSnap)
    r = snapRectEdges(r, edgeSnap)
    r = snapPositionsXY(r, tidy.posSnapTol)
    r = resolveOverlaps(r, tidy.minGap, tidy.overlapPasses)

    return if (preserve) restoreCentroid(r, before, centroidMode) else r
}

fun routeAndTidyLocal(
    rects: List<Rect>,
    edges: List<EdgeId>,
    route: RouteParams = RouteParams(),
    sizeSnap: SizeSnapParams = SizeSnapParams(),
    edgeSnap: EdgeSnapParams = EdgeSnapParams(),
    tidy: LocalTidyParams = LocalTidyParams()
): List<Rect> {
    if (rects.isEmpty()) return emptyList()
    if (rects.size == 1) return listOf(rects[0].copy())

    val before = rects.map { it.copy() }

    val snapped = snapRectSizes(before, sizeSnap)
    val snappedEdges = snapRectEdges(snapped, edgeSnap)

    val routed = routePositionGuidedWithDummies(snappedEdges, edges, route)

    var out = snapAndTidyLocal(
        rects = routed,
        sizeSnap = sizeSnap,
        edgeSnap = edgeSnap,
        tidy = tidy,
        preserve = false // we preserve at the very end, after columnize
    )

    if (route.finalColumnize) {
        repeat(route.finalColumnizePasses.coerceAtLeast(1)) {
            out = columnizeFromCurrent(out, edges, route)
        }
        out = snapRectEdges(out, edgeSnap)
    }

    if (route.preserveCentroid) {
        out = restoreCentroid(out, before, route.centroidMode)
    }
    return out
}

// =====================================================================================
// Local tidy pieces (no global repack)
// =====================================================================================

private fun snapPositionsXY(rects: List<Rect>, tol: Double): List<Rect> {
    if (rects.isEmpty()) return emptyList()
    val out = rects.map { it.copy() }.toMutableList()

    fun cluster(values: List<Pair<Int, Double>>, tol0: Double): List<List<Pair<Int, Double>>> {
        val sorted = values.sortedBy { it.second }
        val clusters = mutableListOf<MutableList<Pair<Int, Double>>>()
        for (v in sorted) {
            val last = clusters.lastOrNull()
            if (last == null || abs(v.second - last.last().second) > tol0) clusters += mutableListOf(v)
            else last += v
        }
        return clusters
    }

    fun medianOf(vals: List<Double>): Double {
        val s = vals.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    run {
        val xs = out.mapIndexed { i, r -> i to r.x }
        for (c in cluster(xs, tol)) {
            if (c.size < 2) continue
            val guide = medianOf(c.map { it.second })
            for ((idx, _) in c) {
                val r = out[idx]
                val dx = guide - r.x
                if (abs(dx) <= tol) out[idx] = Rect(x = r.x + dx, y = r.y, w = r.w, h = r.h)
            }
        }
    }

    run {
        val ys = out.mapIndexed { i, r -> i to r.y }
        for (c in cluster(ys, tol)) {
            if (c.size < 2) continue
            val guide = medianOf(c.map { it.second })
            for ((idx, _) in c) {
                val r = out[idx]
                val dy = guide - r.y
                if (abs(dy) <= tol) out[idx] = Rect(x = r.x, y = r.y + dy, w = r.w, h = r.h)
            }
        }
    }

    return out
}

private fun resolveOverlaps(rects: List<Rect>, minGap: Double, passes: Int): List<Rect> {
    val out = rects.map { it.copy() }.toMutableList()
    if (out.size <= 1) return out

    fun overlap1D(a0: Double, a1: Double, b0: Double, b1: Double): Double =
        min(a1, b1) - max(a0, b0)

    for (pass in 0 until passes) {
        var any = false
        for (i in 0 until out.size) {
            for (j in i + 1 until out.size) {
                val a = out[i]
                val b = out[j]

                val ox = overlap1D(a.left, a.right, b.left, b.right)
                val oy = overlap1D(a.top, a.bottom, b.top, b.bottom)
                if (ox <= 0.0 || oy <= 0.0) continue

                val pushX = ox + minGap
                val pushY = oy + minGap

                if (pushX <= pushY) {
                    val dir = if (b.cx >= a.cx) 1.0 else -1.0
                    out[j] = Rect(x = b.x + dir * pushX, y = b.y, w = b.w, h = b.h)
                } else {
                    val dir = if (b.cy >= a.cy) 1.0 else -1.0
                    out[j] = Rect(x = b.x, y = b.y + dir * pushY, w = b.w, h = b.h)
                }
                any = true
            }
        }
        if (!any) break
    }
    return out
}

// =====================================================================================
// Size snap + edge snap
// =====================================================================================

private fun snapRectSizes(rects: List<Rect>, p: SizeSnapParams): List<Rect> {
    if (rects.isEmpty()) return emptyList()
    val out = rects.map { it.copy() }.toMutableList()

    fun cluster(values: List<Pair<Int, Double>>, tol: Double): List<List<Pair<Int, Double>>> {
        val sorted = values.sortedBy { it.second }
        val clusters = mutableListOf<MutableList<Pair<Int, Double>>>()
        for (v in sorted) {
            val last = clusters.lastOrNull()
            if (last == null || abs(v.second - last.last().second) > tol) clusters += mutableListOf(v)
            else last += v
        }
        return clusters
    }

    fun guideOf(cluster: List<Pair<Int, Double>>): Double {
        val xs = cluster.map { it.second }.sorted()
        return when (p.mode) {
            GuideMode1D.MEAN -> xs.sum() / xs.size
            GuideMode1D.MEDIAN -> if (xs.size % 2 == 1) xs[xs.size / 2] else (xs[xs.size / 2 - 1] + xs[xs.size / 2]) / 2.0
        }
    }

    run {
        val candidates = out.mapIndexed { i, r -> i to max(r.w, p.minW) }
        for (c in cluster(candidates, p.widthSnapTol)) {
            if (c.size < p.onlyIfClusterAtLeast) continue
            val g = max(guideOf(c), p.minW)
            for ((idx, w0) in c) {
                val r = out[idx]
                val dw = (g - w0).coerceIn(-p.maxDeltaPerRect, p.maxDeltaPerRect)
                if (abs(dw) < 1e-9) continue
                val newW = max(r.w + dw, p.minW)
                out[idx] = if (p.preserveCenter) {
                    val cx = r.cx
                    Rect(x = cx - newW / 2.0, y = r.y, w = newW, h = r.h)
                } else {
                    Rect(x = r.x, y = r.y, w = newW, h = r.h)
                }
            }
        }
    }

    run {
        val candidates = out.mapIndexed { i, r -> i to max(r.h, p.minH) }
        for (c in cluster(candidates, p.heightSnapTol)) {
            if (c.size < p.onlyIfClusterAtLeast) continue
            val g = max(guideOf(c), p.minH)
            for ((idx, h0) in c) {
                val r = out[idx]
                val dh = (g - h0).coerceIn(-p.maxDeltaPerRect, p.maxDeltaPerRect)
                if (abs(dh) < 1e-9) continue
                val newH = max(r.h + dh, p.minH)
                out[idx] = if (p.preserveCenter) {
                    val cy = r.cy
                    Rect(x = r.x, y = cy - newH / 2.0, w = r.w, h = newH)
                } else {
                    Rect(x = r.x, y = r.y, w = r.w, h = newH)
                }
            }
        }
    }

    return out
}

private fun snapRectEdges(rects: List<Rect>, p: EdgeSnapParams): List<Rect> {
    if (rects.isEmpty()) return emptyList()
    val out = rects.map { it.copy() }.toMutableList()

    fun cluster(values: List<Pair<Int, Double>>, tol: Double): List<List<Pair<Int, Double>>> {
        val sorted = values.sortedBy { it.second }
        val clusters = mutableListOf<MutableList<Pair<Int, Double>>>()
        for (v in sorted) {
            val last = clusters.lastOrNull()
            if (last == null || abs(v.second - last.last().second) > tol) clusters += mutableListOf(v)
            else last += v
        }
        return clusters
    }

    fun medianOf(vals: List<Double>): Double {
        val s = vals.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    fun applyCluster(values: List<Pair<Int, Double>>, tol: Double, move: (idx: Int, guide: Double) -> Unit) {
        for (c in cluster(values, tol)) {
            if (c.size < p.onlyIfClusterAtLeast) continue
            val guide = medianOf(c.map { it.second })
            for ((idx, _) in c) move(idx, guide)
        }
    }

    if (EdgeAnchor.LEFT in p.anchors) {
        applyCluster(out.mapIndexed { i, r -> i to r.left }, p.tol) { idx, guide ->
            val r = out[idx]
            val dx = (guide - r.x).coerceIn(-p.maxDeltaPerRect, p.maxDeltaPerRect)
            if (abs(dx) < 1e-9) return@applyCluster
            out[idx] = Rect(x = r.x + dx, y = r.y, w = r.w, h = r.h)
        }
    }
    if (EdgeAnchor.RIGHT in p.anchors) {
        applyCluster(out.mapIndexed { i, r -> i to r.right }, p.tol) { idx, guide ->
            val r = out[idx]
            val targetX = guide - r.w
            val dx = (targetX - r.x).coerceIn(-p.maxDeltaPerRect, p.maxDeltaPerRect)
            if (abs(dx) < 1e-9) return@applyCluster
            out[idx] = Rect(x = r.x + dx, y = r.y, w = r.w, h = r.h)
        }
    }
    if (EdgeAnchor.TOP in p.anchors) {
        applyCluster(out.mapIndexed { i, r -> i to r.top }, p.tol) { idx, guide ->
            val r = out[idx]
            val dy = (guide - r.y).coerceIn(-p.maxDeltaPerRect, p.maxDeltaPerRect)
            if (abs(dy) < 1e-9) return@applyCluster
            out[idx] = Rect(x = r.x, y = r.y + dy, w = r.w, h = r.h)
        }
    }
    if (EdgeAnchor.BOTTOM in p.anchors) {
        applyCluster(out.mapIndexed { i, r -> i to r.bottom }, p.tol) { idx, guide ->
            val r = out[idx]
            val targetY = guide - r.h
            val dy = (targetY - r.y).coerceIn(-p.maxDeltaPerRect, p.maxDeltaPerRect)
            if (abs(dy) < 1e-9) return@applyCluster
            out[idx] = Rect(x = r.x, y = r.y + dy, w = r.w, h = r.h)
        }
    }

    return out
}

// =====================================================================================
// Routing core with dummy nodes for long edges
// =====================================================================================

private fun Rect.idOrNull(): String? = null

private fun buildGraph(nodes: Map<String, Rect>, edges: List<EdgeId>): G {
    val out = mutableMapOf<String, MutableList<EdgeId>>()
    val inn = mutableMapOf<String, MutableList<EdgeId>>()
    for (k in nodes.keys) {
        out.putIfAbsent(k, mutableListOf())
        inn.putIfAbsent(k, mutableListOf())
    }
    for (e in edges) {
        if (!nodes.containsKey(e.fromId) || !nodes.containsKey(e.toId)) continue
        out.getOrPut(e.fromId) { mutableListOf() }.add(e)
        inn.getOrPut(e.toId) { mutableListOf() }.add(e)
    }
    return G(nodes, out, inn)
}

private fun routePositionGuidedWithDummies(
    rects: List<Rect>,
    edges: List<EdgeId>,
    p: RouteParams
): List<Rect> {
    val realIds = rects.mapIndexed { i, r -> r.idOrNull() ?: i.toString() }
    val realNodes: Map<String, Rect> = realIds.zip(rects).toMap()
    if (realNodes.isEmpty()) return rects.map { it.copy() }

    val g0 = buildGraph(realNodes, edges)
    val root = chooseRootPositionGuided(g0, p)
    val (axis, sign) = if (p.directionFromPositions) inferForwardSign(g0, p.axis) else (p.axis to +1)

    val layerReal = assignLayersPositionGuided(g0, root, axis, sign, p)

    val expanded = expandLongEdgesWithDummies(
        realNodes = realNodes,
        edges = edges,
        layerOf = layerReal,
        axis = axis,
        sign = sign,
        p = p
    )

    val g = buildGraph(expanded.nodes, expanded.edges)

    val layers = mutableMapOf<Int, MutableList<String>>()
    for ((id, l) in expanded.layerOf) layers.getOrPut(l) { mutableListOf() }.add(id)

    orderWithinLayersAggressive(g, layers, axis, p.crossingIterations)

    val placedExpanded = placeExpandedGraph(
        g = g,
        layers = layers,
        axis = axis,
        sign = sign,
        anchorMajor = expanded.anchorMajor,
        anchorOrtho = expanded.anchorOrtho,
        p = p
    )

    val outById = placedExpanded.filterKeys { it in realNodes.keys }
    return realIds.map { id -> outById[id] ?: realNodes[id]!!.copy() }
}

private data class Expanded(
    val nodes: Map<String, Rect>,
    val edges: List<EdgeId>,
    val layerOf: Map<String, Int>,
    val anchorMajor: Double,
    val anchorOrtho: Double
)

private fun expandLongEdgesWithDummies(
    realNodes: Map<String, Rect>,
    edges: List<EdgeId>,
    layerOf: Map<String, Int>,
    axis: RouteAxis,
    sign: Int,
    p: RouteParams
): Expanded {
    val nodes = realNodes.toMutableMap()
    val newEdges = mutableListOf<EdgeId>()
    val newLayer = layerOf.toMutableMap()

    val anchorMajor = when (axis) {
        RouteAxis.HORIZONTAL -> median(realNodes.values.map { it.x })
        RouteAxis.VERTICAL -> median(realNodes.values.map { it.y })
    }
    val anchorOrtho = when (axis) {
        RouteAxis.HORIZONTAL -> median(realNodes.values.map { it.y })
        RouteAxis.VERTICAL -> median(realNodes.values.map { it.x })
    }

    var dummyCounter = 0
    fun makeDummyId(): String = "__d${dummyCounter++}"

    fun dummyRectAt(from: Rect, to: Rect): Rect {
        val cx = (from.cx + to.cx) * 0.5
        val cy = (from.cy + to.cy) * 0.5
        val w = max(1.0, p.dummyW)
        val h = max(1.0, p.dummyH)
        return Rect(x = cx - w / 2.0, y = cy - h / 2.0, w = w, h = h)
    }

    for (e in edges) {
        val u = e.fromId
        val v = e.toId
        val lu = newLayer[u] ?: 0
        val lv = newLayer[v] ?: 0
        val delta = lv - lu

        if (delta <= 1) {
            newEdges += e
            continue
        }

        var prev = u
        val fromR = nodes[u] ?: continue
        val toR = nodes[v] ?: continue

        for (k in 1 until delta) {
            val did = makeDummyId()
            nodes[did] = dummyRectAt(fromR, toR)
            newLayer[did] = lu + k
            newEdges += EdgeId(prev, did, e.weight)
            prev = did
        }
        newEdges += EdgeId(prev, v, e.weight)
    }

    return Expanded(nodes = nodes, edges = newEdges, layerOf = newLayer, anchorMajor = anchorMajor, anchorOrtho = anchorOrtho)
}

private fun orderWithinLayersAggressive(
    g: G,
    layers: MutableMap<Int, MutableList<String>>,
    axis: RouteAxis,
    iterations: Int
) {
    fun orthoCoord(r: Rect): Double = when (axis) {
        RouteAxis.HORIZONTAL -> r.cy
        RouteAxis.VERTICAL -> r.cx
    }

    for ((l, ids) in layers) {
        layers[l] = ids.sortedBy { orthoCoord(g.nodes[it]!!) }.toMutableList()
    }

    val keys = layers.keys.sorted()

    fun positions(ids: List<String>): Map<String, Double> =
        ids.withIndex().associate { it.value to it.index.toDouble() }

    fun baryFromPrev(id: String, prevPos: Map<String, Double>, g: G): Double {
        val parents = g.`in`[id].orEmpty().map { it.fromId }.filter { it in prevPos }
        if (parents.isEmpty()) return Double.POSITIVE_INFINITY
        return parents.map { prevPos[it]!! }.average()
    }

    fun baryFromNext(id: String, nextPos: Map<String, Double>, g: G): Double {
        val kids = g.out[id].orEmpty().map { it.toId }.filter { it in nextPos }
        if (kids.isEmpty()) return Double.POSITIVE_INFINITY
        return kids.map { nextPos[it]!! }.average()
    }

    for (iter in 0 until iterations) {
        for (i in 1 until keys.size) {
            val prev = layers[keys[i - 1]] ?: continue
            val curKey = keys[i]
            val cur = layers[curKey] ?: continue
            val prevPos = positions(prev)
            layers[curKey] = cur.sortedBy { baryFromPrev(it, prevPos, g) }.toMutableList()
        }
        for (i in keys.size - 2 downTo 0) {
            val next = layers[keys[i + 1]] ?: continue
            val curKey = keys[i]
            val cur = layers[curKey] ?: continue
            val nextPos = positions(next)
            layers[curKey] = cur.sortedBy { baryFromNext(it, nextPos, g) }.toMutableList()
        }
    }
}

private fun placeExpandedGraph(
    g: G,
    layers: Map<Int, List<String>>,
    axis: RouteAxis,
    sign: Int,
    anchorMajor: Double,
    anchorOrtho: Double,
    p: RouteParams
): Map<String, Rect> {
    fun orthoCenterOf(r: Rect): Double = when (axis) {
        RouteAxis.HORIZONTAL -> r.cy
        RouteAxis.VERTICAL -> r.cx
    }
    fun orthoSizeOf(r: Rect): Double = when (axis) {
        RouteAxis.HORIZONTAL -> r.h
        RouteAxis.VERTICAL -> r.w
    }

    val ortho = mutableMapOf<String, Double>()
    for ((id, r) in g.nodes) ortho[id] = orthoCenterOf(r)
    fun oc(id: String): Double = ortho[id] ?: anchorOrtho

    val sortedKeys = layers.keys.sorted()

    for (iter in 0 until p.orthoRelaxIterations) {
        for (k in sortedKeys) {
            val ids = layers[k].orEmpty()
            for (id in ids) {
                val parents = g.`in`[id].orEmpty().map { it.fromId }.filter { it in ortho }
                if (parents.isNotEmpty()) {
                    val b = parents.map { oc(it) }.average()
                    ortho[id] = lerp(oc(id), b, p.orthoBaryWeight)
                }
            }
        }
        for (kIdx in sortedKeys.size - 1 downTo 0) {
            val k = sortedKeys[kIdx]
            val ids = layers[k].orEmpty()
            for (id in ids) {
                val kids = g.out[id].orEmpty().map { it.toId }.filter { it in ortho }
                if (kids.isNotEmpty()) {
                    val b = kids.map { oc(it) }.average()
                    ortho[id] = lerp(oc(id), b, p.orthoBaryWeight)
                }
            }
        }
    }

    val placed = g.nodes.mapValues { (_, r) -> r.copy() }.toMutableMap()

    for (layerIdx in sortedKeys) {
        val ids = layers[layerIdx].orEmpty()
        if (ids.isEmpty()) continue

        val major = anchorMajor + sign * layerIdx * p.layerGap

        val sizes = DoubleArray(ids.size)
        val desiredStarts = DoubleArray(ids.size)
        val prefix = DoubleArray(ids.size)

        var acc = 0.0
        for (i in ids.indices) {
            val id = ids[i]
            val r = g.nodes[id]!!
            val s = orthoSizeOf(r)
            sizes[i] = s
            desiredStarts[i] = oc(id) - s / 2.0
            prefix[i] = acc
            acc += s + p.nodeGap
        }

        var start0 = 0.0
        for (i in ids.indices) start0 += (desiredStarts[i] - prefix[i])
        start0 /= ids.size.toDouble()

        val starts = DoubleArray(ids.size)
        for (i in ids.indices) starts[i] = start0 + prefix[i]

        for (i in ids.indices) {
            val id = ids[i]
            val r0 = placed[id] ?: continue
            val orthoStart = starts[i]
            placed[id] = when (axis) {
                RouteAxis.HORIZONTAL -> Rect(x = major, y = orthoStart, w = r0.w, h = r0.h)
                RouteAxis.VERTICAL -> Rect(x = orthoStart, y = major, w = r0.w, h = r0.h)
            }
        }
    }

    val moveT = (1.0 - p.inertia).coerceIn(0.0, 1.0)
    val blended = mutableMapOf<String, Rect>()
    for ((id, rT) in placed) {
        val r = g.nodes[id] ?: rT
        val dx = (rT.x - r.x).coerceIn(-p.maxMove, p.maxMove)
        val dy = (rT.y - r.y).coerceIn(-p.maxMove, p.maxMove)
        blended[id] = Rect(x = r.x + dx * moveT, y = r.y + dy * moveT, w = r.w, h = r.h)
    }
    return blended
}

private fun columnizeFromCurrent(rects: List<Rect>, edges: List<EdgeId>, p: RouteParams): List<Rect> {
    val ids = rects.mapIndexed { i, r -> r.idOrNull() ?: i.toString() }
    val nodes = ids.zip(rects).toMap()
    if (nodes.isEmpty()) return rects.map { it.copy() }

    val g = buildGraph(nodes, edges)
    val root = chooseRootPositionGuided(g, p)
    val (axis, sign) = if (p.directionFromPositions) inferForwardSign(g, p.axis) else (p.axis to +1)
    val layer = assignLayersPositionGuided(g, root, axis, sign, p)

    val layers = mutableMapOf<Int, MutableList<String>>()
    for ((id, l) in layer) layers.getOrPut(l) { mutableListOf() }.add(id)

    orderWithinLayersAggressive(g, layers, axis, max(2, p.crossingIterations / 3))

    val anchorMajor = when (axis) {
        RouteAxis.HORIZONTAL -> median(nodes.values.map { it.x })
        RouteAxis.VERTICAL -> median(nodes.values.map { it.y })
    }
    val anchorOrtho = when (axis) {
        RouteAxis.HORIZONTAL -> median(nodes.values.map { it.y })
        RouteAxis.VERTICAL -> median(nodes.values.map { it.x })
    }

    val placed = placeExpandedGraph(g, layers, axis, sign, anchorMajor, anchorOrtho, p)
        .filterKeys { it in nodes.keys }

    return ids.map { id -> placed[id] ?: nodes[id]!!.copy() }
}

// =====================================================================================
// Root + sign + layers
// =====================================================================================

private fun chooseRootPositionGuided(g: G, p: RouteParams): String {
    fun distToOrigin(r: Rect): Double {
        val dx = r.cx - p.origin.x
        val dy = r.cy - p.origin.y
        return sqrt(dx * dx + dy * dy)
    }

    fun score(id: String): Double {
        val r = g.nodes[id] ?: return Double.NEGATIVE_INFINITY
        val inDeg = g.`in`[id]?.size ?: 0
        val outDeg = g.out[id]?.size ?: 0
        val originScore = 1.0 / (1.0 + distToOrigin(r))
        val indegBonus = if (inDeg == 0) 1.0 else 0.0
        return p.preferNearOrigin * originScore +
                p.preferInDegree0 * indegBonus +
                p.preferOutDegree * outDeg.toDouble()
    }

    return g.nodes.keys.maxByOrNull { score(it) } ?: g.nodes.keys.first()
}

private fun inferForwardSign(g: G, axis: RouteAxis): Pair<RouteAxis, Int> {
    var forward = 0
    for ((u, outs) in g.out) {
        val ru = g.nodes[u] ?: continue
        for (e in outs) {
            val rv = g.nodes[e.toId] ?: continue
            forward += when (axis) {
                RouteAxis.HORIZONTAL -> if (rv.cx >= ru.cx) 1 else -1
                RouteAxis.VERTICAL -> if (rv.cy >= ru.cy) 1 else -1
            }
        }
    }
    val sign = if (forward >= 0) +1 else -1
    return axis to sign
}

private fun assignLayersPositionGuided(
    g: G,
    root: String,
    axis: RouteAxis,
    sign: Int,
    p: RouteParams
): MutableMap<String, Int> {
    val bfs = mutableMapOf<String, Int>()
    bfs[root] = 0
    val q: ArrayDeque<String> = ArrayDeque()
    q.add(root)

    while (q.isNotEmpty()) {
        val u = q.removeFirst()
        val lu = bfs[u]!!
        for (e in g.out[u].orEmpty()) {
            val v = e.toId
            val cand = lu + 1
            val prev = bfs[v]
            if (prev == null || cand < prev) {
                bfs[v] = cand
                q.add(v)
            }
        }
    }

    fun coord(r: Rect): Double = when (axis) {
        RouteAxis.HORIZONTAL -> r.cx
        RouteAxis.VERTICAL -> r.cy
    }

    val rootRect = g.nodes[root] ?: return bfs
    val rootCoord = coord(rootRect)

    val layer = mutableMapOf<String, Int>()
    for (id in g.nodes.keys) {
        val r = g.nodes[id] ?: continue
        val base = bfs[id] ?: 0
        val desired = (sign * (coord(r) - rootCoord)) / p.layerGap
        val blended = lerp(base.toDouble(), desired, p.inertia)
        layer[id] = blended.roundToInt()
    }

    for (iter in 0 until 10) {
        var changed = false
        for ((u, outs) in g.out) {
            val lu = layer[u] ?: continue
            for (e in outs) {
                val v = e.toId
                val lv = layer[v] ?: continue
                if (lv <= lu) {
                    layer[v] = lu + 1
                    changed = true
                }
            }
        }
        if (!changed) break
    }

    val shift = layer[root] ?: 0
    for (k in layer.keys.toList()) layer[k] = layer[k]!! - shift
    return layer
}

// =====================================================================================
// Helpers
// =====================================================================================

private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
private fun Double.roundToInt(): Int = round(this).toInt()

// =====================================================================================
// Actions installation
// =====================================================================================

@RunsAt(newRoot)
fun installAlignment(root: Box) {
    root.actions["align"] = Action("Align boxes", "Automatically align selected boxes outlines vertically or horizontally") { boxes ->
        val before = boxes.map { it.frame }
        val after = alignAndDistribute(
            before,
            LayoutParams(intraGap = 16.0, crossGap = 16.0, intraDistribution = IntraDistribution.PACK_MIN_MOVE)
        )
        val stabilized = restoreCentroid(after, before, CentroidMode.AREA_WEIGHTED)
        boxes.zip(stabilized).forEach { (b, r) -> b.frame = r }
    }

    root.actions["snap_tidy"] = Action("Snap + tidy boxes", "Automatically tidy up the arrangement of selected boxes") { boxes ->
        val before = boxes.map { it.frame }
        val out = snapAndTidyLocal(
            rects = before,
            sizeSnap = SizeSnapParams(widthSnapTol = 14.0, heightSnapTol = 14.0, preserveCenter = false),
            edgeSnap = EdgeSnapParams(tol = 14.0, anchors = setOf(EdgeAnchor.RIGHT, EdgeAnchor.BOTTOM)),
            tidy = LocalTidyParams(posSnapTol = 10.0, minGap = 16.0, overlapPasses = 3),
            preserve = true,
            centroidMode = CentroidMode.AREA_WEIGHTED
        )
        boxes.zip(out).forEach { (b, r) -> b.frame = r }
    }

    root.actions["route"] = Action("Route (position-guided)", detail = "Intelligently organize a selection of boxes by how they are connected together") { boxes ->
        val before = boxes.map { it.frame }

        val boxId: Map<Box, String> = boxes.mapIndexed { i, b -> b to i.toString() }.toMap()
        val edges = boxes.flatMap { a ->
            val from = boxId[a] ?: return@flatMap emptyList()
            a.children.mapNotNull { b ->
                val to = boxId[b] ?: return@mapNotNull null
                EdgeId(from, to)
            }
        }

        val out = routeAndTidyLocal(
            rects = before,
            edges = edges,
            route = RouteParams(
                layerGap = 240.0,
                nodeGap = 36.0,
                inertia = 0.15,
                crossingIterations = 12,
                orthoRelaxIterations = 10,
                finalColumnize = true,
                finalColumnizePasses = 1,
                preserveCentroid = true,
                centroidMode = CentroidMode.AREA_WEIGHTED
            ),
            sizeSnap = SizeSnapParams(widthSnapTol = 14.0, heightSnapTol = 14.0, preserveCenter = false),
            edgeSnap = EdgeSnapParams(tol = 14.0, anchors = setOf(EdgeAnchor.RIGHT, EdgeAnchor.BOTTOM)),
            tidy = LocalTidyParams(posSnapTol = 8.0, minGap = 28.0, overlapPasses = 4)
        )

        boxes.zip(out).forEach { (b, r) -> b.frame = r }
    }
}
