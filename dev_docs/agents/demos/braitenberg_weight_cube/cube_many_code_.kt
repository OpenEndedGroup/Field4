val lights = [Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0)]
val view = CubeView(size = 560.0)
val starts = kotlin.random.Random(11)

// Twelve learners, each starting at a random wiring and each with its own trials. They are
// independent, so they train in parallel.
val learners = (1..12).map { k ->
    val start = CubeWiring(
        starts.nextDouble(-2.5, 2.5),
        starts.nextDouble(-2.5, 2.5),
        starts.nextDouble(-2.5, 2.5),
    )
    WeightCubeLearner(lights, CubeReward.SEEK, start = start, seed = k.toLong())
}
learners.parallelStream().forEach { it.train(2000) }
val drawn = learners.flatMap { l ->
    val end = l.measure(l.wiring, 99999)
    println("" + l.path[0] + " -> " + l.wiring + ", light " + end.light.f2)
    [
        view.path(l.path, vec(0.45, 0.75, 1.0, 0.55), 1.2),
        view.dot(l.path[0], vec(1.0, 1.0, 1.0, 0.6), 3.0),
        view.dot(l.wiring, vec(1.0, 0.6, 0.2, 1.0), 4.5),
    ]
}
lines(view.frame() + drawn, w = 560.0, h = 560.0)
