@file:afield.CompletionCategory("Data")

package afield.stdlib

import afield.Colormaps
import java.util.Base64
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The bridge from Wave Function Collapse into the things Field already displays.
 *
 * `wfc.kt` knows about integer tile IDs, `BufferedImage` and `FloatArray`, and nothing else. The
 * hop into [Img] is here, and with it `Box.showImg`, `showImgGrid`, `montage`, `Img.colormap` and
 * the `ToHTML` thumbnail a box shows when you evaluate a value.
 *
 * Three kinds of view:
 *
 *  1. **The result.** [PngTileSet.toImg], [PngOverlappingTileSet.toImg] and their `Float`
 *     counterparts turn a solved tile grid into an image. Pair them with
 *     [Img.upscaleNearest] — WFC output is pixels-as-pixels, and any filtering upscale destroys
 *     exactly the local structure you were trying to show.
 *  2. **The process.** [PngTileSet.waveToImg] renders a *partially collapsed* solver by blending
 *     each cell's still-possible tiles, and [WfcSolver.possibilityCountImg] exposes the
 *     possibility count per cell as a scalar field to colour-map. Sample either one inside a
 *     `while (solver.step() == WfcStatus.Running)` loop and you have the collapse itself as a
 *     sequence of frames, which is what to watch when rules are not behaving.
 *  3. **The solver, inline.** [wfcSolverHtml] is the body of [WfcSolver]'s own `ToHTML`, so simply
 *     *evaluating* a solver in a box — or `solver.h` — draws its current state: uncertainty,
 *     entropy, what has been decided, where it broke. Nothing to set up, no tile set needed. See
 *     [WfcSolver.possibilityCountHeatmapImg], [WfcSolver.entropyHeatmapImg] and
 *     [WfcSolver.resolvedTileImg] for the three panels on their own.
 *
 * A solved cell has exactly one possibility, so `waveToImg` on a [WfcStatus.Solved] solver is the
 * finished output: the animation's last frame and the result are the same picture.
 */

/** Magenta. Cells whose possibility set is *empty* are drawn in it, so a contradiction is visible. */
private const val WFC_CONTRADICTION_R = 1f
private const val WFC_CONTRADICTION_G = 0f
private const val WFC_CONTRADICTION_B = 0.6f

// ==============================================================================================
// The result
// ==============================================================================================

/**
 * Render a solved simple-tiled grid as an RGB [Img].
 *
 * ```
 * val grid = solver.resolvedTileGridOrNull() ?: error("not solved")
 * tiles.toImg(grid, 24, 16).upscaleNearest(4)
 * ```
 *
 * @param tileIds one tile ID per output cell, row-major, `outWidthInTiles * outHeightInTiles` long
 */
fun PngTileSet.toImg(tileIds: IntArray, outWidthInTiles: Int, outHeightInTiles: Int): Img =
    render(tileIds, outWidthInTiles, outHeightInTiles).toImg(3)

/** Render a solved overlapping-pattern grid as an RGB [Img]. See [PngOverlappingTileSet.render]. */
fun PngOverlappingTileSet.toImg(
    patternIds: IntArray,
    outWidth: Int,
    outHeight: Int,
    originX: Int = 0,
    originY: Int = 0
): Img = render(patternIds, outWidth, outHeight, originX, originY).toImg(3)

/** Render a solved simple-tiled grid of scalars as a 1-channel [Img]. */
fun FloatTileSet.toImg(tileIds: IntArray, outWidthInTiles: Int, outHeightInTiles: Int): Img =
    floatsToImg(
        render(tileIds, outWidthInTiles, outHeightInTiles),
        outWidthInTiles * tileWidth,
        outHeightInTiles * tileHeight
    )

/** Render a solved overlapping grid of scalars as a 1-channel [Img]. */
fun FloatOverlappingTileSet.toImg(
    patternIds: IntArray,
    outWidth: Int,
    outHeight: Int,
    originX: Int = 0,
    originY: Int = 0
): Img = floatsToImg(render(patternIds, outWidth, outHeight, originX, originY), outWidth, outHeight)

/** The source image a tile set was learned from, as an [Img] — useful next to the output. */
fun PngTileSet.sampleToImg(): Img = toImg(sampleIds, sampleWidthInTiles, sampleHeightInTiles)

// ==============================================================================================
// The process
// ==============================================================================================

/**
 * Render a solver **mid-collapse**: each cell is the average of the tiles still possible there.
 *
 * This is the picture that makes WFC legible. A cell with every tile still possible averages to
 * flat grey; as propagation rules options out, cells drift toward the colour of what they are
 * becoming, and structure precipitates out of the haze in the order the solver actually decided it.
 * Cells whose possibility set has been emptied — a contradiction — are drawn magenta.
 *
 * The output is sized like the finished result (`solver.width * tileWidth` by
 * `solver.height * tileHeight`), so frames sampled across a run are all the same size and can be
 * stacked into a video or a [montage] without rescaling.
 *
 * ```
 * while (solver.step() == WfcStatus.Running) frames += tiles.waveToImg(solver)
 * ```
 */
