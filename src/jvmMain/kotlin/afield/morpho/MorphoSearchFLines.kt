package afield.morpho

import afield.Rect
import afield.graphics.FLine
import afield.stdlib.Vec4
import afield.stdlib.color
import afield.stdlib.filled
import afield.stdlib.fline
import afield.stdlib.pointSize
import afield.stdlib.pointed
import afield.stdlib.stroked
import afield.stdlib.thickness
import afield.stdlib.vec
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Drawing what a search did, as vector `FLine`s for [afield.stdlib.showPlaneGrid].
 *
 * Two figures, and between them they are the whole argument:
 *
 * * [morphoConvergenceFLines] — best-so-far error against compiler invocations, one line per
 *   driver. This is where the **control arm** lives: the random-search line and the evolution line
 *   are drawn on the same axes at the same budget, so "the search found it" is a statement about
 *   the gap between two curves rather than about one number.
 * * [morphoParetoFLines] — gates against depth for every *correct* design found, with morpho's own
 *   hand-written designs marked. A search result that lands outside the human front is a result;
 *   one that lands on it is a different and much smaller result; and the picture says which.
 *
 * Everything here is geometry. Nothing rasterises, so a figure scales, exports to SVG, and can be
 * redrawn every frame of a launched box while the search is still running.
 */

/** The window every figure in this file is drawn into: a unit plot box with room for the frame. */
fun morphoPlotWindow(): Rect = Rect(-0.16, -1.10, 1.30, 1.26)

private val PLOT_FRAME = vec(0.45, 0.48, 0.55, 0.9)
private val PLOT_GRID = vec(0.35, 0.38, 0.44, 0.55)

/** Default line colours, in the order runs are passed. Distinguishable at hairline width. */
val morphoRunColours: List<Vec4> = listOf(
    vec(0.40, 0.85, 1.00, 0.95),   // cyan   — first run, conventionally the control
    vec(1.00, 0.62, 0.24, 0.95),   // orange
    vec(0.65, 1.00, 0.45, 0.95),   // green
    vec(0.95, 0.45, 0.85, 0.95),   // magenta
)

/**
 * The box, the gridlines and the ticks. Values do not appear — [afield.stdlib.showPlaneGrid] draws
 * SVG geometry and has nowhere to put text, so the ranges belong in the panel caption, which is
 * also where a reader is looking for them.
 */
private fun plotFrame(xTicks: Int, yTicks: Int): List<FLine> = buildList {
    add(
        fline {
            moveTo(0.0, 0.0); lineTo(1.0, 0.0); lineTo(1.0, -1.0); lineTo(0.0, -1.0); lineTo(0.0, 0.0)
            color = PLOT_FRAME
            thickness = 1.1
            stroked = true
            filled = false
        }
    )
    for (i in 1 until xTicks) {
        val x = i.toDouble() / xTicks
        add(fline { moveTo(x, 0.0); lineTo(x, -1.0); color = PLOT_GRID; thickness = 0.5; stroked = true; filled = false })
    }
    for (i in 1 until yTicks) {
        val y = -i.toDouble() / yTicks
        add(fline { moveTo(0.0, y); lineTo(1.0, y); color = PLOT_GRID; thickness = 0.5; stroked = true; filled = false })
    }
}

/**
 * Best-so-far error against evaluations, one polyline per run.
 *
 * @param logX put evaluations on a log axis. On by default because the interesting part of every
 *   one of these searches is the first few hundred invocations, and a linear axis spends 90% of its
 *   width on a flat tail.
 * @param yMax top of the error axis; by default the worst starting error across the runs, so the
 *   curves fill the panel instead of hugging the floor.
 */
