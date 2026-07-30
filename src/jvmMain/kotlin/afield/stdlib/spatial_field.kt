@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import afield.Box
import afield.Rect
import afield.graphics.FLine
import afield.graphics.drawToSVG
import afield.graphics.polylineFrom

/**
 * The bridges out of Field's plane-geometry results and into the things Field already draws.
 *
 * Every partitioner in `graph_connectivity.kt`, `dirichlet.kt`, `contours.kt` and `clustering.kt`
 * answers with *structure* — cell vertex rings, circular arcs, triangle index triples, cluster
 * labels — and none of them answers with geometry. This file is the missing hop: one `toFLines` per
 * result type, so a Voronoi diagram, an Apollonius cell, a Delaunay triangulation or a k-medoids
 * partition lands in the same drawing, animation and export machinery as anything drawn by hand.
 * It is the plane counterpart of `mesh_field.kt`, which does the same job for `TriMesh`.
 *
 * Two layers:
 *
 *  1. **Structure → [FLine].** [PointGraph.toFLine], [delaunayTrianglesToFLine],
 *     [VoronoiDiagram.toFLines], [CircularDirichlet.Diagram.toFLines],
 *     [CircularDirichlet.CellBoundary.toFLines], [ClusterLayer.toFLines] and
 *     [pointCloudFLine]. Each returns styled, drawable lines and nothing else — no `Box`, no
 *     output, so they are usable from a test or a renderer as readily as from a box.
 *  2. **[FLine] → a captioned figure.** [Box.showPlane] and [Box.showPlaneGrid] rasterise lines to
 *     inline SVG with `drawToSVG` and push a titled card, or a grid of them, to a box output
 *     channel — the fastest way to see a whole pipeline at once, and the 2D twin of
 *     `Box.showMeshGrid`. [planeFigureHtml] is the markup on its own, for when you want the string.
 *
 * The figure helpers are AWT-free (Skia's SVG canvas, same as `Box.lines`), so they work headless,
 * including inside the docs build.
 */

// ==============================================================================================
// Structure -> FLine
// ==============================================================================================

/**
 * The points themselves as one dot-drawn [FLine] — the layer every other figure here sits on top of.
 *
 * Each point becomes its own `moveTo`, and `pointed = true` with `stroked = false` means the
 * renderer draws dots rather than joining them into a path.
 *
 * @param pointSize a dot's *radius* comes out as `3 * pointSize` output pixels — `outputTo` in
 *   `someSkiko.kt` multiplies by three — so the default 1.2 draws a dot about 3.6 px across. Note
 *   that [planeFigureHtml] fits the geometry but not the styles, so this is in final pixels
 *   regardless of the coordinate system the points are in.
 */
fun pointCloudFLine(
    points: List<Vec2>,
    color: Vec4 = vec(1.0, 1.0, 1.0, 0.85),
    pointSize: Double = 1.2,
    z: Number = 0,
): FLine = fline {
    val zz = z.toDouble()
    points.forEach { p -> moveTo(p.x.toDouble(), p.y.toDouble(), zz) }
    this.color = Vec4(color)
    this.pointed = true
    this.pointSize = pointSize
    this.stroked = false
    this.filled = false
}

/**
 * Every edge of this graph as one [FLine], with [points] supplying the positions.
 *
 * One line rather than one per edge, because a graph is a single visual layer: give the Delaunay
 * triangulation a thin translucent stroke and the minimum spanning tree a thick opaque one and the
 * two read as different weights of the same drawing. `PointGraph` carries only indices, so the
 * point list must be the same one the graph was built from — indices out of its range throw.
 */
fun PointGraph.toFLine(
    points: List<Vec2>,
    color: Vec4 = vec(0.55, 0.68, 0.82, 0.55),
    thickness: Double = 1.4,
    z: Number = 0,
): FLine = fline {
    val zz = z.toDouble()
    edges.forEach { e ->
        require(e.i in points.indices && e.j in points.indices) {
            "PointGraph.toFLine: edge (${e.i}, ${e.j}) is outside points.indices (${points.size} points)"
        }
        val a = points[e.i]
        val b = points[e.j]
        moveTo(a.x.toDouble(), a.y.toDouble(), zz)
        lineTo(b.x.toDouble(), b.y.toDouble(), zz)
    }
    this.color = Vec4(color)
    this.thickness = thickness
    this.stroked = true
    this.filled = false
}

