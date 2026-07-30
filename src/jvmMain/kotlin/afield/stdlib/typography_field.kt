@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import afield.Box
import afield.Rect
import afield.graphics.FLine
import afield.graphics.FloatEarcut
import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The bridges out of a [TextLayout] and into the four families Field already has.
 *
 * `typography.kt` answers with *structure* — rings, depths, advances, handles — and structure is not
 * yet a drawing, a mesh, an image or a point cloud. This file is the missing hop, and it is the
 * reason the typography family is worth having at all: once a string is contours, letters get to be
 * inputs to machinery that never knew about text.
 *
 * It is the typographic counterpart of `spatial_field.kt` (plane geometry → `FLine`) and
 * `mesh_field.kt` (`TriMesh` → `FLine`), and it is written to the same rule: every function here
 * returns a *value* — no `Box`, no output — except the two figure helpers at the bottom, which are
 * `drawToSVG` and so work headless, in a test and in the docs build.
 *
 * Five bridges:
 *
 *  1. **→ [FLine]** ([TextLayout.toFLines], [TextLayout.toFLine], [Glyph.toFLine]). One line per
 *     glyph, so a per-glyph handle actually moves something, with the font's real cubics intact.
 *     Filling works because of `typography.kt`'s winding convention and nothing else: Skia and
 *     Java2D both fill non-zero, so a counter is a hole precisely because its ring runs the other
 *     way. [TextLayout.toFLines] takes `honourHoles = false` to show you the blob you get without a
 *     convention, which is the control arm the docs figure uses.
 *  2. **→ [TriMesh]** ([TextLayout.toMesh]). Hole-aware extrusion: `extrudeMesh` in
 *     `mesh_generate.kt:619` and `triangulatePolygon` (`:547`) explicitly cannot do holes, so the
 *     caps here go through `FloatEarcut` with hole indices and the walls are emitted ring by ring
 *     from the *same* flattened vertices, which is what makes the result watertight — and makes an
 *     extruded `o` genus 1 and an extruded `l` genus 0.
 *  3. **→ an SDF** ([TextLayout.toSdf]). A 2D signed-distance grid, bilinearly sampled and extruded
 *     in z, so letters compose with `sdfSphere`, `sdfSmoothUnion`, `.twisted()` and everything else
 *     in `mesh_sdf.kt`, and `marchingCubes` will mesh the result.
 *  4. **→ [Img]** ([rasterizeText]). An analytic even-odd scanline fill of the layout's own rings —
 *     deliberately *not* `Graphics2D.drawString`, both because AWT text rendering in-process is what
 *     `imaging_field.kt:417` forbids and because filling our own outlines means a legible PNG is
 *     evidence about *this* code rather than about AWT's rasteriser.
 *  5. **→ points** ([TextLayout.samplePoints], [TextLayout.toPointFLine]). Even-by-arc-length
 *     samples along the outlines, which is what the alife agents, the clusterers and the
 *     contourers in `clustering.kt` / `alife_agents.kt` consume.
 *
 * Frames: bridges 1, 3, 4 and 5 stay in text space (y **down**, origin on the first baseline);
 * bridge 2 flips y at its boundary, because a mesh wants y up, and re-normalises winding when it
 * does.
 */

// ==============================================================================================
// TextLayout -> FLine
// ==============================================================================================

private fun AffineTransform.tx(p: Vec2): Vec2 {
    val d = transform(Point2D.Double(p.x.toDouble(), p.y.toDouble()), null)
    return Vec2(d.x.toFloat(), d.y.toFloat())
}

private fun AffineTransform.tx3(p: Vec3): Vec3 {
    val d = transform(Point2D.Double(p.x.toDouble(), p.y.toDouble()), null)
    return Vec3(d.x.toFloat(), d.y.toFloat(), p.z)
}

/**
 * This glyph's outline as a drawable [FLine], with the font's cubics kept.
 *
 * One `moveTo`-led subpath per contour, in [Glyph.contours] order, winding per this family's
 * convention — so `filled = true` renders the counters as holes under either fill rule.
 */
fun Glyph.toFLine(
    color: Vec4 = vec(1.0, 1.0, 1.0, 0.9),
    thickness: Double = 1.4,
    filled: Boolean = false,
    stroked: Boolean = true,
): FLine {
    val f = outline.copy()
    f.color = Vec4(color)
    f.thickness = thickness
    f.filled = filled
    f.stroked = stroked
    return f
}

/**
 * One [FLine] per inked glyph, each with that glyph's handles applied.
 *
 * One line per glyph rather than one for the whole string, because that is what makes the handles
 * mean anything: a renderer, an animation or a `showText` figure can move, colour or drop a single
 * letter. Blank glyphs (spaces) contribute nothing.
 *
 * @param honourHoles the **control arm**. `true` is the family's convention (outer rings positive,
 *   holes negative), and both Skia's and Java2D's non-zero fill then punch the counters out. `false`
 *   forces every ring positive, which is what a layer with no winding convention hands you — the
 *   counters fill solid. Nothing in Field should pass `false`; the docs figure and
 *   `TypographyTest.holesNeedTheWindingConvention` do, to show the difference.
 */
