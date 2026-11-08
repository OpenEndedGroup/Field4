val pl = ParticleLenia.random(200, side = 12.0, seed = 1L)
for (n in [0, 100, 500, 3000]) {
    pl.stepTimes(n - pl.steps)
    println(pl)
}
showImg(
    montage(
        [
            "particles over -E, step 3000" to pl.render(extent = 24.0, size = 360),
            "U, step 3000" to pl.fieldImg(extent = 24.0, size = 360, which = "U").colormap("magma", 0f, 1f),
        ],
        columns = 2,
        cellSide = 360,
    ),
)
// Control: the same seed with no kernel, so repulsion alone moves the particles.
val control = ParticleLenia.random(200, side = 12.0, seed = 1L, params = ParticleLeniaParams(wK = 0.0)).stepTimes(3000)
println("CONTROL, wK = 0: " + control)
showImg(
    energyPlotImg(pl.energyHistory, 720, 160),
    channel = "energy",
    caption =
        "mean particle energy, steps 0 to 3000: %.3f down to %.3f".format(pl.energyHistory.first(), pl.currentEnergy()),
)
