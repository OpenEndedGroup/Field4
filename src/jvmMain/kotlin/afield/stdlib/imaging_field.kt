@file:afield.CompletionCategory("Imaging")

package afield.stdlib

import afield.Box
import afield.graphics.FLine
import afield.graphics.polylineFrom
import afield.rectsl.Color4
import afield.rectsl.Colormaps
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * What makes the imaging family *Field's* rather than a generic CV library: the bridges out of the
 * raster and into the things Field already draws, colours and displays.
 *
 * Three bridges:
 *
 *  1. **Structure → [FLine].** Every detector in `imaging_features.kt` produces geometry, and
 *     geometry in Field is an `FLine`. [Img.toContourFLines], [cornersToFLines], [linesToFLines],
 *     [FlowField.toFLines] and [Img.isoContourFLines] hand you drawable lines, so a Canny result
 *     goes straight into the same drawing/animation/export machinery as anything hand-drawn.
 *  2. **Scalar → colour.** [Img.colormap] runs a 1-channel response image through the same
 *     [Colormaps] tables RectSL's `heat`/`bars`/`scatter` use, so a Harris response, a distance
 *     transform and a RectSL chart are coloured by one source of truth.
 *  3. **Value → screen.** [Img] is a [afield.ToHTML], so evaluating one in a box shows the picture;
 *     [Box.showImg] pushes a full-size version to an output channel, and [Box.showImgGrid] lays
 *     several out side by side with captions — the fastest way to see a whole pipeline at once.
 *
 * The scalar-field bridge runs the other way too: [Img.asScalarField2D] adapts an [Img] to the
 * `ScalarField2D` type `contours.kt` marches, so Field's existing marching-squares iso-contour
 * extractor works on image data with no glue.
 */

// ==============================================================================================
// Structure -> FLine
// ==============================================================================================

/** Convert a point list in image coordinates to an [FLine] polyline. */
fun contourToFLine(points: List<Vec2>, closed: Boolean = true, z: Number = 0): FLine =
    polylineFrom(points, z, closed)

/** Convert many point lists (e.g. from [Img.traceContours]) to one [FLine] each. */
fun contoursToFLines(contours: List<List<Vec2>>, closed: Boolean = true, z: Number = 0): List<FLine> =
    contours.filter { it.size >= 2 }.map { polylineFrom(it, z, closed) }

/**
 * Trace this binary mask's blob outlines and hand them back as [FLine]s — the one-call path from
 * "I thresholded an image" to "I have drawable geometry".
 *
 * @param simplifyTolerance if > 0, run Ramer–Douglas–Peucker at this pixel tolerance first, which
 *   turns a staircase pixel boundary into a handful of straight runs (a 500-point square outline
 *   becomes ~5 points at tolerance 1.0).
 */
fun Img.toContourFLines(minPerimeter: Int = 8, simplifyTolerance: Double = 1.0, z: Number = 0): List<FLine> {
    val raw = traceContours(minPerimeter)
    val pts = if (simplifyTolerance > 0.0) raw.map { simplifyPolyline(it, simplifyTolerance) } else raw
    return contoursToFLines(pts, closed = true, z = z)
}

/**
 * Ramer–Douglas–Peucker polyline simplification: recursively keep the point furthest from the
 * chord until every dropped point is within [tolerance] of the retained line. Exposed on its own
 * because pixel-traced contours are unusable without it.
 */
fun simplifyPolyline(points: List<Vec2>, tolerance: Double): List<Vec2> {
    if (points.size < 3 || tolerance <= 0.0) return points
    val keep = BooleanArray(points.size)
    keep[0] = true
    keep[points.size - 1] = true
    val stack = ArrayDeque<Pair<Int, Int>>()
    stack.addLast(0 to points.size - 1)
    while (stack.isNotEmpty()) {
        val (a, b) = stack.removeLast()
        if (b <= a + 1) continue
        val ax = points[a].x.toDouble(); val ay = points[a].y.toDouble()
        val bx = points[b].x.toDouble(); val by = points[b].y.toDouble()
        val dx = bx - ax; val dy = by - ay
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        var worst = -1.0
        var worstAt = -1
        for (i in a + 1 until b) {
            val px = points[i].x.toDouble(); val py = points[i].y.toDouble()
            val d = if (len < 1e-12) kotlin.math.hypot(px - ax, py - ay)
            else abs(dy * px - dx * py + bx * ay - by * ax) / len
            if (d > worst) { worst = d; worstAt = i }
        }
        if (worst > tolerance && worstAt > 0) {
            keep[worstAt] = true
            stack.addLast(a to worstAt)
            stack.addLast(worstAt to b)
        }
    }
    return points.filterIndexed { i, _ -> keep[i] }
}