fun TextLayout.toFLines(
    color: Vec4 = vec(1.0, 1.0, 1.0, 0.9),
    thickness: Double = 1.4,
    filled: Boolean = false,
    stroked: Boolean = true,
    honourHoles: Boolean = true,
): List<FLine> = inkedGlyphs.map { g ->
    val t = g.transform()
    val src = if (honourHoles) g.glyph.outline else forcePositiveWinding(g.glyph)
    val f = src.transform { p -> t.tx3(p) }
    f.color = Vec4(g.color ?: color)
    f.thickness = thickness
    f.filled = filled
    f.stroked = stroked
    f
}

/**
 * The whole layout as a single [FLine] — every glyph's every ring as subpaths of one line.
 *
 * For when you want one drawable object (an export, a single stroke style, a `byThickening`) and do
 * not care about per-glyph identity.
 */
fun TextLayout.toFLine(
    color: Vec4 = vec(1.0, 1.0, 1.0, 0.9),
    thickness: Double = 1.4,
    filled: Boolean = false,
    stroked: Boolean = true,
): FLine {
    val out = FLine()
    toFLines(color, thickness, filled, stroked).forEach { line -> out.nodes.addAll(line.nodes) }
    out.color = Vec4(color)
    out.thickness = thickness
    out.filled = filled
    out.stroked = stroked
    return out
}

/**
 * Rebuild a glyph's outline with every ring wound positively — the "no convention" control.
 *
 * Reverses the hole subpaths back, which under non-zero fill makes the counter of an `o` disappear
 * into the bowl. Only [TextLayout.toFLines]`(honourHoles = false)` calls it.
 */
private fun forcePositiveWinding(glyph: Glyph): FLine {
    val out = FLine()
    glyph.contours.forEach { ring ->
        val pts = if (ring.signedArea >= 0) ring.points else ring.points.reversed()
        pts.forEachIndexed { i, p -> if (i == 0) out.moveTo(p.x, p.y) else out.lineTo(p.x, p.y) }
        if (pts.isNotEmpty()) out.lineTo(pts[0].x, pts[0].y)
    }
    return out
}

/**
 * Every ring of the layout, with the handles applied, still classified.
 *
 * The workhorse the other bridges share. An affine preserves containment, so [GlyphContour.depth]
 * survives it untouched; a *mirroring* affine (a negative [PlacedGlyph.scale] component) flips the
 * area sign, so the ring is reversed here to keep the convention true — which is why a mirrored
 * letter still meshes and rasterises with its counters intact.
 */
fun TextLayout.toContours(honourHoles: Boolean = true): List<GlyphContour> {
    val out = mutableListOf<GlyphContour>()
    inkedGlyphs.forEach { g ->
        val t = g.transform()
        g.glyph.contours.forEach { ring ->
            val pts = ring.points.map { t.tx(it) }
            // honourHoles = false is the control arm: every ring wound positively, which is what a
            // layer with no winding convention hands its consumers. Under NON_ZERO fill the counter
            // of an `o` then vanishes into the bowl — see rasterizeText's fillRule.
            val wantPositive = !honourHoles || !ring.isHole
            val area = signedAreaOf(pts)
            out.add(GlyphContour(if ((area > 0.0) == wantPositive) pts else pts.reversed(), ring.depth))
        }
    }
    return out
}

private fun signedAreaOf(points: List<Vec2>): Double {
    var s = 0.0
    for (i in points.indices) {
        val a = points[i]
        val b = points[(i + 1) % points.size]
        s += a.x.toDouble() * b.y.toDouble() - b.x.toDouble() * a.y.toDouble()
    }
    return 0.5 * s
}

// ==============================================================================================
// TextLayout -> points
// ==============================================================================================

/**
 * Even-by-arc-length samples along every outline of the layout.
 *
 * The bridge to the point-consuming families: hand these to `kMedoids` in `clustering.kt`, to
 * `vec2DelaunayGraph`, to a Physarum or DLA seed in `alife_agents.kt`, or to `pointCloudFLine` in
 * `spatial_field.kt` to see them. Spacing is in text-space units, so `spacing = size / 12` gives
 * about a dozen samples across an `o`'s stroke.
 */
fun TextLayout.samplePoints(spacing: Double = typeface.size / 20.0): List<Vec2> =
    toContours().flatMap { it.resample(spacing) }

