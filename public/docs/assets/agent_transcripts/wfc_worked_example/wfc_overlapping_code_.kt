// Section 3: the overlapping model. Every 3x3 window of the source becomes a "tile", and two
// windows may be neighbours only if they agree on their overlap -- so each cell carries shape,
// not just colour. Also the classic "ground" constraint, because WFC has no absolute position.
val SKY = Triple(0.75f, 0.86f, 0.94f)
val SOIL = Triple(0.42f, 0.29f, 0.16f)
val STEM = Triple(0.24f, 0.56f, 0.23f)
val BLOSSOM = Triple(0.88f, 0.34f, 0.42f)

val sw = 26
val sh = 18
val soilTop = 13
val flowers = listOf(3 to 4, 8 to 6, 13 to 3, 18 to 5, 22 to 4)   // column to stem height

val source = Img.generateRGB(sw, sh) { x, y ->
    val flower = flowers.firstOrNull { it.first == x }
    when {
        y >= soilTop -> SOIL
        flower != null && y == soilTop - flower.second - 1 -> BLOSSOM
        flower != null && y >= soilTop - flower.second -> STEM
        else -> SKY
    }
}

// wrapY = false: wrapping vertically would teach it that soil sits above sky, which is the one
// thing this source is trying to say is untrue.
val patterns = source.toBufferedImage().sliceIntoOverlappingPatterns(3, 3, wrapX = true, wrapY = false)
val rules = patterns.toRules()

// Named outW/outH: inside a lambda with its own receiver (the prepare block below) a bare `h`
// resolves to the Box's frame height rather than to a local. Likewise a bare `sky` is taken.
val outW = 60
val outH = 16

// Left alone the solver has no reason to put the soil anywhere: it produces a sky full of
// flowers whose stems run off the bottom edge with nothing to grow out of. Legal, and useless.
val free = WfcSolver(outW, outH, rules, seed = 9L)
free.solveWithRetries(maxAttempts = 30)
val freeOut = patterns.toImg(free.resolvedTileGridOrNull()!!, outW, outH)

// The all-soil and all-sky 3x3 patterns, found by looking for a flat window and testing its blue.
var groundPattern: Int = -1
var skyPattern: Int = -1
for (id in 0 until patterns.tileCount) {
    val px: IntArray = patterns.patterns[id]
    if (!px.all { v -> v == px[0] }) continue
    val blue: Int = px[0] and 0xff
    if (blue < 0x60) groundPattern = id
    if (blue > 0xC0) skyPattern = id
}

val solver = WfcSolver(outW, outH, rules, seed = 4L)
val outcome = solver.solveWithRetries(maxAttempts = 30) {
    for (x in 0 until outW) {
        forceTile(x, outH - 1, groundPattern)
        forceTile(x, 0, skyPattern)
    }
}
val out = patterns.toImg(solver.resolvedTileGridOrNull()!!, outW, outH)

println(patterns.tileCount.toString() + " distinct 3x3 patterns; ground=#" + groundPattern +
    " sky=#" + skyPattern + "; solved in " + outcome.attempts + " attempt(s)")
println("every blossom still sits on a stem: that is a 3x3-local fact, so the model enforces it")
showImgGrid(
    "source 26x18" to source.upscaleNearest(9),
    "no ground constraint" to freeOut.upscaleNearest(9),
    "ground + sky pinned" to out.upscaleNearest(9),
    columns = 1,
    cellSide = 620
)
