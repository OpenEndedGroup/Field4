val world = flowLeniaWorld(288, 192, [FlowLeniaPresets.rings(), FlowLeniaPresets.cell()], positions = [38.0 to 32.0, 58.0 to 32.0], scale = 3)

animateEvolution(
    world,
    totalSteps = 1200,
    seconds = 9.0,
    columns = 2,
    cellSide = 480,
    step = { it.step() },
    panels = { w, n ->
        [
            ("step " + n) to w.speciesImg().resize(480, 320),
            ("hybrid %.0f of %.0f".format(w.mixedMass(), w.totalMass())) to w.weightImg(0).colormap("viridis", 0f, 0.4f).resize(480, 320),
        ]
    },
)
