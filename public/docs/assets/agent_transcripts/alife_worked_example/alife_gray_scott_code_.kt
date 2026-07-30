// Gray-Scott reaction-diffusion. Two coupled fields, two parameters, and the whole zoo of
// morphologies -- spots, worms, mazes, holes -- is a function of those two numbers.
//
// The point of `morphology()` is that a regime claim should be a claim about numbers. Spots and a
// labyrinth can have identical coverage, identical mean and identical variance; what separates
// them is the SHAPE of their features (isoperimetric ratio P^2/4piA, ~1 for a disc) and whether
// one connected structure dominates.
val side = 128
val steps = 6000
val runs = listOf(
    "spots 0.030/0.062" to GRAY_SCOTT_SPOTS,
    "worms 0.037/0.060" to GRAY_SCOTT_WORMS,
    "maze 0.029/0.057" to GRAY_SCOTT_MAZE,
    "holes 0.039/0.058" to GRAY_SCOTT_HOLES
).map { (label, p) -> label to grayScottSeed(side, side, patches = 6, patchSize = 14, seed = 11L).stepTimes(p, steps) }
for ((label, end) in runs) println(label + " -> " + end.v.morphology())

// Two controls. v = 0 is a fixed point of the equations, so an unseeded field never patterns;
// and equal diffusion coefficients destroy the Turing instability entirely, which is Turing's
// own argument restated as an experiment.
val unseeded = grayScottSeed(side, side, patches = 0).stepTimes(GRAY_SCOTT_WORMS, 500)
println("CONTROL no seed: max autocatalyst after 500 steps = " + unseeded.v.max())
val equalD = grayScottSeed(side, side, patches = 6, patchSize = 14, seed = 11L)
    .stepTimes(GrayScottParams(feed = 0.037, kill = 0.060, du = 0.08, dv = 0.08), steps)
println("CONTROL Du == Dv -> " + equalD.v.morphology())

showImg(
    montage(
        (runs + listOf("CONTROL no seed" to unseeded, "CONTROL Du == Dv" to equalD))
            .map { (label, s) -> label to s.v.colormap("magma", 0f, 0.5f).upscaleNearest(2) },
        columns = 3, cellSide = 260
    )
)
