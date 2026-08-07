@file:afield.CompletionCategory("Imaging")

package afield.stdlib

import afield.Box
import afield.graphics.FLine
import afield.graphics.polylineFrom
import afield.Color4
import afield.Colormaps
import java.util.Base64
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

/**
 * Clip this [HoughLine] to a `w x h` image rectangle and return the visible segment as an [FLine],
 * or `null` if the line misses the rectangle entirely.
 *
 * A Hough line is *infinite* — `(rho, theta)` names a whole line, not a segment — so it has no
 * drawable form until you say which rectangle you are drawing it into. That is why this takes the
 * image dimensions and why it is nullable; a peak in the accumulator can, at the edges of the
 * parameter range, correspond to a line that grazes past the image.
 *
 * ```
 * val lines = edges.houghLines()
 * lines(lines.mapNotNull { it.toFLine(img.width, img.height) })
 * ```
 *
 * @see linesToFLines for the whole-list form, which drops the misses for you
 */
fun HoughLine.toFLine(w: Int, h: Int, z: Number = 0): FLine? =
    clipTo(w, h)?.let { (a, b) -> polylineFrom(listOf(a, b), z, false) }

/** Clip each [HoughLine] to a `w x h` image rectangle and return the visible segments as [FLine]s. */
fun linesToFLines(lines: List<HoughLine>, w: Int, h: Int, z: Number = 0): List<FLine> =
    lines.mapNotNull { it.toFLine(w, h, z) }

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
 * Loops are closed and contours that run off the edge of the image are not, decided per contour with
 * [isoContourClosed]. A level set inside a blurred response is almost always a closed ring, and the
 * marcher returns one as a cycle whose ends are *adjacent* rather than equal — so drawing them all
 * open, as this used to, leaves a notch one grid cell wide in every loop.
 *
 * @param cells grid resolution; defaults to one cell per pixel
 */