fun PngTileSet.waveToImg(solver: WfcSolver): Img {
    val outW = solver.width * tileWidth
    val outH = solver.height * tileHeight
    val out = FloatArray(outW * outH * 3)
    val scratch = IntArray(solver.rules.tileCount)

    var cy = 0
    while (cy < solver.height) {
        var cx = 0
        while (cx < solver.width) {
            val n = solver.possibilitiesAt(cx, cy, scratch)
            var py = 0
            while (py < tileHeight) {
                var px = 0
                while (px < tileWidth) {
                    val di = ((cy * tileHeight + py) * outW + (cx * tileWidth + px)) * 3
                    if (n == 0) {
                        out[di] = WFC_CONTRADICTION_R
                        out[di + 1] = WFC_CONTRADICTION_G
                        out[di + 2] = WFC_CONTRADICTION_B
                    } else {
                        var r = 0f
                        var g = 0f
                        var b = 0f
                        var k = 0
                        while (k < n) {
                            val argb = tiles[scratch[k]][px + py * tileWidth]
                            r += ((argb shr 16) and 0xff) / 255f
                            g += ((argb shr 8) and 0xff) / 255f
                            b += (argb and 0xff) / 255f
                            k++
                        }
                        out[di] = r / n
                        out[di + 1] = g / n
                        out[di + 2] = b / n
                    }
                    px++
                }
                py++
            }
            cx++
        }
        cy++
    }
    return Img(outW, outH, 3, out)
}

/**
 * [PngTileSet.waveToImg] for the overlapping model: one output pixel per solver cell, averaging the
 * origin pixel of every pattern still possible there.
 *
 * @param originX which pixel of the pattern a cell stands for, matching
 *   [PngOverlappingTileSet.render]'s `originX`
 */
fun PngOverlappingTileSet.waveToImg(solver: WfcSolver, originX: Int = 0, originY: Int = 0): Img {
    val ox = ((originX % patternWidth) + patternWidth) % patternWidth
    val oy = ((originY % patternHeight) + patternHeight) % patternHeight
    val origin = ox + oy * patternWidth
    val out = FloatArray(solver.width * solver.height * 3)
    val scratch = IntArray(solver.rules.tileCount)

    var y = 0
    while (y < solver.height) {
        var x = 0
        while (x < solver.width) {
            val n = solver.possibilitiesAt(x, y, scratch)
            val di = (y * solver.width + x) * 3
            if (n == 0) {
                out[di] = WFC_CONTRADICTION_R
                out[di + 1] = WFC_CONTRADICTION_G
                out[di + 2] = WFC_CONTRADICTION_B
            } else {
                var r = 0f
                var g = 0f
                var b = 0f
                var k = 0
                while (k < n) {
                    val argb = patterns[scratch[k]][origin]
                    r += ((argb shr 16) and 0xff) / 255f
                    g += ((argb shr 8) and 0xff) / 255f
                    b += (argb and 0xff) / 255f
                    k++
                }
                out[di] = r / n
                out[di + 1] = g / n
                out[di + 2] = b / n
            }
            x++
        }
        y++
    }
    return Img(solver.width, solver.height, 3, out)
}

/**
 * How many tiles are still possible in every cell, row-major — the solver's remaining uncertainty.
 *
 * `1` means decided, `0` means contradiction, `rules.tileCount` means untouched. This is the raw
 * count rather than the Shannon entropy the solver actually minimises (which is weighted, see
 * [WfcRules.weights]); the count is the quantity worth *looking* at, because its scale is
 * interpretable — "three options left here" — where entropy's is not.
 */
fun WfcSolver.possibilityCountGrid(): IntArray {
    val out = IntArray(width * height)
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            out[x + y * width] = possibilityCountAt(x, y)
            x++
        }
        y++
    }
    return out
}

/**
 * [possibilityCountGrid] as a 1-channel [Img], ready to colour-map.
 *
 * The values are *counts*, not `0..1`, so pass an explicit range when mapping — otherwise the
 * colours rescale on every frame and an animation of the collapse appears to go nowhere:
 *
 * ```
 * solver.possibilityCountImg().colormap("magma", lo = 0f, hi = rules.tileCount.toFloat())
 * ```
 */
fun WfcSolver.possibilityCountImg(): Img {
    val counts = possibilityCountGrid()
    val out = FloatArray(counts.size)
    var i = 0
    while (i < counts.size) {
        out[i] = counts[i].toFloat()
        i++
    }
    return Img(width, height, 1, out)
}

/**
 * [WfcSolver.resolvedCellCount] as a `0..1` fraction — a progress read-out for a `step()` loop.
 *
 * (There used to be a `WfcSolver.resolvedCellCount()` extension here as well. It was dead on
 * arrival: the class has a member of the same name, a member always wins over an extension, so no
 * caller could ever have reached it. The compiler said so; it is gone.)
 */
fun WfcSolver.resolvedFraction(): Double =
    resolvedCellCount().toDouble() / (width.toDouble() * height.toDouble())

// ==============================================================================================
// The solver, inline — WfcSolver's own ToHTML
// ==============================================================================================

