val lights = [Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0)]
val view = CubeView(size = 560.0)
val colours = [
    CubeReward.SEEK to vec(1.0, 0.75, 0.25, 1.0),
    CubeReward.FLEE to vec(0.4, 0.75, 1.0, 1.0),
    CubeReward.FREEZE to vec(0.75, 0.75, 0.8, 1.0),
    CubeReward.CHARGE to vec(1.0, 0.35, 0.35, 1.0),
]

// Three learners per reward, all from the centre of the cube, trained in parallel.
val learners = colours.associate { (reward, _) ->
    reward to (1..3).map { k -> WeightCubeLearner(lights, reward, seed = 100L + k) }
}
learners.values.flatten().parallelStream().forEach { it.train(2000) }
val drawn = colours.flatMap { (reward, colour) ->
    val group = learners[reward]!!
    val ends = group.map { it.wiring }
    val mean =
        CubeWiring(ends.map { it.same }.average(), ends.map { it.cross }.average(), ends.map { it.drive }.average())
    val trial = group[0].measure(mean, 99999)
    println(reward.toString() + ": mean end " + mean + ", light " + trial.light.f2 + ", speed " + trial.speed.f2)
    group.flatMap { l ->
        [view.path(l.path, vec(colour.x, colour.y, colour.z, 0.45), 1.0), view.dot(l.wiring, colour, 4.0)]
    } + view.label(reward.name.lowercase(), mean, colour, 18.0)
}
val start = view.dot(CubeWiring(0.0, 0.0, 0.0), vec(1.0, 1.0, 1.0, 1.0), 4.0)
lines(view.frame() + drawn + start, w = 560.0, h = 560.0)