/**
 * The triangles of a triangulation as one [FLine] of closed triangle outlines.
 *
 * Takes what [vec2DelaunayTriangles] returns. Unlike [PointGraph.toFLine] on the same triangulation
 * this draws each *face*, so a shared edge is stroked twice and `filled = true` actually fills
 * something — which is what you want when the triangles are the subject rather than the connectivity.
 */
fun delaunayTrianglesToFLine(
    points: List<Vec2>,
    triangles: List<Triple<Int, Int, Int>>,
    color: Vec4 = vec(0.62, 0.74, 0.88, 0.45),
    thickness: Double = 1.2,
    filled: Boolean = false,
    z: Number = 0,
): FLine = fline {
    val zz = z.toDouble()
    triangles.forEach { (a, b, c) ->
        require(a in points.indices && b in points.indices && c in points.indices) {
            "delaunayTrianglesToFLine: triangle ($a, $b, $c) is outside points.indices (${points.size} points)"
        }
        moveTo(points[a].x.toDouble(), points[a].y.toDouble(), zz)
        lineTo(points[b].x.toDouble(), points[b].y.toDouble(), zz)
        lineTo(points[c].x.toDouble(), points[c].y.toDouble(), zz)
        lineTo(points[a].x.toDouble(), points[a].y.toDouble(), zz)
    }
    this.color = Vec4(color)
    this.thickness = thickness
    this.filled = filled
    this.stroked = true
}

/**
 * One closed [FLine] per Voronoi cell, in cell order.
 *
 * One line per cell rather than one for the whole diagram, so each can be coloured and filled
 * independently — pass `palette = okhslSet(diagram.cells.size, 0.7, 0.6, 0.28f)` and `filled = true`
 * for a tinted tessellation, or leave the palette null for a uniform wireframe. Cells with fewer
 * than three vertices are dropped: JTS returns an empty cell for a duplicated site, and an empty
 * `FLine` in the list would only be a trap for the caller.
 *
 * @param palette a colour per cell; shorter lists wrap. `null` uses [color] for every cell.
 */
fun VoronoiDiagram.toFLines(
    color: Vec4 = vec(0.72, 0.82, 0.95, 0.7),
    palette: List<Vec4>? = null,
    thickness: Double = 1.3,
    filled: Boolean = false,
    z: Number = 0,
): List<FLine> = cells.mapIndexedNotNull { index, cell ->
    if (cell.vertices.size < 3) return@mapIndexedNotNull null
    val tint = palette?.let { it[index % it.size] } ?: color
    polylineFrom(cell.vertices, z, closed = true).also { line ->
        line.color = Vec4(tint)
        line.thickness = thickness
        line.filled = filled
        line.stroked = !filled || thickness > 0.0
    }
}

/**
 * The boundary of one multiplicatively-weighted Voronoi cell, tessellated into [FLine]s.
 *
 * **One `FLine` per piece, not one per cell**, and that is not an implementation detail: a cell of a
 * weighted diagram need not be a single closed ring. An [CircularDirichlet.ArcPiece] whose sweep is
 * the whole circle is a *complete* circular boundary; a cell can be bounded by two of those and be
 * the region outside both, i.e. a cell with holes. Joining the pieces into one path would be a lie
 * about the topology, so this does not try.
 *
 * @param clip required for any piece that runs to infinity, which is every [CircularDirichlet.LinePiece]
 *   of an outer cell — a line cannot be flattened without one. Clipping an arc can also split it into
 *   several polylines, and each of those becomes its own `FLine`.
 * @param maxChordError how finely arcs are tessellated, in the diagram's own units.
 */
fun CircularDirichlet.CellBoundary.toFLines(
    clip: CircularDirichlet.Aabb? = null,
    maxChordError: Float = 0.02f,
    color: Vec4 = vec(0.8, 0.86, 0.98, 0.8),
    thickness: Double = 1.4,
    z: Number = 0,
): List<FLine> {
    val out = ArrayList<FLine>(pieces.size)
    pieces.forEach { piece ->
        val parts: List<List<Vec2>> = when (piece) {
            is CircularDirichlet.ArcPiece -> piece.toPolylines(maxChordError, clip)
            else -> listOf(piece.toPolyline(maxChordError, clip))
        }
        parts.forEach { polyline ->
            if (polyline.size < 2) return@forEach
            // A full-sweep arc comes back as a closed ring of samples with no repeated endpoint, so
            // it needs the closing segment that `closed = true` adds; anything clipped is an open run.
            val closed = piece is CircularDirichlet.ArcPiece &&
                clip == null &&
                piece.sweepAngle >= (2.0 * Math.PI - 1e-4).toFloat()
            out += polylineFrom(polyline, z, closed = closed).also { line ->
                line.color = Vec4(color)
                line.thickness = thickness
                line.stroked = true
                line.filled = false
            }
        }
    }
    return out
}