/** [samplePoints] as a dot-drawn [FLine], via `spatial_field.kt`'s [pointCloudFLine]. */
fun TextLayout.toPointFLine(
    spacing: Double = typeface.size / 20.0,
    color: Vec4 = vec(0.55, 0.82, 0.95, 0.9),
    pointSize: Double = 0.6,
): FLine = pointCloudFLine(samplePoints(spacing), color, pointSize)

// ==============================================================================================
// TextLayout -> TriMesh
// ==============================================================================================

/**
 * Extrude the layout into a watertight [TriMesh], counters included.
 *
 * Why this is not `extrudeMesh(polygon, height)`: that function, and the `triangulatePolygon` it
 * leans on (`mesh_generate.kt:619` and `:547`), take a single ring and say so — "holes are not
 * supported". A letter is a ring *system*. So the caps here go straight to `FloatEarcut` with hole
 * indices (the same triangulator `FLine.tessellate` uses, `FLine.kt:1021`), and the walls are built
 * from the identical flattened vertices the caps used, which is the whole trick: cap and wall share
 * vertices, so every edge has exactly two faces and the result is watertight and manifold. That is
 * what makes `mesh.genus()` a meaningful question about a letter — 1 for `o`, 2 for `%`, 0 for `l`.
 *
 * Orientation is worked out, not fixed up afterwards: every cap triangle is emitted with the winding
 * that faces its own cap, and every wall quad with the winding whose normal is `(eᵥ, −eₓ)`, the
 * outward side of any ring under this family's convention. Nothing calls `fixWinding()`, so
 * `signedVolume() > 0` is a real check on this code rather than a restatement of it.
 *
 * @param depth extrusion in text-space units; `0.0` gives a single-sided flat cap (useful, and *not*
 *   watertight — say so if you assert on it).
 * @param yUp flip y on the way out, so the text is the right way up in a y-up 3D world. The flip
 *   mirrors, so the winding is reversed to compensate.
 * @param centre translate so the ink's bounding box is centred on the origin — usually what you want
 *   before handing a mesh to a renderer.
 *
 * Normals are **not** attached, exactly as `extrudeMesh` leaves them off (`mesh_generate.kt:625`):
 * call `flatShaded()` before rendering. That is not a convenience left undone, it is the point —
 * `flatShaded()` un-welds every triangle into its own three vertices, so a mesh that has been through
 * it is no longer topologically anything, and `isWatertight()` / `genus()` on the result would answer
 * about the *render* copy rather than about the extrusion. Measure first, shade second.
 */
fun TextLayout.toMesh(
    depth: Number = typeface.size * 0.25,
    yUp: Boolean = true,
    centre: Boolean = false,
): TriMesh {
    val h = depth.toDouble()
    val solid = h > 1e-9
    val builder = TriMeshBuilder()

    inkedGlyphs.forEach { placed ->
        val t = placed.transform()
        // Ring groups, per glyph: an even-depth ring and the odd-depth rings directly inside it.
        // Deeper nesting (an island inside a counter, as in some display faces) becomes its own
        // group, because an even-depth ring is an outer ring however deep it sits.
        val rings = placed.glyph.contours.map { ring ->
            val pts = ring.points.map { t.tx(it) }
            val wantPositive = !ring.isHole
            val area = signedAreaOf(pts)
            GlyphContour(if ((area > 0.0) == wantPositive) pts else pts.reversed(), ring.depth)
        }
        val outers = rings.filter { it.isOuter }
        outers.forEach { outer ->
            val holes = rings.filter { r ->
                r.isHole && r.depth == outer.depth + 1 && r.points.isNotEmpty() &&
                    pointInRing(outer.points, r.points[0].x.toDouble(), r.points[0].y.toDouble())
            }
            addExtrudedRingGroup(builder, outer, holes, h, solid)
        }
    }

    var mesh = builder.build()
    if (mesh.isEmpty) return mesh
    if (yUp) mesh = mesh.transformed { vec(it.x, -it.y, it.z) }.flipped()
    if (centre) {
        val b = mesh.bounds()
        val c = b.center
        mesh = mesh.translated(-c.x, -c.y, -c.z)
    }
    return mesh
}