/*
 * Everything below is the body of [WfcSolver.toHTML]. It lives here, not in `wfc.kt`, so that the
 * solver file keeps its "integer tile IDs and nothing else" footprint: `wfc.kt` names exactly one
 * symbol from this file, [wfcSolverHtml], and inherits every pixel of the view from here.
 *
 * Range-policy discipline, which is the whole difference between a heatmap and a lie:
 *
 *  - Every colour scale is **named in the caption**, with its two endpoints spelled out in the
 *    units of the thing being shown. A picture whose scale is a mystery is decoration.
 *  - Domains are **fixed by meaning, not by the frame**: possibilities left runs `1..tileCount`,
 *    entropy runs `0..` the full-set entropy. Auto-ranging per frame makes an animation of a
 *    collapse appear to stand still, because the ends of the ramp move with the data.
 *  - Values with no place on the scale — NaN, infinities, an emptied possibility set — are
 *    **excluded from the statistics and painted a marker colour** ([WFC_UNDEFINED_COLOR],
 *    [WFC_CONTRADICTION_COLOR]), never clamped onto an end of the ramp where they would read as a
 *    legitimate extreme. The caption says how many there were.
 *  - A **degenerate domain** (no finite values at all, or a zero-width range) is drawn mid-ramp and
 *    declared flat, rather than dividing by zero and painting whatever falls out.
 *  - A marker is only a marker if it is **outside its own ramp**. The count panel is viridis
 *    (purple → teal → yellow) because its marker is magenta; the entropy panel is magma
 *    (black → pink → cream) because its marker is cyan. Drawing counts in magma — the first thing
 *    tried — put mid-ramp pinks a shade away from [WFC_CONTRADICTION_COLOR] and made a perfectly
 *    healthy collapse frontier read as a field of contradictions. Only looking at it caught that.
 */

/** Packed `0xRRGGBB` of the contradiction marker — magenta, matching [PngTileSet.waveToImg]. */
val WFC_CONTRADICTION_COLOR: Int = packRgb(WFC_CONTRADICTION_R, WFC_CONTRADICTION_G, WFC_CONTRADICTION_B)

/** Packed `0xRRGGBB` of the "this value has no place on the scale" marker — cyan. NaN, or infinite. */
const val WFC_UNDEFINED_COLOR: Int = 0x00D9FF

/** Packed `0xRRGGBB` drawn for a cell that is still undecided in [WfcSolver.resolvedTileImg] — dark grey. */
const val WFC_UNDECIDED_COLOR: Int = 0x23262E

/**
 * A colour-mapped scalar-per-cell field, together with the range policy that produced it — so a
 * caption can state the scale rather than implying one.
 *
 * @property img one RGB pixel per cell, row-major, same shape as the solver grid
 * @property lo sample value at the dark end of the ramp
 * @property hi sample value at the bright end
 * @property finiteCount cells whose value landed on the scale
 * @property nonFiniteCount cells painted the marker colour instead (NaN or infinite)
 * @property flat `true` when the domain was degenerate and every finite cell was drawn mid-ramp
 * @property colormap the [Colormaps] name used
 */
class WfcHeatmap internal constructor(
    val img: Img,
    val lo: Float,
    val hi: Float,
    val finiteCount: Int,
    val nonFiniteCount: Int,
    val flat: Boolean,
    val colormap: String
) {
    override fun toString(): String =
        "WfcHeatmap(${img.width}x${img.height}, $colormap ${"%.3f".format(lo)}..${"%.3f".format(hi)}" +
            (if (flat) ", flat" else "") + (if (nonFiniteCount > 0) ", $nonFiniteCount non-finite" else "") + ")"
}

/** Pack three `0..1` channels the way [Img.toPngBytes] quantises them, so the two agree exactly. */
private fun packRgb(r: Float, g: Float, b: Float): Int = (q8(r) shl 16) or (q8(g) shl 8) or q8(b)

private fun q8(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)

private fun writePacked(out: FloatArray, di: Int, packed: Int) {
    out[di] = ((packed shr 16) and 0xff) / 255f
    out[di + 1] = ((packed shr 8) and 0xff) / 255f
    out[di + 2] = (packed and 0xff) / 255f
}

/**
 * Colour-map one scalar per cell under an explicit range policy. The workhorse behind
 * [WfcSolver.possibilityCountHeatmap] and [WfcSolver.entropyHeatmap]; see the note above for the
 * rules it follows.
 *
 * @param requestedLo/[requestedHi] the fixed domain; `null` (or non-finite) falls back to the
 *   finite min/max of [values]
 * @param markerColor packed `0xRRGGBB` for cells whose value is not finite
 */
private fun heatmapOf(
    values: FloatArray,
    width: Int,
    height: Int,
    colormap: String,
    requestedLo: Float?,
    requestedHi: Float?,
    markerColor: Int
): WfcHeatmap {
    require(values.size == width * height) {
        "heatmap needs one value per cell: ${width * height} expected, ${values.size} given"
    }
    var finite = 0
    var vmin = Float.POSITIVE_INFINITY
    var vmax = Float.NEGATIVE_INFINITY
    for (v in values) if (v.isFinite()) {
        finite++
        if (v < vmin) vmin = v
        if (v > vmax) vmax = v
    }
    val lo = requestedLo?.takeIf { it.isFinite() } ?: if (finite > 0) vmin else 0f
    val hi = requestedHi?.takeIf { it.isFinite() } ?: if (finite > 0) vmax else 0f
    val span = hi - lo
    val flat = finite == 0 || !span.isFinite() || span <= 1e-12f

    val lut = Array(256) { Colormaps.sample(colormap, it / 255.0) }
    val out = FloatArray(width * height * 3)
    for (p in values.indices) {
        val di = p * 3
        val v = values[p]
        if (!v.isFinite()) {
            writePacked(out, di, markerColor)
        } else {
            val t = if (flat) 0.5 else ((v - lo) / span).coerceIn(0f, 1f).toDouble()
            val c = lut[(t * 255.0).roundToInt().coerceIn(0, 255)]
            out[di] = c.r.toFloat()
            out[di + 1] = c.g.toFloat()
            out[di + 2] = c.b.toFloat()
        }
    }
    return WfcHeatmap(Img(width, height, 3, out), lo, hi, finite, values.size - finite, flat, colormap)
}

