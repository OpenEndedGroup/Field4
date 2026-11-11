val world = flowLeniaWorld(
    288,
    288,
    [FlowLeniaPresets.wanderers(), FlowLeniaPresets.rings(), FlowLeniaPresets.cell()],
    scale = 3,
)

animateEvolution(
    world,
    totalSteps = 1200,
    seconds = 9.0,
    columns = 2,
    cellSide = 480,
    step = { it.step() },
    panels = { w, n ->
        [
            ("step " + n) to w.speciesImg().resize(480, 480),
            ("total mass " + w.totalMass().f3) to w.massPlot(480, 480),
        ]
    },
)
