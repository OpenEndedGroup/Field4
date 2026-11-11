val world = flowLeniaWorld(96, 96, [FlowLeniaPresets.wanderers(), FlowLeniaPresets.rings(), FlowLeniaPresets.cell()])

animateEvolution(
    world,
    totalSteps = 1200,
    seconds = 9.0,
    columns = 2,
    cellSide = 384,
    step = { it.step() },
    panels = { w, n ->
        [
            ("step " + n) to w.speciesImg().upscaleNearest(4),
            ("total mass %.6f".format(w.totalMass())) to w.massPlot(384, 384),
        ]
    },
)