/**
 * Uncertainty as a picture: one pixel per cell, coloured by how many tiles are still possible there.
 *
 * The domain is fixed at `1 .. rules.tileCount` — decided at the dark end, untouched at the bright
 * end — so successive frames of a collapse are directly comparable. A cell with **zero**
 * possibilities is a contradiction, not "even more decided than decided"; it is taken off the scale
 * and painted [WFC_CONTRADICTION_COLOR], because clamping it to the low end would render the one
 * failure mode you most need to see as a success.
 */
fun WfcSolver.possibilityCountHeatmap(colormap: String = "viridis"): WfcHeatmap {
    val counts = possibilityCountGrid()
    val values = FloatArray(counts.size) { if (counts[it] <= 0) Float.NaN else counts[it].toFloat() }
    return heatmapOf(
        values, width, height, colormap,
        1f, max(1, rules.tileCount).toFloat(),
        WFC_CONTRADICTION_COLOR
    )
}

/** [possibilityCountHeatmap] as just the image. */
fun WfcSolver.possibilityCountHeatmapImg(colormap: String = "viridis"): Img =
    possibilityCountHeatmap(colormap).img

/**
 * The **weighted Shannon entropy** of every cell, in nats, row-major — the quantity the solver
 * itself minimises when it picks the next cell to observe, unlike the raw count from
 * [possibilityCountGrid].
 *
 * For a cell whose remaining tiles have weights `wᵢ` and `S = Σwᵢ`, this is
 * `ln S − (Σ wᵢ ln wᵢ) / S`: zero when one tile remains, largest when every tile does, and
 * sensitive to *which* tiles remain rather than only how many.
 *
 * Returns [Float.NaN] where the entropy is genuinely undefined: an emptied possibility set (a
 * contradiction), or a cell whose every remaining tile has weight zero — which is reachable, since
 * [WfcRules.fromSampleGrid] gives weight zero to any tile the sample never contained. Callers must
 * not treat that NaN as a small number; see [entropyHeatmap].
 */
fun WfcSolver.entropyGrid(): FloatArray {
    val scratch = IntArray(max(1, rules.tileCount))
    val out = FloatArray(width * height)
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val n = possibilitiesAt(x, y, scratch)
            out[x + y * width] = if (n == 0) Float.NaN else {
                var sum = 0.0
                var sumLog = 0.0
                var k = 0
                while (k < n) {
                    val w = rules.weights[scratch[k]]
                    if (w > 0.0) {
                        sum += w
                        sumLog += w * ln(w)
                    }
                    k++
                }
                if (sum <= 0.0) Float.NaN else (ln(sum) - sumLog / sum).toFloat()
            }
            x++
        }
        y++
    }
    return out
}

/**
 * The entropy of a cell in which *every* tile is still possible — the top of the [entropyHeatmap]
 * scale, and the value [WfcSolver.entropyGrid] returns for an untouched cell.
 *
 * [Double.NaN] if no tile has a positive weight, in which case entropy is undefined everywhere.
 */
fun WfcRules.fullSetEntropy(): Double {
    var sum = 0.0
    var sumLog = 0.0
    for (w in weights) if (w > 0.0) {
        sum += w
        sumLog += w * ln(w)
    }
    return if (sum <= 0.0) Double.NaN else ln(sum) - sumLog / sum
}

/**
 * [entropyGrid] colour-mapped over a fixed `0 ..` [WfcRules.fullSetEntropy] domain — decided cells
 * dark, wide-open cells bright, and cells whose entropy is undefined painted
 * [WFC_UNDEFINED_COLOR] and excluded from the range rather than clamped to the "decided" end.
 */
fun WfcSolver.entropyHeatmap(colormap: String = "magma"): WfcHeatmap {
    val full = rules.fullSetEntropy()
    return heatmapOf(
        entropyGrid(), width, height, colormap,
        0f, if (full.isFinite()) full.toFloat() else null,
        WFC_UNDEFINED_COLOR
    )
}

/** [entropyHeatmap] as just the image. */
fun WfcSolver.entropyHeatmapImg(colormap: String = "magma"): Img = entropyHeatmap(colormap).img

/**
 * A stable, tileset-independent display colour for tile id [tile], packed `0xRRGGBB`.
 *
 * Hues walk the golden angle so that consecutive ids are maximally far apart and any two runs agree
 * — the same tile is the same colour in every frame and every session. These are emphatically *not*
 * the tileset's own colours (the solver has never seen them); they say "this cell decided on tile 3"
 * and nothing more. For the real pixels, use [PngTileSet.toImg].
 */
fun wfcTileColor(tile: Int): Int {
    val h = ((tile * 0.6180339887498949) % 1.0 + 1.0) % 1.0
    return hsvPacked(h, 0.62, 0.95)
}

