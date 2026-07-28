// Section 5: step() is public and does exactly one WFC iteration, so the collapse is drawable.
// waveToImg averages each cell's remaining possibilities -- undecided cells are a blur of what
// they might become. possibilityCountImg is the remaining option count as a scalar field.
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

// Section 2's boost, so the collapse has all five bands in it and is worth watching.
val tileOfBand = IntArray(palette.size)
for (i in bands.indices) tileOfBand[bands[i]] = tiles.sampleIds[i]
rules.weights[tileOfBand[0]] *= 2.0
rules.weights[tileOfBand[1]] *= 1.5
rules.weights[tileOfBand[2]] *= 10.0

val solver = WfcSolver(40, 28, rules, seed = 3L)

val waves = mutableListOf<Pair<String, Img>>()
val entropy = mutableListOf<Pair<String, Img>>()
fun sample() {
    val pct = (solver.resolvedFraction() * 100).toInt()
    waves.add(pct.toString() + "% decided" to tiles.waveToImg(solver).upscaleNearest(6))
    entropy.add(pct.toString() + "% decided" to
        solver.possibilityCountImg().colormap("magma", lo = 0f, hi = rules.tileCount.toFloat()).upscaleNearest(6))
}

sample()
var steps = 0
while (solver.step() == WfcStatus.Running) {
    steps++
    if (steps % 200 == 0) sample()
}
sample()

// A solved cell has exactly one possibility, so the last wave frame IS the finished output.
println(steps.toString() + " steps, " + waves.size + " sampled frames, ended " + solver.status)
println("magma is mapped with an explicit lo/hi so the colours mean the same thing in every frame")
display("main", vstack(
    montage(waves, columns = 4, cellSide = 260),
    montage(entropy, columns = 4, cellSide = 260),
    gap = 14
).toPngBytes())
