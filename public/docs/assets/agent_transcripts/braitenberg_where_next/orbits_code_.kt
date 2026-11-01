val lights = [Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0)]
val panels = mutableListOf<Pair<String, Img>>()
val groups = mutableListOf<Pair<String, List<Double>>>()
for (d in [8.0, 12.0, 16.0, 20.0]) {
    val p = VehicleParams(
        neuron = NeuronParams(activation = Activation.BUMP, gain = 4.0, tauMembrane = 1.0, bias = orbitBias(d)),
    )
    val world = VehicleWorld.random(60, lights, wiring = VehicleWorld.AGGRESSOR, seed = 3L)
    val canvas = VehicleCanvas(world, scale = 1.2, fade = 0.99f)
    world.stepTimes(p, 6000)
    val radii = OrbitRadii()
    repeat(2000) {
        world.step(p)
        radii.observe(world)
        canvas.record()
    }
    println(
        "tuned ${d.f2}: median ${radii.median().f2}, middle half ${radii.quantile(0.25).f2} to ${radii.quantile(0.75).f2}",
    )
    panels += (d.f2 + " -> " + radii.median().f2) to canvas.image()
    groups += ("tuned " + d.f2) to radii.distances(every = 40)
}
showImg(montage(panels, columns = 2, cellSide = 300))
display(
    "radii",
    violin(
        *groups.toTypedArray(),
        references = [8.0, 12.0, 16.0, 20.0],
        yRange = 0.0..60.0,
        yTitle = "distance to nearest lamp",
    ),
)
