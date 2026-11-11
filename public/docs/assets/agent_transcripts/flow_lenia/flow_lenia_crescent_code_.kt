// Noise seed 2 folds into the crescent; grow it to three times the resolution once it has formed.
val world = flowLeniaWorld(64, 64, [FlowLeniaPresets.crescent()], seedRadius = 6.0, randomSeed = 2L).steps(200).scaledCopy(3)
val trail = FloatArray(world.width * world.height)

animateEvolution(
    world,
    totalSteps = 3000,
    seconds = 9.0,
    columns = 2,
    cellSide = 480,
    step = { it.step() },
    panels = { w, n ->
        val b = w.bodies().first()
        for (c in trail.indices) trail[c] = maxOf(0.97f * trail[c], w.matter[c].toFloat())
        [
            ("step %d: mass %.1f at (%.0f, %.0f)".format(n, b.mass, b.x, b.y)) to w.speciesImg().resize(480, 480),
            "the path" to Img(w.width, w.height, 1, trail.copyOf()).colormap("magma", 0f, 1.2f).resize(480, 480),
        ]
    },
)
