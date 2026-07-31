@file:afield.CompletionCategory("Data")

package afield.stdlib

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The one honest way to *look at* `dynamic_graph_systems.kt` — a raster of the point cloud and the
 * weighted edges joining it, so a time-varying graph can be watched rather than described.
 *
 * It sits in its own file for the same reason `alife_field.kt` and `wfc_field.kt` do:
 * `dynamic_graph_systems.kt` names nothing from Field's visualizer or from the imaging family, and a
 * reader of it sees points, edges and clocks and nothing about pixels. Everything here returns an
 * [Img], which is what makes it compose with [montage], [Img.colormap] and — the reason it exists —
 * [Box.animateEvolution].
 *
 * **The range policy, which is the whole difference between a picture and a lie.** Copied from
 * `alife_field.kt` because these are exactly the traps a graph animation walks into:
 *
 *  - **The world window is the caller's, and it is fixed.** [dynamicGraphImg] takes an explicit
 *    `window` rather than fitting itself to the cloud, because a cloud that is *moving* and *growing*
 *    re-fitted every frame appears to stand still while the world shrinks around it. This is the same
 *    failure `rasterizeFLinesFramed` exists to prevent, one family over. [dynamicGraphWindow] computes
 *    a window once, from a reference state, for you to keep.
 *  - **Edge brightness is `weight / maxWeight` with `maxWeight` named by the caller**, not normalised
 *    per frame. Per-frame normalisation makes a graph that is uniformly saturating look identical to
 *    one that is uniformly dying, which is the single most misleading thing this family could draw.
 *  - **Points are drawn at full intensity and edges below it**, so the population is legible even
 *    where the graph has decayed to nothing — an empty frame and a frame of isolated points are
 *    different states and must look different.
 *  - **A frame that clips says so.** [dynamicGraphImg] reports how many points fell outside the
 *    window in [DynamicGraphRaster.clipped], because a figure quietly cropping half its subject is
 *    the other way this goes wrong.
 */

/** What [dynamicGraphImg] drew, and what it had to leave out. */
data class DynamicGraphRaster(
    /** 1-channel intensity in `0 .. 1`: `1` at a point, `weight / maxWeight` along an edge. */
    val img: Img,
    /** Points that fell outside the window and were not drawn. */
    val clipped: Int,
    /** The heaviest edge actually drawn, in weight units — for a caption that names its own scale. */
    val heaviestWeight: Double
)

/**
 * A world window `[xMin, yMin, xMax, yMax]` around [points], padded by [padFraction] of the larger
 * span and forced square so the aspect ratio of the raster matches the aspect ratio of the world.
 *
 * Compute this **once**, from the state you want the figure framed on, and pass the same array to
 * every frame. See the range policy on this file for why.
 */
fun dynamicGraphWindow(
    points: List<Vec3>,
    padFraction: Double = 0.12,
    project: (Vec3) -> Pair<Double, Double> = { it.x.toDouble() to it.y.toDouble() }
): DoubleArray {
    require(points.isNotEmpty()) { "dynamicGraphWindow: needs at least one point" }
    var xMin = Double.POSITIVE_INFINITY
    var yMin = Double.POSITIVE_INFINITY
    var xMax = Double.NEGATIVE_INFINITY
    var yMax = Double.NEGATIVE_INFINITY
    for (p in points) {
        val (x, y) = project(p)
        xMin = min(xMin, x); xMax = max(xMax, x)
        yMin = min(yMin, y); yMax = max(yMax, y)
    }
    val cx = 0.5 * (xMin + xMax)
    val cy = 0.5 * (yMin + yMax)
    // Square, so the raster does not stretch the world on one axis.
    val half = max(1e-9, 0.5 * max(xMax - xMin, yMax - yMin)) * (1.0 + padFraction)
    return doubleArrayOf(cx - half, cy - half, cx + half, cy + half)
}

/**
 * Rasterise a dynamic graph: every edge as a line whose brightness is its weight, every point as a
 * dot at full intensity.
 *
 * The result is 1-channel and in `0 .. 1`, so [Img.colormap] with an explicit `0f, 1f` range is the
 * intended next step and the colour ramp then means the same thing in every frame of a sequence.
 *
 * @param window `[xMin, yMin, xMax, yMax]` in world units — from [dynamicGraphWindow], computed once
 * @param maxWeight the weight that maps to full edge brightness; name it in the figure's caption. The
 *   natural choice is the `maxWeight` the [DynamicGraphSystem] was given, since that is the ceiling
 *   [DynamicEdgeList.reinforce] saturates at.
 * @param minEdgeIntensity the brightness a weight of ~0 gets, so a fresh faint edge is visible at all
 * @param project world -> plane; the default drops z, which is right for the planar clouds this
 *   family's examples use. Pass your own for an isometric or a different pair of axes.
 */
