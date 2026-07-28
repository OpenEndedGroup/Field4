// Section 5b: WfcSolver implements ToHTML, so evaluating a solver in a box draws its state
// instead of a property tree. `.h` is that same fragment, which is how one box shows two
// solvers at once. Nothing here arranges a view: no colormap call, no montage, no channel
// plumbing beyond display().
fun bump(u: Double, v: Double, cx: Double, cy: Double, r: Double): Double {
    val dx = u - cx
    val dy = v - cy
    return exp(-(dx * dx + dy * dy) / (2.0 * r * r))
}
val palette = listOf(
    Triple(0.08f, 0.20f, 0.31f),
    Triple(0.18f, 0.48f, 0.66f),
    Triple(0.85f, 0.78f, 0.55f),
    Triple(0.31f, 0.55f, 0.24f),
    Triple(0.12f, 0.29f, 0.14f)
)
val bands = IntArray(26 * 18)
for (cy in 0 until 18) for (cx in 0 until 26) {
    val u = cx / 25.0
    val v = cy / 17.0
    val e = bump(u, v, 0.30, 0.40, 0.25) +
        0.90 * bump(u, v, 0.70, 0.62, 0.21) +
        0.55 * bump(u, v, 0.82, 0.22, 0.13) -
        0.62 * bump(u, v, 0.34, 0.44, 0.085)
    bands[cx + cy * 26] = if (e < 0.16) 0 else if (e < 0.32) 1 else if (e < 0.44) 2 else if (e < 0.74) 3 else 4
}
val source = Img.generateRGB(26, 18) { x, y -> palette[bands[x + y * 26]] }
val tiles = source.toBufferedImage().sliceIntoUniqueTiles(1, 1)
val rules = tiles.toRules()
val tileOfBand = IntArray(palette.size)
for (i in bands.indices) tileOfBand[bands[i]] = tiles.sampleIds[i]
rules.weights[tileOfBand[0]] *= 2.0
rules.weights[tileOfBand[1]] *= 1.5
rules.weights[tileOfBand[2]] *= 10.0

// Stop halfway and look at it. Three panels: possibilities left (viridis, fixed
// 1..tileCount), entropy in nats (magma, fixed 0..fullSetEntropy), and the tiles decided
// so far (a stable hue per id). Every caption names its own colour scale.
val solver = WfcSolver(34, 24, rules, seed = 5L)
while (solver.resolvedFraction() < 0.45 && solver.step() == WfcStatus.Running) { }
display("a. mid-collapse", solver.h)

// The same view of a failure. Section 7's over-constrained chain: nothing may sit east of
// tile 2, so a 6x6 grid cannot be filled and propagation empties a cell. That cell is
// magenta in the count panel (0 possibilities is off a 1..N scale, not at its decided end)
// and cyan in the entropy panel (an empty set has no entropy).
val strict = wfcRules(tileCount = 3) {
    allow(WFC_EAST, 0, 1)
    allow(WFC_EAST, 1, 2)
    allow(WFC_SOUTH, 0, 1)
    allow(WFC_SOUTH, 1, 2)
}
val stuck = WfcSolver(6, 6, strict, seed = 1L)
stuck.run()
display("b. contradicted", stuck.h)

// The numbers the panels are drawn from, so the picture can be checked against them.
println(solver.toString())
println("  entropy range drawn: 0 .. " + "%.3f".format(rules.fullSetEntropy()) + " nats, " +
    solver.entropyHeatmap().nonFiniteCount + " cell(s) undefined")
println(stuck.toString())
println("  cells with no possibilities left: " + stuck.possibilityCountGrid().count { it == 0 })