/**
 * Every cell of a weighted diagram, tinted per site.
 *
 * Note that each interior boundary is drawn **twice**, once from either side, because both cells own
 * it. That is deliberate: it is what makes the two tints meet along the arc, and it is honest about
 * the fact that [CircularDirichlet] builds each cell independently rather than sharing edges.
 *
 * @param palette a colour per site; shorter lists wrap. `null` uses [color] throughout.
 */
fun CircularDirichlet.Diagram.toFLines(
    clip: CircularDirichlet.Aabb? = null,
    maxChordError: Float = 0.02f,
    color: Vec4 = vec(0.8, 0.86, 0.98, 0.8),
    palette: List<Vec4>? = null,
    thickness: Double = 1.4,
    z: Number = 0,
): List<FLine> = cells.flatMapIndexed { index, cell ->
    cell.toFLines(
        clip = clip,
        maxChordError = maxChordError,
        color = palette?.let { it[index % it.size] } ?: color,
        thickness = thickness,
        z = z,
    )
}

/**
 * A cluster assignment drawn over the points it labels: one dot layer per cluster, plus optional
 * spokes from each sample to its cluster's centre.
 *
 * The returned list is one [FLine] per cluster (dots), then optionally one per cluster of spokes,
 * then optionally one for the centres — so a caller that wants only the dots can take the first
 * `clusterCount` of them.
 *
 * @param centers the medoids or medians, if you have them. Supplying them draws the centre markers
 *   and anchors the spokes at the real centre; leaving them null anchors the spokes at each
 *   cluster's mean position instead, which is *not* the same point for a medoid or a median result
 *   and is only a visual aid.
 * @param drawSpokes spokes make the assignment legible when clusters interleave, and unreadable
 *   when they do not.
 */
fun ClusterLayer.toFLines(
    points: List<Vec2>,
    centers: List<Vec2>? = null,
    palette: List<Vec4> = okhslSet(maxOf(1, clusterCount), saturation = 0.78, lightness = 0.62),
    pointSize: Double = 1.3,
    centerSize: Double = 3.0,
    drawSpokes: Boolean = true,
    spokeAlpha: Float = 0.18f,
    z: Number = 0,
): List<FLine> {
    require(points.size == sampleCount) {
        "ClusterLayer.toFLines: this layer labels $sampleCount samples but was given ${points.size} points"
    }
    val zz = z.toDouble()
    val out = ArrayList<FLine>()

    for (cluster in 0 until clusterCount) {
        val members = indices(cluster)
        if (members.isEmpty()) continue
        out += pointCloudFLine(
            members.map { points[it] },
            color = palette[cluster % palette.size],
            pointSize = pointSize,
            z = z,
        )
    }

    if (drawSpokes) {
        for (cluster in 0 until clusterCount) {
            val members = indices(cluster)
            if (members.isEmpty()) continue
            val anchor = centers?.getOrNull(cluster) ?: vec(
                members.sumOf { points[it].x.toDouble() } / members.size,
                members.sumOf { points[it].y.toDouble() } / members.size,
            )
            val tint = palette[cluster % palette.size]
            out += fline {
                members.forEach { i ->
                    moveTo(points[i].x.toDouble(), points[i].y.toDouble(), zz)
                    lineTo(anchor.x.toDouble(), anchor.y.toDouble(), zz)
                }
                this.color = vec(tint.x, tint.y, tint.z, spokeAlpha)
                this.thickness = 0.9
                this.stroked = true
                this.filled = false
            }
        }
    }

    if (centers != null && centers.isNotEmpty()) {
        centers.forEachIndexed { cluster, centre ->
            out += pointCloudFLine(
                listOf(centre),
                color = vec(1.0, 1.0, 1.0, 0.96),
                pointSize = centerSize,
                z = z,
            )
            out += pointCloudFLine(
                listOf(centre),
                color = palette[cluster % palette.size],
                pointSize = centerSize * 0.55,
                z = z,
            )
        }
    }

    return out
}

// ==============================================================================================
// FLine -> a captioned figure in a box output
// ==============================================================================================

