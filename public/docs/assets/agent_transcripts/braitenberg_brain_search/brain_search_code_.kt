val task = VehicleBrainTask.orbit(radius = 20.0)
val seeds = [1L, 2L, 3L, 4L, 5L, 6L]
val narrow = compareVehicleSearch(task, hidden = 4, budget = 300, seeds = seeds)
val wide =
    compareVehicleSearch(task, hidden = 4, budget = 300, seeds = seeds, mu = 12, lambda = 24, immigrantRate = 0.25)
println(narrow.table() + narrow.perSeed())
println(wide.table())

val range = 0.0..1.0
fun trails(name: String, run: SearchRun<DoubleArray>): Pair<String, Img> {
    val world = task.world(run.best!!.genome, hidden = 4, seed = 7L, vehicles = 12)
    world.stepTimes(1200)
    return (name + " " + run.bestFitness.f2) to world.trails(300)
}
showImg(
    montage(
        [
            "mu 6, lambda 12" to narrow.plot(yRange = range),
            "mu 12 + immigrants" to wide.plot(yRange = range),
            trails("median random", wide.medianRun(VehicleSearchDriver.RANDOM)),
            trails("median mu 6", narrow.medianRun(VehicleSearchDriver.EVOLVE)),
            trails("median mu 12+imm", wide.medianRun(VehicleSearchDriver.EVOLVE)),
        ],
        columns = 3,
        cellSide = 300,
    ),
)