fun dynamicGraphImg(
    points: List<Vec3>,
    edges: Collection<DynamicGraphEdge>,
    window: DoubleArray,
    width: Int = 480,
    height: Int = 480,
    maxWeight: Double = 1.0,
    pointRadius: Int = 1,
    minEdgeIntensity: Float = 0.12f,
    project: (Vec3) -> Pair<Double, Double> = { it.x.toDouble() to it.y.toDouble() }
): DynamicGraphRaster {
    require(width >= 8 && height >= 8) { "dynamicGraphImg: raster must be at least 8x8, got ${width}x$height" }
    require(window.size == 4) { "dynamicGraphImg: window must be [xMin, yMin, xMax, yMax]" }
    require(window[2] > window[0] && window[3] > window[1]) {
        "dynamicGraphImg: window must be non-degenerate, got ${window.toList()}"
    }
    require(maxWeight > 0.0) { "dynamicGraphImg: maxWeight must be > 0, found $maxWeight" }
    require(pointRadius >= 0) { "dynamicGraphImg: pointRadius must be >= 0, found $pointRadius" }

    val out = Img.zeros(width, height, 1)
    val sx = (width - 1) / (window[2] - window[0])
    val sy = (height - 1) / (window[3] - window[1])

    fun px(x: Double) = ((x - window[0]) * sx).roundToInt()
    // y downward, so the figure is the right way up when it is displayed.
    fun py(y: Double) = (height - 1 - (y - window[1]) * sy).roundToInt()

    // Additive would let a dense knot of faint edges impersonate one heavy edge, so this is a max.
    fun plotMax(xi: Int, yi: Int, v: Float) {
        if (xi in 0 until width && yi in 0 until height) {
            val at = yi * width + xi
            if (v > out.data[at]) out.data[at] = v
        }
    }

    fun line(x0i: Int, y0i: Int, x1i: Int, y1i: Int, v: Float) {
        var x0 = x0i
        var y0 = y0i
        val dx = abs(x1i - x0)
        val sxi = if (x0 < x1i) 1 else -1
        val dy = -abs(y1i - y0)
        val syi = if (y0 < y1i) 1 else -1
        var err = dx + dy
        while (true) {
            plotMax(x0, y0, v)
            if (x0 == x1i && y0 == y1i) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x0 += sxi }
            if (e2 <= dx) { err += dx; y0 += syi }
        }
    }

    val screen = points.map { val (x, y) = project(it); px(x) to py(y) }

    var heaviest = 0.0
    for (e in edges) {
        if (e.i !in screen.indices || e.j !in screen.indices) continue
        heaviest = max(heaviest, e.weight)
        val t = (e.weight / maxWeight).coerceIn(0.0, 1.0)
        val v = minEdgeIntensity + (1f - minEdgeIntensity) * t.toFloat()
        val (ax, ay) = screen[e.i]
        val (bx, by) = screen[e.j]
        line(ax, ay, bx, by, v)
    }

    var clipped = 0
    for ((x, y) in screen) {
        if (x !in 0 until width || y !in 0 until height) { clipped++; continue }
        for (dy in -pointRadius..pointRadius) {
            for (dx in -pointRadius..pointRadius) {
                if (dx * dx + dy * dy <= pointRadius * pointRadius) plotMax(x + dx, y + dy, 1f)
            }
        }
    }

    return DynamicGraphRaster(out, clipped, heaviest)
}

/**
 * [dynamicGraphImg] straight through [Img.colormap] with a **fixed** `0 .. 1` range, which is the
 * combination every figure on `dynamic_graph_systems_examples.md` uses.
 *
 * Convenience only: it exists so a call site does not accidentally leave the colormap auto-ranging,
 * which is the mistake the range policy at the top of this file is about.
 */
fun dynamicGraphPanel(
    points: List<Vec3>,
    edges: Collection<DynamicGraphEdge>,
    window: DoubleArray,
    width: Int = 480,
    height: Int = 480,
    maxWeight: Double = 1.0,
    colormap: String = "magma",
    pointRadius: Int = 1,
    project: (Vec3) -> Pair<Double, Double> = { it.x.toDouble() to it.y.toDouble() }
): Img = dynamicGraphImg(
    points, edges, window, width, height, maxWeight, pointRadius, project = project
).img.colormap(colormap, 0f, 1f)

/**
 * The two numbers a dynamic-graph figure needs in its caption, measured rather than assumed: how many
 * points and edges there are, and what the weight distribution looks like.
 *
 * Reported as a one-line string so a `montage` panel label or a `println` can carry the state of the
 * system it is drawing. "It produced a picture" is not evidence; this is the least that has to go with
 * one.
 */
fun dynamicGraphSummary(state: DynamicPointState, edges: DynamicEdgeList): String {
    val weights = edges.values().map { it.weight }
    val degrees = IntArray(state.pointCount)
    for (e in edges.values()) {
        if (e.i in degrees.indices) degrees[e.i]++
        if (e.j in degrees.indices && e.j != e.i) degrees[e.j]++
    }
    val isolated = degrees.count { it == 0 }
    return buildString {
        append("${state.pointCount} pts, ${weights.size} edges")
        if (weights.isNotEmpty()) {
            append(", w %.3f..%.3f".format(weights.min(), weights.max()))
            append(" mean %.3f".format(weights.average()))
        }
        append(", mean deg %.2f".format(if (state.pointCount == 0) 0.0 else degrees.average()))
        if (isolated > 0) append(", $isolated isolated")
        append(", t=%.2fs".format(state.time))
    }
}
