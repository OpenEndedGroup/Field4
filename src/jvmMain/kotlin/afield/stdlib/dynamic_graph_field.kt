@file:afield.CompletionCategory("Data")

package afield.stdlib

import afield.Rect
import afield.graphics.FLine
import afield.Colormaps
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The two honest ways to *look at* `dynamic_graph_systems.kt` — the point cloud and the weighted edges
 * joining it, so a time-varying graph can be watched rather than described.
 *
 * It sits in its own file for the same reason `alife_field.kt` and `wfc_field.kt` do:
 * `dynamic_graph_systems.kt` names nothing from Field's visualizer or from the imaging family, and a
 * reader of it sees points, edges and clocks and nothing about pixels.
 *
 * # Which of the two to use, because they are not interchangeable
 *
 * | you want | call | why |
 * | --- | --- | --- |
 * | a **figure** — one state, on a page, in a box | [dynamicGraphFLines] → [Box.showPlaneGrid] | vector: real `FLine`s through `drawToSVG`, so it scales, and edge weight drives colour *and* thickness |
 * | a **frame of an animation** | [dynamicGraphPanel] → [Box.animateEvolution] | raster: [Box.animateEvolution] encodes a `montage` of [Img]s per frame, and there is no vector path into an mp4 |
 *
 * [dynamicGraphFLines] is the default and [dynamicGraphImg]/[dynamicGraphPanel] are the animation
 * path. That split is deliberate and it is the whole reason this header exists: a hand-rolled
 * scan-converted pixel buffer is not how this codebase draws a figure — `FLine` →
 * [planeFigureHtml]/[drawToSVG] is — and the raster only survives here because
 * [Box.animateEvolution]'s `panels` lambda is typed `List<Pair<String, Img>>`
 * (`alife_animate.kt`), so a clip genuinely needs pixels. If you are reaching for
 * [dynamicGraphPanel] and you are not inside an `animateEvolution`, you want [dynamicGraphFLines].
 *
 * **The range policy, which is the whole difference between a picture and a lie.** Copied from
 * `alife_field.kt` because these are exactly the traps a graph animation walks into, and it applies
 * to both paths:
 *
 *  - **The world window is the caller's, and it is fixed.** [dynamicGraphImg] takes an explicit
 *    `window`, and [Box.showPlaneGrid] takes one too, rather than fitting to the cloud: a cloud that
 *    is *moving* and *growing* re-fitted every frame appears to stand still while the world shrinks
 *    around it. [dynamicGraphWindow] computes a window once, from a reference state, for you to keep;
 *    [dynamicGraphPlaneWindow] converts it for the vector path.
 *  - **Edge weight is read against a `maxWeight` named by the caller**, not normalised per frame or
 *    per panel. Per-frame normalisation makes a graph that is uniformly saturating look identical to
 *    one that is uniformly dying, which is the single most misleading thing this family could draw.
 *  - **Points are drawn at the top of the ramp and edges below it**, so the population is legible even
 *    where the graph has decayed to nothing — an empty frame and a frame of isolated points are
 *    different states and must look different.
 *  - **A figure that clips says so.** [dynamicGraphImg] reports how many points fell outside the
 *    window in [DynamicGraphRaster.clipped] and [dynamicGraphOutsideWindow] answers the same question
 *    for the vector path, because a figure quietly cropping half its subject is the other way this
 *    goes wrong.
 */

/** What [dynamicGraphImg] drew into one animation frame, and what it had to leave out. */
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

// ==============================================================================================
// The figure path — FLines, for Box.showPlaneGrid
// ==============================================================================================

