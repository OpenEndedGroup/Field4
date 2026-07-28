// Section 1: the model, by hand. Three tile IDs and a table; nothing is learned.
// Sand is the only tile allowed to touch both sea and grass, so a coastline is not
// something the solver is told to draw -- it is what is left once a sea-grass edge is illegal.
val SEA = 0
val SAND = 1
val GRASS = 2

// allow(d, a, b) is directional: "b may sit east of a", and therefore "a may sit west of b".
// It does NOT say a may sit east of b, so an undirected "may touch" is both orders.
fun WfcRulesBuilder.mayTouch(a: Int, b: Int) {
    for (d in listOf(WFC_EAST, WFC_SOUTH)) {
        allow(d, a, b)
        allow(d, b, a)
    }
}

val rules = wfcRules(tileCount = 3) {
    weights(3.0, 1.0, 3.0)
    mayTouch(SEA, SEA)
    mayTouch(SEA, SAND)
    mayTouch(SAND, SAND)
    mayTouch(SAND, GRASS)
    mayTouch(GRASS, GRASS)
    // Deliberately absent: mayTouch(SEA, GRASS). That single omission is the whole model.
}

// Pin sea down the left edge and grass down the right, so the interface has to appear.
// reset() clears forced cells, so the pinning belongs in the prepare block where every
// retry re-applies it.
val solver = WfcSolver(width = 22, height = 14, rules = rules, seed = 12L)
val outcome = solver.solveWithRetries {
    for (row in 0 until 14) {
        forceTile(0, row, SEA)
        forceTile(21, row, GRASS)
    }
}
val grid = solver.resolvedTileGridOrNull()!!

val names = listOf("sea", "sand", "grass")
for (a in 0..2) {
    println("may sit east of " + names[a] + ": " +
        (0..2).filter { rules.allows(WFC_EAST, a, it) }.joinToString(", ") { names[it] })
}
println("solved in " + outcome.attempts + " attempt(s); symmetric=" + (rules.findAsymmetry() == null))
println("sea=" + grid.count { it == SEA } + " sand=" + grid.count { it == SAND } +
    " grass=" + grid.count { it == GRASS } + " of " + grid.size)

val palette = listOf(
    Triple(0.07f, 0.20f, 0.33f),
    Triple(0.85f, 0.78f, 0.55f),
    Triple(0.28f, 0.52f, 0.22f)
)
showImgGrid(
    "22x14 cells, seed 12" to Img.generateRGB(22, 14) { x, y -> palette[grid[x + y * 22]] }.upscaleNearest(20),
    columns = 1,
    cellSide = 560
)
