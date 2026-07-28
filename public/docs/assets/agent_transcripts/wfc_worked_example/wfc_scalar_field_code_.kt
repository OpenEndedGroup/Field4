// Section 6: the solver never sees a pixel. sliceIntoUniqueTiles2D and
// sliceIntoOverlappingPatterns2D learn from a row-major FloatArray, so the same machinery
// synthesises numbers -- and numbers in Field are geometry waiting to happen.
fun bump(u: Double, v: Double, cx: Double, cy: Double, r: Double): Double {
    val dx = u - cx
    val dy = v - cy
    return exp(-(dx * dx + dy * dy) / (2.0 * r * r))
}

val sw = 26
val sh = 18
val levels = 6
val source = FloatArray(sw * sh)
for (cy in 0 until sh) for (cx in 0 until sw) {
    val u = cx / (sw - 1.0)
    val v = cy / (sh - 1.0)
    val e = (bump(u, v, 0.32, 0.42, 0.26) +
        0.85 * bump(u, v, 0.70, 0.60, 0.22) +
        0.50 * bump(u, v, 0.80, 0.24, 0.14)).coerceIn(0.0, 1.0)
    // Quantise: sliceIntoUniqueTiles2D dedupes on exact float equality, so a continuous field
    // would give one tile per distinct value instead of six.
    source[cx + cy * sw] = (Math.round(e * (levels - 1)).toDouble() / (levels - 1)).toFloat()
}

val tiles = source.sliceIntoUniqueTiles2D(sw, sh, tileWidth = 1, tileHeight = 1)
val patterns = source.sliceIntoOverlappingPatterns2D(sw, sh, 3, 3, wrapX = false, wrapY = false)

val outW = 40
val outH = 26

var lowestTile = 0
for (id in 0 until tiles.tileCount) {
    if (tiles.tiles[id][0] < tiles.tiles[lowestTile][0]) lowestTile = id
}

// The 1x1 model needs its border pinned or it settles on whichever level nucleates first.
val tiledSolver = WfcSolver(outW, outH, tiles.toRules(), seed = 5L)
tiledSolver.solveWithRetries(maxAttempts = 30) {
    for (x in 0 until outW) { forceTile(x, 0, lowestTile); forceTile(x, outH - 1, lowestTile) }
    for (y in 0 until outH) { forceTile(0, y, lowestTile); forceTile(outW - 1, y, lowestTile) }
}
// `throw`, not `error(...)`: a box has its own `error` in scope and it does not return Nothing.
val tiledGrid: IntArray = tiledSolver.resolvedTileGridOrNull()
    ?: throw IllegalStateException("1x1 model ended " + tiledSolver.status)
val tiledHeights: FloatArray = tiles.render(tiledGrid, outW, outH)

// The 3x3 model gets no pinning: pinning the whole border over-constrains it into a
// contradiction, and its windows already encode what a slope looks like.
val patternSolver = WfcSolver(outW, outH, patterns.toRules(), seed = 5L)
patternSolver.solveWithRetries(maxAttempts = 30)
val patternGrid: IntArray = patternSolver.resolvedTileGridOrNull()
    ?: throw IllegalStateException("3x3 model ended " + patternSolver.status)
val patternHeights: FloatArray = patterns.render(patternGrid, outW, outH)

// Mean absolute difference along rows vs down columns. The 1x1 result is isotropic speckle;
// the 3x3 result is smooth along rows and rough down them, i.e. horizontally banded.
fun roughness(values: FloatArray): String {
    var hs = 0.0
    var hn = 0
    var vs = 0.0
    var vn = 0
    for (y in 0 until outH) for (x in 0 until outW) {
        if (x + 1 < outW) { hs += abs(values[x + y * outW] - values[x + 1 + y * outW]); hn++ }
        if (y + 1 < outH) { vs += abs(values[x + y * outW] - values[x + (y + 1) * outW]); vn++ }
    }
    return "h=" + "%.3f".format(hs / hn) + " v=" + "%.3f".format(vs / vn)
}

println(tiles.tileCount.toString() + " levels, " + patterns.tileCount + " distinct 3x3 value patterns")
println("1x1 tiles:    mean " + "%.2f".format(tiledHeights.average()) + ", roughness " + roughness(tiledHeights))
println("3x3 patterns: mean " + "%.2f".format(patternHeights.average()) + ", roughness " + roughness(patternHeights))

// The payoff: iso-contours of the synthesised field are FLines, not a picture of them.
val smooth = floatsToImg(patternHeights, outW, outH).gaussianBlur(1.2)
val contours = ArrayList<afield.graphics.FLine>()
for (iso in listOf(0.15, 0.35, 0.55, 0.75)) {
    for (line in smooth.isoContourFLines(iso)) {
        line.color = vec(0.30 + iso * 0.65, 0.95 - iso * 0.45, 0.40 + iso * 0.45, 0.95)
        line.thickness = 1.6
        contours.add(line)
    }
}
println(contours.size.toString() + " iso-contour FLines across four levels")

showImgGrid(
    "source 26x18" to floatsToImg(source, sw, sh).colormap("viridis", lo = 0f, hi = 1f).upscaleNearest(20),
    "1x1 tiles, pinned" to floatsToImg(tiledHeights, outW, outH).colormap("viridis", lo = 0f, hi = 1f).upscaleNearest(13),
    "3x3 patterns" to floatsToImg(patternHeights, outW, outH).colormap("viridis", lo = 0f, hi = 1f).upscaleNearest(13),
    columns = 1,
    cellSide = 640
)
lines(contours, channel = "contours", w = 900.0, h = 560.0, fitToSize = true)
