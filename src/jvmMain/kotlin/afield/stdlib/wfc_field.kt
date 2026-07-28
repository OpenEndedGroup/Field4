@file:afield.CompletionCategory("Data")

package afield.stdlib

/**
 * The bridge from Wave Function Collapse into the things Field already displays.
 *
 * `wfc.kt` is deliberately dependency-free — it knows about integer tile IDs, `BufferedImage` and
 * `FloatArray`, and nothing else. That keeps the solver honest, but it means a box running WFC has
 * no direct way to *look* at what it is doing. These extensions supply the missing hop into
 * [Img], and therefore into `Box.showImg`, `showImgGrid`, `montage`, `Img.colormap` and the
 * `ToHTML` thumbnail a box shows when you evaluate a value.
 *
 * Two kinds of view:
 *
 *  1. **The result.** [PngTileSet.toImg], [PngOverlappingTileSet.toImg] and their `Float`
 *     counterparts turn a solved tile grid into an image. Pair them with
 *     [Img.upscaleNearest] — WFC output is pixels-as-pixels, and any filtering upscale destroys
 *     exactly the local structure you were trying to show.
 *  2. **The process.** [PngTileSet.waveToImg] renders a *partially collapsed* solver by blending
 *     each cell's still-possible tiles, and [WfcSolver.possibilityCountImg] exposes the
 *     possibility count per cell as a scalar field to colour-map. Sample either one inside a
 *     `while (solver.step() == WfcStatus.Running)` loop and you have the collapse itself as a
 *     sequence of frames — which is by far the most informative thing you can look at when rules
 *     are not behaving.
 *
 * Because a solved cell has exactly one possibility, `waveToImg` on a [WfcStatus.Solved] solver is
 * the finished output: the animation's last frame and the result are the same picture, by
 * construction rather than by coincidence.
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

/** How many cells have collapsed to exactly one possibility. */
fun WfcSolver.resolvedCellCount(): Int {
    var resolved = 0
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            if (possibilityCountAt(x, y) == 1) resolved++
            x++
        }
        y++
    }
    return resolved
}

/** [resolvedCellCount] as a `0..1` fraction — a progress read-out for a `step()` loop. */
fun WfcSolver.resolvedFraction(): Double =
    resolvedCellCount().toDouble() / (width.toDouble() * height.toDouble())
