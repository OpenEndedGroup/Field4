val task = VehicleBrainTask.orbit(radius = 20.0)
val seeds = [1L, 2L, 3L, 4L, 5L, 6L]
val narrow = compareVehicleSearch(task, hidden = 4, budget = 300, seeds = seeds)
val wide =
    compareVehicleSearch(task, hidden = 4, budget = 300, seeds = seeds, mu = 12, lambda = 24, immigrantRate = 0.25)
println(narrow.table() + narrow.perSeed())
println(wide.table())

fun trails(name: String, genes: DoubleArray): Pair<String, Img> {
    val world = task.world(genes, hidden = 4, seed = 7L, vehicles = 12)
    world.stepTimes(1000)
    return name to world.trails(600)
}
showImg(
    montage(
        [
            "mu 6, lambda 12" to narrow.plot(),
            "mu 12 + immigrants" to wide.plot(),
            trails("best random", wide.champion(VehicleSearchDriver.RANDOM)),
            trails("best evolved", wide.champion(VehicleSearchDriver.EVOLVE)),
        ],
        columns = 2,
        cellSide = 300,
    ),
)