/** One outer ring plus its holes: two caps and one wall per ring, all off shared vertices. */
private fun addExtrudedRingGroup(
    builder: TriMeshBuilder,
    outer: GlyphContour,
    holes: List<GlyphContour>,
    depth: Double,
    solid: Boolean,
) {
    val ringList = listOf(outer) + holes
    val total = ringList.sumOf { it.points.size }
    if (outer.points.size < 3) return

    val data = FloatArray(total * 2)
    val holeIndices = IntArray(holes.size)
    var w = 0
    var vertexBase = 0
    ringList.forEachIndexed { ri, ring ->
        if (ri > 0) holeIndices[ri - 1] = vertexBase
        ring.points.forEach { p ->
            data[w++] = p.x
            data[w++] = p.y
        }
        vertexBase += ring.points.size
    }

    val zFront = if (solid) (depth / 2.0) else 0.0
    val zBack = if (solid) (-depth / 2.0) else 0.0

    // Shared vertices. front[i] and back[i] are the same xy, and both the caps and the walls index
    // into them — that identity is what makes the mesh watertight.
    val front = IntArray(total)
    val back = IntArray(total)
    var k = 0
    ringList.forEach { ring ->
        ring.points.forEach { p ->
            front[k] = builder.vertex(p.x.toDouble(), p.y.toDouble(), zFront)
            if (solid) back[k] = builder.vertex(p.x.toDouble(), p.y.toDouble(), zBack)
            k++
        }
    }

    val tris = FloatEarcut.earcut(data, if (holes.isEmpty()) null else holeIndices, 2)
    var ti = 0
    while (ti + 2 < tris.size) {
        val a = tris[ti]
        val b = tris[ti + 1]
        val c = tris[ti + 2]
        ti += 3
        // Earcut normalises its input winding internally, so its output orientation is its business
        // and not ours: measure each triangle and emit it the way round the cap needs. A front cap at
        // +z faces +z when its vertices run counter-clockwise in xy, i.e. positive signed area.
        val ax = data[a * 2].toDouble(); val ay = data[a * 2 + 1].toDouble()
        val bx = data[b * 2].toDouble(); val by = data[b * 2 + 1].toDouble()
        val cx = data[c * 2].toDouble(); val cy = data[c * 2 + 1].toDouble()
        val area2 = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay)
        if (abs(area2) < 1e-12) continue
        if (area2 > 0) builder.triangle(front[a], front[b], front[c])
        else builder.triangle(front[a], front[c], front[b])
        if (solid) {
            if (area2 > 0) builder.triangle(back[a], back[c], back[b])
            else builder.triangle(back[a], back[b], back[c])
        }
    }

    if (!solid) return

    // Walls. For any ring under this family's convention the outward xy normal is (e.y, -e.x): an
    // outer ring runs counter-clockwise so its interior is on the left, and a hole runs clockwise so
    // the *material* is on the left too. One formula, both cases.
    var base = 0
    ringList.forEach { ring ->
        val n = ring.points.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            val fi = front[base + i]
            val fj = front[base + j]
            val bi = back[base + i]
            val bj = back[base + j]
            builder.triangle(fi, bi, fj)
            builder.triangle(fj, bi, bj)
        }
        base += n
    }
}

// ==============================================================================================
// TextLayout -> signed distance
// ==============================================================================================

/**
 * A signed-distance grid over a layout, in text space, negative inside the ink.
 *
 * Built once and sampled bilinearly, because the honest brute-force answer — nearest point over
 * every segment of every ring — costs a few thousand segment tests per query, and `marchingCubes` at
 * resolution 96 asks a million times. The grid pays that cost `nx*ny` times instead.
 *
 * The sign convention is `mesh_sdf.kt`'s (**negative inside**), which is the opposite of
 * [GlyphContour.signedArea]'s. That is not an inconsistency to fix: an area's sign says which way a
 * ring runs, a distance's sign says which side of the ink you are on, and the two live in different
 * files for different consumers.
 *
 * @property bounds the region the grid covers, in text space.
 */
class TextDistanceField internal constructor(
    val bounds: Rect,
    val nx: Int,
    val ny: Int,
    internal val values: FloatArray,
) {
    private val dx = bounds.w / (nx - 1).coerceAtLeast(1)
    private val dy = bounds.h / (ny - 1).coerceAtLeast(1)

    /**
     * Bilinear sample. Outside [bounds] the nearest edge value is used plus the distance to the
     * box — which keeps the field monotone far away, so a marcher never finds a spurious surface out
     * there.
     */
    fun at(x: Double, y: Double): Double {
        val cx = x.coerceIn(bounds.x, bounds.x + bounds.w)
        val cy = y.coerceIn(bounds.y, bounds.y + bounds.h)
        val outside = hypot(x - cx, y - cy)
        val gx = ((cx - bounds.x) / dx).coerceIn(0.0, (nx - 1).toDouble())
        val gy = ((cy - bounds.y) / dy).coerceIn(0.0, (ny - 1).toDouble())
        val i0 = gx.toInt().coerceAtMost(nx - 1)
        val j0 = gy.toInt().coerceAtMost(ny - 1)
        val i1 = (i0 + 1).coerceAtMost(nx - 1)
        val j1 = (j0 + 1).coerceAtMost(ny - 1)
        val fx = gx - i0
        val fy = gy - j0
        val v00 = values[j0 * nx + i0]
        val v10 = values[j0 * nx + i1]
        val v01 = values[j1 * nx + i0]
        val v11 = values[j1 * nx + i1]
        val v = (v00 * (1 - fx) + v10 * fx) * (1 - fy) + (v01 * (1 - fx) + v11 * fx) * fy
        return v + outside
    }

    /** Bilinear sample at a point. */
    fun at(p: Vec2): Double = at(p.x.toDouble(), p.y.toDouble())

    /** The grid as a one-channel [Img], distance mapped to 0..1 — for looking at the field itself. */
    fun toImg(): Img {
        val span = values.maxOf { abs(it) }.coerceAtLeast(1e-6f)
        return Img.generate(nx, ny) { x, y -> 0.5f + 0.5f * values[y * nx + x] / span }
    }

    /** Largest inscribed distance: how far inside the ink you can get. A stroke-weight measure. */
    fun deepestInside(): Double = -(values.minOrNull()?.toDouble() ?: 0.0)
}