/**
 * Draw each corner as a small cross (or box) [FLine] at its location, sized by [size].
 * @param box `true` draws an axis-aligned square outline instead of a cross
 */
fun cornersToFLines(corners: List<Corner>, size: Double = 4.0, box: Boolean = false, z: Number = 0): List<FLine> =
    corners.map { c ->
        if (box) polylineFrom(
            listOf(
                Vec2((c.x - size).toFloat(), (c.y - size).toFloat()),
                Vec2((c.x + size).toFloat(), (c.y - size).toFloat()),
                Vec2((c.x + size).toFloat(), (c.y + size).toFloat()),
                Vec2((c.x - size).toFloat(), (c.y + size).toFloat())
            ), z, true
        ) else {
            val f = FLine()
            f.moveTo(c.x - size, c.y, z)
            f.lineTo(c.x + size, c.y, z)
            f.moveTo(c.x, c.y - size, z)
            f.lineTo(c.x, c.y + size, z)
            f
        }
    }

/** Clip each [HoughLine] to a `w x h` image rectangle and return the visible segments as [FLine]s. */
fun linesToFLines(lines: List<HoughLine>, w: Int, h: Int, z: Number = 0): List<FLine> =
    lines.mapNotNull { it.clipTo(w, h) }.map { (a, b) -> polylineFrom(listOf(a, b), z, false) }

/** Draw each [HoughCircle] as a closed polygon of [segments] sides. */
fun circlesToFLines(circles: List<HoughCircle>, segments: Int = 48, z: Number = 0): List<FLine> =
    circles.map { c ->
        polylineFrom(
            (0 until segments).map { i ->
                val a = 2.0 * Math.PI * i / segments
                Vec2((c.x + c.radius * cos(a)).toFloat(), (c.y + c.radius * sin(a)).toFloat())
            }, z, true
        )
    }

/**
 * Draw a sparse optical-flow field as one arrow [FLine] per tracked point — shaft plus a two-stroke
 * arrowhead, so direction is visible and not just magnitude.
 *
 * @param scale multiply displacements (small real motions are otherwise invisible)
 * @param minMagnitude skip near-stationary tracks
 * @param headSize arrowhead length in pixels
 */
fun FlowField.toFLines(
    scale: Double = 1.0,
    minMagnitude: Double = 0.25,
    headSize: Double = 3.0,
    z: Number = 0
): List<FLine> = tracked().mapNotNull { v ->
    val dx = v.dx * scale
    val dy = v.dy * scale
    if (kotlin.math.hypot(dx, dy) < minMagnitude) return@mapNotNull null
    val ex = v.x0 + dx
    val ey = v.y0 + dy
    val ang = atan2(dy, dx)
    val f = FLine()
    f.moveTo(v.x0, v.y0, z)
    f.lineTo(ex, ey, z)
    f.lineTo(ex - headSize * cos(ang - 0.4), ey - headSize * sin(ang - 0.4), z)
    f.moveTo(ex, ey, z)
    f.lineTo(ex - headSize * cos(ang + 0.4), ey - headSize * sin(ang + 0.4), z)
    f
}

/**
 * Adapt this 1-channel image to the `ScalarField2D` that `contours.kt` marches — the image becomes
 * a bilinearly-interpolated continuous field over `[0, width-1] x [0, height-1]`.
 */
fun Img.asScalarField2D(border: Border = Border.CLAMP): ScalarField2D {
    require(channels == 1) { "asScalarField2D expects a 1-channel image; call .luma() first" }
    return { x, y -> sampleBilinear(x, y, 0, border).toDouble() }
}

