/*
Reynolds' boids on a torus. A flock is not "dots that move together"; it is a population whose
alignment order parameter is far above what independent headings would give, AND whose
nearest-neighbour distribution has a floor. Both are measured against the same initial state run
with one weight zeroed -- so the difference is attributable to that weight and nothing else.
*/
val n = 400
val start = BoidFlock.random(n, 260.0, 260.0, speed = 1.4, seed = 4L)
val params = BoidParams()
println("random-heading baseline for " + n + " agents: " + randomPolarizationBaseline(n).f3)
println("initial polarization: " + start.order().f3)

val flock = start.copy()
flock.stepTimes(params, 900)
val noAlign = start.copy()
noAlign.stepTimes(params.copy(alignmentWeight = 0.0), 900)
val noSep = start.copy()
noSep.stepTimes(params.copy(separationWeight = 0.0), 900)

println(
    "flock polarization " + flock.order().f3 + ", CONTROL with no alignment " + noAlign.order().f3,
)
println(
    "5th-percentile nearest neighbour: flock " +
        quantileOf(flock.neighborDistances(), 0.05).f2 +
        ", CONTROL with no separation " +
        quantileOf(noSep.neighborDistances(), 0.05).f2,
)
println("mean speed " + flock.meanSpeed().f2 + " of a " + params.maxSpeed + " cap")

showImg(
    montage(
        [
            "gen 0: order " + start.order().f2 to start.toImg(1.4, tail = 5.0),
            "900 steps: " + flock.order().f2 to flock.toImg(1.4, tail = 5.0),
            "no alignment: " + noAlign.order().f2 to noAlign.toImg(1.4, tail = 5.0),
            "no separation" to noSep.toImg(1.4, tail = 5.0),
        ],
        columns = 2,
        cellSide = 300,
    ),
)