fun Img.isoContourFLines(iso: Double, cells: Int = max(width, height), z: Number = 0): List<FLine> {
    require(channels == 1) { "isoContourFLines expects a 1-channel image; call .luma() first" }
    require(cells >= 2) { "cells must be >= 2, got $cells" }
    val nx = max(2, (cells.toDouble() * width / max(width, height)).toInt())
    val ny = max(2, (cells.toDouble() * height / max(width, height)).toInt())
    val xMax = (width - 1).toDouble()
    val yMax = (height - 1).toDouble()
    val contours = generateIsoContours(asScalarField2D(), 0.0, xMax, 0.0, yMax, nx, ny, iso)
    // One cell's worth of slack, because a crossing lands where the field puts it, not on the bound.
    val boundaryEpsilon = 0.75 * max(xMax / nx, yMax / ny)
    return contours.filter { it.size >= 2 }.map { contour ->
        contourToFLine(
            contour,
            closed = isoContourClosed(contour, 0.0, xMax, 0.0, yMax, boundaryEpsilon),
            z = z,
        )
    }
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
 * the long edge. Signed / out-of-range images are normalised for display, and when a [caption] is
 * asked for the caption says so.
 *
 * With **no caption** this is the AWT-free bytes path — [Img.toPngBytes] → `display(channel, bytes)`,
 * which the output pipeline renders as an image, exactly as the spectral examples do for their
 * heatmaps. That is also the path an animation wants: one `display` per frame, nothing to tear.
 *
 * With a **caption** the picture and its caption go out as a *single* `display` payload — one
 * `<figure>`, styled like [Box.showMesh]'s. They have to be one payload: `display(channel, …)`
 * *replaces* that channel's contents rather than appending, so emitting them separately wrote the
 * caption and then immediately overwrote it with the PNG, and the caption was never seen.
 */
fun Box.showImg(img: Img, channel: String = "main", maxSide: Int = 900, caption: String? = null) {
    val scale = min(1.0, maxSide.toDouble() / max(img.width, img.height))
    val shown = if (scale >= 1.0) img else img.scaleBy(scale)
    val lo = shown.min()
    val hi = shown.max()
    val normalised = lo < -1e-4f || hi > 1f + 1e-4f
    val ready = if (normalised) shown.normalized() else shown
    val png = ready.toPngBytes()
    if (caption == null) {
        display(channel, png)
        return
    }
    val b64 = Base64.getEncoder().encodeToString(png)
    // Absolute max-width (not 100%), the same cap the bytes path uses: the box output panel is
    // shrink-wrap, so a percentage width collapses the image instead of sizing the panel to it.
    display(channel, buildString {
        append("<figure style='margin:0;max-width:${SHOW_IMG_MAX_CSS_WIDTH}px'>")
        append("<img src='data:image/png;base64,$b64' ")
        append("style='max-width:${SHOW_IMG_MAX_CSS_WIDTH}px;height:auto;border-radius:6px;display:block' ")
        append("alt='${escapeCaptionHtml(caption)}'/>")
        append("<figcaption style='color:#93a4ba;font-size:12px;padding-top:6px;line-height:1.5'>")
        append("<div>${escapeCaptionHtml(caption)}</div>")
        if (normalised) {
            val note = "normalised for display from %.4g … %.4g".format(lo, hi)
            append("<div style='font-family:monospace'>${escapeCaptionHtml(note)}</div>")
        }
        append("</figcaption></figure>")
    })
}

/** The CSS cap the raw-bytes display path puts on an inline raster; the `<figure>` path matches it. */
private const val SHOW_IMG_MAX_CSS_WIDTH = 900

private fun escapeCaptionHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("'", "&#39;")
    .replace("\"", "&quot;")

/**
 * Lay several captioned images out on a uniform grid and return the montage as one [Img].
 *
 * The captions are drawn **into the raster**, not emitted alongside it, so the montage stays
 * self-describing wherever it ends up — a box output, a docs screenshot, a file. Tiles are scaled
 * to fit [cellSide] on their long edge, centred in their cell and top-aligned. Every panel is mapped
 * through the same [displayRange] and clamped, so an overshoot in one image cannot silently put just
 * that panel on a different scale. When no range is supplied, ordinary `0..1` imagery stays on that
 * domain while out-of-range panels use one common range across the whole montage. Pass an explicit
 * signed range such as `-1f..1f` when the domain itself carries meaning.
 *
 * Captions are drawn with a built-in 5x7 bitmap font, **not AWT** — see [drawLabels] for why that
 * matters inside a live Field box. A montage is display output, not analysis input; keep the float
 * [Img]s you built it from rather than feeding a montage back into a filter chain.
 *
 * @param columns tiles per row
 * @param cellSide the longest edge a tile is allowed to occupy
 * @param displayRange an optional sample domain shared by every panel and mapped to display values
 *   `0..1`; `null` chooses `0..1` for ordinary imagery or one common range for out-of-range panels
 */
fun montage(
    panels: List<Pair<String, Img>>,
    columns: Int = 2,
    cellSide: Int = 320,
    gap: Int = 10,
    background: Float = 0.08f,
    displayRange: ClosedFloatingPointRange<Float>? = null
): Img {
    require(panels.isNotEmpty()) { "montage needs at least one panel" }
    require(columns >= 1) { "columns must be >= 1, got $columns" }
    val textScale = 2
    val captionH = GLYPH_HEIGHT * textScale + 8
    val sized = panels.map { (label, img) ->
        val s = min(1.0, cellSide.toDouble() / max(img.width, img.height))
        label to if (s >= 1.0) img else img.scaleBy(s)
    }
    val effectiveRange = displayRange ?: run {
        val lo = sized.minOf { it.second.min() }
        val hi = sized.maxOf { it.second.max() }
        when {
            lo >= -1e-4f && hi <= 1f + 1e-4f -> 0f..1f
            hi - lo < 1e-12f -> (lo - 0.5f)..(lo + 0.5f)
            else -> lo..hi
        }
    }
    val displayLo = effectiveRange.start
    val displayHi = effectiveRange.endInclusive
    require(displayLo.isFinite() && displayHi.isFinite() && displayHi > displayLo) {
        "displayRange must be finite and increasing, got $effectiveRange"
    }
    val displayScale = 1f / (displayHi - displayLo)
    val scaled = sized.map { (label, small) ->
        label to small.map { ((it - displayLo) * displayScale).coerceIn(0f, 1f) }.toRGB()
    }
    val cols = min(columns, scaled.size)
    val cellW = scaled.maxOf { it.second.width }
    val cellH = scaled.maxOf { it.second.height }
    val rows = scaled.chunked(cols)
    val w = cols * cellW + gap * (cols + 1)
    val h = rows.size * (cellH + captionH) + gap * (rows.size + 1)

    val out = Img.filled(w, h, 3, background)
    var y0 = gap
    val captions = ArrayList<Triple<String, Int, Int>>()
    for (row in rows) {
        row.forEachIndexed { col, (label, img) ->
            val cellX = gap + col * (cellW + gap)
            val x0 = cellX + (cellW - img.width) / 2
            for (y in 0 until img.height) for (x in 0 until img.width) for (c in 0 until 3) {
                out.data[out.index(x0 + x, y0 + y, c)] = img.data[img.index(x, y, c)]
            }
            captions.add(Triple(label, cellX, y0 + cellH + 4))
        }
        y0 += cellH + captionH + gap
    }
    return drawLabels(out, captions, cellW, textScale)
}

// ----------------------------------------------------------------------------------------------
// A tiny built-in bitmap font, so captions can be burned in with NO AWT.
//
// Why not `Graphics2D.drawString`? Because this code runs inside a live Field box, and Field's
// `Server.main` deliberately does *not* initialise the AWT toolkit (the eager-init block there is
// commented out on purpose). On macOS, touching the AWT font manager pulls in AppKit, which
// competes with GLFW for the main run loop. A stdlib call that every box might make must not be
// able to take the process down, so text here is drawn from a table, not from a font engine.
//
// The classic public-domain 5x7 LCD font, ASCII 0x20..0x7E: five bytes per glyph, one byte per
// column, bit 0 = top row.
// ----------------------------------------------------------------------------------------------

private val FONT_5X7: IntArray = intArrayOf(
    0x00, 0x00, 0x00, 0x00, 0x00, // space
    0x00, 0x00, 0x5F, 0x00, 0x00, // !
    0x00, 0x07, 0x00, 0x07, 0x00, // "
    0x14, 0x7F, 0x14, 0x7F, 0x14, // #
    0x24, 0x2A, 0x7F, 0x2A, 0x12, // $
    0x23, 0x13, 0x08, 0x64, 0x62, // %
    0x36, 0x49, 0x55, 0x22, 0x50, // &
    0x00, 0x05, 0x03, 0x00, 0x00, // '
    0x00, 0x1C, 0x22, 0x41, 0x00, // (
    0x00, 0x41, 0x22, 0x1C, 0x00, // )
    0x14, 0x08, 0x3E, 0x08, 0x14, // *
    0x08, 0x08, 0x3E, 0x08, 0x08, // +
    0x00, 0x50, 0x30, 0x00, 0x00, // ,
    0x08, 0x08, 0x08, 0x08, 0x08, // -
    0x00, 0x60, 0x60, 0x00, 0x00, // .
    0x20, 0x10, 0x08, 0x04, 0x02, // /
    0x3E, 0x51, 0x49, 0x45, 0x3E, // 0
    0x00, 0x42, 0x7F, 0x40, 0x00, // 1
    0x42, 0x61, 0x51, 0x49, 0x46, // 2
    0x21, 0x41, 0x45, 0x4B, 0x31, // 3
    0x18, 0x14, 0x12, 0x7F, 0x10, // 4
    0x27, 0x45, 0x45, 0x45, 0x39, // 5
    0x3C, 0x4A, 0x49, 0x49, 0x30, // 6
    0x01, 0x71, 0x09, 0x05, 0x03, // 7
    0x36, 0x49, 0x49, 0x49, 0x36, // 8
    0x06, 0x49, 0x49, 0x29, 0x1E, // 9
    0x00, 0x36, 0x36, 0x00, 0x00, // :
    0x00, 0x56, 0x36, 0x00, 0x00, // ;
    0x00, 0x08, 0x14, 0x22, 0x41, // <
    0x14, 0x14, 0x14, 0x14, 0x14, // =
    0x41, 0x22, 0x14, 0x08, 0x00, // >
    0x02, 0x01, 0x51, 0x09, 0x06, // ?
    0x32, 0x49, 0x79, 0x41, 0x3E, // @
    0x7E, 0x11, 0x11, 0x11, 0x7E, // A
    0x7F, 0x49, 0x49, 0x49, 0x36, // B
    0x3E, 0x41, 0x41, 0x41, 0x22, // C
    0x7F, 0x41, 0x41, 0x22, 0x1C, // D
    0x7F, 0x49, 0x49, 0x49, 0x41, // E
    0x7F, 0x09, 0x09, 0x01, 0x01, // F
    0x3E, 0x41, 0x41, 0x51, 0x32, // G
    0x7F, 0x08, 0x08, 0x08, 0x7F, // H
    0x00, 0x41, 0x7F, 0x41, 0x00, // I
    0x20, 0x40, 0x41, 0x3F, 0x01, // J
    0x7F, 0x08, 0x14, 0x22, 0x41, // K
    0x7F, 0x40, 0x40, 0x40, 0x40, // L
    0x7F, 0x02, 0x04, 0x02, 0x7F, // M
    0x7F, 0x04, 0x08, 0x10, 0x7F, // N
    0x3E, 0x41, 0x41, 0x41, 0x3E, // O
    0x7F, 0x09, 0x09, 0x09, 0x06, // P
    0x3E, 0x41, 0x51, 0x21, 0x5E, // Q
    0x7F, 0x09, 0x19, 0x29, 0x46, // R
    0x46, 0x49, 0x49, 0x49, 0x31, // S
    0x01, 0x01, 0x7F, 0x01, 0x01, // T
    0x3F, 0x40, 0x40, 0x40, 0x3F, // U
    0x1F, 0x20, 0x40, 0x20, 0x1F, // V
    0x7F, 0x20, 0x18, 0x20, 0x7F, // W
    0x63, 0x14, 0x08, 0x14, 0x63, // X
    0x03, 0x04, 0x78, 0x04, 0x03, // Y
    0x61, 0x51, 0x49, 0x45, 0x43, // Z
    0x00, 0x00, 0x7F, 0x41, 0x41, // [
    0x02, 0x04, 0x08, 0x10, 0x20, // backslash
    0x41, 0x41, 0x7F, 0x00, 0x00, // ]
    0x04, 0x02, 0x01, 0x02, 0x04, // ^
    0x40, 0x40, 0x40, 0x40, 0x40, // _
    0x00, 0x01, 0x02, 0x04, 0x00, // `
    0x20, 0x54, 0x54, 0x54, 0x78, // a
    0x7F, 0x48, 0x44, 0x44, 0x38, // b
    0x38, 0x44, 0x44, 0x44, 0x20, // c
    0x38, 0x44, 0x44, 0x48, 0x7F, // d
    0x38, 0x54, 0x54, 0x54, 0x18, // e
    0x08, 0x7E, 0x09, 0x01, 0x02, // f
    0x08, 0x14, 0x54, 0x54, 0x3C, // g
    0x7F, 0x08, 0x04, 0x04, 0x78, // h
    0x00, 0x44, 0x7D, 0x40, 0x00, // i
    0x20, 0x40, 0x44, 0x3D, 0x00, // j
    0x00, 0x7F, 0x10, 0x28, 0x44, // k
    0x00, 0x41, 0x7F, 0x40, 0x00, // l
    0x7C, 0x04, 0x18, 0x04, 0x78, // m
    0x7C, 0x08, 0x04, 0x04, 0x78, // n
    0x38, 0x44, 0x44, 0x44, 0x38, // o
    0x7C, 0x14, 0x14, 0x14, 0x08, // p
    0x08, 0x14, 0x14, 0x18, 0x7C, // q
    0x7C, 0x08, 0x04, 0x04, 0x08, // r
    0x48, 0x54, 0x54, 0x54, 0x20, // s
    0x04, 0x3F, 0x44, 0x40, 0x20, // t
    0x3C, 0x40, 0x40, 0x20, 0x7C, // u
    0x1C, 0x20, 0x40, 0x20, 0x1C, // v
    0x3C, 0x40, 0x30, 0x40, 0x3C, // w
    0x44, 0x28, 0x10, 0x28, 0x44, // x
    0x0C, 0x50, 0x50, 0x50, 0x3C, // y
    0x44, 0x64, 0x54, 0x4C, 0x44, // z
    0x00, 0x08, 0x36, 0x41, 0x00, // {
    0x00, 0x00, 0x7F, 0x00, 0x00, // |
    0x00, 0x41, 0x36, 0x08, 0x00, // }
    0x08, 0x08, 0x2A, 0x1C, 0x08  // ~
)

/** Glyph advance in source pixels (5 columns + 1 space), before [textScale]. */
private const val GLYPH_ADVANCE = 6

/** Glyph height in source pixels, before [textScale]. */
private const val GLYPH_HEIGHT = 7

/**
 * Fold a string down to the printable ASCII the built-in font covers, mapping the typographic
 * characters that show up in real captions (en/em dashes, middle dot, multiplication sign,
 * ellipsis, curly quotes) onto sensible ASCII rather than dropping them.
 */
private fun toFontAscii(s: String): String {
    val b = StringBuilder(s.length)
    for (ch in s) {
        when (ch) {
            '–', '—', '−' -> b.append('-')       // en dash, em dash, minus
            '·', '•' -> b.append('.')                  // middle dot, bullet
            '×' -> b.append('x')                            // multiplication sign
            '…' -> b.append("...")                          // ellipsis
            '‘', '’' -> b.append('\'')
            '“', '”' -> b.append('"')
            '°' -> b.append('o')
            'π' -> b.append("pi")
            'θ' -> b.append("th")
            'σ' -> b.append("sigma")
            else -> b.append(if (ch.code in 0x20..0x7E) ch else '?')
        }
    }
    return b.toString()
}

/** Rendered width of [s] in pixels at [scale], in the built-in font. */
private fun textWidth(s: String, scale: Int): Int = toFontAscii(s).length * GLYPH_ADVANCE * scale

/**
 * Burn text labels into an [Img] using the built-in bitmap font — **no AWT, no font manager, no
 * AppKit**. `(text, x, yTop)` triples in image coordinates; text longer than [maxWidth] is
 * ellipsised. Mutates and returns [img].
 */
private fun drawLabels(
    img: Img,
    labels: List<Triple<String, Int, Int>>,
    maxWidth: Int,
    scale: Int = 1,
    r: Float = 0.60f,
    g: Float = 0.62f,
    b: Float = 0.68f
): Img {
    val advance = GLYPH_ADVANCE * scale
    for ((raw, x0, yTop) in labels) {
        var s = toFontAscii(raw)
        if (s.length * advance > maxWidth) {
            val fits = max(1, maxWidth / advance - 3)
            s = s.take(fits) + "..."
        }
        var penX = x0
        for (ch in s) {
            val gi = (ch.code - 0x20) * 5
            if (gi >= 0 && gi + 4 < FONT_5X7.size) {
                for (col in 0 until 5) {
                    val bits = FONT_5X7[gi + col]
                    for (row in 0 until GLYPH_HEIGHT) {
                        if ((bits shr row) and 1 == 0) continue
                        for (sy in 0 until scale) for (sx in 0 until scale) {
                            val px = penX + col * scale + sx
                            val py = yTop + row * scale + sy
                            if (px < 0 || py < 0 || px >= img.width || py >= img.height) continue
                            val i = img.index(px, py, 0)
                            if (img.channels >= 3) {
                                img.data[i] = r; img.data[i + 1] = g; img.data[i + 2] = b
                            } else {
                                img.data[i] = (r + g + b) / 3f
                            }
                        }
                    }
                }
            }
            penX += advance
        }
    }
    return img
}

/**
 * Draw a caption into an [Img] at `(x, y)` with the built-in AWT-free bitmap font — useful on its
 * own for labelling a frame before you write it out.
 *
 * @param scale integer pixel scale; 1 gives a 5x7 glyph, 2 gives 10x14
 */
fun Img.withLabel(
    text: String,
    x: Int = 4,
    y: Int = 4,
    scale: Int = 2,
    color: Color4 = Color4(0.85, 0.87, 0.92)
): Img = drawLabels(
    copy(), listOf(Triple(text, x, y)), width - x, scale,
    color.r.toFloat(), color.g.toFloat(), color.b.toFloat()
)

/**
 * Show several images side by side with their captions burned in — the fastest way to see a whole
 * pipeline (source / blurred / gradient / edges) at a glance.
 *
 * @param columns images per row
 * @param displayRange an optional sample domain shared by every panel; see [montage]
 */
fun Box.showImgGrid(
    vararg panels: Pair<String, Img>,
    channel: String = "main",
    columns: Int = 2,
    cellSide: Int = 320,
    displayRange: ClosedFloatingPointRange<Float>? = null,
) {
    require(panels.isNotEmpty()) { "showImgGrid needs at least one panel" }
    display(
        channel,
        montage(panels.toList(), columns, cellSide, displayRange = displayRange).toPngBytes(),
    )
}
