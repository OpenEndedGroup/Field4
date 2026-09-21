@file:afield.CompletionCategory("Visualization")

package afield

import afield.stdlib.ComponentLabelling
import afield.stdlib.Corner
import afield.stdlib.FlowField
import afield.stdlib.FlowVector
import afield.stdlib.GaussianPyramid
import afield.stdlib.Gradient
import afield.stdlib.HoughCircle
import afield.stdlib.HoughLine
import afield.stdlib.Img
import afield.stdlib.LaplacianPyramid
import afield.stdlib.resize
import afield.stdlib.toPngBytes
import java.util.Base64
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Inline visualizers for the CPU imaging and computer-vision family (`afield.stdlib/imaging*.kt`):
 * what a gradient, a pyramid, a flow field or a list of corners looks like when a box evaluates one.
 *
 * The imaging layer is around 250 public symbols of raster and feature code, and [Img] is the only
 * one of its types that carries a picture of its own. Everything else falls through to a reflective
 * object tree unless something here claims it, and there a list of forty [Corner]s is a wall of
 * `{x=…, y=…, response=…}` and a [GaussianPyramid] is `GaussianPyramid@3f1a2b`. Those are exactly
 * the values you want to look at.
 *
 * #### Where this hooks in
 *
 * Three one-line hooks, small enough that this file can grow without touching `ValueVisualizer.kt`:
 *
 *  - `Img.toHTML` delegates to [img]. [Img] implements [ToHTML], which `ValueVisualizer` dispatches
 *    before its built-in table, so improving the `Img` picture has to happen there.
 *  - `ValueVisualizer.visualizeBuiltIn`'s fallback branch tries [format] first, which covers the
 *    non-data classes ([Gradient], [GaussianPyramid], [LaplacianPyramid], [FlowField],
 *    [ComponentLabelling]).
 *  - `ValueVisualizer.formatList` tries [formatList] first, which covers the homogeneous detector
 *    outputs (`List<Corner>`, `List<HoughLine>`, `List<HoughCircle>`, `List<FlowVector>`,
 *    `List<Img>`).
 *
 * #### Turning a float image into pixels
 *
 * The family is float-channel and unbounded: a Sobel `dx`, a Laplacian and a
 * difference-of-Gaussians are legitimately signed, an unnormalised response legitimately exceeds 1,
 * and a division somewhere upstream legitimately produces a NaN. A visualizer that clamps all of
 * those to `0..1` shows a flat white or a flat black rectangle and tells you nothing, so the raster
 * renderer picks one of three modes:
 *
 *  - **direct** — every finite sample already sits in `0..1`. Shown as-is; nothing is rescaled.
 *  - **signed ±m** — some finite sample is negative. The range is made symmetric about zero
 *    (`m = max(|min|, |max|)`) so that zero is a fixed, recognisable colour. A 1-channel image is
 *    additionally run through the `coolwarm` diverging ramp from [Colormaps] (blue negative, pale
 *    zero, red positive), because grey cannot carry the sign of a gradient. Multi-channel signed
 *    data is offset and scaled per sample without a ramp.
 *  - **auto-ranged** — everything is non-negative but the maximum exceeds 1. Linearly rescaled, with
 *    the data's true range printed beside it, so a bright pixel is never mistaken for 1.0.
 *
 * The caption names the mode whenever the mode rescaled something; the statistics table inside the
 * disclosure names it in every case, including the fourth one, where no sample is finite at all.
 *
 * Non-finite samples (NaN, ±∞) are counted, excluded from every statistic, and painted **magenta**
 * so they are impossible to miss. Excluding them is what keeps one NaN out of the minimum and the
 * maximum, where it would take the whole display range with it and quantise to a uniformly black
 * thumbnail captioned `range NaN…NaN`.
 *
 * A 4-channel image is drawn over a **checkerboard**, so transparent and black are distinguishable.
 *
 * #### The disclosure
 *
 * Each raster visualizer carries a closed `<details>` with the per-channel strip, a per-channel
 * histogram computed over the full-resolution samples, and the numeric stats. It is a plain
 * `<details>` element rather than a `lazy-value-fragment` expander link, so it needs no running
 * server and works identically in a live box, in a docs capture and in a saved HTML file.
 */

/**
 * The dispatch surface: [format] for a single imaging value, [formatList] for a homogeneous list of
 * them, and [img] for the [Img] thumbnail that `Img.toHTML` calls directly.
 */
object ImagingVisualizer {

    private object K {
        /** Long edge of the main inline raster thumbnail, in CSS px. */
        const val THUMB_MAX_SIDE = 240

        /** Long edge of one per-channel thumbnail inside the disclosure. */
        const val CHANNEL_SIDE = 76

        /** Long edge of one pyramid rung; the base rung gets this and the rest scale from it. */
        const val PYRAMID_BASE_SIDE = 120

        /** Long edge of a filmstrip frame for a `List<Img>`. */
        const val FILMSTRIP_SIDE = 92

        /** Width of an SVG geometry panel (corners / lines / circles / flow). */
        const val PANEL_W = 236

        /** Height bounds for an SVG geometry panel; the aspect of the data decides within these. */
        const val PANEL_H_MIN = 72
        const val PANEL_H_MAX = 236

        /** Width of the small `(θ, ρ)` Hough accumulator panel. */
        const val HOUGH_PARAM_W = 116
        const val HOUGH_PARAM_H = 96

        /** Histogram geometry, in CSS px. */
        const val HIST_W = 236
        const val HIST_H = 52
        const val HIST_BINS = 64

        /** Beyond this many list elements the geometry panels sub-sample rather than draw everything. */
        const val MAX_GLYPHS = 900

        /** Numeric readouts of individual detections stop after this many. */
        const val MAX_LISTED = 6

        /** Marker radii in CSS px. */
        const val DOT_R_MIN = 1.6
        const val DOT_R_MAX = 5.0

        /** A flow field whose median displacement is under this fraction of the frame gets exaggerated. */
        const val FLOW_VISIBLE_FRACTION = 0.035

        // ---- palette (kept close to ValueVisualizer's: #9cf headers, #888 captions) --------------
        const val C_HEAD = "#9cf"
        const val C_DIM = "#8a90a0"
        const val C_FAINT = "#666"
        const val C_ACCENT = "#fc8"
        const val C_OK = "#6cf"
        const val C_BAD = "#f6a"
        const val BORDER = "#3a3f4b"
        const val PANEL_BG = "#111"

        /** Painted over any non-finite sample. */
        val NON_FINITE = Color4(1.0, 0.0, 0.85)
    }

