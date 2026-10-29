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
println(
    "the glider the float stepper carried moved by " +
        translationBetween(LifePatterns.glider.centeredOn(32, 32), field.toIntGrid(0.5f)),
)

// Control: move the growth window by one neighbour count and nothing else.
println(
    "CONTROL, growth window at B4/S234: difference " +
        fieldMaxAbsDiff(
            LifePatterns.glider.centeredOn(32, 32).toImg(2).leniaTimes(leniaFromLifeRule(lifeRule("B4/S234")), 20),
            grid.toImg(2),
        ),
)

// The continuous regime: Orbium, from Bert Chan's catalogue: its cells shuffled, then only mu moved.
val orbium = LeniaPresets.orbium()
for (seed in 1L..6L) {
    println(
        "shuffled cells, seed " + seed + ": " + leniaRun(orbium.shuffled(seed).seed(64, 64), orbium.rule, steps = 300),
    )
}
for (mu in [0.11, 0.12, 0.13, 0.16, 0.17, 0.18]) {
    val rule = leniaRuleFromCatalogue(radius = 13, t = 10.0, mu = mu, sigma = 0.015)
    println("mu = " + mu + ": " + leniaRun(orbium.seed(64, 64), rule, steps = 300))
}

val moments = [0, 5, 10, 20, 250]
val panels =
    [0.12, 0.15, 0.18].flatMap { mu ->
        val rule = leniaRuleFromCatalogue(radius = 13, t = 10.0, mu = mu, sigma = 0.015)
        var current = orbium.seed(64, 64)
        var done = 0
        moments.map { step ->
            current = current.leniaTimes(rule, step - done)
            done = step
            (if (step == 0) "mu " + mu else "step " + step) to current.colormap("inferno", 0f, 1f).upscaleNearest(3)
        }
    }
showImg(
    montage(panels, columns = 5, cellSide = 192),
    caption = "rows: mu = 0.12, 0.15, 0.18; columns: steps 0, 5, 10, 20 and 250",
)