fun morphoConvergenceFLines(
    runs: List<MorphoSearchRun>,
    logX: Boolean = true,
    yMax: Double = -1.0,
    colours: List<Vec4> = morphoRunColours,
): List<FLine> {
    require(runs.isNotEmpty()) { "morphoConvergenceFLines needs at least one run" }
    val maxEvals = max(2, runs.maxOf { it.trace.size })
    val top = if (yMax > 0.0) yMax else max(1e-6, runs.maxOf { it.trace.firstOrNull() ?: 1.0 })

    fun px(i: Int): Double {
        val e = (i + 1).toDouble()
        return if (logX) ln(e) / ln(maxEvals.toDouble()) else e / maxEvals
    }

    val out = ArrayList<FLine>()
    out += plotFrame(if (logX) log10(maxEvals.toDouble()).toInt().coerceAtLeast(1) else 5, 4)

    runs.forEachIndexed { r, run ->
        val c = colours[r % colours.size]
        if (run.trace.isEmpty()) return@forEachIndexed
        out += fline {
            run.trace.forEachIndexed { i, err ->
                val x = px(i).coerceIn(0.0, 1.0)
                val y = -(err / top).coerceIn(0.0, 1.0)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            color = c
            thickness = 1.7
            stroked = true
            filled = false
        }
        // The moment a fully correct design first appeared, as a dropped tick. Without it a curve
        // that reaches zero and one that reaches 0.004 look the same at this scale, and the whole
        // question is which of the two happened.
        if (run.solved) {
            val x = px(run.firstCorrectAt).coerceIn(0.0, 1.0)
            out += fline {
                moveTo(x, 0.0); lineTo(x, -1.0)
                color = vec(c.x, c.y, c.z, 0.45)
                thickness = 1.0
                stroked = true
                filled = false
            }
            out += fline {
                moveTo(x, 0.0)
                color = c
                pointed = true
                pointSize = 2.4
                stroked = false
                filled = false
            }
        }
    }
    return out
}

/** One point on a cost figure: a circuit, what it cost, and whether a person or a search made it. */
class MorphoCostPoint(
    val label: String,
    val gates: Int,
    val depth: Int,
    /** Drawn larger and off the run palette — the hand-written designs a result is measured against. */
    val reference: Boolean = false,
)

/**
 * Gates against depth, with the non-dominated staircase drawn through the search's own points.
 *
 * Both axes start at zero and are scaled to the widest point, so two panels built from different
 * searches are *not* automatically comparable — pass the same [gateMax] and [depthMax] when they
 * need to be.
 */
fun morphoParetoFLines(
    points: List<MorphoCostPoint>,
    gateMax: Int = -1,
    depthMax: Int = -1,
): List<FLine> {
    require(points.isNotEmpty()) { "morphoParetoFLines needs at least one point" }
    val found = points.filterNot { it.reference }
    val references = points.filter { it.reference }
    val all = points
    // A little headroom, or the most expensive and the deepest design each sit exactly on the frame
    // and read as clipped rather than as extremal.
    val gMax = (if (gateMax > 0) gateMax else all.maxOf { it.gates }).coerceAtLeast(1) * 1.12
    val dMax = (if (depthMax > 0) depthMax else all.maxOf { it.depth }).coerceAtLeast(1) * 1.12

    fun at(p: MorphoCostPoint): Pair<Double, Double> =
        (p.depth / dMax).coerceIn(0.0, 1.0) to -(p.gates / gMax).coerceIn(0.0, 1.0)

    val out = ArrayList<FLine>()
    out += plotFrame(4, 4)

    // The staircase, over the non-dominated subset of `found`. A Pareto front between two integer
    // costs is a step function and drawing it as a smooth line through the points would claim
    // designs exist in between, which is exactly what a front is asserting does *not* happen.
    val front = found
        .filter { p -> found.none { it !== p && it.gates <= p.gates && it.depth <= p.depth && (it.gates < p.gates || it.depth < p.depth) } }
        .sortedBy { it.depth }
    if (front.size > 1) {
        out += fline {
            front.forEachIndexed { i, p ->
                val (x, y) = at(p)
                if (i == 0) moveTo(x, y) else {
                    val (_, prevY) = at(front[i - 1])
                    lineTo(x, prevY)
                    lineTo(x, y)
                }
            }
            color = vec(1.0, 0.62, 0.24, 0.75)
            thickness = 1.4
            stroked = true
            filled = false
        }
    }

    if (found.isNotEmpty()) {
        out += fline {
            found.forEach { p -> val (x, y) = at(p); moveTo(x, y) }
            color = vec(1.0, 0.62, 0.24, 0.95)
            pointed = true
            pointSize = 2.0
            stroked = false
            filled = false
        }
    }
    if (references.isNotEmpty()) {
        out += fline {
            references.forEach { p -> val (x, y) = at(p); moveTo(x, y) }
            color = vec(0.40, 0.85, 1.00, 0.95)
            pointed = true
            pointSize = 3.4
            stroked = false
            filled = false
        }
    }
    return out
}

/** Every correct design a run found, as cost points. */
fun MorphoSearchRun.costPoints(): List<MorphoCostPoint> =
    solutions.mapNotNull { it.fitness.cost?.let { c -> MorphoCostPoint(label, c.gates, c.depth) } }

/**
 * A caption carrying the numbers the picture cannot: budget, control, and whether it was solved.
 *
 * Panel titles in [afield.stdlib.showPlaneGrid] take everything after the first newline as the
 * caption, so this is meant to be appended to a title with a `\n`.
 */
fun morphoRunCaption(runs: List<MorphoSearchRun>): String = runs.joinToString("  ·  ") { r ->
    val when_ = if (r.solved) "solved @ ${r.firstCorrectAt + 1}" else "err %.3f".format(r.best?.fitness?.error ?: 1.0)
    "${r.label}: $when_"
}

/** log10 helper kept local so the axis code reads the same as the caption code. */
internal fun decadeCeiling(v: Double): Double = 10.0.pow(kotlin.math.ceil(log10(max(1.0, v))))