    // ==========================================================================================
    // Dispatch
    // ==========================================================================================

    /**
     * Render one of the imaging family's non-list types, or `null` if [value] is not one of them
     * (in which case `ValueVisualizer` carries on with its reflective/`toString` fallback).
     */
    fun format(value: Any): String? = when (value) {
        is Img -> img(value)
        is Gradient -> gradient(value)
        is GaussianPyramid -> gaussianPyramid(value)
        is LaplacianPyramid -> laplacianPyramid(value)
        is FlowField -> flowField(value)
        is ComponentLabelling -> componentLabelling(value)
        else -> null
    }

    /**
     * Render a homogeneous list of imaging values as one picture, or `null` when the list is not
     * homogeneously one of the supported element types (empty lists and mixed lists fall through to
     * `ValueVisualizer`'s ordinary list rendering).
     */
    fun formatList(list: List<*>): String? {
        if (list.isEmpty()) return null
        return when {
            list.all { it is Corner } -> corners(list.filterIsInstance<Corner>())
            list.all { it is HoughLine } -> houghLines(list.filterIsInstance<HoughLine>())
            list.all { it is HoughCircle } -> houghCircles(list.filterIsInstance<HoughCircle>())
            list.all { it is FlowVector } -> flowField(FlowField(list.filterIsInstance<FlowVector>()))
            list.all { it is Img } -> filmstrip(list.filterIsInstance<Img>())
            else -> null
        }
    }

    // ==========================================================================================
    // Img
    // ==========================================================================================

    /**
     * The inline picture for an [Img]: an aspect-correct thumbnail of the actual pixels under the
     * range policy this file's overview sets out, a one-line readout, and a disclosure holding
     * the per-channel strip, histogram and statistics.
     *
     * Called from `Img.toHTML()`, so this *is* what evaluating an image in a box shows.
     */
    fun img(image: Img): String {
        val stats = Stats.of(image)
        val raster = renderRaster(image, stats, K.THUMB_MAX_SIDE)
        return card {
            append(rasterFigure(raster, image.channels == 4))
            append(
                caption(
                    typeToken("Img"),
                    "${image.width}&times;${image.height}",
                    channelWord(image.channels),
                    stats.rangeText(),
                    raster.modeNote
                )
            )
            append(imgDisclosure(image, stats, raster))
        }
    }

    /** Per-channel strip + histogram + numeric statistics, behind a closed `<details>`. */
    private fun imgDisclosure(image: Img, stats: Stats, raster: Raster): String = disclosure(
        summary = if (image.channels == 1) "grey &middot; viridis &middot; histogram &middot; stats"
        else "channels &middot; histogram &middot; stats"
    ) {
        append(channelStrip(image, stats))
        append(histogramSvg(image, stats))
        append(statsTable(image, stats, raster))
    }

    /**
     * One small thumbnail per channel. A 1-channel image has no channels to split, so it instead
     * shows two *different readings of itself*: plain grey (forced, even when the main thumbnail is
     * the `coolwarm` diverging map — otherwise the strip would just repeat it) and `viridis`, whose
     * perceptual uniformity pulls structure out of a response image that grey flattens.
     */
    private fun channelStrip(image: Img, stats: Stats): String {
        val small = fitLongEdge(image, K.CHANNEL_SIDE)
        val panels: List<Pair<String, Raster>> = if (image.channels == 1) {
            listOf(
                "grey" to renderRaster(small, stats, K.CHANNEL_SIDE, ramp = null, forceRamp = true),
                "viridis" to renderRaster(small, stats, K.CHANNEL_SIDE, ramp = "viridis", forceRamp = true)
            )
        } else {
            (0 until image.channels).map { c ->
                val ch = extractChannel(small, c)
                // Alpha is always 0..1 by construction, so it gets its own direct-mode statistics
                // rather than inheriting the colour channels' range.
                val chStats = if (c == 3) Stats.of(ch) else stats
                channelName(image.channels, c) to
                    renderRaster(ch, chStats, K.CHANNEL_SIDE, ramp = null, forceRamp = true)
            }
        }
        return buildString {
            append("<div style='display:flex;gap:6px;flex-wrap:wrap;margin-bottom:6px'>")
            for ((label, r) in panels) {
                append("<div>")
                append(rasterFigure(r, checkerboard = false))
                append("<div style='color:${K.C_FAINT};font-size:10px;text-align:center;margin-top:2px'>")
                append(label)
                append("</div></div>")
            }
            append("</div>")
        }
    }

    /**
     * Per-channel histogram over the **full-resolution** samples (not the thumbnail), across the
     * same display range the picture uses, so a bar's position corresponds to a colour above.
     */
    private fun histogramSvg(image: Img, stats: Stats): String {
        val lo = stats.displayLo
        val hi = stats.displayHi
        if (!(hi > lo)) {
            return "<div style='color:${K.C_FAINT};font-size:10px;margin-bottom:6px'>histogram: constant image</div>"
        }
        val bins = K.HIST_BINS
        val k = bins / (hi - lo)
        val counts = Array(image.channels) { IntArray(bins) }
        val n = image.pixelCount
        for (p in 0 until n) {
            val base = p * image.channels
            for (c in 0 until image.channels) {
                val v = image.data[base + c]
                if (!v.isFinite()) continue
                counts[c][(((v - lo) * k).toInt()).coerceIn(0, bins - 1)]++
            }
        }
        val peak = counts.maxOf { arr -> arr.max() }.coerceAtLeast(1)
        val bw = K.HIST_W.toDouble() / bins
        return buildString {
            append("<svg xmlns='http://www.w3.org/2000/svg' width='${K.HIST_W}' height='${K.HIST_H + 12}' ")
            append("viewBox='0 0 ${K.HIST_W} ${K.HIST_H + 12}' style='display:block;margin-bottom:4px'>")
            append("<rect x='0' y='0' width='${K.HIST_W}' height='${K.HIST_H}' fill='${K.PANEL_BG}' stroke='${K.BORDER}'/>")
            // A zero line, when zero is inside the displayed range — the reference a signed image needs.
            if (lo < 0f && hi > 0f) {
                val zx = fmt(((0f - lo) / (hi - lo) * K.HIST_W).toDouble())
                append("<line x1='$zx' y1='0' x2='$zx' y2='${K.HIST_H}' stroke='#555' stroke-dasharray='2 2'/>")
            }
            for (c in 0 until image.channels) {
                val col = channelColor(image.channels, c)
                val opacity = if (image.channels == 1) "0.9" else "0.55"
                append("<g fill='$col' fill-opacity='$opacity'>")
                for (b in 0 until bins) {
                    val v = counts[c][b]
                    if (v == 0) continue
                    // sqrt on the count: a linear axis hides everything next to a background spike.
                    val hgt = sqrt(v.toDouble() / peak) * (K.HIST_H - 2)
                    append("<rect x='${fmt(b * bw)}' y='${fmt(K.HIST_H - hgt)}' ")
                    append("width='${fmt(max(0.8, bw - 0.3))}' height='${fmt(hgt)}'/>")
                }
                append("</g>")
            }
            append("<text x='0' y='${K.HIST_H + 10}' fill='${K.C_FAINT}' font-size='9' font-family='monospace'>")
            append(num(lo.toDouble()))
            append("</text>")
            append("<text x='${K.HIST_W}' y='${K.HIST_H + 10}' text-anchor='end' fill='${K.C_FAINT}' ")
            append("font-size='9' font-family='monospace'>")
            append(num(hi.toDouble()))
            append("</text>")
            append("<text x='${K.HIST_W / 2}' y='${K.HIST_H + 10}' text-anchor='middle' fill='${K.C_FAINT}' ")
            append("font-size='9' font-family='monospace'>")
            append("&#8730;count, peak ${counts.maxOf { it.max() }}")
            append("</text>")
            append("</svg>")
        }
    }