/**
 * Build a [TextDistanceField] over this layout.
 *
 * @param resolution cells along the longer axis of the padded bounds.
 * @param margin extra room around the ink, as a fraction of its longer side. Some room matters: a
 *   field that stops at the ink has nowhere to be positive, and a marcher then produces open shells.
 */
fun TextLayout.distanceField(resolution: Int = 256, margin: Double = 0.15): TextDistanceField {
    require(resolution >= 4) { "resolution must be >= 4, got $resolution" }
    val rings = toContours().filter { it.points.size >= 3 }
    val ink = bounds
    require(rings.isNotEmpty() && ink.w > 0 && ink.h > 0) { "layout has no inked geometry to measure" }

    val pad = max(ink.w, ink.h) * margin
    val b = Rect(ink.x - pad, ink.y - pad, ink.w + 2 * pad, ink.h + 2 * pad)
    val longer = max(b.w, b.h)
    val nx = max(4, ceil(resolution * b.w / longer).toInt())
    val ny = max(4, ceil(resolution * b.h / longer).toInt())

    // Ring bounding boxes, so a query far from a ring skips its segments entirely. On a word this is
    // the difference between "every glyph every time" and "the glyph you are standing on".
    val ringBounds = rings.map { it.bounds }
    val values = FloatArray(nx * ny)
    val dx = b.w / (nx - 1)
    val dy = b.h / (ny - 1)

    for (j in 0 until ny) {
        val y = b.y + j * dy
        for (i in 0 until nx) {
            val x = b.x + i * dx
            var best = Double.MAX_VALUE
            var inside = false
            rings.forEachIndexed { ri, ring ->
                val rb = ringBounds[ri]
                // Cheap reject: if the box is already further away than the best segment so far,
                // nothing in this ring can win.
                val bd = boxDistance(x, y, rb)
                if (bd * bd <= best) {
                    val pts = ring.points
                    for (s in pts.indices) {
                        val a = pts[s]
                        val c = pts[(s + 1) % pts.size]
                        val d2 = pointSegmentDistance(x, y, a, c)
                        if (d2 < best) best = d2
                    }
                }
                if (pointInRing(ring.points, x, y)) inside = !inside
            }
            val d = if (best == Double.MAX_VALUE) 0.0 else sqrt(best)
            values[j * nx + i] = (if (inside) -d else d).toFloat()
        }
    }
    return TextDistanceField(b, nx, ny, values)
}

private fun boxDistance(x: Double, y: Double, r: Rect): Double {
    val ddx = max(max(r.x - x, 0.0), x - (r.x + r.w))
    val ddy = max(max(r.y - y, 0.0), y - (r.y + r.h))
    return hypot(ddx, ddy)
}

/**
 * A layout as a 3D signed-distance field, ready for `marchingCubes` and every combinator in
 * `mesh_sdf.kt`.
 *
 * This is the bridge that makes letters first-class in the SDF family: `sdfSmoothUnion(text, sphere,
 * 0.1)` melts a word into a ball, `.twisted(0.4)` wrings it, `.shell(0.02)` hollows it. The 2D
 * distance is extruded with the exact prism formula `min(max(d, dz), 0) + |max(d, 0), max(dz, 0)|`
 * rather than `max(d, dz)`, so the edges are properly rounded-off-square instead of over-estimating
 * near the corners — which matters as soon as you `.rounded()` or smooth-union it.
 *
 * The field is delivered in **normalised, y-up, origin-centred** coordinates: the layout's ascent
 * becomes 1.0, so `bounds` is a couple of units across whatever the point size was, which is the
 * scale `boundsAround()` and the SDF primitives already speak.
 *
 * Note the `mesh_sdf.kt` contract (`:61-66`): the marcher reuses one scratch `Vec3`, so the returned
 * lambda only ever *reads* its argument.
 *
 * @param depth extrusion thickness in normalised units (1.0 = one ascent).
 * @param gridResolution cells along the long axis of the 2D distance grid. This, not the marching
 *   resolution, is what limits how crisp a serif can be.
 */
