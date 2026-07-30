// Reynolds' boids on a torus. A flock is not "dots that move together"; it is a population whose
// alignment order parameter is far above what independent headings would give, AND whose
// nearest-neighbour distribution has a floor. Both are measured against the same initial state run
// with one weight zeroed -- so the difference is attributable to that weight and nothing else.
val n = 400
val start = BoidFlock.random(n, 260.0, 260.0, speed = 1.4, seed = 4L)
val params = BoidParams()
println("random-heading baseline for " + n + " agents: " + "%.3f".format(randomPolarizationBaseline(n)))
println("initial polarization: " + "%.3f".format(start.order()))

val flock = start.copy(); flock.stepTimes(params, 900)
val noAlign = start.copy(); noAlign.stepTimes(params.copy(alignmentWeight = 0.0), 900)
val noSep = start.copy(); noSep.stepTimes(params.copy(separationWeight = 0.0), 900)

println("flock polarization " + "%.3f".format(flock.order()) +
    ", CONTROL with no alignment " + "%.3f".format(noAlign.order()))
println("5th-percentile nearest neighbour: flock " + "%.2f".format(quantileOf(flock.neighborDistances(), 0.05)) +
    ", CONTROL with no separation " + "%.2f".format(quantileOf(noSep.neighborDistances(), 0.05)))
println("mean speed " + "%.2f".format(flock.meanSpeed()) + " of a " + params.maxSpeed + " cap")

showImg(
    montage(
        listOf(
            "gen 0: order " + "%.2f".format(start.order()) to start.toImg(1.4, tail = 5.0),
            "900 steps: " + "%.2f".format(flock.order()) to flock.toImg(1.4, tail = 5.0),
            "no alignment: " + "%.2f".format(noAlign.order()) to noAlign.toImg(1.4, tail = 5.0),
            "no separation" to noSep.toImg(1.4, tail = 5.0)
        ),
        columns = 2, cellSide = 300
    )
)