/**
 * Extract smooth iso-contours at [iso] through Field's existing marching-squares extractor and
 * return them as [FLine]s. Unlike [toContourFLines] (which traces the pixel boundary of a binary
 * mask) this interpolates between samples, so the contour is sub-pixel smooth — the right tool for
 * a distance transform, a blurred response or a height field.
 *
 * @param cells grid resolution; defaults to one cell per pixel
 */
fun Img.isoContourFLines(iso: Double, cells: Int = max(width, height), z: Number = 0): List<FLine> {
    require(channels == 1) { "isoContourFLines expects a 1-channel image; call .luma() first" }
    require(cells >= 2) { "cells must be >= 2, got $cells" }
    val nx = max(2, (cells.toDouble() * width / max(width, height)).toInt())
    val ny = max(2, (cells.toDouble() * height / max(width, height)).toInt())
    val contours = generateIsoContours(
        asScalarField2D(),
        0.0, (width - 1).toDouble(),
        0.0, (height - 1).toDouble(),
        nx, ny, iso
    )
    return contoursToFLines(contours, closed = false, z = z)
}

// ==============================================================================================
// Scalar -> colour
// ==============================================================================================

/**
 * Colour-map a 1-channel image through one of Field's [Colormaps] ramps (`viridis`, `magma`,
 * `inferno`, `plasma`, `turbo`, `coolwarm`, `grayscale`), returning a 3-channel RGB image.
 *
 * @param name colormap name; see [Colormaps.names]
 * @param lo/[hi] the sample values that map to the ends of the ramp; `null` uses the image's own
 *   min/max, which is almost always what you want for a response image
 */
fun Img.colormap(name: String = "viridis", lo: Float? = null, hi: Float? = null): Img {
    require(channels == 1) { "colormap expects a 1-channel image; call .luma() or .channel(c) first" }
    val l = lo ?: min()
    val h = hi ?: max()
    val d = if (h - l < 1e-12f) 1f else h - l
    // Precompute a 256-entry LUT rather than interpolating the stops per pixel.
    val lut = Array(256) { Colormaps.sample(name, it / 255.0) }
    val out = FloatArray(pixelCount * 3)
    for (p in 0 until pixelCount) {
        val t = ((data[p] - l) / d).coerceIn(0f, 1f)
        val c = lut[(t * 255f).toInt().coerceIn(0, 255)]
        out[p * 3] = c.r.toFloat()
        out[p * 3 + 1] = c.g.toFloat()
        out[p * 3 + 2] = c.b.toFloat()
    }
    return Img(width, height, 3, out)
}

/**
 * Colour-map with a symmetric range around zero — the correct default for a **signed** image
 * (a Laplacian, a difference of Gaussians, a gradient component), where the meaningful thing is
 * which side of zero a pixel is on. Pair with a diverging map like `coolwarm`.
 */
fun Img.colormapDiverging(name: String = "coolwarm"): Img {
    require(channels == 1) { "colormapDiverging expects a 1-channel image" }
    val m = max(abs(min()), abs(max()))
    return colormap(name, -m, m)
}

/**
 * Tint a binary mask a single colour over a transparent background — for overlaying a detection on
 * the original picture with [overlay].
 */
fun Img.maskToRGBA(color: Color4 = Color4(1.0, 0.35, 0.15), alpha: Double = 1.0): Img {
    require(channels == 1) { "maskToRGBA expects a 1-channel mask" }
    val out = FloatArray(pixelCount * 4)
    for (p in 0 until pixelCount) {
        val a = (data[p].coerceIn(0f, 1f) * alpha).toFloat()
        out[p * 4] = color.r.toFloat()
        out[p * 4 + 1] = color.g.toFloat()
        out[p * 4 + 2] = color.b.toFloat()
        out[p * 4 + 3] = a
    }
    return Img(width, height, 4, out)
}

/**
 * Alpha-composite [top] (RGBA, or RGB treated as opaque) over this image, returning RGB.
 * The standard way to show a mask/overlay on top of the source picture.
 */
fun Img.overlay(top: Img, opacity: Double = 1.0): Img {
    require(width == top.width && height == top.height) { "overlay sizes must match" }
    val base = toRGB()
    val out = FloatArray(pixelCount * 3)
    for (p in 0 until pixelCount) {
        val a = (if (top.channels == 4) top.data[p * 4 + 3] else 1f) * opacity.toFloat()
        for (c in 0 until 3) {
            val t = if (top.channels == 1) top.data[p] else top.data[p * top.channels + c]
            out[p * 3 + c] = base.data[p * 3 + c] * (1f - a) + t * a
        }
    }
    return Img(width, height, 3, out)
}