fun TextLayout.toSdf(
    depth: Number = 0.25,
    gridResolution: Int = 320,
    margin: Double = 0.2,
): TextSdf {
    val df = distanceField(gridResolution, margin)
    val ink = bounds
    val s = 1.0 / ascent
    val cx = ink.cx
    val cy = ink.cy
    val h = depth.toDouble() / 2.0

    // Normalised (x right, y up, centred) -> text space (x right, y down, baseline origin).
    val field: ScalarField3D = { p ->
        val tx = p.x / s + cx
        val ty = -p.y / s + cy
        val d = df.at(tx, ty) * s
        val dz = abs(p.z.toDouble()) - h
        val outX = max(d, 0.0)
        val outZ = max(dz, 0.0)
        (min(max(d, dz), 0.0) + hypot(outX, outZ)).toFloat()
    }

    val halfW = ink.w * s / 2.0
    val halfH = ink.h * s / 2.0
    val pad = max(halfW, halfH) * margin + h
    val bounds3 = AxisAlignedBoundingBox3D(
        vec(-halfW - pad, -halfH - pad, -h - pad),
        vec(halfW + pad, halfH + pad, h + pad)
    )
    return TextSdf(field, bounds3, df, s)
}

/**
 * A layout's SDF plus the bounds it lives in — the pair `marchingCubes` wants.
 *
 * @property field negative inside the letters; safe to compose with any `ScalarField3D`.
 * @property bounds a box comfortably containing the surface, so [march] needs no arguments.
 * @property distanceField the 2D grid underneath, if you want to look at it or measure it.
 * @property scale text-space units per normalised unit's reciprocal — multiply a text-space length by
 *   this to get a normalised one.
 */
class TextSdf internal constructor(
    val field: ScalarField3D,
    val bounds: AxisAlignedBoundingBox3D,
    val distanceField: TextDistanceField,
    val scale: Double,
) {
    /** March the field. `resolution` is cells along the longest axis, as in `marchingCubes`. */
    fun march(resolution: Int = 96, vertexNormals: Boolean = true): TriMesh =
        marchingCubes(field, bounds, resolution, 0.0, true, vertexNormals)
}

// ==============================================================================================
// TextLayout -> Img
// ==============================================================================================

/**
 * How a scanline decides whether a crossing count means "inside".
 *
 * The two rules a filled outline can be interpreted under, and the reason the winding convention in
 * `typography.kt` is load-bearing rather than tidy:
 *
 *  - [NON_ZERO] sums the *directions* of the crossings, and is what Skia (`someSkiko.kt`'s
 *    `outputTo`) and Java2D's `GeneralPath` both do by default. A counter is a hole under this rule
 *    **only** if its ring runs the opposite way to its bowl — which is exactly what this family
 *    guarantees, and exactly what [TextLayout.toContours]`(honourHoles = false)` takes away.
 *  - [EVEN_ODD] counts crossings and ignores their direction, so it depends on nesting parity
 *    instead. Robust to a broken winding, blind to a self-overlapping stroke.
 *
 * Two rules that agree on correct input and disagree on broken input is a control arm, so both are
 * here on purpose.
 */
enum class TextFillRule { NON_ZERO, EVEN_ODD }

/**
 * Rasterise a layout by filling its own rings: an analytic-in-x, supersampled-in-y scanline fill.
 *
 * Deliberately not `Graphics2D.drawString`. Two reasons, and the second is the important one:
 * `imaging_field.kt:417-425` forbids in-process AWT text rendering (AppKit versus GLFW's main run
 * loop), and — more to the point — a PNG produced by AWT's rasteriser would be evidence that *AWT*
 * can draw text. Filling the contours this family extracted makes a legible PNG evidence about the
 * extraction, the winding and the layout.
 *
 * Coverage is exact horizontally (a span contributes its real overlap with each pixel) and
 * [supersample]-fold vertically, which is enough to read 14pt type.
 *
 * @param window the text-space rectangle mapped onto the image; defaults to the inked bounds. Pass
 *   the same window for several layouts and they share a scale — the way `planeFigureHtml` does.
 * @param rings override the geometry, keeping the fit and the fill — the hook the control-arm figure
 *   uses to raster `toContours(honourHoles = false)` through the identical filler.
 * @return a one-channel coverage [Img], 0 outside the ink and 1 well inside it. `.toRGB()`,
 *   `montage(...)` and `savePng(...)` all take it from here.
 */
