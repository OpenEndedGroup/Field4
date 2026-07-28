// Section 4: the neighborhood is a modelling decision, not a performance knob.
// Diagonal stripes two cells wide: colour depends on (x + y) mod 4, so along the anti-diagonal
// offset (1, -1) the colour never changes, while east and south change only half the time.
val stripeA = Triple(0.17f, 0.25f, 0.45f)
val stripeB = Triple(0.91f, 0.71f, 0.29f)
val source = Img.generateRGB(24, 16) { x, y -> if ((x + y) % 4 < 2) stripeA else stripeB }
val tiles = source.toBufferedImage().sliceIntoUniqueTiles(1, 1)

fun solveStripes(neighborhood: WfcNeighborhood): Pair<Img, Int> {
    val rules = tiles.toRules(neighborhood = neighborhood)
    val solver = WfcSolver(40, 26, rules, seed = 5L)
    solver.solveWithRetries(maxAttempts = 30)
    val grid = solver.resolvedTileGridOrNull()!!
    var breaks = 0
    for (y in 1 until 26) for (x in 0 until 39) {
        if (grid[x + y * 40] != grid[(x + 1) + (y - 1) * 40]) breaks++
    }
    return tiles.toImg(grid, 40, 26) to breaks
}

val vn = solveStripes(WfcNeighborhood.VonNeumann)
val moore = solveStripes(WfcNeighborhood.Moore)

// von Neumann observes every cardinal pair and so learns nothing; Moore observes the one offset
// along which the colour is constant, and that alone pins the stripes exactly.
println("anti-diagonal breaks of 975 pairs -- von Neumann: " + vn.second + ", Moore: " + moore.second)
showImgGrid(
    "source 24x16" to source.upscaleNearest(12),
    "von Neumann (4)" to vn.first.upscaleNearest(7),
    "Moore (8)" to moore.first.upscaleNearest(7),
    columns = 3,
    cellSide = 340
)
