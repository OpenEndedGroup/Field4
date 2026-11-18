val lights = [Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0)]
val learner = WeightCubeLearner(lights, CubeReward.SEEK, seed = 2L).train(2000)
val view = CubeView(size = 560.0)

// Colour the path by time, blue for the first tries and orange for the last, and drop a faint
// shadow of it onto the floor of the cube so its depth reads.
val path = learner.path
val shadow = view.path(path.map { it.copy(drive = -3.0) }, vec(1.0, 1.0, 1.0, 0.18), 1.0)
val segments =
    (0 until path.size - 1 step 10).map { i ->
        val t = sqrt(i / 2000.0)
        view.path(path.subList(i, min(i + 11, path.size)), vec(0.3 + 0.7 * t, 0.6, 1.0 - 0.7 * t, 0.9))
    }
val marks =
    [0, 50, 250, 500, 2000].flatMap { n ->
        val w = path[n]
        println(n.toString() + " tries: " + w + ", light " + learner.measure(w, 99999).light.f2)
        [view.dot(w, vec(1.0, 1.0, 1.0, 1.0))] + view.label(n.toString(), w, vec(1.0, 1.0, 1.0, 0.9), 18.0)
    }
lines(view.frame() + shadow + segments + marks, w = 560.0, h = 560.0)