private fun hsvPacked(h: Double, s: Double, v: Double): Int {
    val i = (h * 6.0).toInt()
    val f = h * 6.0 - i
    val p = v * (1 - s)
    val q = v * (1 - f * s)
    val t = v * (1 - (1 - f) * s)
    val (r, g, b) = when (i % 6) {
        0 -> Triple(v, t, p)
        1 -> Triple(q, v, p)
        2 -> Triple(p, v, t)
        3 -> Triple(p, q, v)
        4 -> Triple(t, p, v)
        else -> Triple(v, p, q)
    }
    return packRgb(r.toFloat(), g.toFloat(), b.toFloat())
}

/**
 * What the solver has actually decided: one pixel per cell, [wfcTileColor] where the cell has
 * collapsed to a single tile, [WFC_UNDECIDED_COLOR] where it has not, [WFC_CONTRADICTION_COLOR]
 * where its possibility set is empty.
 *
 * This is the complement of [possibilityCountHeatmapImg] — that one shows how much freedom is left,
 * this one shows what was spent. Together they read as "where is it working, and on what".
 */
fun WfcSolver.resolvedTileImg(): Img {
    val out = FloatArray(width * height * 3)
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val p = x + y * width
            val count = possibilityCountAt(x, y)
            val packed = when {
                count == 0 -> WFC_CONTRADICTION_COLOR
                count == 1 -> wfcTileColor(resolvedTileAt(x, y))
                else -> WFC_UNDECIDED_COLOR
            }
            writePacked(out, p * 3, packed)
            x++
        }
        y++
    }
    return Img(width, height, 3, out)
}

/** Longest side, in CSS pixels, of each panel in [wfcSolverHtml]. */
private const val WFC_PANEL_MAX_SIDE = 156

/**
 * The inline view of a solver — the body of [WfcSolver]'s `ToHTML`, so that evaluating a solver in
 * a box (or `solver.h`) shows the collapse rather than a reflective property tree.
 *
 * Three panels, each one pixel per solver cell (browser-scaled with `image-rendering: pixelated`,
 * so screen pixel `(x·f, y·f)` really is cell `(x, y)`):
 *
 *  1. **possibilities left** — [possibilityCountHeatmap], viridis over a fixed `1..tileCount`
 *  2. **entropy** — [entropyHeatmap], magma over a fixed `0..`[WfcRules.fullSetEntropy]
 *  3. **decided tiles** — [resolvedTileImg], a stable hue per tile id
 *
 * plus a header carrying status, the collapse percentage, any contradiction count, and the last
 * observation. Each panel's caption names its colour scale and both endpoints in units, and reports
 * how many cells fell off the scale; see the range-policy note at the top of this section.
 *
 * Never throws: a solver mid-mutation on another thread degrades to the header line alone rather
 * than taking out the box's whole output.
 */
fun wfcSolverHtml(solver: WfcSolver): String {
    val cells = solver.width * solver.height
    var resolved = 0
    var contradictions = 0
    var untouched = 0
    val counts = try {
        solver.possibilityCountGrid()
    } catch (t: Throwable) {
        return wfcHeaderHtml(solver, 0, 0, 0, 0) + "\n<div style=\"$WFC_NOTE_STYLE\">state unavailable: $t</div>"
    }
    for (c in counts) when {
        c <= 0 -> contradictions++
        c == 1 -> resolved++
        c >= solver.rules.tileCount -> untouched++
    }
    val header = wfcHeaderHtml(solver, cells, resolved, contradictions, untouched)

    val panels = try {
        val count = solver.possibilityCountHeatmap()
        val entropy = solver.entropyHeatmap()
        val tileNames = (0 until min(6, solver.rules.tileCount)).joinToString(" ") { tile ->
            "<span style=\"color:#${"%06x".format(wfcTileColor(tile))}\">&#9632;$tile</span>"
        } + if (solver.rules.tileCount > 6) " <span style=\"color:#8a90a0\">&hellip;</span>" else ""
        listOf(
            wfcPanelHtml(
                count.img,
                "possibilities left",
                rampLegendHtml(count.colormap, wfcNum(count.lo), wfcNum(count.hi)),
                "colour = tiles still possible in that cell, ${count.colormap} " +
                    "${wfcNum(count.lo)} (decided) &hellip; ${wfcNum(count.hi)} (untouched)" +
                    wfcFlatNote(count) +
                    if (count.nonFiniteCount > 0) {
                        " &middot; <b style=\"color:#${"%06x".format(WFC_CONTRADICTION_COLOR)}\">magenta</b> " +
                            "= 0 possible, a contradiction (${count.nonFiniteCount} cell${wfcS(count.nonFiniteCount)}, off scale)"
                    } else ""
            ),
            wfcPanelHtml(
                entropy.img,
                "entropy",
                rampLegendHtml(entropy.colormap, wfcNum(entropy.lo), wfcNum(entropy.hi)),
                "colour = weighted Shannon entropy in nats, ${entropy.colormap} " +
                    "${wfcNum(entropy.lo)} (decided) &hellip; ${wfcNum(entropy.hi)} (every tile possible)" +
                    wfcFlatNote(entropy) +
                    if (entropy.nonFiniteCount > 0) {
                        " &middot; <b style=\"color:#${"%06x".format(WFC_UNDEFINED_COLOR)}\">cyan</b> = undefined " +
                            "(no possibilities left, or every remaining tile has weight 0) in " +
                            "${entropy.nonFiniteCount} cell${wfcS(entropy.nonFiniteCount)}, excluded from the range"
                    } else ""
            ),
            wfcPanelHtml(
                solver.resolvedTileImg(),
                "decided tiles",
                null,
                "colour = the tile a cell settled on, a stable hue per id, <i>not</i> the tileset's own " +
                    "colours: $tileNames &middot; dark grey = still undecided"
            )
        )
    } catch (t: Throwable) {
        listOf("<div style=\"$WFC_NOTE_STYLE\">view unavailable: $t</div>")
    }

    return """
        <div style="font-family:monospace;font-size:11px;line-height:1.4">
          $header
        ${wfcPanelGridHtml(panels)}
        </div>
    """.trimIndent()
}