/**
 * The markup for one titled figure: [lines] fitted into a [width] × [height] frame, rasterised to
 * inline SVG, on the same dark card the rest of Field's inline figures use.
 *
 * The lines are scaled and translated to fit, preserving aspect, so the caller's coordinate system
 * does not have to be anything in particular. Titles and captions are inserted as text, so do not
 * pass HTML you did not build.
 *
 * @param window which region of the caller's coordinate system to show. `null` fits the union of the
 *   lines' own bounds ([List<FLine>.fitInto]), which is right until one line reaches far outside the
 *   interesting part — `vec2VoronoiDiagram`'s hull cells do, by three times, because JTS clips an
 *   unbounded cell to a generous envelope, and fitting to that collapses the diagram into a speck.
 *   Passing a `window` maps *that* rectangle onto the frame instead, and whatever falls outside is
 *   clipped by the frame rather than allowed to set the scale. Several panels sharing one `window`
 *   also line up with each other, which is usually what a comparison wants.
 *
 * # Why the dots are drawn here and not by Skia
 *
 * Skia's SVG export has no circle primitive: `outputTo` in `someSkiko.kt` adds each `pointed` node to
 * a `Path`, and that path is written out as **quadratic Bézier segments** — measured, one dot costs
 * about 5 kB, so the 90-point cloud on a clustering figure came to **458 kB of SVG for one panel**.
 * This function therefore strips `pointed` from a *copy* of each line before handing the geometry to
 * `drawToSVG`, and overlays the dots as real `<circle>` elements: the same 90 dots in a few kB. The
 * radius convention is `outputTo`'s, `3 * pointSize`, so a figure looks the same here as through
 * `Box.lines`.
 */
fun planeFigureHtml(
    lines: List<FLine>,
    title: String? = null,
    caption: String? = null,
    width: Int = 640,
    height: Int = 460,
    padding: Double = 14.0,
    window: Rect? = null,
): String {
    val frame = Rect(0.0, 0.0, width.toDouble(), height.toDouble())
    val fitted = if (window == null) lines.fitInto(frame, padding = padding)
    else lines.mapped(window, frame, padding)

    val dots = StringBuilder()
    val geometry = fitted.map { line ->
        if (!line.pointed) return@map line
        val fill = svgColor(line.color)
        line.nodes.forEach { node ->
            if (node is FLine.Close) return@forEach
            val radius = (node.pointSize ?: line.pointSize) * 3.0
            val p = node.to
            dots.append(
                "<circle cx=\"%.2f\" cy=\"%.2f\" r=\"%.2f\" fill=\"%s\"/>".format(p.x, p.y, radius, fill)
            )
        }
        // A copy, because fitInto hands back the caller's own lines when the bounds are degenerate.
        line.copy { pointed = false }
    }

    val svg = drawToSVG(geometry, width.toDouble(), height.toDouble())
    val dotLayer = if (dots.isEmpty()) "" else
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$width\" height=\"$height\" " +
            "style=\"position:absolute;left:0;top:0;overflow:visible\">$dots</svg>"
    // Both text blocks are pinned to the picture's width, and that pin is load-bearing rather than
    // cosmetic. [Box.showPlaneGrid] lays its cards out in `max-content` grid columns, and the
    // max-content size of a block of text is its width with *no wrapping at all* — so an unpinned
    // caption one line longer than the picture silently widens the whole column past `cellWidth`,
    // pushes the grid wider than the box, and a docs `capture="box"` screenshot loses the last
    // column entirely. Pinned, the column's max-content is exactly the picture plus this card's
    // padding, whatever the caption says, and a long caption wraps onto another line instead.
    val titleHtml = title?.let {
        "<div style=\"font-family:monospace;font-size:12px;font-weight:700;letter-spacing:0.04em;" +
            "color:#91a4ba;margin-bottom:8px;width:${width}px;overflow-wrap:break-word\">" +
            "${escapePlaneHtml(it)}</div>"
    } ?: ""
    // The caption goes *above* the picture, which looks like a subtitle and is deliberate: a docs
    // `capture="box"` screenshot crops to the output area's measured height, and that measurement
    // comes up about one text line short of the real card. Below the picture, the caption on the
    // bottom row of a grid arrived sliced in half every time; above it, the only thing a short crop
    // can eat is the empty margin `fitInto` already leaves around the geometry.
    val captionHtml = caption?.let {
        "<div style=\"font-family:monospace;font-size:11px;line-height:1.5;color:#7f92a8;" +
            "margin:-2px 0 8px 0;width:${width}px;overflow-wrap:break-word\">" +
            "${escapePlaneHtml(it)}</div>"
    } ?: ""
    return "<div style=\"padding:12px 12px 16px 12px;background:linear-gradient(180deg,#0d1219,#090d12);" +
        "border:1px solid #202b38;border-radius:10px\">$titleHtml$captionHtml" +
        // overflow:hidden matters: `fitInto` keeps the geometry inside the frame, but a caller who
        // adds a frame line on purpose (to pin the window, or to make several panels share an
        // extent) wants everything outside it *cut* rather than spilled over the caption below.
        "<div style=\"position:relative;width:${width}px;height:${height}px;line-height:0;overflow:hidden\">" +
        "<div style=\"position:absolute;left:0;top:0\">$svg</div>$dotLayer</div></div>"
}