fun rasterizeText(
    layout: TextLayout,
    width: Int,
    height: Int,
    margin: Double = 0.06,
    supersample: Int = 4,
    window: Rect? = null,
    fillRule: TextFillRule = TextFillRule.NON_ZERO,
    rings: List<GlyphContour> = layout.toContours(),
): Img {
    require(width > 0 && height > 0) { "rasterizeText needs a positive size, got ${width}x$height" }
    require(supersample >= 1) { "supersample must be >= 1, got $supersample" }
    val img = Img.zeros(width, height, 1)
    val poly = rings.filter { it.points.size >= 3 }
    if (poly.isEmpty()) return img

    val src = window ?: layout.bounds
    if (src.w <= 0 || src.h <= 0) return img

    // Fit preserving aspect, centred, with a margin — the same convention planeFigureHtml uses, so a
    // raster panel and an SVG panel of the same layout frame it the same way.
    val availW = width * (1 - 2 * margin)
    val availH = height * (1 - 2 * margin)
    val scale = min(availW / src.w, availH / src.h)
    val offX = width / 2.0 - (src.x + src.w / 2.0) * scale
    val offY = height / 2.0 - (src.y + src.h / 2.0) * scale

    // One flat edge array in pixel space. Horizontal edges are dropped: they never cross a scanline
    // and keeping them only risks a double count at a vertex.
    val ex0 = ArrayList<Double>()
    val ey0 = ArrayList<Double>()
    val ex1 = ArrayList<Double>()
    val ey1 = ArrayList<Double>()
    poly.forEach { ring ->
        val pts = ring.points
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            val ay = a.y * scale + offY
            val by = b.y * scale + offY
            if (ay == by) continue
            ex0.add(a.x * scale + offX); ey0.add(ay)
            ex1.add(b.x * scale + offX); ey1.add(by)
        }
    }
    if (ex0.isEmpty()) return img

    val n = ex0.size
    val ax = DoubleArray(n) { ex0[it] }
    val ay = DoubleArray(n) { ey0[it] }
    val bx = DoubleArray(n) { ex1[it] }
    val by = DoubleArray(n) { ey1[it] }

    // Crossings, plus a parallel array of edge directions — NON_ZERO needs to know which way the
    // edge was going and EVEN_ODD does not care.
    val xs = DoubleArray(n)
    val dir = IntArray(n)
    val weight = (1.0 / supersample).toFloat()

    for (py in 0 until height) {
        for (k in 0 until supersample) {
            val sy = py + (k + 0.5) / supersample
            var count = 0
            for (e in 0 until n) {
                val y0 = ay[e]
                val y1 = by[e]
                // Half-open in y, so a vertex shared by two edges is counted exactly once.
                if (sy >= y0 && sy < y1) {
                    xs[count] = ax[e] + (sy - y0) / (y1 - y0) * (bx[e] - ax[e])
                    dir[count] = 1
                    count++
                } else if (sy >= y1 && sy < y0) {
                    xs[count] = ax[e] + (sy - y0) / (y1 - y0) * (bx[e] - ax[e])
                    dir[count] = -1
                    count++
                }
            }
            if (count < 2) continue
            // Insertion sort over the two parallel arrays. A scanline through a word crosses a few
            // dozen edges, so this beats boxing indices to hand java.util.Arrays a comparator.
            for (i in 1 until count) {
                val x = xs[i]
                val d = dir[i]
                var j = i - 1
                while (j >= 0 && xs[j] > x) {
                    xs[j + 1] = xs[j]; dir[j + 1] = dir[j]; j--
                }
                xs[j + 1] = x; dir[j + 1] = d
            }

            when (fillRule) {
                TextFillRule.EVEN_ODD -> {
                    var c = 0
                    while (c + 1 < count) {
                        addSpan(img, py, xs[c], xs[c + 1], weight)
                        c += 2
                    }
                }

                TextFillRule.NON_ZERO -> {
                    var wind = 0
                    for (c in 0 until count - 1) {
                        wind += dir[c]
                        if (wind != 0) addSpan(img, py, xs[c], xs[c + 1], weight)
                    }
                }
            }
        }
    }
    return img
}

/** Add [w] coverage over [x0, x1) of row [py], with real fractional coverage at both ends. */
private fun addSpan(img: Img, py: Int, x0: Double, x1: Double, w: Float) {
    if (x1 <= x0) return
    val lo = max(0.0, x0)
    val hi = min(img.width.toDouble(), x1)
    if (hi <= lo) return
    var px = lo.toInt()
    while (px < hi) {
        val cellLo = max(lo, px.toDouble())
        val cellHi = min(hi, (px + 1).toDouble())
        if (cellHi > cellLo) {
            val idx = img.index(px, py, 0)
            img.data[idx] = min(1f, img.data[idx] + w * (cellHi - cellLo).toFloat())
        }
        px++
    }
}

/** [rasterizeText] with the layout as receiver. */
fun TextLayout.toImg(
    width: Int,
    height: Int,
    margin: Double = 0.06,
    supersample: Int = 4,
    window: Rect? = null,
    fillRule: TextFillRule = TextFillRule.NON_ZERO,
): Img = rasterizeText(this, width, height, margin, supersample, window, fillRule)

