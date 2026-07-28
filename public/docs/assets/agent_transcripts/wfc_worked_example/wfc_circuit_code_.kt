// Section 2b: the same simple-tiled solver, but the tiles carry art whose EDGES must line up.
// Rules come from edge sockets rather than from a sample: b may sit east of a exactly when
// a's east socket matches b's west socket. Every trace in the output therefore connects.
val sockets = listOf(
    intArrayOf(0, 0, 0, 0),   // blank
    intArrayOf(0, 1, 0, 1),   // horizontal
    intArrayOf(1, 0, 1, 0),   // vertical
    intArrayOf(1, 1, 0, 0),   // elbow N-E
    intArrayOf(0, 1, 1, 0),   // elbow E-S
    intArrayOf(0, 0, 1, 1),   // elbow S-W
    intArrayOf(1, 0, 0, 1),   // elbow W-N
    intArrayOf(1, 1, 1, 1)    // cross
)

val rules = wfcRules(tileCount = sockets.size) {
    weights(6.0, 2.0, 2.0, 1.0, 1.0, 1.0, 1.0, 0.35)
    for (a in sockets.indices) for (b in sockets.indices) {
        if (sockets[a][WFC_EAST] == sockets[b][WFC_WEST]) allow(WFC_EAST, a, b)
        if (sockets[a][WFC_SOUTH] == sockets[b][WFC_NORTH]) allow(WFC_SOUTH, a, b)
    }
}

// A top-level fun, not one nested inside the Array(...) lambda below: a local fun declared
// inside a lambda in a Field box trips a K2 codegen assertion ("has no callableId").
val side = 11
val c = side / 2
val bg = 0xFF10241C.toInt()
val trace = 0xFF64E0A0.toInt()
val pad = 0xFFB8FFDA.toInt()

fun traceArt(s: IntArray): IntArray {
    val px = IntArray(side * side) { bg }
    if (s[WFC_NORTH] == 1) for (y in 0..c) { px[c + y * side] = trace; px[(c - 1) + y * side] = trace }
    if (s[WFC_SOUTH] == 1) for (y in c until side) { px[c + y * side] = trace; px[(c - 1) + y * side] = trace }
    if (s[WFC_WEST] == 1) for (x in 0..c) { px[x + c * side] = trace; px[x + (c - 1) * side] = trace }
    if (s[WFC_EAST] == 1) for (x in c until side) { px[x + c * side] = trace; px[x + (c - 1) * side] = trace }
    if (s.any { it == 1 }) for (y in (c - 1)..c) for (x in (c - 1)..c) px[x + y * side] = pad
    return px
}

// A PngTileSet can be assembled from hand-drawn art; sampleIds is just the atlas row,
// because nothing is being learned from it.
val tiles = PngTileSet(side, side, Array(sockets.size) { traceArt(sockets[it]) },
    IntArray(sockets.size) { it }, sockets.size, 1)

val solver = WfcSolver(34, 22, rules, seed = 20260728L)
val outcome = solver.solveWithRetries()
val grid = solver.resolvedTileGridOrNull()!!

println(outcome.attempts.toString() + " attempt(s); " + grid.count { it == 7 } + " crossings, " +
    grid.count { it == 0 } + " blank of " + grid.size + " cells")
display("tiles", tiles.sampleToImg().upscaleNearest(6).toPngBytes())
display("main", tiles.toImg(grid, 34, 22).upscaleNearest(2).toPngBytes())
