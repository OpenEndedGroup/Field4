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
 */
fun pointCloudFLine(
    points: List<Vec2>,
    color: Vec4 = vec(1.0, 1.0, 1.0, 0.85),
    pointSize: Double = 3.4,
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
    pointSize: Double = 4.0,
    centerSize: Double = 10.0,
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
 * The lines are scaled and translated to fit ([List<FLine>.fitInto]), preserving aspect, so the
 * caller's coordinate system does not have to be anything in particular. Titles and captions are
 * inserted as text, so do not pass HTML you did not build.
 */
fun planeFigureHtml(
    lines: List<FLine>,
    title: String? = null,
    caption: String? = null,
    width: Int = 640,
    height: Int = 460,
    padding: Double = 14.0,
): String {
    val frame = Rect(0.0, 0.0, width.toDouble(), height.toDouble())
    val fitted = lines.fitInto(frame, padding = padding)
    val svg = drawToSVG(fitted, width.toDouble(), height.toDouble())
    val titleHtml = title?.let {
        "<div style=\"font-family:monospace;font-size:12px;font-weight:700;letter-spacing:0.04em;" +
            "color:#91a4ba;margin-bottom:8px\">${escapePlaneHtml(it)}</div>"
    } ?: ""
    val captionHtml = caption?.let {
        "<div style=\"font-family:monospace;font-size:11px;line-height:1.5;color:#7f92a8;" +
            "margin-top:8px\">${escapePlaneHtml(it)}</div>"
    } ?: ""
    return "<div style=\"padding:12px;background:linear-gradient(180deg,#0d1219,#090d12);" +
        "border:1px solid #202b38;border-radius:10px\">$titleHtml" +
        "<div style=\"line-height:0\">$svg</div>$captionHtml</div>"
}

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
) {
    display(channel, planeFigureHtml(lines, title, caption, width, height, padding))
}

/**
 * Several plane figures side by side, each titled — the 2D twin of `Box.showMeshGrid`, and the
 * fastest way to see a whole pipeline (sites → triangulation → dual → clipped) at a glance.
 *
 * A title may contain newlines; everything after the first becomes the figure's caption, which is
 * how you get the numbers that carry the argument under the picture they are about.
 *
 * @param columns figures per row.
 */
fun Box.showPlaneGrid(
    vararg panels: Pair<String, List<FLine>>,
    channel: String = "main",
    columns: Int = 2,
    cellWidth: Int = 420,
    cellHeight: Int = -1,
    padding: Double = 12.0,
) {
    require(panels.isNotEmpty()) { "showPlaneGrid needs at least one panel" }
    val height = if (cellHeight > 0) cellHeight else (cellWidth * 0.78).toInt()
    val html = buildString {
        append("<div style='display:grid;grid-template-columns:repeat($columns,minmax(0,1fr));gap:10px'>")
        for ((label, lines) in panels) {
            val title = label.substringBefore('\n')
            val caption = label.substringAfter('\n', "").ifBlank { null }
            append("<div>")
            append(planeFigureHtml(lines, title, caption, cellWidth, height, padding))
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