    private fun statsTable(image: Img, stats: Stats, raster: Raster): String = buildString {
        // flex-wrap, not a run of &nbsp;: `px=43200 samples=129600` is otherwise one unbreakable
        // line that pushes the card past its max-width and gets clipped.
        append("<div style='color:${K.C_DIM};font-size:10px;line-height:1.5;display:flex;")
        append("flex-wrap:wrap;column-gap:10px;max-width:${K.HIST_W}px'>")
        append(kv("min", num(stats.min)))
        append(kv("max", num(stats.max)))
        append(kv("mean", num(stats.mean)))
        append(kv("&sigma;", num(stats.sd)))
        append(kv("px", "${image.pixelCount}"))
        append(kv("samples", "${image.data.size}"))
        append(kv("mode", raster.mode))
        if (stats.nonFinite > 0) append(kv("non-finite", "${stats.nonFinite}", K.C_BAD))
        append("</div>")
    }

    // ==========================================================================================
    // Gradient
    // ==========================================================================================

    /**
     * A [Gradient] is two signed scalar images plus a direction; showing all three is what makes it
     * readable. `dx`/`dy` are drawn with the diverging policy (so a bright-to-dark edge and a
     * dark-to-bright edge are visibly opposite), alongside the magnitude and the hue-by-direction
     * image the type already knows how to make.
     */
    private fun gradient(g: Gradient): String {
        val mag = g.magnitude()
        val panels = listOf(
            "dx (signed)" to g.dx,
            "dy (signed)" to g.dy,
            "magnitude" to mag,
            "hue = direction" to g.toColorImg()
        )
        return card {
            append(imageRow(panels, K.CHANNEL_SIDE + 20))
            append(
                caption(
                    typeToken("Gradient"),
                    "${g.width}&times;${g.height}",
                    "|&nabla;| max ${num(mag.max().toDouble())}"
                )
            )
        }
    }

    // ==========================================================================================
    // Pyramids
    // ==========================================================================================

    /** A [GaussianPyramid] as a ladder of rungs at *true relative scale* — the whole point of it. */
    private fun gaussianPyramid(p: GaussianPyramid): String = card {
        append(pyramidLadder(p.levels))
        append(
            caption(
                typeToken("GaussianPyramid"),
                plural(p.size, "level"),
                p.levels.joinToString(" &rarr; ") { "${it.width}&times;${it.height}" }
            )
        )
    }

    /**
     * A [LaplacianPyramid] as its band-pass rungs (signed, so diverging) plus the low-pass residual.
     * The residual is tagged, because it is the one rung that is *not* a band.
     */
    private fun laplacianPyramid(p: LaplacianPyramid): String = card {
        append(pyramidLadder(p.bands + p.residual, residualIndex = p.bands.size))
        append(
            caption(
                typeToken("LaplacianPyramid"),
                plural(p.size, "band"),
                "residual ${p.residual.width}&times;${p.residual.height}",
                "reconstructs to ${p.bands.firstOrNull()?.let { "${it.width}&times;${it.height}" } ?: "residual"}"
            )
        )
    }

    /**
     * Lay levels out left to right, each rung sized by its *real* fraction of the base resolution,
     * bottom-aligned so the halving is the visible fact.
     */
    private fun pyramidLadder(levels: List<Img>, residualIndex: Int = -1): String {
        val baseW = levels.firstOrNull()?.width?.toDouble() ?: 1.0
        return buildString {
            append("<div style='display:flex;gap:6px;align-items:flex-end;flex-wrap:wrap;margin-bottom:4px'>")
            levels.forEachIndexed { i, level ->
                val side = max(8.0, K.PYRAMID_BASE_SIDE * level.width / baseW).roundToInt()
                val stats = Stats.of(level)
                val r = renderRaster(level, stats, side)
                append("<div>")
                append(rasterFigure(r, checkerboard = level.channels == 4))
                append("<div style='color:${K.C_FAINT};font-size:9px;text-align:center;margin-top:2px'>")
                append(if (i == residualIndex) "residual" else "L$i")
                append("<br/>${level.width}&times;${level.height}")
                append("</div></div>")
            }
            append("</div>")
        }
    }

    /** A plain `List<Img>` as a filmstrip — the shape a frame sequence or a channel split has. */
    private fun filmstrip(images: List<Img>): String {
        val shown = images.take(10)
        return card {
            append(imageRow(shown.mapIndexed { i, im -> "[$i]" to im }, K.FILMSTRIP_SIDE))
            append(
                caption(
                    typeToken("List&lt;Img&gt;"),
                    plural(images.size, "image"),
                    images.map { "${it.width}&times;${it.height}" }.distinct().take(3).joinToString(", "),
                    if (images.size > shown.size) "showing first ${shown.size}" else null
                )
            )
        }
    }

    /** A labelled row of raster thumbnails, each independently ranged. */
    private fun imageRow(panels: List<Pair<String, Img>>, side: Int): String = buildString {
        append("<div style='display:flex;gap:6px;align-items:flex-start;flex-wrap:wrap;margin-bottom:4px'>")
        for ((label, im) in panels) {
            val stats = Stats.of(im)
            val r = renderRaster(im, stats, side)
            append("<div>")
            append(rasterFigure(r, checkerboard = im.channels == 4))
            append("<div style='color:${K.C_FAINT};font-size:9px;text-align:center;margin-top:2px;max-width:${side + 8}px'>")
            append(label)
            append("</div></div>")
        }
        append("</div>")
    }

