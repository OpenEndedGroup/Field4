val lights = [Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0)]
val panels = mutableListOf<Pair<String, Img>>()
for (cost in [0.0, 0.2, 0.5, 1.0]) {
    val p = VehicleParams(
        neuron = NeuronParams(bias = -1.0),
        plasticity = PlasticityParams(learningRate = 3.0, speedCost = cost),
    )
    val world = VehicleWorld.random(80, lights, seed = 5L)
    val canvas = VehicleCanvas(world, scale = 1.2)
    world.stepTimes(p, 15000)
    var light = 0.0
    var speed = 0.0
    repeat(2000) {
        world.step(p)
        canvas.record()
        light += world.meanLight() / 2000
        speed += world.meanSpeed() / 2000
    }
    println(
        "speedCost ${cost.f2}: light ${light.f3}, speed ${speed.f3}, summed weight ${(world.w.sum() / world.count).f2}",
    )
    panels += ("cost " + cost.f2 + ": " + speed.f2) to canvas.image()
}
showImg(montage(panels, columns = 4, cellSide = 240))