/**
 * What fraction of the raster the ink covers.
 *
 * A blunt but effective liveness check for a capture: a garbled or empty render is near 0, a
 * bucket-filled one is near 1, and real type at a readable size lands in between. `TypographyGallery`
 * asserts on it before it writes a PNG, for exactly the reason the disk-pressure notes in
 * `dev_docs` keep rediscovering — a plausible-looking blank image is the failure that gets shipped.
 */
fun Img.inkFraction(threshold: Float = 0.5f): Double {
    var hits = 0
    var total = 0
    for (y in 0 until height) for (x in 0 until width) {
        total++
        if (at(x, y, 0) >= threshold) hits++
    }
    return if (total == 0) 0.0 else hits.toDouble() / total
}

// ==============================================================================================
// Figures
// ==============================================================================================

/**
 * A titled figure of a layout in a box output — `drawToSVG`, so no AWT rasteriser and no GPU.
 *
 * Defaults to `filled = true`, because filled is where the winding convention shows: a figure of the
 * word "geography" is an immediate answer to "are the counters holes?".
 *
 * @param showBaseline draw the baseline and the ascent/descent lines, which turns the figure into a
 *   metrics diagram.
 */
fun Box.showText(
    layout: TextLayout,
    channel: String = "main",
    title: String? = null,
    caption: String? = null,
    width: Int = 720,
    height: Int = 260,
    filled: Boolean = true,
    stroked: Boolean = false,
    color: Vec4 = vec(0.93, 0.96, 1.0, 1.0),
    showBaseline: Boolean = false,
) {
    val lines = mutableListOf<FLine>()
    if (showBaseline) lines.addAll(layout.metricsFLines())
    lines.addAll(layout.toFLines(color = color, filled = filled, stroked = stroked, thickness = 1.0))
    display(channel, planeFigureHtml(lines, title, caption, width, height))
}

/** Several [showText] figures side by side; `label` may carry a caption after a newline. */
fun Box.showTextGrid(
    vararg panels: Pair<String, TextLayout>,
    channel: String = "main",
    columns: Int = 2,
    cellWidth: Int = 420,
    cellHeight: Int = -1,
    filled: Boolean = true,
    stroked: Boolean = false,
    color: Vec4 = vec(0.93, 0.96, 1.0, 1.0),
) {
    require(panels.isNotEmpty()) { "showTextGrid needs at least one panel" }
    showPlaneGrid(
        *panels.map { (label, layout) ->
            label to layout.toFLines(color = color, filled = filled, stroked = stroked, thickness = 1.0)
        }.toTypedArray(),
        channel = channel,
        columns = columns,
        cellWidth = cellWidth,
        cellHeight = cellHeight,
    )
}

/**
 * The layout's metric lines — baseline, ascent, descent, and the advance box — as drawable [FLine]s.
 *
 * A diagram of what layout decided, which is the fastest way to see that tracking went where you
 * meant it to and that a multi-line block is spaced by the font's own leading.
 */
fun TextLayout.metricsFLines(
    baselineColor: Vec4 = vec(0.35, 0.75, 0.95, 0.65),
    guideColor: Vec4 = vec(0.45, 0.52, 0.62, 0.4),
): List<FLine> {
    val out = mutableListOf<FLine>()
    val w = advanceWidth
    lines.forEach { line ->
        out.add(fline {
            moveTo(0.0, line.baselineY); lineTo(w, line.baselineY)
            color = Vec4(baselineColor); thickness = 1.2
        })
        listOf(line.baselineY - ascent, line.baselineY + descent).forEach { y ->
            out.add(fline {
                moveTo(0.0, y); lineTo(w, y)
                color = Vec4(guideColor); thickness = 0.7
            })
        }
    }
    return out
}

/**
 * A per-glyph advance-box diagram: one rectangle per glyph, at its handles.
 *
 * The picture of the layout's *decisions* rather than its letters, and the honest way to see what
 * tracking, kerning or `onCurve` did — the boxes move even for a space, which the glyphs do not.
 */
fun TextLayout.advanceBoxFLines(color: Vec4 = vec(0.95, 0.62, 0.35, 0.55)): List<FLine> = glyphs.map { g ->
    val t = g.transform()
    val a = g.glyph.advance
    val corners = listOf(
        Vec2(0f, (-ascent).toFloat()), Vec2(a.toFloat(), (-ascent).toFloat()),
        Vec2(a.toFloat(), descent.toFloat()), Vec2(0f, descent.toFloat())
    ).map { t.tx(it) }
    fline {
        moveTo(corners[0].x, corners[0].y)
        corners.drop(1).forEach { lineTo(it.x, it.y) }
        lineTo(corners[0].x, corners[0].y)
        this.color = Vec4(color)
        thickness = 0.8
    }
}
