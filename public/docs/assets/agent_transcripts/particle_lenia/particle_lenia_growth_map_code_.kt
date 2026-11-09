// The rotator's kernel (muK 4, sigmaK 0.6, wK 0.022) and repulsion (cRep 0.3) at 100 particles, seed 1,
// with the growth curve's centre muG across and its width sigmaG up: 3000 steps to settle, 4000 measured.
val muGs = [0.3, 0.4, 0.5, 0.6, 0.7, 0.8]
val sigmaGs = [0.06, 0.1, 0.15, 0.2]
val cells =
    leniaMotionMap(muGs, sigmaGs, settle = 3000, window = 4000) { muG, sigmaG ->
        ParticleLenia.random(
            100,
            side = 12.0 * Math.sqrt(0.5),
            seed = 1L,
            params = ParticleLeniaParams(sigmaK = 0.6, cRep = 0.3, muG = muG, sigmaG = sigmaG),
        )
    }
for (c in cells) println("muG %.2f  sigmaG %.2f  %s".format(c.x, c.y, c.window))
println(cells.groupingBy { it.motion }.eachCount())

showImg(
    montage(cells.map { "%.2f, %.2f".format(it.x, it.y) to it.tile(size = 160) }, columns = muGs.size, cellSide = 160),
)
