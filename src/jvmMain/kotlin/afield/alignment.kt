package afield

import afield.stdlib.Action
import afield.stdlib.actions
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.min

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class Orientation { VERTICAL, HORIZONTAL }
enum class IntraDistribution { EQUAL_GAPS, PACK_MIN_MOVE }

data class LayoutParams(
    val intraGap: Double = 0.0,                    // min gap inside a band
    val crossGap: Double = 16.0,                   // gap between columns/rows
    val verticalPreferenceRatio: Double = 0.95,    // orientation heuristic
    val singleBandOverfillTolerance: Double = 0.08,// keep one band if ≤ 8% over
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
    if (span + 1e-9 < base) return null // infeasible with the requested span+minGap
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

/* ---------- Deterministic, order-preserving partitioning ---------- */

private fun <T> partitionSequential(
    items: List<T>,
    size: (T) -> Double,
    available: Double,
    minGap: Double
): List<List<T>> {
    if (items.isEmpty()) return emptyList()
    if (available <= 0.0) return items.map { listOf(it) } // each in its own band

    val bands = mutableListOf<MutableList<T>>()
    var current = mutableListOf<T>()
    var usedSize = 0.0 // sum(size) + (count-1)*minGap for current band

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


/** Public API */
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

/* ---------------- Orientation heuristic ---------------- */

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

private fun mean(values: List<Double>): Double =
    if (values.isEmpty()) 0.0 else values.sum() / values.size

private fun median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val s = values.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
}

/* ---------------- 1D pack (order-preserving, minimal movement) ---------------- */


/**
 * Order-preserving pack with min gap. Finds an optimal global shift (L2) and,
 * if bounds are given, clamps that shift to stay within bounds.
 */
private data class PackResult(val starts: DoubleArray, val fitsInBounds: Boolean)

/**
 * Order-preserving 1D pack with min gap.
 * - Minimizes movement by a single optimal global shift (L2).
 * - If [minBound,maxBound] is infeasible (empty), we clamp to the nearest bound
 *   and return fitsInBounds=false instead of throwing.
 */
private fun pack1D(
    desiredStarts: DoubleArray,
    sizes: DoubleArray,
    minGap: Double,
    minBound: Double? = null,
    maxBound: Double? = null
): PackResult {
    val n = desiredStarts.size
    if (n == 0) return PackResult(DoubleArray(0), true)

    // Forward pass: enforce order + minGap with minimal forward adjustment.
    val starts = DoubleArray(n)
    for (i in 0 until n) {
        starts[i] = if (i == 0) desiredStarts[0]
        else kotlin.math.max(desiredStarts[i], starts[i - 1] + sizes[i - 1] + minGap)
    }

    // L2-optimal global shift.
    var deltaOpt = 0.0
    for (i in 0 until n) deltaOpt += (desiredStarts[i] - starts[i])
    deltaOpt /= n.toDouble()

    // Shift bounds induced by optional [minBound, maxBound].
    var deltaMin = Double.NEGATIVE_INFINITY
    var deltaMax = Double.POSITIVE_INFINITY
    if (minBound != null) deltaMin = minBound - starts[0]
    if (maxBound != null) deltaMax = maxBound - (starts[n - 1] + sizes[n - 1])

    // Tolerate tiny floating error.
    val eps = 1e-9
    val feasible = deltaMin <= deltaMax + eps

    // SAFE clamp: if infeasible, choose nearest bound (no IllegalArgumentException).
    val delta = when {
        feasible -> deltaOpt.coerceIn(deltaMin, deltaMax)
        deltaOpt < deltaMin -> deltaMin
        deltaOpt > deltaMax -> deltaMax
        else -> deltaMin // min > max; either is fine—pick one deterministically
    }

    for (i in 0 until n) starts[i] += delta
    return PackResult(starts, feasible)
}

/* ---------------- Equal-gap distribution inside a fixed span ---------------- */

private fun distributeEqualGaps(
    spanMin: Double, spanMax: Double,
    sizes: DoubleArray
): DoubleArray {
    val n = sizes.size
    val total = sizes.sum()
    val free = (spanMax - spanMin) - total
    val gap = if (n == 0) 0.0 else max(0.0, free / (n + 1))
    val starts = DoubleArray(n)
    var cursor = spanMin + gap
    for (i in 0 until n) {
        starts[i] = cursor
        cursor += sizes[i] + gap
    }
    return starts
}

/* ---------------- Vertical (columns; extra columns go RIGHT) ---------------- */

private data class IdxRect(val idx: Int, val r: Rect)

private fun layoutVertical(original: List<Rect>, params: LayoutParams): List<Rect> {
    val n = original.size
    val src = original.mapIndexed { i, r -> IdxRect(i, r.copy()) }

    val minTop = src.minOf { it.r.top }
    val maxBottom = src.maxOf { it.r.bottom }
    val spanY = (maxBottom - minTop).coerceAtLeast(0.0)

    // STRICT: top -> bottom by y, stable by original index
    val byY = src.sortedWith(compareBy<IdxRect> { it.r.top }.thenBy { it.idx })

    val sumH = byY.sumOf { it.r.h }
    val reqOne = sumH + (byY.size - 1) * params.intraGap
    val keepSingle = spanY > 0.0 && reqOne <= spanY * (1.0 + params.singleBandOverfillTolerance)

    val columns: List<List<IdxRect>> =
        if (keepSingle) listOf(byY)
        else partitionSequential(byY, { it.r.h }, spanY, params.intraGap)

    val colWidths = columns.map { col -> col.maxOf { it.r.w } }.toDoubleArray()
    val baselineLeft = median(src.map { it.r.left })

    // build desired lefts strictly left->right from the baseline
    val colDesired = DoubleArray(colWidths.size).also {
        var cursor = baselineLeft
        for (i in it.indices) {
            it[i] = cursor
            cursor += colWidths[i] + params.crossGap
        }
    }
    val colLefts = pack1D(colDesired.copyOf(), colWidths, params.crossGap, baselineLeft, null).starts

    val out = Array<Rect?>(n) { null }
    for ((ci, col) in columns.withIndex()) {
        val sizes = col.map { it.r.h }.toDoubleArray()
        val equal = distributeEqualGapsWithinSpan(minTop, maxBottom, sizes, params.intraGap)
        val tops = equal ?: run {
            val desired = col.map { it.r.y }.toDoubleArray()
            pack1D(desired, sizes, params.intraGap, null, null).starts
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

    // STRICT: left -> right by x, stable by original index
    val byX = src.sortedWith(compareBy<IdxRect> { it.r.left }.thenBy { it.idx })

    val sumW = byX.sumOf { it.r.w }
    val reqOne = sumW + (byX.size - 1) * params.intraGap
    val keepSingle = spanX > 0.0 && reqOne <= spanX * (1.0 + params.singleBandOverfillTolerance)

    val rows: List<List<IdxRect>> =
        if (keepSingle) listOf(byX)
        else partitionSequential(byX, { it.r.w }, spanX, params.intraGap)

    val rowHeights = rows.map { row -> row.maxOf { it.r.h } }.toDoubleArray()
    val baselineTop = median(src.map { it.r.top })

    // build desired tops strictly top->bottom from the baseline
    val rowDesired = DoubleArray(rowHeights.size).also {
        var cursor = baselineTop
        for (i in it.indices) {
            it[i] = cursor
            cursor += rowHeights[i] + params.crossGap
        }
    }
    val rowTops = pack1D(rowDesired.copyOf(), rowHeights, params.crossGap, baselineTop, null).starts

    val out = Array<Rect?>(n) { null }
    for ((ri, row) in rows.withIndex()) {
        val sizes = row.map { it.r.w }.toDoubleArray()
        val lefts = distributeEqualGapsWithinSpan(minLeft, maxRight, sizes, params.intraGap)
            ?: run {
                val desired = row.map { it.r.x }.toDoubleArray()
                pack1D(desired, sizes, params.intraGap, null, null).starts
            }
        val top = rowTops[ri]
        for (i in row.indices) {
            val ir = row[i]
            out[ir.idx] = Rect(x = lefts[i], y = top, w = ir.r.w, h = ir.r.h)
        }
    }
    return out.map { it!! }
}


/* ---------------- Horizontal (rows; extra rows go DOWN) ---------------- */



@RunsAt(newRoot)
fun installAlignment(root: Box) {
    root.actions["align"] = Action("Align boxes") {
        val f = it.map { it.frame }
        val f2 = alignAndDistribute(f)
        it.zip(f2).forEach { (b, r) -> b.frame = r }
    }
}