/** Lay images out left-to-right into one strip; heights are matched by scaling to the tallest. */
fun hstack(vararg images: Img, gap: Int = 8, background: Float = 0.08f): Img {
    require(images.isNotEmpty()) { "hstack needs at least one image" }
    val h = images.maxOf { it.height }
    val scaled = images.map { if (it.height == h) it.toRGB() else it.resize((it.width.toDouble() * h / it.height).toInt().coerceAtLeast(1), h).toRGB() }
    val w = scaled.sumOf { it.width } + gap * (scaled.size - 1)
    val out = Img.filled(w, h, 3, background)
    var x0 = 0
    for (s in scaled) {
        for (y in 0 until s.height) for (x in 0 until s.width) for (c in 0 until 3) {
            out.data[out.index(x0 + x, y, c)] = s.data[s.index(x, y, c)]
        }
        x0 += s.width + gap
    }
    return out
}

/** Lay images out top-to-bottom into one column; widths are matched by scaling to the widest. */
fun vstack(vararg images: Img, gap: Int = 8, background: Float = 0.08f): Img {
    require(images.isNotEmpty()) { "vstack needs at least one image" }
    val w = images.maxOf { it.width }
    val scaled = images.map { if (it.width == w) it.toRGB() else it.resize(w, (it.height.toDouble() * w / it.width).toInt().coerceAtLeast(1)).toRGB() }
    val h = scaled.sumOf { it.height } + gap * (scaled.size - 1)
    val out = Img.filled(w, h, 3, background)
    var y0 = 0
    for (s in scaled) {
        for (y in 0 until s.height) for (x in 0 until s.width) for (c in 0 until 3) {
            out.data[out.index(x, y0 + y, c)] = s.data[s.index(x, y, c)]
        }
        y0 += s.height + gap
    }
    return out
}

// ==============================================================================================
// Value -> screen
// ==============================================================================================

/**
 * Push an [Img] to one of this box's output channels as a real PNG, at up to [maxSide] pixels on
 * the long edge. Signed / out-of-range images are normalised for display (and the caption says so).
 *
 * This is the AWT-free path: [Img.toPngBytes] → `display(channel, bytes)`, which the output
 * pipeline renders as an image, exactly as the spectral examples do for their heatmaps.
 */
fun Box.showImg(img: Img, channel: String = "main", maxSide: Int = 900, caption: String? = null) {
    val scale = min(1.0, maxSide.toDouble() / max(img.width, img.height))
    val shown = if (scale >= 1.0) img else img.scaleBy(scale)
    val lo = shown.min()
    val hi = shown.max()
    val ready = if (lo < -1e-4f || hi > 1f + 1e-4f) shown.normalized() else shown
    if (caption != null) display(channel, caption)
    display(channel, ready.toPngBytes())
}

/**
 * Show several images side by side with captions in one output — the fastest way to see a whole
 * pipeline (source / blurred / gradient / edges) at a glance.
 *
 * @param columns images per row
 */
fun Box.showImgGrid(
    vararg panels: Pair<String, Img>,
    channel: String = "main",
    columns: Int = 2,
    cellSide: Int = 320
) {
    require(panels.isNotEmpty()) { "showImgGrid needs at least one panel" }
    require(columns >= 1) { "columns must be >= 1, got $columns" }
    val rows = panels.toList().chunked(columns)
    val rowImgs = rows.map { row ->
        hstack(*row.map { (_, img) ->
            val s = min(1.0, cellSide.toDouble() / max(img.width, img.height))
            val small = if (s >= 1.0) img else img.scaleBy(s)
            val lo = small.min(); val hi = small.max()
            if (lo < -1e-4f || hi > 1f + 1e-4f) small.normalized().toRGB() else small.toRGB()
        }.toTypedArray())
    }
    display(channel, panels.joinToString(" · ") { it.first })
    display(channel, vstack(*rowImgs.toTypedArray()).toPngBytes())
}