    // ==========================================================================================
    // ComponentLabelling
    // ==========================================================================================

    /**
     * The label map with a hashed per-label palette (adjacent labels get unrelated colours, which is
     * what makes touching blobs distinguishable), plus the area/centroid readout for the largest few.
     */
    private fun componentLabelling(cl: ComponentLabelling): String {
        val labels = cl.labels
        val side = K.THUMB_MAX_SIDE
        val scale = min(1.0, side.toDouble() / max(labels.width, labels.height))
        val tw = max(1, (labels.width * scale).roundToInt())
        val th = max(1, (labels.height * scale).roundToInt())
        // Nearest-neighbour: averaging label ids would invent labels that do not exist.
        val px = IntArray(tw * th)
        for (y in 0 until th) {
            val sy = ((y + 0.5) / th * labels.height).toInt().coerceIn(0, labels.height - 1)
            for (x in 0 until tw) {
                val sx = ((x + 0.5) / tw * labels.width).toInt().coerceIn(0, labels.width - 1)
                val v = labels.data[labels.index(sx, sy, 0)]
                px[y * tw + x] = if (!v.isFinite()) rgbInt(K.NON_FINITE) else {
                    val id = v.roundToInt()
                    if (id <= 0) 0xFF101216.toInt() else rgbInt(labelColor(id))
                }
            }
        }
        val b64 = Base64.getEncoder().encodeToString(afield.util.PngEncoder.encode(px, tw, th))
        val biggest = cl.components.sortedByDescending { it.area }.take(K.MAX_LISTED)
        return card {
            append(imgTag(b64, tw, th, checkerboard = false))
            append(
                caption(
                    typeToken("ComponentLabelling"),
                    plural(cl.count, "component"),
                    "${labels.width}&times;${labels.height}",
                    cl.largest()?.let { "largest ${it.area}px" }
                )
            )
            if (biggest.isNotEmpty()) {
                append(disclosure("component areas &amp; centroids") {
                    append("<div style='color:${K.C_DIM};font-size:10px;line-height:1.5'>")
                    for (c in biggest) {
                        append("<div>#${c.label} area=${c.area} bbox=${c.boundingWidth}&times;${c.boundingHeight}")
                        append(" centroid=(${num(c.centroidX)}, ${num(c.centroidY)})")
                        append(" fill=${num(c.fillRatio)}</div>")
                    }
                    if (cl.count > biggest.size) {
                        append("<div style='color:${K.C_FAINT}'>&hellip; +${cl.count - biggest.size} more</div>")
                    }
                    append("</div>")
                })
            }
        }
    }

    // ==========================================================================================
    // Corners
    // ==========================================================================================

    /**
     * A corner set as a scatter, with detector strength encoded as both radius and `viridis` colour.
     *
     * A `List<Corner>` carries no image extent, so the canvas is the points' own bounding box
     * **unioned with the origin** — image coordinates start at `(0, 0)`, so including it keeps the
     * points where they actually sit in the frame instead of floating them to fill the panel. The
     * caption states the inferred extent.
     */
    private fun corners(corners: List<Corner>): String {
        val shown = subsample(corners.sortedByDescending { it.response }, K.MAX_GLYPHS)
        val maxX = max(1.0, corners.maxOf { it.x })
        val maxY = max(1.0, corners.maxOf { it.y })
        val fit = Fit(0.0, 0.0, maxX * 1.02 + 1, maxY * 1.02 + 1)
        val rLo = corners.minOf { it.response }
        val rHi = corners.maxOf { it.response }
        return card {
            append(fit.svg {
                for (c in shown) {
                    val t = norm(c.response, rLo, rHi)
                    val r = K.DOT_R_MIN + (K.DOT_R_MAX - K.DOT_R_MIN) * t
                    append("<circle cx='${fmt(fit.tx(c.x))}' cy='${fmt(fit.ty(c.y))}' r='${fmt(r)}' ")
                    append("fill='${hex(Colormaps.sample("viridis", t))}' fill-opacity='0.85' ")
                    append("stroke='#000' stroke-opacity='0.4' stroke-width='0.5'/>")
                }
            })
            append(
                caption(
                    typeToken("List&lt;Corner&gt;"),
                    plural(corners.size, "corner"),
                    "response ${num(rLo)}&hellip;${num(rHi)}",
                    "inferred extent ${fmt(fit.vbW)}&times;${fmt(fit.vbH)}",
                    if (shown.size < corners.size) "strongest ${shown.size} drawn" else null
                )
            )
            append(disclosure("strongest corners") {
                append("<div style='color:${K.C_DIM};font-size:10px;line-height:1.5'>")
                for (c in corners.sortedByDescending { it.response }.take(K.MAX_LISTED)) {
                    append("<div>(${num(c.x)}, ${num(c.y)}) response=${num(c.response)}</div>")
                }
                append("</div>")
            })
        }
    }

    // ==========================================================================================
    // Hough
    // ==========================================================================================