/**
 * A dynamic graph as **vector** geometry: one two-node polyline per edge, coloured *and* thickened by
 * that edge's weight, followed by one dot-drawn [FLine] carrying every point.
 *
 * Hand the result to [Box.showPlaneGrid] (or [Box.showPlane], or [planeFigureHtml] for the markup
 * alone) with [dynamicGraphPlaneWindow] as its `window`, and the figure is real SVG — it scales, the
 * strokes are anti-aliased, and the panel titles are HTML text rather than a bitmap font:
 *
 * ```
 * val window = dynamicGraphWindow(cloud, padFraction = 0.06)
 * showPlaneGrid(
 *     "kNN k=5: ${edges.values().size}e" to dynamicGraphFLines(state.points, edges.values(), maxWeight = 4.0),
 *     columns = 3, cellWidth = 400, cellHeight = 400, window = dynamicGraphPlaneWindow(window),
 * )
 * ```
 *
 * # Why one `FLine` per edge, against this codebase's usual advice
 *
 * [PointGraph.toFLine] draws a whole graph as a *single* `FLine` on the argument that "a graph is a
 * single visual layer", and that is right for an unweighted graph. It cannot be done here.
 * `FLine.color` and `FLine.thickness` are per-line properties, and `outputTo` in `someSkiko.kt` reads
 * exactly one `Paint` per `FLine` (`someSkiko.kt:359-361`) — so a weight that varies edge to edge can
 * only reach the page as one line per edge. The cost is real and worth knowing before you point this
 * at a big graph: **measured, an edge is about 163 bytes of inline SVG** (688 edges, 112 006 chars,
 * pinned by `theEmittedSvgCostsAboutWhatTheKDocSaysPerEdge`), so figure 6's densest 1300-edge panel is
 * a little over 200 kB. Fine for a figure; not fine 200 times a second. That is the other reason the
 * mp4 path stayed raster.
 *
 * # Coordinates: y is negated, once, here
 *
 * SVG's y axis grows *downward*, and [planeFigureHtml] maps the caller's coordinates onto the frame
 * without flipping anything, so geometry handed to it straight would come out mirrored — the reverse
 * of the same cloud in the animated panels, and the wrong chirality for a phyllotaxis spiral. So this
 * function negates y **after** [project], exactly as [dynamicGraphImg]'s `py()` does, and
 * [dynamicGraphPlaneWindow] negates the window to match. [project] is therefore the same world → plane
 * map in both paths; do not pre-flip in it.
 *
 * @param maxWeight the weight that maps to the top of the ramp and the thickest stroke; name it in the
 *   figure's caption. The natural choice is the `maxWeight` the [DynamicGraphSystem] was given, since
 *   that is the ceiling [DynamicEdgeList.reinforce] saturates at. Not derived from [edges], on purpose
 *   — see the range policy at the top of this file.
 * @param colormap a ramp name from [Colormaps.names]; the same ramp [dynamicGraphPanel] colours with,
 *   so a still and a clip of the same system read the same way
 * @param minEdgeIntensity where on the ramp a weight of ~0 sits, so a fresh faint edge is visible at
 *   all against the dark card. Identical in meaning and default to [dynamicGraphImg]'s.
 * @param thinEdge stroke width, in final figure pixels, at weight 0
 * @param thickEdge stroke width at [maxWeight] — so weight is encoded twice, redundantly, which is
 *   what makes a printed or colour-blind reading of the figure survive
 * @param pointColor the dots' colour; `null` takes the top of [colormap], which is where
 *   [dynamicGraphImg] puts its points and why an isolated point is still visible
 * @param pointSize a dot's *radius* is `3 * pointSize` output pixels ([planeFigureHtml]'s convention),
 *   so the default 0.5 matches the raster path's `pointRadius = 1`
 * @param project world -> plane; the default drops z, which is right for the planar clouds this
 *   family's examples use. Pass your own for an isometric or a different pair of axes.
 */
fun dynamicGraphFLines(
    points: List<Vec3>,
    edges: Collection<DynamicGraphEdge>,
    maxWeight: Double = 1.0,
    colormap: String = "magma",
    minEdgeIntensity: Double = 0.12,
    thinEdge: Double = 0.8,
    thickEdge: Double = 1.8,
    pointColor: Vec4? = null,
    pointSize: Double = 0.5,
    project: (Vec3) -> Pair<Double, Double> = { it.x.toDouble() to it.y.toDouble() }
): List<FLine> {
    require(maxWeight > 0.0) { "dynamicGraphFLines: maxWeight must be > 0, found $maxWeight" }
    require(minEdgeIntensity in 0.0..1.0) {
        "dynamicGraphFLines: minEdgeIntensity must be in 0..1, found $minEdgeIntensity"
    }
    require(thinEdge > 0.0 && thickEdge > 0.0) {
        "dynamicGraphFLines: stroke widths must be > 0, found $thinEdge..$thickEdge"
    }
    require(pointSize >= 0.0) { "dynamicGraphFLines: pointSize must be >= 0, found $pointSize" }

    // Project once. y is negated here and nowhere else; see the KDoc above.
    val plane = points.map { val (x, y) = project(it); x to -y }

    val out = ArrayList<FLine>(edges.size + 1)
    for (e in edges) {
        if (e.i !in plane.indices || e.j !in plane.indices) continue
        val t = (e.weight / maxWeight).coerceIn(0.0, 1.0)
        val ramp = minEdgeIntensity + (1.0 - minEdgeIntensity) * t
        val c = Colormaps.sample(colormap, ramp)
        val (ax, ay) = plane[e.i]
        val (bx, by) = plane[e.j]
        out.add(
            fline {
                moveTo(ax, ay)
                lineTo(bx, by)
                this.color = vec(c.r, c.g, c.b, 1.0)
                this.thickness = thinEdge + (thickEdge - thinEdge) * t
                this.stroked = true
                this.filled = false
            }
        )
    }

    if (plane.isNotEmpty() && pointSize > 0.0) {
        val top = Colormaps.sample(colormap, 1.0)
        out.add(
            fline {
                plane.forEach { (x, y) -> moveTo(x, y) }
                this.color = pointColor ?: vec(top.r, top.g, top.b, 0.95)
                this.pointed = true
                this.pointSize = pointSize
                this.stroked = false
                this.filled = false
            }
        )
    }
    return out
}

