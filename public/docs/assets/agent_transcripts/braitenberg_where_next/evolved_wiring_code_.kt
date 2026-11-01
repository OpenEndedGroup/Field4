val lights = [Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0)]
val ops = VehicleWiringGenome()
val objective = vehicleForageObjective(lights)

val control = PopulationSearch.random(ops, objective, 300, seed = 13L, label = "random")
val evolved =
    PopulationSearch.evolve(ops, objective, 300, seed = 13L, mu = 6, lambda = 12, crossoverRate = 0.3, label = "evolve")
println(control.summary())
println(evolved.summary())
println("lover " + objective.evaluate(VehicleWorld.LOVER).fitness.f3)
val best = evolved.best!!.genome
println("evolved wiring " + best.joinToString { it.f2 })

fun trails(name: String, wiring: DoubleArray): Pair<String, Img> {
    val world = VehicleWorld.random(30, lights, wiring = wiring, seed = 1L)
    val canvas = VehicleCanvas(world, scale = 1.2)
    repeat(3000) {
        world.step(VehicleParams(neuron = NeuronParams(bias = -1.0)))
        canvas.record()
    }
    return (name + ": " + world.meanLight().f2) to canvas.image()
}
showImg(montage([trails("lover", VehicleWorld.LOVER), trails("evolved", best)], columns = 2, cellSide = 300))