    /**
     * Hough lines, twice: clipped into an inferred square canvas, and as the `(θ, ρ)` accumulator
     * peaks they actually are.
     *
     * A [HoughLine] is `(rho, theta)` — an **infinite** line with no extent of its own, which is
     * why [afield.stdlib.toFLine] insists on being handed the image dimensions. With no image in
     * reach, the left panel draws each line clipped to a square of side `S ≈ 1.41 · max|ρ|`, the
     * smallest square that every peak can still cross; the caption says so, and says how many lines
     * missed it. Pass the real dimensions to `linesToFLines(lines, w, h)` when you want them in
     * place. The right panel is extent-free and therefore exact: ρ against θ, sized by votes.
     */
    private fun houghLines(lines: List<HoughLine>): String {
        val maxRho = max(1.0, lines.maxOf { abs(it.rho) })
        val side = ceil(maxRho / 0.7071).coerceAtLeast(2.0)
        // Two panels side by side must both fit inside the card, so the geometry half is narrowed.
        val fit = Fit(0.0, 0.0, side, side, panelW = K.PANEL_W - K.HOUGH_PARAM_W + 8)
        val vLo = lines.minOf { it.votes }.toDouble()
        val vHi = lines.maxOf { it.votes }.toDouble()
        var missed = 0
        val shown = subsample(lines.sortedByDescending { it.votes }, K.MAX_GLYPHS)
        val geometry = fit.svg {
            for (l in shown) {
                val seg = l.clipTo(side.toInt(), side.toInt())
                if (seg == null) { missed++; continue }
                val (a, b) = seg
                val t = norm(l.votes.toDouble(), vLo, vHi)
                append("<line x1='${fmt(fit.tx(a.x.toDouble()))}' y1='${fmt(fit.ty(a.y.toDouble()))}' ")
                append("x2='${fmt(fit.tx(b.x.toDouble()))}' y2='${fmt(fit.ty(b.y.toDouble()))}' ")
                append("stroke='${hex(Colormaps.sample("plasma", 0.15 + 0.8 * t))}' ")
                append("stroke-width='${fmt(0.7 + 1.6 * t)}' stroke-opacity='0.9'/>")
            }
        }
        val param = buildString {
            append("<svg xmlns='http://www.w3.org/2000/svg' width='${K.HOUGH_PARAM_W}' height='${K.HOUGH_PARAM_H}' ")
            append("viewBox='0 0 ${K.HOUGH_PARAM_W} ${K.HOUGH_PARAM_H}' ")
            append("style='display:block;background:${K.PANEL_BG};border:1px solid ${K.BORDER};border-radius:3px'>")
            val rhoLo = lines.minOf { it.rho }
            val rhoHi = lines.maxOf { it.rho }
            for (l in shown) {
                val x = l.theta / Math.PI * (K.HOUGH_PARAM_W - 10) + 5
                val y = K.HOUGH_PARAM_H - 5 - norm(l.rho, rhoLo, rhoHi) * (K.HOUGH_PARAM_H - 10)
                val t = norm(l.votes.toDouble(), vLo, vHi)
                append("<circle cx='${fmt(x)}' cy='${fmt(y)}' r='${fmt(1.4 + 2.6 * t)}' ")
                append("fill='${hex(Colormaps.sample("plasma", 0.15 + 0.8 * t))}' fill-opacity='0.9'/>")
            }
            append("<text x='3' y='${K.HOUGH_PARAM_H - 1}' fill='${K.C_FAINT}' font-size='8' font-family='monospace'>&theta;=0</text>")
            append("<text x='${K.HOUGH_PARAM_W - 3}' y='${K.HOUGH_PARAM_H - 1}' text-anchor='end' fill='${K.C_FAINT}' font-size='8' font-family='monospace'>&pi;</text>")
            append("<text x='3' y='9' fill='${K.C_FAINT}' font-size='8' font-family='monospace'>&rho;</text>")
            append("</svg>")
        }
        return card {
            append("<div style='display:flex;gap:6px;align-items:flex-start;margin-bottom:4px'>")
            append("<div>$geometry<div style='color:${K.C_FAINT};font-size:9px;text-align:center;margin-top:2px'>")
            append("clipped to an inferred ${fmt(side)}&times;${fmt(side)} square</div></div>")
            append("<div>$param<div style='color:${K.C_FAINT};font-size:9px;text-align:center;margin-top:2px'>")
            append("(&theta;, &rho;) peaks</div></div>")
            append("</div>")
            append(
                caption(
                    typeToken("List&lt;HoughLine&gt;"),
                    plural(lines.size, "line"),
                    "votes ${vLo.toInt()}&hellip;${vHi.toInt()}",
                    "no image extent known",
                    if (missed > 0) "$missed missed the inferred square" else null
                )
            )
            append(disclosure("strongest lines") {
                append("<div style='color:${K.C_DIM};font-size:10px;line-height:1.5'>")
                for (l in lines.sortedByDescending { it.votes }.take(K.MAX_LISTED)) {
                    append("<div>&rho;=${num(l.rho)} &theta;=${num(l.theta)} rad ")
                    append("(${num(Math.toDegrees(l.theta))}&deg;) votes=${l.votes}</div>")
                }
                append("</div>")
                append("<div style='color:${K.C_FAINT};font-size:10px;margin-top:3px'>")
                append("draw them in place with <code>linesToFLines(lines, img.width, img.height)</code>")
                append("</div>")
            })
        }
    }

    /**
     * Hough circles as actual circles. Unlike a line, a circle *has* an extent, so the canvas is the
     * union of the circle bounding boxes with the origin — no assumption needed.
     */
    private fun houghCircles(circles: List<HoughCircle>): String {
        val maxX = max(1.0, circles.maxOf { it.x + it.radius })
        val maxY = max(1.0, circles.maxOf { it.y + it.radius })
        val fit = Fit(0.0, 0.0, maxX * 1.02 + 1, maxY * 1.02 + 1)
        val vLo = circles.minOf { it.votes }.toDouble()
        val vHi = circles.maxOf { it.votes }.toDouble()
        val shown = subsample(circles.sortedByDescending { it.votes }, K.MAX_GLYPHS)
        return card {
            append(fit.svg {
                for (c in shown) {
                    val t = norm(c.votes.toDouble(), vLo, vHi)
                    val col = hex(Colormaps.sample("turbo", 0.1 + 0.85 * t))
                    append("<circle cx='${fmt(fit.tx(c.x))}' cy='${fmt(fit.ty(c.y))}' ")
                    append("r='${fmt(max(0.8, c.radius * fit.scale))}' fill='none' ")
                    append("stroke='$col' stroke-width='${fmt(0.8 + 1.4 * t)}' stroke-opacity='0.95'/>")
                    append("<circle cx='${fmt(fit.tx(c.x))}' cy='${fmt(fit.ty(c.y))}' r='1.4' fill='$col'/>")
                }
            })
            append(
                caption(
                    typeToken("List&lt;HoughCircle&gt;"),
                    plural(circles.size, "circle"),
                    "r ${num(circles.minOf { it.radius })}&hellip;${num(circles.maxOf { it.radius })}",
                    "votes ${vLo.toInt()}&hellip;${vHi.toInt()}",
                    "extent ${fmt(fit.vbW)}&times;${fmt(fit.vbH)}"
                )
            )
            append(disclosure("strongest circles") {
                append("<div style='color:${K.C_DIM};font-size:10px;line-height:1.5'>")
                for (c in circles.sortedByDescending { it.votes }.take(K.MAX_LISTED)) {
                    append("<div>centre (${num(c.x)}, ${num(c.y)}) r=${num(c.radius)} votes=${c.votes}</div>")
                }
                append("</div>")
            })
        }
    }

    // ==========================================================================================
    // FlowField
    // ==========================================================================================