/**
 * Map [source] onto [frame] inset by [padding], preserving aspect, and transform every line by it.
 *
 * The counterpart of [List<FLine>.fitInto] for when the caller knows the window rather than wanting
 * it derived: geometry outside [source] lands outside [frame] and is clipped there.
 */
private fun List<FLine>.mapped(source: Rect, frame: Rect, padding: Double): List<FLine> {
    if (source.w <= 0.0 || source.h <= 0.0) return this
    val inset = padding.coerceAtLeast(0.0)
    val innerW = (frame.w - inset * 2.0).coerceAtLeast(1.0)
    val innerH = (frame.h - inset * 2.0).coerceAtLeast(1.0)
    val scale = minOf(innerW / source.w, innerH / source.h)
    if (!scale.isFinite() || scale <= 0.0) return this
    val offsetX = frame.x + inset - source.x * scale + (innerW - source.w * scale) * 0.5
    val offsetY = frame.y + inset - source.y * scale + (innerH - source.h * scale) * 0.5
    return map { line ->
        line.transform { p -> vec(p.x * scale + offsetX, p.y * scale + offsetY, p.z) }
    }
}

/** A `Vec4` in 0..1 as an SVG `rgba(...)`. */
private fun svgColor(c: Vec4): String =
    "rgba(%d,%d,%d,%.3f)".format(
        (c.x.coerceIn(0f, 1f) * 255f).toInt(),
        (c.y.coerceIn(0f, 1f) * 255f).toInt(),
        (c.z.coerceIn(0f, 1f) * 255f).toInt(),
        c.w.coerceIn(0f, 1f),
    )

/**
 * Push one plane figure to a box output channel.
 *
 * The AWT-free path (Skia SVG → `display`), so it is safe in a live box and in a headless build.
 */
fun Box.showPlane(
    lines: List<FLine>,
    channel: String = "main",
    title: String? = null,
    caption: String? = null,
    width: Int = 720,
    height: Int = 520,
    padding: Double = 14.0,
    window: Rect? = null,
) {
    display(channel, planeFigureHtml(lines, title, caption, width, height, padding, window))
}

/**
 * Several plane figures side by side, each titled — the 2D twin of `Box.showMeshGrid`, and the
 * fastest way to see a whole pipeline (sites → triangulation → dual → clipped) at a glance.
 *
 * A title may contain newlines; everything after the first becomes the figure's caption, which is
 * how you get the numbers that carry the argument under the picture they are about.
 *
 * @param columns figures per row.
 * @param window a shared window for every panel — see [planeFigureHtml]. Passing one both keeps a
 *   far-flung stray line from setting the scale and makes the panels line up with each other.
 */
fun Box.showPlaneGrid(
    vararg panels: Pair<String, List<FLine>>,
    channel: String = "main",
    columns: Int = 2,
    cellWidth: Int = 420,
    cellHeight: Int = -1,
    padding: Double = 12.0,
    window: Rect? = null,
) {
    require(panels.isNotEmpty()) { "showPlaneGrid needs at least one panel" }
    val height = if (cellHeight > 0) cellHeight else (cellWidth * 0.78).toInt()
    val html = buildString {
        // max-content columns and fit-content on the container, not `1fr`: with `1fr` the cards
        // stretch to whatever the box happens to be while the picture inside stays at cellWidth, so
        // the layout — and a docs screenshot's crop — depends on the box width. This way the grid is
        // exactly as wide as the panels are.
        append(
            "<div style='display:grid;width:fit-content;" +
                "grid-template-columns:repeat($columns,max-content);gap:10px'>"
        )
        for ((label, lines) in panels) {
            val title = label.substringBefore('\n')
            val caption = label.substringAfter('\n', "").ifBlank { null }
            append("<div>")
            append(planeFigureHtml(lines, title, caption, cellWidth, height, padding, window))
            append("</div>")
        }
        append("</div>")
    }
    display(channel, html)
}

private fun escapePlaneHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
