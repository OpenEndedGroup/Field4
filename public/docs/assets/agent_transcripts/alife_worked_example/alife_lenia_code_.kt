// Lenia is a CONTINUOUS cellular automaton: one field in 0..1, one convolution kernel, one growth
// mapping, and A <- clamp(A + dt * G(K * A)).
//
// The claim worth making about an implementation of it is a cross-check. With a uniform Moore ring
// kernel, dt = 1 and a three-valued step growth mapping, Lenia reduces EXACTLY to an
// outer-totalistic B/S rule -- so this float convolution and the integer Life stepper in
// `alife_ca.kt` must agree bit for bit. `leniaFromLifeRule` builds that limit.
val discrete = leniaFromLifeRule(LIFE)
println("discrete-limit kernel: " + discrete.taps.size + " taps summing to " + discrete.taps.weight.sum())

var grid = LifePatterns.glider.centeredOn(32, 32)
var field = grid.toImg(2)
var worst = 0.0
for (gen in 1..20) {
    grid = grid.step(LIFE)
    field = field.leniaTimes(discrete, 1)
    worst = maxOf(worst, fieldMaxAbsDiff(field, grid.toImg(2)))
}
println("20 generations, float Lenia vs integer Life: worst per-cell difference " + worst)
println("the glider the float stepper carried moved by " +
    translationBetween(LifePatterns.glider.centeredOn(32, 32), field.toIntGrid(0.5f)))

// Control: move the growth window by one neighbour count and nothing else.
println("CONTROL, growth window at B4/S234: difference " +
    fieldMaxAbsDiff(LifePatterns.glider.centeredOn(32, 32).toImg(2).leniaTimes(leniaFromLifeRule(lifeRule("B4/S234")), 20), grid.toImg(2)))

// The continuous regime, reported as a measured phase trichotomy rather than as a named creature:
// this file ships no published Lenia initial pattern, so it claims none.
val runs = listOf(0.06, 0.14).map { mu ->
    val r = LeniaPresets.ring(mu, sigma = 0.017, radius = 8, dt = 0.12)
    mu to leniaRun(leniaSeed(96, 96, r, blobRadii = 1.6, seed = 9L), r, steps = 240)
}
for ((mu, run) in runs) println("mu = " + mu + " -> " + run)
val wide = LeniaPresets.ring(0.60, sigma = 0.60, radius = 8, dt = 0.12)
val sat = leniaRun(leniaSeed(96, 96, wide, blobRadii = 1.6, seed = 9L), wide, steps = 240)
println("a growth bell positive over the whole unit interval -> " + sat)

showImg(
    montage(
        listOf(
            "discrete limit == Life" to field.upscaleNearest(8),
            "ring kernel R=8" to LeniaPresets.ring(0.15, radius = 8).kernelImg().normalized().upscaleNearest(10)
        ) + runs.map { (mu, run) ->
            "mu=" + mu + " " + run.phase.name.lowercase() to run.field.colormap("inferno", 0f, 1f).upscaleNearest(3)
        } + listOf("wide sigma: saturation" to sat.field.colormap("inferno", 0f, 1f).upscaleNearest(3)),
        columns = 3, cellSide = 280
    )
)