    /**
     * A sparse optical-flow field as a quiver: one arrow per converged track, hue by direction (the
     * same angle→hue convention [Gradient.toColorImg] uses), and **failed tracks drawn as grey
     * crosses** rather than dropped — a tracker that never reports failure is lying, and so is a
     * picture that hides the failures.
     *
     * Displacements are drawn at true pixel scale unless the median motion is under
     * `FLOW_VISIBLE_FRACTION` of the frame diagonal, in which case an integer exaggeration factor is
     * applied and named in the caption.
     */
    private fun flowField(flow: FlowField): String {
        val all = flow.vectors
        if (all.isEmpty()) {
            return card {
                append(caption(typeToken("FlowField"), "no tracks"))
            }
        }
        val tracked = flow.tracked()
        val maxX = max(1.0, all.maxOf { max(it.x0, it.x1) })
        val maxY = max(1.0, all.maxOf { max(it.y0, it.y1) })
        val fit = Fit(0.0, 0.0, maxX * 1.02 + 1, maxY * 1.02 + 1)
        val diag = hypot(fit.vbW, fit.vbH)
        val median = flow.medianMagnitude()
        val exaggeration = if (median > 1e-9 && median < K.FLOW_VISIBLE_FRACTION * diag) {
            max(1, (K.FLOW_VISIBLE_FRACTION * diag / median).roundToInt())
        } else 1
        val (mdx, mdy) = flow.medianDisplacement()
        return card {
            append(fit.svg {
                // Failures first, so arrows draw over them.
                for (v in all) {
                    if (v.ok) continue
                    val x = fit.tx(v.x0)
                    val y = fit.ty(v.y0)
                    append("<path d='M${fmt(x - 2.2)} ${fmt(y - 2.2)}L${fmt(x + 2.2)} ${fmt(y + 2.2)}")
                    append("M${fmt(x - 2.2)} ${fmt(y + 2.2)}L${fmt(x + 2.2)} ${fmt(y - 2.2)}' ")
                    append("stroke='#5b606c' stroke-width='0.9'/>")
                }
                for (v in subsample(tracked, K.MAX_GLYPHS)) {
                    val dx = v.dx * exaggeration
                    val dy = v.dy * exaggeration
                    val x0 = fit.tx(v.x0)
                    val y0 = fit.ty(v.y0)
                    val x1 = x0 + dx * fit.scale
                    val y1 = y0 + dy * fit.scale
                    val ang = atan2(y1 - y0, x1 - x0)
                    val head = 4.0
                    val col = hueColor((atan2(v.dy, v.dx) / (2 * Math.PI) + 1.0) % 1.0)
                    append("<path d='M${fmt(x0)} ${fmt(y0)}L${fmt(x1)} ${fmt(y1)}")
                    append("M${fmt(x1)} ${fmt(y1)}L${fmt(x1 - head * cos(ang - 0.4))} ${fmt(y1 - head * sin(ang - 0.4))}")
                    append("M${fmt(x1)} ${fmt(y1)}L${fmt(x1 - head * cos(ang + 0.4))} ${fmt(y1 - head * sin(ang + 0.4))}' ")
                    append("stroke='$col' stroke-width='1.2' stroke-linecap='round' fill='none'/>")
                    append("<circle cx='${fmt(x0)}' cy='${fmt(y0)}' r='1.1' fill='$col' fill-opacity='0.7'/>")
                }
            })
            append(
                caption(
                    typeToken("FlowField"),
                    "${tracked.size}/${all.size} tracked",
                    "median |d| ${num(median)}px",
                    "median d (${num(mdx)}, ${num(mdy)})",
                    if (exaggeration > 1) "arrows &times;$exaggeration" else "arrows at true scale",
                    if (tracked.size < all.size) "&times; = failed track" else null
                )
            )
        }
    }

    // ==========================================================================================
    // Raster rendering — the range policy lives here
    // ==========================================================================================

    /** Finite-only statistics of an [Img], plus the display range the policy derives from them. */
    private class Stats(
        val min: Double,
        val max: Double,
        val mean: Double,
        val sd: Double,
        val nonFinite: Int,
        val total: Int
    ) {
        val allFinite: Boolean get() = nonFinite == 0
        val anyFinite: Boolean get() = nonFinite < total

        /** `true` when some finite sample is negative — the trigger for diverging display. */
        val signed: Boolean get() = anyFinite && min < -1e-6

        /** `true` when the finite range already fits the displayable `0..1` box. */
        val inUnitBox: Boolean get() = anyFinite && min >= -1e-4 && max <= 1f + 1e-4

        val displayLo: Float
            get() = when {
                !anyFinite -> 0f
                signed -> -symmetric
                inUnitBox -> 0f
                else -> min.toFloat()
            }

        val displayHi: Float
            get() = when {
                !anyFinite -> 1f
                signed -> symmetric
                inUnitBox -> 1f
                else -> max.toFloat()
            }

        private val symmetric: Float get() = max(abs(min), abs(max)).toFloat().coerceAtLeast(1e-9f)

        fun rangeText(): String = when {
            !anyFinite -> "no finite samples"
            else -> "range ${num(min)}&hellip;${num(max)}"
        }

        companion object {
            fun of(image: Img): Stats {
                var mn = Double.POSITIVE_INFINITY
                var mx = Double.NEGATIVE_INFINITY
                var sum = 0.0
                var sumSq = 0.0
                var bad = 0
                for (v in image.data) {
                    if (!v.isFinite()) { bad++; continue }
                    val d = v.toDouble()
                    if (d < mn) mn = d
                    if (d > mx) mx = d
                    sum += d
                    sumSq += d * d
                }
                val n = image.data.size - bad
                if (n == 0) return Stats(0.0, 0.0, 0.0, 0.0, bad, image.data.size)
                val mean = sum / n
                val variance = (sumSq / n - mean * mean).coerceAtLeast(0.0)
                return Stats(mn, mx, mean, sqrt(variance), bad, image.data.size)
            }
        }
    }

    /** A rendered thumbnail: the PNG payload plus what the policy did to get there. */
    private class Raster(
        val base64: String,
        val w: Int,
        val h: Int,
        val mode: String,
        val modeNote: String?
    )

