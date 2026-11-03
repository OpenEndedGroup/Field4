val task = VehicleBrainTask.track()
for (h in [0, 16]) {
    val c = compareVehicleSearch(
        task,
        hidden = h,
        budget = 300,
        seeds = [1L, 2L, 3L, 4L],
        mu = 12,
        lambda = 24,
        immigrantRate = 0.25,
    )
    println(c.table() + c.perSeed())
}