private const val WFC_NOTE_STYLE = "color:#8a90a0;margin-top:3px"

/**
 * The panel container: a grid of a **fixed** column count, not `flex-wrap`.
 *
 * A box sizes an HTML output by measuring the fragment's laid-out extent, and a wrapping flex row
 * makes that measurement depend on the width it happened to be measured at. Measure three panels
 * somewhere narrow enough to fit two and the container reports the height of two rows; render it
 * wide and you get one row of panels above an equal expanse of empty black, which is what both
 * `wfc_examples.md` figures used to show. A fixed column count has the same height at every width,
 * so there is nothing to get stale.
 *
 * Capped at three so a many-direction rule set still wraps onto further rows instead of growing one
 * enormous line; the wrapping is just done by the grid, at a point that does not depend on layout.
 */
private fun wfcPanelGridHtml(panels: List<String>): String {
    val columns = panels.size.coerceIn(1, 3)
    return """
        <div style="display:grid;grid-template-columns:repeat($columns, max-content);
                    gap:10px;margin-top:5px;justify-content:start;align-items:start">
        ${panels.joinToString("\n")}
        </div>
    """.trimIndent()
}

/** Format a scale endpoint: integer counts as integers, entropies to three places. */
private fun wfcNum(v: Float): String =
    if (v == v.toInt().toFloat() && kotlin.math.abs(v) < 1e6f) v.toInt().toString() else "%.3f".format(v)

private fun wfcS(n: Int): String = if (n == 1) "" else "s"

private fun wfcFlatNote(h: WfcHeatmap): String = when {
    h.finiteCount == 0 -> " &middot; no cell has a value on this scale"
    h.flat -> " &middot; the range is flat, so every cell is drawn mid-ramp"
    else -> ""
}

private fun wfcHeaderHtml(solver: WfcSolver, cells: Int, resolved: Int, contradictions: Int, untouched: Int): String {
    val statusColor = when (solver.status) {
        WfcStatus.Solved -> "#7ec96f"
        WfcStatus.Contradiction -> "#${"%06x".format(WFC_CONTRADICTION_COLOR)}"
        else -> "#c8cee0"
    }
    val pct = if (cells == 0) 0.0 else 100.0 * resolved / cells
    val obs = solver.lastObservation
    val parts = ArrayList<String>()
    parts += "WfcSolver ${solver.width}&times;${solver.height} &middot; ${solver.rules.tileCount} tiles"
    parts += "<b style=\"color:$statusColor\">${solver.status}</b>"
    if (cells > 0) parts += "$resolved/$cells cells decided (${"%.1f".format(pct)}%)"
    if (untouched > 0) parts += "$untouched untouched"
    if (contradictions > 0) {
        parts += "<b style=\"color:#${"%06x".format(WFC_CONTRADICTION_COLOR)}\">" +
            "$contradictions contradicted</b>"
    }
    if (obs != null) parts += "last observed (${obs.x}, ${obs.y}) &rarr; tile ${obs.tile}"
    return "<div style=\"color:#c8cee0\">${parts.joinToString(" &middot; ")}</div>"
}

private fun wfcPanelHtml(img: Img, title: String, legend: String?, caption: String): String {
    val longest = max(img.width, img.height)
    val scale =
        if (longest > WFC_PANEL_MAX_SIDE) WFC_PANEL_MAX_SIDE.toDouble() / longest
        else max(1, WFC_PANEL_MAX_SIDE / longest).toDouble()
    val dw = max(1, (img.width * scale).roundToInt())
    val dh = max(1, (img.height * scale).roundToInt())
    val b64 = Base64.getEncoder().encodeToString(img.toPngBytes())
    return """
        <div style="width:${max(dw, 160)}px">
          <div style="color:#c8cee0;margin-bottom:2px">$title</div>
          <img src="data:image/png;base64,$b64" width="$dw" height="$dh"
               style="image-rendering:pixelated;border:1px solid #3a3f4b;border-radius:3px;display:block"/>
          ${legend ?: ""}
          <div style="$WFC_NOTE_STYLE">$caption</div>
        </div>
    """.trimIndent()
}

/** A gradient swatch of colormap [name] with its two endpoints labelled, so the scale is readable. */
private fun rampLegendHtml(name: String, loLabel: String, hiLabel: String): String {
    val stops = (0..8).joinToString(", ") { i ->
        val c = Colormaps.sample(name, i / 8.0)
        val r = (c.r * 255).roundToInt().coerceIn(0, 255)
        val g = (c.g * 255).roundToInt().coerceIn(0, 255)
        val b = (c.b * 255).roundToInt().coerceIn(0, 255)
        "rgb($r,$g,$b) ${i * 100 / 8}%"
    }
    return """
        <div style="margin-top:3px">
          <div style="height:6px;border-radius:2px;background:linear-gradient(to right, $stops)"></div>
          <div style="display:flex;justify-content:space-between;color:#8a90a0;font-size:10px">
            <span>$loLabel</span><span>$hiLabel</span>
          </div>
        </div>
    """.trimIndent()
}