    /**
     * Turn an [Img] into a display-ready PNG under the documented range policy.
     *
     * @param ramp the [Colormaps] ramp to use; `null` means grey.
     * @param forceRamp take [ramp] literally. When `false` (the default) a `null` ramp is upgraded to
     *   `coolwarm` for signed 1-channel data, which is the whole point of the diverging mode; the
     *   per-channel strip sets this to `true` so it can show a genuinely plain grey reading.
     */
    private fun renderRaster(
        image: Img,
        stats: Stats,
        maxSide: Int,
        ramp: String? = null,
        forceRamp: Boolean = false
    ): Raster {
        val small = fitLongEdgeSanitized(image, maxSide, stats)
        val tw = small.width
        val th = small.height
        val lo = stats.displayLo
        val hi = stats.displayHi
        val span = (hi - lo).let { if (abs(it) < 1e-12f) 1f else it }
        val chosenRamp = if (forceRamp) ramp
        else ramp ?: if (image.channels == 1 && stats.signed) "coolwarm" else null
        val lut = chosenRamp?.let { name -> Array(256) { rgbInt(Colormaps.sample(name, it / 255.0)) } }
        val badRgb = rgbInt(K.NON_FINITE)
        val px = IntArray(tw * th)
        val ch = small.channels
        for (p in 0 until tw * th) {
            val i = p * ch
            var bad = false
            for (c in 0 until ch) if (!small.data[i + c].isFinite()) bad = true
            if (bad) { px[p] = badRgb; continue }
            px[p] = when {
                ch == 1 -> {
                    val t = ((small.data[i] - lo) / span).coerceIn(0f, 1f)
                    if (lut != null) lut[(t * 255f).toInt().coerceIn(0, 255)]
                    else {
                        val v = q8(t)
                        (255 shl 24) or (v shl 16) or (v shl 8) or v
                    }
                }
                else -> {
                    val r = q8(((small.data[i] - lo) / span))
                    val g = q8(((small.data[i + 1] - lo) / span))
                    val b = q8(((small.data[i + 2] - lo) / span))
                    // Alpha is a coverage, never a signal: it is clamped, never rescaled.
                    val a = if (ch == 4) q8(small.data[i + 3]) else 255
                    (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        val mode = when {
            !stats.anyFinite -> "no finite data"
            stats.signed -> "signed &plusmn;${num(max(abs(stats.min), abs(stats.max)))}"
            stats.inUnitBox -> "direct"
            else -> "auto-ranged"
        }
        val note = buildList {
            when {
                !stats.anyFinite -> add("every sample is non-finite")
                stats.signed -> add("signed &rarr; &plusmn;${num(max(abs(stats.min), abs(stats.max)))}" +
                    (if (chosenRamp != null) " <i>$chosenRamp</i>" else ""))
                stats.inUnitBox -> {}
                else -> add("auto-ranged for display")
            }
            if (chosenRamp != null && !stats.signed) add("<i>$chosenRamp</i>")
            if (stats.nonFinite > 0) add("${stats.nonFinite} non-finite (magenta)")
        }.joinToString(" &middot; ").ifBlank { null }
        return Raster(
            base64 = Base64.getEncoder().encodeToString(afield.util.PngEncoder.encode(px, tw, th)),
            w = tw, h = th, mode = mode, modeNote = note
        )
    }

    /**
     * Downsample to fit [maxSide] on the long edge. Non-finite samples are replaced by the finite
     * mean **before** resampling — the area-average filter would otherwise smear one NaN across a
     * whole neighbourhood — and the original non-finite pixels are re-stamped afterwards by
     * nearest-neighbour, so the magenta lands where the bad data actually is.
     */
    private fun fitLongEdgeSanitized(image: Img, maxSide: Int, stats: Stats): Img {
        if (stats.allFinite) return fitLongEdge(image, maxSide)
        val fill = if (stats.anyFinite) stats.mean.toFloat() else 0f
        val clean = Img(
            image.width, image.height, image.channels,
            FloatArray(image.data.size) { if (image.data[it].isFinite()) image.data[it] else fill }
        )
        val small = fitLongEdge(clean, maxSide)
        if (small.width == image.width && small.height == image.height) {
            // No resampling happened, so restore the exact non-finite pattern.
            for (i in image.data.indices) if (!image.data[i].isFinite()) small.data[i] = image.data[i]
            return small
        }
        for (y in 0 until small.height) {
            val sy = ((y + 0.5) / small.height * image.height).toInt().coerceIn(0, image.height - 1)
            for (x in 0 until small.width) {
                val sx = ((x + 0.5) / small.width * image.width).toInt().coerceIn(0, image.width - 1)
                for (c in 0 until image.channels) {
                    val v = image.data[image.index(sx, sy, c)]
                    if (!v.isFinite()) small.data[small.index(x, y, c)] = v
                }
            }
        }
        return small
    }

    /** Aspect-preserving downsample to at most [maxSide] on the long edge; never upsamples. */
    private fun fitLongEdge(image: Img, maxSide: Int): Img {
        val scale = min(1.0, maxSide.toDouble() / max(image.width, image.height))
        if (scale >= 1.0) return image
        val tw = max(1, (image.width * scale).roundToInt())
        val th = max(1, (image.height * scale).roundToInt())
        if (tw == image.width && th == image.height) return image
        return image.resize(tw, th)
    }

    /** One channel of [image] as a 1-channel image, without going through `Img.channel`'s checks. */
    private fun extractChannel(image: Img, c: Int): Img {
        val out = FloatArray(image.pixelCount)
        for (p in 0 until image.pixelCount) out[p] = image.data[p * image.channels + c]
        return Img(image.width, image.height, 1, out)
    }

    // ==========================================================================================
    // SVG geometry panels
    // ==========================================================================================

    /**
     * A data-space → panel-space fit. Everything is emitted in *panel* pixels rather than through an
     * SVG `viewBox`, so a marker radius or a stroke width means the same thing at any data scale.
     * Y is not flipped: image coordinates and SVG coordinates both run downwards.
     *
     * The members are spelled out (`panelW`, not `w`) for a reason. Inside [svg]'s `buildString` the
     * innermost implicit receiver is a `StringBuilder`, and this package declares
     * `val Any?.h: String` — so a `"height='$h'"` template silently resolved to
     * *`ValueVisualizer.visualize(theStringBuilder)`* and emitted a rendered value tree in the middle
     * of the `<svg>` tag. It compiled, the unit tests still found their `<circle>` substrings, and
     * only looking at the capture showed the panel was gone.
     */
    private class Fit(
        val vbX: Double,
        val vbY: Double,
        val vbW: Double,
        val vbH: Double,
        val panelW: Int = K.PANEL_W
    ) {
        val panelH: Int = (panelW * vbH / vbW).roundToInt().coerceIn(K.PANEL_H_MIN, K.PANEL_H_MAX)

        /** Uniform data→panel scale, with a small inset so glyphs on the border are not clipped. */
        val scale: Double = min((panelW - 8.0) / vbW, (panelH - 8.0) / vbH)
        private val ox = (panelW - vbW * scale) / 2.0
        private val oy = (panelH - vbH * scale) / 2.0

        fun tx(x: Double): Double = (x - vbX) * scale + ox
        fun ty(y: Double): Double = (y - vbY) * scale + oy

        inline fun svg(body: StringBuilder.() -> Unit): String = buildString {
            append("<svg xmlns='http://www.w3.org/2000/svg' ")
            append("width='${panelW}' height='${panelH}' viewBox='0 0 ${panelW} ${panelH}' ")
            append("style='display:block;background:${K.PANEL_BG};border:1px solid ${K.BORDER};border-radius:3px'>")
            // The data rectangle, so "where in the frame" is legible even for a sparse scatter.
            append("<rect x='${fmt(ox)}' y='${fmt(oy)}' ")
            append("width='${fmt(vbW * scale)}' height='${fmt(vbH * scale)}' ")
            append("fill='none' stroke='#262a33'/>")
            body()
            append("</svg>")
        }
    }

    // ==========================================================================================
    // Small HTML helpers
    // ==========================================================================================

    private inline fun card(body: StringBuilder.() -> Unit): String = buildString {
        append("<div style='display:inline-block;max-width:340px;font-family:monospace;font-size:11px;line-height:1.4'>")
        body()
        append("</div>")
    }

    private fun typeToken(name: String): String = "<span style='color:${K.C_HEAD}'>$name</span>"

    private fun caption(vararg parts: String?): String = buildString {
        append("<div style='color:${K.C_DIM};margin-top:3px'>")
        append(parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" &middot; "))
        append("</div>")
    }

    private inline fun disclosure(summary: String, body: StringBuilder.() -> Unit): String = buildString {
        append("<details style='margin-top:4px'>")
        append("<summary style='cursor:pointer;color:${K.C_FAINT};font-size:10px;outline:none'>")
        append(summary)
        append("</summary>")
        append("<div style='margin-top:4px'>")
        body()
        append("</div></details>")
    }

    private fun rasterFigure(r: Raster, checkerboard: Boolean): String = imgTag(r.base64, r.w, r.h, checkerboard)

    /**
     * The `<img>` itself. A 4-channel image sits on a CSS checkerboard so transparent black and
     * opaque black are not the same picture, and `image-rendering:pixelated` keeps a small thumbnail
     * exact about where its pixel edges are.
     */
    private fun imgTag(base64: String, w: Int, h: Int, checkerboard: Boolean): String = buildString {
        val bg = if (checkerboard) {
            "background-color:#3a3f4b;" +
                "background-image:linear-gradient(45deg,#22252d 25%,transparent 25%,transparent 75%,#22252d 75%)," +
                "linear-gradient(45deg,#22252d 25%,transparent 25%,transparent 75%,#22252d 75%);" +
                "background-size:12px 12px;background-position:0 0,6px 6px;"
        } else ""
        append("<div style='display:inline-block;line-height:0;border:1px solid ${K.BORDER};border-radius:3px;$bg'>")
        append("<img src='data:image/png;base64,$base64' width='$w' height='$h' ")
        append("style='image-rendering:pixelated;display:block'/>")
        append("</div>")
    }

    private fun kv(key: String, value: String, color: String = K.C_ACCENT): String =
        "<span style='white-space:nowrap'><span style='color:${K.C_FAINT}'>$key</span>=" +
            "<span style='color:$color'>$value</span></span>"

    // ==========================================================================================
    // Numeric / colour helpers
    // ==========================================================================================

    private fun channelWord(channels: Int): String = when (channels) {
        1 -> "gray"
        3 -> "rgb"
        else -> "rgba"
    }

    private fun channelName(channels: Int, c: Int): String =
        if (channels == 1) "v" else listOf("R", "G", "B", "A")[c]

    private fun channelColor(channels: Int, c: Int): String = when {
        channels == 1 -> K.C_OK
        else -> listOf("#f66", "#6f6", "#69f", "#ccc")[c]
    }

    /** A stable, well-separated colour per component label: golden-ratio hue rotation. */
    private fun labelColor(id: Int): Color4 = hsv((id * 0.61803398875) % 1.0, 0.72, 0.95)

    private fun hueColor(hue: Double): String = hex(hsv(hue, 0.85, 1.0))

    private fun hsv(h: Double, s: Double, v: Double): Color4 {
        val hh = ((h % 1.0) + 1.0) % 1.0 * 6.0
        val i = hh.toInt()
        val f = hh - i
        val p = v * (1 - s)
        val q = v * (1 - s * f)
        val t = v * (1 - s * (1 - f))
        return when (i % 6) {
            0 -> Color4(v, t, p)
            1 -> Color4(q, v, p)
            2 -> Color4(p, v, t)
            3 -> Color4(p, q, v)
            4 -> Color4(t, p, v)
            else -> Color4(v, p, q)
        }
    }

    private fun hex(c: Color4): String =
        "#%02x%02x%02x".format(q8(c.r.toFloat()), q8(c.g.toFloat()), q8(c.b.toFloat()))

    private fun rgbInt(c: Color4): Int =
        (255 shl 24) or (q8(c.r.toFloat()) shl 16) or (q8(c.g.toFloat()) shl 8) or q8(c.b.toFloat())

    private fun q8(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)

    private fun norm(v: Double, lo: Double, hi: Double): Double =
        if (hi - lo < 1e-12) 0.5 else ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0)

    /** Evenly thin a list down to at most [limit] entries, keeping the first (i.e. the strongest). */
    private fun <T> subsample(items: List<T>, limit: Int): List<T> {
        if (items.size <= limit) return items
        val step = items.size.toDouble() / limit
        return (0 until limit).map { items[(it * step).toInt().coerceIn(0, items.size - 1)] }
    }

    /** Compact fixed-point for SVG coordinates. */
    private fun fmt(v: Double): String {
        val r = (v * 100.0).roundToInt() / 100.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
    }

    private fun fmt(v: Int): String = v.toString()

    /** Human number: fixed-point in the middle of the range, scientific at the extremes. */
    private fun num(v: Double): String = when {
        v.isNaN() -> "NaN"
        v == Double.POSITIVE_INFINITY -> "&infin;"
        v == Double.NEGATIVE_INFINITY -> "-&infin;"
        v == 0.0 -> "0"
        abs(v) >= 1e6 || abs(v) < 1e-4 -> "%.2e".format(v)
        abs(v) >= 1000 -> "%.0f".format(v)
        else -> "%.4f".format(v).trimEnd('0').trimEnd('.')
    }

    private fun num(v: Float): String = num(v.toDouble())

    /** "1 circle", "4 circles" — a detector that finds exactly one thing should not stutter. */
    private fun plural(n: Int, singular: String): String =
        if (n == 1) "1 $singular" else "$n ${singular}s"
}