/**
 * A [dynamicGraphWindow] result as the [Rect] [Box.showPlaneGrid] and [planeFigureHtml] want, with y
 * negated so it frames what [dynamicGraphFLines] actually emitted.
 *
 * Pass it to every panel of a comparison. Without it each panel fits to its own contents, so two
 * graphs over the same cloud are drawn at two different scales and the comparison is worthless — the
 * exact failure the range policy at the top of this file is about, in its vector form.
 */
fun dynamicGraphPlaneWindow(window: DoubleArray): Rect {
    require(window.size == 4) { "dynamicGraphPlaneWindow: window must be [xMin, yMin, xMax, yMax]" }
    require(window[2] > window[0] && window[3] > window[1]) {
        "dynamicGraphPlaneWindow: window must be non-degenerate, got ${window.toList()}"
    }
    // y is negated, so [yMin, yMax] becomes [-yMax, -yMin] and the Rect's origin is -yMax.
    return Rect(window[0], -window[3], window[2] - window[0], window[3] - window[1])
}

/**
 * How many of [points] fall outside [window] — so a caption can say it.
 *
 * The vector path has no natural place to report this: [dynamicGraphFLines] emits geometry and it is
 * [planeFigureHtml]'s frame that does the cropping, silently. [dynamicGraphImg] reports the same
 * number in [DynamicGraphRaster.clipped]. A figure that hides part of its subject and does not admit
 * it is the failure this exists to prevent.
 */
fun dynamicGraphOutsideWindow(
    points: List<Vec3>,
    window: DoubleArray,
    project: (Vec3) -> Pair<Double, Double> = { it.x.toDouble() to it.y.toDouble() }
): Int {
    require(window.size == 4) { "dynamicGraphOutsideWindow: window must be [xMin, yMin, xMax, yMax]" }
    return points.count {
        val (x, y) = project(it)
        x < window[0] || x > window[2] || y < window[1] || y > window[3]
    }
}

// ==============================================================================================
// The animation path — Img frames, for Box.animateEvolution
// ==============================================================================================

/**
 * Rasterise a dynamic graph into **one frame of an animation**: every edge as a scan-converted line
 * whose brightness is its weight, every point as a dot at full intensity.
 *
 * **This is not the way to draw a figure — [dynamicGraphFLines] is.** It exists because
 * [Box.animateEvolution] is typed on [Img] (`panels: (S, Int) -> List<Pair<String, Img>>`, see
 * `alife_animate.kt`) and there is no vector path from an `FLine` into an mp4: the docs runner records
 * a box's PNG output channel, so a clip is pixels by construction. Two consequences worth knowing:
 *
 *  - It cannot be replaced by `rasterizeFLines`/[rasterizeFLinesFramed] one family over, even though
 *    those also turn lines into an [Img]. That rasteriser plots a constant `1f`
 *    (`alife_lsystem.kt:538`), so it is a *binary* mask — every edge the same brightness, and the
 *    weight, which is the whole subject of this family, gone.
 *  - It uses `max` rather than an additive plot, so a dense knot of faint edges cannot impersonate one
 *    heavy edge.
 *
 * The result is 1-channel and in `0 .. 1`, so [Img.colormap] with an explicit `0f, 1f` range is the
 * intended next step — [dynamicGraphPanel] is that pairing — and the colour ramp then means the same
 * thing in every frame of a sequence.
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
 * [dynamicGraphImg] straight through [Img.colormap] with a **fixed** `0 .. 1` range — **the panel of an
 * animation frame, not a figure.**
 *
 * This is what the two clips on `dynamic_graph_systems_examples.md` pass to [Box.animateEvolution]'s
 * `panels`, and it is the only thing it is for. The page's *still* figures go through
 * [dynamicGraphFLines] and [Box.showPlaneGrid] instead; if you are writing a new still, so should you.
 *
 * Beyond the [Img] shape [Box.animateEvolution] needs, it exists so a call site does not accidentally
 * leave the colormap auto-ranging, which is the mistake the range policy at the top of this file is
 * about — and which in an animation is a per-*frame* lie rather than a per-figure one.
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