// ==============================================================================================
// The rules, inline — WfcRules' own ToHTML
// ==============================================================================================

/*
 * The solver is almost never the thing that is wrong; the rules are. So they draw themselves too,
 * and the view is built around the three ways a rule set fails rather than around its contents:
 *
 *   1. an **empty mask** — a `(direction, tile)` pair with no legal neighbour at all, which is a
 *      guaranteed contradiction the moment that tile is placed anywhere but an edge;
 *   2. an **asymmetry** — `allows(d, a, b)` without `allows(opposite(d), b, a)`, which creates arcs
 *      propagation structurally cannot check, so the solver can emit a pair its own rules forbid;
 *   3. a **zero weight** — a tile that exists in the model and can never be chosen, which
 *      `fromSampleGrid` hands out silently to any tile the sample never contained.
 *
 * All three are marked in the picture and stated in the header, because all three are invisible in
 * `toString()` and all three cost an afternoon.
 */

/** Packed `0xRRGGBB` for an allowed `(a, b)` pair in [wfcRulesHtml] — viridis teal. */
const val WFC_ALLOWED_COLOR: Int = 0x21908C

/** Packed `0xRRGGBB` for a disallowed pair — the same near-black as an undecided cell. */
const val WFC_DISALLOWED_COLOR: Int = WFC_UNDECIDED_COLOR

/**
 * Packed `0xRRGGBB` for a pair that is allowed but was never *observed* — amber.
 *
 * A pair the widening fallback invented is legal as far as the solver is concerned and is not
 * evidence of anything as far as the sample is concerned, and those are different enough to be
 * different colours. Amber because it has to be outside both of the other two categories at once:
 * [WFC_ALLOWED_COLOR] teal, which it would otherwise be indistinguishable from, and
 * [WFC_CONTRADICTION_COLOR] magenta, which means the opposite thing (a mask with *nothing* in it).
 * Before this existed, widening turned an alarming all-magenta row into a reassuring all-teal one
 * and the picture said the problem had been fixed rather than papered over.
 */
const val WFC_WIDENED_COLOR: Int = 0xE0A33A

/** How many direction matrices [wfcRulesHtml] draws before it stops and says how many it skipped. */
private const val WFC_MAX_RULE_PANELS = 8

/**
 * The adjacency matrix for one [direction] as an [Img], one pixel per `(tile, neighbour)` pair:
 * row `a` is "what may sit at [direction] from tile `a`", column `b` is the candidate.
 *
 * [WFC_ALLOWED_COLOR] where the pair is allowed, [WFC_DISALLOWED_COLOR] where it is not, and the
 * whole row in [WFC_CONTRADICTION_COLOR] when tile `a` has **no** legal neighbour in this direction
 * — an empty mask, which is a contradiction waiting for that tile to be placed rather than a merely
 * strict rule.
 *
 * A pair that is allowed only because the widening fallback invented it is [WFC_WIDENED_COLOR]
 * amber rather than teal — see [WfcRules.observedAllows]. That is the difference between a rule the
 * sample taught and a rule the fallback assumed, and on anything but the smallest tile alphabet the
 * second can outnumber the first several times over.
 */
fun WfcRules.adjacencyImg(direction: Int): Img {
    require(direction in 0 until neighborhood.directionCount) {
        "direction $direction outside 0 until ${neighborhood.directionCount}"
    }
    val n = tileCount
    val out = FloatArray(n * n * 3)
    for (a in 0 until n) {
        val empty = allowedCount(direction, a) == 0
        for (b in 0 until n) {
            val packed = when {
                empty -> WFC_CONTRADICTION_COLOR
                !allows(direction, a, b) -> WFC_DISALLOWED_COLOR
                observedAllows(direction, a, b) -> WFC_ALLOWED_COLOR
                else -> WFC_WIDENED_COLOR
            }
            writePacked(out, (a * n + b) * 3, packed)
        }
    }
    return Img(n, n, 3, out)
}

/**
 * The tile weights as a one-row [Img], viridis over a fixed `0 ..` the largest weight.
 *
 * A weight of **zero** is drawn [WFC_CONTRADICTION_COLOR] rather than at the bottom of the ramp,
 * because it is categorically different from "rare": a zero-weight tile can never be chosen by a
 * collapse, so it is a tile that exists in the model and cannot appear in the output.
 * [WfcRules.fromSampleGrid] hands one out to any tile ID the sample never contained.
 */
fun WfcRules.weightsImg(colormap: String = "viridis"): WfcHeatmap {
    val values = FloatArray(tileCount) { if (weights[it] <= 0.0) Float.NaN else weights[it].toFloat() }
    val hi = weights.maxOrNull()?.toFloat()?.takeIf { it > 0f }
    return heatmapOf(values, tileCount, 1, colormap, 0f, hi, WFC_CONTRADICTION_COLOR)
}

/**
 * The inline view of a rule set — the body of [WfcRules]' `ToHTML`, so evaluating one in a box shows
 * the model rather than three nested arrays of longs.
 *
 * One [adjacencyImg] per direction (at most [WFC_MAX_RULE_PANELS], since a `chebyshev(3)`
 * neighborhood has 48 and nobody reads 48 matrices), then the [weightsImg] strip. The header states
 * the three things that silently break a solve: unreachable-empty masks, asymmetry, and zero weights.
 *
 * Never throws; a malformed rule set degrades to the header line.
 */
