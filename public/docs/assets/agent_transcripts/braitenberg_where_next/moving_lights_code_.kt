val still = [Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0)]
val moving = still.mapIndexed { k, l -> l.copy(vx = 0.1 * cos(k * 1.7), vy = 0.1 * sin(k * 1.7)) }
val p = VehicleParams(neuron = NeuronParams(bias = -1.0))

val trained = VehicleWorld.random(80, still, seed = 5L)
trained.stepTimes(p.copy(plasticity = PlasticityParams(learningRate = 3.0)), 15000)
val learned = trained.withLights(moving, seed = 11L)
val lovers = VehicleWorld.random(80, still, wiring = VehicleWorld.LOVER, seed = 5L).withLights(moving, seed = 11L)
val canvasA = VehicleCanvas(learned, scale = 1.2, fade = 0.98f)
val canvasB = VehicleCanvas(lovers, scale = 1.2, fade = 0.98f)

animateEvolution(
    Unit,
    totalSteps = 3000,
    seconds = 12.0,
    framesTarget = 240,
    columns = 2,
    cellSide = 300,
    step = {
        learned.step(p)
        lovers.step(p)
        canvasA.record()
        canvasB.record()
    },
    panels = { _, n ->
        [
            "learned " + n + ": " + learned.meanLight().f2 to canvasA.image(),
            "lovers " + n + ": " + lovers.meanLight().f2 to canvasB.image(),
        ]
    },
)
