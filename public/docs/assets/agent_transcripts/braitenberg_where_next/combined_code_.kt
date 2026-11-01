val lights = [Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0)]
val base = VehicleParams(neuron = NeuronParams(bias = -1.0))

fun arm(name: String, adaptation: Double, learning: Double): Pair<String, Img> {
    val p = base.copy(
        neuron = base.neuron.copy(adaptationRate = adaptation),
        plasticity = PlasticityParams(learningRate = learning),
    )
    val world = VehicleWorld.random(80, lights, seed = 5L)
    val canvas = VehicleCanvas(world, scale = 1.2, fade = 0.998f)
    world.stepTimes(p, 15000)
    val visits = LightVisits(world)
    var light = 0.0
    repeat(5000) {
        world.step(p)
        visits.observe(world)
        canvas.record()
        light += world.meanLight() / 5000
    }
    println(name + ": light " + light.f3 + ", lights visited " + visits.mean().f2)
    return (name + ": " + light.f2) to canvas.image()
}

showImg(
    montage(
        [
            arm("neither", 0.0, 0.0),
            arm("homeostasis", 0.002, 0.0),
            arm("learning", 0.0, 3.0),
            arm("both", 0.002, 3.0),
        ],
        columns = 4,
        cellSide = 240,
    ),
)