fun wfcRulesHtml(rules: WfcRules): String {
    val n = rules.tileCount
    val directions = rules.neighborhood.directionCount
    val shown = min(directions, WFC_MAX_RULE_PANELS)

    var emptyMasks = 0
    var allowedPairs = 0
    for (d in 0 until directions) for (a in 0 until n) {
        val c = rules.allowedCount(d, a)
        if (c == 0) emptyMasks++
        allowedPairs += c
    }
    val zeroWeights = rules.weights.count { it <= 0.0 }
    val asymmetry = try {
        rules.findAsymmetry()
    } catch (t: Throwable) {
        "check failed: $t"
    }
    val density = if (n == 0 || directions == 0) 0.0
    else 100.0 * allowedPairs / (n.toDouble() * n * directions)

    val parts = ArrayList<String>()
    parts += "WfcRules $n tiles &middot; $directions direction${wfcS(directions)}"
    parts += "${"%.1f".format(density)}% of pairs allowed"
    parts += if (asymmetry == null) "<b style=\"color:#7ec96f\">symmetric</b>"
    else "<b style=\"color:#${"%06x".format(WFC_CONTRADICTION_COLOR)}\">asymmetric: $asymmetry</b>"
    if (emptyMasks > 0) {
        parts += "<b style=\"color:#${"%06x".format(WFC_CONTRADICTION_COLOR)}\">$emptyMasks empty mask" +
            "${wfcS(emptyMasks)}</b> (a tile with no legal neighbour there)"
    }
    if (zeroWeights > 0) {
        parts += "<b style=\"color:#${"%06x".format(WFC_CONTRADICTION_COLOR)}\">$zeroWeights tile" +
            "${wfcS(zeroWeights)} of weight 0</b> (can never be chosen)"
    }
    // The widening fallback is the one of the three that leaves no trace in the picture unless it
    // is named: after it fires, the mask it filled looks like any other permissive rule.
    val widenedMasks = rules.widenedMaskCount
    if (widenedMasks > 0) {
        val (observedPairs, inventedPairs) = rules.observedAndInventedPairCounts()
        val share = if (observedPairs + inventedPairs == 0) 0
        else 100 * inventedPairs / (observedPairs + inventedPairs)
        parts += "<b style=\"color:#${"%06x".format(WFC_WIDENED_COLOR)}\">$widenedMasks widened mask" +
            "${wfcS(widenedMasks)}</b> &rarr; $observedPairs observed + $inventedPairs invented pair" +
            "${wfcS(inventedPairs)} ($share% of the table)"
    }
    val header = "<div style=\"color:#c8cee0\">${parts.joinToString(" &middot; ")}</div>"

    val panels = try {
        val matrices = (0 until shown).map { d ->
            val offset = rules.neighborhood.offset(d)
            val hasEmptyRow = (0 until n).any { rules.allowedCount(d, it) == 0 }
            val hasInvented = (0 until n).any { a ->
                (0 until n).any { b -> rules.allows(d, a, b) && !rules.observedAllows(d, a, b) }
            }
            val emptyNote = if (!hasEmptyRow) "" else
                " &middot; a fully <b style=\"color:#${"%06x".format(WFC_CONTRADICTION_COLOR)}\">magenta</b>" +
                    " row is an empty mask"
            val widenedNote = if (!hasInvented) "" else
                " &middot; <b style=\"color:#${"%06x".format(WFC_WIDENED_COLOR)}\">amber</b> = allowed but never" +
                    " observed, invented by the widening fallback"
            wfcPanelHtml(
                rules.adjacencyImg(d),
                "direction $d (${offset.dx}, ${offset.dy})",
                null,
                "row = tile, column = the neighbour that may sit at (${offset.dx}, ${offset.dy}) from it &middot; " +
                    "<b style=\"color:#${"%06x".format(WFC_ALLOWED_COLOR)}\">teal</b> = allowed, dark = not" +
                    emptyNote + widenedNote
            )
        }
        val w = rules.weightsImg()
        matrices + wfcPanelHtml(
            w.img,
            "weights",
            rampLegendHtml(w.colormap, wfcNum(w.lo), wfcNum(w.hi)),
            "one pixel per tile, in tile-ID order &middot; colour = relative frequency, ${w.colormap} " +
                "${wfcNum(w.lo)} &hellip; ${wfcNum(w.hi)}" + wfcFlatNote(w) +
                if (w.nonFiniteCount > 0) {
                    " &middot; <b style=\"color:#${"%06x".format(WFC_CONTRADICTION_COLOR)}\">magenta</b> = weight 0," +
                        " off the scale (${w.nonFiniteCount} tile${wfcS(w.nonFiniteCount)} that can never be chosen)"
                } else ""
        )
    } catch (t: Throwable) {
        listOf("<div style=\"$WFC_NOTE_STYLE\">view unavailable: $t</div>")
    }

    val skipped = if (directions > shown) {
        "<div style=\"$WFC_NOTE_STYLE\">${directions - shown} further direction" +
            "${wfcS(directions - shown)} not drawn</div>"
    } else ""

    return """
        <div style="font-family:monospace;font-size:11px;line-height:1.4">
          $header
        ${wfcPanelGridHtml(panels)}
          $skipped
        </div>
    """.trimIndent()
}
